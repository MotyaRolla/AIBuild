package dev.aibuild.model;

import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Immutable, server-independent block state value: a block name (without the "minecraft:" namespace)
 * plus a sorted map of block-state properties.
 *
 * <p>Instances produced by {@link BlockInfo#parse(String)} are <b>canonical</b>: the name is valid and
 * properties equal to the block's default value are omitted, so two canonical specs are equal iff they
 * describe the same in-game block state. {@link #toString()} renders Minecraft syntax, e.g.
 * {@code oak_stairs[facing=east,half=top]}.</p>
 */
public final class BlockSpec {
    public static final BlockSpec AIR = new BlockSpec("air", Collections.emptySortedMap());

    private final String name;
    private final SortedMap<String, String> props;
    private final String str;
    private final int hash;

    private BlockSpec(String name, SortedMap<String, String> props) {
        this.name = name;
        this.props = props;
        if (props.isEmpty()) {
            this.str = name;
        } else {
            StringBuilder sb = new StringBuilder(name).append('[');
            boolean first = true;
            for (Map.Entry<String, String> e : props.entrySet()) {
                if (!first) sb.append(',');
                sb.append(e.getKey()).append('=').append(e.getValue());
                first = false;
            }
            this.str = sb.append(']').toString();
        }
        this.hash = str.hashCode();
    }

    /** Creates a spec without validation. Name is normalized (lower-case, namespace stripped). */
    public static BlockSpec of(String name, Map<String, String> props) {
        String n = normalizeName(name);
        if (props == null || props.isEmpty()) {
            return isAirName(n) && n.equals("air") ? AIR : new BlockSpec(n, Collections.emptySortedMap());
        }
        TreeMap<String, String> p = new TreeMap<>();
        for (Map.Entry<String, String> e : props.entrySet()) {
            p.put(e.getKey().trim().toLowerCase(Locale.ROOT), e.getValue().trim().toLowerCase(Locale.ROOT));
        }
        return new BlockSpec(n, Collections.unmodifiableSortedMap(p));
    }

    public static BlockSpec of(String name) {
        return of(name, null);
    }

    /**
     * Syntax-only parse of "minecraft:name[k=v,k2=v2]" (namespace optional). Does NOT validate that the
     * block or properties exist; use {@link BlockInfo#parse(String)} for that.
     *
     * @throws IllegalArgumentException on malformed syntax
     */
    public static BlockSpec parseRaw(String raw) {
        if (raw == null) throw new IllegalArgumentException("block is null");
        String s = raw.trim();
        if (s.isEmpty()) throw new IllegalArgumentException("empty block string");
        int br = s.indexOf('[');
        if (br < 0) return of(s, null);
        if (!s.endsWith("]")) throw new IllegalArgumentException("missing ']' in block state: " + raw);
        String name = s.substring(0, br);
        String inner = s.substring(br + 1, s.length() - 1).trim();
        TreeMap<String, String> p = new TreeMap<>();
        if (!inner.isEmpty()) {
            for (String part : inner.split(",")) {
                int eq = part.indexOf('=');
                if (eq <= 0) throw new IllegalArgumentException("bad property '" + part.trim() + "' in " + raw);
                p.put(part.substring(0, eq).trim(), part.substring(eq + 1).trim());
            }
        }
        return of(name, p);
    }

    public static String normalizeName(String name) {
        String n = name.trim().toLowerCase(Locale.ROOT);
        if (n.startsWith("minecraft:")) n = n.substring("minecraft:".length());
        return n;
    }

    public static boolean isAirName(String n) {
        return n.equals("air") || n.equals("cave_air") || n.equals("void_air");
    }

    public String name() {
        return name;
    }

    /** Unmodifiable sorted property map (only explicitly set / non-default values for canonical specs). */
    public SortedMap<String, String> props() {
        return props;
    }

    /** Explicit property value or null (for canonical specs null means "default value"). */
    public String prop(String key) {
        return props.get(key);
    }

    public boolean hasProp(String key) {
        return props.containsKey(key);
    }

    /** Returns a copy with the property set (no validation / canonicalization; prefer BlockInfo#with). */
    public BlockSpec with(String key, String value) {
        TreeMap<String, String> p = new TreeMap<>(props);
        p.put(key, value);
        return new BlockSpec(name, Collections.unmodifiableSortedMap(p));
    }

    public BlockSpec without(String key) {
        if (!props.containsKey(key)) return this;
        TreeMap<String, String> p = new TreeMap<>(props);
        p.remove(key);
        return new BlockSpec(name, Collections.unmodifiableSortedMap(p));
    }

    public boolean isAir() {
        return isAirName(name);
    }

    @Override
    public String toString() {
        return str;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BlockSpec b)) return false;
        return hash == b.hash && str.equals(b.str);
    }

    @Override
    public int hashCode() {
        return hash;
    }

    /** Convenience: true if names are equal (ignores properties). */
    public boolean sameBlock(BlockSpec other) {
        return other != null && Objects.equals(name, other.name);
    }
}
