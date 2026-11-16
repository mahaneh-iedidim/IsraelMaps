package mp;

import java.io.IOException;

/**
 * One tile grid of the map file (a map level or a road graph): the header and the row table are read at once, the
 * entries of a row (column, offset, length) the first time a tile of that row is wanted.
 */
final class TileSet {
    final MapFile f;
    final int base, oLat, oLon, tile, cols, rows, quant;
    private final int[] rowOff, rowCnt;
    private final short[][] rowCol;
    private final int[][] rowPos, rowLen;
    int pos, len;   // result of find()

    TileSet(MapFile f, int sec) throws IOException {
        this.f = f;
        base = f.sOff[sec];
        byte[] h = f.read(base, 17);
        oLat = MapFile.s32(h, 0);
        oLon = MapFile.s32(h, 4);
        tile = MapFile.s32(h, 8);
        cols = MapFile.u16(h, 12);
        rows = MapFile.u16(h, 14);
        quant = h[16] & 255;
        byte[] t = f.read(base + 17, rows * 6);
        rowOff = new int[rows];
        rowCnt = new int[rows];
        for (int r = 0; r < rows; r++) {
            rowOff[r] = MapFile.s32(t, r * 6);
            rowCnt[r] = MapFile.u16(t, r * 6 + 4);
        }
        rowCol = new short[rows][];
        rowPos = new int[rows][];
        rowLen = new int[rows][];
    }

    int row(int lat) { return floorDiv(lat - oLat, tile); }

    int col(int lon) { return floorDiv(lon - oLon, tile); }

    static int floorDiv(int a, int b) { return a >= 0 ? a / b : -((-a + b - 1) / b); }

    /** finds tile (r, c): true and pos / len (absolute file offset) when it exists */
    synchronized boolean find(int r, int c) throws IOException {
        if (r < 0 || c < 0 || r >= rows || c >= cols || rowCnt[r] == 0) return false;
        if (rowCol[r] == null) {
            int n = rowCnt[r];
            byte[] d = f.read(base + rowOff[r], n * 10);
            short[] cs = new short[n];
            int[] ps = new int[n], ls = new int[n];
            for (int i = 0; i < n; i++) {
                cs[i] = (short) MapFile.u16(d, i * 10);
                ps[i] = MapFile.s32(d, i * 10 + 2);
                ls[i] = MapFile.s32(d, i * 10 + 6);
            }
            rowPos[r] = ps;
            rowLen[r] = ls;
            rowCol[r] = cs;
        }
        short[] cs = rowCol[r];
        int lo = 0, hi = cs.length - 1;
        while (lo <= hi) {
            int m = (lo + hi) >> 1;
            int v = cs[m] & 0xFFFF;
            if (v < c) lo = m + 1;
            else if (v > c) hi = m - 1;
            else {
                pos = base + rowPos[r][m];
                len = rowLen[r][m];
                return true;
            }
        }
        return false;
    }

    /** reads tile (r, c) or null */
    byte[] load(int r, int c) throws IOException {
        int p, l;
        synchronized (this) {
            if (!find(r, c)) return null;
            p = pos;
            l = len;
        }
        return f.read(p, l);
    }
}
