"""mkpal.py <out file>: the palette resource /pal of the maps app ("כחול עמוק וזהב", scheme 'deepblue' of
tools/tehillim_pals.py), the 36 colours of the sibling apps."""
import sys

def mix(a, b, t):
    return tuple(round(a[i] + (b[i] - a[i]) * t) for i in range(3))
def h(c): return ((c >> 16) & 255, (c >> 8) & 255, c & 255)
def n(t): return (t[0] << 16) | (t[1] << 8) | t[2]
WHITE, BLACK = (255, 255, 255), (0, 0, 0)

def build(main, metal, paper, ink):
    M, G, P, I = h(main), h(metal), h(paper), h(ink)
    base = [M, mix(M, WHITE, 0.3), G, mix(G, WHITE, 0.55),
            mix(M, WHITE, 0.08), mix(M, G, 0.25), mix(M, G, 0.45), mix(M, G, 0.7)]
    day = [P, P, mix(P, M, 0.13), I, mix(I, P, 0.45), WHITE, mix(P, M, 0.1), mix(M, WHITE, 0.12),
           M, mix(P, G, 0.25), mix(G, BLACK, 0.35), mix(G, WHITE, 0.5), mix(M, WHITE, 0.2), mix(M, WHITE, 0.35)]
    nb = mix(M, BLACK, 0.82)
    night = [nb, mix(nb, M, 0.08), mix(nb, M, 0.35), (230, 228, 222), (156, 154, 150), mix(nb, BLACK, 0.4),
             mix(nb, M, 0.18), mix(M, WHITE, 0.55), G, mix(nb, G, 0.2), mix(G, WHITE, 0.15), mix(nb, G, 0.45),
             mix(M, WHITE, 0.7), mix(M, WHITE, 0.6)]
    return [n(c) for c in base + day + night]

cols = build(0x1F3A68, 0xD8B24E, 0xF6F4EC, 0x16181C)
open(sys.argv[1], 'wb').write(b''.join(bytes([c >> 16, (c >> 8) & 255, c & 255]) for c in cols) + b'maps')
print('pal')
