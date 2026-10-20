package net.flameslight.rwstructuresawareness.mixins.roadweaver;

import net.shiroha233.roadweaver.config.structure.StructureSelectionConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Set;

/**
 * The structures selected one by one in RoadWeaver's structure selection (getEnabledStructures()
 * also returns the members of enabled tags, this is the explicit set only).
 */
@Mixin(value = StructureSelectionConfig.class, remap = false)
public interface StructureSelectionConfigAccessor {
    @Accessor("enabledStructures")
    Set<String> rwstructuresawareness$getExplicitStructures();
}
