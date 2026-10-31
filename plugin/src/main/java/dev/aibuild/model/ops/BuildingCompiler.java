package dev.aibuild.model.ops;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.aibuild.model.Dir;
import dev.aibuild.model.BlockInfo;
import dev.aibuild.model.VoxelModel;

import java.util.LinkedHashSet;
import java.util.Set;

public final class BuildingCompiler {
    private final JsonArray ops = new JsonArray();
    private final BlockInfo info;

    private BuildingCompiler(BlockInfo info) {
        this.info = info;
    }

    public static JsonArray compile(JsonObject input, BlockInfo info) {
        return new BuildingCompiler(info).building(input);
    }

    private JsonArray building(JsonObject input) {
        int[][] region = OpArgs.region(input);
        if (region == null) throw new IllegalArgumentException("building needs from/to: floor corner and opposite wall-top corner");
        int x1 = Math.min(region[0][0], region[1][0]), x2 = Math.max(region[0][0], region[1][0]);
        int z1 = Math.min(region[0][2], region[1][2]), z2 = Math.max(region[0][2], region[1][2]);
        int floorY = Math.min(region[0][1], region[1][1]), top = Math.max(region[0][1], region[1][1]);
        if (x2-x1 < 6 || z2-z1 < 6 || x2-x1 > 63 || z2-z1 > 63 || top-floorY < 4 || top-floorY > 12) {
            throw new IllegalArgumentException("building needs width/depth 7..64 and wall height 4..12 (one room envelope)");
        }
        String foundation = OpArgs.str(input,"70%cobblestone,20%mossy_cobblestone,10%andesite","foundation");
        String floor = OpArgs.str(input,"oak_planks","floor");
        String wall = OpArgs.str(input,"oak_planks","wall");
        String frame = OpArgs.str(input,"oak_log","frame");
        String glass = OpArgs.str(input,"glass_pane","glass");
        String sill = OpArgs.str(input,"stone_brick_stairs","sill");
        String shutter = OpArgs.str(input,"oak_trapdoor","shutter");
        String door = OpArgs.str(input,"oak_door","door");
        boolean framing = OpArgs.bool(input,true,"framing");
        boolean windows = OpArgs.bool(input,true,"windows");
        boolean shutters = OpArgs.bool(input,true,"shutters");
        boolean planters = OpArgs.bool(input,true,"planters");
        boolean sills = OpArgs.bool(input,true,"sills");
        boolean porch = OpArgs.bool(input,true,"porch");
        int spacing = OpArgs.integer(input,4,"window_spacing");
        if (spacing < 3 || spacing > 16) throw new IllegalArgumentException("window_spacing must be 3..16");
        int doorX = OpArgs.integer(input, (x1+x2)/2,"door_x");
        if (doorX < x1+2 || doorX > x2-2) throw new IllegalArgumentException("door_x needs two blocks of wall on each side");

        box("fill",x1,floorY-1,z1,x2,floorY-1,z2,foundation);
        box("fill",x1,floorY,z1,x2,floorY,z2,floor);
        box("walls",x1,floorY+1,z1,x2,top,z2,wall);
        box("walls",x1,floorY+1,z1,x2,floorY+1,z2,foundation);
        box("fill",x1+1,top,z1+1,x2-1,top,z2-1,floor);
        if (framing) {
            box("outline",x1,top,z1,x2,top,z2,frame);
            for (int x : new int[]{x1,x2}) for (int z : new int[]{z1,z2}) {
                box("line",x,floorY+1,z,x,top,z,frame);
            }
            box("line",x1,top,(z1+z2)/2,x2,top,(z1+z2)/2,frame);
        }

        JsonElement roofInput = input.get("roof");
        JsonObject roof = roofInput != null && roofInput.isJsonObject() ? roofInput.getAsJsonObject().deepCopy() : new JsonObject();
        roof.addProperty("op","roof");roof.add("from",point(x1,top+1,z1));roof.add("to",point(x2,top+1,z2));
        defaultString(roof,"style","gable");defaultString(roof,"stairs","spruce_stairs");
        defaultString(roof,"slab","spruce_slab");defaultString(roof,"fill",wall);
        if (!roof.has("overhang")) roof.addProperty("overhang",1);
        ops.add(roof);
        VoxelModel roofModel = new VoxelModel();
        new RoofBuilder(info).build(roofModel,roof,new OpResult());
        int[][] heights = new int[z2-z1+1][x2-x1+1];
        roofModel.forEach((x,y,z,s) -> {
            if (x>x1 && x<x2 && z>z1 && z<z2) heights[z-z1][x-x1]=Math.max(heights[z-z1][x-x1],y);
        });
        for (int z=z1+1;z<z2;z++) {
            int x=x1+1;
            while (x<x2) {
                int end=x, height=heights[z-z1][x-x1];
                while (end+1<x2 && heights[z-z1][end+1-x1]==height) end++;
                if (height>top+1) box("fill",x,top+1,z,end,height-1,z,floor);
                x=end+1;
            }
        }
        JsonObject trim = box("trim",x1,top,z1,x2,top,z2,
                OpArgs.str(roof,"spruce_stairs","stairs"));
        trim.addProperty("half","top");

        int windowBottom = floorY+2, windowTop = Math.min(top-1,floorY+3);
        for (Dir side : windows ? new Dir[]{Dir.NORTH,Dir.SOUTH,Dir.EAST,Dir.WEST} : new Dir[0]) {
            boolean alongX = side == Dir.NORTH || side == Dir.SOUTH;
            int low = alongX ? x1 : z1, high = alongX ? x2 : z2;
            Set<Integer> centers = new LinkedHashSet<>();
            if (side == Dir.SOUTH) {
                centers.add((x1+doorX)/2);centers.add((doorX+x2)/2);
            } else {
                int count = Math.max(1,(high-low)/spacing);
                for (int i=0;i<count;i++) centers.add(count==1 ? (low+high)/2
                        : low+2+(int)Math.round((high-low-4.0)*i/(count-1)));
            }
            for (int center : centers) {
                if (center <= low || center >= high) continue;
                int wx = alongX ? center : side == Dir.WEST ? x1 : x2;
                int wz = alongX ? side == Dir.NORTH ? z1 : z2 : center;
                box("fill",wx,windowBottom,wz,wx,windowTop,wz,glass);
                Dir tangent = alongX ? Dir.EAST : Dir.SOUTH;
                for (int sign : new int[]{-1,1}) {
                    int px = wx+tangent.dx*sign, pz = wz+tangent.dz*sign;
                    if (framing) box("line",px,floorY+1,pz,px,top,pz,frame);
                    if (shutters) box("fill",px+side.dx,windowBottom,pz+side.dz,px+side.dx,windowTop,pz+side.dz,
                            shutter+"[facing="+side.id()+",open=true]");
                }
                if (sills || planters) set(wx+side.dx,windowBottom-1,wz+side.dz,
                        sill+"[half=top,facing="+side.opposite().id()+"]");
                if (planters) set(wx+side.dx,windowBottom,wz+side.dz,"potted_poppy");
            }
        }

        box("clear",doorX,floorY+1,z2-1,doorX,floorY+2,z2+1,null);
        set(doorX,floorY+1,z2,door+"[facing=north]");
        if (porch) {
            box("fill",doorX-1,floorY,z2+1,doorX+1,floorY,z2+2,foundation);
            for (int x : new int[]{doorX-1,doorX+1}) box("line",x,floorY+1,z2+2,x,floorY+3,z2+2,frame);
            box("fill",doorX-1,floorY+4,z2+1,doorX+1,floorY+4,z2+2,
                    OpArgs.str(roof,"spruce_slab","slab"));
        }
        if (OpArgs.bool(input,true,"lighting")) {
            for (int x=x1+3;x<=x2-2;x+=6) for (int z=z1+3;z<=z2-2;z+=6) {
                set(x,top-1,z,"lantern[hanging=true]");
            }
        }
        return ops;
    }

    private static void defaultString(JsonObject o,String key,String value) {
        if (!o.has(key)) o.addProperty(key,value);
    }

    private JsonObject box(String name,int x1,int y1,int z1,int x2,int y2,int z2,String block) {
        JsonObject op=new JsonObject();op.addProperty("op",name);
        op.add("from",point(x1,y1,z1));op.add("to",point(x2,y2,z2));
        if (block!=null)op.addProperty("block",block);ops.add(op);return op;
    }

    private void set(int x,int y,int z,String block) {
        JsonObject op=new JsonObject();op.addProperty("op","set");op.add("pos",point(x,y,z));op.addProperty("block",block);ops.add(op);
    }

    private static JsonArray point(int x,int y,int z) {
        JsonArray a=new JsonArray();a.add(x);a.add(y);a.add(z);return a;
    }
}
