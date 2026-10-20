package net.flameslight.rwstructuresawareness.logger;

import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import net.flameslight.rwstructuresawareness.config.CommonConfig;
import net.flameslight.rwstructuresawareness.mixins.ChunkMapAccessor;
import net.flameslight.rwstructuresawareness.utils.CoordinateKeys;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.shiroha233.roadweaver.core.model.RoadData;
import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;
import net.shiroha233.roadweaver.persistence.sharded.RoadShardStorage;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * debugMod-only log lines that follow a road from its save to its paving, to find where a road ends
 * up cut: which code saved / replaced it, which of its chunks were already decorated at that moment
 * (RoadWeaver paves a chunk only while it generates), and how many of its positions each chunk's
 * paving skipped. Nothing here changes behaviour.
 */
public final class RoadDebug {
    /** Generated chunks listed per line, at most. */
    private static final int MAX_LISTED_CHUNKS = 12;

    /** Per worldgen thread, during one PathFeature.place: {positions checked, skipped, tunnel floors kept}. */
    private static final ThreadLocal<int[]> PAVING = new ThreadLocal<>();

    /**
     * Tells whether these log lines are on.
     *
     * @return the debugMod option
     */
    public static boolean enabled() {
        return CommonConfig.isDebugMod();
    }

    // ---- Storage ----

    /**
     * Logs a road RoadFileStorage.addRoad saved.
     *
     * @param level the level, may be null
     * @param road  the saved road, not changed
     */
    public static void roadSaved(ServerLevel level, RoadData road) {
        if (enabled()) {
            ModLogger.debug("Road saved by {}: {}, ends: {}, {}", caller(), describe(road), endContext(level, road),
                    decoratedChunks(level, road));
        }
    }

    /**
     * Logs a road RoadFileStorage.replaceRoad replaced or deleteRoad deleted.
     *
     * @param level   the level, may be null
     * @param oldRoad the road before the call, null if unknown
     * @param newRoad the replacing road, null for a delete
     */
    public static void roadReplaced(ServerLevel level, @Nullable RoadData oldRoad, @Nullable RoadData newRoad) {
        if (!enabled()) {
            return;
        }
        if (newRoad == null) {
            ModLogger.debug("Road deleted by {}: {}", caller(), oldRoad == null ? "?" : describe(oldRoad));
        } else {
            ModLogger.debug("Road replaced by {}: was {}, now {}, {} segment(s) without positions (not paved), ends: {}, {}",
                    caller(), oldRoad == null ? "?" : describe(oldRoad), describe(newRoad), emptySegments(newRoad),
                    endContext(level, newRoad), decoratedChunks(level, newRoad));
        }
    }

    /**
     * Counts a road's segments without positions.
     *
     * @param road the road
     * @return the number of null or positionless segments
     */
    private static int emptySegments(RoadData road) {
        int n = 0;
        if (road.roadSegmentList() != null) {
            for (RoadSegmentPlacement s : road.roadSegmentList()) {
                if (s == null || s.positions() == null || s.positions().isEmpty()) {
                    n++;
                }
            }
        }
        return n;
    }

    /**
     * Describes what each end of a road meets.
     *
     * @param level the level, may be null
     * @param road  the road
     * @return per end, "joins road A -> B" if another stored road has a segment within JUNCTION_REACH blocks
     *         of it (a junction / branch end), else "free" (a structure or open end). "?" if unknown
     */
    private static String endContext(ServerLevel level, RoadData road) {
        List<RoadSegmentPlacement> segments = road.roadSegmentList();
        if (level == null || segments == null || segments.isEmpty()) {
            return "?";
        }
        BlockPos first = segments.get(0).middlePos();
        BlockPos last = segments.get(segments.size() - 1).middlePos();
        return "start " + roadNear(level, road, first) + ", end " + roadNear(level, road, last);
    }

    private static final int JUNCTION_REACH = 8;

    /**
     * Finds the nearest other stored road to a position.
     *
     * @param level the level
     * @param self  the road itself, skipped
     * @param pos   the position, may be null
     * @return "joins road ..." with the distance, "free" past JUNCTION_REACH, "?" for a null position
     */
    private static String roadNear(ServerLevel level, RoadData self, @Nullable BlockPos pos) {
        if (pos == null) {
            return "?";
        }
        int best = Integer.MAX_VALUE;
        RoadData nearest = null;
        for (RoadData other : RoadShardStorage.queryRect(level, pos.getX() - JUNCTION_REACH, pos.getZ() - JUNCTION_REACH,
                pos.getX() + JUNCTION_REACH, pos.getZ() + JUNCTION_REACH)) {
            if (other == null || other == self || sameShape(other, self) || other.roadSegmentList() == null) {
                continue;
            }
            for (RoadSegmentPlacement s : other.roadSegmentList()) {
                BlockPos m = s == null ? null : s.middlePos();
                if (m != null) {
                    int d = Math.max(Math.abs(m.getX() - pos.getX()), Math.abs(m.getZ() - pos.getZ()));
                    if (d < best) {
                        best = d;
                        nearest = other;
                    }
                }
            }
        }
        return nearest == null || best > JUNCTION_REACH ? "free"
                : "joins road " + describe(nearest) + " (" + best + " blocks)";
    }

    /**
     * Tells whether two roads look like the same road (a stored copy of it).
     *
     * @param a one road
     * @param b the other road
     * @return true if both have the same segment count and the same first and last middles
     */
    private static boolean sameShape(RoadData a, RoadData b) {
        List<RoadSegmentPlacement> sa = a.roadSegmentList();
        List<RoadSegmentPlacement> sb = b.roadSegmentList();
        return sa != null && sb != null && sa.size() == sb.size() && !sa.isEmpty()
                && java.util.Objects.equals(sa.get(0).middlePos(), sb.get(0).middlePos())
                && java.util.Objects.equals(sa.get(sa.size() - 1).middlePos(), sb.get(sb.size() - 1).middlePos());
    }

    /**
     * Describes how far a trimmed stretch's edges are from the nearest other stored road (a cut right next to
     * another road points at a junction).
     *
     * @param level the level, may be null
     * @param edgeA the stretch's first middle, may be null
     * @param edgeB the stretch's last middle, may be null
     * @return the log suffix, empty when debugMod is off
     */
    public static String trimContext(ServerLevel level, @Nullable BlockPos edgeA, @Nullable BlockPos edgeB) {
        if (!enabled() || level == null) {
            return "";
        }
        return ", nearest other road: " + nearestRoadDistance(level, edgeA) + " / " + nearestRoadDistance(level, edgeB)
                + " blocks from the trimmed stretch's edges";
    }

    /**
     * Measures the distance to the nearest stored road segment middle.
     *
     * @param level the level
     * @param pos   the position, may be null
     * @return the distance (blocks, Chebyshev), ">64" if none is that close, "?" for a null position
     */
    private static String nearestRoadDistance(ServerLevel level, @Nullable BlockPos pos) {
        if (pos == null) {
            return "?";
        }
        int reach = 64;
        int best = Integer.MAX_VALUE;
        for (RoadData other : RoadShardStorage.queryRect(level, pos.getX() - reach, pos.getZ() - reach,
                pos.getX() + reach, pos.getZ() + reach)) {
            if (other == null || other.roadSegmentList() == null) {
                continue;
            }
            for (RoadSegmentPlacement s : other.roadSegmentList()) {
                BlockPos m = s == null ? null : s.middlePos();
                if (m != null) {
                    best = Math.min(best, Math.max(Math.abs(m.getX() - pos.getX()), Math.abs(m.getZ() - pos.getZ())));
                }
            }
        }
        return best == Integer.MAX_VALUE ? ">" + reach : String.valueOf(best);
    }

    /**
     * Lists the structures starting in a chunk. Reads the published holder map, safe off the server thread.
     *
     * @param level  the level, may be null
     * @param chunkX the chunk's X (chunk coordinates)
     * @param chunkZ the chunk's Z (chunk coordinates)
     * @return the structure ids, comma separated, empty unless the chunk is loaded and past structure starts
     */
    public static String structureIdsAt(ServerLevel level, int chunkX, int chunkZ) {
        if (level == null) {
            return "";
        }
        ChunkHolder holder = ((ChunkMapAccessor) level.getChunkSource().chunkMap)
                .rwstructuresawareness$getVisibleChunkIfPresent(ChunkPos.asLong(chunkX, chunkZ));
        ChunkAccess chunk = holder == null ? null : holder.getLastAvailable();
        if (chunk == null || !chunk.getStatus().isOrAfter(ChunkStatus.STRUCTURE_REFERENCES)) {
            return "";
        }
        List<String> ids = new ArrayList<>();
        var registry = level.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.STRUCTURE);
        for (var structure : chunk.getAllStarts().keySet()) {
            var key = registry.getKey(structure);
            if (key != null) {
                ids.add(key.toString());
            }
        }
        return String.join(",", ids);
    }

    /**
     * Describes a road for the log.
     *
     * @param road the road
     * @return its first and last middle positions, segment count and owners
     */
    public static String describe(RoadData road) {
        List<RoadSegmentPlacement> segments = road.roadSegmentList();
        int n = segments == null ? 0 : segments.size();
        String from = n == 0 || segments.get(0).middlePos() == null ? "?" : segments.get(0).middlePos().toShortString();
        String to = n == 0 || segments.get(n - 1).middlePos() == null ? "?" : segments.get(n - 1).middlePos().toShortString();
        return from + " -> " + to + " (" + n + " segments, owners " + owner(road.ownerA2dKey()) + " / "
                + owner(road.ownerB2dKey()) + ")";
    }

    /**
     * Describes a road owner key.
     *
     * @param key a 2D owner key
     * @return "x,z", or "none" for RoadData.NO_OWNER_2D
     */
    private static String owner(long key) {
        return key == RoadData.NO_OWNER_2D ? "none" : CoordinateKeys.unpackA(key) + "," + CoordinateKeys.unpackB(key);
    }

    /**
     * Names the code that saved a road.
     *
     * @return "Class.method" of the first frame outside RoadWeaver's storage and this addon's storage hooks,
     *         "?" if none
     */
    private static String caller() {
        return StackWalker.getInstance().walk(frames -> frames
                .filter(f -> {
                    String c = f.getClassName();
                    return !c.startsWith("net.shiroha233.roadweaver.persistence.")
                            && !c.startsWith("net.flameslight.rwstructuresawareness.logger.")
                            && !c.startsWith("net.flameslight.rwstructuresawareness.roadweaver.");
                })
                .findFirst()
                .map(f -> f.getClassName().substring(f.getClassName().lastIndexOf('.') + 1) + "." + f.getMethodName())
                .orElse("?"));
    }

    /**
     * Lists the loaded chunks a road crosses that already reached FEATURES: RoadWeaver won't pave the road
     * there anymore. Chunks generated earlier but unloaded now aren't seen.
     *
     * @param level the level, may be null
     * @param road  the road
     * @return the count and up to MAX_LISTED_CHUNKS chunk positions
     */
    private static String decoratedChunks(ServerLevel level, RoadData road) {
        List<RoadSegmentPlacement> segments = road.roadSegmentList();
        if (level == null || segments == null) {
            return "decorated chunks: ?";
        }
        LongLinkedOpenHashSet chunks = new LongLinkedOpenHashSet();
        for (RoadSegmentPlacement s : segments) {
            BlockPos m = s == null ? null : s.middlePos();
            if (m != null) {
                chunks.add(ChunkPos.asLong(m.getX() >> 4, m.getZ() >> 4));
            }
        }
        ChunkMapAccessor map = (ChunkMapAccessor) level.getChunkSource().chunkMap;
        List<String> listed = new ArrayList<>();
        int decorated = 0;
        for (long key : chunks) {
            ChunkHolder holder = map.rwstructuresawareness$getVisibleChunkIfPresent(key);
            ChunkAccess chunk = holder == null ? null : holder.getLastAvailable();
            if (chunk != null && chunk.getStatus().isOrAfter(ChunkStatus.FEATURES)) {
                decorated++;
                if (listed.size() < MAX_LISTED_CHUNKS) {
                    listed.add("[" + ChunkPos.getX(key) + ", " + ChunkPos.getZ(key) + "]");
                }
            }
        }
        return "already decorated (won't be paved): " + decorated + " of " + chunks.size() + " chunks"
                + (listed.isEmpty() ? "" : " " + String.join(" ", listed) + (decorated > listed.size() ? " ..." : ""));
    }

    // ---- Paving ----

    /** Starts counting one PathFeature.place (HEAD) on this thread. */
    public static void pavingStarted() {
        if (enabled()) {
            PAVING.set(new int[3]);
        }
    }

    /**
     * Counts one position SegmentPaver asked StructureAvoidanceService about.
     *
     * @param skipped     true if the position was skipped as inside a structure
     * @param tunnelFloor true if it was paved anyway as a tunnel floor
     */
    public static void pavingChecked(boolean skipped, boolean tunnelFloor) {
        int[] counts = PAVING.get();
        if (counts != null) {
            counts[0]++;
            if (skipped) {
                counts[1]++;
            }
            if (tunnelFloor) {
                counts[2]++;
            }
        }
    }

    /**
     * Ends counting at PathFeature.place RETURN, and logs one line if paving skipped road positions.
     *
     * @param chunk the chunk paved
     */
    public static void pavingFinished(ChunkPos chunk) {
        int[] counts = PAVING.get();
        PAVING.remove();
        if (counts != null && counts[1] > 0) {
            ModLogger.debug("Paving chunk [{}, {}]: {} of {} road positions skipped as inside a structure "
                    + "(RoadWeaver's StructureAvoidanceService){}.", chunk.x, chunk.z, counts[1], counts[0],
                    counts[2] > 0 ? ", " + counts[2] + " tunnel floor positions paved anyway" : "");
        }
    }
}
