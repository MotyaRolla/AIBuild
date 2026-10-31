package dev.aibuild.scene;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.Locale;

/**
 * Prompts of the 3D pipeline: design a scene, then critique it from rendered pictures.
 *
 * <p>Two things make these prompts different from the old block-DSL prompts. First, the model writes a few
 * dozen declarative objects instead of thousands of block operations — that is where the token saving comes
 * from. Second, the critique prompt always carries the rendered PNGs of the <em>current</em> design, so the
 * model judges what the building looks like rather than what its coordinates say.</p>
 *
 * <p>Prompts are plain {@link String}s (built once per call) and must stay deterministic: the API caches on
 * the exact prefix, so the system prompt is a constant and the iteration-specific text comes after it.</p>
 */
public final class ScenePrompts {

    /** Largest scene JSON inlined into one critique prompt; bigger scenes are sent as a digest. */
    public static final int MAX_SCENE_JSON_CHARS = 24_000;

    /** How many objects the digest lists when the full JSON does not fit. */
    public static final int MAX_DIGEST_OBJECTS = 120;

    private ScenePrompts() {
    }

    /**
     * The system prompt: coordinates, the object catalogue and the quality bar.
     *
     * <p>Kept as one constant so it can be prompt-cached between iterations.</p>
     *
     * @return the system prompt, never {@code null}
     */
    public static String system() {
        return """
                You are AIBuild, a master architect who designs Minecraft-scale buildings as a declarative
                3D scene. You never write blocks directly: you write a JSON scene of architectural primitives,
                a geometry engine turns it into voxels, and a renderer draws it. You will then SEE pictures of
                your own design and improve it over several iterations.

                # COORDINATES
                Right-handed: +x = east, +z = south, +y = up. One unit = one Minecraft block.
                Ground is y = 0. The main entrance faces SOUTH (+z). Keep the building body at x 0..W-1, z 0..D-1.

                # SCENE FORMAT
                {"name": string, "seed": number, "palette": {"key": "#rrggbb", ...}, "notes": string,
                 "objects": [OBJ, ...]}
                Every OBJ has "type" and usually "material" (a palette key). Types:
                - box: solid or hollow body. from/to (inclusive corners), optional hollow, open:["up","down",...],
                  openings:[{wall,at:[u,y],w,h,kind:"door|window|stairs"}]
                - prism: a roof. from/to of the wall tops, style gable|hip|shed|pyramid, ridge "x"|"z",
                  overhang int, pitch number
                - cylinder / cone / sphere: towers and domes. center, radius, height, hollow
                - ring: horizontal band (cornice, eaves, plinth). from/to, y, profile flat|cornice|eave|base
                - wall: straight wall between two points, thickness, height, openings
                - floor: from/to, thickness, pattern solid|checker|border|planks
                - stairs: from/to, axis "x"|"z", direction forward|back, railing
                - window: wall north|south|east|west, at:[u,y], w, h, frame, glass, sill, shutters, arch
                - door: wall, at:[u,y], w, h, frame, canopy
                - arch: wall, at:[u,y], w, h
                - balustrade: from/to, spacing, style fence|baluster|solid
                - beam: from/to, thickness (timber framing)
                - column: at:[x,y,z], height, radius, style round|square
                - chimney: at:[x,y,z], w, d, height, cap
                - detail: free-form cells [[x,y,z], ...] with material(s) — use sparingly, for odd bits

                # QUALITY BAR
                - A building needs: plinth, walls, floors, a door with a canopy, windows with sills and shutters,
                  a roof with overhang and a closed ridge, a chimney, a staircase, and surroundings (path, garden).
                - Detail IS the quality: many small meaningful objects beat one big box.
                - Nothing may float. Openings must be walkable: a door is 2 high with free cells in front/behind.
                - Respect an explicitly requested size exactly ("10 на 10" -> body x 0..9, z 0..9).
                - Use 6-10 palette materials; the roof should differ from the walls.
                Answer with ONE JSON object, no prose, no markdown fences.
                """;
    }

    /**
     * The critic's system prompt: how to look at the renders, and the exact answer shape.
     *
     * <p>Separate from {@link #system()} because the two calls have different jobs: the first writes a
     * scene, the second judges pictures of it. Both are constants so they can be prompt-cached between
     * iterations, and both demand one bare JSON object (jsonMode is on for every call).</p>
     *
     * @return the critic system prompt, never {@code null}
     */
    public static String criticSystem() {
        return """
                You are AIBuild's architectural critic. You are shown renders of a Minecraft-scale building
                that was designed as a declarative 3D scene (SceneSpec), plus an objective analysis computed
                by code. The renders are drawn by raytracing the voxel grid, so the blocks in the picture are
                exactly the blocks that will be placed in the world: if the roof has a hole in the render, the
                building will have that hole.

                # WHAT TO JUDGE
                - silhouette and proportions (massing, roof shape, height against footprint);
                - roof: is it complete, does it overhang, does the ridge close, is the pitch believable;
                - facade depth and rhythm: frames, sills, cornices, plinths, timber framing, recesses;
                - openings: is the entrance obvious, are windows aligned and evenly spaced, blank walls;
                - style fidelity: does it read as the requested building, palette and period;
                - broken things: floating parts, gaps, interpenetrating geometry, a flat "just a box" look.

                The objective analysis lists facts found by code (missing door, missing roof, size mismatch).
                Treat them as true. Do not repeat an analyzer finding in your own list unless you can add a
                visual observation to it.

                # ANSWER FORMAT
                Answer with ONE JSON object, no prose, no markdown fences:
                {"issues":[{"severity":"critical|major|minor","what":"...","where":"..."}],
                 "scores":{"shape":0-10,"detail":0-10,"style":0-10,"structure":0-10,"fidelity":0-10},
                 "verdict":"one sentence",
                 "patch":{"add":[OBJ,...],"replace":[OBJ,...],"remove":["id-or-index",...]}}

                Rules:
                - severity: "critical" = broken or does not answer the request; "major" = a clearly visible
                  architectural defect; "minor" = polish.
                - "what", "where" and "verdict" are written in RUSSIAN: the report is shown to a Russian
                  user. "where" names a facade, a view or an object id ("южный фасад", "крыша", "#roof").
                - scores are integers 0..10; be honest, most first drafts land at 3..6.
                - patch: address objects by their id, by "#id", or by their number in the object list.
                  A replaced object keeps its id. New objects get a short unique id. Coordinates are integers.
                - If you list ANY critical or major issue you MUST include the patch that fixes it in the
                  same answer: an issue without a fix is wasted work.
                - Prefer several small surgical ops (move a window, add a sill, enlarge the roof overhang,
                  add a bargeboard, swap a material) over rebuilding. At most 12 operations.
                - An empty patch is valid ONLY when the design is already good.
                """;
    }

    /**
     * Prompt for the first design.
     *
     * @param prompt the user request
     * @return the user message
     */
    public static String initial(String prompt) {
        return "Design this structure: \"" + prompt + "\"\n\n"
                + "Return ONE JSON scene (no prose) with 40-90 objects.\n"
                + "Order: plinth, walls with openings, floors, roof, then detail (beams, sills, shutters, chimney,\n"
                + "stairs, path, garden). Every requested feature must appear as an object.\n"
                + "Size: if the request names a footprint, the main body must match it exactly.";
    }

    /**
     * Prompt for one improvement round: the pictures, the facts and the patch request.
     *
     * @param prompt    the original user request
     * @param spec      the current design
     * @param analysis  {@link SceneAnalysis#render()} output
     * @param viewNames the order of the attached pictures
     * @param iteration the 1-based iteration number
     * @return the user message
     */
    public static String critique(String prompt, SceneSpec spec, String analysis, List<String> viewNames, int iteration) {
        int[][] b = spec.bounds();
        StringBuilder sb = new StringBuilder();
        sb.append("Review YOUR OWN design, iteration ").append(iteration).append(".\n");
        sb.append("Original request: \"").append(prompt).append("\"\n");
        sb.append("Current size: ").append(b[2][0]).append(" x ").append(b[2][1]).append(" x ").append(b[2][2])
                .append(" (x,y,z), objects: ").append(spec.objects().size()).append(".\n");
        sb.append("Pictures attached, in this order: ").append(String.join(", ", viewNames))
                .append(" — renders of exactly the scene below, drawn from its voxels.\n\n");
        String json = spec.toJson().toString();
        if (json.length() <= MAX_SCENE_JSON_CHARS) {
            sb.append("CURRENT SCENE:\n```json\n").append(json).append("\n```\n\n");
        } else {
            // A scene that outgrew the inline budget is described by the numbered digest instead: the
            // object numbers in it are the same references the patch may use.
            sb.append("CURRENT SCENE (too large to inline; numbered digest, objects are numbered from 1):\n")
                    .append(spec.digest(MAX_DIGEST_OBJECTS)).append('\n');
        }
        sb.append("OBJECTIVE CHECKS (computed by code, these are facts):\n").append(analysis).append('\n');
        sb.append("""

                Look at the pictures as an architect. Judge: silhouette and proportions, roof shape and coverage,
                facade depth and rhythm (framing, sills, cornices), placement of windows and door, whether it reads
                as the requested building, and anything broken, floating, empty or flat.
                Return ONE JSON object:
                {"issues":[{"severity":"critical|major|minor","what":"...","where":"..."}],
                 "scores":{"shape":0-10,"detail":0-10,"style":0-10,"structure":0-10},
                 "verdict":"one sentence",
                 "patch":{"remove":["id",...],"replace":[OBJ,...],"add":[OBJ,...]}}
                Rules for the patch: address objects by their id (or their number in the list above);
                replaced objects keep their id; new objects get a short id; coordinates are integers.
                If you list ANY critical or major issue you MUST include the patch that fixes it in the same answer.
                Prefer several small surgical ops over rewriting the building. Keep what already looks good.
                Write "what"/"where"/"verdict" in RUSSIAN: the report is shown to a Russian-speaking user.
                """);
        return sb.toString();
    }

    /**
     * Prompt used when the model's JSON could not be parsed.
     *
     * @param problem what was wrong
     * @return the corrective instruction
     */
    public static String repair(String problem) {
        return "Your previous answer was rejected: " + problem + "\n"
                + "Return a CORRECTED single JSON scene object with an \"objects\" array. No prose, no fences.";
    }

}
