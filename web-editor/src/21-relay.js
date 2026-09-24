/* ==========================================================================
 * 21-relay.js — RelayBridge: the real-server counterpart to MockBridge.
 *
 * When the page is opened with `?session=<id>` in the URL, NT.createBridge()
 * (see the bottom of 20-bridge.js) returns one of these instead of a
 * MockBridge. It fetches a one-time snapshot of a real server's nametag
 * configuration from the Cloudflare Worker relay (see
 * cloudflare-worker/worker.js), then behaves *exactly* like MockBridge from
 * that point on: every save/clear only mutates an in-memory copy — nothing
 * is ever written back to the relay, and the relay never talks to the
 * Minecraft server at all. The only extra thing this bridge does over
 * MockBridge is remember what changed (`getPendingChanges`) so the Publish
 * screen (65-publish.js) can turn that diff into the `/nametags format ...`
 * console commands that actually apply it.
 *
 * Same method contract as MockBridge — see the big comment atop
 * 20-bridge.js for the full method list.
 * ========================================================================== */
(function (NT) {
  'use strict';
  const M = NT.model;

  const clone = (o) => JSON.parse(JSON.stringify(o));

  /**
   * The single relay Worker this editor talks to. Matches
   * EditorRelayClient.WORKER_URL on the plugin side — if you self-host your
   * own Worker, change both together. `?api=` in the URL overrides this for
   * local testing against a dev Worker, without needing a rebuild.
   */
  const DEFAULT_WORKER_URL = 'https://customplayernametags-editor-relay.YOUR_SUBDOMAIN.workers.dev';

  function urlParam(name) {
    try {
      return new URLSearchParams(window.location.search).get(name);
    } catch (e) {
      return null;
    }
  }

  /** The session id in the current URL, or null if this isn't a relay session. */
  function sessionIdFromUrl() {
    const id = urlParam('session');
    return id && /^[a-f0-9]{1,64}$/i.test(id) ? id : null;
  }

  function workerUrl() {
    const override = urlParam('api');
    return (override || DEFAULT_WORKER_URL).replace(/\/+$/, '');
  }

  class RelayBridge {
    constructor(sessionId) {
      this.isMock = false;
      this.isRelay = true;
      this.sessionId = sessionId;
      this.workerUrl = workerUrl();
      this.state = null;
      this.original = null; // frozen copy of `formats`, for diffing on Publish
      this.meta = null;
      this.loadPromise = null;
    }

    /** Fetches the snapshot once and caches it; safe to call more than once. */
    _ensureFetched() {
      if (this.loadPromise) return this.loadPromise;
      this.loadPromise = fetch(this.workerUrl + '/session/' + encodeURIComponent(this.sessionId), {
        method: 'GET',
      })
        .then((res) => {
          if (!res.ok) {
            return res
              .json()
              .catch(() => ({}))
              .then((body) => {
                throw new Error((body && body.error) || ('The relay returned HTTP ' + res.status + '.'));
              });
          }
          return res.json();
        })
        .then((body) => {
          const snapshot = body.snapshot || {};
          this.meta = Object.assign(
            { createdAt: body.createdAt, expiresAt: body.expiresAt },
            snapshot.meta || {}
          );
          this.state = normalizeSnapshot(snapshot);
          this.original = clone(this.state.formats);
        });
      return this.loadPromise;
    }

    async load() {
      await this._ensureFetched();
      const s = this.state;
      // The admin who ran `/nametags editor web` isn't necessarily one of the
      // online players in the snapshot (could've been run from console), so
      // "self" is a synthetic identity — the admin role never actually
      // targets 'self' anyway (see A.boot in 30-app.js).
      return clone({
        session: { role: 'admin', player: { uuid: '__admin__', name: 'Admin' } },
        settings: s.settings,
        placeholders: s.placeholders,
        players: s.players.map((p) => ({
          uuid: p.uuid,
          name: p.name,
          group: p.group,
          hasFormat: s.formats.player[p.uuid] != null,
        })),
        groups: {
          java: Object.keys(s.formats.group.java),
          bedrock: Object.keys(s.formats.group.bedrock),
        },
      });
    }

    profileByUuid(uuid) {
      return this.state.players.find((p) => p.uuid === uuid) || null;
    }

    /** Same resolution order as MockBridge#effectiveFor / the plugin's NametagManager. */
    effectiveFor(profile, platform) {
      const s = this.state;
      const bedrock = platform === 'bedrock';
      const own = s.formats.player[profile.uuid];
      if (own != null) return { raw: own, source: 'player' };
      const useBedrockGroups = bedrock && s.settings.separateBedrockGroups;
      const groupFormats = useBedrockGroups ? s.formats.group.bedrock : s.formats.group.java;
      if (groupFormats[profile.group] != null) return { raw: groupFormats[profile.group], source: 'group' };
      if (bedrock && s.settings.separateBedrockGlobal) return { raw: s.formats.global.bedrock, source: 'global' };
      return { raw: s.formats.global.java, source: 'global' };
    }

    async loadFormat(target) {
      await this._ensureFetched();
      const s = this.state;
      const bedrock = target.platform === 'bedrock';
      if (target.type === 'global') {
        const raw = bedrock ? s.formats.global.bedrock : s.formats.global.java;
        return { raw, exists: true, effectiveRaw: raw };
      }
      if (target.type === 'group') {
        const raw = (bedrock ? s.formats.group.bedrock : s.formats.group.java)[target.id];
        return { raw: raw == null ? '' : raw, exists: raw != null, effectiveRaw: raw == null ? '' : raw };
      }
      if (target.type === 'player') {
        const p = this.profileByUuid(target.id);
        const own = s.formats.player[target.id];
        const eff = p ? this.effectiveFor(p, target.platform) : { raw: '', source: 'global' };
        return { raw: own == null ? '' : own, exists: own != null, effectiveRaw: eff.raw, effectiveSource: eff.source };
      }
      // 'self' isn't reachable from the admin-only relay flow, but keep this
      // harmless rather than throwing if something ever asks for it.
      return { raw: '', exists: false, effectiveRaw: '' };
    }

    async saveFormat(target, raw) {
      await this._ensureFetched();
      if (!raw) return { ok: false, error: 'A nametag format cannot be empty.' };
      const s = this.state;
      const bedrock = target.platform === 'bedrock';
      if (target.type === 'global') {
        s.formats.global[bedrock ? 'bedrock' : 'java'] = raw;
      } else if (target.type === 'group') {
        (bedrock ? s.formats.group.bedrock : s.formats.group.java)[target.id] = raw;
      } else if (target.type === 'player') {
        s.formats.player[target.id] = raw;
      } else {
        return { ok: false, error: 'Unknown target.' };
      }
      return { ok: true };
    }

    async saveOwn() {
      // Admin-only relay session — there is no "own" nametag to save here.
      return { ok: false, error: 'Not available in a web editor session.' };
    }

    async clearPlayerFormat(uuid) {
      await this._ensureFetched();
      delete this.state.formats.player[uuid];
      return { ok: true };
    }

    async clearGroupFormat(id, bedrock) {
      await this._ensureFetched();
      delete this.state.formats.group[bedrock ? 'bedrock' : 'java'][id];
      return { ok: true };
    }

    async previewContext(uuid) {
      await this._ensureFetched();
      const p = this.profileByUuid(uuid) || this.state.players[0];
      if (!p) return { name: '', values: {}, lineLimit: this.state.settings.lineMaxCharacters };
      const preview = this.state.previews[p.uuid] || { name: p.name, values: {}, lineLimit: this.state.settings.lineMaxCharacters };
      return { name: preview.name, values: clone(preview.values), lineLimit: preview.lineLimit, look: p.look, group: p.group };
    }

    /* ---- sandbox-only no-ops: a relay session has no role switch / fake data. ---- */
    setRole() {}
    addPlaceholder() { return false; }
    removePlaceholder() {}

    /* ---------------------------------------------------------- publish ---- */

    /**
     * Every format-level change made this session, as a flat list —
     * independent of the UI's undo/redo history, since it's a diff against
     * the snapshot as it was when the page loaded, not a log of edits.
     * Each entry has enough to both display itself and generate its command:
     *   { kind: 'global'|'group'|'player', platform: 'java'|'bedrock',
     *     action: 'set'|'add'|'edit'|'remove', id, label, raw, previousRaw }
     */
    getPendingChanges() {
      if (!this.state || !this.original) return [];
      const s = this.state;
      const o = this.original;
      const changes = [];

      ['java', 'bedrock'].forEach((platform) => {
        if (s.formats.global[platform] !== o.global[platform]) {
          changes.push({
            kind: 'global', platform, action: 'set', id: null, label: 'Global',
            raw: s.formats.global[platform], previousRaw: o.global[platform],
          });
        }
      });

      ['java', 'bedrock'].forEach((platform) => {
        const current = s.formats.group[platform];
        const before = o.group[platform];
        const names = new Set([...Object.keys(current), ...Object.keys(before)]);
        names.forEach((name) => {
          const now = current[name];
          const was = before[name];
          if (now === was) return;
          if (now == null) {
            changes.push({ kind: 'group', platform, action: 'remove', id: name, label: "Group '" + name + "'", raw: null, previousRaw: was });
          } else {
            changes.push({
              kind: 'group', platform, action: was == null ? 'add' : 'edit', id: name,
              label: "Group '" + name + "'", raw: now, previousRaw: was == null ? null : was,
            });
          }
        });
      });

      const currentP = s.formats.player;
      const beforeP = o.player;
      const uuids = new Set([...Object.keys(currentP), ...Object.keys(beforeP)]);
      uuids.forEach((uuid) => {
        const now = currentP[uuid];
        const was = beforeP[uuid];
        if (now === was) return;
        const profile = this.profileByUuid(uuid);
        const name = profile ? profile.name : uuid;
        if (now == null) {
          changes.push({ kind: 'player', platform: 'java', action: 'disable', id: uuid, label: name, raw: null, previousRaw: was });
        } else {
          changes.push({
            kind: 'player', platform: 'java', action: 'set', id: uuid, label: name, raw: now,
            previousRaw: was == null ? null : was,
          });
        }
      });

      return changes;
    }

    hasPendingChanges() {
      return this.getPendingChanges().length > 0;
    }

    /**
     * Turns `getPendingChanges()` into the exact `/nametags format ...`
     * command lines the admin pastes into the console — see
     * NametagCommand#handleFormatCommand on the plugin side for the
     * grammar this mirrors. `label` is the command alias/root to use
     * ("nametags" unless the server owner aliased it differently).
     */
    generateCommands(label) {
      const cmd = (label || 'nametags').replace(/^\/+/, '');
      const s = this.state;
      const quote = (raw) => '"' + String(raw) + '"';
      const plat = (platform, enabled) => (enabled ? platform + ' ' : '');

      return this.getPendingChanges().map((c) => {
        if (c.kind === 'global') {
          const withPlatform = plat(c.platform, s.settings.separateBedrockGlobal);
          return '/' + cmd + ' format global set ' + withPlatform + quote(c.raw);
        }
        if (c.kind === 'group') {
          const withPlatform = plat(c.platform, s.settings.separateBedrockGroups);
          if (c.action === 'remove') {
            return '/' + cmd + ' format groups remove ' + withPlatform + c.id;
          }
          return '/' + cmd + ' format groups ' + c.action + ' ' + withPlatform + c.id + ' ' + quote(c.raw);
        }
        // player
        if (c.action === 'disable') {
          return '/' + cmd + ' format player disable ' + c.label;
        }
        return '/' + cmd + ' format player set ' + c.label + ' ' + quote(c.raw);
      });
    }
  }

  /** Fills in any snapshot fields the plugin might omit, so the rest of the app never has to null-check them. */
  function normalizeSnapshot(snapshot) {
    const settings = Object.assign(
      {
        lineMaxCharacters: 24, truncateIndicator: true, widgetTruncateIndicator: true, crouchEffect: 'DEFAULT',
        separateBedrockGlobal: false, separateBedrockGroups: false,
        bedrockPrefix: '', bedrockSuffix: '', affixGlobal: false, affixPlayer: false, affixGroup: false,
      },
      snapshot.settings || {}
    );
    const placeholders = Array.isArray(snapshot.placeholders) ? snapshot.placeholders : [];
    const players = (Array.isArray(snapshot.players) ? snapshot.players : []).map((p) => ({
      uuid: p.uuid, name: p.name, group: p.group || 'default', look: deriveLook(p.uuid),
    }));
    const previews = snapshot.previews || {};
    const formats = snapshot.formats || {};
    return {
      settings, placeholders, players, previews,
      formats: {
        global: { java: (formats.global && formats.global.java) || '{player}', bedrock: (formats.global && formats.global.bedrock) || '{player}' },
        group: {
          java: Object.assign({}, formats.group && formats.group.java),
          bedrock: Object.assign({}, formats.group && formats.group.bedrock),
        },
        player: Object.assign({}, formats.player),
      },
    };
  }

  /** A small deterministic palette per uuid, purely cosmetic (16-figure.js falls back to a default look if this is omitted). */
  function deriveLook(uuid) {
    if (!uuid) return null;
    let h = 0;
    for (let i = 0; i < uuid.length; i++) h = (h * 31 + uuid.charCodeAt(i)) >>> 0;
    const hue = h % 360;
    const hsl = (hOff, s, l) => 'hsl(' + ((hue + hOff) % 360) + ',' + s + '%,' + l + '%)';
    return { skin: hsl(0, 45, 68), hair: hsl(20, 30, 25), shirt: hsl(140, 45, 45), pants: hsl(220, 30, 30), eyes: hsl(0, 10, 15) };
  }

  NT.relay = { sessionIdFromUrl, workerUrl };
  NT.RelayBridge = RelayBridge;
})((globalThis.NT = globalThis.NT || {}));
