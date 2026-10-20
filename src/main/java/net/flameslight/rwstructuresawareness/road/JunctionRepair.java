package net.flameslight.rwstructuresawareness.road;

import net.flameslight.rwstructuresawareness.logger.ModLogger;
import net.minecraft.core.BlockPos;
import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Reconnects a side road that RoadWeaver cut short where it meets a main road, so it is paved right into it.
 * <p>
 * RoadWeaver's road snapping (RoadSnapService.trySnap) directs a side road onto a main road it runs alongside,
 * and blends a few "junction" segments, partially carved road path, so the side road bends into the main one.
 * When both roads lead to the same destination, it then cuts the side road (tryTruncateSharedEndpoint):
 * the stretch alongside, from the shared destination up to the stretch's far edge, is replaced by one
 * positionless anchor segment on the main road, which only tells RoadWeaver's map where the road ends.
 * The cut also throws away the junction segments it just blended. On top of that, RoadWeaver's PathFeature
 * never paves a road's first ~9 and last ~7 segments (its segment counter must lie in [8, size - 8]). So the
 * visibly, the result is a side road that stops several blocks before the main road.
 * <p>
 * The repair (restoreJunction), run right after the cut (see RoadSnapServiceMixin), has three steps:
 *  1. Puts the dropped junction segments back between the kept part and the anchor, with their blended heights.
 *  2. Adds a few positionless lead segments on the main road next to the joint, at the main road's heights.
 *     They cover PathFeature's unpaved first or last segments, so the junction falls in the paved range. They
 *     lie between the main road's segment middles, never on one, because PathFeature skips a middle it
 *     already paved in that chunk.
 *  3. Moves the anchor onto the main road just past the lead segments, so RoadWeaver's map draws the road
 *     ending where it joins instead of at the far end of the stretch alongside.
 * <p>
 */
public final class JunctionRepair {
    /** RoadSnapService.TRANSITION in RoadWeaver 2.3.0. */
    private static final int TRANSITION = 3;
    /** Positionless segments after the junction at a shared end: with the anchor, PathFeature's last 7 unpaved. */
    private static final int LEAD_OUT = 6;
    /** Positionless segments before the junction at a shared start: with the anchor, PathFeature's first 9 unpaved. */
    private static final int LEAD_IN = 8;

    /**
     * Puts back the junction segments RoadWeaver's snap truncation dropped, and leads the road into the one
     * it joins. Does nothing if the lists don't match the expected truncated shape.
     *
     * @param segments the truncated result's segments, mutated in place
     * @param targetY  the truncated result's heights, mutated in place
     * @param runs     trySnap's runs along the other road, {first, last} snapped segment index each
     * @param snapped  the road's segments before truncation, not changed
     * @param snappedY their heights, not changed
     * @param other    the road it joins, not changed, may be null
     * @param otherY   that road's heights, not changed, may be null
     * @param targets  trySnap's snap targets, per snapped segment the nearest index on {@code other} or -1
     */
    public static void restoreJunction(List<RoadSegmentPlacement> segments, List<Integer> targetY, List<int[]> runs,
                                       List<RoadSegmentPlacement> snapped, List<Integer> snappedY,
                                       List<RoadSegmentPlacement> other, List<Integer> otherY, int[] targets) {
        if (segments == null || targetY == null || runs == null || runs.isEmpty() || snapped == null
                || snappedY == null || segments.size() < 2 || segments.size() != targetY.size()
                || snapped.size() != snappedY.size()) {
            return;
        }
        boolean endAnchor = isAnchor(segments.get(segments.size() - 1));
        boolean startAnchor = isAnchor(segments.get(0));
        if (endAnchor == startAnchor) {
            return; // not a truncation (or unexpected shape)
        }
        try {
            if (endAnchor) {
                int[] run = runs.get(runs.size() - 1);
                int start = run[0];
                int trans = Math.min(TRANSITION, (run[1] - run[0] + 2) / 2);
                if (segments.size() - 1 != start || start + trans > snapped.size()) {
                    return; // kept prefix isn't snapped[0 .. start-1]
                }
                int at = segments.size() - 1;
                segments.addAll(at, snapped.subList(start, start + trans));
                targetY.addAll(at, snappedY.subList(start, start + trans));
                int joint = target(targets, start + trans - 1, other);
                leadInto(segments, targetY, segments.size() - 1, joint, other, otherY, LEAD_OUT, false);
                log("end", start, trans, segments.get(segments.size() - 1));
            } else {
                int[] run = runs.get(0);
                int end = run[1];
                int trans = Math.min(TRANSITION, (run[1] - run[0] + 2) / 2);
                if (segments.size() - 1 != snapped.size() - (end + 1) || end - trans + 1 < 0) {
                    return; // kept tail isn't snapped[end+1 .. n-1]
                }
                segments.addAll(1, snapped.subList(end - trans + 1, end + 1));
                targetY.addAll(1, snappedY.subList(end - trans + 1, end + 1));
                int joint = target(targets, end - trans + 1, other);
                leadInto(segments, targetY, 0, joint, other, otherY, LEAD_IN, true);
                log("start", end, trans, segments.get(0));
            }
        } catch (RuntimeException e) {
            ModLogger.error("Could not restore a road junction", e);
        }
    }

    /**
     * Inserts positionless lead segments on the joined road next to the joint, and moves the anchor right
     * after them.
     *
     * @param segments the road's segments, mutated
     * @param targetY  the road's heights, mutated
     * @param anchorAt the anchor's index in {@code segments}
     * @param joint    the index on {@code other} the junction meets, -1 if unknown (then nothing happens)
     * @param other    the joined road, not changed
     * @param otherY   its heights, not changed, may be null
     * @param count    the most lead segments to insert
     * @param atStart  true if the anchor is the road's first segment (the lead goes between it and the
     *                 junction, in road order), false if it is the last
     */
    private static void leadInto(List<RoadSegmentPlacement> segments, List<Integer> targetY, int anchorAt, int joint,
                                 List<RoadSegmentPlacement> other, List<Integer> otherY, int count, boolean atStart) {
        if (joint < 0 || other == null || other.size() < 2) {
            return;
        }
        int anchorIndex = indexOfMiddle(other, segments.get(anchorAt).middlePos());
        int dir = anchorIndex >= 0 && anchorIndex < joint ? -1 : 1;
        Set<BlockPos> otherMiddles = new HashSet<>();
        for (RoadSegmentPlacement s : other) {
            otherMiddles.add(s.middlePos());
        }
        Set<BlockPos> used = new HashSet<>();
        for (RoadSegmentPlacement s : segments) {
            used.add(s.middlePos());
        }
        List<RoadSegmentPlacement> lead = new ArrayList<>();
        List<Integer> leadY = new ArrayList<>();
        int k = joint;
        for (; lead.size() < count && k + dir >= 0 && k + dir < other.size(); k += dir) {
            BlockPos a = other.get(k).middlePos();
            BlockPos b = other.get(k + dir).middlePos();
            BlockPos mid = new BlockPos((a.getX() + b.getX()) >> 1, (a.getY() + b.getY()) >> 1, (a.getZ() + b.getZ()) >> 1);
            if (otherMiddles.contains(mid) || !used.add(mid)) {
                continue; // never one of the other road's middles (PathFeature deduplicates them per chunk)
            }
            lead.add(new RoadSegmentPlacement(mid, List.of()));
            leadY.add((heightOf(other, otherY, k) + heightOf(other, otherY, k + dir)) / 2);
        }
        if (lead.isEmpty()) {
            return;
        }
        // The anchor: the other road's next middle past the lead segments.
        int anchorOn = Math.max(0, Math.min(other.size() - 1, k + dir));
        RoadSegmentPlacement anchor = new RoadSegmentPlacement(other.get(anchorOn).middlePos(), List.of());
        int anchorY = heightOf(other, otherY, anchorOn);
        if (atStart) {
            Collections.reverse(lead); // road order: anchor, then toward the joint
            Collections.reverse(leadY);
            segments.set(0, anchor);
            targetY.set(0, anchorY);
            segments.addAll(1, lead);
            targetY.addAll(1, leadY);
        } else {
            segments.set(anchorAt, anchor);
            targetY.set(anchorAt, anchorY);
            segments.addAll(anchorAt, lead);
            targetY.addAll(anchorAt, leadY);
        }
    }

    /**
     * Reads one snap target.
     *
     * @param targets trySnap's snap targets, may be null
     * @param index   the snapped segment's index
     * @param other   the joined road, may be null
     * @return the target index on {@code other}, -1 if missing or out of range
     */
    private static int target(int[] targets, int index, List<RoadSegmentPlacement> other) {
        if (targets == null || index < 0 || index >= targets.length || other == null) {
            return -1;
        }
        int t = targets[index];
        return t >= 0 && t < other.size() ? t : -1;
    }

    /**
     * Finds a segment by its middle position.
     *
     * @param road   the road's segments
     * @param middle the middle position
     * @return the segment's index, -1 if none has that middle
     */
    private static int indexOfMiddle(List<RoadSegmentPlacement> road, BlockPos middle) {
        for (int i = 0; i < road.size(); i++) {
            if (road.get(i).middlePos().equals(middle)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Returns a segment's height.
     *
     * @param road the road's segments
     * @param ys   the road's heights, may be null or mismatched
     * @param i    the segment's index
     * @return its saved height, or its middle's Y when there is none
     */
    private static int heightOf(List<RoadSegmentPlacement> road, List<Integer> ys, int i) {
        return ys != null && ys.size() == road.size() && ys.get(i) != null ? ys.get(i) : road.get(i).middlePos().getY();
    }

    /**
     * Tells whether a road was already truncated onto another.
     *
     * @param road  the road, not changed, may be null
     * @param other the other road, not changed, may be null
     * @return true if a positionless end segment of {@code road} lies on one of {@code other}'s middles
     */
    public static boolean alreadyJoined(List<RoadSegmentPlacement> road, List<RoadSegmentPlacement> other) {
        if (road == null || road.isEmpty() || other == null || other.isEmpty()) {
            return false;
        }
        RoadSegmentPlacement first = road.get(0);
        RoadSegmentPlacement last = road.get(road.size() - 1);
        boolean firstOpen = isAnchor(first);
        boolean lastOpen = isAnchor(last);
        if (!firstOpen && !lastOpen) {
            return false;
        }
        for (RoadSegmentPlacement s : other) {
            BlockPos m = s.middlePos();
            if ((firstOpen && m.equals(first.middlePos())) || (lastOpen && m.equals(last.middlePos()))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Tells whether a segment has no positions: RoadWeaver's truncation anchor, or one of the lead segments.
     *
     * @param segment the segment, may be null
     * @return true if it is non-null and has no positions
     */
    public static boolean isAnchor(RoadSegmentPlacement segment) {
        return segment != null && (segment.positions() == null || segment.positions().isEmpty());
    }

    /**
     * Logs a restored junction (debug).
     *
     * @param side    "start" or "end"
     * @param runEdge the run's edge segment index
     * @param trans   the junction segments restored
     * @param anchor  the road's anchor after the repair
     */
    private static void log(String side, int runEdge, int trans, RoadSegmentPlacement anchor) {
        ModLogger.debug("Road snap truncated a road at its shared {} (run edge segment {}): restored {} junction "
                + "segment(s) leading into the road it joins at {}.", side, runEdge, trans,
                anchor.middlePos() == null ? "?" : anchor.middlePos().toShortString());
    }
}
