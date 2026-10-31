# GitHub baseline and local AIBuild changes

Comparison against local GitHub checkout `5e082d7` and installed local release 1.1.0.
The 1.1.0 changes were committed locally as `2bff405`. Subsequent 1.2.0 validation
and review changes are described separately in [focused-review.md](focused-review.md).

| Area | GitHub baseline | Installed local version |
|---|---|---|
| Creation | Separate design brief followed by 4–6 construction-stage calls. Independent later stages could run concurrently. | One AI response contains the design brief and construction program. Java expands the program before review. The staged path remains available in configuration. |
| Construction language | Existing voxel operations, including fills, walls, roofs, copies and individual blocks. | Adds configurable `building` envelopes and nested `repeat` modules to those operations. The AI still chooses materials, dimensions, layout and custom operations. |
| Standard reviewers | Three specialist calls per active round: structure, exterior, interior. | One reviewer covers all three areas, reducing duplicated context and conflicting repairs. |
| Extra reviewers | Three specialists, with high reasoning. | Retains three specialists. Follow-up rounds honor the configured lower effort. Repairs merge against the snapshot each reviewer actually inspected. |
| Thinking | Standard construction/review used low reasoning; its configured follow-up setting was off. Adaptive reviews reused the initial review effort. | Standard construction and every review use low reasoning. Extra starts with high reasoning and uses low for follow-ups. Standard/Extra do not silently fall back to thinking off. |
| Model input | Text coordinates/layers, design brief, checklists and automated structural analysis already existed. | Uses compact coordinate runs and adds computed height profiles, walkable zones, door connections and furniture approach evidence. Images are never sent to the model. |
| Design instructions | Emphasized many details, material variety, facade depth and cottage-like features. | Emphasizes proportions, silhouette, hierarchy, rhythm, material roles, circulation and detail appropriate to the brief. Restraint is allowed. |
| Style controls | Ordinary operations could express custom geometry, but there was no new envelope primitive to configure. | Automatic framing, windows, shutters, sills, planters, porch and lighting can each be disabled. Custom openings and forms still use ordinary operations. |
| Repair verification | Model checklist responses and self-scores drove adaptive completion, with automatic analysis. | Exact block-state predicates can verify a repair locally. Proofs must match the failed checklist item; conflicts and failed operations prevent completion. |
| Completion | Adaptive reviewers could finish through small-change/repeated-failure shortcuts. | Applicable quality criteria each require at least 3/4 plus observed evidence, all assigned checklist items must pass, overall score must meet the threshold, and unresolved serious findings block approval. No-progress rounds stop as unapproved. |
| Concurrent repairs | Specialist responses could overwrite one another's changes. | Applies only the snapshot-relative differences whose original blocks remain current. Conflicting writes need review again. Shared checklist items have one owner. |
| Failed results | A generated version could be committed and announced as done despite failed approval. | Failed new creations are explicitly marked drafts. Failed edits save the attempted draft separately and restore the previous version and design metadata. |
| Streaming failures | General streaming/retry handling. | Adds repeated-operation detection and rejects truncated programs. |
| Progress | Boss bars and messages existed, but console-owned jobs did not reach the player reliably. | Console jobs mirror boss bars and stage messages to online operators; long calls report status at least every 30 seconds. |
| Measurement | Call transcripts and final analysis existed. | Adds machine-readable job timings, calls, tokens, revision, approval, criterion evidence and blockers; local tools summarize runs and compare saved models with world blocks. |

One model performs every AI role: `deepseek/deepseek-v4.1-flash` through Command Code.
The architect and reviewers are separate requests with different instructions, not different model families
or independently trained judges. Review calls receive the current build and bounded review history.

For Standard creation, the usual sequence is:

```text
User request
  -> DeepSeek: design choices + compact construction program
  -> Java: expand modules, execute operations, resolve shapes/orientation/entrance
  -> Paper: live preview
  -> Java: structural analysis + spatial evidence + exact coordinate text
  -> DeepSeek: inspect all quality criteria and propose targeted repairs
  -> Java: validate/merge repairs, check proofs and acceptance
  -> repeat review if needed, within the configured cap
  -> place/save an approved version, or mark a new creation as a draft
```

Extra replaces the all-area review call with parallel structure, exterior and interior calls.
It does not create three separate versions of the entire house. Code merges proposed repairs into
one model, then recomputes evidence. Construction and review counts vary with failures and rechecks.

The existing analyzer, placement, undo/redo, import, editing and persistence systems were extended,
not newly invented in this revision. No Blender, image generator, vision input, external geometry
service, new model or new runtime library was added. The closest borrowed principle is procedural
geometry: let the model describe an intentional program and let deterministic code expand repetition.

The current envelope is rectangular and single-storey. Multi-part architecture still requires
composed envelopes or custom operations. There is no general room/furniture constraint solver or
automatic aesthetic oracle. Text navigation approximates Minecraft movement, and same-model review
can still miss errors. More rubric checks establish stricter acceptance behavior; they do not establish
a numerical gain in beauty or creativity.

This is a partial implementation of the supplied quality plan. In particular, the current fast creation
path combines its separate Spec and Compose stages in one response; diagnosis and repair also remain
combined in each review response. Named components with anchors, a furniture solver, model-directed
region-inspection tools, adaptive decomposition for complex builds and the plan's broad blind evaluation
are not implemented. A successful small house does not establish that one initial construction response
is the best strategy for castles or complicated multi-room layouts. Such builds need their own comparison
against a shared specification followed by component construction and intermediate checks.

Timing and correctness observations are in [generation-performance.md](generation-performance.md)
and [text-quality.md](text-quality.md). Fresh baseline-versus-current measurements are recorded
separately so historical and newly measured runs are not conflated.
