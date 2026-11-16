"""bounds.py <pbf> <out.pkl>: the municipal boundaries (boundary=administrative, admin_level 7 and 8) as
(admin_level, Hebrew name, WKB polygon) for assigning streets and addresses to their city (tools/mkmap.py)."""
import sys, pickle, osmium
import shapely.wkb

def he(t):
    return t.get('name:he') or t.get('name') or ''

class H(osmium.SimpleHandler):
    def __init__(self):
        super().__init__()
        self.fab = osmium.geom.WKBFactory()
        self.out = []

    def area(self, a):
        t = a.tags
        if t.get('boundary') != 'administrative' or t.get('admin_level') not in ('7', '8'): return
        nm = he(t)
        if not nm: return
        try:
            wkb = self.fab.create_multipolygon(a)
        except Exception:
            return
        self.out.append((int(t.get('admin_level')), nm, bytes.fromhex(wkb) if isinstance(wkb, str) else wkb))

src, out = sys.argv[1:3]
h = H()
h.apply_file(src, locations=True, idx='flex_mem')
print('boundaries', len(h.out), 'level 8:', sum(1 for x in h.out if x[0] == 8), 'level 7:', sum(1 for x in h.out if x[0] == 7))
for lv, nm, w in h.out:
    if nm in ('ירושלים', 'מבשרת ציון', 'מטה יהודה', 'בית זית', 'מוצא'):
        print(' ', lv, nm, shapely.wkb.loads(w).area)
pickle.dump(h.out, open(out, 'wb'), protocol=4)
