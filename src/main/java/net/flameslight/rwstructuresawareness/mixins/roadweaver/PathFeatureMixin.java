package net.flameslight.rwstructuresawareness.mixins.roadweaver;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.flameslight.rwstructuresawareness.types.road.RoadLevel;
import net.flameslight.rwstructuresawareness.logger.RoadDebug;
import net.flameslight.rwstructuresawareness.road.JunctionRepair;
import net.flameslight.rwstructuresawareness.tunnel.TunnelCarver;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.shiroha233.roadweaver.config.ModConfig;
import net.shiroha233.roadweaver.core.model.RoadData;
import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;
import net.shiroha233.roadweaver.features.path.PathFeature;
import net.shiroha233.roadweaver.features.path.config.PathFeatureConfig;
import net.shiroha233.roadweaver.features.path.decoration.base.Decoration;
import net.shiroha233.roadweaver.features.path.decoration.system.DecorationPlanner;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.Set;

/**
 * RoadWeaver's road feature (runs last in a chunk's decoration, top_layer_modification):
 *  - every segment it paves is noted, and after it paved the chunk, the TUNNEL segments it paved are cleared and
 *    then, the tunnels there (and in already-decorated neighbours) are cleared again where the road is paved.
 *  - lamps, posts and signs are not planned on tunnel stretches (they'd stand on the ground above), on
 *    positionless segments, nor as the end sign of a road end RoadWeaver's snap truncated.
 *
 * `place` overrides vanilla Feature.place, so in the released RoadWeaver jar it carries the SRG name
 * m_142674_. Both names are listed (remap = false can't translate a name in a non-vanilla class).
 */
@Mixin(value = PathFeature.class, remap = false)
public abstract class PathFeatureMixin {
    /**
     * At PathFeature.place HEAD: counts the paving for the debug log, and forgets the paved segments noted for the
     * previous chunk.
     *
     * @param context the feature context
     * @param cir     place's return
     */
    @Inject(method = {"place", "m_142674_"}, at = @At("HEAD"))
    private void rwstructuresawareness$countPaving(FeaturePlaceContext<PathFeatureConfig> context,
                                                   CallbackInfoReturnable<Boolean> cir) {
        RoadDebug.pavingStarted();
        TunnelCarver.pavingStarted();
    }

    /**
     * Wraps processRoadDataInChunk's SegmentPaver.paveSegment: after RoadWeaver paved a segment, notes it, so a
     * TUNNEL segment is cleared at the end of the chunk (TunnelCarver.carveAround) exactly when RoadWeaver paved it.
     *
     * @param level         the WorldGenRegion
     * @param segment       the segment being paved
     * @param index         its index in the road
     * @param middles       the road's segment middles
     * @param ys            the heights RoadWeaver paves with
     * @param width         the road width
     * @param materials     the road materials
     * @param slabMaterials the slab materials
     * @param random        the random source
     * @param cfg           RoadWeaver's config
     * @param original      paveSegment
     * @param road          the road being processed
     */
    @WrapOperation(method = "processRoadDataInChunk", at = @At(value = "INVOKE",
            target = "Lnet/shiroha233/roadweaver/features/path/pathlogic/core/SegmentPaver;paveSegment(Lnet/minecraft/world/level/WorldGenLevel;Lnet/shiroha233/roadweaver/core/model/RoadSegmentPlacement;ILjava/util/List;[IILjava/util/List;Ljava/util/List;Lnet/minecraft/util/RandomSource;Lnet/shiroha233/roadweaver/config/ModConfig;)V"))
    private static void rwstructuresawareness$notePaved(WorldGenLevel level, RoadSegmentPlacement segment, int index,
                                                        List<BlockPos> middles, int[] ys, int width,
                                                        List<BlockState> materials, List<BlockState> slabMaterials,
                                                        RandomSource random, ModConfig cfg, Operation<Void> original,
                                                        @Local(argsOnly = true) RoadData road) {
        original.call(level, segment, index, middles, ys, width, materials, slabMaterials, random, cfg);
        TunnelCarver.notePaved(road, index, middles, ys);
    }

    /**
     * At PathFeature.place RETURN: carves the tunnels of the chunk and its decorated neighbours.
     *
     * @param context the feature context, its level's blocks are changed
     * @param cir     place's return
     */
    @Inject(method = {"place", "m_142674_"}, at = @At("RETURN"))
    private void rwstructuresawareness$digTunnels(FeaturePlaceContext<PathFeatureConfig> context,
                                                  CallbackInfoReturnable<Boolean> cir) {
        ChunkPos chunk = new ChunkPos(context.origin());
        RoadDebug.pavingFinished(chunk);
        ServerLevel server = context.level().getLevel();
        if (RoadLevel.isRoadLevel(server)) {
            TunnelCarver.carveAround(context.level(), server, chunk);
        }
    }

    /**
     * Wraps processRoadDataInChunk's DecorationPlanner.addDecoration: skipped on tunnel stretches, on
     * positionless segments, and for the end sign of a truncated road end.
     *
     * @param level           the WorldGenRegion
     * @param decorations     the planned decorations, mutated by addDecoration
     * @param anchor          the decoration's anchor (its segment's middle X/Z at the surface Y)
     * @param index           the segment counter
     * @param next            the next middle position
     * @param prev            the previous middle position
     * @param middlePositions the road's segment middles
     * @param width           the road width
     * @param random          the random source
     * @param cfg             RoadWeaver's config
     * @param mode            the planner mode
     * @param original        addDecoration
     * @param road            the road being processed
     */
    @WrapOperation(method = "processRoadDataInChunk", at = @At(value = "INVOKE",
            target = "Lnet/shiroha233/roadweaver/features/path/decoration/system/DecorationPlanner;addDecoration(Lnet/minecraft/world/level/WorldGenLevel;Ljava/util/Set;Lnet/minecraft/core/BlockPos;ILnet/minecraft/core/BlockPos;Lnet/minecraft/core/BlockPos;Ljava/util/List;ILnet/minecraft/util/RandomSource;Lnet/shiroha233/roadweaver/config/ModConfig;Lnet/shiroha233/roadweaver/features/path/decoration/system/DecorationPlanner$Mode;)V"))
    private static void rwstructuresawareness$noDecorationsInTunnels(WorldGenLevel level, Set<Decoration> decorations,
                                                                     BlockPos anchor, int index, BlockPos next,
                                                                     BlockPos prev, List<BlockPos> middlePositions,
                                                                     int width, RandomSource random, ModConfig cfg,
                                                                     DecorationPlanner.Mode mode, Operation<Void> original,
                                                                     @Local(argsOnly = true) RoadData road) {
        if (!inTunnel(road, middlePositions, anchor) && !onPositionlessSegment(road, middlePositions, anchor)
                && !endSignAtJunction(road, middlePositions, index)) {
            original.call(level, decorations, anchor, index, next, prev, middlePositions, width, random, cfg, mode);
        }
    }

    /**
     * Tells whether a decoration is the end sign of a road end RoadWeaver's snap truncated.
     * <p>
     * addDecoration places only the road's start / end distance sign ("Welcome") at counter 8 / size - 10,
     * taking the road's stored ends for its destinations. An end RoadWeaver's snap truncated (it ends in
     * positionless segments on the road it joins, see JunctionRepair) isn't one: no sign there.
     *
     * @param road            the road, may be null
     * @param middlePositions the road's segment middles, may be null
     * @param index           the segment counter
     * @return true if this is the start or end sign at a truncated end
     */
    private static boolean endSignAtJunction(RoadData road, List<BlockPos> middlePositions, int index) {
        List<RoadSegmentPlacement> segments = road == null ? null : road.roadSegmentList();
        if (segments == null || segments.isEmpty() || middlePositions == null) {
            return false;
        }
        return (index == 8 && JunctionRepair.isAnchor(segments.get(0)))
                || (index == middlePositions.size() - 10 && JunctionRepair.isAnchor(segments.get(segments.size() - 1)));
    }

    /**
     * Tells whether a decoration sits on a segment without positions (a truncation anchor or a lead segment on
     * another road), which is never decorated.
     *
     * @param road            the road, may be null
     * @param middlePositions the road's segment middles, may be null
     * @param anchor          the decoration's anchor, may be null
     * @return true if the anchor's segment has no positions
     */
    private static boolean onPositionlessSegment(RoadData road, List<BlockPos> middlePositions, BlockPos anchor) {
        List<RoadSegmentPlacement> segments = road == null ? null : road.roadSegmentList();
        if (segments == null || middlePositions == null || anchor == null) {
            return false;
        }
        for (int i = 0; i < middlePositions.size() && i < segments.size(); i++) {
            BlockPos m = middlePositions.get(i);
            if (m != null && m.getX() == anchor.getX() && m.getZ() == anchor.getZ()) {
                return JunctionRepair.isAnchor(segments.get(i));
            }
        }
        return false;
    }

    /**
     * Tells whether a decoration sits on a tunnel stretch. The anchor is its segment's middle (X/Z) at the
     * decoration's surface Y.
     *
     * @param road            the road, may be null
     * @param middlePositions the road's segment middles, may be null
     * @param anchor          the decoration's anchor, may be null
     * @return true if the anchor's segment lies in a tunnel stretch
     */
    private static boolean inTunnel(RoadData road, List<BlockPos> middlePositions, BlockPos anchor) {
        boolean[] tunnel = TunnelCarver.getTunnelSegmentsByRoadData(road);
        if (tunnel == null || middlePositions == null || anchor == null) {
            return false;
        }
        for (int i = 0; i < middlePositions.size() && i < tunnel.length; i++) {
            BlockPos m = middlePositions.get(i);
            if (m != null && m.getX() == anchor.getX() && m.getZ() == anchor.getZ()) {
                return tunnel[i];
            }
        }
        return false;
    }
}
