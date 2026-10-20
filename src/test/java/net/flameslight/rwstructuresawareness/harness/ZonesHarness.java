package net.flameslight.rwstructuresawareness.harness;

import net.flameslight.rwstructuresawareness.RWStructuresAwareness;
import net.flameslight.rwstructuresawareness.compat.zones.ZonesCompat;
import net.flameslight.rwstructuresawareness.config.CommonConfig;
import net.flameslight.rwstructuresawareness.compat.zones.GpuFlattenCorrection;
import net.flameslight.rwstructuresawareness.types.road.RoadObstacles;
import net.flameslight.rwstructuresawareness.roadweaver.RoadWeaverAccessor;
import net.flameslight.rwstructuresawareness.road.RoadDestinationsFilter;
import net.flameslight.rwstructuresawareness.types.zones.ZonePerimeter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.forgespi.language.IModFileInfo;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.shiroha233.roadweaver.pathfinding.cache.CoarseHeightBatchRequest;
import net.shiroha233.roadweaver.pathfinding.cache.opencl.DensityGraphCompileResult;
import net.shiroha233.roadweaver.pathfinding.cache.opencl.DensityGraphCompiler;
import net.shiroha233.roadweaver.core.model.StructureInfo;
import net.shiroha233.roadweaver.persistence.sharded.RoadShardStorage;

import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

/**
 * Harness 2: compatibility with Zones (run with Zones on the runtime classpath, every test in
 * RoadWeaverHarness runs again in the same server). Without Zones these tests pass trivially and
 * RoadWeaverHarness.wiringZonesStateMatchesRun checks that Zones is really absent.
 */
@GameTestHolder(RWStructuresAwareness.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ZonesHarness {
    /**
     * Passes a test that needs Zones when Zones isn't loaded.
     *
     * @param helper the test's helper, succeeded when Zones is absent
     * @return true if Zones is absent and the test must stop
     */
    private static boolean zonesAbsent(GameTestHelper helper) {
        if (ModList.get().isLoaded(ZonesCompat.MOD_ID)) {
            return false;
        }
        helper.succeed();
        return true;
    }

    /**
     * The installed Zones reports its RWSA API version through apiVersion(), and RWSA accepted it.
     *
     * @param helper the test's helper
     */
    @GameTest(template = HarnessSetup.TEMPLATE)
    public static void zonesApiVersionIsSupported(GameTestHelper helper) {
        if (zonesAbsent(helper)) {
            return;
        }
        int version = ZonesTypes.apiVersion();
        H.check(version >= 1, "Zones reports RWSA API version %d", version);
        H.check(ZonesCompat.isZonesLoaded(), "RWSA rejected Zones' RWSA API version %d", version);
        H.log("Zones RWSA API version {}", version);
        helper.succeed();
    }

    /**
     * Zones' own RoadWeaver compat must be gone, or both mods would handle roads twice.
     *
     * @param helper the test's helper
     */
    @GameTest(template = HarnessSetup.TEMPLATE)
    public static void zonesHasNoLeftoverRoadWeaverCompat(GameTestHelper helper) {
        if (zonesAbsent(helper)) {
            return;
        }
        boolean compatClass;
        try {
            Class.forName("net.flameslight.zones.compat.roadweaver.RoadWeaverCompat", false, ZonesHarness.class.getClassLoader());
            compatClass = true;
        } catch (ClassNotFoundException e) {
            compatClass = false;
        }
        H.check(!compatClass, "Zones still contains net.flameslight.zones.compat.roadweaver (old RoadWeaver compat)");
        IModFileInfo zonesFile = ModList.get().getModFileById(ZonesCompat.MOD_ID);
        H.check(!Files.exists(zonesFile.getFile().findResource("zones.roadweaver.mixins.json")),
                "Zones still ships zones.roadweaver.mixins.json");
        helper.succeed();
    }

    /**
     * Both mods' mixins sit on the shared targets (neither replaced the other).
     *
     * @param helper the test's helper
     * @throws Exception when a target class can't be loaded
     */
    @GameTest(template = HarnessSetup.TEMPLATE)
    public static void zonesMixinsCoexistOnSharedTargets(GameTestHelper helper) throws Exception {
        if (zonesAbsent(helper)) {
            return;
        }
        String[] shared = {
                "net.minecraft.world.level.levelgen.structure.Structure",
                "net.minecraft.world.level.chunk.ChunkGenerator",
                "net.minecraft.world.level.levelgen.structure.pools.JigsawPlacement$Placer",
        };
        for (String name : shared) {
            Class<?> c = Class.forName(name, false, ZonesHarness.class.getClassLoader());
            boolean addon = Arrays.stream(c.getDeclaredMethods()).anyMatch(m -> m.getName().contains("rwstructuresawareness$"));
            boolean zones = Arrays.stream(c.getDeclaredMethods()).anyMatch(m -> m.getName().contains("structurezones$"));
            H.check(addon && zones, "%s: addon handlers %s, Zones handlers %s", name, addon, zones);
        }
        helper.succeed();
    }

    /**
     * Zones gates StructureCheck (the prediction RoadWeaver verifies its destinations with), so a
     * structure the zones forbid is never chosen as a road destination.
     *
     * @param helper the test's helper
     * @throws Exception when StructureCheck can't be loaded
     */
    @GameTest(template = HarnessSetup.TEMPLATE)
    public static void zonesGateStructurePredictions(GameTestHelper helper) throws Exception {
        if (zonesAbsent(helper)) {
            return;
        }
        Class<?> c = Class.forName("net.minecraft.world.level.levelgen.structure.StructureCheck", false,
                ZonesHarness.class.getClassLoader());
        H.check(Arrays.stream(c.getDeclaredMethods()).anyMatch(m -> m.getName().contains("structurezones$")),
                "Zones' StructureCheckMixin isn't applied");
        // Outside any zone a structure that isn't zone-restricted is still predicted as vanilla would.
        ServerLevel level = helper.getLevel();
        BlockPos area = H.area();
        ChunkPos chunk = new ChunkPos((area.getX() + 1024) >> 4, (area.getZ() + 1024) >> 4);
        Holder<Structure> high = H.structure(level, "rwstructuresawareness:harness_high");
        if (ZonesTypes.insideAnyZone(level.dimension().location(), chunk.getMinBlockX() + 8, chunk.getMinBlockZ() + 8)) {
            helper.succeed(); // inside a zone the gate may rightly reject it, only the hook check applies
            return;
        }
        H.check(level.structureManager().checkStructurePresence(chunk, high.value(), false)
                        != net.minecraft.world.level.levelgen.structure.StructureCheckResult.START_NOT_PRESENT,
                "a structure allowed everywhere was rejected by the prediction gate");
        helper.succeed();
    }

    /**
     * Zones' flattening wrapper no longer sends RoadWeaver's GPU sampling back to the CPU.
     *
     * @param helper the test's helper
     */
    @GameTest(template = HarnessSetup.TEMPLATE)
    public static void zonesFlatteningWrapperCompilesForGpu(GameTestHelper helper) {
        if (zonesAbsent(helper)) {
            return;
        }
        ServerLevel level = helper.getLevel();
        DensityFunction original = DensityFunctions.constant(3.0);
        DensityFunction wrapper = ZonesTypes.flatteningWrapper(original, level.getChunkSource().randomState());
        H.check(ZonesTypes.isFlatteningWrapper(wrapper), "harness: not a Zones wrapper");
        H.check(ZonesCompat.unwrapPreliminarySurface(wrapper) == original, "unwrap didn't return the wrapped function");
        DensityGraphCompileResult direct = DensityGraphCompiler.compile(wrapper);
        H.check(direct.supported(), "GPU compile of Zones' wrapper failed: %s", direct.unsupportedReason());
        DensityGraphCompileResult nested = DensityGraphCompiler.compile(DensityFunctions.add(wrapper, DensityFunctions.constant(1.0)));
        H.check(nested.supported(), "GPU compile of a graph containing Zones' wrapper failed: %s", nested.unsupportedReason());
        helper.succeed();
    }

    /**
     * With no flattening zone, the GPU correction leaves RoadWeaver's GPU heights untouched.
     *
     * @param helper the test's helper
     */
    @GameTest(template = HarnessSetup.TEMPLATE)
    public static void zonesGpuCorrectionOnlyTouchesFlattenedColumns(GameTestHelper helper) {
        if (zonesAbsent(helper)) {
            return;
        }
        H.check(ZonesCompat.hasAnyFlattenZone() == ZonesTypes.hasAnyFlattenZone(), "hasAnyFlattenZone disagrees with Zones");
        int[] heights = {7, 8, 9, 10, 11, 12};
        GpuFlattenCorrection.correct(helper.getLevel(), new CoarseHeightBatchRequest(4096, 4096, 4, 3, 2), heights);
        if (!ZonesTypes.hasAnyFlattenZone()) {
            H.check(Arrays.equals(heights, new int[]{7, 8, 9, 10, 11, 12}), "heights changed without any flattening zone: %s",
                    Arrays.toString(heights));
        }
        helper.succeed();
    }

    /**
     * With Zones' StructureMixin on the same method, the addon still rejects a structure that would generate on
     * a road.
     *
     * @param helper the test's helper
     */
    @GameTest(template = HarnessSetup.TEMPLATE)
    public static void zonesAddonStillRejectsStructureOnRoad(GameTestHelper helper) {
        if (zonesAbsent(helper)) {
            return;
        }
        ServerLevel level = helper.getLevel();
        Holder<Structure> high = H.structure(level, "rwstructuresawareness:harness_high");
        BlockPos area = H.area();
        ChunkPos chunk = new ChunkPos((area.getX() + 1024) >> 4, (area.getZ() + 1024) >> 4);
        StructureStart before = H.generate(level, high, chunk);
        H.check(H.isValid(before), "harness: the structure doesn't generate with Zones present even without a road");
        BoundingBox b = before.getPieces().get(0).getBoundingBox(); // the start piece: never trimmed
        int z = (b.minZ() + b.maxZ()) / 2;
        RoadShardStorage.addRoad(level, H.roadAlongX((b.minX() - 24) & ~1, b.maxX() + 24, z, b.minY(), H.NO_OWNER, H.NO_OWNER));
        H.check(!H.isValid(H.generate(level, high, chunk)), "with Zones present, a structure generated on a road");
        helper.succeed();
    }

    /**
     * zonesWithNoRoads against Zones' real zones: a zone counts only once its definition id is listed,
     * then it blocks roads and drops the destinations inside it. A nested zone (when the world has one) is
     * covered by listing only its parent. Skipped if Zones placed no zone.
     *
     * @param helper the test's helper
     */
    @GameTest(template = HarnessSetup.TEMPLATE)
    public static void zonesNoRoadZoneLookup(GameTestHelper helper) {
        if (zonesAbsent(helper)) {
            return;
        }
        ZonePerimeter zone = ZonesTypes.firstZone(Level.OVERWORLD.location());
        if (zone == null) {
            H.log("zonesWithNoRoads lookup: Zones placed no zone in this world, nothing to look up");
            helper.succeed();
            return;
        }
        ServerLevel level = helper.getLevel();
        int x = zone.centerX();
        int z = zone.centerZ();
        List<StructureInfo> candidates = List.of(new StructureInfo(new BlockPos(x, 80, z), "minecraft:village_plains"));
        List<? extends String> old = CommonConfig.ZONES_WITH_NO_ROADS.get();
        ZonesCompat.setZonesForTests(null);
        try {
            CommonConfig.ZONES_WITH_NO_ROADS.set(List.of());
            CommonConfig.refresh();
            H.check(ZonesCompat.noRoadZoneAt(x, z) == null, "an unlisted zone (%s) blocks roads", zone.zoneId());
            H.check(RoadDestinationsFilter.filter(level, candidates).size() == 1,
                    "a destination inside an unlisted zone (%s) was dropped", zone.zoneId());

            CommonConfig.ZONES_WITH_NO_ROADS.set(List.of(zone.zoneId()));
            CommonConfig.refresh();
            ZonePerimeter found = ZonesCompat.noRoadZoneAt(x, z);
            H.check(found != null && zone.zoneId().equals(found.matchedId(CommonConfig.zonesWithNoRoads())),
                    "listed zone %s not found at its center %d, %d", zone.zoneId(), x, z);
            RoadObstacles obstacles = RoadObstacles.forRoute(x - 200, z, x + 200, z, RoadWeaverAccessor.configuredRoadMargin());
            H.check(obstacles != null && obstacles.isBlocked(x, z, -64, 320), "listed zone %s doesn't block a road",
                    zone.zoneId());
            H.check(RoadDestinationsFilter.filter(level, candidates).isEmpty(),
                    "a destination inside listed zone %s was kept", zone.zoneId());

            ZonePerimeter child = ZonesTypes.firstNestedZone(Level.OVERWORLD.location());
            if (child == null) {
                H.log("zonesWithNoRoads lookup: no nested zone in this world, the nested check is skipped");
            } else {
                String parent = child.ancestorIds().get(0);
                int cx = child.centerX();
                int cz = child.centerZ();
                CommonConfig.ZONES_WITH_NO_ROADS.set(List.of(parent));
                CommonConfig.refresh();
                ZonePerimeter at = ZonesCompat.noRoadZoneAt(cx, cz);
                H.check(at != null && parent.equals(at.matchedId(CommonConfig.zonesWithNoRoads())),
                        "nested zone %s isn't covered by its listed parent %s", child.zoneId(), parent);
                RoadObstacles nested = RoadObstacles.forRoute(cx - 200, cz, cx + 200, cz,
                        RoadWeaverAccessor.configuredRoadMargin());
                H.check(nested != null && nested.isBlocked(cx, cz, -64, 320),
                        "nested zone %s (parent %s listed) doesn't block a road", child.zoneId(), parent);
                List<StructureInfo> inChild = List.of(new StructureInfo(new BlockPos(cx, 80, cz), "minecraft:village_plains"));
                H.check(RoadDestinationsFilter.filter(level, inChild).isEmpty(),
                        "a destination inside nested zone %s (parent %s listed) was kept", child.zoneId(), parent);
            }
        } finally {
            CommonConfig.ZONES_WITH_NO_ROADS.set(old);
            CommonConfig.refresh();
        }
        helper.succeed();
    }
}
