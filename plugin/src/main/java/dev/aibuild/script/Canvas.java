package dev.aibuild.script;

import dev.aibuild.model.BlockFamilies;
import dev.aibuild.model.BlockInfo;
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
    }

    public void endPart() {
        if (!partStack.isEmpty()) partStack.pop();
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
