package mikey.me.antiBase;

import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockSupport;
import org.bukkit.block.data.BlockData;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/** opt in check against a real patched paper runtime, no server boot needed */
public final class PaperChestVerification {
    private static final List<BlockFace> FACES = List.of(BlockFace.DOWN, BlockFace.UP, BlockFace.NORTH,
            BlockFace.SOUTH, BlockFace.WEST, BlockFace.EAST);
    private static final Set<String> CHESTS = Set.of("chest", "trapped_chest", "ender_chest", "copper_chest",
            "exposed_copper_chest", "weathered_copper_chest", "oxidized_copper_chest", "waxed_copper_chest",
            "waxed_exposed_copper_chest", "waxed_weathered_copper_chest", "waxed_oxidized_copper_chest");

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Supply an output TSV path");
        String version = Class.forName("net.minecraft.server.Bootstrap").getPackage().getImplementationVersion();
        if (version == null || !version.startsWith("1.21.11-")) {
            throw new IllegalArgumentException("This state matrix targets Paper 1.21.11; received " + version);
        }
        Class.forName("net.minecraft.SharedConstants").getMethod("tryDetectVersion").invoke(null);
        Class.forName("net.minecraft.server.Bootstrap").getMethod("bootStrap").invoke(null);
        Object registry = Class.forName("net.minecraft.world.level.block.Block").getField("BLOCK_STATE_REGISTRY").get(null);
        var cases = new TreeMap<String, BlockData>();
        BlockData air = null, stone = null;
        for (Object state : (Iterable<?>) registry) {
            String key = state.toString();
            // registry keys look like Block{minecraft:name}[props]. filter before making bukkit data
            if (!key.contains("chest") && !key.equals("Block{minecraft:air}")
                    && !key.equals("Block{minecraft:stone}") && !key.startsWith("Block{minecraft:barrel}")) continue;
            BlockData data = (BlockData) state.getClass().getMethod("createCraftBlockData").invoke(state);
            String name = data.getAsString();
            if (name.equals("minecraft:air")) air = data;
            else if (name.equals("minecraft:stone")) stone = data;
            else cases.put(name, data);
        }
        if (air == null || stone == null) throw new AssertionError("Missing real AIR/STONE controls");
        var counts = new TreeMap<String, Integer>();
        var lines = new ArrayList<String>();
        lines.add("# Captured from actual Paper runtime " + version + " by PaperChestVerification");
        lines.add("# state\tcanOcclude\tfullFaces\texpectedClassification");
        for (var entry : cases.entrySet()) {
            String name = entry.getKey();
            String material = name.substring("minecraft:".length(), name.indexOf('['));
            int expected = CHESTS.contains(material) ? 2 : 1; // barrel is the full container control
            BlockData data = entry.getValue();
            if (BlockOcclusion.classify(data) != expected) throw new AssertionError("Wrong classification: " + name);
            checkRoom(name, data, air, stone, expected == 2);
            counts.merge(material, 1, Integer::sum);
            String faces = FACES.stream().filter(face -> data.isFaceSturdy(face, BlockSupport.FULL))
                    .map(Enum::name).collect(Collectors.joining(","));
            lines.add(name + "\t" + data.isOccluding() + "\t" + faces + "\t" + expected);
        }
        for (String chest : CHESTS) {
            int expectedStates = chest.equals("ender_chest") ? 8 : 24;
            if (counts.getOrDefault(chest, 0) != expectedStates) throw new AssertionError("Incomplete states for " + chest);
        }
        if (counts.getOrDefault("barrel", 0) != 12) throw new AssertionError("Missing open/closed barrel controls");
        Path output = Path.of(args[0]);
        Files.createDirectories(output.toAbsolutePath().getParent());
        Files.write(output, lines);
        System.out.println("PASS: 248 real chest states and 12 barrel controls; both terrain scanners; sealed rock stays hidden.");
        System.out.println("No Minecraft server, client, networking, or lid animation was started.");
    }

    static void checkRoom(String label, BlockData opening, BlockData air, BlockData stone, boolean open) {
        VisibilityScanner.BlockAccess map = (x, y, z) -> BlockOcclusion.classify(
                x == 1 && y == -10 && z == 0 ? opening
                        : y == -10 && z == 0 && (x == 0 || (x >= 2 && x <= 6)) ? air : stone);
        VisibilitySnapshot connected = ConnectedVisibilityScanner.scan(map, .5, -9.5, .5, -32, 0, 0, 16);
        VisibilitySnapshot strict = VisibilityScanner.scan(map, .5, -9.5, .5, -32, 0, 0, 16, 50000, 2000000);
        if (!connected.isTerrainVisible(1, -10, 0) || !strict.isTerrainVisible(1, -10, 0)
                || connected.isConnected(6, -10, 0) != open
                || connected.isTerrainVisible(7, -10, 0) != open
                || strict.isTerrainVisible(7, -10, 0) != open
                || connected.isBlockVisible(8, -10, 0) || strict.isBlockVisible(8, -10, 0)) {
            throw new AssertionError("Chest-gap or sealed-wall regression: " + label);
        }
    }
}
