package mp;

/**
 * A computed route: its points (micro-degrees), the road segments it follows and the turn-by-turn instructions in
 * Hebrew. Turns are judged from the directions 25 m before and after each junction.
 */
final class Route {
    int n;
    int[] lat = new int[256], lon = new int[256], cum = new int[256];   // cum: metres from the start
    int segN;
    int[] segStart = new int[64], segAttr = new int[64], segLen = new int[64];
    String[] segName = new String[64];
    int fromLat, fromLon, toLat, toLon, profile;
    int length, seconds;

    // instructions
    int stepN;
    int[] stepPt, stepTurn;   // turn: 0 straight, 1..3 right (slight, turn, sharp), -1..-3 left, 10 start, 11 arrive, 12 roundabout
    String[] stepText;

    void addPoint(int la, int lo) {
        if (n > 0 && lat[n - 1] == la && lon[n - 1] == lo) return;
        if (n == lat.length) {
            lat = grow(lat);
            lon = grow(lon);
            cum = grow(cum);
        }
        lat[n] = la;
        lon[n] = lo;
        cum[n] = n == 0 ? 0 : cum[n - 1] + Router.dist(lat[n - 1], lon[n - 1], la, lo);
        n++;
    }

    void startSeg(String name, int attr, int len) {
        if (segN == segStart.length) {
            segStart = grow(segStart);
            segAttr = grow(segAttr);
            segLen = grow(segLen);
            String[] s = new String[segN * 2];
            System.arraycopy(segName, 0, s, 0, segN);
            segName = s;
        }
        segStart[segN] = n - 1;
        segAttr[segN] = attr;
        segLen[segN] = len;
        segName[segN] = name;
        segN++;
    }

    static int[] grow(int[] a) {
        int[] b = new int[a.length * 2];
        System.arraycopy(a, 0, b, 0, a.length);
        return b;
    }

    void finish() {
        length = n > 0 ? cum[n - 1] : 0;
        int t = 0;
        for (int i = 0; i < segN; i++) {
            int sp = Router.SPEED[profile][segAttr[i] & 15];
            if (sp <= 0) sp = 5;
            t += segLen[i] * 36 / (sp * 10);
            // junctions and lights in town
            if (profile == Router.CAR && (segAttr[i] & 15) >= 3) t += 2;
        }
        seconds = t;
    }

    /** the point index where the direction leaving point i is measured to, about d metres on */
    int ahead(int i, int d) {
        int j = i + 1;
        while (j < n - 1 && cum[j] - cum[i] < d) j++;
        return j < n ? j : n - 1;
    }

    int behind(int i, int d) {
        int j = i - 1;
        while (j > 0 && cum[i] - cum[j] < d) j--;
        return j < 0 ? 0 : j;
    }

    /** -3..3: the turn at point i (positive = right) */
    int turnAt(int i) {
        if (i <= 0 || i >= n - 1) return 0;
        int a = behind(i, 25), b = ahead(i, 25);
        float ax = (lon[i] - lon[a]) * 0.8526f, ay = lat[i] - lat[a];
        float bx = (lon[b] - lon[i]) * 0.8526f, by = lat[b] - lat[i];
        float la = (float) Math.sqrt(ax * ax + ay * ay), lb = (float) Math.sqrt(bx * bx + by * by);
        if (la == 0 || lb == 0) return 0;
        float cos = (ax * bx + ay * by) / (la * lb), cross = ax * by - ay * bx;
        int k;
        if (cos > 0.94f) k = 0;
        else if (cos > 0.71f) k = 1;
        else if (cos > -0.64f) k = 2;
        else k = 3;
        return cross > 0 ? -k : k;   // counter-clockwise (cross > 0) is a left turn
    }

    static String toName(String nm) {
        if (nm == null || nm.length() == 0) return "";
        boolean digits = true;
        for (int i = 0; i < nm.length(); i++) if (nm.charAt(i) < '0' || nm.charAt(i) > '9') digits = false;
        StringBuffer b = new StringBuffer(Ui.L(" אל ", " onto "));
        if (digits) b.append(Ui.L("כביש ", "road "));
        b.append(nm);
        return b.toString();
    }

    static String[] RIGHT = {"המשך ישר", "פנה קלות ימינה", "פנה ימינה", "פנה חדות ימינה"};
    static String[] LEFT = {"המשך ישר", "פנה קלות שמאלה", "פנה שמאלה", "פנה חדות שמאלה"};

    void steps() {
        int cap = segN + 2;
        stepPt = new int[cap];
        stepTurn = new int[cap];
        stepText = new String[cap];
        stepN = 0;
        StringBuffer b = new StringBuffer(Ui.L("צא לדרך", "Head off"));
        String first = segN > 0 ? segName[0] : "";
        if (first.length() > 0) {
            b.append(Ui.L(" לאורך ", " along "));
            b.append(first);
        }
        add(0, 10, b.toString());
        String cur = first;
        for (int i = 1; i < segN; i++) {
            int p = segStart[i];
            boolean rbIn = (segAttr[i] & 32) != 0, rbPrev = (segAttr[i - 1] & 32) != 0;
            String nm = segName[i];
            if (rbIn && rbPrev) continue;
            if (rbPrev) {   // left the roundabout: announced at its entry
                cur = nm;
                continue;
            }
            if (rbIn) {
                // the road after the roundabout
                String out = "";
                for (int j = i + 1; j < segN; j++) if ((segAttr[j] & 32) == 0) {
                    out = segName[j];
                    break;
                }
                StringBuffer t = new StringBuffer(Ui.L("בכיכר, צא", "At the roundabout, exit"));
                t.append(out.length() > 0 ? toName(out) : Ui.L(" ביציאה המתאימה", " at the right exit"));
                add(p, 12, t.toString());
                continue;
            }
            int turn = turnAt(p);
            boolean renamed = nm.length() > 0 && !nm.equals(cur);
            boolean link = (segAttr[i] & 512) != 0 && (segAttr[i - 1] & 512) == 0;
            int at = turn < 0 ? -turn : turn;
            if (at >= 2 || (at == 1 && (renamed || link)) || (renamed && segLen[i] + restLen(i, nm) >= 60)) {
                StringBuffer t = new StringBuffer();
                if (link && at <= 1) t.append(turn < 0 ? Ui.L("היצמד לשמאל", "Keep left") : Ui.L("היצמד לימין", "Keep right"));
                else t.append(turn < 0 ? LEFT[at] : RIGHT[at]);
                if (renamed || cur.length() == 0) t.append(toName(nm));
                add(p, turn, t.toString());
            }
            if (nm.length() > 0) cur = nm;
        }
        add(n - 1, 11, Ui.L("הגעת ליעד", "You have arrived"));
    }

    /** length of the run of segments after i with the same name */
    int restLen(int i, String nm) {
        int l = 0;
        for (int j = i + 1; j < segN && nm.equals(segName[j]); j++) l += segLen[j];
        return l;
    }

    void add(int pt, int turn, String text) {
        // two manoeuvres at one junction (divided roads): the later one says it all
        if (stepN > 1 && turn != 11 && stepTurn[stepN - 1] != 10 && cum[pt] - cum[stepPt[stepN - 1]] < 25) stepN--;
        if (stepN == stepPt.length) {
            stepPt = grow(stepPt);
            stepTurn = grow(stepTurn);
            String[] s = new String[stepN * 2];
            System.arraycopy(stepText, 0, s, 0, stepN);
            stepText = s;
        }
        stepPt[stepN] = pt;
        stepTurn[stepN] = turn;
        stepText[stepN] = text;
        stepN++;
    }

    /** index of the route point nearest to (la, lo) from point 'from' on (a window of the route), its distance in dist */
    int nearest(int la, int lo, int from, int[] dist) {
        int best = from, bd = Integer.MAX_VALUE;
        int end = Math.min(n, from + 400);
        for (int i = Math.max(0, from - 20); i < end; i++) {
            int d = Router.dist(la, lo, lat[i], lon[i]);
            if (d < bd) {
                bd = d;
                best = i;
            }
        }
        dist[0] = bd;
        return best;
    }
}
