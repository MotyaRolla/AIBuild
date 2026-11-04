package dev.aibuild.render;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds a tiny, fully synthetic {@link BlockTextures} for tests that need a client atlas without a Minecraft
 * jar.
 *
 * <p>It lives in {@code dev.aibuild.render} because {@link BlockTextures}'s only constructor is package private,
 * and it exists at all because the atlas section of a model pack cannot be tested without real tiles: the
 * encoder asks the installed atlas for both the PNG bytes and each block's {top, side, bottom} tile indices.</p>
 */
public final class SyntheticAtlas {

    private SyntheticAtlas() {
    }

    /** ARGB of the solid tile at index {@code n}; distinct per tile so a misplaced tile is visible in the PNG. */
    public static int tileColour(int n) {
        return 0xFF000000 | ((n * 37) & 255) << 16 | ((n * 71) & 255) << 8 | ((n * 113) & 255);
    }

    /**
     * An atlas of {@code tiles} solid tiles with {@code oak_planks} on tile 0 and {@code stone} on tile 1.
     *
     * <p>Every tile is a flat colour: the point of these tests is the layout of the grid, so a tile that lands in
     * the wrong cell of the PNG is detectable by one pixel read instead of a texture comparison.</p>
     */
    public static BlockTextures of(int tiles) {
        if (tiles < 2) throw new IllegalArgumentException("need at least two tiles, got " + tiles);
        List<int[]> list = new ArrayList<>(tiles);
        for (int i = 0; i < tiles; i++) {
            int[] px = new int[BlockTextures.S * BlockTextures.S];
            Arrays.fill(px, tileColour(i));
            list.add(px);
        }
        Map<String, int[]> faces = new LinkedHashMap<>();
        faces.put("oak_planks", new int[]{0, 0, 0});
        faces.put("stone", new int[]{1, 1, 1});
        return new BlockTextures(list, faces);
    }
}
