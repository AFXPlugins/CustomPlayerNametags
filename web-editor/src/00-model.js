/* ==========================================================================
 * 00-model.js — the raw nametag format string <-> structured chunk model.
 *
 * A format is stored as ONE string, using the literal two-character sequence
 * `\n` (backslash + n) to mark line breaks (mirrors how the plugin stores it
 * in YAML). Each line is a flat array of "chunks":
 *
 *   { kind: 'text',        text }
 *   { kind: 'color',       raw }                 // a legacy (&c/&#rrggbb) or
 *                                                 // MiniMessage (<red>, </red>,
 *                                                 // <gradient:...>, ...) tag
 *   { kind: 'placeholder', raw }                 // '{player}' or '%some_key%'
 *   { kind: 'limit',       limit, raw }          // '{chars N}', verbatim
 *   { kind: 'widget', widgetId, colors, placeholders, text, limit, contents }
 *
 * Widgets are atomic: line-splitting never happens inside a widget, even if
 * its filled-in content contains a real newline or a literal `\n`.
 *
 * Pure functions, no DOM — unit-tested by test/model.test.js.
 * ========================================================================== */
(function (NT) {
  'use strict';

  const PLAYER_TAG = '{player}';
  const WIDGET_OPEN_PREFIX = '{widget';

  /* --------------------------------------------------------- known tags */
  // Kept in sync with the MiniMessage subset 10-render.js understands, so the
  // model and the renderer agree on where one chunk ends and the next begins.
  const NAMED = new Set(['black', 'dark_blue', 'dark_green', 'dark_aqua', 'dark_red', 'dark_purple',
    'gold', 'gray', 'grey', 'dark_gray', 'dark_grey', 'blue', 'green', 'aqua', 'red', 'light_purple',
    'yellow', 'white']);
  const DECO = new Set(['bold', 'b', 'italic', 'i', 'em', 'underlined', 'u', 'strikethrough', 'st', 'obfuscated', 'obf']);
  const IGNORED = new Set(['click', 'hover', 'insert', 'font', 'key', 'lang', 'tr', 'translate', 'transition',
    'shadow', 'selector', 'score', 'nbt', 'head', 'sprite', 'atlas']);
  const HEX6 = /^#[0-9a-fA-F]{6}$/;
  const LEGACY_CHARS = /[0-9a-fk-orA-FK-OR]/;

  const MINI_TAG_RE = /^<(\/)?(!)?([#A-Za-z0-9_]+)(?::(?:'[^']*'|"[^"]*"|[^:<>'"]*))*\/?>/;

  function isKnownMiniTag(name) {
    if (HEX6.test(name)) return true;
    return NAMED.has(name) || DECO.has(name) || IGNORED.has(name) ||
      name === 'color' || name === 'colour' || name === 'c' || name === 'reset' ||
      name === 'gradient' || name === 'rainbow' || name === 'newline' || name === 'br';
  }

  /* -------------------------------------------------------------- widget id */

  function mintWidgetId() {
    let s = '';
    for (let i = 0; i < 8; i++) s += Math.floor(Math.random() * 16).toString(16);
    return s;
  }

  /* --------------------------------------------------------- {chars N} tag */

  function charsAt(raw, i) {
    const m = /^\{chars\s+(\d+)\}/.exec(raw.slice(i));
    if (!m) return null;
    return { limit: parseInt(m[1], 10), end: i + m[0].length, raw: m[0] };
  }

  /* ------------------------------------------------------------- attributes */

  function parseAttrs(str) {
    const attrs = { id: null, colors: false, placeholders: false, text: false, limit: -1 };
    if (!str) return attrs;
    const re = /([A-Za-z]+)=("[^"]*"|'[^']*'|[^\s]+)/g;
    let m;
    while ((m = re.exec(str))) {
      const key = m[1].toLowerCase();
      let val = m[2];
      if ((val[0] === '"' && val[val.length - 1] === '"') || (val[0] === "'" && val[val.length - 1] === "'")) {
        val = val.slice(1, -1);
      }
      if (key === 'id') attrs.id = val;
      else if (key === 'colors') attrs.colors = val === 'true';
      else if (key === 'placeholders') attrs.placeholders = val === 'true';
      else if (key === 'text') attrs.text = val === 'true';
      else if (key === 'limit') {
        const n = parseInt(val, 10);
        attrs.limit = Number.isFinite(n) ? Math.max(0, n) : -1;
      }
    }
    return attrs;
  }

  function attributeLimit(attrs) {
    return attrs && typeof attrs.limit === 'number' ? attrs.limit : -1;
  }

  /* ------------------------------------------------------------- {widget} */

  /** Finds a `{widget ...}...{/widget}` block starting exactly at `i`, or null. */
  function widgetAt(raw, i) {
    if (raw.slice(i, i + 7) !== '{widget') return null;
    const boundary = raw[i + 7];
    if (boundary !== ' ' && boundary !== '}') return null;
    const attrsEnd = raw.indexOf('}', i + 7);
    if (attrsEnd < 0) return null;
    const attrs = parseAttrs(raw.slice(i + 7, attrsEnd));
    const contentStart = attrsEnd + 1;
    let depth = 1;
    let j = contentStart;
    while (j < raw.length) {
      if (raw.slice(j, j + 7) === '{widget') {
        const b2 = raw[j + 7];
        if (b2 === ' ' || b2 === '}') { depth++; j += 7; continue; }
      }
      if (raw.slice(j, j + 9) === '{/widget}') {
        depth--;
        if (depth === 0) return { end: j + 9, attrs, contentStart, contentEnd: j };
        j += 9;
        continue;
      }
      j++;
    }
    return null; // unterminated — treat as not a widget
  }

  function encodeWidget(id, colors, placeholders, text, limit, content) {
    let s = '{widget id=' + id + ' colors=' + !!colors + ' placeholders=' + !!placeholders + ' text=' + !!text;
    if (typeof limit === 'number' && limit >= 0) s += ' limit=' + limit;
    s += '}' + (content || '') + '{/widget}';
    return s;
  }

  /* ----------------------------------------------------------- flat parse */

  /** Parses one line's raw text (no `\n`s expected) into an array of chunks. */
  function parseFlat(raw) {
    const chunks = [];
    let buf = '';
    const flush = () => { if (buf) { chunks.push({ kind: 'text', text: buf }); buf = ''; } };
    let i = 0;
    const s = raw || '';
    while (i < s.length) {
      const ch = s[i];
      if (ch === '{') {
        if (s.slice(i, i + 8) === PLAYER_TAG) {
          flush(); chunks.push({ kind: 'placeholder', raw: PLAYER_TAG }); i += 8; continue;
        }
        const w = widgetAt(s, i);
        if (w) {
          flush();
          const id = w.attrs.id || mintWidgetId();
          chunks.push({
            kind: 'widget', widgetId: id, colors: w.attrs.colors, placeholders: w.attrs.placeholders,
            text: w.attrs.text, limit: w.attrs.limit, contents: parseFlat(s.slice(w.contentStart, w.contentEnd)),
          });
          i = w.end;
          continue;
        }
        const c = charsAt(s, i);
        if (c) { flush(); chunks.push({ kind: 'limit', limit: c.limit, raw: c.raw }); i = c.end; continue; }
        buf += ch; i++; continue;
      }
      if (ch === '%') {
        const m = /^%([^%\n]+)%/.exec(s.slice(i));
        if (m) { flush(); chunks.push({ kind: 'placeholder', raw: m[0] }); i += m[0].length; continue; }
        buf += ch; i++; continue;
      }
      if (ch === '&') {
        let m = /^&#[0-9a-fA-F]{6}/.exec(s.slice(i));
        if (m) { flush(); chunks.push({ kind: 'color', raw: m[0] }); i += m[0].length; continue; }
        if (LEGACY_CHARS.test(s[i + 1] || '')) {
          flush(); chunks.push({ kind: 'color', raw: s.slice(i, i + 2) }); i += 2; continue;
        }
        buf += ch; i++; continue;
      }
      if (ch === '<') {
        const m = MINI_TAG_RE.exec(s.slice(i));
        if (m) {
          const name = m[3].toLowerCase();
          if (isKnownMiniTag(name)) { flush(); chunks.push({ kind: 'color', raw: m[0] }); i += m[0].length; continue; }
        }
        buf += ch; i++; continue;
      }
      buf += ch; i++;
    }
    flush();
    return chunks;
  }

  function serializeChunk(c) {
    switch (c.kind) {
      case 'text': return c.text;
      case 'color': return c.raw;
      case 'placeholder': return c.raw;
      case 'limit': return c.raw != null ? c.raw : '{chars ' + c.limit + '}';
      case 'widget': return encodeWidget(c.widgetId, c.colors, c.placeholders, c.text, c.limit, c.contents.map(serializeChunk).join(''));
      default: return '';
    }
  }

  /* --------------------------------------------------------- lines <-> raw */

  /** Splits raw into per-line raw substrings, treating widgets as atomic. */
  function splitRawLines(raw) {
    const s = raw || '';
    const segments = [];
    let buf = '';
    let i = 0;
    while (i < s.length) {
      const ch = s[i];
      if (ch === '{') {
        if (s.slice(i, i + 8) === PLAYER_TAG) { buf += PLAYER_TAG; i += 8; continue; }
        const w = widgetAt(s, i);
        if (w) { buf += s.slice(i, w.end); i = w.end; continue; }
        const c = charsAt(s, i);
        if (c) { buf += c.raw; i = c.end; continue; }
        buf += ch; i++; continue;
      }
      if (ch === '\n') { segments.push(buf); buf = ''; i++; continue; }
      if (ch === '\\' && s[i + 1] === 'n') { segments.push(buf); buf = ''; i += 2; continue; }
      buf += ch; i++;
    }
    segments.push(buf);
    return segments;
  }

  function parseLines(raw) {
    return splitRawLines(raw).map(parseFlat);
  }

  function serializeLines(lines) {
    return lines.map((line) => line.map(serializeChunk).join('')).join('\\n');
  }

  /* --------------------------------------------------------- typed cleanup */

  /** Strips `{widget}...{/widget}` and `{chars N}` spans entirely (used to
   *  measure/display "plain" length of admin-typed text that may still
   *  contain raw tags). Placeholders and colour tags are left alone. */
  function stripTagText(raw) {
    const s = raw || '';
    let out = '';
    let i = 0;
    while (i < s.length) {
      if (s[i] === '{') {
        const w = widgetAt(s, i);
        if (w) { i = w.end; continue; }
        const c = charsAt(s, i);
        if (c) { i = c.end; continue; }
      }
      out += s[i]; i++;
    }
    return out;
  }

  /** { widgetId: innerRawText } for every widget currently in `raw`. */
  function extractWidgetContents(raw) {
    const s = raw || '';
    const out = {};
    let i = 0;
    while (i < s.length) {
      if (s[i] === '{') {
        const w = widgetAt(s, i);
        if (w) {
          const id = w.attrs.id || mintWidgetId();
          out[id] = s.slice(w.contentStart, w.contentEnd);
          i = w.end;
          continue;
        }
      }
      i++;
    }
    return out;
  }

  /** Rewrites the widgets in `raw` whose id is a key in `fills`, keeping every
   *  other character (including untouched widgets) exactly as-is. */
  function overlayWidgetFills(raw, fills) {
    const s = raw || '';
    if (!fills) return s;
    let out = '';
    let i = 0;
    while (i < s.length) {
      if (s[i] === '{') {
        const w = widgetAt(s, i);
        if (w) {
          const id = w.attrs.id;
          if (id && Object.prototype.hasOwnProperty.call(fills, id)) {
            out += encodeWidget(id, w.attrs.colors, w.attrs.placeholders, w.attrs.text, w.attrs.limit, fills[id]);
          } else {
            out += s.slice(i, w.end);
          }
          i = w.end;
          continue;
        }
      }
      out += s[i]; i++;
    }
    return out;
  }

  /* ----------------------------------------------------- widget-text rules */

  /** Sanitises text a player typed into a widget slot: strips whatever the
   *  slot disallows, then cuts to its character limit (colour tags are free). */
  function sanitizeWidgetText(text, w, opts) {
    const o = opts || {};
    let chunks = parseFlat(text || '');
    chunks = chunks.filter((c) => {
      if (c.kind === 'limit') return false;
      if (c.kind === 'color') return !!w.colors;
      if (c.kind === 'placeholder') return !!w.placeholders;
      return !!w.text;
    });
    const limit = typeof w.limit === 'number' ? w.limit : -1;
    if (limit >= 0) {
      const out = [];
      let seen = 0;
      let truncated = false;
      for (const c of chunks) {
        if (c.kind === 'color') { out.push(c); continue; }
        const len = c.kind === 'text' ? c.text.length : (c.raw ? c.raw.length : 0);
        if (seen >= limit) { truncated = true; continue; }
        if (seen + len <= limit) { out.push(c); seen += len; continue; }
        truncated = true;
        if (c.kind === 'text') out.push({ kind: 'text', text: c.text.slice(0, limit - seen) });
        seen = limit;
      }
      chunks = out;
      if (truncated && o.ellipsis) chunks.push({ kind: 'color', raw: '<white>...</white>' });
    }
    return chunks.map(serializeChunk).join('');
  }

  NT.model = {
    PLAYER_TAG, WIDGET_OPEN_PREFIX,
    parseFlat, parseLines, serializeChunk, serializeLines,
    widgetAt, charsAt, parseAttrs, attributeLimit, encodeWidget,
    stripTagText, extractWidgetContents, overlayWidgetFills, sanitizeWidgetText,
    mintWidgetId,
  };
})((globalThis.NT = globalThis.NT || {}));
