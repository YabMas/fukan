// The explorer's page. It owns exactly one piece of state — what the viewer chose — and keeps
// it in the address. The view itself is there as the explorer's own graph-request parameters,
// under the request's names and in its encodings: nest, link, select, open, unplaced, root, plane.
// They are sent as the address holds them, and the plane with a declaration request too, so a box
// selected in a view of a plane is answered from that plane. Which planes exist is the model's to
// say: the page offers what the explorer answers and names none itself. Beside them are three things only this page reads: trail
// (the ids dived through to reach the root), sel (the selected path) and embed.
//
// The page draws nothing. It shows the SVG the explorer answers for the address, and of that
// drawing it says one thing: which box is selected, by a class on that box's group.

const $ = (id) => document.getElementById(id);
const key = (path) => JSON.stringify(path);

// ── the address ──────────────────────────────────────────────────────────────

const VIEW = ['nest', 'link', 'select', 'open', 'unplaced', 'root', 'plane'];
const json = (text, otherwise) => { try { const v = JSON.parse(text); return v == null ? otherwise : v; } catch (_) { return otherwise; } };

// The address as the explorer's transport reads it: one value per key, the LAST occurrence of a
// repeated key winning. Every reading of the address goes through this, so the page cannot ask
// for — or show — a different view than the one its address names.
//
// An address written by this page before `root` became one id carries the whole dived path
// there, as a JSON array — at load, and again whenever Back or Forward restores an entry from
// before. Reading it rewrites it in place, so nothing is asked of it first: the last id is the
// root and the ids before it are the trail.
const address = () => {
  const q = new URLSearchParams();
  for (const [k, v] of new URLSearchParams(location.search)) q.set(k, v);
  const root = q.get('root') || '';
  if (!root.startsWith('[')) return q;
  const ids = json(root, []).filter((id) => typeof id === 'string');
  q.delete('root'); q.delete('trail');
  if (ids.length) q.set('root', ids[ids.length - 1]);
  if (ids.length > 1) q.set('trail', JSON.stringify(ids.slice(0, -1)));
  history.replaceState(null, '', location.pathname + (q.toString() ? '?' + q : ''));
  return q;
};

function readState() {
  const q = address();
  const list = (k) => (q.get(k) || '').split(',').map((s) => s.trim()).filter(Boolean);
  return {
    nest: list('nest'), link: list('link'), select: q.get('select') || '',
    unplaced: q.get('unplaced') === '1', root: q.get('root') || '', plane: q.get('plane') || '',
    open: json(q.get('open') || '[]', []),
    trail: json(q.get('trail') || '[]', []), sel: json(q.get('sel') || 'null', null),
    embed: q.get('embed') === '1',
  };
}

function writeState(state, push) {
  const q = new URLSearchParams();
  if (state.nest.length) q.set('nest', state.nest.join(','));
  if (state.link.length) q.set('link', state.link.join(','));
  if (state.select) q.set('select', state.select);
  if (state.unplaced) q.set('unplaced', '1');
  if (state.root) q.set('root', state.root);
  if (state.plane) q.set('plane', state.plane);
  if (state.open.length) q.set('open', JSON.stringify(state.open));
  if (state.trail.length) q.set('trail', JSON.stringify(state.trail));
  if (state.sel) q.set('sel', JSON.stringify(state.sel));
  if (state.embed) q.set('embed', '1');
  const url = location.pathname + (q.toString() ? '?' + q : '');
  if (push) history.pushState(null, '', url); else history.replaceState(null, '', url);
}

// The request for the address as it stands: its view parameters, copied and not rebuilt.
function request() {
  const here = address();
  const q = new URLSearchParams();
  for (const k of VIEW) if (here.has(k)) q.set(k, here.get(k));
  q.set('theme', matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light');
  return q;
}

let state = readState();
// Shown inside another page, which has its own title: `embed=1` drops this one. Every request is
// relative, so the page also works when a host serves it under a path of its own.
document.body.classList.toggle('embedded', state.embed);
let relations = [];
let planes = [];
let drawn = 0; // guards against an older answer landing after a newer one

const shortName = (qualified) => qualified.slice(qualified.lastIndexOf('/') + 1);

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
  section('stored between nodes', stored);
  section('derived or unused', rest);
}

function renderPickers() {
  const toggle = (field) => (name, on) => {
    state[field] = on ? [...state[field], name] : state[field].filter((n) => n !== name);
    if (field === 'nest') { state.open = []; state.sel = null; state.root = ''; state.trail = []; }
    writeState(state, true);
    renderPickers();
    load();
  };
  fillMenu($('nest-menu'), state.nest, toggle('nest'));
  fillMenu($('link-menu'), state.link, toggle('link'));
  $('nest-sum').textContent = state.nest.join(', ') || 'choose…';
  $('link-sum').textContent = state.link.join(', ') || 'none';
  $('select').value = state.select;
  // the declared design, then every plane the model holds — and the one the address names even
  // when it holds no such plane, so the page shows what it asked for and the explorer says why not
  const menu = $('plane');
  const names = planes.map((p) => p.name);
  if (state.plane && !names.includes(state.plane)) names.push(state.plane);
  menu.textContent = '';
  for (const [value, text] of [['', 'declared design'], ...names.map((n) => {
    const p = planes.find((x) => x.name === n);
    return [n, p ? n + ' · ' + p.nodes : n];
  })]) {
    const o = document.createElement('option');
    o.value = value; o.textContent = text; o.selected = value === state.plane;
    menu.append(o);
  }
  menu.hidden = names.length === 0;
  $('title').textContent = 'fukan · ' + (state.plane || 'declared design');
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
  showCrumbs();
  let body;
  try {
    body = await (await fetch('api/drawing?' + request())).json();
  } catch (e) {
    if (ticket === drawn) { clearCanvas(); notice('The explorer did not answer: ' + e.message, true); }
    return;
  }
  if (ticket !== drawn) return;
  $('at').textContent = 'model read ' + new Date(body.at).toLocaleTimeString();
  const [outcome, value] = body.answer; // exactly one of: drawing, rejected, unanswerable, no-model
  showUnplaced(outcome === 'drawing' ? value.unplaced : []);
  if (outcome === 'no-model') { clearCanvas(); notice('No model is loaded. Build one at the REPL, then reload.'); return; }
  if (outcome === 'rejected') { clearCanvas(); notice(value, true); return; }
  if (outcome === 'unanswerable') { clearCanvas(); notice('The model could not answer this view:\n' + value, true); return; }
  showDetail();
  if (!value.nodes) {
    clearCanvas();
    notice(value.unplaced.length
      ? 'Nothing in this view is placed by ' + state.nest.join(', ') + '.'
      : 'Nothing in the declared design matches this view.');
    return;
  }
  notice('');
  show(value.svg);
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
  box.append(head, ' · ' + counts.map((c) => c.count + ' ' + shortName(c.sort)).join(', '), toggle);
}

// The ids dived along are the trail and then the root. The explorer is asked for the root alone;
// the trail is what lets the viewer step back out one level at a time.
const dived = () => (state.root ? [...state.trail, state.root] : []);

function showCrumbs() {
  const nav = $('crumbs');
  const ids = dived();
  nav.textContent = '';
  nav.classList.toggle('on', ids.length > 0);
  const step = (label, depth) => {
    const a = document.createElement(depth === ids.length ? 'b' : 'a');
    a.textContent = label;
    if (depth !== ids.length) a.addEventListener('click', () => setRoot(ids.slice(0, depth)));
    return a;
  };
  nav.append(step('top', 0));
  ids.forEach((id, i) => nav.append(' › ', step(shortName(id), i + 1)));
}

function setRoot(ids) {
  state.root = ids.length ? ids[ids.length - 1] : '';
  state.trail = ids.slice(0, -1);
  state.open = []; state.sel = null;
  writeState(state, true);
  showDetail();
  load();
}

// ── showing the drawing ──────────────────────────────────────────────────────

function clearCanvas() { $('viewport').textContent = ''; }

const boxes = () => $('viewport').querySelectorAll('g.box');
const pathOf = (g) => JSON.parse(g.dataset.path);

function markSelected() {
  const sel = key(state.sel);
  for (const g of boxes()) g.classList.toggle('sel', key(pathOf(g)) === sel);
}

let fitted = null; // the view and drawing size the camera was last fitted for

function show(svg) {
  $('viewport').innerHTML = svg;
  const picture = $('viewport').firstElementChild;
  const w = Number(picture.getAttribute('width')), h = Number(picture.getAttribute('height'));
  markSelected();
  // Opening or closing a box moves everything, so the old camera would point at empty canvas;
  // a redraw at the same size keeps the camera where the viewer put it.
  const view = [state.plane, state.nest.join(','), state.select, state.unplaced, state.root, w, h].join('|');
  if (fitted !== view) { fit(w, h); fitted = view; }
}

// A click lands on a mark; the group around it says which occurrence it belongs to, and the
// mark's role whether it is the marker that opens and closes the box.
$('viewport').addEventListener('click', (ev) => {
  const g = ev.target.closest('g.box');
  if (!g) return;
  ev.stopPropagation();
  if (ev.target.closest('[data-role="toggle"]')) toggleOpen(pathOf(g)); else select(pathOf(g));
});
$('viewport').addEventListener('dblclick', (ev) => {
  const g = ev.target.closest('g.box');
  if (!g || !(Number(g.dataset.members) > 0)) return;
  ev.stopPropagation();
  setRoot([...dived(), ...pathOf(g)]);
});

// ── pan and zoom ─────────────────────────────────────────────────────────────

const cam = { x: 20, y: 20, k: 1 };
const applyCam = () => { $('viewport').style.transform = `translate(${cam.x}px,${cam.y}px) scale(${cam.k})`; };

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
    if (ev.target.closest('g.box')) return;
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

function select(path) {
  if (key(path) === key(state.sel)) return;
  state.sel = path;
  writeState(state, false);
  markSelected();
  showDetail();
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
    const asked = new URLSearchParams({ id });
    if (state.plane) asked.set('plane', state.plane);
    const res = await fetch('api/node?' + asked);
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
  state.open = []; state.sel = null; state.root = ''; state.trail = [];
  writeState(state, true);
  showDetail();
  load();
});
$('plane').addEventListener('change', (ev) => {
  state.plane = ev.target.value;
  state.open = []; state.sel = null; state.root = ''; state.trail = [];
  writeState(state, true);
  renderPickers();
  showDetail();
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
    planes = outcome === 'choices' ? value.planes : [];
  } catch (_) { relations = []; planes = []; }
  renderPickers();
  showDetail();
  load();
}
boot();
