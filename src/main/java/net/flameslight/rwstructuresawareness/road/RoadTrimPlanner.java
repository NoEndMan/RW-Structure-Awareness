package net.flameslight.rwstructuresawareness.road;

import net.flameslight.rwstructuresawareness.logger.ModLogger;
import net.flameslight.rwstructuresawareness.types.road.RoadObstacles;
import net.flameslight.rwstructuresawareness.types.road.RoadTrimResult;
import net.minecraft.core.BlockPos;
import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;
import net.shiroha233.roadweaver.core.model.RoadSpan;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The last fallback for a road that still crosses recorded structures. A road is first rerouted around them
 * (RoadRerouter), then tunnelled under them (RoadTunnelPlanner). The runs of blocked segments that can't be
 * tunnelled (too deep, below the world, no room for the ramps, over water, on the road's own destination or
 * in a no-road zone) are trimmed here: their segments are removed, so the road stops before the structures and
 * continues after them. The remaining pieces become separate roads (RoadMixin saves them), so RoadWeaver never
 * paves or shapes terrain in between.
 * <p>
 * Trimming changes no blocks. It only decides which segments the saved road keeps.
 */
public final class RoadTrimPlanner {
    /**
     * Builds the road kept after trimming.
     *
     * @param segments  the road's segments, not changed
     * @param spans     the road's spans, not changed, may be null
     * @param newY      the planned height of every segment (tunnelled stretches already lowered), not changed
     * @param trimmed   per segment, true if it lies in a run that can't be tunnelled, not changed
     * @param obstacles the road's obstacles
     * @param from      the road's start, for the log
     * @param to        the road's end, for the log
     * @return the kept segments split into parts, or droppedRoad() when the whole road lies on its own
     *         destination or a no-road zone, or unchangedRoad() when it lies entirely on other structures
     */
    public static RoadTrimResult plan(List<RoadSegmentPlacement> segments, @Nullable List<RoadSpan> spans, int[] newY,
                                      boolean[] trimmed, RoadObstacles obstacles, BlockPos from, BlockPos to) {
        int n = segments.size();
        logTrimmedRuns(trimmed, from, to);

        // ---- Kept segments and their heights, split into parts at every trimmed gap ----
        List<RoadSegmentPlacement> keptSegments = new ArrayList<>(n);
        List<Integer> keptTargetY = new ArrayList<>(n);
        List<int[]> parts = new ArrayList<>();
        int partStart = -1;
        for (int i = 0; i < n; i++) {
            if (trimmed[i]) {
                if (partStart >= 0) {
                    parts.add(new int[]{partStart, keptSegments.size()});
                    partStart = -1;
                }
                continue;
            }
            if (partStart < 0) {
                partStart = keptSegments.size();
            }
            keptSegments.add(segments.get(i));
            keptTargetY.add(newY[i]);
        }
        if (partStart >= 0) {
            parts.add(new int[]{partStart, keptSegments.size()});
        }

        if (keptSegments.isEmpty()) {
            // Every segment was trimmed, so every run is a trimmed run.
            boolean allOnDestinations = true;
            for (int[] run : runsOf(trimmed)) {
                allOnDestinations &= obstacles.touchesDestination(segments, run[0], run[1])
                        || obstacles.noRoadZoneIn(segments, run[0], run[1]) != null;
            }
            if (allOnDestinations) {
                /*
                    A short road inside its own destination's pieces (e.g. a branch from a village to a road
                    passing at its edge), or inside a no-road zone: paving it would run through them. It
                    isn't built.
                 */
                ModLogger.debug("Road {} -> {} lies entirely on its own destination's structures or a no-road zone. "
                        + "It isn't built.", from.toShortString(), to.toShortString());
                return RoadTrimResult.droppedRoad();
            }
            ModLogger.warn("Road {} -> {} lies entirely on structures and can't be tunneled, leaving it unchanged.",
                    from.toShortString(), to.toShortString());
            return RoadTrimResult.unchangedRoad();
        }

        return new RoadTrimResult(keptSegments, keptTargetY, parts, spansWithoutTrimmed(segments, spans, trimmed),
                !trimmed[n - 1], false, false);
    }

    /**
     * Drops the spans touching a trimmed segment.
     *
     * @param segments the road's segments, not changed
     * @param spans    the road's spans, not changed, may be null
     * @param trimmed  per segment, true if trimmed, not changed
     * @return a new list of the spans kept. A span whose ends aren't segment middles is kept
     */
    private static List<RoadSpan> spansWithoutTrimmed(List<RoadSegmentPlacement> segments,
                                                      @Nullable List<RoadSpan> spans, boolean[] trimmed) {
        List<RoadSpan> kept = new ArrayList<>();
        if (spans == null) {
            return kept;
        }
        Map<BlockPos, Integer> indexOf = new HashMap<>();
        for (int i = 0; i < segments.size(); i++) {
            indexOf.put(segments.get(i).middlePos(), i);
        }
        for (RoadSpan span : spans) {
            Integer a = indexOf.get(span.start());
            Integer b = indexOf.get(span.end());
            if (a == null || b == null || !anyIn(trimmed, Math.min(a, b), Math.max(a, b))) {
                kept.add(span);
            }
        }
        return kept;
    }

    /**
     * Lists the runs of consecutive trimmed segments.
     *
     * @param trimmed per segment, true if trimmed, not changed
     * @return {first, last} segment index per run, in road order
     */
    private static List<int[]> runsOf(boolean[] trimmed) {
        List<int[]> runs = new ArrayList<>();
        for (int i = 0; i < trimmed.length; ) {
            if (!trimmed[i]) {
                i++;
                continue;
            }
            int s = i;
            while (i < trimmed.length && trimmed[i]) {
                i++;
            }
            runs.add(new int[]{s, i - 1});
        }
        return runs;
    }

    /**
     * Logs every trimmed run (debug).
     *
     * @param trimmed per segment, true if trimmed, not changed
     * @param from    the road's start
     * @param to      the road's end
     */
    private static void logTrimmedRuns(boolean[] trimmed, BlockPos from, BlockPos to) {
        for (int[] run : runsOf(trimmed)) {
            ModLogger.debug("Road {} -> {}: structures block segments {}..{} and no tunnel can be dug. "
                    + "The road stops before them.", from.toShortString(), to.toShortString(), run[0], run[1]);
        }
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
}
