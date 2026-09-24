/* ==========================================================================
 * 06-icons.js — NT.icon(name, size) -> inline <svg class="i">.
 * A small hand-rolled, Feather-style outline set (stroke only, no fill —
 * style.css draws them with `stroke: currentColor`).
 * ========================================================================== */
(function (NT) {
  'use strict';

  const PATHS = {
    'plus': '<path d="M12 5v14M5 12h14"/>',
    'minus': '<path d="M5 12h14"/>',
    'x': '<path d="M18 6 6 18M6 6l12 12"/>',
    'check': '<path d="M4 12.5 9.5 18 20 6.5"/>',
    'menu': '<path d="M4 6h16M4 12h16M4 18h16"/>',
    'search': '<circle cx="11" cy="11" r="7"/><path d="m21 21-4.3-4.3"/>',
    'save': '<path d="M5 4h11l3 3v13H5z"/><path d="M8 4v6h8V4M8 14h8v6"/>',
    'undo': '<path d="M4 10h9a6 6 0 1 1-5.2 9"/><path d="M4 4v6h6"/>',
    'redo': '<path d="M20 10h-9a6 6 0 1 0 5.2 9"/><path d="M20 4v6h-6"/>',
    'copy': '<rect x="8" y="8" width="12" height="12" rx="2"/><path d="M5 15V5a1 1 0 0 1 1-1h10"/>',
    'trash': '<path d="M5 7h14M9 7V5h6v2M7 7l1 13h8l1-13"/>',
    'refresh': '<path d="M20 11a8 8 0 1 0-2.2 5.6"/><path d="M20 5v6h-6"/>',
    'globe': '<circle cx="12" cy="12" r="8.5"/><path d="M3.5 12h17M12 3.5c2.3 2.3 3.5 5.3 3.5 8.5s-1.2 6.2-3.5 8.5c-2.3-2.3-3.5-5.3-3.5-8.5S9.7 5.8 12 3.5Z"/>',
    'lock': '<rect x="5" y="11" width="14" height="9" rx="2"/><path d="M8 11V8a4 4 0 1 1 8 0v3"/>',
    'user': '<circle cx="12" cy="8.5" r="3.5"/><path d="M5 20c1-4 4-6 7-6s6 2 7 6"/>',
    'layers': '<path d="m12 4 8 4.5-8 4.5-8-4.5Z"/><path d="m4 13.5 8 4.5 8-4.5"/>',
    'alert': '<path d="M12 4 3 20h18Z"/><path d="M12 10.5v4M12 17.5v.1"/>',
    'flask': '<path d="M10 3h4M10 3v6.2L5.3 18a2 2 0 0 0 1.8 3h9.8a2 2 0 0 0 1.8-3L14 9.2V3"/><path d="M8 15h8"/>',
    'chevron-down': '<path d="m6 9 6 6 6-6"/>',
    'chevron-left': '<path d="m15 18-6-6 6-6"/>',
    'code': '<path d="m9 8-5 4 5 4M15 8l5 4-5 4"/>',
    'sliders': '<path d="M5 5v6M5 15v4M12 5v3M12 12v7M19 5v11M19 20v-.1"/><circle cx="5" cy="13" r="2"/><circle cx="12" cy="9.5" r="2"/><circle cx="19" cy="17.5" r="2"/>',
    'type': '<path d="M6 6h12M12 6v13M9.5 19h5"/>',
    'palette': '<path d="M12 3.5a8.5 8.5 0 1 0 0 17c1 0 1.7-.8 1.7-1.7 0-.5-.2-.9-.4-1.2-.3-.4-.4-.7-.4-1.1 0-.9.7-1.5 1.5-1.5H16a4.5 4.5 0 0 0 4.5-4.5c0-4-3.8-7-8.5-7Z"/><circle cx="7.7" cy="10.3" r="1.1"/><circle cx="10.4" cy="7" r="1.1"/><circle cx="14.6" cy="7.3" r="1.1"/><circle cx="16.9" cy="10.8" r="1.1"/>',
    'percent': '<path d="M18 6 6 18"/><circle cx="7.5" cy="7.5" r="2"/><circle cx="16.5" cy="16.5" r="2"/>',
    'slot': '<rect x="4" y="6" width="16" height="12" rx="2.5"/><path d="M4 12h16"/>',
    'ruler': '<rect x="3.5" y="7" width="17" height="10" rx="1.5"/><path d="M7 7v3M11 7v4M15 7v3M19 7v4"/>',
    'corner-down-left': '<path d="M9 10 4 15l5 5"/><path d="M20 4v7a4 4 0 0 1-4 4H4"/>',
    'eye-off': '<path d="M3 3l18 18"/><path d="M10.6 5.2A10.4 10.4 0 0 1 12 5c5 0 9 4 10 7-.4 1.1-1.3 2.6-2.6 3.9M6.6 6.6C4.3 8 2.9 10 2 12c1 3 5 7 10 7 1.5 0 2.9-.3 4.1-.9"/><path d="M9.5 10a3 3 0 0 0 4.2 4.3"/>',
    'arrow-up': '<path d="M12 19V5M6 11l6-6 6 6"/>',
    'arrow-down': '<path d="M12 5v14M18 13l-6 6-6-6"/>',
    'arrow-left': '<path d="M19 12H5M11 6l-6 6 6 6"/>',
    'arrow-right': '<path d="M5 12h14M13 6l6 6-6 6"/>',
    'edit': '<path d="M12 20h9"/><path d="M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4Z"/>',
    'crouch': '<circle cx="12" cy="5" r="2"/><path d="M9 21v-5l-2.5-2L8 9h8l1.5 5-2.5 2v5"/>',
  };

  function icon(name, size) {
    const body = PATHS[name] || PATHS['sliders'];
    const cls = 'i' + (size ? ' ' + size : '');
    return '<svg class="' + cls + '" viewBox="0 0 24 24" aria-hidden="true">' + body + '</svg>';
  }

  NT.icon = icon;
})((globalThis.NT = globalThis.NT || {}));
