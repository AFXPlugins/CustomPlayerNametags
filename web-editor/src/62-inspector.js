/* ==========================================================================
 * 62-inspector.js — the right-hand inspector, the add-menu popover, the
 * confirm dialog and toasts. Same rules as 60-view.js: strings out, no
 * listeners (70-events.js reads the data-* attributes).
 * ========================================================================== */
(function (NT) {
  'use strict';
  const A = NT.app;
  const S = A.S;
  const V = A.view;
  const M = NT.model;
  const D = NT.doc;
  const P = NT.preview;
  const I = NT.icon;
  const esc = P.esc;
  const $ = (id) => document.getElementById(id);
  const KIND = V.KIND;

  const RAINBOW = 'linear-gradient(90deg,#ff5555,#ffaa00,#ffff55,#55ff55,#55ffff,#5555ff,#ff55ff)';
  const SHORT = { strikethrough: 'Strike', underlined: 'Underline', obfuscated: 'Obfuscate' };
  const STYLES = ['bold', 'italic', 'underlined', 'strikethrough', 'obfuscated'];
  const STYLE_LABEL = { bold: 'Bold', italic: 'Italic', underlined: 'Underlined', strikethrough: 'Strikethrough', obfuscated: 'Obfuscated' };

  /* ---------------------------------------------------------------- helpers */

  function head(kind, title, crumb) {
    const k = KIND[kind] || { icon: 'sliders', color: 'var(--text)' };
    return '<div class="insp-head" style="--kc:' + k.color + '"><div class="ico">' + I(k.icon) + '</div>' +
      '<div class="grow"><h2>' + esc(title) + '</h2><div class="crumb">' + esc(crumb) + '</div></div>' +
      '<button type="button" class="btn sm icon ghost sheet-close" data-act="deselect" aria-label="Close panel">' + I('x') + '</button></div>';
  }

  const crumbFor = (ref) => 'Line ' + (ref.l + 1) + (ref.w != null ? ', inside a widget' : '');

  function foot(ref) {
    if (!A.canModify(ref)) return '';
    const list = D.listOf(S.lines, ref) || [];
    return '<div class="insp-foot">' +
      '<button type="button" class="btn sm icon" data-act="nudge" data-dir="-1" aria-label="Move earlier" title="Move earlier (Alt+Left)"' + (ref.i === 0 ? ' disabled' : '') + '>' + I('arrow-left') + '</button>' +
      '<button type="button" class="btn sm icon" data-act="nudge" data-dir="1" aria-label="Move later" title="Move later (Alt+Right)"' + (ref.i >= list.length - 1 ? ' disabled' : '') + '>' + I('arrow-right') + '</button>' +
      (A.full() ? '<button type="button" class="btn sm icon" data-act="dup-chunk" aria-label="Duplicate" title="Duplicate">' + I('copy') + '</button>' : '') +
      '<span class="grow"></span>' +
      '<button type="button" class="btn sm danger" data-act="del-chunk">' + I('trash') + 'Remove</button></div>';
  }

  function switchRow(input, title, desc, checked, disabled) {
    return '<div class="switch-row"><div class="tx"><b>' + title + '</b><span>' + desc + '</span></div>' +
      '<label class="switch"><input type="checkbox" data-input="' + input + '"' + (checked ? ' checked' : '') + (disabled ? ' disabled' : '') + ' aria-label="' + esc(title) + '"><i></i></label></div>';
  }
  V.switchRow = switchRow;

  /** "12/14 characters" for the widget a chunk lives in (or is). Updated live while typing. */
  function meterHtml(widget) {
    const used = A.widgetUsage(widget);
    const over = widget.limit >= 0 && used > widget.limit;
    return '<span class="count-note' + (over ? ' warn' : '') + '" id="w-meter">' + used + (widget.limit >= 0 ? '/' + widget.limit : '') + ' characters' + (over ? ', the rest is cut' : '') + '</span>';
  }
  V.updateMeter = () => {
    const el = $('w-meter');
    if (!el || !S.sel) return;
    const w = S.sel.w != null ? S.lines[S.sel.l][S.sel.w] : A.selected();
    if (w && w.kind === 'widget') el.outerHTML = meterHtml(w);
  };

  const pal = (spec, sw, label, cur) =>
    '<button type="button" class="pal" data-act="color-set" data-v="' + esc(spec) + '" aria-pressed="' + (cur === spec) + '">' + sw + '<span>' + esc(label) + '</span></button>';
  const namedSw = (name) => '<span class="sw" style="background:' + NT.mini.NAMED[name] + '"></span>';

  /* ---------------------------------------------------------- per-kind bodies */

  function inspText(c, ref) {
    const w = A.widgetOf(ref);
    return '<div><label class="field-label" for="i-text">Text</label>' +
      '<input id="i-text" class="field" data-input="text" value="' + esc(c.text) + '" autocomplete="off" spellcheck="false">' +
      '<p class="help">Shown exactly as typed. Spaces at the ends count. Typed colour codes and placeholders become their own chips when you leave the field.</p>' +
      '<div class="row between" style="margin-top:8px"><span class="count-note" id="t-count">' + c.text.length + ' characters</span>' + (w ? meterHtml(w) : '') + '</div></div>';
  }

  function currentHex(c) {
    const spec = A.specOf(c.raw);
    if (spec && spec.indexOf('hex:') === 0) return spec.slice(4);
    if (spec && spec.indexOf('name:') === 0) return NT.mini.NAMED[spec.slice(5)] || '#f4b942';
    const st = A.gradientStops(c.raw);
    return st ? st[0] : '#f4b942';
  }

  function inspColor(c) {
    const cur = A.specOf(c.raw);
    const isGrad = cur === 'gradient';
    const named = '<div class="palette">' + V.COLOR_ORDER.map((n) => pal('name:' + n, namedSw(n), A.prettify(n), cur)).join('') + '</div>';
    const hex = currentHex(c);
    const custom = '<div class="custom"><div class="row"><input type="color" data-input="hex-picker" value="' + hex + '" aria-label="Pick a colour">' +
      '<input class="field mono" data-input="hex-text" value="' + hex + '" maxlength="7" spellcheck="false" aria-label="Hex colour"><button type="button" class="btn sm" data-act="hex-apply">Apply</button></div></div>';
    const styles = '<div class="palette">' + STYLES.map((s) => pal('style:' + s, V.swatchHtml({ kind: 'style', label: STYLE_LABEL[s] }), SHORT[s] || STYLE_LABEL[s], cur)).join('') +
      pal('reset', '<span class="sw style">' + I('refresh', 'sm') + '</span>', 'Reset', cur) + '</div>';
    let effects = '<div class="palette">' +
      (isGrad ? '' : pal('gradient:' + hex + ',#e52e71', '<span class="sw" style="background:linear-gradient(90deg,' + hex + ',#e52e71)"></span>', 'Gradient', cur)) +
      pal('rainbow', '<span class="sw" style="background:' + RAINBOW + '"></span>', 'Rainbow', cur) + '</div>';
    if (isGrad) {
      const stops = A.gradientStops(c.raw) || ['#ff8a00', '#e52e71'];
      effects = '<div class="custom"><div class="grad-preview" style="background:linear-gradient(90deg,' + stops.join(',') + ')"></div><div class="row" style="flex-wrap:wrap">' +
        stops.map((s, i) => '<input type="color" data-input="grad-stop" data-idx="' + i + '" value="' + s + '" aria-label="Gradient colour ' + (i + 1) + '">').join('') +
        '<button type="button" class="btn sm icon" data-act="grad-add" aria-label="Add a colour stop" title="Add a colour"' + (stops.length >= 5 ? ' disabled' : '') + '>' + I('plus') + '</button>' +
        '<button type="button" class="btn sm icon" data-act="grad-del" aria-label="Remove a colour stop" title="Remove the last colour"' + (stops.length <= 2 ? ' disabled' : '') + '>' + I('minus') + '</button></div></div>' +
        '<div class="palette" style="margin-top:8px">' + pal('rainbow', '<span class="sw" style="background:' + RAINBOW + '"></span>', 'Rainbow', cur) + '</div>';
    }
    const ends = '<div class="palette">' + STYLES.concat(['gradient', 'rainbow']).map((n) =>
      pal('close:' + n, '<span class="sw style">/</span>', 'End ' + (SHORT[n] || A.prettify(n)).toLowerCase(), cur)).join('') + '</div>';
    return '<div><h3 class="group-title">Colors</h3>' + named + '</div>' +
      '<div><h3 class="group-title">Custom color</h3>' + custom + '</div>' +
      '<div><h3 class="group-title">Styles</h3>' + styles + '</div>' +
      '<div><h3 class="group-title">Effects</h3>' + effects + '</div>' +
      '<div><h3 class="group-title">Stop an effect</h3>' + ends + '<p class="help">Styles, gradients and rainbows run until you end them or reset.</p></div>';
  }

  function inspPlaceholder(c) {
    const list = [{ value: M.PLAYER_TAG, title: 'Player name' }].concat((S.data.placeholders || []).map((p) => ({ value: p.value, title: p.title || A.prettify(p.key) })));
    const known = list.some((p) => p.value === c.raw);
    const items = list.map((p) => {
      const info = A.phInfo(p.value);
      const now = info.state === 'none' ? 'empty' : info.state === 'miss' ? '' : info.text;
      return '<button type="button" class="pick" data-act="ph-set" data-v="' + esc(p.value) + '" aria-pressed="' + (p.value === c.raw) + '">' + I('percent') +
        '<span class="nm"><b>' + esc(p.title) + '</b><span>' + esc(p.value) + '</span></span><span class="now">' + esc(now) + '</span></button>';
    }).join('');
    const custom = A.full()
      ? '<div><label class="field-label" for="i-ph">Any placeholder</label><input id="i-ph" class="field mono" data-input="ph-custom" placeholder="%placeholder_name%" value="' + (known ? '' : esc(c.raw)) + '" spellcheck="false" autocomplete="off">' +
        '<p class="help">Type any PlaceholderAPI placeholder. Values that are not in the list cannot be previewed here.</p></div>'
      : '';
    return '<div><h3 class="group-title">Value <span class="grow"></span><span class="count-note">shown for ' + esc(S.pctx ? S.pctx.name : '') + '</span></h3><div class="pick-list">' + items + '</div></div>' + custom;
  }

  function inspLimit(c) {
    const server = A.ctx().lineLimit;
    return '<div><label class="field-label" for="i-limit">Maximum characters on this line</label>' +
      '<div class="row"><input id="i-limit" type="number" class="field num" min="0" max="999" value="' + c.limit + '" data-input="limit"><span class="count-note">characters</span></div>' +
      '<p class="help">Only this line is cut, ending in an ellipsis if the server shows one. Colors do not count. ' +
      (server < 0 ? 'The previewed player has no server limit.' : 'The server limit for the previewed player is ' + server + '.') + '</p></div>';
  }

  function inspWidget(w, ref) {
    const full = A.full();
    const lr = { l: ref.l, w: ref.i };
    const items = w.contents.map((c, j) => V.chipHtml(c, { l: ref.l, w: ref.i, i: j })).join('');
    const contents = '<div><h3 class="group-title">' + (full ? 'Default contents' : 'Your text') + '<span class="grow"></span>' + meterHtml(w) + '</h3>' +
      '<div class="contents"><div class="widget-items" data-list="' + D.listKey(lr) + '">' + items +
      (items ? '' : '<span class="slot-empty">Empty</span>') + V.addChipHtml(D.listKey(lr), 'Add to widget') + '</div></div>' +
      '<p class="help">' + (full ? 'What the slot shows until a player fills it in.' : 'Add text, colors or placeholders. Drag chips to reorder them.') + '</p></div>';
    const pills = '<div class="allowed"><span class="pill' + (w.text ? '' : ' no') + '">Text</span><span class="pill' + (w.colors ? '' : ' no') + '">Colors</span><span class="pill' + (w.placeholders ? '' : ' no') + '">Placeholders</span>' +
      (w.limit >= 0 ? '<span class="pill">Up to ' + w.limit + ' characters</span>' : '<span class="pill">No length limit</span>') + '</div>';
    if (!full) {
      return '<div><h3 class="group-title">This slot allows</h3>' + pills + '</div>' + contents;
    }
    const limitRow = '<div class="switches">' +
      switchRow('w-text', 'Free text', 'Players can type words', w.text) +
      switchRow('w-colors', 'Colors', 'Players can add colors and styles', w.colors) +
      switchRow('w-placeholders', 'Placeholders', 'Players can add values like ping', w.placeholders) +
      switchRow('w-limited', 'Limit length', 'Cut what players write', w.limit >= 0) + '</div>' +
      (w.limit >= 0 ? '<div class="row" style="margin-top:8px"><input type="number" class="field num" min="0" max="256" value="' + w.limit + '" data-input="w-limit" aria-label="Widget character limit"><span class="count-note">characters</span></div>' : '');
    return '<div><h3 class="group-title">What players can add</h3>' + limitRow + '</div>' + contents +
      '<div class="kv">Slot id <code>' + esc(w.widgetId) + '</code></div>' +
      '<p class="help" style="margin-top:-8px">Each player’s text is saved under this id. Duplicating a widget gives the copy a new one.</p>';
  }

  /* ---------------------------------------------------------- nothing selected */

  function inspNone() {
    const t = S.target;
    const admin = A.isAdmin();
    const full = A.full();
    const legendItem = (k, title, desc) => '<div class="legend-item" style="--kc:' + KIND[k].color + '"><div class="ico">' + I(KIND[k].icon) + '</div><div><b>' + title + '</b><span>' + desc + '</span></div></div>';
    let body = '<div><h3 class="group-title">Applies to</h3><p class="help" style="margin:0;color:var(--muted)">' + esc(A.appliesTo()) + '</p></div>';
    if (full) {
      body += '<div class="legend">' +
        legendItem('text', 'Text', 'Words and symbols, shown exactly as typed.') +
        legendItem('color', 'Color', 'Colors, styles and gradients. They last until ended or reset.') +
        legendItem('placeholder', 'Placeholder', 'A live value, such as ping or rank, filled in per player.') +
        legendItem('widget', 'Widget', 'A slot that players fill in themselves, within limits you set.') + '</div>';
    } else {
      body += '<div class="legend">' + legendItem('widget', 'Widget slots', 'The highlighted boxes on your nametag are yours to fill in.') + '</div>' +
        '<div class="tips">Pick a slot, then press <kbd>+</kbd> to add text. Colors and placeholders appear only if the slot allows them. <kbd>Ctrl</kbd> + <kbd>S</kbd> saves.</div>';
    }
    const ctx = A.ctx();
    body += '<div><h3 class="group-title">Preview rules</h3>' +
      '<div class="kv">Line limit <code>' + (ctx.lineLimit < 0 ? 'none' : ctx.lineLimit) + '</code></div>' +
      '<div class="kv" style="margin-top:6px">Crouching <code>' + esc(String(A.settings().crouchEffect || 'DEFAULT').toLowerCase()) + '</code></div></div>';
    if (admin && t.type === 'player' && S.fmt.exists) {
      body += '<button type="button" class="btn danger" data-act="clear-override">' + I('trash') + 'Remove personal format</button>';
    }
    if (admin && t.type === 'group' && S.fmt.exists) {
      body += '<button type="button" class="btn danger" data-act="clear-group-format">' + I('trash') + 'Remove group format</button>';
    }
    return { head: head('sliders', A.targetTitle(), 'Nothing selected'), body, foot: '' };
  }

  /* -------------------------------------------------------------- assemble */

  V.renderInspector = () => {
    const el = $('inspector');
    if (!el) return;
    if (!S.data || !S.target) { el.innerHTML = ''; return; }
    const c = A.selected();
    let parts;
    if (!c) {
      S.sel = null;
      parts = inspNone();
    } else {
      const ref = S.sel;
      const k = KIND[c.kind];
      const title = c.kind === 'color' ? NT.mini.describeTag(c.raw).label : c.kind === 'placeholder' ? A.phInfo(c.raw).title : k.label;
      const editable = A.canModify(ref);
      let body;
      if (c.kind === 'widget') body = inspWidget(c, ref);
      else if (!editable) body = '<p class="help">You can’t change this part of the nametag.</p>';
      else if (c.kind === 'text') body = inspText(c, ref);
      else if (c.kind === 'color') body = inspColor(c);
      else if (c.kind === 'placeholder') body = inspPlaceholder(c);
      else body = inspLimit(c);
      parts = { head: head(c.kind, title, crumbFor(ref)), body, foot: foot(ref) };
    }
    el.dataset.open = c ? '1' : '0';
    el.innerHTML = parts.head + '<div class="insp-body">' + parts.body + '</div>' + parts.foot;
  };

  /* ---------------------------------------------------------- add-menu popover */

  V.openPop = (anchor, lr) => {
    if (S.menu) V.closeCtx(false);
    const r = anchor.getBoundingClientRect();
    S.pop = { list: lr, view: 'menu', rect: { left: r.left, top: r.top, bottom: r.bottom }, anchor };
    V.renderPop();
  };
  /** The same popover, opened at a point instead of under a button (used by the right-click menu). */
  V.openPopAt = (x, y, lr) => {
    if (S.menu) V.closeCtx(false);
    S.pop = { list: lr, view: 'menu', rect: { left: x, top: y, bottom: y }, anchor: null };
    V.renderPop();
  };
  /** The same popover again, but jumped straight to one kind's view, pre-filled
   *  with `ref`'s current value, and wired to edit that chunk in place instead
   *  of adding a new one (used by the right-click menu's "Edit …"). */
  V.openEditPop = (x, y, ref, view) => {
    if (S.menu) V.closeCtx(false);
    S.pop = { list: { l: ref.l, w: ref.w }, view, rect: { left: x, top: y, bottom: y }, anchor: null, editRef: ref };
    V.renderPop();
  };
  V.closePop = (restoreFocus) => {
    const a = S.pop && S.pop.anchor;
    S.pop = null;
    V.renderPop();
    if (restoreFocus && a && document.contains(a)) a.focus();
  };

  function menuItem(k, ok, hint) {
    const m = KIND[k];
    return '<button type="button" class="menu-item k-' + k + '" role="menuitem" data-act="pop-pick" data-v="' + k + '"' + (ok ? '' : ' disabled') + '>' +
      '<span class="ico">' + I(m.icon) + '</span><span class="mi-tx"><b>' + m.label + '</b><span>' + (ok ? (hint || m.hint) : 'Not allowed in this slot') + '</span></span></button>';
  }

  function popBody() {
    const p = S.pop;
    const lr = p.list;
    const editing = !!p.editRef;
    const allowed = A.allowedIn(lr);
    const inWidget = lr.w != null;
    const paletteOf = (specs) => '<div class="palette">' + specs.join('') + '</div>';
    const padd = (spec, sw, label, pressed) => '<button type="button" class="pal" data-act="pop-add-color" data-v="' + esc(spec) + '"' + (pressed ? ' aria-pressed="true"' : '') + '>' + sw + '<span>' + esc(label) + '</span></button>';
    switch (p.view) {
      case 'text': {
        const cur = editing ? (A.selected() || { text: '' }).text : '';
        const cancelAct = editing ? 'pop-close' : 'pop-back';
        const cancelLbl = editing ? 'Cancel' : 'Back';
        return { title: editing ? 'Edit text' : 'Add text', wide: false, html: '<div class="pop-form"><div><label class="field-label" for="pop-text">Text</label><input id="pop-text" class="field" data-pop-input="text" placeholder="Type anything" value="' + esc(cur) + '" autocomplete="off" spellcheck="false"></div>' +
          '<div class="row between"><button type="button" class="btn sm ghost" data-act="' + cancelAct + '">' + cancelLbl + '</button><button type="button" class="btn sm primary" data-act="pop-submit">' + (editing ? 'Save' : 'Add text') + '</button></div></div>' };
      }
      case 'color': {
        const curSpec = editing ? A.specOf((A.selected() || {}).raw) : null;
        const named = V.COLOR_ORDER.map((n) => padd('name:' + n, namedSw(n), A.prettify(n), curSpec === 'name:' + n));
        const styles = STYLES.map((s) => padd('style:' + s, V.swatchHtml({ kind: 'style', label: STYLE_LABEL[s] }), SHORT[s] || STYLE_LABEL[s], curSpec === 'style:' + s))
          .concat([padd('reset', '<span class="sw style">' + I('refresh', 'sm') + '</span>', 'Reset', curSpec === 'reset')]);
        const fx = [padd('hex:#f4b942', '<span class="sw" style="background:conic-gradient(#f55,#fa0,#ff5,#5f5,#5ff,#55f,#f5f,#f55)"></span>', 'Custom', curSpec && curSpec.indexOf('hex:') === 0),
          padd('gradient:#ff8a00,#e52e71', '<span class="sw" style="background:linear-gradient(90deg,#ff8a00,#e52e71)"></span>', 'Gradient', curSpec === 'gradient'),
          padd('rainbow', '<span class="sw" style="background:' + RAINBOW + '"></span>', 'Rainbow', curSpec === 'rainbow')];
        return { title: editing ? 'Edit color' : 'Add a color', wide: true, html: '<div class="pop-form"><div class="group-title" style="margin:0">Colors</div>' + paletteOf(named) +
          '<div class="group-title" style="margin:6px 0 0">Styles</div>' + paletteOf(styles) + '<div class="group-title" style="margin:6px 0 0">Effects</div>' + paletteOf(fx) + '</div>' };
      }
      case 'placeholder': {
        const cur = editing ? (A.selected() || {}).raw : null;
        const list = [{ value: M.PLAYER_TAG, title: 'Player name' }].concat((S.data.placeholders || []).map((q) => ({ value: q.value, title: q.title || A.prettify(q.key) })));
        return { title: editing ? 'Edit placeholder' : 'Add a placeholder', wide: true, html: '<div class="pick-list">' + list.map((q) => {
          const info = A.phInfo(q.value);
          return '<button type="button" class="pick" data-act="pop-add-ph" data-v="' + esc(q.value) + '"' + (cur === q.value ? ' aria-pressed="true"' : '') + '>' + I('percent') +
            '<span class="nm"><b>' + esc(q.title) + '</b><span>' + esc(q.value) + '</span></span><span class="now">' + esc(info.state === 'ok' ? info.text : info.state === 'none' ? 'empty' : '') + '</span></button>';
        }).join('') + '</div>' };
      }
      default: {
        let items = menuItem('text', allowed.text) + menuItem('color', allowed.colors) + menuItem('placeholder', allowed.placeholders);
        if (!inWidget) items += menuItem('widget', true) + menuItem('newline', true);
        return { title: inWidget ? 'Add to widget' : 'Add to line ' + (lr.l + 1), wide: false, html: '<div role="menu">' + items + '</div>' };
      }
    }
  }

  V.renderPop = () => {
    const host = $('layer-pop');
    if (!host) return;
    if (!S.pop) { host.innerHTML = ''; return; }
    const b = popBody();
    host.innerHTML = '<div class="pop' + (b.wide ? ' wide' : '') + '" id="pop" role="dialog" aria-label="' + esc(b.title) + '">' +
      '<div class="pop-head">' + (S.pop.view !== 'menu' && !S.pop.editRef ? '<button type="button" class="btn sm icon ghost" data-act="pop-back" aria-label="Back">' + I('chevron-left') + '</button>' : '') +
      '<span class="grow">' + esc(b.title) + '</span><button type="button" class="btn sm icon ghost" data-act="pop-close" aria-label="Close">' + I('x') + '</button></div>' + b.html + '</div>';
    const el = $('pop');
    const r = S.pop.rect;
    const w = el.offsetWidth;
    const h = el.offsetHeight;
    let left = Math.min(Math.max(8, r.left), window.innerWidth - w - 8);
    let top = r.bottom + 6;
    if (top + h > window.innerHeight - 8) top = Math.max(8, r.top - h - 6);
    el.style.left = left + 'px';
    el.style.top = top + 'px';
    const first = el.querySelector('input,.menu-item:not(:disabled),.pal,.pick');
    if (first) first.focus({ preventScroll: true });
  };

  /* ------------------------------------------------------- right-click menu */

  // A small menu for one chip or widget. It only calls the same actions the
  // inspector footer does, so the two always agree on what is allowed.

  V.openCtx = (ref, x, y) => {
    if (!A.canModify(ref)) return false;
    if (S.pop) V.closePop(false);
    S.menu = { ref, x, y };
    V.renderCtx();
    return true;
  };
  V.closeCtx = (restoreFocus) => {
    const m = S.menu;
    S.menu = null;
    V.renderCtx();
    if (restoreFocus && m) V.focusRef(m.ref);
  };

  function ctxItem(act, icon, label, o) {
    o = o || {};
    return '<button type="button" class="ctx-item' + (o.danger ? ' danger' : '') + '" role="menuitem" data-act="' + act + '"' + (o.disabled ? ' disabled' : '') + '>' +
      I(icon, 'sm') + '<span class="lbl">' + label + '</span>' + (o.key ? '<span class="key">' + o.key + '</span>' : '') + '</button>';
  }

  function ctxBody(ref) {
    const list = D.listOf(S.lines, { l: ref.l, w: ref.w }) || [];
    const c = list[ref.i];
    if (!c) return '';
    const sep = '<div class="ctx-sep" role="separator"></div>';
    let html = ctxItem('ctx-edit', 'edit', 'Edit ' + KIND[c.kind].label.toLowerCase());
    if (c.kind === 'widget' && A.canModify({ l: ref.l, w: ref.i, i: 0 })) html += ctxItem('ctx-add', 'plus', 'Add to widget…');
    // Reordering (Move earlier/later) is left to the inspector footer and the
    // Alt+←/→ shortcut; it stays off this menu to keep it short.
    if (A.full()) html += sep + ctxItem('ctx-dup', 'copy', 'Duplicate');
    html += sep + ctxItem('ctx-del', 'trash', 'Delete', { danger: true, key: 'Del' });
    return html;
  }

  V.renderCtx = () => {
    const host = $('layer-ctx');
    if (!host) return;
    if (!S.menu) { host.innerHTML = ''; return; }
    const html = ctxBody(S.menu.ref);
    if (!html) { S.menu = null; host.innerHTML = ''; return; }
    host.innerHTML = '<div class="ctx" id="ctx" role="menu" aria-label="Item actions">' + html + '</div>';
    const el = $('ctx');
    const w = el.offsetWidth;
    const h = el.offsetHeight;
    el.style.left = Math.max(8, Math.min(S.menu.x, window.innerWidth - w - 8)) + 'px';
    el.style.top = Math.max(8, S.menu.y + h > window.innerHeight - 8 ? S.menu.y - h : S.menu.y) + 'px';
    const first = el.querySelector('.ctx-item:not(:disabled)');
    if (first) first.focus({ preventScroll: true });
  };

  /* ------------------------------------------------------------ modal + toast */

  V.confirm = (opts) => new Promise((resolve) => {
    S.modal = { opts, resolve };
    const host = $('layer-modal');
    host.innerHTML = '<div class="scrim" data-act="modal-value" data-v="__cancel"></div>' +
      '<div class="modal" role="dialog" aria-modal="true" aria-labelledby="modal-title"><h2 id="modal-title">' + esc(opts.title) + '</h2><p>' + esc(opts.body) + '</p><div class="btns">' +
      opts.buttons.map((b) => '<button type="button" class="btn' + (b.kind ? ' ' + b.kind : '') + '" data-act="modal-value" data-v="' + esc(b.value) + '">' + esc(b.label) + '</button>').join('') + '</div></div>';
    const primary = host.querySelector('.btn.primary') || host.querySelector('.btn:last-child');
    if (primary) primary.focus();
  });
  V.closeModal = (value) => {
    if (!S.modal) return;
    const m = S.modal;
    S.modal = null;
    $('layer-modal').innerHTML = '';
    m.resolve(value === '__cancel' ? (m.opts.buttons[0] && m.opts.buttons[0].value) : value);
  };

  V.toast = (msg, opts) => {
    const o = opts || {};
    const kind = o.kind || 'ok';
    const icon = kind === 'ok' ? 'check' : kind === 'warn' ? 'alert' : 'alert';
    const el = document.createElement('div');
    el.className = 'toast ' + kind;
    el.setAttribute('role', kind === 'err' ? 'alert' : 'status');
    el.innerHTML = I(icon) + '<span>' + esc(msg) + '</span>' + (o.action ? '<button type="button" class="link">' + esc(o.action.label) + '</button>' : '') +
      '<button type="button" class="btn sm icon ghost" aria-label="Dismiss">' + I('x') + '</button>';
    const host = $('toasts');
    host.appendChild(el);
    const done = () => { if (el.parentNode) el.parentNode.removeChild(el); };
    const btns = el.querySelectorAll('button');
    if (o.action) btns[0].addEventListener('click', () => { o.action.fn(); done(); });
    btns[btns.length - 1].addEventListener('click', done);
    while (host.children.length > 3) host.removeChild(host.firstChild);
    setTimeout(done, kind === 'err' ? 8000 : 4200);
  };

  V.renderLayer = () => { V.renderPop(); };
})((globalThis.NT = globalThis.NT || {}));
