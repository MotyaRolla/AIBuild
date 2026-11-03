package dev.aibuild.script;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One version of the build-script library: the JS prelude, the API card and the examples shown to the model.
 *
 * <p>Stored builds keep the version their script was written against ({@code BuildVersion.stdlibVersion}) and
 * are re-run and edited with exactly that library, so improving the kits never changes an existing build.
 * Host functions in Java only ever gain features, so every prelude keeps working.</p>
 */
public final class Stdlib {
    /** 2.0: first script library (frozen). */
    public static final Stdlib V1 = new Stdlib("2.0.0", "/stdlib/v1/", List.of("cottage", "fox", "bridge"));
    /** 3.0: builder-technique kits, shading and palettes. */
    public static final Stdlib V2 = new Stdlib("3.0.0", "/stdlib/v2/", List.of("cottage", "fox", "bridge"));

    private final String version;
    private final String dir;
    private final List<String> examples;
    private volatile String prelude;
    private volatile String apiCard;
    private volatile Set<String> api;

    private Stdlib(String version, String dir, List<String> examples) {
        this.version = version;
        this.dir = dir;
        this.examples = examples;
    }

    public static Stdlib latest() {
        return V2;
    }

    /** The library a stored script was written for; unknown or missing versions get the latest. */
    public static Stdlib of(String version) {
        if (V1.version.equals(version)) return V1;
        return latest();
    }

    public String version() {
        return version;
    }

    public String prelude() {
        String p = prelude;
        if (p == null) prelude = p = resource(dir + "prelude.js");
        return p;
    }

    public String apiCard() {
        String c = apiCard;
        if (c == null) apiCard = c = resource(dir + "api-card.md");
        return c;
    }

    /** Example names, in prompt order. */
    public List<String> examples() {
        return examples;
    }

    public String example(String name) {
        return resource(dir + "examples/" + name + ".js");
    }

    /** Optional extra resource of this library (recipe cards...), or null. */
    public String optional(String path) {
        try (InputStream in = Stdlib.class.getResourceAsStream(dir + path)) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    /** Global names the prelude defines, for "did you mean" hints. */
    public Set<String> api() {
        Set<String> a = api;
        if (a == null) api = a = apiNames(prelude());
        return a;
    }

    private static Set<String> apiNames(String prelude) {
        Set<String> out = new TreeSet<>();
        Matcher m = Pattern.compile("(?m)^(?:function|var)\\s+([A-Za-z][\\w$]*)").matcher(prelude);
        while (m.find()) out.add(m.group(1));
        m = Pattern.compile("(?m)^\\s{4}([a-zA-Z]\\w*): function").matcher(prelude);
        while (m.find()) out.add(m.group(1));
        out.addAll(List.of("Math", "JSON", "Object", "Array", "String", "Number", "SEED", "parseInt", "parseFloat", "isNaN"));
        return Set.copyOf(out);
    }

    private static String resource(String path) {
        try (InputStream in = Stdlib.class.getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("missing resource " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + path, e);
        }
    }

    @Override
    public String toString() {
        return "stdlib " + version;
    }
}
