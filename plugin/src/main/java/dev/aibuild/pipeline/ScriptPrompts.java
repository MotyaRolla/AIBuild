package dev.aibuild.pipeline;

import dev.aibuild.script.ScriptError;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Prompts for the build-script strategy: the model writes one JavaScript program against the stdlib
 * ({@code stdlib/api-card.md}) instead of a JSON op list.
 *
 * <p>The system prompt is identical for create, repair and review calls so the provider's prefix cache hits.
 * It carries the role, the coordinate and block-state rules, the craft principles that separate impressive
 * builds from assembled boxes, the API card and three short idiomatic examples (kept short: long worked
 * examples measurably hurt several models).</p>
 */
public final class ScriptPrompts {
    private ScriptPrompts() {
    }

    private static final String API_CARD = resource("/stdlib/api-card.md");
    private static final String EXAMPLES = "## Example: cottage\n```js\n" + resource("/stdlib/examples/cottage.js")
            + "```\n## Example: creature\n```js\n" + resource("/stdlib/examples/fox.js")
            + "```\n## Example: bridge\n```js\n" + resource("/stdlib/examples/bridge.js") + "```\n";

    private static final String HEAD = """
            You are AIBuild — a master Minecraft builder, sculptor, architect and landscaper (Java Edition 26.3).
            You create builds by writing ONE JavaScript build script. A sandboxed program runs it and places the
            blocks: you have loops, math, functions, smooth signed-distance shapes, material gradients and kits.
            Use code to express structure (dimensions as variables, repetition as loops, symmetry with sym(),
            radial patterns with around()), so the whole build stays proportional and consistent.

            # COORDINATES
            - x -> EAST, z -> SOUTH, y -> UP; integer blocks; all ranges inclusive.
            - The FRONT (main entrance / main view) faces SOUTH (+z). West = small x, north = small z.
            - y = 0 is the natural ground surface. Floors, paths and ponds replace it at y = 0 (or below). Everything
              standing on the ground starts at y = 1. Unset cells at y <= 0 stay natural ground; y >= 1 become air.
            - Start the main subject around x = 0, z = 0. Scenery may use negative coordinates.
            """;

    private static final String CRAFT = """
            # WHAT MAKES A BUILD IMPRESSIVE (people rotate the camera, zoom in, and compare it with another build)
            - Recognizable at a glance: decide the 3-5 traits that make the subject unmistakable (silhouette,
              proportions, signature features) and get them right first. Primary masses -> secondary forms
              (towers, wings, limbs, roofs) -> tertiary detail (trim, lights, props). Never polish a weak silhouette.
            - Sculpted, not assembled: avoid raw boxes that read as boxes. Organic subjects (creatures, trees, terrain,
              hulls, statues) use tube / loft / revolve / ellipsoid / blend with smooth:true and warp(). Curved and
              tapered roofs, domes and spires use roof({type:'dome'|'cone'|'onion'|'spire'}) or shapes with smooth:true.
            - Depth everywhere: no flat walls. Facades get 2-3 depth layers: plinth, recessed openings with frames
              and sills, protruding posts/pilasters/beams, cornices and eaves, overhangs. Big surfaces get texture with
              mix() / weather() / grad() (heavier, darker, mossier near the ground; lighter toward the top).
            - Concentrate detail where the eye goes (entrance, face, crown, roofline, joints); keep large areas calmer.
            - Palette: 3-6 harmonious materials with clear roles (base, structure, infill, roof, accent).
            - Composition: give the subject a setting that frames it — ground transitions, paths, gardens, trees,
              rocks, water, a base or pedestal for sculptures — without burying it in clutter.
            - Function where it applies: buildings have working doors, furnished rooms with light, stairs between
              floors, headroom >= 3, nothing blocking entrances. Sculptures and objects need no rooms or windows.
            - Use the space: typical sizes are a house 12-25 wide, a mansion 25-45, a castle 40-90, a creature 20-60
              long, a ship 30-70 long. Bigger builds allow finer detail. Explicit sizes in the request are exact.
            - Build everything the request names. If it asks for a scene, build the scene.
            """;

    private static final String CONTRACT = """
            # ANSWER FORMAT
            Answer with ONE ```js code block containing the complete script, and nothing outside it.
            1. Start with a comment block: the defining traits, overall dimensions, the palette with roles, the parts.
            2. Call meta({...}) once: title (in the user's language), summary (one sentence, user's language),
               type (house | building | tower | castle | bridge | sculpture | statue | creature | vehicle | ship |
               tree | landscape | scene | object), style, expect_interior (true only for enterable buildings),
               exact_size {x,y,z} only when the user gave exact dimensions, rooms [{name, level, area:'x1-x2, z1-z2',
               furnishing}] for buildings with interiors.
            3. Build inside named part('...', () => {...}) blocks. Keep dimensions in variables; reuse functions.
            Scripts are re-run deterministically; random choices come from the seeded rng().
            """;

    /** System prompt for every script call; {@code blockList} is appended when configured. */
    public static String system(String blockList) {
        return HEAD + "\n" + Prompts.blockStateRules()
                + "\n- Kits (door, windowAt, roof, stairs, column, chair...) orient their blocks for you: prefer them.\n\n"
                + CRAFT + "\n" + API_CARD + "\n" + CONTRACT + "\n# EXAMPLES (style reference only; do not copy)\n" + EXAMPLES
                + (blockList == null || blockList.isBlank() ? "" : "\n# VALID BLOCK IDS (Minecraft Java 26.3 — use ONLY these)\n" + blockList);
    }

    /** First construction request. {@code scale} describes the block budget of the mode. */
    public static String create(String request, String scale) {
        return """
                BUILD REQUEST: "%s"

                Write the complete build script. %s
                Think about the subject's real appearance from every side before writing code, then write the script
                directly (do not enumerate coordinates in your thoughts — compute them in code).
                """.formatted(Prompts.escape(request), scale);
    }

    /** Asks for a corrected full script after a failed run. */
    public static String repair(ScriptError error, int attempt) {
        return """
                The script failed (attempt %d). Fix the cause and answer with the COMPLETE corrected script in one ```js
                block. Keep everything that worked; change only what is needed.

                %s""".formatted(attempt, error.describe());
    }

    private static final String HUNKS = """
            Express every change as SEARCH/REPLACE hunks against the CURRENT script (copy the SEARCH lines exactly,
            without the line-number prefix; include enough lines to be unique; several hunks are fine):
            <<<<<<< SEARCH
            exact existing lines
            =======
            replacement lines
            >>>>>>> REPLACE
            Put all hunks in one ```text block. A complete rewrite is allowed only when most of the script changes:
            then answer with the full script in one ```js block instead.
            """;

    /**
     * One critic round: the model sees the render (if attached), analyzer findings, parts and the numbered script,
     * names the most important improvements and implements them as hunks, or answers DONE.
     */
    public static String critic(String request, String script, String evidence, String parts, boolean image, int round, int rounds,
                                java.util.List<String> rejected) {
        return """
                BUILD REQUEST: "%s"
                CRITIC ROUND %d of %d. %s
                Judge the build the way a demanding human would when comparing it with another builder's version:
                recognizability and proportions first, then form (flat walls, visible raw boxes, missing depth, stiff
                silhouettes), then requested features, function (doors, access, light) and composition, then detail.
                Problems the analyzer lists as CRITICAL or MAJOR must be fixed unless they are clearly false for
                this kind of object. Do not churn: keep what already works.

                Answer in this format:
                DIAGNOSIS: up to 5 numbered findings, most important first, each naming the part and the concrete fix.
                Then the hunks implementing ALL findings. If the build already fully meets the request with no
                meaningful improvement left, answer only: DONE
                %s
                %s
                PARTS:
                %s
                AUTOMATIC ANALYSIS:
                %s

                CURRENT SCRIPT:
                %s""".formatted(Prompts.escape(request), round, rounds,
                image ? "The attached image shows the current build from four sides (front = south) in real block colours."
                        : "No image is available; rely on the analysis and the script.",
                HUNKS,
                rejected.isEmpty() ? "" : "EARLIER REVISIONS THAT WERE REJECTED (do not repeat them):\n- " + String.join("\n- ", rejected) + "\n",
                parts, evidence, dev.aibuild.script.ScriptPatch.numbered(script));
    }

    /** A user's change request against the stored script of the current version. */
    public static String edit(String original, String change, String script, String evidence, String parts, boolean image) {
        return """
                ORIGINAL REQUEST: "%s"
                CHANGE REQUEST: "%s"
                Modify the build script so the change is fully and beautifully implemented, consistent with the existing
                style, and nothing unrelated breaks. %s
                Briefly state what you change (CHANGE: one or two sentences in the user's language), then the hunks.
                %s
                PARTS:
                %s
                AUTOMATIC ANALYSIS:
                %s

                CURRENT SCRIPT:
                %s""".formatted(Prompts.escape(original), Prompts.escape(change),
                image ? "The attached image shows the current build." : "", HUNKS, parts, evidence,
                dev.aibuild.script.ScriptPatch.numbered(script));
    }

    /** Repair request when a revision does not apply or its script fails. */
    public static String revisionFailed(String problem) {
        return "Your revision could not be used: " + problem + "\nAnswer again with corrected hunks (or the full script).";
    }

    /** System prompt of the pairwise judge (short and separate: it does not write code). */
    public static final String JUDGE_SYSTEM = """
            You are an impartial judge of Minecraft builds. You compare two builds made for the same request and pick
            the one a demanding human player would prefer. Criteria, most important first: fidelity to the request
            (every requested element, exact sizes), recognizability and proportions, sculpted 3D form and depth
            (not flat walls or raw boxes), coherent palette, composition and setting, detail quality where the eye
            goes. Bigger or busier is not better by itself. Ignore image order and labels; judge only the builds.
            Answer with ONE JSON object: {"winner":"A"|"B"|"tie","reasons":["...","...","..."]}""";

    public static String judge(String request) {
        return "REQUEST: \"" + Prompts.escape(request) + "\"\nThe first image is build A, the second image is build B "
                + "(each shows four sides of the build). Which build is better?";
    }

    /** Canary that checks the gateway really delivers images to the model. */
    public static String visionProbe() {
        return "What number is written in the image? Answer with the digits only.";
    }

    private static String resource(String path) {
        try (InputStream in = ScriptPrompts.class.getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("missing resource " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + path, e);
        }
    }
}
