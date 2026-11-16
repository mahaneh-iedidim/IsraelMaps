package mp;

/**
 * A map tile kept as its bytes (format of tools/mkmap.py) with the offset and type of each feature, so drawing
 * decodes the points of a feature only when its layer is drawn.
 */
final class Tile {
    final int key, bl, bo, quant;
    final byte[] d;
    String[] str;
    int n;
    int[] off;      // feature start (its type byte)
    byte[] type;
    int bytes;      // memory estimate for the cache
    int use;

    Tile(int key, byte[] d, int bl, int bo, int quant) {
        this.key = key;
        this.d = d;
        this.bl = bl;
        this.bo = bo;
        this.quant = quant;
        int[] p = {0};
        int ns = vint(d, p);
        str = new String[ns];
        for (int i = 0; i < ns; i++) {
            int l = vint(d, p);
            str[i] = utf8(d, p[0], l);
            p[0] += l;
        }
        n = vint(d, p);
        off = new int[n];
        type = new byte[n];
        for (int i = 0; i < n; i++) {
            off[i] = p[0];
            type[i] = d[p[0]];
            int fl = d[p[0] + 2];
            p[0] += 3;
            if ((fl & 1) != 0) vint(d, p);
            if ((fl & 2) != 0) vint(d, p);
            int np = vint(d, p);
            skip(d, p, np * 2);
        }
        bytes = d.length + n * 5 + 64 + ns * 40;
    }

    static int vint(byte[] d, int[] p) {
        int v = 0, s = 0, i = p[0];
        while (true) {
            int b = d[i++];
            v |= (b & 0x7F) << s;
            if ((b & 0x80) == 0) break;
            s += 7;
        }
        p[0] = i;
        return v;
    }

    static int zz(byte[] d, int[] p) {
        int v = vint(d, p);
        return (v >>> 1) ^ -(v & 1);
    }

    static void skip(byte[] d, int[] p, int count) {
        int i = p[0];
        for (int k = 0; k < count; k++) while ((d[i++] & 0x80) != 0) ;
        p[0] = i;
    }

    static String utf8(byte[] d, int s, int l) {
        StringBuffer b = new StringBuffer(l);
        int e = s + l;
        while (s < e) {
            int c = d[s++] & 255;
            if (c < 0x80) b.append((char) c);
            else if (c < 0xE0) {
                b.append((char) (((c & 0x1F) << 6) | (d[s++] & 0x3F)));
            } else if (c < 0xF0) {
                b.append((char) (((c & 0x0F) << 12) | ((d[s] & 0x3F) << 6) | (d[s + 1] & 0x3F)));
                s += 2;
            } else {
                s += 3;
                b.append('?');
            }
        }
        return b.toString();
    }
}
