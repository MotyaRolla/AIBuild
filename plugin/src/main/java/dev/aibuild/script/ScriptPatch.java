package dev.aibuild.script;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Applies a model's revision to a build script. The answer either contains SEARCH/REPLACE hunks
 *
 * <pre>
 * &lt;&lt;&lt;&lt;&lt;&lt;&lt; SEARCH
 * exact existing lines
 * =======
 * new lines
 * &gt;&gt;&gt;&gt;&gt;&gt;&gt; REPLACE
 * </pre>
 *
 * or one complete script in a fenced js block. Hunks must match exactly once; a hunk that only matches after
 * ignoring indentation and trailing spaces is accepted too, because models often re-indent. A hunk that
 * matches nowhere or several times rejects the whole revision, with a message naming the hunk.
 */
public final class ScriptPatch {
    private static final Pattern HUNK = Pattern.compile(
            "<{5,9} ?SEARCH[^\\n]*\\n(.*?)\\n?={5,9}[^\\n]*\\n(.*?)\\n?>{5,9} ?REPLACE", Pattern.DOTALL);

    /** Outcome: the new source, or an error explaining which hunk failed. */
    public record Result(String source, String error, int hunks, boolean fullRewrite) {
        public boolean ok() {
            return error == null;
        }
    }

    private ScriptPatch() {
    }

    public static boolean hasHunks(String answer) {
        return answer != null && HUNK.matcher(answer).find();
    }

    public static Result apply(String source, String answer) {
        if (answer == null || answer.isBlank()) return new Result(source, "empty revision", 0, false);
        List<String[]> hunks = hunksOf(answer);
        if (hunks.isEmpty()) {
            String full = ScriptLint.extract(answer);
            if (full.isBlank() || !full.contains("(")) return new Result(source, "the revision contains no SEARCH/REPLACE hunks and no script", 0, false);
            return new Result(full, null, 0, true);
        }
        return applyHunks(source, hunks, true);
    }

    /**
     * The SEARCH/REPLACE pairs of an answer, in order; an answer without hunks yields an empty list (its
     * revision is a complete rewrite, which cannot be applied in part).
     */
    public static List<String[]> hunksOf(String answer) {
        List<String[]> hunks = new ArrayList<>();
        if (answer == null) return hunks;
        Matcher m = HUNK.matcher(answer);
        while (m.find()) hunks.add(new String[]{m.group(1), m.group(2)});
        return hunks;
    }

    /**
     * Applies a list of hunks to a source, skipping the ones whose SEARCH text no longer matches (a hunk
     * written against a region an earlier hunk already changed). The model writes every hunk against the
     * same current source, so a subset applied in order is exactly the same revision with the other hunks
     * left out — this is what lets a review keep the good hunks of a rejected revision.
     *
     * <p>A hunk that matches more than once, or nowhere, is skipped rather than failing the whole list;
     * {@code strict} (used by {@link #apply}) keeps the old behaviour of failing loudly, because a
     * whole-revision application must match exactly what the model wrote.</p>
     */
    public static Result applyHunks(String source, List<String[]> hunks) {
        return applyHunks(source, hunks, false);
    }

    static Result applyHunks(String source, List<String[]> hunks, boolean strict) {
        if (hunks == null || hunks.isEmpty()) return new Result(source, "no hunks", 0, false);
        String out = source;
        int applied = 0;
        for (int i = 0; i < hunks.size(); i++) {
            String search = hunks.get(i)[0], replace = hunks.get(i)[1];
            if (search.isBlank()) {
                out = out.endsWith("\n") ? out + replace + "\n" : out + "\n" + replace + "\n";
                applied++;
                continue;
            }
            int first = out.indexOf(search);
            if (first >= 0) {
                if (out.indexOf(search, first + 1) >= 0) {
                    if (strict) return new Result(source, "hunk " + (i + 1) + " matches more than once; include more context lines", hunks.size(), false);
                    continue;
                }
                out = out.substring(0, first) + replace + out.substring(first + search.length());
                applied++;
                continue;
            }
            int[] loose = looseMatch(out, search);
            if (loose == null) {
                if (strict) return new Result(source, "hunk " + (i + 1) + " SEARCH text was not found:\n" + clip(search), hunks.size(), false);
                continue;
            }
            if (loose[0] < 0) {
                if (strict) return new Result(source, "hunk " + (i + 1) + " matches more than once; include more context lines", hunks.size(), false);
                continue;
            }
            out = out.substring(0, loose[0]) + replace + out.substring(loose[1]);
            applied++;
        }
        if (strict) return new Result(out, null, hunks.size(), false);
        return new Result(out, null, applied, false);
    }

    /** Line-wise match ignoring surrounding whitespace; returns {start,end} offsets, {-1,-1} if ambiguous, or null. */
    private static int[] looseMatch(String text, String search) {
        String[] lines = text.split("\n", -1);
        String[] want = search.strip().split("\n");
        for (int i = 0; i < want.length; i++) want[i] = want[i].strip();
        int[] offsets = new int[lines.length + 1];
        for (int i = 0; i < lines.length; i++) offsets[i + 1] = offsets[i] + lines[i].length() + 1;
        int[] hit = null;
        for (int i = 0; i + want.length <= lines.length; i++) {
            boolean ok = true;
            for (int j = 0; j < want.length && ok; j++) ok = lines[i + j].strip().equals(want[j]);
            if (!ok) continue;
            if (hit != null) return new int[]{-1, -1};
            hit = new int[]{offsets[i], Math.min(text.length(), offsets[i + want.length] - 1)};
        }
        return hit;
    }

    /**
     * Replaces the {@code part('name', ...)} statement of {@code source} with {@code replacement} (which must
     * itself be a part('name', ...) statement, usually a sculptor's rewrite). The replacement may be a whole
     * answer: its fenced code is used.
     */
    public static Result replacePart(String source, String name, String replacement) {
        String code = ScriptLint.extract(replacement);
        if (code.isBlank()) code = replacement == null ? "" : replacement.strip();
        int[] at = ScriptLint.partSpan(source, name);
        if (at == null) return new Result(source, "the script has no part('" + name + "')", 0, false);
        int[] mine = ScriptLint.partSpan(code, name);
        if (mine == null) return new Result(source, "the rewrite must be one part('" + name + "', () => { ... }) call", 0, false);
        // only the part statement itself: top-level helpers around it could redeclare names of the script
        return new Result(source.substring(0, at[0]) + code.substring(mine[0], mine[1]) + source.substring(at[1]), null, 1, false);
    }

    /**
     * Like {@link #replacePart}, but appends the statement at the end of the script when it has no such part yet
     * (a new part that runs after everything else, such as the interior designer's furnishing).
     */
    public static Result putPart(String source, String name, String replacement) {
        if (ScriptLint.partSpan(source, name) != null) return replacePart(source, name, replacement);
        String code = ScriptLint.extract(replacement);
        if (code.isBlank()) code = replacement == null ? "" : replacement.strip();
        int[] mine = ScriptLint.partSpan(code, name);
        if (mine == null) return new Result(source, "the answer must be one part('" + name + "', () => { ... }) call", 0, false);
        String base = source.endsWith("\n") ? source : source + "\n";
        return new Result(base + code.substring(mine[0], mine[1]) + "\n", null, 1, false);
    }

    private static final java.util.regex.Pattern OWN_CONFINE =
            java.util.regex.Pattern.compile("\\b(?:function|var|let|const)\\s+confine\\b|\\bconfine\\s*=");

    /**
     * The script with the rule {@code confine('name', rule)} as its first line, so every run of it skips the part's
     * out-of-scope writes the same way (see {@code Canvas.confine}). Unchanged when the rule is already there, or
     * when the script defines its own {@code confine}, which would shadow the library's.
     */
    public static String confine(String source, String name, String rule) {
        String line = "confine('" + name.replace("\\", "\\\\").replace("'", "\\'") + "', '" + rule + "');";
        if (source.contains(line) || OWN_CONFINE.matcher(source).find()) return source;
        return line + "\n" + source;
    }

    /** The source text of one part() statement, or null. */
    public static String partSource(String source, String name) {
        int[] at = ScriptLint.partSpan(source, name);
        return at == null ? null : source.substring(at[0], at[1]);
    }

    /** The script with 1-based line numbers, for prompts that ask for hunks. */
    public static String numbered(String source) {
        String[] lines = source.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i == lines.length - 1 && lines[i].isEmpty()) break;
            sb.append(String.format("%4d| ", i + 1)).append(lines[i]).append('\n');
        }
        return sb.toString();
    }

    private static String clip(String s) {
        return s.length() > 300 ? s.substring(0, 299) + "…" : s;
    }
}
