package net.flameslight.rwstructuresawareness.config;

import net.flameslight.rwstructuresawareness.logger.ModLogger;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.common.ForgeConfigSpec;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class CommonConfig {
    public static final boolean DEFAULT_DEBUG_MOD = false;
    public static final boolean DEFAULT_BLOCK_UNDERGROUND_DESTINATIONS = true;

    public static final ForgeConfigSpec COMMON_SPEC;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> STRUCTURES_ALLOWED_ON_ROAD;
    public static final ForgeConfigSpec.BooleanValue BLOCK_UNDERGROUND_DESTINATIONS;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> ZONES_WITH_NO_ROADS;
    public static final ForgeConfigSpec.BooleanValue DEBUG_MOD;

    /** Parsed zonesWithNoRoads (Zones zone ids). Replaced wholesale on (re)load. */
    private static volatile Set<String> zonesWithNoRoads = Set.of();

    private static volatile boolean isDebugMod = DEFAULT_DEBUG_MOD;
    private static volatile boolean isDebugModResolved = false;
    private static volatile boolean blockUndergroundDestinations = DEFAULT_BLOCK_UNDERGROUND_DESTINATIONS;
    private static volatile boolean blockUndergroundDestinationsResolved = false;

    /** Parsed structuresAllowedOnRoad, as "namespace:path" strings. Replaced wholesale on (re)load. */
    private static volatile Set<String> allowedOnRoad = Set.of();

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();

        builder.comment(
                "RW - Structures Awareness Addon configuration (RoadWeaver addon)")
                .push("general");

        STRUCTURES_ALLOWED_ON_ROAD = builder
                .comment("Structure resource ids that are allowed to be placed on roads.",
                        "Example: structuresAllowedOnRoad = [\"minecraft:ruined_portal\",\"minecraft:igloo\"]")
                .defineList("structuresAllowedOnRoad", new ArrayList<>(), o -> o instanceof String);

        BLOCK_UNDERGROUND_DESTINATIONS = builder
                .comment("Blocks every structure considered underground (mineshafts, strongholds, ancient cities...)",
                        "from being a road destination, so no road would lead to an empty spot (above an underground",
                        "structure). Structures defined in RoadWeaver's own structure selection or structure whitelist",
                        "ignore that option, meaning this option would have no impact for this structures.")
                .define("blockUndergroundStructuresDestinations", DEFAULT_BLOCK_UNDERGROUND_DESTINATIONS);

        ZONES_WITH_NO_ROADS = builder
                .comment("Only has an effect when Zones mod is installed.",
                        "zone ids (under \"id\" key inside each zoneDefinition) that roads stay out of and would re-route",
                        "around them (or stop at its edges if cant).",
                        "Zones nested inside a listed zone (through parentZone, at any depth) are covered too.",
                        "Example: zonesWithNoRoads = [\"witch_village\"]")
                .defineList("zonesWithNoRoads", new ArrayList<>(), o -> o instanceof String);

        DEBUG_MOD = builder
                .comment("Enables this mod's own [DEBUG] log lines.")
                .define("debugMod", DEFAULT_DEBUG_MOD);

        builder.pop();
        COMMON_SPEC = builder.build();
    }

    /**
     * Re-reads the parsed options from the config file. Called on config load and reload (ModEvents).
     * Invalid ids are logged and skipped.
     */
    public static void refresh() {
        Set<String> parsed = new HashSet<>();
        for (String raw : STRUCTURES_ALLOWED_ON_ROAD.get()) {
            ResourceLocation id = raw == null ? null : ResourceLocation.tryParse(raw.trim());
            if (id == null) {
                ModLogger.warn("structuresAllowedOnRoad: '{}' is not a valid structure id, ignored.", raw);
                continue;
            }
            parsed.add(id.toString());
        }
        allowedOnRoad = Set.copyOf(parsed);
        Set<String> zones = new HashSet<>();
        for (String raw : ZONES_WITH_NO_ROADS.get()) {
            if (raw != null && !raw.isBlank()) {
                zones.add(raw.trim());
            }
        }
        zonesWithNoRoads = Set.copyOf(zones);
        isDebugModResolved = false;
        blockUndergroundDestinationsResolved = false;
    }

    /**
     * Reads blockUndergroundStructuresDestinations. Safe at any point in the lifecycle, like isDebugMod.
     *
     * @return the option's value, the default until the config file has loaded
     */
    public static boolean isBlockUndergroundDestinations() {
        if (!blockUndergroundDestinationsResolved) {
            if (!COMMON_SPEC.isLoaded()) {
                return DEFAULT_BLOCK_UNDERGROUND_DESTINATIONS;
            }
            blockUndergroundDestinations = BLOCK_UNDERGROUND_DESTINATIONS.get();
            blockUndergroundDestinationsResolved = true;
        }
        return blockUndergroundDestinations;
    }

    /**
     * Reads zonesWithNoRoads.
     *
     * @return the listed zone ids, trimmed, immutable, empty until the config loads
     */
    public static Set<String> zonesWithNoRoads() {
        return zonesWithNoRoads;
    }

    /**
     * Tells whether a structure is listed in structuresAllowedOnRoad.
     *
     * @param structureId the structure's id, may be null
     * @return true if it is listed, false for null
     */
    public static boolean isAllowedOnRoad(ResourceLocation structureId) {
        Set<String> allowed = allowedOnRoad;
        return !allowed.isEmpty() && structureId != null && allowed.contains(structureId.toString());
    }

    /**
     * Reads debugMod.
     * <p>
     * Safe at any point in the lifecycle: returns the default until the config file has actually
     * been loaded, rather than throwing the way ConfigValue.get() does.
     *
     * @return the option's value, the default until the config file has loaded
     */
    public static boolean isDebugMod() {
        if (!isDebugModResolved) {
            if (!COMMON_SPEC.isLoaded()) {
                return DEFAULT_DEBUG_MOD;
            }
            isDebugMod = DEBUG_MOD.get();
            isDebugModResolved = true;
        }
        return isDebugMod;
    }
}
