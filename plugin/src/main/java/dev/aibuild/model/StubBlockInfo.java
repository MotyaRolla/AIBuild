package dev.aibuild.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Heuristic, name-based {@link BlockInfo} for offline unit tests (no server needed).
 * Accepts any syntactically valid block name; knows default properties for common block families.
 */
public final class StubBlockInfo implements BlockInfo {
    private static final Pattern NAME = Pattern.compile("[a-z0-9_]+");
    private static final Map<String, String> ALIASES = Map.of(
            "chain", "iron_chain",
            "grass", "short_grass",
            "grass_path", "dirt_path");
    private static final Set<String> GRAVITY = Set.of("sand", "red_sand", "gravel", "anvil", "chipped_anvil",
            "damaged_anvil", "suspicious_sand", "suspicious_gravel", "dragon_egg");
    private static final Set<String> PLANTS = Set.of("poppy", "dandelion", "blue_orchid", "allium", "azure_bluet",
            "red_tulip", "orange_tulip", "white_tulip", "pink_tulip", "oxeye_daisy", "cornflower", "lily_of_the_valley",
            "short_grass", "fern", "dead_bush", "sweet_berry_bush", "bush", "firefly_bush", "torchflower", "wheat",
            "carrots", "potatoes", "beetroots", "sugar_cane", "oak_sapling", "spruce_sapling", "birch_sapling");
    private static final Set<String> DOUBLE_PLANTS = Set.of("tall_grass", "large_fern", "sunflower", "lilac",
            "rose_bush", "peony", "pitcher_plant");

    @Override
    public BlockSpec parse(String raw) {
        BlockSpec s = BlockSpec.parseRaw(raw);
        String n = ALIASES.getOrDefault(s.name(), s.name());
        if (!NAME.matcher(n).matches()) throw new IllegalArgumentException("Unknown block '" + s.name() + "'");
        Map<String, String> defs = defaults(n);
        for (String k : s.props().keySet()) {
            if (!defs.containsKey(k) && !genericProp(k)) {
                throw new IllegalArgumentException("Block '" + n + "' has no property '" + k + "'");
            }
        }
        return canonical(BlockSpec.of(n, s.props()));
    }

    private static boolean genericProp(String k) {
        // properties we do not model explicitly but accept in tests
        return Set.of("age", "level", "power", "rotation", "candles", "lit", "open", "powered", "type", "face",
                "attached", "hanging", "distance", "occupied", "signal_fire", "snowy", "up", "bottom", "pickles",
                "layers", "moisture", "stage", "honey_level", "has_book", "tilt", "segment_amount", "flower_amount").contains(k);
    }

    @Override
    public BlockSpec with(BlockSpec spec, String key, String value) {
        return canonical(spec.with(key, value));
    }

    @Override
    public BlockSpec canonical(BlockSpec spec) {
        Map<String, String> defs = defaults(spec.name());
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : spec.props().entrySet()) {
            if (!e.getValue().equals(defs.get(e.getKey()))) out.put(e.getKey(), e.getValue());
        }
        if (out.size() == spec.props().size()) return spec;
        return BlockSpec.of(spec.name(), out);
    }

    @Override
    public boolean hasProperty(String blockName, String property) {
        return defaults(blockName).containsKey(property);
    }

    @Override
    public String getProp(BlockSpec spec, String property) {
        String v = spec.prop(property);
        return v != null ? v : defaults(spec.name()).get(property);
    }

    /** Default property table for common families. */
    public static Map<String, String> defaults(String n) {
        Map<String, String> d = new HashMap<>();
        if (n.endsWith("_stairs")) {
            d.put("facing", "north"); d.put("half", "bottom"); d.put("shape", "straight"); d.put("waterlogged", "false");
        } else if (n.endsWith("_slab")) {
            d.put("type", "bottom"); d.put("waterlogged", "false");
        } else if (n.endsWith("_trapdoor")) {
            d.put("facing", "north"); d.put("half", "bottom"); d.put("open", "false"); d.put("powered", "false"); d.put("waterlogged", "false");
        } else if (n.endsWith("_door")) {
            d.put("facing", "north"); d.put("half", "lower"); d.put("hinge", "left"); d.put("open", "false"); d.put("powered", "false");
        } else if (n.endsWith("_fence_gate")) {
            d.put("facing", "north"); d.put("in_wall", "false"); d.put("open", "false"); d.put("powered", "false");
        } else if (n.endsWith("_fence") || n.endsWith("glass_pane") || n.equals("iron_bars")) {
            for (Dir h : Dir.HORIZONTAL) d.put(h.id(), "false");
            d.put("waterlogged", "false");
        } else if (n.endsWith("_wall") && !n.contains("wall_")) {
            for (Dir h : Dir.HORIZONTAL) d.put(h.id(), "none");
            d.put("up", "true"); d.put("waterlogged", "false");
        } else if (n.endsWith("_bed")) {
            d.put("facing", "north"); d.put("part", "foot"); d.put("occupied", "false");
        } else if (n.endsWith("lantern") && !n.equals("sea_lantern") && !n.equals("jack_o_lantern")) {
            d.put("hanging", "false"); d.put("waterlogged", "false");
        } else if (n.endsWith("wall_torch") || n.equals("ladder") || n.endsWith("_wall_sign") || n.endsWith("_wall_banner")) {
            d.put("facing", "north");
            if (n.equals("ladder")) d.put("waterlogged", "false");
        } else if (n.endsWith("_leaves")) {
            d.put("persistent", "false"); d.put("distance", "7"); d.put("waterlogged", "false");
        } else if (n.endsWith("campfire")) {
            d.put("facing", "north"); d.put("lit", "true"); d.put("signal_fire", "false"); d.put("waterlogged", "false");
        } else if (DOUBLE_PLANTS.contains(n)) {
            d.put("half", "lower");
        } else if (n.endsWith("candle")) {
            d.put("candles", "1"); d.put("lit", "false"); d.put("waterlogged", "false");
        } else if (n.equals("barrel")) {
            d.put("facing", "north"); d.put("open", "false");
        } else if (n.equals("chest") || n.equals("trapped_chest")) {
            d.put("facing", "north"); d.put("type", "single"); d.put("waterlogged", "false");
        } else if (n.equals("furnace") || n.equals("smoker") || n.equals("blast_furnace")) {
            d.put("facing", "north"); d.put("lit", "false");
        } else if (n.endsWith("_button") || n.equals("lever")) {
            d.put("face", "wall"); d.put("facing", "north"); d.put("powered", "false");
        } else if (n.endsWith("_log") || n.endsWith("_wood") || n.endsWith("_stem") || n.endsWith("_hyphae")
                || n.endsWith("pillar") || n.equals("hay_block") || n.equals("bone_block") || n.equals("iron_chain")
                || n.equals("basalt") || n.equals("polished_basalt")) {
            d.put("axis", "y");
        } else if (n.equals("grass_block") || n.equals("podzol") || n.equals("mycelium")) {
            d.put("snowy", "false");
        } else if (n.endsWith("_sign") || n.endsWith("_banner")) {
            d.put("rotation", "0");
        } else if (n.equals("anvil") || n.endsWith("glazed_terracotta") || n.equals("loom") || n.equals("stonecutter")
                || n.equals("lectern") || n.equals("grindstone") || n.equals("bell") || n.endsWith("_head")
                || n.equals("carved_pumpkin") || n.equals("jack_o_lantern") || n.equals("beehive")
                || n.equals("chiseled_bookshelf") || n.equals("decorated_pot")) {
            d.put("facing", "north");
            if (n.equals("grindstone")) d.put("face", "wall");
            if (n.equals("bell")) { d.put("attachment", "floor"); d.put("powered", "false"); }
        } else if (n.equals("water_cauldron")) {
            d.put("level", "1");
        } else if (n.equals("composter")) {
            d.put("level", "0");
        } else if (n.equals("vine")) {
            for (String side : List.of("north", "east", "south", "west", "up")) d.put(side, "false");
        }
        return d;
    }

    @Override
    public boolean isAir(BlockSpec spec) {
        return spec == null || spec.isAir();
    }

    @Override
    public boolean isSolid(BlockSpec s) {
        if (isAir(s)) return false;
        String n = s.name();
        if (PLANTS.contains(n) || DOUBLE_PLANTS.contains(n)) return false;
        return !(n.contains("torch") || n.endsWith("_sign") || n.endsWith("_banner") || n.endsWith("_button")
                || n.endsWith("pressure_plate") || n.equals("lever") || n.contains("rail") || n.equals("water")
                || n.equals("lava") || n.endsWith("vine") || n.endsWith("vines") || n.equals("cobweb")
                || n.equals("ladder") || n.endsWith("_carpet") || n.equals("snow") || n.endsWith("sapling")
                || n.startsWith("potted_") && false || n.equals("fire") || n.endsWith("_coral_fan") || n.equals("redstone_wire"));
    }

    @Override
    public boolean isOccluding(BlockSpec s) {
        if (!isSolid(s)) return false;
        String n = s.name();
        if (n.endsWith("_slab")) return "double".equals(s.prop("type"));
        String[] partial = {"stairs", "fence", "pane", "glass", "door", "torch", "lantern", "carpet", "leaves",
                "sign", "banner", "button", "pressure_plate", "rail", "ladder", "_bed", "chest", "candle", "pot",
                "chain", "bars", "campfire", "anvil", "cake", "_head", "skull", "sapling", "vine", "lichen", "ice",
                "slab", "lectern", "grindstone", "bell", "stonecutter", "brewing_stand", "cauldron", "hopper",
                "composter", "enchanting_table", "end_rod", "scaffolding", "shelf", "lightning_rod", "daylight",
                "honey_block", "slime_block", "beacon", "conduit", "barrier", "spawner", "dripleaf", "azalea",
                "decorated_pot", "flower", "wall_", "_wall"};
        for (String p : partial) if (n.contains(p)) return false;
        return true;
    }

    @Override
    public boolean isSturdyFace(BlockSpec s, Dir face) {
        if (isOccluding(s)) return true;
        String n = s.name();
        if (isAir(s)) return false;
        if (n.endsWith("_slab")) {
            String t = getProp(s, "type");
            if ("double".equals(t)) return true;
            return (face == Dir.DOWN && "bottom".equals(t)) || (face == Dir.UP && "top".equals(t));
        }
        if (n.endsWith("_stairs")) {
            String half = getProp(s, "half");
            if (face == Dir.DOWN) return "bottom".equals(half);
            if (face == Dir.UP) return "top".equals(half);
            Dir f = Dir.parse(getProp(s, "facing"));
            return face == f; // the tall back side
        }
        return n.contains("glass") && !n.contains("pane");
    }

    @Override
    public boolean hasGravity(BlockSpec s) {
        return s != null && (GRAVITY.contains(s.name()) || s.name().endsWith("_concrete_powder"));
    }

    @Override
    public int lightEmission(BlockSpec s) {
        if (s == null) return 0;
        String n = s.name();
        boolean lit = !"false".equals(getProp(s, "lit"));
        return switch (n) {
            case "torch", "wall_torch", "copper_torch", "copper_wall_torch", "end_rod" -> 14;
            case "soul_torch", "soul_wall_torch", "soul_lantern" -> 10;
            case "lantern", "copper_lantern", "glowstone", "sea_lantern", "shroomlight", "jack_o_lantern", "beacon",
                 "lava", "fire", "ochre_froglight", "verdant_froglight", "pearlescent_froglight" -> 15;
            case "campfire" -> lit ? 15 : 0;
            case "soul_campfire" -> lit ? 10 : 0;
            case "redstone_lamp" -> "true".equals(s.prop("lit")) ? 15 : 0;
            case "redstone_torch", "redstone_wall_torch" -> 7;
            case "magma_block" -> 3;
            case "furnace", "smoker", "blast_furnace" -> "true".equals(s.prop("lit")) ? 13 : 0;
            default -> {
                if (n.endsWith("candle") && "true".equals(s.prop("lit"))) {
                    int c = 1;
                    try { c = Integer.parseInt(getProp(s, "candles")); } catch (Exception ignored) { }
                    yield 3 * c;
                }
                yield 0;
            }
        };
    }

    @Override
    public boolean isLiquid(BlockSpec s) {
        return s != null && (s.name().equals("water") || s.name().equals("lava") || s.name().equals("bubble_column"));
    }

    @Override
    public int mapColor(BlockSpec s) {
        if (s == null || s.isAir()) return 0;
        String n = s.name();
        if (n.contains("oak") && !n.contains("dark")) return 0xA0824D;
        if (n.contains("spruce")) return 0x6B4F2E;
        if (n.contains("dark_oak")) return 0x4A3018;
        if (n.contains("birch")) return 0xD7CB8D;
        if (n.contains("cobble") || n.contains("stone")) return 0x7F7F7F;
        if (n.contains("glass")) return 0xC0E0FF;
        if (n.contains("leaves") || n.contains("grass")) return 0x5A8A2F;
        if (n.contains("brick")) return 0x96594A;
        return 0x808080 ^ (n.hashCode() & 0x3F3F3F);
    }

    @Override
    public BlockSpec rotate(BlockSpec spec, int turns) {
        int t = ((turns % 4) + 4) % 4;
        if (t == 0) return spec;
        BlockSpec s = spec;
        for (int i = 0; i < t; i++) s = rotateOnce(s);
        return canonical(s);
    }

    private BlockSpec rotateOnce(BlockSpec s) {
        Map<String, String> p = new LinkedHashMap<>(s.props());
        String f = getProp(s, "facing");
        Dir d = Dir.parse(f);
        if (d != null && d.isHorizontal()) p.put("facing", d.cw().id());
        String axis = s.prop("axis");
        if ("x".equals(axis)) p.put("axis", "z");
        else if ("z".equals(axis)) p.put("axis", "x");
        String rot = s.prop("rotation");
        if (rot != null) {
            try { p.put("rotation", Integer.toString((Integer.parseInt(rot) + 4) % 16)); } catch (NumberFormatException ignored) { }
        }
        // multiple-facing connections (fences, panes, walls)
        Map<String, String> conn = new HashMap<>();
        for (Dir h : Dir.HORIZONTAL) if (s.props().containsKey(h.id())) conn.put(h.cw().id(), s.props().get(h.id()));
        if (!conn.isEmpty()) {
            for (Dir h : Dir.HORIZONTAL) p.remove(h.id());
            p.putAll(conn);
        }
        return BlockSpec.of(s.name(), p);
    }

    @Override
    public BlockSpec mirror(BlockSpec s, char axis) {
        if (s.props().isEmpty() && defaults(s.name()).get("facing") == null) return s;
        Map<String, String> p = new LinkedHashMap<>(s.props());
        Dir d = Dir.parse(getProp(s, "facing"));
        if (d != null && d.isHorizontal() && d.axis() == axis) p.put("facing", d.opposite().id());
        // horizontal connections
        if (axis == 'x') swap(p, "east", "west");
        else swap(p, "north", "south");
        String shape = s.prop("shape");
        if (shape != null && s.name().endsWith("_stairs")) {
            if (shape.endsWith("_left")) p.put("shape", shape.replace("_left", "_right"));
            else if (shape.endsWith("_right")) p.put("shape", shape.replace("_right", "_left"));
        }
        if (s.name().endsWith("_door")) {
            p.put("hinge", "right".equals(getProp(s, "hinge")) ? "left" : "right");
        }
        String rot = s.prop("rotation");
        if (rot != null) {
            try {
                int r = Integer.parseInt(rot);
                p.put("rotation", Integer.toString(axis == 'z' ? (8 - r + 16) % 16 : (16 - r) % 16));
            } catch (NumberFormatException ignored) { }
        }
        return canonical(BlockSpec.of(s.name(), p));
    }

    private static void swap(Map<String, String> p, String a, String b) {
        String va = p.remove(a), vb = p.remove(b);
        if (vb != null) p.put(a, vb);
        if (va != null) p.put(b, va);
    }

    @Override
    public List<String> blockNames() {
        List<String> l = new ArrayList<>(List.of("air", "stone", "cobblestone", "mossy_cobblestone", "stone_bricks",
                "oak_planks", "spruce_planks", "dark_oak_planks", "oak_log", "spruce_log", "stripped_spruce_log",
                "oak_stairs", "spruce_stairs", "oak_slab", "spruce_slab", "glass", "glass_pane", "oak_door",
                "spruce_door", "oak_trapdoor", "spruce_trapdoor", "oak_fence", "spruce_fence", "torch", "wall_torch",
                "lantern", "iron_chain", "red_bed", "chest", "barrel", "crafting_table", "furnace", "bookshelf",
                "white_terracotta", "grass_block", "dirt_path", "oak_leaves", "poppy", "campfire", "ladder",
                "stone_stairs", "stone_slab", "cobblestone_stairs", "cobblestone_slab", "cobblestone_wall",
                "mossy_cobblestone_stairs", "mossy_cobblestone_slab", "stone_brick_stairs", "stone_brick_slab",
                "stone_brick_wall", "mossy_stone_bricks", "cracked_stone_bricks", "mossy_stone_brick_stairs",
                "mossy_stone_brick_slab", "dark_oak_stairs", "dark_oak_slab", "dark_oak_log", "dark_oak_fence",
                "spruce_leaves", "birch_log", "birch_leaves", "bricks", "brick_stairs", "brick_slab", "brick_wall",
                "deepslate_tiles", "deepslate_tile_stairs", "deepslate_tile_slab", "andesite", "coarse_dirt", "gravel",
                "dirt", "lightning_rod", "water", "oak_pressure_plate", "spruce_pressure_plate", "lantern",
                "bookshelf", "red_carpet", "white_wool", "short_grass", "dandelion", "cornflower", "oxeye_daisy",
                "azure_bluet", "fern", "iron_chain", "barrel", "smoker", "cauldron", "glass_pane", "spruce_trapdoor"));
        l = new ArrayList<>(new java.util.TreeSet<>(l));
        l.sort(null);
        return l;
    }
}
