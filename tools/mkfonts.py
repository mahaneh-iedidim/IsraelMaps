"""mkfonts.py <out dir> <reader tools dir>: the fonts of the maps app (BFont format, nikud.pack) in <out>/f/:
ur / ub / us = Segoe UI 13, Semibold 13 and 11 (the interface and the map labels), tt = Segoe UI Semilight 26."""
import sys, os
out, tools = sys.argv[1:3]
sys.path.insert(0, tools)
import nikud

HEB = ''.join(chr(c) for c in range(0x5D0, 0x5EB))
ASCII = ''.join(chr(c) for c in range(0x20, 0x7F))
UI = HEB + ASCII + '\u2022\u2026\u2013\u2014\u00b7\u05f3\u05f4\u05be\u2010\u2011\u2019\u201c\u201d\u2190\u2192'
W = 'C:/Windows/Fonts/'
os.makedirs(os.path.join(out, 'f'), exist_ok=True)
res = {
    'f/ur': nikud.pack(nikud.ttf_face(W + 'segoeui.ttf', 13, UI, False)),
    'f/ub': nikud.pack(nikud.ttf_face(W + 'seguisb.ttf', 13, UI, False)),
    'f/us': nikud.pack(nikud.ttf_face(W + 'segoeui.ttf', 11, UI, False)),
    'f/tt': nikud.pack(nikud.ttf_face(W + 'segoeuisl.ttf', 26, HEB + ASCII + '\u05f3\u05f4', False)),
}
for k, v in res.items():
    open(os.path.join(out, k), 'wb').write(v)
print('fonts', len(res), sum(len(v) for v in res.values()))
