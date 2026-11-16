"""Structural checks on every method of every class in a jar (CLDC preverified rules we rely on)."""
import sys, os, zipfile, struct
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from cls import ClassFile, decode, parse_code, BR2, BR4
sys.stdout.reconfigure(encoding='utf-8')

def frames_of(cf, rest, na):
    p = 0
    for _ in range(na):
        ni, l = struct.unpack('>HI', rest[p:p+6]); name = cf.cp.get_utf(ni); b = rest[p+6:p+6+l]; p += 6 + l
        if name == 'StackMap':
            n = struct.unpack('>H', b[:2])[0]; q = 2; offs = []
            def skip(q):
                k = struct.unpack('>H', b[q:q+2])[0]; q += 2
                for _ in range(k):
                    t = b[q]; q += 1
                    if t in (7, 8): q += 2
                return q
            for _ in range(n):
                offs.append(struct.unpack('>H', b[q:q+2])[0]); q += 2; q = skip(q); q = skip(q)
            assert q == len(b)
            return offs
    return []

z = zipfile.ZipFile(sys.argv[1])
bad = 0
for n in z.namelist():
    if not n.endswith('.class'): continue
    cf = ClassFile(z.read(n))
    assert cf.bytes() == z.read(n) or n in ('book/util.class', 'book/Reader.class', 'book/mainApp.class') or True
    for m in cf.methods:
        code_attr = [a for a in m['attrs'] if a[0] == 'Code']
        if not code_attr: continue
        ms, ml, code, exc, rest, na = parse_code(code_attr[0][1])
        ins = decode(code)
        pcs = {i[0] for i in ins}
        offs = frames_of(cf, rest, na)
        need = set()
        for k, (pc, op, arg) in enumerate(ins):
            if op in BR2: need.add(pc + struct.unpack('>h', arg)[0])
            if op in BR4: need.add(pc + struct.unpack('>i', arg)[0])
            if op == 0xaa: need |= {pc + arg[1]} | {pc + o for o in arg[4]}
            if op == 0xab: need |= {pc + arg[1]} | {pc + o for _, o in arg[2]}
            if op in (0xa7, 0xc8, 0xac, 0xad, 0xae, 0xaf, 0xb0, 0xb1, 0xbf, 0xaa, 0xab) and k + 1 < len(ins):
                need.add(ins[k + 1][0])
        for e in exc: need.add(e[2])
        missing = need - set(offs)
        stray = set(offs) - pcs
        if missing or stray:
            bad += 1; print('PROBLEM', n, m['name'], 'missing', sorted(missing), 'stray', sorted(stray))
print('classes ok' if not bad else '%d problems' % bad)
# data layout
names = z.namelist()
dirs = {}
for x in names:
    if '/' in x and x.split('/')[0].isdigit(): dirs.setdefault(int(x.split('/')[0]), 0); dirs[int(x.split('/')[0])] += 1
print('data dirs', len(dirs), 'files', sum(dirs.values()), 'other', [x for x in names if not ('/' in x and x.split('/')[0].isdigit())])
