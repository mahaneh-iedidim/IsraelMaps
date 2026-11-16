"""Minimal class-file reader/writer, code relocator and assembler for CLDC (StackMap) classes."""
import struct

def u2(x): return struct.pack('>H', x)
def u4(x): return struct.pack('>I', x)

# ---------- constant pool ----------
class CP:
    def __init__(self, entries=None):
        self.e = entries or [None]  # index 0 unused; entries are (tag, value) raw tuples
    def _find(self, ent):
        for i, x in enumerate(self.e):
            if x == ent: return i
        self.e.append(ent)
        if ent[0] in (5, 6): self.e.append(None)
        return len(self.e) - 1 - (1 if ent[0] in (5, 6) else 0)
    def utf(self, s): return self._find((1, s))
    def cls(self, n): return self._find((7, self.utf(n)))
    def string(self, s): return self._find((8, self.utf(s)))
    def integer(self, v): return self._find((3, v))
    def nat(self, n, t): return self._find((12, (self.utf(n), self.utf(t))))
    def field(self, c, n, t): return self._find((9, (self.cls(c), self.nat(n, t))))
    def method(self, c, n, t): return self._find((10, (self.cls(c), self.nat(n, t))))
    def imethod(self, c, n, t): return self._find((11, (self.cls(c), self.nat(n, t))))
    def get_utf(self, i): return self.e[i][1]
    def find_string(self, s):
        for i, x in enumerate(self.e):
            if x and x[0] == 8 and self.e[x[1]] == (1, s): return i
    def bytes(self):
        out = u2(len(self.e))
        for x in self.e[1:]:
            if x is None: continue
            t, v = x
            if t == 1:
                b = mutf8(v); out += bytes([1]) + u2(len(b)) + b
            elif t == 3: out += bytes([3]) + struct.pack('>i', v)
            elif t == 4: out += bytes([4]) + struct.pack('>f', v)
            elif t == 5: out += bytes([5]) + struct.pack('>q', v)
            elif t == 6: out += bytes([6]) + struct.pack('>d', v)
            elif t in (7, 8): out += bytes([t]) + u2(v)
            else: out += bytes([t]) + u2(v[0]) + u2(v[1])
        return out

def mutf8(s):
    out = bytearray()
    for ch in s:
        c = ord(ch)
        if 0 < c < 0x80: out.append(c)
        elif c < 0x800: out += bytes([0xC0 | c >> 6, 0x80 | c & 0x3F])
        else: out += bytes([0xE0 | c >> 12, 0x80 | (c >> 6) & 0x3F, 0x80 | c & 0x3F])
    return bytes(out)

def demutf8(b):
    s, i = [], 0
    while i < len(b):
        c = b[i]
        if c < 0x80: s.append(chr(c)); i += 1
        elif c >> 5 == 6: s.append(chr((c & 0x1F) << 6 | b[i+1] & 0x3F)); i += 2
        else: s.append(chr((c & 0x0F) << 12 | (b[i+1] & 0x3F) << 6 | b[i+2] & 0x3F)); i += 3
    return ''.join(s)

# ---------- class file ----------
class ClassFile:
    def __init__(self, data):
        p = 8
        self.minor, self.major = struct.unpack('>HH', data[4:8])
        n = struct.unpack('>H', data[p:p+2])[0]; p += 2
        e = [None]; i = 1
        while i < n:
            t = data[p]; p += 1
            if t == 1:
                l = struct.unpack('>H', data[p:p+2])[0]; e.append((1, demutf8(data[p+2:p+2+l]))); p += 2 + l
            elif t == 3: e.append((3, struct.unpack('>i', data[p:p+4])[0])); p += 4
            elif t == 4: e.append((4, struct.unpack('>f', data[p:p+4])[0])); p += 4
            elif t == 5: e.append((5, struct.unpack('>q', data[p:p+8])[0])); e.append(None); p += 8; i += 1
            elif t == 6: e.append((6, struct.unpack('>d', data[p:p+8])[0])); e.append(None); p += 8; i += 1
            elif t in (7, 8): e.append((t, struct.unpack('>H', data[p:p+2])[0])); p += 2
            else: e.append((t, struct.unpack('>HH', data[p:p+4]))); p += 4
            i += 1
        self.cp = CP(e)
        self.access, self.this, self.super = struct.unpack('>HHH', data[p:p+6]); p += 6
        ni = struct.unpack('>H', data[p:p+2])[0]; p += 2
        self.interfaces = list(struct.unpack('>%dH' % ni, data[p:p+2*ni])); p += 2 * ni
        self.fields, p = self._members(data, p)
        self.methods, p = self._members(data, p)
        self.attrs, p = self._attrs(data, p)

    def _attrs(self, d, p):
        n = struct.unpack('>H', d[p:p+2])[0]; p += 2; out = []
        for _ in range(n):
            ni, l = struct.unpack('>HI', d[p:p+6]); out.append([self.cp.get_utf(ni), d[p+6:p+6+l]]); p += 6 + l
        return out, p

    def _members(self, d, p):
        n = struct.unpack('>H', d[p:p+2])[0]; p += 2; out = []
        for _ in range(n):
            acc, ni, di = struct.unpack('>HHH', d[p:p+6]); p += 6
            attrs, p = self._attrs(d, p)
            out.append({'access': acc, 'name': self.cp.get_utf(ni), 'desc': self.cp.get_utf(di), 'attrs': attrs})
        return out, p

    def method(self, name, desc=None):
        for m in self.methods:
            if m['name'] == name and (desc is None or m['desc'] == desc): return m

    def bytes(self):
        cp = self.cp
        def attrs(a):
            out = u2(len(a))
            for n, b in a: out += u2(cp.utf(n)) + u4(len(b)) + b
            return out
        def members(ms):
            out = u2(len(ms))
            for m in ms: out += u2(m['access']) + u2(cp.utf(m['name'])) + u2(cp.utf(m['desc'])) + attrs(m['attrs'])
            return out
        # serialize members first so any new utf entries land in the pool
        body = u2(self.access) + u2(self.this) + u2(self.super) + u2(len(self.interfaces)) + b''.join(u2(i) for i in self.interfaces)
        body += members(self.fields) + members(self.methods) + attrs(self.attrs)
        return b'\xca\xfe\xba\xbe' + u2(self.minor) + u2(self.major) + cp.bytes() + body

# ---------- Code attribute ----------
LEN = {}
for op in range(256): LEN[op] = 1
for op in (0x10, 0x12, 0x15, 0x16, 0x17, 0x18, 0x19, 0x36, 0x37, 0x38, 0x39, 0x3a, 0xa9, 0xbc): LEN[op] = 2
for op in (0x11, 0x13, 0x14, 0x84, 0xb2, 0xb3, 0xb4, 0xb5, 0xb6, 0xb7, 0xb8, 0xbb, 0xbd, 0xc0, 0xc1) + tuple(range(0x99, 0xa9)) + (0xc6, 0xc7): LEN[op] = 3
LEN[0xc5] = 4; LEN[0xb9] = 5; LEN[0xc8] = 5; LEN[0xc9] = 5
BR2 = set(range(0x99, 0xa9)) | {0xc6, 0xc7}
BR4 = {0xc8, 0xc9}

def decode(code):
    """list of [pc, op, operand-bytes or switch tuple]"""
    ins, pc = [], 0
    while pc < len(code):
        op = code[pc]
        if op == 0xaa:
            q = (pc + 4) & ~3
            df, lo, hi = struct.unpack('>iii', code[q:q+12])
            offs = list(struct.unpack('>%di' % (hi - lo + 1), code[q+12:q+12+4*(hi-lo+1)]))
            ins.append([pc, op, ('T', df, lo, hi, offs)]); pc = q + 12 + 4 * (hi - lo + 1)
        elif op == 0xab:
            q = (pc + 4) & ~3
            df, n = struct.unpack('>ii', code[q:q+8])
            pairs = [struct.unpack('>ii', code[q+8+8*k:q+16+8*k]) for k in range(n)]
            ins.append([pc, op, ('L', df, pairs)]); pc = q + 8 + 8 * n
        elif op == 0xc4:
            l = 6 if code[pc+1] == 0x84 else 4
            ins.append([pc, op, code[pc+1:pc+l]]); pc += l
        else:
            l = LEN[op]; ins.append([pc, op, code[pc+1:pc+l]]); pc += l
    return ins

def encode(ins, newpc, oldpc_of):
    """re-encode with new pcs; branch targets mapped through newpc (old->new)"""
    out = bytearray()
    for pc, op, arg in ins:
        npc = len(out)
        if op in (0xaa, 0xab):
            out.append(op)
            while len(out) % 4: out.append(0)
            if arg[0] == 'T':
                _, df, lo, hi, offs = arg
                out += struct.pack('>iii', newpc[pc + df] - npc, lo, hi)
                for o in offs: out += struct.pack('>i', newpc[pc + o] - npc)
            else:
                _, df, pairs = arg
                out += struct.pack('>ii', newpc[pc + df] - npc, len(pairs))
                for m, o in pairs: out += struct.pack('>ii', m, newpc[pc + o] - npc)
        elif op in BR2 and pc is not None and oldpc_of(pc):
            t = struct.unpack('>h', arg)[0]
            out.append(op); out += struct.pack('>h', newpc[pc + t] - npc)
        elif op in BR4 and pc is not None and oldpc_of(pc):
            t = struct.unpack('>i', arg)[0]
            out.append(op); out += struct.pack('>i', newpc[pc + t] - npc)
        else:
            out.append(op); out += arg
    return bytes(out)

def parse_code(b):
    max_stack, max_locals, clen = struct.unpack('>HHI', b[:8])
    code = b[8:8+clen]; p = 8 + clen
    n = struct.unpack('>H', b[p:p+2])[0]; p += 2
    exc = [list(struct.unpack('>HHHH', b[p+8*k:p+8*k+8])) for k in range(n)]; p += 8 * n
    na = struct.unpack('>H', b[p:p+2])[0]; p += 2; attrs = []
    return max_stack, max_locals, code, exc, b[p:], na

def patch_code(cf, m, edits, extra_stack=0, extra_locals=0):
    """edits: list of (old_pc, remove_len, new_bytes) applied to the method's code.
    Inserted bytes must not contain branches. Branch targets, exception table and StackMap are relocated;
    LineNumberTable / LocalVariableTable are dropped."""
    attr = [a for a in m['attrs'] if a[0] == 'Code'][0]
    max_stack, max_locals, code, exc, rest, na = parse_code(attr[1])
    # sub-attributes
    subs, p = [], 0
    for _ in range(na):
        ni, l = struct.unpack('>HI', rest[p:p+6]); subs.append([cf.cp.get_utf(ni), rest[p+6:p+6+l]]); p += 6 + l
    ins = decode(code)
    edits = {e[0]: e for e in edits}
    new_ins = []
    for i in ins:
        if i[0] in edits:
            pc, rm, nb = edits[i[0]]
            assert rm in (0, 1 + len(i[2])), 'edit must insert before, or replace exactly one instruction'
            for k, x in enumerate(decode(nb)):
                new_ins.append([None, x[1], x[2], i[0] if k == 0 else None])
            if not rm: new_ins.append(i + [i[0]])
        else:
            new_ins.append(i + [i[0]])
    # compute new pcs iteratively (switch padding depends on position)
    def layout():
        pcs, pc = [], 0
        for x in new_ins:
            pcs.append(pc); op, arg = x[1], x[2]
            if op == 0xaa: pc = ((pc + 4) & ~3) + 12 + 4 * len(arg[4])
            elif op == 0xab: pc = ((pc + 4) & ~3) + 8 + 8 * len(arg[2])
            else: pc += 1 + len(arg)
        return pcs, pc
    pcs, end = layout()
    newpc = {}
    for x, npc in zip(new_ins, pcs):
        if x[3] is not None: newpc[x[3]] = npc
    newpc[len(code)] = end
    out = bytearray()
    for x, npc in zip(new_ins, pcs):
        assert len(out) == npc
        pc, op, arg, anchor = x
        if pc is None:  # inserted
            out.append(op); out += arg; continue
        if op in (0xaa, 0xab):
            out.append(op)
            while len(out) % 4: out.append(0)
            if arg[0] == 'T':
                _, df, lo, hi, offs = arg
                out += struct.pack('>iii', newpc[pc + df] - npc, lo, hi)
                for o in offs: out += struct.pack('>i', newpc[pc + o] - npc)
            else:
                _, df, pairs = arg
                out += struct.pack('>ii', newpc[pc + df] - npc, len(pairs))
                for mm, o in pairs: out += struct.pack('>ii', mm, newpc[pc + o] - npc)
        elif op in BR2:
            t = struct.unpack('>h', arg)[0]; out.append(op); out += struct.pack('>h', newpc[pc + t] - npc)
        elif op in BR4:
            t = struct.unpack('>i', arg)[0]; out.append(op); out += struct.pack('>i', newpc[pc + t] - npc)
        else:
            out.append(op); out += arg
    newcode = bytes(out)
    newexc = [[newpc[a], newpc[b], newpc[c], t] for a, b, c, t in exc]
    newsubs = []
    for name, b in subs:
        if name == 'StackMap':
            newsubs.append([name, reloc_stackmap(b, newpc)])
        elif name in ('LineNumberTable', 'LocalVariableTable'):
            continue
        else:
            raise Exception('unknown code attr ' + name)
    attr[1] = build_code(max_stack + extra_stack, max_locals + extra_locals, newcode, newexc, newsubs, cf.cp)

def reloc_stackmap(b, newpc):
    n = struct.unpack('>H', b[:2])[0]; p = 2; out = bytearray(u2(n))
    def types(p):
        k = struct.unpack('>H', b[p:p+2])[0]; o = bytearray(u2(k)); p += 2
        for _ in range(k):
            t = b[p]; o.append(t); p += 1
            if t == 7: o += b[p:p+2]; p += 2
            elif t == 8: o += u2(newpc[struct.unpack('>H', b[p:p+2])[0]]); p += 2
        return o, p
    for _ in range(n):
        off = struct.unpack('>H', b[p:p+2])[0]; p += 2
        out += u2(newpc[off])
        o, p = types(p); out += o
        o, p = types(p); out += o
    return bytes(out)

def build_code(max_stack, max_locals, code, exc, subs, cp):
    b = u2(max_stack) + u2(max_locals) + u4(len(code)) + code + u2(len(exc))
    for e in exc: b += b''.join(u2(x) for x in e)
    b += u2(len(subs))
    for n, d in subs: b += u2(cp.utf(n)) + u4(len(d)) + d
    return b

# ---------- tiny assembler ----------
OPC = {
 'nop':0,'aconst_null':1,'iconst_m1':2,'iconst_0':3,'iconst_1':4,'iconst_2':5,'iconst_3':6,'iconst_4':7,'iconst_5':8,
 'bipush':0x10,'sipush':0x11,'ldc':0x12,'ldc_w':0x13,'iload':0x15,'aload':0x19,
 'iload_0':0x1a,'iload_1':0x1b,'iload_2':0x1c,'iload_3':0x1d,'aload_0':0x2a,'aload_1':0x2b,'aload_2':0x2c,'aload_3':0x2d,
 'aaload':0x32,'istore':0x36,'astore':0x3a,'istore_1':0x3c,'istore_2':0x3d,'istore_3':0x3e,'astore_0':0x4b,'astore_1':0x4c,'astore_2':0x4d,'astore_3':0x4e,
 'aastore':0x53,'pop':0x57,'dup':0x59,'iadd':0x60,'iinc':0x84,
 'ifeq':0x99,'ifne':0x9a,'iflt':0x9b,'ifge':0x9c,'ifgt':0x9d,'ifle':0x9e,
 'if_icmpeq':0x9f,'if_icmpne':0xa0,'if_icmplt':0xa1,'if_icmpge':0xa2,'if_icmpgt':0xa3,'if_icmple':0xa4,
 'if_acmpeq':0xa5,'if_acmpne':0xa6,'goto':0xa7,'ireturn':0xac,'areturn':0xb0,'return':0xb1,
 'getstatic':0xb2,'putstatic':0xb3,'getfield':0xb4,'putfield':0xb5,'invokevirtual':0xb6,'invokespecial':0xb7,
 'invokestatic':0xb8,'invokeinterface':0xb9,'new':0xbb,'anewarray':0xbd,'arraylength':0xbe,'ifnull':0xc6,'ifnonnull':0xc7,
}

def assemble(cp, lines, frames):
    """lines: list of tuples (op, *args) or ('label', name). frames: {label: (locals, stack)} with
    type specs 'I', 'T' (top), 'N' (null), or a class name. Returns (code, stackmap_bytes)."""
    # pass 1: sizes
    labels, pc = {}, 0
    sized = []
    for ln in lines:
        if ln[0] == 'label': labels[ln[1]] = pc; continue
        op = ln[0]; o = OPC[op]
        size = {0x10: 2, 0x11: 3, 0x12: 2, 0x13: 3, 0x15: 2, 0x19: 2, 0x36: 2, 0x3a: 2, 0x84: 3}.get(o)
        if size is None:
            size = 3 if o in BR2 or o in (0xb2, 0xb3, 0xb4, 0xb5, 0xb6, 0xb7, 0xb8, 0xbb, 0xbd) else 5 if o == 0xb9 else 1
        if op == 'ldc':
            idx = ln[1]
            if idx > 255: op, o, size = 'ldc_w', 0x13, 3
        sized.append((pc, op, o, ln[1:])); pc += size
    out = bytearray()
    for pc, op, o, args in sized:
        assert len(out) == pc
        out.append(o)
        if o in BR2: out += struct.pack('>h', labels[args[0]] - pc)
        elif o == 0x10: out += struct.pack('>b', args[0])
        elif o == 0x11: out += struct.pack('>h', args[0])
        elif o == 0x12: out.append(args[0])
        elif o in (0x15, 0x19, 0x36, 0x3a): out.append(args[0])
        elif o == 0x84: out += bytes([args[0], args[1] & 0xff])
        elif o in (0x13, 0xb2, 0xb3, 0xb4, 0xb5, 0xb6, 0xb7, 0xb8, 0xbb, 0xbd): out += u2(args[0])
        elif o == 0xb9: out += u2(args[0]) + bytes([args[1], 0])
    sm = bytearray(u2(len(frames)))
    def ty(t):
        if t == 'I': return b'\x01'
        if t == 'T': return b'\x00'
        if t == 'N': return b'\x05'
        return b'\x07' + u2(cp.cls(t))
    for lab in sorted(frames, key=lambda l: labels[l]):
        loc, stk = frames[lab]
        sm += u2(labels[lab]) + u2(len(loc)) + b''.join(ty(t) for t in loc) + u2(len(stk)) + b''.join(ty(t) for t in stk)
    return bytes(out), bytes(sm)
