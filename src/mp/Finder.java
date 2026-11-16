package mp;

import java.io.IOException;
import java.util.Vector;

/**
 * Search in the index of the map file: records (place, street, address, point of interest) sorted by the
 * normalized name, in blocks of about 3 KB with the first key of each block in a small table read once.
 * A query is "name [house number] [city]": the words are tried as name + city, longest name first.
 */
final class Finder {
    static final class Hit {
        int kind, cat, lat, lon, dist, score;
        String name, city, house;
    }

    final MapFile f;
    String[] keys;
    int[] offs;
    int blocks;
    volatile boolean cancel;

    Finder(MapFile f) {
        this.f = f;
    }

    void index() throws IOException {
        if (keys != null) return;
        int s = f.sOff[5];
        int hl = MapFile.s32(f.read(s, 4), 0);
        byte[] h = f.read(s + 4, hl);
        int[] p = {0};
        int n = Tile.vint(h, p);
        String[] k = new String[n];
        int[] o = new int[n];
        for (int i = 0; i < n; i++) {
            int l = Tile.vint(h, p);
            k[i] = Tile.utf8(h, p[0], l);
            p[0] += l;
            o[i] = MapFile.s32(h, p[0]);
            p[0] += 4;
        }
        blocks = s + 4 + hl;
        offs = o;
        keys = k;
    }

    static String norm(String s) {
        StringBuffer b = new StringBuffer(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x591 && c <= 0x5C7) continue;
            if (c == 'ך') c = 'כ';
            else if (c == 'ם') c = 'מ';
            else if (c == 'ן') c = 'נ';
            else if (c == 'ף') c = 'פ';
            else if (c == 'ץ') c = 'צ';
            if (c >= 'A' && c <= 'Z') c = (char) (c + 32);
            if ((c >= 0x5D0 && c <= 0x5EA) || (c >= '0' && c <= '9') || (c >= 'a' && c <= 'z')) b.append(c);
            else if (b.length() > 0 && b.charAt(b.length() - 1) != ' ') b.append(' ');
        }
        int e = b.length();
        while (e > 0 && b.charAt(e - 1) == ' ') e--;
        b.setLength(e);
        // spelling with or without the extra vav / yod (זנגוויל = זנגויל), as tools/mkmap.py
        StringBuffer o = new StringBuffer(e);
        for (int i = 0; i < e; i++) {
            char c = b.charAt(i);
            if ((c == 'ו' || c == 'י') && o.length() > 0 && o.charAt(o.length() - 1) == c) continue;
            o.append(c);
        }
        return o.toString();
    }

    static boolean isNumber(String w) { return w.length() > 0 && w.charAt(0) >= '0' && w.charAt(0) <= '9'; }

    /** results for the query, nearest first among equals; near = the map centre */
    Vector search(String query, int nearLat, int nearLon) throws IOException {
        index();
        cancel = false;
        String q = norm(query);
        Vector words = new Vector();
        String house = "";
        int i = 0;
        while (i < q.length()) {
            int j = q.indexOf(' ', i);
            if (j < 0) j = q.length();
            String w = q.substring(i, j);
            if (isNumber(w) && house.length() == 0 && words.size() > 0) house = w;
            else words.addElement(w);
            i = j + 1;
        }
        if (words.size() > 1 && (words.elementAt(0).equals("רחוב") || words.elementAt(0).equals("רח"))) words.removeElementAt(0);
        Vector res = new Vector();
        for (int k = words.size(); k >= 1 && res.size() == 0; k--) {
            String name = join(words, 0, k), city = join(words, k, words.size());
            scan(name, city, house, nearLat, nearLon, res);
            if (res.size() == 0 && house.length() > 0) scan(name, city, "", nearLat, nearLon, res);
        }
        return res;
    }

    static String join(Vector v, int a, int b) {
        StringBuffer s = new StringBuffer();
        for (int i = a; i < b; i++) {
            if (s.length() > 0) s.append(' ');
            s.append((String) v.elementAt(i));
        }
        return s.toString();
    }

    void scan(String name, String city, String house, int nearLat, int nearLon, Vector res) throws IOException {
        if (name.length() == 0) return;
        // the first block that may hold the name: the last one whose first key is smaller
        int lo = 0, hi = keys.length - 1, b = 0;
        while (lo <= hi) {
            int m = (lo + hi) >> 1;
            if (keys[m].compareTo(name) < 0) {
                b = m;
                lo = m + 1;
            } else hi = m - 1;
        }
        int seen = 0;
        Hit[] top = new Hit[60];
        int topN = 0;
        boolean done = false;
        for (; b < keys.length && !done && !cancel; b++) {
            int end = b + 1 < offs.length ? offs[b + 1] : f.sLen[5] - (blocks - f.sOff[5]);
            byte[] d = f.read(blocks + offs[b], end - offs[b]);
            int[] p = {0};
            int n = Tile.vint(d, p);
            for (int r = 0; r < n; r++) {
                int kind = d[p[0]] & 255, cat = d[p[0] + 1] & 255;
                p[0] += 2;
                int l = Tile.vint(d, p);
                String nm = Tile.utf8(d, p[0], l);
                p[0] += l;
                l = Tile.vint(d, p);
                String ct = Tile.utf8(d, p[0], l);
                p[0] += l;
                l = Tile.vint(d, p);
                String hs = Tile.utf8(d, p[0], l);
                p[0] += l;
                int la = MapFile.s32(d, p[0]), lo2 = MapFile.s32(d, p[0] + 4);
                p[0] += 8;
                String nn = norm(nm);
                int c = nn.compareTo(name);
                if (c < 0) continue;
                if (!nn.startsWith(name)) {
                    done = true;
                    break;
                }
                if (++seen > 20000) {
                    done = true;
                    break;
                }
                if (house.length() > 0) {
                    if (kind != 3 || !norm(hs).equals(house)) continue;
                } else if (kind == 3) continue;
                if (city.length() > 0) {
                    String nc = norm(ct);
                    if (!nc.startsWith(city) && nc.indexOf(" ".concat(city)) < 0) continue;
                }
                Hit h = new Hit();
                h.kind = kind;
                h.cat = cat;
                h.name = nm;
                h.city = ct;
                h.house = hs;
                h.lat = la;
                h.lon = lo2;
                h.dist = Router.dist(nearLat, nearLon, la, lo2);
                // settlements named by the query (also "תל אביב" for "תל אביב-יפו") first, then exact names, whole
                // words, other prefixes; nearer first within each
                boolean exact = nn.length() == name.length(), word = !exact && nn.charAt(name.length()) == ' ';
                int sc;
                if (kind == 1 && (exact || word)) sc = 0;
                else if (exact) sc = 1000;
                else if (word) sc = 50000;
                else sc = 1000000;
                sc += h.dist / 1000;
                if (kind == 1) sc -= cat <= 1 ? 600 : cat == 2 ? 300 : 100;
                else if (kind == 4) sc += 20;
                h.score = sc;
                if (topN < top.length) top[topN++] = h;
                else if (sc < top[topN - 1].score) top[topN - 1] = h;
                else continue;
                for (int x = topN - 1; x > 0 && top[x].score < top[x - 1].score; x--) {
                    Hit t = top[x];
                    top[x] = top[x - 1];
                    top[x - 1] = t;
                }
            }
        }
        for (int x = 0; x < topN; x++) res.addElement(top[x]);
    }
}
