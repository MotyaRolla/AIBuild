# Generation performance

Measured locally on Paper 26.3, October 31, 2026. All reported runs below use
`deepseek/deepseek-v4.1-flash` (latest). The server defaults to **standard**.

The request was: “A simple small oak and cobblestone house with a gabled roof,
windows, a door, a furnished interior with a bed, crafting table and chest,
warm lighting, and a short entrance path.”

| Pipeline / mode | Time to committed world | AI calls | Critical / major findings | Blocks |
|---|---:|---:|---:|---:|
| Original staged / fast, diagnostic baseline | 183.74 s | 27 | 0 / 2 | 1,119 |
| Compiled / standard, initial audit protocol | 319.25 s | 3 | 0 / 0 | 888 |
| Compiled / standard, block-condition proofs | 211.65 s | 3 | 0 / 0 | 934 |
| Compiled / extra, three specialists | 476.00 s | 5 | 0 / 0 | 1,020 |

These are individual observations, not a statistically controlled comparison.
The original standard/extra pipelines were not rerun; the user's approximately
40-minute extra result is a reported reference, not a newly measured baseline.
The last standard run overlapped the extra run. The two standard houses have
different layouts and details. Analyzer findings do not certify aesthetic quality;
AI self-scores are also not independent quality measurements.

## What changed

Creation now uses one architectural program instead of a plan call followed by
four to six dependent construction calls. The mode's construction reasoning is
retained: low for standard, high for extra. `generation.create-strategy: staged`
restores the previous construction path.

The local compiler provides two additional operations:

* `building`: a configurable rectangular room envelope with foundation, visible
  stone plinth, timber framing, windows, shutters, sills, porch, door clearance,
  ceiling lighting, and closed roof. It accepts independent material and roof
  choices and translated bounds. Its insulated roof core has no inaccessible
  hollow attic. Furniture and distinctive details remain AI-designed. Multiple
  envelopes and ordinary operations can form wings or custom architecture.
* `repeat`: bounded instancing of a local-coordinate module. Offsets compose;
  geometry operations are translated, while a copy operation's relative offset
  stays relative. Expansion is bounded before execution. Existing plot, operation
  volume and model limits still apply.

This follows the useful part of [Blender's geometry instancing](https://docs.blender.org/manual/en/latest/modeling/geometry_nodes/instances.html)
and [Minecraft box-split grammars](https://slothlab.info/assets/pdf/eger2022fdg.pdf):
the model chooses architecture and parameters; local code expands precise
geometry. [MCP for Blender](https://github.com/ahujasid/mcp-for-blender) also uses
code execution and visual inspection. No Blender dependency was added.
Voxel diffusion, such as [Dream-Cubed](https://pub.sakana.ai/dream-cubed/), is a
related longer-term option, but it is not the implementation used here.

Standard uses one coherent reviewer covering structure, exterior and interior.
Extra retains three parallel specialists. Both use adaptive checklists and local
analysis. Standard follow-up reviews now retain thinking. Adaptive reviews honor
the follow-up effort setting; they previously reused the initial effort every round.
Neither standard nor extra silently retries with thinking disabled.

Review changes are computed against the shared snapshot and merged only where
the original block is still current. Conflicting stale writes are skipped and
rechecked. Shared checklist details have one owner instead of being overwritten
by all three reviewers. Coordinate-run renders replace verbose character grids.

Reviewers can attach explicit block-state predicates to a correction. The plugin
checks those predicates against the resulting model, including requested default
state properties, before certifying the correction. An invalid proof stays open.
Global structural checks and the quality threshold still apply. A later round is
required when the correction cannot be demonstrated locally. This is a local
verification mechanism, not a proof of visual beauty.

Streams repeating the same operation six times in a rolling window are aborted
and retried instead of consuming the full token budget. Nested module children
at different offsets do not count as such a loop. Truncated programs are rejected.
Aborted streams may not report token usage; metrics identify those missing counters.

## World validation and progress

Fire and lava damaged the extra preview during testing. Two lava blocks and 46
fire blocks were removed. Fire spread was disabled on the test servers using
`gamerule minecraft:fire_spread_radius_around_player 0`. The saved generated
programs contained no lava. The standard house was restored and the extra
commit reapplied its generated model.

After flushing the world, direct Anvil-file comparisons found all **888/888**
initial standard demo blocks, **934/934** latest standard blocks, and
**1020/1020** extra blocks in their expected states.
The checks include rotation and directional properties. The world reader handles
the 26.3 dimension paths and compact palette encoding; runtime commands separately
confirmed the implicit north-facing stair and campfire states.

Console-created jobs now show native boss bars to online operators and send their
stage messages to those operators. Readable chat status updates are sent on stage
changes and at least every 30 seconds during long AI calls. Player-owned jobs
remain visible to their owner. The full status remains available with `/aib status`.

Use `tools/benchmark_local.py <server> <build-id>... --output results.json` to
summarize transcripts and metrics without another API call. Use
`tools/verify_world.py <server> <build-id> --output world-check.json` after
`save-all flush` to compare a placed build with its saved world blocks. These
helpers read local data and do not read the API key.

Raw measurements and renders from this session are in the ignored local directory
`bench/fast-house-20261031/`; full transcripts remain in each server's plugin logs.

The installed server on port 25565 contains all finished demos. Latest standard:
`/aib tp 24c23e`, entrance `(308,173,12)`; extra: `/aib tp e763a8`, entrance
`(258,173,11)`. The temporary benchmark server on port 25566 was stopped.

Validation passed: 15 JUnit tests plus the existing operation, orientation,
large-build, analyzer, mode, checklist, world-math, placement, and streaming-client
self-check suites. The installed jar, Maven output, and repository jar have the
same SHA-256: `efe6a3ca76a84fc91b2b45d13f7649055bbfb10d014399a368e85127389bf9f4`.

## Limits

The `building` primitive is a room/storey envelope, not a complete generator for
every possible building. Complex floors and non-rectangular silhouettes still
need composed envelopes or ordinary operations. Exact predicates validate block
states, not whether a self-chosen predicate fully expresses the user's intent;
the thinking reviewer remains responsible for that judgement. Reasoning latency
still dominates these runs, so timings vary by request and provider load.
