# AntiBase

AntiBase is a lightweight obfuscation plugin for Paper servers designed to hide underground bases and caves from X-ray and Freecam. Unlike simple proximity systems, it scans what a player can actually reach or see and hides everything else in outgoing packets. World files are never touched.

## How it works

AntiBase intercepts outgoing packets to hide underground structures and entities:
- **Visibility Scanning**: `connected` mode (default) floods through open space so entire connected caves are preloaded before you walk in, including rooms around corners. `line-of-sight` mode keeps the old ray method if you want stronger corner hiding.
- **Occlusion**: hidden blocks are sent as AIR in initial chunks. Once a block is known hidden on the client, further updates for it are just suppressed.
- **Entity Hiding**: players and mobs without actual line of sight are removed from the tracker. Players stay in the TAB list.

## Features

- **Connected Flood Engine**: sealed rooms stay hidden, connected caves load ahead of room entry. Solid full blocks stop the flood.
- **Partial Blocks**: slabs, stairs, walls, doors, fences and chests keep their gaps. A block only counts as solid if it fully supports all six faces.
- **Terrain Padding**: preloads a configurable number of solid layers behind visible terrain (default 2) so fast mining doesn't flash unrendered blocks.
- **Explosions & Falling Blocks**: crystal blasts and gravel/sand columns update nearby observers without canceling running scans.
- **Entity Checks**: sight is checked per entity height, so an Enderman head poking over a wall shows but the same wall still hides a minecart.
- **Dynamic Radius**: scans up to 96 blocks around the player (default 64), only reading loaded chunks.
- **Packet-Level**: no modifications to world files or block data on disk.
- **Debug Mode**: action bar stats via `/antibase debug`, console capture via `/antibase debug on [player]`.

## Installation

1. Drop AntiBase.jar into your `plugins` folder. Requires Paper 1.21.11, Java 21 and PacketEvents 2.11.1 as a separate plugin.
2. Restart the server.
3. Configure `hide-below-y` in the `config.yml` (default is 0).

## Configuration

```yaml
enabled: true
render-mode: connected   # connected (default) or line-of-sight
hide-below-y: 0          # obfuscation starts below this Y level
terrain-padding: 2       # extra solid layers behind visible terrain (0..4)
scan-radius: 64          # scan reach, connected mode rounds up to whole sections
max-scan-blocks: 50000   # line-of-sight mode only
max-ray-steps: 2000000   # line-of-sight mode only
console-debug: false     # periodic console summaries
blacklisted-worlds:
  - world_nether
  - world_the_end
```

Old `proximity-distance` and `replacement-block` settings are ignored (hidden terrain is always AIR now).

## Commands

- `/antibase` or `/antibase status` — current mode and queue info (`antibase.admin`)
- `/antibase enable` / `/antibase disable` — toggle obfuscation (`antibase.admin`)
- `/antibase debug` — toggle action bar stats (`antibase.debug`)
- `/antibase debug on [player]` / `off` / `status` — console capture (`antibase.admin`)

Permissions default to operators.

## Building

`./gradlew clean build` with JDK 21. This compiles the plugin and runs the unit + regression tests. There are also gradle tasks for the cave simulation (`caveSimulation`), the old-method baseline (`caveBaseline`), the visibility benchmark (`visibilityBenchmark`) and an optional real-Paper chest state check (`paperChestVerification`). GitHub Actions builds and tests on Linux and Windows.

---
*Requires Paper 1.21.11 and PacketEvents 2.11.1. Folia is not supported.*
