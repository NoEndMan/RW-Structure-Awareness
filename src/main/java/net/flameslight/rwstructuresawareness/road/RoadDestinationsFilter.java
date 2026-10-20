package net.flameslight.rwstructuresawareness.road;

import net.flameslight.rwstructuresawareness.types.road.RoadLevel;
import net.flameslight.rwstructuresawareness.compat.zones.ZonesCompat;
import net.flameslight.rwstructuresawareness.config.CommonConfig;
import net.flameslight.rwstructuresawareness.logger.ModLogger;
import net.flameslight.rwstructuresawareness.mixins.roadweaver.StructureSelectionConfigAccessor;
import net.flameslight.rwstructuresawareness.types.zones.ZonePerimeter;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.shiroha233.roadweaver.config.ConfigService;
import net.shiroha233.roadweaver.config.ModConfig;
import net.shiroha233.roadweaver.config.structure.StructureSelectionConfig;
import net.shiroha233.roadweaver.core.model.StructureInfo;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Filter RoadWeaver's destination candidates inside a zonesWithNoRoads zone (with Zones
 * installed), every structure considered underground if blockUndergroundStructuresDestinations is enabled,
 * so no road leads to an empty spot above an underground structure (For example, a mineshaft).
 *
 * Underground structure: a structure generated in an underground step (Structure.step() is
 * UNDERGROUND_STRUCTURES, UNDERGROUND_DECORATION or STRONGHOLDS). RoadWeaver's own custom
 * destinations take priority: an id selected one by one in its structure selection, or listed (not as
 * a #tag) in its structureWhitelist, is always kept. Candidates whose id isn't a registered structure
 * (e.g. RoadWeaver's "village" category) are kept.
 */
public final class RoadDestinationsFilter {
    /**
     * Drops the destination candidates roads must not lead to. Wraps StructureIndexService's results.
     *
     * @param level      the level RoadWeaver plans in
     * @param candidates RoadWeaver's candidates, not changed, may be null
     * @return {@code candidates} itself when nothing is dropped, otherwise a new list of the kept ones
     */
    public static List<StructureInfo> filter(ServerLevel level, List<StructureInfo> candidates) {
        boolean underground = CommonConfig.isBlockUndergroundDestinations();
        boolean zones = !CommonConfig.zonesWithNoRoads().isEmpty();
        if (candidates == null || candidates.isEmpty() || (!underground && !zones) || !RoadLevel.isRoadLevel(level)) {
            return candidates;
        }
        Registry<Structure> registry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        Set<String> explicit = null;
        List<StructureInfo> kept = null;
        for (int i = 0; i < candidates.size(); i++) {
            StructureInfo info = candidates.get(i);
            ResourceLocation id = info == null || info.structureId() == null ? null
                    : ResourceLocation.tryParse(info.structureId().toLowerCase(Locale.ROOT));
            Structure structure = id == null ? null : registry.get(id);
            boolean drop = false;
            ZonePerimeter zone = zones && info != null && info.pos() != null
                    ? ZonesCompat.noRoadZoneAt(info.pos().getX(), info.pos().getZ()) : null;
            if (zone != null) {
                // zonesWithNoRoads is an explicit "no roads here": applies to RoadWeaver's own selection too.
                drop = true;
                ModLogger.debug("Destination {} at {} dropped: inside no-road zone {} (zonesWithNoRoads).",
                        info.structureId(), info.pos().toShortString(), describe(zone));
            } else if (underground && structure != null && isUnderground(structure)) {
                if (explicit == null) {
                    explicit = explicitDestinations();
                }
                if (explicit.contains(id.toString())) {
                    ModLogger.debug("Destination {} at {} is underground but kept: named in RoadWeaver's own selection.",
                            id, info.pos().toShortString());
                } else {
                    drop = true;
                    ModLogger.debug("Destination {} at {} dropped: underground structure "
                            + "(blockUndergroundStructuresDestinations).", id, info.pos().toShortString());
                }
            }
            if (drop && kept == null) {
                kept = new ArrayList<>(candidates.subList(0, i));
            } else if (!drop && kept != null) {
                kept.add(info);
            }
        }
        return kept == null ? candidates : kept;
    }

    /**
     * Tells whether a structure generates in an underground step.
     *
     * @param structure the structure
     * @return true for UNDERGROUND_STRUCTURES, UNDERGROUND_DECORATION and STRONGHOLDS
     */
    public static boolean isUnderground(Structure structure) {
        GenerationStep.Decoration step = structure.step();
        return step == GenerationStep.Decoration.UNDERGROUND_STRUCTURES
                || step == GenerationStep.Decoration.UNDERGROUND_DECORATION
                || step == GenerationStep.Decoration.STRONGHOLDS;
    }

    /**
     * Names a no-road zone for the debug log.
     *
     * @param zone a zone returned by ZonesCompat.noRoadZoneAt
     * @return its id, plus "(nested in Y)" when it is listed only through the zone Y it is nested in
     */
    private static String describe(ZonePerimeter zone) {
        String listed = zone.matchedId(CommonConfig.zonesWithNoRoads());
        return listed == null || listed.equals(zone.zoneId()) ? zone.zoneId()
                : zone.zoneId() + " (nested in " + listed + ")";
    }

    /**
     * Collects the structure ids RoadWeaver's config names one by one.
     *
     * @return lowercase "namespace:path" ids from its structure selection and its structureWhitelist (no #tags)
     */
    private static Set<String> explicitDestinations() {
        Set<String> out = new HashSet<>();
        try {
            Set<String> selected = ((StructureSelectionConfigAccessor) (Object) StructureSelectionConfig.get())
                    .rwstructuresawareness$getExplicitStructures();
            if (selected != null) {
                for (String s : List.copyOf(selected)) {
                    addId(out, s);
                }
            }
        } catch (RuntimeException e) {
            ModLogger.error("Could not read RoadWeaver's structure selection", e);
        }
        ModConfig config = ConfigService.get();
        if (config != null && config.structureWhitelist() != null) {
            for (String s : config.structureWhitelist()) {
                if (s != null && !s.startsWith("#")) {
                    addId(out, s);
                }
            }
        }
        return out;
    }

    /**
     * Adds a config id to a set, normalized.
     *
     * @param out the set, mutated
     * @param raw the id as written in the config, may be null or invalid (then nothing is added)
     */
    private static void addId(Set<String> out, String raw) {
        ResourceLocation id = raw == null ? null : ResourceLocation.tryParse(raw.trim().toLowerCase(Locale.ROOT));
        if (id != null) {
            out.add(id.toString());
        }
    }
}
