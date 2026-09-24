/* ==========================================================================
 * 10-render.js — turns a raw format string into what a player would see.
 *
 * `NT.mini`     A MiniMessage-subset + legacy `&` code parser (colors, hex,
 *               decorations, gradient, rainbow, reset) producing styled runs,
 *               plus the plugin's visible-character truncation rules.
 * `NT.preview`  A mirror of NametagManager#parseFormat and
 *               #truncateToCharacterLimit: widgets unwrap to their content,
 *               {player}/%placeholders% resolve, empty widget-only lines are
 *               dropped, then every line is cut to its character limit.
 *
 * Pure functions, no DOM — so it can be unit-tested in Node.
 * ========================================================================== */
(function (NT) {
  'use strict';
  const M = NT.model;

  /* ------------------------------------------------------------ constants */

  const NAMED = {
    black: '#000000', dark_blue: '#0000aa', dark_green: '#00aa00', dark_aqua: '#00aaaa',
    dark_red: '#aa0000', dark_purple: '#aa00aa', gold: '#ffaa00', gray: '#aaaaaa', grey: '#aaaaaa',
    dark_gray: '#555555', dark_grey: '#555555', blue: '#5555ff', green: '#55ff55', aqua: '#55ffff',
    red: '#ff5555', light_purple: '#ff55ff', yellow: '#ffff55', white: '#ffffff',
  };
  const LEGACY_COLOR = {
    '0': 'black', '1': 'dark_blue', '2': 'dark_green', '3': 'dark_aqua', '4': 'dark_red', '5': 'dark_purple',
    '6': 'gold', '7': 'gray', '8': 'dark_gray', '9': 'blue', a: 'green', b: 'aqua', c: 'red', d: 'light_purple',
    e: 'yellow', f: 'white',
  };
  const LEGACY_STYLE = { k: 'obfuscated', l: 'bold', m: 'strikethrough', n: 'underlined', o: 'italic', r: 'reset' };
  const DECO = {
    bold: 'b', b: 'b', italic: 'i', i: 'i', em: 'i', underlined: 'u', u: 'u',
    strikethrough: 'st', st: 'st', obfuscated: 'o', obf: 'o',
  };
  const DECO_CANON = { b: 'bold', i: 'italic', u: 'underlined', st: 'strikethrough', o: 'obfuscated' };
  const IGNORED = new Set(['click', 'hover', 'insert', 'font', 'key', 'lang', 'tr', 'translate', 'transition', 'shadow', 'selector', 'score', 'nbt', 'head', 'sprite', 'atlas']);
  const HEX6 = /^#[0-9a-fA-F]{6}$/;
  const WHITE = '#ffffff';
  const SNEAK_GREY = '#e1e1e1';
  const SNEAK_GREY_BEDROCK = '#bebebe';

  // Private-use marks for widgets that carry a character limit through
  // {player}/placeholder substitution (same as the plugin; never rendered).
  const MARK_OPEN = '\uE010';
  const MARK_SEP = '\uE011';
  const MARK_CLOSE = '\uE012';

  /* ------------------------------------------------------- legacy → mini */

  function fromLegacy(text) {
    if (!text) return '';
    let out = text.replace(/&#([0-9A-Fa-f]{6})/g, '<#$1>');
    out = out.replace(/&([0-9a-fk-orA-FK-OR])/g, (_, c) => {
      const k = c.toLowerCase();
      if (LEGACY_COLOR[k]) return '<' + LEGACY_COLOR[k] + '>';
      if (LEGACY_STYLE[k]) return '<' + LEGACY_STYLE[k] + '>';
      return '';
    });
    return out;
  }

  /* ------------------------------------------------------------ tokenizer */

  const TAG_RE = /<(\/)?(!)?([#A-Za-z0-9_]+)((?::(?:'[^']*'|"[^"]*"|[^:<>'"]*))*)(\/)?>/y;

  function splitArgs(s) {
    if (!s) return [];
    const args = [];
    const re = /:(?:'([^']*)'|"([^"]*)"|([^:]*))/g;
    let m;
    while ((m = re.exec(s))) args.push(m[1] !== undefined ? m[1] : m[2] !== undefined ? m[2] : m[3]);
    return args;
  }

  function isKnownTag(name) {
    if (HEX6.test(name)) return true;
    return NAMED[name] !== undefined || DECO[name] !== undefined || IGNORED.has(name) ||
      name === 'color' || name === 'colour' || name === 'c' || name === 'reset' || name === 'gradient' ||
      name === 'rainbow' || name === 'newline' || name === 'br';
  }

  function keyOf(name) {
    if (DECO[name]) return DECO_CANON[DECO[name]];
    if (name === 'colour' || name === 'c') return 'color';
    if (name === 'br') return 'newline';
    return name;
  }

  function tokenize(src) {
    const tokens = [];
    let buf = '';
    let bufRaw = '';
    const flush = () => {
      if (buf) { tokens.push({ type: 'text', text: buf, raw: bufRaw }); buf = ''; bufRaw = ''; }
    };
    let i = 0;
    while (i < src.length) {
      const ch = src[i];
      if (ch === '\\' && (src[i + 1] === '<' || src[i + 1] === '\\')) {
        buf += src[i + 1]; bufRaw += ch + src[i + 1]; i += 2; continue;
      }
      if (ch === '<') {
        TAG_RE.lastIndex = i;
        const m = TAG_RE.exec(src);
        if (m) {
          const name = m[3].toLowerCase();
          if (isKnownTag(name)) {
            flush();
            tokens.push({
              type: m[1] ? 'close' : (m[5] || keyOf(name) === 'newline') ? 'self' : 'open',
              name, key: keyOf(name), neg: !!m[2], args: splitArgs(m[4]), raw: m[0],
            });
            i += m[0].length;
            continue;
          }
        }
      }
      buf += ch; bufRaw += ch; i++;
    }
    flush();
    return tokens;
  }

  /** For every open token, finds where it ends (matching close, `<reset>`, or end). */
  function resolveSpans(tokens) {
    const stack = [];
    tokens.forEach((t, i) => {
      const isReset = t.key === 'reset' && (t.type === 'open' || t.type === 'self');
      if (isReset) {
        stack.forEach((j) => { tokens[j].closeAt = i; });
        stack.length = 0;
      } else if (t.type === 'open') {
        stack.push(i);
      } else if (t.type === 'close') {
        for (let k = stack.length - 1; k >= 0; k--) {
          if (tokens[stack[k]].key === t.key) {
            for (let j = k; j < stack.length; j++) tokens[stack[j]].closeAt = i;
            stack.length = k;
            break;
          }
        }
      }
    });
    stack.forEach((j) => { tokens[j].closeAt = tokens.length; });
  }

  /* -------------------------------------------------------------- colors */

  function parseColor(v) {
    if (!v) return null;
    const s = String(v).trim().toLowerCase();
    if (NAMED[s]) return NAMED[s];
    if (HEX6.test(s)) return s;
    return null;
  }

  const hexToRgb = (h) => [parseInt(h.slice(1, 3), 16), parseInt(h.slice(3, 5), 16), parseInt(h.slice(5, 7), 16)];
  const rgbToHex = (r, g, b) => '#' + [r, g, b].map((n) => Math.max(0, Math.min(255, Math.round(n))).toString(16).padStart(2, '0')).join('');

  function hsvToHex(h, s, v) {
    const i = Math.floor(h * 6) % 6;
    const f = h * 6 - Math.floor(h * 6);
    const p = v * (1 - s), q = v * (1 - f * s), t = v * (1 - (1 - f) * s);
    const [r, g, b] = [[v, t, p], [q, v, p], [p, v, t], [p, q, v], [t, p, v], [v, p, q]][i];
    return rgbToHex(r * 255, g * 255, b * 255);
  }

  function gradientColor(stops, t) {
    if (stops.length === 1) return stops[0];
    const pos = Math.min(0.999999, Math.max(0, t)) * (stops.length - 1);
    const i = Math.floor(pos);
    const f = pos - i;
    const a = hexToRgb(stops[i]);
    const b = hexToRgb(stops[i + 1]);
    return rgbToHex(a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f, a[2] + (b[2] - a[2]) * f);
  }

  function gradientStops(args) {
    const stops = [];
    let phase = 0;
    for (const a of args) {
      const c = parseColor(a);
      if (c) stops.push(c);
      else if (/^-?\d*\.?\d+$/.test(String(a).trim())) phase = parseFloat(a);
    }
    if (!stops.length) stops.push(WHITE, '#000000'); // MiniMessage default: white → black
    return { stops, phase };
  }

  /* --------------------------------------------------------------- parse */

  /**
   * Parses MiniMessage source into lines of runs.
   * run = { t: text, c: '#rrggbb', b, i, u, st, o }
   */
  function parse(src) {
    const tokens = tokenize(src || '');
    resolveSpans(tokens);
    const stack = [];
    const lines = [[]];
    let cur = lines[0];

    const countChars = (from, to) => {
      let n = 0;
      for (let j = from; j < to && j < tokens.length; j++) if (tokens[j].type === 'text') n += tokens[j].text.length;
      return n;
    };
    const styleKey = (r) => r.c + '|' + (r.b ? 1 : 0) + (r.i ? 1 : 0) + (r.u ? 1 : 0) + (r.st ? 1 : 0) + (r.o ? 1 : 0);

    const emit = (ch) => {
      if (ch === '\n') { cur = []; lines.push(cur); for (const e of stack) if (e.grad || e.rainbow) e.k++; return; }
      const st = { c: WHITE, b: false, i: false, u: false, st: false, o: false };
      for (const e of stack) {
        if (e.color) st.c = e.color;
        else if (e.deco) st[e.deco] = !e.neg;
        else if (e.grad) {
          const t = e.n <= 1 ? 0 : e.k / (e.n - 1);
          let tt = t + e.grad.phase;
          if (e.grad.phase !== 0) tt = ((tt % 1) + 1) % 1;
          st.c = gradientColor(e.grad.stops, tt);
        } else if (e.rainbow) {
          const h = (((e.k / Math.max(1, e.n)) + e.phase) % 1 + 1) % 1;
          st.c = hsvToHex(h, 1, 1);
        }
      }
      for (const e of stack) if (e.grad || e.rainbow) e.k++;
      st.t = ch;
      const last = cur[cur.length - 1];
      if (last && styleKey(last) === styleKey(st)) last.t += ch; else cur.push(st);
    };

    tokens.forEach((t, idx) => {
      if (t.type === 'text') { for (const ch of t.text) emit(ch); return; }
      if (t.key === 'newline' && (t.type === 'open' || t.type === 'self')) { emit('\n'); return; }
      if (t.key === 'reset') { stack.length = 0; return; }
      if (t.type === 'close') {
        for (let k = stack.length - 1; k >= 0; k--) if (stack[k].key === t.key) { stack.length = k; break; }
        return;
      }
      if (t.type === 'self') return;
      // open
      const e = { key: t.key };
      if (NAMED[t.name]) e.color = NAMED[t.name];
      else if (HEX6.test(t.name)) e.color = t.name.toLowerCase();
      else if (t.key === 'color') { e.color = parseColor(t.args[0]); if (!e.color) e.ignore = true; }
      else if (DECO[t.name]) { e.deco = DECO[t.name]; e.neg = t.neg; }
      else if (t.key === 'gradient') {
        e.grad = gradientStops(t.args); e.k = 0; e.n = countChars(idx + 1, t.closeAt);
      } else if (t.key === 'rainbow') {
        e.rainbow = true; e.k = 0; e.n = countChars(idx + 1, t.closeAt);
        const ph = t.args.find((a) => /^-?\d*\.?\d+$/.test(String(a).trim()));
        e.phase = ph !== undefined ? parseFloat(ph) : 0;
      } else e.ignore = true;
      stack.push(e);
    });
    return lines;
  }

  /* ------------------------------------------------------------- helpers */

  const runsPlain = (runs) => runs.map((r) => r.t).join('');
  const linesPlain = (lines) => lines.map(runsPlain);

  function visibleLength(src) {
    let n = 0;
    for (const t of tokenize(src)) if (t.type === 'text') n += t.text.length;
    return n;
  }

  const escapeMini = (s) => s.replace(/[\\<]/g, (c) => '\\' + c);

  /**
   * Cuts MiniMessage source to `limit` visible characters, keeping every style
   * tag opened before the cut and closing them again afterwards, then appends a
   * plain white "..." if `ellipsis`. Source already within the limit is returned as is.
   */
  function truncateSource(src, limit, ellipsis) {
    if (limit < 0 || visibleLength(src) <= limit) return src;
    let out = '';
    let seen = 0;
    const open = [];
    for (const t of tokenize(src)) {
      if (t.type === 'text') {
        if (seen >= limit) continue;
        const room = limit - seen;
        if (t.text.length <= room) { out += t.raw; seen += t.text.length; } else { out += escapeMini(t.text.slice(0, room)); seen += room; }
        continue;
      }
      if (seen >= limit) continue;
      if (t.type === 'open') { if (t.key === 'reset') open.length = 0; else open.push(t); out += t.raw; }
      else if (t.type === 'close') {
        for (let k = open.length - 1; k >= 0; k--) if (open[k].key === t.key) { open.length = k; break; }
        out += t.raw;
      } else out += t.raw;
    }
    for (let k = open.length - 1; k >= 0; k--) if (open[k].key !== 'newline') out += '</' + open[k].name + '>';
    if (ellipsis) out += '<white>...</white>';
    return out;
  }

  /** Truncates already-parsed runs to `limit` visible characters (+ white "..."). */
  function truncateRuns(runs, limit, ellipsis) {
    if (limit < 0) return { runs, truncated: false };
    const total = runsPlain(runs).length;
    if (total <= limit) return { runs, truncated: false };
    const out = [];
    let left = limit;
    for (const r of runs) {
      if (left <= 0) break;
      if (r.t.length <= left) { out.push(r); left -= r.t.length; } else { out.push(Object.assign({}, r, { t: r.t.slice(0, left) })); left = 0; }
    }
    if (ellipsis) out.push({ t: '...', c: WHITE, b: false, i: false, u: false, st: false, o: false });
    return { runs: out, truncated: true };
  }

  /* ------------------------------------------------ TemplateMarkers mirror */

  /** Widgets unwrap to their content (wrapped in marks if limited); `{chars}` tags vanish. */
  function stripForRendering(raw) {
    if (!raw || raw.indexOf('{') < 0) return raw;
    let out = '';
    let i = 0;
    while (i < raw.length) {
      if (raw[i] === '{') {
        const w = M.widgetAt(raw, i);
        if (w) {
          const limit = M.attributeLimit(w.attrs);
          const inner = raw.slice(w.contentStart, w.contentEnd);
          out += limit >= 0 ? MARK_OPEN + limit + MARK_SEP + inner + MARK_CLOSE : inner;
          i = w.end;
          continue;
        }
        const c = M.charsAt(raw, i);
        if (c) { i = c.end; continue; }
      }
      out += raw[i];
      i++;
    }
    return out;
  }

  function truncateMarkedWidgets(resolved, ellipsis) {
    if (!resolved || resolved.indexOf(MARK_OPEN) < 0) return resolved;
    let out = '';
    let i = 0;
    while (i < resolved.length) {
      if (resolved[i] === MARK_OPEN) {
        const sep = resolved.indexOf(MARK_SEP, i + 1);
        const close = sep < 0 ? -1 : resolved.indexOf(MARK_CLOSE, sep + 1);
        if (sep > i && close > sep) {
          const limit = parseInt(resolved.slice(i + 1, sep), 10);
          const inner = resolved.slice(sep + 1, close);
          const src = fromLegacy(inner);
          out += Number.isFinite(limit) && limit >= 0 && inner && visibleLength(src) > limit
            ? truncateSource(src, limit, ellipsis)
            : inner;
          i = close + 1;
          continue;
        }
        i++;
        continue;
      }
      out += resolved[i];
      i++;
    }
    return out;
  }

  /** Per raw line, the `{chars N}` override (or -1). */
  function lineCharacterOverrides(raw) {
    if (!raw) return [-1];
    const s = raw.split('\\n').join('\n');
    const res = [-1];
    let i = 0;
    while (i < s.length) {
      const ch = s[i];
      if (ch === '\n') { res.push(-1); i++; continue; }
      if (ch === '{') {
        const w = M.widgetAt(s, i);
        if (w) { i = w.end; continue; }
        const c = M.charsAt(s, i);
        if (c) { res[res.length - 1] = c.limit; i = c.end; continue; }
      }
      i++;
    }
    return res;
  }

  function widgetLineFlags(raw) {
    if (!raw) return [false];
    const s = raw.split('\\n').join('\n');
    const flags = [false];
    let i = 0;
    while (i < s.length) {
      const ch = s[i];
      if (ch === '\n') { flags.push(false); i++; continue; }
      if (ch === '{') {
        const w = M.widgetAt(s, i);
        if (w) { flags[flags.length - 1] = true; i = w.end; continue; }
        const c = M.charsAt(s, i);
        if (c) { i = c.end; continue; }
      }
      i++;
    }
    return flags;
  }

  function containsWidget(raw) {
    if (!raw || raw.indexOf('{') < 0) return false;
    let i = raw.indexOf(M.WIDGET_OPEN_PREFIX);
    while (i >= 0) {
      if (M.widgetAt(raw, i)) return true;
      i = raw.indexOf(M.WIDGET_OPEN_PREFIX, i + 1);
    }
    return false;
  }

  /** Drops widget-only lines that render blank, so an unfilled slot leaves no gap. */
  function dropEmptyWidgetLines(raw, resolved) {
    if (!resolved || !containsWidget(raw)) return resolved;
    const flags = widgetLineFlags(raw);
    const lines = resolved.split('\n');
    const kept = [];
    lines.forEach((line, i) => {
      if (i < flags.length && flags[i]) {
        const blank = line === '' || linesPlain(parse(fromLegacy(line))).join('').trim() === '';
        if (blank) return;
      }
      kept.push(line);
    });
    return kept.join('\n');
  }

  /* ------------------------------------------------------ parseFormat mirror */

  /**
   * Builds what the nametag shows.
   *
   * ctx: { name, values: {key: value}, platform: 'java'|'bedrock', lineLimit, ellipsis,
   *        widgetEllipsis, bedrockPrefix, bedrockSuffix, applyAffix }
   * Returns { lines: runs[][], plain: string[], rawLines, info: [{length, limit, truncated}] }
   */
  function build(raw, ctx) {
    const c = ctx || {};
    const withAffix = c.platform === 'bedrock' && c.applyAffix
      ? (c.bedrockPrefix || '') + (raw || '') + (c.bedrockSuffix || '')
      : (raw || '');
    if (!withAffix) return { lines: [], plain: [], info: [] };

    let s = stripForRendering(withAffix);
    if (c.name != null) s = s.split('{player}').join(c.name);
    const values = c.values || {};
    s = s.replace(/%([^%\n]+)%/g, (m, key) => (Object.prototype.hasOwnProperty.call(values, key) ? values[key] : m));
    s = s.split('\\n').join('\n');
    s = truncateMarkedWidgets(s, !!c.widgetEllipsis);
    s = dropEmptyWidgetLines(withAffix, s);
    const src = fromLegacy(s);
    let lines = parse(src);
    if (lines.length === 1 && lines[0].length === 0) lines = [];

    // truncateToCharacterLimit
    const limit = typeof c.lineLimit === 'number' ? c.lineLimit : -1;
    const overrides = lineCharacterOverrides(withAffix);
    const info = [];
    const out = lines.map((runs, i) => {
      const eff = overrides[i] !== undefined && overrides[i] >= 0 ? overrides[i] : limit;
      const length = runsPlain(runs).length;
      const t = truncateRuns(runs, eff, !!c.ellipsis);
      info.push({ length, limit: eff, truncated: t.truncated });
      return t.runs;
    });
    return { lines: out, plain: linesPlain(out), info };
  }

  /** What one piece (a %placeholder% or {player}) currently resolves to, as plain text. */
  function resolveOne(value, ctx) {
    const c = ctx || {};
    let s = value;
    if (c.name != null) s = s.split('{player}').join(c.name);
    const values = c.values || {};
    s = s.replace(/%([^%\n]+)%/g, (m, key) => (Object.prototype.hasOwnProperty.call(values, key) ? values[key] : m));
    return { text: linesPlain(parse(fromLegacy(s))).join(' '), known: s !== value };
  }

  /* ---------------------------------------------------------- tag metadata */

  const prettify = (key) => key.split('_').map((w) => w[0].toUpperCase() + w.slice(1)).join(' ');

  /**
   * Describes a COLOR chunk's tag for the UI:
   * { label, swatch: css background|null, kind: 'color'|'style'|'gradient'|'rainbow'|'close'|'other' }
   */
  function describeTag(tag) {
    const t = String(tag);
    const RAINBOW = 'linear-gradient(90deg,#ff5555,#ffaa00,#ffff55,#55ff55,#55ffff,#5555ff,#ff55ff)';
    let m = /^&#([0-9a-fA-F]{6})$/.exec(t);
    if (m) return { label: '#' + m[1].toLowerCase(), swatch: '#' + m[1].toLowerCase(), kind: 'color', legacy: true };
    m = /^&([0-9a-fk-orA-FK-OR])$/.exec(t);
    if (m) {
      const k = m[1].toLowerCase();
      if (LEGACY_COLOR[k]) return { label: prettify(LEGACY_COLOR[k]), swatch: NAMED[LEGACY_COLOR[k]], kind: 'color', legacy: true };
      const name = LEGACY_STYLE[k];
      return { label: name === 'reset' ? 'Reset' : prettify(name), swatch: null, kind: name === 'reset' ? 'other' : 'style', legacy: true };
    }
    m = /^<(\/)?(!)?([#A-Za-z0-9_]+)((?::[^<>]*)?)>$/.exec(t);
    if (!m) return { label: t, swatch: null, kind: 'other' };
    const closing = !!m[1];
    const name = m[3].toLowerCase();
    const args = splitArgs(m[4]);
    if (closing) return { label: 'End ' + (NAMED[name] ? prettify(name) : name.replace(/^#/, '#')), swatch: null, kind: 'close' };
    if (NAMED[name]) return { label: prettify(name), swatch: NAMED[name], kind: 'color' };
    if (HEX6.test(name)) return { label: name.toLowerCase(), swatch: name.toLowerCase(), kind: 'color' };
    if (DECO[name]) return { label: name === 'obfuscated' || name === 'obf' ? 'Obfuscated' : prettify(DECO_CANON[DECO[name]]), swatch: null, kind: 'style' };
    if (name === 'rainbow') return { label: 'Rainbow', swatch: RAINBOW, kind: 'rainbow' };
    if (name === 'gradient') {
      const { stops } = gradientStops(args);
      return { label: 'Gradient', swatch: 'linear-gradient(90deg,' + stops.join(',') + ')', kind: 'gradient', stops };
    }
    if (name === 'color' || name === 'colour' || name === 'c') {
      const col = parseColor(args[0]);
      if (col) return { label: args[0], swatch: col, kind: 'color' };
    }
    if (name === 'reset') return { label: 'Reset', swatch: null, kind: 'other' };
    return { label: t, swatch: null, kind: 'other' };
  }

  /* --------------------------------------------------------------- HTML */

  const esc = (s) => String(s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

  /* ----------------------------------------------------------- obfuscated */
  // Mirrors vanilla Minecraft's "obfuscated"/magic formatting: each character
  // keeps cycling to a random glyph of the same kind (digit/upper/lower/
  // symbol) so the word shape and roughly the width stay put, spaces stay put.
  const OBF_DIGIT = '0123456789';
  const OBF_UPPER = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ';
  const OBF_LOWER = 'abcdefghijklmnopqrstuvwxyz';
  const OBF_SYMBOL = '!"#$%&\'()*+,-./:;<=>?@[\\]^_`{|}~';
  function obfChar(ch) {
    if (ch === ' ') return ' ';
    if (OBF_DIGIT.indexOf(ch) >= 0) return OBF_DIGIT[(Math.random() * OBF_DIGIT.length) | 0];
    if (ch >= 'A' && ch <= 'Z') return OBF_UPPER[(Math.random() * OBF_UPPER.length) | 0];
    if (ch >= 'a' && ch <= 'z') return OBF_LOWER[(Math.random() * OBF_LOWER.length) | 0];
    return OBF_SYMBOL[(Math.random() * OBF_SYMBOL.length) | 0];
  }
  function scrambleObf(src) {
    let out = '';
    for (let i = 0; i < src.length; i++) out += obfChar(src[i]);
    return out;
  }

  /** One line of runs → inline HTML spans. `grey` flattens colour (crouching).
   *  Obfuscated runs are built as one fixed-width cell per character (.mc-o-c)
   *  so the plate never resizes as the glyphs keep changing underneath — only
   *  their textContent is touched by the animation tick, never the layout. */
  function runsToHtml(runs, grey) {
    if (!runs.length) return '&#8203;';
    return runs.map((r) => {
      const cls = [];
      if (r.b) cls.push('mc-b');
      if (r.i) cls.push('mc-i');
      if (r.u && r.st) cls.push('mc-us'); else if (r.u) cls.push('mc-u'); else if (r.st) cls.push('mc-st');
      const color = grey || r.c;
      if (r.o) {
        cls.push('mc-o');
        const cells = r.t.split('').map((ch) => '<span class="mc-o-c">' + esc(obfChar(ch)) + '</span>').join('');
        return '<span class="' + cls.join(' ') + '" style="color:' + color + '" data-obf="' + esc(r.t) + '">' + cells + '</span>';
      }
      return '<span' + (cls.length ? ' class="' + cls.join(' ') + '"' : '') + ' style="color:' + color + '">' + esc(r.t) + '</span>';
    }).join('');
  }

  NT.mini = {
    NAMED, LEGACY_COLOR, fromLegacy, tokenize, parse, visibleLength, truncateSource, truncateRuns,
    describeTag, parseColor, gradientColor, hsvToHex, WHITE, SNEAK_GREY, SNEAK_GREY_BEDROCK,
  };
  NT.preview = {
    build, resolveOne, stripForRendering, truncateMarkedWidgets, lineCharacterOverrides,
    dropEmptyWidgetLines, containsWidget, linesPlain, runsPlain, runsToHtml, scrambleObf, esc,
  };
})((globalThis.NT = globalThis.NT || {}));
