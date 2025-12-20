# AntiBase

AntiBase is a Minecraft Paper plugin designed to completely hide underground bases, caves, and tunnels from X-ray users and freecam abusers. It works by filling the underground world with a replacement block (e.g., Stone) in packets sent to the client, effectively making the entire underground area appear as solid terrain to anyone using X-ray or Freecam.

## Features

- **Cave & Base Hiding**: Automatically fills caves, tunnels, and player bases with a replacement block (e.g., Stone) when the player is not nearby. This prevents players from seeing void spaces or structures underground.
- **Entity Hiding**: Hides entities (players, mobs) that are underground and far away, or not in the player's line of sight.
- **Proximity System**: Only obfuscates the world when the player is beyond a configurable distance, ensuring legitimate gameplay is unaffected while hiding distant secrets.
- **Packet-Based**: Uses ProtocolLib to modify packets, ensuring the server-side world remains unchanged while the client sees a solid underground.
- **Highly Configurable**: Customize the Y-level threshold, proximity distance, and the replacement block.

## Requirements

- **Java**: Java 21 or higher.
- **Server Software**: Paper 1.21 (or compatible fork).
- **Dependencies**: [ProtocolLib](https://www.spigotmc.org/resources/protocollib.1997/) (Build for 1.21).

## Installation

1. Download the latest release of AntiBase.
2. Ensure you have **ProtocolLib** installed on your server.
3. Place the `AntiBase.jar` file into your server's `plugins` folder.
4. Restart your server.

## Configuration

The configuration file `config.yml` allows you to tweak the plugin's behavior:

```yaml
enabled: true
hide-below-y: 60          # Blocks below this Y level will be checked for obfuscation
proximity-distance: 16    # Distance (in blocks) required to trigger obfuscation
replacement-block: STONE  # The block that hidden blocks will appear as
```

## Building from Source

To build AntiBase from source, you need JDK 21 installed.

1. Clone the repository.
2. Open a terminal in the project directory.
3. Run the build command:

   **Linux/macOS:**
   ```bash
   ./gradlew build
   ```

   **Windows:**
   ```cmd
   gradlew build
   ```

4. The compiled JAR file will be located in `build/libs/`.

## How it Works

AntiBase listens for outgoing packets related to block changes, chunk map data, and entity spawning.
- When a chunk is sent to a player, the plugin checks if the player is far enough away. If so, it modifies the chunk data to replace "hidden blocks" with the "replacement block".
- When entities are spawned or updated, the plugin checks if they are underground and obscured. If so, it prevents the spawn packet or sends a destroy entity packet to hide them.
