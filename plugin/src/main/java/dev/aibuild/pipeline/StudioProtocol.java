package dev.aibuild.pipeline;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.aibuild.script.ScriptPatch;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What one studio turn of the model asks for. The model answers in plain text with any of:
 *
 * <ul>
 *   <li>a ```js block with the whole script, or SEARCH/REPLACE hunks against the current script (in a fence or bare);</li>
 *   <li>```look with a JSON view or a list of up to four views;</li>
 *   <li>```inspect with a JSON box {box:[x1,y1,z1,x2,y2,z2]}, for exact block layers;</li>
 *   <li>```guide with one or more handbook topics;</li>
 *   <li>```delegate with {part, brief} (or a list): a helper session refines that part in parallel;</li>
 *   <li>commands on a line of their own: REVERT n, FINISH, WAIT, ACCEPT dN, DISCARD dN, DONE [n], PICK name=choice,
 *   KEEP n (a red-pen mark is intended), PREFER A|B (the answer to a blind comparison).</li>
 * </ul>
 *
 * <p>Parsing never fails: a block it cannot read becomes a {@link Turn#problems problem} the reply names, so the
 * model learns what went wrong instead of the turn being lost.</p>
 */
public final class StudioProtocol {
    private StudioProtocol() {
    }

    /** A helper request. */
    public record Delegate(String part, String brief) {
    }

    /** Everything one answer asks for. Lists are never null. */
    public record Turn(String script, List<String[]> hunks, List<JsonObject> looks, List<int[]> inspects, List<String> guides,
                       List<Delegate> delegates, List<Integer> accepts, List<Integer> discards, Integer revert,
                       boolean finish, boolean waitHelpers, boolean done, Integer doneVersion, List<String> problems,
                       List<String[]> picks, List<Integer> keeps, Character prefer) {
        /** True when the turn changes the script. */
        public boolean changes() {
            return script != null || !hunks.isEmpty();
        }

        /** True when the turn asks for anything at all. */
        public boolean acts() {
            return changes() || !looks.isEmpty() || !inspects.isEmpty() || !guides.isEmpty() || !delegates.isEmpty()
                    || !accepts.isEmpty() || !discards.isEmpty() || revert != null || finish || waitHelpers || done
                    || !picks.isEmpty() || !keeps.isEmpty() || prefer != null;
        }
    }

    private static final Pattern FENCE = Pattern.compile("```([A-Za-z]*)[^\\n]*\\n([\\s\\S]*?)```");
    private static final Pattern COMMAND = Pattern.compile(
            "(?m)^\\s*(?:\\*\\*)?(DONE|REVERT|FINISH|WAIT|ACCEPT|DISCARD)(?:\\*\\*)?\\b[ \\t]*:?[ \\t]*(?:v|d)?(\\d+)?[ \\t]*(?:\\*\\*)?[ \\t]*$");

    private static final Pattern PICK = Pattern.compile("(?m)^\\s*(?:\\*\\*)?PICK(?:\\*\\*)?\\b:?[ \\t]*(.+)$");
    private static final Pattern KEEP = Pattern.compile("(?m)^\\s*(?:\\*\\*)?KEEP(?:\\*\\*)?\\b:?[ \\t]*([0-9][0-9 ,\\t]*)$");
    private static final Pattern PREFER = Pattern.compile("(?m)^\\s*(?:\\*\\*)?PREFER(?:\\*\\*)?\\b:?[ \\t]*\\(?([ABab])\\)?[ \\t.]*(?:\\*\\*)?[ \\t]*$");

    public static Turn parse(String answer) {
        String text = answer == null ? "" : answer;
        List<String> problems = new ArrayList<>();
        List<JsonObject> looks = new ArrayList<>();
        List<int[]> inspects = new ArrayList<>();
        List<String> guides = new ArrayList<>();
        List<Delegate> delegates = new ArrayList<>();
        String script = null;
        // tool fences are cut out, so neither the hunk parser nor the commands see their contents
        StringBuilder rest = new StringBuilder();
        Matcher m = FENCE.matcher(text);
        int last = 0;
        while (m.find()) {
            String tag = m.group(1).toLowerCase(Locale.ROOT);
            String body = m.group(2);
            boolean tool = true;
            switch (tag) {
                case "look", "view", "views" -> readLooks(body, looks, problems);
                case "inspect" -> readInspect(body, inspects, problems);
                case "guide", "handbook" -> {
                    for (String line : body.split("[\\n,]")) {
                        String t = line.strip().toLowerCase(Locale.ROOT);
                        if (!t.isEmpty()) guides.add(t);
                    }
                }
                case "delegate" -> readDelegates(body, delegates, problems);
                default -> tool = false;
            }
            if (tool) {
                rest.append(text, last, m.start());
                last = m.end();
            } else if ((tag.isEmpty() || tag.equals("js") || tag.equals("javascript")) && !ScriptPatch.hasHunks(body)
                    && body.contains("(") && (script == null || body.length() > script.length())) {
                script = body.strip() + "\n";
            }
        }
        rest.append(text.substring(last));
        String code = rest.toString();
        List<String[]> hunks = ScriptPatch.hunksOf(code);
        if (!hunks.isEmpty()) script = null;   // hunks win: a fence around them is not a whole script
        Integer revert = null, doneVersion = null;
        boolean finish = false, waitHelpers = false, done = false;
        List<Integer> accepts = new ArrayList<>(), discards = new ArrayList<>();
        // commands count only outside code: a script may well contain the word DONE in a comment
        String prose = FENCE.matcher(code).replaceAll("");
        Matcher c = COMMAND.matcher(prose);
        while (c.find()) {
            Integer n = c.group(2) == null ? null : Integer.parseInt(c.group(2));
            switch (c.group(1)) {
                case "DONE" -> {
                    done = true;
                    doneVersion = n;
                }
                case "REVERT" -> {
                    if (n == null) problems.add("REVERT needs a version number, e.g. REVERT 3");
                    else revert = n;
                }
                case "FINISH" -> finish = true;
                case "WAIT" -> waitHelpers = true;
                case "ACCEPT" -> {
                    if (n == null) problems.add("ACCEPT needs a helper number, e.g. ACCEPT d1");
                    else accepts.add(n);
                }
                case "DISCARD" -> {
                    if (n == null) problems.add("DISCARD needs a helper number, e.g. DISCARD d1");
                    else discards.add(n);
                }
                default -> { }
            }
        }
        List<String[]> picks = new ArrayList<>();
        Matcher p = PICK.matcher(prose);
        while (p.find()) {
            for (String item : p.group(1).replace("**", "").split("[,;]")) {
                String it = item.strip();
                if (it.isEmpty()) continue;
                int eq = it.indexOf('=');
                if (eq <= 0 || eq == it.length() - 1) problems.add("PICK needs name=choice, e.g. PICK roof=hipped (got '" + clip(it) + "')");
                else picks.add(new String[]{it.substring(0, eq).strip(), unquote(it.substring(eq + 1).strip())});
            }
        }
        List<Integer> keeps = new ArrayList<>();
        Matcher k = KEEP.matcher(prose);
        while (k.find()) for (String n : k.group(1).strip().split("[ ,\\t]+")) if (!n.isEmpty()) keeps.add(Integer.parseInt(n));
        Character prefer = null;
        Matcher pr = PREFER.matcher(prose);
        if (pr.find()) prefer = Character.toUpperCase(pr.group(1).charAt(0));
        return new Turn(script, hunks, looks, inspects, guides, delegates, accepts, discards, revert, finish, waitHelpers,
                done, doneVersion, problems, picks, keeps, prefer);
    }

    private static void readLooks(String body, List<JsonObject> out, List<String> problems) {
        JsonElement e = json(body, "look", problems);
        if (e == null) return;
        if (e.isJsonObject()) out.add(e.getAsJsonObject());
        else if (e.isJsonArray()) {
            for (JsonElement v : e.getAsJsonArray()) {
                if (v.isJsonObject()) out.add(v.getAsJsonObject());
                else problems.add("look: each view must be an object, e.g. {\"part\":\"keep\"}");
            }
        } else problems.add("look: give a view object or a list of them");
    }

    private static void readInspect(String body, List<int[]> out, List<String> problems) {
        JsonElement e = json(body, "inspect", problems);
        if (e == null) return;
        JsonArray box = e.isJsonArray() ? e.getAsJsonArray()
                : e.isJsonObject() && e.getAsJsonObject().get("box") instanceof JsonArray a ? a : null;
        if (box == null || box.size() != 6) {
            problems.add("inspect: give {\"box\":[x1,y1,z1,x2,y2,z2]}");
            return;
        }
        int[] b = new int[6];
        try {
            for (int i = 0; i < 6; i++) b[i] = (int) Math.round(box.get(i).getAsDouble());
        } catch (RuntimeException ex) {
            problems.add("inspect: the box must be six numbers");
            return;
        }
        out.add(b);
    }

    private static void readDelegates(String body, List<Delegate> out, List<String> problems) {
        JsonElement e = json(body, "delegate", problems);
        if (e == null) return;
        List<JsonElement> items = new ArrayList<>();
        if (e.isJsonArray()) e.getAsJsonArray().forEach(items::add);
        else items.add(e);
        for (JsonElement v : items) {
            if (!v.isJsonObject() || !v.getAsJsonObject().has("part")) {
                problems.add("delegate: give {\"part\":\"name\", \"brief\":\"what to do\"}");
                continue;
            }
            JsonObject o = v.getAsJsonObject();
            out.add(new Delegate(o.get("part").getAsString(), o.has("brief") ? o.get("brief").getAsString() : ""));
        }
    }

    private static JsonElement json(String body, String what, List<String> problems) {
        try {
            return JsonParser.parseString(body.strip());
        } catch (RuntimeException e) {
            problems.add(what + ": not valid JSON (" + clip(e.getMessage()) + ")");
            return null;
        }
    }

    private static String unquote(String v) {
        if (v.length() >= 2 && (v.startsWith("'") && v.endsWith("'") || v.startsWith("\"") && v.endsWith("\""))) return v.substring(1, v.length() - 1);
        return v;
    }

    private static String clip(String s) {
        if (s == null) return "";
        return s.length() > 120 ? s.substring(0, 119) + "…" : s;
    }
}
