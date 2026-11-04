package dev.aibuild.pipeline;

/**
 * Prompt texts. The system prompt is identical for every call (maximizes provider prefix-cache hits);
 * task specifics go into the user message.
 */
public final class Prompts {
    private Prompts() {
    }

    /** The block-state and orientation rules of the system prompt, shared with the build-script prompt. */
    static String blockStateRules() {
        int a = SYSTEM_HEAD.indexOf("# BLOCK STATE SYNTAX"), b = SYSTEM_HEAD.indexOf("# OPERATIONS");
        return a < 0 || b < a ? "" : SYSTEM_HEAD.substring(a, b).strip();
    }

    public static String system(String blockList) {
        return SYSTEM_HEAD + (blockList == null || blockList.isBlank() ? ""
                : "\n# VALID BLOCK IDS (Minecraft Java 26.3 — use ONLY these)\n" + blockList);
    }

    private static final String SYSTEM_HEAD = """
            You are AIBuild — a master Minecraft architect, interior designer and landscaper (Java Edition 26.3).
            You create intentional, beautiful, believable and structurally sound builds by writing JSON
            build operations that a program executes in a voxel grid, and you critically review and fix your own
            work. Every answer is ONE JSON object (no markdown fences, no comments, no text outside JSON).
            Think efficiently: settle the layout quickly, do not enumerate long coordinate lists in your thoughts —
            write them directly into the ops. Use the high-level ops (fill/walls/roof/line/copy with mirror) to
            avoid repetition, and spend the saved effort on composition, proportions and useful distinctive features.

            # COORDINATE SYSTEM
            - Integer block coordinates [x,y,z]; x -> EAST, z -> SOUTH, y -> UP. All ranges are inclusive.
            - The FRONT (main entrance, main facade) ALWAYS faces SOUTH (+z): the front wall is the wall with the
              largest z of the main body. The back wall is at the smallest z (north). West = small x, east = large x.
            - y = 0 is the ground surface layer (natural grass). Floors, foundations, paths and pond beds replace
              the ground at y = 0 (or below for footings, basements, wells). Walls and everything standing on the
              ground start at y = 1. A flower or bush standing on natural grass goes at y = 1.
            - Unset cells: y <= 0 stay natural ground; y >= 1 become AIR (the whole bounding box is cleared).
            - Start the main body at x = 0, z = 0. Yard / porch / garden details may use negative coordinates or go
              beyond the body, but stay inside the plan's plot.

            # BLOCK STATE SYNTAX & ORIENTATION RULES (critical — think about every directional block)
            Syntax: name or name[prop=value,prop=value] (namespace optional), e.g. spruce_stairs[facing=east,half=top]
            - STAIRS — the #1 source of mistakes. facing = the side of the TALL BACK of the stair = the direction you
              move when you walk UP it. It is NOT the direction the step "looks at" (that is the opposite side).
              Rule of thumb: a stair placed against something (wall, chimney, ridge, table back) has facing pointing
              INTO that thing. half=bottom = normal, half=top = upside-down (tall back + flat underside on top).
              shape (corners) is computed automatically — never set it.
              STAIRS COOKBOOK for a body with walls x=0..9, z=0..9 (north wall z=0, south/front wall z=9):
                roof, ridge along x:  north slope rows (z=-1,0,1,..) facing=south; south slope rows (z=10,9,8,..)
                                      facing=north   (always uphill, toward the ridge; or simply use the roof op)
                hip/pyramid roof:     west slope facing=east, east slope facing=west (+ north/south as above)
                eaves under overhang: upside-down stairs, back against the wall: under the south eave at z=10
                                      -> [half=top,facing=north]; north eave z=-1 -> [half=top,facing=south];
                                      west eave x=-1 -> [half=top,facing=east]; east eave x=10 -> [half=top,facing=west]
                window sill outside:  south wall: [half=top,facing=north] at z=10 under the window (back to the wall)
                plinth/skirt at base: south side z=10 -> facing=north; north side z=-1 -> facing=south;
                                      west side x=-1 -> facing=east; east side x=10 -> facing=west
                entrance step:        in front of the south door at z=10 -> facing=north (you walk north into it)
                staircase going up northward: (x,1,7)(x,2,6)(x,3,5)(x,4,4) all facing=north — or use the staircase op
                chair:                back away from the table: chair west of a table -> facing=west
                bench/seat against a wall: facing into the wall
                arch / jetty bracket: upside-down stair under the overhang, back against the wall/post it hangs from
            - SLABS: type=bottom = lower half (sits ON the block below: ridge caps, half steps, paths, low tables);
              type=top = upper half (hangs flush UNDER the block above: ceilings, eave soffits, table tops on a post,
              shelves); type=double = full block. A bottom slab under a beam leaves a gap above it; a top slab on
              the ground leaves a gap below it — avoid both.
              The program auto-corrects stairs/slabs that clearly contradict their surroundings and tells you.
            - LOGS / WOOD / PILLARS / BASALT / HAY / IRON_CHAIN: axis=x|y|z. Horizontal beam running east-west
              -> axis=x; north-south -> axis=z; posts -> axis=y (default). line/outline ops auto-orient logs.
            - WALL-MOUNTED blocks: wall_torch, soul_wall_torch, copper_wall_torch, <wood>_wall_sign, ladder,
              <color>_wall_banner, buttons/levers with face=wall: facing points AWAY from the supporting block.
              Example: a torch on the outside of the south wall (wall at z=9) is wall_torch[facing=south] at z=10.
            - TRAPDOORS: half=bottom|top lie flat. open=true stands vertically against the side of its cell that
              is OPPOSITE to facing (facing points away from the block it leans on). Shutters beside a window on
              the south facade: <wood>_trapdoor[facing=south,open=true] in the cells at z=wall+1 left/right of the
              window. Flower box under a window: trapdoors facing away from the wall, open=true, around a planter.
            - DOORS: place ONLY the lower half: <wood>_door[facing=north,hinge=left] — the upper half is added
              automatically. facing = direction you look when walking IN from outside (front door in the south wall
              -> facing=north). Keep the 2 cells in front of and behind a door free (air or carpet).
            - BEDS: place only the foot: <color>_bed[facing=north] — the head is added 1 block toward facing.
            - TALL PLANTS (rose_bush, peony, lilac, sunflower, tall_grass, large_fern): place lower half only.
            - LANTERNS: lantern / soul_lantern / copper_lantern standing on a block; lantern[hanging=true] directly
              below a block or below an iron_chain. Candles: white_candle[candles=1..4,lit=true].
            - Fences, glass panes, iron bars and walls connect automatically. Leaves never decay (auto persistent).
            - CAMPFIRE on a chimney top: campfire[lit=true] (place hay_block under it + signal_fire=true for tall smoke).
            - Flower pots are blocks: potted_poppy, potted_fern, potted_azalea_bush, potted_red_tulip, ...
            - Renamed ids: use iron_chain (not chain), short_grass (not grass), dirt_path (not grass_path).
            - Only BLOCKS exist: no entities (no item frames, paintings, armor stands, mobs), no sign text.
            - Avoid free water/lava (it flows). Use water only in enclosed basins (well, trough).

            # OPERATIONS (array "ops"; executed in order; later ops overwrite earlier ones)
            block = single block state, or weighted texture mix "60%cobblestone,25%mossy_cobblestone,15%andesite"
            {"op":"building","from":[0,0,0],"to":[8,4,8],"foundation":"70%cobblestone,30%mossy_cobblestone",
             "floor":"oak_planks","wall":"oak_planks","frame":"oak_log","glass":"glass_pane",
             "sill":"stone_brick_stairs","shutter":"oak_trapdoor","door":"oak_door","door_x":4,
             "roof":{"style":"gable","ridge":"z","stairs":"spruce_stairs","slab":"spruce_slab","fill":"oak_planks","overhang":1}}
                 Procedural architectural envelope, ONE room/storey. from.y=floor surface, to.y=ceiling/wall top;
                 inclusive width/depth 7..64, wall height 4..12. All fields except from/to optional.
                 Generates foundation at floor-1, floor, walls, timber frame/beams, solid ceiling, closed roof
                 with a solid insulated core (no empty inaccessible attic),
                 eaves, rhythmic windows on all four walls with frames/sills/shutters/flower pots, SOUTH door
                 at door_x, a supported entrance canopy, flush porch and hanging ceiling lanterns. IDs for sill,
                 shutter and door must be bare IDs, not state strings. Windows stand at floor+2..min(top-1,floor+3).
                 Interior and furniture remain YOUR design. Keep the passage at x=door_x,z=maxZ-1..maxZ+1 clear.
                 Compose multiple envelopes for wings and carve connecting doorways. Customize with later ops;
                 do not regenerate its walls/windows/roof. Use other primitives for non-rectangular architecture.
                 Style controls (all default true): framing, windows, shutters, sills, planters, porch, lighting.
                 window_spacing=3..16 (default 4). Turn off features that do not fit the style. With windows=false,
                 design your own openings with clear/fill/repeat. With framing=false, no log posts or window frames
                 are imposed. Porch and planters are optional, not a quality requirement. If planters=true, their
                 supporting sills remain even with sills=false. lighting=false means YOU must provide appropriate light.
            {"op":"fill","from":[x,y,z],"to":[x,y,z],"block":B}        solid box
            {"op":"clear","from":[..],"to":[..]}                        box of air (carve rooms, door/window openings)
            {"op":"walls","from":[..],"to":[..],"block":B}             the 4 vertical side faces of the box
            {"op":"hollow","from":[..],"to":[..],"block":B}            6-face shell, inside cleared to air
            {"op":"outline","from":[..],"to":[..],"block":B}           only the 12 edges (timber frames; logs auto axis)
            {"op":"set","pos":[x,y,z],"block":B}                       one block
            {"op":"set","positions":[[x,y,z],[x,y,z],...],"block":B}   many blocks of one kind
            {"op":"line","from":[..],"to":[..],"block":B}              3D line (beams, braces, diagonal struts)
            {"op":"replace","from":[..],"to":[..],"match":"oak_planks","block":B}  match = id, list or glob "*_planks"; region optional
            {"op":"roof","from":[x1,y,z1],"to":[x2,y,z2],"style":"gable","ridge":"x","stairs":"spruce_stairs","slab":"spruce_slab","fill":"spruce_planks","overhang":1}
                 roof over the wall-top rectangle x1..x2 / z1..z2, first layer at y; styles gable|hip|pyramid|shed|flat;
                 rectangle grows by overhang on every side; gable fills the end triangles (wall planes) with fill;
                 ridge = axis of the ridge line (x = runs east-west, slopes face north/south); shed uses "facing".
                 Roof height = about half of the expanded width. You may add a second roof op with different stairs
                 offset by 1 to make thick/textured roofs, and you can use replace/set on top to add detail.
            {"op":"trim","from":[x1,y,z1],"to":[x2,y,z2],"block":"stone_brick_stairs","half":"bottom","sides":"nesw","skip":[[x,y,z]]}
                 ring of stairs on the cells just OUTSIDE the wall rectangle x1..x2/z1..z2 at height y, every stair
                 with its back to the wall (orientation handled for you). half=bottom: plinth skirt; half=top: eaves,
                 cornice, sill band. "skip" leaves gaps (e.g. in front of the door), "sides" limits the walls.
            {"op":"staircase","from":[x,y,z],"direction":"north","steps":4,"width":1,"block":"spruce_stairs","support":"spruce_planks","headroom":3}
                 walkable flight climbing toward direction from the lowest step; steps rise 1 per block; support
                 fills under the steps; headroom clears 3 cells above every step (cuts the hole in the floor above).
                 Prefer trim/staircase/roof over hand-placed stairs: they are always oriented correctly.
            {"op":"cylinder","center":[x,y,z],"radius":r,"height":h,"block":B,"hollow":true}   towers, wells
            {"op":"sphere","center":[x,y,z],"radius":r,"block":B,"hollow":true,"half":"top"}  domes, tree crowns
            {"op":"copy","from":[..],"to":[..],"offset":[dx,dy,dz],"mirror":"x"}  duplicate (mirror "x"/"z", rotate 90/180/270); states are mirrored correctly
            {"op":"repeat","offsets":[[dx,dy,dz],...],"ops":[...]}  execute a local-coordinate detail module at each offset.
                 Each offset is a translation, not a rotation. Use this for repeated framed windows, rafters,
                 furniture or lantern posts. Include [0,0,0] if the original module is needed. Child operations
                 read the actual model (clear/replace/copy work normally); all coordinates except copy.offset
                 are translated. At most 128 offsets, 4 nesting levels and 2048 expanded ops per repeat.
            Write the coarse shapes first (fill/hollow/walls/roof), then carve (clear), then details (set/line).
            Prefer exact "set"/"positions" for details. Never exceed the plot.

            # DESIGN PRINCIPLES
            - Design the requested object first. A house, bridge, sculpture, modern pavilion and castle have
              different forms and functions. Never turn a non-building object into a generic tower or cottage.
            - Resolve primary masses, roof/body proportions and recognizable silhouette before small details.
              Distinctiveness comes from a coherent idea: a purposeful offset wing, structural rhythm, courtyard,
              expressive roof or unusual section when it serves the request. Do not enlarge a small request for novelty.
            - Establish hierarchy: primary volume, subordinate parts, focal entrance or requested signature feature.
              Use repetition with deliberate variation; preserve quiet surfaces and negative space.
            - Give materials roles (structure, infill, roof, accent). Choose a cohesive palette, controlled contrast
              and meaningful texture transitions. There is NO minimum material count or decoration quota.
            - Depth, timber frames, shutters, planters, steep roofs, chimneys and gardens are style choices.
              Use them only when they fit. Restrained modern surfaces and flat roofs can be excellent architecture.
              Avoid random block noise, props added just to raise a score, and identical decoration on every facade.
            - Plan usable space before furnishing. Reserve entrance approaches, routes, stair landings and bed access.
              Furnish every required room for its function, at the requested scale; do not cram every available prop in.
            - Required walls and roofs close cleanly. Every required floor is reachable, passages have headroom,
              furniture is usable, attachments are supported, directional states are correct and interiors are lit.
              These building requirements do not imply rooms, windows, a roof or furniture for solid sculptures.
            - Connect the build to its site intentionally. Paths, landscape and secondary structures must support
              the requested design rather than distract from it. Beauty is not the count of blocks or details.
            """;

    // ------------------------------------------------------------------ task messages

    public static String compile(String request) {
        return """
                TASK: DESIGN AND BUILD ONE COMPLETE MINECRAFT STRUCTURE
                User request: "%s"

                Write a coherent executable construction program, with the SAME architectural quality as a
                fully staged build. Design and implement the entire build in this response. Do not spend output
                restating operations as prose or making a stage schedule: the executable ops ARE the plan.
                Preserve all requested features and exact dimensions. Choose a fitting size when unspecified;
                simple/small describes scope, not permission to omit detail. Do not add unnecessary storeys.
                Set floors at y=0, standing furniture and doors at y=1, or explicitly raise both consistently.
                For buildings, keep the entrance and interior circulation free, including the head of the bed. Keep beams out
                of windows and doorways. Close the roof with the roof op including fill, use trim for eaves,
                and staircase for upper floor access. Windows need frames, sills and accents; furnish and light
                every required room. Include chimneys, gardens and other surroundings only when appropriate.

                For a conventional house use the building op for its architectural envelope: specify its exact
                inclusive from/to, palette and roof. It provides the complete detailed shell in ONE operation.
                Choose style controls explicitly: do not accept cottage framing/shutters/planters/porch by habit.
                For non-conventional forms compose other primitives; the building op is a convenience, not a required form.
                Do not manually enumerate or repeat anything it already generates. Furnish its interior with
                your own compact ops, then add the chimney, path and distinctive landscape/architectural accents.
                Compile repetition: roof/trim/walls/outline for other geometry; repeat for multi-block motifs;
                set.positions for scattered identical blocks; copy with mirror for symmetry. Invest the saved
                tokens in composition and clean proportions. Each repeat is a small local-coordinate
                module, instantiated with explicit offsets; it can carve windows into walls already built.
                Different designs remain unrestricted: these are geometry tools, not a catalogue of houses.

                Return JSON:
                {"plan": {
                  "title":"short name in the user's language", "summary":"one sentence", "style":"...",
                  "type":"house|tower|castle|shop|bridge|statue|tree|ship|other",
                  "body":{"x":9,"z":9,"floors":1,"wall_top_y":4},
                  "plot":{"min":[x,y,z],"max":[x,y,z]}, "expect_interior":true,
                  "size_limits":{"x":0,"y":0,"z":0}, "exact_size":{"x":0,"y":0,"z":0},
                  "palette":{"foundation":"...","frame":"...","infill":"...","roof":"..."},
                  "design":{"massing":"primary and subordinate forms, with approximate proportions",
                    "rhythm":"opening/structural repetition or deliberate restraint",
                    "material_roles":"why the palette fits the requested style",
                    "circulation":"entrance to required usable spaces, or not applicable",
                    "identity":"one coherent distinctive decision, appropriate to the requested scope"},
                  "layout":[{"level":"ground floor","y":1,"height":4,
                    "rooms":[{"name":"...","area":"x1-x2,z1-z2","furnishing":"..."}]}],
                  "exterior":"...", "details":["distinctive features worth verifying, no duplicate prose"]
                }, "ops":[...all construction operations, in dependency order...]}
                plot must enclose ALL roofs, garden, paths, chimney and foundations. Keep notes brief.
                size_limits ONLY for user-specified maximum overall dimensions; exact_size ONLY for explicitly
                exact overall dimensions (zero means unconstrained). Dimensions are block counts, inclusive,
                in x/y/z order. A maximum 48x48 footprint and height 36 means size_limits x=48,y=36,z=48,
                not an exact 48x48 wall footprint. body.x/z describes exact main walls ONLY for buildings.
                Set type accurately and expect_interior=false for solid sculptures, bridges and terrain unless
                a usable interior was requested. Do not invent rooms, doors or roofs for those objects.
                Include the finished interior and exterior now; reviewers correct defects rather than finish it.
                Each operation must do new work. Never repeat identical ops or oscillate between materials.
                Prefer a compact complete program (usually 20-60 ops for a small house) over block enumeration.
                """.formatted(escape(request));
    }

    public static String plan(String request, String sizeHint) {
        return """
                TASK: PLAN (design brief for a new build)
                User request (any language; keep names in the user's language): "%s"
                %s
                Think like an architect: decide the exact dimensions, floors, rooms, palette and an ordered build
                schedule. Respect explicit sizes EXACTLY: "10x10" / "10 на 10" means the main body (outer walls) is
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
                 "size_limits": {"x":0,"y":0,"z":0}, "exact_size": {"x":0,"y":0,"z":0},
                 "palette": {"foundation": "...", "frame": "...", "infill": "...", "roof": "...", "floor": "...", "trim": "...", "accent": "..."},
                 "layout": [{"level": "ground floor", "y": 1, "height": 4, "rooms": [{"name": "...", "area": "x1-x2, z1-z2", "furnishing": "..."}]}],
                 "exterior": "what goes around the body",
                 "details": ["at least 25 concrete details with locations"],
                 "stages": [{"name": "...", "goal": "precise description incl. coordinates, blocks and orientations", "parallel": false}]
                }
                plot = full bounding box incl. roof overhang, chimney and yard decorations (y from the lowest
                footing to the highest point). size_limits records only USER-specified maximum overall x/y/z
                dimensions; exact_size records only USER-specified exact overall dimensions. Zero is unconstrained.
                Maximum dimensions must not become exact wall dimensions. body.x/z is a wall footprint only
                for buildings. Set type accurately; solid sculptures/bridges/terrain normally expect_interior=false.
                Do not invent habitable rooms, doors or roofs for objects that do not require them.
                Plan 4-6 stages bottom-up, e.g.: 1) foundation,
                plinth & floors; 2) walls, timber frame, windows, doors & facade details; 3) roof, eaves, gables,
                chimney, dormers; 4) ground floor interior; 5) upper floor / attic interior; 6) exterior landscaping.
                Do NOT plan a separate "final check/detailing" stage: three expert reviewers verify and polish the
                build afterwards.
                "parallel": true marks stages that only work inside their own clearly separated area (e.g. the
                interior of one floor, the yard outside the walls). Consecutive parallel stages are built at the
                same time by different builders who all see the model as it was before them, so give every
                parallel stage exact coordinate bounds and never let two parallel stages touch the same cells.
                Shell stages (foundation, walls, roof) are never parallel.
                Floors between storeys belong to the shell. Keep the brief compact (no prose padding).
                """.formatted(escape(request), sizeHint == null ? "" : sizeHint);
    }

    public static String stage(String request, String planJson, int index, int total, String stageName,
                               String stageGoal, String modelRender, String analysis, String parallelNote) {
        return """
                TASK: BUILD STAGE %d/%d — "%s"
                Original request: "%s"
                Stage goal: %s
                %s
                DESIGN BRIEF:
                %s

                CURRENT MODEL (everything built so far):
                %s

                AUTOMATED ANALYSIS OF CURRENT MODEL:
                %s

                Build THIS stage with maximum detail, consistent with the existing blocks and the brief (exact
                coordinates — read them from the layers above!). Fill the stage's area richly: every wall, corner and
                floor tile of it should get attention. Double-check every directional block against the orientation
                rules and that every attachable block has support. Return JSON:
                {"notes": "what you built (short)", "ops": [ ... ]}
                """.formatted(index, total, escape(stageName), escape(request), stageGoal,
                parallelNote == null ? "" : parallelNote + "\n", planJson,
                modelRender == null ? "(empty — nothing built yet)" : modelRender,
                analysis == null ? "(none)" : analysis);
    }

    public static String repair(String failedSummary) {
        return """
                TASK: REPAIR FAILED OPERATIONS
                Some of your operations failed and were skipped:
                %s
                Return JSON {"ops": [...]} containing corrected versions of ONLY the failed operations (valid block
                ids from the list, valid properties). Do not repeat operations that succeeded.
                """.formatted(failedSummary);
    }

    public static String review(String request, String planJson, int round, int rounds, String modelRender,
                                String analysis, String focus, int minScore) {
        return """
                TASK: REVIEW & FIX (self-verification round %d/%d)
                Original request: "%s"
                %s
                DESIGN BRIEF:
                %s

                CURRENT MODEL:
                %s

                AUTOMATED ANALYSIS (objective checks — every CRITICAL/MAJOR item MUST be fixed):
                %s

                You are now a strict senior reviewer. Inspect the model layer by layer and the elevations:
                1. request compliance (type, style, EXACT size of the main body, requested features);
                2. structure: closed walls and roof, no holes, no floating blocks, supports for attachables;
                3. orientation of every stairs/door/trapdoor/torch/bed/log (apply the rules literally);
                4. composition: massing, proportions, hierarchy, opening rhythm, style-appropriate materials and depth;
                5. roof/form: silhouette, clean junctions and closure where required, without imposing a roof style;
                6. interior: each room furnished for its function, lighting (no dark spots), reachable floors;
                7. exterior: relationship to the site; 8. coherence, craftsmanship and purposeful distinctiveness.
                Then write targeted fix operations for confirmed problems. Plain surfaces can be intentional;
                do not add unrelated detail. Use exact coordinates from the model. Return JSON:
                {"score": 0-100, "approved": true|false,
                 "issues": [{"severity": "critical|major|minor", "what": "...", "where": "x,y,z or area"}],
                 "summary": "one sentence",
                 "ops": [ ... fixes and improvements ... ]}
                approved = true only if (after your ops) the build fully satisfies the request with no critical or
                major issue left and score >= %s. Score honestly.
                """.formatted(round, rounds, escape(request), focus == null ? "" : focus, planJson, modelRender,
                analysis, Integer.toString(minScore));
    }

    /** Reviewer specialisations used for parallel self-verification. */
    public enum ReviewRole {
        STRUCTURE("STRUCTURE & CORRECTNESS reviewer", """
                Your area: the whole build, but ONLY correctness. Fix every automated CRITICAL/MAJOR item; check
                request compliance (EXACT main-body size, type, requested features), closed walls and roof (no
                holes), no floating blocks, every attachable block supported, and the orientation of EVERY stairs,
                door, trapdoor, wall torch, ladder, bed and log against the rules. Do not redecorate."""),
        EXTERIOR("EXTERIOR & FACADE reviewer", """
                Your area: everything visible from OUTSIDE — facades, windows, door surround, roof, eaves, gables,
                chimney, plinth, and the yard/garden/path when applicable. Check massing, proportions, hierarchy,
                silhouette, opening rhythm, material coherence and restrained detail suited to the request.
                Never impose timber framing, shutters, flowers or steep roofs on other styles. Only touch the outer
                face of the walls and cells outside the body — never the interior."""),
        INTERIOR("INTERIOR reviewer", """
                Your area: everything INSIDE the outer walls — every room furnished for its function, believable
                furniture made from blocks, floors/ceilings/beams, wall decor, lighting (no dark walkable cell),
                stairs/ladders to every floor with free head room, free cells in front of doors. Only touch cells
                inside the outer walls.""");

        public final String title;
        public final String instructions;

        ReviewRole(String title, String instructions) {
            this.title = title;
            this.instructions = instructions;
        }
    }

    /**
     * Specialised review prompt. The shared part (request, brief, model, analysis) comes first so the three
     * parallel reviewers share a long common prefix (provider prompt cache); the role comes last.
     */
    public static String reviewAs(ReviewRole role, String request, String planJson, int round, int rounds,
                                  String modelRender, String analysis, int minScore) {
        return """
                TASK: REVIEW & FIX (self-verification round %d/%d)
                Original request: "%s"
                DESIGN BRIEF:
                %s

                CURRENT MODEL:
                %s

                AUTOMATED ANALYSIS (objective checks):
                %s

                YOU ARE THE %s (two other specialists review the other aspects in parallel).
                %s
                Find confirmed problems in your area, then write precise fix and
                improvement operations (exact coordinates read from the model). Be decisive and concise in your
                reasoning. Return JSON:
                {"score": 0-100 (quality of YOUR observed area), "approved": true|false,
                 "issues": [{"severity": "critical|major|minor", "what": "...", "where": "x,y,z or area"}],
                 "summary": "one sentence", "ops": [ ... ]}
                approved = true only if your area has no critical/major problem left after your ops and its score
                is >= %d. Score honestly.
                """.formatted(round, rounds, escape(request), planJson, modelRender, analysis, role.title,
                role.instructions, minScore);
    }

    public static String edit(String request, String planJson, String history, String modelRender,
                              String analysis, String change) {
        return """
                TASK: EDIT AN EXISTING BUILD
                Original request: "%s"
                Previous changes: %s
                DESIGN BRIEF:
                %s

                CURRENT MODEL:
                %s

                AUTOMATED ANALYSIS:
                %s

                The user now asks for this change: "%s"

                Implement exactly this change with the same (maximum) level of detail and keep everything else
                intact. Remove things with "clear" (or fill with air), restyle with "replace", extend with new ops.
                Keep the result structurally sound (supports, closed roof, free doors, lighting).
                If (and only if) the change is a completely different building or a radical resize that makes the
                current model useless, return {"rebuild": true, "new_request": "full merged description of the new build"}.
                Otherwise return JSON:
                {"summary": "what was changed (in the user's language)", "plan_update": {optional changed brief fields},
                 "ops": [ ... ]}
                """.formatted(escape(request), history == null || history.isBlank() ? "none" : history, planJson,
                modelRender, analysis, escape(change));
    }

    // ------------------------------------------------------------------ planned edits

    public static String editPlan(String request, String planJson, String history, String modelRender,
                                  String analysis, String change) {
        return """
                TASK: PLAN A CHANGE TO AN EXISTING BUILD
                Original request: "%s"
                Previous changes: %s
                DESIGN BRIEF:
                %s

                CURRENT MODEL:
                %s

                AUTOMATED ANALYSIS:
                %s

                The user asks for this change: "%s"

                Think like the architect who designed the build. Decide WHAT changes and WHERE (which wall / floor /
                side, approximate coordinates and dimensions read from the model), the materials and style (match the
                existing build), how the new part is reached and used (doors/openings from the existing rooms,
                walkable depth >= 2, headroom, railings), and what must not be harmed (existing doors, windows, paths,
                light sources, supports). Do NOT pin every block: each step's builder sees the model and chooses exact
                coordinates; give them intent, location, size, materials and must-haves.
                Split the work into 1-5 ordered steps (a small change = 1 step; a new wing or floor = several steps:
                shell, roof, openings, interior, details). Steps marked "parallel": true run simultaneously and must
                touch disjoint areas.
                Acceptance criteria describe the RESULT the user would expect, as checkable quality/function statements
                (e.g. "the veranda is entered from the house through a door and its floor is level with the room",
                "no existing window or door is blocked", "the balcony is at least 2 deep with a railing on every open
                side") — never just a restatement of coordinates from your plan.
                If (and only if) the change is a completely different building or a radical resize that makes the
                current model useless, return {"rebuild": true, "new_request": "full merged description"}.
                Otherwise return JSON:
                {"summary": "what will change (in the user's language)",
                 "plan_update": {optional changed brief fields},
                 "area": {"min": [x,y,z], "max": [x,y,z]},
                 "steps": [{"name": "...", "goal": "precise instructions incl. coordinates, blocks, orientations", "parallel": false}],
                 "acceptance": ["concrete checkable criteria", "..."],
                 "preserve": ["parts that must stay exactly as they are", "..."]}
                """.formatted(escape(request), history == null || history.isBlank() ? "none" : history, planJson,
                modelRender, analysis, escape(change));
    }

    public static String editStep(String request, String change, String editPlanJson, int index, int total,
                                  String stepName, String stepGoal, String modelRender, String analysis, String parallelNote) {
        return """
                TASK: CHANGE STEP %d/%d — "%s"
                Original request of the build: "%s"
                The user's change request: "%s"
                Step goal: %s
                %s
                EDIT PLAN:
                %s

                CURRENT MODEL:
                %s

                AUTOMATED ANALYSIS:
                %s

                Implement THIS step precisely with the build's level of detail and style. Remove things with "clear",
                restyle with "replace", add with new ops. Do not touch anything outside the step's area unless the step
                says so. Double-check orientations and supports. Return JSON:
                {"notes": "what you did (short)", "ops": [ ... ]}
                """.formatted(index, total, escape(stepName), escape(request), escape(change), stepGoal,
                parallelNote == null ? "" : parallelNote + "\n", editPlanJson, modelRender, analysis);
    }

    // ------------------------------------------------------------------ adaptive verification

    public static String reviewAdaptive(String roleTitle, String roleInstructions, String request, String planJson,
                                        int round, String modelRender, String analysis, String checklist,
                                        String history, int minScore) {
        return """
                TASK: SELF-VERIFICATION, round %d — you decide when verification is complete
                Original request: "%s"
                DESIGN BRIEF:
                %s

                CURRENT MODEL:
                %s

                AUTOMATED ANALYSIS (objective checks):
                %s

                VERIFICATION CHECKLIST (OPEN = not verified yet, RECHECK = a problem was found/fixed and must be
                verified again, OK = verified):
                %s
                PREVIOUS ROUNDS:
                %s

                YOU ARE THE %s.
                %s
                Work through the OPEN and RECHECK checklist items of your area systematically and actually inspect them
                in the model (layers, elevations, coordinates). Report every inspected item in "checked": ok=true when it
                is verified correct, ok=false when you found a problem — then you MUST add ops that fix it (it will be
                re-checked next round). Fix every real problem you notice, also ones not on the list. Automated
                CRITICAL/MAJOR findings must be fixed or explicitly dismissed with a reason (false positive).
                Stay strictly inside your area; problems in other areas belong to the other experts (mention them in
                "issues" but do not fix them). If an automated finding persists after an earlier fix (see PREVIOUS
                ROUNDS), the fix did not work: try a different fix or dismiss it with a reason — never repeat it.
                YOU decide when verification is complete: "done": true when every checklist item of your area is
                verified OK and no critical or major problem remains in your area. If you fixed a critical/major
                problem this round, set "done": false so the fix gets verified.
                If the ONLY things left are minor cosmetic nits (a book turned the wrong way, a slightly odd stair
                hood, an extra prop) — fix the cheap ones, list the rest as "minor" issues and still set
                "done": true: the build does not need another round for those. Do not create busywork, do not
                redecorate what is already good, and never re-report a problem you already fixed.
                Review the actual request, not an ever-growing wish list. Do not invent additional requirements,
                new rooms or new decorations. Inspect every checklist item you own in this round; emit one short
                evidence note per item. A proposed correction must refer to a real defect at exact coordinates.
                If an earlier fix is already correct, confirm it; do not replace it with another design.
                For a checklist item you FIX now, you may attach exact postconditions in "proof":
                [{"pos":[x,y,z],"block":"oak_log[axis=z]"},
                 {"from":[x1,y1,z1],"to":[x2,y2,z2],"block":["cobblestone","mossy_cobblestone"]}].
                Each predicate checks the ACTUAL final block(s), including explicit state properties; a weighted
                list accepts any listed material (no weighted strings in proofs). Regions are inclusive, ordered and bounded. Use sufficient
                predicates to prove the WHOLE correction, not a token sample. Up to 32 predicates per item and
                20000 inspected cells total. The program will only turn ok=false into verified if every predicate
                passes after all patches and post-processing. Unknown or failed proofs require another round.
                Set "done_after_proofs":true ONLY if all remaining failed items have complete block-state proofs
                and you already verified everything else in your area. This can replace a redundant inspection
                round; critical/major automated checks must ALSO pass. Use proofs for exact local corrections
                (missing furnishings, plinth material, supports, clear passages); appearance/composition still
                needs your actual review. Never use a proof to dismiss an uninspected design concern.
                Return JSON:
                {"checked": [{"id": "C1", "ok": true, "note": "short evidence", "proof":[]}],
                 "issues": [{"severity": "critical|major|minor", "what": "...", "where": "..."}],
                 "dismissed": [{"code": "automated issue code", "reason": "why it is a false positive"}],
                 "ops": [ ... ], "score": 0-100, "summary": "one sentence", "done": true|false,
                 "done_after_proofs":true|false}
                Score = quality of your observed area now (>= %d means very good).
                """.formatted(round, escape(request), planJson, modelRender, analysis, checklist,
                history == null || history.isBlank() ? "none (first round)" : history, roleTitle, roleInstructions, minScore);
    }

    public static final String EDIT_REVIEWER_TITLE = "CHANGE REVIEWER";

    public static String reviewScope(boolean full) {
        return full ? """

                REVIEW SCOPE: FULL WHOLE-BUILD ASSESSMENT
                Inspect every checklist item you own, including previously OK items, against the current model.
                Check interactions between repaired and unchanged parts. Assess every quality criterion from the
                supplied full model. This is the assessment required before final approval. Repair real defects only.
                """ : """

                REVIEW SCOPE: FOCUSED REPAIR RECHECK
                Inspect OPEN/RECHECK items, the supplied repair regions and their connections to surrounding parts.
                Previously OK items remain provisional: only report them again if affected by these repairs.
                Do not re-describe the whole build or repeat unchanged checklist evidence. Keep notes and quality
                evidence concise and specific. Global views describe silhouette; they do not expose hidden cells.
                Only propose ops inside the supplied exact regions. If required evidence is missing, set
                "needs_full_review":true, explain what must be inspected, and do not guess or repair unseen cells.
                You may report done when your focused work is complete; code will still schedule a FULL assessment
                before approval. For each issue include "region":{"from":[x,y,z],"to":[x,y,z]} when known.
                """;
    }

    public static String qualityReview(String role) {
        return """

                EVIDENCE-BASED ACCEPTANCE CONTRACT
                You are an independent critic of the ACTUAL supplied blocks, not an advocate for the builder.
                The design brief expresses intent, not proof of execution. The original user request takes priority.
                Spatial evidence is computed but approximate: inspect coordinate layers before declaring a failure.
                detailScore, material counts and fixture counts are descriptive heuristics, never evidence of beauty.
                Diagnose the CURRENT model before proposing changes. Do not give credit for an imagined result of
                your ops. Repairs to composition or style require an observed recheck; exact local block corrections
                may use the existing proof mechanism. Preserve good work and deliberate restraint.

                Add a "quality" object with EACH of these criterion keys: %s.
                Each value is {"rating":0..4,"evidence":"specific observed parts, dimensions, coordinates or relationships"}.
                Anchors: 0=missing/unusable; 1=major defect; 2=adequate but a concrete significant weakness remains;
                3=coherent, well-proportioned and carefully executed for THIS request; 4=exceptional, distinctive
                execution with evidence beyond generic praise. Every criterion must be >=3 with evidence to pass.
                Fidelity=requested form, style, dimensions and features; soundness=geometry/support/orientation;
                composition=primary/secondary mass hierarchy, silhouette, proportions and opening rhythm;
                coherence=consistent architectural language, purposeful material roles, no discordant additions;
                craft=clean joints and deliberate details at the appropriate density, without clutter;
                usability=required routes, headroom, furnishings and functional parts (for a sculpture: recognizable
                complete parts and appropriate site access, not an invented interior).

                Follow the supplied review scope: full assessment covers every applicable item; focused rechecks
                cover pending and affected items without claiming to inspect unseen parts. Include a "check"
                checklist ID on each issue. Any unresolved critical/major issue blocks acceptance regardless of score.
                If you fix a serious issue, either recheck next round or supply sufficient passing block-state proofs
                on that SAME failed checklist item; a proof for an unrelated item cannot approve it.
                If a criterion is below 3, explain the real weakness and make a bounded, purposeful correction.
                Do not invent a defect to appear strict. Unknown/uncertain observations need inspection, not fabrication.
                """.formatted(String.join(", ", ReviewDecision.criteria(role)));
    }

    public static String editReviewerInstructions(String change) {
        return "Your area: the user's change request \"" + escape(change) + "\". Judge it like a demanding client, not "
                + "against the builder's own plan: is the user's intent fully met (every part of the request), is the new "
                + "part usable (reachable through a door/opening, walkable depth, headroom, railings), integrated with "
                + "the existing build, matching its style and level of detail, and did it damage anything (blocked "
                + "doors or windows, removed supports, floating blocks, broken orientations, dark spots)? Assume there "
                + "ARE problems and actively look for them in the model; fix every one you find.";
    }

    // ------------------------------------------------------------------ blind judge (benchmarks)

    /** Judge prompt for N builds: the caller passes "===== BUILD A ===== ... " blocks in {@code buildsBlock}. */
    public static String judge(String request, String change, String beforeRender, String buildsBlock) {
        boolean edit = change != null;
        return """
                TASK: BLIND EXPERT EVALUATION OF MINECRAFT BUILDS
                %s
                %s
                %s
                Evaluate every build independently and strictly (0-10 each criterion):
                fulfilment (%s), structure (closed shell, supports, nothing floating), orientation (stairs, slabs,
                doors, trapdoors, torches, logs), detail (richness and density), interior, exterior, beauty%s.
                Check the actual blocks — do not trust any self-description. Then pick the best build.
                Return JSON: {"A": {"fulfilment":0,"structure":0,"orientation":0,"detail":0,"interior":0,"exterior":0,"beauty":0%s,"total":0,"notes":"..."},
                 "B": {...same keys...}, [more letters if present], "winner": "A|B|C", "reason": "..."}
                total = 0-100 overall quality.
                """.formatted(edit ? "Original build request: \"" + escape(request) + "\"\nCHANGE REQUEST being evaluated: \""
                                + escape(change) + "\"" : "Request: \"" + escape(request) + "\"",
                edit ? "===== BUILD BEFORE THE CHANGE =====\n" + beforeRender : "", buildsBlock,
                edit ? "how completely and well the CHANGE was implemented" : "how well the request is fulfilled",
                edit ? ", preservation (unrelated parts kept intact)" : "", edit ? ",\"preservation\":0" : "");
    }

    public static String judge(String request, String change, String beforeRender, String renderA, String analysisA,
                               String renderB, String analysisB) {
        boolean edit = change != null;
        return """
                TASK: BLIND EXPERT EVALUATION OF TWO MINECRAFT BUILDS
                %s
                %s
                ===== BUILD A =====
                %s
                AUTOMATED ANALYSIS A:
                %s

                ===== BUILD B =====
                %s
                AUTOMATED ANALYSIS B:
                %s

                Evaluate A and B independently and strictly (0-10 each criterion):
                fulfilment (%s), structure (closed shell, supports, nothing floating), orientation (stairs, slabs,
                doors, trapdoors, torches, logs), detail (richness and density), interior, exterior, beauty%s.
                Check the actual blocks — do not trust any self-description. Then pick the better build.
                Return JSON:
                {"A": {"fulfilment":0,"structure":0,"orientation":0,"detail":0,"interior":0,"exterior":0,"beauty":0%s,"total":0,"notes":"..."},
                 "B": {...same keys...}, "winner": "A|B|tie", "reason": "..."}
                total = 0-100 overall quality.
                """.formatted(edit ? "Original build request: \"" + escape(request) + "\"\nCHANGE REQUEST being evaluated: \""
                                + escape(change) + "\"" : "Request: \"" + escape(request) + "\"",
                edit ? "===== BUILD BEFORE THE CHANGE =====\n" + beforeRender : "",
                renderA, analysisA, renderB, analysisB,
                edit ? "how completely and well the CHANGE was implemented" : "how well the request is fulfilled",
                edit ? ", preservation (unrelated parts kept intact)" : "",
                edit ? ",\"preservation\":0" : "");
    }

    static String escape(String s) {
        return s == null ? "" : s.replace("\"", "'").replace("\n", " ").trim();
    }

    /** Where the enhanced brief starts inside a request string; the user's own words come before it. */
    static final String BRIEF_MARK = "\n\u0001brief\u0001\n";

    /** The user's request with the enhancer's brief attached, or the request alone when there is no brief. */
    public static String withBrief(String request, String brief) {
        return brief == null || brief.isBlank() ? request : request + BRIEF_MARK + brief.strip();
    }

    /** The user's own words of a request that may carry a brief. */
    public static String userWords(String request) {
        if (request == null) return "";
        int at = request.indexOf(BRIEF_MARK);
        return at < 0 ? request : request.substring(0, at);
    }

    /**
     * The request line of a prompt: the user's words in quotes, then the brief when there is one. The quoted
     * words stay first and verbatim, so a brief that drifted from them is visibly the secondary source.
     */
    static String request(String label, String request) {
        String line = label + ": \"" + escape(userWords(request)) + "\"";
        int at = request == null ? -1 : request.indexOf(BRIEF_MARK);
        if (at < 0) return line;
        return line + "\n\nREQUEST BRIEF (the request above, worked out by the lead designer: REQUIRED restates what it asks,"
                + "\nSUGGESTED fills in what it leaves open and may be varied; where the brief and the request differ, the"
                + "\nrequest wins):\n" + request.substring(at + BRIEF_MARK.length());
    }
}
