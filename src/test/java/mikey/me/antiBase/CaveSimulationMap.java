package mikey.me.antiBase;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** deterministic editable multi chunk test world, no bukkit or worldgen involved */
final class CaveSimulationMap implements VisibilityScanner.BlockAccess {
    static final int SIZE = 192, HEIGHT = 64, MIN = -96, MIN_Y = -64;
    static final String[] PALETTE = {"minecraft:air", "minecraft:stone", "minecraft:deepslate[axis=y]",
            "minecraft:water[level=0]", "minecraft:glass", "minecraft:obsidian", "minecraft:gold_block", "minecraft:bedrock"};
    final byte[] cells = new byte[SIZE * SIZE * HEIGHT];
    final List<Room> rooms = List.of(
            new Room("Entry cavern", -48, -30, -40, 24, 14, 22),
            new Room("Grand cavern", 0, -27, 0, 29, 20, 27),
            new Room("Flooded cavern", 50, -36, 25, 20, 12, 22),
            new Room("Deep chamber", -28, -49, 55, 17, 9, 18),
            new Room("Upper chamber", 48, -17, -46, 16, 10, 17),
            new Room("West branch", -58, -27, 30, 19, 11, 17));
    final List<Eye> route = new ArrayList<>();

    CaveSimulationMap() {
        for (int y = MIN_Y; y < 0; y++) Arrays.fill(cells, (y - MIN_Y) * SIZE * SIZE,
                (y - MIN_Y + 1) * SIZE * SIZE, (byte) (y == MIN_Y ? 7 : y < -32 ? 2 : 1));
        for (Room room : rooms) cavern(room);
        passage(new double[][]{{-48,-30,-40},{-27,-33,-29},{-17,-30,-10},{0,-27,0}}, 3.1, true);
        passage(new double[][]{{0,-27,0},{23,-32,10},{35,-35,25},{50,-36,25}}, 2.6, true);
        passage(new double[][]{{50,-36,25},{26,-42,42},{0,-46,45},{-28,-49,55}}, 2.6, true);
        passage(new double[][]{{-28,-49,55},{-47,-38,48},{-58,-27,30},{-53,-29,0},{-48,-30,-40}}, 2.8, true);
        passage(new double[][]{{0,-27,0},{15,-25,-20},{34,-20,-34},{48,-17,-46}}, 2.4, true);
        passage(new double[][]{{-48,-30,-40},{-70,-49,-60},{-70,-49,-78}}, 2.4, false);
        box(-72,-51,-80,-25,-47,-76,0);
        box(-29,-51,-78,-25,-47,-55,0);
        box(-48,-51,-80,-48,-47,-76,1);
        set(-48,-49,-78,0); // one cell slit then a bent corridor
        passage(new double[][]{{48,-17,-46},{42,-32,-64},{42,-48,-76}}, 2.5, false);
        box(40,-50,-78,59,-46,-74,0); // mining gallery, solid rock starts at x=60
        for (int y=-46; y<=-41; y++) for (int z=10; z<=40; z++) for (int x=37; x<=63; x++) {
            if (material(x,y,z)==0) set(x,y,z,3);
        }
        // pillars + hanging ledges make narrow partly occluded views
        box(6,-46,6,8,-8,8,2);
        box(-13,-45,8,-11,-10,10,2);
        box(-8,-15,-10,14,-13,-8,1);
        for (int x=-6; x<=6; x+=4) box(x,-22,14,x+1,-11,15,2);
        box(22,-34,8,22,-30,12,4); // glass across a connecting tunnel
        // sealed base in its own obsidian shell, within sight radius of the upper chamber
        box(53,-45,-67,67,-35,-53,5);
        box(54,-44,-66,66,-36,-54,0);
        box(59,-44,-61,61,-44,-59,6);
        for (int i=0; i<route.size(); i++) {
            Eye eye=route.get(i);
            if (blockAt((int)Math.floor(eye.x),(int)Math.floor(eye.y),(int)Math.floor(eye.z))==1)
                throw new IllegalStateException("Route entered a wall at "+eye);
        }
    }

    int material(int x,int y,int z) {
        if (x<MIN || x>=MIN+SIZE || z<MIN || z>=MIN+SIZE || y<MIN_Y || y>=0) return -1;
        return cells[(y-MIN_Y)*SIZE*SIZE+(z-MIN)*SIZE+(x-MIN)];
    }
    @Override public int blockAt(int x,int y,int z) {
        return switch(material(x,y,z)) { case -1 -> -1; case 0 -> 0; case 3,4 -> 2; default -> 1; };
    }
    void set(int x,int y,int z,int material) {
        if (x<MIN || x>=MIN+SIZE || z<MIN || z>=MIN+SIZE || y<=MIN_Y || y>=0) return;
        cells[(y-MIN_Y)*SIZE*SIZE+(z-MIN)*SIZE+(x-MIN)]=(byte)material;
    }
    void box(int x0,int y0,int z0,int x1,int y1,int z1,int material) {
        for(int y=y0;y<=y1;y++) for(int z=z0;z<=z1;z++) for(int x=x0;x<=x1;x++) set(x,y,z,material);
    }
    LongHashSet blast(int cx,int cy,int cz,int radius) {
        LongHashSet changed=new LongHashSet(1024);
        for(int y=cy-radius;y<=cy+radius;y++) for(int z=cz-radius;z<=cz+radius;z++) for(int x=cx-radius;x<=cx+radius;x++) {
            if ((x-cx)*(x-cx)+(y-cy)*(y-cy)+(z-cz)*(z-cz)>radius*radius) continue;
            int material=material(x,y,z);
            if (material==1 || material==2 || material==4) {
                set(x,y,z,0);
                changed.add(Coordinates.block(x,y,z));
            }
        }
        return changed;
    }
    private void cavern(Room room) {
        for(int y=room.y-room.ry-2;y<=room.y+room.ry+2;y++)
            for(int z=room.z-room.rz-2;z<=room.z+room.rz+2;z++)
                for(int x=room.x-room.rx-2;x<=room.x+room.rx+2;x++) {
                    double dx=(x-room.x)/(double)room.rx, dy=(y-room.y)/(double)room.ry, dz=(z-room.z)/(double)room.rz;
                    double roughness=0.035*Math.sin(x*0.71)*Math.cos(z*0.43)+0.02*Math.sin(y*0.83+z*0.19);
                    if(dx*dx+dy*dy+dz*dz<1+roughness) set(x,y,z,0);
                }
    }
    private void passage(double[][] points,double radius,boolean track) {
        for(int segment=0;segment<points.length-1;segment++) {
            double[] a=points[segment], b=points[segment+1];
            double length=Math.sqrt(sq(b[0]-a[0])+sq(b[1]-a[1])+sq(b[2]-a[2]));
            int steps=(int)Math.ceil(length*2);
            for(int step=0;step<=steps;step++) {
                double t=step/(double)steps, x=a[0]+(b[0]-a[0])*t, y=a[1]+(b[1]-a[1])*t, z=a[2]+(b[2]-a[2])*t;
                int r=(int)Math.ceil(radius);
                for(int by=(int)y-r;by<=(int)y+r;by++) for(int bz=(int)z-r;bz<=(int)z+r;bz++) for(int bx=(int)x-r;bx<=(int)x+r;bx++) {
                    if(sq(bx-x)+sq(by-y)+sq(bz-z)<radius*radius) set(bx,by,bz,0);
                }
                if(track && step%8==0) route.add(new Eye(x+0.37,y+0.61,z+0.43));
            }
        }
    }
    private static double sq(double value) { return value*value; }
    record Room(String name,int x,int y,int z,int rx,int ry,int rz) {}
    record Eye(double x,double y,double z) {}
}
