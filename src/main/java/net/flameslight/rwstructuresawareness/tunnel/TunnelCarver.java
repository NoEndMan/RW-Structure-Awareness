package net.flameslight.rwstructuresawareness.tunnel;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import net.flameslight.rwstructuresawareness.road.JunctionRepair;
import net.flameslight.rwstructuresawareness.roadweaver.RoadWeaverAccessor;
import net.flameslight.rwstructuresawareness.types.road.RoadLevel;
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
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongPredicate;

/**
 * Digs the tunnels RoadTunnelPlanner recorded, and the cleared junctions of snapped side roads
 * (SnapJunctionShaper), shaped like RoadWeaver's own tunnels: on every road column of a tunnel stretch and
 * the road-wide strip between its consecutive segments (the road's own width, nothing beside it, see
 * roadFloors), from the road's first air block up clearHeight() blocks, exactly what RoadWeaver's
 * AboveColumnClearer clears above a paved road. The terrain beside and above the passage stays natural.
 * Done at both stages a chunk can be in when its road was planned:
 *  - terrain stage (BeardifierMixin): density forced to TUNNEL_DENSITY, i.e. air, at any depth
 *    (RoadWeaver's own road carving, at most -0.6, is too weak below the surface). It runs before
 *    RoadWeaver decides what it paves, so roadFloors leaves out the segments it never paves (its first
 *    and last segments), and segments in chunks decorated before the road was saved get no tunnel stretch.
 *  - decoration stage (right after RoadWeaver's PathFeature paved the chunk): each TUNNEL segment
 *    RoadWeaver actually paved (PathFeatureMixin notes every SegmentPaver.paveSegment) is cleared, at the
 *    heights it paved with, so a road block is never removed and a segment it skipped is never cleared.
 *    The chunk and its already-decorated neighbours in the WorldGenRegion are cleared again too where the
 *    road is really paved, since this chunk's decoration (e.g. a structure's foundations) may have written
 *    into their tunnels.
 *
 * TunnelStore's boxes (TunnelStore.BOX_INTS: minX, minZ, maxX, maxZ, floorY, ceilY) only index where
 * tunnels are. They don't define what is dug.
 */
public final class TunnelCarver {
    /** Added to the terrain density inside a tunnel, far below any solid terrain value. */
    public static final double TUNNEL_DENSITY = -20.0;
    /** How far outside an area a segment's middle may lie and still have columns or strips inside it (blocks). */
    private static final int REACH = 32;
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
     * Carves the tunnels around a chunk PathFeature just decorated.
     * <p>
     * Decoration stage, at the end of RoadWeaver's PathFeature for {@code center}. First the tunnel segments
     * RoadWeaver paved there are cleared (clearPavedSegments), so nothing it skipped is cleared. Then the chunk and
     * every already-decorated neighbour present in the WorldGenRegion are cleared again where the road is really
     * paved (carveChunk): this chunk's decoration (e.g. a structure's foundations) may have written into them.
     *
     * @param level  the WorldGenRegion being decorated, its blocks are changed
     * @param server the server level, to read saved roads
     * @param center the chunk PathFeature just paved
     */
    public static void carveAround(WorldGenLevel level, ServerLevel server, ChunkPos center) {
        clearPavedSegments(level, server, center);
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
        // RoadWeaver paved a TUNNEL segment once its middle's chunk was decorated (this one is being decorated now).
        LongPredicate paved = key -> key == center.toLong() || isDecorated(level, new ChunkPos(key))
                || RoadLevel.isDecorated(server, ChunkPos.getX(key), ChunkPos.getZ(key));
        for (ChunkPos target : targets) {
            Long2IntOpenHashMap floors = roadFloors(RoadShardStorage.queryRect(server, target.getMinBlockX(),
                    target.getMinBlockZ(), target.getMaxBlockX(), target.getMaxBlockZ()), target.getMinBlockX(),
                    target.getMinBlockZ(), target.getMaxBlockX(), target.getMaxBlockZ(), paved);
            carveChunk(level, target, floors, clear);
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
     * Computes the first air Y on every column a tunnel stretch clears inside an area.
     * <p>
     * A TUNNEL segment clears its own paved columns, plus a road-wide strip along the line from the previous
     * segment's middle to its own. At the tunnel stretch's last segment it also clears the strip into the next
     * segment outside the stretch. On a continuous road the strips only add the corner wedges where it turns.
     * Between a snapped side road's junction pieces (SnapJunctionShaper) they open the ground, so the pieces join
     * each other, the side road's own route and the main road. The heights are computed exactly as RoadWeaver's SegmentPaver does
     * (RoadHeightInterpolator over the road's segment middles and saved heights). The lowest wins where two roads
     * share a column.
     * <p>
     * A column an ordinary road segment paves (not a TUNNEL one) is left out when that road's paved block lies in
     * the clearing's height range: the clearing would dig it out, and that road keeps its own space clear anyway.
     * A tunnel well under another road is still dug.
     *
     * @param roads saved roads, not changed, may be null
     * @param minX  the area's min X (block, inclusive)
     * @param minZ  the area's min Z (block, inclusive)
     * @param maxX  the area's max X (block, inclusive)
     * @param maxZ  the area's max Z (block, inclusive)
     * @return CoordinateKeys.pack(x, z) to the first air Y, default Integer.MAX_VALUE for other columns
     */
    public static Long2IntOpenHashMap roadFloors(List<RoadData> roads, int minX, int minZ, int maxX, int maxZ) {
        return roadFloors(roads, minX, minZ, maxX, maxZ, null);
    }

    /**
     * Computes the first air Y on every column a tunnel stretch clears inside an area, for the TUNNEL segments whose
     * middle lies in a chunk {@code pavedChunks} accepts (see the other roadFloors).
     *
     * @param roads       saved roads, not changed, may be null
     * @param minX        the area's min X (block, inclusive)
     * @param minZ        the area's min Z (block, inclusive)
     * @param maxX        the area's max X (block, inclusive)
     * @param maxZ        the area's max Z (block, inclusive)
     * @param pavedChunks ChunkPos.asLong of a segment middle's chunk to true when RoadWeaver paved it, null for
     *                    every chunk
     * @return CoordinateKeys.pack(x, z) to the first air Y, default Integer.MAX_VALUE for other columns
     */
    public static Long2IntOpenHashMap roadFloors(List<RoadData> roads, int minX, int minZ, int maxX, int maxZ,
                                                 @Nullable LongPredicate pavedChunks) {
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
            List<BlockPos> middles = middlesOf(segments);
            int[] ys = heightsOf(targetY);
            int radius = Math.max(0, road.width() / 2);
            for (int i = 0; i < segments.size(); i++) {
                if (!tunnel[i] || isPositionless(segments.get(i)) || !JunctionRepair.isPavedIndex(i, segments.size())
                        || farFrom(segments.get(i).middlePos(), minX, minZ, maxX, maxZ)) {
                    continue; // PathFeature never paves it: nothing to clear
                }
                BlockPos m = segments.get(i).middlePos();
                if (pavedChunks != null && !pavedChunks.test(ChunkPos.asLong(m.getX() >> 4, m.getZ() >> 4))) {
                    continue; // not paved (yet)
                }
                List<BlockPos> columns = clearedColumns(segments, tunnel, i, radius, ys[i], minX, minZ, maxX, maxZ);
                if (columns.isEmpty()) {
                    continue;
                }
                int[] heights = RoadHeightInterpolator.batchInterpolate(columns, i, middles, ys);
                for (int p = 0; p < columns.size(); p++) {
                    long key = CoordinateKeys.pack(columns.get(p).getX(), columns.get(p).getZ());
                    floors.put(key, Math.min(floors.get(key), heights[p]));
                }
            }
        }
        if (!floors.isEmpty()) {
            keepOpenRoads(roads, floors, minX, minZ, maxX, maxZ);
        }
        return floors;
    }

    /**
     * Lists the columns one TUNNEL segment clears inside an area: its paved columns, the road-wide strip from the
     * previous segment's middle to its own, and at the tunnel stretch's end the strip into the next segment.
     * A strip only leads to a segment PathFeature paves (it has positions and lies in the paved index range).
     *
     * @param segments the road's segments, not changed
     * @param tunnel   per segment, true inside a tunnel stretch, not changed
     * @param i        the TUNNEL segment's index
     * @param radius   half the road width (blocks)
     * @param y        the segment's height, given to the columns (RoadHeightInterpolator reads X and Z)
     * @param minX     the area's min X (block, inclusive)
     * @param minZ     the area's min Z (block, inclusive)
     * @param maxX     the area's max X (block, inclusive)
     * @param maxZ     the area's max Z (block, inclusive)
     * @return the columns inside the area, each once, in a new list
     */
    private static List<BlockPos> clearedColumns(List<RoadSegmentPlacement> segments, boolean[] tunnel, int i,
                                                 int radius, int y, int minX, int minZ, int maxX, int maxZ) {
        LongLinkedOpenHashSet cells = new LongLinkedOpenHashSet();
        for (BlockPos p : segments.get(i).positions()) {
            cells.add(CoordinateKeys.pack(p.getX(), p.getZ()));
        }
        BlockPos middle = segments.get(i).middlePos();
        int n = segments.size();
        if (i > 0 && !isPositionless(segments.get(i - 1)) && JunctionRepair.isPavedIndex(i - 1, n)) {
            strip(segments.get(i - 1).middlePos(), middle, radius, cells);
        }
        if (i + 1 < n && !tunnel[i + 1] && !isPositionless(segments.get(i + 1))
                && JunctionRepair.isPavedIndex(i + 1, n)) {
            strip(middle, segments.get(i + 1).middlePos(), radius, cells);
        }
        List<BlockPos> columns = new ArrayList<>(cells.size());
        for (long cell : cells) {
            int x = CoordinateKeys.unpackA(cell);
            int z = CoordinateKeys.unpackB(cell);
            if (x >= minX && x <= maxX && z >= minZ && z <= maxZ) {
                columns.add(new BlockPos(x, y, z));
            }
        }
        return columns;
    }

    /**
     * Adds the columns within a radius of the line between two middles (X and Z only), not past either middle: a
     * road-wide strip from one segment to the next.
     *
     * @param a      one end, not changed
     * @param b      the other end, not changed
     * @param radius half the road width (blocks)
     * @param cells  CoordinateKeys.pack(x, z) of the columns, mutated
     */
    public static void strip(BlockPos a, BlockPos b, int radius, LongLinkedOpenHashSet cells) {
        double ax = a.getX();
        double az = a.getZ();
        double dx = b.getX() - ax;
        double dz = b.getZ() - az;
        double len2 = dx * dx + dz * dz;
        double reach = (radius + 0.5) * (radius + 0.5);
        int minX = Math.min(a.getX(), b.getX()) - radius - 1;
        int maxX = Math.max(a.getX(), b.getX()) + radius + 1;
        int minZ = Math.min(a.getZ(), b.getZ()) - radius - 1;
        int maxZ = Math.max(a.getZ(), b.getZ()) + radius + 1;
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                double t = len2 < 1e-9 ? 0 : ((x - ax) * dx + (z - az) * dz) / len2;
                if (t < 0 || t > 1) {
                    continue; // past either middle: that segment's own columns cover its end, nothing beyond it
                }
                double ex = x - (ax + t * dx);
                double ez = z - (az + t * dz);
                if (ex * ex + ez * ez <= reach) {
                    cells.add(CoordinateKeys.pack(x, z));
                }
            }
        }
    }

    /**
     * Drops the cleared columns where an ordinary road segment (not a TUNNEL one) has its paved block inside the
     * clearing's height range, from the floor up to clearHeight() blocks.
     *
     * @param roads  saved roads, not changed
     * @param floors CoordinateKeys.pack(x, z) to the first air Y of the cleared columns, mutated
     * @param minX   the area's min X (block, inclusive)
     * @param minZ   the area's min Z (block, inclusive)
     * @param maxX   the area's max X (block, inclusive)
     * @param maxZ   the area's max Z (block, inclusive)
     */
    private static void keepOpenRoads(List<RoadData> roads, Long2IntOpenHashMap floors, int minX, int minZ, int maxX,
                                      int maxZ) {
        int clear = clearHeight();
        for (RoadData road : roads) {
            List<RoadSegmentPlacement> segments = road == null ? null : road.roadSegmentList();
            List<Integer> targetY = road == null ? null : road.targetY();
            if (segments == null || targetY == null || targetY.size() != segments.size()) {
                continue;
            }
            boolean[] tunnel = getTunnelSegmentsByRoadData(road);
            List<BlockPos> middles = null;
            int[] ys = null;
            for (int i = 0; i < segments.size(); i++) {
                if (tunnel != null && tunnel[i] || isPositionless(segments.get(i))
                        || farFrom(segments.get(i).middlePos(), minX, minZ, maxX, maxZ)) {
                    continue;
                }
                List<BlockPos> shared = new ArrayList<>();
                for (BlockPos p : segments.get(i).positions()) {
                    if (floors.containsKey(CoordinateKeys.pack(p.getX(), p.getZ()))) {
                        shared.add(p);
                    }
                }
                if (shared.isEmpty()) {
                    continue;
                }
                if (middles == null) {
                    middles = middlesOf(segments);
                    ys = heightsOf(targetY);
                }
                int[] heights = RoadHeightInterpolator.batchInterpolate(shared, i, middles, ys);
                for (int p = 0; p < shared.size(); p++) {
                    long key = CoordinateKeys.pack(shared.get(p).getX(), shared.get(p).getZ());
                    int floor = floors.get(key);
                    int block = heights[p] - 1;
                    if (block >= floor && block < floor + clear) {
                        floors.remove(key);
                    }
                }
            }
        }
    }

    /**
     * Tells whether a segment middle lies too far from an area for its columns or strips to reach into it.
     *
     * @param middle the segment's middle
     * @param minX   the area's min X (block, inclusive)
     * @param minZ   the area's min Z (block, inclusive)
     * @param maxX   the area's max X (block, inclusive)
     * @param maxZ   the area's max Z (block, inclusive)
     * @return true if it lies more than REACH blocks outside the area
     */
    private static boolean farFrom(BlockPos middle, int minX, int minZ, int maxX, int maxZ) {
        return middle.getX() < minX - REACH || middle.getX() > maxX + REACH || middle.getZ() < minZ - REACH
                || middle.getZ() > maxZ + REACH;
    }

    /**
     * Collects a road's segment middles.
     *
     * @param segments the road's segments, not changed
     * @return their middles, in order, in a new list
     */
    private static List<BlockPos> middlesOf(List<RoadSegmentPlacement> segments) {
        List<BlockPos> middles = new ArrayList<>(segments.size());
        for (RoadSegmentPlacement segment : segments) {
            middles.add(segment.middlePos());
        }
        return middles;
    }

    /**
     * Copies a road's saved heights.
     *
     * @param targetY the road's heights, not changed
     * @return them as a new array
     */
    private static int[] heightsOf(List<Integer> targetY) {
        int[] ys = new int[targetY.size()];
        for (int i = 0; i < ys.length; i++) {
            ys[i] = targetY.get(i);
        }
        return ys;
    }

    /**
     * Tells whether a segment paves nothing (a cut road's anchor or lead segment).
     *
     * @param segment the segment, may be null
     * @return true if it has no positions
     */
    private static boolean isPositionless(@Nullable RoadSegmentPlacement segment) {
        return segment == null || segment.positions() == null || segment.positions().isEmpty();
    }

    /**
     * Clears again the tunnel road columns of a chunk, as RoadWeaver's AboveColumnClearer does above a paved road.
     * The floors come from the TUNNEL segments RoadWeaver already paved (carveAround), so a chunk it never paved is
     * left alone.
     *
     * @param level      the WorldGenRegion, its blocks are changed
     * @param chunk      the chunk to carve, columns outside it are skipped
     * @param roadFloors CoordinateKeys.pack(x, z) to the road's first air Y, not changed
     * @param clear      blocks to clear above the floor
     */
    private static void carveChunk(WorldGenLevel level, ChunkPos chunk, Long2IntOpenHashMap roadFloors, int clear) {
        for (Long2IntMap.Entry e : roadFloors.long2IntEntrySet()) {
            int x = CoordinateKeys.unpackA(e.getLongKey());
            int z = CoordinateKeys.unpackB(e.getLongKey());
            if (x >> 4 == chunk.x && z >> 4 == chunk.z) {
                clearColumn(level, x, z, e.getIntValue(), clear);
            }
        }
    }

    /**
     * Clears one column above a road floor: {@code clear} blocks from the road's first air block (the road block
     * below is kept), then a plant or snow layer resting on top of the passage.
     *
     * @param level the WorldGenRegion, its blocks are changed
     * @param x     the column's X (block)
     * @param z     the column's Z (block)
     * @param floor the road's first air Y
     * @param clear blocks to clear above the floor
     */
    private static void clearColumn(WorldGenLevel level, int x, int z, int floor, int clear) {
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
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

    /** One segment RoadWeaver's PathFeature just paved, noted so its tunnel columns are cleared afterwards. */
    private record PavedSegment(RoadData road, boolean[] tunnel, int index, List<BlockPos> middles, int[] ys) {
    }

    /** The TUNNEL segments PathFeature paved in the chunk it's decorating on this thread. */
    private static final ThreadLocal<List<PavedSegment>> PAVED = ThreadLocal.withInitial(ArrayList::new);
    /** Per road being paved on this thread, its TUNNEL flags (NO_TUNNEL when it has none). */
    private static final ThreadLocal<Map<RoadData, boolean[]>> TUNNEL_MASKS =
            ThreadLocal.withInitial(IdentityHashMap::new);
    private static final boolean[] NO_TUNNEL = new boolean[0];

    /** Forgets the segments noted for the previous chunk. Called when PathFeature starts decorating a chunk. */
    public static void pavingStarted() {
        PAVED.get().clear();
        TUNNEL_MASKS.get().clear();
    }

    /**
     * Notes a segment RoadWeaver's SegmentPaver just paved: if it lies in a tunnel stretch, its columns are cleared once
     * PathFeature finishes the chunk (clearPavedSegments).
     *
     * @param road    the road being paved, not changed
     * @param index   the segment's index
     * @param middles PathFeature's segment middles for that road, not changed
     * @param ys      the heights PathFeature paved with, not changed
     */
    public static void notePaved(RoadData road, int index, List<BlockPos> middles, int[] ys) {
        if (road == null || middles == null || ys == null) {
            return;
        }
        boolean[] tunnel = TUNNEL_MASKS.get().computeIfAbsent(road, r -> {
            boolean[] mask = getTunnelSegmentsByRoadData(r);
            return mask == null ? NO_TUNNEL : mask;
        });
        if (index >= 0 && index < tunnel.length && tunnel[index]) {
            PAVED.get().add(new PavedSegment(road, tunnel, index, middles, ys));
        }
    }

    /**
     * Clears the tunnel columns of the segments RoadWeaver paved in a chunk (notePaved): their own columns, and the
     * road-wide strips to their neighbours (clearedColumns), at the heights RoadWeaver paved with. A segment
     * RoadWeaver skipped is never cleared here. Columns of ordinary roads at about the same height are left out
     * (keepOpenRoads).
     *
     * @param level  the WorldGenRegion being decorated, its blocks are changed
     * @param server the server level, to read saved roads
     * @param center the chunk PathFeature just decorated, the clearing stays within one chunk of it
     */
    private static void clearPavedSegments(WorldGenLevel level, ServerLevel server, ChunkPos center) {
        List<PavedSegment> paved = PAVED.get();
        if (paved.isEmpty()) {
            return;
        }
        int minX = (center.x - 1) << 4;
        int minZ = (center.z - 1) << 4;
        int maxX = ((center.x + 1) << 4) + 15;
        int maxZ = ((center.z + 1) << 4) + 15;
        Long2IntOpenHashMap floors = new Long2IntOpenHashMap();
        floors.defaultReturnValue(Integer.MAX_VALUE);
        for (PavedSegment p : paved) {
            List<RoadSegmentPlacement> segments = p.road().roadSegmentList();
            if (segments == null || p.ys().length != segments.size() || p.middles().size() != segments.size()
                    || p.tunnel().length != segments.size()) {
                continue;
            }
            List<BlockPos> columns = clearedColumns(segments, p.tunnel(), p.index(),
                    Math.max(0, p.road().width() / 2), p.ys()[p.index()], minX, minZ, maxX, maxZ);
            if (columns.isEmpty()) {
                continue;
            }
            int[] heights = RoadHeightInterpolator.batchInterpolate(columns, p.index(), p.middles(), p.ys());
            for (int c = 0; c < columns.size(); c++) {
                long key = CoordinateKeys.pack(columns.get(c).getX(), columns.get(c).getZ());
                floors.put(key, Math.min(floors.get(key), heights[c]));
            }
        }
        paved.clear();
        if (floors.isEmpty()) {
            return;
        }
        keepOpenRoads(RoadShardStorage.queryRect(server, minX, minZ, maxX, maxZ), floors, minX, minZ, maxX, maxZ);
        int clear = clearHeight();
        for (Long2IntMap.Entry e : floors.long2IntEntrySet()) {
            clearColumn(level, CoordinateKeys.unpackA(e.getLongKey()), CoordinateKeys.unpackB(e.getLongKey()),
                    e.getIntValue(), clear);
        }
    }

    /**
     * Finds which segments of a saved road run through a tunnel.
     * <p>
     * RoadWeaver marks a stretch of a road with a {@code RoadSpan(start, end, type)}: it covers every segment from
     * the one whose middle position is {@code start} to the one whose middle position is {@code end}, with a
     * {@link SpanType} such as {@code BRIDGE} or {@code TUNNEL}. This mod calls them bridge stretches and tunnel
     * stretches. A tunnel stretch is a stretch of the road, not a piece of the tunnel's shape. RoadTunnelPlanner adds
     * one tunnel stretch per lowered part of the road, its ramps included. Stretches point at segments by middle
     * position, while carving and decorating walk a road by segment index, so this turns the tunnel stretches into
     * one flag per segment.
     * <p>
     * Used to dig only the tunnel road columns ({@link #roadFloors}), to tell whether a saved road has a tunnel at
     * all ({@link #invalidateFloors}), and to keep lamps, posts and signs off tunnel stretches (PathFeatureMixin).
     *
     * @param road a saved RoadWeaver road. Its {@code spans()} and {@code roadSegmentList()} are read, nothing is
     *             changed. May be null.
     * @return one entry per segment of {@code road.roadSegmentList()}, in the same order: {@code true} if that
     *         segment lies inside a tunnel stretch. Null if the road is null or has no tunnel stretch.
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
