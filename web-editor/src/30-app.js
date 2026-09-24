/* ==========================================================================
 * 30-app.js — the controller. Owns `S` (all mutable state) and exposes every
 * action the UI can take as a method on `A` (= NT.app). Nothing here touches
 * the DOM directly except calling into NT.app.view (rendering) and
 * NT.app.view.confirm/toast (which 62-inspector.js provides). 70-events.js
 * is the only thing that calls these methods, from data-act handlers.
 * ========================================================================== */
(function (NT) {
  'use strict';
  const M = NT.model;
  const D = NT.doc;

  const S = {
    bridge: null,
    data: null,
    target: null,
    fmt: { raw: '', exists: false },
    lines: [[]],
    history: [],
    future: [],
    sel: null,
    pop: null,
    menu: null,
    modal: null,
    railOpen: false,
    rawOpen: false,
    rawEditing: false,
    loading: false,
    loadError: null,
    busy: false,
    q: { group: '', player: '' },
    bg: 'day',
    crouch: false,
    previewUuid: null,
    pctx: null,
    pcache: {},
    railTab: null,
    last: { group: null, player: null },
  };

  const A = { S };
  NT.app = A;

  const clone = (o) => JSON.parse(JSON.stringify(o));
  const prettify = (key) => String(key || '').split('_').map((w) => (w ? w[0].toUpperCase() + w.slice(1) : w)).join(' ');
  A.prettify = prettify;

  /* ---------------------------------------------------------------- render */
  // A thin call-through so action code doesn't need to know view internals.
  const render = {
    all: () => A.view && A.view.renderAll && A.view.renderAll(),
    live: () => A.view && A.view.renderLive && A.view.renderLive(),
    rail: () => A.view && A.view.renderRail && A.view.renderRail(),
    inspector: () => A.view && A.view.renderInspector && A.view.renderInspector(),
    work: () => A.view && A.view.renderWork && A.view.renderWork(),
    top: () => A.view && A.view.renderTop && A.view.renderTop(),
  };

  /* ------------------------------------------------------------- identity */

  A.isAdmin = () => !!(S.data && S.data.session && S.data.session.role === 'admin');
  A.settings = () => (S.data && S.data.settings) || {};
  A.full = () => (A.isAdmin() ? true : !!(S.fmt && S.fmt.hasIndividual));
  A.playerName = (uuid) => { const p = (S.data.players || []).find((x) => x.uuid === uuid); return p ? p.name : uuid; };
  A.bedrockAvailable = () => { const st = A.settings(); return !!(st.separateBedrockGlobal || st.separateBedrockGroups); };
  // Is there a Java/Bedrock choice to make for this kind of format? Only global
  // and group formats can have a separate Bedrock version, and only when the
  // server has that switched on (enable-separate-bedrock-global-format /
  // -group-formats). Personal formats are one format for both editions.
  A.editionEnabled = (type) => {
    const st = A.settings();
    if (type === 'global') return !!st.separateBedrockGlobal;
    if (type === 'group') return !!st.separateBedrockGroups;
    return false;
  };
  A.editionUnused = () => {
    const t = S.target; if (!t || t.platform !== 'bedrock') return false;
    const st = A.settings();
    if (t.type === 'global') return !st.separateBedrockGlobal;
    if (t.type === 'group') return !st.separateBedrockGroups;
    return false;
  };
  A.groupNames = (forPlatform) => {
    const platform = forPlatform || (S.target && S.target.platform) || 'java';
    const stored = new Set(((S.data && S.data.groups && S.data.groups[platform]) || []));
    const fromPlayers = new Set((S.data.players || []).map((p) => p.group).filter(Boolean));
    const all = Array.from(new Set([...stored, ...fromPlayers])).sort((a, b) => a.localeCompare(b));
    return all.map((name) => ({ name, stored: stored.has(name) }));
  };
  A.targetTitle = () => {
    const t = S.target; if (!t) return '';
    if (t.type === 'global') return 'Global format';
    if (t.type === 'group') return 'Group: ' + t.id;
    if (t.type === 'player') return A.playerName(t.id);
    return 'Your nametag';
  };
  A.appliesTo = () => {
    const t = S.target; if (!t) return '';
    if (t.type === 'global') return 'Everyone without a closer match (a group or a personal format).';
    if (t.type === 'group') return 'Players in the \u201c' + t.id + '\u201d group without a personal format.';
    if (t.type === 'player') return 'Just ' + A.playerName(t.id) + '.';
    return 'Just you.';
  };

  /* ----------------------------------------------------------------- ctx */

  A.ctx = () => {
    const st = A.settings();
    const t = S.target || {};
    const platform = t.platform || 'java';
    const pc = S.pctx || { name: '', values: {}, lineLimit: st.lineMaxCharacters };
    const applyAffix = platform === 'bedrock' && (t.type === 'global' ? st.affixGlobal : t.type === 'group' ? st.affixGroup : st.affixPlayer);
    return {
      name: pc.name, values: pc.values || {}, platform,
      lineLimit: typeof pc.lineLimit === 'number' ? pc.lineLimit : (st.lineMaxCharacters != null ? st.lineMaxCharacters : -1),
      ellipsis: !!st.truncateIndicator, widgetEllipsis: !!st.widgetTruncateIndicator,
      bedrockPrefix: st.bedrockPrefix || '', bedrockSuffix: st.bedrockSuffix || '', applyAffix: !!applyAffix,
    };
  };
  A.serialize = () => M.serializeLines(S.lines);
  A.previewBuild = () => NT.preview.build(A.serialize(), A.ctx());
  A.dirty = () => !!(S.fmt && S.fmt.exists && A.serialize() !== S.fmt.raw);

  A.phInfo = (raw) => {
    const c = A.ctx();
    if (raw === M.PLAYER_TAG) return { title: 'Player name', text: c.name || '', state: c.name ? 'ok' : 'none' };
    const m = /^%([^%]+)%$/.exec(raw || '');
    const key = m ? m[1] : null;
    const known = key != null && (S.data.placeholders || []).find((p) => p.key === key);
    const title = known ? (known.title || prettify(key)) : (key != null ? prettify(key) : raw);
    if (key == null) return { title, text: raw, state: 'ok' };
    if (Object.prototype.hasOwnProperty.call(c.values, key)) {
      const val = c.values[key];
      return { title, text: NT.preview.resolveOne(val, c).text, state: val === '' ? 'none' : 'ok' };
    }
    return { title, text: '', state: 'miss' };
  };

  A.gradientStops = (raw) => { const d = NT.mini.describeTag(raw); return d.kind === 'gradient' ? d.stops : null; };

  const ALIAS = { b: 'bold', i: 'italic', u: 'underlined', st: 'strikethrough', o: 'obfuscated' };
  A.specOf = (raw) => {
    const d = NT.mini.describeTag(raw);
    if (d.kind === 'close') {
      const m = /^<\/(?:!)?([#A-Za-z0-9_]+)>$/.exec(raw);
      const nm = m ? m[1].toLowerCase() : '';
      return 'close:' + (ALIAS[nm] || nm);
    }
    if (d.kind === 'style') return 'style:' + d.label.toLowerCase();
    if (d.kind === 'gradient') return 'gradient';
    if (d.kind === 'rainbow') return 'rainbow';
    if (d.kind === 'other' && d.label === 'Reset') return 'reset';
    if (d.kind === 'color' && d.swatch) {
      const named = Object.keys(NT.mini.NAMED).find((k) => NT.mini.NAMED[k] === d.swatch.toLowerCase());
      return named ? 'name:' + named : 'hex:' + d.swatch.toLowerCase();
    }
    return null;
  };

  function rawFromSpec(spec) {
    if (spec === 'reset') return '<reset>';
    if (spec === 'rainbow') return '<rainbow>';
    if (spec.indexOf('name:') === 0) return '<' + spec.slice(5) + '>';
    if (spec.indexOf('hex:') === 0) return '<' + spec.slice(4) + '>';
    if (spec.indexOf('style:') === 0) return '<' + spec.slice(6) + '>';
    if (spec === 'gradient') return '<gradient:#ff8a00:#e52e71>';
    if (spec.indexOf('gradient:') === 0) return '<gradient:' + spec.slice(9).split(',').join(':') + '>';
    if (spec.indexOf('close:') === 0) return '</' + spec.slice(6) + '>';
    return null;
  }

  /* --------------------------------------------------------------- refs */

  A.selected = () => {
    if (!S.sel) return null;
    const ref = S.sel;
    const line = S.lines[ref.l]; if (!line) return null;
    if (ref.w == null) return line[ref.i] || null;
    const w = line[ref.w];
    return w && w.kind === 'widget' ? (w.contents[ref.i] || null) : null;
  };
  A.widgetOf = (ref) => {
    if (!ref || ref.w == null) return null;
    const line = S.lines[ref.l];
    return line && line[ref.w] && line[ref.w].kind === 'widget' ? line[ref.w] : null;
  };
  A.canModify = (ref) => A.full() || ref.w != null;
  A.allowedIn = (lr) => {
    if (lr.w == null) return { text: true, colors: true, placeholders: true };
    const line = S.lines[lr.l] || [];
    const w = line[lr.w];
    if (!w || w.kind !== 'widget') return { text: false, colors: false, placeholders: false };
    return { text: !!w.text, colors: !!w.colors, placeholders: !!w.placeholders };
  };
  A.widgetUsage = (w) => {
    const raw = w.contents.map(M.serializeChunk).join('');
    return NT.preview.resolveOne(raw, A.ctx()).text.length;
  };

  /* ------------------------------------------------------------- lineInfo */

  A.lineInfo = (l) => {
    const line = S.lines[l] || [];
    if (!line.length) return { kind: 'blank', length: 0, limit: -1 };
    const raw = line.map(M.serializeChunk).join('');
    const st = A.settings();
    const built = NT.preview.build(raw, Object.assign({}, A.ctx(), { ellipsis: !!st.truncateIndicator, widgetEllipsis: !!st.widgetTruncateIndicator }));
    if (D.lineHasWidget(line) && built.lines.length === 0) return { kind: 'hidden', length: 0, limit: -1 };
    const info = built.info[0] || { length: 0, limit: A.ctx().lineLimit, truncated: false };
    return { kind: info.truncated ? 'warn' : 'normal', length: info.length, limit: info.limit };
  };

  /* --------------------------------------------------------------- undo */

  function pushHistory() {
    S.history.push(clone(S.lines));
    if (S.history.length > 60) S.history.shift();
    S.future.length = 0;
  }
  A.canUndo = () => S.history.length > 0;
  A.canRedo = () => S.future.length > 0;
  A.undo = () => {
    if (!S.history.length) return;
    S.future.push(clone(S.lines));
    S.lines = S.history.pop();
    S.sel = null;
    render.live(); render.inspector();
  };
  A.redo = () => {
    if (!S.future.length) return;
    S.history.push(clone(S.lines));
    S.lines = S.future.pop();
    S.sel = null;
    render.live(); render.inspector();
  };

  /* ---------------------------------------------------------- mutations */

  function remintIds(chunks) {
    chunks.forEach((c) => { if (c.kind === 'widget') { c.widgetId = M.mintWidgetId(); remintIds(c.contents); } });
    return chunks;
  }

  A.select = (ref) => { S.sel = (S.sel && D.sameRef(S.sel, ref)) ? null : ref; render.inspector(); render.live(); };
  A.deselect = () => { S.sel = null; render.inspector(); render.live(); };
  // Like select, but never un-selects: right-clicking an already selected chip keeps it selected.
  A.selectRef = (ref) => { if (S.sel && D.sameRef(S.sel, ref)) return; S.sel = ref; render.inspector(); render.live(); };

  A.addText = (lr, text) => {
    pushHistory();
    const list = D.listOf(S.lines, lr); if (!list) return;
    list.push({ kind: 'text', text: text || '' });
    S.sel = { l: lr.l, w: lr.w, i: list.length - 1 };
  };
  A.addColor = (lr, spec) => {
    const raw = rawFromSpec(spec); if (raw == null) return;
    pushHistory();
    const list = D.listOf(S.lines, lr); if (!list) return;
    list.push({ kind: 'color', raw });
    S.sel = { l: lr.l, w: lr.w, i: list.length - 1 };
  };
  A.addPlaceholder = (lr, value) => {
    pushHistory();
    const list = D.listOf(S.lines, lr); if (!list) return;
    list.push({ kind: 'placeholder', raw: value });
    S.sel = { l: lr.l, w: lr.w, i: list.length - 1 };
  };
  A.addLimit = (lr, n) => {
    pushHistory();
    const list = D.listOf(S.lines, lr); if (!list) return;
    list.push({ kind: 'limit', limit: Math.max(0, n | 0), raw: null });
    S.sel = { l: lr.l, w: lr.w, i: list.length - 1 };
  };
  A.addWidget = (lr) => {
    pushHistory();
    const list = D.listOf(S.lines, lr); if (!list) return;
    list.push({ kind: 'widget', widgetId: M.mintWidgetId(), colors: true, placeholders: true, text: true, limit: -1, contents: [] });
    S.sel = { l: lr.l, w: null, i: list.length - 1 };
  };
  A.addNewlineAfter = (lr) => {
    pushHistory();
    S.lines.splice(lr.l + 1, 0, []);
    S.sel = null;
  };

  A.removeChunk = (ref) => {
    pushHistory();
    const list = D.listOf(S.lines, { l: ref.l, w: ref.w }); if (!list) return;
    list.splice(ref.i, 1);
    S.sel = null;
  };
  A.duplicateChunk = (ref) => {
    const list = D.listOf(S.lines, { l: ref.l, w: ref.w }); if (!list) return;
    pushHistory();
    const dup = clone(list[ref.i]);
    if (dup.kind === 'widget') { dup.widgetId = M.mintWidgetId(); remintIds(dup.contents); }
    list.splice(ref.i + 1, 0, dup);
    S.sel = { l: ref.l, w: ref.w, i: ref.i + 1 };
  };
  A.nudge = (ref, dir) => {
    const list = D.listOf(S.lines, { l: ref.l, w: ref.w }); if (!list) return;
    const j = ref.i + dir; if (j < 0 || j >= list.length) return;
    pushHistory();
    const tmp = list[ref.i]; list[ref.i] = list[j]; list[j] = tmp;
    S.sel = { l: ref.l, w: ref.w, i: j };
  };
  A.moveChunk = (from, toLr, toIndex) => {
    const fromList = D.listOf(S.lines, { l: from.l, w: from.w }); if (!fromList) return;
    const chunk = fromList[from.i]; if (!chunk) return;
    if (chunk.kind === 'widget' && toLr.w != null) return;
    const toList = D.listOf(S.lines, toLr); if (!toList) return;
    pushHistory();
    fromList.splice(from.i, 1);
    let idx = toIndex;
    if (from.l === toLr.l && from.w === toLr.w && from.i < idx) idx -= 1;
    idx = Math.max(0, Math.min(idx, toList.length));
    toList.splice(idx, 0, chunk);
    S.sel = { l: toLr.l, w: toLr.w, i: idx };
  };

  // Field edits: intentionally NOT pushed onto the undo stack per keystroke —
  // undo/redo works at the level of adding, removing, and reordering chunks.
  A.setSelectedText = (text) => { const c = A.selected(); if (c && c.kind === 'text') c.text = text; };
  A.setSelectedColor = (spec) => { const c = A.selected(); if (!c || c.kind !== 'color') return; const raw = rawFromSpec(spec); if (raw != null) c.raw = raw; };
  A.setSelectedHex = (hex) => {
    const c = A.selected(); if (!c || c.kind !== 'color') return;
    let h = String(hex || '').trim(); if (h[0] !== '#') h = '#' + h;
    if (!/^#[0-9a-fA-F]{6}$/.test(h)) return;
    c.raw = '<' + h.toLowerCase() + '>';
  };
  A.setGradientStops = (stops) => { const c = A.selected(); if (!c || c.kind !== 'color') return; c.raw = '<gradient:' + stops.join(':') + '>'; };
  A.addGradientStop = () => { const c = A.selected(); const st = A.gradientStops(c && c.raw); if (!st || st.length >= 5) return; A.setGradientStops(st.concat([st[st.length - 1]])); };
  A.removeGradientStop = () => { const c = A.selected(); const st = A.gradientStops(c && c.raw); if (!st || st.length <= 2) return; A.setGradientStops(st.slice(0, -1)); };
  A.setSelectedPlaceholder = (value) => { const c = A.selected(); if (c && c.kind === 'placeholder') c.raw = value; };
  A.setSelectedLimit = (n) => { const c = A.selected(); if (c && c.kind === 'limit') { c.limit = Math.max(0, n | 0); c.raw = null; } };
  A.setWidgetFlag = (flag, val) => { const c = A.selected(); if (c && c.kind === 'widget') c[flag] = val; };
  A.setWidgetLimited = (on) => { const c = A.selected(); if (!c || c.kind !== 'widget') return; c.limit = on ? (c.limit >= 0 ? c.limit : 16) : -1; };
  A.setWidgetLimit = (n) => { const c = A.selected(); if (c && c.kind === 'widget' && c.limit >= 0) c.limit = Math.max(0, n | 0); };

  A.applyRawEdit = (text) => { pushHistory(); S.lines = M.parseLines(text || ''); S.sel = null; S.rawEditing = false; };

  /* ------------------------------------------------------------- lines */

  A.addLine = () => { pushHistory(); S.lines.push([]); };
  A.moveLineUp = (l) => {
    if (l <= 0) return;
    pushHistory();
    const t = S.lines[l - 1]; S.lines[l - 1] = S.lines[l]; S.lines[l] = t;
    if (S.sel) { if (S.sel.l === l) S.sel = Object.assign({}, S.sel, { l: l - 1 }); else if (S.sel.l === l - 1) S.sel = Object.assign({}, S.sel, { l }); }
  };
  A.moveLineDown = (l) => A.moveLineUp(l + 1);
  A.duplicateLine = (l) => {
    pushHistory();
    const dup = clone(S.lines[l]); remintIds(dup);
    S.lines.splice(l + 1, 0, dup);
  };
  A.deleteLine = (l) => {
    pushHistory();
    if (S.lines.length <= 1) S.lines[0] = [];
    else S.lines.splice(l, 1);
    if (S.sel && S.sel.l === l) S.sel = null;
  };

  /* ------------------------------------------------------------- UI-only */

  A.toggleRail = () => { S.railOpen = !S.railOpen; render.top(); };
  A.closeRail = () => { S.railOpen = false; render.top(); };
  A.setBg = (bg) => { S.bg = bg; A.view.updateStage(); };
  A.toggleCrouch = () => { S.crouch = !S.crouch; A.view.updateStage(); };
  A.setQuery = (which, val) => { S.q[which] = val; };
  A.toggleRawOpen = () => { S.rawOpen = !S.rawOpen; render.work(); };
  A.startRawEdit = () => { S.rawEditing = true; render.work(); };
  A.cancelRawEdit = () => { S.rawEditing = false; render.work(); };

  /* --------------------------------------------------------------- data */

  function withDefaultPlatform(t) { return Object.assign({ platform: 'java' }, t); }

  A.confirmDiscard = async () => {
    if (!A.dirty()) return true;
    const v = await A.view.confirm({
      title: 'Discard changes?',
      body: 'You have unsaved changes to this format. Leave without saving them?',
      buttons: [{ value: 'cancel', label: 'Keep editing' }, { value: 'leave', label: 'Discard & leave', kind: 'danger' }],
    });
    return v === 'leave';
  };

  async function loadPreviewContext(uuid) {
    const p = await S.bridge.previewContext(uuid);
    S.pcache[uuid] = p;
    S.pctx = p;
    S.previewUuid = uuid;
  }
  A.setPreviewPlayer = async (uuid) => { await loadPreviewContext(uuid); A.view.updateStage(); render.rail(); };

  async function loadCurrentFormat() {
    S.loading = true; S.loadError = null;
    // Mark busy without tearing down and rebuilding the whole work area (that
    // full-teardown-and-rebuild, happening twice in quick succession around a
    // fast/local fetch, is what causes the visible flash when switching
    // targets). The single render.all() below does the real rebuild once the
    // new data is actually in hand.
    const workEl = typeof document !== 'undefined' ? document.getElementById('work') : null;
    if (workEl) workEl.setAttribute('aria-busy', 'true');
    try {
      const res = await S.bridge.loadFormat(S.target);
      S.fmt = res;
      S.lines = res.exists ? M.parseLines(res.raw) : [[]];
    } catch (e) {
      S.loadError = (e && e.message) || 'Something went wrong.';
    }
    S.loading = false;
    S.history.length = 0; S.future.length = 0; S.sel = null;
    render.all();
  }

  A.goto = async (target) => {
    if (!(await A.confirmDiscard())) return;
    S.target = withDefaultPlatform(target);
    S.railTab = null;
    if (S.target.type === 'group' || S.target.type === 'player') S.last[S.target.type] = S.target.id;
    // Reflect the new target (title, pills) right away, instead of leaving the
    // topbar showing the old one until the whole fetch below finishes.
    render.top();
    render.rail();
    if (S.target.type === 'player') await A.setPreviewPlayer(S.target.id);
    else if (!S.previewUuid && (S.data.players || []).length) await loadPreviewContext(S.data.players[0].uuid);
    await loadCurrentFormat();
    if (window.matchMedia && window.matchMedia('(max-width: 1179px)').matches) A.closeRail();
  };
  A.setPlatform = async (platform) => {
    if (!S.target || !A.editionEnabled(S.target.type)) return;
    if (!(await A.confirmDiscard())) return;
    S.target = Object.assign({}, S.target, { platform });
    render.top();
    await loadCurrentFormat();
  };
  A.retry = () => loadCurrentFormat();

  /* ------------------------------------------------------------ rail tabs */
  // The admin rail has one tab per kind of format. The open tab is normally just
  // the kind of format being edited; S.railTab only holds it while an empty tab
  // (no groups yet, nobody online) is on show, because there is nothing on it to
  // open and the editor is still on the previous format.
  A.TABS = ['global', 'group', 'player'];
  A.railTab = () => S.railTab || (S.target && S.target.type) || 'global';

  /** What a tab opens when clicked: the one visited last, else the first entry. */
  A.tabDestination = (tab) => {
    if (tab === 'global') return { type: 'global' };
    if (tab === 'group') {
      const names = A.groupNames('java').map((g) => g.name);
      const id = names.includes(S.last.group) ? S.last.group : names[0];
      return id != null ? { type: 'group', id } : null;
    }
    if (tab === 'player') {
      const players = S.data.players || [];
      const p = players.find((x) => x.uuid === S.last.player) || players[0];
      return p ? { type: 'player', id: p.uuid } : null;
    }
    return null;
  };

  A.openTab = async (tab) => {
    if (!A.TABS.includes(tab) || tab === A.railTab()) return;
    if (S.target && S.target.type === tab) { S.railTab = null; render.rail(); return; }
    const dest = A.tabDestination(tab);
    if (dest) { await A.goto(dest); return; }
    S.railTab = tab;
    render.rail();
  };

  A.startFromCurrent = () => {
    pushHistory();
    S.fmt = Object.assign({}, S.fmt, { exists: true, raw: '' });
    S.lines = M.parseLines(S.fmt.effectiveRaw || '');
    render.all();
  };

  A.save = async () => {
    if (!A.dirty() || S.busy) return;
    S.busy = true; render.top();
    const raw = A.serialize();
    try {
      const res = A.isAdmin() ? await S.bridge.saveFormat(S.target, raw) : await S.bridge.saveOwn(raw);
      if (res.ok) {
        S.fmt = Object.assign({}, S.fmt, { raw, exists: true });
        A.view.toast('Format saved.', { kind: 'ok' });
        if (S.target && S.target.type === 'player') {
          const p = (S.data.players || []).find((x) => x.uuid === S.target.id);
          if (p) p.hasFormat = true;
        }
      } else {
        A.view.toast(res.error || 'Could not save.', { kind: 'err' });
      }
    } catch (e) {
      A.view.toast('Could not reach the server.', { kind: 'err' });
    }
    S.busy = false;
    render.all();
  };

  A.discard = async () => {
    if (!A.dirty()) return;
    const v = await A.view.confirm({
      title: 'Discard changes?', body: 'This throws away everything you\u2019ve changed since the last save.',
      buttons: [{ value: 'cancel', label: 'Keep editing' }, { value: 'discard', label: 'Discard changes', kind: 'danger' }],
    });
    if (v !== 'discard') return;
    S.lines = S.fmt.exists ? M.parseLines(S.fmt.raw) : [[]];
    S.history.length = 0; S.future.length = 0; S.sel = null;
    render.all();
  };

  A.clearOverride = async () => {
    if (!S.target || S.target.type !== 'player') return;
    const v = await A.view.confirm({
      title: 'Remove personal format?', body: A.playerName(S.target.id) + ' will go back to following their group or the global format.',
      buttons: [{ value: 'cancel', label: 'Cancel' }, { value: 'remove', label: 'Remove format', kind: 'danger' }],
    });
    if (v !== 'remove') return;
    await S.bridge.clearPlayerFormat(S.target.id);
    const p = (S.data.players || []).find((x) => x.uuid === S.target.id);
    if (p) p.hasFormat = false;
    await loadCurrentFormat();
    A.view.toast('Personal format removed.', { kind: 'ok' });
  };

  A.clearGroupFormat = async () => {
    if (!S.target || S.target.type !== 'group') return;
    const groupName = S.target.id;
    const v = await A.view.confirm({
      title: 'Remove this group\u2019s format?',
      body: "Group '" + groupName + "' will go back to following the global format" + (S.target.platform === 'bedrock' ? ' (Bedrock).' : '.'),
      buttons: [{ value: 'cancel', label: 'Cancel' }, { value: 'remove', label: 'Remove format', kind: 'danger' }],
    });
    if (v !== 'remove') return;
    await S.bridge.clearGroupFormat(groupName, S.target.platform === 'bedrock');
    await loadCurrentFormat();
    render.rail();
    A.view.toast("Group format removed.", { kind: 'ok' });
  };

  A.switchRole = async (role) => {
    S.bridge.setRole(role);
    await A.boot(S.bridge);
  };

  /* --------------------------------------------------------------- boot */

  A.boot = async (bridge) => {
    S.bridge = bridge;
    S.data = await bridge.load();
    S.pcache = {};
    S.railTab = null;
    S.last = { group: null, player: null };
    const me = S.data.session.player;
    await loadPreviewContext(me.uuid).catch(() => {});
    const initial = A.isAdmin() ? { type: 'global', platform: 'java' } : { type: 'self', platform: 'java' };
    S.target = initial;
    if (initial.type === 'player') await A.setPreviewPlayer(initial.id);
    await loadCurrentFormat();
  };

  A.init = async () => {
    try {
      await A.boot(NT.createBridge());
    } catch (e) {
      // Almost always a RelayBridge whose session has expired, was already
      // used up, or was mistyped — there is no format data to show at all
      // in that case, so this replaces the whole app shell with a plain
      // explanation instead of leaving a blank page behind the boot screen.
      renderFatalBootError((e && e.message) || 'Something went wrong loading this editor session.');
    } finally {
      const boot = document.getElementById('boot');
      if (boot) { boot.classList.add('done'); setTimeout(() => boot.remove(), 300); }
    }
  };

  function renderFatalBootError(message) {
    const app = document.getElementById('app');
    if (!app) return;
    app.innerHTML =
      '<div class="fatal-error"><div class="fatal-error-card">' +
      '<h1>Couldn\u2019t open this editor session</h1>' +
      '<p>' + String(message).replace(/[&<>]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;' }[c])) + '</p>' +
      '<p class="fatal-error-hint">Session links expire after 30 minutes and can only be opened while they\u2019re still valid. Run <code>/nametags editor web</code> again on the server to get a fresh one.</p>' +
      '</div></div>';
  }
})((globalThis.NT = globalThis.NT || {}));
