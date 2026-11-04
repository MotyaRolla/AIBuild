package dev.aibuild.script;

import dev.aibuild.model.BlockFamilies;
import dev.aibuild.model.BlockInfo;
import dev.aibuild.model.BlockRoles;
import dev.aibuild.model.BlockSpec;
import dev.aibuild.model.VoxelModel;
import dev.aibuild.script.geom.Frame;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The model a build script writes into: a coordinate-frame stack, named parts, the plot limits and the
 * cell budget. Every write goes through {@link #setWorld}, so limits and part ownership are enforced in one
 * place. Not thread-safe; one canvas belongs to one script run.
 */
public final class Canvas {
    /** Part id used for cells written outside any {@code part(...)}. */
    public static final String NO_PART = "(main)";

    final VoxelModel model;
    final BlockInfo info;
    final BlockFamilies families;
    private final int[] limits;
    private final int maxCells;
    private final Deque<Frame> frames = new ArrayDeque<>();
    private Frame frame = Frame.identity();
    private final Deque<String> partStack = new ArrayDeque<>();
    private final Map<Long, String> owner = new HashMap<>();
    private final Map<String, int[]> partBounds = new LinkedHashMap<>();
    private final Set<Long> smoothed = new HashSet<>();
    private final List<String> warnings = new ArrayList<>();
    private long outside;
    private long writes;
    /** Damaged cells per "victim|culprit|kind", in first-seen order, with the block that was there before. */
    private final Map<String, Map<Long, BlockSpec>> damage = new LinkedHashMap<>();
    private int damageCells;
    /** Wall cells one top-level part replaced in another, per "victim|culprit": count and box. */
    private final Map<String, int[]> overlap = new LinkedHashMap<>();
    /** Calls so far per part path, so every call of a part gets its own random stream. */
    private final Map<String, Integer> partCalls = new HashMap<>();
    /** The tree being placed (trees nest no further), and the tree that placed each cell still standing. */
    private Growth growing;
    private final Map<Long, Growth> grown = new HashMap<>();
    private final List<Growth> trees = new ArrayList<>();
    /** A building being placed by volume(): it fits around the masonry other parts already built. */
    private int fitting;
    private String fitPart, fitLabel, fitBy;
    private int fitBlocked;
    /** Cells fitted buildings placed (cell -> "label|part"), and how many of them a later part cut, per "label|part|culprit". */
    private final Map<Long, String> fitted = new HashMap<>();
    private final Map<String, Integer> fitCut = new LinkedHashMap<>();
    /** Writes the interior part made into other parts' walls, floors or stairs, which were skipped. */
    private int guarded;
    private String guardedAt;
    /** Write rules per part name, from confine(): {@link #OTHERS} or {@link #ROOMS}. */
    private final Map<String, String> confined = new HashMap<>();
    /** The confined part being run (its full path), its rule, and the room boxes {x1,z1,x2,z2,y} for ROOMS. */
    private String scopePath, scopeKind;
    private List<int[]> scopeRooms = List.of();
    private final Map<String, Confinement> confinements = new LinkedHashMap<>();

    /** Named points and boxes of the build, in world coordinates, in the order they were set (the build sheet). */
    private final Map<String, Anchor> anchors = new LinkedHashMap<>();
    /** Intent lines per part path, from note(). */
    private final Map<String, List<String>> notes = new LinkedHashMap<>();
    static final int MAX_ANCHORS = 400, MAX_NOTES_PER_PART = 3, MAX_NOTE = 200;

    /**
     * A named point or box in world coordinates ({minX,minY,minZ,maxX,maxY,maxZ}; a point has min = max), set by
     * mark() or by a kit ({@code auto}), with an optional note.
     */
    public record Anchor(String key, String part, int[] box, boolean point, String note, boolean auto) {
        public String describe() {
            String where = point ? "(" + box[0] + "," + box[1] + "," + box[2] + ")"
                    : "box x " + box[0] + ".." + box[3] + ", y " + box[1] + ".." + box[4] + ", z " + box[2] + ".." + box[5];
            return key + " " + where + (note.isEmpty() ? "" : " — " + note);
        }
    }

    /** confine() rule: the part may not break blocks other parts placed before it. */
    public static final String OTHERS = "others";
    /** confine() rule: the part writes only inside the rooms that stand when it starts. */
    public static final String ROOMS = "rooms";

    /** What a confined part tried: its writes, the ones dropped, and a few of the dropped cells. */
    public static final class Confinement {
        public final String kind;
        public int writes, dropped;
        public final List<String> samples = new ArrayList<>();

        Confinement(String kind) {
            this.kind = kind;
        }

        /** The share of the part's writes that was dropped, 0 when it wrote nothing. */
        public double droppedShare() {
            return writes == 0 ? 0 : (double) dropped / writes;
        }
    }

    /** The part an interior designer furnishes in: it may fill rooms but not alter the building around them. */
    public static boolean interiorName(String name) {
        String l = name.toLowerCase(java.util.Locale.ROOT);
        return l.equals("interiors") || l.equals("interior") || l.equals("furniture") || l.equals("furnishing") || l.equals("rooms");
    }

    /**
     * One tree() call. Trees give way to buildings: a trunk or branch never replaces a wall, and a tree that would
     * grow into one, or that a later part cuts through, is taken out whole rather than left half inside the wall.
     */
    private static final class Growth {
        final int depth;
        final Map<Long, BlockSpec> was = new LinkedHashMap<>();
        final Map<Long, String> wasOwner = new HashMap<>();
        final Map<String, Integer> cutBy = new LinkedHashMap<>();
        int blocked;
        String blockedBy;
        long blockedAt;

        Growth(int depth) {
            this.depth = depth;
        }
    }

    /**
     * @param model    target model (may already contain a base build)
     * @param limits   allowed world box {minX,minY,minZ,maxX,maxY,maxZ} or null for no plot limit
     * @param maxCells maximum stored cells in the model
     */
    public Canvas(VoxelModel model, BlockInfo info, BlockFamilies families, int[] limits, int maxCells) {
        this.model = model;
        this.info = info;
        this.families = families;
        this.limits = limits == null ? null : limits.clone();
        this.maxCells = maxCells;
    }

    // ------------------------------------------------------------------ frames and parts

    public Frame frame() {
        return frame;
    }

    public void push(Frame local) {
        frames.push(frame);
        frame = frame.then(local);
    }

    public void pop() {
        if (frames.isEmpty()) throw new IllegalStateException("pop() without matching push");
        frame = frames.pop();
    }

    public void beginPart(String name) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("part name must not be empty");
        String full = partStack.isEmpty() ? name : partStack.peek() + "/" + name;
        partStack.push(full);
        partBounds.putIfAbsent(full, null);
        partCalls.merge(full, 1, Integer::sum);
        String rule = scopePath == null ? confined.get(name) : null;
        if (rule != null) {
            scopePath = full;
            scopeKind = rule;
            scopeRooms = List.of();
            confinements.computeIfAbsent(full, q -> new Confinement(rule));
        }
        if (growing == null && (name.equals("tree") || name.endsWith("/tree"))) {
            growing = new Growth(partStack.size());
            trees.add(growing);
        }
    }

    public void endPart() {
        if (growing != null && growing.depth == partStack.size()) {
            Growth g = growing;
            growing = null;
            if (g.blocked > Math.max(4, g.was.size() / 25)) {
                uproot(g);
                warn("a tree at " + at(g) + " would grow into " + g.blockedBy + " (" + g.blocked + " blocks), so it was left out; plant trees in open ground");
            }
        }
        if (!partStack.isEmpty() && partStack.peek().equals(scopePath)) scopePath = null;
        if (!partStack.isEmpty()) partStack.pop();
    }

    /**
     * Puts a write rule on every call of the part named {@code name}: {@link #OTHERS} drops its writes that would
     * break blocks other parts placed (their walls, floors, stairs, doors), {@link #ROOMS} drops its writes
     * outside the rooms standing when it starts. A pipeline stage that rewrote one part sets the rule, so a few
     * stray cells cost those cells instead of the whole rewrite.
     */
    public void confine(String name, String kind) {
        if (!OTHERS.equals(kind) && !ROOMS.equals(kind)) throw new IllegalArgumentException("confine(): rule must be 'others' or 'rooms'");
        confined.put(name, kind);
    }

    /** Records one line of intent for the current part (clipped; at most three per part, repeats ignored). */
    public void note(String text) {
        String t = text == null ? "" : text.strip().replaceAll("\\s+", " ");
        if (t.isEmpty()) return;
        if (t.length() > MAX_NOTE) t = t.substring(0, MAX_NOTE - 1) + "…";
        List<String> list = notes.computeIfAbsent(currentPart(), q -> new ArrayList<>());
        if (list.size() < MAX_NOTES_PER_PART && !list.contains(t)) list.add(t);
    }

    /**
     * Records a named point (3 local coordinates) or box (6) of the current part in world coordinates. A kit's
     * anchor ({@code auto}) is named "part.name" and numbered when the part has several ("keep.door2"); a script's
     * own mark keeps its name, a later mark of the same name replacing it. Returns the stored name.
     */
    public String mark(String name, double[] c, String note, boolean auto) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("mark(): the name must not be empty");
        if (c.length != 3 && c.length != 6) throw new IllegalArgumentException("mark(): give x, y, z or [x1, y1, z1, x2, y2, z2]");
        String part = currentPart();
        String key = name.strip();
        if (auto) {
            String base = (NO_PART.equals(part) ? "" : part + ".") + key;
            key = base;
            for (int i = 2; anchors.containsKey(key); i++) key = base + i;
        }
        if (anchors.size() >= MAX_ANCHORS && !anchors.containsKey(key)) return key;
        double[] p = frame.point(c[0], c[1], c[2]);
        int[] box;
        if (c.length == 3) {
            int x = (int) Math.round(p[0]), y = (int) Math.round(p[1]), z = (int) Math.round(p[2]);
            box = new int[]{x, y, z, x, y, z};
        } else {
            double[] q = frame.point(c[3], c[4], c[5]);
            box = new int[]{(int) Math.round(Math.min(p[0], q[0])), (int) Math.round(Math.min(p[1], q[1])), (int) Math.round(Math.min(p[2], q[2])),
                    (int) Math.round(Math.max(p[0], q[0])), (int) Math.round(Math.max(p[1], q[1])), (int) Math.round(Math.max(p[2], q[2]))};
        }
        String n = note == null ? "" : note.strip();
        if (n.length() > MAX_NOTE) n = n.substring(0, MAX_NOTE - 1) + "…";
        anchors.put(key, new Anchor(key, part, box, c.length == 3, n, auto));
        return key;
    }

    /** The anchor of that name, or the first whose name ends in "/name" or ".name"; null when there is none. */
    public Anchor anchor(String name) {
        Anchor a = anchors.get(name);
        if (a != null) return a;
        for (Anchor b : anchors.values()) if (b.key().endsWith("/" + name) || b.key().endsWith("." + name)) return b;
        return null;
    }

    public List<Anchor> anchors() {
        return List.copyOf(anchors.values());
    }

    public Map<String, List<String>> notes() {
        Map<String, List<String>> out = new LinkedHashMap<>();
        notes.forEach((k, v) -> out.put(k, List.copyOf(v)));
        return out;
    }

    /** Whether the part just started is confined to rooms and needs their boxes. */
    public boolean confiningRooms() {
        return scopePath != null && ROOMS.equals(scopeKind) && scopePath.equals(currentPart());
    }

    /** The room boxes {x1,z1,x2,z2,floorY} the current {@link #ROOMS} part may write in. */
    public void scopeRooms(List<int[]> rooms) {
        scopeRooms = List.copyOf(rooms);
    }

    /** What each confined part tried and what was dropped, by part path. */
    public Map<String, Confinement> confinements() {
        return Map.copyOf(confinements);
    }

    /** Whether a confined part's write would leave its scope (it is then dropped). */
    private boolean outOfScope(long k, int x, int y, int z, BlockSpec old, BlockSpec spec) {
        if (OTHERS.equals(scopeKind)) {
            String o = owner.get(k);
            if (o == null || o.equals(scopePath) || o.startsWith(scopePath + "/") || scopePath.startsWith(o + "/")) return false;
            return BlockRoles.breaks(old, spec, info);
        }
        boolean same = old == null || old.isAir() ? spec == null || spec.isAir() : spec != null && old.name().equals(spec.name());
        if (same) return false;
        // the ring around a room counts for furniture: a bed or shelf against the wall hides the floor it stands on
        int ring = !BlockRoles.structural(old, info) && !BlockRoles.structural(spec, info) ? 1 : 0;
        for (int[] r : scopeRooms) {
            if (x >= r[0] - ring && x <= r[2] + ring && z >= r[1] - ring && z <= r[3] + ring && y >= r[4] - 1 && y <= r[4] + 8) return false;
        }
        return true;
    }

    private static String nameOf(BlockSpec s) {
        return s == null || s.isAir() ? "air" : s.name();
    }

    /**
     * Starts a building (volume()) that must stand against the walls and towers already built rather than cut
     * into them: its writes over another part's masonry are left out, so a house in a bailey meets the curtain
     * wall at its face and the wall stays whole. Towers and gatehouses, which do cut through walls, are not fitted.
     */
    public void beginFit(String label) {
        if (fitting++ > 0) return;
        fitPart = currentPart();
        fitLabel = label;
        fitBy = null;
        fitBlocked = 0;
    }

    public void endFit() {
        if (fitting == 0 || --fitting > 0) return;
        if (fitBlocked >= 12) warn("a building (" + fitLabel + ") ran into " + fitBy + ": " + fitBlocked + " of its blocks were left out so "
                + fitBy + " stays whole; move the building clear of it or flush against its face (see partBox('" + fitBy + "'))");
    }

    /** Masonry a fitted building must not cut: walls, floors, stairs and doors, not the ground, plants or trees. */
    private boolean masonry(BlockSpec s) {
        if (!BlockRoles.structural(s, info) && !BlockRoles.access(s)) return false;
        String n = s.name();
        return !(n.endsWith("_leaves") || n.endsWith("_log") || n.endsWith("_wood") || TERRAIN.contains(n));
    }

    private static final Set<String> TERRAIN = Set.of("grass_block", "dirt", "coarse_dirt", "rooted_dirt", "podzol", "mycelium",
            "mud", "clay", "sand", "red_sand", "gravel", "stone", "deepslate", "andesite", "diorite", "granite", "tuff", "calcite",
            "dirt_path", "farmland", "moss_block", "snow_block", "packed_mud", "terracotta", "netherrack", "end_stone", "bedrock");

    /** How many times the current part has been entered, counting this call. */
    public int partCall() {
        return partCalls.getOrDefault(currentPart(), 0);
    }

    /**
     * Ends the run: trees a later part cut through (a wall or tower built across the canopy), or whose trunk or
     * branches touch a building, are taken out whole, so no half tree is left sticking out of the masonry; leaves
     * that merely brush a wall are pruned back from it.
     */
    public void settle() {
        fitCut.forEach((key, n) -> {
            if (n < 12) return;
            String[] p = key.split("\\|", 3);
            warn("a building (" + p[0] + ", part " + p[1] + ") was cut through by " + p[2] + " (" + n + " blocks), which was built"
                    + " after it; move the building clear of " + p[2] + " or flush against its face");
        });
        confinements.forEach((p, t) -> {
            if (t.dropped > 0) warn(t.dropped + " of " + t.writes + " writes by part " + p + " left its scope ("
                    + (OTHERS.equals(t.kind) ? "blocks of other parts" : "outside the rooms") + ") and were skipped, e.g. " + String.join(", ", t.samples));
        });
        if (guarded > 0) warn(guarded + " interior writes into the building's walls, floors or stairs were skipped, e.g. " + guardedAt
                + "; furnish inside rooms() only");
        for (Growth g : trees) {
            int cut = g.cutBy.values().stream().mapToInt(Integer::intValue).sum();
            String where = g.was.isEmpty() ? null : at(g);
            if (cut >= 6) {
                String by = g.cutBy.keySet().iterator().next();
                uproot(g);
                warn("a tree at " + where + " was cut through by " + by + " (" + cut + " blocks), so it was removed; plant trees clear of buildings");
                continue;
            }
            String[] by = new String[1];
            List<Long> touching = contact(g, by);
            long wood = touching.stream().filter(k -> !model.get(VoxelModel.kx(k), VoxelModel.ky(k), VoxelModel.kz(k)).name().endsWith("_leaves")).count();
            long leaves = g.was.keySet().stream().filter(k -> grown.get(k) == g
                    && model.get(VoxelModel.kx(k), VoxelModel.ky(k), VoxelModel.kz(k)).name().endsWith("_leaves")).count();
            if (wood >= 4 || touching.size() - wood > leaves * 2 / 5) {
                uproot(g);
                warn("a tree at " + where + " grew against " + by[0] + " (" + touching.size() + " blocks), so it was removed; keep trees 3+ blocks from walls");
            } else {
                for (long k : touching) if (model.get(VoxelModel.kx(k), VoxelModel.ky(k), VoxelModel.kz(k)).name().endsWith("_leaves")) restore(g, k);
            }
        }
    }

    private static final int[][] SIDES = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

    /** The tree's cells above its roots that touch a block of the build (not soil, plants or another tree). */
    private List<Long> contact(Growth g, String[] by) {
        int minY = Integer.MAX_VALUE;
        for (long k : g.was.keySet()) if (grown.get(k) == g) minY = Math.min(minY, VoxelModel.ky(k));
        List<Long> out = new ArrayList<>();
        for (long k : g.was.keySet()) {
            int x = VoxelModel.kx(k), y = VoxelModel.ky(k), z = VoxelModel.kz(k);
            if (grown.get(k) != g || y <= minY + 2) continue;
            for (int[] d : SIDES) {
                long nk = VoxelModel.key(x + d[0], y + d[1], z + d[2]);
                BlockSpec nb = model.get(x + d[0], y + d[1], z + d[2]);
                if (nb == null || grown.containsKey(nk) || yields(nb)) continue;
                if (out.isEmpty()) by[0] = owner.getOrDefault(nk, "the build");
                out.add(k);
                break;
            }
        }
        return out;
    }

    private static String at(Growth g) {
        long k = g.was.isEmpty() ? g.blockedAt : g.was.keySet().iterator().next();
        return "(" + VoxelModel.kx(k) + "," + VoxelModel.ky(k) + "," + VoxelModel.kz(k) + ")";
    }

    /** Puts back what a tree replaced in the cells it still holds. */
    private void uproot(Growth g) {
        for (long k : List.copyOf(g.was.keySet())) restore(g, k);
    }

    private void restore(Growth g, long k) {
        if (grown.get(k) != g) return;
        grown.remove(k);
        BlockSpec old = g.was.get(k);
        model.set(VoxelModel.kx(k), VoxelModel.ky(k), VoxelModel.kz(k), old == null ? BlockSpec.AIR : old);
        String o = g.wasOwner.get(k);
        if (o == null || old == null || old.isAir()) owner.remove(k);
        else owner.put(k, o);
    }

    /** What a tree may grow through: air, plants, other trees' leaves and the soil it stands in. */
    private boolean yields(BlockSpec s) {
        if (s == null || s.isAir()) return true;
        String n = s.name();
        return n.endsWith("_leaves") || n.equals("vine") || n.equals("grass_block") || n.equals("dirt") || n.equals("coarse_dirt")
                || n.equals("podzol") || n.equals("rooted_dirt") || n.equals("dirt_path") || n.equals("gravel") || n.equals("mycelium") || n.equals("moss_block") || n.equals("snow")
                || BlockRoles.decor(s, info) && !info.isOccluding(s) && info.lightEmission(s) == 0 && !n.endsWith("_carpet");
    }

    public String currentPart() {
        return partStack.isEmpty() ? NO_PART : partStack.peek();
    }

    // ------------------------------------------------------------------ writes

    /** Writes a block given in local (frame) coordinates, rotating its state with the frame when possible. */
    public void setLocal(double lx, double ly, double lz, BlockSpec spec) {
        double[] p = frame.point(lx, ly, lz);
        setWorld((int) Math.round(p[0]), (int) Math.round(p[1]), (int) Math.round(p[2]), orient(spec), false);
    }

    /** Applies the frame's block-state transform (mirror, then quarter turns) to a locally specified state. */
    public BlockSpec orient(BlockSpec spec) {
        if (spec == null || spec.isAir() || !frame.exactStates() || (frame.quarterTurns() == 0 && !frame.mirrorX())) return spec;
        BlockSpec s = spec;
        if (frame.mirrorX()) s = info.mirror(s, 'x');
        if (frame.quarterTurns() != 0) s = info.rotate(s, frame.quarterTurns());
        return s;
    }

    /** Writes a world cell; {@code smooth} marks smoother-made stairs/slabs that auto-orientation must keep. */
    public void setWorld(int x, int y, int z, BlockSpec spec, boolean smooth) {
        if (limits != null && (x < limits[0] || y < limits[1] || z < limits[2] || x > limits[3] || y > limits[4] || z > limits[5])) {
            outside++;
            return;
        }
        long k = VoxelModel.key(x, y, z);
        boolean isNew = !model.isSet(x, y, z);
        if (isNew && model.size() >= maxCells) {
            throw new ScriptBudgetException("the build exceeds " + maxCells + " cells; use fewer or smaller solid volumes (shell:1 for hollow masses)");
        }
        if (scopePath != null) {
            String cur = currentPart();
            if (cur.equals(scopePath) || cur.startsWith(scopePath + "/")) {
                Confinement t = confinements.get(scopePath);
                t.writes++;
                BlockSpec old = model.get(x, y, z);
                if (outOfScope(k, x, y, z, old, spec)) {
                    t.dropped++;
                    if (t.samples.size() < 6) t.samples.add("(" + x + "," + y + "," + z + ") " + nameOf(old)
                            + (owner.containsKey(k) ? " of " + owner.get(k) : "") + " -> " + nameOf(spec));
                    return;
                }
            }
        }
        if (fitting > 0) {
            BlockSpec old = model.get(x, y, z);
            String o = owner.get(k);
            // buildings may join each other (a wing under the house's eaves); walls and towers stay whole
            if (o != null && !o.equals(fitPart) && !o.startsWith(fitPart + "/") && !fitPart.startsWith(o + "/") && masonry(old)
                    && !old.equals(spec) && !fitted.containsKey(k)) {
                if (fitBlocked++ == 0) fitBy = top(o);
                return;
            }
            if (spec != null && !spec.isAir() && masonry(spec)) fitted.put(k, fitLabel + "|" + fitPart);
            else fitted.remove(k);
        } else if (!fitted.isEmpty()) {
            // a wall or tower built after a house, through it
            String f = fitted.get(k);
            if (f != null) {
                String house = f.substring(f.indexOf('|') + 1), culprit = currentPart();
                if (!culprit.equals(house) && !culprit.startsWith(house + "/") && !house.startsWith(culprit + "/")
                        && (spec == null || spec.isAir() || masonry(spec)) && !"finish".equals(culprit)) {
                    fitted.remove(k);
                    fitCut.merge(f + "|" + top(culprit), 1, Integer::sum);
                }
            }
        }
        if (!partStack.isEmpty() && interiorName(partStack.peekLast())) {
            BlockSpec old = model.get(x, y, z);
            String o = owner.getOrDefault(k, NO_PART);
            // stairs, ladders and doors stay as they are; a wall may become other masonry (a fireplace back) but
            // not a hole, furniture or decoration
            boolean breaks = BlockRoles.access(old) ? !BlockRoles.access(spec) : BlockRoles.structural(old, info) && !BlockRoles.structural(spec, info);
            if (breaks && !o.equals(partStack.peekLast()) && !o.startsWith(partStack.peekLast() + "/")) {
                if (guarded++ == 0) guardedAt = "(" + x + "," + y + "," + z + ") " + o + "'s " + old.name();
                return;
            }
        }
        if (growing != null) {
            BlockSpec old = model.get(x, y, z);
            if (!growing.was.containsKey(k) && !yields(old)) {
                if (growing.blocked++ == 0) {
                    growing.blockedBy = owner.getOrDefault(k, "the existing build");
                    growing.blockedAt = k;
                }
                return;
            }
            if (!growing.was.containsKey(k)) {
                growing.was.put(k, old);
                growing.wasOwner.put(k, owner.get(k));
            }
            grown.put(k, growing);
        } else {
            Growth tree = grown.get(k);
            if (tree != null) {
                grown.remove(k);
                if (!yields(spec) || spec.isAir()) tree.cutBy.merge(currentPart(), 1, Integer::sum);
            }
        }
        ledger(k, x, y, z, spec);
        model.set(x, y, z, spec);
        writes++;
        if (smooth) smoothed.add(k);
        else smoothed.remove(k);
        if (spec == null || spec.isAir()) {
            owner.remove(k);
            return;
        }
        String part = currentPart();
        owner.put(k, part);
        int[] b = partBounds.get(part);
        if (b == null) partBounds.put(part, new int[]{x, y, z, x, y, z});
        else {
            b[0] = Math.min(b[0], x);
            b[1] = Math.min(b[1], y);
            b[2] = Math.min(b[2], z);
            b[3] = Math.max(b[3], x);
            b[4] = Math.max(b[4], y);
            b[5] = Math.max(b[5], z);
        }
    }

    /**
     * Records damage before a write lands: loose decoration written into a wall cell (by any part), or another
     * part's stairs, ladder or door removed. Joins between parts (a tower merging into a wall, a path laid over
     * grass, a doorway carved through a wall) are not damage.
     */
    private void ledger(long k, int x, int y, int z, BlockSpec spec) {
        BlockSpec old = model.get(x, y, z);
        if (old == null || old.isAir()) return;
        String victim = owner.getOrDefault(k, NO_PART), culprit = currentPart();
        // the finisher's own repairs are checked by the pipeline, not counted as damage
        if (culprit.equals("finish")) return;
        String vTop = top(victim), cTop = top(culprit);
        if (!vTop.equals(cTop) && BlockRoles.structural(old, info)) {
            int[] o = overlap.computeIfAbsent(vTop + "|" + cTop, q -> new int[]{0, x, y, z, x, y, z});
            o[0]++;
            o[1] = Math.min(o[1], x); o[2] = Math.min(o[2], y); o[3] = Math.min(o[3], z);
            o[4] = Math.max(o[4], x); o[5] = Math.max(o[5], y); o[6] = Math.max(o[6], z);
        }
        String kind = null;
        if (BlockRoles.decor(spec, info) && BlockRoles.structural(old, info) && !old.name().endsWith("_door")) kind = "embedded";
        else if (BlockRoles.access(old) && !victim.equals(culprit) && !culprit.startsWith(victim + "/") && !victim.startsWith(culprit + "/")
                && !BlockRoles.access(spec) && !BlockRoles.structural(spec, info)) kind = "cleared";
        if (kind == null || damageCells >= 20_000) return;
        Map<Long, BlockSpec> cells = damage.computeIfAbsent(victim + "|" + culprit + "|" + kind, q -> new LinkedHashMap<>());
        if (cells.putIfAbsent(k, old) == null) damageCells++;
    }

    private static String top(String part) {
        int i = part.indexOf('/');
        return i < 0 ? part : part.substring(0, i);
    }

    /** Wall cells one top-level part overwrote in another: "victim|culprit" -> {cells, x1, y1, z1, x2, y2, z2}. */
    public Map<String, int[]> overlaps() {
        return overlap;
    }

    /**
     * Damage the script did to its own blocks and that is still there at the end (a cell rebuilt later is not
     * damaged), largest first.
     */
    public List<Collision> collisions() {
        List<Collision> out = new ArrayList<>();
        damage.forEach((key, cells) -> {
            String[] p = key.split("\\|", 3);
            boolean embedded = p[2].equals("embedded");
            Map<Long, BlockSpec> still = new LinkedHashMap<>();
            String now = null;
            for (Map.Entry<Long, BlockSpec> e : cells.entrySet()) {
                long k = e.getKey();
                BlockSpec cur = model.get(VoxelModel.kx(k), VoxelModel.ky(k), VoxelModel.kz(k));
                boolean damaged = embedded ? BlockRoles.decor(cur, info)
                        : !BlockRoles.access(cur) && !BlockRoles.structural(cur, info);
                if (!damaged) continue;
                still.put(k, e.getValue());
                if (now == null) now = cur == null ? "air" : cur.name();
            }
            if (still.isEmpty()) return;
            long first = still.keySet().iterator().next();
            out.add(new Collision(p[0], p[1], p[2], still.size(), new int[]{VoxelModel.kx(first), VoxelModel.ky(first), VoxelModel.kz(first)},
                    still.get(first).name(), now, Map.copyOf(still)));
        });
        out.sort((a, b) -> Integer.compare(b.cells(), a.cells()));
        return out;
    }

    public BlockSpec getLocal(double lx, double ly, double lz) {
        double[] p = frame.point(lx, ly, lz);
        return model.get((int) Math.round(p[0]), (int) Math.round(p[1]), (int) Math.round(p[2]));
    }

    public BlockSpec getWorld(int x, int y, int z) {
        return model.get(x, y, z);
    }

    public void warn(String message) {
        if (warnings.size() < 40 && !warnings.contains(message)) warnings.add(message);
    }

    // ------------------------------------------------------------------ results

    public VoxelModel model() {
        return model;
    }

    public long outside() {
        return outside;
    }

    public long writes() {
        return writes;
    }

    public List<String> warnings() {
        return List.copyOf(warnings);
    }

    /** Cells placed by the smoother; {@code OrientationFixer} must not re-orient them. */
    public Set<Long> smoothed() {
        return Set.copyOf(smoothed);
    }

    /** World bounds of a part and the parts nested in it, as placed so far, or null when it placed nothing. */
    public int[] partBox(String name) {
        int[] out = null;
        for (Map.Entry<String, int[]> e : partBounds.entrySet()) {
            String p = e.getKey();
            if (!p.equals(name) && !p.startsWith(name + "/")) continue;
            int[] b = e.getValue();
            if (out == null) out = b.clone();
            else for (int i = 0; i < 3; i++) {
                out[i] = Math.min(out[i], b[i]);
                out[i + 3] = Math.max(out[i + 3], b[i + 3]);
            }
        }
        return out;
    }

    /** Owning part of every non-air cell written by the script. */
    public Map<Long, String> owners() {
        return Map.copyOf(owner);
    }

    /** Per-part cell counts and bounds, in first-declared order, for the parts that still own cells. */
    public List<ScriptResult.Part> parts() {
        Map<String, Integer> counts = new HashMap<>();
        for (String p : owner.values()) counts.merge(p, 1, Integer::sum);
        List<ScriptResult.Part> out = new ArrayList<>();
        for (Map.Entry<String, int[]> e : partBounds.entrySet()) {
            Integer n = counts.get(e.getKey());
            if (n == null || e.getValue() == null) continue;
            out.add(new ScriptResult.Part(e.getKey(), n, e.getValue().clone()));
        }
        return out;
    }
}
