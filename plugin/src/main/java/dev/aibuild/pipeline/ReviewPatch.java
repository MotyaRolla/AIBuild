package dev.aibuild.pipeline;

import dev.aibuild.model.BlockSpec;
import dev.aibuild.model.VoxelModel;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

final class ReviewPatch {
    record Result(int changed, int conflicts) { }

    static Result merge(VoxelModel snapshot, VoxelModel proposed, VoxelModel current) {
        Set<Long> keys = new HashSet<>(snapshot.entries().keySet());
        keys.addAll(proposed.entries().keySet());
        int changed = 0, conflicts = 0;
        for (long key : keys) {
            BlockSpec base = snapshot.entries().get(key), next = proposed.entries().get(key);
            if (Objects.equals(base, next)) continue;
            BlockSpec now = current.entries().get(key);
            if (Objects.equals(now, next)) continue;
            if (!Objects.equals(now, base)) {
                conflicts++;
                continue;
            }
            current.set(VoxelModel.kx(key), VoxelModel.ky(key), VoxelModel.kz(key), next);
            changed++;
        }
        return new Result(changed, conflicts);
    }
}
