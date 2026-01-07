# AntiBase

AntiBase is a lightweight obfuscation plugin for Paper servers designed to hide underground bases and caves from X-ray and Freecam. Unlike simple proximity systems, it uses a 3D flood-fill algorithm to determine what a player can actually see based on physical connectivity.

## How it works

AntiBase intercepts outgoing packets to hide underground structures and players:
- **Visibility Scanning**: Uses a 3D flood-fill algorithm to find reachable air blocks.
- **Occlusion**: Buried blocks are replaced with a configurable block (e.g., Stone or Air) in real-time.
- **Entity Hiding**: Players and mobs in hidden areas are removed from the world tracker.

## Features

- **Flood-Fill Engine**: Precise occlusion that respects walls and 1-block gaps.
- **Player Hiding**: Prevents ESP and wall-hacks while keeping players in the TAB list.
- **Dynamic Radius**: Scans up to 160 blocks around the player.
- **Packet-Level**: No modifications to world files or block data on disk.
- **Debug Mode**: Toggle visible sections via `/antibase debug`.

## Installation

1. Drop `AntiBase.jar` into your `plugins` folder.
2. Restart the server.
3. Configure `hide-below-y` in the `config.yml` (default is 0).

## Configuration

```yaml
enabled: true
hide-below-y: 0           # Obfuscation starts below this Y level
proximity-distance: 16    # Safety buffer for block updates
replacement-block: STONE  # Block to show instead of air/ores
```

## Permissions

- `antibase.debug`: Allows use of the debug toggle command.

---
*Requires Paper 1.21.x and PacketEvents.*
