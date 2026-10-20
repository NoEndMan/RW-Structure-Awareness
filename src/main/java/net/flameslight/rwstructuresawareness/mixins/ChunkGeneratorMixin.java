package net.flameslight.rwstructuresawareness.mixins;

import net.flameslight.rwstructuresawareness.types.road.RoadLevel;
import net.flameslight.rwstructuresawareness.StructureChunkRecorder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Records every structure that really generated (StructureChunkRecorder), so roads RoadWeaver plans later
 * avoid it. tryGenerateStructure returns true only once the start was committed to its chunk, after every
 * rejection (ours, Zones', any other mod's), so this records exactly what generated.
 */
@Mixin(ChunkGenerator.class)
public abstract class ChunkGeneratorMixin {
    /**
     * At ChunkGenerator.tryGenerateStructure RETURN: records a committed road-level structure's footprint.
     */
    @Inject(method = "tryGenerateStructure", at = @At("RETURN"))
    private void rwstructuresawareness$recordStructureChunks(StructureSet.StructureSelectionEntry entry,
                                                             StructureManager structureManager,
                                                             RegistryAccess registryAccess, RandomState randomState,
                                                             StructureTemplateManager templateManager, long seed,
                                                             ChunkAccess chunk, ChunkPos chunkPos, SectionPos sectionPos,
                                                             CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ() || !RoadLevel.isRoadGenerator((ChunkGenerator) (Object) this)) {
            return;
        }
        ResourceLocation structureId = entry.structure().unwrapKey().map(k -> k.location()).orElse(null);
        if (structureId != null) {
            StructureChunkRecorder.onStructureCommitted(chunk, chunkPos, entry.structure().value(), structureId,
                    (ChunkGenerator) (Object) this, randomState);
        }
    }
}
