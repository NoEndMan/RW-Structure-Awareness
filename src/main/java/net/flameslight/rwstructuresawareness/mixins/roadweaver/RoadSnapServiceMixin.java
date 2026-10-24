package net.flameslight.rwstructuresawareness.mixins.roadweaver;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import net.flameslight.rwstructuresawareness.road.JunctionRepair;
import net.flameslight.rwstructuresawareness.road.SnapJunctionShaper;
import net.flameslight.rwstructuresawareness.tunnel.TunnelCarver;
import net.shiroha233.roadweaver.core.model.RoadData;
import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;
import net.shiroha233.roadweaver.core.model.RoadSpan;
import net.shiroha233.roadweaver.postprocess.RoadSnapService;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

/**
 * Makes RoadWeaver's road snapping join a side road cleanly onto a main road, with ramps, and keeps that join.
 * <p>
 * What RoadWeaver does: after roads are saved, RoadSnapService.trySnap(main, side) runs on every pair of roads in
 * the area. It looks for snapped stretches: 3 or more consecutive side-road segments that lie close to the main
 * road, each as a {first, last} segment index pair (extractRuns). It moves those side-road segments onto the main
 * road and blends a few junction segments at each edge of every snapped stretch.
 * <p>
 * The problem: when both roads end at the same structure, trySnap then cuts the side road short
 * (tryTruncateSharedEndpoint), leaving a gap before the main road.
 * <p>
 * The three hooks, in the order they act inside trySnap:
 * <ol>
 *     <li>{@code skipAlreadyJoined} (at extractRuns): a road is never snapped and cut twice.</li>
 *     <li>{@code shapeJunctions} (before tryTruncateSharedEndpoint): builds the ramps and junction heights.</li>
 *     <li>{@code repairCutJunction} (at filterSpansByRetainedSegments, only when the side road was cut):
 *     joins the cut side road back onto the main road.</li>
 * </ol>
 * A {@code RoadSpan} is RoadWeaver's mark on a stretch of road (TUNNEL or BRIDGE), from one segment's middle
 * position to another's. In this class such a stretch is called a tunnel stretch or a bridge stretch.
 */
@Mixin(value = RoadSnapService.class, remap = false)
public abstract class RoadSnapServiceMixin {
    /**
     * Builds the ramps and junction heights around every snapped stretch of the side road, and adds a tunnel
     * stretch wherever the side road has to dig through the main road's embankment (SnapJunctionShaper.shape).
     * <p>
     * Runs right before tryTruncateSharedEndpoint, so the side road is fully moved onto the main road but not cut
     * yet, whether or not the cut happens afterwards. The new tunnel stretches are added to the side road's own.
     * <p>
     *
     * @param cir       trySnap's return value, not used
     * @param secondary the side road (local 2), read for its tunnel stretches, width and the two structures it joins
     * @param own       the side road's segments before snapping (local 3)
     * @param other     the main road's segments (local 4)
     * @param targets   for each side-road segment, the nearest main-road segment index, or -1 if none is close
     *                  (local 6)
     * @param runs      the snapped stretches, {first, last} side-road segment index each (local 8)
     * @param snapped   the side road's segments after snapping (local 9), changed in place
     * @param ownY      the side road's heights before snapping (local 10), may be null
     * @param otherY    the main road's heights (local 11), may be null
     * @param snappedY  the heights of the snapped segments (local 14), changed in place
     * @param spans     the side road's tunnel and bridge stretches (local 17), replaced with the new tunnel stretches
     *                  added
     */
    @Inject(method = "trySnap", at = @At(value = "INVOKE",
            target = "Lnet/shiroha233/roadweaver/postprocess/RoadSnapService;tryTruncateSharedEndpoint(Lnet/shiroha233/roadweaver/core/model/RoadData;Lnet/shiroha233/roadweaver/core/model/RoadData;Ljava/util/List;Ljava/util/List;Ljava/util/List;[ILjava/util/List;Ljava/util/List;)Lnet/shiroha233/roadweaver/postprocess/RoadSnapService$TruncateResult;"))
    private static void rwstructuresawareness$shapeJunctions(CallbackInfoReturnable<RoadData> cir,
                                                             @Local(index = 2) RoadData secondary,
                                                             @Local(index = 3) List<RoadSegmentPlacement> own,
                                                             @Local(index = 4) List<RoadSegmentPlacement> other,
                                                             @Local(index = 6) int[] targets,
                                                             @Local(index = 8) List<int[]> runs,
                                                             @Local(index = 9) List<RoadSegmentPlacement> snapped,
                                                             @Local(index = 10) List<Integer> ownY,
                                                             @Local(index = 11) List<Integer> otherY,
                                                             @Local(index = 14) List<Integer> snappedY,
                                                             @Local(index = 17) LocalRef<List<RoadSpan>> spans) {
        List<RoadSpan> cleared = SnapJunctionShaper.shape(snapped, snappedY, runs, targets, own, ownY, other, otherY,
                TunnelCarver.getTunnelSegmentsByRoadData(secondary), secondary.width(), secondary.ownerA2dKey(),
                secondary.ownerB2dKey());
        if (cleared.isEmpty()) {
            return;
        }
        List<RoadSpan> all = spans.get() == null ? new ArrayList<>() : new ArrayList<>(spans.get());
        for (RoadSpan span : cleared) {
            if (!all.contains(span)) {
                all.add(span);
            }
        }
        spans.set(all);
    }

    /**
     * Joins a cut side road back onto the main road, then keeps only the tunnel and bridge stretches whose
     * segments are still part of the road.
     * <p>
     * trySnap calls filterSpansByRetainedSegments only after tryTruncateSharedEndpoint cut the side road, so this
     * runs only in that case. The repair (JunctionRepair.restoreJunction) puts back the side road's segments and
     * heights up to the main road. trySnap passes the side road's original stretches, which lack the tunnel
     * stretches shapeJunctions added, so the filter gets {@code withCleared} instead.
     *
     * @param spans       the side road's original tunnel and bridge stretches, used only when {@code withCleared}
     *                    is null
     * @param segments    the cut side road's segments, changed in place by the repair
     * @param original    the wrapped filterSpansByRetainedSegments call
     * @param other       the main road's segments (local 4)
     * @param targets     for each side-road segment, the nearest main-road segment index, or -1 if none is close
     *                    (local 6)
     * @param runs        the snapped stretches, {first, last} side-road segment index each (local 8)
     * @param snapped     the side road's segments after snapping, junction segments included (local 9)
     * @param otherY      the main road's heights (local 11)
     * @param snappedY    the heights of the snapped segments (local 14)
     * @param resultY     the cut side road's heights (local 16), changed in place by the repair
     * @param withCleared the side road's stretches with shapeJunctions' tunnel stretches added (local 17)
     * @return the tunnel and bridge stretches that still cover segments of the repaired road
     */
    @WrapOperation(method = "trySnap", at = @At(value = "INVOKE",
            target = "Lnet/shiroha233/roadweaver/postprocess/RoadSnapService;filterSpansByRetainedSegments(Ljava/util/List;Ljava/util/List;)Ljava/util/List;"))
    private static List<RoadSpan> rwstructuresawareness$repairCutJunction(List<RoadSpan> spans,
                                                                         List<RoadSegmentPlacement> segments,
                                                                         Operation<List<RoadSpan>> original,
                                                                         @Local(index = 4) List<RoadSegmentPlacement> other,
                                                                         @Local(index = 6) int[] targets,
                                                                         @Local(index = 8) List<int[]> runs,
                                                                         @Local(index = 9) List<RoadSegmentPlacement> snapped,
                                                                         @Local(index = 11) List<Integer> otherY,
                                                                         @Local(index = 14) List<Integer> snappedY,
                                                                         @Local(index = 16) List<Integer> resultY,
                                                                         @Local(index = 17) List<RoadSpan> withCleared) {
        JunctionRepair.restoreJunction(segments, resultY, runs, snapped, snappedY, other, otherY, targets);
        return original.call(withCleared != null ? withCleared : spans, segments);
    }

    /**
     * Stops a road from being snapped and cut a second time.
     * <p>
     * A side road that was already cut and joined onto this main road gets no snapped stretches against it
     * (JunctionRepair.alreadyJoined). Against any other road, snapped stretches that reach into an end of the
     * road that was already cut are dropped (JunctionRepair.runsOutsideCutEnds).
     *
     * @param targets  for each side-road segment, the nearest main-road segment index, or -1 if none is close
     * @param size     the side road's segment count
     * @param original the wrapped extractRuns call
     * @param road     the side road's segments (local 3)
     * @param other    the main road's segments (local 4)
     * @return an empty list when the side road already joins this main road, otherwise the snapped stretches,
     *         {first, last} segment index each, that stay clear of the side road's cut ends
     */
    @WrapOperation(method = "trySnap", at = @At(value = "INVOKE",
            target = "Lnet/shiroha233/roadweaver/postprocess/RoadSnapService;extractRuns([II)Ljava/util/List;"))
    private static List<int[]> rwstructuresawareness$skipAlreadyJoined(int[] targets, int size,
                                                                       Operation<List<int[]>> original,
                                                                       @Local(index = 3) List<RoadSegmentPlacement> road,
                                                                       @Local(index = 4) List<RoadSegmentPlacement> other) {
        if (JunctionRepair.alreadyJoined(road, other)) {
            return new ArrayList<>();
        }
        return JunctionRepair.runsOutsideCutEnds(original.call(targets, size), road);
    }
}
