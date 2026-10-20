package net.flameslight.rwstructuresawareness.tunnel;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.flameslight.rwstructuresawareness.roadweaver.RoadWeaverAccessor;
import net.flameslight.rwstructuresawareness.types.tunnel.TunnelStore;
import net.flameslight.rwstructuresawareness.utils.CoordinateKeys;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.shiroha233.roadweaver.core.model.RoadData;
import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;
import net.shiroha233.roadweaver.core.model.RoadSpan;
import net.shiroha233.roadweaver.core.model.SpanType;
import net.shiroha233.roadweaver.features.path.pathlogic.pathfinding.RoadHeightInterpolator;
import net.shiroha233.roadweaver.persistence.sharded.RoadShardStorage;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Digs the tunnels RoadTunnelPlanner recorded, shaped like RoadWeaver's own tunnels: on every road
 * column of a TUNNEL span only (the road's own width, nothing beside it), from the road's first air
 * block up clearHeight() blocks, exactly what RoadWeaver's AboveColumnClearer clears above a paved
 * road. The terrain beside and above the passage stays natural. Done at both stages a chunk can be in
 * when its road was planned:
 *  - terrain stage (BeardifierMixin): density forced to TUNNEL_DENSITY, i.e. air, at any depth
 *    (RoadWeaver's own road carving, at most -0.6, is too weak below the surface).
 *  - decoration stage (right after RoadWeaver's PathFeature paved the chunk): the same blocks set to
 *    air, using RoadWeaver's own per-block road heights, so a road block is never removed. RoadWeaver
 *    itself only clears above blocks it actually paved, and natural roads leave some unpaved on
 *    purpose. The already-decorated neighbours in the WorldGenRegion are carved again too, since this
 *    chunk's decoration (e.g. a structure's foundations) may have written into their tunnels.
 *
 * TunnelStore's boxes (TunnelStore.BOX_INTS: minX, minZ, maxX, maxZ, floorY, ceilY) only index where
 * tunnels are. They don't define what is dug.
 */
public final class TunnelCarver {
    /** Added to the terrain density inside a tunnel, far below any solid terrain value. */
    public static final double TUNNEL_DENSITY = -20.0;
    private static final int[] EMPTY = new int[0];
    /** Chunks whose tunnel floors stay in memory (a few hundred columns each at most). */
    private static final int MAX_CACHED_CHUNKS = 512;
    /** Chunk key -> its tunnel floors. Only touched while holding the map itself. */
    private static final Map<Long, Long2IntOpenHashMap> FLOORS_CACHE =
            new LinkedHashMap<>(MAX_CACHED_CHUNKS, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Long, Long2IntOpenHashMap> eldest) {
                    return size() > MAX_CACHED_CHUNKS;
                }
            };
    /** Bumped by every invalidation, so a computation that raced a road save isn't cached. */
    private static long floorsGeneration;

    /**
     * Returns the tunnel boxes overlapping a chunk, clipped to it.
     *
     * @param chunkX the chunk's X (chunk coordinates)
     * @param chunkZ the chunk's Z (chunk coordinates)
     * @return the clipped boxes, TunnelStore.BOX_INTS ints each, empty if no tunnel touches the chunk
     */
    public static int[] boxesForChunk(int chunkX, int chunkZ) {
        int[] region = TunnelStore.boxesInRegionOf(chunkX, chunkZ);
        if (region.length == 0) {
            return EMPTY;
        }
        int minX = chunkX << 4;
        int minZ = chunkZ << 4;
        int maxX = minX + 15;
        int maxZ = minZ + 15;
        IntArrayList out = null;
        for (int i = 0; i + TunnelStore.BOX_INTS - 1 < region.length; i += TunnelStore.BOX_INTS) {
            if (region[i + 2] < minX || region[i] > maxX || region[i + 3] < minZ || region[i + 1] > maxZ) {
                continue;
            }
            if (out == null) {
                out = new IntArrayList();
            }
            out.add(Math.max(region[i], minX));
            out.add(Math.max(region[i + 1], minZ));
            out.add(Math.min(region[i + 2], maxX));
            out.add(Math.min(region[i + 3], maxZ));
            out.add(region[i + 4]);
            out.add(region[i + 5]);
        }
        return out == null ? EMPTY : out.toIntArray();
    }

    /**
     * Tells whether a block lies in one of the given tunnel air volumes.
     *
     * @param boxes tunnel boxes, TunnelStore.BOX_INTS ints each, not changed
     * @param x     the block's X
     * @param y     the block's Y
     * @param z     the block's Z
     * @return true if a box contains the block
     */
    public static boolean insideTunnel(int[] boxes, int x, int y, int z) {
        for (int i = 0; i + TunnelStore.BOX_INTS - 1 < boxes.length; i += TunnelStore.BOX_INTS) {
            if (x >= boxes[i] && x <= boxes[i + 2] && z >= boxes[i + 1] && z <= boxes[i + 3]
                    && y >= boxes[i + 4] && y <= boxes[i + 5]) {
                return true;
            }
        }
        return false;
    }

    /**
     * Tells whether a block lies in any recorded tunnel's air volume (one region lookup, no allocation).
     *
     * @param x the block's X
     * @param y the block's Y
     * @param z the block's Z
     * @return true if a recorded tunnel box contains the block
     */
    public static boolean insideAnyTunnel(int x, int y, int z) {
        return insideTunnel(TunnelStore.boxesInRegionOf(x >> 4, z >> 4), x, y, z);
    }

    /**
     * Carves the tunnels of a chunk and of every already-decorated neighbour present in the WorldGenRegion.
     * <p>
     * Decoration stage, at the end of RoadWeaver's PathFeature for {@code center}.
     *
     * @param level  the WorldGenRegion being decorated, its blocks are changed
     * @param server the server level, to read saved roads
     * @param center the chunk PathFeature just paved
     */
    public static void carveAround(WorldGenLevel level, ServerLevel server, ChunkPos center) {
        List<ChunkPos> targets = new ArrayList<>(9);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                ChunkPos c = new ChunkPos(center.x + dx, center.z + dz);
                if ((dx != 0 || dz != 0) && !isDecorated(level, c)) {
                    continue; // not decorated yet: it carves itself when its own PathFeature runs
                }
                if (boxesForChunk(c.x, c.z).length == 0) {
                    continue; // no tunnel here
                }
                targets.add(c);
            }
        }
        if (targets.isEmpty()) {
            return;
        }
        int clear = clearHeight();
        for (ChunkPos target : targets) {
            carveChunk(level, target, tunnelColumns(server, target), clear);
        }
    }

    /**
     * Returns the blocks cleared above a tunnel road's first air block: RoadWeaver's own clearance.
     *
     * @return the clear height (blocks)
     */
    public static int clearHeight() {
        return RoadWeaverAccessor.roadClearHeight();
    }

    /**
     * Returns the tunnel road columns of one chunk.
     * <p>
     * Cached per chunk: the terrain stage and up to nine decoration passes ask for the same chunk.
     *
     * @param level the server level, to read saved roads
     * @param chunk the chunk
     * @return CoordinateKeys.pack(x, z) to the road's first air Y, default Integer.MAX_VALUE for other columns.
     *         Shared, callers must not change it
     */
    public static Long2IntOpenHashMap tunnelColumns(ServerLevel level, ChunkPos chunk) {
        long key = chunk.toLong();
        long generation;
        synchronized (FLOORS_CACHE) {
            Long2IntOpenHashMap cached = FLOORS_CACHE.get(key);
            if (cached != null) {
                return cached;
            }
            generation = floorsGeneration;
        }
        int minX = chunk.getMinBlockX();
        int minZ = chunk.getMinBlockZ();
        int maxX = chunk.getMaxBlockX();
        int maxZ = chunk.getMaxBlockZ();
        Long2IntOpenHashMap floors = roadFloors(RoadShardStorage.queryRect(level, minX, minZ, maxX, maxZ),
                minX, minZ, maxX, maxZ);
        synchronized (FLOORS_CACHE) {
            if (generation == floorsGeneration) { // no road saved meanwhile: not stale
                FLOORS_CACHE.put(key, floors);
            }
        }
        return floors;
    }

    /**
     * Drops the cached floors a saved, replaced or deleted road may have changed.
     *
     * @param road the road, not changed. Null means an unknown area: the whole cache is dropped
     */
    public static void invalidateFloors(@Nullable RoadData road) {
        if (road != null && getTunnelSegmentsByRoadData(road) == null) {
            return; // no tunnel columns
        }
        int minCX = Integer.MIN_VALUE, minCZ = Integer.MIN_VALUE, maxCX = Integer.MAX_VALUE, maxCZ = Integer.MAX_VALUE;
        if (road != null && road.roadSegmentList() != null) {
            minCX = Integer.MAX_VALUE; minCZ = Integer.MAX_VALUE; maxCX = Integer.MIN_VALUE; maxCZ = Integer.MIN_VALUE;
            for (RoadSegmentPlacement segment : road.roadSegmentList()) {
                if (segment == null || segment.positions() == null) {
                    continue;
                }
                for (BlockPos pos : segment.positions()) {
                    minCX = Math.min(minCX, pos.getX() >> 4);
                    minCZ = Math.min(minCZ, pos.getZ() >> 4);
                    maxCX = Math.max(maxCX, pos.getX() >> 4);
                    maxCZ = Math.max(maxCZ, pos.getZ() >> 4);
                }
            }
        }
        synchronized (FLOORS_CACHE) {
            floorsGeneration++;
            if (minCX == Integer.MIN_VALUE) {
                FLOORS_CACHE.clear();
                return;
            }
            Iterator<Long> it = FLOORS_CACHE.keySet().iterator();
            while (it.hasNext()) {
                long key = it.next();
                int cx = ChunkPos.getX(key);
                int cz = ChunkPos.getZ(key);
                if (cx >= minCX && cx <= maxCX && cz >= minCZ && cz <= maxCZ) {
                    it.remove();
                }
            }
        }
    }

    /** Drops every cached floor. Called by TunnelStore when a world opens, closes or resets. */
    public static void clearFloorsCache() {
        synchronized (FLOORS_CACHE) {
            floorsGeneration++;
            FLOORS_CACHE.clear();
        }
    }

    /**
     * Tells whether a chunk of the region has been decorated.
     *
     * @param level the WorldGenRegion
     * @param c     the chunk
     * @return true if the chunk is loaded in the region and at FEATURES or later
     */
    private static boolean isDecorated(WorldGenLevel level, ChunkPos c) {
        if (!level.hasChunk(c.x, c.z)) {
            return false;
        }
        ChunkAccess chunk = level.getChunk(c.x, c.z, ChunkStatus.EMPTY, false);
        return chunk != null && chunk.getStatus().isOrAfter(ChunkStatus.FEATURES);
    }

    /**
     * Computes the paved road's first air Y on every tunnel-span column inside an area.
     * <p>
     * Computed exactly as RoadWeaver's SegmentPaver does (RoadHeightInterpolator over the road's segment
     * middles and saved heights). The lowest wins where two roads share a column.
     *
     * @param roads saved roads, not changed, may be null
     * @param minX  the area's min X (block, inclusive)
     * @param minZ  the area's min Z (block, inclusive)
     * @param maxX  the area's max X (block, inclusive)
     * @param maxZ  the area's max Z (block, inclusive)
     * @return CoordinateKeys.pack(x, z) to the first air Y, default Integer.MAX_VALUE for other columns
     */
    public static Long2IntOpenHashMap roadFloors(List<RoadData> roads, int minX, int minZ, int maxX, int maxZ) {
        Long2IntOpenHashMap floors = new Long2IntOpenHashMap();
        floors.defaultReturnValue(Integer.MAX_VALUE);
        if (roads == null) {
            return floors;
        }
        for (RoadData road : roads) {
            boolean[] tunnel = getTunnelSegmentsByRoadData(road);
            List<Integer> targetY = road == null ? null : road.targetY();
            if (tunnel == null || targetY == null || targetY.size() != road.roadSegmentList().size()) {
                continue;
            }
            List<RoadSegmentPlacement> segments = road.roadSegmentList();
            List<BlockPos> middles = new ArrayList<>(segments.size());
            int[] ys = new int[segments.size()];
            for (int i = 0; i < segments.size(); i++) {
                middles.add(segments.get(i).middlePos());
                ys[i] = targetY.get(i);
            }
            for (int i = 0; i < segments.size(); i++) {
                List<BlockPos> positions = segments.get(i).positions();
                if (!tunnel[i] || positions == null || positions.isEmpty()) {
                    continue;
                }
                int[] heights = RoadHeightInterpolator.batchInterpolate(positions, i, middles, ys);
                for (int p = 0; p < positions.size(); p++) {
                    BlockPos pos = positions.get(p);
                    if (pos.getX() < minX || pos.getX() > maxX || pos.getZ() < minZ || pos.getZ() > maxZ) {
                        continue;
                    }
                    long key = CoordinateKeys.pack(pos.getX(), pos.getZ());
                    floors.put(key, Math.min(floors.get(key), heights[p]));
                }
            }
        }
        return floors;
    }

    /**
     * Clears every tunnel road column of a chunk as RoadWeaver's AboveColumnClearer does above a paved road.
     * <p>
     * {@code clear} blocks from the road's first air block (the road block below is kept), then a plant or
     * snow layer resting on top of the passage.
     *
     * @param level      the WorldGenRegion, its blocks are changed
     * @param chunk      the chunk to carve, columns outside it are skipped
     * @param roadFloors CoordinateKeys.pack(x, z) to the road's first air Y, not changed
     * @param clear      blocks to clear above the floor
     */
    private static void carveChunk(WorldGenLevel level, ChunkPos chunk, Long2IntOpenHashMap roadFloors, int clear) {
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (Long2IntMap.Entry e : roadFloors.long2IntEntrySet()) {
            int x = CoordinateKeys.unpackA(e.getLongKey());
            int z = CoordinateKeys.unpackB(e.getLongKey());
            if (x >> 4 != chunk.x || z >> 4 != chunk.z) {
                continue;
            }
            int floor = e.getIntValue();
            for (int y = floor; y < floor + clear; y++) {
                pos.set(x, y, z);
                if (!level.getBlockState(pos).isAir()) {
                    level.setBlock(pos, air, 2);
                }
            }
            pos.set(x, floor + clear, z);
            Block top = level.getBlockState(pos).getBlock();
            if (top instanceof BushBlock || top instanceof SnowLayerBlock) {
                level.removeBlock(pos, false);
            }
        }
    }

    /**
     * Finds which segments of a saved road run through a tunnel.
     * <p>
     * A road span is RoadWeaver's way of marking a stretch of a road: a {@code RoadSpan(start, end, type)}
     * covers every segment from the one whose middle position is {@code start} to the one whose middle position
     * is {@code end}, with a {@link SpanType} such as {@code BRIDGE} or {@code TUNNEL}. A span is a stretch of the
     * road, not a piece of the tunnel's shape. RoadTunnelPlanner adds one {@code TUNNEL} span per lowered stretch,
     * its ramps included. Spans point at segments by middle position, while carving and decorating walk a road by
     * segment index, so this turns the {@code TUNNEL} spans into one flag per segment.
     * <p>
     * Used to dig only the tunnel road columns ({@link #roadFloors}), to tell whether a saved road has a tunnel at
     * all ({@link #invalidateFloors}), and to keep lamps, posts and signs off tunnel stretches (PathFeatureMixin).
     *
     * @param road a saved RoadWeaver road. Its {@code spans()} and {@code roadSegmentList()} are read, nothing is
     *             changed. May be null.
     * @return one entry per segment of {@code road.roadSegmentList()}, in the same order: {@code true} if that
     *         segment lies inside a {@code TUNNEL} span. Null if the road is null or has no {@code TUNNEL} span.
     */
    @Nullable
    public static boolean[] getTunnelSegmentsByRoadData(@Nullable RoadData road) {
        List<RoadSpan> spans = road == null ? null : road.spans();
        if (spans == null || spans.isEmpty()) {
            return null;
        }
        List<RoadSegmentPlacement> segments = road.roadSegmentList();
        Map<BlockPos, Integer> indexOf = null;
        boolean[] mask = null;
        for (RoadSpan span : spans) {
            if (span == null || span.type() != SpanType.TUNNEL) {
                continue;
            }
            if (indexOf == null) {
                indexOf = new HashMap<>();
                for (int i = 0; i < segments.size(); i++) {
                    if (segments.get(i) != null && segments.get(i).middlePos() != null) {
                        indexOf.put(segments.get(i).middlePos(), i);
                    }
                }
                mask = new boolean[segments.size()];
            }
            Integer a = indexOf.get(span.start());
            Integer b = indexOf.get(span.end());
            if (a == null || b == null) {
                continue;
            }
            for (int i = Math.min(a, b); i <= Math.max(a, b); i++) {
                mask[i] = true;
            }
        }
        return mask;
    }
}
