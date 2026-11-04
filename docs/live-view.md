# Live view: watching a build in the browser

## Why

A build takes 20 minutes or more in `standard`. Until now the only sign of progress was the boss bar and chat
lines, and the AI's own work — the program it writes, what it is reasoning about, which stage is running —
was invisible. Users asked to watch a build happen, with the real Minecraft blocks.

## What the viewer sees

One page per run, at `/live/<token>/`. No framework, no external requests: the page is a single `index.html`
from the jar's resources, and everything it draws arrives from the same origin.

- **The request** in the header, under the build name, so a shared link says what was asked for.
- **The build**, drawn as shaded isometric cubes on a 2D canvas (`getContext("2d")`, no WebGL), with real block
  textures. Block shapes are honoured: slabs, stairs (including upside-down ones), posts, panes, crossed plants,
  plates. Drag turns it, shift- or right-drag pans, the wheel zooms about the cursor. Buttons `Iso` / `Front` /
  `Top` set the camera, `Fit` frames the build, `Changes` re-flashes the blocks that changed in the last frame.
- **AI at work**: one tab per AI call that streams — each program candidate and its repairs, the concept writer,
  the art director, every sculptor (`Sculptor · roof`), the interior designer, each critic round, the judges.
  The pane follows whichever call is writing, and holds on to it while it keeps writing so two parallel
  candidates do not flip it with every token; clicking a tab pins it, `following` goes back to auto. While a call
  is still reasoning (minutes at high effort) its reasoning fills the pane; once the answer streams, the reasoning
  shrinks to a few lines above the code (`expand` opens it). The code follows its end only while the viewer is
  at the bottom.
- **Stages**: every review stage as it ends — first design, each sculptor that failed and why, the sculptors'
  merge, the interior designer, each critic round, the finisher — marked kept or rejected with the reason
  (`your rewrite removes or covers 57 blocks that OTHER parts built`, `the judge preferred the previous version`)
  and the measured quality after it: floating, unsupported, dark cells, blocked doors, blocks destroyed between
  parts, rooms and furnished rooms, each with its change against the previous kept stage in green or red.
  Clicking a kept stage shows the build as that stage left it; `back to live` (or `l`) returns.
- **Activity**: stage rows with their durations and the chat lines of the job.
- **Parts**: named parts with cell counts and the block total.

`Stop job` in the viewer's toolbar cancels the run: it posts to `/live/<token>/stop`, and the server sets
`job.cancelled`, which the pipeline polls between calls. The button hides itself once the job has ended, since
there is nothing left to cancel. `/aib cancel` in chat does the same thing from the game side.

A late viewer is not shown the whole history: the session keeps a bounded journal (400 events; 120 notes,
60 chat lines), so a browser that connects after ten minutes gets the recent stages, the current program text,
the last transcript lines and the newest model. High-frequency inputs coalesce — program text and the model keep
only their newest value — so a slow encode never builds a backlog.

## One link per run

`LiveSession.Registry.open` mints 16 random bytes from `SecureRandom` (128 bits) as 32 lowercase hex characters.
That is the only credential: the page has no accounts, and whoever holds the URL sees that one build.
Consequences, all deliberate:

- **One link per run.** Opening a run for a build revokes the build's previous session (it is finished as
  `cancelled` with "Superseded by a newer run of this build"), so an old link never shows the wrong job.
- **Read access to one build only.** Tokens go through a registry callback and never into a path, so no request
  can traverse the filesystem, and the lookup re-compares the token in constant time after the registry match.
- **Unknown tokens are 404, not 403.** Nothing reveals whether a token merely looks valid.

Tokens are logged only as a 6-character prefix: the request log does it at `FINE`, and
`BuildPipeline.openLiveLink`, when `public-url` is empty and no link goes to chat, logs the prefix with a hint to
set the public URL. The full link comes from `/aib watch`. The chat line that carries the link is not repeated
on the page's activity list.

`/aib watch [id]` (`/aib live`, `/aib смотреть`) resolves the job as: explicit id from `BuildStore`; else the
player's active build, falling back to the newest *running* job they own (`firstOwned` walks the live job map, not
the stored builds); else, for the console, any running job. It refuses another player's build unless the sender
has `aibuild.admin`.

## How it is served

`LiveViewServer` is the JDK's `com.sun.net.httpserver.HttpServer`: no dependency, and chunked responses are all
this feature needs. The traffic is one-way, so the stream is Server-Sent Events rather than a WebSocket — plain
HTTP survives a proxy or tunnel without an upgrade handshake, the browser reconnects by itself, and the only
client-to-server call is the stop button.

| Method | Path | Serves |
|---|---|---|
| GET | `/live/<token>/` | the page, from `/live/index.html`, or a readable placeholder when the resource is missing |
| GET | `/live/<token>/state.json` | the whole current state as one JSON object (`bootstrapJson`) |
| GET | `/live/<token>/model.bin` | the newest `ModelPack`; `204` when nothing has been encoded yet |
| GET | `/live/<token>/stage/<n>.bin` | the build after kept review stage `n`; `404` for a rejected or dropped one |
| GET | `/live/<token>/atlas.png` | the block texture atlas |
| GET | `/live/<token>/events` | the SSE stream |
| POST | `/live/<token>/stop` | cancels the run; `405` when no stop handler is registered |
| GET | `/live/<token>/step/<n>` | one recorded step with all of its fields; `404` when it was evicted |
| GET | `/live/<token>/trace.md` | the whole trace as markdown, as a download |
| GET | `/healthz` | `ok` |

Frames are `event: <type>\nid: <seq>\ndata: <json>\n\n`, one of `stage`, `code`, `think`, `preview`, `model`,
`parts`, `note`, `say`, `step`, `verdict`, `finish`. `code` and `think` belong to one stream (`kind`): the first
frame of a stream carries its whole text, later ones only `{kind, at, append}`, since an answer grows by
appending and resending 20 KB per token for each of three candidates was almost all repetition. A page whose copy
of a stream is shorter than `at` lost frames and re-reads `state.json`; a stream that starts over (a transport
retry) is sent whole again. `verdict` is `{n, stage, title, kept, why, quality, url}`; the session keeps the
builds of the last 12 kept stages. `seq` is monotonic per session and `state.json` carries it too, so the page
drops replayed frames it already applied after hydrating. A heartbeat comment every 15 s keeps idle proxies from
closing the stream; each stream opens with `retry: 2000`.

Limits, because one browser must never cost a build:

- `max-viewers` (default 8) concurrent `/events` streams across all tokens; each parks a server thread, so it
  bounds threads as well as memory. Over the cap: `503` with `Retry-After: 5`.
- `MAX_IN_FLIGHT_PER_TOKEN = 4` concurrent requests per token, then `429`. A leaked or refreshed URL must not
  become unbounded load on the small pool.
- Per-stream queue bounded to 512 frames / 8 MB. A viewer that falls behind is disconnected rather than
  buffered; its browser reconnects and reloads `state.json`.
- On every stream (re)open the page re-reads `state.json` and rebuilds its sequence watermark from the `seq`
  it carries. Event ids are per session, so a restarted server numbers them from 1 again; trusting the old
  watermark would make the page silently drop every new frame.

## Debugging a build

The page answers "what is it doing"; the **Debug** button (or `d`) answers "why did it do that, and where did it
go wrong". It records every step of the job as a row:

| Kind | What it is |
|---|---|
| `pipeline` | a phase change — concept, program, plan, sculpt, review, place |
| `llm` | one model call, with its prompt, reasoning and raw answer |
| `script` | one run of the build program in the sandbox, with the source and the traceback |
| `ops` | one applied batch of JSON operations, with per-op errors |
| `model` | a new version of the build, so a version can be tied to the call that produced it |

Rows carry a duration and turn red when they failed, and a `failures` chip hides everything else — that filter
usually takes a hundred-row trace down to the two rows that matter. Opening a row shows the full prompt as the
model saw it (every message, in order, with the role), its reasoning, the answer it gave, the token usage and
finish reason, and for a script run the exact program text with the sandbox error.

The list arrives on the stream as summaries; the payload is fetched from `/live/<token>/step/<n>` when a row is
opened. Prompts are large and a build makes dozens of calls, so sending them all would push the events a viewer
actually needs out of the bounded journal. `save` downloads the whole trace as markdown
(`/live/<token>/trace.md`) for a bug report or an editor.

Some deliberate limits: a field is capped at 48 000 characters with the truncation marked, the trace holds 600
steps per job, and total field text is bounded at 24 MB, oldest steps dropped first. Streaming model previews are
not trace rows — they would fill the trace within a minute and the build growing is already visible. Everything
is in memory with the session, so the trace is gone when the link expires.

## The model on the wire

The page draws client-side, so the model has to cross the wire, and JSON would be far too large. `ModelPack`
is a flat little-endian blob: a 20-byte header (`"AIBLV02X"`, payload length, atlas length, version, three
reserved bytes), a JSON payload, an optional PNG atlas section, then one 16-byte record per cell
(`int16 x,y,z | uint8 shape | uint8 facing | uint16 block | uint32 flags | int16 part`). Cells are sorted by y,
then x, then z so the page can extend an already-drawn build without flicker, and each frame flags the cells
that appeared, moved or changed since the previous one (`Flags.CHANGED`), which is what `Changes` flashes.

The atlas is roughly 190 KB of PNG and identical for every frame, so a pack normally carries its URL
(`/live/<token>/atlas.png`) rather than the bytes; the page fetches it once and the browser caches it. When the
server has no textures installed (`render.textures: off`), the page falls back to flat palette colours.

With WebGL2 (every current browser) the page draws on the GPU. Each model becomes one mesh: a quad per block
face, except faces between two full opaque cubes and between two cells of the same glass or water. The 61k-block
castle meshes to 102 830 quads (80 210 opaque, 22 620 see-through) in about 70 ms, once per model update. A frame
is then two draw calls, opaque faces with depth writes and then glass and water blended over them, so turning the
camera costs the same as panning. The vertex shader is the same map as `project()`, so `fit()`, zooming about the
cursor and the grid match the 2D path. Depth is the negated `nearness()`, and there is no painter's sort. Textures
are sampled nearest-neighbour for the pixel look; below about 5 px per block they fade into the palette colour so
a zoomed-out castle does not shimmer. Highlighting a part dims the rest, and the change flash is a uniform. In
software-rendered headless Chrome a frame of that castle takes 45 ms, against 350 ms for the 2D painter.

Without WebGL2, or with `?2d` in the URL, the page falls back to the 2D canvas painter described next.

Cells are painted farthest first by `nearness()` (rotated depth plus height, towards a camera above the
build), and each draws its top and the two sides facing the camera. Both were inverted until 3.2.1: cells were
painted nearest first with their two hidden sides, so a 13-block stone tower vanished behind the grass around
it and the ground read as a zigzag of triangles.

Drawing skips cells that cannot show from the current side: a full opaque cube whose neighbours above and on its
two visible sides are full opaque cubes is covered by them (a cube's back faces project inside its front ones),
so painting it changes no pixel. The set is computed once per quadrant of the camera. On the 61k-block extra
castle that leaves 35 779 of 65 447 cells. Slabs, stairs, panes, glass and liquids never count as occluders.
Comparing culled and unculled frames of that castle from four angles differs only in 1-pixel antialiasing seams.
Blocks the atlas has no tile for (some stained glass panes) are drawn in their palette colour.

In 2D the finished frame is kept: panning, zooming and the change flash blit it (under 1 ms) instead of painting
the build again, and a full frame follows when the motion stops. A full frame of that castle is about 350 ms in
software-rendered headless Chrome; turning the camera pays it per frame.

Model updates that arrive while one is still loading collapse into one reload, since the URL always serves the
newest pack. The code pane highlights finished lines once and freezes them, and only the tail after the last
line that ends outside a string or comment is re-highlighted each frame. The reasoning pane appends new text
instead of replacing it.

The full layout, shape codes and flag bits are documented in the `ModelPack` class Javadoc; that file is the
reference, not this page.

## Exposing it

The listener binds loopback and never opens a public interface on its own. To let other people watch:

```yaml
generation:
  live-view:
    enabled: true
    bind: "127.0.0.1"
    port: 8791                        # fixed, so the tunnel has something to point at
    public-url: "https://builds.example.com"
    max-viewers: 8
    expire-minutes: 15
```

Then point a tunnel or reverse proxy at `127.0.0.1:8791` — `cloudflared tunnel --url http://127.0.0.1:8791`,
`ssh -L 8791:127.0.0.1:8791 user@server`, or nginx with `proxy_pass http://127.0.0.1:8791` and
`proxy_buffering off`. The server already sends `X-Accel-Buffering: no` on the event stream. Check the listener
with `curl -s http://127.0.0.1:8791/healthz` → `ok`.

`public-url` is separate from `bind` because the proxy terminates TLS and may rewrite the host; the plugin cannot
derive the outside address it is reached by. While `public-url` is empty and the listener is loopback-only,
`LiveViewServer.urlForUser` returns `null`: the pipeline then puts no link in chat (`BuildPipeline.openLiveLink`
falls through to a log line), because a link nobody outside the machine can open is worse than no link.
`/aib watch` is the exception — it builds the URL from `publicBaseUrl()`, which falls back to
`http://127.0.0.1:<port>`, prints that clickable loopback link, and adds the warning "Публичный адрес не задан
(generation.live-view.public-url), поэтому по ссылке можно зайти только с сервера".

`requireSameOrigin` exists in `LiveHttpConfig` (default `false`) but is hard-wired to `false` at the only call
site, in `AIBuildPlugin.startLiveView`, and is not read from `config.yml` at all. The origin check is therefore
dead code today: the token is the access control. No `Access-Control-Allow-*` header is sent, so a browser
blocks a cross-origin *read* of `state.json` anyway; the residual risk is a cross-origin `POST /stop`, which is a
simple request and does not need CORS.

## Expiry

A link is valid while its run is going and for `expire-minutes` after the run ends (default 15, clamped 1–1440).
`Registry.get` checks `finished() && now - finishedAt > ttlMillis` on every request and removes the session when
it is past; a sweep runs once a minute to drop finished sessions nobody asks for. The journal and the last model
stay readable for that whole window, which is why a finished build's page still shows the result.

All links are in memory. `/aib reload` re-reads the config but does not restart the listener, and a server restart
drops every link, so `generation.live-view.*` changes need a restart.

## Verified

`plugin/src/test/java/dev/aibuild/live/TraceTest` covers the debug trace: that an LLM step keeps its prompt,
reasoning and answer, that blank fields are dropped and long ones truncated with the cut marked, that eviction
keeps sequence identity, that the snapshot carries summaries but not payloads, that `/step/<n>` returns the
source and traceback and `404`s for an unknown or malformed number, and that `/trace.md` downloads with the
prompts and errors in it.

`plugin/src/test/java/dev/aibuild/live/LiveViewTest` covers the pack encoding, cell ordering, part assignment, the
changed-cell diff, journal replay to a late viewer, finish-after-last-model, and the registry (one link per build,
fresh token per rerun, revocation). `LiveViewServerTest` covers the HTTP surface: the page and `/healthz`, unknown
tokens as 404, `state.json`, the `model.bin` header, the atlas served once and cacheably (and absent without
textures), SSE framing order, the viewer cap, and `stop` as 405 without a handler.

`LiveViewTest` also covers parallel streams staying apart and growing by appends, the request and the per-stream
reasoning in `state.json`, and verdicts: a kept stage keeps a valid pack, a rejected one only its reason.
`LiveViewServerTest` covers `/stage/<n>.bin`.

Rendering was checked by eye and by pixel diff on real builds, with headless Chrome driven by
`tools/live-view/shot.mjs` (`PRE=` runs page code before the capture, `EVAL=` prints a measurement).
