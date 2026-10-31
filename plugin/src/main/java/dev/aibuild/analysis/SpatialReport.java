package dev.aibuild.analysis;

import dev.aibuild.model.BlockInfo;
import dev.aibuild.model.BlockSpec;
import dev.aibuild.model.Dir;
import dev.aibuild.model.VoxelModel;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Computed spatial evidence for a text-only reviewer; never a beauty score. */
public final class SpatialReport {
    public static final int TEXT_LIMIT = 6500;
    private static final long MAX_VOLUME = 1_200_000;

    private SpatialReport() { }

    public static String render(VoxelModel model, BlockInfo info) {
        int[] b = model.solidBounds();
        if (b == null) return "SPATIAL EVIDENCE: empty model\n";
        long volume = ((long)b[3]-b[0]+3)*((long)b[4]-b[1]+3)*((long)b[5]-b[2]+3);
        if (volume > MAX_VOLUME) return "SPATIAL EVIDENCE: detailed navigation omitted (volume limit); inspect coordinate layers.\n";
        return new Survey(model, info).text();
    }

    private static final class Survey {
        final Grid grid;
        final BlockInfo info;
        final boolean[] walk;
        final boolean[] reached;
        final int[] zones;
        final int[] queue;
        final List<Zone> regions = new ArrayList<>();
        final List<Integer> doors = new ArrayList<>();
        final List<Integer> objects = new ArrayList<>();

        Survey(VoxelModel model, BlockInfo info) {
            this.info = info;
            grid = Grid.ofNonAir(model, 1);
            int n = (int)grid.volume;
            walk = new boolean[n]; reached = new boolean[n]; zones = new int[n]; queue = new int[n];
            for (int i=0;i<n;i++) {
                int x=grid.xOf(i), y=grid.yOf(i), z=grid.zOf(i);
                BlockSpec s=grid.eff(i);
                walk[i] = passable(s) && passable(grid.eff(x,y+1,z))
                        && (footing(grid.eff(x,y-1,z)) || s.name().equals("ladder"));
                if (door(s) && !"upper".equals(info.getProp(s,"half"))) doors.add(i);
                if (furniture(s) && !"head".equals(info.getProp(s,"part"))) objects.add(i);
            }
            findReachable();
            findZones();
        }

        String text() {
            StringBuilder out = new StringBuilder("SPATIAL EVIDENCE (computed from actual blocks)\n");
            out.append("Navigation approximation: whole-block clearance, 2-block headroom, steps up/down <=1, doors treated as open. ")
                    .append("Zones split at doors; they are walkable areas, not certified enclosed rooms. Roofs/outdoor areas can form zones.\n");
            out.append("Height profiles include solid objects/vegetation; they describe silhouette, not roof intent.\n");
            out.append("East-west skyline: ").append(profile(true)).append('\n');
            out.append("North-south skyline: ").append(profile(false)).append('\n');
            out.append("Walkable zones: ").append(regions.size()).append('\n');
            regions.stream().sorted(Comparator.comparingInt((Zone z)->z.count).reversed()).limit(8).forEach(z ->
                    out.append("Z").append(z.id).append(" feet bounds ").append(z.bounds()).append(" cells=")
                            .append(z.count).append(" connected-to-site=").append(z.reachable).append('\n'));
            if (regions.size()>8) out.append("Smaller zones omitted: ").append(regions.size()-8).append('\n');
            out.append("Door connections (N/S or W/E zones; - means no standing cell):\n");
            for (int i : doors.subList(0, Math.min(12,doors.size()))) {
                int x=grid.xOf(i), y=grid.yOf(i), z=grid.zOf(i);
                Dir facing=Dir.parse(info.getProp(grid.eff(i),"facing"));
                if (facing==null || facing.dy!=0) facing=Dir.NORTH;
                out.append(at(i)).append(' ').append(grid.eff(i).name()).append(" facing=").append(facing.id())
                        .append(" connects ").append(zoneAt(x+facing.dx,y,z+facing.dz)).append(" / ")
                        .append(zoneAt(x-facing.dx,y,z-facing.dz)).append(" reachable-approach=").append(approach(i)).append('\n');
            }
            if (doors.size()>12) out.append("Additional doors omitted: ").append(doors.size()-12).append('\n');
            out.append("Functional objects (reachable-approach is approximate; false needs inspection):\n");
            for (int i : objects.subList(0, Math.min(24,objects.size()))) {
                BlockSpec s=grid.eff(i);
                out.append(at(i)).append(' ').append(s.toString()).append(" reachable-approach=").append(approach(i));
                if (s.name().equals("chest") || s.name().equals("trapped_chest")) {
                    out.append(" opaque-block-above=").append(info.isOccluding(grid.eff(grid.xOf(i),grid.yOf(i)+1,grid.zOf(i))));
                }
                out.append('\n');
            }
            if (objects.size()>24) out.append("Additional functional objects omitted: ").append(objects.size()-24).append('\n');
            if (out.length()>TEXT_LIMIT) {
                int end=out.lastIndexOf("\n",TEXT_LIMIT-80);
                out.setLength(Math.max(0,end+1));
                out.append("Further evidence omitted; use coordinate layers for exact inspection.\n");
            }
            return out.toString();
        }

        boolean passable(BlockSpec s) {
            if (info.isLiquid(s)) return false;
            if (door(s) || s.name().equals("ladder")) return true;
            return !info.isSolid(s);
        }

        boolean footing(BlockSpec s) {
            return info.isSolid(s) && !door(s) && !s.name().endsWith("_fence") && !s.name().endsWith("_wall")
                    && !s.name().endsWith("_fence_gate");
        }

        static boolean door(BlockSpec s) { return s.name().endsWith("_door") && !s.name().endsWith("_trapdoor"); }

        static boolean furniture(BlockSpec s) {
            String n=s.name();
            return n.endsWith("_bed") || List.of("chest","trapped_chest","barrel","furnace","smoker","blast_furnace",
                    "crafting_table","lectern","enchanting_table","brewing_stand","anvil","stonecutter","loom").contains(n);
        }

        void findReachable() {
            int tail=0;
            for (int i=0;i<walk.length;i++) if (walk[i] && (grid.xOf(i)==grid.minX || grid.xOf(i)==grid.maxX
                    || grid.zOf(i)==grid.minZ || grid.zOf(i)==grid.maxZ)) {
                reached[i]=true; queue[tail++]=i;
            }
            for (int head=0;head<tail;head++) tail=visitNeighbours(queue[head],tail,true,0);
        }

        void findZones() {
            for (int i=0;i<walk.length;i++) if (walk[i] && zones[i]==0 && !door(grid.eff(i))) {
                Zone zone=new Zone(regions.size()+1);
                regions.add(zone);
                zones[i]=zone.id; queue[0]=i;
                int tail=1;
                for (int head=0;head<tail;head++) {
                    int cell=queue[head]; zone.add(grid.xOf(cell),grid.yOf(cell),grid.zOf(cell),reached[cell]);
                    tail=visitNeighbours(cell,tail,false,zone.id);
                }
            }
        }

        int visitNeighbours(int cell,int tail,boolean reach,int zone) {
            int x=grid.xOf(cell), y=grid.yOf(cell), z=grid.zOf(cell);
            for (Dir dir : Dir.HORIZONTAL) for (int dy : new int[]{0,1,-1}) {
                int nx=x+dir.dx, ny=y+dy, nz=z+dir.dz;
                if (dy==1 && !passable(grid.eff(x,y+2,z))) continue;
                if (dy==-1 && !passable(grid.eff(nx,y+1,nz))) continue;
                tail=enqueue(nx,ny,nz,tail,reach,zone);
            }
            // Ladders supply support while climbing; other unsupported vertical moves are not inferred.
            if (grid.eff(cell).name().equals("ladder")) {
                tail=enqueue(x,y+1,z,tail,reach,zone);
                tail=enqueue(x,y-1,z,tail,reach,zone);
            }
            return tail;
        }

        int enqueue(int x,int y,int z,int tail,boolean reach,int zone) {
            if (!grid.contains(x,y,z)) return tail;
            int i=grid.index(x,y,z);
            if (!walk[i] || (reach ? reached[i] : zones[i]!=0 || door(grid.eff(i)))) return tail;
            if (reach) reached[i]=true; else zones[i]=zone;
            queue[tail++]=i;
            return tail;
        }

        String zoneAt(int x,int y,int z) {
            if (!grid.contains(x,y,z)) return "-";
            int id=zones[grid.index(x,y,z)]; return id==0 ? "-" : "Z"+id;
        }

        boolean approach(int i) {
            int x=grid.xOf(i), y=grid.yOf(i), z=grid.zOf(i);
            for (Dir d : Dir.HORIZONTAL) {
                if (grid.contains(x+d.dx,y,z+d.dz) && reached[grid.index(x+d.dx,y,z+d.dz)]) return true;
            }
            return false;
        }

        String at(int i) { return "["+grid.xOf(i)+","+grid.yOf(i)+","+grid.zOf(i)+"]"; }

        String profile(boolean alongX) {
            int lo=alongX?grid.minX+1:grid.minZ+1, hi=alongX?grid.maxX-1:grid.maxZ-1;
            int[] heights=new int[hi-lo+1]; Arrays.fill(heights,Integer.MIN_VALUE);
            for (int i=0;i<walk.length;i++) if (info.isSolid(grid.eff(i)) && grid.at(i)!=null) {
                int coordinate=alongX?grid.xOf(i):grid.zOf(i);
                if (coordinate>=lo && coordinate<=hi) heights[coordinate-lo]=Math.max(heights[coordinate-lo],grid.yOf(i));
            }
            StringBuilder out=new StringBuilder();
            for (int p=0;p<heights.length;) {
                int end=p; while (end+1<heights.length && heights[end+1]==heights[p]) end++;
                if (!out.isEmpty()) out.append(' ');
                out.append(alongX?'x':'z').append(lo+p);
                if (end>p) out.append("..").append(lo+end);
                out.append("=y").append(heights[p]==Integer.MIN_VALUE?"none":heights[p]);
                p=end+1;
            }
            return out.toString();
        }
    }

    private static final class Zone {
        final int id;
        int count;
        int minX=Integer.MAX_VALUE,minY=Integer.MAX_VALUE,minZ=Integer.MAX_VALUE;
        int maxX=Integer.MIN_VALUE,maxY=Integer.MIN_VALUE,maxZ=Integer.MIN_VALUE;
        boolean reachable;
        Zone(int id) { this.id=id; }
        void add(int x,int y,int z,boolean reached) {
            count++; minX=Math.min(minX,x); minY=Math.min(minY,y); minZ=Math.min(minZ,z);
            maxX=Math.max(maxX,x); maxY=Math.max(maxY,y); maxZ=Math.max(maxZ,z); reachable|=reached;
        }
        String bounds() { return String.format(Locale.ROOT,"[%d,%d,%d]..[%d,%d,%d]",minX,minY,minZ,maxX,maxY,maxZ); }
    }
}
