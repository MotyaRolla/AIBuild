package dev.aibuild.script;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.aibuild.model.BlockFamilies;
import dev.aibuild.model.BlockInfo;
import dev.aibuild.model.BlockPattern;
import dev.aibuild.model.BlockSpec;
import dev.aibuild.model.VoxelModel;
import dev.aibuild.model.ops.Doorway;
import dev.aibuild.model.ops.OpExecutor;
import dev.aibuild.model.ops.OpResult;
import dev.aibuild.script.geom.Frame;
import dev.aibuild.script.geom.Shape;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.ContextFactory;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.LambdaFunction;
import org.mozilla.javascript.NativeArray;
import org.mozilla.javascript.RhinoException;
import org.mozilla.javascript.ScriptStackElement;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.SerializableCallable;
import org.mozilla.javascript.Undefined;
import org.mozilla.javascript.WrappedException;
import org.mozilla.javascript.regexp.RegExpImpl;

import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.SplittableRandom;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs an AI-written build script in a sandboxed Rhino interpreter.
 *
 * <p><b>Sandbox.</b> Scripts get only the safe ECMAScript standard objects (no {@code Packages}, {@code java},
 * {@code importClass}), a class shutter that denies every Java class, no {@code Date}, and a seeded
 * {@code Math.random}. Host functions exchange numbers, strings and plain JS objects only; shapes and materials
 * are integer handles, so no Java object ever reaches script code. An instruction observer enforces the
 * instruction, wall-clock, allocation and cancellation budget.</p>
 *
 * <p><b>Library.</b> The {@link Stdlib} prelude ({@code stdlib/v<n>/prelude.js}) defines the API the model sees (shapes, materials, kits) on top
 * of the {@code __*} host functions registered here. Heavy work (distance-field sampling, smoothing, legacy
 * ops) runs in Java.</p>
 */
public final class ScriptRuntime {
    public static final String SCRIPT_NAME = "build.js";
    private static final String PRELUDE_NAME = "prelude.js";

    private static final SandboxFactory FACTORY = new SandboxFactory();

    /** Per-run limits. */
    /**
     * Per-run limits. They guard the server, not the design: a live script did ~260M instructions in 0.7 s, so the
     * old 300M cap stopped real builds after a second of work. The instruction cap is deterministic (a stored
     * script re-runs to the same model); the time cap only stops a stuck loop; memory is guarded by the live heap
     * after garbage collection ({@code allocatedBytes} caps the run's total allocations, garbage included, so it
     * is set far above anything a real build allocates).
     */
    public record Budget(long instructions, long millis, long allocatedBytes, int maxCells, long samples) {
        public static Budget standard() {
            return new Budget(30_000_000_000L, 120_000, 4_000L << 30, 4_000_000, 60_000_000_000L);
        }

        public static Budget extra() {
            return new Budget(60_000_000_000L, 240_000, 8_000L << 30, 4_000_000, 120_000_000_000L);
        }

        /** The same budget with {@code factor} times the wall-clock time: re-running a script that already finished. */
        public Budget rerun(int factor) {
            return new Budget(instructions, millis * Math.max(1, factor), allocatedBytes, maxCells, samples);
        }
    }

    private final BlockInfo info;
    private final BlockFamilies families;
    private final Stdlib stdlib;

    /** A runtime with the latest library. */
    public ScriptRuntime(BlockInfo info) {
        this(info, Stdlib.latest());
    }

    /** A runtime with a specific library version (stored builds re-run with the library they were written for). */
    public ScriptRuntime(BlockInfo info, Stdlib stdlib) {
        this.info = Objects.requireNonNull(info, "info");
        this.families = new BlockFamilies(info);
        this.stdlib = Objects.requireNonNull(stdlib, "stdlib");
    }

    public Stdlib stdlib() {
        return stdlib;
    }

    public BlockFamilies families() {
        return families;
    }

    /**
     * Executes {@code source}.
     *
     * @param base   existing model to build on (copied), or null for an empty canvas
     * @param limits plot box writes are clipped to, or null
     * @param cancel cooperative cancellation flag, or null
     */
    public ScriptResult run(String source, long seed, VoxelModel base, int[] limits, Budget budget, AtomicBoolean cancel) {
        long start = System.currentTimeMillis();
        String prepared = ScriptLint.prepare(source);
        ScriptError pre = ScriptLint.precheck(prepared);
        Run run = new Run(seed, base == null ? new VoxelModel() : base.copy(), limits, budget,
                cancel == null ? new AtomicBoolean() : cancel, start);
        ScriptError error = null;
        boolean libraryLoaded = false;
        Context cx = FACTORY.enterContext();
        try {
            cx.setLanguageVersion(Context.VERSION_ECMASCRIPT);
            cx.setInterpretedMode(true);
            cx.setMaximumInterpreterStackDepth(1500);
            cx.setClassShutter(className -> false);
            cx.putThreadLocal(Run.class, run);
            ScriptableObject scope = cx.initSafeStandardObjects(null, false);
            install(cx, scope, run);
            cx.evaluateString(scope, stdlib.prelude(), PRELUDE_NAME, 1, null);
            libraryLoaded = true;
            if (pre != null) error = pre;
            else cx.evaluateString(scope, prepared, SCRIPT_NAME, 1, null);
            if (error == null && run.canvas.model().nonAirCount() == 0) {
                error = new ScriptError("runtime", "the script ran but placed no blocks", 0, 0, "", "");
            }
        } catch (ScriptBudgetException e) {
            error = budgetError(e, prepared);
        } catch (WrappedException e) {
            Throwable inner = e.getWrappedException();
            error = inner instanceof ScriptBudgetException b ? budgetError(b, prepared) : fromRhino(e, prepared, "runtime");
        } catch (RhinoException e) {
            String kind = e instanceof org.mozilla.javascript.EvaluatorException && e.getScriptStack().length == 0 ? "syntax" : "runtime";
            error = fromRhino(e, prepared, kind);
        } catch (StackOverflowError e) {
            error = new ScriptError("budget", "recursion too deep", 0, 0, "", "");
        } catch (RuntimeException e) {
            error = new ScriptError("runtime", String.valueOf(e.getMessage()), 0, 0, "", "");
        } finally {
            Context.exit();
        }
        if (!libraryLoaded && error != null && !run.cancel.get()) {
            // Not the script's fault: repairing it cannot help, so the caller falls back instead of retrying.
            throw new IllegalStateException("the build-script library failed to load: " + error.message());
        }
        error = ScriptLint.explain(error, prepared, stdlib.api());
        Canvas c = run.canvas;
        if (error == null) c.settle();
        return new ScriptResult(c.model(), error, run.meta, c.parts(), c.owners(), c.smoothed(), List.copyOf(run.logs),
                merge(c.warnings(), c.outside()), c.outside(), run.instructions, System.currentTimeMillis() - start,
                List.copyOf(run.doorways), c.collisions(), Map.copyOf(c.overlaps()), c.confinements(),
                c.anchors(), c.notes(), List.copyOf(run.options.values()));
    }

    private static List<String> merge(List<String> warnings, long outside) {
        List<String> out = new ArrayList<>(warnings);
        if (outside > 0) out.add(outside + " block writes fell outside the allowed build area and were dropped");
        return out;
    }

    private static ScriptError budgetError(ScriptBudgetException e, String source) {
        return new ScriptError("budget", e.getMessage(), 0, 0, "", "");
    }

    private static ScriptError fromRhino(RhinoException e, String source, String kind) {
        int line = e.lineNumber(), col = e.columnNumber();
        StringBuilder stack = new StringBuilder();
        boolean located = SCRIPT_NAME.equals(e.sourceName());
        for (ScriptStackElement el : e.getScriptStack()) {
            if (stack.length() < 600) {
                stack.append("  at ").append(el.functionName == null ? "<top>" : el.functionName)
                        .append(" (").append(el.fileName).append(':').append(el.lineNumber).append(")\n");
            }
            if (!located && SCRIPT_NAME.equals(el.fileName)) {
                line = el.lineNumber;
                col = 0;
                located = true;
            }
        }
        String msg = e.details();
        if (e instanceof WrappedException w && w.getWrappedException() != null) msg = w.getWrappedException().getMessage();
        if (!located) line = 0;
        if (msg != null && msg.startsWith("Java class ")) msg = "access to Java is not available in build scripts";
        return new ScriptError(kind, msg == null ? e.getClass().getSimpleName() : msg, Math.max(0, line), Math.max(0, col),
                ScriptError.excerpt(source, line, 3), stack.toString());
    }

    // ------------------------------------------------------------------ run state

    private final class Run {
        /** The current part's random stream; each part() call gets its own, so rewriting one part leaves the others' draws alone. */
        SplittableRandom rng;
        final Deque<SplittableRandom> rngStack = new ArrayDeque<>();
        final Noise noise;
        final long seed;
        final Canvas canvas;
        final Budget budget;
        final AtomicBoolean cancel;
        final long start;
        final long[] samples;
        final long threadId = Thread.currentThread().threadId();
        final long allocatedAtStart;
        final List<Shape> shapes = new ArrayList<>();
        final List<Material> materials = new ArrayList<>();
        final Map<String, Material> parsed = new HashMap<>();
        final List<String> logs = new ArrayList<>();
        final List<Doorway> doorways = new ArrayList<>();
        final Map<String, ScriptResult.Option> options = new java.util.LinkedHashMap<>();
        JsonObject meta = new JsonObject();
        long instructions;
        int ticks;
        int logChars;

        Run(long seed, VoxelModel model, int[] limits, Budget budget, AtomicBoolean cancel, long start) {
            this.seed = seed;
            this.rng = new SplittableRandom(seed);
            this.noise = new Noise(seed);
            this.canvas = new Canvas(model, info, families, limits, budget.maxCells());
            this.budget = budget;
            this.cancel = cancel;
            this.start = start;
            this.samples = new long[]{budget.samples()};
            this.allocatedAtStart = allocated(threadId);
        }

        private List<dev.aibuild.analysis.AnalysisReport.Room> rooms;
        private long roomsAt = -1;

        /** The analyzer's rooms in what the script has built so far; recomputed only after new writes. */
        List<dev.aibuild.analysis.AnalysisReport.Room> rooms() {
            if (rooms == null || roomsAt != canvas.writes()) {
                // doors and beds get their second halves when shapes resolve; without them every doorway is a hole
                VoxelModel m = canvas.model().copy();
                dev.aibuild.model.ShapeResolver.resolve(m, info);
                rooms = m.isEmpty() ? List.of() : dev.aibuild.analysis.StructureAnalyzer.analyze(m, info,
                        new dev.aibuild.analysis.AnalyzerOptions(true)).roomList;
                roomsAt = canvas.writes();
            }
            return rooms;
        }

        void tick(int n) {
            instructions += n;
            if (cancel.get()) throw new ScriptBudgetException("cancelled");
            if (instructions > budget.instructions()) {
                throw new ScriptBudgetException("instruction budget exhausted (" + budget.instructions() / 1_000_000
                        + "M): avoid per-block JS loops over large volumes, use box()/shapes instead");
            }
            if (System.currentTimeMillis() - start > budget.millis()) {
                throw new ScriptBudgetException("time budget exhausted (" + budget.millis() / 1000 + " s)");
            }
            long a = allocated(threadId);
            if (a > 0 && a - allocatedAtStart > budget.allocatedBytes()) {
                throw new ScriptBudgetException("memory budget exhausted: build smaller arrays/objects");
            }
            if ((++ticks & 63) == 0 && heapNearlyFull()) {
                throw new ScriptBudgetException("the server is running out of memory: build smaller arrays/objects");
            }
        }

        Shape shape(Object handle) {
            int id = handleId(handle, "__s", "shape");
            if (id < 0 || id >= shapes.size()) throw new IllegalArgumentException("not a shape (use sphere(), box(), ... )");
            return shapes.get(id);
        }

        int addShape(Shape s) {
            if (shapes.size() > 5_000_000) throw new ScriptBudgetException("too many shapes created");
            shapes.add(s);
            return shapes.size() - 1;
        }

        Material material(Object m) {
            if (m instanceof CharSequence cs) {
                String raw = cs.toString().trim();
                return parsed.computeIfAbsent(raw, this::parseMaterial);
            }
            if (m instanceof Scriptable s && ScriptableObject.hasProperty(s, "__m")) {
                int id = handleId(m, "__m", "material");
                if (id < 0 || id >= materials.size()) throw new IllegalArgumentException("unknown material handle");
                return materials.get(id);
            }
            if (m == null || Undefined.isUndefined(m)) throw new IllegalArgumentException("missing block/material argument");
            throw new IllegalArgumentException("material must be a block name like 'stone_bricks' or a material from mix()/grad()/...");
        }

        private Material parseMaterial(String raw) {
            if (raw.isEmpty()) throw new IllegalArgumentException("empty block name");
            try {
                if (raw.contains("%") || (raw.contains(",") && !raw.contains("["))) {
                    return new Material.Pattern(BlockPattern.parse(raw, info));
                }
                return new Material.Fixed(info.parse(raw));
            } catch (IllegalArgumentException e) {
                // a bare wood name ('dark_oak') means its planks, as in stair('dark_oak', ...)
                if (raw.matches("[a-z_]+") && families.exists(raw + "_planks")) return new Material.Fixed(info.parse(raw + "_planks"));
                Material fixed = lenient(raw);
                if (fixed != null) return fixed;
                throw new IllegalArgumentException(e.getMessage() + suggest(raw));
            }
        }

        /**
         * A block name the model got slightly wrong, corrected the way a builder would read it, with a warning: an
         * unknown property is dropped, a shape that does not exist in that material falls back to the nearest one
         * that does ({@code polished_andesite_wall} -> {@code andesite_wall}) or to the full block, and a name one or
         * two letters off becomes the real one. Null when nothing close exists: then the script stops, because
         * guessing further would build something the model did not mean. Deterministic, so a stored script re-runs
         * the same way.
         */
        private Material lenient(String raw) {
            if (raw.contains("%") || (raw.contains(",") && !raw.contains("["))) {
                StringBuilder sb = new StringBuilder();
                boolean changed = false;
                for (String entry : raw.split(",")) {
                    String t = entry.strip();
                    int pc = t.indexOf('%');
                    String weight = pc < 0 ? "" : t.substring(0, pc + 1);
                    String block = pc < 0 ? t : t.substring(pc + 1);
                    BlockSpec spec = lenientSpec(block);
                    if (spec != null && !block.equals(spec.toString())) changed = true;
                    if (sb.length() > 0) sb.append(',');
                    sb.append(weight).append(spec == null ? block : spec.toString());
                }
                if (!changed) return null;
                try {
                    Material m = new Material.Pattern(BlockPattern.parse(sb.toString(), info));
                    canvas.warn("auto-corrected: '" + raw + "' -> '" + sb + "'");
                    return m;
                } catch (IllegalArgumentException e) {
                    return null;
                }
            }
            BlockSpec spec = lenientSpec(raw);
            if (spec == null) return null;
            canvas.warn("auto-corrected: '" + raw + "' -> '" + spec + "'");
            return new Material.Fixed(spec);
        }

        private static final String[] SHAPES = {"_stairs", "_slab", "_wall", "_fence_gate", "_fence", "_trapdoor", "_door",
                "_button", "_pressure_plate", "_sign", "_carpet", "_pane"};

        private BlockSpec lenientSpec(String raw) {
            BlockSpec wanted;
            try {
                wanted = BlockSpec.parseRaw(raw.strip());
            } catch (IllegalArgumentException e) {
                return null;
            }
            String name = wanted.name().startsWith("minecraft:") ? wanted.name().substring(10) : wanted.name();
            String real = realName(name, wanted.props().containsKey("axis"));
            if (real == null) return null;
            BlockSpec out;
            try {
                out = info.parse(real);
            } catch (IllegalArgumentException e) {
                return null;
            }
            for (Map.Entry<String, String> p : wanted.props().entrySet()) {
                try {
                    out = info.with(out, p.getKey(), p.getValue());
                } catch (IllegalArgumentException dropped) {
                    // a property this block does not have ('hanging' on a chain): the block without it
                }
            }
            return out;
        }

        /** The existing block a wrong name most likely means, or null; {@code axis}: the model gave a log's axis. */
        private String realName(String name, boolean axis) {
            java.util.Collection<String> names = info.blockNames();
            if (names.contains(name)) return name;
            // a bare material name: 'dark_oak[axis=y]' is its log, 'dark_oak' its planks, 'deepslate_brick' the bricks
            for (String full : axis ? new String[]{name + "_log", name + "_stem", name + "_planks"}
                    : new String[]{name + "_planks", name + "s", name + "_block"}) {
                if (names.contains(full)) return full;
            }
            for (String shape : SHAPES) {
                if (!name.endsWith(shape)) continue;
                String[] tokens = name.substring(0, name.length() - shape.length()).split("_");
                // drop leading qualifiers until the shape exists: polished_andesite_wall -> andesite_wall
                for (int i = 1; i < tokens.length; i++) {
                    String cand = String.join("_", java.util.Arrays.copyOfRange(tokens, i, tokens.length)) + shape;
                    if (names.contains(cand)) return cand;
                }
                // no such shape in this material: the full block keeps the form readable
                String root = String.join("_", tokens);
                for (String full : new String[]{root, root + "s", root + "_planks", root + "_block"}) {
                    if (names.contains(full)) return full;
                }
                break;
            }
            List<String> near = Suggest.nearest(name, names, 1);
            if (!near.isEmpty() && Suggest.distance(name, near.get(0)) <= 2) return near.get(0);
            return null;
        }

        int addMaterial(Material m) {
            if (materials.size() > 1_000_000) throw new ScriptBudgetException("too many materials created");
            materials.add(m);
            return materials.size() - 1;
        }
    }

    private String suggest(String raw) {
        String name = BlockSpec.parseRaw(raw).name();
        List<String> near = Suggest.nearest(name, info.blockNames(), 3);
        return near.isEmpty() ? "" : " (did you mean " + String.join(", ", near) + "?)";
    }

    /**
     * True when the old generation is still over 90% full after its last collection: live data, not garbage. Only
     * that can take the server down, whatever the script allocated and dropped on the way.
     */
    static boolean heapNearlyFull() {
        for (java.lang.management.MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() != java.lang.management.MemoryType.HEAP || !pool.isCollectionUsageThresholdSupported()) continue;
            java.lang.management.MemoryUsage u = pool.getCollectionUsage();
            if (u == null || u.getMax() <= 0) continue;
            String n = pool.getName().toLowerCase(java.util.Locale.ROOT);
            if ((n.contains("old") || n.contains("tenured")) && u.getUsed() > u.getMax() * 0.9) return true;
        }
        return false;
    }

    private static long allocated(long threadId) {
        if (ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean t && t.isThreadAllocatedMemorySupported()) {
            return t.getThreadAllocatedBytes(threadId);
        }
        return -1;
    }

    // ------------------------------------------------------------------ host functions

    private interface Host {
        Object call(Context cx, Scriptable scope, Object[] args);
    }

    private static void fn(ScriptableObject scope, String name, int arity, Host body) {
        SerializableCallable c = (cx, sc, self, args) -> {
            try {
                return body.call(cx, sc, args);
            } catch (ScriptBudgetException | RhinoException e) {
                throw e;
            } catch (IllegalArgumentException | IllegalStateException | IndexOutOfBoundsException e) {
                String api = name.startsWith("__") ? name.substring(2) : name;
                throw Context.reportRuntimeError(api + "(): " + e.getMessage());
            }
        };
        ScriptableObject.defineProperty(scope, name, new LambdaFunction(scope, name, arity, c), ScriptableObject.DONTENUM);
    }

    /**
     * A write primitive (block, box, line, place, mark): a call with a NaN or infinite coordinate is skipped with a
     * warning instead of stopping the whole script. One bad number in a loop used to cost the model a full retry;
     * the rest of the build is unaffected by skipping that one write.
     */
    private static void fnSkip(ScriptableObject scope, Run run, String name, int arity, Host body) {
        fn(scope, name, arity, (cx, sc, args) -> {
            try {
                return body.call(cx, sc, args);
            } catch (NonFiniteException e) {
                String api = name.startsWith("__") ? name.substring(2) : name;
                run.canvas.warn("auto-corrected: " + api + "() calls with a NaN or infinite coordinate were skipped ("
                        + e.getMessage() + ")");
                return Undefined.instance;
            }
        });
    }

    private void install(Context cx, ScriptableObject scope, Run run) {
        scope.delete("Date");
        Scriptable math = (Scriptable) ScriptableObject.getProperty(scope, "Math");
        ScriptableObject.putProperty(math, "random", new LambdaFunction(scope, "random", 0,
                (SerializableCallable) (c, s, self, a) -> run.rng.nextDouble()));
        ScriptableObject.putProperty(scope, "SEED", (double) run.seed);

        fn(scope, "__rng", 0, (c, s, a) -> run.rng.nextDouble());
        fn(scope, "__noise", 4, (c, s, a) -> {
            double scale = a.length > 3 ? Math.max(1e-3, num(a[3])) : 8;
            return run.noise.fbm(num(a[0]) / scale, num(a[1]) / scale, num(a[2]) / scale);
        });
        fn(scope, "__log", 1, (c, s, a) -> {
            String msg = a.length == 0 ? "" : Context.toString(a[0]);
            if (run.logChars < 4000) {
                run.logs.add(msg.length() > 300 ? msg.substring(0, 299) + "…" : msg);
                run.logChars += msg.length();
            }
            return Undefined.instance;
        });
        fn(scope, "__warn", 1, (c, s, a) -> {
            run.canvas.warn(a.length == 0 ? "" : Context.toString(a[0]));
            return Undefined.instance;
        });
        fn(scope, "__rooms", 0, (c, s, a) -> {
            List<dev.aibuild.analysis.AnalysisReport.Room> rooms = run.rooms();
            Object[] out = new Object[rooms.size()];
            for (int i = 0; i < out.length; i++) {
                var r = rooms.get(i);
                Scriptable o = c.newObject(s);
                ScriptableObject.putProperty(o, "x1", r.x1());
                ScriptableObject.putProperty(o, "z1", r.z1());
                ScriptableObject.putProperty(o, "x2", r.x2());
                ScriptableObject.putProperty(o, "z2", r.z2());
                ScriptableObject.putProperty(o, "y", r.y());
                ScriptableObject.putProperty(o, "area", r.area());
                ScriptableObject.putProperty(o, "furniture", r.furniture());
                ScriptableObject.putProperty(o, "light", r.minLight());
                out[i] = o;
            }
            return c.newArray(s, out);
        });
        fn(scope, "__solid", 3, (c, s, a) -> {
            BlockSpec b = run.canvas.getLocal(num(a[0]), num(a[1]), num(a[2]));
            return b != null && !b.isAir() && info.isOccluding(b);
        });
        fn(scope, "__partBox", 1, (c, s, a) -> {
            int[] b = run.canvas.partBox(Context.toString(a[0]));
            return b == null ? null : c.newArray(s, new Object[]{b[0], b[1], b[2], b[3], b[4], b[5]});
        });
        fn(scope, "__meta", 1, (c, s, a) -> {
            JsonElement j = toJson(a[0], 0);
            if (!j.isJsonObject()) throw new IllegalArgumentException("meta() takes an object");
            run.meta = j.getAsJsonObject();
            return Undefined.instance;
        });
        fn(scope, "__option", 4, (c, s, a) -> {
            String name = Context.toString(a[0]);
            List<String> choices = new ArrayList<>();
            for (com.google.gson.JsonElement e : com.google.gson.JsonParser.parseString(Context.toString(a[1])).getAsJsonArray()) {
                choices.add(e.getAsString());
            }
            run.options.putIfAbsent(name, new ScriptResult.Option(name, List.copyOf(choices), (int) num(a[2]), Boolean.TRUE.equals(a[3])));
            return Undefined.instance;
        });
        fn(scope, "__note", 1, (c, s, a) -> {
            run.canvas.note(Context.toString(a[0]));
            return Undefined.instance;
        });
        fnSkip(scope, run, "__mark", 4, (c, s, a) -> {
            if (!(a[1] instanceof org.mozilla.javascript.NativeArray arr)) throw new IllegalArgumentException("mark(): give x, y, z or [x1, y1, z1, x2, y2, z2]");
            double[] xyz = new double[(int) arr.getLength()];
            for (int i = 0; i < xyz.length; i++) xyz[i] = num(arr.get(i, arr));
            return run.canvas.mark(Context.toString(a[0]), xyz, a[2] == null || a[2] == Undefined.instance ? "" : Context.toString(a[2]),
                    Boolean.TRUE.equals(a[3]));
        });
        fn(scope, "__anchor", 1, (c, s, a) -> {
            Canvas.Anchor an = run.canvas.anchor(Context.toString(a[0]));
            if (an == null) return null;
            Scriptable o = c.newObject(s);
            int[] b = an.box();
            if (an.point()) {
                ScriptableObject.putProperty(o, "x", b[0]);
                ScriptableObject.putProperty(o, "y", b[1]);
                ScriptableObject.putProperty(o, "z", b[2]);
            } else {
                String[] k = {"x1", "y1", "z1", "x2", "y2", "z2"};
                for (int i = 0; i < 6; i++) ScriptableObject.putProperty(o, k[i], b[i]);
            }
            ScriptableObject.putProperty(o, "note", an.note());
            ScriptableObject.putProperty(o, "name", an.key());
            return o;
        });
        fn(scope, "__confine", 2, (c, s, a) -> {
            run.canvas.confine(Context.toString(a[0]), Context.toString(a[1]));
            return Undefined.instance;
        });
        fn(scope, "__part", 1, (c, s, a) -> {
            run.canvas.beginPart(Context.toString(a[0]));
            if (run.canvas.confiningRooms()) {
                List<int[]> boxes = new ArrayList<>();
                for (var r : run.rooms()) boxes.add(new int[]{r.x1(), r.z1(), r.x2(), r.z2(), r.y()});
                run.canvas.scopeRooms(boxes);
            }
            run.rngStack.push(run.rng);
            run.rng = new SplittableRandom(run.seed ^ run.canvas.currentPart().hashCode() * 0x9E3779B97F4A7C15L
                    ^ run.canvas.partCall() * 0xC2B2AE3D27D4EB4FL);
            return Undefined.instance;
        });
        fn(scope, "__fit", 1, (c, s, a) -> {
            run.canvas.beginFit(Context.toString(a[0]));
            return Undefined.instance;
        });
        fn(scope, "__endfit", 0, (c, s, a) -> {
            run.canvas.endFit();
            return Undefined.instance;
        });
        fn(scope, "__endpart", 0, (c, s, a) -> {
            run.canvas.endPart();
            if (!run.rngStack.isEmpty()) run.rng = run.rngStack.pop();
            return Undefined.instance;
        });
        fn(scope, "__push", 1, (c, s, a) -> {
            run.canvas.push(frame(a[0]));
            return Undefined.instance;
        });
        fn(scope, "__pop", 0, (c, s, a) -> {
            run.canvas.pop();
            return Undefined.instance;
        });
        fnSkip(scope, run, "__block", 4, (c, s, a) -> {
            Material m = run.material(a[3]);
            double[] p = run.canvas.frame().point(num(a[0]), num(a[1]), num(a[2]));
            int x = (int) Math.round(p[0]), y = (int) Math.round(p[1]), z = (int) Math.round(p[2]);
            run.canvas.setWorld(x, y, z, run.canvas.orient(m.pick(Material.Sample.at(x, y, z))), false);
            return Undefined.instance;
        });
        fnSkip(scope, run, "__box", 8, (c, s, a) -> box(run, new double[]{num(a[0]), num(a[1]), num(a[2])},
                new double[]{num(a[3]), num(a[4]), num(a[5])}, a[6], a.length > 7 ? Context.toString(a[7]) : "fill"));
        fnSkip(scope, run, "__line", 4, (c, s, a) -> {
            double[] from = vec(a[0]), to = vec(a[1]);
            Material m = run.material(a[2]);
            double r = a.length > 3 && !Undefined.isUndefined(a[3]) ? num(a[3]) : 0;
            if (r >= 0.5) {
                return Rasterizer.place(run.canvas, Shape.capsule(from, to, r, r), m, Rasterizer.Options.SOLID, run.samples);
            }
            int n = (int) Math.ceil(Math.max(Math.abs(to[0] - from[0]), Math.max(Math.abs(to[1] - from[1]), Math.abs(to[2] - from[2]))));
            if (n > 100_000) throw new IllegalArgumentException("line longer than 100000 blocks");
            String axis = Math.abs(to[1] - from[1]) >= Math.max(Math.abs(to[0] - from[0]), Math.abs(to[2] - from[2])) ? "y"
                    : Math.abs(to[0] - from[0]) >= Math.abs(to[2] - from[2]) ? "x" : "z";
            for (int i = 0; i <= n; i++) {
                double t = n == 0 ? 0 : (double) i / n;
                double lx = Math.round(from[0] + (to[0] - from[0]) * t);
                double ly = Math.round(from[1] + (to[1] - from[1]) * t);
                double lz = Math.round(from[2] + (to[2] - from[2]) * t);
                double[] p = run.canvas.frame().point(lx, ly, lz);
                int x = (int) Math.round(p[0]), y = (int) Math.round(p[1]), z = (int) Math.round(p[2]);
                BlockSpec b = m.pick(Material.Sample.at(x, y, z));
                if (info.hasProperty(b.name(), "axis") && !b.hasProp("axis")) b = info.with(b, "axis", axis);
                run.canvas.setWorld(x, y, z, run.canvas.orient(b), false);
            }
            return n + 1;
        });
        fn(scope, "__get", 3, (c, s, a) -> {
            BlockSpec b = run.canvas.getLocal(num(a[0]), num(a[1]), num(a[2]));
            return b == null || b.isAir() ? null : b.toString();
        });
        fn(scope, "__shape", 1, (c, s, a) -> run.addShape(shape(run, (Scriptable) a[0], c, s)));
        fn(scope, "__bounds", 1, (c, s, a) -> {
            double[] b = run.shape(a[0]).bounds();
            return c.newArray(s, new Object[]{b[0] + 0.5, b[1] + 0.5, b[2] + 0.5, b[3] - 0.5, b[4] - 0.5, b[5] - 0.5});
        });
        fnSkip(scope, run, "__place", 3, (c, s, a) -> {
            Shape sh = run.shape(a[0]);
            Scriptable o = a.length > 2 && a[2] instanceof Scriptable so ? so : null;
            boolean carve = bool(o, "carve");
            Material m = carve ? new Material.Fixed(BlockSpec.AIR) : run.material(a[1]);
            Rasterizer.Options opts = new Rasterizer.Options(bool(o, "smooth"), number(o, "shell", 0), carve,
                    bool(o, "onlyAir"), bool(o, "onlySolid"));
            return Rasterizer.place(run.canvas, sh, m, opts, run.samples);
        });
        fn(scope, "__mat", 1, (c, s, a) -> run.addMaterial(material(run, (Scriptable) a[0])));
        fn(scope, "__family", 2, (c, s, a) -> {
            String name = run.material(a[0]).representative().name();
            String kind = Context.toString(a[1]);
            String out = switch (kind) {
                case "stairs" -> families.stairs(name);
                case "slab" -> families.slab(name);
                case "wall" -> families.wall(name);
                case "fence" -> families.fence(name);
                default -> throw new IllegalArgumentException("family kind must be stairs/slab/wall/fence");
            };
            return out;
        });
        fn(scope, "__exists", 1, (c, s, a) -> families.exists(BlockSpec.parseRaw(Context.toString(a[0])).name()));
        fn(scope, "__ramp", 3, (c, s, a) -> {
            String base = a[0] instanceof String str ? str : run.material(a[0]).representative().name();
            List<String> r = Ramps.ramp(base, (int) num(a[1]), num(a[2]), info);
            return c.newArray(s, r.toArray());
        });
        fn(scope, "__door", 4, (c, s, a) -> {
            double[] p = run.canvas.frame().point(num(a[0]), num(a[1]), num(a[2]));
            BlockSpec door = run.canvas.orient(run.material(a[3]).representative());
            run.doorways.add(new Doorway((int) Math.round(p[0]), (int) Math.round(p[1]), (int) Math.round(p[2]), door));
            return Undefined.instance;
        });
        fn(scope, "__op", 1, (c, s, a) -> legacyOp(run, toJson(a[0], 0)));
        fn(scope, "__model", 0, (c, s, a) -> {
            int[] b = run.canvas.model().solidBounds();
            return b == null ? null : c.newArray(s, new Object[]{b[0], b[1], b[2], b[3], b[4], b[5]});
        });
    }

    private Object box(Run run, double[] a, double[] b, Object mat, String mode) {
        Canvas canvas = run.canvas;
        boolean air = mat == null || Undefined.isUndefined(mat) || "air".equals(mat);
        Material m = air ? new Material.Fixed(BlockSpec.AIR) : run.material(mat);
        int[] lo = new int[3], hi = new int[3];
        for (int i = 0; i < 3; i++) {
            lo[i] = (int) Math.round(Math.min(a[i], b[i]));
            hi[i] = (int) Math.round(Math.max(a[i], b[i]));
        }
        long volume = (long) (hi[0] - lo[0] + 1) * (hi[1] - lo[1] + 1) * (hi[2] - lo[2] + 1);
        if (volume > 40_000_000L) throw new IllegalArgumentException("box of " + volume + " cells is larger than the whole build space");
        Frame f = canvas.frame();
        boolean grid = f.exactStates() && f.minStretch() == 1;
        if (!grid) {
            if (!mode.equals("fill")) throw new IllegalArgumentException("only solid boxes can be placed inside a non-quarter rotation; use shapes");
            return Rasterizer.place(canvas, Shape.box(new double[]{lo[0], lo[1], lo[2]}, new double[]{hi[0], hi[1], hi[2]}),
                    m, Rasterizer.Options.SOLID, run.samples);
        }
        double[] wb = f.bounds(new double[]{lo[0], lo[1], lo[2], hi[0], hi[1], hi[2]});
        int[] cells = {(int) Math.round(wb[0]), (int) Math.round(wb[1]), (int) Math.round(wb[2]),
                (int) Math.round(wb[3]), (int) Math.round(wb[4]), (int) Math.round(wb[5])};
        int n = 0;
        for (int y = lo[1]; y <= hi[1]; y++) {
            for (int z = lo[2]; z <= hi[2]; z++) {
                for (int x = lo[0]; x <= hi[0]; x++) {
                    boolean faceX = x == lo[0] || x == hi[0], faceY = y == lo[1] || y == hi[1], faceZ = z == lo[2] || z == hi[2];
                    boolean write = switch (mode) {
                        case "hollow" -> true;
                        case "walls" -> faceX || faceZ;
                        case "outline" -> (faceX ? 1 : 0) + (faceY ? 1 : 0) + (faceZ ? 1 : 0) >= 2;
                        case "fill" -> true;
                        default -> throw new IllegalArgumentException("box mode must be fill/hollow/walls/outline");
                    };
                    if (!write) continue;
                    double[] p = f.point(x, y, z);
                    int wx = (int) Math.round(p[0]), wy = (int) Math.round(p[1]), wz = (int) Math.round(p[2]);
                    boolean shellCell = faceX || faceY || faceZ;
                    BlockSpec spec;
                    if (mode.equals("hollow") && !shellCell) spec = BlockSpec.AIR;
                    else {
                        spec = canvas.orient(m.pick(new Material.Sample(wx, wy, wz, 0, faceY ? (y == hi[1] ? 1 : -1) : 0, 0, cells,
                                (faceX ? 1 : 0) + (faceY ? 1 : 0) + (faceZ ? 1 : 0) >= 2)));
                        if (mode.equals("outline") && info.hasProperty(spec.name(), "axis") && !spec.hasProp("axis")) {
                            String axis = !faceX ? "x" : !faceZ ? "z" : "y";
                            spec = info.with(spec, "axis", axis);
                        }
                    }
                    canvas.setWorld(wx, wy, wz, spec, false);
                    n++;
                }
            }
        }
        return n;
    }

    private Object legacyOp(Run run, JsonElement op) {
        if (!op.isJsonObject()) throw new IllegalArgumentException("op() takes one operation object like {op:'roof', ...}");
        String kind = op.getAsJsonObject().has("op") ? op.getAsJsonObject().get("op").getAsString().toLowerCase(Locale.ROOT) : "";
        Canvas canvas = run.canvas;
        if (kind.equals("replace")) {
            if (!canvas.frame().isIdentity()) throw new IllegalArgumentException("op 'replace' cannot run inside transforms; use place(shape, m, {onlySolid:true})");
            OpResult r = new OpExecutor(info).apply(canvas.model(), op);
            if (r.opsFailed > 0) throw new IllegalArgumentException(String.join("; ", r.errors));
            return r.cellsChanged;
        }
        VoxelModel temp = new VoxelModel();
        OpResult r = new OpExecutor(info).apply(temp, op);
        if (r.opsFailed > 0) throw new IllegalArgumentException(r.errors.isEmpty() ? "operation failed" : String.join("; ", r.errors));
        temp.forEach((x, y, z, spec) -> canvas.setLocal(x, y, z, spec));
        for (Doorway d : r.doorways) {
            double[] p = canvas.frame().point(d.x(), d.y(), d.z());
            run.doorways.add(new Doorway((int) Math.round(p[0]), (int) Math.round(p[1]), (int) Math.round(p[2]), canvas.orient(d.door())));
        }
        return r.cellsChanged;
    }

    // ------------------------------------------------------------------ shape & material specs

    private Shape shape(Run run, Scriptable o, Context cx, Scriptable scope) {
        String t = str(o, "t");
        return switch (t) {
            case "sphere" -> Shape.sphere(vec(get(o, "c")), num(get(o, "r")));
            case "ellipsoid" -> Shape.ellipsoid(vec(get(o, "c")), vec(get(o, "r")));
            case "box" -> Shape.box(vec(get(o, "a")), vec(get(o, "b")));
            case "rbox" -> Shape.rbox(vec(get(o, "a")), vec(get(o, "b")), num(get(o, "round")));
            case "cyl" -> Shape.cyl(vec(get(o, "a")), vec(get(o, "b")), num(get(o, "r")));
            case "cone" -> Shape.cone(vec(get(o, "a")), vec(get(o, "b")), num(get(o, "r0")), num(get(o, "r1")));
            case "capsule" -> Shape.capsule(vec(get(o, "a")), vec(get(o, "b")), num(get(o, "r0")), num(get(o, "r1")));
            case "torus" -> Shape.torus(vec(get(o, "c")), num(get(o, "R")), num(get(o, "r")), axisChar(get(o, "axis"), 'y'));
            case "revolve" -> Shape.revolve(vec(get(o, "base")), pairs(get(o, "profile")));
            case "extrude" -> Shape.extrude(pairs(get(o, "poly")), num(get(o, "y0")), num(get(o, "y1")));
            case "tube" -> Shape.tube(vecs(get(o, "pts")), nums(get(o, "radii")), str(o, "curve"));
            case "loft" -> Shape.loft(vecs(get(o, "centers")), pairs(get(o, "radii")));
            case "union" -> Shape.union(shapes(run, get(o, "of")));
            case "inter" -> Shape.intersect(shapes(run, get(o, "of")));
            case "sub" -> Shape.subtract(run.shape(get(o, "a")), shapes(run, get(o, "of")));
            case "blend" -> Shape.blend(num(get(o, "k")), shapes(run, get(o, "of")));
            case "offset" -> Shape.offset(run.shape(get(o, "of")), num(get(o, "by")));
            case "xf" -> Shape.transform(run.shape(get(o, "of")), frame(get(o, "ops")));
            case "arch" -> Shape.arch(axisChar(get(o, "along"), 'x'), num(get(o, "c")), num(get(o, "w0")), num(get(o, "w1")),
                    num(get(o, "hw")), num(get(o, "rise")), num(get(o, "bottom")), num(get(o, "spring")), str(o, "style"));
            case "warp" -> {
                Shape base = run.shape(get(o, "of"));
                double amp = Math.clamp(num(get(o, "amp")), 0, 16), sc = Math.max(0.5, num(get(o, "scale")));
                double[] b = base.bounds();
                for (int i = 0; i < 3; i++) {
                    b[i] -= amp;
                    b[i + 3] += amp;
                }
                Noise n = run.noise;
                yield Shape.of(b, base.cost() + 1, (x, y, z) -> base.d(x, y, z) + amp * n.fbm(x / sc + 11.3, y / sc, z / sc - 7.1));
            }
            case "fn" -> {
                Object f = get(o, "f");
                if (!(f instanceof Function fun)) throw new IllegalArgumentException("sdf() needs a function (x,y,z) => distance");
                double[] b = nums(get(o, "bounds"));
                if (b.length != 6) throw new IllegalArgumentException("sdf() bounds are [x0,y0,z0,x1,y1,z1]");
                double[] cb = {Math.min(b[0], b[3]) - 0.5, Math.min(b[1], b[4]) - 0.5, Math.min(b[2], b[5]) - 0.5,
                        Math.max(b[0], b[3]) + 0.5, Math.max(b[1], b[4]) + 0.5, Math.max(b[2], b[5]) + 0.5};
                yield Shape.of(cb, 40, (x, y, z) -> Context.toNumber(fun.call(cx, scope, scope, new Object[]{x, y, z})));
            }
            default -> throw new IllegalArgumentException("unknown shape type '" + t + "'");
        };
    }

    private List<Shape> shapes(Run run, Object arr) {
        List<Shape> out = new ArrayList<>();
        for (Object o : list(arr)) out.add(run.shape(o));
        if (out.isEmpty()) throw new IllegalArgumentException("needs at least one shape");
        return out;
    }

    private Material material(Run run, Scriptable o) {
        String t = str(o, "t");
        return switch (t) {
            case "mix" -> {
                List<Material> opts = new ArrayList<>();
                for (Object m : list(get(o, "of"))) opts.add(run.material(m));
                double[] w = nums(get(o, "w"));
                if (opts.isEmpty() || w.length != opts.size()) throw new IllegalArgumentException("mix() needs {block: weight, ...}");
                for (double v : w) if (!(v > 0)) throw new IllegalArgumentException("mix() weights must be positive");
                double scale = has(o, "scale") ? num(get(o, "scale")) : 3;
                yield new Material.Mix(opts, w, scale, run.noise);
            }
            case "grad" -> {
                List<Material> mats = new ArrayList<>();
                for (Object m : list(get(o, "of"))) mats.add(run.material(m));
                double[] stops = nums(get(o, "stops"));
                if (mats.size() < 2 || stops.length != mats.size()) throw new IllegalArgumentException("grad() needs 2+ stops [[t, block], ...]");
                for (int i = 1; i < stops.length; i++) if (stops[i] < stops[i - 1]) throw new IllegalArgumentException("grad() stops must increase");
                double from = has(o, "from") ? num(get(o, "from")) : Double.NaN, to = has(o, "to") ? num(get(o, "to")) : Double.NaN;
                yield new Material.Gradient(axisChar(get(o, "axis"), 'y'), stops, mats, from, to, run.noise);
            }
            case "normal" -> {
                Material side = run.material(get(o, "side"));
                yield new Material.ByNormal(has(o, "top") ? run.material(get(o, "top")) : side, side,
                        has(o, "bottom") ? run.material(get(o, "bottom")) : side);
            }
            case "weather" -> new Material.Weather(run.material(get(o, "of")), Math.clamp(num(get(o, "amount")), 0, 1), run.noise, families);
            case "trim" -> new Material.Trim(run.material(get(o, "of")), run.material(get(o, "edge")));
            case "shade" -> {
                List<Material> ramp = new ArrayList<>();
                for (Object m : list(get(o, "of"))) ramp.add(run.material(m));
                if (ramp.size() < 2) throw new IllegalArgumentException("shade() needs a ramp of 2+ blocks, dark to light");
                double[] l = has(o, "light") ? nums(get(o, "light")) : new double[]{-0.35, 0.85, 0.4};
                double len = Math.sqrt(l[0] * l[0] + l[1] * l[1] + l[2] * l[2]);
                if (l.length != 3 || len < 1e-9) throw new IllegalArgumentException("shade() light must be [x, y, z]");
                yield new Material.Shade(ramp, new double[]{l[0] / len, l[1] / len, l[2] / len},
                        has(o, "height") ? num(get(o, "height")) : 0.25, has(o, "cavity") ? num(get(o, "cavity")) : 0.9, run.noise);
            }
            default -> throw new IllegalArgumentException("unknown material type '" + t + "'");
        };
    }

    /** A transform list: [["t",x,y,z], ["ry",deg], ["rx",deg], ["rz",deg], ["s",sx,sy,sz], ["m","x"]], applied left to right. */
    private static Frame frame(Object spec) {
        Frame f = Frame.identity();
        for (Object step : list(spec)) {
            List<Object> s = list(step);
            if (s.isEmpty()) continue;
            String k = Context.toString(s.getFirst());
            Frame local = switch (k) {
                case "t" -> Frame.translate(num(s.get(1)), num(s.get(2)), num(s.get(3)));
                case "ry" -> Frame.rotY(num(s.get(1)));
                case "rx" -> Frame.rotX(num(s.get(1)));
                case "rz" -> Frame.rotZ(num(s.get(1)));
                case "s" -> Frame.scale(num(s.get(1)), num(s.get(2)), num(s.get(3)));
                case "m" -> Frame.mirror(axisChar(s.get(1), 'x'));
                default -> throw new IllegalArgumentException("unknown transform '" + k + "'");
            };
            // later steps apply after earlier ones: p' = local(f(p))
            f = local.then(f);
        }
        return f;
    }

    // ------------------------------------------------------------------ conversions

    private static int handleId(Object handle, String key, String what) {
        if (handle instanceof Scriptable s) {
            Object v = ScriptableObject.getProperty(s, key);
            if (v instanceof Number n) return n.intValue();
        }
        throw new IllegalArgumentException("expected a " + what);
    }

    static double num(Object o) {
        if (o == null || Undefined.isUndefined(o)) throw new IllegalArgumentException("missing number");
        double d = Context.toNumber(o);
        if (!Double.isFinite(d)) throw new NonFiniteException("number is not finite (" + Context.toString(o) + ")");
        return d;
    }

    /** A NaN or infinite coordinate: a write primitive skips just that call (see {@link #fnSkip}). */
    static final class NonFiniteException extends IllegalArgumentException {
        NonFiniteException(String message) {
            super(message);
        }
    }

    static double[] vec(Object o) {
        if (o instanceof NativeArray a && a.getLength() == 3) {
            return new double[]{num(a.get(0)), num(a.get(1)), num(a.get(2))};
        }
        if (o instanceof Scriptable s && ScriptableObject.hasProperty(s, "x")) {
            return new double[]{num(ScriptableObject.getProperty(s, "x")), num(ScriptableObject.getProperty(s, "y")),
                    num(ScriptableObject.getProperty(s, "z"))};
        }
        throw new IllegalArgumentException("expected a point [x, y, z]");
    }

    static double[] nums(Object o) {
        List<Object> l = list(o);
        double[] out = new double[l.size()];
        for (int i = 0; i < out.length; i++) out[i] = num(l.get(i));
        return out;
    }

    static double[][] vecs(Object o) {
        List<Object> l = list(o);
        double[][] out = new double[l.size()][];
        for (int i = 0; i < out.length; i++) out[i] = vec(l.get(i));
        return out;
    }

    static double[][] pairs(Object o) {
        List<Object> l = list(o);
        double[][] out = new double[l.size()][];
        for (int i = 0; i < out.length; i++) {
            double[] p = nums(l.get(i));
            if (p.length != 2) throw new IllegalArgumentException("expected pairs [a, b]");
            out[i] = p;
        }
        return out;
    }

    static List<Object> list(Object o) {
        if (o instanceof NativeArray a) {
            List<Object> out = new ArrayList<>((int) a.getLength());
            for (int i = 0; i < a.getLength(); i++) out.add(a.get(i));
            return out;
        }
        throw new IllegalArgumentException("expected an array");
    }

    private static Object get(Scriptable o, String key) {
        Object v = ScriptableObject.getProperty(o, key);
        return v == Scriptable.NOT_FOUND ? Undefined.instance : v;
    }

    private static boolean has(Scriptable o, String key) {
        Object v = get(o, key);
        return v != null && !Undefined.isUndefined(v);
    }

    private static String str(Scriptable o, String key) {
        Object v = get(o, key);
        return v == null || Undefined.isUndefined(v) ? "" : Context.toString(v);
    }

    private static boolean bool(Scriptable o, String key) {
        return o != null && has(o, key) && Context.toBoolean(get(o, key));
    }

    private static double number(Scriptable o, String key, double fallback) {
        return o != null && has(o, key) ? num(get(o, key)) : fallback;
    }

    private static char axisChar(Object o, char fallback) {
        if (o == null || Undefined.isUndefined(o)) return fallback;
        String s = Context.toString(o).toLowerCase(Locale.ROOT);
        if (s.length() != 1 || "xyz".indexOf(s.charAt(0)) < 0) throw new IllegalArgumentException("axis must be 'x', 'y' or 'z'");
        return s.charAt(0);
    }

    /** Converts a plain JS value to JSON (for meta and legacy ops). */
    static JsonElement toJson(Object o, int depth) {
        if (depth > 12) throw new IllegalArgumentException("object nesting too deep");
        if (o == null || Undefined.isUndefined(o)) return JsonNull.INSTANCE;
        if (o instanceof Boolean b) return new JsonPrimitive(b);
        if (o instanceof Number n) {
            double d = n.doubleValue();
            return d == Math.rint(d) && Math.abs(d) < 1e15 ? new JsonPrimitive((long) d) : new JsonPrimitive(d);
        }
        if (o instanceof CharSequence cs) return new JsonPrimitive(cs.toString());
        if (o instanceof NativeArray a) {
            JsonArray arr = new JsonArray();
            for (int i = 0; i < a.getLength(); i++) arr.add(toJson(a.get(i), depth + 1));
            return arr;
        }
        if (o instanceof Function) return JsonNull.INSTANCE;
        if (o instanceof Scriptable s) {
            JsonObject obj = new JsonObject();
            for (Object id : s.getIds()) {
                String key = String.valueOf(id);
                obj.add(key, toJson(ScriptableObject.getProperty(s, key), depth + 1));
            }
            return obj;
        }
        return new JsonPrimitive(Context.toString(o));
    }



    private static final class SandboxFactory extends ContextFactory {
        @Override
        protected Context makeContext() {
            Context cx = super.makeContext();
            cx.setInstructionObserverThreshold(20_000);
            // Rhino finds its RegExp engine with ServiceLoader on the thread context class loader, once, when
            // Context is first loaded. Under Paper that loader cannot see the plugin jar, so the lookup fails
            // and every regex literal throws "Regular expressions are not available". Install it directly.
            org.mozilla.javascript.ScriptRuntime.setRegExpProxy(cx, new RegExpImpl());
            return cx;
        }

        @Override
        protected void observeInstructionCount(Context cx, int instructionCount) {
            Object r = cx.getThreadLocal(Run.class);
            if (r instanceof ScriptRuntime.Run run) run.tick(instructionCount);
        }
    }
}
