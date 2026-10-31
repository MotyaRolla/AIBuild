package dev.aibuild.scene;

import java.util.List;

/**
 * The planning and detailing prompts of the 3D pipeline.
 *
 * <p>Two things separate a mediocre 3D build from a good one, and neither is the renderer:</p>
 * <ol>
 *   <li><b>A brief.</b> The first version went from one sentence straight to objects, so the model never
 *       decided dimensions, floors, palette or details up front. {@link #plan} borrows the depth of the block
 *       pipeline's plan: exact size, floors with rooms, palette roles, an exterior description, at least 25
 *       concrete details with locations, and an ordered stage schedule.</li>
 *   <li><b>Staged building.</b> Instead of one answer covering the whole building, {@link #stage} gives the
 *       model one call per stage (foundation, walls, roof, interiors, yard) so each part gets full attention.</li>
 * </ol>
 *
 * <p>{@link #detailing} has no analogue in the block pipeline. A scene primitive can express a wall or a roof
 * but not a lantern on a chain, a trapdoor used as a shutter, a flower pot on a sill, a button beside a door, a
 * carpet, a sign or a cauldron. Those are the blocks that make a build feel finished in game, and they can only
 * be chosen once the structure exists in Minecraft — so this pass runs after placement and works on real world
 * coordinates.</p>
 */
public final class ScenePlan {

    private ScenePlan() {
    }

    /**
     * Prompt 1: the design brief for a new 3D build.
     *
     * @param prompt   the user request (any language)
     * @param sizeHint an extra size instruction, or {@code null}
     * @return the user message
     */
    public static String plan(String prompt, String sizeHint) {
        return """
                TASK: PLAN (design brief for a new 3D build)
                User request (any language; keep names in the user's language): "%s"
                %s
                Think like an architect: decide exact dimensions, floors, rooms, palette and an ordered build
                schedule. Respect explicit sizes EXACTLY: "10x10"/"10 на 10" means the main body (outer walls) is
                exactly 10 x 10 blocks: x 0..9, z 0..9. If no size is given choose a fitting one (max 48).
                Return JSON:
                {
                 "title": "short name in the user's language",
                 "summary": "2-3 sentences describing the design",
                 "style": "architectural style",
                 "type": "house|tower|castle|shop|bridge|statue|tree|ship|other",
                 "body": {"x": 10, "z": 10, "floors": 2, "wall_top_y": 9},
                 "plot": {"min": [x,y,z], "max": [x,y,z]},
                 "expect_interior": true,
                 "palette": {"foundation": "...", "frame": "...", "infill": "...", "roof": "...",
                             "floor": "...", "trim": "...", "accent": "..."},
                 "layout": [{"level": "ground floor", "y": 1, "height": 4,
                             "rooms": [{"name": "...", "area": "x1-x2, z1-z2", "furnishing": "..."}]}],
                 "exterior": "what goes around the body",
                 "details": ["at least 25 concrete details with locations"],
                 "stages": [{"name": "...", "goal": "precise description incl. coordinates and materials"}]
                }
                plot = the full bounding box including roof overhang, chimney and yard decorations.
                Plan 5-7 stages bottom-up, for example:
                  1) foundation, plinth and floors;
                  2) walls, timber frame, windows, doors and facade details;
                  3) roof, eaves, gables, ridge cap, chimney, dormers;
                  4) ground floor interior;
                  5) upper floor / attic interior;
                  6) exterior: path, garden, fence, props.
                Every stage goal must name coordinates and materials, not just an intention. Keep the brief
                compact but concrete, with no prose padding.
                """.formatted(escape(prompt), sizeHint == null || sizeHint.isBlank() ? "" : sizeHint + "\n");
    }

    /**
     * Prompt 2: build one stage of the brief as scene objects.
     *
     * @param prompt    the original request
     * @param planJson  the brief as JSON text
     * @param index     1-based stage number
     * @param total     number of stages
     * @param stageName the stage title
     * @param stageGoal the stage description (coordinates, materials)
     * @param existing  a compact digest of the objects built so far
     * @param viewNames the pictures attached (renders of the current model)
     * @return the user message
     */
    public static String stage(String prompt, String planJson, int index, int total, String stageName,
                               String stageGoal, String existing, List<String> viewNames) {
        return """
                TASK: BUILD STAGE %d/%d — "%s"
                Original request: "%s"
                Stage goal: %s

                Pictures attached are renders of the CURRENT model, in this order: %s.
                THE DESIGN BRIEF:
                %s

                ALREADY BUILT (do not duplicate it; match its coordinates and materials):
                %s

                Build THIS stage with maximum detail and return the objects of this stage only, as JSON:
                {"notes":"what you built (short, in the user's language)","objects":[OBJ,...]}
                Rules: use the brief's coordinates and palette; give every object a unique id; never place beams,
                columns or framing across an opening you did not create; every object must rest on or attach to
                something that already exists.
                ROOF RULE: a roof is ONE prism object covering the whole footprint plus overhang, NOT a series of
                beams or boxes. Beams laid along the ridge are decoration on top of a closed roof; they must never
                be the roof itself. Never leave a column of the footprint without roof material above it.
                """.formatted(index, total, escape(stageName), escape(prompt),
                stageGoal == null || stageGoal.isBlank() ? stageName : stageGoal,
                String.join(", ", viewNames), planJson,
                existing == null || existing.isBlank() ? "(nothing yet)" : existing);
    }

    /**
     * Prompt 4 — the pass with no analogue in the block pipeline: add the small blocks that a 3D scene cannot
     * express, after the structure is already in the world.
     *
     * @param prompt    the original request
     * @param planJson  the brief
     * @param placed    a description of the placed structure (size, palette, room layout, landmark coordinates)
     * @param analysis  the block-level analyzer report for the placed build
     * @param viewNames the renders attached
     * @return the user message
     */
    public static String detailing(String prompt, String planJson, String placed, String analysis,
                                   List<String> viewNames) {
        return """
                TASK: DETAILING PASS ON THE PLACED BUILD
                The 3D scene has already been transferred into Minecraft and stands in the world.
                Original request: "%s"
                Pictures attached (%s) show the placed build.

                THE DESIGN BRIEF:
                %s

                WHAT WAS PLACED (dimensions, palette, layout, landmark coordinates):
                %s

                BLOCK-LEVEL ANALYSIS OF THE PLACED BUILD:
                %s

                Now do the last part of the work that a 3D scene cannot express: the SMALL blocks that make a
                finished Minecraft build. Only blocks smaller than a full block, or purely decorative, count.
                Work through this list and add what fits this building:
                - lanterns: hanging on chains under the canopy and the eaves, standing on posts by the path;
                - trapdoors: as window shutters, hatch covers, attic doors, barrel lids;
                - buttons and levers: beside doors, on gate posts;
                - item frames and paintings: interior walls;
                - flower pots and potted plants: sills, tables, corners;
                - signs: inn sign above the door, name plate;
                - carpets and pressure plates: floors, thresholds;
                - chains, cobwebs in the attic and cellar;
                - barrels, cauldrons, anvils, grindstones, composters, bookshelves, crafting tables, beds, chests;
                - ladders: cellar and roof access;
                - torches, soul lanterns, candles;
                - fences and gates around the garden, path lanterns, potted flowers along the plinth;
                - hay bales, firewood stacks, crates in the yard.
                Place at least 12 and at most 60 of them. Every one must have support (attached to a wall or a
                floor, or hanging from the block above), must not block a doorway, and must not sit inside a wall.
                Use ONLY valid Minecraft 1.21+ block ids with valid block states.
                Return JSON:
                {"notes":"what you added (short, in the user's language)",
                 "blocks":[{"x":int,"y":int,"z":int,"block":"minecraft:id[state]","why":"what it is"}],
                 "skipped":[{"what":"...","why":"..."}]}
                Coordinates are ABSOLUTE world coordinates: read them from the description above.
                """.formatted(escape(prompt), String.join(", ", viewNames), planJson, placed, analysis);
    }

    /**
     * Escapes a user string for safe inclusion inside a quoted prompt section.
     *
     * @param s the text, may be {@code null}
     * @return the escaped text, never {@code null}
     */
    static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "'").replace("\n", " ");
    }
}
