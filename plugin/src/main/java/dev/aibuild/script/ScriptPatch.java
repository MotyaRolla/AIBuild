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
        Matcher m = HUNK.matcher(answer);
        List<String[]> hunks = new ArrayList<>();
        while (m.find()) hunks.add(new String[]{m.group(1), m.group(2)});
        if (hunks.isEmpty()) {
            String full = ScriptLint.extract(answer);
            if (full.isBlank() || !full.contains("(")) return new Result(source, "the revision contains no SEARCH/REPLACE hunks and no script", 0, false);
            return new Result(full, null, 0, true);
        }
        String out = source;
        for (int i = 0; i < hunks.size(); i++) {
            String search = hunks.get(i)[0], replace = hunks.get(i)[1];
            if (search.isBlank()) {
                out = out.endsWith("\n") ? out + replace + "\n" : out + "\n" + replace + "\n";
                continue;
            }
            int first = out.indexOf(search);
            if (first >= 0) {
                if (out.indexOf(search, first + 1) >= 0) return new Result(source, "hunk " + (i + 1) + " matches more than once; include more context lines", hunks.size(), false);
                out = out.substring(0, first) + replace + out.substring(first + search.length());
                continue;
            }
            int[] loose = looseMatch(out, search);
            if (loose == null) return new Result(source, "hunk " + (i + 1) + " SEARCH text was not found:\n" + clip(search), hunks.size(), false);
            if (loose[0] < 0) return new Result(source, "hunk " + (i + 1) + " matches more than once; include more context lines", hunks.size(), false);
            out = out.substring(0, loose[0]) + replace + out.substring(loose[1]);
        }
        return new Result(out, null, hunks.size(), false);
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
