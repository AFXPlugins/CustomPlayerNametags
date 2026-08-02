# CustomPlayerNametags

![CustomPlayerNametags Banner](https://cdn.modrinth.com/data/cached_images/559370701fd340038daa3fef45b8339a295bf717.png)

## Features
- Fully customize entire player nametags, including usernames, using PlaceholderAPI placeholders.
- Set a global nametag format for all players.
- Set custom nametag formats for individual players.
- Java and bedrock compatibility.

## Requirements

- [PacketEvents](https://modrinth.com/plugin/packetevents) (required - plugin functionality)
- [PlaceholderAPI](https://modrinth.com/plugin/placeholderapi) (required - placeholder support)
## Commands

- `/nametags reload` — Reload plugin.
- `/nametags update` — Check plugin for updates.
- `/nametags format view <unparsed|parsed> global` — View the global nametag format.
- `/nametags format view <unparsed|parsed> player <player>` — View a player's nametag format.
- `/nametags format set global "<format>"` — Set the global nametag format.
- `/nametags format set player <player> "<format>"` — Set a player's nametag format.
- `/nametags format reset global` — Reset the global nametag format to default.
- `/nametags format reset player <player>` — Reset a player's nametag format.

## Permissions

- `customplayernametags.admin` — Allows reloading the plugin configuration (default: OP).