package net.flameslight.rwstructuresawareness.mixins.roadweaver;

import net.flameslight.rwstructuresawareness.compat.zones.ZonesCompat;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.shiroha233.roadweaver.pathfinding.cache.opencl.DensityGraphCompiler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * GPU hybrid (with Zones): RoadWeaver's GPU compiler rejects Zones' flattening wrapper, falling back
 * to the CPU for the whole overworld. Compile the function it wraps instead. GpuFlattenCorrection
 * then fixes the columns Zones flattens. Without Zones, nothing is ever unwrapped.
 */
@Mixin(value = DensityGraphCompiler.class, remap = false)
public abstract class DensityGraphCompilerMixin {
    /**
     * RoadWeaver's DensityGraphCompiler.compileNode.
     *
     * @param function the function to compile
     * @return the compiled node's index
     */
    @Shadow
    private int compileNode(DensityFunction function) {
        throw new AssertionError();
    }

    /**
     * At DensityGraphCompiler.compileNode HEAD: compiles the function Zones' flattening wrapper wraps instead
     * of the wrapper.
     *
     * @param function the function to compile
     * @param cir      compileNode's return, set to the unwrapped function's node
     */
    @Inject(method = "compileNode", at = @At("HEAD"), cancellable = true)
    private void rwstructuresawareness$unwrapZonesFlattening(DensityFunction function, CallbackInfoReturnable<Integer> cir) {
        DensityFunction unwrapped = ZonesCompat.unwrapPreliminarySurface(function);
        if (unwrapped != function) {
            cir.setReturnValue(this.compileNode(unwrapped));
        }
    }
}
