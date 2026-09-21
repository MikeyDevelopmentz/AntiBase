package mikey.me.antiBase;

import com.github.retrooper.packetevents.protocol.nbt.*;
import com.github.retrooper.packetevents.protocol.nbt.serializer.DefaultNBTSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.DataInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.zip.GZIPInputStream;
import static org.junit.jupiter.api.Assertions.*;

class CaveSimulationTest {
    @TempDir Path temporary;

    @Test
    void mapIsValid() {
        CaveSimulationMap map=new CaveSimulationMap();
        assertEquals(2359296,map.cells.length);
        assertArrayEquals(map.cells,new CaveSimulationMap().cells);
        assertEquals(109,map.route.size());
        for(var eye:map.route) assertNotEquals(1,map.blockAt((int)Math.floor(eye.x()),(int)Math.floor(eye.y()),(int)Math.floor(eye.z())));
        assertEquals(3,map.material(48,-42,25));
        assertEquals(4,map.material(22,-32,10));
        assertEquals(6,map.material(60,-44,-60));
        assertEquals(5,map.material(53,-40,-60));
        assertEquals(0,map.material(-48,-49,-78));
        assertEquals(1,map.blockAt(-48,-48,-78));
    }

    @Test
    void narrowApproachesRevealDistantWalls() {
        CaveSimulationMap map=new CaveSimulationMap();
        var west=scan(map,map.route.get(81));
        for(int[] cell:new int[][]{{-64,-26,46},{-63,-22,45},{-60,-27,47}})
            assertTrue(west.isTerrainVisible(cell[0],cell[1],cell[2]),"missed wall behind the west entrance");
        var deep=scan(map,map.route.get(48));
        for(int[] cell:new int[][]{{-46,-49,54},{-44,-48,61},{-42,-54,60}})
            assertTrue(deep.isTerrainVisible(cell[0],cell[1],cell[2]),"missed wall behind the deep entrance");
        var upper=scan(map,new CaveSimulationMap.Eye(48.37,-17.39,-45.57));
        for(int y=-44;y<=-36;y++) for(int z=-66;z<=-54;z++) for(int x=54;x<=66;x++)
            assertFalse(upper.isBlockVisible(x,y,z),"sealed base leaked");
    }

    @Test
    void referenceFindsGalleryWall() {
        var simulation=new CaveSimulation(temporary);
        var eye=new CaveSimulationMap.Eye(56.37,-47.39,-75.57);
        int[] hit=simulation.reference(eye,1,0,0,new HashSet<>());
        assertArrayEquals(new int[]{60,-48,-76,2},hit);
    }

    @Test
    void schematicRoundTrips() throws Exception {
        var file=temporary.resolve("cave.schem");
        new CaveSimulation(temporary).writeSchematic(file);
        try(var input=new DataInputStream(new GZIPInputStream(Files.newInputStream(file)))) {
            NBTCompound root=(NBTCompound)DefaultNBTSerializer.INSTANCE.deserializeTag(NBTLimiter.noop(),input);
            assertEquals(2,root.getNumberTagOrThrow("Version").getAsInt());
            assertEquals(3700,root.getNumberTagOrThrow("DataVersion").getAsInt());
            assertEquals(192,root.getNumberTagOrThrow("Width").getAsInt());
            assertEquals(64,root.getNumberTagOrThrow("Height").getAsInt());
            assertEquals(192,root.getNumberTagOrThrow("Length").getAsInt());
            assertArrayEquals(new int[]{-96,-64,-96},root.getTagOfTypeOrThrow("Offset",NBTIntArray.class).getValue());
            byte[] data=root.getTagOfTypeOrThrow("BlockData",NBTByteArray.class).getValue();
            assertArrayEquals(new CaveSimulationMap().cells,data);
            NBTCompound palette=root.getCompoundTagOrThrow("Palette");
            assertEquals(8,palette.getTagNames().size());
            assertEquals(4,palette.getNumberTagOrThrow("minecraft:glass").getAsInt());
            assertEquals(-1,input.read(),"no trailing nbt data");
        }
    }

    private VisibilitySnapshot scan(CaveSimulationMap map,CaveSimulationMap.Eye eye) {
        return VisibilityScanner.scan(map,eye.x(),eye.y(),eye.z(),-64,0,0,64,50000,2000000);
    }
}
