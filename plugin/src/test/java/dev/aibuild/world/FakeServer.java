package dev.aibuild.world;

import dev.aibuild.build.Build;
import dev.aibuild.model.BlockInfo;
import dev.aibuild.model.BlockSpec;
import dev.aibuild.model.StubBlockInfo;
import dev.aibuild.model.VoxelModel;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.block.data.BlockData;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A tiny in-memory {@link BlockData} + {@link World} fake so the placement algorithm of
 * {@link WorldPlacer} and the capture logic of {@link RegionCapture} can be exercised without a running
 * server. Only the methods the module actually calls are implemented; everything else returns a benign
 * default.
 *
 * <p>The fake's {@code BlockData#rotate} reproduces the four vaninlla directional rotations needed by the
 * tests: {@code facing} (horizontal), {@code axis} and {@code multiline shape} are rotated; properties that
 * are not directional are left alone.</p>
 */
final class FakeServer {

    private FakeServer() {
    }

    // ------------------------------------------------------------------ block data

    /** Simple, immutable-ish {@link BlockData} implementation keyed by its state string. */
    static final class Data implements BlockData {
        private final String name;
        private final Map<String, String> props;
        private final Material material;

        Data(String name, Map<String, String> props) {
            this.name = BlockSpec.normalizeName(name);
            this.props = new LinkedHashMap<>(props);
            Material m;
            try {
                m = Material.valueOf(this.name.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Unknown block: " + name);
            }
            this.material = m;
        }

        static Data of(String state) {
            BlockSpec s = BlockSpec.parseRaw(state);
            return new Data(s.name(), new HashMap<>(s.props()));
        }

        @Override
        public Material getMaterial() {
            return material;
        }

        @Override
        public String getAsString() {
            if (props.isEmpty()) return "minecraft:" + name;
            StringBuilder sb = new StringBuilder("minecraft:").append(name).append('[');
            boolean first = true;
            for (Map.Entry<String, String> e : props.entrySet()) {
                if (!first) sb.append(',');
                sb.append(e.getKey()).append('=').append(e.getValue());
                first = false;
            }
            return sb.append(']').toString();
        }

        @Override
        public String getAsString(boolean hideUnspecified) {
            return getAsString();
        }

        @Override
        public BlockData merge(BlockData data) {
            Map<String, String> p = new LinkedHashMap<>(props);
            if (data instanceof Data d) p.putAll(d.props);
            return new Data(name, p);
        }

        @Override
        public boolean matches(BlockData data) {
            if (!(data instanceof Data d)) return false;
            if (!name.equals(d.name)) return false;
            // unspecified properties in `this` match anything (Bukkit semantics)
            for (Map.Entry<String, String> e : props.entrySet()) {
                String other = d.props.get(e.getKey());
                if (other == null) return false;
                if (!other.equals(e.getValue())) return false;
            }
            return true;
        }

        @Override
        public BlockData clone() {
            return new Data(name, props);
        }

        @Override
        public boolean isOccluding() {
            return switch (name) {
                case "stone", "dirt", "grass_block", "cobblestone", "stone_bricks", "oak_planks",
                     "oak_log", "dirt_path", "farmland", "coarse_dirt", "podzol", "rooted_dirt", "mud",
                     "sand", "gravel", "clay", "deepslate", "oak_leaves" -> true;
                default -> false;
            };
        }

        @Override
        public boolean isReplaceable() {
            return switch (name) {
                case "air", "cave_air", "void_air", "short_grass", "tall_grass", "fern", "large_fern",
                     "dead_bush", "water", "lava", "snow", "vine" -> true;
                default -> false;
            };
        }

        @Override
        public void rotate(org.bukkit.block.structure.StructureRotation rotation) {
            int turns = switch (rotation) {
                case NONE -> 0;
                case CLOCKWISE_90 -> 1;
                case CLOCKWISE_180 -> 2;
                case COUNTERCLOCKWISE_90 -> 3;
            };
            for (int i = 0; i < turns; i++) rotateOnce();
        }

        private void rotateOnce() {
            // facing: N->E->S->W->N (clockwise seen from above)
            String facing = props.get("facing");
            if (facing != null) {
                props.put("facing", switch (facing) {
                    case "north" -> "east";
                    case "east" -> "south";
                    case "south" -> "west";
                    case "west" -> "north";
                    default -> facing;
                });
            }
            String axis = props.get("axis");
            if (axis != null && !axis.equals("y")) {
                props.put("axis", axis.equals("x") ? "z" : "x");
            }
            String shape = props.get("shape");
            if (shape != null) {
                props.put("shape", switch (shape) {
                    case "north_south" -> "east_west";
                    case "east_west" -> "north_south";
                    case "ascending_north" -> "ascending_east";
                    case "ascending_east" -> "ascending_south";
                    case "ascending_south" -> "ascending_west";
                    case "ascending_west" -> "ascending_north";
                    default -> shape;
                });
            }
        }

        @Override
        public void mirror(org.bukkit.block.structure.Mirror mirror) {
            // unused by the tests
        }

        @Override
        public void copyTo(BlockData blockData) {
            throw new UnsupportedOperationException();
        }

        @Override
        public BlockState createBlockState() {
            throw new UnsupportedOperationException();
        }

        @Override
        public int getLightEmission() {
            return 0;
        }

        @Override
        public boolean requiresCorrectToolForDrops() {
            return false;
        }

        @Override
        public boolean isPreferredTool(org.bukkit.inventory.ItemStack itemStack) {
            return false;
        }

        @Override
        public org.bukkit.block.PistonMoveReaction getPistonMoveReaction() {
            return org.bukkit.block.PistonMoveReaction.MOVE;
        }

        @Override
        public boolean isSupported(Block block) {
            return true;
        }

        @Override
        public boolean isSupported(org.bukkit.Location location) {
            return true;
        }

        @Override
        public boolean isFaceSturdy(org.bukkit.block.BlockFace face, org.bukkit.block.BlockSupport support) {
            return isOccluding();
        }

        @Override
        public org.bukkit.util.VoxelShape getCollisionShape(org.bukkit.Location location) {
            return null;
        }

        @Override
        public org.bukkit.Color getMapColor() {
            return org.bukkit.Color.GRAY;
        }

        @Override
        public Material getPlacementMaterial() {
            return material;
        }

        @Override
        public org.bukkit.SoundGroup getSoundGroup() {
            return null;
        }

        @Override
        public float getDestroySpeed(org.bukkit.inventory.ItemStack itemStack, boolean considerEnchants) {
            return 1f;
        }

        @Override
        public boolean isRandomlyTicked() {
            return false;
        }

        @Override
        public String toString() {
            return getAsString();
        }
    }

    // ------------------------------------------------------------------ world

    /** World backed by a sparse block map; missing blocks are air. */
    static final class FakeWorld {
        final Map<Long, Data> blocks = new LinkedHashMap<>();
        final String name;
        int minHeight = -64;
        int maxHeight = 320;
        int chunkLoads;

        FakeWorld(String name) {
            this.name = name;
        }

        void set(int x, int y, int z, String state) {
            blocks.put(WorldKey.pack(x, y, z), Data.of(state));
        }

        String get(int x, int y, int z) {
            Data d = blocks.get(WorldKey.pack(x, y, z));
            return d == null ? null : d.getAsString();
        }

        World world() {
            InvocationHandler h = (proxy, method, args) -> {
                switch (method.getName()) {
                    case "getName":
                        return name;
                    case "getMinHeight":
                        return minHeight;
                    case "getMaxHeight":
                        return maxHeight;
                    case "getBlockData":
                        return blockData((Integer) args[0], (Integer) args[1], (Integer) args[2]);
                    case "setBlockData": {
                        Object d = args[3];
                        blocks.put(WorldKey.pack((Integer) args[0], (Integer) args[1], (Integer) args[2]),
                                (Data) d);
                        return null;
                    }
                    case "getBlockAt":
                        return block((Integer) args[0], (Integer) args[1], (Integer) args[2]);
                    case "getType":
                        return blockData((Integer) args[0], (Integer) args[1], (Integer) args[2]).getMaterial();
                    case "getHighestBlockYAt": {
                        // the first air block above the highest non-air block (Bukkit semantics)
                        int x = (Integer) args[0], z = (Integer) args[1];
                        int best = minHeight - 1;
                        for (long k : blocks.keySet()) {
                            if (WorldKey.x(k) == x && WorldKey.z(k) == z) best = Math.max(best, WorldKey.y(k));
                        }
                        return best + 1;
                    }
                    case "getChunkAt":
                        chunkLoads++;
                        return null;
                    case "toString":
                        return "FakeWorld{" + name + "}";
                    case "hashCode":
                        return name.hashCode();
                    case "equals":
                        return proxy == args[0];
                    default:
                        return defaultValue(method.getReturnType());
                }
            };
            return (World) Proxy.newProxyInstance(FakeServer.class.getClassLoader(),
                    new Class<?>[]{World.class}, h);
        }

        private Data blockData(int x, int y, int z) {
            Data d = blocks.get(WorldKey.pack(x, y, z));
            return d == null ? Data.of("air") : d;
        }

        Block block(int x, int y, int z) {
            InvocationHandler h = (proxy, method, args) -> {
                switch (method.getName()) {
                    case "getX":
                        return x;
                    case "getY":
                        return y;
                    case "getZ":
                        return z;
                    case "getBlockData":
                        return blockData(x, y, z);
                    case "setBlockData": {
                        if (args.length > 1 && Boolean.TRUE.equals(args[1])) {
                            throw new IllegalStateException("physics must be disabled");
                        }
                        blocks.put(WorldKey.pack(x, y, z), (Data) args[0]);
                        return null;
                    }
                    case "getType":
                        return blockData(x, y, z).getMaterial();
                    case "getState":
                        return null; // not a container: the placer's instanceof check fails
                    case "getWorld":
                        return world();
                    case "toString":
                        return "FakeBlock{" + x + "," + y + "," + z + "}";
                    case "hashCode":
                        return Long.hashCode(WorldKey.pack(x, y, z));
                    case "equals":
                        return proxy == args[0];
                    default:
                        return defaultValue(method.getReturnType());
                }
            };
            return (Block) Proxy.newProxyInstance(FakeServer.class.getClassLoader(),
                    new Class<?>[]{Block.class}, h);
        }
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == void.class) return null;
        if (type == double.class) return 0d;
        if (type == float.class) return 0f;
        if (type == long.class) return 0L;
        if (type == short.class) return (short) 0;
        if (type == byte.class) return (byte) 0;
        if (type == char.class) return (char) 0;
        return 0;
    }

    // ------------------------------------------------------------------ plugin / scheduler / data factory

    static org.bukkit.plugin.Plugin fakePlugin(java.util.logging.Logger log) {
        return fakePlugin(log, null);
    }

    /**
     * Plugin stub whose {@code getServer()} resolves {@code defaultWorld} by name, so {@link WorldPlacer}
     * can look worlds up exactly the way it does in production.
     */
    static org.bukkit.plugin.Plugin fakePlugin(java.util.logging.Logger log, FakeWorld defaultWorld) {
        org.bukkit.Server server = fakeServer(defaultWorld);
        InvocationHandler h = (proxy, method, args) -> switch (method.getName()) {
            case "getLogger" -> log;
            case "getServer" -> server;
            case "isEnabled" -> true;
            case "getName" -> "AIBuild";
            case "getDataFolder" -> new java.io.File(".");
            case "toString" -> "FakePlugin";
            case "hashCode" -> 31;
            case "equals" -> proxy == args[0];
            default -> defaultValue(method.getReturnType());
        };
        return (org.bukkit.plugin.Plugin) Proxy.newProxyInstance(FakeServer.class.getClassLoader(),
                new Class<?>[]{org.bukkit.plugin.Plugin.class}, h);
    }

    /** Server stub resolving a single fake world by name. */
    static org.bukkit.Server fakeServer(FakeWorld world) {
        InvocationHandler h = (proxy, method, args) -> switch (method.getName()) {
            case "getWorld" -> {
                if (world == null) yield null;
                Object arg = args.length > 0 ? args[0] : null;
                if (arg instanceof String s) yield s.equals(world.name) ? world.world() : null;
                yield world.world();
            }
            case "getWorlds" -> world == null ? java.util.List.of() : java.util.List.of(world.world());
            case "getLogger" -> java.util.logging.Logger.getLogger("FakeServer");
            case "getName" -> "FakeServer";
            case "isPrimaryThread" -> true;
            case "toString" -> "FakeServer";
            case "hashCode" -> 17;
            case "equals" -> proxy == args[0];
            default -> defaultValue(method.getReturnType());
        };
        return (org.bukkit.Server) Proxy.newProxyInstance(FakeServer.class.getClassLoader(),
                new Class<?>[]{org.bukkit.Server.class}, h);
    }

    /**
     * Manual scheduler: ticks are driven explicitly by the test. Models a real scheduler faithfully:
     * a repeating task stays registered until it is cancelled, a cancelled task never runs again, and a
     * one-shot task runs once.
     */
    static final class ManualScheduler implements WorldPlacer.TickScheduler {
        private static final class Entry {
            final Runnable task;
            final boolean repeating;
            boolean cancelled;

            Entry(Runnable task, boolean repeating) {
                this.task = task;
                this.repeating = repeating;
            }
        }

        private final List<Entry> entries = new ArrayList<>();
        final AtomicInteger scheduledTicks = new AtomicInteger();
        final AtomicInteger oneShots = new AtomicInteger();

        @Override
        public Object scheduleRepeating(Runnable task, long delayTicks, long periodTicks) {
            Entry e = new Entry(task, true);
            entries.add(e);
            return e;
        }

        @Override
        public void cancel(Object handle) {
            if (handle instanceof Entry e) {
                e.cancelled = true;
                entries.remove(e);
            }
        }

        @Override
        public void runOnMainThread(Runnable task) {
            entries.add(new Entry(task, false));
        }

        /** Runs all registered tasks once (one-shots are removed, repeating ones stay). */
        int tick() {
            int ran = 0;
            for (Entry e : new ArrayList<>(entries)) {
                if (e.cancelled || !entries.contains(e)) continue;
                if (!e.repeating) {
                    entries.remove(e);
                    oneShots.incrementAndGet();
                }
                e.task.run();
                ran++;
            }
            scheduledTicks.incrementAndGet();
            return ran;
        }

        /** Runs up to {@code maxTicks} ticks until the future is done. */
        boolean runUntil(CompletableFuture<?> future, int maxTicks) {
            for (int i = 0; i < maxTicks && !future.isDone(); i++) {
                tick();
            }
            return future.isDone();
        }
    }

    /** Block data factory backed by {@link Data}. */
    static WorldPlacer.BlockDataFactory factory() {
        return Data::of;
    }

    static final BlockInfo INFO = new StubBlockInfo();
}
