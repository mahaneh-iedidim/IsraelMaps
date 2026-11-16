"""Turn Java 8 class files (StackMapTable) into CLDC preverified class files (StackMap, version 47).
Also checks that every referenced class exists in CLDC 1.1 / MIDP 2.0 or in the app itself."""
import sys, os, struct, zipfile
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from cls import ClassFile, parse_code, build_code, u2

CLDC_OK = set('''java/lang/Object java/lang/String java/lang/StringBuffer java/lang/Math java/lang/System java/lang/Integer
java/lang/Character java/lang/Thread java/lang/Runnable java/lang/Throwable java/lang/Exception java/lang/RuntimeException
java/lang/Error java/lang/OutOfMemoryError java/lang/ArrayIndexOutOfBoundsException java/lang/IndexOutOfBoundsException
java/lang/NullPointerException java/lang/Class java/lang/Runtime java/lang/IllegalArgumentException java/lang/IllegalStateException
java/lang/InterruptedException java/lang/Boolean java/lang/Byte java/lang/Short
java/io/InputStream java/io/IOException java/io/DataInputStream java/io/ByteArrayOutputStream java/io/DataOutputStream
java/io/ByteArrayInputStream java/io/EOFException java/io/PrintStream java/io/OutputStream java/util/Vector java/util/Enumeration java/util/Hashtable java/lang/SecurityException java/lang/ClassNotFoundException java/util/Timer java/util/TimerTask java/util/Calendar java/util/Date java/util/TimeZone'''.split())
CLDC_PREFIX = ('javax/microedition/', 'com/nokia/mid/ui/DeviceControl')

def split_desc_args(desc):
    args, i = [], 1
    while desc[i] != ')':
        j = i
        while desc[j] == '[': j += 1
        if desc[j] == 'L': j = desc.index(';', j)
        args.append(desc[i:j+1]); i = j + 1
    return args

def vt_from_desc(cp, d):
    if d in ('I', 'Z', 'B', 'C', 'S'): return [1]
    if d == 'F': return [2]
    if d in ('J', 'D'): raise Exception('long/double not supported')
    if d[0] == 'L': return [7, cp.cls(d[1:-1])]
    return [7, cp.cls(d)]  # array

def read_vt(b, p):
    t = b[p]
    if t in (7, 8): return [t, struct.unpack('>H', b[p+1:p+3])[0]], p + 3
    if t in (3, 4): raise Exception('long/double in frame')
    return [t], p + 1

def convert_method(cf, m, cname):
    code_attr = [a for a in m['attrs'] if a[0] == 'Code']
    if not code_attr: return
    ms, ml, code, exc, rest, na = parse_code(code_attr[0][1])
    subs, p = [], 0
    for _ in range(na):
        ni, l = struct.unpack('>HI', rest[p:p+6]); subs.append([cf.cp.get_utf(ni), rest[p+6:p+6+l]]); p += 6 + l
    smt = [b for n, b in subs if n == 'StackMapTable']
    # initial locals
    locals_ = []
    if not m['access'] & 0x0008:
        locals_.append([6] if m['name'] == '<init>' else [7, cf.cp.cls(cname)])
    for a in split_desc_args(m['desc']): locals_.append(vt_from_desc(cf.cp, a))
    frames = []
    if smt:
        b = smt[0]; n = struct.unpack('>H', b[:2])[0]; p = 2; off = -1
        loc = list(locals_)
        for _ in range(n):
            t = b[p]; p += 1
            if t < 64: delta, stack = t, []
            elif t < 128:
                delta = t - 64; v, p = read_vt(b, p); stack = [v]
            elif t == 247:
                delta = struct.unpack('>H', b[p:p+2])[0]; p += 2; v, p = read_vt(b, p); stack = [v]
            elif 248 <= t <= 250:
                delta = struct.unpack('>H', b[p:p+2])[0]; p += 2; loc = loc[:len(loc) - (251 - t)]; stack = []
            elif t == 251:
                delta = struct.unpack('>H', b[p:p+2])[0]; p += 2; stack = []
            elif 252 <= t <= 254:
                delta = struct.unpack('>H', b[p:p+2])[0]; p += 2
                loc = list(loc)
                for _ in range(t - 251):
                    v, p = read_vt(b, p); loc.append(v)
                stack = []
            elif t == 255:
                delta = struct.unpack('>H', b[p:p+2])[0]; p += 2
                k = struct.unpack('>H', b[p:p+2])[0]; p += 2; loc = []
                for _ in range(k):
                    v, p = read_vt(b, p); loc.append(v)
                k = struct.unpack('>H', b[p:p+2])[0]; p += 2; stack = []
                for _ in range(k):
                    v, p = read_vt(b, p); stack.append(v)
            else:
                raise Exception('bad frame %d' % t)
            off = off + delta + 1
            frames.append((off, list(loc), stack))
    out = bytearray(u2(len(frames)))
    enc = lambda v: bytes([v[0]]) + (u2(v[1]) if len(v) > 1 else b'')
    for off, loc, stack in frames:
        out += u2(off) + u2(len(loc)) + b''.join(enc(v) for v in loc) + u2(len(stack)) + b''.join(enc(v) for v in stack)
    newsubs = [['StackMap', bytes(out)]] if frames else []
    code_attr[0][1] = build_code(ms, ml, code, exc, newsubs, cf.cp)

def convert(data, app_classes):
    cf = ClassFile(data)
    cname = cf.cp.get_utf(cf.cp.e[cf.this][1])
    for m in cf.methods: convert_method(cf, m, cname)
    for f in cf.fields + cf.methods:
        f['attrs'] = [a for a in f['attrs'] if a[0] in ('Code', 'ConstantValue', 'Exceptions')]
    cf.attrs = []
    cf.major, cf.minor = 47, 0
    bad = []
    for x in cf.cp.e:
        if x and x[0] == 7:
            n = cf.cp.get_utf(x[1]).lstrip('[')
            if n.startswith('L'): n = n[1:-1]
            if len(n) == 1: continue
            if n not in CLDC_OK and not n.startswith(CLDC_PREFIX) and n not in app_classes: bad.append(n)
    if bad: raise Exception('%s uses non-CLDC classes: %s' % (cname, bad))
    return cf.bytes()

if __name__ == '__main__':
    root = sys.argv[1]
    files = []
    for dp, _, fs in os.walk(root):
        files += [os.path.join(dp, f) for f in fs if f.endswith('.class')]
    app = {os.path.relpath(f, root)[:-6].replace('\\', '/') for f in files}
    for f in files:
        d = convert(open(f, 'rb').read(), app)
        open(f, 'wb').write(d)
    print('converted', len(files))
