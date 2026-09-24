/**
 * CustomPlayerNametags — web editor relay
 * ----------------------------------------------------------------------
 * A tiny, free-tier Cloudflare Worker that lets the plugin hand a
 * temporary "session" (a snapshot of a server's nametag configuration)
 * to the browser-based web editor, without the server owner needing an
 * account, an API key, or any port forwarding.
 *
 *   POST /session       body = JSON snapshot   -> { id, expiresAt }
 *   GET  /session/<id>                         -> the stored JSON snapshot
 *
 * Sessions are stored in Workers KV with a TTL (SESSION_TTL_SECONDS) and
 * are never written back to — the whole "apply changes" flow happens by
 * hand in the Minecraft console (the web editor only ever *reads* a
 * session from here), so there is nothing here that can be used to push
 * commands into a server. This Worker only ever stores and returns
 * plugin-editor snapshots; it does not execute anything.
 *
 * Deploy with Wrangler — see README.md next to this file.
 */

// ---------------------------------------------------------------- config

/** Reject any request body larger than this (bytes). Keeps KV usage and
 *  abuse potential bounded — a nametag config snapshot is normally a few KB. */
const MAX_BODY_BYTES = 256 * 1024; // 256 KB

/** How long a session survives in KV before it silently expires. */
const SESSION_TTL_SECONDS = 30 * 60; // 30 minutes

/** Simple fixed-window rate limit, per client IP, for session creation. */
const RATE_LIMIT_WINDOW_SECONDS = 60;
const RATE_LIMIT_MAX_CREATES = 12;

/** Same idea for reads, a little looser since a browser reload counts too. */
const RATE_LIMIT_MAX_READS = 60;

// ------------------------------------------------------------------ CORS

function corsHeaders(env) {
  // ALLOWED_ORIGIN is set in wrangler.toml / the dashboard — normally your
  // GitHub Pages origin, e.g. "https://yourname.github.io". "*" also works
  // (the payloads are short-lived nametag config, not secrets) if you'd
  // rather not restrict it.
  const origin = (env && env.ALLOWED_ORIGIN) || "*";
  return {
    "Access-Control-Allow-Origin": origin,
    "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
    "Access-Control-Allow-Headers": "Content-Type",
    "Access-Control-Max-Age": "86400",
    "Vary": "Origin",
  };
}

function json(data, status, env, extraHeaders) {
  return new Response(JSON.stringify(data), {
    status: status || 200,
    headers: Object.assign(
      { "Content-Type": "application/json; charset=utf-8" },
      corsHeaders(env),
      extraHeaders || {}
    ),
  });
}

function textError(message, status, env) {
  return json({ error: message }, status, env);
}

// ------------------------------------------------------------- session id

/** 32 unpredictable URL-safe characters (~190 bits of entropy). */
function generateSessionId() {
  const bytes = new Uint8Array(24);
  crypto.getRandomValues(bytes);
  let out = "";
  for (let i = 0; i < bytes.length; i++) {
    out += bytes[i].toString(16).padStart(2, "0");
  }
  return out; // 48 hex chars
}

const SESSION_ID_RE = /^[a-f0-9]{48}$/;

// ---------------------------------------------------------------- ratelimit

/**
 * A crude fixed-window counter stored in the same KV namespace, keyed by
 * client IP + action + the current window. Good enough to blunt casual
 * abuse of a free Worker without needing Durable Objects. Fails open (lets
 * the request through) if KV itself errors, since availability of the
 * editor matters more than perfect enforcement here.
 */
async function checkRateLimit(env, request, action, limit) {
  try {
    const ip = request.headers.get("CF-Connecting-IP") || "unknown";
    const window = Math.floor(Date.now() / 1000 / RATE_LIMIT_WINDOW_SECONDS);
    const key = `rl:${action}:${ip}:${window}`;
    const current = parseInt((await env.NAMETAG_SESSIONS.get(key)) || "0", 10);
    if (current >= limit) {
      return false;
    }
    await env.NAMETAG_SESSIONS.put(key, String(current + 1), {
      expirationTtl: RATE_LIMIT_WINDOW_SECONDS + 5,
    });
    return true;
  } catch (e) {
    return true;
  }
}

// -------------------------------------------------------------- validation

/**
 * Cheap structural sanity check on an incoming snapshot — this Worker
 * doesn't need to understand nametag formats, it just refuses obvious
 * garbage before it ever reaches KV/the browser. The plugin is the source
 * of truth for what a "real" snapshot looks like.
 */
function looksLikeSnapshot(body) {
  return (
    body &&
    typeof body === "object" &&
    !Array.isArray(body) &&
    body.formats &&
    typeof body.formats === "object" &&
    body.settings &&
    typeof body.settings === "object"
  );
}

// -------------------------------------------------------------------- routes

async function handleCreate(request, env) {
  if (!(await checkRateLimit(env, request, "create", RATE_LIMIT_MAX_CREATES))) {
    return textError("Too many sessions created from this address recently — try again in a minute.", 429, env);
  }

  const contentLength = request.headers.get("Content-Length");
  if (contentLength && parseInt(contentLength, 10) > MAX_BODY_BYTES) {
    return textError("Snapshot too large.", 413, env);
  }

  let raw;
  try {
    raw = await request.text();
  } catch (e) {
    return textError("Could not read request body.", 400, env);
  }
  if (raw.length > MAX_BODY_BYTES) {
    return textError("Snapshot too large.", 413, env);
  }

  let body;
  try {
    body = JSON.parse(raw);
  } catch (e) {
    return textError("Request body must be valid JSON.", 400, env);
  }

  if (!looksLikeSnapshot(body)) {
    return textError("Request body does not look like a nametag editor snapshot.", 400, env);
  }

  const id = generateSessionId();
  const createdAt = Date.now();
  const expiresAt = createdAt + SESSION_TTL_SECONDS * 1000;

  const stored = JSON.stringify({ createdAt, expiresAt, snapshot: body });

  await env.NAMETAG_SESSIONS.put(id, stored, { expirationTtl: SESSION_TTL_SECONDS });

  return json({ id, expiresAt, ttlSeconds: SESSION_TTL_SECONDS }, 201, env);
}

async function handleGet(request, env, id) {
  if (!SESSION_ID_RE.test(id)) {
    return textError("Invalid session id.", 400, env);
  }
  if (!(await checkRateLimit(env, request, "read", RATE_LIMIT_MAX_READS))) {
    return textError("Too many requests from this address recently — try again shortly.", 429, env);
  }

  const stored = await env.NAMETAG_SESSIONS.get(id);
  if (!stored) {
    return textError("This editor session has expired or does not exist. Run /nametags editor web again.", 404, env);
  }

  let parsed;
  try {
    parsed = JSON.parse(stored);
  } catch (e) {
    return textError("Stored session was corrupted.", 500, env);
  }

  return json(
    { id, createdAt: parsed.createdAt, expiresAt: parsed.expiresAt, snapshot: parsed.snapshot },
    200,
    env
  );
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);

    if (request.method === "OPTIONS") {
      return new Response(null, { status: 204, headers: corsHeaders(env) });
    }

    if (url.pathname === "/session" && request.method === "POST") {
      return handleCreate(request, env);
    }

    const match = url.pathname.match(/^\/session\/([a-f0-9]{1,64})$/);
    if (match && request.method === "GET") {
      return handleGet(request, env, match[1]);
    }

    if (url.pathname === "/" || url.pathname === "/health") {
      return json({ ok: true, service: "customplayernametags-editor-relay" }, 200, env);
    }

    return textError("Not found.", 404, env);
  },
};
