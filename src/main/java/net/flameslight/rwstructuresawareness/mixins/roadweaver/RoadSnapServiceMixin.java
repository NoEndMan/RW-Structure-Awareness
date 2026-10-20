package net.flameslight.rwstructuresawareness.mixins.roadweaver;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.flameslight.rwstructuresawareness.road.JunctionRepair;
import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;
import net.shiroha233.roadweaver.core.model.RoadSpan;
import net.shiroha233.roadweaver.postprocess.RoadSnapService;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.ArrayList;
import java.util.List;

/**
 * Hooks RoadWeaver's road snapping so a side road cut short at a main road is paved right into it, and stays so.
 * <p>
 * After roads are saved, RoadSnapService.trySnap runs on every pair of roads in the area. It pulls the second
 * (side) road onto the first (main) road wherever they run alongside for 3+ segments, and blends junction
 * segments at the edges. When both roads lead to the same destination, it then cuts the side road short
 * (tryTruncateSharedEndpoint), leaving a gap before the main road. JunctionRepair explains the cut and the repair.
 * <p>
 * Two hooks, both in trySnap:
 *  - filterSpansByRetainedSegments: RoadWeaver calls it only on a cut result, right before it builds the saved
 *    RoadData from the same (mutable) lists. So it runs exactly when a cut happened, while the lists can still
 *    change. JunctionRepair repairs the cut there.
 *  - extractRuns: after a repair, the side road's junction and lead segments lie along the main road. Every
 *    later snap of the area would find them alongside again and cut the road a little further each time. A
 *    road that already ends on the main road gets no runs, so trySnap leaves it alone.
 */
@Mixin(value = RoadSnapService.class, remap = false)
public abstract class RoadSnapServiceMixin {
    /**
     * Wraps trySnap's filterSpansByRetainedSegments, called only when the side road was cut: repairs the cut
     * (JunctionRepair.restoreJunction), then filters the spans against the repaired segments.
     *
     * @param spans    the spans to filter
     * @param segments the truncated result's segments, mutated by the repair
     * @param original filterSpansByRetainedSegments
     * @param other    local 4, the other road's segments
     * @param targets  local 6, the snap targets
     * @param runs     local 8, the runs along the other road
     * @param snapped  local 9, the snapped segments with the junction segments
     * @param otherY   local 11, the other road's targetY
     * @param snappedY local 14, the snapped segments' targetY
     * @param resultY  local 16, the truncated result's targetY, mutated by the repair
     * @return the spans kept for the repaired segments
     */
    @WrapOperation(method = "trySnap", at = @At(value = "INVOKE",
            target = "Lnet/shiroha233/roadweaver/postprocess/RoadSnapService;filterSpansByRetainedSegments(Ljava/util/List;Ljava/util/List;)Ljava/util/List;"))
    private static List<RoadSpan> rwstructuresawareness$keepJunctionSegments(List<RoadSpan> spans,
                                                                            List<RoadSegmentPlacement> segments,
                                                                            Operation<List<RoadSpan>> original,
                                                                            @Local(index = 4) List<RoadSegmentPlacement> other,
                                                                            @Local(index = 6) int[] targets,
                                                                            @Local(index = 8) List<int[]> runs,
                                                                            @Local(index = 9) List<RoadSegmentPlacement> snapped,
                                                                            @Local(index = 11) List<Integer> otherY,
                                                                            @Local(index = 14) List<Integer> snappedY,
                                                                            @Local(index = 16) List<Integer> resultY) {
        JunctionRepair.restoreJunction(segments, resultY, runs, snapped, snappedY, other, otherY, targets);
        return original.call(spans, segments);
    }

    /**
     * Wraps trySnap's extractRuns: a side road already cut and repaired onto this main road gets no runs, so
     * later snaps don't cut it again.
     *
     * @param targets  the snap targets
     * @param size     the road's segment count
     * @param original extractRuns
     * @param road     local 3, the road being snapped
     * @param other    local 4, the other road's segments
     * @return no runs when the road already joins the other one, otherwise extractRuns' runs
     */
    @WrapOperation(method = "trySnap", at = @At(value = "INVOKE",
            target = "Lnet/shiroha233/roadweaver/postprocess/RoadSnapService;extractRuns([II)Ljava/util/List;"))
    private static List<int[]> rwstructuresawareness$keepRepairedJunction(int[] targets, int size,
                                                                          Operation<List<int[]>> original,
                                                                          @Local(index = 3) List<RoadSegmentPlacement> road,
                                                                          @Local(index = 4) List<RoadSegmentPlacement> other) {
        if (JunctionRepair.alreadyJoined(road, other)) {
            return new ArrayList<>();
        }
        return original.call(targets, size);
    }
}
