package net.flameslight.rwstructuresawareness.roadweaver;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.flameslight.rwstructuresawareness.logger.ModLogger;
import net.flameslight.rwstructuresawareness.logger.RoadDebug;
import net.flameslight.rwstructuresawareness.RoadInsideRegionChunksRecorder;
import net.flameslight.rwstructuresawareness.tunnel.TunnelCarver;
import net.flameslight.rwstructuresawareness.types.road.RoadColumns;
import net.flameslight.rwstructuresawareness.utils.CoordinateKeys;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.shiroha233.roadweaver.config.ConfigService;
import net.shiroha233.roadweaver.config.ModConfig;
import net.shiroha233.roadweaver.core.model.RoadData;
import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;
import net.shiroha233.roadweaver.persistence.files.RoadFileStorage;
import net.shiroha233.roadweaver.persistence.sharded.RoadShardStorage;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/** RoadWeaver storage and config access, and RoadData conversion. */
public final class RoadWeaverAccessor {
    private static final int DEFAULT_CLEAR_HEIGHT = 4;
    private static final int DEFAULT_SLOPE_STEP = 2;

    // ---- Called from RoadWeaver's storage (mixins) ----

    /**
     * Updates RWSA's road maps after RoadFileStorage.addRoad or replaceRoad returned.
     *
     * @param level the level the road was saved in, may be null (then nothing happens)
     * @param road  the saved road, not changed, may be null (then nothing happens)
     */
    public static void onRoadSaved(ServerLevel level, RoadData road) {
        if (level == null || road == null) {
            return;
        }
        try {
            RoadInsideRegionChunksRecorder.onRoadSaved(level, toColumns(road));
            TunnelCarver.invalidateFloors(road);
            RoadDebug.roadSaved(level, road);
        } catch (RuntimeException e) {
            ModLogger.error("Could not record a RoadWeaver road", e);
        }
    }

    /**
     * Reads the stored road a replaceRoad or deleteRoad is about to remove.
     *
     * @param level       the level
     * @param fingerprint the road's RoadWeaver fingerprint
     * @return the stored road, null if it can't be read
     */
    @Nullable
    public static RoadData loadStoredRoad(ServerLevel level, long fingerprint) {
        try {
            return RoadFileStorage.loadByFingerprint(level, fingerprint);
        } catch (RuntimeException e) {
            ModLogger.error("Could not read the RoadWeaver road being replaced", e);
            return null;
        }
    }

    /**
     * Updates RWSA's road maps after RoadFileStorage.replaceRoad or deleteRoad returned.
     *
     * @param level   the level, may be null (then nothing happens)
     * @param oldRoad the road as read before the call, null if unknown (every loaded region is refreshed)
     * @param newRoad the replacing road, null for a delete
     */
    public static void onRoadReplaced(ServerLevel level, @Nullable RoadData oldRoad, @Nullable RoadData newRoad) {
        if (level == null) {
            return;
        }
        try {
            RoadInsideRegionChunksRecorder.onRoadReplaced(level, oldRoad == null ? null : toColumns(oldRoad),
                    newRoad == null ? null : toColumns(newRoad));
            TunnelCarver.invalidateFloors(oldRoad); // null (unknown) drops every cached chunk
            if (newRoad != null) {
                TunnelCarver.invalidateFloors(newRoad);
            }
            RoadDebug.roadReplaced(level, oldRoad, newRoad);
        } catch (RuntimeException e) {
            ModLogger.error("Could not update a replaced RoadWeaver road", e);
        }
    }

    /**
     * Empties RWSA's road maps after RoadFileStorage.clearAll returned.
     *
     * @param level the level, may be null (then nothing happens)
     */
    public static void onRoadsCleared(ServerLevel level) {
        if (level != null) {
            RoadInsideRegionChunksRecorder.onRoadsCleared(level);
            TunnelCarver.invalidateFloors(null);
        }
    }

    // ---- Storage queries ----

    /**
     * Tells whether RoadWeaver has stored any road.
     *
     * @param level the level
     * @return true if RoadWeaver's index holds a road
     */
    public static boolean hasAnyRoad(ServerLevel level) {
        return RoadShardStorage.hasAnyRoad(level);
    }

    /**
     * Returns every stored road whose bounding rectangle overlaps an area. Reads RoadWeaver's files.
     *
     * @param level the level
     * @param minX  the area's min X (block, inclusive)
     * @param minZ  the area's min Z (block, inclusive)
     * @param maxX  the area's max X (block, inclusive)
     * @param maxZ  the area's max Z (block, inclusive)
     * @return the roads as RoadColumns, empty if none
     */
    public static List<RoadColumns> queryRoads(ServerLevel level, int minX, int minZ, int maxX, int maxZ) {
        List<RoadData> roads = RoadShardStorage.queryRect(level, minX, minZ, maxX, maxZ);
        if (roads == null || roads.isEmpty()) {
            return List.of();
        }
        List<RoadColumns> result = new ArrayList<>(roads.size());
        for (RoadData road : roads) {
            if (road != null) {
                result.add(toColumns(road));
            }
        }
        return result;
    }

    // ---- RoadWeaver config ----

    /**
     * Returns how far RoadWeaver's paving reaches from a center column.
     *
     * @return half the configured road width plus one (blocks)
     */
    public static int configuredRoadMargin() {
        ModConfig config = ConfigService.get();
        return marginForWidth(config == null ? 1 : config.roadAppearance().roadWidth());
    }

    /**
     * Returns the blocks RoadWeaver keeps clear above a road surface, exactly as its AboveColumnClearer clears
     * them after paving. Starts at the road's first air block.
     *
     * @return tunnelClearHeight (2..16) with RoadWeaver's tunnels enabled, else roadClearHeight (1..16)
     */
    public static int roadClearHeight() {
        ModConfig config = ConfigService.get();
        if (config == null) {
            return DEFAULT_CLEAR_HEIGHT;
        }
        return config.tunnelEnabled() ? Math.max(2, Math.min(16, config.tunnelClearHeight()))
                : Math.max(1, Math.min(16, config.roadClearHeight()));
    }

    /**
     * Returns RoadWeaver's maximum height change per two segments.
     *
     * @return the step (blocks, 0..8), 0 means flat only
     */
    public static int maxSlopeStepPerTwoSegments() {
        ModConfig config = ConfigService.get();
        return config == null ? DEFAULT_SLOPE_STEP : Math.max(0, Math.min(8, config.maxSlopeStepPerTwoSegments()));
    }

    /**
     * Returns the margin of a road of a given width.
     *
     * @param width the road width (blocks)
     * @return half the width plus one, at least 1 (blocks)
     */
    public static int marginForWidth(int width) {
        return Math.max(1, Math.max(1, width) / 2 + 1);
    }

    // ---- Conversion ----

    /**
     * Reduces a RoadWeaver road to its columns, surface heights, bounds, margin and destinations.
     *
     * @param road the road, not changed
     * @return the road's columns, RoadColumns.EMPTY if it has no segment
     */
    public static RoadColumns toColumns(RoadData road) {
        List<RoadSegmentPlacement> segments = road.roadSegmentList();
        if (segments == null || segments.isEmpty()) {
            return RoadColumns.EMPTY;
        }
        List<Integer> targetY = road.targetY();
        boolean useTargetY = targetY != null && targetY.size() == segments.size();

        // One surface Y per column, a column shared by two segments keeps the lower one.
        Long2IntOpenHashMap columns = new Long2IntOpenHashMap();
        columns.defaultReturnValue(Integer.MAX_VALUE);
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (int i = 0; i < segments.size(); i++) {
            RoadSegmentPlacement segment = segments.get(i);
            if (segment == null) {
                continue;
            }
            BlockPos middle = segment.middlePos();
            int y = useTargetY && targetY.get(i) != null ? targetY.get(i) : (middle != null ? middle.getY() : 0);
            if (middle != null) {
                addColumn(columns, middle.getX(), middle.getZ(), y);
                minX = Math.min(minX, middle.getX());
                minZ = Math.min(minZ, middle.getZ());
                maxX = Math.max(maxX, middle.getX());
                maxZ = Math.max(maxZ, middle.getZ());
            }
            List<BlockPos> positions = segment.positions();
            if (positions == null) {
                continue;
            }
            for (BlockPos pos : positions) {
                if (pos == null) {
                    continue;
                }
                addColumn(columns, pos.getX(), pos.getZ(), y);
                minX = Math.min(minX, pos.getX());
                minZ = Math.min(minZ, pos.getZ());
                maxX = Math.max(maxX, pos.getX());
                maxZ = Math.max(maxZ, pos.getZ());
            }
        }
        if (columns.isEmpty()) {
            return RoadColumns.EMPTY;
        }

        long[] packed = new long[columns.size()];
        int[] ys = new int[columns.size()];
        int n = 0;
        for (Long2IntOpenHashMap.Entry e : columns.long2IntEntrySet()) {
            packed[n] = e.getLongKey();
            ys[n] = e.getIntValue();
            n++;
        }

        /*
            Read raw, not through hasOwnerPair: a road without an owner (NO_OWNER_2D == Long.MIN_VALUE,
            "none" here too) still keeps the one it has.
         */
        return new RoadColumns(packed, ys, minX, minZ, maxX, maxZ, marginForWidth(road.width()),
                road.ownerA2dKey(), road.ownerB2dKey());
    }

    /**
     * Records a column's surface Y, keeping the lower one when it is already there.
     *
     * @param columns CoordinateKeys.pack(x, z) to surface Y, mutated
     * @param x       the column's X (block)
     * @param z       the column's Z (block)
     * @param y       the surface Y
     */
    private static void addColumn(Long2IntOpenHashMap columns, int x, int z, int y) {
        long key = CoordinateKeys.pack(x, z);
        if (y < columns.get(key)) {
            columns.put(key, y);
        }
    }
}
