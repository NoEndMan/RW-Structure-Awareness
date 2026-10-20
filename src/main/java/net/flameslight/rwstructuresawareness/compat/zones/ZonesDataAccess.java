package net.flameslight.rwstructuresawareness.compat.zones;

import net.flameslight.rwstructuresawareness.types.zones.ZonePerimeter;
import net.flameslight.zones.compat.RWSARouters;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.DensityFunction;

import java.util.ArrayList;
import java.util.List;

/**
 * The only main-code class that references Zones, and only through RWSARouters, its stable API. Reached only
 * through ZonesCompat once Zones is loaded and its API version checked.
 * <p>
 * The class stays package-private on purpose: only ZonesCompat may call it, after its "Zones loaded" check.
 * Called without Zones installed, any method here fails with NoClassDefFoundError.
 */
final class ZonesDataAccess {
    /**
     * Returns the installed Zones' RWSA API version.
     *
     * @return RWSARouters.apiVersion()
     * @throws LinkageError when the installed Zones has no RWSARouters, or an RWSARouters without apiVersion()
     */
    public static int apiVersion() {
        return RWSARouters.apiVersion();
    }

    /**
     * Returns the function Zones' preliminary-surface flattening wraps.
     *
     * @param function any density function, not changed
     * @return the wrapped original if {@code function} is Zones' wrapper, otherwise {@code function} itself
     */
    public static DensityFunction unwrapPreliminarySurface(DensityFunction function) {
        return RWSARouters.unwrapPreliminarySurface(function);
    }

    /**
     * Tells whether any placed zone flattens terrain.
     *
     * @return true if at least one placed zone flattens terrain
     */
    public static boolean hasAnyFlattenZone() {
        return RWSARouters.hasAnyFlattenZone();
    }

    /**
     * Tells whether Zones flattens this column's preliminary surface.
     *
     * @param dimension the dimension id
     * @param blockX    the column's X (block)
     * @param blockZ    the column's Z (block)
     * @return true if a flattening zone sets this column's preliminary surface height
     */
    public static boolean flattensColumn(ResourceLocation dimension, int blockX, int blockZ) {
        return RWSARouters.flattensColumn(dimension, blockX, blockZ);
    }

    /**
     * Lists every zone placed in a dimension.
     *
     * @param dimension the dimension id
     * @return one circle per placed zone, with its definition id and the ids it is nested in
     */
    public static List<ZonePerimeter> allZones(ResourceLocation dimension) {
        List<RWSARouters.ZoneArea> zones = RWSARouters.allZones(dimension);
        List<ZonePerimeter> out = new ArrayList<>(zones.size());
        for (RWSARouters.ZoneArea zone : zones) {
            out.add(new ZonePerimeter(zone.zoneId(), List.copyOf(zone.ancestorIds()), zone.centerX(), zone.centerZ(),
                    zone.radius()));
        }
        return out;
    }
}
