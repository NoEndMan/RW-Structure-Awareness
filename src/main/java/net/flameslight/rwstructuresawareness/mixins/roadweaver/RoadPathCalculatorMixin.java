package net.flameslight.rwstructuresawareness.mixins.roadweaver;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.flameslight.rwstructuresawareness.road.RoadRerouter;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.shiroha233.roadweaver.config.sub.PathfindingCostConfig;
import net.shiroha233.roadweaver.features.path.pathlogic.pathfinding.RoadPathCalculator;
import net.shiroha233.roadweaver.pathfinding.PathResult;
import net.shiroha233.roadweaver.pathfinding.Pathfinder;
import net.shiroha233.roadweaver.pathfinding.cache.TerrainSamplingCache;
import net.shiroha233.roadweaver.pathfinding.terrain.PathTerrainField;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** The fine road search (coarse pass of an uncached road, and the quantized pass of every road). */
@Mixin(value = RoadPathCalculator.class, remap = false)
public abstract class RoadPathCalculatorMixin {
    /**
     * Wraps calculateRawPath's Pathfinder.findRawPath: the search routes around recorded structures
     * (RoadRerouter).
     *
     * @param pathfinder the pathfinder
     * @param start      the road's start
     * @param end        the road's end
     * @param level      the level
     * @param maxSteps   the search's step budget
     * @param cache      RoadWeaver's terrain cache
     * @param terrain    the search terrain
     * @param config     RoadWeaver's pathfinding config
     * @param original   Pathfinder.findRawPath
     * @return the search result, rerouted when a route around the structures exists
     */
    @WrapOperation(method = "calculateRawPath", at = @At(value = "INVOKE",
            target = "Lnet/shiroha233/roadweaver/pathfinding/Pathfinder;findRawPath(Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/BlockPos;Lnet/minecraft/server/level/ServerLevel;ILnet/shiroha233/roadweaver/pathfinding/cache/TerrainSamplingCache;Lnet/shiroha233/roadweaver/pathfinding/terrain/PathTerrainField;Lnet/shiroha233/roadweaver/config/sub/PathfindingCostConfig;)Lnet/shiroha233/roadweaver/pathfinding/PathResult;"))
    private static PathResult rwstructuresawareness$routeAroundStructures(Pathfinder pathfinder, BlockPos start, BlockPos end,
                                                                          ServerLevel level, int maxSteps,
                                                                          TerrainSamplingCache cache, PathTerrainField terrain,
                                                                          PathfindingCostConfig config,
                                                                          Operation<PathResult> original) {
        return RoadRerouter.findRawPath(pathfinder, start, end, level, maxSteps, cache, terrain, config, original);
    }
}
