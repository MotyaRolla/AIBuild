# Dragon comparison: Original, Three.js, Our

Completed on 31 October 2026. All three saved models are in the main server's
world, registered for `/aib tp`, and checked against saved world blocks. The main
server is running Our 1.1.0 and its original configuration again. Both workers and
measurement proxies are stopped; temporary placement functions and forced chunk
loads have been removed.

All three variants use the same prompt, DeepSeek `deepseek/deepseek-v4.1-flash`,
Standard mode, Paper version, superflat world layout, JVM version and host. Runs
started sequentially, then changed to overlapping runs at the user’s request. Original
kept running on the main server; Three.js and Our started together on local workers.
Each has a predefined 45-minute limit. A limit or failure is reported
as such, rather than counted as an approved completion. This is one sample per
variant, not a statistically reliable ranking.

The original is the jar whose classes match GitHub commit `5e082d7` (only the jar
manifest differs). Three.js is the in-game `/aib 3d` path at branch commit
`dbde7d27207c14210e7bf5357b54f42800b0744d`. Our is the installed local 1.1.0
jar, SHA256 `d3c4e1e61b3c4aa926458b9b8c3c9c60e97827e57fc6e125292bb29204c0fb24`.
The native Three.js branch sends rendered images to the same DeepSeek model for
review; Original and Our send text only. That input difference is part of this
comparison of the actual implementations. The Three.js plugin implements its scene/voxel/render loop in Java; the branch
also contains a separate JavaScript studio. This test exercises the Minecraft
command, not the studio CLI.

Original and Our use their existing configurations. Three.js uses its branch
configuration. The API base URL is routed through the same transparent local
proxy implementation (two instances), forwarding to Command Code. The proxy records
request bodies, returned SSE, usage, response time, model identifiers and image
counts; it does not record authentication headers or image bytes. Consequently,
this compares complete configured systems, not the geometry representation alone.

The comparison plots are near X=1000 (Original), X=1160 (Three.js), X=1320 (Our),
Y=172, Z=0. The server's previous plugin, config and build records and its world
were backed up before testing. The final state runs Our plugin again. Worker models were placed unchanged in
the main world with vanilla datapack functions and their records registered at the
final plugin restart. Main uses a 3 GiB heap / 4 advertised processors; workers use
1.5 GiB / 3 processors each. Parallel provider load, the different start times and
these resource differences limit causal speed comparisons. Initial local build or
server startup time is excluded from generation timings.

## Shared request

> Build a detailed freestanding fantasy dragon sculpture, not a house or a habitable building. Fit the entire sculpture including its base within a 48 by 48 block footprint and at most 36 blocks high. Show an expressive dragon with two large spread wings, four distinct legs with claws, a long curled tail, a curved neck, a horned head with an open jaw and visible teeth, bright amber eyes, and a ridged spine. Use dark deepslate and blackstone scales with restrained red accents and a lighter underside. Give the wings a convincing membrane and supporting fingers, make all body parts visibly connected, and create a dramatic asymmetrical pose perched on a modest rocky base. Prioritize a recognizable dragon silhouette, balanced proportions, coherent anatomy, and carefully shaped detail visible from multiple sides. No buildings, roof, doors, windows, lava, fire, or unrelated scenery.

## Measurements

| Metric | Original | Three.js | Our 1.1.0 |
|---|---:|---:|---:|
| Build ID | `9f01f9` | `ed7a48` | `ac3286` |
| Generation through placement | about 19m12s | about 6m20s | 9m05s (545.147s) |
| Model calls | 12 | 9 | 4 |
| Input tokens | 591,051 | 61,091 | 158,483 |
| Cached input tokens, included above | 120,832 | 1,024 | 34,304 |
| Output tokens, including reasoning | 206,962 | 80,169 | 88,634 |
| Reasoning tokens, included above | 185,247 | 4,596 | 76,730 |
| Rendered images sent to model | 0 | 32 | 0 |
| Review rounds | 2, three reviewers each | 8, one visual critic each | 3, one general reviewer each |
| Solid blocks | 9,704 | 13,370 | 7,118 |
| Distinct block names | 26 | 1: stone | 11 |
| Actual width × height × depth | 47×33×46 | 42×37×40 | 45×34×32 |
| Within requested maximum dimensions | Yes | No: height 37 exceeds 36 | Yes |
| Face-connected components | 9 | 3 | 1 |
| Cells outside largest face-connected component | 47 | 357 | 0 |
| Common analyzer: critical / major / minor | 0 / 1 / 0 | 0 / 3 / 4 | 0 / 0 / 0 |
| Internal result | Committed; 44/44 checks; self-score 91 | Iteration cap; self-score 34; critic retains 3 critical + 4 major findings | **DRAFT**, despite 16/16 checks and self-score 92 |
| Main-world match | 9,704/9,704 | 13,370/13,370 | 7,118/7,118 |

Original and Three.js report whole elapsed seconds in their logs. Independent
controller observations were 1153.486s and 380.791s, including polling delay.
Our's metrics file records 545.147s; its controller observed 545.451s. Do not read
sub-second precision into comparisons between the different timers.

In this sample Our took about **2.11× less time** than Original: approximately
**52.7% less elapsed time, 57.2% fewer output tokens and 73.2% fewer input tokens**.
Three.js finished about 30% sooner than Our, but did not achieve an acceptable
dragon. Our also remained a draft because of a checker error described below.
These are times to the saved results, **not times to three equally approved
builds**. No other model was used. Returned stream identifiers included the
provider-resolved `deepseek/deepseek-v4.1-flash-1017-cm` alias.

Original started at 13:51:02 local time; the workers started at 14:03:37. The last
generation ended at 14:12:42. The generation window was about 21m40s, with a
substantial Original head start. Preparation, transfer, final restart and
verification are outside those generation timings. This was not a test in which
all three began simultaneously.

## What the quality checks actually show

**Original:** the result has a recognizable creature silhouette and the requested
dark material family. Its reviewers accepted 44/44 checks, but the old pipeline
still printed three serious analyzer findings at commit. The common pass leaves
one `dark_interior` finding: 17 cells in small enclosed cavities. That is a
building lighting heuristic, not proof that an inaccessible sculpture needs
interior lighting; the server is peaceful anyway. The 47 face-disconnected cells
are spread across eight small components of at most 15 blocks. Small details can
touch diagonally, so this count alone does not prove a broken limb. The large
pedestal and angular, narrow wing shapes remain visible in the renders.

**Three.js:** this is a failed dragon result, even though the branch committed it.
The initial design reached the 60,000-token output limit with
`finish_reason=length`; the branch recovered 36 objects from incomplete JSON and
continued. Eight critique rounds evaluated scores 16, 28, 23, 23, 22, 23, 25, 34.
The final critic still described an unrecognizable flattened body, plate-like
wings, an oversized stepped pedestal, short neck, box-shaped legs and inadequate
tail. The loop placed its highest-scoring evaluated version, iteration 8, after
reaching the cap. It did not place that last critique's unevaluated proposed patch.

Custom material keys fell through `SceneGeometry.blockFor` to `plain` (`stone`),
so every saved Minecraft block is stone. The visual renderer colors voxels from
scene palette information: those previews are not a reliable record of the actual
Minecraft palette. This is an implementation defect, not evidence that scene
graphs or visual feedback are inherently inferior. The common pass finds two
substantial disconnected components (192 and 165 blocks), plus a dark two-cell
cavity. Four minor findings describe the all-stone palette. The branch-specific
analyzer also demands a roof, door and windows and interprets the maximum 48×48
allowance as an exact building size. Those are false requirements for this
request; detached pieces and the height overrun were measured independently.

**Our:** its first program produced 7,067 blocks in 189.422s. Review repaired a
disconnected west wing and horn tips, added wing fingers, and reinforced narrow
wing joints. The first two rounds changed 262 and 13 cells; round 3 changed none.
The final 7,118-cell sculpture is one face-connected component, fits the requested
maximum, retains 11 block types and has no findings in the common pass. It still
has angular anatomy and broad, stepped wing surfaces. The numbers do not establish
realistic anatomy or a universal aesthetic improvement.

Our's **draft status is a pipeline defect in this sample**. Its own analyzer was
configured with the plan's `body` footprint 45×31 and compared that with the 26×24
cross-section near ground level. A dragon is not a rectangular building with
walls spanning its entire silhouette. Although the reviewer completed 16/16
checks, its rubric passed and the total dimensions are 45×34×32, the final hard
gate retained `footprint_mismatch` and forced `approved=false`. The blocker-map
field was empty and chat misleadingly reported `16/16 (all verified)` as the
reason for non-approval. The result, flag and geometry were preserved; no
production fix or post-hoc approval was applied to improve this benchmark row.
The next targeted fix should make size checks aware of object type and maximum
bounds and show the final analyzer reason in draft diagnostics.

Internal scores 91, 34 and 92 use different prompts in the same model family;
they are not independent, calibrated quality scores. The 44/16 checklists also
cannot be compared as one accuracy percentage. The strongest objective results
are better connectivity and material retention in Our than the tested Three.js
branch, with fewer calls and less token usage than Original. Aesthetic preference
between Original and Our still needs the player's inspection; a general ranking
needs repeated, preferably blind comparisons.

## Where the time went

| Phase | Original | Three.js | Our |
|---|---:|---:|---:|
| Initial planning | 20.855s | Included in scene design | Included in program |
| Construction | Five calls: 64.033, 117.913, 238.485, 185.995, 322.003s | Scene design 233.579s, without reasoning | Program 189.422s, low reasoning |
| Review | Two groups of three parallel calls; about 120s then 80s | Eight calls, 141.622s summed | 197.838 + 126.966 + 28.148s |

Three.js's scene design uses Standard's `plan=off`; its first critic uses `low`,
later critics `off`. Original uses `off` for planning and `low` for construction
and review. Our uses `low` for its combined design/program and all three reviews.
All commands selected Standard, but that label does not mean identical effort
settings in each implementation. Exact request settings and per-call metadata are
retained in `api-summary.json` and `api/*-meta.json`.

## Inspect in Minecraft

| Version | Command | Solid block bounds in main world |
|---|---|---|
| Original | `/aib tp 9f01f9` | X=978..1024, Y=172..204, Z=-48..-3 |
| Three.js | `/aib tp ed7a48` | X=1140..1181, Y=172..208, Z=-40..-1 |
| Our, draft | `/aib tp ac3286` | X=1300..1344, Y=172..205, Z=-35..-4 |

All three builds and all 10 pre-existing build records are present. Our jar and
configuration match their before-test snapshots. No changes were committed or
pushed to GitHub.

![Front and back views at the same block scale](../bench/dragon-comparison-20261031/comparison.png)

Final geometry was passed through the same local 1.1.0 analyzer with
`expectInterior=false`, since a solid sculpture needs no habitable room or door.
Exact solid bounds, block count, distinct materials and face-connected component
sizes were measured independently of each pipeline's own reviewer. Components are
whole-block adjacency measurements; decorative diagonal contacts can appear
separate. These measurements do not by themselves certify good dragon anatomy.

Common isometric renders are human-facing previews of the saved voxel models,
with simplified block shapes and colors. In-world appearance remains the primary
visual comparison. Internal self-scores from different review prompts are not a
common independent beauty score. Placement was checked against the saved world
after `save-all flush`.

Raw evidence: `bench/dragon-comparison-20261031/` (local, ignored by Git).
