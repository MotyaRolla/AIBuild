/**
 * Path resolution for the studio server.
 *
 * `root` is the AIBuild "three" project root (the directory that holds `packages/`, `node_modules/`
 * and `out/`). It is resolved once at start so tests can point the studio at a temporary `out/`
 * directory (or at a copied fixture) without touching the real build output.
 *
 * Everything is resolved inside the project on purpose: no request path is ever joined without
 * going through `resolveInside()` in respond.mjs.
 */
import path from 'node:path';
import { existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

/** Absolute path of `packages/studio/`. */
export const STUDIO_DIR = fileURLToPath(new URL('../', import.meta.url));

/** Absolute path of the repository root (`three/`), inferred from this module's location. */
export const DEFAULT_ROOT = path.resolve(STUDIO_DIR, '..', '..');

/**
 * @typedef {object} StudioRoots
 * @property {string} projectRoot    Root that `out/` and `node_modules/` live in.
 * @property {string} publicDir      Directory served at `/`.
 * @property {string} outDir         Directory served at `/out/<id>/<file>` and scanned by the API.
 * @property {string} threeDir       Installed three.js package (vendor source).
 * @property {string} vendorBuildDir `three/build/` (three.module.js, three.core.js, ...).
 * @property {string} vendorControlsDir `three/examples/jsm/controls/`.
 * @property {string} vendorExamplesDir `three/examples/jsm/`.
 * @property {string} vendorRoot    Directory mounted at `/vendor/` (== vendorBuildDir).
 */

/**
 * Resolves the directories the studio serves from.
 *
 * Explicit `publicDir` / `outDir` / `vendorDir` win; otherwise they are derived from `root`.
 * When the bundled public directory or three.js is missing under `root`, the copy that ships with
 * this package / repo is used, so a data-only root still produces a working studio.
 *
 * @param {{root?: string, publicDir?: string, outDir?: string, vendorDir?: string}} [options]
 * @returns {StudioRoots}
 */
export function resolveRoots(options = {}) {
  const projectRoot = path.resolve(options.root || DEFAULT_ROOT);

  const wantedPublic = path.resolve(options.publicDir || path.join(projectRoot, 'packages', 'studio', 'public'));
  const publicDir = existsSync(wantedPublic) ? wantedPublic : path.join(STUDIO_DIR, 'public');

  const outDir = path.resolve(options.outDir || path.join(projectRoot, 'out'));

  const wantedThree = path.resolve(options.vendorDir || path.join(projectRoot, 'node_modules', 'three'));
  const threeDir = existsSync(wantedThree) ? wantedThree : path.join(DEFAULT_ROOT, 'node_modules', 'three');

  return {
    projectRoot,
    publicDir,
    outDir,
    threeDir,
    vendorBuildDir: path.join(threeDir, 'build'),
    vendorControlsDir: path.join(threeDir, 'examples', 'jsm', 'controls'),
    vendorExamplesDir: path.join(threeDir, 'examples', 'jsm'),
    vendorRoot: path.join(threeDir, 'build'),
  };
}
