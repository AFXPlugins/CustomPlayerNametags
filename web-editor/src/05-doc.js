/* ==========================================================================
 * 05-doc.js — tiny helpers for addressing a chunk inside `S.lines`.
 *
 * A "ref" is { l, w, i }: line index, widget index within that line (or null
 * for a top-level chunk), and the chunk's index within whichever list it
 * lives in. A "list ref" (lr) is just { l, w } — the list itself, no index.
 * ========================================================================== */
(function (NT) {
  'use strict';

  const key = (ref) => ref.l + ':' + (ref.w == null ? '-' : ref.w) + ':' + ref.i;
  const listKey = (lr) => lr.l + ':' + (lr.w == null ? '-' : lr.w);
  const sameRef = (a, b) => !!a && !!b && a.l === b.l && a.w === b.w && a.i === b.i;

  function parseKey(str) {
    const [l, w, i] = String(str).split(':');
    return { l: parseInt(l, 10), w: w === '-' ? null : parseInt(w, 10), i: parseInt(i, 10) };
  }
  function parseListKey(str) {
    const [l, w] = String(str).split(':');
    return { l: parseInt(l, 10), w: w === '-' ? null : parseInt(w, 10) };
  }

  /** The array a ref/list-ref points into. */
  function listOf(lines, lr) {
    const line = lines[lr.l];
    if (!line) return null;
    if (lr.w == null) return line;
    const w = line[lr.w];
    return w && w.kind === 'widget' ? w.contents : null;
  }

  function lineHasWidget(line) {
    return line.some((c) => c.kind === 'widget');
  }

  NT.doc = { key, listKey, sameRef, listOf, lineHasWidget, parseKey, parseListKey };
})((globalThis.NT = globalThis.NT || {}));
