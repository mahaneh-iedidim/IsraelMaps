"""mkmap.py <osm.pkl> <out israel.map>: pass 2 of the map build (pass 1 = osmread.py).

File layout (all integers big-endian unless "varint" = unsigned LEB128, "zz" = zigzag varint):
  header   'ILMP' u32 | version u16 | section count u16 | sections: id u8, offset u32, length u32
  section 1..4  map levels L0..L3   (tile set)
  section 5     search index
  section 6     road graph G (all roads, tiles of 15625 micro-degrees; vertex id on the phone = tile << 15 | local)
  section 7     main-road graph M (motorway..tertiary, chains contracted)
  section 8     meta (utf-8 text)
Tile set: originLat i32, originLon i32, tile i32 (micro-degrees), cols u16, rows u16, quant u8,
          rows x (rowOffset u32, count u16)   then per row: count x (col u16, offset u32, length u32)  (offsets from
          the section start), then the tile blobs.
Map tile: varint nStrings, strings (varint len + utf-8) | varint nFeatures | features:
          type u8, sub u8, flags u8 (1 name, 2 ref, 4 oneway, 8 link, 16 tunnel, 32 bridge), [varint name], [varint ref],
          varint nPoints, then zz coordinates in quant units: first relative to the tile origin, then deltas.
Feature types: 1..10 road class 0..9, 11 rail, 12 waterway, 13 coastline, 20 water, 21 park, 22 forest, 23 building,
          25 industrial, 26 cemetery (areas: one outer ring), 30 place label, 31 POI, 32 house number.
Search: varint nBlocks, then per block: varint keyLen + normalized first key (utf-8), u32 offset; blocks:
          varint n, records: kind u8 (1 place 2 street 3 address 4 POI), cat u8, name, city, house (varint len + utf-8),
          lat i32, lon i32.  Records are sorted by normalized name, then city.
Graph G tile: strings | varint nVertices | vertex: varint dLat, varint dLon (micro-degrees from the tile origin),
          u8 vflags (1 = also an M vertex), [varint mTile, varint mLocal], varint nEdges,
          edge: varint targetTile+1 (0 = this tile), varint targetLocal, varint lengthMetres, varint attr,
          varint name+1 (0 none), varint nGeom, zz deltas (micro-degrees) from the vertex point.
          attr = class | flags << 4: 16 car/bike may not go this way (one-way), 32 roundabout, 64 no car, 128 no foot,
          256 no bike, 512 link.
Graph M tile: strings | varint nVertices | vertex: varint dLat, varint dLon, varint gTile, varint gLocal, varint nEdges,
          edge: varint targetTile+1, varint targetLocal, varint target gTile, varint target gLocal, varint lengthMetres,
          varint attr, varint name+1, varint nGeom, zz deltas (units of 4 micro-degrees)."""
import sys, os, re, pickle, struct, math, time, array, collections
import numpy as np
import shapely

OLAT, OLON = 29000000, 34000000
EXT_LAT, EXT_LON = 4600000, 2200000
LEVELS = [(5000000, 50), (500000, 10), (62500, 2), (8000, 1)]
G_TILE, M_TILE = 15625, 125000

F_ONEWAY, F_BACKWARD, F_LINK, F_ROUNDABOUT, F_NOCAR, F_NOFOOT, F_NOBIKE, F_TUNNEL, F_BRIDGE = 1, 2, 4, 8, 16, 32, 64, 128, 256

def vint(b, v):
    while v >= 0x80:
        b.append((v & 0x7F) | 0x80)
        v >>= 7
    b.append(v)

def zz(b, v):
    vint(b, (v << 1) if v >= 0 else ((-v << 1) - 1))

def vstr(b, s):
    e = s.encode('utf-8')
    vint(b, len(e)); b.extend(e)

def grid(tile):
    return (EXT_LON + tile - 1) // tile, (EXT_LAT + tile - 1) // tile

def tile_of(lat, lon, tile):
    return (lat - OLAT) // tile, (lon - OLON) // tile

FINALS = {'ך': 'כ', 'ם': 'מ', 'ן': 'נ', 'ף': 'פ', 'ץ': 'צ'}
def norm(s):
    out = []
    for ch in s:
        o = ord(ch)
        if 0x591 <= o <= 0x5C7: continue
        ch = FINALS.get(ch, ch)
        if 0x5D0 <= ord(ch) <= 0x5EA or ch.isdigit() or ('a' <= ch.lower() <= 'z'):
            out.append(ch.lower())
        elif out and out[-1] != ' ':
            out.append(' ')
    # spelling with or without the extra vav / yod (זנגוויל = זנגויל)
    return re.sub('יי+', 'י', re.sub('וו+', 'ו', ''.join(out).strip()))

def readable(s):
    """'' for names the phone fonts cannot show (Arabic-only and the like): Hebrew, Latin and digits are kept"""
    if not s: return ''
    s = s.strip()
    for ch in s:
        o = ord(ch)
        if 0x5D0 <= o <= 0x5EA or ('a' <= ch.lower() <= 'z'): break
    else:
        return s if s.isdigit() else ''
    return ''.join(ch for ch in s if ord(ch) < 0x600 or 0x2010 <= ord(ch) <= 0x2027)

def clean_names(D):
    for k in ('r_name', 'r_ref', 'l_name', 'a_name'):
        D[k] = [readable(s) for s in D[k]]
    places = [(sub, readable(n), lat, lon, pop) for sub, n, lat, lon, pop in D['places'] if readable(n)]
    # one point per settlement: of the same name and rank within 25 km keep the most populated
    places.sort(key=lambda p: (p[0], -p[4]))
    kept = []
    byname = collections.defaultdict(list)
    for p in places:
        if p[0] <= 2 and any(abs(q[2] - p[2]) < 225000 and abs(q[3] - p[3]) < 265000 for q in byname[(p[0], p[1])]):
            continue
        byname[(p[0], p[1])].append(p)
        kept.append(p)
    print('places: %d, %d duplicates dropped' % (len(kept), len(places) - len(kept)))
    D['places'] = kept
    D['pois'] = [(cat, readable(n), lat, lon, readable(st), hn, readable(city)) for cat, n, lat, lon, st, hn, city in D['pois']]
    D['addrs'] = [(readable(st), hn, readable(city), lat, lon) for st, hn, city, lat, lon in D['addrs'] if readable(st) and hn]

def add_sea(D):
    """the Mediterranean as a water area: the merged coastline closed along the grid's west edge"""
    from shapely.ops import linemerge
    ls, la, lo = D['l_start'], D['l_lat'], D['l_lon']
    lines = []
    for i in range(len(D['l_kind'])):
        if D['l_kind'][i] != 13: continue
        s, e = ls[i], ls[i + 1]
        if e - s >= 2: lines.append(shapely.LineString([(lo[k] / 1e6, la[k] / 1e6) for k in range(s, e)]))
    m = linemerge(lines)
    parts = list(m.geoms) if hasattr(m, 'geoms') else [m]
    parts = [p for p in parts if p.bounds[0] < 34.8 and p.bounds[3] - p.bounds[1] > 0.5]
    if not parts:
        print('sea: no coastline'); return
    coast = max(parts, key=lambda p: p.length)
    xy = list(coast.coords)
    print('sea: coastline %d points, from %.3f,%.3f to %.3f,%.3f' % (len(xy), xy[0][1], xy[0][0], xy[-1][1], xy[-1][0]))
    if xy[0][1] < xy[-1][1]: xy = xy[::-1]   # north to south
    # beyond the extract: the Lebanese coast up to the grid's north edge and the Sinai coast to its west edge
    north = [(35.37, 33.60), (35.24, 33.36), (35.19, 33.25)] if xy[0][1] > 33.0 else []
    south = [(34.10, 31.24), (33.98, 31.19)] if xy[-1][1] < 31.5 else []
    pts = north + xy + south
    ring = pts + [(33.98, pts[-1][1]), (33.98, pts[0][1])]
    poly = shapely.Polygon(ring)
    if not poly.is_valid: poly = poly.buffer(0)
    if poly.geom_type == 'MultiPolygon': poly = max(poly.geoms, key=lambda q: q.area)
    xy = np.asarray(poly.exterior.coords)
    D['a_kind'].append(20); D['a_name'].append('הים התיכון')
    D['a_lat'].extend(int(round(v * 1e6)) for v in xy[:, 1]); D['a_lon'].extend(int(round(v * 1e6)) for v in xy[:, 0])
    D['a_start'].append(len(D['a_lat']))
    print('sea: %d points, %.2f square degrees' % (len(xy), poly.area))

class Strings:
    def __init__(self):
        self.idx = {}; self.lst = []
    def get(self, s):
        if s not in self.idx:
            self.idx[s] = len(self.lst); self.lst.append(s)
        return self.idx[s]
    def write(self, b):
        vint(b, len(self.lst))
        for s in self.lst: vstr(b, s)

class Tile:
    __slots__ = ('strings', 'feats', 'nfeat')
    def __init__(self):
        self.strings = Strings(); self.feats = []

def tileset_bytes(tiles, tile, quant):
    """tiles: dict (row, col) -> bytes"""
    cols, rows = grid(tile)
    head = bytearray(struct.pack('>iiiHHB', OLAT, OLON, tile, cols, rows, quant))
    rowtab_at = len(head)
    head.extend(b'\0' * (rows * 6))
    byrow = collections.defaultdict(list)
    for (r, c), blob in tiles.items():
        if 0 <= r < rows and 0 <= c < cols: byrow[r].append((c, blob))
    rowentries = bytearray()
    rowmeta = []
    for r in range(rows):
        ents = sorted(byrow.get(r, []))
        rowmeta.append((len(rowentries), len(ents)))
        rowentries.extend(b'\0' * (len(ents) * 10))
    base_entries = len(head)
    base_blobs = base_entries + len(rowentries)
    blobs = bytearray()
    for r in range(rows):
        off, n = rowmeta[r]
        struct.pack_into('>IH', head, rowtab_at + r * 6, base_entries + off, n)
        for i, (c, blob) in enumerate(sorted(byrow.get(r, []))):
            struct.pack_into('>HII', rowentries, off + i * 10, c, base_blobs + len(blobs), len(blob))
            blobs.extend(blob)
    return bytes(head) + bytes(rowentries) + bytes(blobs)

# ---------------------------------------------------------------- map levels
def split_by_tile(lat, lon, tile):
    """cuts a polyline where it changes tile; the crossing segment goes to both tiles"""
    out = collections.defaultdict(list)
    n = len(lat)
    if n == 0: return out
    tr = (lat - OLAT) // tile; tc = (lon - OLON) // tile
    start = 0
    for i in range(1, n):
        if tr[i] != tr[i - 1] or tc[i] != tc[i - 1]:
            out[(int(tr[i - 1]), int(tc[i - 1]))].append((start, i + 1))
            start = i - 1
    out[(int(tr[n - 1]), int(tc[n - 1]))].append((start, n))
    return out

def encode_feature(t, ftype, sub, flags, name, ref, la, lo, tile, quant, r, c):
    b = bytearray()
    f = flags & ~3
    if name: f |= 1
    if ref: f |= 2
    b.append(ftype); b.append(sub); b.append(f)
    if name: vint(b, t.strings.get(name))
    if ref: vint(b, t.strings.get(ref))
    bl, bo = OLAT + r * tile, OLON + c * tile
    qa = [(int(v) - bl) // quant for v in la]
    qo = [(int(v) - bo) // quant for v in lo]
    pts = []
    pa = po = None
    for a, o in zip(qa, qo):
        if a == pa and o == po: continue
        pts.append((a, o)); pa, po = a, o
    if len(pts) < (1 if ftype >= 30 else 2): return
    vint(b, len(pts))
    pa = po = 0
    for a, o in pts:
        zz(b, a - pa); zz(b, o - po); pa, po = a, o
    t.feats.append(bytes(b))

def simplify_lines(lat_list, lon_list, tol):
    """lists of int arrays -> simplified lists (degrees tolerance)"""
    if tol <= 0: return lat_list, lon_list
    geoms = [shapely.linestrings(np.column_stack((lo / 1e6, la / 1e6))) for la, lo in zip(lat_list, lon_list)]
    s = shapely.simplify(np.array(geoms, dtype=object), tol, preserve_topology=False)
    ol, oo = [], []
    for g in s:
        xy = shapely.get_coordinates(g)
        oo.append(np.round(xy[:, 0] * 1e6).astype(np.int64)); ol.append(np.round(xy[:, 1] * 1e6).astype(np.int64))
    return ol, oo

def build_levels(D):
    t0 = time.time()
    r_start = np.frombuffer(D['r_start'], dtype=np.uint32).astype(np.int64)
    r_lat = np.frombuffer(D['r_lat'], dtype=np.int32).astype(np.int64)
    r_lon = np.frombuffer(D['r_lon'], dtype=np.int32).astype(np.int64)
    r_cls = list(D['r_cls']); r_flags = list(D['r_flags'])
    l_start = np.frombuffer(D['l_start'], dtype=np.uint32).astype(np.int64)
    l_lat = np.frombuffer(D['l_lat'], dtype=np.int32).astype(np.int64)
    l_lon = np.frombuffer(D['l_lon'], dtype=np.int32).astype(np.int64)
    a_start = np.frombuffer(D['a_start'], dtype=np.uint32).astype(np.int64)
    a_lat = np.frombuffer(D['a_lat'], dtype=np.int32).astype(np.int64)
    a_lon = np.frombuffer(D['a_lon'], dtype=np.int32).astype(np.int64)
    a_kind = list(D['a_kind'])
    # area sizes (deg^2, shoelace) for the per-level thresholds
    n_a = len(a_kind)
    a_size = np.zeros(n_a)
    for i in range(n_a):
        s, e = a_start[i], a_start[i + 1]
        if a_kind[i] == 23: continue
        x = a_lon[s:e] / 1e6; y = a_lat[s:e] / 1e6
        a_size[i] = abs(np.dot(x, np.roll(y, -1)) - np.dot(y, np.roll(x, -1))) / 2
    KM2 = 1.0 / (111.0 * 95.0)
    ROAD_MAX = [2, 4, 7, 9]
    TOL = [0.0015, 0.00025, 0.00003, 0.0]
    AREA_MIN = {20: [3 * KM2, 0.05 * KM2, 0.0005 * KM2, 0], 21: [None, 0.5 * KM2, 0.001 * KM2, 0], 22: [None, 0.5 * KM2, 0.001 * KM2, 0],
                25: [None, None, 0.005 * KM2, 0], 26: [None, None, 0.001 * KM2, 0], 23: [None, None, None, 0]}
    PLACE_MAX = [0, 2, 4, 4]
    POI_L2 = {2, 7, 20}
    out = []
    for L, (tile, quant) in enumerate(LEVELS):
        tiles = collections.defaultdict(Tile)
        # areas first (draw order: areas, lines, roads, labels: the app sorts by type anyway)
        idx = [i for i in range(n_a) if AREA_MIN[a_kind[i]][L] is not None and a_size[i] >= AREA_MIN[a_kind[i]][L]]
        if TOL[L] > 0 and idx:
            def mkpoly(i):
                xy = np.column_stack((a_lon[a_start[i]:a_start[i + 1]] / 1e6, a_lat[a_start[i]:a_start[i + 1]] / 1e6))
                if len(xy) < 4: return None
                try: return shapely.Polygon(xy)
                except Exception: return None
            polys = np.array([mkpoly(i) for i in idx], dtype=object)
            polys = shapely.simplify(polys, TOL[L], preserve_topology=True)
        for j, i in enumerate(idx):
            kind = a_kind[i]
            if TOL[L] > 0:
                g = polys[j]
                if g is None or g.is_empty: continue
                if g.geom_type == 'MultiPolygon': g = max(g.geoms, key=lambda q: q.area)
                if g.geom_type != 'Polygon': continue
                xy = np.asarray(g.exterior.coords)
                lo = np.round(xy[:, 0] * 1e6).astype(np.int64); la = np.round(xy[:, 1] * 1e6).astype(np.int64)
            else:
                la = a_lat[a_start[i]:a_start[i + 1]]; lo = a_lon[a_start[i]:a_start[i + 1]]
            if len(la) < 3: continue
            r0, c0 = tile_of(int(la.min()), int(lo.min()), tile); r1, c1 = tile_of(int(la.max()), int(lo.max()), tile)
            name = D['a_name'][i]
            if r0 == r1 and c0 == c1:
                encode_feature(tiles[(r0, c0)], kind, 0, 0, name, '', la, lo, tile, quant, r0, c0)
            else:
                poly = shapely.Polygon(np.column_stack((lo / 1e6, la / 1e6)))
                if not poly.is_valid: poly = poly.buffer(0)
                rr, cc = np.meshgrid(np.arange(r0, r1 + 1), np.arange(c0, c1 + 1), indexing='ij')
                rr = rr.ravel(); cc = cc.ravel()
                d = tile / 1e6
                bx = (OLON + cc * tile) / 1e6; by = (OLAT + rr * tile) / 1e6
                if len(rr) > 16:
                    # big areas (the sea, forests): whole cells inside become a square, only edge cells are clipped
                    shapely.prepare(poly)
                    boxes = shapely.box(bx, by, bx + d, by + d)
                    inside = shapely.contains_properly(poly, boxes)
                    touch = shapely.intersects(poly, boxes)
                else:
                    inside = np.zeros(len(rr), dtype=bool); touch = np.ones(len(rr), dtype=bool)
                for k in range(len(rr)):
                    r, c = int(rr[k]), int(cc[k])
                    x0, y0 = bx[k], by[k]
                    if inside[k]:
                        e = d * 0.01
                        sq_la = np.round(np.array([y0 - e, y0 - e, y0 + d + e, y0 + d + e, y0 - e]) * 1e6).astype(np.int64)
                        sq_lo = np.round(np.array([x0 - e, x0 + d + e, x0 + d + e, x0 - e, x0 - e]) * 1e6).astype(np.int64)
                        encode_feature(tiles[(r, c)], kind, 0, 0, '', '', sq_la, sq_lo, tile, quant, r, c)
                        continue
                    if not touch[k]: continue
                    if True:
                        part = shapely.clip_by_rect(poly, x0 - d * 0.01, y0 - d * 0.01, x0 + d * 1.01, y0 + d * 1.01)
                        parts = [] if part.is_empty else (list(part.geoms) if hasattr(part, 'geoms') else [part])
                        for p in parts:
                            if p.geom_type != 'Polygon': continue
                            xy = np.asarray(p.exterior.coords)
                            encode_feature(tiles[(r, c)], kind, 0, 0, name if p is parts[0] else '', '',
                                           np.round(xy[:, 1] * 1e6).astype(np.int64), np.round(xy[:, 0] * 1e6).astype(np.int64), tile, quant, r, c)
        # lines
        li = [i for i in range(len(D['l_kind'])) if (D['l_kind'][i] == 13) or (D['l_kind'][i] == 11 and L >= 1)
              or (D['l_kind'][i] == 12 and ((D['l_sub'][i] == 0 and L >= 1) or L >= 2))]
        las = [l_lat[l_start[i]:l_start[i + 1]] for i in li]; los = [l_lon[l_start[i]:l_start[i + 1]] for i in li]
        las, los = simplify_lines(las, los, TOL[L])
        for k, i in enumerate(li):
            la, lo = las[k], los[k]
            for (r, c), pieces in split_by_tile(la, lo, tile).items():
                for s, e in pieces:
                    encode_feature(tiles[(r, c)], D['l_kind'][i], D['l_sub'][i], 0, D['l_name'][i] if L >= 2 else '', '', la[s:e], lo[s:e], tile, quant, r, c)
        # roads
        ri = [i for i in range(len(r_cls)) if r_cls[i] <= ROAD_MAX[L]]
        las = [r_lat[r_start[i]:r_start[i + 1]] for i in ri]; los = [r_lon[r_start[i]:r_start[i + 1]] for i in ri]
        las, los = simplify_lines(las, los, TOL[L])
        for k, i in enumerate(ri):
            la, lo = las[k], los[k]
            fl = r_flags[i]
            f = (4 if fl & F_ONEWAY else 0) | (8 if fl & F_LINK else 0) | (16 if fl & F_TUNNEL else 0) | (32 if fl & F_BRIDGE else 0)
            sub = 1 if fl & F_BACKWARD else 0
            name = D['r_name'][i] if L >= 2 else ''
            ref = D['r_ref'][i] if r_cls[i] <= 4 else ''
            for (r, c), pieces in split_by_tile(la, lo, tile).items():
                for s, e in pieces:
                    encode_feature(tiles[(r, c)], r_cls[i] + 1, sub, f, name, ref, la[s:e], lo[s:e], tile, quant, r, c)
        # labels: places, POIs, house numbers
        for sub, name, lat, lon, pop in D['places']:
            ok = sub <= PLACE_MAX[L] or (L == 0 and sub == 1 and pop >= 40000)
            if not ok: continue
            r, c = tile_of(lat, lon, tile)
            encode_feature(tiles[(r, c)], 30, sub, 0, name, '', [lat], [lon], tile, quant, r, c)
        if L >= 2:
            for cat, name, lat, lon, st, hn, city in D['pois']:
                if L == 2 and cat not in POI_L2: continue
                r, c = tile_of(lat, lon, tile)
                encode_feature(tiles[(r, c)], 31, cat, 0, name, '', [lat], [lon], tile, quant, r, c)
        if L == 3:
            seen = set()
            for st, hn, city, lat, lon in D['addrs']:
                key = (lat // 20, lon // 20, hn)
                if key in seen: continue
                seen.add(key)
                r, c = tile_of(lat, lon, tile)
                encode_feature(tiles[(r, c)], 32, 0, 0, hn[:8], '', [lat], [lon], tile, quant, r, c)
        blobs = {}
        for key, t in tiles.items():
            if not t.feats: continue
            b = bytearray(); t.strings.write(b); vint(b, len(t.feats))
            for f in t.feats: b.extend(f)
            blobs[key] = bytes(b)
        sec = tileset_bytes(blobs, tile, quant)
        print('level %d: %d tiles, %.1f MB, largest tile %d KB (%.0fs)' % (L, len(blobs), len(sec) / 1e6,
              max(len(v) for v in blobs.values()) // 1024, time.time() - t0))
        out.append(sec)
    return out

# ---------------------------------------------------------------- settlements (for street / address cities)
class Settle:
    """the city of a point: the municipality whose boundary contains it (OSM admin_level 8, from tools/bounds.py),
    else the nearest settlement (villages of regional councils have no boundary of their own)"""
    def __init__(self, places, bounds=()):
        import shapely.wkb
        self.polys = [shapely.wkb.loads(w) for lv, nm, w in bounds if lv == 8]
        self.pnames = [nm for lv, nm, w in bounds if lv == 8]
        self.parea = [p.area for p in self.polys]
        self.tree = shapely.STRtree(self.polys) if self.polys else None
        for p in self.polys: shapely.prepare(p)
        self.p = [(name, lat, lon, sub) for sub, name, lat, lon, pop in places if sub <= 2]
        self.cell = collections.defaultdict(list)
        for i, (name, lat, lon, sub) in enumerate(self.p):
            self.cell[(lat // 50000, lon // 50000)].append(i)
    def near(self, lat, lon, maxd=8000):
        if self.tree is not None:
            pt = shapely.Point(lon / 1e6, lat / 1e6)
            hit = None
            for i in self.tree.query(pt):
                if self.polys[i].contains(pt) and (hit is None or self.parea[i] < self.parea[hit]): hit = i
            if hit is not None: return self.pnames[hit]
        best, bd = '', maxd * maxd
        ca, co = lat // 50000, lon // 50000
        for da in (-1, 0, 1):
            for do in (-1, 0, 1):
                for i in self.cell.get((ca + da, co + do), ()):
                    name, pl, po, sub = self.p[i]
                    dy = (pl - lat) * 0.111; dx = (po - lon) * 0.095
                    d = dx * dx + dy * dy
                    w = d * (0.6 if sub == 0 else 1.0)
                    if w < bd: bd, best = w, name
        return best

# ---------------------------------------------------------------- search index
def build_search(D, settle):
    recs = []
    for sub, name, lat, lon, pop in D['places']:
        city = settle.near(lat, lon, 6000) if sub >= 3 else ''
        if city == name: city = ''
        recs.append((1, sub, name, city, '', lat, lon))
    r_start = D['r_start']; r_lat = D['r_lat']; r_lon = D['r_lon']
    streets = {}
    for i in range(len(D['r_cls'])):
        nm = D['r_name'][i]
        if not nm or D['r_cls'][i] > 7: continue
        s, e = r_start[i], r_start[i + 1]
        m = (s + e) // 2
        lat, lon = r_lat[m], r_lon[m]
        city = settle.near(lat, lon)
        k = (nm, city)
        ln = e - s
        if k not in streets or streets[k][0] < ln: streets[k] = (ln, lat, lon, D['r_cls'][i])
    for (nm, city), (ln, lat, lon, cls) in streets.items():
        recs.append((2, cls, nm, city, '', lat, lon))
    seen = set()
    for st, hn, city, lat, lon in D['addrs']:
        city = city or settle.near(lat, lon)
        k = (st, hn, city)
        if k in seen: continue
        seen.add(k)
        recs.append((3, 0, st, city, hn[:12], lat, lon))
    for cat, name, lat, lon, st, hn, city in D['pois']:
        if not name: continue
        recs.append((4, cat, name, city or settle.near(lat, lon), '', lat, lon))
    recs.sort(key=lambda r: (norm(r[2]), r[0], norm(r[3]), len(r[4]), r[4]))
    blocks = []
    cur = bytearray(); n = 0; first = None
    allb = bytearray()
    index = []
    def flush():
        nonlocal cur, n, first
        if n:
            b = bytearray(); vint(b, n); b.extend(cur)
            index.append((first, len(allb)))
            allb.extend(b)
        cur = bytearray(); n = 0; first = None
    for kind, cat, name, city, house, lat, lon in recs:
        rb = bytearray([kind, cat]); vstr(rb, name); vstr(rb, city); vstr(rb, house); rb.extend(struct.pack('>ii', lat, lon))
        if n and len(cur) + len(rb) > 3000: flush()
        if first is None: first = norm(name)
        cur.extend(rb); n += 1
    flush()
    head = bytearray(); vint(head, len(index))
    # offsets are relative to the start of the blocks area, which follows the index
    for key, off in index:
        vstr(head, key); head.extend(struct.pack('>I', off))
    sec = struct.pack('>I', len(head)) + bytes(head) + bytes(allb)
    print('search: %d records, %d blocks, %.1f MB (index %d KB)' % (len(recs), len(index), len(sec) / 1e6, len(head) // 1024))
    return sec

# ---------------------------------------------------------------- road graph
def build_graphs(D):
    t0 = time.time()
    r_start = np.frombuffer(D['r_start'], dtype=np.uint32).astype(np.int64)
    nodes = np.frombuffer(D['r_nodes'], dtype=np.int64)
    lat = np.frombuffer(D['r_lat'], dtype=np.int32).astype(np.int64)
    lon = np.frombuffer(D['r_lon'], dtype=np.int32).astype(np.int64)
    cls = list(D['r_cls']); flags = list(D['r_flags'])
    uniq, inv, cnt = np.unique(nodes, return_inverse=True, return_counts=True)
    isv = cnt >= 2
    isv[inv[r_start[:-1]]] = True
    isv[inv[r_start[1:] - 1]] = True
    vid_of_uniq = np.full(len(uniq), -1, dtype=np.int64)
    vids = np.nonzero(isv)[0]
    vid_of_uniq[vids] = np.arange(len(vids))
    nv = len(vids)
    vlat = np.zeros(nv, dtype=np.int64); vlon = np.zeros(nv, dtype=np.int64)
    pos_v = vid_of_uniq[inv]
    m = pos_v >= 0
    vlat[pos_v[m]] = lat[m]; vlon[pos_v[m]] = lon[m]
    print('graph: %d vertices (%.0fs)' % (nv, time.time() - t0))
    # edges: (a, b, length, cls, flags, name, geometry lat/lon arrays excluding endpoints)
    E_a = array.array('i'); E_b = array.array('i'); E_len = array.array('i'); E_cls = array.array('b'); E_fl = array.array('H')
    E_name = []; E_gs = array.array('I', [0]); G_lat = array.array('i'); G_lon = array.array('i')
    coslat = math.cos(math.radians(31.5))
    for w in range(len(cls)):
        s, e = int(r_start[w]), int(r_start[w + 1])
        pv = pos_v[s:e]
        idx = np.nonzero(pv >= 0)[0]
        la = lat[s:e]; lo = lon[s:e]
        dy = np.diff(la) * 0.11132; dx = np.diff(lo) * 0.11132 * coslat
        seg = np.sqrt(dx * dx + dy * dy)
        cum = np.concatenate(([0.0], np.cumsum(seg)))
        for k in range(len(idx) - 1):
            i0, i1 = idx[k], idx[k + 1]
            a, b = int(pv[i0]), int(pv[i1])
            if a == b and i1 - i0 < 2: continue
            E_a.append(a); E_b.append(b); E_len.append(max(1, int(round(cum[i1] - cum[i0]))))
            E_cls.append(cls[w]); E_fl.append(flags[w]); E_name.append(D['r_name'][w] or D['r_ref'][w])
            G_lat.extend(int(v) for v in la[i0 + 1:i1]); G_lon.extend(int(v) for v in lo[i0 + 1:i1]); E_gs.append(len(G_lat))
    ne = len(E_a)
    print('graph: %d edges (%.0fs)' % (ne, time.time() - t0))
    E_gs_np = np.frombuffer(E_gs, dtype=np.uint32).astype(np.int64)
    G_lat_np = np.frombuffer(G_lat, dtype=np.int32).astype(np.int64); G_lon_np = np.frombuffer(G_lon, dtype=np.int32).astype(np.int64)
    # vertex tiles and local ids (G)
    gcols, grows = grid(G_TILE)
    vr = (vlat - OLAT) // G_TILE; vc = (vlon - OLON) // G_TILE
    vt = vr * gcols + vc
    order = np.lexsort((vlon, vlat, vt))
    vlocal = np.zeros(nv, dtype=np.int64)
    tstart = {}
    prev = -1; k = 0
    for v in order:
        t = int(vt[v])
        if t != prev: prev = t; k = 0
        vlocal[v] = k; k += 1
    maxlocal = int(vlocal.max()) + 1
    # adjacency (both directions)
    adj = [[] for _ in range(nv)]
    for e in range(ne):
        adj[E_a[e]].append((e, 0)); adj[E_b[e]].append((e, 1))
    # ---- M graph: car edges of class <= 4, chains contracted
    major = [e for e in range(ne) if E_cls[e] <= 4 and not (E_fl[e] & F_NOCAR)]
    madj = collections.defaultdict(list)
    for e in major:
        madj[E_a[e]].append((e, 0)); madj[E_b[e]].append((e, 1))
    def dirkey(e, rev):
        fl = E_fl[e]
        if not fl & F_ONEWAY: return 0
        fwd = not (fl & F_BACKWARD)
        return 1 if fwd != bool(rev) else 2
    keep = set()
    for v, lst in madj.items():
        if len(lst) != 2:
            keep.add(v); continue
        (e1, r1), (e2, r2) = lst
        # a chain passes through v if v is the end of e1 and the start of e2 (direction-wise)
        if E_name[e1] != E_name[e2] or E_cls[e1] != E_cls[e2]: keep.add(v); continue
        d1 = dirkey(e1, 1 - r1); d2 = dirkey(e2, r2)
        if d1 != d2: keep.add(v)
    mverts = {}
    medges = []   # (va, vb, length, cls, attrflags, name, list of (lat,lon) incl. endpoints)
    used = set()
    def edge_pts(e, rev):
        s, t = E_gs_np[e], E_gs_np[e + 1]
        pts = [(int(vlat[E_a[e]]), int(vlon[E_a[e]]))] + list(zip(G_lat_np[s:t].tolist(), G_lon_np[s:t].tolist())) + [(int(vlat[E_b[e]]), int(vlon[E_b[e]]))]
        return pts[::-1] if rev else pts
    for v0 in list(madj.keys()):
        if v0 not in keep: continue
        for e, rev in madj[v0]:
            if (e, rev) in used: continue
            # walk along the chain from v0 through edge e
            pts = edge_pts(e, rev)
            length = E_len[e]; c = E_cls[e]; dk = dirkey(e, rev); name = E_name[e]; rnd = E_fl[e] & F_ROUNDABOUT
            used.add((e, rev)); used.add((e, 1 - rev))
            cur = E_b[e] if rev == 0 else E_a[e]
            guard = 0
            while cur not in keep and guard < 100000:
                guard += 1
                nxt = [(e2, r2) for e2, r2 in madj[cur] if (e2, r2) not in used]
                if not nxt: break
                e2, r2 = nxt[0]
                used.add((e2, r2)); used.add((e2, 1 - r2))
                p2 = edge_pts(e2, r2)
                pts.extend(p2[1:]); length += E_len[e2]
                cur = E_b[e2] if r2 == 0 else E_a[e2]
            if cur not in keep: keep.add(cur)
            medges.append((v0, cur, length, c, dk, rnd, name, pts))
    for a, b, *_ in medges:
        mverts.setdefault(a, None); mverts.setdefault(b, None)
    mcols, mrows = grid(M_TILE)
    mv_list = sorted(mverts.keys(), key=lambda v: (((vlat[v] - OLAT) // M_TILE) * mcols + (vlon[v] - OLON) // M_TILE, vlat[v], vlon[v]))
    mtile = {}; mlocal = {}
    prev = -1; k = 0
    for v in mv_list:
        t = int(((vlat[v] - OLAT) // M_TILE) * mcols + (vlon[v] - OLON) // M_TILE)
        if t != prev: prev = t; k = 0
        mtile[v] = t; mlocal[v] = k; k += 1
    print('graph M: %d vertices, %d edges (%.0fs)' % (len(mv_list), len(medges), time.time() - t0))
    # ---- encode G tiles
    def attr_of(e, rev):
        fl = E_fl[e]
        a = E_cls[e]
        oneway_block = False
        if fl & F_ONEWAY:
            fwd_ok = not (fl & F_BACKWARD)
            oneway_block = (not fwd_ok) if rev == 0 else fwd_ok
        a |= (16 if oneway_block else 0) | (32 if fl & F_ROUNDABOUT else 0) | (64 if fl & F_NOCAR else 0) | \
             (128 if fl & F_NOFOOT else 0) | (256 if fl & F_NOBIKE else 0) | (512 if fl & F_LINK else 0)
        return a
    gtiles = {}
    by_tile = collections.defaultdict(list)
    for v in order: by_tile[int(vt[v])].append(int(v))
    for t, vs in by_tile.items():
        r, c = divmod(t, gcols)
        S = Strings(); body = bytearray()
        bl, bo = OLAT + r * G_TILE, OLON + c * G_TILE
        for v in vs:
            vint(body, int(vlat[v] - bl)); vint(body, int(vlon[v] - bo))
            if v in mtile:
                body.append(1); vint(body, mtile[v]); vint(body, mlocal[v])
            else:
                body.append(0)
            lst = adj[v]
            vint(body, len(lst))
            for e, rev in lst:
                tgt = E_b[e] if rev == 0 else E_a[e]
                tt = int(vt[tgt])
                vint(body, 0 if tt == t else tt + 1); vint(body, int(vlocal[tgt]))
                vint(body, E_len[e]); vint(body, attr_of(e, rev))
                nm = E_name[e]
                vint(body, S.get(nm) + 1 if nm else 0)
                s0, s1 = E_gs_np[e], E_gs_np[e + 1]
                gla = G_lat_np[s0:s1].tolist(); glo = G_lon_np[s0:s1].tolist()
                if rev: gla.reverse(); glo.reverse()
                vint(body, len(gla))
                pa, po = int(vlat[v]), int(vlon[v])
                for a, o in zip(gla, glo):
                    zz(body, a - pa); zz(body, o - po); pa, po = a, o
        b = bytearray(); S.write(b); vint(b, len(vs)); b.extend(body)
        gtiles[(r, c)] = bytes(b)
    gsec = tileset_bytes(gtiles, G_TILE, 1)
    print('graph G: %d tiles, %.1f MB, largest %d KB, max vertices/tile %d (%.0fs)' % (len(gtiles), len(gsec) / 1e6,
          max(len(x) for x in gtiles.values()) // 1024, maxlocal, time.time() - t0))
    # ---- encode M tiles
    mby = collections.defaultdict(list)
    for v in mv_list: mby[mtile[v]].append(v)
    mout = collections.defaultdict(list)
    for a, b, length, c, dk, rnd, name, pts in medges:
        base = c | (32 if rnd else 0)
        mout[a].append((b, length, base | (16 if dk == 2 else 0), name, pts))
        mout[b].append((a, length, base | (16 if dk == 1 else 0), name, pts[::-1]))
    mtiles = {}
    for t, vs in mby.items():
        r, c = divmod(t, mcols)
        S = Strings(); body = bytearray()
        bl, bo = OLAT + r * M_TILE, OLON + c * M_TILE
        for v in vs:
            vint(body, int(vlat[v] - bl)); vint(body, int(vlon[v] - bo))
            vint(body, int(vt[v])); vint(body, int(vlocal[v]))
            lst = mout[v]
            vint(body, len(lst))
            for tgt, length, at, name, pts in lst:
                vint(body, 0 if mtile[tgt] == t else mtile[tgt] + 1); vint(body, mlocal[tgt])
                vint(body, int(vt[tgt])); vint(body, int(vlocal[tgt]))
                vint(body, length); vint(body, at); vint(body, S.get(name) + 1 if name else 0)
                inner = pts[1:-1]
                q = [(p[0] // 4, p[1] // 4) for p in inner]
                vint(body, len(q))
                pa, po = int(vlat[v]) // 4, int(vlon[v]) // 4
                for a, o in q:
                    zz(body, a - pa); zz(body, o - po); pa, po = a, o
        b = bytearray(); S.write(b); vint(b, len(vs)); b.extend(body)
        mtiles[(r, c)] = bytes(b)
    msec = tileset_bytes(mtiles, M_TILE, 4)
    print('graph M: %d tiles, %.1f MB, largest %d KB (%.0fs)' % (len(mtiles), len(msec) / 1e6, max(len(x) for x in mtiles.values()) // 1024, time.time() - t0))
    return gsec, msec

def main():
    src, out = sys.argv[1:3]
    only = sys.argv[3] if len(sys.argv) > 3 else ''
    t0 = time.time()
    with open(src, 'rb') as f:
        D = pickle.load(f)
    print('loaded %.0fs' % (time.time() - t0))
    clean_names(D)
    add_sea(D)
    bp = os.path.join(os.path.dirname(os.path.abspath(src)), 'bounds.pkl')
    settle = Settle(D['places'], pickle.load(open(bp, 'rb')) if os.path.exists(bp) else ())
    print('municipal boundaries', len(settle.polys))
    secs = {}
    wd = os.path.dirname(os.path.abspath(out))
    def cached(ids, fn):
        paths = [os.path.join(wd, 'sec%d.bin' % i) for i in ids]
        if all(os.path.exists(p) for p in paths) and not any(str(i) in only for i in ids):
            for i, p in zip(ids, paths): secs[i] = open(p, 'rb').read()
            print('cached sections', ids)
            return
        res = fn()
        for i, p, s in zip(ids, paths, res):
            secs[i] = s; open(p, 'wb').write(s)
    cached([1, 2, 3, 4], lambda: build_levels(D))
    cached([5], lambda: [build_search(D, settle)])
    cached([6, 7], lambda: build_graphs(D))
    meta = 'Israel map from OpenStreetMap (ODbL). Built %s from %s' % (time.strftime('%Y-%m-%d'), os.path.basename(src))
    secs[8] = meta.encode('utf-8')
    ids = sorted(secs)
    head = 8 + len(ids) * 9
    b = bytearray(b'ILMP' + struct.pack('>HH', 1, len(ids)))
    off = head
    for i in ids:
        b.extend(struct.pack('>BII', i, off, len(secs[i]))); off += len(secs[i])
    with open(out, 'wb') as f:
        f.write(b)
        for i in ids: f.write(secs[i])
    print('wrote %s: %.1f MB (%.0fs)' % (out, os.path.getsize(out) / 1e6, time.time() - t0))

if __name__ == '__main__':
    main()
