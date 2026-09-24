/* ==========================================================================
 * 70-events.js — the only file that touches addEventListener. Reads the
 * data-act / data-input / data-pop-input attributes 60-view.js and
 * 62-inspector.js render, and calls into NT.app (A) or NT.app.view (V).
 * ========================================================================== */
(function (NT) {
  'use strict';
  const A = NT.app;
  const S = A.S;
  const D = NT.doc;
  const $ = (id) => document.getElementById(id);

  const liveInsp = () => { A.view.renderLive(); A.view.renderInspector(); };

  /* -------------------------------------------------------------- popover */

  function submitPop() {
    if (!S.pop) return;
    const view = S.pop.view;
    if (view === 'text') {
      const el = $('pop-text');
      if (S.pop.editRef) A.setSelectedText(el ? el.value : '');
      else A.addText(S.pop.list, el ? el.value : '');
    } else return;
    A.view.closePop(true);
    liveInsp();
  }

  /* ----------------------------------------------------------- right-click menu */

  function runCtx(act) {
    const m = S.menu;
    if (!m) return;
    const ref = m.ref;
    A.view.closeCtx(false);
    switch (act) {
      case 'ctx-edit': {
        A.selectRef(ref);
        const c = A.selected();
        if (c && (c.kind === 'text' || c.kind === 'color' || c.kind === 'placeholder')) {
          A.view.openEditPop(m.x, m.y, ref, c.kind);
          return;
        }
        const field = document.querySelector('#inspector [data-input="text"]');
        if (field) field.focus(); else A.view.focusRef(ref);
        return;
      }
      case 'ctx-add': A.view.openPopAt(m.x, m.y, { l: ref.l, w: ref.i }); return;
      case 'ctx-dup': A.duplicateChunk(ref); break;
      case 'ctx-del': A.removeChunk(ref); break;
      default: return;
    }
    liveInsp();
    if (S.sel) A.view.focusRef(S.sel);
  }

  // Right-click (or the Menu key / Shift+F10) on a chip or widget. Anything we
  // don't handle keeps the browser's own menu, so copy/paste still works in fields.
  document.addEventListener('contextmenu', (e) => {
    if (e.target.closest('#layer-ctx')) { e.preventDefault(); return; }
    const el = e.target.closest('#work .chip[data-ref], #work .widget[data-ref]');
    if (!el || e.target.closest('.chip.add')) return;
    const ref = D.parseKey(el.dataset.ref);
    if (!A.canModify(ref)) return;
    e.preventDefault();
    let x = e.clientX;
    let y = e.clientY;
    if (!x && !y) { const r = el.getBoundingClientRect(); x = r.left; y = r.bottom; } // keyboard
    A.selectRef(ref);
    A.view.openCtx(ref, x, y);
  });

  /* ---------------------------------------------------------------- click */

  document.addEventListener('click', async (e) => {
    const btn = e.target.closest('[data-act]');
    if (!btn) return;
    const act = btn.dataset.act;

    switch (act) {
      case 'select': A.select(D.parseKey(btn.dataset.ref)); return;
      case 'deselect': A.deselect(); return;
      case 'add': A.view.openPop(btn, D.parseListKey(btn.dataset.list)); return;

      case 'rail-toggle': A.toggleRail(); return;
      case 'discard': A.discard(); return;
      case 'save': A.save(); return;
      case 'undo': A.undo(); return;
      case 'redo': A.redo(); return;
      case 'retry': A.retry(); return;
      case 'start-from-current': A.startFromCurrent(); return;
      case 'clear-override': A.clearOverride(); return;
      case 'clear-group-format': A.clearGroupFormat(); return;
      case 'sandbox': NT.sandbox && NT.sandbox.open(); return;

      case 'publish': A.openPublish(); return;
      case 'publish-close': A.view.closePublish(); return;
      case 'publish-copy': A.copyPublishCommands(btn); return;

      case 'go': {
        const type = btn.dataset.type;
        const id = btn.dataset.id;
        await A.goto(type === 'global' ? { type: 'global' } : { type, id });
        return;
      }
      case 'tab': await A.openTab(btn.dataset.tab); return;
      case 'platform': await A.setPlatform(btn.dataset.v); return;
      case 'bg': A.setBg(btn.dataset.bg); return;
      case 'crouch': A.toggleCrouch(); return;

      case 'add-line': A.addLine(); A.view.renderWork(); return;
      case 'line-up': A.moveLineUp(parseInt(btn.dataset.line, 10)); A.view.renderWork(); return;
      case 'line-down': A.moveLineDown(parseInt(btn.dataset.line, 10)); A.view.renderWork(); return;
      case 'line-dup': A.duplicateLine(parseInt(btn.dataset.line, 10)); A.view.renderWork(); return;
      case 'line-del': A.deleteLine(parseInt(btn.dataset.line, 10)); A.view.renderWork(); return;

      case 'raw-toggle': A.toggleRawOpen(); return;
      case 'raw-edit': A.startRawEdit(); return;
      case 'raw-cancel': A.cancelRawEdit(); return;
      case 'raw-apply': { const el = $('raw-edit'); A.applyRawEdit(el ? el.value : ''); A.view.renderAll(); return; }
      case 'raw-copy': {
        const text = A.serialize();
        if (navigator.clipboard) navigator.clipboard.writeText(text).catch(() => {});
        A.view.toast('Copied to clipboard.', { kind: 'ok' });
        return;
      }

      case 'nudge': { if (!S.sel) return; A.nudge(S.sel, parseInt(btn.dataset.dir, 10)); liveInsp(); return; }
      case 'dup-chunk': { if (!S.sel) return; A.duplicateChunk(S.sel); liveInsp(); return; }
      case 'del-chunk': { if (!S.sel) return; A.removeChunk(S.sel); liveInsp(); return; }

      case 'color-set': A.setSelectedColor(btn.dataset.v); liveInsp(); return;
      case 'hex-apply': { const el = document.querySelector('[data-input="hex-text"]'); A.setSelectedHex(el ? el.value : ''); liveInsp(); return; }
      case 'grad-add': A.addGradientStop(); liveInsp(); return;
      case 'grad-del': A.removeGradientStop(); liveInsp(); return;
      case 'ph-set': A.setSelectedPlaceholder(btn.dataset.v); liveInsp(); return;

      case 'pop-pick': {
        const kind = btn.dataset.v;
        if (kind === 'widget') { A.addWidget(S.pop.list); A.view.closePop(true); liveInsp(); return; }
        if (kind === 'newline') { A.addNewlineAfter(S.pop.list); A.view.closePop(true); A.view.renderWork(); return; }
        S.pop.view = kind;
        A.view.renderPop();
        return;
      }
      case 'pop-back': S.pop.view = 'menu'; A.view.renderPop(); return;
      case 'pop-close': A.view.closePop(true); return;
      case 'pop-submit': submitPop(); return;
      case 'pop-add-color':
        if (S.pop.editRef) A.setSelectedColor(btn.dataset.v); else A.addColor(S.pop.list, btn.dataset.v);
        A.view.closePop(true); liveInsp(); return;
      case 'pop-add-ph':
        if (S.pop.editRef) A.setSelectedPlaceholder(btn.dataset.v); else A.addPlaceholder(S.pop.list, btn.dataset.v);
        A.view.closePop(true); liveInsp(); return;

      case 'ctx-edit': case 'ctx-add': case 'ctx-dup': case 'ctx-del': runCtx(act); return;

      case 'modal-value': A.view.closeModal(btn.dataset.v); return;
      default: return;
    }
  });

  /* --------------------------------------------------------- text inputs */

  document.addEventListener('input', (e) => {
    const el = e.target;
    const key = el.dataset.input;
    if (key) {
      switch (key) {
        case 'text': {
          A.setSelectedText(el.value);
          const cnt = $('t-count'); if (cnt) cnt.textContent = el.value.length + ' characters';
          A.view.updateMeter();
          A.view.renderLive();
          return;
        }
        case 'hex-text': return; // committed via the Apply button
        case 'hex-picker': A.setSelectedHex(el.value); liveInsp(); return;
        case 'grad-stop': {
          const c = A.selected();
          const stops = (A.gradientStops(c && c.raw) || []).slice();
          stops[parseInt(el.dataset.idx, 10)] = el.value;
          A.setGradientStops(stops);
          liveInsp();
          return;
        }
        case 'ph-custom': A.setSelectedPlaceholder(el.value); A.view.renderLive(); return;
        case 'limit': { const c = A.selected(); if (c && c.kind === 'limit') A.setSelectedLimit(parseInt(el.value, 10) || 0); A.view.renderLive(); return; }
        case 'w-limit': A.setWidgetLimit(parseInt(el.value, 10) || 0); A.view.updateMeter(); A.view.renderLive(); return;
        case 'q-group': A.setQuery('group', el.value); A.view.renderRailLists(); return;
        case 'q-player': A.setQuery('player', el.value); A.view.renderRailLists(); return;
        default: return;
      }
    }
    if (el.dataset.popInput) A.view.renderPop && null; // pop inputs need no live handling besides submit
  });

  document.addEventListener('change', async (e) => {
    const el = e.target;
    const key = el.dataset.input;
    if (!key) return;
    if (key === 'preview') { await A.setPreviewPlayer(el.value); return; }
    if (key === 'w-text') { A.setWidgetFlag('text', el.checked); liveInsp(); return; }
    if (key === 'w-colors') { A.setWidgetFlag('colors', el.checked); liveInsp(); return; }
    if (key === 'w-placeholders') { A.setWidgetFlag('placeholders', el.checked); liveInsp(); return; }
    if (key === 'w-limited') { A.setWidgetLimited(el.checked); liveInsp(); return; }
  });

  /* -------------------------------------------------------------- enter */

  document.addEventListener('keydown', (e) => {
    const tag = (e.target.tagName || '').toLowerCase();
    const typing = tag === 'input' || tag === 'textarea';

    // Rail tabs: arrows / Home / End move focus between them; Enter or Space opens one.
    if (tag === 'button' && e.target.classList.contains('rail-tab') && ['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(e.key)) {
      const tabs = Array.from(document.querySelectorAll('.rail-tab'));
      const at = tabs.indexOf(e.target);
      const to = e.key === 'Home' ? 0 : e.key === 'End' ? tabs.length - 1 : (at + (e.key === 'ArrowRight' ? 1 : -1) + tabs.length) % tabs.length;
      e.preventDefault();
      tabs[to].focus();
      return;
    }

    // Right-click menu: arrows / Home / End move through it, Escape or Tab close it,
    // and any other key (Delete, Alt+arrows, Ctrl+Z ...) closes it and carries on as usual.
    if (S.menu) {
      const items = Array.from(document.querySelectorAll('#ctx .ctx-item:not(:disabled)'));
      const at = items.indexOf(document.activeElement);
      if (e.key === 'ArrowDown' || e.key === 'ArrowUp' || e.key === 'Home' || e.key === 'End') {
        e.preventDefault();
        if (!items.length) return;
        const to = e.key === 'Home' ? 0 : e.key === 'End' ? items.length - 1 : (at + (e.key === 'ArrowDown' ? 1 : -1) + items.length) % items.length;
        items[to].focus();
        return;
      }
      if (e.key === 'Escape' || e.key === 'Tab') { e.preventDefault(); A.view.closeCtx(true); return; }
      if (e.key === 'Enter' || e.key === ' ') return; // activates the focused item
      if (!['Shift', 'Control', 'Alt', 'Meta'].includes(e.key)) A.view.closeCtx(false);
    }

    if (typing && e.key === 'Enter' && e.target.id === 'pop-text') {
      e.preventDefault(); submitPop(); return;
    }
    const mod = e.ctrlKey || e.metaKey;
    if (mod && !e.shiftKey && e.key.toLowerCase() === 'z') { e.preventDefault(); A.undo(); return; }
    if (mod && e.shiftKey && e.key.toLowerCase() === 'z') { e.preventDefault(); A.redo(); return; }
    if (mod && e.key.toLowerCase() === 's') { e.preventDefault(); A.save(); return; }
    if (typing) return;
    if (e.key === 'Escape') { if (S.pop) A.view.closePop(true); else if (S.sel) A.deselect(); return; }
    if (!S.sel) return;
    if (e.altKey && e.key === 'ArrowLeft') { A.nudge(S.sel, -1); liveInsp(); return; }
    if (e.altKey && e.key === 'ArrowRight') { A.nudge(S.sel, 1); liveInsp(); return; }
    if ((e.key === 'Delete' || e.key === 'Backspace') && A.canModify(S.sel)) { e.preventDefault(); A.removeChunk(S.sel); liveInsp(); return; }
  });

  document.addEventListener('mousedown', (e) => {
    if (S.menu && !e.target.closest('#ctx')) A.view.closeCtx(false);
    if (!S.pop) return;
    const pop = $('pop');
    if (pop && !pop.contains(e.target) && !e.target.closest('[data-act="add"]')) A.view.closePop(false);
  });

  /* --------------------------------------------------------- drag & drop */

  let dragging = null;

  document.addEventListener('dragstart', (e) => {
    const el = e.target.closest('[draggable="true"]');
    if (!el) return;
    dragging = D.parseKey(el.dataset.ref);
    el.classList.add('is-dragging');
    if (e.dataTransfer) { e.dataTransfer.effectAllowed = 'move'; e.dataTransfer.setData('text/plain', el.dataset.ref); }
  });

  document.addEventListener('dragend', () => {
    dragging = null;
    document.querySelectorAll('.is-dragging').forEach((el) => el.classList.remove('is-dragging'));
    document.querySelectorAll('.drop-before,.drop-after,.drop-end').forEach((el) => el.classList.remove('drop-before', 'drop-after', 'drop-end'));
  });

  document.addEventListener('dragover', (e) => {
    if (!dragging) return;
    const listEl = e.target.closest('[data-list]');
    if (!listEl) return;
    e.preventDefault();
    document.querySelectorAll('.drop-before,.drop-after,.drop-end').forEach((el) => el.classList.remove('drop-before', 'drop-after', 'drop-end'));
    const chipEl = e.target.closest('.chip:not(.add), .widget');
    if (chipEl && listEl.contains(chipEl) && chipEl.dataset.ref) {
      const r = chipEl.getBoundingClientRect();
      const before = (e.clientX - r.left) < r.width / 2;
      chipEl.classList.add(before ? 'drop-before' : 'drop-after');
    } else {
      listEl.classList.add('drop-end');
    }
  });

  document.addEventListener('drop', (e) => {
    if (!dragging) return;
    const listEl = e.target.closest('[data-list]');
    if (!listEl) { dragging = null; return; }
    e.preventDefault();
    const toLr = D.parseListKey(listEl.dataset.list);
    let toIndex;
    const chipEl = e.target.closest('.chip:not(.add), .widget');
    if (chipEl && listEl.contains(chipEl) && chipEl.dataset.ref) {
      const ref2 = D.parseKey(chipEl.dataset.ref);
      const r = chipEl.getBoundingClientRect();
      const before = (e.clientX - r.left) < r.width / 2;
      toIndex = ref2.i + (before ? 0 : 1);
    } else {
      const list = D.listOf(S.lines, toLr);
      toIndex = list ? list.length : 0;
    }
    A.moveChunk(dragging, toLr, toIndex);
    dragging = null;
    A.view.renderWork();
    A.view.renderInspector();
  });

  window.addEventListener('resize', () => {
    if (S.menu) A.view.closeCtx(false);
    if (S.pop) A.view.renderPop();
    // The rail is only ever an overlay below 1180px; if it was left open and
    // the window grows past that, close it so its state doesn't go stale.
    if (S.railOpen && window.matchMedia('(min-width: 1180px)').matches) A.closeRail();
  });
  window.addEventListener('scroll', () => { if (S.menu) A.view.closeCtx(false); }, true);
  window.addEventListener('blur', () => { if (S.menu) A.view.closeCtx(false); });
})((globalThis.NT = globalThis.NT || {}));
