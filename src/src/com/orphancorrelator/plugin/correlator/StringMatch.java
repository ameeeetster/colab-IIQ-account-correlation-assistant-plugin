package com.orphancorrelator.plugin.correlator;

import java.text.Normalizer;

/**
 * Pure string-similarity and normalization helpers for the orphan matching
 * engine. IIQ/BeanShell ships none of these (no Jaro-Winkler, no Levenshtein),
 * so they are implemented here with no external dependencies.
 *
 * All methods are null-safe and side-effect free. Similarity methods return a
 * ratio in [0.0, 1.0] where 1.0 is an exact (post-normalization) match.
 */
public final class StringMatch {

    private StringMatch() {}

    /**
     * L2: defensive cap on inputs to the O(n*m) similarity metrics, to bound CPU
     * and memory if a directory hands us a pathologically long attribute value.
     * 256 is far beyond any real login, name, email local-part or DN leaf.
     */
    private static final int MAX_LEN = 256;
    private static String cap(String s) {
        return (s != null && s.length() > MAX_LEN) ? s.substring(0, MAX_LEN) : s;
    }

    // =====================================================================
    // Normalization
    // =====================================================================

    /**
     * Normalize a LOGIN/username for comparison: strip a DOMAIN\ prefix, strip
     * an @domain suffix, lowercase, drop diacritics, then remove everything
     * that is not a letter or digit (dots, hyphens, underscores, spaces).
     * e.g. "WONKA\\John.Smith" -> "johnsmith", "john.smith@x.com" -> "johnsmith".
     */
    public static String normLogin(String s) {
        if (s == null) { return ""; }
        String v = s.trim();
        int slash = v.lastIndexOf('\\');
        if (slash >= 0) { v = v.substring(slash + 1); }
        int at = v.indexOf('@');
        if (at >= 0) { v = v.substring(0, at); }
        v = stripDiacritics(v).toLowerCase();
        return v.replaceAll("[^a-z0-9]", "");
    }

    /**
     * Normalize a NAME for token comparison: handle "Last, First" -> "first last",
     * lowercase, drop diacritics, collapse non-alphanumeric runs to single spaces,
     * and trim. Preserves word boundaries (unlike normLogin) so tokens survive.
     */
    public static String normName(String s) {
        if (s == null) { return ""; }
        String v = s.trim();
        if (v.indexOf(',') >= 0) {
            String[] parts = v.split(",", 2);
            v = parts[1].trim() + " " + parts[0].trim();   // "Smith, John" -> "John Smith"
        }
        v = stripDiacritics(v).toLowerCase();
        v = v.replaceAll("[^a-z0-9]+", " ").trim();
        return v.replaceAll("\\s+", " ");
    }

    /**
     * Normalize an IDENTIFIER (e.g. employee ID) for EXACT comparison: trim and
     * lowercase only. Unlike normLogin it preserves dashes, dots and leading
     * zeros, so "12-34" != "1234" and "00123" != "123" - critical because the
     * employee-ID signal is the strongest and must not collide false-positively.
     */
    public static String normId(String s) {
        return (s == null) ? "" : s.trim().toLowerCase();
    }

    /** Local part of an email/UPN, lowercased and trimmed; "" if none. */
    public static String emailLocalPart(String s) {
        if (s == null) { return ""; }
        String v = s.trim().toLowerCase();
        int at = v.indexOf('@');
        return (at >= 0) ? v.substring(0, at) : v;
    }

    /** Remove accents/diacritics (e.g. "José" -> "Jose"). */
    public static String stripDiacritics(String s) {
        if (s == null) { return ""; }
        String n = Normalizer.normalize(s, Normalizer.Form.NFD);
        return n.replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
    }

    // =====================================================================
    // Similarity metrics (0.0 - 1.0)
    // =====================================================================

    /** Levenshtein edit-distance ratio: 1 - distance/maxLen. */
    public static double levenshteinRatio(String a, String b) {
        if (a == null) { a = ""; }
        if (b == null) { b = ""; }
        a = cap(a); b = cap(b);
        int max = Math.max(a.length(), b.length());
        if (max == 0) { return 1.0; }
        int dist = levenshtein(a, b);
        return 1.0 - ((double) dist / (double) max);
    }

    /** Classic Levenshtein edit distance (two-row DP, O(n) memory). */
    public static int levenshtein(String a, String b) {
        if (a == null) { a = ""; }
        if (b == null) { b = ""; }
        a = cap(a); b = cap(b);
        int n = a.length(), m = b.length();
        if (n == 0) { return m; }
        if (m == 0) { return n; }
        int[] prev = new int[m + 1];
        int[] curr = new int[m + 1];
        for (int j = 0; j <= m; j++) { prev[j] = j; }
        for (int i = 1; i <= n; i++) {
            curr[0] = i;
            char ca = a.charAt(i - 1);
            for (int j = 1; j <= m; j++) {
                int cost = (ca == b.charAt(j - 1)) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev; prev = curr; curr = tmp;
        }
        return prev[m];
    }

    /** Jaro-Winkler similarity (favours common prefixes; good for names/logins). */
    public static double jaroWinkler(String a, String b) {
        if (a == null) { a = ""; }
        if (b == null) { b = ""; }
        a = cap(a); b = cap(b);
        double jaro = jaro(a, b);
        // Winkler prefix boost: up to 4 leading chars, scaling factor 0.1
        int prefix = 0;
        int maxPrefix = Math.min(4, Math.min(a.length(), b.length()));
        for (int i = 0; i < maxPrefix; i++) {
            if (a.charAt(i) == b.charAt(i)) { prefix++; } else { break; }
        }
        return jaro + (prefix * 0.1 * (1.0 - jaro));
    }

    private static double jaro(String a, String b) {
        int la = a.length(), lb = b.length();
        if (la == 0 && lb == 0) { return 1.0; }
        if (la == 0 || lb == 0) { return 0.0; }

        int matchDistance = Math.max(la, lb) / 2 - 1;
        if (matchDistance < 0) { matchDistance = 0; }

        boolean[] aMatches = new boolean[la];
        boolean[] bMatches = new boolean[lb];
        int matches = 0;

        for (int i = 0; i < la; i++) {
            int start = Math.max(0, i - matchDistance);
            int end   = Math.min(i + matchDistance + 1, lb);
            for (int j = start; j < end; j++) {
                if (bMatches[j] || a.charAt(i) != b.charAt(j)) { continue; }
                aMatches[i] = true;
                bMatches[j] = true;
                matches++;
                break;
            }
        }
        if (matches == 0) { return 0.0; }

        double transpositions = 0;
        int k = 0;
        for (int i = 0; i < la; i++) {
            if (!aMatches[i]) { continue; }
            while (!bMatches[k]) { k++; }
            if (a.charAt(i) != b.charAt(k)) { transpositions++; }
            k++;
        }
        transpositions /= 2.0;

        double m = matches;
        return ((m / la) + (m / lb) + ((m - transpositions) / m)) / 3.0;
    }

    /**
     * Token-set name similarity: order-independent overlap of name tokens.
     * "John Smith" vs "Smith John" -> 1.0. Uses exact token equality after
     * normName(); returns |intersection| / |union of distinct tokens|.
     */
    public static double tokenSetRatio(String a, String b) {
        String na = normName(a), nb = normName(b);
        if (na.isEmpty() && nb.isEmpty()) { return 1.0; }
        if (na.isEmpty() || nb.isEmpty()) { return 0.0; }
        java.util.Set<String> sa = new java.util.HashSet<String>(java.util.Arrays.asList(na.split(" ")));
        java.util.Set<String> sb = new java.util.HashSet<String>(java.util.Arrays.asList(nb.split(" ")));
        java.util.Set<String> inter = new java.util.HashSet<String>(sa);
        inter.retainAll(sb);
        java.util.Set<String> union = new java.util.HashSet<String>(sa);
        union.addAll(sb);
        return union.isEmpty() ? 0.0 : (double) inter.size() / (double) union.size();
    }
}
