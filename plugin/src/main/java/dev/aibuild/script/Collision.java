package dev.aibuild.script;

/**
 * Damage one part did to blocks another part (or itself) built: {@code kind} is "embedded" (a light, banner,
 * plant or other loose decoration written into a wall cell, leaving a hole) or "cleared" (stairs, ladders or doors
 * of another part removed or replaced by something that is not a way through).
 *
 * @param victim  the part whose block was overwritten
 * @param culprit the part that wrote over it
 * @param sample  one world cell {x, y, z} with the old and new block, for the report
 * @param before  every damaged cell key with the block that was there before, so a finisher can restore it
 */
public record Collision(String victim, String culprit, String kind, int cells, int[] sample, String oldBlock, String newBlock,
                        java.util.Map<Long, dev.aibuild.model.BlockSpec> before) {
    public String describe() {
        return culprit + " " + (kind.equals("embedded") ? "put " + newBlock + " into " + victim + "'s " + oldBlock
                : "removed " + victim + "'s " + oldBlock + (newBlock.equals("air") ? "" : " (now " + newBlock + ")"))
                + " in " + cells + " cell" + (cells == 1 ? "" : "s") + ", e.g. (" + sample[0] + "," + sample[1] + "," + sample[2] + ")";
    }
}
