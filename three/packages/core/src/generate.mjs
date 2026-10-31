/**
 * Generator: prompt -> StructureSpec, then iterative refinement guided by rendered views.
 *
 * This is the "many attempts" part of the idea: instead of one giant answer that produces blocks,
 * the model proposes a declarative spec, we render it from several angles, the model looks at the
 * pictures and patches the spec. Repeat until the critic is satisfied.
 *
 * The prompts are deliberately strict about the spec format, because a malformed spec cannot be
 * rendered at all.
 */
import { normalizeSpec, validateSpec, specDigest, specBounds, TYPES, OBJECT_TYPES } from './spec.mjs';

/** System prompt shared by every call (kept stable to benefit from provider prompt caching). */
export const SPEC_SYSTEM = `You are AIBuild, a master architect who designs Minecraft-scale buildings as a declarative
3D scene. You never write blocks directly: you write a JSON StructureSpec that a geometry engine turns into a
three.js scene, which is then voxelised into Minecraft blocks. The renderer shows your current design from several
angles and you will have to critique and improve it over many iterations.

# COORDINATES AND SCALE
Right-handed space: +x = east, +z = south, +y = up. One unit = one Minecraft block = one voxel.
The ground surface is y = 0. The MAIN ENTRANCE faces SOUTH (+z). Keep the main body at x = 0..W-1, z = 0..D-1.

# SPEC FORMAT
{"name": string, "seed": number, "palette": {"key": "#rrggbb", ...}, "notes": string, "objects": [OBJ, ...]}
Every OBJ has "type" and usually "material" (a palette key). Available types and their fields:
${Object.entries(TYPES).map(([k, v]) => `- ${k}: ${v.doc} fields: ${Object.entries(v.fields).map(([f, d]) => `${f} (${d})`).join(', ')}`).join('\n')}

# RULES
- Coordinates are integers; "from" corners are inclusive and smaller than "to".
- Openings really cut the walls: a door must be walkable (2 high, 1-2 wide, cells in front and behind free).
- A roof must cover the building completely: use "prism" with overhang 1 and a pitch that closes the ridge.
- Nothing may float: every decorative element must sit on or hang from something.
- Buildings need: plinth, walls with a frame, windows with sills and shutters, a door with a canopy, a textured
  roof with eaves, a chimney, interior floors and a staircase, and a bit of surroundings (path, garden).
- Prefer many small, meaningful objects over one huge box: the detail IS the quality.
- Answer with ONE JSON object only.`;

/** Builds the initial-spec prompt. */
export function initialPrompt(request, { maxObjects = 80 } = {}) {
  return `Design this structure: "${request}"

Return ONE JSON StructureSpec (no prose) with at most ${maxObjects} objects.
Start with the massing (plinth, walls, floors, roof), then openings, then details. Every requested feature must
appear as an object. Respect an explicitly requested size exactly (e.g. "10 на 10" -> main body x 0..9, z 0..9).`;
}

/** Builds the critique/improvement prompt for one iteration. */
export function critiquePrompt({ request, spec, analysis, viewNames, iteration, history }) {
  const b = specBounds(spec);
  return `You are reviewing YOUR OWN design, iteration ${iteration}.
Original request: "${request}"
Current size: ${b.size.join(' x ')} (x,y,z), objects: ${spec.objects.length}.
Images attached in this order: ${viewNames.join(', ')} — these are renders of exactly the spec below.

CURRENT SPEC:
\`\`\`json
${JSON.stringify(spec)}
\`\`\`

SPEC DIGEST:
${specDigest(spec)}

OBJECTIVE ANALYSIS (program checks, these are facts):
${JSON.stringify(analysis, null, 2)}

${history ? `WHAT YOU ALREADY CHANGED IN PREVIOUS ITERATIONS:\n${history}\n` : ''}
Look at the images as an architect. Judge: silhouettes and proportions, roof shape and coverage, facade depth and
rhythm, window and door placement, whether the build looks like the request, and whether anything looks broken,
floating, empty or flat. Then return JSON:
{
 "issues": [{"severity":"critical|major|minor","what":"...","where":"..."}],
 "scores": {"shape":0-10,"detail":0-10,"style":0-10,"structure":0-10},
 "verdict": "one sentence",
 "patch": {"remove": ["objectId", ...], "replace": [OBJ, ...], "add": [OBJ, ...]}
}
Rules for the patch: use ids from the spec for remove/replace; replaced objects keep their id; added objects get a
short new id; coordinates must follow the spec rules. Fix every critical/major issue you list. Keep the parts that
already look good. Answer with ONE JSON object.`;
}

/** Applies a model patch to a spec (pure; invalid entries are dropped and reported). */
export function applyPatch(spec, patch) {
  const errors = [];
  const out = normalizeSpec({ ...spec, objects: spec.objects.map((o) => ({ ...o })) });
  // The model may address objects by id ("walls"), by 1-based index ("9") or by 0-based index.
  // Accept all three so a small labelling mistake does not throw away a whole improvement.
  const byId = new Map();
  out.objects.forEach((o, i) => {
    if (o.id) byId.set(String(o.id), i);
    byId.set(String(i), i);          // 0-based
    byId.set(String(i + 1), i);      // 1-based (what a human counting a list would write)
  });
  const resolve = (ref) => {
    if (ref === undefined || ref === null) return undefined;
    const key = String(ref).trim().replace(/^#/, '');
    return byId.get(key);
  };
  for (const id of patch?.remove || []) {
    const idx = resolve(id);
    if (idx === undefined) {
      errors.push(`remove: unknown id ${id}`);
      continue;
    }
    out.objects[idx] = null;
  }
  for (const obj of patch?.replace || []) {
    if (!obj || !obj.id) {
      errors.push('replace: object without id');
      continue;
    }
    const idx = resolve(obj.id);
    if (idx === undefined) {
      errors.push(`replace: unknown id ${obj.id}`);
      continue;
    }
    out.objects[idx] = obj;
  }
  out.objects = out.objects.filter(Boolean);
  for (const obj of patch?.add || []) {
    if (!obj || !obj.type) {
      errors.push('add: object without type');
      continue;
    }
    if (!OBJECT_TYPES.includes(obj.type)) {
      errors.push(`add: unknown type ${obj.type}`);
      continue;
    }
    out.objects.push(obj);
  }
  const normalized = normalizeSpec(out);
  const v = validateSpec(normalized);
  if (!v.ok) errors.push(...v.errors);
  // a patch that silently dropped every change is not an improvement: report it as not applied
  if (errors.length) return { spec, applied: false, errors };
  return { spec: normalized, applied: true, errors };
}

/** Rough quality score 0..100 from the critic's per-category scores. */
export function scoreFromCritique(critique) {
  const s = critique?.scores || {};
  const keys = ['shape', 'detail', 'style', 'structure'];
  const vals = keys.map((k) => Number(s[k])).filter((n) => Number.isFinite(n));
  if (!vals.length) return null;
  return Math.round((vals.reduce((a, b) => a + b, 0) / vals.length) * 10);
}

/** Counts issues by severity. */
export function severityCounts(issues = []) {
  const c = { critical: 0, major: 0, minor: 0 };
  for (const i of issues) {
    const s = String(i?.severity || 'minor').toLowerCase();
    if (s.startsWith('crit')) c.critical++;
    else if (s.startsWith('maj')) c.major++;
    else c.minor++;
  }
  return c;
}
