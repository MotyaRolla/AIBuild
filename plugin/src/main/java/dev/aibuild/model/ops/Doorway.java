package dev.aibuild.model.ops;

import dev.aibuild.model.BlockInfo;
import dev.aibuild.model.BlockSpec;
import dev.aibuild.model.VoxelModel;
import dev.aibuild.model.Dir;

import java.util.Objects;

public record Doorway(int x, int y, int z, BlockSpec door) {
    /** Resolve an explicitly replaced/recessed entrance once, after the full construction program. */
    public Doorway followCompiledEntrance(VoxelModel model,BlockInfo info) {
        for (int recess=0;recess<=3;recess++) {
            BlockSpec candidate=model.get(x,y,z-recess);
            if (candidate!=null && candidate.name().endsWith("_door") && !candidate.name().endsWith("_trapdoor")
                    && !"upper".equals(info.getProp(candidate,"half"))) return new Doorway(x,y,z-recess,candidate);
        }
        return this;
    }

    public int restore(VoxelModel model, BlockInfo info) {
        int changed = 0;
        Dir facing=Dir.parse(info.getProp(door,"facing"));
        if (facing==null || !facing.isHorizontal()) facing=Dir.NORTH;
        for (int dy=0;dy<2;dy++) {
            BlockSpec target = info.with(door,"half",dy==0 ? "lower" : "upper");
            if (!Objects.equals(model.get(x,y+dy,z),target)) {
                model.set(x,y+dy,z,target);
                changed++;
            }
            for (int side : new int[]{-1,1}) {
                int px=x+facing.dx*side, pz=z+facing.dz*side;
                BlockSpec current = model.get(px,y+dy,pz);
                if (current != null && info.isSolid(current)) {
                    model.set(px,y+dy,pz,null);
                    changed++;
                }
            }
        }
        return changed;
    }
}
