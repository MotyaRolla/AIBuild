/**
 * three.js vendoring: serves the installed package to the browser without a bundler.
 *
 * three 0.187 ships `build/three.module.js` as a facade that imports `./three.core.js`, and the
 * `examples/jsm` addons import the bare specifier `'three'`. Browsers cannot resolve either, so
 * every served module is rewritten on the fly:
 *
 *   'three'                       -> '/vendor/three.module.js'
 *   'three/addons/x/y.js'         -> '/vendor/addons/x/y.js'
 *   'three/examples/jsm/x/y.js'   -> '/vendor/addons/x/y.js'
 *   './three.core.js'             -> stays relative (the browser resolves it inside /vendor/)
 *   addon-internal relative paths -> stay relative
 *   relative paths that leave the addon/build mount -> absolute '/vendor/...'
 *
 * The rewrite is a small scanner rather than a regex: it tracks string literals, comments and
 * template interpolation, so `// import 'three'` in a comment or `"from 'three'"` inside a shader
 * string is never touched. A regex could not tell those apart, and three.js is 650 kB of code.
 *
 * Rewritten text is cached in memory per roots object (tests create many roots; caches must not mix).
 */
import { readFile, stat } from 'node:fs/promises';
import path from 'node:path';

/** The module every other vendor module resolves `'three'` to. */
export const THREE_SPECIFIER = '/vendor/three.module.js';

/** URL prefix the addon tree (`three/examples/jsm/`) is mounted at. */
export const ADDONS_PREFIX = '/vendor/addons/';

/**
 * Text that may immediately precede a module specifier:
 *   `from '…'` (import/export), `import '…'` (side-effect import), `import('…')` (dynamic).
 * A bare `(` is deliberately NOT enough — `new Foo( '.bar[' + x )` is an ordinary string.
 */
const SPECIFIER_KEYWORDS = /(?:\bfrom|\bimport|\bimport\s*\()\s*$/;

/**
 * Rewrites module specifiers the browser cannot resolve.
 *
 * The scanner walks the source the way a JS tokeniser would: it skips comments, string literals and
 * the template-literal parts of tagged templates, and only considers quoted text that directly
 * follows `from`, `import` or `import(`. That precision matters — three.js is 650 kB of shader
 * strings and comments, and a naive regex would corrupt them.
 *
 * @param {string} source module text
 * @param {string} baseDir absolute directory the module lives in (for relative resolution)
 * @param {string} vendorExamplesDir absolute `three/examples/jsm` directory
 * @param {string} vendorRoot absolute directory mounted at `/vendor/` (three/build)
 * @returns {string} rewritten module text
 */
export function rewriteModule(source, baseDir, vendorExamplesDir, vendorRoot) {
  const chunks = [];
  let codeStart = 0;
  let length = source.length;

  /**
   * Records a specifier rewrite, if the string is one.
   * @param {number} quotePos index of the opening quote
   * @param {number} quoteEnd index just after the closing quote
   * @param {string} quote the quote character
   */
  const consider = (quotePos, quoteEnd, quote) => {
    const before = source.slice(Math.max(codeStart, quotePos - 80), quotePos);
    if (!SPECIFIER_KEYWORDS.test(before)) return;
    const spec = source.slice(quotePos + 1, quoteEnd - 1);
    const next = toUrl(spec, baseDir, vendorExamplesDir, vendorRoot);
    if (next === spec) return;
    chunks.push(source.slice(codeStart, quotePos), quote, next, quote);
    codeStart = quoteEnd;
  };

  /**
   * Scans code from `pos`. Returns the index of the unmatched `}` when `depth > 0` closes back to
   * zero (used to resume template scanning), otherwise `end`.
   * @param {number} pos
   * @param {number} end
   * @param {number} depth
   * @returns {number}
   */
  const scanCode = (pos, end, depth = 0) => {
    let j = pos;
    let local = depth;
    while (j < end) {
      const ch = source[j];
      if (ch === '/' && source[j + 1] === '/') {
        const nl = source.indexOf('\n', j);
        j = nl === -1 ? end : nl;
        continue;
      }
      if (ch === '/' && source[j + 1] === '*') {
        const close = source.indexOf('*/', j + 2);
        j = close === -1 ? end : close + 2;
        continue;
      }
      if (ch === '"' || ch === "'") {
        const after = skipQuoted(source, j);
        consider(j, after, ch);
        j = after;
        continue;
      }
      if (ch === '`') {
        j = scanTemplate(j);
        continue;
      }
      if (ch === '{') local++;
      else if (ch === '}') {
        if (local === 0) return j;
        local--;
      }
      j++;
    }
    return j;
  };

  /**
   * Scans a template literal starting at `pos`, including any `${...}` interpolations.
   * @param {number} pos
   * @returns {number} index just after the closing backtick
   */
  const scanTemplate = (pos) => {
    let j = pos + 1;
    while (j < length) {
      const ch = source[j];
      if (ch === '\\') {
        j += 2;
        continue;
      }
      if (ch === '`') return j + 1;
      if (ch === '$' && source[j + 1] === '{') {
        const close = scanCode(j + 2, length, 0);
        j = close < length ? close + 1 : length;
        continue;
      }
      j++;
    }
    return length;
  };

  scanCode(0, length, 0);
  chunks.push(source.slice(codeStart));
  return chunks.join('');
}

/**
 * Skips a single/double-quoted string (honouring backslash escapes) and returns the index just past
 * the closing quote. Unterminated strings stop at the end of the source.
 * @param {string} source
 * @param {number} pos index of the opening quote
 * @returns {number}
 */
function skipQuoted(source, pos) {
  const quote = source[pos];
  let j = pos + 1;
  while (j < source.length) {
    const ch = source[j];
    if (ch === '\\') {
      j += 2;
      continue;
    }
    if (ch === quote) return j + 1;
    j++;
  }
  return source.length;
}

/**
 * Maps one specifier to the URL the studio serves.
 *
 * The two mounts are `/vendor/` -> `three/build/` and `/vendor/addons/` -> `three/examples/jsm/`,
 * so a relative specifier stays untouched while it points inside the same mount (`./three.core.js`
 * from `three.module.js`, `../libs/x.js` from an addon) and becomes an absolute `/vendor/...` URL
 * only when it would cross mounts or leave the package.
 *
 * @param {string} spec
 * @param {string} baseDir directory of the importing module
 * @param {string} vendorExamplesDir
 * @param {string} vendorRoot
 * @returns {string}
 */
function toUrl(spec, baseDir, vendorExamplesDir, vendorRoot) {
  if (spec === 'three') return THREE_SPECIFIER;
  if (spec.startsWith('three/addons/')) return ADDONS_PREFIX + spec.slice('three/addons/'.length);
  if (spec.startsWith('three/examples/jsm/')) return ADDONS_PREFIX + spec.slice('three/examples/jsm/'.length);
  if (spec.startsWith('three/')) return `/vendor/${spec.slice('three/'.length)}`;

  if (!spec.startsWith('.')) return spec;

  const absolute = path.resolve(baseDir, spec);
  const sameMount = (isInside(vendorExamplesDir, baseDir) && isInside(vendorExamplesDir, absolute))
    || (isInside(vendorRoot, baseDir) && isInside(vendorRoot, absolute));
  if (sameMount) return spec;
  if (isInside(vendorExamplesDir, absolute)) return ADDONS_PREFIX + toPosix(path.relative(vendorExamplesDir, absolute));
  if (isInside(vendorRoot, absolute)) return `/vendor/${toPosix(path.relative(vendorRoot, absolute))}`;
  return spec;
}

/**
 * True when `file` is `dir` or inside it.
 * @param {string} dir
 * @param {string} file
 * @returns {boolean}
 */
function isInside(dir, file) {
  const base = path.resolve(dir);
  const target = path.resolve(file);
  return target === base || target.startsWith(base + path.sep);
}

/** @param {string} p @returns {string} */
function toPosix(p) {
  return p.split(path.sep).join('/');
}

/**
 * Maps a `/vendor/<rest>` URL path to a file inside the three package.
 * Refuses traversal and anything outside the served three directories.
 *
 * @param {string} rest path after `/vendor/` (`three.module.js`, `addons/controls/OrbitControls.js`)
 * @param {{vendorRoot: string, vendorExamplesDir: string, vendorBuildDir: string}} roots
 * @returns {{file: string, js: boolean}|null}
 */
export function resolveVendorPath(rest, roots) {
  if (typeof rest !== 'string' || !rest || rest.includes('\0')) return null;
  let decoded;
  try {
    decoded = decodeURIComponent(rest);
  } catch {
    return null;
  }
  if (decoded.includes('\0') || decoded.startsWith('/') || decoded.includes('..')) return null;

  // Friendly aliases used by the brief and by the studio's import map.
  let rel = decoded;
  if (rel === 'OrbitControls.js') rel = 'addons/controls/OrbitControls.js';

  if (rel.startsWith('addons/')) {
    const file = path.resolve(roots.vendorExamplesDir, rel.slice('addons/'.length));
    if (!isInside(roots.vendorExamplesDir, file)) return null;
    return { file, js: isJs(file) };
  }
  const file = path.resolve(roots.vendorBuildDir, rel);
  if (!isInside(roots.vendorBuildDir, file)) return null;
  return { file, js: isJs(file) };
}

/** @param {string} file */
function isJs(file) {
  return /\.(m?js)$/i.test(file);
}

/**
 * Reads a vendor file and rewrites its imports. Results are cached as text (callers encode to a
 * Buffer themselves, so a HEAD request costs no allocation).
 *
 * @param {string} file absolute path
 * @param {{vendorRoot: string, vendorExamplesDir: string, vendorBuildDir: string}} roots
 * @returns {Promise<string|null>} null when the file is missing
 */
export async function loadVendorModule(file, roots) {
  const cache = getCache(roots);
  const hit = cache.get(file);
  if (hit !== undefined) return hit;
  let info;
  try {
    info = await stat(file);
  } catch {
    return null;
  }
  if (!info.isFile()) return null;
  const source = await readFile(file, 'utf8');
  const out = isJs(file) ? rewriteModule(source, path.dirname(file), roots.vendorExamplesDir, roots.vendorRoot) : source;
  if (cache.size > 64) cache.clear();
  cache.set(file, out);
  return out;
}

/** Per-roots text cache (separate roots in tests must not share entries). */
const CACHES = new WeakMap();

/** @param {object} roots */
function getCache(roots) {
  let cache = CACHES.get(roots);
  if (!cache) {
    cache = new Map();
    CACHES.set(roots, cache);
  }
  return cache;
}

/**
 * Clears the rewrite cache — only needed when the three.js install changes under a live server.
 * @param {object} roots
 */
export function clearVendorCache(roots) {
  CACHES.delete(roots);
}
