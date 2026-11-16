package mp;

import java.io.IOException;

/**
 * Offline routing on the two road graphs of the map file (tools/mkmap.py): A* with travel time as the cost, the
 * full graph G near the start and the destination and the main-road graph M (motorway..tertiary, chains contracted)
 * in between. Nodes are G vertices, id = tile << 15 | local.
 */
final class Router {
    static final int CAR = 0, FOOT = 1, BIKE = 2;
    // km/h per road class 0..9 (motorway, trunk, primary, secondary, tertiary, residential, living / pedestrian,
    // service, track, path); 0 = not allowed
    static final int[][] SPEED = {
        {100, 80, 60, 50, 40, 28, 10, 15, 12, 0},
        {0, 3, 4, 5, 5, 5, 5, 5, 5, 5},
        {0, 12, 15, 16, 16, 16, 12, 15, 10, 10}};
    static final int[] VMAX = {110, 5, 18};

    final TileSet gs, ms;
    int profile;
    volatile boolean cancel;
    String error;

    Router(TileSet gs, TileSet ms) {
        this.gs = gs;
        this.ms = ms;
    }

    // ---------- graph tiles (small LRU caches) ----------
    static final class GT {
        int key, bl, bo, use, bytes;
        byte[] d;
        String[] str;
        int[] vOff;
        boolean m;
    }

    private final GT[] gc = new GT[28], mc = new GT[6];
    private int clock;

    GT tile(boolean m, int t) throws IOException {
        GT[] c = m ? mc : gc;
        for (int i = 0; i < c.length; i++) if (c[i] != null && c[i].key == t) {
            c[i].use = ++clock;
            return c[i];
        }
        TileSet s = m ? ms : gs;
        int r = t / s.cols, col = t % s.cols;
        byte[] d = s.load(r, col);
        if (d == null) return null;
        GT g = new GT();
        g.key = t;
        g.m = m;
        g.d = d;
        g.bl = s.oLat + r * s.tile;
        g.bo = s.oLon + col * s.tile;
        int[] p = {0};
        int ns = Tile.vint(d, p);
        g.str = new String[ns];
        for (int i = 0; i < ns; i++) {
            int l = Tile.vint(d, p);
            g.str[i] = Tile.utf8(d, p[0], l);
            p[0] += l;
        }
        int nv = Tile.vint(d, p);
        g.vOff = new int[nv];
        for (int v = 0; v < nv; v++) {
            g.vOff[v] = p[0];
            Tile.skip(d, p, m ? 4 : 2);
            if (!m && d[p[0]++] != 0) Tile.skip(d, p, 2);
            int ne = Tile.vint(d, p);
            for (int e = 0; e < ne; e++) {
                Tile.skip(d, p, m ? 7 : 5);
                int ng = Tile.vint(d, p);
                Tile.skip(d, p, ng * 2);
            }
        }
        g.bytes = d.length + nv * 4 + ns * 32 + 64;
        // room for it: drop the least recently used tiles while the cache is over its share of memory
        int budget = cacheBudget(m);
        while (true) {
            int total = g.bytes, v = -1, free = -1;
            for (int i = 0; i < c.length; i++) {
                if (c[i] == null) {
                    if (free < 0) free = i;
                    continue;
                }
                total += c[i].bytes;
                if (v < 0 || c[i].use < c[v].use) v = i;
            }
            if (free >= 0 && (total <= budget || v < 0)) {
                g.use = ++clock;
                c[free] = g;
                return g;
            }
            if (v < 0) return g;   // not kept (still usable by the caller)
            c[v] = null;
        }
    }

    /** bytes the graph tiles may take: a share of the phone's memory (small phones keep only a few tiles) */
    static int cacheBudget(boolean m) {
        int t = (int) Math.min(64000000L, Runtime.getRuntime().totalMemory());
        int b = m ? t / 6 : t / 5;
        if (b > 4000000) b = 4000000;
        if (b < 150000) b = 150000;
        return b;
    }

    void clearCache() {
        for (int i = 0; i < gc.length; i++) gc[i] = null;
        for (int i = 0; i < mc.length; i++) mc[i] = null;
    }

    // ---------- costs ----------
    boolean allowed(int attr) {
        int cls = attr & 15;
        if (SPEED[profile][cls] == 0) return false;
        if (profile == CAR) return (attr & (16 | 64)) == 0;
        if (profile == FOOT) return (attr & 128) == 0;
        return (attr & (16 | 256)) == 0;
    }

    int cost(int len, int attr) { return len * 360 / SPEED[profile][attr & 15] + (profile == CAR ? 20 : 0); }

    static int dist(int lat1, int lon1, int lat2, int lon2) {
        float dy = (lat2 - lat1) * 0.11132f, dx = (lon2 - lon1) * 0.09491f;
        return (int) Math.sqrt(dx * dx + dy * dy);
    }

    // ---------- the search ----------
    int cap, count;
    int[] nid, ng, npar, nmid, nedge, nlat, nlon, hash;
    byte[] nfl;   // 1 closed, 2 reached through an M edge
    int[] hf, hs;
    int hn;
    int expanded;

    int maxCap;

    /**
     * starts a search: small tables (a short route needs a few hundred junctions) that grow() doubles on demand,
     * up to what the free memory allows (about 90 bytes a junction)
     */
    void alloc() {
        System.gc();
        // no limit from freeMemory(): many phones enlarge the heap on demand; grow() stops at the first failure
        maxCap = 160000;
        if (nid == null) make(2048);
        cap = nid.length;
        count = 0;
        hn = 0;
        for (int i = 0; i < hash.length; i++) hash[i] = 0;
    }

    void make(int c) {
        nid = new int[c];
        ng = new int[c];
        npar = new int[c];
        nmid = new int[c];
        nedge = new int[c];
        nlat = new int[c];
        nlon = new int[c];
        nfl = new byte[c];
        int h = 1;
        while (h < c * 2) h <<= 1;
        hash = new int[h];
        hf = new int[c * 2];
        hs = new int[c * 2];
        cap = c;
    }

    static int[] copy(int[] a, int n) {
        int[] b = new int[n];
        System.arraycopy(a, 0, b, 0, a.length);
        return b;
    }

    /** doubles the tables (false when memory does not allow it) */
    boolean grow() {
        if (cap >= maxCap) return false;
        int c = Math.min(maxCap, cap * 2);
        try {
            int[] id2 = copy(nid, c), g2 = copy(ng, c), p2 = copy(npar, c), m2 = copy(nmid, c), e2 = copy(nedge, c),
                la2 = copy(nlat, c), lo2 = copy(nlon, c);
            byte[] f2 = new byte[c];
            System.arraycopy(nfl, 0, f2, 0, nfl.length);
            int h = 1;
            while (h < c * 2) h <<= 1;
            int[] hash2 = new int[h];
            int[] hf2 = copy(hf, c * 2), hs2 = copy(hs, c * 2);
            nid = id2;
            ng = g2;
            npar = p2;
            nmid = m2;
            nedge = e2;
            nlat = la2;
            nlon = lo2;
            nfl = f2;
            hf = hf2;
            hs = hs2;
            hash = hash2;
            cap = c;
            for (int i = 0; i < count; i++) hash[-1 - find(nid[i])] = i + 1;
            return true;
        } catch (OutOfMemoryError e) {
            maxCap = cap;
            System.gc();
            return false;
        }
    }

    int find(int id) {
        int m = hash.length - 1, h = (id * 0x9E3779B1) >>> 7 & m;
        while (true) {
            int s = hash[h];
            if (s == 0) return -1 - h;
            if (nid[s - 1] == id) return s - 1;
            h = (h + 1) & m;
        }
    }

    void push(int f, int s) {
        if (hn >= hf.length) {
            // the heap is full: keep the better half
            hn = hf.length / 2;
        }
        int i = hn++;
        while (i > 0) {
            int p = (i - 1) >> 1;
            if (hf[p] <= f) break;
            hf[i] = hf[p];
            hs[i] = hs[p];
            i = p;
        }
        hf[i] = f;
        hs[i] = s;
    }

    int pop() {
        int s = hs[0];
        hn--;
        int f = hf[hn], x = hs[hn], i = 0;
        while (true) {
            int c = 2 * i + 1;
            if (c >= hn) break;
            if (c + 1 < hn && hf[c + 1] < hf[c]) c++;
            if (hf[c] >= f) break;
            hf[i] = hf[c];
            hs[i] = hs[c];
            i = c;
        }
        hf[i] = f;
        hs[i] = x;
        return s;
    }

    int srcLat, srcLon, dstLat, dstLon, dstId, radius;
    int vLat, vLon, vMid;   // decoded by vertex()

    /** decodes position and M id of G vertex id */
    boolean vertex(int id) throws IOException {
        GT g = tile(false, id >>> 15);
        if (g == null) return false;
        int local = id & 0x7FFF;
        if (local >= g.vOff.length) return false;
        int[] p = {g.vOff[local]};
        vLat = g.bl + Tile.vint(g.d, p);
        vLon = g.bo + Tile.vint(g.d, p);
        vMid = -1;
        if (g.d[p[0]++] != 0) {
            int mt = Tile.vint(g.d, p);
            vMid = (mt << 20) | Tile.vint(g.d, p);
        }
        return true;
    }

    /** a new node or a better way to it */
    void relax(int id, int g, int par, int edge, boolean viaM, int lat, int lon, int mid) throws IOException {
        int s = find(id);
        if (s >= 0) {
            if ((nfl[s] & 1) != 0 || g >= ng[s]) return;
        } else {
            if (count >= cap) {
                if (!grow()) return;
                s = find(id);   // the slot in the new table
            }
            if (lat == Integer.MIN_VALUE) {
                if (!vertex(id)) return;
                lat = vLat;
                lon = vLon;
                mid = vMid;
            }
            hash[-1 - s] = count + 1;
            s = count++;
            nid[s] = id;
            nlat[s] = lat;
            nlon[s] = lon;
            nmid[s] = mid;
            nfl[s] = 0;
        }
        if (mid >= 0 && nmid[s] < 0) nmid[s] = mid;
        ng[s] = g;
        npar[s] = par;
        nedge[s] = edge;
        nfl[s] = (byte) (viaM ? 2 : 0);
        // a slightly inflated estimate (weighted A*): far fewer nodes, routes within a few percent of the best
        int h = dist(nlat[s], nlon[s], dstLat, dstLon) * 360 / VMAX[profile];
        push(g + h + (h >> 2), s);
    }

    /** nearest G vertex of the point that has a usable road, or -1 */
    int snapBest, snapDist;

    int snap(int lat, int lon) throws IOException {
        int r0 = gs.row(lat), c0 = gs.col(lon);
        snapBest = -1;
        snapDist = 400000;
        for (int ring = 0; ring <= 2 && (snapBest < 0 || ring < 2); ring++) {
            for (int r = r0 - ring; r <= r0 + ring; r++) {
                for (int c = c0 - ring; c <= c0 + ring; c++) {
                    if (Math.max(Math.abs(r - r0), Math.abs(c - c0)) != ring) continue;
                    if (r < 0 || c < 0 || r >= gs.rows || c >= gs.cols) continue;
                    // a neighbouring tile only when a road in it could be nearer than the one found
                    int bl = gs.oLat + r * gs.tile, bo = gs.oLon + c * gs.tile;
                    int nla = Math.max(bl, Math.min(lat, bl + gs.tile)), nlo = Math.max(bo, Math.min(lon, bo + gs.tile));
                    if (ring > 0 && dist(lat, lon, nla, nlo) >= snapDist) continue;
                    snapTile(r, c, lat, lon);
                }
            }
        }
        return snapBest;
    }

    void snapTile(int r, int c, int lat, int lon) throws IOException {
        int best = snapBest, bd = snapDist;
        GT g = tile(false, r * gs.cols + c);
        if (g == null) return;
        for (int v = 0; v < g.vOff.length; v++) {
            int[] p = {g.vOff[v]};
            int la = g.bl + Tile.vint(g.d, p), lo = g.bo + Tile.vint(g.d, p);
            int dd = dist(lat, lon, la, lo);
            if (dd >= bd) continue;
            if (g.d[p[0]++] != 0) Tile.skip(g.d, p, 2);
            int ne = Tile.vint(g.d, p);
            int minCls = 99;
            for (int e = 0; e < ne; e++) {
                Tile.skip(g.d, p, 3);
                int attr = Tile.vint(g.d, p);
                Tile.skip(g.d, p, 1);
                int ngm = Tile.vint(g.d, p);
                Tile.skip(g.d, p, ngm * 2);
                int a2 = attr & ~16;   // a one-way road still counts for the start / end
                if (allowed(a2) && (attr & 15) < minCls) minCls = attr & 15;
            }
            // tracks, paths and service roads only when nothing better is near
            if (minCls >= 7 && minCls < 99) dd += profile == CAR ? 400 : 60;
            if (minCls < 99 && dd < bd) {
                bd = dd;
                best = (r * gs.cols + c) << 15 | v;
            }
        }
        snapBest = best;
        snapDist = bd;
    }

    /** the route from (lat1, lon1) to (lat2, lon2), or null with error set */
    Route route(int lat1, int lon1, int lat2, int lon2) {
        Route r = route0(lat1, lon1, lat2, lon2);
        if (r == null && oom && !cancel) {
            // out of memory: everything freed, once more
            r = route0(lat1, lon1, lat2, lon2);
        }
        if (DEBUG) {
            StringBuffer b = new StringBuffer("route cap=");
            b.append(cap);
            b.append(" max=");
            b.append(maxCap);
            b.append(" nodes=");
            b.append(count);
            b.append(" expanded=");
            b.append(expanded);
            int tb = 0, tn = 0;
            for (int i = 0; i < gc.length; i++) if (gc[i] != null) {
                tb += gc[i].bytes;
                tn++;
            }
            b.append(" gtiles=");
            b.append(tn);
            b.append(" bytes=");
            b.append(tb);
            b.append(" ok=");
            b.append(r != null);
            System.out.println(b.toString());
        }
        // give the memory back to the map
        nid = null;
        ng = npar = nmid = nedge = nlat = nlon = hash = hf = hs = null;
        nfl = null;
        clearCache();
        return r;
    }

    boolean oom;
    static final boolean DEBUG = false;

    Route route0(int lat1, int lon1, int lat2, int lon2) {
        error = null;
        cancel = false;
        oom = false;
        try {
            int a = snap(lat1, lon1), b = snap(lat2, lon2);
            if (a < 0 || b < 0) {
                error = a < 0 ? Ui.L("אין דרך ליד נקודת המוצא", "No road near the starting point") : Ui.L("אין דרך ליד היעד", "No road near the destination");
                return null;
            }
            int[] radii = {2500, 9000, 400000};
            for (int k = 0; k < radii.length; k++) {
                if (k == 2 && dist(lat1, lon1, lat2, lon2) > 30000) break;
                int end = search(a, b, radii[k]);
                if (cancel) {
                    error = Ui.L("בוטל", "Cancelled");
                    return null;
                }
                if (end >= 0) {
                    Route r = build(end);
                    r.fromLat = lat1;
                    r.fromLon = lon1;
                    r.toLat = lat2;
                    r.toLon = lon2;
                    r.profile = profile;
                    r.steps();
                    return r;
                }
            }
            error = Ui.L("לא נמצא מסלול", "No route found");
        } catch (OutOfMemoryError e) {
            oom = true;
            nid = null;
            ng = npar = nmid = nedge = nlat = nlon = hash = hf = hs = null;
            nfl = null;
            clearCache();
            System.gc();
            error = Ui.L("אין מספיק זיכרון למסלול כזה", "Not enough memory for this route");
        } catch (Exception e) {
            error = Ui.L("שגיאה בקריאת המפה", "Error reading the map");
        }
        return null;
    }

    int search(int a, int b, int rad) throws IOException {
        alloc();
        radius = rad;
        if (!vertex(a)) return -1;
        srcLat = vLat;
        srcLon = vLon;
        int amid = vMid;
        if (!vertex(b)) return -1;
        dstLat = vLat;
        dstLon = vLon;
        dstId = b;
        expanded = 0;
        relax(a, 0, -1, 0, false, srcLat, srcLon, amid);
        while (hn > 0) {
            if (cancel) return -1;
            int s = pop();
            if ((nfl[s] & 1) != 0) continue;
            nfl[s] |= 1;
            expanded++;
            if (nid[s] == b) return s;
            int la = nlat[s], lo = nlon[s];
            boolean local = dist(la, lo, srcLat, srcLon) < rad || dist(la, lo, dstLat, dstLon) < rad;
            if (local) expandG(s);
            else if (nmid[s] >= 0) expandM(s);

        }
        return -1;
    }

    void expandG(int s) throws IOException {
        int id = nid[s], t = id >>> 15;
        GT g = tile(false, t);
        if (g == null) return;
        int[] p = {g.vOff[id & 0x7FFF]};
        Tile.skip(g.d, p, 2);
        if (g.d[p[0]++] != 0) Tile.skip(g.d, p, 2);
        int ne = Tile.vint(g.d, p), g0 = ng[s];
        for (int e = 0; e < ne; e++) {
            int tt = Tile.vint(g.d, p);
            int tl = Tile.vint(g.d, p);
            int len = Tile.vint(g.d, p);
            int attr = Tile.vint(g.d, p);
            Tile.skip(g.d, p, 1);
            int ngm = Tile.vint(g.d, p);
            Tile.skip(g.d, p, ngm * 2);
            if (!allowed(attr)) continue;
            int tid = ((tt == 0 ? t : tt - 1) << 15) | tl;
            relax(tid, g0 + cost(len, attr), s, e, false, Integer.MIN_VALUE, 0, -1);
            // relax may have loaded other tiles and evicted this one: the bytes stay valid (g holds them)
        }
    }

    void expandM(int s) throws IOException {
        int mid = nmid[s], t = mid >>> 20;
        GT g = tile(true, t);
        if (g == null) return;
        int local = mid & 0xFFFFF;
        if (local >= g.vOff.length) return;
        int[] p = {g.vOff[local]};
        Tile.skip(g.d, p, 4);
        int ne = Tile.vint(g.d, p), g0 = ng[s];
        for (int e = 0; e < ne; e++) {
            int tt = Tile.vint(g.d, p);
            int tl = Tile.vint(g.d, p);
            int gt = Tile.vint(g.d, p);
            int gl = Tile.vint(g.d, p);
            int len = Tile.vint(g.d, p);
            int attr = Tile.vint(g.d, p);
            Tile.skip(g.d, p, 1);
            int ngm = Tile.vint(g.d, p);
            Tile.skip(g.d, p, ngm * 2);
            if (!allowed(attr)) continue;
            int tmt = tt == 0 ? t : tt - 1;
            int tid = (gt << 15) | gl;
            int fs = find(tid);
            if (fs >= 0) {
                relax(tid, g0 + cost(len, attr), s, e, true, 0, 0, (tmt << 20) | tl);
                continue;
            }
            // the position of the target from its M tile
            GT tg = tmt == t ? g : tile(true, tmt);
            if (tg == null || tl >= tg.vOff.length) continue;
            int[] q = {tg.vOff[tl]};
            int la = tg.bl + Tile.vint(tg.d, q), lo = tg.bo + Tile.vint(tg.d, q);
            relax(tid, g0 + cost(len, attr), s, e, true, la, lo, (tmt << 20) | tl);
        }
    }

    // ---------- the result ----------
    Route build(int end) throws IOException {
        int n = 0;
        for (int s = end; s >= 0; s = npar[s]) n++;
        int[] path = new int[n];
        for (int s = end, i = n - 1; s >= 0; s = npar[s], i--) path[i] = s;
        Route r = new Route();
        r.profile = profile;
        r.addPoint(nlat[path[0]], nlon[path[0]]);
        for (int i = 1; i < n; i++) {
            int s = path[i], par = path[i - 1];
            boolean viaM = (nfl[s] & 2) != 0;
            GT g;
            int[] p;
            if (viaM) {
                int mid = nmid[par];
                g = tile(true, mid >>> 20);
                p = new int[] {g.vOff[mid & 0xFFFFF]};
                Tile.skip(g.d, p, 4);
            } else {
                int id = nid[par];
                g = tile(false, id >>> 15);
                p = new int[] {g.vOff[id & 0x7FFF]};
                Tile.skip(g.d, p, 2);
                if (g.d[p[0]++] != 0) Tile.skip(g.d, p, 2);
            }
            int ne = Tile.vint(g.d, p);
            for (int e = 0; e < ne; e++) {
                Tile.skip(g.d, p, viaM ? 4 : 2);
                int len = Tile.vint(g.d, p);
                int attr = Tile.vint(g.d, p);
                int nm = Tile.vint(g.d, p);
                int ngm = Tile.vint(g.d, p);
                if (e != nedge[s]) {
                    Tile.skip(g.d, p, ngm * 2);
                    continue;
                }
                r.startSeg(nm > 0 ? g.str[nm - 1] : "", attr, len);
                int la = viaM ? nlat[par] / 4 : nlat[par], lo = viaM ? nlon[par] / 4 : nlon[par];
                for (int k = 0; k < ngm; k++) {
                    la += Tile.zz(g.d, p);
                    lo += Tile.zz(g.d, p);
                    if (viaM) r.addPoint(la * 4, lo * 4);
                    else r.addPoint(la, lo);
                }
                break;
            }
            r.addPoint(nlat[s], nlon[s]);
        }
        r.finish();
        return r;
    }
}
