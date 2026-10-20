package net.flameslight.rwstructuresawareness.compat.zones;

import net.flameslight.rwstructuresawareness.config.CommonConfig;
import net.flameslight.rwstructuresawareness.logger.ModLogger;
import net.flameslight.rwstructuresawareness.types.zones.ZonePerimeter;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraftforge.fml.ModList;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Zones is optional. This class references no Zones type, so it is always safe to load. Every call into
 * Zones goes through ZonesDataAccess, which is only reached once isZonesLoaded() is true.
 */
public final class ZonesCompat {
    public static final String MOD_ID = "zones";

    /** The lowest RWSARouters API version that has every method RWSA calls. */
    private static final int REQUIRED_API_VERSION = 1;

    /** Set once from the mod constructor: Zones is loaded and its API is new enough. */
    private static boolean zonesLoaded = false;

    /**
     * Tells whether Zones is usable.
     *
     * @return true once init found Zones loaded with a new enough RWSA API
     */
    public static boolean isZonesLoaded() {
        return zonesLoaded;
    }

    /**
     * Decides whether Zones is usable. Called once from the mod constructor.
     * <p>
     * The installed Zones' API version is read through RWSARouters.apiVersion(), a method call that runs in the
     * installed jar. A Zones without RWSARouters (or without apiVersion()), or with an API older than
     * REQUIRED_API_VERSION, logs an error and is treated as not installed.
     */
    public static void init() {
        if (!ModList.get().isLoaded(MOD_ID)) {
            return;
        }
        int version;
        try {
            version = ZonesDataAccess.apiVersion();
        } catch (LinkageError e) {
            ModLogger.error("Zones is installed but has no RWSA API (net.flameslight.zones.compat.RWSARouters), "
                    + "version {} or newer is required. Update Zones. Running as if Zones were not installed.",
                    REQUIRED_API_VERSION);
            return;
        }
        if (version < REQUIRED_API_VERSION) {
            ModLogger.error("Zones is installed but its RWSA API is version {}, version {} or newer is required. "
                    + "Update Zones. Running as if Zones were not installed.", version, REQUIRED_API_VERSION);
            return;
        }
        zonesLoaded = true;
        ModLogger.info("Zones detected: RoadWeaver's GPU terrain sampling will account for flattened zones.");
    }

    /**
     * Returns the function Zones' preliminary-surface flattening wraps.
     *
     * @param function any density function, not changed
     * @return the wrapped original if {@code function} is Zones' wrapper, otherwise {@code function} itself
     *         (always itself without Zones)
     */
    public static DensityFunction unwrapPreliminarySurface(DensityFunction function) {
        return zonesLoaded ? ZonesDataAccess.unwrapPreliminarySurface(function) : function;
    }

    /**
     * Tells whether any placed zone flattens terrain.
     *
     * @return true if Zones is loaded and at least one placed zone flattens terrain
     */
    public static boolean hasAnyFlattenZone() {
        return zonesLoaded && ZonesDataAccess.hasAnyFlattenZone();
    }

    /**
     * Tells whether Zones flattens this column's preliminary surface.
     *
     * @param dimension the dimension id
     * @param blockX    the column's X (block)
     * @param blockZ    the column's Z (block)
     * @return true if Zones is loaded and a flattening zone sets this column's preliminary surface height
     */
    public static boolean flattensColumn(ResourceLocation dimension, int blockX, int blockZ) {
        return zonesLoaded && ZonesDataAccess.flattensColumn(dimension, blockX, blockZ);
    }

    // ---- zonesWithNoRoads ----

    /**
     * Lists the overworld no-road zones whose circle meets an area.
     * <p>
     * A zone counts when its own id, or the id of any zone it is nested in (its parentZone chain, at any
     * depth), is listed in zonesWithNoRoads. Zones are placed when the level loads, before RoadWeaver plans.
     *
     * @param minX the area's min X (block, inclusive)
     * @param minZ the area's min Z (block, inclusive)
     * @param maxX the area's max X (block, inclusive)
     * @param maxZ the area's max Z (block, inclusive)
     * @return the matching zones, empty without Zones or with an empty zonesWithNoRoads list
     */
    public static List<ZonePerimeter> noRoadZones(int minX, int minZ, int maxX, int maxZ) {
        Set<String> listed = CommonConfig.zonesWithNoRoads();
        List<ZonePerimeter> test = testZones;
        if (listed.isEmpty() || (test == null && !zonesLoaded)) {
            return List.of();
        }
        List<ZonePerimeter> all = test != null ? test : ZonesDataAccess.allZones(Level.OVERWORLD.location());
        List<ZonePerimeter> out = new ArrayList<>();
        for (ZonePerimeter zone : all) {
            if (zone.matchedId(listed) != null && zone.intersects(minX, minZ, maxX, maxZ)) {
                out.add(zone);
            }
        }
        return out;
    }

    /**
     * Finds the no-road zone containing an overworld column.
     *
     * @param x the column's X (block)
     * @param z the column's Z (block)
     * @return the smallest no-road zone (see noRoadZones) containing the column, null if none does
     */
    @Nullable
    public static ZonePerimeter noRoadZoneAt(int x, int z) {
        ZonePerimeter best = null;
        for (ZonePerimeter zone : noRoadZones(x, z, x, z)) {
            if (zone.contains(x, z, 0) && (best == null || zone.radius() < best.radius())) {
                best = zone;
            }
        }
        return best;
    }

    // ---- Used by the harness tests only ----

    /** Zones to use instead of Zones' own, any id. Filtered by zonesWithNoRoads like real ones. */
    @Nullable
    private static volatile List<ZonePerimeter> testZones;

    /**
     * Replaces the zones noRoadZones reads, for the harness tests.
     *
     * @param zones the zones to use, null to go back to Zones' own
     */
    public static void setZonesForTests(@Nullable List<ZonePerimeter> zones) {
        testZones = zones;
    }
}
