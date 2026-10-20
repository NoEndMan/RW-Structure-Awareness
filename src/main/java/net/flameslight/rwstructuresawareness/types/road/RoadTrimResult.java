package net.flameslight.rwstructuresawareness.types.road;

import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;
import net.shiroha233.roadweaver.core.model.RoadSpan;

import java.util.List;

/**
 * What RoadTrimPlanner made of a road: the segments kept after trimming, split into parts at every trimmed
 * gap, or one of the two whole-road answers (dropped, unchanged).
 *
 * @param keptSegments the kept segments, in road order, empty when dropped or unchanged
 * @param keptTargetY  the planned height of each kept segment (first air Y above the road)
 * @param parts        [fromIndex, toIndex) ranges into the kept lists, one per road piece
 * @param keptSpans    the road's spans minus those touching a trimmed segment
 * @param reachesEnd   true if the road's last segment was kept
 * @param dropped      true if the road lies entirely on its own destination or a no-road zone: it isn't built
 * @param unchanged    true if the road lies entirely on other structures: it is left as RoadWeaver planned it
 */
public record RoadTrimResult(List<RoadSegmentPlacement> keptSegments, List<Integer> keptTargetY, List<int[]> parts,
                             List<RoadSpan> keptSpans, boolean reachesEnd, boolean dropped, boolean unchanged) {
    /**
     * Creates the result for a road that isn't built at all.
     *
     * @return a dropped result
     */
    public static RoadTrimResult droppedRoad() {
        return new RoadTrimResult(List.of(), List.of(), List.of(), List.of(), false, true, false);
    }

    /**
     * Creates the result for a road left as RoadWeaver planned it.
     *
     * @return an unchanged result
     */
    public static RoadTrimResult unchangedRoad() {
        return new RoadTrimResult(List.of(), List.of(), List.of(), List.of(), false, false, true);
    }
}
