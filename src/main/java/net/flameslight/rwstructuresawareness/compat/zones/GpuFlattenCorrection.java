package net.flameslight.rwstructuresawareness.compat.zones;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.shiroha233.roadweaver.pathfinding.cache.CoarseHeightBatchRequest;
import net.shiroha233.roadweaver.pathfinding.cache.FastHeightSampler;

/**
 * GPU hybrid (with Zones only). RoadWeaver's GPU program is compiled from the router function
 * Zones wraps (DensityGraphCompilerMixin unwraps it), so the GPU returns unflattened heights. This
 * replaces every column Zones flattens with RoadWeaver's own CPU result (FastHeightSampler, which
 * evaluates the wrapped function), before RoadWeaver compares GPU and CPU results. Correcting a
 * column that didn't need it is harmless: the CPU result is exactly what the comparison expects.
 */
public final class GpuFlattenCorrection {
    /**
     * Replaces the GPU heights of every column Zones flattens with RoadWeaver's CPU result.
     *
     * @param level   the level sampled, may be null (then nothing happens)
     * @param request the batch request, its layout is index = row * sampleWidth + column
     * @param heights the GPU heights, mutated in place
     */
    public static void correct(ServerLevel level, CoarseHeightBatchRequest request, int[] heights) {
        if (level == null || request == null || heights == null || !ZonesCompat.hasAnyFlattenZone()) {
            return;
        }
        ResourceLocation dimension = level.dimension().location();
        FastHeightSampler cpu = null;
        int width = request.sampleWidth();
        // Same layout as RoadWeaver's CPU sampler: index = row * sampleWidth + column.
        for (int row = 0; row < request.sampleHeight(); row++) {
            int z = request.blockZAt(row);
            for (int col = 0; col < width; col++) {
                int i = row * width + col;
                if (i >= heights.length) {
                    return;
                }
                int x = request.blockXAt(col);
                // FastHeightSampler samples the 4-block-aligned column, so check that one as well.
                if (!ZonesCompat.flattensColumn(dimension, (x >> 2) << 2, (z >> 2) << 2)
                        && !ZonesCompat.flattensColumn(dimension, x, z)) {
                    continue;
                }
                if (cpu == null) {
                    cpu = FastHeightSampler.create(level);
                }
                heights[i] = cpu.sampleHeight(x, z);
            }
        }
    }
}
