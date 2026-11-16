package mp;

/**
 * The Unicode Bidirectional Algorithm for one paragraph without explicit embeddings (UAX #9 rules P2-P3, W1-W7,
 * N1-N2, I1-I2, and L1-L2 per line; L4 mirroring is done by the caller with mirror()). LRM / RLM count as strong
 * characters; embedding / isolate controls are ignored like boundary neutrals. Combining marks stay with their base
 * character: reorder() returns the visual order of the base characters only, each followed (when drawn) by its marks.
 */
final class Bidi {
    static final byte L = 0, R = 1, EN = 2, ES = 3, ET = 4, AN = 5, CS = 6, NSM = 7, BN = 8, B = 9, S = 10, WS = 11, ON = 12;

    static byte type(char c) {
        if (c < 0x80) {
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')) return L;
            if (c >= '0' && c <= '9') return EN;
            if (c == ' ') return WS;
            if (c == '+' || c == '-') return ES;
            if (c == '#' || c == '$' || c == '%') return ET;
            if (c == ',' || c == '.' || c == ':' || c == '/') return CS;
            if (c == '\t' || c == 0x1F) return S;
            if (c == '\n' || c == '\r') return B;
            if (c < ' ' || c == 0x7F) return BN;
            return ON;
        }
        if (c >= 0x590 && c <= 0x5FF) {
            if (c >= 0x5D0 || c == 0x5BE || c == 0x5C0 || c == 0x5C3 || c == 0x5C6) return R;
            return NSM;
        }
        if (c >= 0xE000 && c <= 0xE0FF) return R;  // precomposed pointed letters
        if (c >= 0xFB1D && c <= 0xFB4F) return R;
        if (c >= 0x600 && c <= 0x6FF) {
            if (c >= 0x660 && c <= 0x669) return AN;
            if (c >= 0x64B && c <= 0x65F) return NSM;
            return R;
        }
        if (c >= 0x300 && c <= 0x36F) return NSM;
        if (c == 0x200F) return R;
        if (c == 0x200E) return L;
        if (c == 0xA0) return CS;
        if (c >= 0x2000 && c <= 0x200A) return WS;
        if (c == 0x200B || c == 0x200C || c == 0x200D || c == 0xAD || c == 0xFEFF || (c >= 0x202A && c <= 0x202E) || (c >= 0x2066 && c <= 0x2069)) return BN;
        if (c == 0x2028) return WS;
        if (c == 0x2029) return B;
        if (c == 0xB0 || c == 0x2030 || (c >= 0xA2 && c <= 0xA5) || c == 0x20AA || c == 0x20AC) return ET;
        if (c == 0xD7 || c == 0xF7 || (c >= 0xA1 && c <= 0xBF) || (c >= 0x2010 && c <= 0x2BFF)) return ON;
        return L;
    }

    static boolean strongR(byte t) { return t == R || t == EN || t == AN; }

    /** first strong character: 1 (R), 0 (L) or -1 */
    static int firstStrong(char[] t, int s, int e) {
        for (int i = s; i < e; i++) {
            byte k = type(t[i]);
            if (k == L) return 0;
            if (k == R) return 1;
        }
        return -1;
    }

    private byte[] ty = new byte[256];

    /**
     * resolves the embedding level of each character of t[0..n) into lv; dir 1 = RTL, 2 = LTR, otherwise the first
     * strong character decides (def when there is none). Returns the paragraph level.
     */
    int resolve(char[] t, int n, int dir, int def, byte[] lv) {
        int para;
        if (dir == 1) para = 1;
        else if (dir == 2) para = 0;
        else {
            int f = firstStrong(t, 0, n);
            para = f < 0 ? def : f;
        }
        if (ty.length < n) ty = new byte[n + 64];
        byte[] k = ty;
        byte sos = para == 1 ? R : L;
        for (int i = 0; i < n; i++) k[i] = type(t[i]);
        // W1: NSM takes the type of the previous character
        byte prev = sos;
        for (int i = 0; i < n; i++) {
            if (k[i] == NSM) k[i] = prev;
            else if (k[i] != BN) prev = k[i];
        }
        // W4: a single separator between two numbers
        for (int i = 1; i + 1 < n; i++) {
            if (k[i] == ES || k[i] == CS) {
                int a = i - 1, b = i + 1;
                if (k[a] == EN && k[b] == EN) k[i] = EN;
                else if (k[i] == CS && k[a] == AN && k[b] == AN) k[i] = AN;
            }
        }
        // W5: terminators next to European numbers
        for (int i = 0; i < n; i++) {
            if (k[i] == ET) {
                int j = i;
                while (j < n && (k[j] == ET || k[j] == BN)) j++;
                boolean en = (i > 0 && k[i - 1] == EN) || (j < n && k[j] == EN);
                if (en) for (int m = i; m < j; m++) k[m] = EN;
                i = j - 1;
            }
        }
        // W6: other separators and terminators are neutral
        for (int i = 0; i < n; i++) if (k[i] == ES || k[i] == ET || k[i] == CS) k[i] = ON;
        // W7: European numbers after L (or at a start in L) are L
        byte strong = sos;
        for (int i = 0; i < n; i++) {
            if (k[i] == L || k[i] == R) strong = k[i];
            else if (k[i] == EN && strong == L) k[i] = L;
        }
        // N1 / N2: neutrals between two strong characters of one direction take it, otherwise the embedding direction
        for (int i = 0; i < n; i++) {
            byte c = k[i];
            if (c == ON || c == WS || c == S || c == B || c == BN) {
                int j = i;
                while (j < n && (k[j] == ON || k[j] == WS || k[j] == S || k[j] == B || k[j] == BN)) j++;
                byte before = i == 0 ? sos : (strongR(k[i - 1]) ? R : L);
                byte after = j >= n ? sos : (strongR(k[j]) ? R : L);
                byte v = before == after ? before : sos;
                for (int m = i; m < j; m++) k[m] = v;
                i = j - 1;
            }
        }
        // I1 / I2
        for (int i = 0; i < n; i++) {
            byte c = k[i];
            int l = para;
            if (para == 0) {
                if (c == R) l = 1;
                else if (c == AN || c == EN) l = 2;
            } else {
                if (c == L || c == EN || c == AN) l = 2;
            }
            lv[i] = (byte) l;
        }
        return para;
    }

    private int[] cl = new int[256];
    private byte[] cv = new byte[256];

    /**
     * visual order (left to right) of the base characters of line t[s..e) with levels lv; the line's trailing
     * white space goes to the paragraph level (L1). Writes base indices into out and returns their count.
     */
    int reorder(char[] t, byte[] lv, int s, int e, int para, int[] out) {
        int m = 0;
        if (cl.length < e - s + 1) {
            cl = new int[e - s + 64];
            cv = new byte[e - s + 64];
        }
        int trail = e;
        while (trail > s) {
            byte k = type(t[trail - 1]);
            if (k == WS || k == S || k == BN || k == B) trail--;
            else break;
        }
        for (int i = s; i < e; i++) {
            char c = t[i];
            if (BFont.isMark(c) && m > 0) continue;
            cl[m] = i;
            byte k = type(c);
            cv[m] = (byte) (i >= trail || k == S || k == B ? para : lv[i]);
            m++;
        }
        int max = 0, minOdd = 127;
        for (int i = 0; i < m; i++) {
            if (cv[i] > max) max = cv[i];
            if ((cv[i] & 1) != 0 && cv[i] < minOdd) minOdd = cv[i];
        }
        for (int lev = max; lev >= minOdd && lev > 0; lev--) {
            int i = 0;
            while (i < m) {
                if (cv[i] >= lev) {
                    int j = i;
                    while (j < m && cv[j] >= lev) j++;
                    for (int a = i, b = j - 1; a < b; a++, b--) {
                        int x = cl[a];
                        cl[a] = cl[b];
                        cl[b] = x;
                        byte y = cv[a];
                        cv[a] = cv[b];
                        cv[b] = y;
                    }
                    i = j;
                } else i++;
            }
        }
        System.arraycopy(cl, 0, out, 0, m);
        return m;
    }

    static char mirror(char c) {
        switch (c) {
            case '(': return ')';
            case ')': return '(';
            case '[': return ']';
            case ']': return '[';
            case '{': return '}';
            case '}': return '{';
            case '<': return '>';
            case '>': return '<';
            case 0xAB: return 0xBB;
            case 0xBB: return 0xAB;
            default: return c;
        }
    }
}
