package net.flameslight.rwstructuresawareness.mixins.roadweaver;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.flameslight.rwstructuresawareness.compat.zones.GpuFlattenCorrection;
import net.minecraft.server.level.ServerLevel;
import net.shiroha233.roadweaver.pathfinding.cache.CoarseHeightBatchRequest;
import net.shiroha233.roadweaver.pathfinding.cache.opencl.OpenCLCoarseHeightBatchSampler;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * GPU hybrid (with Zones): corrects the GPU heights of columns Zones flattens right after the GPU
 * run, before RoadWeaver compares them with its CPU result. Without Zones nothing changes.
 */
@Mixin(value = OpenCLCoarseHeightBatchSampler.class, remap = false)
public abstract class OpenCLCoarseHeightBatchSamplerMixin {
    @Shadow
    @Final
    private ServerLevel level;

    /**
     * Wraps sampleHeights' sampleHeightsOpenCL: the GPU heights of flattened columns are corrected.
     *
     * @param instance the sampler
     * @param request  the batch request
     * @param original sampleHeightsOpenCL
     * @return the GPU heights, corrected in place
     */
    @WrapOperation(method = "sampleHeights", at = @At(value = "INVOKE",
            target = "Lnet/shiroha233/roadweaver/pathfinding/cache/opencl/OpenCLCoarseHeightBatchSampler;sampleHeightsOpenCL(Lnet/shiroha233/roadweaver/pathfinding/cache/CoarseHeightBatchRequest;)[I"))
    private int[] rwstructuresawareness$correctFlattenedColumns(OpenCLCoarseHeightBatchSampler instance,
                                                                CoarseHeightBatchRequest request,
                                                                Operation<int[]> original) {
        int[] heights = original.call(instance, request);
        GpuFlattenCorrection.correct(this.level, request, heights);
        return heights;
    }
}
