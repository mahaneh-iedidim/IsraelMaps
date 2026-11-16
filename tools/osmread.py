"""osmread.py <pbf> <out.pkl>: pass 1 of the map build. Reads the OSM extract once (pyosmium, node locations
cached) and keeps everything the app needs in compact arrays:
  roads      highway ways with their node ids and coordinates (micro-degrees), class, name, ref, flags
  lines      railways, waterways, coastline
  areas      water / park / forest / industrial / building outer rings (closed ways and multipolygons)
  points     places, POIs, addresses (house numbers)
Buildings are by far the largest set, so their rings are kept as flat int arrays."""
import sys, pickle, array, time
import osmium

def micro(v):
    return int(round(v * 1000000))

# road class: 0 motorway .. 9 path; links keep the class of their road
ROAD_CLASS = {
    'motorway': 0, 'motorway_link': 0, 'trunk': 1, 'trunk_link': 1, 'primary': 2, 'primary_link': 2,
    'secondary': 3, 'secondary_link': 3, 'tertiary': 4, 'tertiary_link': 4,
    'residential': 5, 'unclassified': 5, 'road': 5, 'living_street': 6, 'pedestrian': 6,
    'service': 7, 'track': 8, 'footway': 9, 'path': 9, 'cycleway': 9, 'steps': 9, 'bridleway': 9,
}
# road flags
F_ONEWAY, F_BACKWARD, F_LINK, F_ROUNDABOUT, F_NOCAR, F_NOFOOT, F_NOBIKE, F_TUNNEL, F_BRIDGE = 1, 2, 4, 8, 16, 32, 64, 128, 256

# POI categories (sub type of point features; also the search "kind" detail)
POI = {}
def poi_cat(t):
    a = t.get('amenity'); s = t.get('shop'); tour = t.get('tourism'); hw = t.get('highway'); rw = t.get('railway')
    if a == 'place_of_worship':
        return 1 if t.get('religion') == 'jewish' else 17
    if a in ('hospital',): return 2
    if a in ('clinic', 'doctors', 'dentist'): return 21
    if a == 'pharmacy': return 3
    if a in ('school', 'kindergarten'): return 4
    if a in ('university', 'college'): return 20
    if a == 'fuel': return 5
    if a == 'bus_station' or hw == 'bus_stop': return 6
    if rw in ('station', 'halt') or t.get('public_transport') == 'station' and t.get('train') == 'yes': return 7
    if s in ('mall', 'supermarket', 'department_store'): return 8
    if s: return 22
    if a in ('restaurant', 'cafe', 'fast_food', 'bar', 'pub', 'ice_cream'): return 9
    if a in ('bank', 'atm', 'bureau_de_change'): return 10
    if a == 'post_office': return 11
    if a in ('police', 'fire_station'): return 12
    if a == 'parking': return 14
    if tour in ('hotel', 'hostel', 'guest_house', 'motel'): return 15
    if a == 'mikveh' or t.get('mikveh'): return 16
    if tour in ('attraction', 'museum', 'viewpoint', 'zoo', 'theme_park') or t.get('historic'): return 18
    if a in ('townhall', 'courthouse') or t.get('office') == 'government': return 19
    if a in ('library', 'community_centre', 'social_facility', 'theatre', 'cinema'): return 23
    if a in ('drinking_water', 'toilets', 'bench', 'waste_basket', 'vending_machine', 'recycling', 'bicycle_parking',
             'parking_entrance', 'parking_space', 'charging_station', 'shelter', 'telephone', 'post_box', 'water_point',
             'fountain', 'clock', 'motorcycle_parking', 'grit_bin', 'waste_disposal', 'hunting_stand', 'loading_dock'):
        return 0
    if a: return 17
    if tour: return 18
    if t.get('office'): return 24
    return 0

PLACE = {'city': 0, 'town': 1, 'village': 2, 'suburb': 3, 'quarter': 3, 'neighbourhood': 4, 'hamlet': 4,
         'isolated_dwelling': 4, 'locality': 4, 'island': 4}

def he(t):
    return t.get('name:he') or t.get('name') or ''

class H(osmium.SimpleHandler):
    def __init__(self):
        super().__init__()
        # roads
        self.r_cls = array.array('b'); self.r_flags = array.array('H'); self.r_name = []; self.r_ref = []
        self.r_start = array.array('I', [0]); self.r_nodes = array.array('q'); self.r_lat = array.array('i'); self.r_lon = array.array('i')
        self.r_maxspeed = array.array('B')
        # lines: kind 11 rail, 12 waterway, 13 coastline (sub: 0 river/canal, 1 stream/drain)
        self.l_kind = array.array('b'); self.l_sub = array.array('b'); self.l_name = []
        self.l_start = array.array('I', [0]); self.l_lat = array.array('i'); self.l_lon = array.array('i')
        # areas: kind 20 water, 21 park/grass, 22 forest, 23 building, 25 industrial/commercial, 26 cemetery
        self.a_kind = array.array('b'); self.a_name = []; self.a_start = array.array('I', [0])
        self.a_lat = array.array('i'); self.a_lon = array.array('i')
        # points: places, POIs, addresses
        self.places = []    # (sub, name, lat, lon, population)
        self.pois = []      # (cat, name, lat, lon, street, house, city)
        self.addrs = []     # (street, house, city, lat, lon)
        self.n = 0

    def _ring_add(self, ring, kind, name):
        k = 0
        for nd in ring:
            self.a_lat.append(micro(nd.lat)); self.a_lon.append(micro(nd.lon)); k += 1
        if k < 3:
            del self.a_lat[len(self.a_lat) - k:]; del self.a_lon[len(self.a_lon) - k:]
            return
        self.a_kind.append(kind); self.a_name.append(name); self.a_start.append(len(self.a_lat))

    def node(self, n):
        t = n.tags
        if not t: return
        try:
            lat, lon = micro(n.location.lat), micro(n.location.lon)
        except Exception:
            return
        pl = t.get('place')
        nm = he(t)
        if pl in PLACE and nm:
            pop = 0
            try: pop = int(t.get('population', '0').replace(',', ''))
            except Exception: pass
            self.places.append((PLACE[pl], nm, lat, lon, pop))
        c = poi_cat(t)
        if c and (nm or c in (6, 5, 10, 3, 16)):
            self.pois.append((c, nm or t.get('ref', ''), lat, lon, t.get('addr:street', ''), t.get('addr:housenumber', ''), t.get('addr:city', '')))
        hn = t.get('addr:housenumber')
        if hn and t.get('addr:street'):
            self.addrs.append((t['addr:street'], hn, t.get('addr:city', ''), lat, lon))

    def way(self, w):
        t = w.tags
        hw = t.get('highway')
        if hw in ROAD_CLASS:
            if t.get('area') == 'yes': return
            cls = ROAD_CLASS[hw]
            fl = 0
            ow = t.get('oneway')
            if ow in ('yes', '1', 'true'): fl |= F_ONEWAY
            elif ow == '-1': fl |= F_ONEWAY | F_BACKWARD
            if t.get('junction') in ('roundabout', 'circular'):
                fl |= F_ROUNDABOUT
                if ow != 'no': fl |= F_ONEWAY
            if cls == 0 and ow != 'no': fl |= F_ONEWAY
            if hw.endswith('_link'): fl |= F_LINK
            acc = t.get('access')
            mv = t.get('motor_vehicle') or t.get('motorcar') or acc
            if cls == 9 or mv in ('no', 'private', 'agricultural', 'forestry', 'delivery') or hw in ('pedestrian', 'footway', 'steps', 'path', 'cycleway', 'bridleway'):
                fl |= F_NOCAR
            if hw == 'cycleway' and t.get('foot') not in ('yes', 'designated'): pass
            if cls in (0,) or t.get('foot') in ('no',) or acc == 'no' and t.get('foot') not in ('yes', 'designated'):
                fl |= F_NOFOOT
            if cls == 0 or hw == 'steps' or t.get('bicycle') == 'no': fl |= F_NOBIKE
            if t.get('tunnel') in ('yes', 'building_passage'): fl |= F_TUNNEL
            if t.get('bridge') == 'yes': fl |= F_BRIDGE
            k = 0
            for nd in w.nodes:
                try:
                    lat, lon = micro(nd.lat), micro(nd.lon)
                except Exception:
                    continue
                self.r_nodes.append(nd.ref); self.r_lat.append(lat); self.r_lon.append(lon); k += 1
            if k < 2:
                if k:
                    del self.r_nodes[-k:]; del self.r_lat[-k:]; del self.r_lon[-k:]
                return
            ms = 0
            try: ms = min(130, int(t.get('maxspeed', '0').split()[0]))
            except Exception: pass
            self.r_cls.append(cls); self.r_flags.append(fl); self.r_name.append(he(t)); self.r_ref.append(t.get('ref', ''))
            self.r_maxspeed.append(ms); self.r_start.append(len(self.r_nodes))
            return
        rw = t.get('railway'); ww = t.get('waterway'); nat = t.get('natural')
        kind = sub = None
        if rw in ('rail', 'light_rail', 'subway', 'narrow_gauge') and t.get('service') not in ('yard', 'siding', 'spur'):
            kind, sub = 11, (0 if rw == 'rail' else 1)
        elif ww in ('river', 'canal'): kind, sub = 12, 0
        elif ww in ('stream', 'drain', 'ditch', 'wadi'): kind, sub = 12, 1
        elif nat == 'coastline': kind, sub = 13, 0
        if kind is not None:
            k = 0
            for nd in w.nodes:
                try:
                    self.l_lat.append(micro(nd.lat)); self.l_lon.append(micro(nd.lon)); k += 1
                except Exception:
                    pass
            if k < 2:
                if k:
                    del self.l_lat[-k:]; del self.l_lon[-k:]
                return
            self.l_kind.append(kind); self.l_sub.append(sub); self.l_name.append(he(t)); self.l_start.append(len(self.l_lat))

    def area(self, a):
        t = a.tags
        kind = None
        if t.get('building') and t.get('building') != 'no': kind = 23
        elif t.get('natural') == 'water' or t.get('landuse') in ('reservoir', 'basin') or t.get('water') or t.get('leisure') == 'swimming_pool' and False:
            kind = 20
        elif t.get('leisure') in ('park', 'garden', 'playground', 'pitch', 'golf_course', 'nature_reserve') or t.get('landuse') in ('grass', 'recreation_ground', 'village_green', 'meadow', 'orchard', 'vineyard'):
            kind = 21
        elif t.get('landuse') == 'forest' or t.get('natural') in ('wood', 'scrub'):
            kind = 22
        elif t.get('landuse') in ('industrial', 'commercial', 'retail', 'railway', 'military'):
            kind = 25
        elif t.get('landuse') == 'cemetery' or t.get('amenity') == 'grave_yard':
            kind = 26
        nm = he(t)
        if kind is not None:
            for outer in a.outer_rings():
                self._ring_add(outer, kind, nm if kind != 23 else '')
        # POIs and addresses on areas: at the centroid of the first outer ring
        c = poi_cat(t)
        hn = t.get('addr:housenumber')
        if (c and nm) or (hn and t.get('addr:street')) or (t.get('place') in PLACE and nm):
            try:
                ring = next(iter(a.outer_rings()))
                la = lo = k = 0
                for nd in ring:
                    la += nd.lat; lo += nd.lon; k += 1
                if not k: return
                lat, lon = micro(la / k), micro(lo / k)
            except Exception:
                return
            if c and nm:
                self.pois.append((c, nm, lat, lon, t.get('addr:street', ''), hn or '', t.get('addr:city', '')))
            if hn and t.get('addr:street'):
                self.addrs.append((t['addr:street'], hn, t.get('addr:city', ''), lat, lon))
            if t.get('place') in PLACE and nm:
                self.places.append((PLACE[t['place']], nm, lat, lon, 0))

def main():
    pbf, out = sys.argv[1:3]
    t0 = time.time()
    h = H()
    h.apply_file(pbf, locations=True, idx='flex_mem')
    print('read %.0fs: roads %d (%d nodes), lines %d, areas %d, places %d, pois %d, addrs %d' % (
        time.time() - t0, len(h.r_cls), len(h.r_nodes), len(h.l_kind), len(h.a_kind), len(h.places), len(h.pois), len(h.addrs)))
    keep = {k: getattr(h, k) for k in ('r_cls', 'r_flags', 'r_name', 'r_ref', 'r_start', 'r_nodes', 'r_lat', 'r_lon', 'r_maxspeed',
                                        'l_kind', 'l_sub', 'l_name', 'l_start', 'l_lat', 'l_lon',
                                        'a_kind', 'a_name', 'a_start', 'a_lat', 'a_lon', 'places', 'pois', 'addrs')}
    with open(out, 'wb') as f:
        pickle.dump(keep, f, protocol=4)
    print('saved', out)

if __name__ == '__main__':
    main()
