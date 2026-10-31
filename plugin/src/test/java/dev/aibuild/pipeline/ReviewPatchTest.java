package dev.aibuild.pipeline;

import com.google.gson.JsonParser;
import dev.aibuild.model.BlockSpec;
import dev.aibuild.model.VoxelModel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ReviewPatchTest {
    @Test void disjointPatchesMergeAndStaleWritesCannotUndoARepair() {
        VoxelModel snapshot = new VoxelModel();
        snapshot.set(0,1,0,BlockSpec.of("oak_planks"));
        snapshot.set(1,1,0,BlockSpec.of("cobblestone"));
        VoxelModel current = snapshot.copy();
        VoxelModel a = snapshot.copy();
        a.set(0,1,0,null);
        assertEquals(new ReviewPatch.Result(1,0), ReviewPatch.merge(snapshot,a,current));
        VoxelModel b = snapshot.copy();
        b.set(0,1,0,BlockSpec.of("stone"));
        b.set(1,1,0,BlockSpec.of("glass"));
        assertEquals(new ReviewPatch.Result(1,1), ReviewPatch.merge(snapshot,b,current));
        assertNull(current.get(0,1,0));
        assertEquals("glass", current.get(1,1,0).name());
        assertEquals(new ReviewPatch.Result(0,0), ReviewPatch.merge(snapshot,a,current));
    }

    @Test void sharedChecklistDetailsHaveOneOwner() {
        var cl = Checklist.forCreate(JsonParser.parseString("{\"details\":[\"roof skylight\"]}").getAsJsonObject());
        assertTrue(cl.render("structure").contains("C11"));
        assertFalse(cl.render("exterior").contains("C11"));
        var ok = JsonParser.parseString("[{\"id\":\"C11\",\"ok\":true}]").getAsJsonArray();
        cl.apply(ok,1,"exterior");
        assertEquals(0,cl.verified());
        cl.apply(ok,1,"structure");
        assertEquals(1,cl.verified());
        cl.apply(JsonParser.parseString("[{\"id\":\"C11\",\"ok\":false}]").getAsJsonArray(),2,"interior");
        assertEquals(1,cl.verified());
    }
}
