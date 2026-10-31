/**
 * Build store: reads and writes the `three/out/<buildId>/` layout.
 *
 * On-disk contract (written by the orchestrator, read here):
 *
 *   out/<id>/meta.json        { id, prompt, status, name?, createdAt?, updatedAt?, ... }
 *   out/<id>/spec.json        final StructureSpec
 *   out/<id>/spec-v<N>.json   spec after iteration N
 *   out/<id>/history.json     [{ iteration, score, verdict, issues[], sev, applied }]
 *   out/<id>/v<N>-<view>.png  renders of iteration N ('se', 'front', 'top', ...)
 *   out/<id>/<view>.png       renders without an iteration prefix
 *
 * Nothing here assumes a field is present: a build written before this module existed, or by a
 * half-finished iteration, still lists and still opens. Only `meta.json` is required for a build
 * to appear in the gallery, exactly as the brief says.
 *
 * The store never throws on a broken build: it degrades (a bad meta.json is skipped, a bad spec
 * version is reported with `error`) so the studio UI keeps working while the pipeline is running.
 */
import { readdir, readFile, stat, mkdir, writeFile } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import path from 'node:path';
import { randomBytes } from 'node:crypto';

import { resolveRoots } from './paths.mjs';

/** Maximum bytes of a JSON file the studio is willing to serve (specs are small; history is small). */
export const MAX_JSON_BYTES = 8 * 1024 * 1024;

/** Ids are generated here and come back in URLs, so they are restricted to a filename-safe charset. */
export const BUILD_ID_RE = /^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/;

/** A file name inside a build directory (no separators, no traversal). */
export const FILE_NAME_RE = /^[A-Za-z0-9][A-Za-z0-9._ -]{0,255}$/;

/** Renders are expected to be PNG; the gallery tolerates a few other still formats. */
const IMAGE_EXT = /\.(png|jpe?g|webp|gif)$/i;

/** Preferred views, best first — used to pick a gallery thumbnail. */
const VIEW_PREFERENCE = ['se', 'front', 'sw', 'ne', 'nw', 'back', 'top', 'axo', 'iso'];

/** @typedef {import('./paths.mjs').StudioRoots} StudioRoots */

/**
 * True when `id` is a safe build id and is not a path traversal attempt.
 * @param {unknown} id
 * @returns {boolean}
 */
export function isBuildId(id) {
  return typeof id === 'string' && BUILD_ID_RE.test(id) && id !== '.' && id !== '..';
}

/**
 * True when `name` is a safe single file name.
 * @param {unknown} name
 * @returns {boolean}
 */
export function isFileName(name) {
  return typeof name === 'string' && FILE_NAME_RE.test(name) && name !== '.' && name !== '..' && !name.includes('/') && !name.includes('\\');
}

/**
 * Generates a sortable, human-readable build id: `YYYYMMDD-HHMMSS-xxxx`.
 * Same shape the CLI uses, so ids from both sources live together in `out/`.
 * @param {Date} [now]
 * @returns {string}
 */
export function newBuildId(now = new Date()) {
  const p = (n, w = 2) => String(n).padStart(w, '0');
  const stamp = `${now.getFullYear()}${p(now.getMonth() + 1)}${p(now.getDate())}-${p(now.getHours())}${p(now.getMinutes())}${p(now.getSeconds())}`;
  return `${stamp}-${randomBytes(2).toString('hex')}`;
}

/**
 * Allocates a build id that does not exist under `outDir` yet.
 * @param {string} outDir
 * @returns {Promise<string>}
 */
export async function allocateBuildId(outDir) {
  for (let attempt = 0; attempt < 20; attempt++) {
    const id = newBuildId();
    if (!existsSync(path.join(outDir, id))) return id;
  }
  throw new Error('could not allocate a unique build id');
}

/**
 * Reads and parses a JSON file, returning `null` when it is missing or invalid.
 * @param {string} file
 * @returns {Promise<any|null>}
 */
export async function readJsonSafe(file) {
  try {
    const info = await stat(file);
    if (!info.isFile() || info.size > MAX_JSON_BYTES) return null;
    return JSON.parse(await readFile(file, 'utf8'));
  } catch {
    return null;
  }
}

/** @param {string} dir @returns {Promise<string[]>} */
async function listDir(dir) {
  try {
    return await readdir(dir);
  } catch (e) {
    if (e.code === 'ENOENT' || e.code === 'ENOTDIR') return [];
    throw e;
  }
}

/**
 * Lists build directories (`out/<id>/`) that contain a `meta.json`, newest first.
 * @param {StudioRoots|string} rootsOrOutDir
 * @returns {Promise<string[]>}
 */
export async function listBuildIds(rootsOrOutDir) {
  const outDir = typeof rootsOrOutDir === 'string' ? rootsOrOutDir : rootsOrOutDir.outDir;
  const entries = await listDir(outDir);
  const ids = [];
  for (const entry of entries) {
    if (!isBuildId(entry)) continue;
    if (existsSync(path.join(outDir, entry, 'meta.json'))) ids.push(entry);
  }
  // Ids start with a timestamp, so a plain descending sort is newest-first; fall back to mtime
  // for anything hand-made that does not follow the convention.
  const withTime = await Promise.all(ids.map(async (id) => {
    const meta = (await readJsonSafe(path.join(outDir, id, 'meta.json'))) || {};
    const t = Date.parse(meta.updatedAt || meta.updated_at || meta.createdAt || meta.created_at || '') || 0;
    const info = t ? { mtime: t } : await stat(path.join(outDir, id)).catch(() => ({ mtimeMs: 0 }));
    return { id, t: t || info.mtimeMs || 0 };
  }));
  withTime.sort((a, b) => b.t - a.t || (a.id < b.id ? 1 : -1));
  return withTime.map((b) => b.id);
}

/**
 * Parses a render file name into `{ view, iteration }`.
 * `v3-se.png` -> { view: 'se', iteration: 3 }; `se.png` -> { view: 'se', iteration: null }.
 * @param {string} file
 * @returns {{view: string, iteration: number|null}}
 */
export function parseViewName(file) {
  const base = file.replace(IMAGE_EXT, '');
  const m = /^v(\d+)-(.+)$/.exec(base);
  if (m) return { view: m[2], iteration: Number(m[1]) };
  return { view: base, iteration: null };
}

/**
 * Lists the renders of one build, sorted by iteration then by preferred view order.
 * @param {StudioRoots} roots
 * @param {string} id
 * @returns {Promise<{name: string, view: string, iteration: number|null, file: string, url: string, size: number, mtime: number}[]>}
 */
export async function listViews(roots, id) {
  if (!isBuildId(id)) return [];
  const dir = path.join(roots.outDir, id);
  const files = (await listDir(dir)).filter((f) => IMAGE_EXT.test(f) && isFileName(f));
  const views = [];
  for (const file of files) {
    const info = await stat(path.join(dir, file)).catch(() => null);
    if (!info || !info.isFile()) continue;
    const { view, iteration } = parseViewName(file);
    views.push({
      name: view,
      view,
      iteration,
      file,
      url: `/out/${encodeURIComponent(id)}/${encodeURIComponent(file)}`,
      size: info.size,
      mtime: info.mtimeMs,
    });
  }
  const rank = (v) => {
    const i = VIEW_PREFERENCE.indexOf(v);
    return i === -1 ? VIEW_PREFERENCE.length : i;
  };
  views.sort((a, b) => (a.iteration ?? -1) - (b.iteration ?? -1) || rank(a.view) - rank(b.view) || a.file.localeCompare(b.file));
  return views;
}

/**
 * Lists spec versions of a build: `spec-v0.json`, `spec-v1.json`, ..., and the final `spec.json`.
 * @param {StudioRoots} roots
 * @param {string} id
 * @returns {Promise<{version: number|null, file: string, final: boolean, url: string, size: number, mtime: number}[]>}
 */
export async function listVersions(roots, id) {
  if (!isBuildId(id)) return [];
  const dir = path.join(roots.outDir, id);
  const files = await listDir(dir);
  const versions = [];
  for (const file of files) {
    const m = /^spec(?:-v(\d+))?\.json$/.exec(file);
    if (!m) continue;
    const info = await stat(path.join(dir, file)).catch(() => null);
    if (!info || !info.isFile()) continue;
    const version = m[1] === undefined ? null : Number(m[1]);
    versions.push({
      version,
      file,
      final: version === null,
      url: `/api/build/${encodeURIComponent(id)}?version=${version === null ? 'final' : version}`,
      size: info.size,
      mtime: info.mtimeMs,
    });
  }
  versions.sort((a, b) => {
    if (a.final !== b.final) return a.final ? 1 : -1;
    return (a.version ?? 0) - (b.version ?? 0);
  });
  return versions;
}

/**
 * Loads the history of a build: `history.json`, or a `history` array embedded in `meta.json`.
 * @param {StudioRoots} roots
 * @param {string} id
 * @param {any} [meta]
 * @returns {Promise<object[]>}
 */
export async function readHistory(roots, id, meta = null) {
  const fromFile = await readJsonSafe(path.join(roots.outDir, id, 'history.json'));
  const raw = Array.isArray(fromFile) ? fromFile : Array.isArray(meta?.history) ? meta.history : [];
  return raw
    .filter((h) => h && typeof h === 'object')
    .map((h, i) => ({
      iteration: Number.isFinite(h.iteration) ? h.iteration : i,
      score: Number.isFinite(h.score) ? h.score : null,
      verdict: typeof h.verdict === 'string' ? h.verdict : '',
      issues: Array.isArray(h.issues) ? h.issues : [],
      sev: h.sev && typeof h.sev === 'object' ? h.sev : severityOf(h.issues),
      applied: Boolean(h.applied),
      errors: Array.isArray(h.errors) ? h.errors : [],
      changes: Array.isArray(h.changes) ? h.changes : undefined,
    }))
    .sort((a, b) => a.iteration - b.iteration);
}

/**
 * Counts issues by severity, tolerating several field spellings an LLM might produce.
 * @param {any[]} issues
 * @returns {{critical: number, major: number, minor: number}}
 */
export function severityOf(issues) {
  const out = { critical: 0, major: 0, minor: 0 };
  for (const issue of Array.isArray(issues) ? issues : []) {
    const sev = String(issue?.severity || issue?.level || 'minor').toLowerCase();
    if (sev === 'critical' || sev === 'blocker' || sev === 'high') out.critical++;
    else if (sev === 'major' || sev === 'medium' || sev === 'serious') out.major++;
    else out.minor++;
  }
  return out;
}

/**
 * Reads one spec version of a build.
 * @param {StudioRoots} roots
 * @param {string} id
 * @param {number|string} [version] iteration number, 'final' (default) or a spec file name
 * @returns {Promise<{version: number|null, file: string, spec: any}|null>}
 */
export async function readSpec(roots, id, version = 'final') {
  if (!isBuildId(id)) return null;
  const dir = path.join(roots.outDir, id);
  let file;
  if (typeof version === 'string' && version !== 'final' && version !== 'latest' && !/^\d+$/.test(version)) {
    if (!/^spec(?:-v\d+)?\.json$/.test(version)) return null;
    file = version;
  } else if (version === undefined || version === null || version === 'final' || version === 'latest') {
    file = 'spec.json';
    if (!existsSync(path.join(dir, file))) {
      const versions = (await listVersions(roots, id)).filter((v) => !v.final);
      if (!versions.length) return null;
      file = versions[versions.length - 1].file;
    }
  } else {
    file = `spec-v${Number(version)}.json`;
  }
  const spec = await readJsonSafe(path.join(dir, file));
  if (!spec) return null;
  const m = /^spec(?:-v(\d+))?\.json$/.exec(file);
  return { version: m && m[1] !== undefined ? Number(m[1]) : null, file, spec };
}

/**
 * Builds the summary shown in the gallery sidebar. Reads meta.json plus two directory listings;
 * it never parses specs, so a hundred builds list in milliseconds.
 * @param {StudioRoots} roots
 * @param {string} id
 * @returns {Promise<object|null>}
 */
export async function readBuildSummary(roots, id) {
  if (!isBuildId(id)) return null;
  const dir = path.join(roots.outDir, id);
  const meta = await readJsonSafe(path.join(dir, 'meta.json'));
  if (!meta) return null;
  const [views, versions, history] = await Promise.all([
    listViews(roots, id),
    listVersions(roots, id),
    readHistory(roots, id, meta),
  ]);
  const last = history.length ? history[history.length - 1] : null;
  const best = history.reduce((acc, h) => (h.score !== null && (acc === null || h.score > acc) ? h.score : acc), null);
  const thumb = views.find((v) => v.iteration === null && v.view === 'se')
    || views.find((v) => v.view === 'se')
    || views.find((v) => v.view === 'front')
    || views[0]
    || null;
  // The object count is normally in meta.json, but a build written by an older pipeline (or by
  // hand) may only have the spec. Only then is the spec read — the gallery listing stays cheap.
  let objects = Number.isFinite(meta.objects) ? meta.objects : null;
  if (objects === null) {
    const candidates = ['spec.json', ...[...versions].filter((v) => !v.final).reverse().map((v) => v.file)];
    for (const candidate of candidates) {
      const spec = await readJsonSafe(path.join(dir, candidate));
      if (Array.isArray(spec?.objects)) {
        objects = spec.objects.length;
        break;
      }
    }
  }
  return {
    id,
    name: meta.name || meta.spec?.name || id,
    prompt: typeof meta.prompt === 'string' ? meta.prompt : '',
    status: meta.status || 'ready',
    createdAt: meta.createdAt || meta.created_at || null,
    updatedAt: meta.updatedAt || meta.updated_at || null,
    objects,
    iterations: Number.isFinite(meta.iterations) ? meta.iterations : history.length,
    score: Number.isFinite(meta.score) ? meta.score : last?.score ?? null,
    bestScore: best,
    issues: last?.sev || severityOf([]),
    verdict: last?.verdict || '',
    versionCount: versions.length,
    viewCount: views.length,
    thumbnail: thumb ? thumb.url : null,
    views: views.slice(0, 1),
    error: meta.error || (meta.status === 'error' ? String(meta.message || 'build failed') : null),
  };
}

/**
 * Lists every build that has a `meta.json`, newest first.
 * @param {StudioRoots} roots
 * @returns {Promise<object[]>}
 */
export async function listBuilds(roots) {
  const ids = await listBuildIds(roots.outDir);
  const out = [];
  for (const id of ids) {
    const summary = await readBuildSummary(roots, id);
    if (summary) out.push(summary);
  }
  return out;
}

/**
 * Reads everything the viewer needs for one build: meta, final spec, versions, history and views.
 * @param {StudioRoots} roots
 * @param {string} id
 * @param {{version?: number|string}} [options]
 * @returns {Promise<object|null>}
 */
export async function readBuild(roots, id, options = {}) {
  const summary = await readBuildSummary(roots, id);
  if (!summary) return null;
  const meta = (await readJsonSafe(path.join(roots.outDir, id, 'meta.json'))) || {};
  const wanted = options.version === undefined ? 'final' : options.version;
  const current = await readSpec(roots, id, wanted);
  const [versions, history, views] = await Promise.all([
    listVersions(roots, id),
    readHistory(roots, id, meta),
    listViews(roots, id),
  ]);
  return {
    ...summary,
    meta,
    version: current ? current.version : null,
    specFile: current ? current.file : null,
    spec: current ? current.spec : null,
    versions: versions.map((v) => ({
      version: v.version,
      file: v.file,
      final: v.final,
      url: v.url,
      size: v.size,
    })),
    history,
    views,
  };
}

/**
 * Applies a build status change to meta.json without touching other fields.
 * @param {StudioRoots} roots
 * @param {string} id
 * @param {Record<string, any>} patch
 * @returns {Promise<object|null>}
 */
export async function updateMeta(roots, id, patch) {
  if (!isBuildId(id)) return null;
  const file = path.join(roots.outDir, id, 'meta.json');
  const meta = (await readJsonSafe(file)) || { id };
  const next = { ...meta, ...patch, id, updatedAt: new Date().toISOString() };
  await writeFile(file, `${JSON.stringify(next, null, 2)}\n`);
  return next;
}

/**
 * Creates a new build directory with a `meta.json` in status `pending`.
 *
 * The real generation is wired later by the architect (the orchestrator writes more files into this
 * directory and flips the status). When `sample` names a file in `three/samples/`, the sample spec is
 * copied in as `spec-v0.json` + `spec.json` and the build starts as `ready`, which makes the studio
 * demoable before the LLM is connected.
 *
 * @param {StudioRoots} roots
 * @param {{prompt?: string, sample?: string, name?: string, source?: string}} request
 * @returns {Promise<{id: string, meta: object, sample?: string}>}
 */
export async function createBuild(roots, request = {}) {
  const prompt = typeof request.prompt === 'string' ? request.prompt.trim() : '';
  const sample = typeof request.sample === 'string' ? request.sample.trim() : '';
  if (!prompt && !sample) throw Object.assign(new Error('prompt is required'), { statusCode: 400 });
  if (prompt.length > 20_000) throw Object.assign(new Error('prompt is too long (max 20000 characters)'), { statusCode: 400 });

  let sampleSpec = null;
  if (sample) {
    if (!/^[A-Za-z0-9][A-Za-z0-9._-]*$/.test(sample)) throw Object.assign(new Error('invalid sample name'), { statusCode: 400 });
    const file = path.join(roots.projectRoot, 'samples', sample.endsWith('.json') ? sample : `${sample}.json`);
    sampleSpec = await readJsonSafe(file);
    if (!sampleSpec) throw Object.assign(new Error(`sample '${sample}' not found`), { statusCode: 404 });
  }

  const id = await allocateBuildId(roots.outDir);
  const dir = path.join(roots.outDir, id);
  await mkdir(dir, { recursive: true });
  const now = new Date().toISOString();
  const meta = {
    id,
    prompt: prompt || `Sample: ${sample}`,
    name: typeof request.name === 'string' && request.name ? request.name : sampleSpec?.name || '',
    status: sampleSpec ? 'ready' : 'pending',
    createdAt: now,
    updatedAt: now,
    objects: sampleSpec?.objects?.length ?? null,
    source: request.source || (sample ? 'sample' : 'api'),
    versions: 0,
  };
  if (sampleSpec) {
    const body = `${JSON.stringify(sampleSpec, null, 2)}\n`;
    await writeFile(path.join(dir, 'spec-v0.json'), body);
    await writeFile(path.join(dir, 'spec.json'), body);
    meta.versions = 1;
  }
  await writeFile(path.join(dir, 'meta.json'), `${JSON.stringify(meta, null, 2)}\n`);
  return { id, meta, sample: sample || undefined };
}

/**
 * Lists the prepared sample specs (`three/samples/*.json`) the studio can open as a build.
 * @param {StudioRoots} roots
 * @returns {Promise<{name: string, file: string, title: string, objects: number|null}[]>}
 */
export async function listSamples(roots) {
  const dir = path.join(roots.projectRoot, 'samples');
  const files = (await listDir(dir)).filter((f) => f.endsWith('.json') && isFileName(f));
  const out = [];
  for (const file of files) {
    const spec = await readJsonSafe(path.join(dir, file));
    out.push({ name: file.replace(/\.json$/, ''), file, title: spec?.name || file, objects: Array.isArray(spec?.objects) ? spec.objects.length : null });
  }
  return out;
}

/** Convenience for callers that only have options rather than resolved roots. */
export function rootsFrom(options = {}) {
  return resolveRoots(options);
}
