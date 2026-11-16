package mp;

import java.io.IOException;

/**
 * The satellite file israel-sat.map (tools/mksat.py): JPEG tiles of 128x128 pixels in the app's own projection, one
 * grid for each map zoom 0..7, laid out from the top-left corner (latTop, lonLeft).
 */
final class Sat {
    static final int T = 128;
    final MapFile f;
    int latTop, lonLeft, n;
    int[] zoom, rows, cols, tab;

    Sat(MapFile f) throws IOException {
        this.f = f;
        byte[] h = f.read(0, 15);
        if (h[0] != 'I' || h[1] != 'L' || h[2] != 'S' || h[3] != 'T') throw new IOException("not a satellite file");
        n = h[6] & 255;
        latTop = MapFile.s32(h, 7);
        lonLeft = MapFile.s32(h, 11);
        byte[] l = f.read(15, n * 9);
        zoom = new int[n];
        rows = new int[n];
        cols = new int[n];
        tab = new int[n];
        for (int i = 0; i < n; i++) {
            zoom[i] = l[i * 9] & 255;
            rows[i] = MapFile.u16(l, i * 9 + 1);
            cols[i] = MapFile.u16(l, i * 9 + 3);
            tab[i] = MapFile.s32(l, i * 9 + 5);
        }
    }

    /** the grid of map zoom z, or -1 when the photos do not reach it */
    int level(int z) {
        for (int i = 0; i < n; i++) if (zoom[i] == z) return i;
        return -1;
    }

    /** the JPEG bytes of a tile, or null */
    byte[] tile(int li, int r, int c) throws IOException {
        if (r < 0 || c < 0 || r >= rows[li] || c >= cols[li]) return null;
        byte[] o = f.read(tab[li] + (r * cols[li] + c) * 4, 8);
        int a = MapFile.s32(o, 0), b = MapFile.s32(o, 4);
        if (b <= a) return null;
        return f.read(a, b - a);
    }
}
