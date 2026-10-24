package net.flameslight.rwstructuresawareness.road;

import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import net.flameslight.rwstructuresawareness.logger.ModLogger;
import net.flameslight.rwstructuresawareness.tunnel.TunnelCarver;
import net.flameslight.rwstructuresawareness.types.road.RoadLevel;
import net.flameslight.rwstructuresawareness.types.road.RoadObstacles;
import net.minecraft.server.level.ServerLevel;
import net.flameslight.rwstructuresawareness.roadweaver.RoadWeaverAccessor;
import net.flameslight.rwstructuresawareness.tunnel.RoadTunnelPlanner;
import net.flameslight.rwstructuresawareness.types.tunnel.TunnelStore;
import net.flameslight.rwstructuresawareness.utils.CoordinateKeys;
import net.minecraft.core.BlockPos;
import net.shiroha233.roadweaver.core.model.RoadData;
import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;
import net.shiroha233.roadweaver.core.model.RoadSpan;
import net.shiroha233.roadweaver.core.model.SpanType;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Shapes the side road around every stretch RoadWeaver's road snapping pulls onto a main road, so the side road
 * reaches the main road through RoadWeaver's own junction pieces, walkable and never buried.
 * <p>
 * RoadWeaver's snap (RoadSnapService.trySnap) pulls a side road onto a main road wherever it runs within 12 blocks
 * of it, measured on X and Z only. Inside each such stretch the side road's segments become the main road's. At the
 * stretch's two edges, 3 junction pieces blend between the two roads (buildJunctionSegment): each paves the side
 * road's own cross-section moved 25, 50 or 75 % of the way toward the main road, plus the main road's columns there.
 * That stepped look is RoadWeaver's snapped junction. Two things go wrong there when the two roads lie at different
 * heights:
 *  - the whole height difference falls on those 3 pieces, a cliff of 5 or more blocks per piece.
 *  - a junction going down into a hill is buried, pieces and the ground between them: RoadWeaver's terrain carving
 *    is too weak below the surface.
 * <p>
 * Here, right after trySnap blended a road and before it may cut it, around every snapped stretch:
 *  - a ramp on the side road's own route: its own segments outside the stretch move toward the main road's height,
 *    no faster than RoadWeaver's slope setting allows, and only as far as needed. Their places never change, only
 *    their heights. The stretch itself stays RoadWeaver's merge onto the main road.
 *  - the junction pieces stay RoadWeaver's (same middles, same moved cross-sections), their heights blended from the
 *    ramp's, so they only cover what the ramp left. Their copy of the main road's columns is dropped, so they stop
 *    at the main road's edge instead of paving the side road over it.
 *  - the clearing of RWSA's own tunnels: a tunnel stretch over the junction (always, its ground isn't known before
 *    worldgen) and over every ramp segment lowered below its own height (it then runs under its own ground). The
 *    tunnel stretch's boxes are recorded when the road is saved (recordNewTunnelBoxes), and worldgen then clears its
 *    columns up to RoadWeaver's clear height (BeardifierMixin, TunnelCarver): the pieces' own columns and a
 *    road-wide strip between consecutive pieces, so the ground between them is open and walkable, nothing placed.
 *  - structures: a changed segment (ramp segment, junction piece) running into a recorded structure is trimmed
 *    (trimAtStructures): it paves nothing and is never cleared. No reroute, no tunnel.
 *  - no clearing where RoadWeaver won't pave: a segment whose chunk is already decorated gets no tunnel stretch.
 * RoadWeaver snaps an area again later. A junction already inside one of the road's tunnel stretches was shaped
 * then, and is kept as it is, so nothing moves and no tunnel box is recorded twice.
 * Segments are replaced in place, never inserted, so trySnap's targets, snapped stretches and cut keep their indices.
 * <p>
 * Terms: a snapped stretch is a group of consecutive side-road segments snapped onto the main road, given as a
 * {first, last} segment index pair (trySnap's {@code runs}). A tunnel stretch is a {@code RoadSpan} of type TUNNEL:
 * RoadWeaver's mark on every segment from the one whose middle position is its start to the one whose middle
 * position is its end.
 */
public final class SnapJunctionShaper {
    /** RoadSnapService.TRANSITION in RoadWeaver 2.3.0: its junction length at each edge of a snapped stretch. */
    public static final int TRANSITION = 3;
    /** The main road segments read on each side of a junction piece's target for its columns. */
    private static final int OTHER_REACH = 8;

    /**
     * Shapes the side road around every snapped stretch: the ramps on its own route, the heights of RoadWeaver's
     * junction pieces, and the tunnel stretches that clear them. Called inside trySnap, after it blended the road and before
     * tryTruncateSharedEndpoint.
     *
     * @param snapped  the snapped road's segments (trySnap's newSegs), mutated in place, same size
     * @param snappedY their heights (trySnap's targetYList), mutated in place, same size
     * @param runs     trySnap's snapped stretches, {first, last} segment index each, in road order, not changed
     * @param targets  per segment, the nearest index on {@code other} or -1, not changed
     * @param own      the road's segments before snapping, not changed
     * @param ownY     their heights, not changed, may be null
     * @param other    the road it snaps onto, not changed
     * @param otherY   that road's heights, not changed, may be null
     * @param shaped   per segment of {@code own}, true inside one of its tunnel stretches (TunnelCarver
     *                 .getTunnelSegmentsByRoadData), not changed, may be null. A junction already inside one was
     *                 shaped by an earlier snap of the area and is kept as it is: blending it again would move it
     *                 each time and record its tunnel boxes again
     * @param width    the road's width (blocks), for the cleared strips checked against structures
     * @param ownerA   the road's first destination (2D key, CoordinateKeys.pack(x, z))
     * @param ownerB   the road's other destination (2D key)
     * @return the new tunnel stretches over the segments to clear, one per continuous group of them, in a new list
     */
    public static List<RoadSpan> shape(List<RoadSegmentPlacement> snapped, List<Integer> snappedY, List<int[]> runs,
                                       int[] targets, List<RoadSegmentPlacement> own, @Nullable List<Integer> ownY,
                                       List<RoadSegmentPlacement> other, @Nullable List<Integer> otherY,
                                       @Nullable boolean[] shaped, int width, long ownerA, long ownerB) {
        List<RoadSpan> spans = new ArrayList<>();
        int n = snapped == null ? 0 : snapped.size();
        if (n == 0 || runs == null || runs.isEmpty() || targets == null || targets.length != n || own == null
                || own.size() != n || snappedY == null || snappedY.size() != n || other == null || other.isEmpty()) {
            return spans;
        }
        boolean[] done = shaped != null && shaped.length == n ? shaped : new boolean[n];
        /*
            An edge an earlier snap shaped: its junction, or the segment right outside it, lies in a tunnel stretch.
            A later snap's stretch can start or end a few segments off (the shaped junction is nearer the other
            road now), so the window covers the junction's length on both sides of the edge.
         */
        boolean[][] edgeDone = new boolean[runs.size()][2];
        for (int r = 0; r < runs.size(); r++) {
            int[] run = runs.get(r);
            int trans = junctionLength(run);
            edgeDone[r][0] = anySet(done, run[0] - trans, run[0] + trans - 1);
            edgeDone[r][1] = anySet(done, run[1] - trans + 1, run[1] + trans);
        }
        int[] base = new int[n];
        for (int i = 0; i < n; i++) {
            base[i] = heightOf(own, ownY, i);
        }
        int[] ramped = ramp(base, snapped, runs, edgeDone, targets, other, otherY);
        boolean[] clear = new boolean[n];
        boolean[] changed = new boolean[n];
        for (int r = 0; r < runs.size(); r++) {
            int lo = r == 0 ? 0 : runs.get(r - 1)[1] + 1;
            int hi = r + 1 < runs.size() ? runs.get(r + 1)[0] - 1 : n - 1;
            int[] run = runs.get(r);
            int rampBefore = 0;
            int rampAfter = 0;
            for (int i = lo; i <= hi; i++) {
                if (i >= run[0] && i <= run[1] || ramped[i] == base[i]) {
                    continue;
                }
                snappedY.set(i, ramped[i]);
                changed[i] = true;
                clear[i] |= ramped[i] < base[i];
                if (i < run[0]) {
                    rampBefore++;
                } else {
                    rampAfter++;
                }
            }
            int columns = shapeJunctions(snapped, snappedY, run, ramped, targets, own, ownY, other, otherY,
                    edgeDone[r], clear);
            ModLogger.debug("Snap junction on road {} -> {}: stretch {}..{}, ramp of {} + {} segment(s), {} junction "
                            + "column(s).", own.get(0).middlePos().toShortString(),
                    own.get(n - 1).middlePos().toShortString(), run[0], run[1], rampBefore, rampAfter, columns);
        }
        for (int i = 0; i < n; i++) {
            changed[i] |= clear[i]; // the junction pieces shaped here
        }
        int trimmed = trimAtStructures(snapped, snappedY, changed, Math.max(0, width / 2), ownerA, ownerB);
        ServerLevel level = RoadLevel.level();
        int unpaved = 0;
        for (int i = 0; i < n; i++) {
            clear[i] &= !done[i]; // already inside a tunnel stretch of the road
            BlockPos m = snapped.get(i).middlePos();
            if (clear[i] && RoadLevel.isDecorated(level, m.getX() >> 4, m.getZ() >> 4)) {
                clear[i] = false; // its chunk is decorated already: RoadWeaver never paves it, so nothing is cleared
                unpaved++;
            }
        }
        if (trimmed > 0 || unpaved > 0) {
            ModLogger.debug("Snap junction on road {} -> {}: {} segment(s) trimmed at structures, {} left uncleared "
                            + "in chunks already decorated.", own.get(0).middlePos().toShortString(),
                    own.get(n - 1).middlePos().toShortString(), trimmed, unpaved);
        }
        for (int i = 0; i < n; ) {
            if (!clear[i] || isPositionless(snapped.get(i))) {
                i++;
                continue;
            }
            int s = i;
            while (i + 1 < n && clear[i + 1] && !isPositionless(snapped.get(i + 1))) {
                i++;
            }
            spans.add(new RoadSpan(snapped.get(s).middlePos(), snapped.get(i).middlePos(), SpanType.TUNNEL));
            i++;
        }
        if (!spans.isEmpty()) {
            ModLogger.debug("Snap junction on road {} -> {}: {} stretch(es) cleared like a tunnel.",
                    own.get(0).middlePos().toShortString(), own.get(n - 1).middlePos().toShortString(), spans.size());
        }
        return spans;
    }

    /**
     * Trims the segments the snap changed (ramp segments, junction pieces) that run into a recorded structure.
     * <p>
     * The snap moved and lowered them after RWSA's structure avoidance ran on the road, so they are checked again,
     * the way RoadTunnelPlanner checks a road: RoadObstacles.lowestStructureY over each column, from the road block
     * up to the clear height, at the segment's new height, plus the cleared strip from the segment before it
     * (TunnelCarver.strip). The road's own destinations block as well. A blocked segment is trimmed, never rerouted
     * or tunnelled: it keeps its place (trySnap's indices must hold) but loses its columns, so RoadWeaver paves and
     * shapes nothing there, it gets no tunnel stretch, and no strip is cleared toward it.
     *
     * @param snapped  the snapped road's segments, mutated in place (blocked ones become positionless)
     * @param snappedY their heights, not changed
     * @param changed  per segment, true if the snap changed it here, not changed
     * @param radius   half the road width (blocks)
     * @param ownerA   the road's first destination (2D key)
     * @param ownerB   the road's other destination (2D key)
     * @return the segments trimmed, counted
     */
    private static int trimAtStructures(List<RoadSegmentPlacement> snapped, List<Integer> snappedY, boolean[] changed,
                                        int radius, long ownerA, long ownerB) {
        int n = snapped.size();
        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (int i = 0; i < n; i++) {
            if (!changed[i] || isPositionless(snapped.get(i))) {
                continue;
            }
            for (int k = Math.max(0, i - 1); k <= i; k++) {
                BlockPos m = snapped.get(k).middlePos();
                minX = Math.min(minX, m.getX() - radius - 1);
                minZ = Math.min(minZ, m.getZ() - radius - 1);
                maxX = Math.max(maxX, m.getX() + radius + 1);
                maxZ = Math.max(maxZ, m.getZ() + radius + 1);
            }
            for (BlockPos p : snapped.get(i).positions()) {
                minX = Math.min(minX, p.getX());
                minZ = Math.min(minZ, p.getZ());
                maxX = Math.max(maxX, p.getX());
                maxZ = Math.max(maxZ, p.getZ());
            }
        }
        if (minX > maxX) {
            return 0;
        }
        RoadObstacles obstacles = RoadObstacles.forArea(minX, minZ, maxX, maxZ, CoordinateKeys.unpackA(ownerA),
                CoordinateKeys.unpackB(ownerA), CoordinateKeys.unpackA(ownerB), CoordinateKeys.unpackB(ownerB),
                RoadWeaverAccessor.configuredRoadMargin());
        if (obstacles == null) {
            return 0; // nothing recorded nearby
        }
        int clearHeight = RoadWeaverAccessor.roadClearHeight();
        int trimmed = 0;
        for (int i = 0; i < n; i++) {
            RoadSegmentPlacement segment = snapped.get(i);
            if (!changed[i] || isPositionless(segment)) {
                continue;
            }
            LongLinkedOpenHashSet cells = new LongLinkedOpenHashSet();
            for (BlockPos p : segment.positions()) {
                cells.add(CoordinateKeys.pack(p.getX(), p.getZ()));
            }
            if (i > 0 && !isPositionless(snapped.get(i - 1))) {
                TunnelCarver.strip(snapped.get(i - 1).middlePos(), segment.middlePos(), radius, cells);
            }
            int y = snappedY.get(i);
            boolean blocked = false;
            for (long cell : cells) {
                if (obstacles.lowestStructureY(CoordinateKeys.unpackA(cell), CoordinateKeys.unpackB(cell), y - 1,
                        y + clearHeight) != RoadObstacles.NOT_BLOCKED) {
                    blocked = true;
                    break;
                }
            }
            if (blocked) {
                snapped.set(i, new RoadSegmentPlacement(segment.middlePos(), List.of()));
                trimmed++;
            }
        }
        return trimmed;
    }

    /**
     * Sets the heights of RoadWeaver's junction pieces at both edges of one snapped stretch from the ramp, drops
     * their main road columns (offOtherRoad), and marks them to be cleared. The pieces' middles and moved
     * cross-sections stay RoadWeaver's. An edge whose junction an earlier snap already shaped keeps the road's own
     * segments there.
     *
     * @param snapped  the snapped road's segments, mutated in place
     * @param snappedY their heights, mutated in place
     * @param run      the stretch, {first, last} segment index
     * @param ramped   the road's own heights after the ramps, not changed
     * @param targets  per segment, the nearest index on {@code other} or -1, not changed
     * @param own      the road's segments before snapping, not changed
     * @param ownY     their heights, not changed, may be null
     * @param other    the road it snaps onto, not changed
     * @param otherY   that road's heights, not changed, may be null
     * @param edgeDone {start edge, end edge}, true where an earlier snap already shaped it, not changed
     * @param clear    per segment, true to clear it, mutated (set for every junction piece shaped here)
     * @return the junction pieces' paved columns, counted
     */
    private static int shapeJunctions(List<RoadSegmentPlacement> snapped, List<Integer> snappedY, int[] run,
                                      int[] ramped, int[] targets, List<RoadSegmentPlacement> own,
                                      @Nullable List<Integer> ownY, List<RoadSegmentPlacement> other,
                                      @Nullable List<Integer> otherY, boolean[] edgeDone, boolean[] clear) {
        int trans = junctionLength(run);
        int columns = 0;
        for (int i = run[0]; i <= run[1]; i++) {
            double ratio = ratio(i, run, trans);
            if (ratio >= 1) {
                continue;
            }
            if (edgeDone[i - run[0] <= run[1] - i ? 0 : 1]) {
                snapped.set(i, own.get(i)); // shaped by an earlier snap: blending it again would move it
                snappedY.set(i, heightOf(own, ownY, i));
                continue;
            }
            int t = targets[i];
            if (t < 0 || t >= other.size() || isPositionless(snapped.get(i))) {
                continue;
            }
            int y = ramped[i];
            snappedY.set(i, (int) Math.round(y + (heightOf(other, otherY, t) - y) * ratio));
            snapped.set(i, offOtherRoad(snapped.get(i), own.get(i).middlePos(), other, t));
            clear[i] = true;
            columns += snapped.get(i).positions().size();
        }
        return columns;
    }

    /**
     * Drops a junction piece's columns that belong to the road it snaps onto.
     * <p>
     * RoadWeaver's piece paves the side road's own cross-section moved toward the main road, plus a copy of the main
     * road's columns at its target, in the side road's materials and at the junction's height. Without that copy the
     * piece stops at the main road's edge, and the main road keeps its own blocks. The main road's columns are taken
     * from its segments around the target only (OTHER_REACH on each side), which covers every piece. A piece lying
     * wholly on the main road keeps its one column nearest the side road's own middle: without positions it would
     * read as a cut road's anchor (JunctionRepair).
     *
     * @param piece      RoadWeaver's junction piece, not changed
     * @param ownMiddle  the side road's own middle for that segment, before snapping
     * @param other      the road it snaps onto, not changed
     * @param target     the piece's target index on {@code other}
     * @return the piece without the main road's columns, a new placement with the same middle
     */
    private static RoadSegmentPlacement offOtherRoad(RoadSegmentPlacement piece, BlockPos ownMiddle,
                                                     List<RoadSegmentPlacement> other, int target) {
        Set<Long> otherColumns = new HashSet<>();
        for (int k = Math.max(0, target - OTHER_REACH); k <= Math.min(other.size() - 1, target + OTHER_REACH); k++) {
            List<BlockPos> positions = other.get(k).positions();
            if (positions != null) {
                for (BlockPos p : positions) {
                    otherColumns.add(CoordinateKeys.pack(p.getX(), p.getZ()));
                }
            }
        }
        List<BlockPos> kept = new ArrayList<>(piece.positions().size());
        BlockPos nearest = null;
        for (BlockPos p : piece.positions()) {
            if (!otherColumns.contains(CoordinateKeys.pack(p.getX(), p.getZ()))) {
                kept.add(p);
            } else if (nearest == null || p.distSqr(ownMiddle) < nearest.distSqr(ownMiddle)) {
                nearest = p;
            }
        }
        if (kept.size() == piece.positions().size()) {
            return piece;
        }
        if (kept.isEmpty()) {
            kept.add(nearest); // wholly on the main road: one column, never positionless
        }
        return new RoadSegmentPlacement(piece.middlePos(), kept);
    }

    /**
     * Tells whether any flag in a range is set.
     *
     * @param flags the flags, not changed
     * @param from  the first index (inclusive), clamped to the array
     * @param to    the last index (inclusive), clamped to the array
     * @return true if a flag in from..to is set
     */
    private static boolean anySet(boolean[] flags, int from, int to) {
        for (int i = Math.max(0, from); i <= Math.min(flags.length - 1, to); i++) {
            if (flags[i]) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the junction length at each edge of a snapped stretch, as RoadWeaver's computeRatio uses it.
     *
     * @param run the stretch, {first, last} segment index
     * @return min(TRANSITION, (stretch length + 1) / 2) segments
     */
    public static int junctionLength(int[] run) {
        return Math.min(TRANSITION, (run[1] - run[0] + 2) / 2);
    }

    /**
     * Computes the road's own heights with a ramp outside every snapped stretch.
     * <p>
     * Each edge of a stretch has an apex: its first fully snapped segment, at the height of the main road segment
     * it snaps onto. A segment d segments from the apex may be at most (step2 * d + 1) / 2 blocks from that height,
     * the same slope RoadTunnelPlanner's ramps use. A segment already within it keeps its own height, so a ramp ends
     * by itself. A ramp stays in the gap between its stretch and the next one (or the road's end), and stops at the
     * first segment without positions (another junction's anchor or lead segments). Where two ramps share a gap, a
     * segment takes a height both allow, or the nearer edge's when none fits both. Junction segments are ramped
     * from their own edge too, so their blend starts from the ramp. An edge an earlier snap already shaped gets no
     * ramp again.
     *
     * @param base     the road's own heights, not changed
     * @param snapped  the snapped road's segments, read for positionless segments only
     * @param runs     the snapped stretches, {first, last} segment index each, in road order, not changed
     * @param edgeDone per stretch, {start edge, end edge}, true where an earlier snap already shaped it, not changed
     * @param targets  per segment, the nearest index on {@code other} or -1, not changed
     * @param other    the road it snaps onto, not changed
     * @param otherY   that road's heights, not changed, may be null
     * @return the ramped heights, a new array (equal to {@code base} where nothing changes)
     */
    private static int[] ramp(int[] base, List<RoadSegmentPlacement> snapped, List<int[]> runs, boolean[][] edgeDone,
                              int[] targets, List<RoadSegmentPlacement> other, @Nullable List<Integer> otherY) {
        int n = base.length;
        int step2 = RoadWeaverAccessor.slopeLimitEnabled() ? RoadWeaverAccessor.maxSlopeStepPerTwoSegments()
                : RoadWeaverAccessor.UNLIMITED_SLOPE_STEP;
        int[] out = base.clone();
        if (step2 <= 0) {
            return out; // no slope allowed at all: RoadWeaver's own junction is all there is
        }
        for (int g = 0; g <= runs.size(); g++) {
            int[] before = g > 0 ? runs.get(g - 1) : null;
            int[] after = g < runs.size() ? runs.get(g) : null;
            // The gap's own route, plus the junction segments of both edges facing it.
            int lo = before == null ? 0 : before[1] - junctionLength(before) + 1;
            int hi = after == null ? n - 1 : after[0] + junctionLength(after) - 1;
            int leftApex = before == null ? -1 : Math.max(before[0], before[1] - junctionLength(before));
            int rightApex = after == null ? -1 : Math.min(after[1], after[0] + junctionLength(after));
            int leftH = before == null || edgeDone[g - 1][1] ? Integer.MIN_VALUE
                    : apexHeight(leftApex, targets, other, otherY);
            int rightH = after == null || edgeDone[g][0] ? Integer.MIN_VALUE
                    : apexHeight(rightApex, targets, other, otherY);
            // How far each ramp may reach: up to the first positionless segment.
            int leftReach = lo - 1;
            if (leftH != Integer.MIN_VALUE) {
                while (leftReach + 1 <= hi && !isPositionless(snapped.get(leftReach + 1))) {
                    leftReach++;
                }
            }
            int rightReach = hi + 1;
            if (rightH != Integer.MIN_VALUE) {
                while (rightReach - 1 >= lo && !isPositionless(snapped.get(rightReach - 1))) {
                    rightReach--;
                }
            }
            for (int i = lo; i <= hi; i++) {
                boolean left = i <= leftReach;
                boolean right = i >= rightReach;
                int leftAllow = left ? (step2 * (i - leftApex) + 1) / 2 : 0;
                int rightAllow = right ? (step2 * (rightApex - i) + 1) / 2 : 0;
                int min;
                int max;
                if (left && right) {
                    min = Math.max(leftH - leftAllow, rightH - rightAllow);
                    max = Math.min(leftH + leftAllow, rightH + rightAllow);
                    if (min > max) {
                        boolean nearerLeft = i - leftApex <= rightApex - i;
                        min = nearerLeft ? leftH - leftAllow : rightH - rightAllow;
                        max = nearerLeft ? leftH + leftAllow : rightH + rightAllow;
                    }
                } else if (left) {
                    min = leftH - leftAllow;
                    max = leftH + leftAllow;
                } else if (right) {
                    min = rightH - rightAllow;
                    max = rightH + rightAllow;
                } else {
                    continue;
                }
                out[i] = Math.max(min, Math.min(max, base[i]));
            }
        }
        return out;
    }

    /**
     * Returns the main road's height at an edge's apex.
     *
     * @param apex    the apex segment's index on the snapped road, -1 for none
     * @param targets per segment, the nearest index on {@code other} or -1
     * @param other   the road it snaps onto
     * @param otherY  that road's heights, may be null
     * @return the height of the main road segment the apex snaps onto, Integer.MIN_VALUE if there is none
     */
    private static int apexHeight(int apex, int[] targets, List<RoadSegmentPlacement> other,
                                  @Nullable List<Integer> otherY) {
        if (apex < 0 || apex >= targets.length || targets[apex] < 0 || targets[apex] >= other.size()) {
            return Integer.MIN_VALUE;
        }
        return heightOf(other, otherY, targets[apex]);
    }

    /**
     * Returns a segment's blend ratio toward the road it snaps onto, as RoadWeaver's computeRatio does.
     *
     * @param i     the segment's index, inside {@code run}
     * @param run   the stretch, {first, last} segment index
     * @param trans the junction length at each edge
     * @return 1 inside the stretch, (k + 1) / (trans + 1) on the k-th junction segment from an edge, the smaller one
     *         where both edges' junctions reach
     */
    private static double ratio(int i, int[] run, int trans) {
        int k = Math.min(i - run[0], run[1] - i);
        return k < trans ? (k + 1) / (double) (trans + 1) : 1;
    }

    /**
     * Records the tunnel boxes of a saved road's new tunnel stretches: those its replaced version didn't have.
     * <p>
     * RoadWeaver replaces a road when its snap changed it, and the only tunnel stretches a snap adds are the ones
     * shape returned. RoadTunnelPlanner's own tunnel stretches were in the road already, with their boxes recorded
     * when it was built. So a later snap of the same area, which keeps a tunnel stretch it already has, records
     * nothing twice. The boxes are RoadTunnelPlanner's: one per segment at its height, one over every pair of
     * consecutive segments, the segments right before and after the tunnel stretch included (TunnelCarver clears
     * the strips into them too). They tell worldgen (BeardifierMixin, TunnelCarver) where to clear.
     *
     * @param oldRoad the road as stored before, not changed, null if unknown (then every tunnel stretch counts as
     *                new)
     * @param newRoad the road as stored now, not changed, may be null (then nothing happens)
     */
    public static void recordNewTunnelBoxes(@Nullable RoadData oldRoad, @Nullable RoadData newRoad) {
        List<RoadSpan> spans = newRoad == null ? null : newRoad.spans();
        if (spans == null || spans.isEmpty()) {
            return;
        }
        List<RoadSegmentPlacement> segments = newRoad.roadSegmentList();
        List<Integer> ys = newRoad.targetY();
        if (segments == null || ys == null || ys.size() != segments.size()) {
            return;
        }
        List<RoadSpan> known = oldRoad == null || oldRoad.spans() == null ? List.of() : oldRoad.spans();
        Map<BlockPos, Integer> indexOf = null;
        int clearHeight = RoadWeaverAccessor.roadClearHeight();
        int margin = RoadWeaverAccessor.configuredRoadMargin();
        for (RoadSpan span : spans) {
            if (span == null || span.type() != SpanType.TUNNEL || known.contains(span)) {
                continue;
            }
            if (indexOf == null) {
                indexOf = new HashMap<>();
                for (int i = 0; i < segments.size(); i++) {
                    indexOf.put(segments.get(i).middlePos(), i);
                }
            }
            Integer a = indexOf.get(span.start());
            Integer b = indexOf.get(span.end());
            if (a == null || b == null) {
                continue;
            }
            int s = Math.min(a, b);
            int e = Math.max(a, b);
            // From the segment before the tunnel stretch to the one after it: TunnelCarver also clears the strips into them.
            for (int i = Math.max(0, s - 1); i <= Math.min(segments.size() - 1, e + 1); i++) {
                if (isPositionless(segments.get(i))) {
                    continue;
                }
                if (i >= s && i <= e) {
                    TunnelStore.add(RoadTunnelPlanner.tunnelBox(List.of(segments.get(i)), ys.get(i), clearHeight,
                            margin));
                }
                if (i > Math.max(0, s - 1) && !isPositionless(segments.get(i - 1))) {
                    TunnelStore.add(RoadTunnelPlanner.tunnelBox(List.of(segments.get(i - 1), segments.get(i)),
                            Math.max(ys.get(i - 1), ys.get(i)), clearHeight, margin));
                }
            }
        }
    }

    /**
     * Tells whether a segment has no positions (a cut road's anchor or lead segment).
     *
     * @param segment the segment, may be null
     * @return true if it paves nothing
     */
    private static boolean isPositionless(@Nullable RoadSegmentPlacement segment) {
        return segment == null || segment.positions() == null || segment.positions().isEmpty();
    }

    /**
     * Returns a segment's height.
     *
     * @param road the road's segments
     * @param ys   the road's heights, may be null or mismatched
     * @param i    the segment's index
     * @return its saved height, or its middle's Y when there is none
     */
    private static int heightOf(List<RoadSegmentPlacement> road, @Nullable List<Integer> ys, int i) {
        return ys != null && ys.size() == road.size() && ys.get(i) != null ? ys.get(i) : road.get(i).middlePos().getY();
    }
}
