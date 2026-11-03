package dev.aibuild.script;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/** "Did you mean" suggestions by edit distance, for unknown block names and API functions. */
public final class Suggest {
    private Suggest() {
    }

    public static List<String> nearest(String word, Collection<String> candidates, int max) {
        if (word == null || word.isEmpty()) return List.of();
        record Hit(String name, int distance) { }
        List<Hit> hits = new ArrayList<>();
        int limit = Math.max(2, word.length() / 3);
        for (String c : candidates) {
            if (Math.abs(c.length() - word.length()) > limit + 4 && !c.contains(word) && !word.contains(c)) continue;
            int d = c.contains(word) || word.contains(c) ? Math.min(limit, Math.abs(c.length() - word.length())) : distance(word, c);
            if (d <= limit) hits.add(new Hit(c, d));
        }
        hits.sort(Comparator.comparingInt(Hit::distance).thenComparing(Hit::name));
        List<String> out = new ArrayList<>();
        for (Hit h : hits) {
            if (out.size() >= max) break;
            if (!h.name().equals(word)) out.add(h.name());
        }
        return out;
    }

    static int distance(String a, String b) {
        int[] prev = new int[b.length() + 1], cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()];
    }
}
