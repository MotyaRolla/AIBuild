package dev.aibuild.model;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Finds the shape variants of a full block: {@code stone_bricks -> stone_brick_stairs / _slab / _wall},
 * {@code oak_planks -> oak_stairs}, {@code oak_log -> oak_stairs} and weathered variants
 * ({@code mossy_stone_bricks}, {@code cracked_stone_bricks}, {@code exposed_copper}).
 *
 * <p>Names are derived from the block name and accepted only if the registry ({@link BlockInfo#blockNames()})
 * contains them, so a missing variant degrades to "no smoothing" instead of an invalid block.</p>
 */
public final class BlockFamilies {
    /** Roots whose derived name is irregular. */
    private static final Map<String, String> ROOTS = Map.ofEntries(
            Map.entry("bricks", "brick"),
            Map.entry("smooth_stone", "stone"),
            Map.entry("copper_block", "cut_copper"),
            Map.entry("waxed_copper_block", "waxed_cut_copper"),
            Map.entry("quartz_block", "quartz"),
            Map.entry("purpur_block", "purpur"),
            Map.entry("bamboo_block", "bamboo"),
            Map.entry("chiseled_stone_bricks", "stone_brick"),
            Map.entry("cracked_stone_bricks", "stone_brick"),
            Map.entry("basalt", "blackstone"),
            Map.entry("polished_basalt", "polished_blackstone"),
            Map.entry("smooth_basalt", "polished_blackstone"),
            Map.entry("calcite", "diorite"),
            Map.entry("dripstone_block", "granite"),
            Map.entry("packed_mud", "mud_brick"),
            Map.entry("netherrack", "nether_brick"),
            Map.entry("end_stone", "end_stone_brick"));

    private final Set<String> names;
    private final Map<String, String[]> cache = new ConcurrentHashMap<>();

    public BlockFamilies(BlockInfo info) {
        this.names = new HashSet<>(info.blockNames());
    }

    /** Matching stairs block name, or null. */
    public String stairs(String full) {
        return variants(full)[0];
    }

    /** Matching slab block name, or null. */
    public String slab(String full) {
        return variants(full)[1];
    }

    /** Matching wall block name, or null. */
    public String wall(String full) {
        return variants(full)[2];
    }

    /** Matching fence block name, or null. */
    public String fence(String full) {
        return variants(full)[3];
    }

    /** Weathered variants of {@code name} that exist (mossy, cracked, exposed copper...), possibly empty. */
    public List<String> weathered(String name) {
        List<String> out = new ArrayList<>();
        for (String prefix : new String[]{"mossy_", "cracked_"}) {
            if (names.contains(prefix + name)) out.add(prefix + name);
        }
        if (name.contains("copper") && !name.startsWith("exposed_") && !name.startsWith("weathered_") && !name.startsWith("oxidized_")) {
            String bare = name.startsWith("waxed_") ? name.substring(6) : name;
            for (String prefix : new String[]{"exposed_", "weathered_"}) {
                if (names.contains(prefix + bare)) out.add(prefix + bare);
            }
        }
        return out;
    }

    public boolean exists(String name) {
        return names.contains(name);
    }

    private String[] variants(String full) {
        return cache.computeIfAbsent(full, n -> {
            String[] out = new String[4];
            String[] suffix = {"_stairs", "_slab", "_wall", "_fence"};
            for (String root : roots(n)) {
                for (int i = 0; i < 4; i++) {
                    if (out[i] == null && names.contains(root + suffix[i])) out[i] = root + suffix[i];
                }
            }
            return out;
        });
    }

    private static List<String> roots(String n) {
        List<String> r = new ArrayList<>();
        if (ROOTS.containsKey(n)) r.add(ROOTS.get(n));
        String base = n.startsWith("stripped_") ? n.substring(9) : n;
        if (base.endsWith("_planks")) r.add(base.substring(0, base.length() - 7));
        else if (base.endsWith("_log") || base.endsWith("_wood")) r.add(base.substring(0, base.length() - 4));
        else if (base.endsWith("_stem") || base.endsWith("_hyphae")) r.add(base.substring(0, base.lastIndexOf('_')));
        else if (base.endsWith("_bricks") || base.endsWith("_tiles")) r.add(base.substring(0, base.length() - 1));
        else if (base.endsWith("_block")) r.add(base.substring(0, base.length() - 6));
        r.add(base);
        if (base.startsWith("polished_") || base.startsWith("chiseled_") || base.startsWith("cut_")) {
            r.add(base.substring(base.indexOf('_') + 1));
        }
        if (base.startsWith("mossy_") || base.startsWith("cracked_")) {
            String rest = base.substring(base.indexOf('_') + 1);
            r.addAll(roots(rest));
        }
        return r;
    }
}
