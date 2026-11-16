// The explorer's page. It owns exactly one piece of state — what the viewer chose — and keeps
// it in the address: nest, link, select, open (the opened paths) and sel (the selected path).
// Everything drawn is derived from the server's answer for that address, so a reload, a shared
// link and the back button all mean the same thing.

const SVG = 'http://www.w3.org/2000/svg';
const $ = (id) => document.getElementById(id);
const key = (path) => JSON.stringify(path);

// ── the address ──────────────────────────────────────────────────────────────

function readState() {
  const q = new URLSearchParams(location.search);
  const list = (k) => (q.get(k) || '').split(',').map((s) => s.trim()).filter(Boolean);
  let open = [];
  let sel = null;
  let root = [];
  try { root = JSON.parse(q.get('root') || '[]'); } catch (_) { root = []; }
  try { open = JSON.parse(q.get('open') || '[]'); } catch (_) { open = []; }
  try { sel = JSON.parse(q.get('sel') || 'null'); } catch (_) { sel = null; }
  return { nest: list('nest'), link: list('link'), select: q.get('select') || '', unplaced: q.get('unplaced') === '1', embed: q.get('embed') === '1', root, open, sel };
}

function writeState(state, push) {
  const q = new URLSearchParams();
  if (state.nest.length) q.set('nest', state.nest.join(','));
  if (state.link.length) q.set('link', state.link.join(','));
  if (state.select) q.set('select', state.select);
  if (state.unplaced) q.set('unplaced', '1');
  if (state.embed) q.set('embed', '1');
  if (state.root.length) q.set('root', JSON.stringify(state.root));
  if (state.open.length) q.set('open', JSON.stringify(state.open));
  if (state.sel) q.set('sel', JSON.stringify(state.sel));
  const url = location.pathname + (q.toString() ? '?' + q : '');
  if (push) history.pushState(null, '', url); else history.replaceState(null, '', url);
}

let state = readState();
// Shown inside another page, which has its own title: `embed=1` drops this one. Every request is
// relative, so the page also works when a host serves it under a path of its own.
document.body.classList.toggle('embedded', state.embed);
let relations = [];
let drawn = 0; // guards against an older answer landing after a newer one

// ── colour from a name, so no sort or relation is known here ─────────────────

function hue(name) {
  let h = 0;
  for (let i = 0; i < name.length; i++) h = (h * 31 + name.charCodeAt(i)) >>> 0;
  return h % 360;
}
const dark = () => matchMedia('(prefers-color-scheme: dark)').matches;
const sortColour = (sort) => `hsl(${hue(sort)} 45% ${dark() ? 62 : 42}%)`;
const relColour = (rel) => `hsl(${hue('rel:' + rel)} 60% ${dark() ? 66 : 40}%)`;
const shortSort = (sort) => sort.slice(sort.lastIndexOf('/') + 1);

// ── the pickers ──────────────────────────────────────────────────────────────

function fillMenu(menu, chosen, onChange) {
  menu.textContent = '';
  const stored = relations.filter((r) => r.edges > 0);
  const rest = relations.filter((r) => r.edges === 0);
  const section = (title, rels) => {
    if (!rels.length) return;
    const head = document.createElement('div');
    head.className = 'sep';
    head.textContent = title;
    menu.append(head);
    for (const r of rels) {
      const label = document.createElement('label');
      const box = document.createElement('input');
      box.type = 'checkbox';
      box.checked = chosen.includes(r.name);
      box.addEventListener('change', () => onChange(r.name, box.checked));
      const count = document.createElement('small');
      count.textContent = r.edges ? r.edges : '';
      label.append(box, r.name, count);
      menu.append(label);
    }
  };
  section('declared between nodes', stored);
  section('derived or unused', rest);
}

function renderPickers() {
  const toggle = (field) => (name, on) => {
    state[field] = on ? [...state[field], name] : state[field].filter((n) => n !== name);
    if (field === 'nest') { state.open = []; state.sel = null; state.root = []; }
    writeState(state, true);
    renderPickers();
    load();
  };
  fillMenu($('nest-menu'), state.nest, toggle('nest'));
  fillMenu($('link-menu'), state.link, toggle('link'));
  $('nest-sum').textContent = state.nest.join(', ') || 'choose…';
  $('link-sum').textContent = state.link.join(', ') || 'none';
  $('select').value = state.select;
}

// ── asking ───────────────────────────────────────────────────────────────────

function notice(text, bad) {
  const el = $('notice');
  el.className = text ? (bad ? 'on bad' : 'on') : '';
  el.firstElementChild.textContent = text || '';
}

async function load() {
  const ticket = ++drawn;
  if (!state.nest.length) {
    clearCanvas();
    notice(relations.length
      ? 'Choose a relation to nest by. Its edges put their target inside their source.'
      : 'No model is loaded. Build one at the REPL, then reload.');
    return;
  }
  const q = new URLSearchParams({ nest: state.nest.join(','), link: state.link.join(','), open: JSON.stringify(state.open) });
  if (state.select) q.set('select', state.select);
  if (state.unplaced) q.set('unplaced', '1');
  if (state.root.length) q.set('root', state.root[state.root.length - 1]);
  showCrumbs();
  let body;
  try {
    body = await (await fetch('api/graph?' + q)).json();
  } catch (e) {
    if (ticket === drawn) { clearCanvas(); notice('The explorer did not answer: ' + e.message, true); }
    return;
  }
  if (ticket !== drawn) return;
  $('at').textContent = 'model read ' + new Date(body.at).toLocaleTimeString();
  const [outcome, value] = body.answer; // exactly one of: graph, rejected, unanswerable, no-model
  const a = { graph: outcome === 'graph' ? value : null };
  showUnplaced(a.graph ? a.graph.unplaced : []);
  if (outcome === 'no-model') { clearCanvas(); notice('No model is loaded. Build one at the REPL, then reload.'); return; }
  if (outcome === 'rejected') { clearCanvas(); notice(value, true); return; }
  if (outcome === 'unanswerable') { clearCanvas(); notice('The model could not answer this view:\n' + value, true); return; }
  showDetail();
  if (!a.graph.nodes.length) {
    clearCanvas();
    notice(a.graph.unplaced.length
      ? 'Nothing in this view is placed by ' + state.nest.join(', ') + '.'
      : 'Nothing in the declared design matches this view.');
    return;
  }
  notice('');
  await draw(a.graph, ticket);
}

// The nodes the chosen nesting says nothing about: they are a gap in the model, so the page says
// how many there are and of what sort whether or not it is drawing them.
function showUnplaced(counts) {
  const box = $('unplaced');
  box.textContent = '';
  box.classList.toggle('on', counts.length > 0);
  if (!counts.length) return;
  const total = counts.reduce((n, c) => n + c.count, 0);
  const head = document.createElement('b');
  head.textContent = total + (total === 1 ? ' node is' : ' nodes are') + ' not placed by ' + state.nest.join(', ');
  const toggle = document.createElement('button');
  toggle.textContent = state.unplaced ? 'Hide' : 'Show';
  toggle.addEventListener('click', () => { state.unplaced = !state.unplaced; writeState(state, true); load(); });
  box.append(head, ' · ' + counts.map((c) => c.count + ' ' + shortSort(c.sort)).join(', '), toggle);
}

// The view's root is kept as the PATH that was dived along, though the server is asked only for
// its last id: the path is what lets the viewer step back out one level at a time.
function showCrumbs() {
  const nav = $('crumbs');
  nav.textContent = '';
  nav.classList.toggle('on', state.root.length > 0);
  const step = (label, depth) => {
    const a = document.createElement(depth === state.root.length ? 'b' : 'a');
    a.textContent = label;
    if (depth !== state.root.length) a.addEventListener('click', () => setRoot(state.root.slice(0, depth)));
    return a;
  };
  nav.append(step('top', 0));
  state.root.forEach((id, i) => nav.append(' › ', step(id.slice(id.lastIndexOf('/') + 1), i + 1)));
}

function setRoot(path) {
  state.root = path; state.open = []; state.sel = null;
  writeState(state, true);
  showDetail();
  load();
}

// ── layout ───────────────────────────────────────────────────────────────────

const elk = new ELK();
const LEAF_H = 40;
const leafWidth = (n) => Math.max(96, 30 + 7 * Math.max(n.label.length, shortSort(n.sort).length * 0.85 + 4));

function toElk(graph) {
  const byKey = new Map(graph.nodes.map((n) => [key(n.path), { n, kids: [] }]));
  const roots = [];
  for (const entry of byKey.values()) {
    const parent = entry.n.path.length > 1 ? byKey.get(key(entry.n.path.slice(0, -1))) : null;
    (parent ? parent.kids : roots).push(entry);
  }
  const build = ({ n, kids }) => {
    const node = { id: key(n.path), labels: [{ text: n.label }] };
    if (kids.length) {
      node.children = kids.map(build);
      node.layoutOptions = { 'elk.padding': '[top=40,left=14,bottom=14,right=14]' };
    } else {
      node.width = leafWidth(n);
      node.height = LEAF_H;
    }
    return node;
  };
  return {
    id: 'root',
    layoutOptions: {
      'elk.algorithm': 'layered',
      'elk.direction': 'DOWN',
      'elk.hierarchyHandling': 'INCLUDE_CHILDREN',
      'elk.layered.spacing.nodeNodeBetweenLayers': '46',
      'elk.spacing.nodeNode': '22',
      'elk.spacing.edgeNode': '14',
      'elk.layered.considerModelOrder.strategy': 'NODES_AND_EDGES',
    },
    children: roots.map(build),
    edges: graph.edges.map((e, i) => ({ id: 'e' + i, sources: [key(e.from)], targets: [key(e.to)] })),
  };
}

// ── drawing ──────────────────────────────────────────────────────────────────

function el(name, attrs, text) {
  const node = document.createElementNS(SVG, name);
  for (const [k, v] of Object.entries(attrs || {})) node.setAttribute(k, v);
  if (text != null) node.textContent = text;
  return node;
}

function clearCanvas() {
  $('boxes').textContent = '';
  $('nodes').textContent = '';
  $('edges').textContent = '';
  $('defs').textContent = '';
  $('legend').textContent = '';
}

let fitted = null; // the view and drawing size the viewport was last fitted for

async function draw(graph, ticket) {
  const laid = await elk.layout(toElk(graph));
  if (ticket !== drawn) return;
  clearCanvas();
  const info = new Map(graph.nodes.map((n) => [key(n.path), n]));
  const origin = new Map([['root', { x: 0, y: 0 }]]);

  const place = (node, ox, oy) => {
    const x = ox + node.x, y = oy + node.y;
    origin.set(node.id, { x, y });
    const n = info.get(node.id);
    const colour = sortColour(n.sort);
    const g = el('g', { class: 'node' + (n.open ? ' open' : '') + (key(state.sel) === node.id ? ' sel' : ''), transform: `translate(${x},${y})` });
    g.append(el('rect', { width: node.width, height: node.height, rx: 7, stroke: colour }));
    g.append(el('text', { x: 12, y: 17 }, n.label));
    g.append(el('text', { x: 12, y: 31, class: 'kind' }, shortSort(n.sort)));
    if (n.members > 0) {
      const t = el('g', { class: 'toggle', transform: `translate(${node.width - 44},4)` });
      t.append(el('rect', { width: 40, height: 22 }));
      t.append(el('text', { x: 36, y: 15, 'text-anchor': 'end' }, (n.open ? '▾ ' : '▸ ') + n.members));
      t.addEventListener('click', (ev) => { ev.stopPropagation(); toggleOpen(n.path); });
      g.append(t);
    }
    g.addEventListener('click', (ev) => { ev.stopPropagation(); select(n.path); });
    g.addEventListener('dblclick', (ev) => { ev.stopPropagation(); if (n.members > 0) setRoot([...state.root, ...n.path]); });
    // An opened box is drawn UNDER the edges and a closed one over them: an edge to a member
    // runs across its container's interior, and an opaque box above it would cut the edge off
    // at the border, reading as an edge to the container.
    ((node.children || []).length ? $('boxes') : $('nodes')).append(g);
    for (const child of node.children || []) place(child, x, y);
  };
  for (const child of laid.children || []) place(child, 0, 0);

  const rels = [...new Set(graph.edges.map((e) => e.relation))];
  for (const rel of rels) {
    const marker = el('marker', { id: 'arrow-' + rels.indexOf(rel), viewBox: '0 0 10 10', refX: 9, refY: 5, markerWidth: 9, markerHeight: 9, markerUnits: 'userSpaceOnUse', orient: 'auto-start-reverse' });
    marker.append(el('path', { d: 'M0,0 L10,5 L0,10 z', fill: relColour(rel) }));
    $('defs').append(marker);
  }
  const drawEdges = (container) => {
    for (const edge of container.edges || []) {
      const e = graph.edges[Number(edge.id.slice(1))];
      const o = origin.get(edge.container || container.id) || { x: 0, y: 0 };
      const g = el('g', { class: 'edge' });
      for (const s of edge.sections || []) {
        const pts = [s.startPoint, ...(s.bendPoints || []), s.endPoint].map((p) => [p.x + o.x, p.y + o.y]);
        const d = pts.map((p, i) => (i ? 'L' : 'M') + p[0] + ',' + p[1]).join(' ');
        g.append(el('path', { d, stroke: relColour(e.relation), 'stroke-width': Math.min(4, 1 + Math.log2(e.count)), 'marker-end': `url(#arrow-${rels.indexOf(e.relation)})`, opacity: 0.85 }));
        if (e.count > 1) {
          const mid = pts[Math.floor((pts.length - 1) / 2)], nxt = pts[Math.floor((pts.length - 1) / 2) + 1];
          g.append(el('text', { x: (mid[0] + nxt[0]) / 2 + 4, y: (mid[1] + nxt[1]) / 2 - 3 }, e.count));
        }
      }
      const title = el('title', {}, `${e.from[e.from.length - 1]} —${e.relation}→ ${e.to[e.to.length - 1]}` + (e.count > 1 ? ` (${e.count} declared edges)` : ''));
      g.append(title);
      $('edges').append(g);
    }
    for (const child of container.children || []) drawEdges(child);
  };
  drawEdges(laid);

  const legend = $('legend');
  for (const rel of rels) {
    const item = document.createElement('span');
    const swatch = document.createElement('i');
    swatch.style.borderColor = relColour(rel);
    item.append(swatch, rel);
    legend.append(item);
  }
  for (const sort of [...new Set(graph.nodes.map((n) => n.sort))].sort()) {
    const item = document.createElement('span');
    const swatch = document.createElement('u');
    swatch.style.background = sortColour(sort);
    item.title = sort;
    item.append(swatch, shortSort(sort));
    legend.append(item);
  }

  // Opening or closing a box moves everything, so the old camera would point at empty canvas;
  // a selection redraws at the same size and keeps the camera where the viewer put it.
  const view = [state.nest.join(','), state.select, state.unplaced, key(state.root), laid.width, laid.height].join('|');
  if (fitted !== view) { fit(laid.width, laid.height); fitted = view; }
}

// ── pan and zoom ─────────────────────────────────────────────────────────────

const cam = { x: 20, y: 20, k: 1 };
const applyCam = () => $('viewport').setAttribute('transform', `translate(${cam.x},${cam.y}) scale(${cam.k})`);

function fit(w, h) {
  const box = $('canvas').getBoundingClientRect();
  // a notice or the breadcrumb sits over the top of the canvas; the drawing starts below it
  const over = ['unplaced', 'crumbs'].map($).find((b) => b.classList.contains('on'));
  const top = over ? over.offsetHeight + 24 : 20;
  cam.k = Math.min(1, (box.width - 40) / Math.max(w, 1), (box.height - 50 - top) / Math.max(h, 1));
  cam.k = Math.max(cam.k, 0.15);
  cam.x = Math.max(20, (box.width - w * cam.k) / 2);
  cam.y = top;
  applyCam();
}

(function panZoom() {
  const svg = $('canvas');
  let drag = null;
  let panned = false; // the gesture now ending moved the canvas, so its click selects nothing
  svg.addEventListener('pointerdown', (ev) => {
    if (ev.target.closest('.node')) return;
    drag = { x: ev.clientX - cam.x, y: ev.clientY - cam.y, sx: ev.clientX, sy: ev.clientY };
    panned = false;
    svg.setPointerCapture(ev.pointerId);
    svg.classList.add('dragging');
  });
  svg.addEventListener('pointermove', (ev) => {
    if (!drag) return;
    if (Math.abs(ev.clientX - drag.sx) + Math.abs(ev.clientY - drag.sy) > 4) panned = true;
    cam.x = ev.clientX - drag.x; cam.y = ev.clientY - drag.y; applyCam();
  });
  const end = () => { drag = null; svg.classList.remove('dragging'); };
  svg.addEventListener('pointerup', end);
  svg.addEventListener('pointercancel', end);
  svg.addEventListener('wheel', (ev) => {
    ev.preventDefault();
    const box = svg.getBoundingClientRect();
    const px = ev.clientX - box.left, py = ev.clientY - box.top;
    const k = Math.min(3, Math.max(0.1, cam.k * Math.exp(-ev.deltaY * 0.0015)));
    cam.x = px - (px - cam.x) * (k / cam.k);
    cam.y = py - (py - cam.y) * (k / cam.k);
    cam.k = k; applyCam();
  }, { passive: false });
  svg.addEventListener('click', () => { if (!panned) select(null); });
})();

// ── what the viewer does ─────────────────────────────────────────────────────

function toggleOpen(path) {
  const k = key(path);
  const isOpen = state.open.some((p) => key(p) === k);
  // closing a box also forgets what was opened inside it, so reopening starts closed
  state.open = isOpen
    ? state.open.filter((p) => key(p.slice(0, path.length)) !== k)
    : [...state.open, path];
  writeState(state, true);
  load();
}

async function select(path) {
  if (key(path) === key(state.sel)) return;
  state.sel = path;
  writeState(state, false);
  for (const g of document.querySelectorAll('.node.sel')) g.classList.remove('sel');
  load();
}

// An authored form, coloured by token: strings, keywords, comments, brackets, and the symbol
// that heads a list. Purely lexical — it knows no sort and no slot, like the rest of the page.
function highlight(text) {
  const out = document.createDocumentFragment();
  const token = /("(?:[^"\\]|\\[\s\S])*"?)|(;[^\n]*)|(:[^\s()\[\]{}"]+)|([()\[\]{}])|([^\s()\[\]{}";:][^\s()\[\]{}"]*)|(\s+|.)/g;
  let headNext = false;
  for (const m of text.matchAll(token)) {
    const cls = m[1] ? 'str' : m[2] ? 'comment' : m[3] ? 'kw' : m[4] ? 'punct' : m[5] && headNext ? 'head' : null;
    if (cls) {
      const span = document.createElement('span');
      span.className = cls;
      // A docstring is authored wrapped for an editor's width; in a panel of another width
      // those breaks land mid-line. Rejoin them and let the panel wrap; a blank line stays.
      span.textContent = cls === 'str' ? m[0].replace(/[ \t]*\n(?![ \t]*\n)[ \t]*/g, (br, at, all) => (all[at - 1] === '\n' ? br : ' ')) : m[0];
      out.append(span);
    } else {
      out.append(m[0]);
    }
    if (!m[6]) headNext = m[4] === '(';
  }
  return out;
}

async function showDetail() {
  const aside = $('detail');
  const path = state.sel;
  if (!path) {
    aside.innerHTML = '<p class="empty">Select a box to read its declaration. Click its marker to open or close it; double-click it to dive in.</p>';
    return;
  }
  const id = path[path.length - 1];
  let d = null;
  try {
    const res = await fetch('api/node?id=' + encodeURIComponent(id));
    const [outcome, value] = (await res.json()).answer;
    if (outcome === 'declaration') d = value;
  } catch (_) { /* answered below as missing */ }
  if (key(state.sel) !== key(path)) return;
  aside.textContent = '';
  const h = document.createElement('h2');
  h.textContent = id.slice(id.lastIndexOf('/') + 1);
  aside.append(h);
  if (!d) {
    const p = document.createElement('p');
    p.className = 'empty';
    p.textContent = 'The loaded model has no node with this id any more.';
    aside.append(p);
    return;
  }
  const sort = document.createElement('div');
  sort.className = 'sort';
  sort.textContent = d.sort;
  const where = document.createElement('div');
  where.className = 'path';
  where.textContent = path.map((p) => p.slice(p.lastIndexOf('/') + 1)).join(' › ');
  const pre = document.createElement('pre');
  pre.append(highlight(d.text));
  aside.append(sort, where, pre);
}

// ── wiring ───────────────────────────────────────────────────────────────────

$('select').addEventListener('keydown', (ev) => {
  if (ev.key !== 'Enter') return;
  state.select = ev.target.value.trim();
  state.open = []; state.sel = null; state.root = [];
  writeState(state, true);
  load();
});
$('reload').addEventListener('click', () => { boot(); });
$('collapse').addEventListener('click', () => { state.open = []; writeState(state, true); load(); });
document.addEventListener('click', (ev) => {
  for (const d of document.querySelectorAll('details.pick[open]')) if (!d.contains(ev.target)) d.open = false;
});
addEventListener('popstate', () => { state = readState(); renderPickers(); showDetail(); load(); });

async function boot() {
  try {
    const [outcome, value] = (await (await fetch('api/views')).json()).answer;
    relations = outcome === 'choices' ? value.relations : [];
  } catch (_) { relations = []; }
  renderPickers();
  showDetail();
  load();
}
boot();
