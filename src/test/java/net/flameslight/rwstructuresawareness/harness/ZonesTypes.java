package net.flameslight.rwstructuresawareness.harness;

import net.flameslight.rwstructuresawareness.types.zones.ZonePerimeter;
import net.flameslight.zones.compat.RWSARouters;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.RandomState;
import org.jetbrains.annotations.Nullable;

/**
 * The only harness class referencing Zones, and only through RWSARouters, its stable API. Reached only once
 * Zones is known to be loaded.
 */
final class ZonesTypes {
    /**
     * Returns the installed Zones' RWSA API version.
     *
     * @return RWSARouters.apiVersion()
     */
    static int apiVersion() {
        return RWSARouters.apiVersion();
    }

    /**
     * Wraps a function in Zones' preliminary-surface flattening wrapper.
     *
     * @param original the function to wrap, not changed
     * @param owner    the RandomState the wrapper belongs to
     * @return a new wrapper around {@code original}
     */
    static DensityFunction flatteningWrapper(DensityFunction original, RandomState owner) {
        return RWSARouters.wrapForFlattening(original, owner);
    }

    /**
     * Tells whether a function is Zones' flattening wrapper.
     *
     * @param function any density function
     * @return true if it is the wrapper
     */
    static boolean isFlatteningWrapper(DensityFunction function) {
        return RWSARouters.isFlatteningWrapper(function);
    }

    /**
     * Tells whether a column lies inside any zone Zones placed.
     *
     * @param dimension the dimension id
     * @param blockX    the column's X (block)
     * @param blockZ    the column's Z (block)
     * @return true if a zone contains the column
     */
    static boolean insideAnyZone(ResourceLocation dimension, int blockX, int blockZ) {
        return RWSARouters.insideAnyZone(dimension, blockX, blockZ);
    }

    /**
     * Tells whether Zones placed any flattening zone.
     *
     * @return Zones' own answer
     */
    static boolean hasAnyFlattenZone() {
        return RWSARouters.hasAnyFlattenZone();
    }

    /**
     * Returns the first zone Zones placed in a dimension.
     *
     * @param dimension the dimension id
     * @return the zone as a circle (definition id, ancestors, center, radius), null if none
     */
    @Nullable
    static ZonePerimeter firstZone(ResourceLocation dimension) {
        for (RWSARouters.ZoneArea zone : RWSARouters.allZones(dimension)) {
            return toCircle(zone);
        }
        return null;
    }

    /**
     * Returns the first nested zone (one with a parentZone) Zones placed in a dimension.
     *
     * @param dimension the dimension id
     * @return the zone as a circle, null if the world has no nested zone
     */
    @Nullable
    static ZonePerimeter firstNestedZone(ResourceLocation dimension) {
        for (RWSARouters.ZoneArea zone : RWSARouters.allZones(dimension)) {
            if (!zone.ancestorIds().isEmpty()) {
                return toCircle(zone);
            }
        }
        return null;
    }

    /**
     * Converts a Zones zone to RWSA's circle.
     *
     * @param zone the zone
     * @return the same zone as a ZoneCircle
     */
    private static ZonePerimeter toCircle(RWSARouters.ZoneArea zone) {
        return new ZonePerimeter(zone.zoneId(), zone.ancestorIds(), zone.centerX(), zone.centerZ(), zone.radius());
    }
}
