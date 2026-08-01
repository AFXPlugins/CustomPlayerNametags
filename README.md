# CustomPlayerNametags

![CustomPlayerNametags Banner](https://cdn.modrinth.com/data/cached_images/559370701fd340038daa3fef45b8339a295bf717.png)

Customize every player's overhead nametag using PlaceholderAPI. Uses PacketEvents to display custom nametags while keeping player usernames and other systems untouched. Works with both Java and Bedrock players.

## Requirements

- [PacketEvents](https://modrinth.com/plugin/packetevents) (required - plugin functionality)
- [PlaceholderAPI](https://modrinth.com/plugin/placeholderapi) (required - placeholder support)

## Configuration

```yaml
nametag-format: "%player_name%"
nametag-height-offset: 2.1
```

- `nametag-format` — The format used for player nametags. Supports PlaceholderAPI placeholders and `&` color codes.
- `nametag-height-offset` — The Height in blocks the nametag floats above the player.

## Commands

- `/nametags reload` — Reloads the plugin configuration. Requires `customplayernametags.admin`.

## Permissions

- `customplayernametags.admin` — Allows reloading the plugin configuration (default: OP). 
