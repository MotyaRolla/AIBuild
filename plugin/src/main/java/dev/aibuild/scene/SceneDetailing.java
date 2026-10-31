package dev.aibuild.scene;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.aibuild.model.BlockSpec;
import dev.aibuild.model.VoxelModel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The post-transfer detailing pass: adds the small blocks that a 3D scene cannot express.
 *
 * <p>A scene primitive can describe a wall, a roof slope or a window, but not a lantern hanging from a chain, a
 * trapdoor used as a shutter, a flower pot on a sill, a button beside a door, a carpet, a sign, a cauldron or a
 * ladder into the cellar. Those are exactly the blocks that make a Minecraft build look finished — and they are
 * invisible in the renderer, because they are smaller than a voxel or purely decorative. The 3D scene therefore
 * never contains them, and no amount of iteration inside three.js will produce them.</p>
 *
 * <p>This class runs <em>after</em> the structure has been transferred and placed. It asks the model for a list of
 * world-coordinate blocks, validates every one of them against the placed model, and returns the ones that
 * survive. Validation is strict on purpose: a decoration placed inside a wall or floating in the air is worse than
 * no decoration at all.</p>
 *
 * <p>Instances are stateless; all methods are static and side-effect free (the caller applies the blocks).</p>
 */
public final class SceneDetailing {

    /** Blocks that are decorations rather than structure: allowed to be small, must still have support. */
    private static final List<String> DECOR_HINTS = List.of(
            "lantern", "torch", "candle", "chain", "trapdoor", "button", "lever", "frame", "painting", "sign",
            "pot", "flower_pot", "carpet", "pressure_plate", "web", "barrel", "cauldron", "anvil", "grindstone",
            "composter", "bookshelf", "crafting_table", "bed", "chest", "ladder", "fence", "gate", "flower",
            "sapling", "grass", "fern", "vine", "hay_block", "campfire", "bell", "candle_cake", "cake",
            "item_frame", "glow_item_frame", "armor_stand", "lectern", "smithing_table", "stonecutter", "loom",
            "cartography_table", "fletching_table", "brewing_stand", "enchanting_table", "note_block", "jukebox");

    private SceneDetailing() {
    }

    /**
     * One decoration the model proposed.
     *
     * @param x     world x
     * @param y     world y
     * @param z     world z
     * @param block the block with state, e.g. {@code minecraft:lantern[hanging=true]}
     * @param why   what it is (for the log and the report)
     */
    public record Decor(int x, int y, int z, String block, String why) {
    }

    /**
     * Parses the model's answer into decorations.
     *
     * <p>Never throws: malformed entries are reported in {@code errors} so the caller can log them and keep the
     * good ones.</p>
     *
     * @param json   the answer ({@code {"blocks":[...]}})
     * @param errors collects rejected entries; must not be {@code null}
     * @return the accepted decorations, in answer order
     */
    public static List<Decor> parse(JsonObject json, List<String> errors) {
        List<Decor> out = new ArrayList<>();
        if (json == null) {
            errors.add("empty answer");
            return out;
        }
        JsonElement blocks = json.get("blocks");
        if (blocks == null || !blocks.isJsonArray()) {
            errors.add("no blocks array");
            return out;
        }
        int index = 0;
        for (JsonElement e : blocks.getAsJsonArray()) {
            index++;
            if (!e.isJsonObject()) {
                errors.add("blocks[" + index + "]: not an object");
                continue;
            }
            JsonObject o = e.getAsJsonObject();
            int x = SceneObject.num(o, "x", Integer.MIN_VALUE);
            int y = SceneObject.num(o, "y", Integer.MIN_VALUE);
            int z = SceneObject.num(o, "z", Integer.MIN_VALUE);
            String block = SceneObject.str(o, "block");
            if (x == Integer.MIN_VALUE || y == Integer.MIN_VALUE || z == Integer.MIN_VALUE) {
                errors.add("blocks[" + index + "]: needs integer x, y, z");
                continue;
            }
            if (block.isBlank()) {
                errors.add("blocks[" + index + "]: needs a block id");
                continue;
            }
            String why = SceneObject.str(o, "why");
            out.add(new Decor(x, y, z, normaliseId(block), why));
        }
        return out;
    }

    /**
     * Adds the {@code minecraft:} namespace when the model omitted it.
     *
     * @param id a block id, with or without namespace, with or without state
     * @return the namespaced id
     */
    static String normaliseId(String id) {
        String s = id.trim();
        return s.startsWith("minecraft:") ? s : "minecraft:" + s;
    }

    /**
     * Keeps only the decorations that make sense in the placed structure.
     *
     * <p>Rejections, in order of how often they happen in practice:</p>
     * <ul>
     *   <li>the cell is already occupied by the structure (the decoration would replace a wall);</li>
     *   <li>the cell has no solid neighbour to attach to (it would float);</li>
     *   <li>the id is not in the known block list of the server;</li>
     *   <li>the block is not a decoration at all (a full block would look like a mistake).</li>
     * </ul>
     *
     * @param decor      the proposed decorations
     * @param placed     the placed structure, or {@code null} to skip the occupancy test
     * @param knownBlock predicate telling whether a block id exists on this server
     * @param errors     collects the rejection reasons
     * @return the decorations to place
     */
    public static List<Decor> filter(List<Decor> decor, VoxelModel placed,
                                     java.util.function.Predicate<String> knownBlock, List<String> errors) {
        List<Decor> out = new ArrayList<>();
        for (Decor d : decor) {
            String bare = bareName(d.block());
            if (knownBlock != null && !knownBlock.test(bare)) {
                errors.add("unknown block " + d.block() + " at " + d.x() + "," + d.y() + "," + d.z());
                continue;
            }
            if (!isDecor(bare)) {
                errors.add("not a decoration: " + d.block() + " at " + d.x() + "," + d.y() + "," + d.z());
                continue;
            }
            if (placed != null) {
                if (placed.isSet(d.x(), d.y(), d.z())) {
                    errors.add("cell occupied by the structure: " + d.x() + "," + d.y() + "," + d.z());
                    continue;
                }
                if (!hasSupport(placed, d.x(), d.y(), d.z())) {
                    errors.add("no support: " + d.x() + "," + d.y() + "," + d.z());
                    continue;
                }
            }
            out.add(d);
        }
        return out;
    }

    /**
     * @param block a namespaced id with optional state
     * @return the bare id without namespace and state
     */
    static String bareName(String block) {
        String s = block;
        int bracket = s.indexOf('[');
        if (bracket >= 0) s = s.substring(0, bracket);
        return s.startsWith("minecraft:") ? s.substring("minecraft:".length()) : s;
    }

    /**
     * @param bare a block id without namespace
     * @return {@code true} when the id names a small or decorative block
     */
    static boolean isDecor(String bare) {
        String id = bare.toLowerCase(Locale.ROOT);
        for (String hint : DECOR_HINTS) {
            if (id.contains(hint)) return true;
        }
        return false;
    }

    /**
     * @return {@code true} when one of the six neighbours is part of the structure
     */
    static boolean hasSupport(VoxelModel placed, int x, int y, int z) {
        return placed.isSet(x, y - 1, z) || placed.isSet(x, y + 1, z)
                || placed.isSet(x - 1, y, z) || placed.isSet(x + 1, y, z)
                || placed.isSet(x, y, z - 1) || placed.isSet(x, y, z + 1);
    }

    /**
     * Converts a decoration into the model representation used by the placer.
     *
     * @param decor the decoration
     * @return a one-cell model holding the block
     */
    public static VoxelModel toModel(Decor decor) {
        VoxelModel model = new VoxelModel();
        model.set(decor.x(), decor.y(), decor.z(), BlockSpec.parseRaw(decor.block()));
        return model;
    }

    /**
     * Merges decorations into the placed model (used to save them as a new build version).
     *
     * @param placed the placed structure, not modified
     * @param decor  the decorations to add
     * @return a copy with the decorations applied
     */
    public static VoxelModel merge(VoxelModel placed, List<Decor> decor) {
        VoxelModel out = placed.copy();
        for (Decor d : decor) {
            out.set(d.x(), d.y(), d.z(), BlockSpec.parseRaw(d.block()));
        }
        return out;
    }

    /**
     * Summarises decorations by kind for the report.
     *
     * @param decor the placed decorations
     * @return bare block id to count, most frequent first
     */
    public static Map<String, Integer> summary(List<Decor> decor) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Decor d : decor) counts.merge(bareName(d.block()), 1, Integer::sum);
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(counts.entrySet());
        entries.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        Map<String, Integer> sorted = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : entries) sorted.put(e.getKey(), e.getValue());
        return sorted;
    }

    /**
     * Builds the one-line summary for the player, in Russian.
     *
     * @param decor the placed decorations
     * @return e.g. {@code "добавлено 14 мелочей: lantern ×6, flower_pot ×3, ..."}
     */
    public static String describe(List<Decor> decor) {
        if (decor.isEmpty()) return "мелких деталей не добавлено";
        StringBuilder sb = new StringBuilder("добавлено ").append(decor.size()).append(" мелочей: ");
        int n = 0;
        for (Map.Entry<String, Integer> e : summary(decor).entrySet()) {
            if (n++ > 0) sb.append(", ");
            if (n > 6) {
                sb.append("…");
                break;
            }
            sb.append(e.getKey()).append(" ×").append(e.getValue());
        }
        return sb.toString();
    }
}
