package net.flameslight.rwstructuresawareness.mixins.roadweaver;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.sugar.Local;
import net.flameslight.rwstructuresawareness.road.RoadDestinationsFilter;
import net.minecraft.server.level.ServerLevel;
import net.shiroha233.roadweaver.core.model.StructureInfo;
import net.shiroha233.roadweaver.search.StructureIndexService;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;

/** Passes RoadWeaver's destination candidates (predicted and verified structures) through RoadDestinationsFilter. */
@Mixin(value = StructureIndexService.class, remap = false)
public abstract class StructureIndexServiceMixin {
    /**
     * At predictAndVerifyInRect and predictAndVerifyAroundSpawn RETURN: filters the candidates.
     *
     * @param candidates the method's result
     * @param level      the level searched
     * @return the candidates RoadDestinationsFilter keeps
     */
    @ModifyReturnValue(method = {"predictAndVerifyInRect", "predictAndVerifyAroundSpawn"}, at = @At("RETURN"))
    private static List<StructureInfo> rwstructuresawareness$noUndergroundDestinations(List<StructureInfo> candidates,
                                                                                      @Local(argsOnly = true) ServerLevel level) {
        return RoadDestinationsFilter.filter(level, candidates);
    }
}
