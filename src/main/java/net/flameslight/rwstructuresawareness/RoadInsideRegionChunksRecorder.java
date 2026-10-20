package net.flameslight.rwstructuresawareness;

import net.flameslight.rwstructuresawareness.types.road.RoadLevel;
import net.flameslight.rwstructuresawareness.roadweaver.RoadWeaverAccessor;
import net.flameslight.rwstructuresawareness.types.road.RoadColumns;
import net.flameslight.rwstructuresawareness.types.road.RoadInsideRegionChunks;
import net.flameslight.rwstructuresawareness.types.road.RoadRegionHolder;
import net.flameslight.rwstructuresawareness.utils.CoordinateKeys;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Keeps the road maps (RoadInsideRegionChunks) of the regions structures are generating in. A
 * region is filled from RoadWeaver's storage the first time a structure there asks this session,
 * then kept current by RoadWeaver's own road writes (forwarded here from mixins, which hold no data).
 *
 * RoadWeaver's queries re-read its whole road index from disk under one overworld-wide lock, so each
 * region queries at most once while it stays loaded. At most MAX_LOADED_REGIONS are kept. The least
 * recently used is dropped first (a dropped region simply fills again if asked).
 */
public final class RoadInsideRegionChunksRecorder {
    private static final int MAX_LOADED_REGIONS = 512;
    private static final int LOCK_STRIPES = 64;

    private static final int ROADS_UNKNOWN = 0;
    private static final int ROADS_NONE = 1;
    private static final int ROADS_SOME = 2;

    private static final Map<Long, RoadRegionHolder> REGIONS = new ConcurrentHashMap<>();
    private static final Object[] LOCKS = new Object[LOCK_STRIPES];
    private static final AtomicLong CLOCK = new AtomicLong();
    /** Whether RoadWeaver has any road at all, asked once per session (one index read). */
    private static volatile int roadsState = ROADS_UNKNOWN;

    static {
        for (int i = 0; i < LOCK_STRIPES; i++) {
            LOCKS[i] = new Object();
        }
    }

    /**
     * Tells whether RoadWeaver may have any road, so callers can skip all work when it has none.
     *
     * @return false only when RoadWeaver is known to have no road at all, or no road level is open
     */
    public static boolean mayRoadsExist() {
        int state = roadsState;
        if (state == ROADS_UNKNOWN) {
            ServerLevel level = RoadLevel.level();
            if (level == null) {
                return false;
            }
            boolean any = RoadWeaverAccessor.hasAnyRoad(level);
            synchronized (RoadInsideRegionChunksRecorder.class) {
                if (roadsState == ROADS_UNKNOWN) {
                    roadsState = any ? ROADS_SOME : ROADS_NONE; // a road saved meanwhile already set SOME
                }
                state = roadsState;
            }
        }
        return state != ROADS_NONE;
    }

    /**
     * Returns the road map of the region holding a chunk, filling it from RoadWeaver's storage on first use.
     *
     * @param chunkX the chunk's X (chunk coordinates)
     * @param chunkZ the chunk's Z (chunk coordinates)
     * @return the region's road map, null outside the road level
     */
    @Nullable
    public static RoadInsideRegionChunks regionAt(int chunkX, int chunkZ) {
        long regionKey = CoordinateKeys.regionOfChunk(chunkX, chunkZ);
        RoadRegionHolder holder = REGIONS.get(regionKey);
        if (holder != null) {
            holder.setLastUsed(CLOCK.incrementAndGet());
            return holder.getChunks();
        }
        ServerLevel level = RoadLevel.level();
        if (level == null) {
            return null;
        }
        /*
            Held across RoadWeaver's query on purpose: onRoadSaved takes the same lock, so a road written
            while this fill reads is either already in the query or added right after.
         */
        synchronized (lock(regionKey)) {
            holder = REGIONS.get(regionKey);
            if (holder != null) {
                return holder.getChunks();
            }
            RoadInsideRegionChunks filled = RoadInsideRegionChunks.EMPTY;
            if (mayRoadsExist()) {
                int minX = CoordinateKeys.unpackA(regionKey) << CoordinateKeys.REGION_BLOCK_SHIFT;
                int minZ = CoordinateKeys.unpackB(regionKey) << CoordinateKeys.REGION_BLOCK_SHIFT;
                int size = 1 << CoordinateKeys.REGION_BLOCK_SHIFT;
                for (RoadColumns road : RoadWeaverAccessor.queryRoads(level, minX, minZ, minX + size - 1, minZ + size - 1)) {
                    filled = filled.withRoad(road, regionKey);
                }
            }
            REGIONS.put(regionKey, new RoadRegionHolder(filled, CLOCK.incrementAndGet()));
            evictIfNeeded();
            return filled;
        }
    }

    /**
     * Adds a road RoadWeaver added to or replaced in its storage to every loaded region it touches.
     *
     * @param level the level the road was saved in, ignored unless it is the road level
     * @param road  the saved road, not changed
     */
    public static void onRoadSaved(ServerLevel level, RoadColumns road) {
        if (road.isEmpty() || !RoadLevel.isRoadLevel(level)) {
            return;
        }
        roadsState = ROADS_SOME;
        int rx0 = (road.minX() - road.margin()) >> CoordinateKeys.REGION_BLOCK_SHIFT;
        int rz0 = (road.minZ() - road.margin()) >> CoordinateKeys.REGION_BLOCK_SHIFT;
        int rx1 = (road.maxX() + road.margin()) >> CoordinateKeys.REGION_BLOCK_SHIFT;
        int rz1 = (road.maxZ() + road.margin()) >> CoordinateKeys.REGION_BLOCK_SHIFT;
        for (int rx = rx0; rx <= rx1; rx++) {
            for (int rz = rz0; rz <= rz1; rz++) {
                long regionKey = CoordinateKeys.pack(rx, rz);
                /*
                    Locked even while unloaded: see regionAt. An unloaded region stays unloaded. Its first
                    fill reads this road from RoadWeaver's storage, where it already is.
                 */
                synchronized (lock(regionKey)) {
                    RoadRegionHolder holder = REGIONS.get(regionKey);
                    if (holder != null) {
                        holder.setChunks(holder.getChunks().withRoad(road, regionKey));
                    }
                }
            }
        }
    }

    /**
     * Drops the loaded regions a replaced or deleted road touches.
     * <p>
     * A region map can only grow, so every loaded region either shape touches is dropped instead, and
     * refills from RoadWeaver's storage (which now holds only the new shape) the next time a structure asks.
     *
     * @param level   the level the road was changed in, ignored unless it is the road level
     * @param oldRoad the road's old shape, null if unknown (then every loaded region is dropped)
     * @param newRoad the road's new shape, null when it was deleted
     */
    public static void onRoadReplaced(ServerLevel level, @Nullable RoadColumns oldRoad, @Nullable RoadColumns newRoad) {
        if (!RoadLevel.isRoadLevel(level)) {
            return;
        }
        if (newRoad != null && !newRoad.isEmpty()) {
            roadsState = ROADS_SOME;
            dropRegionsOf(newRoad);
        }
        dropRegionsOf(oldRoad);
    }

    /**
     * Drops every loaded region a road's bounds touch.
     *
     * @param road the road, null or empty drops every loaded region
     */
    private static void dropRegionsOf(@Nullable RoadColumns road) {
        if (road == null || road.isEmpty()) {
            for (Long regionKey : REGIONS.keySet()) {
                synchronized (lock(regionKey)) {
                    REGIONS.remove(regionKey);
                }
            }
            return;
        }
        int rx0 = (road.minX() - road.margin()) >> CoordinateKeys.REGION_BLOCK_SHIFT;
        int rz0 = (road.minZ() - road.margin()) >> CoordinateKeys.REGION_BLOCK_SHIFT;
        int rx1 = (road.maxX() + road.margin()) >> CoordinateKeys.REGION_BLOCK_SHIFT;
        int rz1 = (road.maxZ() + road.margin()) >> CoordinateKeys.REGION_BLOCK_SHIFT;
        for (int rx = rx0; rx <= rx1; rx++) {
            for (int rz = rz0; rz <= rz1; rz++) {
                long regionKey = CoordinateKeys.pack(rx, rz);
                synchronized (lock(regionKey)) {
                    REGIONS.remove(regionKey);
                }
            }
        }
    }

    /**
     * Empties every loaded region after RoadWeaver deleted every road.
     *
     * @param level the level, ignored unless it is the road level
     */
    public static void onRoadsCleared(ServerLevel level) {
        if (!RoadLevel.isRoadLevel(level)) {
            return;
        }
        roadsState = ROADS_NONE;
        for (Map.Entry<Long, RoadRegionHolder> e : REGIONS.entrySet()) {
            synchronized (lock(e.getKey())) {
                e.getValue().setChunks(RoadInsideRegionChunks.EMPTY);
            }
        }
    }

    /** Forgets every region and whether roads exist. Called when a world opens or closes. */
    public static void reset() {
        REGIONS.clear();
        roadsState = ROADS_UNKNOWN;
    }

    /** Drops the least recently used regions until at most MAX_LOADED_REGIONS are loaded. */
    private static void evictIfNeeded() {
        while (REGIONS.size() > MAX_LOADED_REGIONS) {
            Long oldest = null;
            long oldestUsed = Long.MAX_VALUE;
            for (Map.Entry<Long, RoadRegionHolder> e : REGIONS.entrySet()) {
                if (e.getValue().getLastUsed() < oldestUsed) {
                    oldestUsed = e.getValue().getLastUsed();
                    oldest = e.getKey();
                }
            }
            if (oldest == null) {
                return;
            }
            REGIONS.remove(oldest);
        }
    }

    /**
     * Returns the lock stripe of a region.
     *
     * @param regionKey CoordinateKeys.pack(regionX, regionZ)
     * @return one of LOCK_STRIPES shared lock objects
     */
    private static Object lock(long regionKey) {
        int h = (int) (regionKey ^ (regionKey >>> 32));
        h ^= h >>> 16;
        return LOCKS[h & (LOCK_STRIPES - 1)];
    }
}
