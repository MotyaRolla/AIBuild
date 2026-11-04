package dev.aibuild.script;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cheap source checks around the Rhino dialect.
 *
 * <p>Rhino 1.9 runs most modern JavaScript, but (pinned by {@code ScriptDialectTest}) it rejects {@code class}
 * declarations and spread arguments in calls ({@code f(...xs)}); those get a one-line fix appended to the error
 * so a single repair call can fix them. Its {@code const} is function-scoped and assigned once: a {@code const}
 * inside a loop silently keeps its first value, and two blocks declaring the same name fail with "redeclaration".
 * Every {@code const} declaration is therefore rewritten to {@code let}, which Rhino scopes correctly. Line and
 * column numbers are never changed by preparation.</p>
 */
public final class ScriptLint {
    private static final Pattern FENCE = Pattern.compile("```(?:js|javascript)?\\s*\\n([\\s\\S]*?)```");
    private static final Pattern NOT_DEFINED = Pattern.compile("\"?([A-Za-z_$][\\w$]*)\"? is not defined");
    private static final Pattern NO_FUNCTION = Pattern.compile("Cannot find function ([A-Za-z_$][\\w$]*)");

    private ScriptLint() {
    }

    /**
     * Extracts the script from a model answer: the longest fenced js block if present, otherwise the whole text.
     */
    public static String extract(String answer) {
        if (answer == null) return "";
        Matcher m = FENCE.matcher(answer);
        String best = null;
        while (m.find()) {
            String body = m.group(1);
            if (best == null || body.length() > best.length()) best = body;
        }
        if (best != null) return best.strip() + "\n";
        String t = answer.strip();
        if (t.startsWith("```")) {
            int nl = t.indexOf('\n');
            t = nl < 0 ? "" : t.substring(nl + 1);
            if (t.endsWith("```")) t = t.substring(0, t.length() - 3);
        }
        return t.strip() + "\n";
    }

    /** Rewrites unsupported-but-equivalent syntax without changing line or column numbers. */
    public static String prepare(String source) {
        String s = source == null ? "" : source;
        StringBuilder out = new StringBuilder(s);
        int n = s.length();
        char prev = 0; // last significant code character, to tell a regex literal from division
        for (int i = 0; i < n; ) {
            char c = s.charAt(i);
            if (c == '/' && i + 1 < n && s.charAt(i + 1) == '/') {
                while (i < n && s.charAt(i) != '\n') i++;
            } else if (c == '/' && i + 1 < n && s.charAt(i + 1) == '*') {
                int end = s.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 2;
            } else if (c == '"' || c == '\'' || c == '`') {
                i = skipQuoted(s, i, c);
                prev = c;
            } else if (c == '/' && (prev == 0 || "(,=:[!&|?{};+-*%<>~^".indexOf(prev) >= 0)) {
                i = skipRegex(s, i);
                prev = '/';
            } else if (Character.isJavaIdentifierStart(c)) {
                int j = i;
                while (j < n && Character.isJavaIdentifierPart(s.charAt(j))) j++;
                if (s.startsWith("const", i) && j - i == 5 && prev != '.' && !nextIs(s, j, ':')) out.replace(i, j, "let  ");
                i = j;
                prev = 'a';
            } else {
                if (!Character.isWhitespace(c)) prev = c;
                i++;
            }
        }
        return out.toString();
    }

    /** A streamed answer cut to what can already run, and the name of the part the model is writing now. */
    public record Partial(String code, String part) {
    }

    private static final Pattern PART_NAME = Pattern.compile("\\bpart\\(\\s*['\"`]([^'\"`\\n]{1,40})['\"`]");

    /**
     * Cuts a partially streamed answer after its last complete top-level statement: a line end outside any
     * bracket, string, comment or template whose last code character does not continue an expression. Runs of
     * such prefixes let the build grow while the model is still writing. {@code code} is empty until a whole
     * statement has arrived.
     */
    public static Partial partial(CharSequence answer) {
        String t = answer == null ? "" : answer.toString();
        int fence = t.indexOf("```");
        String code;
        if (fence >= 0) {
            int nl = t.indexOf('\n', fence);
            if (nl < 0) return new Partial("", null);
            int close = t.indexOf("```", nl + 1);
            code = close < 0 ? t.substring(nl + 1) : t.substring(nl + 1, close);
        } else {
            code = t;
        }
        String part = null;
        Matcher m = PART_NAME.matcher(code);
        while (m.find()) part = m.group(1);
        int n = code.length(), depth = 0, cut = 0;
        char prev = 0;
        for (int i = 0; i < n; ) {
            char c = code.charAt(i);
            if (c == '/' && i + 1 < n && code.charAt(i + 1) == '/') {
                while (i < n && code.charAt(i) != '\n') i++;
            } else if (c == '/' && i + 1 < n && code.charAt(i + 1) == '*') {
                int end = code.indexOf("*/", i + 2);
                if (end < 0) break;
                i = end + 2;
            } else if (c == '"' || c == '\'' || c == '`') {
                int end = skipQuoted(code, i, c);
                if (end >= n || code.charAt(end - 1) != c) break; // still streaming
                i = end;
                prev = c;
            } else if (c == '/' && (prev == 0 || "(,=:[!&|?{};+-*%<>~^".indexOf(prev) >= 0)) {
                i = skipRegex(code, i);
                prev = '/';
            } else if (c == '\n') {
                if (depth == 0 && prev != 0 && "=,+-*/%&|?:.!<>(".indexOf(prev) < 0) cut = i + 1;
                i++;
            } else {
                if ("([{".indexOf(c) >= 0) depth++;
                else if (")]}".indexOf(c) >= 0) depth--;
                if (!Character.isWhitespace(c)) prev = Character.isJavaIdentifierPart(c) ? 'a' : c;
                i++;
            }
        }
        return new Partial(code.substring(0, cut), part);
    }

    /**
     * Source span {start, end} of the statement {@code part('name', ...)} (with its trailing semicolon), or null
     * when the script has no such call. Brackets inside strings, comments and regexes are ignored.
     */
    public static int[] partSpan(String source, String name) {
        Matcher m = PART_NAME.matcher(source);
        while (m.find()) {
            if (!m.group(1).equals(name) || inCommentOrString(source, m.start())) continue;
            int open = source.indexOf('(', m.start());
            int end = matchParen(source, open);
            if (end < 0) return null;
            int j = end;
            while (j < source.length() && (source.charAt(j) == ' ' || source.charAt(j) == '\t')) j++;
            if (j < source.length() && source.charAt(j) == ';') end = j + 1;
            return new int[]{m.start(), end};
        }
        return null;
    }

    /**
     * The script with the body of every part() call folded to a one-line comment, except the part {@code keep}
     * and the parts that enclose it: what a sculptor of one part needs to read (the top-level constants and
     * helpers, the part order, its own source) without the thousands of lines of the others. {@code describe}
     * gives a part's size and bounds for its comment (by name; may be empty).
     */
    public static String foldOtherParts(String source, String keep, java.util.Map<String, String> describe) {
        int[] target = partSpan(source, keep);
        if (target == null) return source;
        java.util.List<int[]> spans = new java.util.ArrayList<>();
        java.util.List<String> names = new java.util.ArrayList<>();
        Matcher m = PART_NAME.matcher(source);
        while (m.find()) {
            if (inCommentOrString(source, m.start())) continue;
            int open = source.indexOf('(', m.start());
            int end = matchParen(source, open);
            if (end < 0) continue;
            int j = end;
            while (j < source.length() && (source.charAt(j) == ' ' || source.charAt(j) == '\t')) j++;
            if (j < source.length() && source.charAt(j) == ';') end = j + 1;
            spans.add(new int[]{m.start(), end});
            names.add(m.group(1));
        }
        StringBuilder out = new StringBuilder();
        int cursor = 0;
        for (int i = 0; i < spans.size(); i++) {
            int[] s = spans.get(i);
            if (s[0] < cursor) continue;                                     // inside a part already folded or kept whole
            if (s[0] == target[0]) {                                         // the part itself, with everything in it
                out.append(source, cursor, s[1]);
                cursor = s[1];
                continue;
            }
            if (s[0] <= target[0] && s[1] >= target[1]) continue;            // encloses the part: open it up
            String body = source.substring(s[0], s[1]);
            long lines = body.chars().filter(c -> c == '\n').count() + 1;
            String what = describe.getOrDefault(names.get(i), "");
            out.append(source, cursor, s[0])
                    .append("part('").append(names.get(i)).append("', () => { /* ").append(lines).append(" lines not shown")
                    .append(what.isEmpty() ? "" : "; " + what).append(" */ })").append(body.endsWith(";") ? ";" : "");
            cursor = s[1];
        }
        out.append(source.substring(cursor));
        return out.toString();
    }

    /** Names of all part() calls written in the script, in source order. */
    public static java.util.List<String> partNames(String source) {
        java.util.List<String> out = new java.util.ArrayList<>();
        Matcher m = PART_NAME.matcher(source);
        while (m.find()) if (!inCommentOrString(source, m.start()) && !out.contains(m.group(1))) out.add(m.group(1));
        return out;
    }

    /** Index just after the parenthesis matching the one at {@code open}, or -1. */
    private static int matchParen(String s, int open) {
        int depth = 0, n = s.length();
        char prev = '(';
        for (int i = open; i < n; ) {
            char c = s.charAt(i);
            if (c == '/' && i + 1 < n && s.charAt(i + 1) == '/') {
                while (i < n && s.charAt(i) != '\n') i++;
            } else if (c == '/' && i + 1 < n && s.charAt(i + 1) == '*') {
                int e = s.indexOf("*/", i + 2);
                if (e < 0) return -1;
                i = e + 2;
            } else if (c == '"' || c == '\'' || c == '`') {
                i = skipQuoted(s, i, c);
                prev = c;
            } else if (c == '/' && "(,=:[!&|?{};+-*%<>~^".indexOf(prev) >= 0) {
                i = skipRegex(s, i);
                prev = '/';
            } else {
                if ("([{".indexOf(c) >= 0) depth++;
                else if (")]}".indexOf(c) >= 0 && --depth == 0) return i + 1;
                if (!Character.isWhitespace(c)) prev = Character.isJavaIdentifierPart(c) ? 'a' : c;
                i++;
            }
        }
        return -1;
    }

    private static boolean inCommentOrString(String s, int pos) {
        int n = Math.min(pos, s.length());
        char prev = 0;
        for (int i = 0; i < n; ) {
            char c = s.charAt(i);
            if (c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '/') {
                int e = s.indexOf('\n', i);
                if (e < 0 || e > pos) return true;
                i = e;
            } else if (c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '*') {
                int e = s.indexOf("*/", i + 2);
                if (e < 0 || e + 2 > pos) return true;
                i = e + 2;
            } else if (c == '"' || c == '\'' || c == '`') {
                int e = skipQuoted(s, i, c);
                if (e > pos) return true;
                i = e;
                prev = c;
            } else {
                if (!Character.isWhitespace(c)) prev = c;
                i++;
            }
        }
        return false;
    }

    private static int skipQuoted(String s, int i, char q) {
        for (i++; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\') i++;
            else if (c == q) return i + 1;
            else if (c == '\n' && q != '`') return i;
        }
        return i;
    }

    private static int skipRegex(String s, int i) {
        boolean inClass = false;
        for (i++; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\') i++;
            else if (c == '[') inClass = true;
            else if (c == ']') inClass = false;
            else if (c == '/' && !inClass) return i + 1;
            else if (c == '\n') return i;
        }
        return i;
    }

    private static boolean nextIs(String s, int i, char want) {
        while (i < s.length() && (s.charAt(i) == ' ' || s.charAt(i) == '\t')) i++;
        return i < s.length() && s.charAt(i) == want;
    }

    /**
     * Static problems that make running pointless: runaway repetition from a looping model, or an empty script.
     * Returns null when the script may run.
     */
    public static ScriptError precheck(String source) {
        if (source == null || source.isBlank()) return new ScriptError("lint", "the answer contains no script", 0, 0, "", "");
        Map<String, Integer> seen = new HashMap<>();
        String[] lines = source.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i].strip();
            if (l.length() < 24) continue;
            int n = seen.merge(l, 1, Integer::sum);
            if (n > 40) {
                return new ScriptError("lint", "the same line repeats more than 40 times; use a loop or a function instead",
                        i + 1, 0, ScriptError.excerpt(source, i + 1, 1), "");
            }
        }
        return null;
    }

    /** Adds a fix hint for known dialect gaps and "did you mean" suggestions for unknown API names. */
    public static ScriptError explain(ScriptError e, String source, Set<String> api) {
        if (e == null) return null;
        String msg = e.message() == null ? "" : e.message();
        String hint = null;
        String line = lineOf(source, e.line());
        if (msg.contains("reserved word: class") || line.matches(".*\\bclass\\s+\\w+.*")) {
            hint = "class syntax is not supported here: use plain functions and object literals";
        } else if (msg.startsWith("syntax error") && line.matches(".*\\w\\s*\\(\\s*\\.\\.\\..*|.*,\\s*\\.\\.\\.\\w.*\\).*")) {
            hint = "spread arguments in calls f(...xs) are not supported: use f.apply(null, xs) or pass the array itself";
        } else {
            Matcher m = NOT_DEFINED.matcher(msg);
            Matcher f = NO_FUNCTION.matcher(msg);
            String name = m.find() ? m.group(1) : f.find() ? f.group(1) : null;
            if (name != null) {
                List<String> near = Suggest.nearest(name, api, 3);
                hint = near.isEmpty() ? "'" + name + "' is not part of the build API (see the API card)"
                        : "did you mean " + String.join(", ", near) + "?";
            }
        }
        if (hint == null) return e;
        return new ScriptError(e.kind(), msg + " — " + hint, e.line(), e.column(), e.excerpt(), e.stack());
    }

    private static String lineOf(String source, int line) {
        if (source == null || line <= 0) return "";
        String[] lines = source.split("\n", -1);
        return line <= lines.length ? lines[line - 1] : "";
    }
}
