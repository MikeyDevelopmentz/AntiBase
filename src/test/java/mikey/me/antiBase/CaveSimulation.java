package mikey.me.antiBase;

import com.google.gson.GsonBuilder;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.zip.GZIPOutputStream;

/** integration sim of the real scanner vs an independent geometric oracle */
public final class CaveSimulation {
    private static final int RAYS=4096;
    private static final double GOLDEN_ANGLE=Math.PI*(3-Math.sqrt(5));
    private final CaveSimulationMap map=new CaveSimulationMap();
    private final List<Frame> frames=new ArrayList<>();
    private final List<String> failures=new ArrayList<>();
    private final Path output;
    private final boolean connected;
    private final int terrainPadding;
    private long entryChecks, lateEntryChecks;
    private CaveSimulationMap.Eye previousRouteEye;
    private int worstEntryMisses;
    private int worstMisses=-1;
    private int sealedLeaks;
    private long destroyed;
    private final Set<Long> allMisses=new HashSet<>();

    CaveSimulation(Path output) { this(output, true); }
    CaveSimulation(Path output, boolean connected) { this(output, connected, 2); }
    CaveSimulation(Path output, boolean connected, int terrainPadding) {
        this.output=output; this.connected=connected; this.terrainPadding=terrainPadding;
        if (terrainPadding < 0 || terrainPadding > 4) throw new IllegalArgumentException("Padding must be 0..4");
    }

    public static void main(String[] args) throws Exception {
        Path output=Path.of(args.length==0 ? "build/cave-simulation" : args[0]);
        CaveSimulation simulation=new CaveSimulation(output, args.length < 2 || !args[1].equals("line-of-sight"),
                args.length < 3 ? 2 : Integer.parseInt(args[2]));
        simulation.run();
        if(!simulation.failures.isEmpty()) throw new AssertionError(String.join("; ",simulation.failures));
    }

    void run() throws Exception {
        Files.createDirectories(output);
        writeSchematic(output.resolve("cave-map.schem"));
        // warm up the same core code before recording the route
        for(int i=0;i<6;i++) full(map.route.get(i));
        VisibilitySnapshot previous=VisibilitySnapshot.EMPTY;
        for(int i=0;i<map.route.size();i++) {
            CaveSimulationMap.Eye eye=map.route.get(i);
            long start=System.nanoTime();
            VisibilitySnapshot next=full(eye);
            record("route-"+i,eye,next,previous,System.nanoTime()-start);
            previous=next;
        }
        System.out.println("Route complete: "+map.route.size()+" positions");
        probe("one-block-slit",new CaveSimulationMap.Eye(-57.63,-48.39,-77.57));
        probe("around-corner",new CaveSimulationMap.Eye(-27.63,-48.39,-59.57));
        probe("underwater",new CaveSimulationMap.Eye(48.37,-42.39,25.43));
        probe("sealed-room-exterior",new CaveSimulationMap.Eye(48.37,-17.39,-45.57));
        mining();
        explosions();
        chunkArrival();
        long misses=frames.stream().mapToLong(Frame::missing).sum();
        long witnesses=frames.stream().mapToLong(Frame::reference).sum();
        long caps=frames.stream().filter(Frame::budgetLimited).count();
        if (connected) {
            check(misses == 0, "Connected flood missed independently visible surfaces");
            check(lateEntryChecks == 0, "Connected flood failed to preload the tested room-entry surfaces");
        }
        double[] times=frames.stream().mapToDouble(Frame::scanMs).sorted().toArray();
        Map<String,Object> report=new LinkedHashMap<>();
        report.put("renderMode",connected?"connected":"line-of-sight");
        report.put("terrainPadding",terrainPadding);
        report.put("roomEntrySurfaceChecks",entryChecks);
        report.put("roomEntrySurfacesNotPreloaded",lateEntryChecks);
        report.put("dimensions",List.of(192,64,192));
        report.put("voxels",map.cells.length);
        report.put("chunks",144);
        report.put("frames",frames.size());
        report.put("referenceRaysPerFrame",RAYS);
        report.put("referenceSurfaceChecks",witnesses);
        report.put("missedSurfaceChecks",misses);
        report.put("coveragePercent",100.0*(witnesses-misses)/Math.max(1,witnesses));
        report.put("coverageStatus",misses==0?"no gaps found by the sampled reference":"gaps left, check the missing witnesses");
        report.put("sealedRoomLeaks",sealedLeaks);
        report.put("budgetLimitedFrames",caps);
        report.put("destroyedBlocks",destroyed);
        report.put("scanMedianMs",times[times.length/2]);
        report.put("scanP95Ms",times[(int)Math.floor((times.length-1)*0.95)]);
        report.put("failures",failures);
        report.put("samples",frames);
        Files.writeString(output.resolve("results.json"),new GsonBuilder().setPrettyPrinting().create().toJson(report));
        StringBuilder csv=new StringBuilder("frame,x,y,z,scan_ms,terrain,reference_surfaces,missing_surfaces,delta_records,budget_limited\n");
        for(Frame frame:frames) csv.append(String.format(Locale.ROOT,"%s,%.3f,%.3f,%.3f,%.3f,%d,%d,%d,%d,%s%n",
                frame.name,frame.eye.x(),frame.eye.y(),frame.eye.z(),frame.scanMs,frame.terrain,frame.reference,frame.missing,frame.deltas,frame.budgetLimited));
        Files.writeString(output.resolve("frames.csv"),csv);
        overview(witnesses,misses);
        String reportText=String.format(Locale.ROOT,"# cave simulation results%n%n"
                +"192 x 192 x 64 blocks, %,d voxels, 144 chunks.%n%n"
                +"- %d scan positions: route travel, a slit, a bent tunnel, underwater sight, mining, explosions, chunk arrival.%n"
                +"- %,d independent reference surface checks, %,d missed (%.4f%% matched).%n"
                +"- %d sealed room leaks, %d budget limited frames.%n"
                +"- %,d blocks removed by scripted mining + blasts.%n"
                +"- scan time: %.3f ms median, %.3f ms p95.%n"
                +"- assertions: %s.%n%n"
                +"![cave layout](cave-map.png)%n%n![reference vs scanner](visibility-comparison.png)%n%n"
                +"the reference is its own ray marcher using voxel exit plane intersections, it never touches the production tracer or candidate search. "
                +"each frame casts 4096 directions over a sphere and records the first opaque surface plus any transparent stuff on the way. "
                +"a miss counts even if its a tiny sliver, and 0 misses is still not proof for every possible camera.%n%n"
                +"this runs the real scanner + snapshot delta code on an in-memory world. no Paper, no PacketEvents, no lighting, no client meshing, no physics, no network. "
                +"the route is scripted eye movement, blasts are plain spheres not minecraft explosions. timings skip the reference checks and file rendering.%n%n"
                +"the map is exported as cave-map.schem (sponge v2, DataVersion 3700, older compatible palette). never imported into a live server. "
                +"offset is [-96,-64,-96] from the sim origin, AIR is part of the map so keep it when importing. "
                +"spec: https://github.com/SpongePowered/Schematic-Specification/blob/master/versions/schematic-2.md%n%n"
                +"raw results: [json](results.json), [per frame csv](frames.csv).%n",
                map.cells.length,frames.size(),witnesses,misses,100.0*(witnesses-misses)/Math.max(1,witnesses),sealedLeaks,caps,destroyed,
                times[times.length/2],times[(int)Math.floor((times.length-1)*0.95)],failures.isEmpty()?"passed":String.join("; ",failures));
        reportText += String.format(Locale.ROOT,"%nmode: **%s**. room entry checks: %,d of %,d surfaces were missing from the previous position's snapshot. "
                +"measures terrain preloading over route steps of at most six blocks, initial load and scripted jumps excluded. "
                +"not a network latency or client frame sim.%n",connected?"connected":"line-of-sight",lateEntryChecks,entryChecks);
        reportText += "\nterrain padding: **"+terrainPadding+" extra solid layers**. padding never grows the connected component.\n";
        if (connected) reportText += "\n![Terrain preloaded before room entry: eye rays versus flood](room-entry-comparison.png)\n";
        Files.writeString(output.resolve("REPORT.md"),reportText);
        System.out.printf(Locale.ROOT,"SIMULATION: frames=%d witnesses=%d missing=%d coverage=%.4f%% sealedLeaks=%d budgetCaps=%d medianMs=%.3f p95Ms=%.3f%n",
                frames.size(),witnesses,misses,100.0*(witnesses-misses)/Math.max(1,witnesses),sealedLeaks,caps,times[times.length/2],times[(int)Math.floor((times.length-1)*.95)]);
        System.out.println("Structural assertions: "+(failures.isEmpty()?"PASS":failures));
        System.out.println("Room entry: "+lateEntryChecks+" / "+entryChecks+" surfaces not preloaded; mode="+(connected?"connected":"line-of-sight"));
    }

    private VisibilitySnapshot full(CaveSimulationMap.Eye eye) {
        return scan(map,eye);
    }
    private VisibilitySnapshot scan(VisibilityScanner.BlockAccess source,CaveSimulationMap.Eye eye) {
        VisibilitySnapshot visibility = connected ? ConnectedVisibilityScanner.scan(source,eye.x(),eye.y(),eye.z(),-64,0,0,64)
                : VisibilityScanner.scan(source,eye.x(),eye.y(),eye.z(),-64,0,0,64,50000,2000000);
        return pad(source, visibility, eye, Integer.MAX_VALUE);
    }
    private VisibilitySnapshot pad(VisibilityScanner.BlockAccess source, VisibilitySnapshot visibility,
                                   CaveSimulationMap.Eye eye, int reads) {
        return TerrainPadding.add(source,visibility,
                ConnectedVisibilityScanner.Window.around(eye.x(),eye.y(),eye.z(),-64,0,64),0,terrainPadding,reads);
    }
    private void probe(String name,CaveSimulationMap.Eye eye) throws IOException {
        long start=System.nanoTime();
        VisibilitySnapshot snapshot=full(eye);
        record(name,eye,snapshot,VisibilitySnapshot.EMPTY,System.nanoTime()-start);
    }
    private void mining() throws IOException {
        for(int x=60;x<68;x++) {
            CaveSimulationMap.Eye eye=new CaveSimulationMap.Eye(x-2.63,-47.39,-75.57);
            VisibilitySnapshot before=full(eye);
            check(map.blockAt(x,-48,-76)==1,"Mining target must initially be solid");
            if (terrainPadding >= 1) check(before.isTerrainVisible(x+1,-48,-76),"Mining buffer must arrive before breaking at "+x);
            map.set(x,-48,-76,0); map.set(x,-47,-76,0);
            destroyed+=2;
            long start=System.nanoTime();
            VisibilitySnapshot after=full(eye);
            record("mine-"+(x-60),eye,after,before,System.nanoTime()-start);
            check(after.isTerrainVisible(x+1,-48,-76),"Mining must reveal the new wall at "+(x+1));
            LongHashSet deltas=new LongHashSet(32);
            after.forEachChangedBlock(before,deltas::add);
            check(deltas.contains(Coordinates.block(x,-48,-76)),"Mining must clear the removed block at "+x);
        }
    }
    private void explosions() throws IOException {
        CaveSimulationMap.Eye[] observers={new CaveSimulationMap.Eye(.37,-38.39,.43),
                new CaveSimulationMap.Eye(-17.63,-32.39,-2.57),new CaveSimulationMap.Eye(17.37,-30.39,-8.57)};
        VisibilitySnapshot[] snapshots=Arrays.stream(observers).map(this::full).toArray(VisibilitySnapshot[]::new);
        for(int blast=0;blast<5;blast++) {
            LongHashSet changed=map.blast(4+blast*3,-44,0,5);
            destroyed+=changed.size();
            check(changed.size()>0,"Each blast must destroy terrain");
            for(int i=0;i<observers.length;i++) {
                var eye=observers[i];
                long start=System.nanoTime();
                VisibilitySnapshot patch=connected ? ConnectedVisibilityScanner.expandChanges(map,eye.x(),eye.y(),eye.z(),-64,0,0,64,
                        snapshots[i],changed,32768) : VisibilityScanner.scanChangedBlocks(map,eye.x(),eye.y(),eye.z(),-64,0,0,64,4096,200000,changed);
                patch=pad(map,patch,eye,32768);
                VisibilitySnapshot next=snapshots[i].withRevealed(patch);
                record("blast-"+blast+"-observer-"+i,eye,next,snapshots[i],System.nanoTime()-start);
                snapshots[i]=next;
            }
        }
    }
    private void chunkArrival() throws IOException {
        var eye=new CaveSimulationMap.Eye(14.37,-26.39,.43);
        VisibilitySnapshot partial=scan((x,y,z)-> (x>>4)==1 ? -1 : map.blockAt(x,y,z),eye);
        partial.forEachBlock(key->check(Coordinates.blockX(key)>>4!=1,"Missing chunk must not be revealed"));
        long start=System.nanoTime();
        VisibilitySnapshot complete=full(eye);
        record("chunk-arrival",eye,complete,partial,System.nanoTime()-start);
        check(complete.terrainCount()>partial.terrainCount(),"Arriving chunk must add terrain");
    }
    private void check(boolean condition,String message) { if(!condition && !failures.contains(message)) failures.add(message); }

    private void record(String name,CaveSimulationMap.Eye eye,VisibilitySnapshot snapshot,VisibilitySnapshot previous,long nanos) throws IOException {
        check(map.blockAt((int)Math.floor(eye.x()),(int)Math.floor(eye.y()),(int)Math.floor(eye.z()))!=1,"Observer embedded in wall: "+name);
        Set<Long> expected=new HashSet<>();
        for(int i=0;i<RAYS;i++) {
            double dy=1-2*(i+.5)/RAYS, r=Math.sqrt(1-dy*dy), angle=i*GOLDEN_ANGLE;
            reference(eye,Math.cos(angle)*r,dy,Math.sin(angle)*r,expected);
        }
        List<Long> missing=new ArrayList<>();
        for(long key:expected) if(!snapshot.isTerrainVisible(Coordinates.blockX(key),Coordinates.blockY(key),Coordinates.blockZ(key))) missing.add(key);
        if (name.startsWith("route-")) {
            if (previousRouteEye != null && Math.pow(eye.x()-previousRouteEye.x(),2)+Math.pow(eye.y()-previousRouteEye.y(),2)
                    +Math.pow(eye.z()-previousRouteEye.z(),2) <= 36) {
                entryChecks += expected.size();
                for (long key:expected) if (!previous.isTerrainVisible(Coordinates.blockX(key),Coordinates.blockY(key),Coordinates.blockZ(key))) lateEntryChecks++;
                if (connected) {
                    var p = previousRouteEye;
                    VisibilitySnapshot old = VisibilityScanner.scan(map,p.x(),p.y(),p.z(),-64,0,0,64,50000,2000000);
                    int oldMisses = (int) expected.stream().filter(key -> !old.isTerrainVisible(
                            Coordinates.blockX(key),Coordinates.blockY(key),Coordinates.blockZ(key))).count();
                    if (oldMisses > worstEntryMisses) {
                        worstEntryMisses = oldMisses;
                        comparison(name+" / terrain available BEFORE this step",eye,previous,old,"room-entry-comparison.png");
                    }
                }
            }
            previousRouteEye = eye;
        }
        allMisses.addAll(missing);
        int[] deltas={0};
        snapshot.forEachChangedBlock(previous,key->deltas[0]++);
        snapshot.forEachBlock(key->{
            int x=Coordinates.blockX(key),y=Coordinates.blockY(key),z=Coordinates.blockZ(key);
            if(x>=54 && x<=66 && y>=-44 && y<=-36 && z>=-66 && z<=-54) sealedLeaks++;
        });
        check(sealedLeaks==0,"Sealed room leaked");
        List<List<Integer>> examples=missing.stream().limit(12).map(key->List.of(Coordinates.blockX(key),Coordinates.blockY(key),Coordinates.blockZ(key))).toList();
        frames.add(new Frame(name,eye,nanos/1_000_000.0,snapshot.terrainCount(),expected.size(),missing.size(),deltas[0],snapshot.budgetLimited(),examples));
        if(missing.size()>worstMisses) {
            worstMisses=missing.size();
            comparison(name,eye,snapshot);
        }
    }

    /** independent reference. computes the exit plane of every box it walks, no scanner helpers */
    int[] reference(CaveSimulationMap.Eye eye,double dx,double dy,double dz,Set<Long> seen) {
        double t=0;
        int[] last=null;
        while(t<63) {
            int x=(int)Math.floor(eye.x()+dx*t),y=(int)Math.floor(eye.y()+dy*t),z=(int)Math.floor(eye.z()+dz*t);
            int type=map.blockAt(x,y,z);
            if(type<0) return last;
            if(type>0) {
                if(seen!=null) seen.add(Coordinates.block(x,y,z));
                last=new int[]{x,y,z,map.material(x,y,z)};
                if(type==1) return last;
            }
            double tx=dx==0?Double.POSITIVE_INFINITY:((dx>0?x+1:x)-eye.x())/dx;
            double ty=dy==0?Double.POSITIVE_INFINITY:((dy>0?y+1:y)-eye.y())/dy;
            double tz=dz==0?Double.POSITIVE_INFINITY:((dz>0?z+1:z)-eye.z())/dz;
            double next=Math.min(tx,Math.min(ty,tz))+1e-7;
            if(next<=t) throw new IllegalStateException("Reference did not advance");
            t=next;
        }
        return last;
    }

    private void overview(long witnesses,long misses) throws IOException {
        BufferedImage image=new BufferedImage(1160,900,BufferedImage.TYPE_INT_RGB);
        Graphics2D g=image.createGraphics();
        g.setColor(new Color(0x0c1623));g.fillRect(0,0,1160,900);
        g.setFont(new Font("SansSerif",Font.BOLD,27));g.setColor(Color.WHITE);g.drawString("ANTIBASE / CAVE SIMULATION",36,46);
        g.setFont(new Font("SansSerif",Font.PLAIN,15));g.setColor(new Color(0xa7b9cc));
        g.drawString("192 x 192 x 64 blocks  /  144 chunks  /  top-down cave projection",36,74);
        int scale=4,ox=36,oz=103;
        for(int z=-96;z<96;z++) for(int x=-96;x<96;x++) {
            int floor=-65,material=0;
            for(int y=-63;y<0;y++) if(map.blockAt(x,y,z)==0 || map.material(x,y,z)==3) {floor=y;material=map.material(x,y,z);break;}
            int color=floor==-65?0x1a2a3b:material==3?0x2674bb:Color.HSBtoRGB(.46f,.5f,.40f+(floor+64)/110f);
            g.setColor(new Color(color));g.fillRect(ox+(x+96)*scale,oz+(z+96)*scale,scale,scale);
        }
        g.setColor(new Color(255,255,255,16));
        for(int i=0;i<=192;i+=16) {g.drawLine(ox+i*scale,oz,ox+i*scale,oz+768);g.drawLine(ox,oz+i*scale,ox+768,oz+i*scale);}
        g.setColor(new Color(0xffd477));g.setStroke(new BasicStroke(2));
        CaveSimulationMap.Eye old=null;
        for(var eye:map.route) {
            if(old!=null && Math.hypot(old.x()-eye.x(),old.z()-eye.z())<12)
                g.drawLine(ox+(int)((old.x()+96)*scale),oz+(int)((old.z()+96)*scale),ox+(int)((eye.x()+96)*scale),oz+(int)((eye.z()+96)*scale));
            old=eye;
        }
        g.setColor(new Color(0xff4d9d));
        for(long key:allMisses) g.fillRect(ox+(Coordinates.blockX(key)+96)*scale,oz+(Coordinates.blockZ(key)+96)*scale,3,3);
        int tx=834,ty=116;
        g.setColor(Color.WHITE);g.setFont(new Font("SansSerif",Font.BOLD,19));g.drawString("TEST WORLD",tx,ty);
        g.setFont(new Font("SansSerif",Font.PLAIN,15));
        int roomNumber=0;
        for(var room:map.rooms) {
            roomNumber++;
            ty+=29;g.setColor(new Color(0xc5d4e6));g.drawString(roomNumber+". "+room.name(),tx,ty);
            int px=ox+(room.x()+96)*scale,pz=oz+(room.z()+96)*scale;
            g.setColor(new Color(0x102436));g.fillOval(px-12,pz-12,24,24);
            g.setColor(Color.WHITE);g.drawString(Integer.toString(roomNumber),px-5,pz+5);
        }
        ty+=29;g.setColor(new Color(0xc5d4e6));g.drawString("S. Sealed base",tx,ty);
        g.setColor(new Color(0xedbfff));g.drawString("S",ox+156*scale-5,oz+36*scale+5);
        ty+=42;g.setColor(new Color(0xffd477));g.drawString("Gold: scripted observer route",tx,ty);
        ty+=28;g.setColor(new Color(0xff4d9d));g.drawString("Pink: missed sight witnesses",tx,ty);
        ty+=28;g.setColor(new Color(0x59a8ed));g.drawString("Blue: flooded passages",tx,ty);
        ty+=52;g.setFont(new Font("SansSerif",Font.BOLD,19));g.setColor(Color.WHITE);g.drawString(frames.size()+" SCAN FRAMES",tx,ty);
        ty+=31;g.setFont(new Font("SansSerif",Font.PLAIN,15));g.drawString(String.format(Locale.ROOT,"%,d reference checks",witnesses),tx,ty);
        ty+=28;g.drawString(String.format(Locale.ROOT,"%,d missed checks",misses),tx,ty);
        ty+=28;g.drawString(sealedLeaks+" sealed-room leaks",tx,ty);
        ty+=45;g.setColor(new Color(0xa7b9cc));g.drawString("Synthetic scanner test.",tx,ty);
        ty+=23;g.drawString("Not a live Minecraft render.",tx,ty);
        g.dispose();ImageIO.write(image,"png",output.resolve("cave-map.png").toFile());
    }

    private void comparison(String name,CaveSimulationMap.Eye eye,VisibilitySnapshot snapshot) throws IOException {
        comparison(name,eye,snapshot,null,"visibility-comparison.png");
    }
    private void comparison(String name,CaveSimulationMap.Eye eye,VisibilitySnapshot snapshot,VisibilitySnapshot old,String file) throws IOException {
        int width=192,height=108,scale=3;
        BufferedImage image=new BufferedImage(width*scale*2+48,height*scale+108,BufferedImage.TYPE_INT_RGB);
        Graphics2D g=image.createGraphics();g.setColor(new Color(0x0c1623));g.fillRect(0,0,image.getWidth(),image.getHeight());
        g.setColor(Color.WHITE);g.setFont(new Font("SansSerif",Font.BOLD,20));
        g.drawString(old==null?"Independent reference":"Before / eye-ray rendering",16,31);
        g.drawString(old==null?"AntiBase / missing surfaces in pink":"After / connected cave flood",width*scale+32,31);
        // a 360 view shows all directions, not just the current camera
        for(int py=0;py<height;py++) for(int px=0;px<width;px++) {
            double pitch=(.5-(py+.5)/height)*Math.PI,yaw=(px+.5)/width*Math.PI*2;
            double dx=Math.cos(pitch)*Math.cos(yaw),dy=Math.sin(pitch),dz=Math.cos(pitch)*Math.sin(yaw);
            Set<Long> pixelCells=new HashSet<>();
            int[] hit=reference(eye,dx,dy,dz,pixelCells);
            int color=0x162437;
            boolean shown=true;
            if(hit!=null) {
                double distance=Math.sqrt(Math.pow(hit[0]+.5-eye.x(),2)+Math.pow(hit[1]+.5-eye.y(),2)+Math.pow(hit[2]+.5-eye.z(),2));
                float light=(float)Math.max(.22,1-distance/80);
                int base=switch(hit[3]) {case 3->0x397ac2;case 4->0x80d7df;case 5->0x44385f;case 6->0xecc65e;default->((hit[0]+hit[1]+hit[2])&1)==0?0x89959e:0x717c89;};
                Color c=new Color(base);color=new Color((int)(c.getRed()*light),(int)(c.getGreen()*light),(int)(c.getBlue()*light)).getRGB();
                shown=pixelCells.stream().allMatch(key->snapshot.isTerrainVisible(Coordinates.blockX(key),Coordinates.blockY(key),Coordinates.blockZ(key)));
            }
            boolean oldShown = old == null || pixelCells.stream().allMatch(key->old.isTerrainVisible(
                    Coordinates.blockX(key),Coordinates.blockY(key),Coordinates.blockZ(key)));
            g.setColor(new Color(oldShown?color:0xff3b94));g.fillRect(16+px*scale,48+py*scale,scale,scale);
            g.setColor(new Color(shown?color:0xff3b94));g.fillRect(width*scale+32+px*scale,48+py*scale,scale,scale);
        }
        g.setColor(new Color(0xb5c6d9));g.setFont(new Font("SansSerif",Font.PLAIN,14));
        g.drawString(name+" / 360-degree projection / pink marks gaps; this is not Minecraft client footage.",16,height*scale+80);
        g.dispose();ImageIO.write(image,"png",output.resolve(file).toFile());
    }

    void writeSchematic(Path path) throws IOException {
        try(DataOutputStream out=new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(path)))) {
            tag(out,10,"Schematic");
            integer(out,"Version",2); integer(out,"DataVersion",3700);
            tag(out,2,"Width");out.writeShort(192);tag(out,2,"Height");out.writeShort(64);tag(out,2,"Length");out.writeShort(192);
            tag(out,11,"Offset");out.writeInt(3);out.writeInt(-96);out.writeInt(-64);out.writeInt(-96);
            integer(out,"PaletteMax",CaveSimulationMap.PALETTE.length);
            tag(out,10,"Palette");for(int i=0;i<CaveSimulationMap.PALETTE.length;i++) integer(out,CaveSimulationMap.PALETTE[i],i);out.writeByte(0);
            tag(out,7,"BlockData");out.writeInt(map.cells.length);out.write(map.cells); // all 8 palette indices fit a one byte varint
            tag(out,9,"BlockEntities");out.writeByte(10);out.writeInt(0);
            tag(out,10,"Metadata");tag(out,8,"Name");out.writeUTF("AntiBase deterministic cave simulation");out.writeByte(0);
            out.writeByte(0);
        }
    }
    private static void tag(DataOutputStream out,int type,String name) throws IOException {out.writeByte(type);out.writeUTF(name);}
    private static void integer(DataOutputStream out,String name,int value) throws IOException {tag(out,3,name);out.writeInt(value);}
    record Frame(String name,CaveSimulationMap.Eye eye,double scanMs,int terrain,int reference,int missing,int deltas,boolean budgetLimited,List<List<Integer>> examples) {}
}
