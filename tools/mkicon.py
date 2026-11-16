"""mkicon.py <out.png> <preview.png>: the 48x48 icon in the sibling style: a deep blue rounded square with a
folded map (three panels, a road on it) and a gold location pin."""
import sys
from PIL import Image, ImageDraw

rgb = lambda c: (c >> 16, (c >> 8) & 255, c & 255)
BLUE, DEEP, GOLD, PAPER, PAPER2, ROAD, GREEN, WATER = (rgb(0x1F3A68), rgb(0x152A4C), rgb(0xD8B24E), rgb(0xF4F1E8),
                                                     rgb(0xDCD6C6), rgb(0xE892A2), rgb(0xBFDDA4), rgb(0x9CCBE0))
S, K = 48, 4
out, prev = sys.argv[1:3]
big = Image.new('RGBA', (S * K, S * K), (0, 0, 0, 0))
d = ImageDraw.Draw(big)
d.rounded_rectangle((0, 0, S * K - 1, S * K - 1), radius=8 * K, fill=BLUE)
d.rounded_rectangle((2 * K, 2 * K, S * K - 2 * K - 1, S * K - 2 * K - 1), radius=7 * K, outline=DEEP, width=K)
P = lambda pts: [(x * K, y * K) for x, y in pts]
# three panels of a folded map
d.polygon(P([(7, 15), (18, 12), (18, 38), (7, 41)]), fill=PAPER2)
d.polygon(P([(18, 12), (30, 15), (30, 41), (18, 38)]), fill=PAPER)
d.polygon(P([(30, 15), (41, 12), (41, 38), (30, 41)]), fill=PAPER2)
d.polygon(P([(7, 31), (18, 28), (18, 38), (7, 41)]), fill=WATER)
d.polygon(P([(30, 30), (41, 27), (41, 38), (30, 41)]), fill=GREEN)
d.line(P([(9, 22), (18, 24), (30, 33), (40, 30)]), fill=ROAD, width=int(2.2 * K))
# the pin
cx, cy = 24, 19
d.ellipse(P([(cx - 7, cy - 9), (cx + 7, cy + 5)]), fill=GOLD)
d.polygon(P([(cx - 6, cy - 0.5), (cx + 6, cy - 0.5), (cx, cy + 12)]), fill=GOLD)
d.ellipse(P([(cx - 3, cy - 5), (cx + 3, cy + 1)]), fill=BLUE)
im = big.resize((S, S), Image.LANCZOS)
im.save(out)
im.resize((S * 6, S * 6), Image.NEAREST).save(prev)
print('icon', out)
