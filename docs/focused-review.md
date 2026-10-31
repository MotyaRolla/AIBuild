# Object validation and focused review (1.2.0)

This revision implements the first two follow-ups from the dragon comparison:
object-aware validation with useful draft diagnostics, and smaller intermediate
repair contexts. It retains DeepSeek V4.1 Flash, Standard/Extra reasoning settings,
the block DSL and the existing Java/Paper dependencies.

## Validation

`body.x/z` remains the main wall footprint for buildings. Statues, sculptures,
bridges, trees, terrain and ships do not acquire a rectangular-wall requirement
from that field. Recognized open object types default to `expect_interior=false`;
an explicitly requested usable interior can still enable interior checks.

Plans may record two independent overall size contracts:

```json
{"size_limits":{"x":48,"y":36,"z":48}, "exact_size":{"x":0,"y":0,"z":0}}
```

These measure the inclusive non-air bounding box, including the base and projecting
parts. Zero/absent axes are unconstrained. `size_limits` is a maximum, not a target;
`exact_size` is for explicitly exact overall dimensions. Building wall dimensions
continue to use the existing footprint check. The model extracts these contracts
from the request; code validates the geometry against them. This is not a general
natural-language dimension parser or a guarantee that the model captures every constraint.

Dark cavities, roof exposure and missing stairs do not impose habitable-building
requirements when `expect_interior=false`. Connectivity, gravity, attachments,
liquid safety and explicit size limits still apply. Open functional structures can
still need routes and lighting specified by the request and checked by the reviewer.

Every blocking final analyzer issue now appears in draft `blockers`, metrics and
the chat rejection reason with its code and description. A completed AI checklist
cannot hide a hard analyzer rejection. Existing saved approval flags are preserved.

## Review scheduling

The default adaptive path begins with a full-model inspection. Intermediate
rounds can use whole-model spatial summaries and exterior views plus exact block
layers around changed cells and issue locations. Two blocks of surrounding context
are included; overlapping regions merge. Global analysis is recomputed each round.

Focused context is used only when it saves at least 20% of the model-render text.
More than 1,024 changed cells, more than eight regions, more than 24,000 region
cells, an unlocalized blocking analyzer issue or missing region evidence causes a
full-model fallback. A reviewer can also request `needs_full_review=true`.

Focused patches outside supplied regions are rejected and trigger a full inspection.
The first round, final allowed round and at least every third repair inspection
use the full model. Once the checklist is complete, the next inspection is full.
Focused completion alone cannot approve a build. Final acceptance requires a full
assessment of the current model, fresh passing results for every owned checklist
item, passing quality criteria and no blocking analyzer finding. Any actual geometry
change invalidates the previous final assessment, including in Extra's parallel
reviews. The legacy fixed path also requires an unchanged final review round.

A full inspection that makes no changes while acceptance still fails stops as a
draft instead of repeating identical evidence. The existing round cap bounds all
reviews; the final sweep is not an unbounded extra call. Depending on repairs,
requiring fresh final assessment can add a call relative to 1.1.0. Smaller context
does not by itself establish lower end-to-end latency or better aesthetics.

Metrics identify `focused-review-v2`, contain the analyzer issues and a
`reviewContexts` array with scope, character counts, fallback reason, actual changed
cells and whether the current whole build was assessed. Boss bars distinguish full
inspection from repair rechecks; chat reports when full assessment remains pending.

## Verification and measurements

38 JUnit tests and the existing operation, orientation, large-build, analysis,
mode, checklist, coordinate, placement and offline transport checks pass.
Eleven controlled scenarios passed on isolated Paper 26.3:

- A sculpture passes without building walls; a sculpture above its height limit fails.
- Repair inspection follows full → focused → full before approval.
- An out-of-region patch is rejected without placing its unwanted blocks.
- A final response omitting earlier checklist items remains a draft.
- Changes during a nominal final review require another full inspection.
- A focused reviewer can request missing whole-model evidence.
- Extra's three reviewers work for unchanged models and recheck after a repair.
- The legacy fixed path rechecks repairs and rejects failing quality criteria.

These fixtures use local HTTP responses, not a paid model or aesthetic judgment.
Their fake usage counters and elapsed times are not generation benchmarks.
Local evidence is retained in `bench/focused-review-20261031/`.

Replaying the recorded dragon operations reproduces all **7,118** saved blocks
with **zero** differing cells. Its old analyzer blocks on `footprint_mismatch`;
the new object-aware analyzer reports no blocking issue. The saved benchmark
record is not retroactively approved or edited.

For those same recorded responses, potential model-context sizes are:

| Review | Full model context | Selected context | Scope |
|---|---:|---:|---|
| 1 | 66,714 characters | 66,714 | Full |
| 2 | 66,101 characters | 41,722 | Focused |
| 3 | 66,285 characters | 66,285 | Full |

Both existing repair patches fit their selected regions. Round 2 uses **36.9%**
less model-context text; across these three contexts the reduction is **12.2%**.
These are characters, not measured tokens, reasoning savings or elapsed-time gains.
The model has not generated fresh responses to these smaller contexts in this replay.
No new end-to-end speed or quality percentage is claimed.

To replay locally retained Standard compile/review responses with a Java 25 SDK:

```bash
cd plugin
mvn package dependency:build-classpath -Dmdep.outputFile=target/classpath.txt
cd ..
mkdir -p .scratch/review-replay
AIBUILD_CP="plugin/target/classes:$(cat plugin/target/classpath.txt)"
javac -cp "$AIBUILD_CP" -d .scratch/review-replay tools/ReviewReplay.java
java -cp ".scratch/review-replay:$AIBUILD_CP" dev.aibuild.pipeline.ReviewReplay \
  bench/dragon-comparison-20261031/our bench/focused-review-20261031/dragon-replay.json
```

The benchmark directory is local and ignored by Git. The replay utility expects a
single Standard compile response followed by its sequential reviewer responses and
the saved `build.json.gz`; it does not simulate parallel reviewer merges or new AI decisions.

Named components/anchors, adaptive construction decomposition, model-directed
inspection tools, furnishing constraints and competing design concepts remain
subsequent experiments. No Three.js implementation was merged.

## Installed server

The main server is running 1.2.0. Its configuration and all 13 pre-existing build
records match the before-installation snapshots. The repository, Maven and installed
jars share SHA256 `28b3da91242f1c719d1530a95cea752be186940dbbde5705d142fd36ff3a9228`.
The previous jar, config, build records and world backup are under
`aibuild-server/backups/before-focused-review-20261031-145406/`.
The isolated fixture server is stopped. Its last three saved models each matched
all 21,078 actual world blocks after shutdown.
