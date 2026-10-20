package net.flameslight.rwstructuresawareness.mixins.roadweaver;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.flameslight.rwstructuresawareness.logger.RoadDebug;
import net.flameslight.rwstructuresawareness.tunnel.TunnelCarver;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.WorldGenLevel;
import net.shiroha233.roadweaver.features.path.pathlogic.core.SegmentPaver;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * RoadWeaver doesn't pave (or clear above) road blocks inside a generated structure's bounds. A
 * tunnel deliberately runs below a structure, sometimes still inside its overall bounds. There the
 * tunnel floor is paved anyway. The dig volume always stays below the structure's own blocks.
 */
@Mixin(value = SegmentPaver.class, remap = false)
public abstract class SegmentPaverMixin {
    /**
     * Wraps paveSegment's StructureAvoidanceService.shouldAvoid: a tunnel floor inside a structure's bounds
     * is paved anyway.
     *
     * @param level    the WorldGenRegion
     * @param pos      the road's first air block, the road block goes one below it
     * @param original shouldAvoid
     * @return true only if RoadWeaver avoids the position and it isn't in a tunnel
     */
    @WrapOperation(method = "paveSegment", at = @At(value = "INVOKE",
            target = "Lnet/shiroha233/roadweaver/features/path/pathlogic/core/StructureAvoidanceService;shouldAvoid(Lnet/minecraft/world/level/WorldGenLevel;Lnet/minecraft/core/BlockPos;)Z"))
    private static boolean rwstructuresawareness$paveTunnelFloors(WorldGenLevel level, BlockPos pos,
                                                                  Operation<Boolean> original) {
        // pos is the road's first air block, the road block goes one below it.
        boolean avoid = original.call(level, pos);
        boolean tunnelFloor = avoid && TunnelCarver.insideAnyTunnel(pos.getX(), pos.getY(), pos.getZ());
        RoadDebug.pavingChecked(avoid && !tunnelFloor, tunnelFloor);
        return avoid && !tunnelFloor;
    }
}
