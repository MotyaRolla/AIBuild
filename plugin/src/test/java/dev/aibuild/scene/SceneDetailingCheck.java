package dev.aibuild.scene;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.aibuild.model.BlockSpec;
import dev.aibuild.model.VoxelModel;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the post-transfer detailing pass.
 *
 * <p>The pass exists because a 3D scene cannot express sub-voxel decoration: a lantern on a chain, a trapdoor
 * used as a shutter, a flower pot on a sill. Those blocks are chosen after the build is in the world, on real
 * coordinates, so the validation here is what stops the model from dropping a torch inside a wall or a lantern
 * into thin air. Every rejection reason is tested.</p>
 */
class SceneDetailingCheck {

    /** A wall at x=0..2, y=0..3, z=0 with a floor at y=0. */
    private static VoxelModel wall() {
        VoxelModel m = new VoxelModel();
        for (int x = 0; x <= 2; x++) {
            for (int y = 0; y <= 3; y++) m.set(x, y, 0, BlockSpec.of("stone_bricks"));
            m.set(x, 0, 1, BlockSpec.of("stone_bricks"));
        }
        return m;
    }

    private static JsonObject json(String blocks) {
        return JsonParser.parseString("{\"blocks\":" + blocks + "}").getAsJsonObject();
    }

    @Test
    void parsesDecorationsWithCoordinatesAndBlock() {
        List<String> errors = new ArrayList<>();
        List<SceneDetailing.Decor> decor = SceneDetailing.parse(json("""
                [{"x":1,"y":2,"z":1,"block":"lantern[hanging=true]","why":"under the canopy"},
                 {"x":2,"y":1,"z":1,"block":"minecraft:flower_pot","why":"sill"}]
                """), errors);
        assertEquals(2, decor.size());
        assertEquals(0, errors.size());
        assertEquals("minecraft:lantern[hanging=true]", decor.get(0).block(), "namespace is added");
        assertEquals("under the canopy", decor.get(0).why());
        assertEquals("minecraft:flower_pot", decor.get(1).block(), "existing namespace is kept");
    }

    @Test
    void rejectsEntriesWithoutCoordinatesOrBlock() {
        List<String> errors = new ArrayList<>();
        List<SceneDetailing.Decor> decor = SceneDetailing.parse(json("""
                [{"x":1,"y":2,"block":"lantern"},
                 {"x":1,"y":2,"z":1},
                 {"x":1,"y":2,"z":1,"block":"lantern"}]
                """), errors);
        assertEquals(1, decor.size());
        assertEquals(2, errors.size());
        assertTrue(errors.get(0).contains("x, y, z"));
        assertTrue(errors.get(1).contains("block id"));
    }

    @Test
    void survivesAnAnswerWithoutBlocks() {
        List<String> errors = new ArrayList<>();
        assertEquals(0, SceneDetailing.parse(new JsonObject(), errors).size());
        assertTrue(errors.get(0).contains("no blocks array"));
        List<String> errors2 = new ArrayList<>();
        assertEquals(0, SceneDetailing.parse(null, errors2).size());
        assertEquals(1, errors2.size());
    }

    @Test
    void acceptsDecorationsThatAttachToTheStructure() {
        List<String> errors = new ArrayList<>();
        List<SceneDetailing.Decor> proposed = List.of(
                new SceneDetailing.Decor(0, 2, 1, "minecraft:lantern[hanging=true]", "eave"),   // beside the wall
                new SceneDetailing.Decor(1, 1, 1, "minecraft:flower_pot", "floor"));           // on the floor
        List<SceneDetailing.Decor> kept = SceneDetailing.filter(proposed, wall(), id -> true, errors);
        assertEquals(2, kept.size(), "both have a neighbour: " + errors);
        assertEquals(0, errors.size());
    }

    @Test
    void rejectsDecorationInsideTheStructure() {
        List<String> errors = new ArrayList<>();
        List<SceneDetailing.Decor> proposed = List.of(
                new SceneDetailing.Decor(1, 2, 0, "minecraft:lantern", "inside a wall"));
        List<SceneDetailing.Decor> kept = SceneDetailing.filter(proposed, wall(), id -> true, errors);
        assertEquals(0, kept.size());
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("occupied"), errors.get(0));
    }

    @Test
    void rejectsDecorationInMidAir() {
        List<String> errors = new ArrayList<>();
        List<SceneDetailing.Decor> proposed = List.of(
                new SceneDetailing.Decor(8, 8, 8, "minecraft:lantern", "floating"));
        List<SceneDetailing.Decor> kept = SceneDetailing.filter(proposed, wall(), id -> true, errors);
        assertEquals(0, kept.size());
        assertTrue(errors.get(0).contains("no support"), errors.get(0));
    }

    @Test
    void rejectsUnknownBlockIds() {
        List<String> errors = new ArrayList<>();
        List<SceneDetailing.Decor> proposed = List.of(
                new SceneDetailing.Decor(0, 2, 1, "minecraft:not_a_block", "typo"));
        List<SceneDetailing.Decor> kept = SceneDetailing.filter(proposed, wall(), id -> false, errors);
        assertEquals(0, kept.size());
        assertTrue(errors.get(0).contains("unknown block"), errors.get(0));
    }

    @Test
    void rejectsFullStructuralBlocksAsDecorations() {
        List<String> errors = new ArrayList<>();
        List<SceneDetailing.Decor> proposed = List.of(
                new SceneDetailing.Decor(0, 2, 1, "minecraft:stone_bricks", "a wall"));
        List<SceneDetailing.Decor> kept = SceneDetailing.filter(proposed, wall(), id -> true, errors);
        assertEquals(0, kept.size());
        assertTrue(errors.get(0).contains("not a decoration"), errors.get(0));
    }

    @Test
    void recognisesTheDecorFamiliesThePromptAsksFor() {
        for (String id : List.of("lantern", "soul_lantern", "chain", "oak_trapdoor", "flower_pot",
                "oak_sign", "white_carpet", "stone_button", "lever", "cobweb", "barrel", "cauldron",
                "bookshelf", "ladder", "hay_block", "campfire", "item_frame", "painting")) {
            assertTrue(SceneDetailing.isDecor(id), id + " must count as decoration");
        }
        assertFalse(SceneDetailing.isDecor("stone_bricks"));
        assertFalse(SceneDetailing.isDecor("oak_planks"));
    }

    @Test
    void bareNameStripsNamespaceAndState() {
        assertEquals("lantern", SceneDetailing.bareName("minecraft:lantern[hanging=true]"));
        assertEquals("oak_trapdoor", SceneDetailing.bareName("oak_trapdoor[facing=north]"));
        assertEquals("lantern", SceneDetailing.bareName("lantern"));
    }

    @Test
    void mergesDecorationsIntoACopyOfTheStructure() {
        VoxelModel original = wall();
        int before = original.size();
        List<SceneDetailing.Decor> decor = List.of(
                new SceneDetailing.Decor(0, 2, 1, "minecraft:lantern", "eave"));
        VoxelModel merged = SceneDetailing.merge(original, decor);
        assertEquals(before + 1, merged.size());
        assertEquals(before, original.size(), "the source model must not be modified");
        assertEquals("lantern", merged.get(0, 2, 1).name());
    }

    @Test
    void summaryAndDescriptionAreReadable() {
        List<SceneDetailing.Decor> decor = List.of(
                new SceneDetailing.Decor(0, 1, 1, "minecraft:lantern", "a"),
                new SceneDetailing.Decor(1, 1, 1, "minecraft:lantern", "b"),
                new SceneDetailing.Decor(2, 1, 1, "minecraft:flower_pot", "c"));
        assertEquals(2, SceneDetailing.summary(decor).get("lantern"));
        String text = SceneDetailing.describe(decor);
        assertTrue(text.contains("3"), text);
        assertTrue(text.contains("lantern"), text);
        assertEquals("мелких деталей не добавлено", SceneDetailing.describe(List.of()));
    }

    @Test
    void promotesADecorationToAOneCellModel() {
        VoxelModel model = SceneDetailing.toModel(new SceneDetailing.Decor(4, 5, 6, "minecraft:lantern", "x"));
        assertEquals(1, model.size());
        assertEquals("lantern", model.get(4, 5, 6).name());
    }
}
