package net.flameslight.rwstructuresawareness.mixins;

import net.flameslight.rwstructuresawareness.logger.ModLogger;
import net.flameslight.rwstructuresawareness.StructureAvoidsRoadHandler;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.Predicate;

/**
 * A structure doesn't generate on a RoadWeaver road. Jigsaw pieces are trimmed during
 * assembly (JigsawPlacerMixin). Anything left on a road is rejected here at RETURN.
 *
 * Priority 900 (below the default 1000) so this RETURN runs before other mods' RETURN handlers, e.g.
 * Zones': a start rejected here returns immediately, so Zones never stores a spaceAround record for it.
 */
@Mixin(value = Structure.class, priority = 900)
public abstract class StructureMixin {
    /**
     * At Structure.generate HEAD: starts this worker thread's road check for the structure.
     */
    @Inject(method = "generate", at = @At("HEAD"))
    private void rwstructuresawareness$beginRoadCheck(RegistryAccess registryAccess, ChunkGenerator chunkGenerator,
                                                      BiomeSource biomeSource, RandomState randomState,
                                                      StructureTemplateManager structureTemplateManager, long seed,
                                                      ChunkPos chunkPos, int references,
                                                      LevelHeightAccessor heightAccessor,
                                                      Predicate<Holder<Biome>> validBiome,
                                                      CallbackInfoReturnable<StructureStart> cir) {
        ResourceLocation structureId = registryAccess.registryOrThrow(Registries.STRUCTURE).getKey((Structure) (Object) this);
        if (structureId == null) {
            StructureAvoidsRoadHandler.end();
            return;
        }
        StructureAvoidsRoadHandler.begin(chunkGenerator, structureId, chunkPos);
    }

    /**
     * At Structure.generate RETURN: replaces a start that sits on a road with INVALID_START, then ends the
     * thread's road check.
     */
    @Inject(method = "generate", at = @At("RETURN"), cancellable = true)
    private void rwstructuresawareness$rejectOnRoad(RegistryAccess registryAccess, ChunkGenerator chunkGenerator,
                                                    BiomeSource biomeSource, RandomState randomState,
                                                    StructureTemplateManager structureTemplateManager, long seed,
                                                    ChunkPos chunkPos, int references,
                                                    LevelHeightAccessor heightAccessor,
                                                    Predicate<Holder<Biome>> validBiome,
                                                    CallbackInfoReturnable<StructureStart> cir) {
        try {
            StructureStart start = cir.getReturnValue();
            if (start != null && start != StructureStart.INVALID_START && start.isValid()
                    && StructureAvoidsRoadHandler.violates(start)) {
                ModLogger.debug("'{}' at chunk [{}, {}] not placed: it would sit on a RoadWeaver road.",
                        registryAccess.registryOrThrow(Registries.STRUCTURE).getKey((Structure) (Object) this),
                        chunkPos.x, chunkPos.z);
                cir.setReturnValue(StructureStart.INVALID_START);
            }
        } finally {
            StructureAvoidsRoadHandler.end();
        }
    }
}
