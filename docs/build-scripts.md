# Build scripts (2.0): the AI writes a program, not a list of boxes

## Why

Earlier measurements and renders point at the tool, not the model, as the limit on quality.
The 1.x language was 16 JSON operations:
- boxes, lines, radius-only spheres and cylinders, five roof styles;
- 90° copies, a translation-only `repeat`, and a one-room `building` macro.

The model had to write every coordinate by hand. It had no math, loops, functions, curves, any-angle transforms,
smooth surfaces, or material gradients. The renders show the result: visible primitives, crude sculptures,
small builds, and similar houses.

The original staged pipeline (origin/master) looked better because it made about 5× more construction calls
with "maximum detail" prompts. It used the same 16 operations. 2.0 keeps the cheaper compiled pipeline
(ac82139) as its base and raises the ceiling of what one program can express.

The closest public evidence is MineBench, where LLM voxel builds look best. There, models write **JavaScript**
(loops, math, functions) over a few primitives, and the prompt asks for sculpted forms, hidden primitives, and
scene composition.

## What changed

| Area | 1.x (compiled JSON ops) | 2.0 (`generation.create-strategy: script`) |
|---|---|---|
| Language | 16 JSON ops, explicit coordinates | sandboxed JavaScript (Rhino 1.9.1): variables, loops, functions, math, seeded random |
| Shapes | box, line, sphere, cylinder | signed-distance solids:<br>• sphere, ellipsoid, rounded box, cylinder, cone, capsule, torus<br>• revolve (lathe), extrude (polygon prism)<br>• tube along a Catmull-Rom or Bézier curve with variable radius<br>• loft, union / subtract / intersect / smooth blend<br>• any-angle rotate / scale / mirror, noise warp |
| Surfaces | whole blocks | `smooth:true` terraces sloped tops and undersides with matching stairs and slabs (domes, roofs, hulls, creatures, hills) |
| Materials | white-noise mixes | coherent noise patches, dithered gradients, by-normal (top/side/bottom), mossy/cracked weathering, crease trim |
| Kits | one `building` macro | readable JS helpers the model may use or ignore:<br>• architecture: roofs incl. dome/cone/spire/onion, windows, doors, arches, columns, battlements, timber framing, stairs, balconies, chimneys<br>• interior and furniture<br>• nature and scene: trees, rocks, hills, paths, flowers, water |
| Structure | flat op list | `part('name', fn)` groups, `withT`/`sym`/`around` frames; block states rotate and mirror correctly |
| Failure handling | one repair call for failed ops | traceback repair (≤ 3 calls) with the failing line, the error, and "did you mean" suggestions, then fallback to the 1.x program |
| Candidates | one program | `script.candidates` independent scripts in parallel (standard 2, extra 3); fewest analyzer defects, then the vision judge |
| Review | op patches + self-score ≥ 86 | critic rounds that see a **render**, the analysis and the numbered script, and return SEARCH/REPLACE edits to the script |
| Acceptance | self-score gate | a revision is kept only if:<br>• it runs<br>• it adds no critical/major analyzer findings<br>• it keeps ≥ 50% of the blocks<br>• the pairwise judge (both orders; disagreement = tie) does not prefer the old build<br><br>Approval = no critical/major findings. |
| Edits | op patches on the voxel model | `/aib edit` changes the stored script (when the version's script reproduces its model), falling back to the 1.x edit pipeline |
| Live view | the build appears after the whole program ran | the first candidate's script runs while it streams; each completed top-level statement (usually a `part`) updates the in-world preview, and the status line names the part being written. No extra tokens |
| Renders | guessed colours | colours derived once from the vanilla client textures (`render.textures`), used by `/aib render`, the critic and the judge |

The script is stored on each version (`script`, `seed`, `stdlibVersion`, `scriptExact`). The voxel model is still
what placement and undo use.

## Files

- `plugin/src/main/java/dev/aibuild/script/`:
  - runtime: `ScriptRuntime` (sandbox and host API), `Canvas` (writes, frames, parts, limits)
  - geometry and materials: `Rasterizer` (sampling and smoothing), `Material`, `Noise`
  - script handling: `ScriptLint`, `ScriptPatch`, `Suggest`
  - geometry package: `geom/Shape`, `geom/Frame`
- `plugin/src/main/resources/stdlib/`:
  - `prelude.js`: the API the model calls, plain JS
  - `api-card.md`: the reference shown to the model
  - `examples/`: three short scripts shown to the model
- `plugin/src/main/java/dev/aibuild/pipeline/ScriptPrompts.java`: the create, repair, critic, edit and judge prompts.
- `BuildPipeline`: `designAndScript`, `writeScript`, `pickCandidate`, `verifyScript`, `editScript`, `judge`, `vision`.
- `plugin/src/main/java/dev/aibuild/render/`: `TextureCache` (client-jar colours), `ContactSheet` (images for the model).
- `ai/ChatContent`: image parts for both transports, ported from the threejs branch.
- `model/BlockFamilies`: block → stairs/slab/wall/fence and weathered variants.

## Sandbox and limits

- **Rhino setup:** interpreted mode, `initSafeStandardObjects`, a class shutter that denies every Java class,
  no `Date`, and a seeded `Math.random`.
- **Host interface:** host functions exchange only numbers, strings and plain objects. Shapes and materials are
  integer handles.
- **Budgets:** an instruction observer enforces instruction, wall-clock, allocation and cancellation budgets.
  Writes are clipped to the plot, and the model is capped in cells (standard 900k, extra 1.4M).
- **Dialect:** Rhino rejects `class` and spread arguments in calls; both get a one-line fix in the error. `ScriptRuntimeTest` pins this behaviour.
- **Packaging:** the jar shades Rhino to `dev.aibuild.lib.mozilla`. Rhino looks up its RegExp engine with
  `ServiceLoader` on the thread context class loader, which under Paper cannot see the plugin jar, so the
  runtime installs `RegExpImpl` on every context itself (`RegExpClassLoaderTest` reproduces the server case).
- **`const`:** Rhino's `const` is function-scoped and assigned once (inside a loop it keeps its first value), so
  every `const` declaration is rewritten to `let` before running.

## Verified offline (this revision)

- 178 JUnit tests pass, including:
  - sandbox escapes (`java`, `Packages`, constructor tricks, `Date`), runaway loops, cell caps and deep recursion;
  - determinism per seed, dialect hints, and frame and mirror state rotation;
  - part ownership, plot clipping, and every kit;
  - the three prompt examples running without warnings;
  - SEARCH/REPLACE application, texture-colour resolution, and judge-answer parsing.
- The smoother puts inward-facing bottom stairs on a dome (no outward-facing stairs in the test). The three
  examples render as a recognizable cottage, fox and arch bridge.
- The shaded jar runs scripts (regex included) and keeps the sandbox closed.
- The real Mojang download path ran once: 1,250 block colours were derived from the 26.3 client, and the jar was deleted afterwards.

## Not yet measured

- **No live generation has been run against DeepSeek in this revision.** Whether the model uses the new
  API well, how often first scripts fail, the token and time cost, and above all whether people prefer the
  results are all unmeasured. Expected cost for standard is about 2.5–3× the 1.x compiled pipeline:
  2 candidates, up to 2 critic rounds, and 2 judge calls per comparison.
- **Vision is still unconfirmed.** The vision judge and image-conditioned critic depend on the gateway
  delivering images. `vision()` checks this once per session with a rendered number and turns visual checks off
  if the answer is wrong.
- **The judge is not calibrated against people.** It is a same-model judge. Until it agrees with human ballots
  on most pairs, treat its choices as a heuristic.

## How to measure

1. Install `plugin/target/AIBuild.jar` (built with `mvn package`) and restart the server.
2. Run each prompt in `tools/bench_suite.txt` as `/aib bench create --mode standard <prompt>`, ideally three
   times each. Every run writes the following to `plugins/AIBuild/bench/<id>/`:
   - both models;
   - `strategy-compiled.png` and `strategy-script.png`, with real colours;
   - `bench.json` (time, calls, tokens, analyzer findings);
   - `report.md`.
3. Build the ballot: `python3 tools/blind_ballot.py make <bench dirs…> --output bench/ballot-<date>`.
   Open `ballot.html` and vote without looking at the mapping, then export the votes.
4. Tally: `python3 tools/blind_ballot.py tally bench/ballot-<date> votes.json`. This gives wins and an exact sign
   test. About 30 decided pairs are needed before a preference means anything.

Set `generation.create-strategy: compiled` to return to the 1.x behaviour at any time.
