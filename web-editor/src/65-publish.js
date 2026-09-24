/* ==========================================================================
 * 65-publish.js — the web-editor-specific UI on top of a RelayBridge:
 *   - the green "live session" banner (relaybar), mirroring how 60-view.js's
 *     V.renderMock() drives #mockbar for MockBridge
 *   - a pending-changes badge + "Publish" button in the top bar
 *   - the Publish modal itself: the list of changes, the generated
 *     `/nametags format ...` commands, and a Copy All button
 *
 * Deliberately kept in its own file, wrapping (rather than editing) 60-view.js's
 * V.renderTop/V.renderAll, so a MockBridge session behaves exactly as before
 * and this whole feature is additive.
 * ========================================================================== */
(function (NT) {
  'use strict';
  const A = NT.app;
  const S = A.S;
  const V = A.view;

  const esc = (s) => String(s == null ? '' : s).replace(/[&<>"']/g, (c) => (
    { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]
  ));

  function isRelay() {
    return !!(S.bridge && S.bridge.isRelay);
  }

  /* --------------------------------------------------------------- banner */

  function renderRelaybar() {
    const el = document.getElementById('relaybar');
    if (!el) return;
    if (!isRelay()) { el.hidden = true; return; }
    el.hidden = false;
    const meta = S.bridge.meta || {};
    const server = meta.serverName ? esc(meta.serverName) : 'your server';
    el.innerHTML =
      '<span class="dot-live" aria-hidden="true"></span>' +
      '<b>Live session</b><span>Editing a snapshot of ' + server + '. Nothing changes until you Publish and run the commands.</span>' +
      '<span class="spacer"></span>';
  }

  /* -------------------------------------------------------- top bar hook */

  const originalRenderTop = V.renderTop;
  V.renderTop = function () {
    originalRenderTop();
    if (!isRelay()) return;
    const actions = document.querySelector('#topbar .actions');
    if (!actions) return;
    const changes = S.bridge.getPendingChanges();
    const badge = document.createElement('span');
    badge.className = 'pending-badge';
    badge.innerHTML = changes.length
      ? 'Pending changes <span class="n">' + changes.length + '</span>'
      : 'No pending changes';
    const publishBtn = document.createElement('button');
    publishBtn.type = 'button';
    publishBtn.className = 'btn primary';
    publishBtn.setAttribute('data-act', 'publish');
    if (!changes.length) publishBtn.disabled = true;
    publishBtn.innerHTML = '<span class="lbl-txt">Publish</span>';
    actions.insertBefore(badge, actions.firstChild);
    actions.insertBefore(publishBtn, actions.firstChild);
  };

  const originalRenderAll = V.renderAll;
  V.renderAll = function () {
    originalRenderAll();
    renderRelaybar();
  };

  /* -------------------------------------------------------------- modal */

  A.openPublish = () => {
    if (!isRelay()) return;
    const host = document.getElementById('layer-modal');
    if (!host) return;
    const changes = S.bridge.getPendingChanges();
    const commands = S.bridge.generateCommands('nametags');

    const listHtml = changes.length
      ? '<ul class="publish-list">' + changes.map((c, i) => (
          '<li><span class="kind">' + esc(c.kind) + '</span>' +
          '<span class="what">' + esc(c.label) + (c.platform === 'bedrock' ? ' (Bedrock)' : '') + '</span>' +
          '<span class="action">' + esc(actionLabel(c.action)) + '</span></li>'
        )).join('') + '</ul>'
      : '<p class="publish-empty">No changes to publish yet — edit a format first.</p>';

    const commandsHtml = commands.length
      ? '<div class="publish-commands"><textarea id="publish-textarea" readonly rows="' + Math.min(10, commands.length + 1) + '">'
        + esc(commands.join('\n')) + '</textarea></div>' +
        '<p class="publish-note">Paste these into the server console (or run them as an op in-game), in order.</p>'
      : '';

    host.innerHTML =
      '<div class="scrim" data-act="publish-close"></div>' +
      '<div class="modal publish" role="dialog" aria-modal="true" aria-labelledby="publish-title">' +
      '<h2 id="publish-title">Publish changes</h2>' +
      listHtml + commandsHtml +
      '<div class="btns">' +
      (commands.length ? '<button type="button" class="btn" data-act="publish-copy">Copy all</button>' : '') +
      '<button type="button" class="btn primary" data-act="publish-close">Done</button>' +
      '</div></div>';
  };

  V.closePublish = () => {
    const host = document.getElementById('layer-modal');
    if (host) host.innerHTML = '';
  };

  A.copyPublishCommands = async (btn) => {
    const textarea = document.getElementById('publish-textarea');
    if (!textarea) return;
    const text = textarea.value;
    let copied = false;
    try {
      if (navigator.clipboard && navigator.clipboard.writeText) {
        await navigator.clipboard.writeText(text);
        copied = true;
      }
    } catch (e) { /* fall through to the legacy path below */ }
    if (!copied) {
      try {
        textarea.removeAttribute('readonly');
        textarea.focus();
        textarea.select();
        copied = document.execCommand('copy');
        textarea.setAttribute('readonly', 'readonly');
      } catch (e) { copied = false; }
    }
    V.toast(copied ? 'Commands copied.' : 'Could not copy automatically — select the text and copy it manually.',
      { kind: copied ? 'ok' : 'warn' });
  };

  function actionLabel(action) {
    switch (action) {
      case 'set': return 'set';
      case 'add': return 'new';
      case 'edit': return 'changed';
      case 'remove': return 'removed';
      case 'disable': return 'cleared';
      default: return action;
    }
  }
})((globalThis.NT = globalThis.NT || {}));
