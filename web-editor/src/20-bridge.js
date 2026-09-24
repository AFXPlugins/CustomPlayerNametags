/* ==========================================================================
 * 20-bridge.js — everything the editor knows about the "server".
 *
 * The UI only ever talks to a `bridge`. Right now that is the in-memory
 * `MockBridge` below, seeded with fake players, groups, placeholders and
 * formats so the editor can be exercised without the plugin. To wire it up
 * later, implement the same methods against the plugin's web API and return it
 * from `NT.createBridge()` — nothing else in the editor has to change.
 *
 * ---------------------------------------------------------------- CONTRACT
 * All methods return Promises.
 *
 *   load() -> {
 *     session:      { role: 'admin' | 'player', player: { uuid, name } },
 *     settings:     { lineMaxCharacters, truncateIndicator, widgetTruncateIndicator,
 *                     crouchEffect: 'NONE'|'DEFAULT'|'HIDE',
 *                     separateBedrockGlobal, separateBedrockGroups,
 *                     bedrockPrefix, bedrockSuffix, affixGlobal, affixPlayer, affixGroup },
 *     placeholders: [ { key, value, title|null } ],       // editor-placeholders.yml
 *     players:      [ { uuid, name, group, hasFormat } ], // online players (admin)
 *     groups:       { java: [name], bedrock: [name] },    // groups with a stored format (admin)
 *   }
 *
 *   loadFormat(target) -> { raw, exists, effectiveRaw }
 *     target = { type: 'global' | 'group' | 'player' | 'self',
 *                id?: groupName | playerUuid,  platform: 'java' | 'bedrock' }
 *     `raw` is the stored format string; `effectiveRaw` is what currently
 *     applies to that player/group/global (used to "start from current").
 *     For type 'self', `raw` is the player's effective format with their own
 *     widget fills already overlaid (NametagManager#getEffectiveRawFormat).
 *
 *   saveFormat(target, raw) -> { ok, error? }          (admin)
 *   saveOwn(raw)            -> { ok, error? }          (player: the server keeps
 *       only the widget fills unless the player already has an individual format)
 *   clearPlayerFormat(uuid) -> { ok }                  (admin)
 *   clearGroupFormat(id, bedrock) -> { ok }             (admin — deletes a group's
 *       custom format entirely, reverting its members to whichever
 *       lower-priority format tier next applies)
 *
 *   previewContext(uuid)    -> { name, values: { key: resolvedString }, lineLimit }
 *       PlaceholderAPI values for that player as the nametag would resolve them,
 *       and their effective per-line character limit (-1 = unlimited).
 *
 *   isMock: true            -> shows the "test session" banner + sandbox drawer.
 * ========================================================================== */
(function (NT) {
  'use strict';
  const M = NT.model;

  const clone = (o) => JSON.parse(JSON.stringify(o));
  const wait = (ms) => new Promise((r) => setTimeout(r, ms));

  /* ------------------------------------------------------------- seed data */

  function seed() {
    const profile = (uuid, name, group, look, limit, values) => ({ uuid, name, group, look, limit, values });
    const base = {
      luckperms_primary_group_name: 'default', player_ping: '48', player_health: '20', player_level: '3',
      player_world: 'world', vault_eco_balance_formatted: '$120', statistic_player_kills: '0', guild_tag: '',
      luckperms_prefix: '',
    };
    const v = (o) => Object.assign({}, base, o);
    return {
      settings: {
        lineMaxCharacters: 24, truncateIndicator: true, widgetTruncateIndicator: true, crouchEffect: 'DEFAULT',
        separateBedrockGlobal: true, separateBedrockGroups: true,
        bedrockPrefix: '', bedrockSuffix: '', affixGlobal: false, affixPlayer: false, affixGroup: false,
      },
      placeholders: [
        { key: 'luckperms_prefix', value: '%luckperms_prefix%', title: 'Rank prefix' },
        { key: 'luckperms_primary_group_name', value: '%luckperms_primary_group_name%', title: 'Rank name' },
        { key: 'player_ping', value: '%player_ping%', title: 'Ping (ms)' },
        { key: 'player_health', value: '%player_health%', title: 'Health' },
        { key: 'player_level', value: '%player_level%', title: 'XP level' },
        { key: 'player_world', value: '%player_world%', title: 'World' },
        { key: 'vault_eco_balance_formatted', value: '%vault_eco_balance_formatted%', title: 'Balance' },
        { key: 'statistic_player_kills', value: '%statistic_player_kills%', title: 'Kills' },
        { key: 'guild_tag', value: '%guild_tag%', title: 'Guild tag' },
      ],
      profiles: [
        profile('p-1', 'Pixelpaw', 'owner', { skin: '#e9b98f', hair: '#3a2a22', shirt: '#2f6fd0', pants: '#39407a', eyes: '#3b6fd8' }, 40, v({
          luckperms_prefix: '&c&l[Owner] &r', luckperms_primary_group_name: 'owner', player_ping: '23', player_level: '47',
          vault_eco_balance_formatted: '$1.2M', statistic_player_kills: '1,204',
          guild_tag: '<gradient:#ff8a00:#e52e71>Dragonkin</gradient>',
        })),
        profile('p-2', 'Ferrowick', 'admin', { skin: '#c68e63', hair: '#1d1d24', shirt: '#b5432e', pants: '#3a3a44', eyes: '#2a2a2a' }, null, v({
          luckperms_prefix: '&6[Admin] ', luckperms_primary_group_name: 'admin', player_ping: '61', player_level: '32',
          vault_eco_balance_formatted: '$88,410', statistic_player_kills: '512', guild_tag: '&e[Forge]',
        })),
        profile('p-3', 'Daisy_Cuts', 'moderator', { skin: '#f2cfae', hair: '#c7622d', shirt: '#3aa06a', pants: '#2e4a6e', eyes: '#2f8f5a' }, null, v({
          luckperms_prefix: '&9[Mod] ', luckperms_primary_group_name: 'moderator', player_ping: '35', player_level: '24',
          vault_eco_balance_formatted: '$5,230', statistic_player_kills: '87', guild_tag: '<rainbow>Petals</rainbow>',
        })),
        profile('p-4', 'Kestrel', 'vip', { skin: '#d9a27c', hair: '#e2c15a', shirt: '#7a48c9', pants: '#2a2f52', eyes: '#7a48c9' }, null, v({
          luckperms_prefix: '&a[VIP] ', luckperms_primary_group_name: 'vip', player_ping: '112', player_level: '18',
          vault_eco_balance_formatted: '$940', statistic_player_kills: '33', guild_tag: '&b[Skyward]',
        })),
        profile('p-5', 'Mossback', 'default', { skin: '#a97b56', hair: '#5a4632', shirt: '#4d7a3a', pants: '#4a3f33', eyes: '#3a3a3a' }, null, v({
          player_ping: '148', player_level: '6', vault_eco_balance_formatted: '$210', player_world: 'world_nether',
        })),
        profile('p-6', 'Sir_Bonks_A_Lot', 'default', { skin: '#f0c9a6', hair: '#a13a3a', shirt: '#d9d9e4', pants: '#59637d', eyes: '#1f6fbd' }, null, v({
          luckperms_prefix: '&5[Grand Archmage of Ember] ', player_ping: '9', player_level: '61',
          vault_eco_balance_formatted: '$12,004,551', statistic_player_kills: '9,999', guild_tag: '&d[The Long Name Guild]',
        })),
      ],
      formats: {
        global: {
          java: '%luckperms_prefix%<white>{player}\\n<gray>Ping <green>%player_ping%<gray> ms {widget id=k3m9x2ab colors=true placeholders=true text=false limit=14}%guild_tag%{/widget}\\n{widget id=b71f04de colors=false placeholders=true text=true limit=18}{/widget}',
          bedrock: '<aqua>{player}\\n<gray>%player_ping% ms',
        },
        group: {
          java: {
            owner: '<gradient:#ff5f6d:#ffc371><bold>OWNER</bold></gradient> <white>{player}\\n{chars 20}<gray>Level <yellow>%player_level%<gray> | %statistic_player_kills% kills',
            admin: '<red>[Admin] <white>{player}\\n{widget id=a7c3e901 colors=true placeholders=true text=true limit=16}{/widget}',
            moderator: '&9[Mod] &f{player}\\n<gray>%player_world%',
            vip: '&a[VIP] &f{player}\\n<gray>%vault_eco_balance_formatted%',
          },
          bedrock: { owner: '<gold>[Owner] {player}' },
        },
        player: { 'p-4': '<green>{player}\\n<rainbow>%player_ping% ping</rainbow>' },
      },
      fills: { 'p-1': { b71f04de: 'Diamonds or bust' } },
      session: { role: 'admin', playerUuid: 'p-1' },
    };
  }

  /* ----------------------------------------------------------------- mock */

  class MockBridge {
    constructor() {
      this.isMock = true;
      this.latency = 90;
      this.reset();
    }

    reset() { this.state = seed(); }

    get settings() { return this.state.settings; }
    profileByUuid(uuid) { return this.state.profiles.find((p) => p.uuid === uuid) || null; }

    async load() {
      await wait(this.latency);
      const s = this.state;
      const me = this.profileByUuid(s.session.playerUuid);
      return clone({
        session: { role: s.session.role, player: { uuid: me.uuid, name: me.name } },
        settings: s.settings,
        placeholders: s.placeholders,
        players: s.profiles.map((p) => ({ uuid: p.uuid, name: p.name, group: p.group, hasFormat: !!s.formats.player[p.uuid] })),
        groups: {
          java: Object.keys(s.formats.group.java),
          bedrock: Object.keys(s.formats.group.bedrock),
        },
      });
    }

    /** The format the server would apply to a player right now (override → group → global). */
    effectiveFor(profile, platform) {
      const s = this.state;
      const bedrock = platform === 'bedrock';
      const own = s.formats.player[profile.uuid];
      let raw;
      let source;
      if (own != null) { raw = own; source = 'player'; }
      else {
        const useBedrockGroups = bedrock && s.settings.separateBedrockGroups;
        const groupFormats = useBedrockGroups ? s.formats.group.bedrock : s.formats.group.java;
        if (groupFormats[profile.group] != null) { raw = groupFormats[profile.group]; source = 'group'; }
        else if (bedrock && s.settings.separateBedrockGlobal) { raw = s.formats.global.bedrock; source = 'global'; }
        else { raw = s.formats.global.java; source = 'global'; }
      }
      const fills = s.fills[profile.uuid];
      return { raw: fills ? M.overlayWidgetFills(raw, fills) : raw, source };
    }

    async loadFormat(target) {
      await wait(this.latency / 2);
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
        const eff = this.effectiveFor(p, target.platform);
        return { raw: own == null ? '' : own, exists: own != null, effectiveRaw: eff.raw, effectiveSource: eff.source };
      }
      // self: the player's own applied format, widget fills overlaid
      const me = this.profileByUuid(s.session.playerUuid);
      const eff = this.effectiveFor(me, 'java');
      return { raw: eff.raw, exists: true, effectiveRaw: eff.raw, effectiveSource: eff.source, hasIndividual: s.formats.player[me.uuid] != null };
    }

    async saveFormat(target, raw) {
      await wait(this.latency);
      if (!raw) return { ok: false, error: 'A nametag format cannot be empty.' };
      const s = this.state;
      const bedrock = target.platform === 'bedrock';
      if (target.type === 'global') s.formats.global[bedrock ? 'bedrock' : 'java'] = raw;
      else if (target.type === 'group') s.formats.group[bedrock ? 'bedrock' : 'java'][target.id] = raw;
      else if (target.type === 'player') s.formats.player[target.id] = raw;
      else return { ok: false, error: 'Unknown target.' };
      return { ok: true };
    }

    async saveOwn(raw) {
      await wait(this.latency);
      if (!raw) return { ok: false, error: 'Your nametag format cannot be empty.' };
      const s = this.state;
      const uuid = s.session.playerUuid;
      // Same rule as NametagEditorManager#confirm: a player with an individual
      // format saves it whole; otherwise only their widget fills are kept.
      if (s.formats.player[uuid] != null) s.formats.player[uuid] = raw;
      else s.fills[uuid] = M.extractWidgetContents(raw);
      return { ok: true };
    }

    async clearPlayerFormat(uuid) {
      await wait(this.latency / 2);
      delete this.state.formats.player[uuid];
      return { ok: true };
    }

    async clearGroupFormat(id, bedrock) {
      await wait(this.latency / 2);
      delete this.state.formats.group[bedrock ? 'bedrock' : 'java'][id];
      return { ok: true };
    }

    async previewContext(uuid) {
      const p = this.profileByUuid(uuid) || this.state.profiles[0];
      // profile.limit: null = server default, -1 = unlimited, N = tiered permission
      // (customplayernametags.bypasslinecharacterlimit.N)
      const lineLimit = p.limit == null ? this.state.settings.lineMaxCharacters : p.limit;
      return { name: p.name, values: clone(p.values), lineLimit, look: p.look, group: p.group };
    }

    /* ---- sandbox-only helpers (not part of the contract) ---- */
    setRole(role) { this.state.session.role = role; }
    addPlaceholder(key, title) {
      if (!key || this.state.placeholders.some((p) => p.key === key)) return false;
      this.state.placeholders.push({ key, value: '%' + key + '%', title: title || null });
      this.state.profiles.forEach((p) => { if (p.values[key] === undefined) p.values[key] = ''; });
      return true;
    }
    removePlaceholder(key) { this.state.placeholders = this.state.placeholders.filter((p) => p.key !== key); }
  }

  // A `?session=<id>` in the URL (put there by `/nametags editor web`) means
  // this page should load real server data through RelayBridge (21-relay.js)
  // instead of the built-in demo data — everything else about the app is
  // identical either way, since both bridges implement the same contract.
  NT.createBridge = () => {
    const sessionId = NT.relay && NT.relay.sessionIdFromUrl();
    if (sessionId && NT.RelayBridge) {
      return new NT.RelayBridge(sessionId);
    }
    return new MockBridge();
  };
  NT.MockBridge = MockBridge;
})((globalThis.NT = globalThis.NT || {}));
