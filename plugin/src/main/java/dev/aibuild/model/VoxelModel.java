package dev.aibuild.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Sparse 3D block model in <b>model space</b>: +x = east, +y = up, +z = south. The build "front" (main
 * entrance) faces SOUTH (+z) by convention; the world placer rotates the model when placing.
 *
 * <h2>Semantics of unset cells</h2>
 * <ul>
 *     <li>A set cell holds a {@link BlockSpec}; it may explicitly be {@link BlockSpec#AIR} ("force air").</li>
 *     <li>Unset cells with {@code y <= 0} are natural terrain (solid ground: grass/dirt). Layer y = 0 is the
 *     ground surface layer: floors / foundations / paths replace terrain there; walls usually start at y = 1.</li>
 *     <li>Unset cells with {@code y >= 1} are air (inside the model bounds the site is cleared on placement).</li>
 * </ul>
 * Not thread-safe; confine an instance to one thread at a time (copy() to hand over).
 */
public final class VoxelModel {
    private static final int OFF = 1 << 20;
    private static final long MASK = (1L << 21) - 1;

    private final HashMap<Long, BlockSpec> cells;
    private int[] boundsCache; // minX,minY,minZ,maxX,maxY,maxZ
    private boolean boundsDirty = true;

    public VoxelModel() {
        this.cells = new HashMap<>();
    }

    private VoxelModel(HashMap<Long, BlockSpec> cells) {
        this.cells = cells;
    }

    @FunctionalInterface
    public interface CellConsumer {
        void accept(int x, int y, int z, BlockSpec spec);
    }

    public static long key(int x, int y, int z) {
        return (((long) (x + OFF)) << 42) | (((long) (y + OFF)) << 21) | ((long) (z + OFF));
    }

    public static int kx(long key) {
        return (int) ((key >>> 42) & MASK) - OFF;
    }

    public static int ky(long key) {
        return (int) ((key >>> 21) & MASK) - OFF;
    }

    public static int kz(long key) {
        return (int) (key & MASK) - OFF;
    }

    /** The set spec or null when unset. */
    public BlockSpec get(int x, int y, int z) {
        return cells.get(key(x, y, z));
    }

    /**
     * Effective block for analysis: the set spec; else {@link #TERRAIN} for y &lt;= 0; else AIR.
     */
    public BlockSpec effective(int x, int y, int z) {
        BlockSpec s = cells.get(key(x, y, z));
        if (s != null) return s;
        return y <= 0 ? TERRAIN : BlockSpec.AIR;
    }

    /** Pseudo block representing untouched natural ground (only returned by {@link #effective}). */
    public static final BlockSpec TERRAIN = BlockSpec.of("grass_block");

    public boolean isSet(int x, int y, int z) {
        return cells.containsKey(key(x, y, z));
    }

    /** Sets a cell; {@code spec == null} unsets it. */
    public void set(int x, int y, int z, BlockSpec spec) {
        long k = key(x, y, z);
        if (spec == null) {
            if (cells.remove(k) != null) boundsDirty = true;
        } else {
            BlockSpec prev = cells.put(k, spec);
            if (prev == null) {
                if (!boundsDirty && boundsCache != null) {
                    int[] b = boundsCache;
                    if (x < b[0] || y < b[1] || z < b[2] || x > b[3] || y > b[4] || z > b[5]) boundsDirty = true;
                } else {
                    boundsDirty = true;
                }
            }
        }
    }

    public int size() {
        return cells.size();
    }

    public boolean isEmpty() {
        return cells.isEmpty();
    }

    public int nonAirCount() {
        int n = 0;
        for (BlockSpec s : cells.values()) if (!s.isAir()) n++;
        return n;
    }

    /**
     * Bounding box of all set cells (including explicit air): {minX,minY,minZ,maxX,maxY,maxZ}, or null when empty.
     * Returns a copy.
     */
    public int[] bounds() {
        if (cells.isEmpty()) return null;
        if (boundsDirty || boundsCache == null) {
            int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
            for (long k : cells.keySet()) {
                int x = kx(k), y = ky(k), z = kz(k);
                if (x < minX) minX = x;
                if (y < minY) minY = y;
                if (z < minZ) minZ = z;
                if (x > maxX) maxX = x;
                if (y > maxY) maxY = y;
                if (z > maxZ) maxZ = z;
            }
            boundsCache = new int[]{minX, minY, minZ, maxX, maxY, maxZ};
            boundsDirty = false;
        }
        return boundsCache.clone();
    }

    /** Bounding box of non-air cells only, or null if there are none. */
    public int[] solidBounds() {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        boolean any = false;
        for (Map.Entry<Long, BlockSpec> e : cells.entrySet()) {
            if (e.getValue().isAir()) continue;
            long k = e.getKey();
            int x = kx(k), y = ky(k), z = kz(k);
            any = true;
            if (x < minX) minX = x;
            if (y < minY) minY = y;
            if (z < minZ) minZ = z;
            if (x > maxX) maxX = x;
            if (y > maxY) maxY = y;
            if (z > maxZ) maxZ = z;
        }
        return any ? new int[]{minX, minY, minZ, maxX, maxY, maxZ} : null;
    }

    public void forEach(CellConsumer consumer) {
        for (Map.Entry<Long, BlockSpec> e : cells.entrySet()) {
            long k = e.getKey();
            consumer.accept(kx(k), ky(k), kz(k), e.getValue());
        }
    }

    /** Snapshot of raw entries (packed key -> spec). */
    public Map<Long, BlockSpec> entries() {
        return java.util.Collections.unmodifiableMap(cells);
    }

    public VoxelModel copy() {
        VoxelModel m = new VoxelModel(new HashMap<>(cells));
        m.boundsCache = boundsCache == null ? null : boundsCache.clone();
        m.boundsDirty = boundsDirty;
        return m;
    }

    public void clear() {
        cells.clear();
        boundsDirty = true;
    }

    /** Block counts ordered by descending count. */
    public LinkedHashMap<BlockSpec, Integer> counts() {
        HashMap<BlockSpec, Integer> c = new HashMap<>();
        for (BlockSpec s : cells.values()) c.merge(s, 1, Integer::sum);
        LinkedHashMap<BlockSpec, Integer> out = new LinkedHashMap<>();
        c.entrySet().stream()
                .sorted((a, b) -> b.getValue() != a.getValue().intValue() ? b.getValue() - a.getValue() : a.getKey().toString().compareTo(b.getKey().toString()))
                .forEach(e -> out.put(e.getKey(), e.getValue()));
        return out;
    }

    // ------------------------------------------------------------------ serialization

    /** {"palette":["air","oak_planks",...],"cells":[x,y,z,p, x,y,z,p, ...]} */
    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        LinkedHashMap<BlockSpec, Integer> pal = new LinkedHashMap<>();
        JsonArray data = new JsonArray();
        for (Map.Entry<Long, BlockSpec> e : cells.entrySet()) {
            int idx = pal.computeIfAbsent(e.getValue(), s -> pal.size());
            long k = e.getKey();
            data.add(kx(k));
            data.add(ky(k));
            data.add(kz(k));
            data.add(idx);
        }
        JsonArray palette = new JsonArray();
        for (BlockSpec s : pal.keySet()) palette.add(s.toString());
        o.add("palette", palette);
        o.add("cells", data);
        return o;
    }

    public static VoxelModel fromJson(JsonObject o) {
        VoxelModel m = new VoxelModel();
        JsonArray palette = o.getAsJsonArray("palette");
        BlockSpec[] pal = new BlockSpec[palette.size()];
        for (int i = 0; i < pal.length; i++) pal[i] = BlockSpec.parseRaw(palette.get(i).getAsString());
        JsonArray data = o.getAsJsonArray("cells");
        for (int i = 0; i + 3 < data.size(); i += 4) {
            m.set(data.get(i).getAsInt(), data.get(i + 1).getAsInt(), data.get(i + 2).getAsInt(), pal[data.get(i + 3).getAsInt()]);
        }
        return m;
    }
}
