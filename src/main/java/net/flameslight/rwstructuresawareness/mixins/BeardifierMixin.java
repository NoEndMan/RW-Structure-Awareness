package net.flameslight.rwstructuresawareness.mixins;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.flameslight.rwstructuresawareness.types.road.RoadLevel;
import net.flameslight.rwstructuresawareness.tunnel.TunnelBeardifierAccess;
import net.flameslight.rwstructuresawareness.tunnel.TunnelCarver;
import net.flameslight.rwstructuresawareness.utils.CoordinateKeys;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.DensityFunction;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The Beardifier built for a chunk carries that chunk's tunnel road columns, and on each one,
 * the density is forced far below solid from the road's first air block up to RoadWeaver's clear height.
 * That is the passage RoadWeaver's own tunnels have (its AboveColumnClearer clears exactly those blocks above
 * a paved road): road-wide, natural terrain beside and above it. TunnelCarver clears anything left at the
 * decoration stage.
 */
@Mixin(Beardifier.class)
public abstract class BeardifierMixin implements TunnelBeardifierAccess {
    @Unique
    @Nullable
    private Long2IntOpenHashMap rwstructuresawareness$tunnelColumns;
    @Unique
    private int rwstructuresawareness$clearHeight;

    /**
     * Gives this Beardifier its chunk's tunnel road columns.
     *
     * @param floors      CoordinateKeys.pack(x, z) to the road's first air Y, kept, not copied
     * @param clearHeight blocks dug above the floor
     */
    @Override
    public void rwstructuresawareness$setTunnelColumns(Long2IntOpenHashMap floors, int clearHeight) {
        this.rwstructuresawareness$tunnelColumns = floors;
        this.rwstructuresawareness$clearHeight = clearHeight;
    }

    @Override
    @Nullable
    public Long2IntOpenHashMap rwstructuresawareness$getTunnelColumns() {
        return this.rwstructuresawareness$tunnelColumns;
    }

    /**
     * At Beardifier.forStructuresInChunk RETURN: attaches the chunk's tunnel road columns to the new
     * Beardifier, on the road level only.
     *
     * @param structureManager the chunk's structure manager, tells the level
     * @param chunkPos         the chunk
     * @param cir              forStructuresInChunk's return, the Beardifier built
     */
    @Inject(method = "forStructuresInChunk", at = @At("RETURN"))
    private static void rwstructuresawareness$attachTunnels(StructureManager structureManager, ChunkPos chunkPos,
                                                            CallbackInfoReturnable<Beardifier> cir) {
        LevelAccessor accessor = ((StructureManagerAccessor) structureManager).rwstructuresawareness$getLevel();
        ServerLevel level = accessor instanceof ServerLevel serverLevel ? serverLevel
                : accessor instanceof WorldGenRegion region ? region.getLevel() : null;
        if (!RoadLevel.isRoadLevel(level) || TunnelCarver.boxesForChunk(chunkPos.x, chunkPos.z).length == 0) {
            return;
        }
        Long2IntOpenHashMap floors = TunnelCarver.tunnelColumns(level, chunkPos);
        if (!floors.isEmpty()) {
            ((TunnelBeardifierAccess) cir.getReturnValue()).rwstructuresawareness$setTunnelColumns(floors,
                    TunnelCarver.clearHeight());
        }
    }

    /**
     * At Beardifier.compute RETURN: forces the density to TUNNEL_DENSITY (air) inside a tunnel's passage.
     *
     * @param context the sampled position
     * @param cir     compute's return, lowered inside a tunnel
     */
    @Inject(method = "compute", at = @At("RETURN"), cancellable = true)
    private void rwstructuresawareness$digTunnels(DensityFunction.FunctionContext context,
                                                  CallbackInfoReturnable<Double> cir) {
        Long2IntOpenHashMap floors = this.rwstructuresawareness$tunnelColumns;
        if (floors == null) {
            return;
        }
        int floor = floors.get(CoordinateKeys.pack(context.blockX(), context.blockZ()));
        int y = context.blockY();
        if (floor != Integer.MAX_VALUE && y >= floor && y < floor + this.rwstructuresawareness$clearHeight) {
            cir.setReturnValue(Math.min(cir.getReturnValue(), TunnelCarver.TUNNEL_DENSITY));
        }
    }
}
