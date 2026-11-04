package dev.aibuild.bukkit;

import dev.aibuild.model.BlockInfo;
import dev.aibuild.model.BlockSpec;
import dev.aibuild.model.Dir;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockSupport;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.structure.Mirror;
import org.bukkit.block.structure.StructureRotation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link BlockInfo} backed by the live server block registry (Paper API). Thread-safe; results are cached.
 */
public final class BukkitBlockInfo implements BlockInfo {

    /** Renamed / commonly hallucinated block ids -> valid 26.3 ids. */
    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("chain", "iron_chain"),
            Map.entry("grass", "short_grass"),
            Map.entry("grass_path", "dirt_path"),
            Map.entry("path", "dirt_path"),
            Map.entry("wooden_door", "oak_door"),
            Map.entry("wood_door", "oak_door"),
            Map.entry("trapdoor", "oak_trapdoor"),
            Map.entry("wooden_trapdoor", "oak_trapdoor"),
            Map.entry("fence", "oak_fence"),
            Map.entry("fence_gate", "oak_fence_gate"),
            Map.entry("wooden_slab", "oak_slab"),
            Map.entry("wooden_stairs", "oak_stairs"),
            Map.entry("planks", "oak_planks"),
            Map.entry("log", "oak_log"),
            Map.entry("leaves", "oak_leaves"),
            Map.entry("stone_brick", "stone_bricks"),
            Map.entry("brick", "bricks"),
            Map.entry("bed", "red_bed"),
            Map.entry("carpet", "white_carpet"),
            Map.entry("wool", "white_wool"),
            Map.entry("glass_panes", "glass_pane"),
            Map.entry("thin_glass", "glass_pane"),
            Map.entry("sign", "oak_sign"),
            Map.entry("wall_sign", "oak_wall_sign"),
            Map.entry("flower_pot_with_flower", "potted_poppy"),
            Map.entry("rose", "poppy"),
            Map.entry("red_flower", "poppy"),
            Map.entry("yellow_flower", "dandelion"),
            Map.entry("tall_flower", "rose_bush"),
            Map.entry("cobble", "cobblestone"),
            Map.entry("mossy_cobble", "mossy_cobblestone"),
            Map.entry("workbench", "crafting_table"),
            Map.entry("smooth_stone_slab_double", "smooth_stone"),
            Map.entry("lit_furnace", "furnace"),
            Map.entry("lit_pumpkin", "jack_o_lantern"),
            Map.entry("hay", "hay_block"),
            Map.entry("hay_bale", "hay_block"),
            Map.entry("snow_layer", "snow"),
            Map.entry("water_source", "water"),
            Map.entry("torch_wall", "wall_torch"),
            Map.entry("wall_lantern", "lantern"),
            Map.entry("hanging_lantern", "lantern"),
            Map.entry("bush", "bush"));

    private static final Set<String> TECHNICAL = Set.of("moving_piston", "piston_head", "end_gateway", "end_portal",
            "nether_portal", "structure_void", "jigsaw", "structure_block", "command_block", "chain_command_block",
            "repeating_command_block", "light", "bubble_column", "frosted_ice", "barrier", "test_block",
            "test_instance_block", "void_air", "cave_air", "spawner", "trial_spawner", "vault", "reinforced_deepslate");

    private final Map<String, Object> parseCache = new ConcurrentHashMap<>(); // raw -> BlockSpec | String error
    private final Map<String, BlockData> dataCache = new ConcurrentHashMap<>();
    private final Map<Material, Map<String, String>> defaults = new ConcurrentHashMap<>();
    private volatile List<String> names;

    @Override
    public BlockSpec parse(String raw) throws IllegalArgumentException {
        if (raw == null) throw new IllegalArgumentException("block is null");
        Object cached = parseCache.get(raw);
        if (cached == null) {
            try {
                cached = doParse(raw);
            } catch (IllegalArgumentException e) {
                cached = e.getMessage();
            }
            if (parseCache.size() < 50_000) parseCache.put(raw, cached);
        }
        if (cached instanceof BlockSpec b) return b;
        throw new IllegalArgumentException((String) cached);
    }

    private BlockSpec doParse(String raw) {
        BlockSpec s = BlockSpec.parseRaw(raw);
        String name = s.name();
        if (name.startsWith("minecraft:")) name = name.substring(10);
        name = ALIASES.getOrDefault(name, name);
        Material m = Material.getMaterial(name.toUpperCase(Locale.ROOT));
        if (m == null || !m.isBlock() || m.isLegacy()) {
            // deepslate_brick, deepslate_tile, nether_brick: the singular names the model keeps writing for the block
            Material plural = Material.getMaterial(name.toUpperCase(Locale.ROOT) + "S");
            if (plural != null && plural.isBlock() && !plural.isLegacy()) {
                m = plural;
                name = name + "s";
            }
        }
        if (m == null || !m.isBlock() || m.isLegacy()) {
            throw new IllegalArgumentException("unknown block '" + s.name() + "'" + suggestion(name));
        }
        Map<String, String> def = defaultsOf(m);
        StringBuilder state = new StringBuilder();
        for (Map.Entry<String, String> e : s.props().entrySet()) {
            if (!def.containsKey(e.getKey())) {
                throw new IllegalArgumentException("block '" + name + "' has no property '" + e.getKey() + "'"
                        + (def.isEmpty() ? " (it has no properties)" : " (valid: " + String.join(", ", def.keySet()) + ")"));
            }
            state.append(state.isEmpty() ? "[" : ",").append(e.getKey()).append('=').append(e.getValue());
        }
        if (!state.isEmpty()) state.append(']');
        BlockData data;
        try {
            data = state.isEmpty() ? m.createBlockData() : Bukkit.createBlockData(m, state.toString());
        } catch (IllegalArgumentException ex) {
            // find the offending property for a precise message
            for (Map.Entry<String, String> e : s.props().entrySet()) {
                try {
                    Bukkit.createBlockData(m, "[" + e.getKey() + "=" + e.getValue() + "]");
                } catch (IllegalArgumentException ex2) {
                    throw new IllegalArgumentException("invalid value '" + e.getValue() + "' for property '" + e.getKey()
                            + "' of " + name + " (default " + e.getKey() + "=" + def.get(e.getKey()) + ")" + allowedHint(e.getKey()));
                }
            }
            throw new IllegalArgumentException("invalid block state '" + raw + "': " + ex.getMessage());
        }
        return fromData(data);
    }

    private static String allowedHint(String key) {
        return switch (key) {
            case "facing" -> "; allowed: north, east, south, west (some blocks also up, down)";
            case "half" -> "; stairs/trapdoors: top|bottom, doors/tall plants: upper|lower";
            case "type" -> "; slabs: top|bottom|double, chests: single|left|right";
            case "axis" -> "; allowed: x, y, z";
            case "shape" -> "; stairs: straight|inner_left|inner_right|outer_left|outer_right";
            case "hinge" -> "; allowed: left, right";
            case "part" -> "; beds: foot|head";
            case "face" -> "; allowed: floor, wall, ceiling";
            default -> "";
        };
    }

    /** Converts live BlockData to a canonical spec (defaults stripped). */
    public BlockSpec fromData(BlockData data) {
        Material m = data.getMaterial();
        Map<String, String> def = defaultsOf(m);
        Map<String, String> props = parseProps(data.getAsString(false));
        TreeMap<String, String> out = new TreeMap<>();
        for (Map.Entry<String, String> e : props.entrySet()) {
            if (!e.getValue().equals(def.get(e.getKey()))) out.put(e.getKey(), e.getValue());
        }
        return BlockSpec.of(m.getKey().getKey(), out);
    }

    private Map<String, String> defaultsOf(Material m) {
        return defaults.computeIfAbsent(m, mm -> Collections.unmodifiableMap(parseProps(mm.createBlockData().getAsString(false))));
    }

    private static Map<String, String> parseProps(String full) {
        int br = full.indexOf('[');
        if (br < 0 || !full.endsWith("]")) return Map.of();
        LinkedHashMap<String, String> map = new LinkedHashMap<>();
        for (String part : full.substring(br + 1, full.length() - 1).split(",")) {
            int eq = part.indexOf('=');
            if (eq > 0) map.put(part.substring(0, eq), part.substring(eq + 1));
        }
        return map;
    }

    private String suggestion(String name) {
        List<String> all = blockNames();
        List<String> best = new ArrayList<>();
        // substring hits first
        for (String n : all) {
            if (n.equals(name + "s") || n.equals(name + "_planks") || n.equals(name + "_block")) best.add(n);
        }
        List<String> sorted = new ArrayList<>(all);
        sorted.sort(Comparator.comparingInt(n -> levenshtein(name, n)));
        for (String n : sorted) {
            if (best.size() >= 3) break;
            if (!best.contains(n) && levenshtein(name, n) <= Math.max(3, name.length() / 3)) best.add(n);
        }
        return best.isEmpty() ? "" : " (did you mean " + String.join(" / ", best) + "?)";
    }

    private static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1], cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int c = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + c);
            }
            int[] t = prev; prev = cur; cur = t;
        }
        return prev[b.length()];
    }

    /** Live BlockData for a spec (a fresh clone each call — safe to mutate). */
    public BlockData data(BlockSpec spec) {
        BlockData d = dataCache.get(spec.toString());
        if (d == null) {
            try {
                d = Bukkit.createBlockData(withNamespace(spec));
            } catch (IllegalArgumentException e) {
                // a spec that bypassed validation: try the canonical form, else the bare block, else stone
                BlockData fb;
                try {
                    fb = Bukkit.createBlockData(withNamespace(parse(spec.toString())));
                } catch (IllegalArgumentException e2) {
                    Material m = Material.getMaterial(spec.name().toUpperCase(Locale.ROOT));
                    fb = (m != null && m.isBlock()) ? m.createBlockData() : Material.STONE.createBlockData();
                }
                d = fb;
            }
            if (dataCache.size() < 20_000) dataCache.put(spec.toString(), d);
        }
        return d.clone();
    }

    private static String withNamespace(BlockSpec s) {
        return "minecraft:" + s.toString();
    }

    private BlockData dataQuiet(BlockSpec spec) {
        try {
            return data(spec);
        } catch (RuntimeException e) {
            return Material.STONE.createBlockData();
        }
    }

    @Override
    public BlockSpec with(BlockSpec spec, String key, String value) {
        return parse(spec.with(key, value).toString());
    }

    @Override
    public BlockSpec canonical(BlockSpec spec) {
        try {
            return parse(spec.toString());
        } catch (IllegalArgumentException e) {
            return spec;
        }
    }

    @Override
    public boolean hasProperty(String blockName, String property) {
        Material m = Material.getMaterial(BlockSpec.normalizeName(blockName).toUpperCase(Locale.ROOT));
        return m != null && m.isBlock() && defaultsOf(m).containsKey(property);
    }

    @Override
    public String getProp(BlockSpec spec, String property) {
        String v = spec.prop(property);
        if (v != null) return v;
        Material m = Material.getMaterial(spec.name().toUpperCase(Locale.ROOT));
        return m == null || !m.isBlock() ? null : defaultsOf(m).get(property);
    }

    private static Material mat(BlockSpec s) {
        Material m = s == null ? null : Material.getMaterial(s.name().toUpperCase(Locale.ROOT));
        return m == null ? Material.AIR : m;
    }

    @Override
    public boolean isAir(BlockSpec spec) {
        return spec == null || spec.isAir();
    }

    @Override
    public boolean isSolid(BlockSpec spec) {
        return !isAir(spec) && mat(spec).isSolid();
    }

    @Override
    public boolean isOccluding(BlockSpec spec) {
        return !isAir(spec) && dataQuiet(spec).isOccluding();
    }

    @Override
    public boolean isSturdyFace(BlockSpec spec, Dir face) {
        if (isAir(spec)) return false;
        BlockFace f = switch (face) {
            case NORTH -> BlockFace.NORTH;
            case SOUTH -> BlockFace.SOUTH;
            case EAST -> BlockFace.EAST;
            case WEST -> BlockFace.WEST;
            case UP -> BlockFace.UP;
            case DOWN -> BlockFace.DOWN;
        };
        try {
            return dataQuiet(spec).isFaceSturdy(f, BlockSupport.FULL);
        } catch (RuntimeException e) {
            return isOccluding(spec);
        }
    }

    @Override
    public boolean hasGravity(BlockSpec spec) {
        return !isAir(spec) && mat(spec).hasGravity();
    }

    @Override
    public int lightEmission(BlockSpec spec) {
        if (isAir(spec)) return 0;
        try {
            return dataQuiet(spec).getLightEmission();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    @Override
    public boolean isLiquid(BlockSpec spec) {
        Material m = mat(spec);
        return m == Material.WATER || m == Material.LAVA || m == Material.BUBBLE_COLUMN;
    }

    @Override
    public int mapColor(BlockSpec spec) {
        if (isAir(spec)) return 0;
        try {
            return dataQuiet(spec).getMapColor().asRGB();
        } catch (RuntimeException e) {
            return 0x808080;
        }
    }

    @Override
    public BlockSpec rotate(BlockSpec spec, int turns) {
        int t = ((turns % 4) + 4) % 4;
        if (t == 0 || spec.props().isEmpty() && defaultsOf(mat(spec)).isEmpty()) return spec;
        BlockData d = dataQuiet(spec);
        d.rotate(switch (t) {
            case 1 -> StructureRotation.CLOCKWISE_90;
            case 2 -> StructureRotation.CLOCKWISE_180;
            default -> StructureRotation.COUNTERCLOCKWISE_90;
        });
        return fromData(d);
    }

    @Override
    public BlockSpec mirror(BlockSpec spec, char axis) {
        if (spec.props().isEmpty() && defaultsOf(mat(spec)).isEmpty()) return spec;
        BlockData d = dataQuiet(spec);
        d.mirror(axis == 'x' ? Mirror.FRONT_BACK : Mirror.LEFT_RIGHT);
        return fromData(d);
    }

    @Override
    public List<String> blockNames() {
        List<String> n = names;
        if (n == null) {
            List<String> l = new ArrayList<>();
            for (Material m : Material.values()) {
                if (!m.isBlock() || m.isLegacy()) continue;
                String k = m.getKey().getKey();
                if (TECHNICAL.contains(k)) continue;
                l.add(k);
            }
            Collections.sort(l);
            names = n = Collections.unmodifiableList(l);
        }
        return n;
    }

    /** Compact block list for prompts grouped by family to save tokens. */
    public String blockListForPrompt() {
        // group by suffix families so the list stays readable: e.g. "*_planks: oak, spruce, ..."
        String[] families = {"_planks", "_log", "_wood", "_stairs", "_slab", "_wall", "_fence", "_fence_gate", "_door",
                "_trapdoor", "_pressure_plate", "_button", "_sign", "_wall_sign", "_hanging_sign", "_wall_hanging_sign",
                "_leaves", "_sapling", "_wool", "_carpet", "_terracotta", "_glazed_terracotta", "_concrete",
                "_concrete_powder", "_stained_glass", "_stained_glass_pane", "_candle", "_bed", "_banner", "_wall_banner",
                "_shulker_box", "_shelf", "_bars", "_lantern", "_chain"};
        Map<String, List<String>> fam = new LinkedHashMap<>();
        for (String f : families) fam.put(f, new ArrayList<>());
        List<String> rest = new ArrayList<>();
        outer:
        for (String n : blockNames()) {
            String best = null;
            for (String f : families) {
                if (n.endsWith(f) && (best == null || f.length() > best.length())) best = f;
            }
            if (best != null) {
                String prefix = n.substring(0, n.length() - best.length());
                if (!prefix.isEmpty()) {
                    fam.get(best).add(prefix);
                    continue outer;
                }
            }
            rest.add(n);
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, List<String>> e : fam.entrySet()) {
            if (e.getValue().isEmpty()) continue;
            sb.append("<X>").append(e.getKey()).append(" where X in {").append(String.join(",", e.getValue())).append("}\n");
        }
        sb.append("other blocks: ").append(String.join(",", rest)).append('\n');
        return sb.toString();
    }

    /** Light-weight counters for debugging. */
    public Map<String, Integer> cacheStats() {
        Map<String, Integer> m = new HashMap<>();
        m.put("parse", parseCache.size());
        m.put("data", dataCache.size());
        return m;
    }
}
