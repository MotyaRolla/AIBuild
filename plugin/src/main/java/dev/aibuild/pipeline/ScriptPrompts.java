package dev.aibuild.pipeline;

import dev.aibuild.script.ScriptError;
import dev.aibuild.script.Stdlib;


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

    private static String examples(Stdlib lib) {
        StringBuilder sb = new StringBuilder();
        for (String name : lib.examples()) sb.append("## Example: ").append(name).append("\n```js\n").append(lib.example(name)).append("```\n");
        return sb.toString();
    }

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

    /** Craft section of the 3.0 library: master-builder standards, failure modes and the kits that implement them. */
    private static final String CRAFT_V2 = """
            # YOU ARE BUILDING LIKE THE BEST MINECRAFT BUILDERS
            Players will walk around this build, look at it from eye level, zoom in, and compare it side by side with
            another builder's version of the same request. Win that comparison. Judges look at:
            - Recognizability: the subject is unmistakable from every side, not only the front.
            - 3D form: true depth and sculpted shapes, never flat walls or boxes stacked on boxes.
            - Fidelity: everything the request names is there, with the requested size and style.
            - Proportions: parts relate correctly; Minecraft scale (doors 2 high, floors 4-5, rails 1).
            - Detail: logical, varied and abundant where the eye goes (entrances, faces, rooflines, joints).
            - Composition: a setting that frames the subject (ground, paths, trees, rocks, water), atmosphere, a story.

            FAILURE MODES THAT LOSE EVERY TIME
            1. Flat decoration: a box with coloured blocks painted on it. Every feature needs real depth.
            2. Visible primitives: "that's a box, that's a cylinder". Hide the construction: blend, smooth, layer.
            3. One flat material per surface: big single-block walls, roofs or bodies. Use mixes, weathering,
               gradients and shade() so surfaces read like real stone, wood, fur or scales.
            4. Lollipop trees (a ball on a stick), uniform grass, nothing on the ground: use tree(), groundCover().
            5. Static isolation: the subject floating in empty space, or so small the scenery dominates (a plot
               far wider than the subject, trees in front of it or taller than it close by).
            6. Uniform detail: the same resolution everywhere instead of concentrating it on the focal areas.

            HOW MASTER BUILDERS WORK
            - Depth layers: every wall has at least 3 layers (structure/frame in front, infill set back, openings set
              back further, with sills, trims, beams, buttresses, cornices poking out again). volume(), tower() and
              curtainWall() build this for you: use and combine them instead of hollow boxes.
            - Silhouette: varied heights and roof lines, overhangs, towers or chimneys breaking the skyline,
              asymmetry where it is natural. Organic subjects get poses, not stiff symmetry.
            - Texture and colour: a palette() with clear roles (60% main, 30% secondary, 10% accent); gradients from
              heavy/dark/mossy at the base to light at the top; partial blocks (stairs, slabs, walls, trapdoors,
              fences) to soften edges and add micro-detail; shade() on every organic mass.
            - Order: primary masses and proportions -> secondary forms (roofs, limbs, towers, wings) -> details
              (trim, lights, props, plants). Never polish a weak silhouette.
            - Scale for detail: house 20-40 across, mansion or temple 30-60, castle 60-140, statue/creature 25-80
              (a "small" subject is small for its kind, still big enough to carry detail). Explicit sizes are exact.
            - Function where it applies: working doors, stairs/ladders between floors, headroom >= 3. Sculptures and
              objects need no rooms.
            - Interiors are judged too: players walk in. Every room of an enterable building is furnished for its
              purpose and lit: give volume() its rooms (types per floor) or call furnish() for rooms you build
              yourself, then add a few hand-placed touches that tell its story. An empty shell loses every time.
            - Build everything the request names; a requested scene is a scene.
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

    /** System prompt of the latest library. */
    public static String system(String blockList) {
        return system(Stdlib.latest(), blockList);
    }

    /** System prompt for every script call against {@code lib}; {@code blockList} is appended when configured. */
    public static String system(Stdlib lib, String blockList) {
        return HEAD + "\n" + Prompts.blockStateRules()
                + "\n- Kits (door, windowAt, roof, stairs, column, chair...) orient their blocks for you: prefer them.\n\n"
                + (lib == Stdlib.V1 ? CRAFT : CRAFT_V2) + "\n" + lib.apiCard() + "\n" + CONTRACT + "\n# EXAMPLES (style reference only; do not copy)\n" + examples(lib)
                + (blockList == null || blockList.isBlank() ? "" : "\n# VALID BLOCK IDS (Minecraft Java 26.3 — use ONLY these)\n" + blockList);
    }

    /** First construction request. {@code scale} describes the block budget of the mode. */
    public static String create(String request, String scale) {
        return create(Stdlib.V1, request, scale);
    }

    /** First construction request against {@code lib}, with its expert recipe cards for this kind of subject. */
    public static String create(Stdlib lib, String request, String scale) {
        return create(lib, request, scale, null);
    }

    /** As above, following {@code concept} (a design brief from {@link #concepts}) when it is not null. */
    public static String create(Stdlib lib, String request, String scale, String concept) {
        if (lib == Stdlib.V1) {
            return """
                    %s

                    Write the complete build script. %s
                    Think about the subject's real appearance from every side before writing code, then write the script
                    directly (do not enumerate coordinates in your thoughts — compute them in code).
                    """.formatted(Prompts.request("BUILD REQUEST", request), scale);
        }
        String recipes = Recipes.forRequest(lib, request);
        return """
                %s

                %s
                %s
                Before writing code, plan in your head (briefly, no coordinate lists):
                1. The 3-5 traits that make this subject unmistakable, and how it looks from every side.
                2. The parts with their sizes and how they connect; which kits build each part.
                3. The setting that frames it, and the focal areas that get the most detail.
                4. How you avoid the failure modes (flat walls, visible primitives, single-block surfaces).
                Then write the complete build script. %s
                """.formatted(Prompts.request("BUILD REQUEST", request), recipes.isBlank() ? "" : recipes,
                concept == null ? "" : "\nDESIGN CONCEPT for your version (follow it; where it conflicts with the request, the request wins):\n"
                        + concept + "\n", scale);
    }

    /**
     * The lead designer writes {@code n} short design concepts for the script candidates: concrete layouts that
     * keep everything the request states and vary only what it leaves open.
     */
    public static String concepts(Stdlib lib, String request, String scale, int n) {
        String recipes = Recipes.forRequest(lib, request);
        return """
                %s

                %s

                You are the lead designer. %s Write %d design concept(s): short, concrete briefs a builder can follow.
                Do not write code.
                - Everything the request states is fixed (subject, sizes, materials, style, named elements): every
                  concept keeps it unchanged. Vary only what the request leaves open.%s
                - The subject dominates: most of the blocks belong to it. The setting frames it on a plot at most about
                  8 blocks beyond it on each side (more only for a requested scene), and nothing hides it from the front.
                - Give the overall size in blocks (width x depth x height), at a scale that carries detail for this kind
                  of subject unless the request gives the size. %s
                - Name 3-5 signature details that make it unmistakable, and where they go (front = south).
                - Palette: 4-6 real block ids with roles (main, structure, roof or body, accent, ground).
                - Say which kits build which parts (volume, roof, tower, tree, ...).
                Each brief is under 120 words, in English; each name is 2-5 words in the request's language.
                Answer with ONE JSON object: {"concepts":[{"name":"...","brief":"..."}]}""".formatted(Prompts.request("BUILD REQUEST", request),
                recipes.isBlank() ? "" : recipes,
                n == 1 ? "A builder will write a build script for this request." : n + " builders will each write a build script for this request, one concept each.",
                n,
                n == 1 ? "" : "\n- The concepts differ in layout and silhouette (plan shape, massing, roof forms, pose or composition),\n  not only in colours or decoration.",
                scale);
    }

    /**
     * The request enhancer: turns a short, often misspelled or non-English request into a brief every later
     * stage can build from, without adding requirements the user did not make. REQUIRED restates the request;
     * SUGGESTED is what an expert would add for this subject and style, which concepts and builders may vary.
     */
    public static String enhance(String request, String scale) {
        return """
                BUILD REQUEST: "%s"

                You are the lead designer of a Minecraft build team. Before anything is designed, work this request
                out into a brief the builders can follow. Do not write code and do not design one specific layout.

                Answer in English, under 260 words, in exactly these sections:
                SUBJECT: what is built, in one sentence, typos fixed and translated.
                REQUIRED: every fact the request states, each as one line: sizes, counts, materials, style, period,
                  named elements, setting. Keep the user's numbers exactly. Say how an ambiguous size is read: a
                  footprint size is the subject itself (a castle's walls, a house's outer walls), not its terrain.
                SUGGESTED: 6-10 lines an expert would add for this subject and style, so it is unmistakable and
                  rich: signature elements with their real names (for a German castle: Bergfried, Palas, Zwinger,
                  Torhaus...), proportions, roof forms, wall details, the rooms that belong inside, the setting.
                  Only what fits the request; nothing that changes what it asks for.
                PALETTE: 5-8 real Minecraft block ids with their roles (walls, trim, roof, floor, accent, ground).
                AVOID: 2-4 lines on what would make this read wrong (wrong period, wrong scale, a hidden subject).

                %s""".formatted(Prompts.escape(request), scale);
    }

    /** The brief in an enhancer answer: the sections without any fence or preamble, or null when there are none. */
    public static String parseBrief(String answer) {
        if (answer == null) return null;
        String s = answer.replaceAll("(?m)^```[a-z]*\\s*$", "").strip();
        int at = s.indexOf("SUBJECT:");
        if (at < 0 || !s.contains("REQUIRED:")) return null;
        s = s.substring(at).strip();
        return s.length() > 3000 ? s.substring(0, 3000) : s;
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
                %s
                CRITIC ROUND %d of %d. %s
                Judge the build the way a demanding human would when comparing it with another builder's version:
                recognizability and proportions first, then form (flat walls, visible raw boxes, missing depth, stiff
                silhouettes), then requested features, function (doors, access, light) and composition, then detail.
                Problems the analyzer lists as CRITICAL or MAJOR must be fixed unless they are clearly false for
                this kind of object. What the analysis says is repaired automatically (lights, loose attachments,
                decoration in walls) needs no hunk.
                Do no harm: every revision is measured, and it is thrown away if it makes anything worse (floating
                or unsupported blocks, dark rooms, blocked doors, fewer rooms, blocks of one part destroyed by
                another) or if the judge does not see an improvement. So keep what already works, do not restyle
                parts that are fine, and keep each hunk inside one part(). Do not guess where existing walls, floors
                or rooms are: read the script, or query them in code (rooms(), partBox(name), isSolid(x, y, z),
                surface(x, z)); place wall decoration with onWall()/banner() and hanging lights with hangFrom().
                If nothing material is wrong, answer DONE.

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
                %s""".formatted(Prompts.request("BUILD REQUEST", request), round, rounds,
                image ? "The attached image shows the current build from four sides (front = south) in real block colours;"
                        + " a second image, when attached, shows its rooms from above with everything higher removed."
                        : "No image is available; rely on the analysis and the script.",
                HUNKS,
                rejected.isEmpty() ? "" : "EARLIER REVISIONS THAT WERE REJECTED (do not repeat them):\n- " + String.join("\n- ", rejected) + "\n",
                parts, evidence, dev.aibuild.script.ScriptPatch.numbered(script));
    }

    /**
     * The art director looks at the renders and names the parts whose rewrite would improve the build most, with a
     * concrete brief for each sculptor.
     */
    public static String director(String request, String parts, String evidence, int max) {
        return director(request, null, parts, evidence, max, false);
    }

    /**
     * As above. {@code concept} is the design brief the build follows (null when none); with {@code refine} the
     * sculptors keep a part's structure and add craft on top, so the briefs ask for additions, not redesigns.
     */
    public static String director(String request, String concept, String parts, String evidence, int max, boolean refine) {
        return """
                %s
                %s                You are the art director of this build. The attached image shows it from four sides, at eye level and
                from the top. Decide which parts would gain the most from a master builder's rework: weak or stiff
                silhouettes, flat walls or single-block surfaces, visible primitives, missing signature details of the
                subject, and above all the focal areas (faces, entrances, crowns, rooflines, joints). Parts that carry
                the subject matter more than scenery. Pick at most %d parts by their exact names from the list; never
                pick a part together with a part nested inside it ('house' and 'house/roof'): the whole subject or its
                weakest pieces. Each brief goes only to that part's sculptor, so name the right part. When scenery
                swamps the subject (a huge plot, trees hiding it), brief the scenery part to shrink and clear the view.
                For each, write a concrete brief: %s
                Answer with ONE JSON object: {"parts":[{"name":"...","brief":"..."}]} (an empty list if nothing would gain).
                PARTS (name: blocks and bounds):
                %s
                AUTOMATIC ANALYSIS:
                %s""".formatted(Prompts.request("BUILD REQUEST", request), concept == null ? "" : "DESIGN CONCEPT: " + concept + "\n", max,
                refine ? """
                        what is weak and what to add or change on the existing part (trims, frames, depth layers,
                        partial blocks, shading, signature details, kit options). The sculptor refines the part and keeps
                        its masses, footprint and roof forms; ask for a redesign only when a part is clearly wrong."""
                        : """
                        what is wrong, what to build instead (forms, depth layers, kits,
                        shading, details), and keep its position and footprint (within about 3 blocks) unless it must grow.""",
                parts, evidence);
    }

    /** A sculptor rewrites one part() statement at a higher level of craft. */
    public static String sculptor(String request, String name, String bounds, String brief, String partSource,
                                  String script, boolean image) {
        return sculptor(request, null, name, bounds, brief, partSource, script, image, false);
    }

    /**
     * As above. With {@code refine} the sculptor keeps the part's structure and the kit calls that build it and adds
     * craft on top (faster than a redesign, and it keeps the kits' roofs and walls); {@code concept} may be null.
     */
    public static String sculptor(String request, String concept, String name, String bounds, String brief, String partSource,
                                  String script, boolean image, boolean refine) {
        if (refine) return """
                %s
                %sYou are a master builder. Refine ONE part of this build: part('%s'), currently %s (world coordinates).
                The rest of the build stays as it is. Keep this part's structure: its masses, footprint, heights, roof
                forms and position, and the kit calls that build them (volume, roof, tower, tree, ...). Change a kit's
                options rather than replacing it with hand-placed blocks; never hand-roll a roof, wall or tree that a
                kit builds. Change the structure only where the brief asks for it.
                ART DIRECTOR'S BRIEF: %s
                %s
                Add craft on top of what is there: frames and trims, depth layers, partial blocks (stairs, slabs, walls,
                trapdoors, fences) for micro-detail, shade() or mixes on plain surfaces, the subject's signature details,
                lights and props where the eye goes. Keep the statement compact: loops and helpers, not long block lists.
                Other parts' blocks are locked: a rewrite that empties or covers more than a few cells of a neighbour
                (its wall, floor or stairs) is thrown away. partBox(name) gives a neighbour's bounds as built. Put wall
                decoration on with onWall()/banner() and hang lights with hangFrom(), never by guessed coordinates.
                You may use the script's top-level variables and functions.
                Answer with ONE ```js block that contains only the complete replacement statement
                part('%s', () => { ... }); — define any helpers inside it.

                CURRENT PART:
                ```js
                %s
                ```
                WHOLE SCRIPT (read-only context):
                %s""".formatted(Prompts.request("BUILD REQUEST", request), concept == null ? "" : "DESIGN CONCEPT: " + concept + "\n", name, bounds, brief,
                image ? "The attached image shows close-ups of this part in the build." : "", name, partSource,
                dev.aibuild.script.ScriptPatch.numbered(script));
        return """
                %s
                You are a master builder. Rewrite ONE part of this build: part('%s'), currently %s (world coordinates).
                The rest of the build stays as it is, so keep this part where it is and connected to its neighbours
                (stay within about 3 blocks of its current bounds unless the brief asks for more).
                ART DIRECTOR'S BRIEF: %s
                %s
                Raise the craft a lot: real depth layers, sculpted forms instead of boxes, texture and shade() on
                surfaces, partial blocks (stairs, slabs, walls, trapdoors, fences) for micro-detail, the subject's
                signature details. Keep what already works. Other parts' blocks are locked: a rewrite that empties or
                covers more than a few cells of a neighbour is thrown away (partBox(name) gives its bounds).
                You may use the script's top-level variables and functions.
                Answer with ONE ```js block that contains only the complete replacement statement
                part('%s', () => { ... }); — define any helpers inside it.

                CURRENT PART:
                ```js
                %s
                ```
                WHOLE SCRIPT (read-only context):
                %s""".formatted(Prompts.request("BUILD REQUEST", request), name, bounds, brief,
                image ? "The attached image shows close-ups of this part in the build." : "", name, partSource,
                dev.aibuild.script.ScriptPatch.numbered(script));
    }

    /**
     * The interior designer furnishes every room of the build in one part() statement that runs after the rest
     * (or replaces the script's own interior part). {@code rooms} lists the rooms the analyzer found.
     */
    public static String interior(String request, String concept, String rooms, String name, String current, String script,
                                  boolean image) {
        return """
                %s
                %sYou are the interior designer of this build. Players walk into every room and judge it like the facade.
                %s
                ROOMS FOUND INSIDE (y = the layer players stand in; furniture there; furnish() takes the floor layer y-1):
                %s
                Make every room look lived-in and right for this build:
                - Give each room a purpose from the request and its place (ground floor: kitchen, living, hall, tavern,
                  shop, smithy; upper floors: bedroom, study, library; a keep's hall: throne or hall; a temple: chapel).
                - Do not type room coordinates: read them. rooms() returns the rooms as built when your part runs
                  ({x1, z1, x2, z2, y, area}, largest first), and furnish(room, type, {replace: true, wood: ...})
                  furnishes one. Pick the type from where the room is, e.g.
                    rooms().forEach(r => furnish(r, r.y <= 3 ? (r.area > 60 ? 'hall' : 'kitchen') : 'bedroom',
                        {replace: true, wood: 'spruce'}));
                  replace:true takes back what volume() furnished; stairs, walls and anything else stay. Furniture only
                  goes into air, so it cannot break a wall or a staircase; cells it could not use are reported.
                - Then add 2-5 hand-placed touches in the important rooms that tell their story (food on the table,
                  a cauldron by the hearth, an open book on a lectern, sacks and tools, flowers on sills, candles).
                  Place them relative to a room from rooms(), and check the cell first: isAir(x, y, z).
                - Wall decoration goes through onWall(x, y, z, face, block) or banner(x, y, z, face, colour): they find
                  the real wall face near the cell and never replace a wall block. Hanging lights go through
                  hangFrom(x, y, z) or chandelier(): they find the real ceiling above. A raw block() of a banner,
                  torch or lantern at a guessed coordinate ends up inside a wall or in the air.
                - Light every room: no corner may stay dark.
                - Only place blocks inside the rooms: never in walls, ceilings, roofs or outside. Keep doorways, stairs
                  and ladders clear.
                %s
                Answer with ONE ```js block that contains only the statement part('%s', () => { ... }); — define any
                helpers inside it. You may use the script's top-level variables and functions.

                WHOLE SCRIPT (read-only context):
                %s""".formatted(Prompts.request("BUILD REQUEST", request), concept == null ? "" : "DESIGN CONCEPT: " + concept + "\n",
                image ? "The attached image shows each room from above with everything higher removed." : "",
                rooms,
                current == null ? "Your statement is added at the end of the script, after everything else."
                        : "Your statement replaces the script's interior part:\n```js\n" + current + "\n```",
                name, dev.aibuild.script.ScriptPatch.numbered(script));
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
        return Prompts.request("REQUEST", request) + "\nThe first image is build A, the second image is build B "
                + "(each shows four sides of the build). Which build is better?";
    }

    /** Canary that checks the gateway really delivers images to the model. */
    public static String visionProbe() {
        return "What number is written in the image? Answer with the digits only.";
    }
}
