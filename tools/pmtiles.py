"""pmtiles.py: reading tiles of a PMTiles v3 archive inside a zip (the satellite layer of MapsOffline-data.mapsdata).
python pmtiles.py <mapsdata> <member> <z> <lat> <lon> <out.jpg>  saves the tile at the point (and its neighbours)."""
import sys, struct, gzip, zipfile, math, io

def varint(b, p):
    v = s = 0
    while True:
        c = b[p]; p += 1
        v |= (c & 0x7F) << s
        if c < 0x80: return v, p
        s += 7

def directory(b):
    p = 0
    n, p = varint(b, p)
    ids, runs, lens, offs = [], [], [], []
    last = 0
    for _ in range(n):
        d, p = varint(b, p); last += d; ids.append(last)
    for _ in range(n):
        r, p = varint(b, p); runs.append(r)
    for _ in range(n):
        l, p = varint(b, p); lens.append(l)
    for i in range(n):
        o, p = varint(b, p)
        offs.append(offs[i - 1] + lens[i - 1] if o == 0 and i > 0 else o - 1)
    return list(zip(ids, runs, lens, offs))

def zxy_to_id(z, x, y):
    acc = sum(4 ** k for k in range(z))
    n = 1 << z
    d = 0
    tx, ty = x, y
    s = n // 2
    while s > 0:
        rx = 1 if tx & s else 0
        ry = 1 if ty & s else 0
        d += s * s * ((3 * rx) ^ ry)
        if ry == 0:
            if rx == 1:
                tx = s - 1 - tx; ty = s - 1 - ty
            tx, ty = ty, tx
        s //= 2
    return acc + d

class PM:
    def __init__(self, f):
        self.f = f
        h = self.read(0, 127)
        v = struct.unpack('<11Q', h[8:96])
        self.root_off, self.root_len, _, _, self.leaf_off, self.leaf_len, self.data_off = v[:7]
        self.icomp = h[97]
        self.minz, self.maxz = h[100], h[101]
    def read(self, o, n):
        self.f.seek(o); return self.f.read(n)
    def dirat(self, o, n):
        b = self.read(o, n)
        return directory(gzip.decompress(b) if self.icomp == 2 else b)
    def get(self, z, x, y):
        tid = zxy_to_id(z, x, y)
        d = self.dirat(self.root_off, self.root_len)
        for _ in range(4):
            lo, hi, e = 0, len(d) - 1, None
            while lo <= hi:
                m = (lo + hi) // 2
                if d[m][0] <= tid: e = d[m]; lo = m + 1
                else: hi = m - 1
            if e is None: return None
            i, run, ln, off = e
            if run == 0:
                d = self.dirat(self.leaf_off + off, ln); continue
            if tid < i + run: return self.read(self.data_off + off, ln)
            return None
        return None

def tile_xy(z, lat, lon):
    n = 1 << z
    x = int((lon + 180) / 360 * n)
    y = int((1 - math.asinh(math.tan(math.radians(lat))) / math.pi) / 2 * n)
    return x, y

if __name__ == '__main__':
    src, member, z, lat, lon, out = sys.argv[1:7]
    z = int(z)
    zf = zipfile.ZipFile(src)
    pm = PM(zf.open(member))
    from PIL import Image
    x, y = tile_xy(z, float(lat), float(lon))
    sheet = Image.new('RGB', (768, 768))
    for dy in (-1, 0, 1):
        for dx in (-1, 0, 1):
            t = pm.get(z, x + dx, y + dy)
            if t: sheet.paste(Image.open(io.BytesIO(t)).convert('RGB'), ((dx + 1) * 256, (dy + 1) * 256))
    sheet.save(out, quality=90)
    t = pm.get(z, x, y)
    print('zoom', pm.minz, pm.maxz, 'tile', z, x, y, 'bytes', len(t) if t else None)
