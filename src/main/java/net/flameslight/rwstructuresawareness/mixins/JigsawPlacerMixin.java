package net.flameslight.rwstructuresawareness.mixins;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.flameslight.rwstructuresawareness.StructureAvoidsRoadHandler;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * tryPlacingChildren asks joinIsNotEmpty(freeSpace, candidate, ONLY_SECOND) whether a candidate jigsaw piece collides.
 * If a piece will answer "collides" to a road, this code will make vanilla to skip it and not placing it.
 */
@Mixin(targets = "net.minecraft.world.level.levelgen.structure.pools.JigsawPlacement$Placer")
public abstract class JigsawPlacerMixin {
    /**
     * Wraps tryPlacingChildren's Shapes.joinIsNotEmpty: a candidate piece on a road counts as colliding.
     *
     */
    @WrapOperation(method = "tryPlacingChildren", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/phys/shapes/Shapes;joinIsNotEmpty(Lnet/minecraft/world/phys/shapes/VoxelShape;Lnet/minecraft/world/phys/shapes/VoxelShape;Lnet/minecraft/world/phys/shapes/BooleanOp;)Z"))
    private boolean rwstructuresawareness$avoidRoads(VoxelShape freeSpace, VoxelShape candidate, BooleanOp op,
                                                     Operation<Boolean> original) {
        return original.call(freeSpace, candidate, op) || StructureAvoidsRoadHandler.blocksCandidate(candidate);
    }
}
