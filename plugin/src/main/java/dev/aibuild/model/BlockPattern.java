package dev.aibuild.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * A block <b>pattern</b>: either a single canonical block state or a weighted mix of block states.
 *
 * <p>Supported input forms (all validated through {@link BlockInfo#parse(String)}):</p>
 * <ul>
 *     <li>single state: {@code "oak_planks"} or {@code "minecraft:spruce_stairs[facing=east,half=top]"}</li>
 *     <li>WorldEdit weighted list: {@code "60%cobblestone,25%mossy_cobblestone,15%andesite"}; a weight applies
 *     to the entry that follows it. Commas inside {@code [...]} do not split entries, so
 *     {@code "50%oak_stairs[facing=east,half=top],50%stone"} is two entries.</li>
 *     <li>equal weights: {@code "cobblestone,andesite"}</li>
 *     <li>JSON array: {@code ["a","b"]} or {@code [{"block":"a","weight":3}, ...]} (also {@code "state"})</li>
 *     <li>JSON object: {@code {"cobblestone":60,"andesite":40}}</li>
 * </ul>
 *
 * <p>Weights are relative; entries with a non-positive weight are dropped and if none remain the last
 * entry is used. {@link #pick(int, int, int)} is deterministic: a mixed 64-bit hash of the coordinate and
 * of the pattern's textual seed selects the entry, so the same model position always receives the same
 * block for the same pattern (stable across runs and reloads) while neighbouring cells vary.</p>
 *
 * <p>Immutable and thread-safe.</p>
 */
public final class BlockPattern {
    private static final String MINECRAFT_NS = "minecraft:";
    private static final int MAX_ENTRIES = 4096;
    private static final long MIX_A = 0x9E3779B97F4A7C15L;
    private static final long MIX_B = 0xC2B2AE3D27D4EB4FL;

    private final List<BlockSpec> options;
    private final int[] weights;      // normalized (sum == WEIGHT_SCALE) when mixed
    private final int[] cumulative;   // prefix sums of normalized weights, last == WEIGHT_SCALE
    private final String seedText;
    private final long seedHash;
    private final boolean single;

    private static final int WEIGHT_SCALE = 1_000_000;

    private BlockPattern(List<BlockSpec> options, int[] weights, String seedText) {
        this.options = List.copyOf(options);
        this.seedText = seedText;
        this.seedHash = mix64(seedText.hashCode() * 0x100000001B3L ^ 0xD1B54A32D192ED03L);
        this.single = this.options.size() == 1;
        if (single) {
            this.weights = new int[]{WEIGHT_SCALE};
            this.cumulative = new int[]{WEIGHT_SCALE};
        } else {
            long total = 0;
            for (int w : weights) total += w;
            if (total <= 0) {
                this.weights = new int[this.options.size()];
                java.util.Arrays.fill(this.weights, WEIGHT_SCALE / this.options.size());
                this.weights[0] += WEIGHT_SCALE - (WEIGHT_SCALE / this.options.size()) * this.options.size();
            } else {
                this.weights = new int[this.options.size()];
                int used = 0;
                for (int i = 0; i < weights.length; i++) {
                    int w = (int) Math.max(0, Math.round((double) weights[i] * WEIGHT_SCALE / total));
                    this.weights[i] = w;
                    used += w;
                }
                this.weights[this.weights.length - 1] += WEIGHT_SCALE - used;
            }
            this.cumulative = new int[this.weights.length];
            int acc = 0;
            for (int i = 0; i < this.weights.length; i++) {
                acc += this.weights[i];
                this.cumulative[i] = acc;
            }
        }
    }

    /**
     * Parses a pattern element.
     *
     * @param el   the JSON element; a JSON string, array, object or {@code null}
     * @param info block registry used to validate every entry
     * @return the parsed pattern (never null)
     * @throws IllegalArgumentException when the value is missing, empty or contains an invalid entry;
     *                                  the message names the offending entry and keeps the parser's reason
     */
    public static BlockPattern parse(JsonElement el, BlockInfo info) throws IllegalArgumentException {
        Objects.requireNonNull(info, "info");
        if (el == null || el.isJsonNull()) {
            throw new IllegalArgumentException("missing block pattern");
        }
        if (el.isJsonArray()) return parseArray(el.getAsJsonArray(), info);
        if (el.isJsonObject()) return parseObject(el.getAsJsonObject(), info);
        if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isString()) {
            return parseString(el.getAsString(), info);
        }
        if (el.isJsonPrimitive()) {
            throw new IllegalArgumentException("block pattern must be a string, array or object, got "
                    + el.getAsJsonPrimitive().getAsString());
        }
        throw new IllegalArgumentException("block pattern must be a string, array or object");
    }

    /** Convenience for a plain string, e.g. a value read from a config. */
    public static BlockPattern parse(String raw, BlockInfo info) throws IllegalArgumentException {
        return parse(new com.google.gson.JsonPrimitive(raw), info);
    }

    private static BlockPattern parseString(String raw, BlockInfo info) {
        if (raw == null) throw new IllegalArgumentException("missing block pattern");
        String s = raw.trim();
        if (s.isEmpty()) throw new IllegalArgumentException("empty block pattern");
        List<String> entries = splitEntries(s);
        if (entries.size() == 1 && !hasWeightPrefix(entries.get(0))) {
            BlockSpec spec = parseSpec(entries.get(0), null, info);
            return new BlockPattern(List.of(spec), new int[]{1}, spec.toString());
        }
        List<BlockSpec> specs = new ArrayList<>();
        List<Integer> weights = new ArrayList<>();
        List<String> canonical = new ArrayList<>();
        int pendingWeight = -1;
        for (String entry : entries) {
            String e = entry.trim();
            if (e.isEmpty()) continue;
            int pct = e.indexOf('%');
            if (pct >= 0) {
                String w = e.substring(0, pct).trim();
                String rest = e.substring(pct + 1).trim();
                int parsed;
                try {
                    parsed = (int) Math.round(Double.parseDouble(w));
                } catch (NumberFormatException ex) {
                    throw new IllegalArgumentException("bad weight in pattern entry '" + e + "'");
                }
                if (parsed < 0) throw new IllegalArgumentException("negative weight in pattern entry '" + e + "'");
                if (rest.isEmpty()) throw new IllegalArgumentException("missing block after weight in '" + e + "'");
                pendingWeight = parsed;
                e = rest;
            }
            BlockSpec spec = parseSpec(e, entry.trim(), info);
            if (spec.isAir()) {
                throw new IllegalArgumentException("entry '" + entry.trim() + "': air is not allowed in a mix");
            }
            specs.add(spec);
            weights.add(pendingWeight >= 0 ? pendingWeight : 1);
            canonical.add(spec.toString());
            pendingWeight = -1;
        }
        if (specs.isEmpty()) throw new IllegalArgumentException("empty block pattern");
        if (specs.size() > MAX_ENTRIES) throw new IllegalArgumentException("too many pattern entries (max " + MAX_ENTRIES + ")");
        int[] w = new int[weights.size()];
        for (int i = 0; i < w.length; i++) w[i] = weights.get(i);
        return new BlockPattern(specs, w, String.join(",", canonical));
    }

    private static boolean hasWeightPrefix(String entry) {
        int pct = entry.indexOf('%');
        if (pct <= 0) return false;
        String w = entry.substring(0, pct).trim();
        if (w.isEmpty()) return false;
        for (int i = 0; i < w.length(); i++) {
            char c = w.charAt(i);
            if (!(Character.isDigit(c) || c == '.')) return false;
        }
        return true;
    }

    /**
     * Splits a WorldEdit-style mix on commas that are <b>outside</b> square brackets. Blank entries are
     * dropped; an entry may contain nested brackets as long as they are balanced.
     */
    static List<String> splitEntries(String s) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '[') depth++;
            else if (c == ']') depth = Math.max(0, depth - 1);
            if (c == ',' && depth == 0) {
                if (!cur.toString().isBlank()) out.add(cur.toString().trim());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        if (!cur.toString().isBlank()) out.add(cur.toString().trim());
        return out;
    }

    private static BlockSpec parseSpec(String raw, String context, BlockInfo info) {
        String s = raw.trim();
        if (s.isEmpty()) throw new IllegalArgumentException(context == null ? "empty block pattern" : "entry '" + context + "': empty block");
        if (s.startsWith("*")) s = s.substring(1); // WorldEdit "random block" marker
        try {
            return info.parse(s);
        } catch (IllegalArgumentException ex) {
            String reason = ex.getMessage() == null ? "invalid block" : ex.getMessage();
            if (context == null || context.equals(s)) {
                throw new IllegalArgumentException(reason, ex);
            }
            throw new IllegalArgumentException("entry '" + context + "': " + reason, ex);
        }
    }

    private static BlockPattern parseArray(JsonArray arr, BlockInfo info) {
        if (arr.isEmpty()) throw new IllegalArgumentException("empty block pattern array");
        List<BlockSpec> specs = new ArrayList<>();
        List<Integer> weights = new ArrayList<>();
        List<String> canonical = new ArrayList<>();
        int index = 0;
        for (JsonElement e : arr) {
            String ctx = "entry " + index;
            if (e == null || e.isJsonNull()) throw new IllegalArgumentException("entry " + index + ": missing block");
            if (e.isJsonObject()) {
                JsonObject o = e.getAsJsonObject();
                JsonElement blockEl = o.get("block");
                if (blockEl == null) blockEl = o.get("state");
                if (blockEl == null) blockEl = o.get("name");
                if (blockEl == null) blockEl = o.get("type");
                if (blockEl == null) throw new IllegalArgumentException("entry " + index + ": missing 'block'");
                if (!blockEl.isJsonPrimitive()) {
                    throw new IllegalArgumentException("entry " + index + ": 'block' must be a string");
                }
                BlockSpec spec = parseSpec(blockEl.getAsString(), blockEl.getAsString(), info);
                int w = 1;
                for (String wk : new String[]{"weight", "chance", "w", "percent"}) {
                    JsonElement we = o.get(wk);
                    if (we != null && !we.isJsonNull() && we.isJsonPrimitive()) {
                        try {
                            w = (int) Math.round(Double.parseDouble(we.getAsString()));
                        } catch (NumberFormatException ex) {
                            throw new IllegalArgumentException("entry " + index + ": bad weight '" + we.getAsString() + "'");
                        }
                        break;
                    }
                }
                if (w < 0) throw new IllegalArgumentException("entry " + index + ": negative weight");
                specs.add(spec);
                weights.add(w);
                canonical.add(spec.toString() + "@" + w);
                index++;
                continue;
            }
            if (e.isJsonPrimitive()) {
                BlockSpec spec = parseSpec(e.getAsString(), e.getAsString(), info);
                specs.add(spec);
                weights.add(1);
                canonical.add(spec.toString());
                index++;
                continue;
            }
            throw new IllegalArgumentException("entry " + index + ": unsupported pattern element");
        }
        if (specs.size() > MAX_ENTRIES) throw new IllegalArgumentException("too many pattern entries (max " + MAX_ENTRIES + ")");
        int[] w = new int[weights.size()];
        for (int i = 0; i < w.length; i++) w[i] = weights.get(i);
        return new BlockPattern(specs, w, String.join(",", canonical));
    }

    private static BlockPattern parseObject(JsonObject o, BlockInfo info) {
        if (o.isEmpty()) throw new IllegalArgumentException("empty block pattern object");
        List<BlockSpec> specs = new ArrayList<>();
        List<Integer> weights = new ArrayList<>();
        List<String> canonical = new ArrayList<>();
        for (String key : o.keySet()) {
            JsonElement v = o.get(key);
            int w;
            if (v == null || v.isJsonNull()) {
                w = 1;
            } else if (v.isJsonPrimitive()) {
                try {
                    w = (int) Math.round(Double.parseDouble(v.getAsString()));
                } catch (NumberFormatException ex) {
                    throw new IllegalArgumentException("weight for '" + key + "' is not a number: '" + v.getAsString() + "'");
                }
            } else {
                throw new IllegalArgumentException("weight for '" + key + "' must be a number");
            }
            if (w < 0) throw new IllegalArgumentException("negative weight for '" + key + "'");
            BlockSpec spec = parseSpec(key, key, info);
            specs.add(spec);
            weights.add(w);
            canonical.add(spec.toString() + "@" + w);
        }
        if (specs.size() > MAX_ENTRIES) throw new IllegalArgumentException("too many pattern entries (max " + MAX_ENTRIES + ")");
        int[] w = new int[weights.size()];
        for (int i = 0; i < w.length; i++) w[i] = weights.get(i);
        return new BlockPattern(specs, w, String.join(",", canonical));
    }

    /**
     * Deterministically picks a block for the given model position. The same pattern and position always
     * yield the same spec within one JVM run and across runs (no {@link Object#hashCode()} of records is used).
     */
    public BlockSpec pick(int x, int y, int z) {
        if (single) return options.get(0);
        long h = mix64(((long) x * MIX_A) ^ ((long) y * MIX_B) ^ (((long) z * MIX_A) * 3) ^ seedHash);
        int roll = (int) ((h >>> 17) % WEIGHT_SCALE);
        int idx = java.util.Arrays.binarySearch(cumulative, roll);
        if (idx < 0) idx = -idx - 1;
        if (idx >= options.size()) idx = options.size() - 1;
        return options.get(idx);
    }

    private static long mix64(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /**
     * True when the pattern has no randomness, i.e. every position yields the same block.
     */
    public boolean isSingle() {
        return single;
    }

    /** The distinct block states, in declaration order (unmodifiable). */
    public List<BlockSpec> options() {
        return options;
    }

    /** Relative weights aligned with {@link #options()} (normalized to sum 1_000_000 for mixes). */
    public int[] weights() {
        return weights.clone();
    }

    /** The first (or only) option; handy when a single block is required, e.g. a roof stair pattern. */
    public BlockSpec first() {
        return options.get(0);
    }

    /** Deterministic seed text used by {@link #pick}. */
    public String seed() {
        return seedText;
    }

    /** Compact representation, e.g. {@code oak_planks} or {@code {60%cobblestone,40%andesite}}. */
    @Override
    public String toString() {
        if (single) return options.get(0).toString();
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < options.size(); i++) {
            if (i > 0) sb.append(',');
            double pct = weights[i] * 100.0 / WEIGHT_SCALE;
            if (weights.length > 1) {
                sb.append(trimPct(pct)).append('%');
            }
            sb.append(options.get(i));
        }
        return sb.append('}').toString();
    }

    private static String trimPct(double pct) {
        String s = String.format(Locale.ROOT, "%.1f", pct);
        if (s.endsWith(".0")) s = s.substring(0, s.length() - 2);
        return s;
    }

    /** Renders an entry the way it should be written back to the LLM, e.g. {@code 60%cobblestone}. */
    public String describe() {
        if (single) return options.get(0).toString();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < options.size(); i++) {
            if (i > 0) sb.append(',');
            if (weights.length > 1) sb.append(trimPct(weights[i] * 100.0 / WEIGHT_SCALE)).append('%');
            sb.append(options.get(i));
        }
        return sb.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BlockPattern p)) return false;
        return options.equals(p.options) && java.util.Arrays.equals(weights, p.weights);
    }

    @Override
    public int hashCode() {
        return Objects.hash(options, java.util.Arrays.hashCode(weights));
    }

    /** Unmodifiable empty-safe copy helper used by callers that need a list view. */
    public static List<BlockPattern> singleton(BlockSpec spec) {
        return Collections.singletonList(new BlockPattern(List.of(spec), new int[]{1}, spec.toString()));
    }
}
