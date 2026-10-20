package net.flameslight.rwstructuresawareness.types.tunnel;

import net.minecraft.core.BlockPos;
import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;
import net.shiroha233.roadweaver.core.model.RoadSpan;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A road changed by RoadTunnelPlanner and RoadTrimPlanner together: the tunnel results (the new heights,
 * TUNNEL spans and dig volumes) and the trim results (the kept segments, in order, split into parts at every
 * trimmed gap). RoadMixin applies it to the RoadData RoadWeaver builds.
 */
public final class TunnelPlan {
    /** The segment list the plan was computed for, compared by identity (RoadTunnelPlanner.planFor). */
    public final List<RoadSegmentPlacement> originalSegments;
    public final List<RoadSegmentPlacement> keptSegments;
    public final List<RoadSpan> keptSpans;
    public final List<Integer> keptTargetY;
    /** [fromIndex, toIndex) ranges into the kept lists, one per road piece. */
    public final List<int[]> parts;
    /** Dig volumes for TunnelStore, TunnelStore.BOX_INTS ints each. */
    public final List<int[]> tunnelBoxes;
    /** True if the last part still ends at the road's original end (its last segment was kept). */
    public final boolean reachesEnd;
    /** The whole road lies on its own destination or a no-road zone: nothing of it is saved or decorated (RoadMixin). */
    public final boolean dropped;

    /**
     * Creates a plan for a road that is kept, in one or more parts.
     *
     * @param originalSegments the segment list the plan was computed for, kept by identity
     * @param keptSegments     the kept segments, in road order
     * @param keptSpans        the spans to save, TUNNEL spans included
     * @param keptTargetY      the new height of each kept segment (first air Y above the road)
     * @param parts            [fromIndex, toIndex) ranges into the kept lists, one per road piece
     * @param tunnelBoxes      dig volumes, TunnelStore.BOX_INTS ints each
     * @param reachesEnd       true if the road's last segment was kept
     */
    public TunnelPlan(List<RoadSegmentPlacement> originalSegments, List<RoadSegmentPlacement> keptSegments,
                      List<RoadSpan> keptSpans, List<Integer> keptTargetY, List<int[]> parts, List<int[]> tunnelBoxes,
                      boolean reachesEnd) {
        this(originalSegments, keptSegments, keptSpans, keptTargetY, parts, tunnelBoxes, reachesEnd, false);
    }

    /**
     * Creates a plan.
     *
     * @param originalSegments the segment list the plan was computed for, kept by identity
     * @param keptSegments     the kept segments, in road order
     * @param keptSpans        the spans to save, TUNNEL spans included
     * @param keptTargetY      the new height of each kept segment (first air Y above the road)
     * @param parts            [fromIndex, toIndex) ranges into the kept lists, one per road piece
     * @param tunnelBoxes      dig volumes, TunnelStore.BOX_INTS ints each
     * @param reachesEnd       true if the road's last segment was kept
     * @param dropped          true if nothing of the road is built
     */
    private TunnelPlan(List<RoadSegmentPlacement> originalSegments, List<RoadSegmentPlacement> keptSegments,
                       List<RoadSpan> keptSpans, List<Integer> keptTargetY, List<int[]> parts, List<int[]> tunnelBoxes,
                       boolean reachesEnd, boolean dropped) {
        this.originalSegments = originalSegments;
        this.keptSegments = keptSegments;
        this.keptSpans = keptSpans;
        this.keptTargetY = keptTargetY;
        this.parts = parts;
        this.tunnelBoxes = tunnelBoxes;
        this.reachesEnd = reachesEnd;
        this.dropped = dropped;
    }

    /**
     * Creates the plan of a road that isn't built at all.
     *
     * @param originalSegments the segment list the plan was computed for, kept by identity
     * @return a dropped plan with no kept segment
     */
    public static TunnelPlan dropped(List<RoadSegmentPlacement> originalSegments) {
        return new TunnelPlan(originalSegments, List.of(), List.of(), List.of(), List.of(), List.of(), false, true);
    }

    /**
     * Returns the spans starting in one road piece.
     *
     * @param part the piece's index in parts
     * @return the kept spans whose start lies in that piece, a new list
     */
    public List<RoadSpan> spansOfPart(int part) {
        int[] range = parts.get(part);
        Map<BlockPos, Integer> index = new HashMap<>();
        for (int i = range[0]; i < range[1]; i++) {
            index.put(keptSegments.get(i).middlePos(), i);
        }
        List<RoadSpan> out = new ArrayList<>();
        for (RoadSpan span : keptSpans) {
            if (index.containsKey(span.start())) {
                out.add(span);
            }
        }
        return out;
    }
}
