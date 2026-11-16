package mp;

import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.Image;

/**
 * Anti-aliased bitmap font (format of tools/mkfonts.py): one row of glyph cells kept as alpha bytes. Each glyph
 * becomes a small coloured image the first time it is drawn in a colour (a few colours are cached per font), so
 * only the glyphs a text actually uses take image memory. Italic is drawn sheared (bands shifted to the right).
 */
final class BFont {
    final int h, ascent, n, aw;
    final char[] chars;
    final int[] cellX;
    final byte[] adv;
    final int[] cw;
    final byte[] alpha;
    private final int[] slotColor;
    private final Image[][] slot;
    private int slotNext;

    BFont(byte[] d, int colors) {
        slotColor = new int[colors];
        slot = new Image[colors][];
        h = d[0] & 255;
        ascent = d[1] & 255;
        n = ((d[2] & 255) << 8) | (d[3] & 255);
        aw = ((d[4] & 255) << 8) | (d[5] & 255);
        chars = new char[n];
        cellX = new int[n];
        adv = new byte[n];
        int p = 6;
        for (int i = 0; i < n; i++, p += 5) {
            chars[i] = (char) (((d[p] & 255) << 8) | (d[p + 1] & 255));
            cellX[i] = ((d[p + 2] & 255) << 8) | (d[p + 3] & 255);
            adv[i] = d[p + 4];
        }
        cw = new int[n];
        for (int i = 0; i < n; i++) cw[i] = (i + 1 < n ? cellX[i + 1] : aw) - cellX[i] - 2;
        alpha = new byte[aw * h];
        System.arraycopy(d, p, alpha, 0, alpha.length);
    }

    /** drops the coloured glyph images (they are made again when drawn) */
    void flush() {
        for (int i = 0; i < slot.length; i++) slot[i] = null;
        slotNext = 0;
    }

    int index(char c) {
        int lo = 0, hi = n - 1;
        while (lo <= hi) {
            int m = (lo + hi) >> 1;
            if (chars[m] < c) lo = m + 1;
            else if (chars[m] > c) hi = m - 1;
            else return m;
        }
        return -1;
    }

    boolean has(char c) { return index(c) >= 0; }

    int w(char c) {
        if (isMark(c) || c < ' ') return 0;
        int i = index(c);
        if (i < 0) {
            if (c == 0x200E || c == 0x200F || c == 0xAD || (c >= 0x2066 && c <= 0x2069) || (c >= 0x202A && c <= 0x202E) || c == 0x200B || c == 0x200D || c == 0x200C || c == 0xFEFF) return 0;
            i = index('?');
            if (i < 0) return h / 3;
        }
        return adv[i] & 255;
    }

    private Image glyph(int i, int color) {
        int s = -1;
        for (int k = 0; k < slot.length; k++) if (slot[k] != null && slotColor[k] == color) s = k;
        if (s < 0) {
            s = slotNext;
            slotNext = (slotNext + 1) % slot.length;
            slot[s] = new Image[n];
            slotColor[s] = color;
        }
        Image im = slot[s][i];
        if (im == null) {
            int gw = cw[i] + 2;
            if (gw < 1) gw = 1;
            int[] px = new int[gw * h];
            int x0 = cellX[i];
            for (int y = 0; y < h; y++) {
                int o = y * aw + x0, q = y * gw;
                for (int x = 0; x < gw; x++) px[q + x] = ((alpha[o + x] & 255) << 24) | color;
            }
            im = Image.createRGBImage(px, gw, h, true);
            slot[s][i] = im;
        }
        return im;
    }

    /** draws glyph c with its advance box starting at xLeft (italic: sheared) */
    void draw(Graphics g, char c, int xLeft, int y, int color, boolean italic) {
        if (c == ' ') return;
        int i = index(c);
        if (i < 0) {
            if (w(c) == 0) return;
            i = index('?');
            if (i < 0) return;
        }
        Image im = glyph(i, color);
        if (!italic) {
            g.drawImage(im, xLeft - 1, y, Graphics.TOP | Graphics.LEFT);
            return;
        }
        int gw = im.getWidth(), bands = h / 4 + 1, bh = (h + bands - 1) / bands;
        int cx = g.getClipX(), cy = g.getClipY(), cwd = g.getClipWidth(), ch = g.getClipHeight();
        for (int b = 0; b < bands; b++) {
            int yy = b * bh, hh = bh;
            if (yy + hh > h) hh = h - yy;
            if (hh <= 0) break;
            int shift = (bands - 1 - b) * 2 * h / (bands * 9);
            g.clipRect(xLeft - 1 + shift, y + yy, gw, hh);
            g.drawImage(im, xLeft - 1 + shift, y, Graphics.TOP | Graphics.LEFT);
            g.setClip(cx, cy, cwd, ch);
        }
    }

    void draw(Graphics g, char c, int xLeft, int y, int color) { draw(g, c, xLeft, y, color, false); }

    static boolean isMark(char c) { return (c >= 0x591 && c <= 0x5C7 && c != 0x5BE && c != 0x5C0 && c != 0x5C3 && c != 0x5C6) || (c >= 0x300 && c <= 0x36F); }

    /** draws mark c on the letter whose advance box is [baseX, baseX + baseW): centred, or holam to its left */
    void drawMark(Graphics g, char c, int baseX, int baseW, int y, int color) {
        int i = index(c);
        if (i < 0) return;
        int x = c == 0x5B9 ? baseX - cw[i] : baseX + (baseW - cw[i]) / 2;
        g.drawImage(glyph(i, color), x - 1, y, Graphics.TOP | Graphics.LEFT);
    }
}
