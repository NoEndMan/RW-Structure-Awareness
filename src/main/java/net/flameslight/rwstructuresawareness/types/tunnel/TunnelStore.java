package net.flameslight.rwstructuresawareness.types.tunnel;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.flameslight.rwstructuresawareness.io.TunnelIO;
import net.flameslight.rwstructuresawareness.tunnel.TunnelCarver;
import net.flameslight.rwstructuresawareness.utils.CoordinateKeys;
import net.minecraft.server.level.ServerLevel;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tunnel dig segments planned by RoadTunnelPlanner, as boxes of 6 ints (minX, minZ, maxX, maxZ,
 * floorY, ceilY): the road surface sits at floorY, air is dug from floorY + 1 up to ceilY. A box crossing
 * regions is filed under each. Chunks may generate long after the road was planned (and after a restart), so
 * boxes are persisted by TunnelIO. Regions being generated are cached in memory, at most MAX_CACHED_REGIONS.
 */
public final class TunnelStore {
    public static final int BOX_INTS = 6;
    private static final int MAX_CACHED_REGIONS = 256;
    private static final int[] EMPTY = new int[0];

    private static final Object LOCK = new Object();
    private static Long2ObjectOpenHashMap<IntArrayList> pending = new Long2ObjectOpenHashMap<>();
    private static Long2ObjectOpenHashMap<IntArrayList> inFlight = null;
    private static final Map<Long, CachedTunnelRegion> CACHE = new ConcurrentHashMap<>();
    private static final AtomicLong CLOCK = new AtomicLong();
    /** A cache hit refreshes its LRU stamp only when the clock moved this far since. */
    private static final long LRU_SLACK = 64;

    // ---- Lifecycle ----

    /**
     * Opens a world's tunnels.
     *
     * @param overworld the overworld
     * @param dataDir   the world's data folder
     */
    public static void open(ServerLevel overworld, Path dataDir) {
        TunnelIO.open(overworld, dataDir);
        CACHE.clear();
        TunnelCarver.clearFloorsCache();
    }

    /** Writes the pending boxes and closes the world's tunnels. */
    public static void close() {
        flush();
        TunnelIO.close();
        CACHE.clear();
        TunnelCarver.clearFloorsCache();
    }

    /** Drops everything in memory, pending boxes included, without writing. */
    public static void reset() {
        TunnelIO.close();
        CACHE.clear();
        TunnelCarver.clearFloorsCache();
        synchronized (LOCK) {
            pending = new Long2ObjectOpenHashMap<>();
            inFlight = null;
        }
    }

    /**
     * Tells whether boxes are waiting to be written.
     *
     * @return true if a flush has something to write
     */
    public static boolean hasPending() {
        synchronized (LOCK) {
            return !pending.isEmpty();
        }
    }

    // ---- Writing ----

    /**
     * Adds one box under every region it touches. Ignored while no world is open.
     *
     * @param box BOX_INTS ints (minX, minZ, maxX, maxZ, floorY, ceilY), block coordinates, copied
     */
    public static void add(int[] box) {
        if (!TunnelIO.isOpen()) {
            return;
        }
        int rx0 = box[0] >> CoordinateKeys.REGION_BLOCK_SHIFT;
        int rz0 = box[1] >> CoordinateKeys.REGION_BLOCK_SHIFT;
        int rx1 = box[2] >> CoordinateKeys.REGION_BLOCK_SHIFT;
        int rz1 = box[3] >> CoordinateKeys.REGION_BLOCK_SHIFT;
        for (int rx = rx0; rx <= rx1; rx++) {
            for (int rz = rz0; rz <= rz1; rz++) {
                long regionKey = CoordinateKeys.pack(rx, rz);
                synchronized (LOCK) {
                    IntArrayList list = pending.computeIfAbsent(regionKey, k -> new IntArrayList());
                    list.addElements(list.size(), box);
                    CachedTunnelRegion cached = CACHE.get(regionKey);
                    if (cached != null) {
                        int[] old = cached.getBoxes();
                        int[] grown = Arrays.copyOf(old, old.length + BOX_INTS);
                        System.arraycopy(box, 0, grown, old.length, BOX_INTS);
                        cached.setBoxes(grown);
                    }
                }
            }
        }
    }

    /**
     * Writes the pending boxes to their region files. The batch stays readable as in-flight until written, so
     * a region read meanwhile still sees it.
     */
    public static void flush() {
        Long2ObjectOpenHashMap<IntArrayList> batch;
        synchronized (LOCK) {
            if (!TunnelIO.isOpen() || pending.isEmpty()) {
                return;
            }
            batch = pending;
            pending = new Long2ObjectOpenHashMap<>();
            inFlight = batch;
        }
        TunnelIO.appendRegions(batch);
        synchronized (LOCK) {
            if (inFlight == batch) {
                inFlight = null;
            }
        }
    }

    // ---- Reading ----

    /**
     * Returns every box filed under the region holding a chunk.
     *
     * @param chunkX the chunk's X (chunk coordinates)
     * @param chunkZ the chunk's Z (chunk coordinates)
     * @return the boxes, BOX_INTS ints each, written, pending and in-flight. Shared, callers must not change
     *         it. Empty when no world is open or the region has no tunnel
     */
    public static int[] boxesInRegionOf(int chunkX, int chunkZ) {
        if (!TunnelIO.isOpen()) {
            return EMPTY;
        }
        long regionKey = CoordinateKeys.regionOfChunk(chunkX, chunkZ);
        CachedTunnelRegion cached = CACHE.get(regionKey);
        if (cached != null) {
            /*
                Only bump the LRU clock now and then: paving asks once per road position, on every
                worldgen thread, and a shared counter hit that often would contend.
             */
            if (CLOCK.get() - cached.getLastUsed() > LRU_SLACK) {
                cached.setLastUsed(CLOCK.incrementAndGet());
            }
            return cached.getBoxes();
        }
        synchronized (LOCK) {
            cached = CACHE.get(regionKey);
            if (cached != null) {
                return cached.getBoxes();
            }
            IntArrayList all = TunnelIO.readRegion(regionKey);
            IntArrayList p = pending.get(regionKey);
            if (p != null) {
                all.addAll(p);
            }
            if (inFlight != null && inFlight.get(regionKey) != null) {
                all.addAll(inFlight.get(regionKey));
            }
            int[] boxes = all.isEmpty() ? EMPTY : all.toIntArray();
            CACHE.put(regionKey, new CachedTunnelRegion(boxes, CLOCK.incrementAndGet()));
            evictIfNeeded();
            return boxes;
        }
    }

    /** Drops the least recently used regions until the cache holds at most MAX_CACHED_REGIONS. */
    private static void evictIfNeeded() {
        while (CACHE.size() > MAX_CACHED_REGIONS) {
            Long oldest = null;
            long oldestUsed = Long.MAX_VALUE;
            for (Map.Entry<Long, CachedTunnelRegion> e : CACHE.entrySet()) {
                if (e.getValue().getLastUsed() < oldestUsed) {
                    oldestUsed = e.getValue().getLastUsed();
                    oldest = e.getKey();
                }
            }
            if (oldest == null) {
                return;
            }
            CACHE.remove(oldest);
        }
    }
}
