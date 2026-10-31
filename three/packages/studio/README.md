# @aibuild/studio — web studio for AIBuild 3D

A dependency-free `node:http` server plus a browser app that shows a `StructureSpec` in three.js,
the PNG renders from the software rasteriser, and the iteration history written by the pipeline.

Nothing is bundled or built: the browser loads plain ES modules, and three.js is served from
`node_modules/three` with its import specifiers rewritten on the fly.

## Run

```bash
cd three
node packages/studio/src/server.mjs            # http://127.0.0.1:8137
node packages/studio/src/server.mjs --port 0   # any free port
npm run studio                                 # same, via the root package.json
```

Options: `--port N`, `--host H`, `--root DIR` (the project root whose `out/` is served),
`--help`.

## HTTP API

| Method | Path | Purpose |
|---|---|---|
| GET | `/` | the studio UI (`public/index.html`) |
| GET | `/app.js`, `/viewer.js`, `/style.css` | studio assets |
| GET | `/vendor/three.module.js` | three.js build (`node_modules/three/build`) |
| GET | `/vendor/three.core.js` | three.js core (three ≥ 0.17x splits the build) |
| GET | `/vendor/OrbitControls.js` | `examples/jsm/controls/OrbitControls.js`, `from 'three'` rewritten |
| GET | `/vendor/addons/<path>` | any other `examples/jsm` file, imports rewritten |
| GET | `/core/<module>.mjs` | allowlisted core modules (`geometry`, `spec`, `materials`, `objects/*`) |
| GET | `/api/health` | liveness plus resolved directories |
| GET | `/api/builds` | gallery: array of build summaries, newest first |
| GET | `/api/samples` | prepared specs in `three/samples/` |
| POST | `/api/build` | `{prompt}` → creates `out/<id>/meta.json` with `status:"pending"`, **202** |
| GET | `/api/build/:id?version=N` | meta + spec + versions + history + views |
| GET | `/api/build/:id/views?iteration=N` | render URLs |
| GET | `/api/build/:id/spec?v=N` | one spec version (`final` by default) |
| GET | `/api/build/:id/history` | iteration history |
| GET | `/out/:id/:file` | a render or artefact (PNG, `.litematic`), with ETag and Range |

`POST /api/build` also accepts `{sample: "house-10x10"}`, which copies a prepared spec in and
returns `status:"ready"` — useful for demoing the studio before the generator is wired up.

## On-disk contract (read, not invented here)

```
out/<id>/meta.json        { id, prompt, status, name?, createdAt?, updatedAt?, objects? }
out/<id>/spec.json        final StructureSpec
out/<id>/spec-v<N>.json   spec after iteration N
out/<id>/history.json     [{ iteration, score, verdict, issues[], sev, applied }]
out/<id>/v<N>-<view>.png  renders ('se', 'front', 'top', ...)
out/<id>/<view>.png       renders without an iteration prefix
```

Every field except `meta.json` is optional: a half-finished build still lists and still opens.

## Frontend

* `public/index.html` — layout, import map (`three` → `/vendor/three.module.js`).
* `public/app.js` — sidebar, viewports, inspector tabs (iterations / renders / spec), create form.
* `public/viewer.js` — `StructureSpec` → `THREE.Group`. Prefers `buildScene()` from
  `packages/core/src/geometry.mjs` (served at `/core/geometry.mjs`) so the live preview matches the
  PNG renders and the Minecraft voxelisation; falls back to primitive volumes if `/core` is absent.
* `public/style.css` — the dark studio theme.

Viewer features: OrbitControls, sun + fill + rim lights with shadows, grid and ground plane scaled to
the model, palette colours, transparent glass, optional edge overlay, camera presets, single and
side-by-side compare modes (two independent viewports, each with its own spec version).

## Tests

```bash
cd three && node --test 'packages/studio/test/*.test.mjs'
```

Note: this Node build rejects a directory argument (`node --test packages/studio/test/` tries to
import the directory as a module — the same happens for `packages/core/test/`), so use the quoted
glob above or the root `npm test` script, which expands the glob itself.
