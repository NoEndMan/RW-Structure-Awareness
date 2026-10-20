package net.flameslight.rwstructuresawareness.types.road;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.flameslight.rwstructuresawareness.utils.CoordinateKeys;

import java.util.Arrays;

/**
 * One 512x512-block region's RoadWeaver road columns: chunk -> one entry per road crossing that
 * chunk, holding a 256-bit column mask (bit (localZ << 4) | localX) and the road surface Y range
 * inside the chunk. Entries keep their road's index, so a structure can ignore the road leading to
 * it (its destination).
 *
 * Never mutated after construction: a road added later produces a new instance (withRoad) that
 * RoadInsideRegionChunksRecorder publishes, so worldgen threads read it lock-free. Never persisted.
 */
public final class RoadInsideRegionChunks {
    public static final RoadInsideRegionChunks EMPTY = new RoadInsideRegionChunks(new Long2ObjectOpenHashMap<>(),
            new long[0], new long[0], 0);

    private final Long2ObjectOpenHashMap<RoadChunkEntry[]> chunks;
    private final long[] ownerA;
    private final long[] ownerB;
    /** Widest road margin (half-width + 1) in this region. */
    private final int margin;

    /**
     * Creates a region map. Only withRoad and EMPTY build one.
     *
     * @param chunks chunk key to the roads crossing it, kept, not copied
     * @param ownerA per road index, one destination (CoordinateKeys.pack), kept, not copied
     * @param ownerB per road index, the other destination (CoordinateKeys.pack), kept, not copied
     * @param margin the widest road margin in the region (blocks)
     */
    private RoadInsideRegionChunks(Long2ObjectOpenHashMap<RoadChunkEntry[]> chunks, long[] ownerA, long[] ownerB,
                                   int margin) {
        this.chunks = chunks;
        this.ownerA = ownerA;
        this.ownerB = ownerB;
        this.margin = margin;
    }

    /**
     * Tells whether no road crosses the region.
     *
     * @return true if the map holds no chunk
     */
    public boolean isEmpty() {
        return chunks.isEmpty();
    }

    /**
     * Returns the widest road margin in the region.
     *
     * @return half-width + 1 of the widest road (blocks), 0 if there is none
     */
    public int margin() {
        return margin;
    }

    /**
     * Returns the roads crossing a chunk.
     *
     * @param chunkKey CoordinateKeys.chunkKey(chunkX, chunkZ)
     * @return one entry per road, shared, callers must not change it. Null if no road crosses the chunk
     */
    public RoadChunkEntry[] entriesAt(long chunkKey) {
        return chunks.get(chunkKey);
    }

    /**
     * Tells whether a road of this region leads to the structure starting in a chunk.
     *
     * @param road        the road's index (RoadChunkEntry.road)
     * @param startChunkX the structure's start chunk X (chunk coordinates)
     * @param startChunkZ the structure's start chunk Z (chunk coordinates)
     * @return true if one of the road's destinations is near that start chunk
     */
    public boolean roadLeadsTo(int road, int startChunkX, int startChunkZ) {
        return RoadColumns.ownerNear(ownerA[road], startChunkX, startChunkZ)
                || RoadColumns.ownerNear(ownerB[road], startChunkX, startChunkZ);
    }

    /**
     * Adds a road's columns that lie inside this region.
     *
     * @param road      the road, not changed
     * @param regionKey CoordinateKeys.pack(regionX, regionZ) of this region
     * @return a new map with the road added, or this one if none of its columns lies in the region
     */
    public RoadInsideRegionChunks withRoad(RoadColumns road, long regionKey) {
        long[] columns = road.packedColumns();
        int[] ys = road.surfaceY();
        Long2ObjectOpenHashMap<long[]> masks = new Long2ObjectOpenHashMap<>();
        Long2ObjectOpenHashMap<int[]> yRanges = new Long2ObjectOpenHashMap<>();
        for (int i = 0; i < columns.length; i++) {
            int x = CoordinateKeys.unpackA(columns[i]);
            int z = CoordinateKeys.unpackB(columns[i]);
            if (CoordinateKeys.regionOfBlock(x, z) != regionKey) {
                continue;
            }
            long chunkKey = CoordinateKeys.chunkKey(x >> 4, z >> 4);
            long[] mask = masks.computeIfAbsent(chunkKey, k -> new long[4]);
            int bit = ((z & 15) << 4) | (x & 15);
            mask[bit >>> 6] |= 1L << (bit & 63);
            int[] range = yRanges.computeIfAbsent(chunkKey, k -> new int[]{Integer.MAX_VALUE, Integer.MIN_VALUE});
            range[0] = Math.min(range[0], ys[i]);
            range[1] = Math.max(range[1], ys[i]);
        }
        if (masks.isEmpty()) {
            return this;
        }

        int roadIndex = ownerA.length;
        Long2ObjectOpenHashMap<RoadChunkEntry[]> copy = new Long2ObjectOpenHashMap<>(chunks);
        for (Long2ObjectMap.Entry<long[]> e : masks.long2ObjectEntrySet()) {
            long chunkKey = e.getLongKey();
            int[] range = yRanges.get(chunkKey);
            RoadChunkEntry added = new RoadChunkEntry(roadIndex, e.getValue(), range[0], range[1]);
            RoadChunkEntry[] existing = copy.get(chunkKey);
            RoadChunkEntry[] merged;
            if (existing == null) {
                merged = new RoadChunkEntry[]{added};
            } else {
                merged = Arrays.copyOf(existing, existing.length + 1);
                merged[existing.length] = added;
            }
            copy.put(chunkKey, merged);
        }
        long[] newOwnerA = Arrays.copyOf(ownerA, roadIndex + 1);
        long[] newOwnerB = Arrays.copyOf(ownerB, roadIndex + 1);
        newOwnerA[roadIndex] = road.ownerA();
        newOwnerB[roadIndex] = road.ownerB();
        return new RoadInsideRegionChunks(copy, newOwnerA, newOwnerB, Math.max(margin, road.margin()));
    }

    /**
     * Tells whether a column mask has a column inside a chunk-local rectangle.
     *
     * @param mask      a RoadChunkEntry mask, not changed
     * @param localMinX the rectangle's min X (chunk-local, 0..15)
     * @param localMinZ the rectangle's min Z (chunk-local, 0..15)
     * @param localMaxX the rectangle's max X (chunk-local, 0..15)
     * @param localMaxZ the rectangle's max Z (chunk-local, 0..15)
     * @return true if any masked column lies inside the rectangle
     */
    public static boolean maskIntersects(long[] mask, int localMinX, int localMinZ, int localMaxX, int localMaxZ) {
        long rowBits = ((1L << (localMaxX - localMinX + 1)) - 1L) << localMinX;
        for (int lz = localMinZ; lz <= localMaxZ; lz++) {
            long row = (mask[lz >>> 2] >>> ((lz & 3) << 4)) & 0xFFFFL;
            if ((row & rowBits) != 0L) {
                return true;
            }
        }
        return false;
    }
}
