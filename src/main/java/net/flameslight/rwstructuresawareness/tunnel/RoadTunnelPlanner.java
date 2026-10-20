package net.flameslight.rwstructuresawareness.tunnel;

import net.flameslight.rwstructuresawareness.types.road.RoadLevel;
import net.flameslight.rwstructuresawareness.config.CommonConfig;
import net.flameslight.rwstructuresawareness.logger.ModLogger;
import net.flameslight.rwstructuresawareness.logger.RoadDebug;
import net.flameslight.rwstructuresawareness.types.road.RoadObstacles;
import net.flameslight.rwstructuresawareness.road.RoadTrimPlanner;
import net.flameslight.rwstructuresawareness.roadweaver.RoadWeaverAccessor;
import net.flameslight.rwstructuresawareness.types.road.RoadTrimResult;
import net.flameslight.rwstructuresawareness.types.tunnel.TunnelPlan;
import net.flameslight.rwstructuresawareness.utils.CoordinateKeys;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.shiroha233.roadweaver.config.sub.RoadGenerationConfig;
import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;
import net.shiroha233.roadweaver.core.model.RoadSpan;
import net.shiroha233.roadweaver.core.model.SpanType;
import net.shiroha233.roadweaver.core.model.StructureConnection;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Underground fallback for a finished RoadWeaver path that still crosses recorded structures (a
 * failed search, a search area too narrow for a detour, or a kept coarse path). Each contiguous run
 * of blocked segments is dug when it can be: the run drops to below the structures' lowest Y, with
 * ramps inside the allowed segments before and after it (RoadWeaver's own slope limit), marked as a
 * TUNNEL span, and its dig segment recorded in TunnelStore.
 * <p>
 * A road segment that can't be dug (too deep, below the world, no room for the ramps, over water, on the road's
 * own destination, whose edge is where the road ends, or in a no-road zone) is handed to RoadTrimPlanner,
 * which removes its segments.
 * <p>
 * Computed at Road.generateRoad's computeTargetY call. The result (a TunnelPlan) is then applied to the
 * RoadData RoadWeaver builds and to its roadside precomputers (see RoadMixin).
 */
public final class RoadTunnelPlanner {
    /** A run is trimmed instead of dug when its floor would be more than this far below the road. */
    private static final int MAX_TUNNEL_DEPTH = 48;
    /** Gap kept between the tunnel's ceiling and the structure above it. */
    private static final int CEILING_GAP = 2;
    /** Height step allowed per two segments when RoadWeaver's slope limit is disabled. */
    private static final int UNLIMITED_SLOPE_STEP = 8;

    private static final ThreadLocal<TunnelPlan> PLAN = new ThreadLocal<>();

    /**
     * Returns the plan computed for a road on this thread.
     *
     * @param segments the road's segment list, compared by identity
     * @return the plan, null if none was computed for exactly these segments
     */
    @Nullable
    public static TunnelPlan planFor(List<RoadSegmentPlacement> segments) {
        TunnelPlan plan = PLAN.get();
        return plan != null && plan.originalSegments == segments ? plan : null;
    }

    /** Forgets this thread's plan. */
    public static void clearPlan() {
        PLAN.remove();
    }

    /**
     * Returns the spans plus a BRIDGE copy of every TUNNEL span.
     * <p>
     * Only for RoadWeaver's roadside structure and village precomputers, which skip BRIDGE stretches but know
     * nothing of tunnels: nothing gets placed beside a road that runs underground. The saved road keeps its
     * real TUNNEL spans.
     *
     * @param spans the road's spans, not changed
     * @return a new list: every span, then one BRIDGE span per TUNNEL span
     */
    public static List<RoadSpan> tunnelsAsBridges(List<RoadSpan> spans) {
        List<RoadSpan> out = new ArrayList<>(spans);
        for (RoadSpan span : spans) {
            if (span.type() == SpanType.TUNNEL) {
                out.add(new RoadSpan(span.start(), span.end(), SpanType.BRIDGE));
            }
        }
        return out;
    }

    /**
     * Plans tunnels for a road's blocked stretches, and has RoadTrimPlanner trim those that can't be dug.
     * Called with computeTargetY's result.
     * <p>
     * The plan is kept for this thread (planFor) when the road changes.
     *
     * @param level      the level the road is built in
     * @param connection the road's connection (its two destinations)
     * @param segments   the road's segments, not changed
     * @param spans      the road's spans, not changed, may be null
     * @param targetY    computeTargetY's heights, one per segment, not changed
     * @param cfg        RoadWeaver's generation config, may be null (then its global slope limit is used)
     * @return the heights to use instead (aligned with TunnelPlan.keptSegments), or {@code targetY} itself when
     *         the road crosses no recorded structure or isn't changed
     */
    public static List<Integer> plan(ServerLevel level, StructureConnection connection,
                                     List<RoadSegmentPlacement> segments, List<RoadSpan> spans,
                                     List<Integer> targetY, RoadGenerationConfig cfg) {
        PLAN.remove();
        if (!RoadLevel.isRoadLevel(level) || connection == null || segments == null || segments.isEmpty()
                || targetY == null || targetY.size() != segments.size()) {
            return targetY;
        }
        int n = segments.size();
        int margin = RoadWeaverAccessor.configuredRoadMargin();

        // ---- Which segments cross a structure, and the lowest structure Y under each ----
        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (RoadSegmentPlacement s : segments) {
            for (BlockPos p : RoadObstacles.columnsOf(s)) {
                minX = Math.min(minX, p.getX());
                minZ = Math.min(minZ, p.getZ());
                maxX = Math.max(maxX, p.getX());
                maxZ = Math.max(maxZ, p.getZ());
            }
        }
        if (minX > maxX) {
            return targetY;
        }
        BlockPos from = connection.from();
        BlockPos to = connection.to();
        RoadObstacles obstacles = RoadObstacles.forArea(minX, minZ, maxX, maxZ, from.getX(), from.getZ(),
                to.getX(), to.getZ(), margin);
        if (obstacles == null) {
            return targetY;
        }
        int clearHeight = RoadWeaverAccessor.roadClearHeight();
        int[] originalY = new int[n];
        for (int i = 0; i < n; i++) {
            originalY[i] = targetY.get(i);
        }
        /*
            A segment is blocked only by structures reaching into its own height band: from the road
            block (targetY - 1, targetY being the first air above it) up to its clearance.
         */
        int[] lowest = new int[n];
        boolean anyBlocked = false;
        for (int i = 0; i < n; i++) {
            lowest[i] = RoadObstacles.NOT_BLOCKED;
            for (BlockPos p : RoadObstacles.columnsOf(segments.get(i))) {
                lowest[i] = Math.min(lowest[i],
                        obstacles.lowestStructureY(p.getX(), p.getZ(), originalY[i] - 1, originalY[i] + clearHeight));
            }
            anyBlocked |= lowest[i] != RoadObstacles.NOT_BLOCKED;
        }
        if (!anyBlocked) {
            return targetY;
        }

        int[] newY = originalY.clone();
        boolean[] trimmed = new boolean[n];
        int step2 = cfg != null && !cfg.slopeLimitEnabled() ? UNLIMITED_SLOPE_STEP
                : Math.max(0, Math.min(8, cfg != null ? cfg.maxSlopeStepPerTwoSegments()
                : RoadWeaverAccessor.maxSlopeStepPerTwoSegments()));
        int worldFloor = level.getMinBuildHeight() + 8;
        // Water crossings (RoadWeaver's BRIDGE spans): a tunnel there would run under the water and flood.
        boolean[] overWater = spanMask(segments, spans, SpanType.BRIDGE);

        // ---- Runs of blocked segments, a run whose ramp would hit another run absorbs it ----
        List<int[]> runs = new ArrayList<>();
        for (int i = 0; i < n; ) {
            if (lowest[i] == RoadObstacles.NOT_BLOCKED) {
                i++;
                continue;
            }
            int s = i;
            while (i < n && lowest[i] != RoadObstacles.NOT_BLOCKED) {
                i++;
            }
            runs.add(new int[]{s, i - 1});
        }

        for (int r = 0; r < runs.size(); r++) {
            int s = runs.get(r)[0];
            int e = runs.get(r)[1];
            boolean merged;
            int floor;
            int left;
            int right;
            do {
                merged = false;
                int structureMinY = Integer.MAX_VALUE;
                int roadMinY = Integer.MAX_VALUE;
                for (int i = s; i <= e; i++) {
                    if (lowest[i] != RoadObstacles.NOT_BLOCKED) {
                        structureMinY = Math.min(structureMinY, lowest[i]);
                    }
                    roadMinY = Math.min(roadMinY, originalY[i]);
                }
                floor = structureMinY - clearHeight - CEILING_GAP;
                left = rampStart(originalY, lowest, overWater, s, floor, step2, -1);
                right = rampStart(originalY, lowest, overWater, e, floor, step2, 1);
                /*
                    A ramp running into the next blocked run: dig both as one run. Never with a run that
                    can't be dug whatever happens (water, the road's own destination), and never for
                    such a run: the merged run would be trimmed whole, taking the road between with it.
                 */
                if (neverDug(obstacles, segments, overWater, s, e)) {
                    left = left == RAMP_HIT_BLOCKED ? RAMP_OUT_OF_ROAD : left;
                    right = right == RAMP_HIT_BLOCKED ? RAMP_OUT_OF_ROAD : right;
                }
                if (right == RAMP_HIT_BLOCKED && r + 1 < runs.size()
                        && !neverDug(obstacles, segments, overWater, runs.get(r + 1)[0], runs.get(r + 1)[1])) {
                    e = runs.remove(r + 1)[1];
                    merged = true;
                } else if (left == RAMP_HIT_BLOCKED && r > 0
                        && !neverDug(obstacles, segments, overWater, runs.get(r - 1)[0], runs.get(r - 1)[1])) {
                    s = runs.remove(r - 1)[0];
                    r--;
                    merged = true;
                    // that run was already decided on its own: undo it, the merged run decides again
                    for (int i = s; i <= e; i++) {
                        trimmed[i] = false;
                        newY[i] = originalY[i];
                    }
                }
                if (!merged) {
                    boolean wet = anyIn(overWater, s, e);
                    // A tunnel under the road's own destination would have to surface inside it.
                    boolean destination = obstacles.touchesDestination(segments, s, e);
                    String noRoadZone = obstacles.noRoadZoneIn(segments, s, e);
                    boolean diggable = step2 > 0 && !wet && !destination && noRoadZone == null && floor >= worldFloor
                            && roadMinY - floor <= MAX_TUNNEL_DEPTH && left >= 0 && right >= 0;
                    if (CommonConfig.isDebugMod()) {
                        String why = diggable ? "tunneling to y " + floor
                                : noRoadZone != null ? "can't tunnel: no-road zone " + noRoadZone
                                : destination ? "can't tunnel: the road's own destination"
                                : step2 <= 0 ? "can't tunnel: slope limit 0"
                                : wet ? "can't tunnel: crosses water"
                                : floor < worldFloor ? "can't tunnel: floor y " + floor + " below the world"
                                : roadMinY - floor > MAX_TUNNEL_DEPTH ? "can't tunnel: " + (roadMinY - floor) + " blocks deep"
                                : "can't tunnel: no room for the " + (left < 0 ? "entry" : "exit") + " ramp";
                        ModLogger.debug("Road {} -> {}: segments {}..{} cross structures (start chunk(s) {}, lowest y {}), {}{}.",
                                from.toShortString(), to.toShortString(), s, e,
                                blockingStarts(level, obstacles, segments, s, e), structureMinY, why,
                                diggable ? "" : RoadDebug.trimContext(level, segments.get(s).middlePos(),
                                        segments.get(e).middlePos()));
                    }
                    if (diggable) {
                        for (int i = s; i <= e; i++) {
                            newY[i] = Math.min(newY[i], floor);
                        }
                        applyRamp(newY, s, floor, step2, -1, left);
                        applyRamp(newY, e, floor, step2, 1, right);
                    } else {
                        for (int i = s; i <= e; i++) {
                            trimmed[i] = true;
                        }
                    }
                }
            } while (merged);
            runs.set(r, new int[]{s, e});
        }

        // ---- The runs that can't be dug are trimmed ----
        RoadTrimResult trim = RoadTrimPlanner.plan(segments, spans, newY, trimmed, obstacles, from, to);
        if (trim.dropped()) {
            PLAN.set(TunnelPlan.dropped(segments));
            return targetY;
        }
        if (trim.unchanged()) {
            return targetY;
        }

        /*
            ---- Spans: drop those touching a lowered segment, add TUNNEL spans for lowered stretches ----
            A lowered stretch is underground: a BRIDGE span over it (the road crossed water there) would
            still make RoadWeaver plan a bridge, its buoys and bank signs on top of the tunnel.
         */
        List<RoadSpan> keptSpans = spansWithoutLowered(segments, trim.keptSpans(), newY, originalY);
        /*
            Dig volumes: one per lowered segment at its own height, plus one over every pair of
            consecutive lowered segments (both segments' columns, from the higher road level up), so
            curves and diagonals leave no uncarved wedge between two segment boxes.
         */
        List<int[]> boxes = new ArrayList<>();
        for (int i = 0; i < n; ) {
            if (trimmed[i] || newY[i] >= originalY[i]) {
                i++;
                continue;
            }
            int s = i;
            while (i < n && !trimmed[i] && newY[i] < originalY[i]) {
                boxes.add(tunnelBox(List.of(segments.get(i)), newY[i], clearHeight, margin));
                if (i > s) {
                    boxes.add(tunnelBox(List.of(segments.get(i - 1), segments.get(i)), Math.max(newY[i - 1], newY[i]),
                            clearHeight, margin));
                }
                i++;
            }
            keptSpans.add(new RoadSpan(segments.get(s).middlePos(), segments.get(i - 1).middlePos(), SpanType.TUNNEL));
        }

        PLAN.set(new TunnelPlan(segments, trim.keptSegments(), keptSpans, trim.keptTargetY(), trim.parts(), boxes,
                trim.reachesEnd()));
        return trim.keptTargetY();
    }

    private static final int RAMP_HIT_BLOCKED = -2;
    private static final int RAMP_OUT_OF_ROAD = -1;

    /**
     * Walks outward from a run's end until the ramp rising from the tunnel floor meets the road's own height.
     *
     * @param originalY the road's heights, not changed
     * @param lowest    per segment, the lowest blocking structure Y or RoadObstacles.NOT_BLOCKED, not changed
     * @param overWater per segment, true inside a water crossing, not changed
     * @param runEnd    the run's first (dir -1) or last (dir +1) segment index
     * @param floor     the tunnel floor Y
     * @param step2     the height step allowed per two segments
     * @param dir       -1 to walk toward the road's start, +1 toward its end
     * @return the last segment index the ramp changes (or runEnd itself when no ramp is needed),
     *         RAMP_HIT_BLOCKED if the ramp runs into another blocked segment, or RAMP_OUT_OF_ROAD if the road
     *         ends (or a water crossing starts) first
     */
    private static int rampStart(int[] originalY, int[] lowest, boolean[] overWater, int runEnd, int floor, int step2,
                                 int dir) {
        if (step2 <= 0) {
            return RAMP_OUT_OF_ROAD;
        }
        int last = runEnd;
        for (int k = 1; ; k++) {
            int j = runEnd + dir * k;
            if (j < 0 || j >= originalY.length) {
                return RAMP_OUT_OF_ROAD;
            }
            int required = floor + (step2 * k + 1) / 2;
            if (required >= originalY[j]) {
                return last;
            }
            if (lowest[j] != RoadObstacles.NOT_BLOCKED) {
                return RAMP_HIT_BLOCKED;
            }
            if (overWater[j]) {
                return RAMP_OUT_OF_ROAD; // a ramp can't go down into a water crossing
            }
            last = j;
        }
    }

    /**
     * Lowers the segments of a ramp found by rampStart.
     *
     * @param newY      the planned heights, mutated
     * @param runEnd    the run's first (dir -1) or last (dir +1) segment index
     * @param floor     the tunnel floor Y
     * @param step2     the height step allowed per two segments
     * @param dir       -1 toward the road's start, +1 toward its end
     * @param lastIndex rampStart's result, the last segment the ramp changes
     */
    private static void applyRamp(int[] newY, int runEnd, int floor, int step2, int dir, int lastIndex) {
        if (lastIndex == runEnd) {
            return;
        }
        for (int k = 1; ; k++) {
            int j = runEnd + dir * k;
            newY[j] = Math.min(newY[j], floor + (step2 * k + 1) / 2);
            if (j == lastIndex) {
                return;
            }
        }
    }

    /**
     * Marks the segments inside spans of one type (span start and end matched by segment middle).
     *
     * @param segments the road's segments, not changed
     * @param spans    the road's spans, not changed, may be null
     * @param type     the span type to mark
     * @return one flag per segment, true inside a span of that type
     */
    private static boolean[] spanMask(List<RoadSegmentPlacement> segments, @Nullable List<RoadSpan> spans, SpanType type) {
        boolean[] mask = new boolean[segments.size()];
        if (spans == null || spans.isEmpty()) {
            return mask;
        }
        Map<BlockPos, Integer> indexOf = new HashMap<>();
        for (int i = 0; i < segments.size(); i++) {
            indexOf.put(segments.get(i).middlePos(), i);
        }
        for (RoadSpan span : spans) {
            Integer a = span == null || span.type() != type ? null : indexOf.get(span.start());
            Integer b = a == null ? null : indexOf.get(span.end());
            if (b != null) {
                for (int i = Math.min(a, b); i <= Math.max(a, b); i++) {
                    mask[i] = true;
                }
            }
        }
        return mask;
    }

    /**
     * Tells whether a flag is set in a range.
     *
     * @param mask the flags, not changed
     * @param from the first index (inclusive)
     * @param to   the last index (inclusive)
     * @return true if any flag in from..to is set
     */
    private static boolean anyIn(boolean[] mask, int from, int to) {
        for (int i = from; i <= to; i++) {
            if (mask[i]) {
                return true;
            }
        }
        return false;
    }

    /**
     * Tells whether a run is never dug, whatever its depth or ramps.
     *
     * @param obstacles the road's obstacles
     * @param segments  the road's segments, not changed
     * @param overWater per segment, true inside a water crossing, not changed
     * @param s         the run's first segment index
     * @param e         the run's last segment index
     * @return true over water, on the road's own destination, or in a no-road zone
     */
    private static boolean neverDug(RoadObstacles obstacles, List<RoadSegmentPlacement> segments, boolean[] overWater,
                                    int s, int e) {
        return anyIn(overWater, s, e) || obstacles.touchesDestination(segments, s, e)
                || obstacles.noRoadZoneIn(segments, s, e) != null;
    }

    /**
     * Drops the spans touching a segment lowered into a tunnel.
     *
     * @param segments  the road's segments, not changed
     * @param spans     the spans left after trimming, not changed
     * @param newY      the planned heights, not changed
     * @param originalY the road's heights, not changed
     * @return a new list of the spans kept. A span whose ends aren't segment middles is kept
     */
    private static List<RoadSpan> spansWithoutLowered(List<RoadSegmentPlacement> segments, List<RoadSpan> spans,
                                                      int[] newY, int[] originalY) {
        Map<BlockPos, Integer> indexOf = new HashMap<>();
        for (int i = 0; i < segments.size(); i++) {
            indexOf.put(segments.get(i).middlePos(), i);
        }
        List<RoadSpan> kept = new ArrayList<>();
        for (RoadSpan span : spans) {
            Integer a = indexOf.get(span.start());
            Integer b = indexOf.get(span.end());
            if (a == null || b == null || !anyLowered(newY, originalY, Math.min(a, b), Math.max(a, b))) {
                kept.add(span);
            }
        }
        return kept;
    }

    /**
     * Tells whether any segment in a range was lowered into a tunnel.
     *
     * @param newY      the planned heights, not changed
     * @param originalY the road's heights, not changed
     * @param from      the first index (inclusive)
     * @param to        the last index (inclusive)
     * @return true if any segment in from..to is planned lower than the road's own height
     */
    private static boolean anyLowered(int[] newY, int[] originalY, int from, int to) {
        for (int i = from; i <= to; i++) {
            if (newY[i] < originalY[i]) {
                return true;
            }
        }
        return false;
    }

    /**
     * Builds the dig volume above lowered segments.
     * <p>
     * Their columns widened by the road margin, from {@code roadY} (RoadWeaver's road Y is the first air block,
     * the road block sits just below it) up to the clearance. The planner keeps roadY + clearHeight + 1 below
     * the structure, so it stays solid.
     *
     * @param segments    the lowered segments, not changed
     * @param roadY       the road's first air Y
     * @param clearHeight the clearance above it (blocks)
     * @param margin      the road's margin (blocks)
     * @return a box of TunnelStore.BOX_INTS ints (minX, minZ, maxX, maxZ, floorY, ceilY)
     */
    private static int[] tunnelBox(List<RoadSegmentPlacement> segments, int roadY, int clearHeight, int margin) {
        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (RoadSegmentPlacement segment : segments) {
            for (BlockPos p : RoadObstacles.columnsOf(segment)) {
                minX = Math.min(minX, p.getX());
                minZ = Math.min(minZ, p.getZ());
                maxX = Math.max(maxX, p.getX());
                maxZ = Math.max(maxZ, p.getZ());
            }
        }
        return new int[]{minX - margin, minZ - margin, maxX + margin, maxZ + margin, roadY, roadY + clearHeight};
    }

    /**
     * Describes the structures over a run, for the debug log.
     *
     * @param level     the level, to read structure ids from loaded chunks
     * @param obstacles the road's obstacles
     * @param segments  the road's segments, not changed
     * @param s         the run's first segment index
     * @param e         the run's last segment index
     * @return the start chunks, each with its structure ids when that chunk is loaded, plus "(own destination)"
     *         for the road's own destinations, "?" if none is known
     */
    private static String blockingStarts(ServerLevel level, RoadObstacles obstacles, List<RoadSegmentPlacement> segments,
                                         int s, int e) {
        Set<Long> starts = new LinkedHashSet<>();
        for (int i = s; i <= e; i++) {
            for (BlockPos p : RoadObstacles.columnsOf(segments.get(i))) {
                long start = obstacles.blockingStartAt(p.getX(), p.getZ());
                if (start != Long.MIN_VALUE) {
                    starts.add(start);
                }
            }
        }
        List<String> out = new ArrayList<>();
        for (long start : starts) {
            int cx = CoordinateKeys.unpackA(start);
            int cz = CoordinateKeys.unpackB(start);
            String ids = RoadDebug.structureIdsAt(level, cx, cz);
            out.add("[" + cx + ", " + cz + "]" + (ids.isEmpty() ? "" : " " + ids));
        }
        return out.isEmpty() ? "?" : String.join(" ", out)
                + (obstacles.touchesDestination(segments, s, e) ? " (own destination)" : "");
    }
}
