/* ==========================================================================
 * 60-view.js — turns state into HTML. Every function returns a string or
 * writes into one of the fixed slots in index.html; nothing here listens for
 * events (see 70-events.js). Interactive elements carry `data-act` /
 * `data-input` attributes that the controller reads.
 * ========================================================================== */
(function (NT) {
  'use strict';
  const A = NT.app;
  const S = A.S;
  const M = NT.model;
  const D = NT.doc;
  const P = NT.preview;
  const I = NT.icon;
  const esc = P.esc;
  const V = (A.view = {});
  const $ = (id) => document.getElementById(id);

  const KIND = {
    text: { icon: 'type', label: 'Text', color: 'var(--k-text)', hint: 'Words, spaces and symbols' },
    color: { icon: 'palette', label: 'Color', color: 'var(--k-color)', hint: 'Colors, styles and gradients' },
    placeholder: { icon: 'percent', label: 'Placeholder', color: 'var(--k-ph)', hint: 'Live values such as ping or rank' },
    widget: { icon: 'slot', label: 'Widget', color: 'var(--k-widget)', hint: 'A slot players can fill in' },
    limit: { icon: 'ruler', label: 'Line limit', color: 'var(--k-limit)', hint: 'Cut this line after N characters' },
    newline: { icon: 'corner-down-left', label: 'New line', color: 'var(--k-nl)', hint: 'Start another line below' },
  };
  V.KIND = KIND;

  const COLOR_ORDER = ['black', 'dark_blue', 'dark_green', 'dark_aqua', 'dark_red', 'dark_purple', 'gold', 'gray',
    'dark_gray', 'blue', 'green', 'aqua', 'red', 'light_purple', 'yellow', 'white'];
  V.COLOR_ORDER = COLOR_ORDER;

  const STYLE_GLYPH = {
    Bold: ['B', 'text-shadow:.075em 0 0 currentColor'],
    Italic: ['I', 'font-style:italic'],
    Underlined: ['U', 'text-decoration:underline'],
    Strikethrough: ['S', 'text-decoration:line-through'],
    Obfuscated: ['?', ''],
  };
  V.STYLE_GLYPH = STYLE_GLYPH;

  /* ------------------------------------------------------------------ chips */

  function textLabel(t) {
    if (!t) return '<span class="txt is-space">empty</span>';
    if (!t.trim()) return '<span class="txt is-space">' + (t.length === 1 ? 'space' : t.length + ' spaces') + '</span>';
    const lead = t.length - t.replace(/^ +/, '').length;
    const trail = t.length - t.replace(/ +$/, '').length;
    const core = t.slice(lead, t.length - trail);
    const dot = '<span class="sp">·</span>';
    return '<span class="txt">' + dot.repeat(lead) + esc(core) + dot.repeat(trail) + '</span>';
  }

  /** The little colour / style / effect badge on a colour chip. */
  function swatchHtml(d) {
    if (d.kind === 'style') {
      const g = STYLE_GLYPH[d.label] || ['?', ''];
      return '<span class="sw style"' + (g[1] ? ' style="' + g[1] + '"' : '') + '>' + g[0] + '</span>';
    }
    if (d.kind === 'close') return '<span class="sw style">/</span>';
    if (d.swatch) return '<span class="sw" style="background:' + esc(d.swatch) + '"></span>';
    if (d.label === 'Reset') return '<span class="sw style">' + I('refresh', 'sm') + '</span>';
    return '<span class="sw style">&lt;&gt;</span>';
  }
  V.swatchHtml = swatchHtml;

  function chipParts(c) {
    switch (c.kind) {
      case 'text': return { html: I('type') + textLabel(c.text), label: 'Text: ' + c.text };
      case 'color': {
        const d = NT.mini.describeTag(c.raw);
        return { html: swatchHtml(d) + '<span class="txt">' + esc(d.label) + '</span>', label: 'Color: ' + d.label };
      }
      case 'placeholder': {
        const p = A.phInfo(c.raw);
        const val = p.state === 'none' ? 'empty' : p.state === 'miss' ? 'no preview' : p.text;
        return {
          html: I('percent') + '<span class="ph"><b>' + esc(p.title) + '</b></span><span class="val ' + (p.state === 'ok' ? '' : p.state) + '">' + esc(val) + '</span>',
          label: 'Placeholder: ' + p.title,
        };
      }
      case 'limit':
        return { html: I('ruler') + '<span class="txt">Limit ' + c.limit + '</span>', label: 'Line limit ' + c.limit };
      default: return { html: '', label: '' };
    }
  }

  function chipHtml(c, ref) {
    const editable = A.canModify(ref);
    const sel = !!S.sel && D.sameRef(S.sel, ref);
    const cls = 'chip' + (sel ? ' is-selected' : '') + (editable ? '' : ' is-locked');
    const part = chipParts(c);
    const common = 'class="' + cls + '" data-kind="' + c.kind + '" data-ref="' + D.key(ref) + '"';
    if (!editable) return '<span ' + common + '>' + part.html + '</span>';
    return '<button type="button" ' + common + ' draggable="true" data-act="select" aria-pressed="' + sel + '" aria-label="' + esc(part.label) + '">' + part.html + '</button>';
  }

  const addChipHtml = (listKey, label) =>
    '<button type="button" class="chip add" data-act="add" data-list="' + listKey + '" aria-label="' + esc(label) + '" title="' + esc(label) + '">' + I('plus') + '</button>';

  function widgetHtml(w, ref) {
    const key = D.key(ref);
    const sel = !!S.sel && D.sameRef(S.sel, ref);
    const editable = A.canModify(ref);
    const fillable = !A.full();
    const lr = { l: ref.l, w: ref.i };
    const flags = [['colors', 'palette', 'Colors'], ['placeholders', 'percent', 'Placeholders'], ['text', 'type', 'Text']];
    const meta = flags.map((f) => '<span class="' + (w[f[0]] ? '' : 'off') + '" title="' + f[2] + (w[f[0]] ? ' allowed' : ' not allowed') + '">' + I(f[1], 'sm') + '</span>').join('') +
      (w.limit >= 0 ? '<span title="Character limit">' + I('ruler', 'sm') + w.limit + '</span>' : '');
    const items = w.contents.map((c, j) => chipHtml(c, { l: ref.l, w: ref.i, i: j })).join('');
    const canAdd = A.canModify({ l: ref.l, w: ref.i, i: 0 });
    const empty = !w.contents.length ? '<span class="slot-empty">' + (fillable ? 'Empty. Add your own text.' : 'Empty slot') + '</span>' : '';
    const cls = 'widget' + (sel ? ' is-selected' : '') + (fillable ? ' is-fillable' : '');
    return '<div class="' + cls + '" data-ref="' + key + '"' + (editable ? ' draggable="true"' : '') + ' data-widget>' +
      '<button type="button" class="widget-head" data-act="select" data-ref="' + key + '" aria-pressed="' + sel + '" aria-label="Widget">' +
      I('slot', 'sm') + '<span>Widget</span><span class="meta">' + meta + '</span></button>' +
      '<div class="widget-items" data-list="' + D.listKey(lr) + '">' + items + empty +
      (canAdd ? addChipHtml(D.listKey(lr), 'Add to widget') : '') + '</div></div>';
  }

  /* ------------------------------------------------------------------ lines */

  function countHtml(l) {
    const info = A.lineInfo(l);
    if (info.kind === 'blank') return '<span class="count">blank</span>';
    if (info.kind === 'hidden') return '<span class="count hide" title="Every widget on this line is empty, so the line is not shown">' + I('eye-off', 'sm') + 'hidden</span>';
    if (info.limit < 0) return '<span class="count" title="Visible characters">' + info.length + '</span>';
    if (info.kind === 'warn') return '<span class="count warn" title="Longer than the limit, so it is cut and ends in an ellipsis">' + I('alert', 'sm') + info.length + '/' + info.limit + '</span>';
    return '<span class="count" title="Visible characters / limit">' + info.length + '/' + info.limit + '</span>';
  }

  function lineHtml(line, l) {
    const full = A.full();
    const chips = line.map((c, i) => {
      const ref = { l, w: null, i };
      return c.kind === 'widget' ? widgetHtml(c, ref) : chipHtml(c, ref);
    }).join('');
    const lk = D.listKey({ l, w: null });
    const tools = full
      ? '<div class="line-tools">' +
        '<button type="button" class="btn sm icon ghost" data-act="line-up" data-line="' + l + '" aria-label="Move line up" title="Move up"' + (l === 0 ? ' disabled' : '') + '>' + I('arrow-up') + '</button>' +
        '<button type="button" class="btn sm icon ghost" data-act="line-down" data-line="' + l + '" aria-label="Move line down" title="Move down"' + (l === S.lines.length - 1 ? ' disabled' : '') + '>' + I('arrow-down') + '</button>' +
        '<button type="button" class="btn sm icon ghost" data-act="line-dup" data-line="' + l + '" aria-label="Duplicate line" title="Duplicate">' + I('copy') + '</button>' +
        '<button type="button" class="btn sm icon ghost danger" data-act="line-del" data-line="' + l + '" aria-label="Delete line" title="Delete line">' + I('trash') + '</button></div>'
      : '';
    const hasWidget = D.lineHasWidget(line);
    return '<div class="line' + (!full && !hasWidget ? ' is-static' : '') + '" data-line="' + l + '">' +
      '<div class="line-no" title="Line ' + (l + 1) + '">' + (l + 1) + '</div>' +
      '<div class="chips" data-list="' + lk + '">' + chips + (full ? addChipHtml(lk, 'Add to line ' + (l + 1)) : '') + '</div>' +
      '<div class="line-side">' + countHtml(l) + tools + '</div></div>';
  }

  /* ---------------------------------------------------------------- composer */

  function emptyStateHtml() {
    const t = S.target;
    let title;
    let body;
    let cta;
    if (t.type === 'player') {
      const src = S.fmt.effectiveSource === 'group' ? 'group' : 'global';
      title = A.playerName(t.id) + ' uses the ' + src + ' format';
      body = 'They have no personal nametag yet. Start from what they see now to give them their own, or leave it so they keep following the ' + src + ' format.';
      cta = 'Create personal format';
    } else {
      title = 'No ' + (t.platform === 'bedrock' ? 'Bedrock ' : '') + 'format for “' + esc(t.id) + '” yet';
      body = 'Players in this group use the global format. Start from it to give the group its own nametag.';
      cta = 'Create group format';
    }
    return '<div class="empty-state">' +
      '<div class="ico">' + I(t.type === 'player' ? 'user' : 'layers', 'lg') + '</div>' +
      '<h2>' + title + '</h2><p>' + body + '</p>' +
      '<button type="button" class="btn primary" data-act="start-from-current">' + I('plus') + cta + '</button></div>';
  }

  function errorStateHtml() {
    return '<div class="empty-state is-error"><div class="ico">' + I('alert', 'lg') + '</div>' +
      '<h2>Could not load this format</h2><p>' + esc(S.loadError) + '</p>' +
      '<button type="button" class="btn" data-act="retry">' + I('refresh') + 'Try again</button></div>';
  }

  function composerHtml() {
    if (S.loadError) return errorStateHtml();
    if (!S.fmt.exists && !S.loading) return emptyStateHtml();
    const full = A.full();
    const head = '<div class="section-head"><h2>' + (full ? 'Format' : 'Your slots') + '</h2>' +
      '<span class="hint">' + (full ? 'Drag chips to reorder, or press + to add' : 'Fill in the highlighted slots') + '</span><span class="grow"></span>' +
      '<button type="button" class="btn sm icon ghost" data-act="undo" aria-label="Undo" title="Undo (Ctrl+Z)"' + (A.canUndo() ? '' : ' disabled') + '>' + I('undo') + '</button>' +
      '<button type="button" class="btn sm icon ghost" data-act="redo" aria-label="Redo" title="Redo (Ctrl+Shift+Z)"' + (A.canRedo() ? '' : ' disabled') + '>' + I('redo') + '</button></div>';
    const lines = '<div class="lines" id="lines">' + S.lines.map(lineHtml).join('') + '</div>';
    const add = full ? '<button type="button" class="btn sm add-line" data-act="add-line">' + I('plus') + 'Add line</button>' : '';
    return head + lines + add;
  }

  /** Syntax-colours the raw string, one chunk at a time. */
  function rawColoured() {
    const cls = { color: 'k-color', placeholder: 'k-placeholder', limit: 'k-limit', text: 'k-text' };
    const chunkHtml = (c) => {
      if (c.kind === 'widget') {
        return '<span class="k-widget">' + esc(M.encodeWidget(c.widgetId, c.colors, c.placeholders, c.text, c.limit, '').replace(/\{\/widget\}$/, '').replace(/^(.*)$/, '$1')) + '</span>' +
          c.contents.map(chunkHtml).join('') + '<span class="k-widget">{/widget}</span>';
      }
      return '<span class="' + cls[c.kind] + '">' + esc(M.serializeChunk(c)) + '</span>';
    };
    return S.lines.map((line) => line.map(chunkHtml).join('')).join('<span class="k-nl">\\n</span>');
  }

  function rawHtml() {
    if (S.loadError || (!S.fmt.exists && !S.loading)) return '';
    const raw = serializeSafe();
    const editable = A.full();
    const open = S.rawOpen;
    const head = '<div class="raw-head"><button type="button" class="toggle" data-act="raw-toggle" aria-expanded="' + open + '">' + I('chevron-down') + 'Raw format</button><span class="grow"></span>' +
      (open && !S.rawEditing ? '<button type="button" class="btn sm ghost" data-act="raw-copy">' + I('copy', 'sm') + 'Copy</button>' +
        (editable ? '<button type="button" class="btn sm" data-act="raw-edit">' + I('code', 'sm') + 'Edit</button>' : '') : '') + '</div>';
    if (!open) return '<div class="raw" data-open="0">' + head + '</div>';
    let body;
    if (S.rawEditing) {
      body = '<textarea class="raw-edit" id="raw-edit" spellcheck="false" aria-label="Raw nametag format">' + esc(raw) + '</textarea>' +
        '<div class="raw-foot"><span>Line breaks are written as \\n. Widgets keep their ids.</span><span class="grow"></span>' +
        '<button type="button" class="btn sm ghost" data-act="raw-cancel">Cancel</button><button type="button" class="btn sm primary" data-act="raw-apply">Apply</button></div>';
    } else {
      body = '<pre class="raw-code" id="raw-code" tabindex="0">' + (raw ? rawColoured() : '<span class="k-text" style="opacity:.5">(empty)</span>') + '</pre>' +
        '<div class="raw-foot"><span>' + raw.length + ' characters, stored as one line</span></div>';
    }
    return '<div class="raw" data-open="1">' + head + '<div class="raw-body">' + body + '</div></div>';
  }
  const serializeSafe = () => (S.fmt.exists ? A.serialize() : '');

  /* ------------------------------------------------------------------ stage */

  const SCENES = [['day', 'Daytime'], ['night', 'Night'], ['nether', 'Nether'], ['end', 'The End']];

  function stageHtml() {
    const t = S.target;
    const admin = A.isAdmin();
    const players = S.data.players || [];
    let who;
    if (admin && t && (t.type === 'global' || t.type === 'group') && players.length) {
      who = '<span class="lbl">Preview as</span><select data-input="preview" aria-label="Preview as player">' +
        players.map((p) => '<option value="' + esc(p.uuid) + '"' + (p.uuid === S.previewUuid ? ' selected' : '') + '>' + esc(p.name) + '</option>').join('') + '</select>';
    } else {
      who = '<span class="lbl">Previewing</span><span class="fixed">' + esc(S.pctx ? S.pctx.name : '') + '</span>';
    }
    const swatches = SCENES.map((s) => '<button type="button" data-act="bg" data-bg="' + s[0] + '" aria-pressed="' + (S.bg === s[0]) + '" aria-label="' + s[1] + '" title="' + s[1] + '"></button>').join('');
    const effect = A.settings().crouchEffect;
    const noCrouch = effect === 'NONE';
    return '<section class="stage" id="stage" data-bg="' + S.bg + '" aria-label="Nametag preview">' +
      '<div class="stage-ctl tl">' + who + '</div>' +
      '<div class="stage-ctl tr"><div class="swatches" role="group" aria-label="Scene">' + swatches + '</div></div>' +
      '<div class="stage-ctl bl"><button type="button" class="btn sm ghost" id="crouch-btn" data-act="crouch" aria-pressed="' + S.crouch + '"' + (noCrouch ? ' disabled title="Crouching does not change nametags on this server"' : '') + '>' + I('crouch', 'sm') + 'Crouch</button><span class="lbl" id="crouch-note"></span></div>' +
      '<div class="stage-ctl br" id="stage-info"></div>' +
      '<div class="figure" id="figure" data-crouch="0"><div class="tagwrap" id="tagwrap"></div></div></section>';
  }

  /** Patches the stage in place so the crouch and scene transitions can animate. */
  V.updateStage = () => {
    const stage = $('stage');
    if (!stage) return;
    const st = A.settings();
    const ctx = A.ctx();
    const b = A.previewBuild();
    const effect = st.crouchEffect;
    const crouching = S.crouch && effect !== 'NONE';
    stage.dataset.bg = S.bg;
    stage.querySelectorAll('[data-act="bg"]').forEach((el) => el.setAttribute('aria-pressed', String(el.dataset.bg === S.bg)));
    const cb = $('crouch-btn');
    if (cb) cb.setAttribute('aria-pressed', String(crouching));
    $('figure').dataset.crouch = crouching ? '1' : '0';
    $('crouch-note').textContent = crouching ? (effect === 'HIDE' ? 'Nametag hidden' : 'Colors fade to grey') : '';
    let tag;
    if (crouching && effect === 'HIDE') tag = '<div class="tag hidden-crouch">Hidden while crouching</div>';
    else if (!b.lines.length) tag = '<div class="tag empty">Nothing to show</div>';
    else {
      const grey = crouching && effect === 'DEFAULT' ? (ctx.platform === 'bedrock' ? NT.mini.SNEAK_GREY_BEDROCK : NT.mini.SNEAK_GREY) : null;
      tag = '<div class="tag" aria-label="' + esc(b.plain.join(' / ')) + '">' + b.lines.map((r) => '<span class="ln">' + P.runsToHtml(r, grey) + '</span>').join('') + '</div>';
    }
    $('tagwrap').innerHTML = tag;
    const n = b.lines.length;
    $('stage-info').textContent = n + (n === 1 ? ' line, ' : ' lines, ') + (ctx.lineLimit < 0 ? 'no character limit' : 'max ' + ctx.lineLimit + ' characters');
  };

  /* -------------------------------------------------------------------- rail */

  function navPlayer(p, current) {
    return '<button type="button" class="nav" data-act="go" data-type="player" data-id="' + esc(p.uuid) + '" aria-current="' + current + '">' +
      '<span class="lbl">' + esc(p.name) + '</span><span class="sub">' + esc(p.group || '') + '</span>' + (p.hasFormat ? '<span class="tick" title="Has a personal format"></span>' : '') + '</button>';
  }

  V.renderRailLists = () => {
    const t = S.target;
    const gEl = $('rail-groups');
    const pEl = $('rail-players');
    const qg = S.q.group.trim().toLowerCase();
    const qp = S.q.player.trim().toLowerCase();
    if (gEl) renderGroupList(gEl, t, qg);
    if (pEl) renderPlayerList(pEl, t, qp);
  };

  function renderGroupList(gEl, t, qg) {
    const groups = A.groupNames().filter((g) => !qg || g.name.toLowerCase().includes(qg));
    let html = groups.map((g) => '<button type="button" class="nav" data-act="go" data-type="group" data-id="' + esc(g.name) + '" aria-current="' + (!!t && t.type === 'group' && t.id === g.name) + '">' +
      '<span class="lbl">' + esc(g.name) + '</span>' + (g.stored ? '<span class="tick" title="Has a format"></span>' : '<span class="sub">global</span>') + '</button>').join('');
    if (qg && !A.groupNames().some((g) => g.name.toLowerCase() === qg)) {
      html += '<button type="button" class="nav" data-act="go" data-type="group" data-id="' + esc(S.q.group.trim()) + '"><span class="lbl">' + I('plus', 'sm') + ' Create “' + esc(S.q.group.trim()) + '”</span></button>';
    }
    gEl.innerHTML = html || '<p class="empty-note">No groups yet.</p>';
  }

  function renderPlayerList(pEl, t, qp) {
    const players = (S.data.players || []).filter((p) => !qp || p.name.toLowerCase().includes(qp));
    pEl.innerHTML = players.map((p) => navPlayer(p, !!t && t.type === 'player' && t.id === p.uuid)).join('') || '<p class="empty-note">' + (qp ? 'No players match.' : 'Nobody is online.') + '</p>';
  }

  V.renderRail = () => {
    const rail = $('rail');
    if (!S.data) { rail.innerHTML = ''; return; }
    const t = S.target || {};
    const me = S.data.session.player;
    const look = (S.data.players || []).length ? null : null;
    const meLook = S.pcache[me.uuid] && S.pcache[me.uuid].look;
    const who = '<div class="who"><div class="face">' + NT.figure.face(meLook) + '</div><div><div class="nm">' + esc(me.name) + '</div><div class="rl">' + (A.isAdmin() ? 'Administrator' : 'Player') + '</div></div></div>';
    if (!A.isAdmin()) {
      const note = A.full()
        ? '<b>Personal format.</b> An admin gave you your own nametag, so you can change all of it.'
        : '<b>Widget slots only.</b> Your admins decide which parts of your nametag you can change. Fill in the highlighted slots and save.';
      rail.innerHTML = who + '<h3>Editing</h3><button type="button" class="nav" aria-current="true"><span class="lbl">Your nametag</span></button><div class="player-note">' + note + '</div>';
      return;
    }
    const tab = A.railTab();
    const hadTabFocus = !!document.activeElement && document.activeElement.classList.contains('rail-tab') && rail.contains(document.activeElement);
    const counts = { group: A.groupNames().length, player: (S.data.players || []).length };
    const tabDefs = [['global', 'Global', 'The global format'], ['group', 'Groups', 'Group formats'], ['player', 'Players', 'Players online']];
    const tabBar = '<div class="rail-tabs" role="tablist" aria-label="Kind of format">' + tabDefs.map((d) => {
      const on = d[0] === tab;
      return '<button type="button" class="rail-tab" role="tab" id="rail-tab-' + d[0] + '" aria-controls="rail-panel" aria-selected="' + on + '" tabindex="' + (on ? 0 : -1) + '" title="' + d[2] + '" data-act="tab" data-tab="' + d[0] + '">' +
        '<span>' + d[1] + '</span>' + (counts[d[0]] != null ? '<span class="n">' + counts[d[0]] + '</span>' : '') + '</button>';
    }).join('') + '</div>';
    // Only offered when this kind of format has a Bedrock version on the server, and
    // only while one of them is open (an empty tab has nothing for it to switch).
    const seg = A.editionEnabled(tab) && t.type === tab
      ? '<div class="seg" role="group" aria-label="Edition">' +
        '<button type="button" data-act="platform" data-v="java" aria-pressed="' + (t.platform !== 'bedrock') + '">Java</button>' +
        '<button type="button" data-act="platform" data-v="bedrock" aria-pressed="' + (t.platform === 'bedrock') + '">Bedrock</button></div>'
      : '';
    let body;
    if (tab === 'global') {
      body = '<p class="rail-note">Used for everyone who has neither a group format nor a personal one.</p>';
    } else if (tab === 'group') {
      body = '<div class="search">' + I('search', 'sm') + '<input type="search" data-input="q-group" placeholder="Find or create a group" value="' + esc(S.q.group) + '" aria-label="Find or create a group" autocomplete="off"></div>' +
        '<div id="rail-groups"></div>';
    } else {
      body = '<div class="search">' + I('search', 'sm') + '<input type="search" data-input="q-player" placeholder="Find a player online" value="' + esc(S.q.player) + '" aria-label="Find a player online" autocomplete="off"></div>' +
        '<div id="rail-players"></div>';
    }
    rail.innerHTML = who + tabBar + '<div class="rail-panel" id="rail-panel" role="tabpanel" aria-labelledby="rail-tab-' + tab + '">' + seg + body + '</div>';
    V.renderRailLists();
    if (hadTabFocus) { const on = rail.querySelector('.rail-tab[aria-selected="true"]'); if (on) on.focus(); }
  };

  /* --------------------------------------------------------------------- top */

  V.renderTop = () => {
    const t = S.target;
    const dirty = A.dirty();
    const pills = [];
    if (t && t.type !== 'self' && A.editionEnabled(t.type)) pills.push('<span class="pill ' + (t.platform === 'bedrock' ? 'bedrock' : 'java') + '"><i></i>' + (t.platform === 'bedrock' ? 'Bedrock Edition' : 'Java Edition') + '</span>');
    if (t && A.editionUnused()) pills.push('<span class="pill locked" title="The server is set to share the Java formats with Bedrock players">' + I('lock', 'sm') + 'Not in use</span>');
    if (t && !A.isAdmin() && !A.full()) pills.push('<span class="pill locked">' + I('lock', 'sm') + 'Widget slots only</span>');
    if (dirty) pills.push('<span class="pill dirty">Unsaved changes</span>');
    const mock = S.bridge && S.bridge.isMock;
    $('topbar').innerHTML =
      '<button type="button" class="btn icon ghost menu-btn" data-act="rail-toggle" aria-label="Show formats" aria-expanded="' + S.railOpen + '">' + I('menu') + '</button>' +
      '<div class="brand">' + brandMark() + '<span class="brand-name">Nametags</span><span class="brand-sub">' + (A.isAdmin() ? 'Nametag Format Admin' : 'Nametag Editor') + '</span></div>' +
      '<div class="crumbs"><span class="title">' + esc(A.targetTitle()) + '</span>' + pills.join('') + '</div><div class="spacer"></div>' +
      '<div class="actions">' +
      (mock ? '<button type="button" class="btn icon ghost" data-act="sandbox" aria-label="Open test sandbox" title="Test sandbox">' + I('flask') + '</button><span class="sep"></span>' : '') +
      '<button type="button" class="btn ghost" data-act="discard"' + (dirty ? '' : ' disabled') + '>' + I('x') + '<span class="lbl-txt">Discard changes</span></button>' +
      '<button type="button" class="btn primary" data-act="save"' + (dirty && !S.busy ? '' : ' disabled') + '>' + (dirty ? '<span class="dot"></span>' : '') + I('save') + '<span class="lbl-txt">' + (S.busy ? 'Saving…' : 'Save format') + '</span></button></div>';
    $('rail-scrim').hidden = !S.railOpen;
    $('app').dataset.rail = S.railOpen ? '1' : '0';
    document.title = (dirty ? '• ' : '') + (A.targetTitle() || 'Nametags') + ' | Nametag editor';
  };

  function brandMark() {
    return '<svg class="brand-mark" viewBox="0 0 32 32" shape-rendering="crispEdges" aria-hidden="true">' +
      '<rect x="2" y="6" width="28" height="20" fill="#0f1528"/><rect x="2" y="6" width="28" height="2" fill="#f4b942"/>' +
      '<rect x="6" y="12" width="9" height="3" fill="#ff7f7f"/><rect x="17" y="12" width="9" height="3" fill="#e9edf8"/>' +
      '<rect x="8" y="18" width="16" height="3" fill="#55d7ea"/><rect x="12" y="26" width="8" height="2" fill="#0f1528"/><rect x="14" y="28" width="4" height="2" fill="#0f1528"/></svg>';
  }

  V.renderMock = () => {
    const el = $('mockbar');
    const mock = S.bridge && S.bridge.isMock;
    el.hidden = !mock;
    if (mock) el.innerHTML = '<b>Test session</b><span>Running on made-up players, groups and placeholders. Nothing here reaches a server.</span><button type="button" class="link" data-act="sandbox">Open sandbox</button>';
  };

  /* ------------------------------------------------------------------- work */

  V.renderWork = () => {
    const work = $('work');
    if (!S.data || !S.target) { work.innerHTML = ''; return; }
    work.setAttribute('aria-busy', String(!!S.loading));
    work.innerHTML = '<div id="stage-slot">' + stageHtml() + '</div><div id="compose-slot" class="compose">' + composerHtml() + '</div><div id="raw-slot" class="raw-slot">' + rawHtml() + '</div>';
    V.updateStage();
  };

  V.renderCanvas = () => {
    const slot = $('compose-slot');
    if (!slot) return;
    slot.innerHTML = composerHtml();
  };
  V.renderRaw = () => {
    const slot = $('raw-slot');
    if (!slot) return;
    slot.innerHTML = rawHtml();
  };

  /** Everything that reflects the working copy, but not the inspector (so inputs keep focus while typing). */
  V.renderLive = () => {
    V.renderCanvas();
    V.updateStage();
    if (!S.rawEditing) V.renderRaw();
    V.renderTop();
  };

  V.renderAll = () => {
    V.renderTop();
    V.renderMock();
    V.renderRail();
    V.renderWork();
    V.renderInspector();
    V.renderLayer();
  };

  V.focusRef = (ref) => {
    if (!ref) return;
    const el = document.querySelector('#work [data-ref="' + D.key(ref) + '"]');
    if (!el) return;
    const target = el.matches('.widget') ? el.querySelector('.widget-head') : el;
    if (target) target.focus({ preventScroll: false });
  };

  /* ----------------------------------------------------------- obfuscated */
  // Keeps every currently-rendered obfuscated run cycling to new random
  // glyphs, the way it looks in the actual game. `scrambleObf` (10-render.js)
  // is the pure per-frame generator; this is just the ticking clock for it.
  const reduceMotion = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  if (!reduceMotion) {
    setInterval(() => {
      const els = document.querySelectorAll('.mc-o[data-obf]');
      for (let i = 0; i < els.length; i++) {
        const el = els[i];
        const scrambled = P.scrambleObf(el.dataset.obf);
        const cells = el.children; // one .mc-o-c per character — only their
        // text changes here, never the cell count/width, so the plate stays put.
        for (let j = 0; j < cells.length && j < scrambled.length; j++) cells[j].textContent = scrambled[j];
      }
    }, 60);
  }

  V.KIND = KIND;
  V.chipHtml = chipHtml;
  V.addChipHtml = addChipHtml;
})((globalThis.NT = globalThis.NT || {}));
