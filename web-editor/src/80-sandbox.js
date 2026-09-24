/* ==========================================================================
 * 80-sandbox.js — the "test sandbox" drawer. Only meaningful against the
 * MockBridge (bridge.isMock): lets whoever is testing flip roles, add or
 * remove placeholders, and reset the made-up data, all without a server.
 * Self-contained: uses its own `data-drawer-act` attribute so it never
 * collides with 70-events.js's `data-act` dispatch.
 * ========================================================================== */
(function (NT) {
  'use strict';
  const A = NT.app;
  const S = A.S;
  const I = NT.icon;
  const P = NT.preview;
  const esc = P.esc;
  const $ = (id) => document.getElementById(id);

  const state = { open: false, tab: 'role' };

  function tabBtn(id, label) {
    return '<button type="button" class="tab" data-drawer-act="tab" data-tab="' + id + '" aria-selected="' + (state.tab === id) + '">' + label + '</button>';
  }

  function roleTab() {
    const role = S.data.session.role;
    return '<div><h3 class="group-title">Signed in as</h3>' +
      '<div class="switches">' +
      '<button type="button" class="btn' + (role === 'admin' ? ' primary' : '') + '" data-drawer-act="role" data-role="admin" style="justify-content:flex-start">' + I('lock', 'sm') + 'Administrator — sees every group and player</button>' +
      '<button type="button" class="btn' + (role === 'player' ? ' primary' : '') + '" data-drawer-act="role" data-role="player" style="justify-content:flex-start">' + I('user', 'sm') + 'Player — edits only their own nametag</button>' +
      '</div><p class="help">Switching role reloads the editor, the way logging in as someone else would.</p></div>';
  }

  function placeholdersTab() {
    const rows = (S.data.placeholders || []).map((p) => '<tr><td><code>%' + esc(p.key) + '%</code></td><td>' + esc(p.title || '') + '</td>' +
      '<td><button type="button" class="btn sm icon ghost danger" data-drawer-act="del-ph" data-key="' + esc(p.key) + '" aria-label="Remove">' + I('trash', 'sm') + '</button></td></tr>').join('');
    return '<div><h3 class="group-title">Placeholders (editor-placeholders.yml)</h3>' +
      '<table class="tbl"><thead><tr><th>Key</th><th>Title</th><th></th></tr></thead><tbody>' + (rows || '<tr><td colspan="3" class="empty-note">None yet.</td></tr>') + '</tbody></table>' +
      '<div class="row" style="margin-top:10px">' +
      '<input class="field mono" id="drawer-ph-key" placeholder="some_placeholder_key" spellcheck="false" autocomplete="off">' +
      '<input class="field" id="drawer-ph-title" placeholder="Display title (optional)" autocomplete="off">' +
      '<button type="button" class="btn sm primary" data-drawer-act="add-ph">' + I('plus', 'sm') + 'Add</button></div>' +
      '<p class="help">Adds a fake, always-blank value for every test player too, so it previews right away.</p></div>';
  }

  function dataTab() {
    return '<div><h3 class="group-title">Made-up data</h3><p class="help">Every player, group and format here lives only in this browser tab.</p>' +
      '<button type="button" class="btn danger" data-drawer-act="reset">' + I('refresh') + 'Reset all sandbox data</button></div>';
  }

  function render() {
    const host = $('layer-drawer');
    if (!host) return;
    if (!state.open) { host.innerHTML = ''; return; }
    const body = state.tab === 'role' ? roleTab() : state.tab === 'placeholders' ? placeholdersTab() : dataTab();
    host.innerHTML = '<div class="scrim" data-drawer-act="close"></div>' +
      '<div class="drawer" role="dialog" aria-modal="true" aria-label="Test sandbox">' +
      '<div class="drawer-head">' + I('flask') + '<h2>Test sandbox</h2><button type="button" class="btn sm icon ghost" data-drawer-act="close" aria-label="Close">' + I('x') + '</button></div>' +
      '<div class="tabs" role="tablist">' + tabBtn('role', 'Role') + tabBtn('placeholders', 'Placeholders') + tabBtn('data', 'Data') + '</div>' +
      '<div class="drawer-body">' + body + '</div></div>';
  }

  async function open() { state.open = true; state.tab = 'role'; render(); }
  function close() { state.open = false; render(); }

  document.addEventListener('click', async (e) => {
    const btn = e.target.closest('[data-drawer-act]');
    if (!btn) return;
    const act = btn.dataset.drawerAct;
    if (act === 'close') { close(); return; }
    if (act === 'tab') { state.tab = btn.dataset.tab; render(); return; }
    if (act === 'role') {
      const role = btn.dataset.role;
      if (role !== S.data.session.role) await A.switchRole(role);
      close();
      return;
    }
    if (act === 'add-ph') {
      const key = ($('drawer-ph-key') || {}).value || '';
      const title = ($('drawer-ph-title') || {}).value || '';
      const clean = key.trim().replace(/[^a-z0-9_]/gi, '_');
      if (!clean) return;
      const ok = S.bridge.addPlaceholder(clean, title.trim());
      if (ok) { render(); A.view.renderAll(); }
      else A.view.toast('That placeholder already exists.', { kind: 'warn' });
      return;
    }
    if (act === 'del-ph') {
      S.bridge.removePlaceholder(btn.dataset.key);
      render(); A.view.renderAll();
      return;
    }
    if (act === 'reset') {
      S.bridge.reset();
      close();
      await A.boot(S.bridge);
      A.view.toast('Sandbox data reset.', { kind: 'ok' });
      return;
    }
  });

  NT.sandbox = { open, close };
})((globalThis.NT = globalThis.NT || {}));
