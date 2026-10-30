# AIBuild — AI structure generation & editing plugin (design)

Target: Purpur/Paper **26.3** (Java 25), plugin `AIBuild`, package root `dev.aibuild`.
LLM: **DeepSeek V4.1 Flash** (`deepseek/deepseek-v4.1-flash`) through the **Command Code** gateway
(`https://api.commandcode.ai/provider/v1/chat/completions`, OpenAI-compatible, `Authorization: Bearer user_…`;
fallback transport `/alpha/generate` for Go-plan keys).

## 1. User experience

```
/aib create <prompt>      generate a new build in front of the player (any language prompt)
/aib edit <prompt>        change the active build ("add a chimney", "make the roof dark oak", "2 floors")
/aib chat                 toggle chat mode: every chat line = edit request for the active build (or create)
/aib undo | redo          step through build versions (world is diff-updated)
/aib list | select [id|look] | info | tp [id]
/aib remove [id]          restore the original terrain
/aib cancel               stop the running AI job
/aib render [id]          isometric PNG preview -> plugins/AIBuild/renders/
/aib wand | pos1 | pos2 | import [name]   adopt an existing (hand-made) structure for AI editing
/aib status | reload
Console: /aib create --at <world> <x> <y> <z> <facing> <prompt>   (testing without a player)
```
Progress is shown in a boss bar; when a job finishes the player gets a summary with clickable
[Edit] [Undo] [Render] buttons. The build is placed live after each stage ("watch it being built").

## 2. Pipeline (async, `pipeline.BuildPipeline`)

1. **Plan** – LLM → JSON design brief (title, style, exact dimensions, palette, floors/rooms, detail checklist,
   ordered list of 3–7 build *stages*).
2. **Build stages** – for each stage the LLM receives: DSL reference, plan, the current model rendered as
   text (layers + elevations + legend) and the automatic analysis; returns JSON `{"ops":[…]}`.
   Ops are executed into the in-memory `VoxelModel` (`model.ops.OpExecutor`), then `ShapeResolver`
   fixes connectivity/multi-block parts. Op errors (unknown block, bad property…) are fed back once for repair.
   Stages marked `"parallel": true` by the planner (interiors of separate floors, yard) are generated
   concurrently against the same snapshot and applied in order.
3. **Verify loop** (up to 3 rounds) – `StructureAnalyzer` computes objective checks (floating blocks,
   unsupported torches/ladders incl. sturdy-face test, dark interior spots, blocked doors, roof holes, vertical
   access, main-body footprint vs. request, monotonous/flat facades, …). Round 1: three specialist reviewers
   run in parallel — STRUCTURE (correctness/orientation), EXTERIOR (facades/roof/yard), INTERIOR (rooms/light)
   — each returning `{"score","approved","issues","ops"}`; later rounds are fast fix-up passes (reasoning off).
   Stops when all reviewers approve (score ≥ min-score) and the analyzer has no critical/major issue.
   Edits use one change-focused reviewer.

Reasoning effort defaults (measured on the 10×10 medieval house test): plan `off` (~25 s),
build stages `low` (0.5–3 min each), first review `low` (~5 min, 3 in parallel), follow-ups `off`.
"high" spent all 60k tokens thinking on the plan alone, so it is not the default.
4. **Place** – `world.WorldPlacer` diff-places the final model (batched per tick, physics off, ordered:
   structure first, attachables last) and records the original terrain for undo.

**Edit** = same loop starting from the current model: editor LLM returns ops (or `"rebuild":true` for
radical changes → full pipeline with merged prompt), then verify loop, then diff-placement as a new version.

## 3. Model space & semantics (`model.VoxelModel`)

* +x = east, +y = up, +z = south. **Front / main entrance faces SOUTH (+z)**.
* y = 0 is the ground surface layer (floors, foundations, paths replace terrain there); walls start at y = 1.
* Unset cells: y ≤ 0 → natural terrain (solid ground); y ≥ 1 → air. Explicit `air` cells force air.
* Placement clears (to air) all unset cells inside the model bounds with y ≥ 1.

## 4. Build DSL (JSON ops) — executed by `model.ops.OpExecutor`

Coordinates are integer arrays `[x,y,z]`; `from`/`to` are inclusive corners in any order.
`block` is a **pattern**: a block state (`"oak_planks"`, `"minecraft:spruce_stairs[facing=east,half=top]"`)
or a weighted mix in WorldEdit syntax `"60%cobblestone,25%mossy_cobblestone,15%andesite"`
(also `"cobblestone,andesite"` = equal weights, or JSON object `{"cobblestone":60,"andesite":40}`).
Mix choice is deterministic per position (hash of x,y,z and pattern).

| op | fields | effect |
|---|---|---|
| `fill` | from,to,block | solid box |
| `clear` | from,to | box of air |
| `walls` | from,to,block | 4 vertical side faces of the box (no floor/ceiling) |
| `hollow` | from,to,block | 6-face shell; interior set to air |
| `outline` | from,to,block | 12 edges only (timber frames). Axis blocks (logs) auto-orient along each edge unless axis given |
| `set` | pos:[x,y,z] **or** positions:[[x,y,z],…], block | individual blocks |
| `line` | from,to,block | 3D Bresenham line; axis blocks auto-orient along dominant direction |
| `replace` | [from,to], match, block | replace blocks whose name matches (`"oak_planks"`, list, or glob `"*_planks"`); if match contains `[props]` they must also match; region optional (default whole model) |
| `roof` | from,to,style,stairs,[slab],[fill],[overhang],[ridge] | generated roof (see below) |
| `cylinder` | center,radius,height,block,[hollow],[axis] | vertical (default) cylinder; disc test dx²+dz² < (r+0.5)² ; hollow = 1-thick shell |
| `sphere` | center,radius,block,[hollow],[half:"top"/"bottom"] | sphere / dome |
| `copy` | from,to,offset,[mirror:"x"/"z"],[rotate:0/90/180/270] | copy set cells of region (states mirrored/rotated correctly) |

Unknown fields are ignored (e.g. `"note"`). Ops continue after errors; every error is reported with the op index.

**Roof op**: `from`/`to` give the x/z rectangle of the walls' top and `y` of the first roof layer (`from[1]`).
The rectangle is expanded by `overhang` (default 1) on every side.
* `gable` (ridge along `ridge` axis, default the longer side): layer k at y=base+k: two rows of `stairs`
  facing toward the ridge, rows move inward each layer; odd width → ridge row of `slab` (or `fill`);
  gable end triangles at the *walls'* x (or z) planes are filled with `fill` below the stairs (if `fill` given).
* `hip` / `pyramid`: rings shrinking each layer, stairs face inward, corners resolved by stair shapes; last
  line/point capped with slab/fill.
* `shed`: single slope ascending toward `facing` (north/east/south/west).
* `flat`: one layer of `slab` (or `fill`) over the expanded rectangle.

## 5. Block-state conventions (taught to the LLM; enforced/auto-fixed by `OrientationFixer` + `ShapeResolver`)

Measured on three generated houses: 23–60 stairs per house had `facing` inverted (skirts, eaves, sills,
chairs facing away from their wall); roof slopes built with the `roof` op were correct. Countermeasures:
explicit cookbook in the system prompt, orientation-by-construction ops (`roof`, `trim`, `staircase`), a
context fixer (slope → uphill, wall-backed → back to wall incl. row consensus, chairs → seat to table, hanging
slab → top, gapped slab → bottom) whose corrections are fed back to the model, and orientation glyphs in the
text render (`^ > v <` / `n e s w` for stairs, `_ ~ =` for slabs) with fixed 2-char cell width.


* Stairs `facing` = direction of the tall back side (ascending direction). Roof stairs face toward the ridge.
  `half=top` = upside-down (eaves, window sills, furniture). `shape` is computed automatically.
* Wall-mounted blocks (`wall_torch`, `*_wall_sign`, `ladder`, `*_wall_banner`, buttons/levers with `face=wall`,
  open trapdoors used as shutters): `facing` points AWAY from the supporting block.
* Logs/pillars: `axis=x|y|z`.  Slabs: `type=bottom|top|double`.  Lantern on ceiling: `lantern[hanging=true]`.
* Auto-completion: door upper halves, bed heads (`facing` = foot→head direction), tall plant upper halves,
  fence/pane/bars/wall connections, stair corner shapes, leaves `persistent=true`.
* Renamed blocks are aliased: `chain→iron_chain`, `grass→short_grass`, `grass_path→dirt_path`.

## 6. Packages / ownership

| package | classes | owner |
|---|---|---|
| `model` | Dir, BlockSpec, BlockInfo, VoxelModel, StubBlockInfo (done) | architect |
| `model` + `model.ops` | BlockPattern, OpExecutor, OpResult, RoofBuilder, ShapeResolver | agent B |
| `analysis` | StructureAnalyzer, AnalysisReport, Issue, TextRenderer, IsoRenderer | agent C |
| `ai` | ChatMessage, ChatRequest, ChatResult, StreamListener, LlmClient, LlmException, OpenAiCompatClient, CommandCodeGenerateClient, LlmRouter | agent A |
| `build` + `world` | Build, BuildVersion, BuildStore, WorldPlacer, PlaceResult, PlacementMath | agent D |
| root, `bukkit`, `pipeline`, `command`, `config`, `util` | plugin main, BukkitBlockInfo, Prompts, BuildPipeline, commands, config | architect |

Compile check for any module (no Maven needed, avoid touching other agents' files):
```
cd /root/ai-build/plugin
javac -d /root/ai-build/.build/<yourname> -cp "$(cat cp.txt)" $(find src/main/java/dev/aibuild/model -maxdepth 1 -name '*.java') <your files>
```
