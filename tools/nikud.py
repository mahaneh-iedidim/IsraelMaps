"""Fonts with nikud for the Tanach app.

The verse text keeps the source's points (mktanach.clean_pointed). In the app a vowel is a Unicode combining
mark that follows its letter and has no advance: BFont draws it centred under (or, for holam, to the left of)
the letter before it. A letter carrying a dagesh / shin dot / sin dot / holam / shuruk, or a final kaf with a
vowel inside, is one precomposed glyph at private-use char 0xE000 + source code, as in the source font.

Glyph model: G = (img, top, adv): img an 'L' image including a 1-pixel pad column on each side, top = row of the
image's first row relative to the baseline row (negative above), adv = advance (0 for a mark). pack() writes the
BFont format (see fontgen.py); a cell's width is implied by the next cell's x, so a mark can be wider than 0.

Faces built here (regular weight gets the nikud, bold is only used for unpointed labels):
  face 4 "מקורי": the bitmap font of the JewishContent Tanach app itself (s.png/m.png/l.png + .w), glyph for glyph
  face 3 pixel:   our pixel font + vowel marks taken from the original font, dots composed by rule
  faces 0..2 TTF: rendered marks, precomposed letters composed by rule from the font's own mark glyphs
"""
import os, struct
from PIL import Image, ImageDraw, ImageFont, ImageChops

MARKS = {0x15: 'ִ', 0x16: 'ָ', 0x17: 'ַ', 0x18: 'ֵ', 0x19: 'ֶ', 0x1A: 'ֻ',
         0x1B: 'ְ', 0x1C: 'ֳ', 0x1D: 'ֲ', 0x1E: 'ֱ', 0x1F: 'ֹ'}
HOLAM = 'ֹ'
DAGESH, SHIN_DOT, SIN_DOT = 'ּ', 'ׁ', 'ׂ'
# source code of a pointed letter -> (base letter, [(mark, rule)])
PRE = {0x10: ('ך', [('ָ', 'inside')]), 0x12: ('ך', [('ְ', 'inside')]),
       0x14: ('ר', [(DAGESH, 'center')]),
       0x41: ('ש', [(SHIN_DOT, 'right')]), 0x42: ('ש', [(SIN_DOT, 'left')]),
       0x43: ('ש', [(DAGESH, 'center'), (SHIN_DOT, 'right')]), 0x44: ('ש', [(DAGESH, 'center'), (SIN_DOT, 'left')]),
       0x49: ('ו', [(DAGESH, 'shuruk')]), 0x58: ('ו', [(HOLAM, 'above')]), 0x5A: ('ל', [(HOLAM, 'lamed')])}
for code, letter in zip([0x45, 0x46, 0x47, 0x48, 0x4A, 0x4B, 0x4C, 0x4D, 0x4E, 0x4F, 0x50, 0x51, 0x52, 0x53, 0x54, 0x55, 0x57],
                        'בגדהזטיךכלמנספצקת'):
    PRE[code] = (letter, [(DAGESH, 'center')])
PRIV = {code: chr(0xE000 + code) for code in PRE}

# ---------------- glyph model ----------------
def from_rows(rows, top, adv):
    """pixel rows ('#'/'.') -> G with pads"""
    w = len(rows[0]) if rows else 0
    img = Image.new('L', (w + 2, max(1, len(rows))), 0)
    for y, r in enumerate(rows):
        for x, p in enumerate(r):
            if p == '#': img.putpixel((x + 1, y), 255)
    return (img, top, adv)

def ink_box(img):
    return img.getbbox()  # (x0, y0, x1, y1) exclusive, or None

def pack(glyphs):
    """{char: G} -> BFont bytes"""
    tops = [t for _, t, _ in glyphs.values()]
    bottoms = [t + im.height - 1 for im, t, _ in glyphs.values()]
    asc, desc = -min(tops), max(bottoms)
    h = asc + desc + 1
    chars = sorted(glyphs)
    aw = sum(glyphs[c][0].width for c in chars)
    atlas = Image.new('L', (aw, h), 0)
    head = struct.pack('>BBHH', h, asc, len(chars), aw)
    x = 0
    for c in chars:
        im, top, adv = glyphs[c]
        atlas.paste(im, (x, asc + top))
        head += struct.pack('>HHB', ord(c), x, adv)
        x += im.width
    return head + atlas.tobytes()

def compose(base, marks, xt):
    """base G + [(ink image, ink top, rule)] -> G. xt = x-height top (row of the top of 'ב', < 0)."""
    bim, btop, badv = base
    bb = ink_box(bim)
    bx0, by0, bx1, by1 = bb[0] - 1, bb[1] + btop, bb[2] - 2, bb[3] - 1 + btop  # inclusive, x without pad
    placed = []
    for im, mtop, rule in marks:
        mw, mh = im.size
        if rule == 'center':
            ya, yb = max(by0, xt), min(by1, 0)
            x = (bx0 + bx1 + 1 - mw) // 2
            y = (ya + yb + 1 - mh) // 2
        elif rule == 'right':
            x, y = bx1 + 1 - mw, xt - 1 - mh
        elif rule == 'left':
            x, y = bx0, xt - 1 - mh
        elif rule == 'lamed':
            # holam of a lamed: just above the x-height, left of the mast (at bx0 it would merge into the mast)
            y = xt - 1 - mh
            stem = None
            for yy in range(y - 1, y + mh + 1):
                row = yy - btop
                if 0 <= row < bim.height:
                    for xx in range(bim.width):
                        if bim.getpixel((xx, row)) > 96:
                            stem = xx - 1 if stem is None else min(stem, xx - 1)
                            break
            if stem is None: stem = bx0
            x = stem - 1 - mw
        elif rule == 'above':
            x, y = (bx0 + bx1 + 1 - mw) // 2, by0 - 1 - mh
        elif rule == 'shuruk':
            # beside the stem at mid height (not left of the head, which reaches further left in some faces)
            y = (xt + 1 - mh) // 2
            stem = None
            for yy in range(y, y + mh):
                row = yy - btop
                if 0 <= row < bim.height:
                    for xx in range(bim.width):
                        if bim.getpixel((xx, row)) > 96:
                            stem = xx - 1 if stem is None else min(stem, xx - 1)
                            break
            if stem is None: stem = bx0
            x = stem - 1 - mw
        else:  # inside: in the bowl of a final kaf
            x, y = (bx0 + bx1 + 1 - mw) // 2, (xt + 1 - mh) // 2 + 1
        placed.append((im, x, y))
    # union of all boxes, in x without pads
    x0 = min([0] + [x for _, x, _ in placed])
    x1 = max([bim.width - 2] + [x + im.width for im, x, _ in placed])
    y0 = min([btop] + [y for _, _, y in placed])
    y1 = max([btop + bim.height] + [y + im.height for im, _, y in placed])
    shift = -x0
    out = Image.new('L', (x1 - x0 + 2, y1 - y0), 0)
    out.paste(bim, (shift, btop - y0))
    for im, x, y in placed:
        layer = Image.new('L', out.size, 0)
        layer.paste(im, (x + shift + 1, y - y0))
        out = ImageChops.lighter(out, layer)
    return (out, y0, badv + shift)

def mark_glyph(ink, top, pad_right=0):
    """a zero-advance mark from its ink image"""
    w, h = ink.size
    img = Image.new('L', (w + pad_right + 2, h), 0)
    img.paste(ink, (1, 0))
    return (img, top, 0)

def dot(n):
    return Image.new('L', (n, n), 255)

# ---------------- face 4: the original JewishContent font ----------------
def orig_strip(png, wfile):
    im = Image.open(png).convert('RGBA')
    w = open(wfile, 'rb').read()
    ratio, widths = w[0], list(w[1:257])
    offs, x = [], 0
    for c in range(256):
        offs.append(x); x += widths[c]
    H = im.height
    alpha = Image.new('L', im.size, 0)
    px = im.load()
    for yy in range(H):
        for xx in range(im.width):
            r, g, b, a = px[xx, yy]
            if a > 128 and r + g + b < 384: alpha.putpixel((xx, yy), 255)
    cells = {c: alpha.crop((offs[c], 0, offs[c] + widths[c], H)) for c in range(256) if widths[c]}
    return ratio, H, cells

def orig_face(png, wfile, chars):
    """regular glyphs (with nikud) and bold rows for the base chars of one size of the original font"""
    ratio, H, cells = orig_strip(png, wfile)
    base_row = ink_box(cells[0x62])[3] - 1  # bottom of ב
    glyphs, rows = {}, {}
    def put(ch, cell):
        w, h = cell.size
        bb = ink_box(cell)
        if bb is None:
            img = Image.new('L', (w + 2, 1), 0); top = 0
        else:
            img = Image.new('L', (w + 2, bb[3] - bb[1]), 0)
            img.paste(cell.crop((0, bb[1], w, bb[3])), (1, 0)); top = bb[1] - base_row
        glyphs[ch] = (img, top, w)
        rows[ch] = ([''.join('#' if img.getpixel((x + 1, y)) else '.' for x in range(w)) for y in range(img.height)], top, w)
    for ch in chars:
        o = ord(ch)
        if 0x5D0 <= o <= 0x5EA: code = 0x61 + o - 0x5D0
        elif o < 0x7F: code = o
        else: continue
        if code in cells: put(ch, cells[code])
    for code, pch in PRIV.items():
        if code in cells: put(pch, cells[code])
    regular = dict(glyphs)
    for code, mch in MARKS.items():
        cell = cells[code]
        bb = ink_box(cell)
        ink = cell.crop((bb[0], bb[1], bb[2], bb[3]))
        if code == 0x1F:  # holam: drawn one ratio left of the letter, at the top of the cell
            regular[mch] = mark_glyph(ink, bb[1] - base_row, 0)
        else:  # drawn at height - ratio below the cell top, centred with the letter's trailing gap
            regular[mch] = mark_glyph(ink, H - ratio + bb[1] - base_row, ratio)
    return regular, rows

def bold_rows(rows):
    out = {}
    for ch, (r, top, adv) in rows.items():
        if ch == ' ':
            out[ch] = from_rows(r, top, adv); continue
        nr = []
        for line in r:
            l2 = line + '.'
            nr.append(''.join('#' if l2[i] == '#' or (i > 0 and l2[i - 1] == '#') else '.' for i in range(len(l2))))
        out[ch] = from_rows(nr, top, adv + 1)
    return out

# ---------------- face 3: pixel font + nikud ----------------
def pixel_with_nikud(glyph_rows, orig_png, orig_w, scale):
    """glyph_rows: pixfont dict {char: (rows, top)}; vowel marks from the original font of a matching size"""
    out = {c: from_rows(r, t, len(r[0]) + 1) for c, (r, t) in glyph_rows.items()}
    xt = glyph_rows['ב'][1]
    ratio, H, cells = orig_strip(orig_png, orig_w)
    for code, mch in MARKS.items():
        cell = cells[code]
        bb = ink_box(cell)
        ink = cell.crop(bb)
        if code == 0x1F:
            out[mch] = mark_glyph(ink, xt - 1 - ink.height, 0)
        else:
            out[mch] = mark_glyph(ink, 2 if scale == 1 else 4, ratio)
    d = dot(scale)
    for code, (letter, ms) in PRE.items():
        if letter not in out: continue
        marks = []
        for m, rule in ms:
            if rule == 'inside':
                cell = cells[{'ָ': 0x16, 'ְ': 0x1B}[m]]
                ink = cell.crop(ink_box(cell))
                marks.append((ink, 0, rule))
            else:
                marks.append((d, 0, rule))
        out[PRIV[code]] = compose(out[letter], marks, xt)
    return out

# ---------------- faces 0..2: TrueType ----------------
def ttf_face(path, px, chars, nikud):
    f = ImageFont.truetype(path, px)
    asc, desc = f.getmetrics()
    pad = px * 2
    W, Hc = px * 6, (asc + desc) * 3
    def draw(c):
        img = Image.new('L', (W, Hc), 0)
        ImageDraw.Draw(img).text((pad, asc + desc), c, font=f, fill=255)
        return img  # baseline at row asc + desc + asc
    base_y = 2 * asc + desc
    out = {}
    for c in chars:
        adv = max(1, round(f.getlength(c)))
        img = draw(c)
        cell = img.crop((pad - 1, 0, pad + adv + 1, Hc))
        bb = ink_box(cell)
        if bb is None:
            out[c] = (Image.new('L', (adv + 2, 1), 0), 0, adv); continue
        out[c] = (cell.crop((0, bb[1], cell.width, bb[3])), bb[1] - base_y, adv)
    if not nikud: return out
    def ink(c):
        img = draw(c)
        bb = ink_box(img)
        return img.crop(bb), bb[1] - base_y
    xt = out['ב'][1]
    for code, mch in MARKS.items():
        im, top = ink(mch)
        out[mch] = mark_glyph(im, top, 0)
    for code, (letter, ms) in PRE.items():
        marks = []
        for m, rule in ms:
            im, top = ink(m)
            marks.append((im, top, rule))
        out[PRIV[code]] = compose(out[letter], marks, xt)
    return out
