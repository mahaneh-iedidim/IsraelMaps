"""dumptile.py <israel.map> <level 0-3> <lat> <lon>: lists the features of the map tile at the point (debugging)"""
import sys, struct

def vint(d, p):
    v = s = 0
    while True:
        b = d[p]; p += 1
        v |= (b & 0x7F) << s
        if not b & 0x80: return v, p
        s += 7

def zz(d, p):
    v, p = vint(d, p)
    return (v >> 1) ^ -(v & 1), p

path, L, lat, lon = sys.argv[1], int(sys.argv[2]), float(sys.argv[3]), float(sys.argv[4])
f = open(path, 'rb')
hd = f.read(8)
n = struct.unpack('>H', hd[6:8])[0]
secs = {}
for i in range(n):
    sid, off, ln = struct.unpack('>BII', f.read(9)); secs[sid] = (off, ln)
base = secs[L + 1][0]
f.seek(base)
oLat, oLon, tile, cols, rows, quant = struct.unpack('>iiiHHB', f.read(17))
rt = f.read(rows * 6)
r = (int(lat * 1e6) - oLat) // tile; c = (int(lon * 1e6) - oLon) // tile
roff, cnt = struct.unpack('>IH', rt[r * 6:r * 6 + 6])
f.seek(base + roff)
ents = f.read(cnt * 10)
for i in range(cnt):
    col, off, ln = struct.unpack('>HII', ents[i * 10:i * 10 + 10])
    if col != c: continue
    f.seek(base + off); d = f.read(ln)
    p = 0
    ns, p = vint(d, p)
    strs = []
    for _ in range(ns):
        l, p = vint(d, p); strs.append(d[p:p + l].decode('utf-8')); p += l
    nf, p = vint(d, p)
    counts = {}
    for _ in range(nf):
        t, sub, fl = d[p], d[p + 1], d[p + 2]; p += 3
        nm = ''
        if fl & 1: k, p = vint(d, p); nm = strs[k]
        if fl & 2: k, p = vint(d, p)
        np_, p = vint(d, p)
        pts = []
        a = o = 0
        for _ in range(np_):
            da, p = zz(d, p); do, p = zz(d, p); a += da; o += do; pts.append((a, o))
        counts[t] = counts.get(t, 0) + 1
        if t in (20,):
            print('water', sub, nm, np_, pts[:6])
    print('tile', r, c, 'bytes', ln, 'features', nf, sorted(counts.items()))
