# AIBuild 3.0: master-builder generation

## Why

2.0 made generation work: the AI writes a JavaScript build script. Its builds still looked amateur:
- **Weak kits:** `tree()` was a leaf sphere on a stick, `roof()` a plain stair slope, the cottage example a box
  with a roof.
- **Too little thought:** scripts were about 200 lines, written at `low` reasoning effort.
- **Coarse vision:** the judge and critic saw one average colour per block in four small isometric views.

The user wants builds "like the best Minecraft builders". The model stays DeepSeek V4.1 Flash, and a standard
build may take about 12–15 minutes. So the craft has to live in the tools, and in how the model's attention is
spent.

## What changed

| Area | 3.0 |
|---|---|
| Library versions | `Stdlib` V1 (2.0, frozen) and V2 (3.0). Each stored build re-runs and is edited with the library it was written for (`BuildVersion.stdlibVersion`). |
| Buildings | `volume()` builds one building mass in three depth layers: plinth with a stair skirt, corner posts and posts every 4 blocks, beams at floor lines, and infill one block back. It adds windows in the bays with sills and flower boxes, a framed door with step, lintel and lantern, an optional jetty, floors, ladders, lights and a chimney. Builds combine several volumes. |
| Roofs | New `roof()`: overhang, eave lip, rake trim, ridge cap, filled gables, a second block mixed in patches, tone bands and steep pitch. Also hip, pyramid, mansard, shed and flat roofs, plus `dormer()`. |
| Fortification | `tower()`: flared plinth, masonry graded and weathered, slits and windows, corbelled parapet, merlons or a cone/pyramid roof. `curtainWall()`: skirt, buttresses, walkway and merlons. |
| Nature | `tree()` gives trunks with root flare, branches of wood blocks, clustered mixed leaves and hanging leaves; conifers in layered skirts; palms. Also `groundCover()` (blended ground plus clustered plants), `terrain()`, `pond()`, layered `rock()`, `lampPost()` and `fenceLine()`. |
| Shading | `shade(ramp)` is a material: each surface cell takes a step of a dark-to-light ramp from light direction, height and how enclosed it is. `ramp(block, n)` picks blocks of one hue and one texture family from the real block colours. `palette(name)` gives 11 curated role sets. |
| Prompts | The craft section is rewritten in MineBench style: judging criteria, six failure modes, how master builders work, and sizes for each kind of subject. Eleven recipe cards (house, castle, tower, asian, sculpture, creature, ship, nature, scene, bridge, general) are picked by a local keyword classifier, so no model call is needed. The examples are rewritten with the v2 kits. |
| Vision | `VoxelRaster` is a ray caster with per-face client textures and mip-maps. It draws partial-block geometry: slabs, stairs, connecting fences, walls and panes, trapdoors, doors, lanterns, chains and crossed plants. Leaves are cut-out; glass and water are translucent. It adds shadows, Minecraft-style corner occlusion, outlines and grass around the build. The sheet now has 4 isometric views, an eye-level view from the front and a top view. Close-ups of named parts are a separate sheet. |
| Flow (standard) | 2 designs in parallel at `medium` effort (120k tokens) → the judge picks one → an art director names up to 3 focal parts with briefs → sculptors rewrite one `part()` each in parallel, from a close-up → merge and gate → judge → 1 critic round. |
| Limits | `max-extent` 160; script budget 1.5M cells in standard, 2.5M in extra. |
| Measurement | `/aib bench create [--a v] [--b v]` with the variants `script-v2` (exactly as shipped: library 2.0, low effort, 60k tokens, 2 critic rounds), `script-v3` and `compiled`. `/aib bench suite` runs the 16 suite prompts one after another. `tools/blind_ballot.py` reads the variant names from `bench.json`. |

## Verified offline

- All 194 unit tests pass. The new tests cover:
  - renderer geometry, texture sampling, eye-level framing and fence connections;
  - the atlas round-trip;
  - part spans and part replacement;
  - the recipe classifier on all 16 suite prompts;
  - both libraries' examples and the v3 prompt contracts.
- I reviewed the kit gallery renders (trees, house, castle, terrain, shaded statue, the three examples) by eye
  and tuned the kits on them. Examples of fixes: eaves that hid the upper floor, shutters filling every bay,
  too much grass, and ramps drifting in hue.

## Measured live

See the section below, added after the first bench on the server.

## Limitations

- **Unproven:** none of the new kits or the sculptor stage has a human ballot yet. 3.0 becomes "better" only
  once `/aib bench suite` plus a blind ballot shows humans prefer it in at least 65% of about 30 decided pairs.
- **Kit look:**
  - `volume()` gives every building the same structural grammar. Variety comes from combining masses and
    palettes, and a single volume still reads as a timber-frame type.
  - Hip-roof corner stairs rely on the shape resolver for outer corners.
- **Stepped terrain:** there are no grass slabs in Minecraft, so `terrain()` shows terraces.
- **Ramps:** `ramp()` is only as good as the average texture colours. For important surfaces, an explicit list
  of blocks is more reliable.
- **Sculptors:** a sculptor may only rewrite a `part()` that is written in the script; parts created inside kits
  (`tree`, `tower`) cannot be chosen. Rewrites that grow far beyond their part are not rejected automatically.
- **Time:** standard mode takes longer than 2.0 (medium effort plus the sculptor stage); see the measured times.
