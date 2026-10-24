package net.flameslight.rwstructuresawareness.types.road;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.flameslight.rwstructuresawareness.StructureChunkRecorder;
import net.flameslight.rwstructuresawareness.compat.zones.ZonesCompat;
import net.flameslight.rwstructuresawareness.config.CommonConfig;
import net.flameslight.rwstructuresawareness.types.structure.StructureChunkStorage;
import net.flameslight.rwstructuresawareness.types.zones.ZonePerimeter;
import net.flameslight.rwstructuresawareness.utils.CoordinateKeys;
import net.minecraft.core.BlockPos;
import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * The recorded structures (and zonesWithNoRoads zones) one RoadWeaver road must avoid. Built per road
 * computation and dropped with it, so nothing here outlives a single road.
 *
 * Every recorded region overlapping the road's area is loaded once, up front, into one map of 2x2-block
 * cells: every recorded footprint widened by the road's margin, each cell keeping the Y range of the
 * structures over it. A column is blocked only when that range overlaps the road's own height band, so
 * structures buried below the road (or floating above it) never block it. A query outside the area
 * (rare: a path drifting past it) loads that region then, copy-on-write.
 *
 * The road's own destinations (start chunk at the road's start or end) block it like any other
 * structure: a road ends at its destination's edge, never runs through its pieces. Their cells are
 * also remembered, so RoadTunnelPlanner never digs under a destination.
 *
 * zonesWithNoRoads zones (with Zones installed), and zones nested in them, block at every height, widened by the
 * margin, and are never dug under either: the road routes around them or stops at their edge.
 */
public final class RoadObstacles {
    /** The route's search area is its start-end rectangle widened by this + a quarter of its length. */
    private static final int AREA_PADDING = 128;
    private static final int CELL_SHIFT = 1;
    /** lowestStructureY result for "not blocked". */
    public static final int NOT_BLOCKED = Integer.MAX_VALUE;
    /** lowestStructureY inside a no-road zone: blocks any band. */
    private static final int ZONE_FLOOR = Integer.MIN_VALUE / 2;
    private static final long NO_RANGE = StructureChunkRecorder.packRange(Integer.MAX_VALUE, Integer.MIN_VALUE);

    @Nullable
    private final StructureChunkStorage store;
    private final int margin;
    private final int startX, startZ, endX, endZ;
    /** Area loaded up front (blocks, inclusive). */
    private final int areaMinX, areaMinZ, areaMaxX, areaMaxZ;
    private final LongOpenHashSet loadedRegions = new LongOpenHashSet();
    /** Last region known to be loaded or empty (lazy loads outside the area only). */
    private volatile long lastCheckedRegion = Long.MIN_VALUE;
    /** Cell -> packed Y range. Replaced (never mutated) after construction, so readers need no lock. */
    private volatile Long2LongOpenHashMap cells;
    /** Cells covered by the road's own destinations. Replaced (never mutated) like `cells`. */
    private volatile LongOpenHashSet destinationCells;
    /** Cell -> start chunk of a structure over it. Only kept with debugMod, for log lines. */
    private volatile Long2LongOpenHashMap starts;
    /** zonesWithNoRoads zones meeting the area (usually none). */
    private final ZonePerimeter[] noRoadZones;

    /**
     * Loads the obstacles of an area.
     *
     * @param store    the footprint store, null when no recorded region overlaps the area
     * @param margin   the road's margin (blocks), footprints and zones are widened by it
     * @param startX   the road's start X (block)
     * @param startZ   the road's start Z (block)
     * @param endX     the road's end X (block)
     * @param endZ     the road's end Z (block)
     * @param areaMinX the preloaded area's min X (block, inclusive)
     * @param areaMinZ the preloaded area's min Z (block, inclusive)
     * @param areaMaxX the preloaded area's max X (block, inclusive)
     * @param areaMaxZ the preloaded area's max Z (block, inclusive)
     * @param zones    the no-road zones meeting the area, copied
     */
    private RoadObstacles(@Nullable StructureChunkStorage store, int margin, int startX, int startZ, int endX, int endZ,
                          int areaMinX, int areaMinZ, int areaMaxX, int areaMaxZ, List<ZonePerimeter> zones) {
        this.store = store;
        this.margin = margin;
        this.startX = startX;
        this.startZ = startZ;
        this.endX = endX;
        this.endZ = endZ;
        this.areaMinX = areaMinX;
        this.areaMinZ = areaMinZ;
        this.areaMaxX = areaMaxX;
        this.areaMaxZ = areaMaxZ;
        this.noRoadZones = zones.toArray(new ZonePerimeter[0]);
        Long2LongOpenHashMap map = new Long2LongOpenHashMap();
        map.defaultReturnValue(NO_RANGE);
        LongOpenHashSet destinations = new LongOpenHashSet();
        Long2LongOpenHashMap startMap = CommonConfig.isDebugMod() ? new Long2LongOpenHashMap() : null;
        if (store != null) {
            for (int rx = (areaMinX - margin) >> CoordinateKeys.REGION_BLOCK_SHIFT; rx <= (areaMaxX + margin) >> CoordinateKeys.REGION_BLOCK_SHIFT; rx++) {
                for (int rz = (areaMinZ - margin) >> CoordinateKeys.REGION_BLOCK_SHIFT; rz <= (areaMaxZ + margin) >> CoordinateKeys.REGION_BLOCK_SHIFT; rz++) {
                    long regionKey = CoordinateKeys.pack(rx, rz);
                    loadedRegions.add(regionKey);
                    if (store.hasRegion(regionKey)) {
                        fill(store.read(regionKey), map, destinations, startMap);
                    }
                }
            }
        }
        this.cells = map;
        this.destinationCells = destinations;
        this.starts = startMap;
    }

    /**
     * Loads the obstacles around a route.
     *
     * @param startX the route's start X (block)
     * @param startZ the route's start Z (block)
     * @param endX   the route's end X (block)
     * @param endZ   the route's end Z (block)
     * @param margin the road's margin (blocks)
     * @return the obstacles, null when no recorded structure or no-road zone is anywhere near the route
     */
    @Nullable
    public static RoadObstacles forRoute(int startX, int startZ, int endX, int endZ, int margin) {
        long dx = endX - startX;
        long dz = endZ - startZ;
        int pad = AREA_PADDING + (int) (Math.sqrt((double) (dx * dx + dz * dz)) / 4);
        return forArea(Math.min(startX, endX) - pad, Math.min(startZ, endZ) - pad,
                Math.max(startX, endX) + pad, Math.max(startZ, endZ) + pad, startX, startZ, endX, endZ, margin);
    }

    /**
     * Loads the obstacles of an explicit area (a path's own bounds).
     *
     * @param minX   the area's min X (block, inclusive)
     * @param minZ   the area's min Z (block, inclusive)
     * @param maxX   the area's max X (block, inclusive)
     * @param maxZ   the area's max Z (block, inclusive)
     * @param startX the road's start X (block)
     * @param startZ the road's start Z (block)
     * @param endX   the road's end X (block)
     * @param endZ   the road's end Z (block)
     * @param margin the road's margin (blocks)
     * @return the obstacles, null if nothing to avoid overlaps the area
     */
    @Nullable
    public static RoadObstacles forArea(int minX, int minZ, int maxX, int maxZ,
                                        int startX, int startZ, int endX, int endZ, int margin) {
        List<ZonePerimeter> zones = ZonesCompat.noRoadZones(minX - margin, minZ - margin, maxX + margin,
                maxZ + margin);
        StructureChunkStorage store = StructureChunkRecorder.store();
        boolean anyRegion = false;
        if (store != null && store.hasAnyRegion()) {
            for (int rx = (minX - margin) >> CoordinateKeys.REGION_BLOCK_SHIFT; rx <= (maxX + margin) >> CoordinateKeys.REGION_BLOCK_SHIFT && !anyRegion; rx++) {
                for (int rz = (minZ - margin) >> CoordinateKeys.REGION_BLOCK_SHIFT; rz <= (maxZ + margin) >> CoordinateKeys.REGION_BLOCK_SHIFT; rz++) {
                    if (store.hasRegion(CoordinateKeys.pack(rx, rz))) {
                        anyRegion = true;
                        break;
                    }
                }
            }
        }
        if (!anyRegion && zones.isEmpty()) {
            return null;
        }
        return new RoadObstacles(anyRegion ? store : null, margin, startX, startZ, endX, endZ, minX, minZ, maxX, maxZ,
                zones);
    }

    /**
     * Tells whether a road must not pass through a column.
     *
     * @param x        the column's X (block)
     * @param z        the column's Z (block)
     * @param bandMinY the bottom of the road's height band
     * @param bandMaxY the top of the road's height band
     * @return true if a structure there reaches into the band, or the column is in a no-road zone
     */
    public boolean isBlocked(int x, int z, int bandMinY, int bandMaxY) {
        return lowestStructureY(x, z, bandMinY, bandMaxY) != NOT_BLOCKED;
    }

    /**
     * Tells whether something here could block a road at some height. Cheap, lets callers skip sampling
     * the terrain height where nothing is.
     *
     * @param x the column's X (block)
     * @param z the column's Z (block)
     * @return true if a structure range lies over the column or it is in a no-road zone
     */
    public boolean mayBlock(int x, int z) {
        return rangeAt(x, z) != NO_RANGE || noRoadZoneAt(x, z) != null;
    }

    /**
     * Returns the lowest structure Y over a column (within the road margin) that blocks a height band.
     *
     * @param x        the column's X (block)
     * @param z        the column's Z (block)
     * @param bandMinY the bottom of the road's height band
     * @param bandMaxY the top of the road's height band
     * @return the lowest structure Y if the structures there reach into the band, NOT_BLOCKED otherwise,
     *         and a value below every band inside a no-road zone
     */
    public int lowestStructureY(int x, int z, int bandMinY, int bandMaxY) {
        if (noRoadZones.length > 0 && noRoadZoneAt(x, z) != null) {
            return ZONE_FLOOR;
        }
        long range = rangeAt(x, z);
        int min = StructureChunkRecorder.rangeMin(range);
        int max = StructureChunkRecorder.rangeMax(range);
        return min <= max && max >= bandMinY && min <= bandMaxY ? min : NOT_BLOCKED;
    }

    /**
     * Tells whether one of the road's own destinations (widened by the margin) covers a column.
     *
     * @param x the column's X (block)
     * @param z the column's Z (block)
     * @return true if a destination's footprint covers the column
     */
    public boolean isDestinationCell(int x, int z) {
        rangeAt(x, z); // loads the region if outside the area
        return destinationCells.contains(CoordinateKeys.pack(x >> CELL_SHIFT, z >> CELL_SHIFT));
    }

    /**
     * Finds the no-road zone covering a column (widened by the margin).
     *
     * @param x the column's X (block)
     * @param z the column's Z (block)
     * @return the zone's id, null if none covers the column
     */
    @Nullable
    public String noRoadZoneAt(int x, int z) {
        for (ZonePerimeter zone : noRoadZones) {
            if (zone.contains(x, z, margin)) {
                return zone.zoneId();
            }
        }
        return null;
    }

    /**
     * Finds the no-road zone any column of a stretch of consecutive segments lies in.
     *
     * @param segments the road's segments, not changed
     * @param s        the stretch's first segment index
     * @param e        the stretch's last segment index
     * @return the zone's id, null if no column is in one
     */
    @Nullable
    public String noRoadZoneIn(List<RoadSegmentPlacement> segments, int s, int e) {
        for (int i = s; i <= e; i++) {
            for (BlockPos p : columnsOf(segments.get(i))) {
                String zone = noRoadZoneAt(p.getX(), p.getZ());
                if (zone != null) {
                    return zone;
                }
            }
        }
        return null;
    }

    /**
     * Tells whether a stretch of consecutive segments touches one of the road's own destinations.
     *
     * @param segments the road's segments, not changed
     * @param s        the stretch's first segment index
     * @param e        the stretch's last segment index
     * @return true if any column of segments s..e lies on a destination's footprint
     */
    public boolean touchesDestination(List<RoadSegmentPlacement> segments, int s, int e) {
        for (int i = s; i <= e; i++) {
            for (BlockPos p : columnsOf(segments.get(i))) {
                if (isDestinationCell(p.getX(), p.getZ())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Lists a segment's columns.
     *
     * @param segment the segment, may be null
     * @return its middle position and every paved position, a new list, empty for null
     */
    public static List<BlockPos> columnsOf(@Nullable RoadSegmentPlacement segment) {
        List<BlockPos> out = new ArrayList<>();
        if (segment == null) {
            return out;
        }
        if (segment.middlePos() != null) {
            out.add(segment.middlePos());
        }
        if (segment.positions() != null) {
            for (BlockPos p : segment.positions()) {
                if (p != null) {
                    out.add(p);
                }
            }
        }
        return out;
    }

    /**
     * Returns the start chunk of a structure over a column, for debug lines.
     *
     * @param x the column's X (block)
     * @param z the column's Z (block)
     * @return the start chunk key, Long.MIN_VALUE if unknown or debugMod is off
     */
    public long blockingStartAt(int x, int z) {
        Long2LongOpenHashMap s = starts;
        return s == null ? Long.MIN_VALUE : s.getOrDefault(CoordinateKeys.pack(x >> CELL_SHIFT, z >> CELL_SHIFT), Long.MIN_VALUE);
    }

    /**
     * Returns the structure Y range over a column, loading its region first if it is outside the area.
     *
     * @param x the column's X (block)
     * @param z the column's Z (block)
     * @return the packed Y range (StructureChunkRecorder.packRange), NO_RANGE if no structure is there
     */
    private long rangeAt(int x, int z) {
        if (x < areaMinX || x > areaMaxX || z < areaMinZ || z > areaMaxZ) {
            ensureLoaded(x - margin, z - margin);
            ensureLoaded(x + margin, z - margin);
            ensureLoaded(x - margin, z + margin);
            ensureLoaded(x + margin, z + margin);
        }
        return cells.get(CoordinateKeys.pack(x >> CELL_SHIFT, z >> CELL_SHIFT));
    }

    /**
     * Loads the region holding a column if it isn't yet, copy-on-write. Only for columns outside the
     * preloaded area.
     *
     * @param x the column's X (block)
     * @param z the column's Z (block)
     */
    private void ensureLoaded(int x, int z) {
        long regionKey = CoordinateKeys.regionOfBlock(x, z);
        if (regionKey == lastCheckedRegion || store == null || !store.hasRegion(regionKey)) {
            return;
        }
        synchronized (loadedRegions) {
            if (loadedRegions.add(regionKey)) {
                Long2LongOpenHashMap next = new Long2LongOpenHashMap(cells);
                next.defaultReturnValue(NO_RANGE);
                LongOpenHashSet nextDestinations = new LongOpenHashSet(destinationCells);
                Long2LongOpenHashMap nextStarts = starts == null ? null : new Long2LongOpenHashMap(starts);
                fill(store.read(regionKey), next, nextDestinations, nextStarts);
                destinationCells = nextDestinations; // published before the cells that use them
                if (nextStarts != null) {
                    starts = nextStarts;
                }
                cells = next;
            }
            lastCheckedRegion = regionKey; // only once the region's cells are published
        }
    }

    /**
     * Rasterizes one region's entries into maps that are not yet published.
     *
     * @param entries      StructureChunkStore.STRIDE longs per entry, not changed
     * @param map          cell to packed Y range, mutated
     * @param destinations destination cells, mutated
     * @param startMap     cell to start chunk, mutated, null when not kept
     */
    private void fill(long[] entries, Long2LongOpenHashMap map, LongOpenHashSet destinations,
                      @Nullable Long2LongOpenHashMap startMap) {
        for (int i = 0; i + StructureChunkStorage.STRIDE - 1 < entries.length; i += StructureChunkStorage.STRIDE) {
            long startChunk = entries[i];
            int startChunkX = CoordinateKeys.unpackA(startChunk);
            int startChunkZ = CoordinateKeys.unpackB(startChunk);
            boolean destination = CoordinateKeys.isColumnLiesOnChunk(startX, startZ, startChunkX, startChunkZ,
                    RoadColumns.DESTINATION_MARGIN)
                    || CoordinateKeys.isColumnLiesOnChunk(endX, endZ, startChunkX, startChunkZ,
                    RoadColumns.DESTINATION_MARGIN);
            long footprint = entries[i + 1];
            int minY = (int) entries[i + 2];
            int maxY = (int) entries[i + 3];
            int minX = StructureChunkRecorder.minXOf(footprint) - margin;
            int minZ = StructureChunkRecorder.minZOf(footprint) - margin;
            int maxX = StructureChunkRecorder.maxXOf(footprint) + margin;
            int maxZ = StructureChunkRecorder.maxZOf(footprint) + margin;
            for (int cx = minX >> CELL_SHIFT; cx <= maxX >> CELL_SHIFT; cx++) {
                for (int cz = minZ >> CELL_SHIFT; cz <= maxZ >> CELL_SHIFT; cz++) {
                    long key = CoordinateKeys.pack(cx, cz);
                    long old = map.get(key);
                    map.put(key, StructureChunkRecorder.packRange(Math.min(StructureChunkRecorder.rangeMin(old), minY),
                            Math.max(StructureChunkRecorder.rangeMax(old), maxY)));
                    if (startMap != null) {
                        startMap.put(key, startChunk);
                    }
                    if (destination) {
                        destinations.add(key);
                    }
                }
            }
        }
    }
}
