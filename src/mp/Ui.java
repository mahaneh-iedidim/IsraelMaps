package mp;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.util.Timer;
import java.util.TimerTask;
import java.util.Vector;
import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.Image;
import javax.microedition.rms.RecordStore;

/**
 * מפות ישראל: the map (tiles of israel.map on the memory card, four levels down to buildings and house numbers),
 * search, points of interest nearby, my places and turn-by-turn navigation. The map centre is the cursor: 5 opens
 * the menu of the point under it.
 */
public class Ui extends Canvas implements Runnable {
    Maps app;
    int W = 240, H = 320;
    BFont uR, uB, uS, fTitle;
    int[] pal = new int[36];
    int MAIN, STEEL, GOLD, SUB, PAPER, ROW, SEL, INK, MUTED, LINE, TAB_BG, ACCENT, BORDER;

    // map colours
    static final int LAND = 0xF2EFE9, WATER = 0xAAD3DF, WATER_LINE = 0x7FB2D6, PARK = 0xCDEBB0, FOREST = 0xADD19E,
        INDUSTRY = 0xEBDBE8, CEMETERY = 0xAACBAF, BUILDING = 0xD9D0C9, BUILDING_EDGE = 0xBFB2A6, RAIL = 0x8A8A8A,
        LABEL = 0x222222, HALO = 0xFFFFFF, HOUSE = 0x6E6458, ROUTE = 0x2F6FDB, ROUTE_EDGE = 0x1A4A9E;
    static final int[] ROAD_FILL = {0xE892A2, 0xF9B29C, 0xFCD6A4, 0xF7FABF, 0xFFFFFF, 0xFFFFFF, 0xEDEDED, 0xFFFFFF, 0xB98A4E, 0xE9806E};
    static final int[] ROAD_EDGE = {0xC24E6B, 0xC8775A, 0xB88A4C, 0x9EA05C, 0xB3AAA2, 0xB3AAA2, 0xB3AAA2, 0xBDB5AD, 0xB98A4E, 0xE9806E};
    // fill width per zoom (rows) and road class (columns)
    static final byte[][] ROAD_W = {
        {2, 1, 1, 0, 0, 0, 0, 0, 0, 0},
        {2, 2, 1, 0, 0, 0, 0, 0, 0, 0},
        {2, 2, 1, 1, 0, 0, 0, 0, 0, 0},
        {2, 2, 2, 1, 1, 0, 0, 0, 0, 0},
        {3, 2, 2, 1, 1, 0, 0, 0, 0, 0},
        {3, 3, 2, 2, 1, 1, 1, 0, 0, 0},
        {4, 4, 3, 3, 2, 1, 1, 1, 0, 0},
        {5, 5, 4, 4, 3, 2, 2, 1, 1, 0},
        {6, 6, 5, 5, 4, 3, 3, 1, 1, 0},
        {8, 8, 7, 6, 5, 4, 4, 2, 1, 1},
        {10, 10, 9, 8, 7, 6, 5, 3, 2, 1},
        {13, 13, 12, 11, 10, 9, 7, 4, 2, 2},
        {18, 18, 16, 15, 14, 12, 10, 6, 3, 2}};
    static final int[] UPP = {16000, 8000, 4000, 2000, 1000, 500, 250, 125, 64, 32, 16, 8, 4};
    static final int ZMAX = 12;

    // data
    MapFile mf;
    TileSet[] lv = new TileSet[4];
    TileSet gset, mset;
    Router router;
    Finder finder;
    String url = "";

    // view
    int cLat = 31450000, cLon = 34950000, z = 0, profile;
    int upp, SH, KX, KY, mapH;

    static final int S_START = 0, S_NOFILE = 1, S_MAP = 2;
    int screen = S_START;
    String startText = Ui.L("מחפש את קובץ המפה...", "Looking for the map file...");

    Ui(Maps app) {
        this.app = app;
        setFullScreenMode(true);
        // the English interface (the -EN build has /lang): texts, units and a left-to-right layout
        EN = read("/lang") != null;
        if (EN) {
            PROFILE = new String[] {"Car", "Walk", "Bicycle"};
            PLACE_KIND = new String[] {"City", "City", "Village", "Neighbourhood", "Place"};
            CAT_NAME = new String[] {"Synagogues", "Mikvehs", "Pharmacies", "Hospitals and clinics", "Fuel", "Supermarkets and malls",
                "Food", "Banks and ATMs", "Bus stops", "Train stations", "Parking", "Schools", "Post offices", "Police and fire",
                "Hotels", "Tourism and heritage", "Shops"};
            HELP = HELP_EN;
            startText = "Looking for the map file...";
            Route.RIGHT = new String[] {"Continue straight", "Bear right", "Turn right", "Turn sharp right"};
            Route.LEFT = new String[] {"Continue straight", "Bear left", "Turn left", "Turn sharp left"};
        }
        loadPalette();
        uR = font("ur", 8);
        uB = font("ub", 6);
        uS = font("us", 8);
        fTitle = font("tt", 2);
        loadPrefs();
        gpsApi = hasClass("javax.microedition.location.LocationProvider");
        Thread t = new Thread(new Runnable() {
            public void run() { findFile(); }
        });
        t.start();
        Thread l = new Thread(new Loader());
        l.start();
    }

    static boolean EN;

    static String L(String he, String en) { return EN ? en : he; }

    static boolean hasClass(String n) {
        try {
            Class.forName(n);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    void loadPalette() {
        byte[] d = read("/pal");
        if (d != null && d.length >= 108) for (int i = 0; i < 36; i++) pal[i] = ((d[3 * i] & 255) << 16) | ((d[3 * i + 1] & 255) << 8) | (d[3 * i + 2] & 255);
        MAIN = pal[0];
        STEEL = pal[1];
        GOLD = pal[2];
        SUB = pal[3];
        int o = 8;
        PAPER = pal[o];
        ROW = pal[o + 1];
        SEL = pal[o + 2];
        INK = pal[o + 3];
        MUTED = pal[o + 4];
        LINE = pal[o + 5];
        TAB_BG = pal[o + 6];
        ACCENT = pal[o + 7];
        BORDER = pal[o + 8];
    }

    // ---------- helpers ----------
    static String cat(String a, String b) {
        StringBuffer s = new StringBuffer(a);
        s.append(b);
        return s.toString();
    }

    static String cat3(String a, String b, String c) {
        StringBuffer s = new StringBuffer(a);
        s.append(b);
        s.append(c);
        return s.toString();
    }

    byte[] read(String p) {
        try {
            InputStream in = getClass().getResourceAsStream(p);
            if (in == null) return null;
            ByteArrayOutputStream o = new ByteArrayOutputStream(4096);
            byte[] b = new byte[1024];
            int n;
            while ((n = in.read(b)) > 0) o.write(b, 0, n);
            in.close();
            return o.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    BFont font(String name, int colors) { return new BFont(read(cat("/f/", name)), colors); }

    static int now() { return (int) (System.currentTimeMillis() & 0x3FFFFFFF); }

    /** "350 מ'" / "4.2 ק"מ" */
    static String meters(int m) {
        StringBuffer b = new StringBuffer();
        if (m < 1000) {
            b.append(m < 100 ? m / 10 * 10 : m / 50 * 50);
            b.append(EN ? " m" : " מ'");
        } else if (m < 20000) {
            b.append(m / 1000);
            b.append('.');
            b.append(m / 100 % 10);
            b.append(EN ? " km" : " ק\"מ");
        } else {
            b.append((m + 500) / 1000);
            b.append(EN ? " km" : " ק\"מ");
        }
        return b.toString();
    }

    static String minutes(int s) {
        StringBuffer b = new StringBuffer();
        int m = (s + 59) / 60;
        if (m < 60) {
            b.append(m);
            b.append(EN ? " min" : " דק'");
        } else {
            b.append(m / 60);
            b.append(":");
            if (m % 60 < 10) b.append('0');
            b.append(m % 60);
            b.append(EN ? " h" : " שע'");
        }
        return b.toString();
    }

    // ---------- bidi text ----------
    final Bidi bd = new Bidi();
    private byte[] tlv = new byte[128];
    private int[] tord = new int[128];

    int textW(BFont f, String s) {
        int w = 0;
        for (int i = 0; i < s.length(); i++) w += f.w(s.charAt(i));
        return w;
    }

    void text(Graphics g, BFont f, int color, String s, int x, int y, int anchor) {
        int n = s.length();
        if (n == 0) return;
        char[] t = s.toCharArray();
        for (int i = 0; i < n; i++) if (t[i] == 0xA0) t[i] = ' ';
        for (int i = 1; i < n; i++) {
            char c = t[i], p = t[i - 1];
            if ((c == '\'' || c == '"') && p >= 0x5D0 && p <= 0x5EA) {
                char g2 = c == '\'' ? (char) 0x5F3 : (char) 0x5F4;
                if (f.has(g2)) t[i] = g2;
            }
        }
        if (tlv.length < n) {
            tlv = new byte[n + 32];
            tord = new int[n + 32];
        }
        int para = bd.resolve(t, n, 0, EN ? 0 : 1, tlv);
        int m = bd.reorder(t, tlv, 0, n, para, tord);
        int w = 0;
        for (int i = 0; i < n; i++) w += f.w(t[i]);
        int xl = (anchor & Graphics.RIGHT) != 0 ? x - w : (anchor & Graphics.HCENTER) != 0 ? x - w / 2 : x;
        for (int k = 0; k < m; k++) {
            int i = tord[k];
            char c = t[i];
            if ((tlv[i] & 1) != 0) c = Bidi.mirror(c);
            int cw = f.w(c);
            f.draw(g, c, xl, y, color);
            xl += cw;
        }
    }

    /** text with a light outline, for labels on the map */
    void halo(Graphics g, BFont f, int color, String s, int x, int y, int anchor) {
        text(g, f, HALO, s, x - 1, y, anchor);
        text(g, f, HALO, s, x + 1, y, anchor);
        text(g, f, HALO, s, x, y - 1, anchor);
        text(g, f, HALO, s, x, y + 1, anchor);
        text(g, f, color, s, x, y, anchor);
    }

    String fit(BFont f, String s, int maxW) {
        if (textW(f, s) <= maxW) return s;
        int dots = textW(f, "..");
        int n = s.length();
        while (n > 1 && textW(f, s.substring(0, n)) + dots > maxW) n--;
        return cat(s.substring(0, n).trim(), "..");
    }

    Vector wrap(BFont f, String s, int w) {
        Vector v = new Vector();
        int i = 0, n = s.length();
        while (i < n) {
            int nl = s.indexOf('\n', i);
            if (nl < 0) nl = n;
            String para = s.substring(i, nl);
            if (para.length() == 0) v.addElement("");
            while (para.length() > 0) {
                int cut = para.length();
                while (cut > 0 && textW(f, para.substring(0, cut)) > w) {
                    int sp = para.lastIndexOf(' ', cut - 1);
                    cut = sp > 0 ? sp : cut - 1;
                }
                if (cut <= 0) cut = 1;
                v.addElement(para.substring(0, cut).trim());
                para = para.substring(cut).trim();
            }
            i = nl + 1;
        }
        return v;
    }

    static int mid(BFont f, int y, int h) { return y + (h - f.h) / 2; }

    int barH() { return uR.h + 9; }

    // ---------- preferences and my places ----------
    String[] favName = new String[60];
    int[] favLat = new int[60], favLon = new int[60];
    int favN;

    void loadPrefs() {
        try {
            RecordStore rs = RecordStore.openRecordStore("maps", true);
            if (rs.getNumRecords() >= 1) {
                DataInputStream in = new DataInputStream(new ByteArrayInputStream(rs.getRecord(1)));
                url = in.readUTF();
                cLat = in.readInt();
                cLon = in.readInt();
                z = in.readInt();
                profile = in.readInt();
                if (z < 0 || z > ZMAX) z = 0;
                try {
                    satOn = in.readBoolean();
                    headingUp = in.readBoolean();
                } catch (Exception e) {
                }
                if (profile < 0 || profile > 2) profile = 0;
            }
            if (rs.getNumRecords() >= 2) {
                DataInputStream in = new DataInputStream(new ByteArrayInputStream(rs.getRecord(2)));
                int n = in.readInt();
                for (int i = 0; i < n && i < favName.length; i++) {
                    favName[i] = in.readUTF();
                    favLat[i] = in.readInt();
                    favLon[i] = in.readInt();
                    favN = i + 1;
                }
            }
            rs.closeRecordStore();
        } catch (Exception e) {
        }
    }

    void save() {
        try {
            RecordStore rs = RecordStore.openRecordStore("maps", true);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            DataOutputStream o = new DataOutputStream(bo);
            o.writeUTF(url);
            o.writeInt(cLat);
            o.writeInt(cLon);
            o.writeInt(z);
            o.writeInt(profile);
            o.writeBoolean(satOn);
            o.writeBoolean(headingUp);
            byte[] a = bo.toByteArray();
            bo = new ByteArrayOutputStream();
            o = new DataOutputStream(bo);
            o.writeInt(favN);
            for (int i = 0; i < favN; i++) {
                o.writeUTF(favName[i]);
                o.writeInt(favLat[i]);
                o.writeInt(favLon[i]);
            }
            byte[] b = bo.toByteArray();
            if (rs.getNumRecords() != 2) {
                // a new (or damaged) store: write it afresh
                rs.closeRecordStore();
                try {
                    RecordStore.deleteRecordStore("maps");
                } catch (Exception e) {
                }
                rs = RecordStore.openRecordStore("maps", true);
                rs.addRecord(a, 0, a.length);
                rs.addRecord(b, 0, b.length);
            } else {
                rs.setRecord(1, a, 0, a.length);
                rs.setRecord(2, b, 0, b.length);
            }
            rs.closeRecordStore();
        } catch (Exception e) {
        }
    }

    void close() {
        if (gps != null) gps.stop();
        if (mf != null) mf.close();
    }

    // ---------- finding and opening the data file ----------
    static final String[] PLACES = {"", "Maps/", "maps/", "jvm/", "Others/", "Other/", "Java/", "Downloads/", "Download/"};

    void findFile() {
        if (!MapFile.available()) {
            noFile(Ui.L("הטלפון לא תומך בגישה לקבצים (JSR-75), ולכן אי אפשר לקרוא את קובץ המפה.", "This phone has no file access (JSR-75), so the map file cannot be read."));
            return;
        }
        if (url.length() > 0 && open(url)) {
            findSat();
            return;
        }
        Vector roots = MapFile.roots();
        if (roots.size() == 0) roots.addElement("E:/");
        for (int k = 0; k < PLACES.length; k++) {
            for (int r = 0; r < roots.size(); r++) {
                StringBuffer u = new StringBuffer("file:///");
                u.append((String) roots.elementAt(r));
                u.append(PLACES[k]);
                u.append("israel.map");
                String s = u.toString();
                if (MapFile.exists(s) && open(s)) {
                    findSat();
                    return;
                }
            }
        }
        noFile(null);
    }

    // ---------- the satellite photos (optional file israel-sat.map) ----------
    Sat sat;
    boolean satOn;
    final Image[] satImg = new Image[30];
    final int[] satKey = new int[30], satUse = new int[30];
    final boolean[] satHas = new boolean[30];

    void findSat() {
        Vector c = new Vector();
        int sl = url.lastIndexOf('/');
        if (sl > 0) c.addElement(cat(url.substring(0, sl + 1), "israel-sat.map"));
        Vector roots = MapFile.roots();
        if (roots.size() == 0) roots.addElement("E:/");
        for (int k = 0; k < PLACES.length; k++) {
            for (int r = 0; r < roots.size(); r++) {
                StringBuffer u = new StringBuffer("file:///");
                u.append((String) roots.elementAt(r));
                u.append(PLACES[k]);
                u.append("israel-sat.map");
                c.addElement(u.toString());
            }
        }
        for (int i = 0; i < c.size(); i++) {
            String u = (String) c.elementAt(i);
            if (!MapFile.exists(u)) continue;
            try {
                sat = new Sat(new MapFile(u, false));
                safeRepaint();
                return;
            } catch (Throwable e) {
            }
        }
    }

    /** how many photo tiles to keep: a screen needs up to 12 (each takes about 64 KB as an image) */
    int satMax() {
        int m = (int) (Math.min(64000000L, Runtime.getRuntime().totalMemory()) / 120000);
        return Math.max(12, Math.min(satImg.length, m));
    }

    int satFind(int key) {
        for (int i = 0; i < satImg.length; i++) if (satHas[i] && satKey[i] == key) return i;
        return -1;
    }

    void satPut(int key, Image im) {
        synchronized (cache) {
            int max = satMax(), used = 0, free = -1, old = -1;
            for (int i = 0; i < satImg.length; i++) {
                if (!satHas[i]) {
                    if (free < 0 && i < max) free = i;
                    continue;
                }
                used++;
                if (satUse[i] != frame && (old < 0 || satUse[i] < satUse[old])) old = i;
            }
            int at = free >= 0 && used < max ? free : old;
            if (at < 0) return;
            satImg[at] = im;
            satKey[at] = key;
            satUse[at] = frame;
            satHas[at] = true;
        }
    }

    void satClear() {
        synchronized (cache) {
            for (int i = 0; i < satImg.length; i++) {
                satImg[i] = null;
                satHas[i] = false;
            }
        }
    }

    /** the satellite layer of this zoom, or -1 */
    int satLevel() { return sat != null && satOn ? sat.level(z) : -1; }

    /** draws the photo tiles under the map; false when there are none for this zoom */
    boolean drawSat(Graphics g) {
        int li = satLevel();
        if (li < 0 || rot) return false;
        int bx = W / 2 + ((sat.lonLeft - cLon) * KX >> SH), by = mapH / 2 - ((sat.latTop - cLat) * KY >> SH);
        int T = Sat.T;
        int c0 = Math.max(0, floorDiv(-bx, T)), c1 = Math.min(sat.cols[li] - 1, floorDiv(W - bx, T));
        int r0 = Math.max(0, floorDiv(-by, T)), r1 = Math.min(sat.rows[li] - 1, floorDiv(mapH - by, T));
        synchronized (cache) {
            for (int r = r0; r <= r1; r++) {
                for (int c = c0; c <= c1; c++) {
                    int key = SATK | (li << 24) | (r << 12) | c;
                    int i = satFind(key);
                    if (i >= 0) {
                        satUse[i] = frame;
                        if (satImg[i] != null) g.drawImage(satImg[i], bx + c * T, by + r * T, Graphics.TOP | Graphics.LEFT);
                    } else if (wantN < want.length && ov != O_BUSY) {
                        want[wantN++] = key;
                        loading++;
                    }
                }
            }
            if (wantN > 0) cache.notify();
        }
        return true;
    }

    static int floorDiv(int a, int b) { return a >= 0 ? a / b : -((-a + b - 1) / b); }

    /** photo tile keys: bit 30 set, level << 24, row << 12, column (map tile keys stay below 1 << 30) */
    static final int SATK = 1 << 30;

    void noFile(String why) {
        noFileWhy = why;
        screen = S_NOFILE;
        safeRepaint();
    }

    String noFileWhy;

    boolean open(String u) {
        try {
            MapFile f = new MapFile(u);
            TileSet[] l = new TileSet[4];
            for (int i = 0; i < 4; i++) l[i] = new TileSet(f, i + 1);
            TileSet g = new TileSet(f, 6), m = new TileSet(f, 7);
            synchronized (cache) {
                for (int i = 0; i < cache.length; i++) cache[i] = null;
                if (mf != null) mf.close();
                mf = f;
                lv = l;
                gset = g;
                mset = m;
                router = new Router(g, m);
                router.profile = profile;
                finder = new Finder(f);
            }
            url = u;
            save();
            screen = S_MAP;
            safeRepaint();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    // ---------- tile cache and loader ----------
    final Tile[] cache = new Tile[48];
    int frame;
    final int[] want = new int[64];
    int wantN;
    int loading;   // tiles still missing in the last frame

    Tile cached(int key) {
        for (int i = 0; i < cache.length; i++) if (cache[i] != null && cache[i].key == key) return cache[i];
        return null;
    }

    int budget() {
        int t = (int) Math.min(64000000L, Runtime.getRuntime().totalMemory());
        int b = t / 2;
        if (b > 6000000) b = 6000000;
        if (b < 500000) b = 500000;
        return b;
    }

    final class Loader implements Runnable {
        public void run() {
            while (true) {
                int key = -1;
                synchronized (cache) {
                    while (wantN == 0) {
                        try {
                            cache.wait();
                        } catch (InterruptedException e) {
                        }
                    }
                    key = want[--wantN];
                    if (key >= SATK ? satFind(key) >= 0 : cached(key) != null) key = -1;
                }
                if (key < 0) continue;
                try {
                    if (key >= SATK) {
                        byte[] d = sat.tile((key >> 24) & 63, (key >> 12) & 0xFFF, key & 0xFFF);
                        satPut(key, d == null ? null : Image.createImage(d, 0, d.length));
                        boolean last;
                        synchronized (cache) {
                            last = wantN == 0;
                        }
                        if (last || (now() & 3) == 0) safeRepaint();
                        continue;
                    }
                    int L = key >>> 28, r = (key >> 14) & 0x3FFF, c = key & 0x3FFF;
                    TileSet ts = lv[L];
                    byte[] d = ts.load(r, c);
                    Tile t = new Tile(key, d == null ? EMPTY : d, ts.oLat + r * ts.tile, ts.oLon + c * ts.tile, ts.quant);
                    put(t);
                } catch (OutOfMemoryError e) {
                    synchronized (cache) {
                        for (int i = 0; i < cache.length; i++) if (cache[i] != null && cache[i].use != frame) cache[i] = null;
                        for (int i = 0; i < satImg.length; i++) if (satHas[i] && satUse[i] != frame) {
                            satImg[i] = null;
                            satHas[i] = false;
                        }
                    }
                    System.gc();
                } catch (Throwable e) {
                    if (key >= SATK) satPut(key, null);
                    else put(new Tile(key, EMPTY, 0, 0, 1));
                }
                boolean last;
                synchronized (cache) {
                    last = wantN == 0;
                }
                if (last || (now() & 3) == 0) safeRepaint();
            }
        }
    }

    static final byte[] EMPTY = {0, 0};

    void put(Tile t) {
        synchronized (cache) {
            int total = t.bytes;
            for (int i = 0; i < cache.length; i++) if (cache[i] != null) total += cache[i].bytes;
            int b = budget();
            // evict the least recently drawn tiles while over the budget (never those of the current frame)
            while (true) {
                int v = -1, free = -1;
                for (int i = 0; i < cache.length; i++) {
                    if (cache[i] == null) {
                        if (free < 0) free = i;
                        continue;
                    }
                    if (cache[i].use == frame) continue;
                    if (v < 0 || cache[i].use < cache[v].use) v = i;
                }
                if (free >= 0 && total <= b) {
                    t.use = frame;
                    cache[free] = t;
                    return;
                }
                if (v < 0) {
                    if (free >= 0) {
                        t.use = frame;
                        cache[free] = t;
                    }
                    return;
                }
                total -= cache[v].bytes;
                cache[v] = null;
            }
        }
    }

    void request(int key) {
        synchronized (cache) {
            for (int i = 0; i < wantN; i++) if (want[i] == key) return;
            if (wantN < want.length) want[wantN++] = key;
            cache.notify();
        }
    }

    // ---------- the view ----------
    int level() { return z <= 2 ? 0 : z <= 4 ? 1 : z <= 8 ? 2 : 3; }

    // the view turns with the road while navigating: the way travelled points up, so a turn to the right is
    // drawn to the right. (hdx, hdy) = the direction of travel (east, north); rc / rs = its cosine / sine * 1024.
    boolean headingUp = true, turnView, rot;
    int hdx, hdy, rc = 1024, rs, ax, ay;
    float frc = 1, frs;

    void setView() {
        mapH = H - barH();
        upp = UPP[z];
        SH = z <= 4 ? 20 : 16;
        KY = (1 << SH) / upp;
        KX = (int) ((1 << SH) * 0.8526f / upp);
        rot = headingUp && turnView && nav && route != null && (hdx != 0 || hdy != 0);
        ax = W / 2;
        ay = rot ? mapH * 5 / 8 : mapH / 2;   // the road ahead gets more of the screen
        if (rot) {
            float len = (float) Math.sqrt((float) hdx * hdx + (float) hdy * hdy);
            frc = hdy / len;
            frs = hdx / len;
            rc = (int) (frc * 1024);
            rs = (int) (frs * 1024);
        }
    }

    /** sets the direction of travel from route point a to point b */
    void heading(Route r, int a, int b) {
        if (a == b) return;
        hdx = (int) ((r.lon[b] - r.lon[a]) * 0.8526f);
        hdy = r.lat[b] - r.lat[a];
    }

    int ppx, ppy;   // result of proj()

    /** the screen position of a point */
    void proj(int lat, int lon) {
        int X = (lon - cLon) * KX >> SH, Y = (lat - cLat) * KY >> SH;
        if (rot) {
            int x2 = (X * rc - Y * rs) >> 10, y2 = (X * rs + Y * rc) >> 10;
            X = x2;
            Y = y2;
        }
        ppx = ax + X;
        ppy = ay - Y;
    }

    int mLat, mLon;   // result of mapAt()

    /** the point under a screen position */
    void mapAt(int x, int y) {
        int X = x - ax, Y = ay - y;
        if (rot) {
            int x2 = (X * rc + Y * rs) >> 10, y2 = (-X * rs + Y * rc) >> 10;
            X = x2;
            Y = y2;
        }
        mLon = cLon + (X << SH) / KX;
        mLat = cLat + (Y << SH) / KY;
    }

    int sx(int lon) { return ax + ((lon - cLon) * KX >> SH); }

    int lonAt(int x) { return cLon + ((x - ax) << SH) / KX; }

    int latAt(int y) { return cLat - ((y - ay) << SH) / KY; }

    void clampView() {
        if (cLat < 29300000) cLat = 29300000;
        if (cLat > 33400000) cLat = 33400000;
        if (cLon < 34200000) cLon = 34200000;
        if (cLon > 35900000) cLon = 35900000;
    }

    // decoded feature
    int fType, fSub, fFl, fn, fMinX, fMinY, fMaxX, fMaxY;
    String fName, fRef;
    int[] px = new int[1024], py = new int[1024];
    final int[] pp = new int[1];

    void decode(Tile t, int i) {
        byte[] d = t.d;
        int[] p = pp;
        p[0] = t.off[i];
        fType = d[p[0]] & 255;
        fSub = d[p[0] + 1] & 255;
        fFl = d[p[0] + 2] & 255;
        p[0] += 3;
        fName = (fFl & 1) != 0 ? t.str[Tile.vint(d, p)] : null;
        fRef = (fFl & 2) != 0 ? t.str[Tile.vint(d, p)] : null;
        int np = Tile.vint(d, p);
        if (np > px.length) {
            px = new int[np + 256];
            py = new int[np + 256];
        }
        int qa = 0, qo = 0, q = t.quant, w2 = ax, h2 = ay;
        boolean turn = rot;
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
        for (int k = 0; k < np; k++) {
            qa += Tile.zz(d, p);
            qo += Tile.zz(d, p);
            int x = (t.bo + qo * q - cLon) * KX >> SH;
            int y = (t.bl + qa * q - cLat) * KY >> SH;
            if (turn) {
                int x2 = (x * rc - y * rs) >> 10;
                y = (x * rs + y * rc) >> 10;
                x = x2;
            }
            x = w2 + x;
            y = h2 - y;
            px[k] = x;
            py[k] = y;
            if (x < minX) minX = x;
            if (x > maxX) maxX = x;
            if (y < minY) minY = y;
            if (y > maxY) maxY = y;
        }
        fn = np;
        fMinX = minX;
        fMinY = minY;
        fMaxX = maxX;
        fMaxY = maxY;
    }

    boolean onScreen(int m) { return fMaxX >= -m && fMinX <= W + m && fMaxY >= -m && fMinY <= mapH + m; }

    Tile[] vis = new Tile[40];
    int visN;

    void paintMap(Graphics g) {
        setView();
        g.setClip(0, 0, W, mapH);
        g.setColor(LAND);
        g.fillRect(0, 0, W, mapH);
        int L = level();
        TileSet ts = lv[L];
        int xw = !rot && cLon - ts.oLon < W * upp ? sx(ts.oLon) : -1;   // (far east of the edge: no overflow)
        if (xw > 0) {
            g.setColor(WATER);
            g.fillRect(0, 0, Math.min(xw, W), mapH);
        }
        // the map area under the screen (its corners, which may be turned)
        int laMin = Integer.MAX_VALUE, laMax = Integer.MIN_VALUE, loMin = Integer.MAX_VALUE, loMax = Integer.MIN_VALUE;
        for (int k = 0; k < 4; k++) {
            mapAt(k % 2 == 0 ? -30 : W + 30, k < 2 ? -30 : mapH + 30);
            if (mLat < laMin) laMin = mLat;
            if (mLat > laMax) laMax = mLat;
            if (mLon < loMin) loMin = mLon;
            if (mLon > loMax) loMax = mLon;
        }
        int r0 = ts.row(laMin), r1 = ts.row(laMax), c0 = ts.col(loMin), c1 = ts.col(loMax);
        if (r0 < 0) r0 = 0;
        if (c0 < 0) c0 = 0;
        if (r1 >= ts.rows) r1 = ts.rows - 1;
        if (c1 >= ts.cols) c1 = ts.cols - 1;
        visN = 0;
        int missing = 0;
        synchronized (cache) {
            frame++;
            wantN = 0;
            for (int r = r0; r <= r1; r++) {
                for (int c = c0; c <= c1; c++) {
                    int key = (L << 28) | (r << 14) | c;
                    Tile t = cached(key);
                    if (t != null) {
                        t.use = frame;
                        if (visN < vis.length) vis[visN++] = t;
                    } else {
                        // nearest the centre loaded first (taken from the end)
                        missing++;
                        if (wantN < want.length && ov != O_BUSY) want[wantN++] = key;
                    }
                }
            }
            // centre tiles last in the list = first to load
            int cr = ts.row(cLat), cc = ts.col(cLon);
            for (int i = 0; i < wantN; i++) for (int j = i + 1; j < wantN; j++) {
                if (tdist(want[i], cr, cc) < tdist(want[j], cr, cc)) {
                    int x = want[i];
                    want[i] = want[j];
                    want[j] = x;
                }
            }
            if (wantN > 0) cache.notify();
        }
        loading = missing;
        // layers: the photos (satellite mode) or the land cover
        boolean photo = drawSat(g);
        if (!photo) {
            drawAreas(g);
            if (z >= 9) drawBuildings(g);
        }
        drawLines(g);
        for (int cls = 9; cls >= 0; cls--) drawRoads(g, cls, true);
        for (int cls = 9; cls >= 0; cls--) drawRoads(g, cls, false);
        labN = 0;
        drawRefs(g);
        drawPlaces(g);
        if (z >= 9) drawRoadNames(g);
        if (z >= 7) drawPois(g);
        if (z >= 11) drawHouses(g);
        drawRoute(g);
        drawMarks(g);
        if (photo) text(g, uS, 0xFFFFFF, "Copernicus Sentinel-2 2026", 4, nav && route != null ? uB.h + uS.h + 16 : 2, Graphics.TOP | Graphics.LEFT);
        g.setClip(0, 0, W, H);
        drawOverlayInfo(g);
    }

    static int tdist(int key, int r, int c) {
        int dr = ((key >> 14) & 0x3FFF) - r, dc = (key & 0x3FFF) - c;
        return dr * dr + dc * dc;
    }

    static final int[] AREA_ORDER = {25, 26, 21, 22, 20};
    static final int[] AREA_COL = {INDUSTRY, CEMETERY, PARK, FOREST, WATER};

    void drawAreas(Graphics g) {
        for (int a = 0; a < AREA_ORDER.length; a++) {
            int ty = AREA_ORDER[a];
            g.setColor(AREA_COL[a]);
            for (int v = 0; v < visN; v++) {
                Tile t = vis[v];
                for (int i = 0; i < t.n; i++) {
                    if (t.type[i] != ty) continue;
                    decode(t, i);
                    if (!onScreen(0)) continue;
                    fillPoly(g);
                }
            }
        }
    }

    void drawBuildings(Graphics g) {
        for (int v = 0; v < visN; v++) {
            Tile t = vis[v];
            for (int i = 0; i < t.n; i++) {
                if (t.type[i] != 23) continue;
                decode(t, i);
                if (!onScreen(0)) continue;
                g.setColor(BUILDING);
                fillPoly(g);
                if (z >= 10) {
                    g.setColor(BUILDING_EDGE);
                    for (int k = 1; k < fn; k++) g.drawLine(px[k - 1], py[k - 1], px[k], py[k]);
                }
            }
        }
    }

    void drawLines(Graphics g) {
        for (int v = 0; v < visN; v++) {
            Tile t = vis[v];
            for (int i = 0; i < t.n; i++) {
                int ty = t.type[i];
                if (ty < 11 || ty > 13) continue;
                decode(t, i);
                if (!onScreen(4)) continue;
                int w = 1;
                if (ty == 11) {
                    g.setColor(RAIL);
                    w = z >= 9 ? 2 : 1;
                } else {
                    g.setColor(WATER_LINE);
                    if (ty == 12 && fSub == 0) w = z >= 10 ? 3 : z >= 7 ? 2 : 1;
                }
                polyline(g, w);
            }
        }
    }

    void drawRoads(Graphics g, int cls, boolean casing) {
        int w = ROAD_W[z][cls];
        if (w == 0) return;
        if (casing && w < 3) return;
        int col;
        if (casing) col = ROAD_EDGE[cls];
        else if (w < 3 && ROAD_FILL[cls] == 0xFFFFFF) col = 0xA9A39C;
        else if (w < 3 && cls == 6) col = 0xC4BEB8;
        else col = ROAD_FILL[cls];
        int ty = cls + 1;
        g.setColor(col);
        for (int v = 0; v < visN; v++) {
            Tile t = vis[v];
            for (int i = 0; i < t.n; i++) {
                if (t.type[i] != ty) continue;
                decode(t, i);
                if (!onScreen(w + 2)) continue;
                if (casing) polyline(g, w + 2);
                else if (cls >= 8 && z >= 10) dashed(g, w);
                else polyline(g, w);
            }
        }
    }

    void polyline(Graphics g, int w) {
        for (int k = 1; k < fn; k++) {
            int x1 = px[k - 1], y1 = py[k - 1], x2 = px[k], y2 = py[k];
            if ((x1 < -w && x2 < -w) || (x1 > W + w && x2 > W + w) || (y1 < -w && y2 < -w) || (y1 > mapH + w && y2 > mapH + w)) continue;
            thick(g, x1, y1, x2, y2, w, k == 1);
        }
    }

    void dashed(Graphics g, int w) {
        int run = 0;
        for (int k = 1; k < fn; k++) {
            int x1 = px[k - 1], y1 = py[k - 1], x2 = px[k], y2 = py[k];
            int dx = x2 - x1, dy = y2 - y1;
            int len = (int) Math.sqrt((float) (dx * dx + dy * dy));
            if (len == 0) continue;
            if ((x1 < 0 && x2 < 0) || (x1 > W && x2 > W) || (y1 < 0 && y2 < 0) || (y1 > mapH && y2 > mapH)) {
                run += len;
                continue;
            }
            for (int s = 0; s < len; s += 2) {
                if (((run + s) / 4 & 1) == 0) {
                    int e = Math.min(len, s + 2);
                    thick(g, x1 + dx * s / len, y1 + dy * s / len, x1 + dx * e / len, y1 + dy * e / len, w, false);
                }
            }
            run += len;
        }
    }

    /** a line w pixels wide (two triangles, round joints) */
    void thick(Graphics g, int x1, int y1, int x2, int y2, int w, boolean cap) {
        if (w <= 1) {
            g.drawLine(x1, y1, x2, y2);
            return;
        }
        int dx = x2 - x1, dy = y2 - y1;
        if (dx > 30000 || dx < -30000 || dy > 30000 || dy < -30000) {
            g.drawLine(x1, y1, x2, y2);
            return;
        }
        float len = (float) Math.sqrt((float) dx * dx + (float) dy * dy);
        if (len < 0.5f) return;
        float k = w / (2 * len);
        int ox = (int) (-dy * k + (dy > 0 ? -0.5f : 0.5f)), oy = (int) (dx * k + (dx > 0 ? 0.5f : -0.5f));
        if (w == 2) {
            g.drawLine(x1, y1, x2, y2);
            g.drawLine(x1 + (ox != 0 ? (ox > 0 ? 1 : -1) : 0), y1 + (oy != 0 ? (oy > 0 ? 1 : -1) : 0),
                x2 + (ox != 0 ? (ox > 0 ? 1 : -1) : 0), y2 + (oy != 0 ? (oy > 0 ? 1 : -1) : 0));
            return;
        }
        g.fillTriangle(x1 + ox, y1 + oy, x2 + ox, y2 + oy, x2 - ox, y2 - oy);
        g.fillTriangle(x1 + ox, y1 + oy, x2 - ox, y2 - oy, x1 - ox, y1 - oy);
        int r = w / 2;
        g.fillArc(x2 - r, y2 - r, w, w, 0, 360);
        if (cap) g.fillArc(x1 - r, y1 - r, w, w, 0, 360);
    }

    int[] xs = new int[64];

    /** scanline fill of the decoded polygon */
    void fillPoly(Graphics g) {
        int n = fn;
        if (n < 3) return;
        if (fMaxX - fMinX <= 1 && fMaxY - fMinY <= 1) {
            g.fillRect(fMinX, fMinY, 1, 1);
            return;
        }
        // an axis-aligned rectangle (cells of big areas)
        if (n == 5 && px[0] == px[4] && py[0] == py[4] && rect()) {
            int x0 = Math.max(fMinX, 0), y0 = Math.max(fMinY, 0), x1 = Math.min(fMaxX, W), y1 = Math.min(fMaxY, mapH);
            if (x1 > x0 && y1 > y0) g.fillRect(x0, y0, x1 - x0 + 1, y1 - y0 + 1);
            return;
        }
        int y0 = Math.max(fMinY, 0), y1 = Math.min(fMaxY, mapH - 1);
        boolean closed = px[0] == px[n - 1] && py[0] == py[n - 1];
        int m = closed ? n - 1 : n;
        if (xs.length < m + 2) xs = new int[m + 8];
        for (int y = y0; y <= y1; y++) {
            float yc = y + 0.5f;
            int c = 0;
            for (int i = 0; i < m; i++) {
                int j = i + 1 == m ? 0 : i + 1;
                int ya = py[i], yb = py[j];
                if ((ya <= yc) == (yb <= yc)) continue;
                xs[c++] = px[i] + (int) ((yc - ya) * (px[j] - px[i]) / (yb - ya));
            }
            for (int a = 1; a < c; a++) {
                int v = xs[a], b = a - 1;
                while (b >= 0 && xs[b] > v) {
                    xs[b + 1] = xs[b];
                    b--;
                }
                xs[b + 1] = v;
            }
            for (int a = 0; a + 1 < c; a += 2) {
                int xa = Math.max(xs[a], 0), xb = Math.min(xs[a + 1], W);
                if (xb >= xa) g.fillRect(xa, y, xb - xa + 1, 1);
            }
        }
    }

    boolean rect() {
        for (int i = 0; i < 4; i++) {
            int j = i + 1;
            if (px[i] != px[j] && py[i] != py[j]) return false;
        }
        return true;
    }

    // ---------- labels ----------
    int[] labX = new int[96], labY = new int[96], labW = new int[96], labH = new int[96];
    int labN;

    /** reserves the box (x, y, w, h) for a label; false when it overlaps one already drawn */
    boolean room(int x, int y, int w, int h) {
        if (x < 0 || y < 0 || x + w > W || y + h > mapH) return false;
        for (int i = 0; i < labN; i++) {
            if (x < labX[i] + labW[i] && labX[i] < x + w && y < labY[i] + labH[i] && labY[i] < y + h) return false;
        }
        if (labN < labX.length) {
            labX[labN] = x;
            labY[labN] = y;
            labW[labN] = w;
            labH[labN] = h;
            labN++;
        }
        return true;
    }

    void drawPlaces(Graphics g) {
        for (int pass = 0; pass <= 4; pass++) {
            for (int v = 0; v < visN; v++) {
                Tile t = vis[v];
                for (int i = 0; i < t.n; i++) {
                    if (t.type[i] != 30) continue;
                    if ((t.d[t.off[i] + 1] & 255) != pass) continue;
                    decode(t, i);
                    if (fName == null || !onScreen(0)) continue;
                    int sub = fSub;
                    if (sub == 2 && z < 5) continue;
                    if (sub == 3 && z < 8) continue;
                    if (sub == 4 && z < 10) continue;
                    if (sub <= 1 && z >= 12) continue;
                    BFont f = sub <= 1 ? uB : sub == 2 ? uR : uS;
                    int col = sub <= 2 ? LABEL : 0x5A5048;
                    int x = px[0], y = py[0];
                    int tw = textW(f, fName);
                    boolean dot = z <= 6 && sub <= 2;
                    int ly = dot ? y - f.h - 2 : y - f.h / 2;
                    if (!room(x - tw / 2 - 2, ly - 1, tw + 4, f.h + 2)) continue;
                    if (dot) {
                        g.setColor(0x3A3A3A);
                        g.fillArc(x - 3, y - 3, 6, 6, 0, 360);
                        g.setColor(HALO);
                        g.fillArc(x - 1, y - 1, 2, 2, 0, 360);
                    }
                    halo(g, f, col, fName, x, ly, Graphics.TOP | Graphics.HCENTER);
                }
            }
        }
    }

    /** road numbers in small shields (main roads, middle zooms) */
    void drawRefs(Graphics g) {
        if (z < 2 || z > 10) return;
        for (int v = 0; v < visN; v++) {
            Tile t = vis[v];
            for (int i = 0; i < t.n; i++) {
                int ty = t.type[i];
                if (ty < 1 || ty > (z >= 6 ? 4 : 3)) continue;
                if ((t.d[t.off[i] + 2] & 2) == 0) continue;
                decode(t, i);
                if (fRef == null || !onScreen(0)) continue;
                int k = longest();
                if (k < 0 || segLen < 30) continue;
                int x = midX, y = midY;
                String r = fRef;
                int sc = r.indexOf(';');
                if (sc > 0) r = r.substring(0, sc);
                int tw = textW(uS, r) + 6, th = uS.h + 2;
                if (!room(x - tw / 2, y - th / 2, tw, th)) continue;
                g.setColor(ty <= 2 ? 0x2B5C9E : 0xFFFFFF);
                g.fillRect(x - tw / 2, y - th / 2, tw, th);
                g.setColor(ty <= 2 ? 0xFFFFFF : 0x777777);
                g.drawRect(x - tw / 2, y - th / 2, tw - 1, th - 1);
                text(g, uS, ty <= 2 ? 0xFFFFFF : LABEL, r, x, y - th / 2 + 1, Graphics.TOP | Graphics.HCENTER);
            }
        }
    }

    int segLen;

    /** the longest on-screen segment of the decoded feature (index of its end point), its length in segLen */
    int longest() {
        int best = -1, bl = 0, bx = 0, by = 0;
        for (int k = 1; k < fn; k++) {
            // the part of the segment inside the screen
            if (!clip(px[k - 1], py[k - 1], px[k], py[k], 6, 6, W - 6, mapH - 6)) continue;
            int dx = cx2 - cx1, dy = cy2 - cy1, l = dx * dx + dy * dy;
            if (l > bl) {
                bl = l;
                best = k;
                bx = (cx1 + cx2) / 2;
                by = (cy1 + cy2) / 2;
            }
        }
        segLen = (int) Math.sqrt((float) bl);
        midX = bx;
        midY = by;
        return best;
    }

    int midX, midY;

    String[] rnS = new String[48];
    int[] rnX = new int[48], rnY = new int[48];
    int rnN;

    void drawRoadNames(Graphics g) {
        rnN = 0;
        for (int cls = 0; cls <= 7; cls++) {
            if (cls >= 6 && z < 11) break;
            if (cls >= 5 && z < 10) break;
            int ty = cls + 1;
            for (int v = 0; v < visN; v++) {
                Tile t = vis[v];
                for (int i = 0; i < t.n; i++) {
                    if (t.type[i] != ty || (t.d[t.off[i] + 2] & 1) == 0) continue;
                    decode(t, i);
                    if (fName == null || !onScreen(0)) continue;
                    int tw = textW(uS, fName);
                    int k = longest();
                    if (k < 0 || segLen < tw + 8) continue;
                    int x = midX, y = midY - uS.h / 2;
                    // one label per street in a neighbourhood
                    boolean dup = false;
                    for (int q = 0; q < rnN && !dup; q++) {
                        int dx = rnX[q] - x, dy = rnY[q] - y;
                        if (dx * dx + dy * dy < 110 * 110 && rnS[q].equals(fName)) dup = true;
                    }
                    if (dup || !room(x - tw / 2 - 1, y, tw + 2, uS.h)) continue;
                    if (rnN < rnS.length) {
                        rnS[rnN] = fName;
                        rnX[rnN] = x;
                        rnY[rnN] = y;
                        rnN++;
                    }
                    halo(g, uS, 0x3C3530, fName, x, y, Graphics.TOP | Graphics.HCENTER);
                }
            }
        }
    }

    static int poiColor(int c) {
        switch (c) {
            case 1: case 16: return 0x8B5A2B;
            case 2: case 21: case 3: return 0xD03A3A;
            case 6: case 7: case 14: return 0x2F6FB0;
            case 5: return 0x6A4FA8;
            case 9: return 0xD9771C;
            case 8: case 22: return 0xA04CA0;
            case 10: return 0x2E8B57;
            case 4: case 20: return 0xA88418;
            case 18: return 0x8A6A2A;
            default: return 0x6B6B6B;
        }
    }

    void drawPois(Graphics g) {
        int r = z >= 11 ? 5 : 4;
        for (int v = 0; v < visN; v++) {
            Tile t = vis[v];
            for (int i = 0; i < t.n; i++) {
                if (t.type[i] != 31) continue;
                decode(t, i);
                if (!onScreen(0)) continue;
                int c = fSub;
                if (z < 10 && c != 2 && c != 7 && c != 20) continue;
                if (z < 11 && (c == 6 || c == 14 || c == 22 || c == 17 || c == 24)) continue;
                int x = px[0], y = py[0];
                String nm = z >= 11 ? fName : null;
                int tw = nm != null ? textW(uS, nm) + 3 : 0;
                if (!room(x - r - tw, y - r, 2 * r + tw, 2 * r)) {
                    if (nm == null || !room(x - r, y - r, 2 * r, 2 * r)) continue;
                    nm = null;
                }
                g.setColor(HALO);
                g.fillArc(x - r - 1, y - r - 1, 2 * r + 2, 2 * r + 2, 0, 360);
                g.setColor(poiColor(c));
                g.fillArc(x - r, y - r, 2 * r, 2 * r, 0, 360);
                g.setColor(HALO);
                g.fillRect(x - 1, y - 1, 2, 2);
                if (nm != null) halo(g, uS, poiColor(c), nm, x - r - 2, y - uS.h / 2, Graphics.TOP | Graphics.RIGHT);
            }
        }
    }

    void drawHouses(Graphics g) {
        for (int v = 0; v < visN; v++) {
            Tile t = vis[v];
            for (int i = 0; i < t.n; i++) {
                if (t.type[i] != 32) continue;
                decode(t, i);
                if (fName == null || !onScreen(0)) continue;
                int tw = textW(uS, fName);
                int x = px[0], y = py[0] - uS.h / 2;
                if (!room(x - tw / 2, y, tw, uS.h)) continue;
                text(g, uS, HOUSE, fName, x, y, Graphics.TOP | Graphics.HCENTER);
            }
        }
    }

    // ---------- route, marks ----------
    Route route;
    int step;            // the instruction shown
    boolean nav;         // a route is active
    int dstLat, dstLon, orgLat, orgLon;
    boolean dstSet, orgSet;
    String dstName = "";
    int pinLat, pinLon;
    String pinName;
    float fkx, fky;

    void drawRoute(Graphics g) {
        Route r = route;
        if (r == null || r.n < 2) return;
        fkx = (float) KX / (1 << SH);
        fky = (float) KY / (1 << SH);
        int w = Math.max(4, ROAD_W[z][4] + 2);
        if (w > 9) w = 9;
        g.setColor(ROUTE_EDGE);
        routeLine(g, r, w + 2);
        g.setColor(ROUTE);
        routeLine(g, r, w);
    }

    void routeLine(Graphics g, Route r, int w) {
        float cx = ax, cy = ay;
        float X = (r.lon[0] - cLon) * fkx, Y = (r.lat[0] - cLat) * fky;
        float x1 = cx + X * frc - Y * frs, y1 = cy - (X * frs + Y * frc);
        if (!rot) {
            x1 = cx + X;
            y1 = cy - Y;
        }
        for (int i = 1; i < r.n; i++) {
            X = (r.lon[i] - cLon) * fkx;
            Y = (r.lat[i] - cLat) * fky;
            float x2 = rot ? cx + X * frc - Y * frs : cx + X, y2 = rot ? cy - (X * frs + Y * frc) : cy - Y;
            if (clip(x1, y1, x2, y2)) thick(g, cx1, cy1, cx2, cy2, w, i == 1);
            x1 = x2;
            y1 = y2;
        }
    }

    int cx1, cy1, cx2, cy2;

    /** Liang-Barsky clip of a segment to the map area (with a margin); the result in cx1..cy2 */
    boolean clip(float x1, float y1, float x2, float y2) { return clip(x1, y1, x2, y2, -40, -40, W + 40, mapH + 40); }

    final float[] pq = new float[8];

    boolean clip(float x1, float y1, float x2, float y2, float xlo, float ylo, float xhi, float yhi) {
        if ((x1 < xlo && x2 < xlo) || (x1 > xhi && x2 > xhi) || (y1 < ylo && y2 < ylo) || (y1 > yhi && y2 > yhi)) return false;
        float t0 = 0, t1 = 1, dx = x2 - x1, dy = y2 - y1;
        pq[0] = -dx;
        pq[1] = x1 - xlo;
        pq[2] = dx;
        pq[3] = xhi - x1;
        pq[4] = -dy;
        pq[5] = y1 - ylo;
        pq[6] = dy;
        pq[7] = yhi - y1;
        for (int k = 0; k < 8; k += 2) {
            float p = pq[k], q = pq[k + 1];
            if (p == 0) {
                if (q < 0) return false;
                continue;
            }
            float t = q / p;
            if (p < 0) {
                if (t > t1) return false;
                if (t > t0) t0 = t;
            } else {
                if (t < t0) return false;
                if (t < t1) t1 = t;
            }
        }
        cx1 = (int) (x1 + t0 * dx);
        cy1 = (int) (y1 + t0 * dy);
        cx2 = (int) (x1 + t1 * dx);
        cy2 = (int) (y1 + t1 * dy);
        return true;
    }

    boolean visible(int lat, int lon, int m) {
        int dl = lat - cLat, dn = lon - cLon, lim = 0x40000000 / Math.max(KX, KY);
        if (dl > lim || dl < -lim || dn > lim || dn < -lim) return false;
        proj(lat, lon);
        return ppx >= -m && ppx <= W + m && ppy >= -m && ppy <= mapH + m;
    }

    void pin(Graphics g, int lat, int lon, int col) {
        if (!visible(lat, lon, 20)) return;
        int x = ppx, y = ppy;
        g.setColor(0x333333);
        g.fillTriangle(x - 5, y - 11, x + 5, y - 11, x, y);
        g.fillArc(x - 7, y - 20, 14, 14, 0, 360);
        g.setColor(col);
        g.fillTriangle(x - 4, y - 11, x + 4, y - 11, x, y - 1);
        g.fillArc(x - 6, y - 19, 12, 12, 0, 360);
        g.setColor(HALO);
        g.fillArc(x - 2, y - 15, 4, 4, 0, 360);
    }

    void dot(Graphics g, int lat, int lon, int col) {
        if (!visible(lat, lon, 10)) return;
        int x = ppx, y = ppy;
        g.setColor(HALO);
        g.fillArc(x - 7, y - 7, 14, 14, 0, 360);
        g.setColor(col);
        g.fillArc(x - 5, y - 5, 10, 10, 0, 360);
    }

    void drawMarks(Graphics g) {
        if (orgSet) dot(g, orgLat, orgLon, 0x2E9E4F);
        if (dstSet) pin(g, dstLat, dstLon, 0xD8343A);
        if (pinName != null && !(dstSet && pinLat == dstLat && pinLon == dstLon)) pin(g, pinLat, pinLon, 0xE07B1F);
        for (int i = 0; i < favN; i++) {
            if (z < 8 || !visible(favLat[i], favLon[i], 8)) continue;
            int x = ppx, y = ppy;
            g.setColor(HALO);
            g.fillRect(x - 5, y - 5, 11, 11);
            g.setColor(0xC79A1E);
            g.fillRect(x - 4, y - 4, 9, 9);
            g.setColor(HALO);
            g.fillRect(x - 1, y - 2, 3, 5);
            g.fillRect(x - 2, y - 1, 5, 3);
        }
        if (gpsOk) {
            if (visible(gLat, gLon, 30)) {
                int x = ppx, y = ppy;
                int ar = gAcc * KY / (1 << SH) * 9;
                if (ar > 6 && ar < 200) {
                    g.setColor(0x8FB4EE);
                    g.drawArc(x - ar, y - ar, 2 * ar, 2 * ar, 0, 360);
                }
                g.setColor(HALO);
                g.fillArc(x - 8, y - 8, 16, 16, 0, 360);
                g.setColor(0x1E6BE0);
                g.fillArc(x - 6, y - 6, 12, 12, 0, 360);
            }
        }
        if (rot) compass(g);
        if (!follow) {
            int x = ax, y = ay;
            g.setColor(HALO);
            g.fillRect(x - 9, y - 2, 19, 5);
            g.fillRect(x - 2, y - 9, 5, 19);
            g.setColor(0x333333);
            g.fillRect(x - 8, y - 1, 6, 3);
            g.fillRect(x + 3, y - 1, 6, 3);
            g.fillRect(x - 1, y - 8, 3, 6);
            g.fillRect(x - 1, y + 3, 3, 6);
        }
    }

    /** while the map is turned: a small compass with its needle to the north */
    void compass(Graphics g) {
        int cx = W - 18, cy = (nav && route != null ? uB.h + uS.h + 14 : 0) + 20;
        g.setColor(HALO);
        g.fillArc(cx - 12, cy - 12, 24, 24, 0, 360);
        g.setColor(0x888888);
        g.drawArc(cx - 12, cy - 12, 24, 24, 0, 360);
        // north on the screen: (-sin, -cos); the needle and its tail
        int nx = -rs * 10 >> 10, ny = -rc * 10 >> 10, wx = -ny * 3 / 10, wy = nx * 3 / 10;
        g.setColor(0xD8343A);
        g.fillTriangle(cx + nx, cy + ny, cx + wx, cy + wy, cx - wx, cy - wy);
        g.setColor(0x555555);
        g.fillTriangle(cx - nx, cy - ny, cx + wx, cy + wy, cx - wx, cy - wy);
    }

    // ---------- the information over the map ----------
    String toast;
    int toastUntil;
    Timer timer = new Timer();

    void showToast(String s) {
        toast = s;
        toastUntil = now() + 1600;
        timer.schedule(new TimerTask() {
            public void run() { safeRepaint(); }
        }, 1700);
    }

    static String[] PROFILE = {"רכב", "הליכה", "אופניים"};

    void drawOverlayInfo(Graphics g) {
        int top = 0;
        if (nav && route != null) top = drawHud(g);
        // scale bar
        int m = 0, px1 = 0;
        int[] nice = {5, 10, 20, 50, 100, 200, 500, 1000, 2000, 5000, 10000, 20000, 50000, 100000};
        float mpp = upp * 0.11132f;
        for (int i = 0; i < nice.length; i++) {
            int p = (int) (nice[i] / mpp);
            if (p > W / 4) break;
            m = nice[i];
            px1 = p;
        }
        if (m > 0) {
            int x = 8, y = mapH - 8;
            g.setColor(HALO);
            g.fillRect(x - 1, y - 4, px1 + 3, 6);
            g.setColor(0x333333);
            g.fillRect(x, y, px1 + 1, 2);
            g.fillRect(x, y - 3, 1, 4);
            g.fillRect(x + px1, y - 3, 1, 4);
            halo(g, uS, 0x333333, meters(m), x + px1 + 4, y - uS.h + 2, Graphics.TOP | Graphics.LEFT);
        }
        if (loading > 0) {
            String s = Ui.L("טוען...", "Loading...");
            int tw = textW(uS, s) + 10;
            g.setColor(MAIN);
            g.fillRect(W - tw - 4, top + 4, tw, uS.h + 4);
            text(g, uS, GOLD, s, W - 9 - (tw - 10) / 2, top + 6, Graphics.TOP | Graphics.HCENTER);
        }
        if (toast != null && now() < toastUntil) {
            int tw = textW(uR, toast) + 16, th = uR.h + 10;
            int y = top + 30;
            g.setColor(MAIN);
            g.fillRect((W - tw) / 2, y, tw, th);
            g.setColor(GOLD);
            g.drawRect((W - tw) / 2, y, tw - 1, th - 1);
            text(g, uR, 0xFFFFFF, toast, W / 2, y + 5, Graphics.TOP | Graphics.HCENTER);
        }
        String left = Ui.L("תפריט", "Menu"), right = nav ? Ui.L("סיום", "End") : Ui.L("יציאה", "Exit");
        softBar(g, left, right, PROFILE[profile]);
    }

    /** the instruction panel of navigation; returns its height */
    int drawHud(Graphics g) {
        Route r = route;
        int s = step < r.stepN ? step : r.stepN - 1;
        int h = uB.h + uS.h + 14;
        g.setColor(MAIN);
        g.fillRect(0, 0, W, h);
        g.setColor(GOLD);
        g.fillRect(0, h - 1, W, 1);
        arrow(g, r.stepTurn[s], EN ? 24 : W - 24, h / 2, 0xFFFFFF);
        int tw = W - 52;
        text(g, uB, 0xFFFFFF, fit(uB, r.stepText[s], tw), EN ? 46 : W - 46, 5, Graphics.TOP | (EN ? Graphics.LEFT : Graphics.RIGHT));
        // distance to the instruction (from the GPS fix or the previous one) and what remains
        int from = gpsOk && follow ? gIdx : (s > 0 ? r.stepPt[s - 1] : 0);
        int toStep = r.cum[r.stepPt[s]] - r.cum[from];
        int rest = r.length - r.cum[from];
        StringBuffer b = new StringBuffer();
        if (s > 0 && r.stepTurn[s] != 11) {
            b.append(Ui.L("בעוד ", "In "));
            b.append(meters(toStep));
            b.append(" · ");
        }
        b.append(Ui.L("נותרו ", "Remaining "));
        b.append(meters(rest));
        b.append(" · ");
        b.append(minutes(r.length == 0 ? 0 : r.seconds * rest / Math.max(1, r.length)));
        StringBuffer k = new StringBuffer();
        k.append(s + 1);
        k.append('/');
        k.append(r.stepN);
        int kw = textW(uS, k.toString());
        text(g, uS, GOLD, k.toString(), EN ? W - 6 : 6, uB.h + 8, Graphics.TOP | (EN ? Graphics.RIGHT : Graphics.LEFT));
        text(g, uS, SUB, fit(uS, b.toString(), tw - kw - 8), EN ? 46 : W - 46, uB.h + 8, Graphics.TOP | (EN ? Graphics.LEFT : Graphics.RIGHT));
        return h;
    }

    /** a turn arrow centred at (x, y) */
    void arrow(Graphics g, int turn, int x, int y, int col) {
        g.setColor(col);
        if (turn == 11) {   // arrive: a flag
            g.fillRect(x - 6, y - 10, 2, 20);
            g.fillTriangle(x - 4, y - 10, x + 8, y - 5, x - 4, y);
            return;
        }
        if (turn == 12) {   // roundabout
            g.drawArc(x - 8, y - 8, 16, 16, 0, 360);
            g.drawArc(x - 7, y - 7, 14, 14, 0, 360);
            g.fillTriangle(x + 4, y - 12, x + 12, y - 8, x + 4, y - 3);
            return;
        }
        int a = turn == 10 ? 0 : turn;
        int dir = a > 0 ? 1 : -1, k = a < 0 ? -a : a;
        g.fillRect(x - 2, y, 4, 11);
        if (k == 0) {
            g.fillRect(x - 2, y - 6, 4, 8);
            g.fillTriangle(x - 7, y - 5, x + 7, y - 5, x, y - 13);
        } else if (k == 1) {
            g.fillTriangle(x - 2, y + 1, x + 2, y + 1, x + 6 * dir, y - 6);
            g.fillTriangle(x + 2 * dir, y + 2, x + 6 * dir, y - 6, x + 9 * dir, y - 3);
            g.fillTriangle(x + 3 * dir, y - 11, x + 12 * dir, y - 11, x + 12 * dir, y - 2);
        } else {
            int ty = k == 3 ? y + 4 : y - 4;
            g.fillRect(dir > 0 ? x - 2 : x - 9, y - 6, 11, 4);
            g.fillRect(x - 2, y - 6, 4, 6);
            g.fillTriangle(x + 9 * dir, y - 11, x + 9 * dir, y + 1, x + 15 * dir, y - 5 + (k == 3 ? 0 : 0));
            if (k == 3) g.fillRect(x + 7 * dir - (dir > 0 ? 0 : 3), y - 6, 3, ty - y + 10);
        }
    }

    void softBar(Graphics g, String left, String right, String center) {
        int bh = barH(), y = H - bh;
        g.setColor(MAIN);
        g.fillRect(0, y, W, bh);
        g.setColor(GOLD);
        g.fillRect(0, y, W, 1);
        int ty = mid(uR, y + 1, bh - 1);
        text(g, uR, GOLD, left, 6, ty, Graphics.TOP | Graphics.LEFT);
        text(g, uR, GOLD, right, W - 6, ty, Graphics.TOP | Graphics.RIGHT);
        if (center != null) {
            int cw = W - 12 - textW(uR, left) - textW(uR, right) - 16;
            text(g, uS, SUB, fit(uS, center, cw), W / 2, mid(uS, y + 1, bh - 1), Graphics.TOP | Graphics.HCENTER);
        }
    }

    // ---------- overlays ----------
    static final int O_NONE = 0, O_LIST = 1, O_INPUT = 2, O_MSG = 3, O_BUSY = 4;
    int ov;
    // list
    static final int L_MENU = 1, L_POINT = 2, L_RESULTS = 3, L_CATS = 4, L_STEPS = 5, L_FAVS = 6, L_FAV = 7, L_HELP = 8,
        L_BROWSE = 9, L_PROFILE = 10;
    int lId, lSel, lTop, lN;
    String lTitle;
    String[] lA = new String[64], lB = new String[64];
    int[] lCode = new int[64];
    Object[] lObj = new Object[64];
    int lBackId;

    void listStart(int id, String title) {
        lId = id;
        lTitle = title;
        lN = 0;
        lSel = 0;
        lTop = 0;
        ov = O_LIST;
    }

    void item(String a, String b, int code, Object o) {
        if (lN == lA.length) {
            String[] na = new String[lN * 2], nb = new String[lN * 2];
            int[] nc = new int[lN * 2];
            Object[] no = new Object[lN * 2];
            System.arraycopy(lA, 0, na, 0, lN);
            System.arraycopy(lB, 0, nb, 0, lN);
            System.arraycopy(lCode, 0, nc, 0, lN);
            System.arraycopy(lObj, 0, no, 0, lN);
            lA = na;
            lB = nb;
            lCode = nc;
            lObj = no;
        }
        lA[lN] = a;
        lB[lN] = b;
        lCode[lN] = code;
        lObj[lN] = o;
        lN++;
    }

    int rowH(int i) { return lB[i] != null ? uR.h + uS.h + 10 : uR.h + 12; }

    void paintList(Graphics g) {
        g.setColor(PAPER);
        g.fillRect(0, 0, W, H);
        int th = fTitle.h + 8;
        g.setColor(MAIN);
        g.fillRect(0, 0, W, th);
        g.setColor(GOLD);
        g.fillRect(0, th - 2, W, 2);
        text(g, uB, GOLD, fit(uB, lTitle, W - 16), W / 2, mid(uB, 0, th - 2), Graphics.TOP | Graphics.HCENTER);
        int top = th, bottom = H - barH();
        // keep the selection visible
        if (lSel < lTop) lTop = lSel;
        while (true) {
            int y = top;
            for (int i = lTop; i <= lSel; i++) y += rowH(i);
            if (y <= bottom || lTop >= lSel) break;
            lTop++;
        }
        int y = top;
        boolean help = lId == L_HELP;
        for (int i = lTop; i < lN && y < bottom; i++) {
            int h = rowH(i);
            if (i == lSel && !help) {
                g.setColor(SEL);
                g.fillRect(0, y, W - 5, h);
                g.setColor(BORDER);
                g.drawRect(0, y, W - 6, h - 1);
            } else {
                g.setColor(LINE);
                g.fillRect(8, y + h - 1, W - 21, 1);
            }
            int ty = lB[i] != null ? y + 4 : mid(uR, y, h);
            text(g, help && lCode[i] == 1 ? uB : uR, INK, fit(uR, lA[i], W - 22), EN ? 10 : W - 10, ty, Graphics.TOP | (EN ? Graphics.LEFT : Graphics.RIGHT));
            if (lB[i] != null) text(g, uS, MUTED, fit(uS, lB[i], W - 22), EN ? 10 : W - 10, y + uR.h + 6, Graphics.TOP | (EN ? Graphics.LEFT : Graphics.RIGHT));
            y += h;
        }
        if (lN == 0) text(g, uR, MUTED, Ui.L("אין פריטים", "No items"), W / 2, top + 20, Graphics.TOP | Graphics.HCENTER);
        // scroll bar
        int vis = 0;
        y = top;
        for (int i = lTop; i < lN && y + rowH(i) <= bottom; i++) {
            y += rowH(i);
            vis++;
        }
        if (vis < lN) {
            int bh = bottom - top - 4;
            int kh = Math.max(12, bh * vis / lN);
            int ky = top + 2 + (bh - kh) * lTop / Math.max(1, lN - vis);
            g.setColor(TAB_BG);
            g.fillRect(W - 4, top + 2, 3, bh);
            g.setColor(MAIN);
            g.fillRect(W - 4, ky, 3, kh);
        }
        softBar(g, help ? "" : Ui.L("בחר", "Select"), Ui.L("חזרה", "Back"), null);
    }

    void keyList(int a, int key) {
        if (a == UP || key == Canvas.KEY_NUM2) {
            if (lId == L_HELP) lTop = Math.max(0, lTop - 1);
            lSel = lSel > 0 ? lSel - 1 : Math.max(0, lN - 1);
        } else if (a == DOWN || key == Canvas.KEY_NUM8) {
            if (lId == L_HELP) {
                lSel = Math.min(lN - 1, lSel + 1);
                return;
            }
            lSel = lSel + 1 < lN ? lSel + 1 : 0;
        } else if (a == SOFT_R) {
            back();
        } else if ((a == FIRE || a == SOFT_L || key == Canvas.KEY_NUM5) && lN > 0 && lId != L_HELP) {
            choose(lId, lCode[lSel], lObj[lSel], lSel);
        }
    }

    void back() {
        if (lId == L_BROWSE) {
            String d = browseDir;
            if (d.length() > 0) {
                int s = d.lastIndexOf('/', d.length() - 2);
                browse(s >= 0 ? d.substring(0, s + 1) : "");
                return;
            }
            ov = O_NONE;
            return;
        }
        if (lId == L_FAV) {
            openFavs();
            return;
        }
        ov = O_NONE;
        if (screen == S_MAP && lId != L_MENU && lBackId == L_MENU) openMenu();
        lBackId = 0;
    }

    // message
    String mTitle;
    Vector mLines;

    void msg(String title, String text) {
        mTitle = title;
        mLines = wrap(uR, text, W - 40);
        ov = O_MSG;
        safeRepaint();
    }

    int dialog(Graphics g, String title, int x, int y, int w, int h) {
        g.setColor(MAIN);
        g.fillRect(x - 2, y - 2, w + 4, h + 4);
        g.setColor(GOLD);
        g.drawRect(x - 3, y - 3, w + 5, h + 5);
        g.setColor(PAPER);
        g.fillRect(x, y, w, h);
        int th = uR.h + 8;
        g.setColor(MAIN);
        g.fillRect(x, y, w, th);
        g.setColor(GOLD);
        g.fillRect(x, y + th - 1, w, 1);
        text(g, uB, GOLD, fit(uB, title, w - 10), x + w / 2, mid(uB, y, th), Graphics.TOP | Graphics.HCENTER);
        return y + th;
    }

    void paintMsg(Graphics g) {
        int lh = uR.h + 4;
        int h = uR.h + 8 + mLines.size() * lh + 16;
        if (h > H - barH() - 10) h = H - barH() - 10;
        int x = 12, w = W - 24, y = (H - barH() - h) / 2;
        int by = dialog(g, mTitle, x, y, w, h) + 8;
        for (int i = 0; i < mLines.size() && by + lh <= y + h; i++, by += lh) {
            text(g, uR, INK, (String) mLines.elementAt(i), EN ? x + 8 : x + w - 8, by, Graphics.TOP | (EN ? Graphics.LEFT : Graphics.RIGHT));
        }
        softBar(g, Ui.L("אישור", "OK"), "", null);
    }

    String busyText;

    void busy(String s) {
        busyText = s;
        ov = O_BUSY;
        safeRepaint();
    }

    void paintBusy(Graphics g) {
        int w = W - 40, h = uR.h * 2 + 30;
        int x = 20, y = (H - h) / 2;
        int by = dialog(g, Ui.L("רגע...", "Please wait"), x, y, w, h);
        text(g, uR, INK, fit(uR, busyText, w - 12), x + w / 2, by + 8, Graphics.TOP | Graphics.HCENTER);
        int ph = (now() / 150) % 8;
        for (int i = 0; i < 8; i++) {
            g.setColor(i == ph ? GOLD : TAB_BG);
            g.fillRect(x + w / 2 - 40 + i * 10, by + uR.h + 14, 7, 4);
        }
        softBar(g, "", Ui.L("ביטול", "Cancel"), null);
    }

    // ---------- search input (multi-tap Hebrew, # switches to digits) ----------
    // what a key types, final letters included (the key caps on the screen show KEYS)
    static final String[] KEYS_TYPE = {" ", "-'\"", "דהו", "אבג", "מםנן", "יכךל", "זחט", "רשת", "צץק", "סעפף"};
    static final String[] KEYS = {" ", "-'\"", "דהו", "אבג", "מנ", "יכל", "זחט", "רשת", "צק", "סעפ"};
    StringBuffer sText = new StringBuffer();
    int sPendKey = -1, sPendIdx, sPendTime;
    int sMode;   // 0 Hebrew letters, 1 digits, 2 English letters
    static final String[] KEYS_EN = {" ", "-'\"", "abc", "def", "ghi", "jkl", "mno", "pqrs", "tuv", "wxyz"};
    static final String[] MODE = {"# אותיות", "# ספרות", "# English"};

    /** the label of the mode # switches to */
    String nextMode() { return EN ? (sMode == 0 ? "# digits" : sMode == 1 ? "# English" : "# Hebrew") : MODE[(sMode + 1) % 3 == 0 ? 0 : (sMode + 1) % 3]; }
    String lastQuery = "";

    static String finals(String s) {
        char[] t = s.toCharArray();
        for (int i = 0; i < t.length; i++) {
            boolean end = i + 1 == t.length || t[i + 1] == ' ' || t[i + 1] == '-';
            if (!end) continue;
            if (i + 1 == t.length) continue;   // the word being typed
            char c = t[i];
            if (c == 'כ') t[i] = 'ך';
            else if (c == 'מ') t[i] = 'ם';
            else if (c == 'נ') t[i] = 'ן';
            else if (c == 'פ') t[i] = 'ף';
            else if (c == 'צ') t[i] = 'ץ';
        }
        return new String(t);
    }

    static String finalsAll(String s) { return finals(cat(s, " ")).trim(); }

    void openInput() {
        sText.setLength(0);
        sPendKey = -1;
        sMode = 0;
        ov = O_INPUT;
    }

    void paintInput(Graphics g) {
        paintMap(g);
        int w = W - 16, cell = uS.h + 9;
        int h = uR.h + 8 + uR.h + 14 + uS.h + 8 + cell * 4 + 10;
        int x = 8, y = (H - barH() - h) / 2;
        int by = dialog(g, Ui.L("חיפוש כתובת או מקום", "Search address or place"), x, y, w, h) + 6;
        g.setColor(0xFFFFFF);
        g.fillRect(x + 6, by, w - 12, uR.h + 8);
        g.setColor(BORDER);
        g.drawRect(x + 6, by, w - 13, uR.h + 7);
        String t = finals(sText.toString());
        int tw = textW(uR, t);
        int xr = x + w - 12;
        if (tw > w - 24) {
            t = fitStart(uR, t, w - 24);
            tw = textW(uR, t);
        }
        text(g, uR, INK, t, xr, by + 4, Graphics.TOP | Graphics.RIGHT);
        g.setColor(ACCENT);
        g.fillRect(xr - tw - 3, by + 3, 1, uR.h + 2);
        by += uR.h + 12;
        text(g, uS, MUTED, Ui.L("לדוגמה: הרצל 12 חולון", "e.g. הרצל 12 חולון"), EN ? x + 10 : x + w - 10, by, Graphics.TOP | (EN ? Graphics.LEFT : Graphics.RIGHT));
        text(g, uS, MUTED, nextMode(), EN ? x + w - 10 : x + 10, by, Graphics.TOP | (EN ? Graphics.RIGHT : Graphics.LEFT));
        by += uS.h + 8;
        int cwd = (w - 12) / 3;
        for (int k = 1; k <= 12; k++) {
            int r = (k - 1) / 3, c = (k - 1) % 3;
            int cx = EN ? x + 6 + c * cwd : x + w - 6 - (c + 1) * cwd, cy = by + r * cell;
            g.setColor(TAB_BG);
            g.fillRect(cx + 1, cy + 1, cwd - 2, cell - 2);
            String lab;
            if (k == 10) lab = Ui.L("* מחק", "* delete");
            else if (k == 11) lab = sMode == 1 ? "0" : Ui.L("0 רווח", "0 space");
            else if (k == 12) lab = nextMode();
            else if (sMode == 1) lab = String.valueOf(k);
            else {
                StringBuffer b = new StringBuffer();
                b.append(k);
                b.append(' ');
                b.append(k == 1 ? "-'\"" : sMode == 2 ? KEYS_EN[k] : KEYS[k]);
                lab = b.toString();
            }
            textL(g, uS, INK, lab, cx + cwd / 2, mid(uS, cy, cell), Graphics.TOP | Graphics.HCENTER);
        }
        softBar(g, Ui.L("חפש", "Search"), sText.length() > 0 ? Ui.L("מחק", "Delete") : Ui.L("ביטול", "Cancel"), null);
    }

    /** the end of s that fits (the start of a long text scrolls away) */
    String fitStart(BFont f, String s, int maxW) {
        int a = 0;
        while (a < s.length() && textW(f, s.substring(a)) > maxW) a++;
        return s.substring(a);
    }

    /** a key label: digit first, then the letters, always left to right */
    void textL(Graphics g, BFont f, int color, String s, int x, int y, int anchor) {
        int sp = s.indexOf(' ');
        if (sp < 0) {
            text(g, f, color, s, x, y, anchor);
            return;
        }
        String a = s.substring(0, sp), b = s.substring(sp + 1);
        int wa = textW(f, a), wb = textW(f, b), gap = f.w(' ');
        int xl = x - (wa + gap + wb) / 2;
        text(g, f, color, a, xl, y, Graphics.TOP | Graphics.LEFT);
        text(g, f, color, b, xl + wa + gap, y, Graphics.TOP | Graphics.LEFT);
    }

    void keyInput(int a, int key) {
        if (key >= Canvas.KEY_NUM0 && key <= Canvas.KEY_NUM9) {
            int d = key - Canvas.KEY_NUM0, t = now();
            if (sMode == 1) {
                if (sText.length() < 40) sText.append((char) ('0' + d));
                sPendKey = -1;
                return;
            }
            String letters = sMode == 2 ? KEYS_EN[d] : KEYS_TYPE[d];
            if (d == sPendKey && t - sPendTime < 1100 && letters.length() > 1 && sText.length() > 0) {
                sPendIdx = (sPendIdx + 1) % letters.length();
                sText.setCharAt(sText.length() - 1, letters.charAt(sPendIdx));
            } else if (sText.length() < 40) {
                sPendIdx = 0;
                sText.append(letters.charAt(0));
            }
            sPendKey = letters.length() > 1 ? d : -1;
            sPendTime = t;
        } else if (key == Canvas.KEY_POUND) {
            sMode = (sMode + 1) % 3;
            if (sMode == 1 &&sText.length() > 0 && sText.charAt(sText.length() - 1) != ' ') sText.append(' ');
            sPendKey = -1;
        } else if (key == Canvas.KEY_STAR || (a == SOFT_R && sText.length() > 0)) {
            if (sText.length() > 0) sText.setLength(sText.length() - 1);
            sPendKey = -1;
        } else if (a == SOFT_R) {
            ov = O_NONE;
        } else if (a == SOFT_L || a == FIRE) {
            String q = sText.toString().trim();
            if (q.length() < 2) return;
            lastQuery = q;
            startJob(J_SEARCH);
        }
    }

    // ---------- jobs in the background ----------
    static final int J_SEARCH = 1, J_ROUTE = 2, J_NEAR = 3, J_OPEN = 4;
    int job;
    volatile boolean jobCancel;
    Vector jobRes;
    int nearCat, nearLat, nearLon;
    String openUrl;

    void startJob(int j) {
        job = j;
        jobCancel = false;
        busy(j == J_SEARCH ? Ui.L("מחפש...", "Searching...") : j == J_ROUTE ? Ui.L("מחשב מסלול...", "Calculating route...") : j == J_NEAR ? Ui.L("מחפש בסביבה...", "Searching nearby...") : Ui.L("פותח את המפה...", "Opening the map..."));
        new Thread(this).start();
        timer.schedule(new TimerTask() {
            public void run() {
                if (ov == O_BUSY) safeRepaint();
                else cancel();
            }
        }, 150, 150);
    }

    public void run() {
        int j = job;
        try {
            if (j == J_SEARCH) {
                Vector v = finder.search(lastQuery, cLat, cLon);
                if (jobCancel) return;
                showResults(v, finalsAll(lastQuery));
            } else if (j == J_ROUTE) {
                // the map tiles give their memory to the route search (they load again afterwards)
                synchronized (cache) {
                    for (int i = 0; i < cache.length; i++) cache[i] = null;
                    wantN = 0;
                }
                System.gc();
                router.profile = profile;
                Route r = router.route(orgLat, orgLon, dstLat, dstLon);
                if (jobCancel) return;
                if (r == null) {
                    msg(Ui.L("ניווט", "Navigation"), router.error == null ? Ui.L("לא נמצא מסלול", "No route found") : router.error);
                    return;
                }
                startRoute(r);
            } else if (j == J_NEAR) {
                Vector v = near(nearCat, nearLat, nearLon);
                if (jobCancel) return;
                showResults(v, CAT_NAME[nearCat]);
            } else if (j == J_OPEN) {
                if (open(openUrl)) ov = O_NONE;
                else msg(Ui.L("קובץ המפה", "Map file"), Ui.L("לא ניתן לפתוח את הקובץ. ודאו שזה קובץ israel.map שלם.", "The file cannot be opened. Make sure it is a complete israel.map file."));
            }
        } catch (OutOfMemoryError e) {
            synchronized (cache) {
                for (int i = 0; i < cache.length; i++) cache[i] = null;
            }
            System.gc();
            msg(Ui.L("שגיאה", "Error"), Ui.L("אין מספיק זיכרון.", "Not enough memory."));
        } catch (Throwable t) {
            msg(Ui.L("שגיאה", "Error"), Ui.L("קריאת קובץ המפה נכשלה.", "Reading the map file failed."));
        }
        safeRepaint();
    }

    void cancelJob() {
        jobCancel = true;
        if (finder != null) finder.cancel = true;
        if (router != null) router.cancel = true;
        ov = O_NONE;
    }

    Vector results;

    void showResults(Vector v, String q) {
        results = v;
        listStart(L_RESULTS, q);
        for (int i = 0; i < v.size(); i++) {
            Finder.Hit h = (Finder.Hit) v.elementAt(i);
            StringBuffer a = new StringBuffer(h.name);
            if (h.house != null && h.house.length() > 0) {
                a.append(' ');
                a.append(h.house);
            }
            StringBuffer b = new StringBuffer(kindName(h));
            if (h.city != null && h.city.length() > 0 && !h.city.equals(h.name)) {
                b.append(" · ");
                b.append(h.city);
                if (EN) b.append((char) 0x200E);   // keeps the distance after the Hebrew name
            }
            b.append(" · ");
            b.append(meters(h.dist));
            item(a.toString(), b.toString(), 0, h);
        }
        if (v.size() == 0) {
            ov = O_NONE;
            msg(Ui.L("חיפוש", "Search"), Ui.L("לא נמצאו תוצאות. נסו שם קצר יותר, בלי מילים כמו 'רחוב', או שם עיר אחרי המספר.", "No results. Try a shorter name, without words like 'רחוב', or a city name after the number."));
            return;
        }
        safeRepaint();
    }

    static String[] PLACE_KIND = {"עיר", "עיר", "יישוב", "שכונה", "מקום"};

    static String kindName(Finder.Hit h) {
        if (h.kind == 1) return PLACE_KIND[Math.min(4, h.cat)];
        if (h.kind == 2) return h.cat <= 2 ? Ui.L("כביש", "Road") : Ui.L("רחוב", "Street");
        if (h.kind == 3) return Ui.L("כתובת", "Address");
        return poiName(h.cat);
    }

    static String poiName(int c) {
        switch (c) {
            case 1: return Ui.L("בית כנסת", "Synagogue");
            case 2: return Ui.L("בית חולים", "Hospital");
            case 21: return Ui.L("מרפאה", "Clinic");
            case 3: return Ui.L("בית מרקחת", "Pharmacy");
            case 4: return Ui.L("בית ספר", "School");
            case 20: return Ui.L("מוסד אקדמי", "College");
            case 5: return Ui.L("תחנת דלק", "Fuel");
            case 6: return Ui.L("תחנת אוטובוס", "Bus stop");
            case 7: return Ui.L("תחנת רכבת", "Train station");
            case 8: return Ui.L("סופרמרקט / קניון", "Supermarket / mall");
            case 22: return Ui.L("חנות", "Shop");
            case 9: return Ui.L("אוכל", "Food");
            case 10: return Ui.L("בנק / כספומט", "Bank / ATM");
            case 11: return Ui.L("דואר", "Post office");
            case 12: return Ui.L("משטרה / כבאות", "Police / fire");
            case 14: return Ui.L("חניון", "Parking");
            case 15: return Ui.L("מלון", "Hotel");
            case 16: return Ui.L("מקווה", "Mikveh");
            case 18: return Ui.L("תיירות ומורשת", "Tourism and heritage");
            case 19: return Ui.L("משרד ממשלתי", "Government office");
            case 23: return Ui.L("קהילה ותרבות", "Community and culture");
            case 24: return Ui.L("משרד", "Office");
            default: return Ui.L("מקום", "Place");
        }
    }

    // categories of "nearby": name and the POI codes
    static String[] CAT_NAME = {"בתי כנסת", "מקוואות", "בתי מרקחת", "בתי חולים ומרפאות", "תחנות דלק", "סופרמרקטים וקניונים",
        "אוכל", "בנקים וכספומטים", "תחנות אוטובוס", "תחנות רכבת", "חניונים", "בתי ספר", "דואר", "משטרה וכבאות", "מלונות",
        "תיירות ומורשת", "חנויות"};
    static final int[][] CAT_CODES = {{1}, {16}, {3}, {2, 21}, {5}, {8}, {9}, {10}, {6}, {7}, {14}, {4, 20}, {11}, {12}, {15}, {18}, {22}};

    Vector near(int cat, int lat, int lon) throws java.io.IOException {
        Vector res = new Vector();
        TileSet ts = lv[3];
        int r0 = ts.row(lat), c0 = ts.col(lon);
        int[] codes = CAT_CODES[cat];
        for (int ring = 0; ring <= 5 && !jobCancel; ring++) {
            if (ring >= 3 && res.size() >= 8) break;
            for (int r = r0 - ring; r <= r0 + ring; r++) {
                for (int c = c0 - ring; c <= c0 + ring; c++) {
                    if (Math.max(Math.abs(r - r0), Math.abs(c - c0)) != ring) continue;
                    byte[] d = ts.load(r, c);
                    if (d == null) continue;
                    Tile t = new Tile(0, d, ts.oLat + r * ts.tile, ts.oLon + c * ts.tile, ts.quant);
                    for (int i = 0; i < t.n; i++) {
                        if (t.type[i] != 31) continue;
                        int sub = d[t.off[i] + 1] & 255;
                        boolean ok = false;
                        for (int k = 0; k < codes.length; k++) if (codes[k] == sub) ok = true;
                        if (!ok) continue;
                        int[] p = {t.off[i] + 3};
                        int fl = d[t.off[i] + 2];
                        String nm = (fl & 1) != 0 ? t.str[Tile.vint(d, p)] : null;
                        if ((fl & 2) != 0) Tile.vint(d, p);
                        Tile.vint(d, p);
                        int qa = Tile.zz(d, p), qo = Tile.zz(d, p);
                        Finder.Hit h = new Finder.Hit();
                        h.kind = 4;
                        h.cat = sub;
                        h.name = nm != null ? nm : poiName(sub);
                        h.city = "";
                        h.house = "";
                        h.lat = t.bl + qa * t.quant;
                        h.lon = t.bo + qo * t.quant;
                        h.dist = Router.dist(lat, lon, h.lat, h.lon);
                        int at = res.size();
                        while (at > 0 && ((Finder.Hit) res.elementAt(at - 1)).dist > h.dist) at--;
                        res.insertElementAt(h, at);
                    }
                }
            }
        }
        while (res.size() > 40) res.removeElementAt(res.size() - 1);
        return res;
    }

    // ---------- menus ----------
    void openMenu() {
        listStart(L_MENU, Ui.L("מפות ישראל", "Israel Maps"));
        item(Ui.L("חיפוש כתובת או מקום", "Search address or place"), Ui.L("מקש 9", "Key 9"), 1, null);
        item(Ui.L("מה יש בסביבה", "Nearby"), Ui.L("בתי כנסת, מקוואות, תחנות דלק...", "Synagogues, mikvehs, fuel..."), 2, null);
        item(Ui.L("המקומות שלי", "My places"), favN == 0 ? Ui.L("עוד לא נשמרו מקומות", "No places saved yet") : null, 3, null);
        if (nav && route != null) {
            item(Ui.L("הוראות המסלול", "Route directions"), null, 4, null);
            item(Ui.L("סיום הניווט", "End navigation"), null, 5, null);
        }
        if (gpsApi) item(gpsOn ? Ui.L("כבה GPS", "Turn GPS off") : Ui.L("המיקום שלי (GPS)", "My location (GPS)"), Ui.L("מקש 0", "Key 0"), 6, null);
        item(cat(Ui.L("אמצעי תנועה: ", "Travel by: "), PROFILE[profile]), Ui.L("מקש 7", "Key 7"), 7, null);
        item(headingUp ? Ui.L("בניווט: המפה מסתובבת עם הדרך", "Navigation: map turns with the road") : Ui.L("בניווט: צפון למעלה", "Navigation: north up"), Ui.L("כך פנייה ימינה נראית מימין", "So a right turn shows on the right"), 12, null);
        item(satOn ? Ui.L("מצב לוויין: פועל", "Satellite: on") : Ui.L("מצב לוויין: כבוי", "Satellite: off"), sat == null ? Ui.L("צריך את הקובץ israel-sat.map", "Needs the file israel-sat.map") : Ui.L("צילומים עד רמת רחוב ראשי (10 מ')", "Photos down to main-street level (10 m)"), 11, null);
        item(Ui.L("מקשים ועזרה", "Keys and help"), null, 8, null);
        item(Ui.L("קובץ המפה", "Map file"), null, 9, null);
        item(Ui.L("יציאה", "Exit"), null, 10, null);
    }

    void openPoint() {
        String here = whatsHere();
        listStart(L_POINT, here);
        item(Ui.L("נווט לכאן", "Navigate here"), gpsOk ? Ui.L("מהמיקום שלי", "From my location") : orgSet ? Ui.L("מנקודת המוצא שנבחרה", "From the chosen start") : null, 1, here);
        item(Ui.L("צא מכאן", "Start from here"), Ui.L("נקודת מוצא לניווט", "Starting point of the route"), 2, here);
        item(Ui.L("שמור במקומות שלי", "Save to my places"), null, 3, here);
        item(Ui.L("מה יש בסביבה", "Nearby"), null, 4, null);
        if (pinName != null) item(Ui.L("הסר את הסימון", "Remove the marker"), null, 5, null);
    }

    void openFavs() {
        listStart(L_FAVS, Ui.L("המקומות שלי", "My places"));
        for (int i = 0; i < favN; i++) item(favName[i], meters(Router.dist(cLat, cLon, favLat[i], favLon[i])), i, null);
        if (favN == 0) {
            ov = O_NONE;
            msg(Ui.L("המקומות שלי", "My places"), Ui.L("עוד לא נשמרו מקומות. הזיזו את המפה למקום, לחצו 5 ובחרו 'שמור במקומות שלי'.", "No places saved yet. Move the map to a place, press 5 and choose 'Save to my places'."));
        }
    }

    void openCats(int lat, int lon) {
        nearLat = lat;
        nearLon = lon;
        listStart(L_CATS, Ui.L("מה יש בסביבה", "Nearby"));
        for (int i = 0; i < CAT_NAME.length; i++) item(CAT_NAME[i], null, i, null);
    }

    void openSteps() {
        Route r = route;
        listStart(L_STEPS, cat3(meters(r.length), " · ", minutes(r.seconds)));
        for (int i = 0; i < r.stepN; i++) {
            int d = i + 1 < r.stepN ? r.cum[r.stepPt[i + 1]] - r.cum[r.stepPt[i]] : 0;
            item(r.stepText[i], d > 0 ? cat(Ui.L("ואז ", "then "), meters(d)) : null, i, null);
        }
        lSel = step;
    }

    static String HELP = "2 4 6 8 / חצים|הזזת המפה\n1 / #|התקרבות\n3 / *|התרחקות\n5|תפריט הנקודה שבמרכז: נווט לכאן, צא מכאן, שמור\n9|חיפוש כתובת או מקום\n7|אמצעי תנועה: רכב, הליכה, אופניים\n0|המיקום שלי (בטלפון עם GPS)\nבזמן ניווט|אישור = ההוראה הבאה, * = הקודמת. המפה מסתובבת כך שהדרך שעשית מצביעה למעלה, ופנייה ימינה נראית מימין (אפשר לבטל בתפריט). המצפן בפינה מראה את הצפון.\nחיפוש|כתבו שם רחוב ומספר ואחריו העיר, למשל: הרצל 12 חולון. # עובר לספרות ולאותיות באנגלית, * מוחק.\nקובץ המפה|הקובץ israel.map צריך להיות בכרטיס הזיכרון: בתיקייה הראשית, ב-Maps או ליד היישום.\nמצב לוויין|בתפריט. צילומים מהחלל עד רמת רחוב ראשי (10 מ'), עם הכבישים והשמות מעליהם. צריך את הקובץ israel-sat.map ליד israel.map.\nנתונים|OpenStreetMap, רישיון ODbL. צילומי לוויין: Copernicus Sentinel-2 מ-23.10.2026, מכיל נתוני Copernicus Sentinel מעובדים";

    static final String HELP_EN = "2/4/6/8 or arrows|Move the map\n1 or #|Zoom in\n3 or *|Zoom out\n5|Menu of the point in the centre: navigate here, start from here, save\n9|Search for an address or place\n7|Travel by: car, walking, bicycle\n0|My location (on phones with GPS)\nWhile navigating|OK = next direction, * = previous one. The map turns so the way you came points up and a right turn shows on the right (can be turned off in the menu). The compass in the corner shows north.\nSearch|Street names are in Hebrew: type the street, the number and then the city, e.g. הרצל 12 חולון. # switches between Hebrew, digits and English letters; * deletes.\nMap file|The file israel.map must be on the memory card: in the main folder, in Maps or next to the app.\nSatellite mode|In the menu. Photos from space down to main-street level (10 m), with the roads and names over them. Needs the file israel-sat.map next to israel.map.\nData|OpenStreetMap, ODbL licence. Satellite photos: Copernicus Sentinel-2 of 23.10.2026, contains modified Copernicus Sentinel data";

    void openHelp() {
        listStart(L_HELP, Ui.L("מקשים ועזרה", "Keys and help"));
        int i = 0;
        String s = HELP;
        while (i < s.length()) {
            int nl = s.indexOf('\n', i);
            if (nl < 0) nl = s.length();
            String line = s.substring(i, nl);
            int bar = line.indexOf('|');
            item(line.substring(0, bar), null, 1, null);
            Vector w = wrap(uR, line.substring(bar + 1), W - 30);
            for (int k = 0; k < w.size(); k++) item((String) w.elementAt(k), null, 0, null);
            i = nl + 1;
        }
    }

    void choose(int id, int code, Object o, int index) {
        if (id == L_MENU) {
            ov = O_NONE;
            lBackId = L_MENU;
            switch (code) {
                case 1: openInput(); break;
                case 2: openCats(cLat, cLon); break;
                case 3: openFavs(); break;
                case 4: openSteps(); break;
                case 5: endNav(); lBackId = 0; break;
                case 6: lBackId = 0; toggleGps(); break;
                case 7:
                    lBackId = 0;
                    listStart(L_PROFILE, Ui.L("אמצעי תנועה", "Travel by"));
                    for (int i = 0; i < 3; i++) item(PROFILE[i], i == profile ? Ui.L("נבחר", "Selected") : null, i, null);
                    lSel = profile;
                    lBackId = L_MENU;
                    break;
                case 8: openHelp(); break;
                case 9:
                    StringBuffer b = new StringBuffer(url);
                    if (mf != null && mf.meta.length() > 0) {
                        b.append("\n\n");
                        b.append(mf.meta);
                    }
                    msg(Ui.L("קובץ המפה", "Map file"), b.toString());
                    lBackId = 0;
                    break;
                case 10: save(); app.quit(); break;
                case 12:
                    lBackId = 0;
                    headingUp = !headingUp;
                    save();
                    showToast(headingUp ? Ui.L("המפה תסתובב עם הדרך", "The map turns with the road") : Ui.L("צפון למעלה", "North up"));
                    break;
                case 11:
                    lBackId = 0;
                    if (sat == null) {
                        msg(Ui.L("מצב לוויין", "Satellite"), Ui.L("כדי לראות צילומי לוויין צריך להעתיק לכרטיס את הקובץ israel-sat.map, ליד israel.map.", "To see satellite photos, copy the file israel-sat.map to the card, next to israel.map."));
                        break;
                    }
                    satOn = !satOn;
                    if (!satOn) satClear();
                    save();
                    showToast(satOn ? (sat.level(z) < 0 ? Ui.L("לוויין: התרחקו כדי לראות", "Satellite: zoom out to see") : Ui.L("מצב לוויין", "Satellite")) : Ui.L("מפה רגילה", "Regular map"));
                    break;
            }
        } else if (id == L_PROFILE) {
            setProfile(code);
            ov = O_NONE;
        } else if (id == L_POINT) {
            ov = O_NONE;
            String here = (String) o;
            if (code == 1) {
                dstLat = cLat;
                dstLon = cLon;
                dstSet = true;
                dstName = here;
                navigate();
            } else if (code == 2) {
                orgLat = cLat;
                orgLon = cLon;
                orgSet = true;
                if (dstSet) navigate();
                else msg(Ui.L("נקודת מוצא", "Starting point"), Ui.L("נקודת המוצא נקבעה. עכשיו חפשו את היעד או הזיזו אליו את המפה, לחצו 5 ובחרו 'נווט לכאן'.", "The starting point is set. Now search for the destination or move the map to it, press 5 and choose 'Navigate here'."));
            } else if (code == 3) {
                addFav(here, cLat, cLon);
            } else if (code == 4) {
                openCats(cLat, cLon);
            } else if (code == 5) {
                pinName = null;
            }
        } else if (id == L_RESULTS) {
            Finder.Hit h = (Finder.Hit) o;
            ov = O_NONE;
            follow = false;
            cLat = h.lat;
            cLon = h.lon;
            z = h.kind == 1 ? (h.cat <= 1 ? 8 : h.cat == 2 ? 9 : 10) : h.kind == 2 ? 10 : 11;
            StringBuffer b = new StringBuffer(h.name);
            if (h.house != null && h.house.length() > 0) {
                b.append(' ');
                b.append(h.house);
            }
            if (h.city != null && h.city.length() > 0 && h.kind != 1) {
                b.append(", ");
                b.append(h.city);
            }
            pinName = b.toString();
            pinLat = h.lat;
            pinLon = h.lon;
            lBackId = 0;
            openPoint();
        } else if (id == L_CATS) {
            nearCat = code;
            ov = O_NONE;
            startJob(J_NEAR);
        } else if (id == L_STEPS) {
            ov = O_NONE;
            goStep(code);
        } else if (id == L_FAVS) {
            listStart(L_FAV, favName[code]);
            item(Ui.L("הצג במפה", "Show on map"), null, 1, new Integer(code));
            item(Ui.L("נווט לכאן", "Navigate here"), null, 2, new Integer(code));
            item(Ui.L("מחק", "Delete"), null, 3, new Integer(code));
        } else if (id == L_FAV) {
            int i = ((Integer) o).intValue();
            if (code == 3) {
                for (int k = i; k + 1 < favN; k++) {
                    favName[k] = favName[k + 1];
                    favLat[k] = favLat[k + 1];
                    favLon[k] = favLon[k + 1];
                }
                favN--;
                save();
                openFavs();
                return;
            }
            ov = O_NONE;
            follow = false;
            cLat = favLat[i];
            cLon = favLon[i];
            if (z < 10) z = 10;
            if (code == 2) {
                dstLat = cLat;
                dstLon = cLon;
                dstSet = true;
                dstName = favName[i];
                navigate();
            }
        } else if (id == L_BROWSE) {
            String e = (String) o;
            if (e.endsWith("/")) browse(cat(browseDir, e));
            else {
                openUrl = cat3("file:///", browseDir, e);
                ov = O_NONE;
                startJob(J_OPEN);
            }
        }
    }

    void setProfile(int p) {
        profile = p;
        showToast(PROFILE[p]);
        save();
        if (nav && dstSet && (orgSet || gpsOk)) navigate();
    }

    void addFav(String name, int lat, int lon) {
        if (favN == favName.length) {
            msg(Ui.L("המקומות שלי", "My places"), Ui.L("הרשימה מלאה (60 מקומות). מחקו מקום ישן.", "The list is full (60 places). Delete an old one."));
            return;
        }
        favName[favN] = name;
        favLat[favN] = lat;
        favLon[favN] = lon;
        favN++;
        save();
        showToast(Ui.L("נשמר במקומות שלי", "Saved to my places"));
    }

    void navigate() {
        if (gpsOk) {
            orgLat = gLat;
            orgLon = gLon;
            orgSet = true;
        }
        if (!orgSet) {
            msg(Ui.L("ניווט", "Navigation"), Ui.L("היעד נקבע. עכשיו הזיזו את המפה לנקודת המוצא (או חפשו אותה), לחצו 5 ובחרו 'צא מכאן'.", "The destination is set. Now move the map to the starting point (or search for it), press 5 and choose 'Start from here'."));
            return;
        }
        startJob(J_ROUTE);
    }

    void startRoute(Route r) {
        route = r;
        nav = true;
        step = 0;
        gIdx = 0;
        offCount = 0;
        pinName = null;
        ov = O_NONE;
        if (!(gpsOk && follow)) fitRoute();
        safeRepaint();
    }

    /** the zoom and centre that show the whole route */
    void fitRoute() {
        Route r = route;
        int minLa = Integer.MAX_VALUE, maxLa = Integer.MIN_VALUE, minLo = Integer.MAX_VALUE, maxLo = Integer.MIN_VALUE;
        for (int i = 0; i < r.n; i++) {
            if (r.lat[i] < minLa) minLa = r.lat[i];
            if (r.lat[i] > maxLa) maxLa = r.lat[i];
            if (r.lon[i] < minLo) minLo = r.lon[i];
            if (r.lon[i] > maxLo) maxLo = r.lon[i];
        }
        cLat = (minLa + maxLa) / 2;
        cLon = (minLo + maxLo) / 2;
        turnView = false;   // the whole route: north up
        int mh = H - barH() - 60, mw = W - 20;
        z = 0;
        for (int k = ZMAX; k >= 0; k--) {
            if ((maxLa - minLa) / UPP[k] <= mh && (int) ((maxLo - minLo) * 0.8526f) / UPP[k] <= mw) {
                z = k;
                break;
            }
        }
        follow = false;
    }

    void goStep(int s) {
        Route r = route;
        if (r == null) return;
        if (s < 0) s = 0;
        if (s >= r.stepN) s = r.stepN - 1;
        step = s;
        follow = false;
        int p = r.stepPt[s];
        cLat = r.lat[p];
        cLon = r.lon[p];
        if (z < 10) z = 11;
        // the way to the turn points up (at the start: the way ahead), so the turn shows on its own side
        if (r.stepTurn[s] == 10) heading(r, p, r.ahead(p, 30));
        else heading(r, r.behind(p, 30), p);
        turnView = true;
    }

    void endNav() {
        nav = false;
        turnView = false;
        route = null;
        dstSet = false;
        orgSet = false;
        showToast(Ui.L("הניווט הסתיים", "Navigation ended"));
    }

    /** the street (and house number) at the map centre, or a general name */
    String whatsHere() {
        if (pinName != null && pinLat == cLat && pinLon == cLon) return pinName;
        setView();
        String best = null, house = null;
        int bd = 18 * 18, hd = 14 * 14;
        int cx = ax, cy = ay;
        for (int v = 0; v < visN; v++) {
            Tile t = vis[v];
            for (int i = 0; i < t.n; i++) {
                int ty = t.type[i];
                if (ty == 32 && z >= 10) {
                    decode(t, i);
                    int dx = px[0] - cx, dy = py[0] - cy, d = dx * dx + dy * dy;
                    if (d < hd && fName != null) {
                        hd = d;
                        house = fName;
                    }
                    continue;
                }
                if (ty < 1 || ty > 8 || (t.d[t.off[i] + 2] & 1) == 0) continue;
                decode(t, i);
                if (fName == null) continue;
                for (int k = 1; k < fn; k++) {
                    int d = segDist2(cx, cy, px[k - 1], py[k - 1], px[k], py[k]);
                    if (d < bd) {
                        bd = d;
                        best = fName;
                    }
                }
            }
        }
        if (best == null) return Ui.L("נקודה במפה", "Point on the map");
        if (house != null) return cat3(best, " ", house);
        return best;
    }

    static int segDist2(int x, int y, int x1, int y1, int x2, int y2) {
        int dx = x2 - x1, dy = y2 - y1;
        int l = dx * dx + dy * dy;
        float t = l == 0 ? 0 : ((x - x1) * dx + (y - y1) * dy) / (float) l;
        if (t < 0) t = 0;
        if (t > 1) t = 1;
        int qx = x1 + (int) (t * dx) - x, qy = y1 + (int) (t * dy) - y;
        return qx * qx + qy * qy;
    }

    // ---------- the file browser ----------
    String browseDir = "";

    void browse(String dir) {
        browseDir = dir;
        listStart(L_BROWSE, dir.length() == 0 ? Ui.L("בחירת קובץ המפה", "Choose the map file") : dir);
        try {
            Vector v = dir.length() == 0 ? MapFile.roots() : MapFile.list(cat("file:///", dir));
            for (int i = 0; i < v.size(); i++) {
                String e = (String) v.elementAt(i);
                if (e.endsWith("/")) item(e.substring(0, e.length() - 1), Ui.L("תיקייה", "Folder"), 0, e);
                else if (e.toLowerCase().endsWith(".map")) item(e, Ui.L("קובץ מפה", "Map file"), 0, e);
            }
        } catch (Throwable t) {
        }
        safeRepaint();
    }

    // ---------- GPS ----------
    boolean gpsApi, gpsOn, gpsOk, follow;
    Gps gps;
    int gLat, gLon, gAcc, gIdx, offCount;

    void toggleGps() {
        if (!gpsApi) {
            msg("GPS", Ui.L("בטלפון הזה אין GPS שהיישום יכול להשתמש בו. אפשר לבחור נקודת מוצא בעזרת 5 ← 'צא מכאן'.", "This phone has no GPS the app can use. Choose a starting point with 5 > 'Start from here'."));
            return;
        }
        if (gpsOn) {
            gps.stop();
            gpsOn = false;
            gpsOk = false;
            follow = false;
            showToast(Ui.L("ה-GPS כובה", "GPS is off"));
            return;
        }
        if (gps == null) gps = new Gps(this);
        if (gps.start()) {
            gpsOn = true;
            follow = true;
            showToast(Ui.L("מחפש מיקום...", "Finding location..."));
        } else msg("GPS", Ui.L("לא ניתן להפעיל את ה-GPS.", "The GPS cannot be started."));
    }

    void gpsFix(int la, int lo, int acc) {
        gLat = la;
        gLon = lo;
        gAcc = acc;
        boolean first = !gpsOk;
        gpsOk = true;
        if (first && z < 10) z = 11;
        if (follow) {
            cLat = la;
            cLon = lo;
        }
        Route r = route;
        if (nav && r != null) {
            int[] d = new int[1];
            gIdx = r.nearest(la, lo, gIdx, d);
            if (follow) {
                heading(r, gIdx, r.ahead(gIdx, 30));
                turnView = true;
            }
            int s = 0;
            while (s < r.stepN - 1 && r.stepPt[s] <= gIdx) s++;
            if (follow) step = s;
            if (d[0] > 70 + acc) offCount++;
            else offCount = 0;
            if (offCount >= 4 && ov == O_NONE && dstSet) {
                offCount = 0;
                orgLat = la;
                orgLon = lo;
                orgSet = true;
                startJob(J_ROUTE);
            }
        }
        safeRepaint();
    }

    void gpsState(boolean ok) {
        if (!ok) gpsOk = false;
        safeRepaint();
    }

    // ---------- keys ----------
    static final int UP = 1, DOWN = 2, LEFT = 3, RIGHT = 4, FIRE = 5, SOFT_L = 6, SOFT_R = 7, NONE = 0;

    int action(int key) {
        if (key == -6 || key == -21 || key == 21) return SOFT_L;
        if (key == -7 || key == -22 || key == 22) return SOFT_R;
        if (key == -5 || key == -20 || key == 20) return FIRE;
        if (key == -1) return UP;
        if (key == -2) return DOWN;
        if (key == -3) return LEFT;
        if (key == -4) return RIGHT;
        if (key >= Canvas.KEY_NUM0 && key <= Canvas.KEY_NUM9) return NONE;
        int a = 0;
        try {
            a = getGameAction(key);
        } catch (Exception e) {
        }
        if (a == Canvas.UP) return UP;
        if (a == Canvas.DOWN) return DOWN;
        if (a == Canvas.LEFT) return LEFT;
        if (a == Canvas.RIGHT) return RIGHT;
        if (a == Canvas.FIRE) return FIRE;
        return NONE;
    }

    public void keyPressed(int key) {
        int a = action(key);
        if (ov == O_LIST) keyList(a, key);
        else if (ov == O_INPUT) keyInput(a, key);
        else if (ov == O_MSG) {
            if (a == SOFT_L || a == FIRE || a == SOFT_R || key == Canvas.KEY_NUM5) ov = O_NONE;
        } else if (ov == O_BUSY) {
            if (a == SOFT_R) cancelJob();
        } else if (screen == S_NOFILE) {
            if (a == SOFT_L) browse("");
            else if (a == SOFT_R) app.quit();
            else if (a == FIRE || key == Canvas.KEY_NUM5) {
                screen = S_START;
                new Thread(new Runnable() {
                    public void run() { findFile(); }
                }).start();
            }
        } else if (screen == S_MAP) keyMap(a, key, false);
        safeRepaint();
    }

    public void keyRepeated(int key) {
        int a = action(key);
        if (ov == O_LIST && (a == UP || a == DOWN)) keyList(a, key);
        else if (ov == O_NONE && screen == S_MAP) {
            if (a >= UP && a <= RIGHT || key == Canvas.KEY_NUM2 || key == Canvas.KEY_NUM4 || key == Canvas.KEY_NUM6 || key == Canvas.KEY_NUM8)
                keyMap(a, key, true);
        }
        safeRepaint();
    }

    void pan(int dx, int dy) {
        setView();
        follow = false;
        mapAt(ax + dx, ay + dy);
        cLon = mLon;
        cLat = mLat;
        clampView();
    }

    void keyMap(int a, int key, boolean rep) {
        setView();
        lBackId = 0;
        int stepX = rep ? W / 6 : W / 4, stepY = rep ? mapH / 6 : mapH / 4;
        if (a == UP || key == Canvas.KEY_NUM2) pan(0, -stepY);
        else if (a == DOWN || key == Canvas.KEY_NUM8) pan(0, stepY);
        else if (a == LEFT || key == Canvas.KEY_NUM4) pan(-stepX, 0);
        else if (a == RIGHT || key == Canvas.KEY_NUM6) pan(stepX, 0);
        else if (key == Canvas.KEY_NUM1 || (key == Canvas.KEY_POUND && !nav)) {
            if (z < ZMAX) z++;
            if (satOn && sat != null && sat.level(z) < 0 && sat.level(z - 1) >= 0) showToast(Ui.L("צילום הלוויין לא מפורט יותר: מוצגת מפה", "No finer photos: showing the map"));
        } else if (key == Canvas.KEY_NUM3 || (key == Canvas.KEY_STAR && !nav)) {
            if (z > 0) z--;
        } else if (key == Canvas.KEY_POUND && nav) goStep(step + 1);
        else if (key == Canvas.KEY_STAR && nav) goStep(step - 1);
        else if (a == FIRE && nav) goStep(step + 1);
        else if (a == FIRE || key == Canvas.KEY_NUM5) openPoint();
        else if (key == Canvas.KEY_NUM9) openInput();
        else if (key == Canvas.KEY_NUM7) setProfile((profile + 1) % 3);
        else if (key == Canvas.KEY_NUM0) {
            if (gpsOn && gpsOk && !follow) {
                follow = true;
                cLat = gLat;
                cLon = gLon;
            } else toggleGps();
        } else if (a == SOFT_L) openMenu();
        else if (a == SOFT_R) {
            if (nav) endNav();
            else {
                save();
                app.quit();
            }
        }
    }

    // ---------- painting ----------
    private final Object plock = new Object();
    private boolean painting, again;

    /** repaint from other threads: never while a paint runs (the emulator paints in the calling thread) */
    void safeRepaint() {
        synchronized (plock) {
            if (painting) {
                again = true;
                return;
            }
        }
        repaint();
    }

    protected void paint(Graphics g) {
        synchronized (plock) {
            if (painting) {
                again = true;
                return;
            }
            painting = true;
        }
        try {
            W = getWidth();
            H = getHeight();
            paint0(g);
        } catch (OutOfMemoryError e) {
            synchronized (cache) {
                for (int i = 0; i < cache.length; i++) cache[i] = null;
            }
            System.gc();
        } catch (RuntimeException e) {
            e.printStackTrace();
        } finally {
            synchronized (plock) {
                painting = false;
            }
        }
        boolean a;
        synchronized (plock) {
            a = again;
            again = false;
        }
        if (a) repaint();
    }

    void paint0(Graphics g) {
        g.setClip(0, 0, W, H);
        if (screen == S_START) {
            paintStart(g, startText, null);
            return;
        }
        if (screen == S_NOFILE) {
            paintStart(g, Ui.L("לא נמצא קובץ המפה", "Map file not found"), noFileWhy != null ? noFileWhy
                : Ui.L("העתיקו את הקובץ israel.map לכרטיס הזיכרון: לתיקייה הראשית, לתיקייה Maps או לתיקייה של היישום. אחר כך לחצו 5 כדי לחפש שוב, או 'בחר קובץ' כדי לבחור אותו בעצמכם.", "Copy the file israel.map to the memory card: to the main folder, to a folder named Maps or to the folder of the app. Then press 5 to look again, or 'Choose file' to pick it yourself."));
            softBar(g, Ui.L("בחר קובץ", "Choose file"), Ui.L("יציאה", "Exit"), null);
            if (ov == O_LIST) paintList(g);
            else if (ov == O_MSG) paintMsg(g);
            else if (ov == O_BUSY) paintBusy(g);
            return;
        }
        if (ov == O_LIST) {
            paintList(g);
            return;
        }
        if (ov == O_INPUT) {
            paintInput(g);
            return;
        }
        paintMap(g);
        if (ov == O_MSG) paintMsg(g);
        else if (ov == O_BUSY) paintBusy(g);
    }

    void paintStart(Graphics g, String title, String body) {
        g.setColor(MAIN);
        g.fillRect(0, 0, W, H);
        // a folded map
        int cx = W / 2, cy = H / 3;
        g.setColor(STEEL);
        g.fillTriangle(cx - 44, cy - 26, cx - 14, cy - 34, cx - 14, cy + 34);
        g.fillTriangle(cx - 44, cy - 26, cx - 14, cy + 34, cx - 44, cy + 42);
        g.setColor(0xE9EEF5);
        g.fillTriangle(cx - 14, cy - 34, cx + 16, cy - 26, cx + 16, cy + 42);
        g.fillTriangle(cx - 14, cy - 34, cx + 16, cy + 42, cx - 14, cy + 34);
        g.setColor(STEEL);
        g.fillTriangle(cx + 16, cy - 26, cx + 46, cy - 34, cx + 46, cy + 34);
        g.fillTriangle(cx + 16, cy - 26, cx + 46, cy + 34, cx + 16, cy + 42);
        g.setColor(GOLD);
        g.fillArc(cx - 9, cy - 22, 18, 18, 0, 360);
        g.fillTriangle(cx - 8, cy - 10, cx + 8, cy - 10, cx, cy + 6);
        g.setColor(MAIN);
        g.fillArc(cx - 4, cy - 17, 8, 8, 0, 360);
        int y = cy + 56;
        text(g, fTitle, GOLD, Ui.L("מפות ישראל", "Israel Maps"), W / 2, y, Graphics.TOP | Graphics.HCENTER);
        y += fTitle.h + 8;
        text(g, uR, 0xFFFFFF, title, W / 2, y, Graphics.TOP | Graphics.HCENTER);
        y += uR.h + 10;
        if (body != null) {
            Vector v = wrap(uS, body, W - 30);
            for (int i = 0; i < v.size(); i++, y += uS.h + 3) text(g, uS, SUB, (String) v.elementAt(i), W / 2, y, Graphics.TOP | Graphics.HCENTER);
        }
    }
}
