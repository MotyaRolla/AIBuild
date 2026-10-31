/**
 * AIBuild 3D studio — browser app.
 *
 * No framework, no build step: plain ES modules loaded straight from the node server. The app owns
 *   • the build sidebar (GET /api/builds) and the new-build form (POST /api/build),
 *   • one or two three.js viewports (single / compare mode) that render the StructureSpec live,
 *   • the inspector tabs: per-iteration score and issues, the render gallery, the raw spec.
 *
 * The viewer intentionally uses the same core geometry module as the offline renderer, so the live
 * model and the PNG renders agree. When a build has no spec yet (status "pending") the viewer says so
 * instead of pretending to show a building.
 */
import * as THREE from 'three';
import { OrbitControls } from 'three/addons/controls/OrbitControls.js';
import { buildPreviewGroup, addEdgeOverlay, boundsOfBox, disposeGroup } from '/viewer.js';

/* ───────────────────────── state ───────────────────────── */

const state = {
  builds: [],
  samples: [],
  build: null,
  /** 'single' | 'compare' */
  mode: 'single',
  /** which spec version each pane shows: 'a' | 'b' -> version key ('final' | 0 | 1 | ...) */
  paneVersion: { a: 'final', b: 'final' },
  /** most recent spec loaded per pane, to avoid rebuilding identical geometry */
  paneSpecKey: { a: null, b: null },
  renderIteration: 'all',
  edges: false,
  tab: 'iterations',
};

const CAMERA_PRESETS = {
  se: { azimuth: Math.PI * 0.75, elevation: 0.28 },
  sw: { azimuth: Math.PI * 0.25, elevation: 0.28 },
  ne: { azimuth: -Math.PI * 0.25, elevation: 0.28 },
  nw: { azimuth: -Math.PI * 0.75, elevation: 0.28 },
  front: { azimuth: Math.PI, elevation: 0.12 },
  top: { azimuth: Math.PI * 0.75, elevation: 1.35 },
  iso: { azimuth: Math.PI * 0.68, elevation: 0.42 },
};

/* ───────────────────────── small DOM helpers ───────────────────────── */

const $ = (id) => document.getElementById(id);
const el = (tag, className, text) => {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text !== undefined && text !== null) node.textContent = text;
  return node;
};
const clear = (node) => {
  while (node.firstChild) node.removeChild(node.firstChild);
  return node;
};

/** @param {string} text @param {'ok'|'err'|''} [kind] */
function status(text, kind = '') {
  const node = $('status-text');
  node.textContent = text;
  node.className = `${kind === 'busy' ? 'busy' : kind === 'err' ? 'err' : ''}`.trim();
}

/** Picks a score's colour band (0..10). */
function scoreClass(score) {
  if (!Number.isFinite(score)) return '';
  if (score >= 7.5) return '';
  if (score >= 5) return 'low';
  return 'bad';
}

/** Human relative time ("2 min ago") for build cards. */
function timeAgo(iso) {
  if (!iso) return '';
  const t = Date.parse(iso);
  if (!Number.isFinite(t)) return '';
  const seconds = Math.round((Date.now() - t) / 1000);
  if (seconds < 60) return 'just now';
  const minutes = Math.round(seconds / 60);
  if (minutes < 60) return `${minutes} min ago`;
  const hours = Math.round(minutes / 60);
  if (hours < 24) return `${hours} h ago`;
  const days = Math.round(hours / 24);
  return days === 1 ? 'yesterday' : `${days} d ago`;
}

/**
 * Fetches JSON, throwing a readable error that includes the server's message.
 * @param {string} url
 * @param {RequestInit} [init]
 */
async function api(url, init) {
  const response = await fetch(url, init);
  const type = response.headers.get('content-type') || '';
  const body = type.includes('application/json') ? await response.json() : await response.text();
  if (!response.ok) {
    const message = typeof body === 'object' && body ? body.error : String(body).slice(0, 200);
    throw new Error(message || `${response.status} ${response.statusText}`);
  }
  return body;
}

/* ───────────────────────── viewport ───────────────────────── */

/**
 * One three.js viewport: renderer, scene, camera, orbit controls and the loaded spec.
 * Two of these exist in compare mode and render independently.
 */
class Viewport {
  /**
   * @param {string} id suffix for the canvas / error elements ('a' | 'b')
   */
  constructor(id) {
    this.id = id;
    this.canvas = $(`view-${id}`);
    this.errorEl = $(`err-${id}`);
    this.hudEl = $(`hud-${id}`);
    this.versionSelect = $(`version-${id}`);
    this.specKey = null;
    this.controls = null;
    this.group = null;
    this.edgeLines = [];
    /** Last bounds the camera was framed to (kept so "Reset view" can re-frame). */
    this.lastBounds = null;

    this.renderer = new THREE.WebGLRenderer({ canvas: this.canvas, antialias: true, alpha: false });
    this.renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2));
    this.renderer.shadowMap.enabled = true;
    this.renderer.shadowMap.type = THREE.PCFSoftShadowMap;
    this.renderer.outputColorSpace = THREE.SRGBColorSpace;
    this.renderer.toneMapping = THREE.ACESFilmicToneMapping;
    this.renderer.toneMappingExposure = 1.05;

    this.scene = new THREE.Scene();
    this.scene.background = new THREE.Color('#14181d');
    this.scene.fog = new THREE.Fog('#14181d', 90, 260);

    this.camera = new THREE.PerspectiveCamera(38, 1, 0.1, 4000);
    this.camera.position.set(22, 16, 26);

    this.controls = new OrbitControls(this.camera, this.canvas);
    this.controls.enableDamping = true;
    this.controls.dampingFactor = 0.08;
    this.controls.minDistance = 3;
    this.controls.maxDistance = 600;
    this.controls.maxPolarAngle = Math.PI * 0.495;
    this.controls.target.set(0, 4, 0);
    this.controls.addEventListener('change', () => this.requestRender());

    this.buildLights();
    this.buildGround();

    this.needsRender = true;

    const resize = () => this.resize();
    new ResizeObserver(resize).observe(this.canvas.parentElement || this.canvas);
    window.addEventListener('resize', resize);
    resize();
    this.loop = this.loop.bind(this);
    requestAnimationFrame(this.loop);
  }

  buildLights() {
    const hemi = new THREE.HemisphereLight('#cfe0f2', '#3a3327', 1.15);
    this.scene.add(hemi);

    const sun = new THREE.DirectionalLight('#fff1d6', 2.1);
    sun.position.set(38, 62, 24);
    sun.castShadow = true;
    sun.shadow.mapSize.set(1024, 1024);
    sun.shadow.camera.near = 1;
    sun.shadow.camera.far = 260;
    sun.shadow.bias = -0.0004;
    sun.shadow.normalBias = 0.04;
    const d = 48;
    sun.shadow.camera.left = -d;
    sun.shadow.camera.right = d;
    sun.shadow.camera.top = d;
    sun.shadow.camera.bottom = -d;
    this.scene.add(sun);
    this.sun = sun;

    const fill = new THREE.DirectionalLight('#93b4d6', 0.55);
    fill.position.set(-34, 22, -30);
    this.scene.add(fill);

    const rim = new THREE.DirectionalLight('#e8c07a', 0.3);
    rim.position.set(8, 12, -40);
    this.scene.add(rim);
  }

  buildGround() {
    const ground = new THREE.Mesh(
      new THREE.PlaneGeometry(600, 600),
      new THREE.MeshStandardMaterial({ color: '#1a2027', roughness: 1, metalness: 0 }),
    );
    ground.rotation.x = -Math.PI / 2;
    ground.position.y = -0.02;
    ground.receiveShadow = true;
    ground.name = 'ground';
    this.scene.add(ground);

    const grid = new THREE.GridHelper(160, 160, '#3a4552', '#242c35');
    grid.position.y = 0;
    grid.material.transparent = true;
    grid.material.opacity = 0.6;
    this.scene.add(grid);
    this.grid = grid;

    // A subtle origin marker so +x/+z stay legible when the model is small.
    const axes = new THREE.AxesHelper(6);
    axes.position.y = 0.01;
    axes.material.transparent = true;
    axes.material.opacity = 0.35;
    this.scene.add(axes);
  }

  resize() {
    const parent = this.canvas.parentElement;
    if (!parent) return;
    const width = Math.max(1, parent.clientWidth);
    const height = Math.max(1, parent.clientHeight);
    this.renderer.setSize(width, height, false);
    this.camera.aspect = width / height;
    this.camera.updateProjectionMatrix();
    this.needsRender = true;
  }

  loop() {
    this.controls.update();
    if (this.needsRender) {
      this.needsRender = false;
      this.renderer.render(this.scene, this.camera);
    }
    requestAnimationFrame(this.loop);
  }

  requestRender() {
    this.needsRender = true;
  }

  showError(message) {
    if (!message) {
      this.errorEl.hidden = true;
      this.errorEl.textContent = '';
      return;
    }
    this.errorEl.hidden = false;
    this.errorEl.textContent = message;
  }

  setHud(text) {
    this.hudEl.textContent = text || '';
  }

  /**
   * Loads a spec into the viewport. Reuses the existing group when the spec is byte-identical.
   * @param {any} spec
   * @param {{preset?: string, resetCamera?: boolean}} [options]
   * @returns {Promise<{triangles: number, objects: number, size: number[], source: string}|null>}
   */
  async showSpec(spec, options = {}) {
    if (!spec || !Array.isArray(spec.objects)) {
      this.clearModel();
      this.showError('This build has no spec yet.');
      this.setHud('');
      return null;
    }
    const key = `${JSON.stringify(spec.objects).length}:${spec.objects.length}:${JSON.stringify(spec.palette || {})}`;
    if (this.specKey === key && this.group) {
      this.showError('');
      if (options.resetCamera) this.frameCamera();
      return this.lastStats;
    }
    this.clearModel();
    this.showError('');
    let built;
    try {
      built = await buildPreviewGroup(spec, { wireframe: false });
    } catch (error) {
      this.showError(`Could not build the model: ${error.message}`);
      return null;
    }
    if (!built.group.children.length) {
      this.showError('The spec produced no geometry.');
      return null;
    }
    this.group = built.group;
    this.scene.add(this.group);
    this.edgeLines = addEdgeOverlay(this.group);
    for (const line of this.edgeLines) line.visible = state.edges;
    this.specKey = key;
    this.lastStats = { ...built.stats, size: boundsOfBox(built.box).size };

    // Ground and grid follow the model footprint so a small tower is not lost on a huge grid.
    const bounds = boundsOfBox(built.box);
    const span = Math.max(bounds.size[0], bounds.size[2], 8);
    this.grid.scale.setScalar(Math.max(1, Math.round(span / 40)));
    this.grid.position.x = Math.round(bounds.center[0]);
    this.grid.position.z = Math.round(bounds.center[2]);
    this.sun.target.position.set(bounds.center[0], bounds.center[1], bounds.center[2]);
    this.sun.target.updateMatrixWorld();
    this.scene.add(this.sun.target);
    const radius = Math.max(8, Math.hypot(bounds.size[0], bounds.size[1], bounds.size[2]));
    this.sun.shadow.camera.left = -radius * 1.4;
    this.sun.shadow.camera.right = radius * 1.4;
    this.sun.shadow.camera.top = radius * 1.4;
    this.sun.shadow.camera.bottom = -radius * 1.4;
    this.sun.shadow.camera.updateProjectionMatrix();

    if (options.resetCamera !== false) this.frame(bounds, options.preset || 'se');
    return this.lastStats;
  }

  /**
   * Frames the model from a named orbit preset.
   * @param {ReturnType<typeof boundsOfBox>} bounds
   * @param {string} preset
   */
  frame(bounds, preset = 'se') {
    const { azimuth, elevation } = CAMERA_PRESETS[preset] || CAMERA_PRESETS.se;
    const radius = Math.max(4, Math.hypot(bounds.size[0], bounds.size[1], bounds.size[2]) / 2);
    const distance = radius * 2.6;
    const horizontal = Math.cos(elevation) * distance;
    this.camera.position.set(
      bounds.center[0] + Math.cos(azimuth) * horizontal,
      bounds.center[1] + Math.sin(elevation) * distance,
      bounds.center[2] + Math.sin(azimuth) * horizontal,
    );
    this.controls.target.set(bounds.center[0], bounds.center[1] - bounds.size[1] * 0.06, bounds.center[2]);
    this.controls.update();
    this.camera.near = Math.max(0.1, distance / 500);
    this.camera.far = distance * 30;
    this.camera.updateProjectionMatrix();
    this.lastBounds = bounds;
    this.needsRender = true;
  }

  frameCamera() {
    if (this.lastBounds) this.frame(this.lastBounds);
  }

  clearModel() {
    if (this.group) {
      this.scene.remove(this.group);
      disposeGroup(this.group);
      this.group = null;
    }
    this.edgeLines = [];
    this.specKey = null;
  }

  setEdges(enabled) {
    for (const line of this.edgeLines) line.visible = enabled;
    this.needsRender = true;
  }
}

/* ───────────────────────── builds sidebar ───────────────────────── */

async function loadBuilds() {
  try {
    const builds = await api('/api/builds');
    state.builds = Array.isArray(builds) ? builds : [];
  } catch (error) {
    status(`Could not load builds: ${error.message}`, 'err');
    return;
  }
  renderBuildList();
  if (!state.build && state.builds.length) await selectBuild(state.builds[0].id);
}

function renderBuildList() {
  const list = clear($('build-list'));
  $('build-count').textContent = String(state.builds.length);
  $('build-empty').hidden = state.builds.length > 0;

  for (const build of state.builds) {
    const card = el('button', 'build-card');
    card.type = 'button';
    card.dataset.id = build.id;
    card.setAttribute('role', 'option');
    if (state.build && state.build.id === build.id) card.classList.add('active');

    const thumb = el('div', build.thumbnail ? 'build-thumb' : 'build-thumb placeholder');
    if (build.thumbnail) {
      const img = el('img');
      img.src = build.thumbnail;
      img.alt = '';
      img.loading = 'lazy';
      thumb.appendChild(img);
    } else {
      thumb.textContent = build.status === 'pending' ? 'queued' : 'no render';
    }

    const main = el('div', 'build-main');
    main.appendChild(el('div', 'build-title', build.name || build.id));
    const sub = el('div', 'build-sub');
    sub.textContent = [
      build.prompt ? build.prompt.slice(0, 64) : build.id,
      timeAgo(build.updatedAt || build.createdAt),
    ].filter(Boolean).join(' · ');
    main.appendChild(sub);

    const tags = el('div', 'build-tags');
    tags.appendChild(el('span', `tag ${build.status}`, build.status));
    if (Number.isFinite(build.score)) tags.appendChild(el('span', 'tag', `score ${build.score}`));
    if (build.versionCount > 1) tags.appendChild(el('span', 'tag', `${build.versionCount} versions`));
    if (!build.thumbnail && build.viewCount) tags.appendChild(el('span', 'tag', `${build.viewCount} views`));
    main.appendChild(tags);

    card.append(thumb, main);
    card.addEventListener('click', () => selectBuild(build.id));
    list.appendChild(card);
  }
}

async function selectBuild(id) {
  status(`loading ${id}…`, 'busy');
  let build;
  try {
    build = await api(`/api/build/${encodeURIComponent(id)}`);
  } catch (error) {
    status(`Could not open ${id}: ${error.message}`, 'err');
    return;
  }
  state.build = build;
  state.renderIteration = 'all';
  $('build-name').textContent = build.name || build.id;
  document.title = `AIBuild 3D — ${build.name || build.id}`;
  renderBuildList();
  renderVersionSelects();
  renderIterations();
  renderRenders();
  renderSpecPanel();
  syncToolbar();
  await showBuildInPanes({ resetCamera: true });
  const count = build.views.length;
  status(`${build.id} · ${build.objects ?? '?'} objects · ${count} render${count === 1 ? '' : 's'} · ${build.status}`);
}

function syncToolbar() {
  const build = state.build;
  const litematic = $('download-litematic');
  if (build) {
    litematic.hidden = false;
    litematic.href = `/out/${encodeURIComponent(build.id)}/structure.litematic`;
  } else {
    litematic.hidden = true;
  }
  $('wire-btn').setAttribute('aria-pressed', String(state.edges));
}

/* ───────────────────────── versions & panes ───────────────────────── */

function renderVersionSelects() {
  const build = state.build;
  const options = [];
  if (build) {
    for (const version of build.versions) {
      if (version.final) continue;
      options.push({ key: String(version.version), label: `v${version.version}` });
    }
    options.push({ key: 'final', label: build.versions.some((v) => v.final) ? 'final' : 'latest' });
  }

  for (const id of ['version-select', 'version-a', 'version-b']) {
    const select = $(id);
    if (!select) continue;
    clear(select);
    if (!options.length) {
      const option = el('option', '', '—');
      option.value = '';
      select.appendChild(option);
      select.disabled = true;
      continue;
    }
    select.disabled = false;
    for (const option of options) {
      const node = el('option', '', option.label);
      node.value = option.key;
      select.appendChild(node);
    }
    const wanted = id === 'version-select' ? state.paneVersion.a : state.paneVersion[id === 'version-a' ? 'a' : 'b'];
    select.value = options.some((o) => o.key === wanted) ? wanted : 'final';
  }
}

/**
 * Resolves a version key to a spec object, using the already-loaded final spec when possible.
 * @param {string} key
 * @returns {Promise<any>}
 */
async function specForVersion(key) {
  const build = state.build;
  if (!build) return null;
  if ((key === 'final' || key === 'latest') && build.spec) return build.spec;
  const body = await api(`/api/build/${encodeURIComponent(build.id)}/spec?version=${encodeURIComponent(key)}`);
  return body.spec;
}

async function showBuildInPanes({ resetCamera = false, preset } = {}) {
  const panes = state.mode === 'compare' ? ['a', 'b'] : ['a'];
  for (const id of panes) {
    const viewport = viewports[id];
    if (!viewport) continue;
    const key = state.paneVersion[id];
    let spec = null;
    try {
      spec = await specForVersion(key);
    } catch (error) {
      viewport.clearModel();
      viewport.setHud('');
      viewport.showError(`Could not load spec ${key === 'final' ? 'final' : `v${key}`}: ${error.message}`);
      continue;
    }
    const stats = await viewport.showSpec(spec, { resetCamera: resetCamera || viewport.specKey === null, preset });
    if (stats) {
      const label = key === 'final' ? 'final' : `v${key}`;
      viewport.setHud(`${state.build?.id || ''} · ${label} · ${stats.triangles.toLocaleString()} tris · ${stats.source}`);
      viewport.versionSelect.value = key;
    }
  }
  const primary = viewports.a;
  if (primary?.lastStats) {
    $('stat-tris').textContent = `${primary.lastStats.triangles.toLocaleString()} tris`;
    $('stat-objects').textContent = `${primary.lastStats.objects} objects`;
    $('stat-size').textContent = primary.lastStats.size.map((v) => Math.round(v)).join(' × ');
    $('status-text').title = `source: ${primary.lastStats.source}`;
  }
}

function setMode(mode) {
  state.mode = mode;
  $('app').dataset.mode = mode;
  for (const button of document.querySelectorAll('.segmented [data-mode]')) {
    const active = button.dataset.mode === mode;
    button.classList.toggle('active', active);
    button.setAttribute('aria-selected', String(active));
  }
  $('pane-b').querySelector('.pane-head').hidden = mode !== 'compare';
  requestAnimationFrame(() => {
    viewports.a?.resize();
    viewports.b?.resize();
    if (mode === 'compare') showBuildInPanes({ resetCamera: true });
  });
}

/* ───────────────────────── inspector tabs ───────────────────────── */

function setTab(tab) {
  state.tab = tab;
  for (const button of document.querySelectorAll('.tabs .tab')) {
    const active = button.dataset.tab === tab;
    button.classList.toggle('active', active);
    button.setAttribute('aria-selected', String(active));
  }
  for (const panel of document.querySelectorAll('.tab-panel')) {
    panel.classList.toggle('active', panel.dataset.panel === tab);
  }
}

function renderIterations() {
  const container = clear($('iterations-list'));
  const build = state.build;
  if (!build) {
    container.appendChild(el('p', 'empty', 'Select a build to see its iterations.'));
    return;
  }
  if (!build.history.length) {
    const hint = el('div', 'empty');
    hint.innerHTML = build.status === 'pending'
      ? 'Queued. The generator has not produced a spec yet — refresh once the pipeline runs.'
      : 'No iteration history yet. Runs made through the CLI (<code>cli.mjs build</code>) write <code>history.json</code> next to the spec.';
    container.appendChild(hint);
    return;
  }

  // Newest first: the last iteration is the most interesting one.
  const history = [...build.history].reverse();
  for (const entry of history) {
    const card = el('article', 'iter-card');
    const head = el('div', 'iter-head');
    head.appendChild(el('span', 'iter-n', `v${entry.iteration}`));
    const sev = entry.sev || { critical: 0, major: 0, minor: 0 };
    const score = el('span', `iter-score ${scoreClass(entry.score)}`, Number.isFinite(entry.score) ? `${entry.score}` : '—');
    head.appendChild(score);
    card.appendChild(head);

    if (entry.verdict) card.appendChild(el('p', 'iter-verdict', entry.verdict));

    if (entry.issues?.length) {
      const list = el('ul', 'iter-issues');
      for (const issue of entry.issues.slice(0, 8)) {
        const item = el('li', 'iter-issue');
        const dot = el('span', `sev ${String(issue.severity || issue.level || 'minor').toLowerCase()}`);
        const text = el('span', '', typeof issue === 'string' ? issue : issue.text || issue.issue || issue.message || JSON.stringify(issue));
        item.append(dot, text);
        list.appendChild(item);
      }
      card.appendChild(list);
    }

    const badges = el('div', 'iter-badges');
    if (sev.critical) badges.appendChild(el('span', 'badge', `${sev.critical} critical`));
    if (sev.major) badges.appendChild(el('span', 'badge', `${sev.major} major`));
    if (sev.minor) badges.appendChild(el('span', 'badge', `${sev.minor} minor`));
    if (entry.applied) badges.appendChild(el('span', 'badge applied', 'patch applied'));
    if (entry.errors?.length) badges.appendChild(el('span', 'badge rejected', `${entry.errors.length} error(s)`));
    if (badges.childElementCount) card.appendChild(badges);

    const actions = el('div', 'iter-actions');
    const viewButton = el('button', 'btn ghost', `Show v${entry.iteration}`);
    viewButton.type = 'button';
    viewButton.addEventListener('click', () => {
      state.paneVersion.a = String(entry.iteration);
      $('version-select').value = String(entry.iteration);
      showBuildInPanes({ resetCamera: true });
      status(`showing spec v${entry.iteration}`);
    });
    actions.appendChild(viewButton);

    const compareButton = el('button', 'btn ghost', 'Compare with final');
    compareButton.type = 'button';
    compareButton.disabled = !build.versions.some((v) => v.final);
    compareButton.addEventListener('click', () => {
      state.paneVersion.a = String(entry.iteration);
      state.paneVersion.b = 'final';
      setMode('compare');
      renderVersionSelects();
      renderIterations();
      showBuildInPanes({ resetCamera: true });
    });
    actions.appendChild(compareButton);
    card.appendChild(actions);

    if (entry.iteration !== null) card.dataset.iteration = String(entry.iteration);
    container.appendChild(card);
  }
}

function renderRenders() {
  const build = state.build;
  const grid = clear($('renders-grid'));
  const iterationSelect = $('render-iteration');
  clear(iterationSelect);

  const allOption = el('option', '', 'all iterations');
  allOption.value = 'all';
  iterationSelect.appendChild(allOption);

  if (!build) {
    grid.appendChild(el('p', 'empty', 'No renders.'));
    return;
  }
  const iterations = [...new Set(build.views.map((v) => v.iteration).filter((v) => v !== null))].sort((a, b) => a - b);
  for (const iteration of iterations) {
    const option = el('option', '', `iteration ${iteration}`);
    option.value = String(iteration);
    iterationSelect.appendChild(option);
  }
  const loose = build.views.filter((v) => v.iteration === null);
  if (loose.length) {
    const option = el('option', '', 'unversioned');
    option.value = 'none';
    iterationSelect.appendChild(option);
  }
  iterationSelect.value = state.renderIteration;

  const filter = state.renderIteration;
  const views = build.views.filter((view) => {
    if (filter === 'all') return true;
    if (filter === 'none') return view.iteration === null;
    return view.iteration === Number(filter);
  });

  if (!views.length) {
    grid.appendChild(el('p', 'empty', build.views.length ? 'No renders for this iteration.' : 'No renders yet. The renderer writes them to out/<id>/*.png.'));
    return;
  }

  for (const view of views) {
    const figure = el('figure', 'render-item');
    const img = el('img');
    img.src = view.url;
    img.alt = `${view.view}${view.iteration === null ? '' : ` (v${view.iteration})`}`;
    img.loading = 'lazy';
    const caption = el('figcaption');
    caption.appendChild(el('span', '', view.view));
    caption.appendChild(el('span', '', view.iteration === null ? '' : `v${view.iteration}`));
    figure.append(img, caption);
    figure.addEventListener('click', () => openLightbox(view));
    grid.appendChild(figure);
  }
}

function openLightbox(view) {
  const lightbox = $('lightbox');
  $('lightbox-img').src = view.url;
  $('lightbox-img').alt = view.view;
  $('lightbox-caption').textContent = `${view.file} · ${(view.size / 1024).toFixed(1)} kB`;
  lightbox.hidden = false;
}

function renderSpecPanel() {
  const build = state.build;
  const meta = clear($('spec-meta'));
  const rows = [];
  if (build) {
    rows.push(['build', build.id]);
    rows.push(['status', build.status]);
    rows.push(['versions', String(build.versions.length)]);
    rows.push(['iterations', String(build.history.length)]);
    rows.push(['views', String(build.views.length)]);
    if (build.specFile) rows.push(['file', build.specFile]);
    if (build.createdAt) rows.push(['created', new Date(build.createdAt).toLocaleString()]);
  }
  for (const [label, value] of rows) {
    const row = el('div', 'row');
    row.appendChild(el('span', '', label));
    row.appendChild(el('b', '', value));
    meta.appendChild(row);
  }
  $('spec-json').textContent = build?.spec ? JSON.stringify(build.spec, null, 2) : '—';
}

/* ───────────────────────── create build ───────────────────────── */

async function createBuild() {
  const prompt = $('prompt').value.trim();
  const sample = $('sample-select').value;
  const hint = $('create-hint');
  hint.className = 'hint';
  if (!prompt && !sample) {
    hint.textContent = 'Write a short description first (or pick a sample).';
    hint.classList.add('err');
    return;
  }
  const button = $('create-btn');
  button.disabled = true;
  hint.textContent = 'creating…';
  try {
    const body = { prompt, sample: sample || undefined };
    const created = await api('/api/build', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify(body),
    });
    hint.textContent = created.status === 'pending'
      ? `queued ${created.id} — the generator will fill it in`
      : `created ${created.id} from ${sample}`;
    hint.classList.add('ok');
    $('prompt').value = '';
    $('sample-select').value = '';
    await loadBuilds();
    await selectBuild(created.id);
  } catch (error) {
    hint.textContent = error.message;
    hint.classList.add('err');
  } finally {
    button.disabled = false;
  }
}

async function loadSamples() {
  try {
    const samples = await api('/api/samples');
    state.samples = samples;
    const select = $('sample-select');
    for (const sample of samples) {
      const option = el('option', '', sample.title || sample.name);
      option.value = sample.name;
      select.appendChild(option);
    }
  } catch {
    /* samples are optional */
  }
}

/* ───────────────────────── wiring ───────────────────────── */

const viewports = { a: null, b: null };

function wireViewSwitcher() {
  const container = $('view-switch');
  const presets = [
    ['se', 'SE'], ['sw', 'SW'], ['ne', 'NE'], ['nw', 'NW'], ['front', 'Front'], ['top', 'Top'],
  ];
  for (const [key, label] of presets) {
    const button = el('button', 'seg', label);
    button.type = 'button';
    button.dataset.preset = key;
    button.title = `Camera: ${key}`;
    button.addEventListener('click', () => {
      for (const node of container.querySelectorAll('.seg')) node.classList.toggle('active', node === button);
      const panes = state.mode === 'compare' ? ['a', 'b'] : ['a'];
      for (const id of panes) {
        const viewport = viewports[id];
        if (!viewport?.group) continue;
        viewport.frame(boundsOfBox(viewport.group ? new THREE.Box3().setFromObject(viewport.group) : null), key);
      }
    });
    container.appendChild(button);
  }
  container.querySelector('[data-preset="se"]')?.classList.add('active');
}

function wireEvents() {
  $('create-btn').addEventListener('click', createBuild);
  $('prompt').addEventListener('keydown', (event) => {
    if (event.key === 'Enter' && (event.metaKey || event.ctrlKey)) createBuild();
  });
  $('refresh-btn').addEventListener('click', async () => {
    await loadBuilds();
    if (state.build) await selectBuild(state.build.id);
  });
  $('sidebar-toggle').addEventListener('click', () => {
    const app = $('app');
    app.classList.toggle('sidebar-hidden');
    const hidden = app.classList.contains('sidebar-hidden');
    $('sidebar-toggle').textContent = hidden ? '›' : '‹';
    $('sidebar-toggle').title = hidden ? 'Show panel' : 'Hide panel';
    requestAnimationFrame(() => viewports.a?.resize());
  });

  for (const button of document.querySelectorAll('.segmented [data-mode]')) {
    button.addEventListener('click', () => setMode(button.dataset.mode));
  }
  for (const button of document.querySelectorAll('.tabs .tab')) {
    button.addEventListener('click', () => setTab(button.dataset.tab));
  }

  $('version-select').addEventListener('change', (event) => {
    state.paneVersion.a = event.target.value || 'final';
    showBuildInPanes({ resetCamera: true });
    renderVersionSelects();
  });
  $('version-a')?.addEventListener('change', (event) => {
    state.paneVersion.a = event.target.value || 'final';
    showBuildInPanes({ resetCamera: true });
  });
  $('version-b')?.addEventListener('change', (event) => {
    state.paneVersion.b = event.target.value || 'final';
    showBuildInPanes({ resetCamera: true });
  });

  $('render-iteration').addEventListener('change', (event) => {
    state.renderIteration = event.target.value;
    renderRenders();
  });
  $('wire-btn').addEventListener('click', () => {
    state.edges = !state.edges;
    $('wire-btn').setAttribute('aria-pressed', String(state.edges));
    viewports.a?.setEdges(state.edges);
    viewports.b?.setEdges(state.edges);
  });

  $('reset-btn').addEventListener('click', () => {
    for (const id of ['a', 'b']) viewports[id]?.frameCamera();
    if (state.mode === 'single') showBuildInPanes({ resetCamera: true });
  });

  $('lightbox-close').addEventListener('click', () => { $('lightbox').hidden = true; });
  $('lightbox').addEventListener('click', (event) => {
    if (event.target === $('lightbox')) $('lightbox').hidden = true;
  });
  window.addEventListener('keydown', (event) => {
    if (event.key === 'Escape') $('lightbox').hidden = true;
  });

  $('stat-server').addEventListener('click', async () => {
    try {
      const health = await api('/api/health');
      status(`server ok · out: ${health.outDir} · three: ${health.three ? 'yes' : 'missing'} · up ${Math.round(health.uptimeMs / 1000)}s`);
    } catch (error) {
      status(`server check failed: ${error.message}`, 'err');
    }
  });
}

/** Boot: build viewports, wire UI, load data. */
async function boot() {
  wireEvents();
  wireViewSwitcher();
  setTab('iterations');
  setMode('single');

  try {
    viewports.a = new Viewport('a');
    viewports.b = new Viewport('b');
  } catch (error) {
    status(`WebGL is not available in this browser: ${error.message}`, 'err');
    $('err-a').hidden = false;
    $('err-a').textContent = 'WebGL is not available, so the live 3D preview cannot start. The PNG renders in the inspector still work.';
    return;
  }

  await Promise.all([loadSamples(), loadBuilds()]);
  if (!state.builds.length) {
    status('no builds yet — design one, or run the core pipeline');
  }

  // Poll for build progress (the generator writes into out/ asynchronously).
  setInterval(async () => {
    const active = state.build;
    if (!active) return;
    try {
      const builds = await api('/api/builds');
      const before = state.builds.find((b) => b.id === active.id);
      const after = builds.find((b) => b.id === active.id);
      state.builds = builds;
      renderBuildList();
      const changed = !before || !after ? false
        : before.status !== after.status || before.versionCount !== after.versionCount
          || before.viewCount !== after.viewCount || before.iterations !== after.iterations;
      if (changed && after) await selectBuild(active.id);
    } catch {
      /* offline for a moment: keep the current view */
    }
  }, 5000);
}

boot().catch((error) => {
  console.error(error);
  status(`studio failed to start: ${error.message}`, 'err');
});
