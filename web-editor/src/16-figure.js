/* ==========================================================================
 * 16-figure.js — a tiny blocky ("Minecraft-ish") player figure, built from
 * plain SVG rects and coloured from a `look` profile:
 *   { skin, hair, shirt, pants, eyes }
 * No textures, no images — just enough shape to read as a person on the
 * preview stage. `avatar()` is the full body (front-facing); `face()` is a
 * cropped head used in small UI spots (the rail's "who" card).
 * ========================================================================== */
(function (NT) {
  'use strict';

  const DEFAULT_LOOK = { skin: '#e0ac81', hair: '#4a3222', shirt: '#5a7dbf', pants: '#33395a', eyes: '#2a2a2a' };
  const px = (x, y, w, h, fill) => '<rect x="' + x + '" y="' + y + '" width="' + w + '" height="' + h + '" fill="' + fill + '"/>';

  function shade(hex, amt) {
    const h = hex.replace('#', '');
    const n = parseInt(h.length === 3 ? h.split('').map((c) => c + c).join('') : h, 16);
    const r = Math.max(0, Math.min(255, (n >> 16) + amt));
    const g = Math.max(0, Math.min(255, ((n >> 8) & 255) + amt));
    const b = Math.max(0, Math.min(255, (n & 255) + amt));
    return '#' + ((1 << 24) + (r << 16) + (g << 8) + b).toString(16).slice(1);
  }

  /** Full-body front-facing figure, 16x32 grid. */
  function avatar(look) {
    const l = Object.assign({}, DEFAULT_LOOK, look || {});
    const skinD = shade(l.skin, -28);
    const shirtD = shade(l.shirt, -30);
    const pantsD = shade(l.pants, -26);
    let s = '';
    // hair (back + top)
    s += px(2, 0, 12, 3, l.hair);
    s += px(2, 3, 2, 3, l.hair) + px(12, 3, 2, 3, l.hair);
    // head
    s += px(4, 3, 8, 6, l.skin);
    s += px(4, 7, 8, 2, skinD);
    // eyes
    s += px(6, 5, 1.4, 1.4, l.eyes) + px(9.6, 5, 1.4, 1.4, l.eyes);
    // torso
    s += px(4, 9, 8, 9, l.shirt);
    s += px(4, 15, 8, 3, shirtD);
    // arms
    s += px(1.5, 9, 2.5, 8, l.skin);
    s += px(12, 9, 2.5, 8, l.skin);
    s += px(1.5, 9, 2.5, 2.4, l.shirt);
    s += px(12, 9, 2.5, 2.4, l.shirt);
    // legs
    s += px(4, 18, 3.8, 12, l.pants);
    s += px(8.2, 18, 3.8, 12, l.pants);
    s += px(4, 27, 3.8, 3, pantsD);
    s += px(8.2, 27, 3.8, 3, pantsD);
    return '<svg class="avatar" viewBox="0 0 16 30.5" xmlns="http://www.w3.org/2000/svg" aria-hidden="true">' + s + '</svg>';
  }

  /** Small cropped head, used at 34x34 in the rail. */
  function face(look) {
    const l = Object.assign({}, DEFAULT_LOOK, look || {});
    let s = '';
    s += px(0, 0, 12, 12, l.skin);
    s += px(0, 0, 12, 2.4, l.hair);
    s += px(0, 2.4, 1.6, 3, l.hair) + px(10.4, 2.4, 1.6, 3, l.hair);
    s += px(2.4, 5.4, 1.8, 1.8, l.eyes) + px(7.8, 5.4, 1.8, 1.8, l.eyes);
    s += px(0, 9.4, 12, 2.6, shade(l.skin, -26));
    return '<svg viewBox="0 0 12 12" width="34" height="34" xmlns="http://www.w3.org/2000/svg" aria-hidden="true">' + s + '</svg>';
  }

  NT.figure = { avatar, face, DEFAULT_LOOK };
})((globalThis.NT = globalThis.NT || {}));
