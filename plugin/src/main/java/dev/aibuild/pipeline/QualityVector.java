package dev.aibuild.pipeline;

import dev.aibuild.analysis.AnalysisReport;
import dev.aibuild.analysis.Severity;
import dev.aibuild.script.ScriptResult;

import java.util.ArrayList;
import java.util.List;

/**
 * What a revision may not make worse, measured in cells rather than issue lines: one "plus 56 more floating
 * components" line hides 99 blocks, and losing five rooms adds no line at all. A revision (sculptor, interior
 * designer, critic) is kept only when no component regresses beyond its tolerance.
 */
public record QualityVector(int critical, int floating, int unsupported, int dark, int blockedDoors, int rooms,
                            int furnishedRooms, boolean verticalAccess, int damage, int blocks) {

    public static QualityVector of(AnalysisReport rep, ScriptResult run) {
        return new QualityVector(rep.count(Severity.CRITICAL), num(rep, "floatingBlocks"), num(rep, "unsupportedAttachments"),
                rep.darkSpots, num(rep, "blockedDoors"), rep.roomList.size(), num(rep, "furnishedRooms"),
                Boolean.TRUE.equals(rep.metrics.get("verticalAccess")), run == null ? 0 : run.collisionCells(), rep.nonAir);
    }

    private static int num(AnalysisReport rep, String key) {
        return rep.metrics.get(key) instanceof Number n ? n.intValue() : 0;
    }

    /** Why this is worse than {@code before}, one reason per component, or an empty list when it is not. */
    public List<String> regressions(QualityVector before) {
        List<String> out = new ArrayList<>();
        if (critical > before.critical) out.add("critical findings " + before.critical + " -> " + critical);
        if (floating > before.floating + 4) out.add("floating blocks " + before.floating + " -> " + floating);
        if (unsupported > before.unsupported + 2) out.add("unsupported attachments " + before.unsupported + " -> " + unsupported);
        if (dark > before.dark + Math.max(4, before.dark / 10)) out.add("dark interior cells " + before.dark + " -> " + dark);
        if (blockedDoors > before.blockedDoors) out.add("blocked doors " + before.blockedDoors + " -> " + blockedDoors);
        if (rooms < before.rooms) out.add("rooms " + before.rooms + " -> " + rooms);
        if (furnishedRooms < before.furnishedRooms) out.add("furnished rooms " + before.furnishedRooms + " -> " + furnishedRooms);
        if (before.verticalAccess && !verticalAccess) out.add("the upper floors can no longer be reached");
        if (damage > before.damage) out.add("blocks one part destroyed of another " + before.damage + " -> " + damage);
        if (blocks < before.blocks / 2) out.add("more than half of the build removed");
        return out;
    }

    /** Whether some defect went down (the judge may then call it even). */
    public boolean fixesDefects(QualityVector before) {
        return critical < before.critical || floating < before.floating || unsupported < before.unsupported || dark < before.dark
                || blockedDoors < before.blockedDoors || damage < before.damage || furnishedRooms > before.furnishedRooms;
    }

    /** The components a person reads, by name, in display order (the live page's stage table). */
    public java.util.Map<String, Integer> metrics() {
        java.util.Map<String, Integer> m = new java.util.LinkedHashMap<>();
        m.put("critical", critical);
        m.put("floating", floating);
        m.put("unsupported", unsupported);
        m.put("dark", dark);
        m.put("blocked doors", blockedDoors);
        m.put("rooms", rooms);
        m.put("furnished", furnishedRooms);
        m.put("destroyed", damage);
        m.put("blocks", blocks);
        return m;
    }

    public String describe() {
        return "critical " + critical + ", floating " + floating + ", unsupported " + unsupported + ", dark " + dark
                + ", blocked doors " + blockedDoors + ", rooms " + rooms + " (furnished " + furnishedRooms + ")"
                + ", stairs reach the floors " + verticalAccess + ", destroyed " + damage + ", blocks " + blocks;
    }
}
