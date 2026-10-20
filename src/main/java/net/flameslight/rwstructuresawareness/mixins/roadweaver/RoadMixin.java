package net.flameslight.rwstructuresawareness.mixins.roadweaver;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.flameslight.rwstructuresawareness.logger.ModLogger;
import net.flameslight.rwstructuresawareness.logger.RoadDebug;
import net.flameslight.rwstructuresawareness.road.RoadRerouter;
import net.flameslight.rwstructuresawareness.tunnel.RoadTunnelPlanner;
import net.flameslight.rwstructuresawareness.types.tunnel.TunnelPlan;
import net.flameslight.rwstructuresawareness.types.tunnel.TunnelStore;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.shiroha233.roadweaver.config.sub.RoadGenerationConfig;
import net.shiroha233.roadweaver.core.model.RoadData;
import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;
import net.shiroha233.roadweaver.core.model.RoadSpan;
import net.shiroha233.roadweaver.core.model.StructureConnection;
import net.shiroha233.roadweaver.features.path.pathlogic.core.Road;
import net.shiroha233.roadweaver.pathfinding.cache.TerrainSamplingCache;
import net.shiroha233.roadweaver.pathfinding.terrain.PathTerrainField;
import net.shiroha233.roadweaver.persistence.sharded.RoadShardStorage;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import java.util.ArrayList;
import java.util.List;

/**
 * Road.generateRoad, where RoadWeaver turns a connection into a saved road:
 *  - a cached coarse path crossing structures is dropped, so the road is searched again (rerouted).
 *  - computeTargetY's heights go through RoadTunnelPlanner, which tunnels under the structures the final
 *    path still crosses, and RoadTrimPlanner, which cuts the road where no tunnel can be dug.
 *  - the RoadData built and the roadside precomputers then get the planned segments and spans
 *    (the precomputers see tunnels as bridges, which they already skip).
 * This class only hooks into RoadWeaver. The planners decide what changes.
 */
@Mixin(value = Road.class, remap = false)
public abstract class RoadMixin {
    @Shadow
    @Final
    private ServerLevel level;

    @Shadow
    @Final
    private StructureConnection connection;

    /** The road built for a dropped plan on this thread, until its save is skipped. */
    @Unique
    private static final ThreadLocal<RoadData> DROPPED = new ThreadLocal<>();

    /**
     * Wraps generateRoad's CoarsePathCache.get: a cached coarse path crossing structures is dropped, so the
     * road is searched again.
     *
     * @param serverLevel the level
     * @param conn        the connection being built
     * @param original    CoarsePathCache.get
     * @return the cached path, or null when it crosses a recorded structure or no-road zone
     */
    @WrapOperation(method = "generateRoad", at = @At(value = "INVOKE",
            target = "Lnet/shiroha233/roadweaver/pathfinding/terrain/region/CoarsePathCache;get(Lnet/minecraft/server/level/ServerLevel;Lnet/shiroha233/roadweaver/core/model/StructureConnection;)Ljava/util/List;"))
    private List<BlockPos> rwstructuresawareness$dropBlockedCoarsePath(ServerLevel serverLevel, StructureConnection conn,
                                                                       Operation<List<BlockPos>> original) {
        return RoadRerouter.dropIfBlocked(serverLevel, conn, original.call(serverLevel, conn));
    }

    /**
     * Wraps generateRoad's computeTargetY: the heights go through RoadTunnelPlanner.
     *
     * @param serverLevel the level
     * @param segments    the road's segments
     * @param spans       the road's spans
     * @param cache       RoadWeaver's terrain cache
     * @param cfg         RoadWeaver's generation config
     * @param original    Road.computeTargetY
     * @return the planner's heights, or computeTargetY's own when the road crosses no structure
     */
    @WrapOperation(method = "generateRoad", at = @At(value = "INVOKE",
            target = "Lnet/shiroha233/roadweaver/features/path/pathlogic/core/Road;computeTargetY(Lnet/minecraft/server/level/ServerLevel;Ljava/util/List;Ljava/util/List;Lnet/shiroha233/roadweaver/pathfinding/cache/TerrainSamplingCache;Lnet/shiroha233/roadweaver/config/sub/RoadGenerationConfig;)Ljava/util/List;"))
    private List<Integer> rwstructuresawareness$planTunnels(ServerLevel serverLevel, List<RoadSegmentPlacement> segments,
                                                           List<RoadSpan> spans, TerrainSamplingCache cache,
                                                           RoadGenerationConfig cfg, Operation<List<Integer>> original) {
        List<Integer> targetY = original.call(serverLevel, segments, spans, cache, cfg);
        DROPPED.remove(); // a new road on this thread
        return RoadTunnelPlanner.plan(serverLevel, this.connection, segments, spans, targetY, cfg);
    }

    /**
     * Wraps generateRoad's new RoadData: the road gets the plan's kept segments, spans and heights, its
     * tunnel boxes are recorded, and every later piece is saved as a road of its own.
     *
     * @param width         the road width
     * @param roadType      RoadWeaver's road type
     * @param materials     the road materials
     * @param slabMaterials the slab materials
     * @param segments      the road's segments
     * @param spans         the road's spans
     * @param targetY       the road's heights
     * @param ownerA        one destination (2D key)
     * @param ownerB        the other destination (2D key)
     * @param original      the RoadData constructor
     * @return the first piece of the planned road, or the road unchanged without a plan
     */
    @WrapOperation(method = "generateRoad", at = @At(value = "NEW",
            target = "(IILjava/util/List;Ljava/util/List;Ljava/util/List;Ljava/util/List;Ljava/util/List;JJ)Lnet/shiroha233/roadweaver/core/model/RoadData;"))
    private RoadData rwstructuresawareness$applyTunnelPlan(int width, int roadType, List<BlockState> materials,
                                                           List<BlockState> slabMaterials,
                                                           List<RoadSegmentPlacement> segments, List<RoadSpan> spans,
                                                           List<Integer> targetY, long ownerA, long ownerB,
                                                           Operation<RoadData> original) {
        TunnelPlan plan = RoadTunnelPlanner.planFor(segments);
        if (plan == null) {
            return original.call(width, roadType, materials, slabMaterials, segments, spans, targetY, ownerA, ownerB);
        }
        if (plan.dropped) {
            // Built (generateRoad must return a road) but never saved: see skipDroppedRoad.
            RoadData road = original.call(width, roadType, materials, slabMaterials, segments, spans, targetY, ownerA, ownerB);
            DROPPED.set(road);
            return road;
        }
        for (int[] box : plan.tunnelBoxes) {
            TunnelStore.add(box);
        }
        if (RoadDebug.enabled()) {
            List<String> ranges = new ArrayList<>();
            for (int[] range : plan.parts) {
                ranges.add(plan.keptSegments.get(range[0]).middlePos().toShortString() + " .. "
                        + plan.keptSegments.get(range[1] - 1).middlePos().toShortString()
                        + " (" + (range[1] - range[0]) + " segments)");
            }
            ModLogger.debug("Road {} -> {}: {} of {} segments kept, {} part(s): {}, {} tunnel box(es), reaches end: {}.",
                    this.connection.from().toShortString(), this.connection.to().toShortString(),
                    plan.keptSegments.size(), segments.size(), plan.parts.size(), String.join(" | ", ranges),
                    plan.tunnelBoxes.size(), plan.reachesEnd);
        }
        /*
            Pieces after a trimmed gap become roads of their own, so RoadWeaver never paves or shapes
            terrain across the gap. Every piece keeps the connection's owner pair: RoadWeaver's
            LegacyRoadDataRepairService takes a road without one for legacy data and registers "unknown"
            structures at its ends, which its planner then connects with new roads planned long after the
            land there generated (never paved). With the pair it finds the existing connection instead,
            and its map draws every piece under that connection.
         */
        for (int p = 1; p < plan.parts.size(); p++) {
            int[] range = plan.parts.get(p);
            if (range[1] - range[0] < 2) {
                continue;
            }
            RoadData piece = new RoadData(width, roadType, materials, slabMaterials,
                    new ArrayList<>(plan.keptSegments.subList(range[0], range[1])), plan.spansOfPart(p),
                    new ArrayList<>(plan.keptTargetY.subList(range[0], range[1])), ownerA, ownerB);
            RoadShardStorage.addRoad(this.level, piece);
        }
        int[] first = plan.parts.get(0);
        return original.call(width, roadType, materials, slabMaterials,
                new ArrayList<>(plan.keptSegments.subList(first[0], first[1])), plan.spansOfPart(0),
                new ArrayList<>(plan.keptTargetY.subList(first[0], first[1])), ownerA, ownerB);
    }

    /**
     * Wraps generateRoad's RoadShardStorage.addRoad: a road RoadTrimPlanner dropped (it lies entirely on
     * its own destination or a no-road zone) isn't saved, so it is never paved. RoadWeaver still marks the connection done
     * (generateRoad returns normally).
     *
     * @param serverLevel the level
     * @param road        the road to save
     * @param original    RoadShardStorage.addRoad
     */
    @WrapOperation(method = "generateRoad", at = @At(value = "INVOKE",
            target = "Lnet/shiroha233/roadweaver/persistence/sharded/RoadShardStorage;addRoad(Lnet/minecraft/server/level/ServerLevel;Lnet/shiroha233/roadweaver/core/model/RoadData;)V"))
    private void rwstructuresawareness$skipDroppedRoad(ServerLevel serverLevel, RoadData road, Operation<Void> original) {
        if (road != null && road == DROPPED.get()) {
            DROPPED.remove();
            return;
        }
        original.call(serverLevel, road);
    }

    /**
     * Wraps generateRoad's RoadsideStructurePrecomputer.precomputeStructures: it sees the planned road, with
     * tunnels as bridges, which it skips.
     *
     * @param serverLevel the level
     * @param segments    the road's segments
     * @param spans       the road's spans
     * @param width       the road width
     * @param cache       RoadWeaver's terrain cache
     * @param random      the random source
     * @param targetY     the road's heights
     * @param original    precomputeStructures
     * @return precomputeStructures' count, 0 for a dropped road
     */
    @WrapOperation(method = "generateRoad", at = @At(value = "INVOKE",
            target = "Lnet/shiroha233/roadweaver/structures/precompute/RoadsideStructurePrecomputer;precomputeStructures(Lnet/minecraft/server/level/ServerLevel;Ljava/util/List;Ljava/util/List;ILnet/shiroha233/roadweaver/pathfinding/cache/TerrainSamplingCache;Lnet/minecraft/util/RandomSource;Ljava/util/List;)I"))
    private int rwstructuresawareness$roadsideStructuresOnPlannedRoad(ServerLevel serverLevel,
                                                                      List<RoadSegmentPlacement> segments,
                                                                      List<RoadSpan> spans, int width,
                                                                      TerrainSamplingCache cache, RandomSource random,
                                                                      List<Integer> targetY, Operation<Integer> original) {
        TunnelPlan plan = RoadTunnelPlanner.planFor(segments);
        if (plan != null && plan.dropped) {
            return 0;
        }
        return plan == null
                ? original.call(serverLevel, segments, spans, width, cache, random, targetY)
                : original.call(serverLevel, plan.keptSegments, RoadTunnelPlanner.tunnelsAsBridges(plan.keptSpans), width,
                cache, random, plan.keptTargetY);
    }

    /**
     * Wraps generateRoad's RoadsideVillagePrecomputer.precomputeVillages: it sees the planned road, with
     * tunnels as bridges. The last use of the road's plan, which is cleared after.
     *
     * @param serverLevel the level
     * @param segments    the road's segments
     * @param spans       the road's spans
     * @param width       the road width
     * @param cache       RoadWeaver's terrain cache
     * @param terrain     RoadWeaver's terrain field
     * @param random      the random source
     * @param targetY     the road's heights
     * @param original    precomputeVillages
     * @return precomputeVillages' count, 0 for a dropped road
     */
    @WrapOperation(method = "generateRoad", at = @At(value = "INVOKE",
            target = "Lnet/shiroha233/roadweaver/structures/precompute/RoadsideVillagePrecomputer;precomputeVillages(Lnet/minecraft/server/level/ServerLevel;Ljava/util/List;Ljava/util/List;ILnet/shiroha233/roadweaver/pathfinding/cache/TerrainSamplingCache;Lnet/shiroha233/roadweaver/pathfinding/terrain/PathTerrainField;Lnet/minecraft/util/RandomSource;Ljava/util/List;)I"))
    private int rwstructuresawareness$roadsideVillagesOnPlannedRoad(ServerLevel serverLevel,
                                                                    List<RoadSegmentPlacement> segments,
                                                                    List<RoadSpan> spans, int width,
                                                                    TerrainSamplingCache cache, PathTerrainField terrain,
                                                                    RandomSource random, List<Integer> targetY,
                                                                    Operation<Integer> original) {
        TunnelPlan plan = RoadTunnelPlanner.planFor(segments);
        try {
            if (plan != null && plan.dropped) {
                return 0;
            }
            return plan == null
                    ? original.call(serverLevel, segments, spans, width, cache, terrain, random, targetY)
                    : original.call(serverLevel, plan.keptSegments, RoadTunnelPlanner.tunnelsAsBridges(plan.keptSpans), width,
                    cache, terrain, random, plan.keptTargetY);
        } finally {
            RoadTunnelPlanner.clearPlan(); // last use of this road's plan
        }
    }
}
