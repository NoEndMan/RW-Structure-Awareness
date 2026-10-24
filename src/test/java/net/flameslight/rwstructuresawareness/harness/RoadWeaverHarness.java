package net.flameslight.rwstructuresawareness.harness;

import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.flameslight.rwstructuresawareness.RWStructuresAwareness;
import net.flameslight.rwstructuresawareness.compat.zones.ZonesCompat;
import net.flameslight.rwstructuresawareness.config.CommonConfig;
import net.flameslight.rwstructuresawareness.compat.zones.GpuFlattenCorrection;
import net.flameslight.rwstructuresawareness.logger.RoadDebug;
import net.flameslight.rwstructuresawareness.mixins.ChunkMapAccessor;
import net.flameslight.rwstructuresawareness.mixins.roadweaver.StructureSelectionConfigAccessor;
import net.flameslight.rwstructuresawareness.road.RoadDestinationsFilter;
import net.shiroha233.roadweaver.config.structure.StructureSelectionConfig;
import net.shiroha233.roadweaver.core.model.StructureInfo;
import net.flameslight.rwstructuresawareness.types.road.RoadObstacles;
import net.flameslight.rwstructuresawareness.road.RoadRerouter;
import net.flameslight.rwstructuresawareness.RoadInsideRegionChunksRecorder;
import net.flameslight.rwstructuresawareness.road.JunctionRepair;
import net.flameslight.rwstructuresawareness.road.SnapJunctionShaper;
import net.flameslight.rwstructuresawareness.roadweaver.RoadWeaverAccessor;
import net.flameslight.rwstructuresawareness.StructureChunkRecorder;
import net.flameslight.rwstructuresawareness.tunnel.RoadTunnelPlanner;
import net.flameslight.rwstructuresawareness.tunnel.TunnelBeardifierAccess;
import net.flameslight.rwstructuresawareness.tunnel.TunnelCarver;
import net.flameslight.rwstructuresawareness.types.road.RoadChunkEntry;
import net.flameslight.rwstructuresawareness.types.road.RoadColumns;
import net.flameslight.rwstructuresawareness.types.road.RoadInsideRegionChunks;
import net.flameslight.rwstructuresawareness.types.structure.StructureChunkStorage;
import net.flameslight.rwstructuresawareness.types.tunnel.TunnelPlan;
import net.flameslight.rwstructuresawareness.types.tunnel.TunnelStore;
import net.flameslight.rwstructuresawareness.types.zones.ZonePerimeter;
import net.flameslight.rwstructuresawareness.utils.CoordinateKeys;
import net.flameslight.rwstructuresawareness.types.road.RoadLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.SectionPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.shiroha233.roadweaver.config.ConfigService;
import net.shiroha233.roadweaver.config.sub.RoadGenerationConfig;
import net.shiroha233.roadweaver.core.model.RoadData;
import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;
import net.shiroha233.roadweaver.core.model.RoadSpan;
import net.shiroha233.roadweaver.core.model.SpanType;
import net.shiroha233.roadweaver.core.model.StructureConnection;
import net.shiroha233.roadweaver.features.path.config.PathFeatureConfig;
import net.shiroha233.roadweaver.features.path.pathlogic.core.Road;
import net.shiroha233.roadweaver.features.path.pathlogic.pathfinding.RoadHeightInterpolator;
import net.shiroha233.roadweaver.features.path.pathlogic.pathfinding.RoadPathCalculator;
import net.shiroha233.roadweaver.pathfinding.cache.CoarseHeightBatchRequest;
import net.shiroha233.roadweaver.pathfinding.cache.TerrainSamplingCache;
import net.shiroha233.roadweaver.pathfinding.cache.opencl.DensityGraphCompiler;
import net.shiroha233.roadweaver.persistence.LegacyRoadDataRepairService;
import net.shiroha233.roadweaver.persistence.files.RoadFileStorage;
import net.shiroha233.roadweaver.persistence.files.StructureFileStorage;
import net.shiroha233.roadweaver.persistence.sharded.RoadShardStorage;
import net.shiroha233.roadweaver.persistence.sqlite.RoadSqliteStorage;
import net.shiroha233.roadweaver.postprocess.RoadSnapService;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Harness 1: RW- Structures Awareness Addon with RoadWeaver. Runs the addon's real code paths inside
 * a real server with every mixin applied, against RoadWeaver's own storage, pathfinder and road
 * builder. Also run with Zones present (harness 2), where every test here must still pass.
 *
 * Terrain varies, so tests needing a known structure height use the harness's own structures:
 * rwstructuresawareness:harness_high (a plains town center fixed at y = 100, streets on the terrain)
 * and rwstructuresawareness:harness_low (a normal mineshaft: always below sea level), both allowed
 * in every overworld biome.
 */
@GameTestHolder(RWStructuresAwareness.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RoadWeaverHarness {
    private static final int MAX_STEPS = 50_000;
    private static final String HIGH = "rwstructuresawareness:harness_high";
    private static final String LOW = "rwstructuresawareness:harness_low";
    /** Ring radius (chunks) around the tunnel tests' destination: room for the exit ramp inside. */
    private static final int RING = 6;

    // ---- Wiring ----

    /**
     * Every mixin target carries the addon's handlers (vanilla targets remapped, RoadWeaver ones not).
     *
     * @param helper the test's helper
     * @throws Exception when a target class can't be loaded
     */
    @GameTest(template = HarnessSetup.TEMPLATE)
    public static void wiringAllMixinsApplied(GameTestHelper helper) throws Exception {
        String[] targets = {
                "net.minecraft.world.level.levelgen.structure.Structure",
                "net.minecraft.world.level.chunk.ChunkGenerator",
                "net.minecraft.world.level.levelgen.structure.pools.JigsawPlacement$Placer",
                "net.minecraft.world.level.levelgen.Beardifier",
                "net.minecraft.world.level.StructureManager",
                "net.shiroha233.roadweaver.pathfinding.cache.opencl.DensityGraphCompiler",
                "net.shiroha233.roadweaver.pathfinding.cache.opencl.OpenCLCoarseHeightBatchSampler",
                "net.shiroha233.roadweaver.features.path.PathFeature",
                "net.shiroha233.roadweaver.persistence.files.RoadFileStorage",
                "net.shiroha233.roadweaver.features.path.pathlogic.core.Road",
                "net.shiroha233.roadweaver.features.path.pathlogic.pathfinding.RoadPathCalculator",
                "net.shiroha233.roadweaver.planning.RoadPlanningService",
                "net.shiroha233.roadweaver.features.path.pathlogic.core.SegmentPaver",
                "net.shiroha233.roadweaver.search.StructureIndexService",
                "net.shiroha233.roadweaver.postprocess.RoadSnapService",
        };
        List<String> missing = new ArrayList<>();
        for (String name : targets) {
            Class<?> c = Class.forName(name, false, RoadWeaverHarness.class.getClassLoader());
            if (Arrays.stream(c.getDeclaredMethods()).noneMatch(m -> m.getName().contains("rwstructuresawareness$"))) {
                missing.add(name);
            }
        }
        H.check(missing.isEmpty(), "No addon handler found in: %s", missing);
        H.check(TunnelBeardifierAccess.class.isAssignableFrom(Beardifier.class), "Beardifier lacks TunnelBeardifierAccess");
        H.check(RoadLevel.level() == helper.getLevel(), "RoadWorld is not bound to the overworld");
        helper.succeed();
    }

    /**
     * Problem-1 debug lines: the ChunkMap accessor sees a generated chunk's status off the chunk
     * system's own calls, and the save / replace / delete lines run without throwing.
     *
     * @param helper the test's helper
     */
    @GameTest(template = HarnessSetup.TEMPLATE)
    public static void wiringRoadDebugLines(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos at = helper.absolutePos(BlockPos.ZERO);
        ChunkPos chunk = new ChunkPos(at);
        level.getChunk(chunk.x, chunk.z); // FULL
        ChunkHolder holder = ((ChunkMapAccessor) level.getChunkSource().chunkMap)
                .rwstructuresawareness$getVisibleChunkIfPresent(chunk.toLong());
        H.check(holder != null && holder.getLastAvailable() != null
                        && holder.getLastAvailable().getStatus().isOrAfter(ChunkStatus.FEATURES),
                "ChunkMapAccessor doesn't see the generated chunk %s", chunk);
        H.check(CommonConfig.isDebugMod(), "harness: debugMod is off, the debug lines wouldn't run");
        RoadData road = H.roadAlongX(chunk.getMinBlockX(), chunk.getMinBlockX() + 40, chunk.getMinBlockZ() + 8, at.getY(),
                H.NO_OWNER, H.NO_OWNER);
        RoadDebug.roadSaved(level, road);
        RoadDebug.roadReplaced(level, road, road);
        RoadDebug.roadReplaced(level, road, null);
        RoadDebug.pavingStarted();
        RoadDebug.pavingChecked(true, false);
        RoadDebug.pavingFinished(chunk);
        helper.succeed();
    }

    /**
     * Zones' presence matches what this run expects, and the optional-dependency bridge behaves. With Zones,
     * ZonesCompat.isZonesLoaded() being true also proves RWSARouters' API version check passed.
     *
     * @param helper the test's helper
     */
    @GameTest(template = HarnessSetup.TEMPLATE)
    public static void wiringZonesStateMatchesRun(GameTestHelper helper) {
        boolean expected = Boolean.parseBoolean(System.getProperty("rwsa.expectZones", "false"));
        boolean loaded = ModList.get().isLoaded("zones");
        H.check(loaded == expected, "Zones loaded = %s, but this run expects %s", loaded, expected);
        H.check(ZonesCompat.isZonesLoaded() == loaded, "ZonesCompat.isZonesLoaded() = %s, ModList says %s",
                ZonesCompat.isZonesLoaded(), loaded);
        DensityFunction constant = DensityFunctions.constant(2.0);
        H.check(ZonesCompat.unwrapPreliminarySurface(constant) == constant, "unwrap changed a plain function");
        if (!loaded) {
            H.check(!ZonesCompat.hasAnyFlattenZone(), "hasAnyFlattenZone without Zones");
            H.check(!ZonesCompat.flattensColumn(helper.getLevel().dimension().location(), 0, 0), "flattensColumn without Zones");
            int[] heights = {1, 2, 3, 4};
            GpuFlattenCorrection.correct(helper.getLevel(), new CoarseHeightBatchRequest(0, 0, 4, 2, 2), heights);
            H.check(Arrays.equals(heights, new int[]{1, 2, 3, 4}), "GPU correction changed heights without Zones: %s",
                    Arrays.toString(heights));
        }
        H.check(DensityGraphCompiler.compile(constant).supported(), "RoadWeaver's GPU compiler rejects a constant");
        helper.succeed();
    }

    // ---- Pure logic (real classes, real runtime) ----

    /**
     * Coordinate keys, footprint and Y-range packing round-trip over the whole world, and the column-in-chunk test.
     *
     * @param helper the test's helper
     */
    @GameTest(template = HarnessSetup.TEMPLATE)
    public static void logicKeysAndPacking(GameTestHelper helper) {
        int[] values = {-30_000_000, -1, 0, 1, 29_999_999};
        for (int a : values) {
            for (int b : values) {
                long key = CoordinateKeys.pack(a, b);
                H.check(CoordinateKeys.unpackA(key) == a && CoordinateKeys.unpackB(key) == b, "pack/unpack (%d, %d)", a, b);
            }
        }
        int[] chunks = {-1_875_000, -33, -1, 0, 31, 32, 1_874_999};
        for (int cx : chunks) {
            int cz = -cx;
            long region = CoordinateKeys.regionOfChunk(cx, cz);
            H.check(region == CoordinateKeys.regionOfBlock(cx << 4, cz << 4)
                    && region == CoordinateKeys.regionOfBlock((cx << 4) + 15, (cz << 4) + 15), "region of chunk %d", cx);
        }
        // chunk (5, -3): blocks [80, 95] x [-48, -33]
        H.check(CoordinateKeys.isColumnLiesOnChunk(80, -48, 5, -3, 0) && CoordinateKeys.isColumnLiesOnChunk(95, -33, 5, -3, 0),
                "chunk edges inside");
        H.check(!CoordinateKeys.isColumnLiesOnChunk(79, -40, 5, -3, 0) && !CoordinateKeys.isColumnLiesOnChunk(96, -40, 5, -3, 0)
                && !CoordinateKeys.isColumnLiesOnChunk(88, -49, 5, -3, 0) && !CoordinateKeys.isColumnLiesOnChunk(88, -32, 5, -3, 0),
                "chunk edges outside");
        // the same chunk as a start chunk: destination window [64, 111] x [-64, -17]
        int m = RoadColumns.DESTINATION_MARGIN;
        H.check(CoordinateKeys.isColumnLiesOnChunk(64, -64, 5, -3, m) && CoordinateKeys.isColumnLiesOnChunk(111, -17, 5, -3, m),
                "window edges inside");
        H.check(!CoordinateKeys.isColumnLiesOnChunk(63, -48, 5, -3, m) && !CoordinateKeys.isColumnLiesOnChunk(112, -48, 5, -3, m)
                && !CoordinateKeys.isColumnLiesOnChunk(80, -65, 5, -3, m) && !CoordinateKeys.isColumnLiesOnChunk(80, -16, 5, -3, m),
                "window edges outside");
        int[][] rects = {{0, 0, 15, 15}, {3, 7, 9, 12}, {15, 0, 15, 0}};
        for (int cx : new int[]{-1_875_000, -1, 0, 1, 1_874_999}) {
            int cz = -cx - 1;
            for (int[] r : rects) {
                long f = StructureChunkRecorder.packFootprint(cx, cz, r[0], r[1], r[2], r[3]);
                H.check(StructureChunkRecorder.chunkXOf(f) == cx && StructureChunkRecorder.chunkZOf(f) == cz
                                && StructureChunkRecorder.minXOf(f) == (cx << 4) + r[0] && StructureChunkRecorder.minZOf(f) == (cz << 4) + r[1]
                                && StructureChunkRecorder.maxXOf(f) == (cx << 4) + r[2] && StructureChunkRecorder.maxZOf(f) == (cz << 4) + r[3],
                        "packFootprint(%d, %d, %s)", cx, cz, Arrays.toString(r));
            }
        }
        for (int[] range : new int[][]{{-2048, 2047}, {-64, 319}, {70, 70}}) {
            long packed = StructureChunkRecorder.packRange(range[0], range[1]);
            H.check(StructureChunkRecorder.rangeMin(packed) == range[0] && StructureChunkRecorder.rangeMax(packed) == range[1],
                    "packRange%s", Arrays.toString(range));
        }
        helper.succeed();
    }

    /**
     * A road's columns, bounds, margin and owners, and its column masks in a region map.
     *
     * @param helper the test's helper
     */
    @GameTest(template = HarnessSetup.TEMPLATE)
    public static void logicRoadColumnsAndRegionMap(GameTestHelper helper) {
        BlockPos a = H.area();
        int x0 = a.getX() + 100; // local x 4
        int z = a.getZ() + 100;  // local z 4
        ChunkPos chunk = new ChunkPos(x0 >> 4, z >> 4);
        RoadData road = H.roadAlongX(x0, x0 + 40, z, 70, H.ownerAtChunk(chunk), H.NO_OWNER);
        RoadColumns columns = RoadWeaverAccessor.toColumns(road);
        H.check(columns.margin() == H.MARGIN, "margin %d, expected %d", columns.margin(), H.MARGIN);
        H.check(columns.minX() == x0 && columns.maxX() == x0 + 40 && columns.minZ() == z - 1 && columns.maxZ() == z + 1,
                "bounds [%d..%d] x [%d..%d]", columns.minX(), columns.maxX(), columns.minZ(), columns.maxZ());
        H.check(columns.leadsTo(chunk.x, chunk.z) && !columns.leadsTo(chunk.x + 3, chunk.z), "destination window");

        long region = CoordinateKeys.regionOfBlock(x0, z);
        RoadInsideRegionChunks map = RoadInsideRegionChunks.EMPTY.withRoad(columns, region);
        RoadChunkEntry[] entries = map.entriesAt(CoordinateKeys.chunkKey(chunk.x, chunk.z));
        H.check(entries != null && entries.length == 1, "one entry in the road's first chunk");
        H.check(entries[0].minY() == 70 && entries[0].maxY() == 70, "Y range %d..%d", entries[0].minY(), entries[0].maxY());
        long[] mask = entries[0].mask();
        H.check(RoadInsideRegionChunks.maskIntersects(mask, 4, 4, 4, 4), "column (4,4) set");
        H.check(RoadInsideRegionChunks.maskIntersects(mask, 4, 3, 4, 5), "columns across the width set");
        H.check(!RoadInsideRegionChunks.maskIntersects(mask, 0, 9, 15, 15), "no column 5+ blocks off the road");
        H.check(!RoadInsideRegionChunks.maskIntersects(mask, 0, 0, 3, 15), "no column before the road start");
        H.check(map.roadLeadsTo(0, chunk.x, chunk.z) && !map.roadLeadsTo(0, chunk.x, chunk.z + 4), "owner kept per road");
        H.check(RoadInsideRegionChunks.EMPTY.withRoad(columns, CoordinateKeys.pack(CoordinateKeys.unpackA(region) + 3, CoordinateKeys.unpackB(region)))
                == RoadInsideRegionChunks.EMPTY, "a road outside the region changes nothing");
        helper.succeed();
    }

    // ---- Keeping structures off roads: RoadWeaver's road writes reach the road maps ----

    /**
     * addRoad, replaceRoad and deleteRoad through RoadWeaver's real storage keep the road maps exact.
     *
     * @param helper the test's helper
     */
    @GameTest(template = HarnessSetup.TEMPLATE)
    public static void goalARoadStorageKeepsRoadMapsCurrent(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos a = H.area();
        int x0 = a.getX() + 100;
        int z1 = a.getZ() + 100; // chunk row A
        int z2 = z1 + 32;         // chunk row A + 2
        int z3 = z1 + 64;         // chunk row A + 4
        int cx = x0 >> 4;
        RoadData r1 = H.roadAlongX(x0, x0 + 60, z1, 70, H.NO_OWNER, H.NO_OWNER);
        RoadShardStorage.addRoad(level, r1);
        H.check(hasEntries(cx, z1 >> 4), "road 1 missing after addRoad (first fill of the region from storage)");

        RoadData r2 = H.roadAlongX(x0, x0 + 60, z2, 70, H.NO_OWNER, H.NO_OWNER);
        RoadShardStorage.addRoad(level, r2);
        H.check(hasEntries(cx, z2 >> 4), "road 2 missing: addRoad on a loaded region didn't reach the map");

        RoadInsideRegionChunksRecorder.reset();
        H.check(hasEntries(cx, z1 >> 4) && hasEntries(cx, z2 >> 4), "refill from RoadWeaver's storage lost a road");

        RoadData r2moved = H.roadAlongX(x0, x0 + 60, z3, 70, H.NO_OWNER, H.NO_OWNER);
        RoadFileStorage.replaceRoad(level, RoadSqliteStorage.computeFingerprint(r2), r2moved);
        H.check(!storageHasRoadAt(level, x0 + 30, z2), "harness: RoadWeaver's replaceRoad did not remove road 2 from storage");
        H.check(!hasEntries(cx, z2 >> 4), "replaced road's old shape is still in the road map (stale obstacle)");
        H.check(hasEntries(cx, z3 >> 4), "replaced road's new shape is missing from the road map");

        RoadFileStorage.deleteRoad(level, RoadSqliteStorage.computeFingerprint(r1));
        H.check(!storageHasRoadAt(level, x0 + 30, z1), "harness: RoadWeaver's deleteRoad did not remove road 1 from storage");
        H.check(!hasEntries(cx, z1 >> 4), "deleted road is still in the road map");
        H.check(hasEntries(cx, z3 >> 4), "deleting road 1 dropped road 2");
        helper.succeed();
    }

    // ---- Keeping structures off roads: pieces trimmed, structures rejected ----

    /**
     * A structure whose start piece sits on a road is rejected.
     *
     * @param helper the test's helper
     */
    @GameTest(template = HarnessSetup.TEMPLATE)
    public static void goalAStructureOnRoadIsRejected(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Holder<Structure> high = H.structure(level, HIGH);
        ChunkPos chunk = chunkIn(H.area());
        BoundingBox b = startPiece(baseline(level, high, chunk));
        addRoadAcross(level, b, b.minY(), H.NO_OWNER);
        H.check(!H.isValid(H.generate(level, high, chunk)), "structure generated on a road (box %s)", b);
        helper.succeed();
    }

    /**
     * A road 30 blocks below a structure doesn't reject it.
     *
     * @param helper the test's helper
     */
    @GameTest(template = HarnessSetup.TEMPLATE)
    public static void goalARoadFarBelowStructureIsIgnored(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Holder<Structure> high = H.structure(level, HIGH);
        ChunkPos chunk = chunkIn(H.area());
        BoundingBox b = startPiece(baseline(level, high, chunk));
        addRoadAcross(level, b, b.minY() - 30, H.NO_OWNER);
        H.check(H.isValid(H.generate(level, high, chunk)), "structure 30 blocks above a road was rejected");
        helper.succeed();
    }

    /**
     * Margin edge: a road whose nearest column is margin blocks away conflicts, one more doesn't.
     *
     * @param helper the test's helper
     */
    @GameTest(template = HarnessSetup.TEMPLATE)
    public static void goalARoadMarginEdges(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Holder<Structure> high = H.structure(level, HIGH);

        ChunkPos near = chunkIn(H.area());
        BoundingBox nb = startPiece(baseline(level, high, near));
        int nearZ = nb.maxZ() + H.MARGIN + 1; // nearest road column at maxZ + MARGIN
        RoadShardStorage.addRoad(level, H.roadAlongX(evenFloor(nb.minX() - 24), nb.maxX() + 24, nearZ, nb.minY(),
                H.NO_OWNER, H.NO_OWNER));
        H.check(!H.isValid(H.generate(level, high, near)), "road %d blocks from the structure (= margin) was ignored",
                H.MARGIN);

        ChunkPos far = chunkIn(H.area());
        BoundingBox fb = startPiece(baseline(level, high, far));
        int farZ = fb.maxZ() + H.MARGIN + 2; // nearest road column at maxZ + MARGIN + 1
        RoadShardStorage.addRoad(level, H.roadAlongX(evenFloor(fb.minX() - 24), fb.maxX() + 24, farZ, fb.minY(),
                H.NO_OWNER, H.NO_OWNER));
        H.check(H.isValid(H.generate(level, high, far)), "road %d blocks from the structure (> margin) rejected it",
                H.MARGIN + 1);
        helper.succeed();
    }

    /**
     * A road's own destination structure may sit on it.
     *
     * @param helper the test's helper
     */
    @GameTest(template = HarnessSetup.TEMPLATE)
    public static void goalADestinationStructureKeepsItsRoad(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Holder<Structure> high = H.structure(level, HIGH);
        ChunkPos chunk = chunkIn(H.area());
        BoundingBox b = startPiece(baseline(level, high, chunk));
        addRoadAcross(level, b, b.minY(), H.ownerAtChunk(chunk));
        H.check(H.isValid(H.generate(level, high, chunk)), "a road's own destination structure was rejected");
        helper.succeed();
    }

    /**
     * A structure listed in structuresAllowedOnRoad generates on a road.
     *
     * @param helper the test's helper
     */
    @GameTest(template = HarnessSetup.TEMPLATE)
    public static void goalAAllowedStructureIgnoresRoads(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Holder<Structure> high = H.structure(level, HIGH);
        ChunkPos chunk = chunkIn(H.area());
        BoundingBox b = startPiece(baseline(level, high, chunk));
        addRoadAcross(level, b, b.minY(), H.NO_OWNER);
        H.setAllowedOnRoad(List.of(HIGH));
        try {
            H.check(H.isValid(H.generate(level, high, chunk)), "a structuresAllowedOnRoad structure was rejected");
        } finally {
            H.setAllowedOnRoad(List.of());
        }
        helper.succeed();
    }

    /**
     * Desert pyramid boxes say y = 64 at generate time. Vanilla moves them to the ground later, so they avoid
     * a road at any height.
     *
     * @param helper the test's helper
     */
    @GameTest(template = HarnessSetup.TEMPLATE)
    public static void goalAPlaceholderHeightStructureAvoidsRoadAtAnyHeight(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Holder<Structure> pyramid = H.structure(level, "minecraft:desert_pyramid");
        int clear = RoadWeaverAccessor.roadClearHeight();
        for (int attempt = 0; attempt < 24; attempt++) {
            ChunkPos chunk = chunkIn(H.area());
            StructureStart before = H.generate(level, pyramid, chunk);
            if (!H.isValid(before)) {
                continue; // vanilla refuses pyramids on ground below sea level
            }
            BoundingBox b = before.getBoundingBox();
            int roadY = H.surfaceY(level, b.getCenter().getX(), b.getCenter().getZ()) - 1;
            if (roadY >= b.minY() - 1 && roadY <= b.maxY() + clear) {
                /*
                    The ground here overlaps the placeholder box (y 64..78), which would prove nothing:
                    put the road where the box says "no overlap" (as the ground does on a hill).
                 */
                roadY = b.maxY() + clear + 20;
            }
            addRoadAcross(level, b, roadY, H.NO_OWNER);
            H.check(!H.isValid(H.generate(level, pyramid, chunk)),
                    "desert pyramid (placeholder box %s) generated on a road at y %d", b, roadY);
            helper.succeed();
            return;
        }
        H.check(false, "harness: no desert pyramid generated in 24 areas");
    }

    /**
     * Buried treasure (always underground) isn't rejected by a road.
     *
     * @param helper the test's helper
     */
    @GameTest(template = HarnessSetup.TEMPLATE)
    public static void goalABuriedTreasureIgnoresRoads(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Holder<Structure> treasure = H.structure(level, "minecraft:buried_treasure");
        ChunkPos chunk = chunkIn(H.area());
        BoundingBox b = baseline(level, treasure, chunk).getBoundingBox();
        addRoadAcross(level, b, b.minY(), H.NO_OWNER);
        H.check(H.isValid(H.generate(level, treasure, chunk)), "buried treasure was rejected by a road");
        helper.succeed();
    }

    /**
     * Village pieces on the road are trimmed during assembly. The village still generates.
     *
     * @param helper the test's helper
     */
    @GameTest(template = HarnessSetup.TEMPLATE)
    public static void goalAJigsawPiecesAreTrimmedAroundRoad(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Holder<Structure> village = H.structure(level, "minecraft:village_plains");
        int clear = RoadWeaverAccessor.roadClearHeight();
        for (int attempt = 0; attempt < 8; attempt++) {
            ChunkPos chunk = chunkIn(H.area());
            StructureStart before = H.generate(level, village, chunk);
            if (!H.isValid(before) || before.getPieces().size() < 3) {
                continue;
            }
            List<StructurePiece> pieces = before.getPieces();
            BoundingBox startBox = pieces.get(0).getBoundingBox();
            for (int i = 1; i < pieces.size(); i++) {
                BoundingBox p = pieces.get(i).getBoundingBox();
                int z = (p.minZ() + p.maxZ()) / 2;
                int x0 = evenFloor(p.minX() - 6);
                int x1 = x0 + ((p.maxX() + 6 - x0) / 2) * 2;
                int roadY = H.surfaceY(level, (x0 + x1) / 2, z) - 1;
                if (onRoad(startBox, x0, x1, z, roadY, clear) || !onRoad(p, x0, x1, z, roadY, clear)) {
                    continue; // the start piece is never trimmed, and this piece must really sit on the road
                }
                RoadShardStorage.addRoad(level, H.roadAlongX(x0, x1, z, roadY, H.NO_OWNER, H.NO_OWNER));
                StructureStart after = H.generate(level, village, chunk);
                H.check(H.isValid(after), "village rejected although its start piece is off the road");
                for (StructurePiece piece : after.getPieces()) {
                    H.check(!onRoad(piece.getBoundingBox(), x0, x1, z, roadY, clear),
                            "village piece %s still on the road (x %d..%d, z %d, y %d)", piece.getBoundingBox(), x0, x1, z, roadY);
                }
                H.log("jigsaw: {} pieces without the road, {} with it", pieces.size(), after.getPieces().size());
                helper.succeed();
                return;
            }
        }
        H.check(false, "harness: found no village with a non-start piece clear of its start piece");
    }

    /**
     * A road leading to a village (its destination) no longer lets the village's houses
     * sit on it. They are trimmed like for any road, and the village still generates.
     *
     * @param helper the test's helper
     */
    @GameTest(template = HarnessSetup.TEMPLATE)
    public static void goalAVillageHousesAvoidTheirOwnRoad(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Holder<Structure> village = H.structure(level, "minecraft:village_plains");
        int clear = RoadWeaverAccessor.roadClearHeight();
        for (int attempt = 0; attempt < 8; attempt++) {
            ChunkPos chunk = chunkIn(H.area());
            StructureStart before = H.generate(level, village, chunk);
            if (!H.isValid(before) || before.getPieces().size() < 3) {
                continue;
            }
            List<StructurePiece> pieces = before.getPieces();
            BoundingBox startBox = pieces.get(0).getBoundingBox();
            for (int i = 1; i < pieces.size(); i++) {
                BoundingBox p = pieces.get(i).getBoundingBox();
                int z = (p.minZ() + p.maxZ()) / 2;
                int x0 = evenFloor(p.minX() - 6);
                int x1 = x0 + ((p.maxX() + 6 - x0) / 2) * 2;
                int roadY = H.surfaceY(level, (x0 + x1) / 2, z) - 1;
                if (onRoad(startBox, x0, x1, z, roadY, clear) || !onRoad(p, x0, x1, z, roadY, clear)) {
                    continue;
                }
                // The road leads to this village: its owner is the village's locate position.
                RoadShardStorage.addRoad(level, H.roadAlongX(x0, x1, z, roadY, H.ownerAtChunk(chunk), H.NO_OWNER));
                StructureStart after = H.generate(level, village, chunk);
                H.check(H.isValid(after), "the road's destination village was rejected");
                for (StructurePiece piece : after.getPieces()) {
                    H.check(!onRoad(piece.getBoundingBox(), x0, x1, z, roadY, clear),
                            "village piece %s sits on its own village's road (x %d..%d, z %d, y %d)",
                            piece.getBoundingBox(), x0, x1, z, roadY);
                }
                helper.succeed();
                return;
            }
        }
        H.check(false, "harness: found no village with a non-start piece clear of its start piece");
    }

    // ---- Keeping roads off structures: recording the structures that generated ----

    /**
     * The real worldgen commit point (ChunkGenerator.tryGenerateStructure) records the structure.
     *
     * @param helper the test's helper
     * @throws Exception when tryGenerateStructure can't be called
     */
    @GameTest(template = HarnessSetup.TEMPLATE)
    public static void goalBWorldgenCommitRecordsStructureChunks(GameTestHelper helper) throws Exception {
        ServerLevel level = helper.getLevel();
        Holder<Structure> high = H.structure(level, HIGH);
        ChunkPos chunk = chunkIn(H.area());
        H.check(tryGenerateStructure(level, high, chunk), "tryGenerateStructure did not commit the structure");
        StructureStart start = level.getChunk(chunk.x, chunk.z).getStartForStructure(high.value());
        H.check(H.isValid(start), "no committed start in the chunk");
        /*
            Expected: every piece split per chunk into its rectangle there, with its own Y range
            (identical rectangles merge their ranges). The structure reaches y >= SURFACE_MIN_Y.
         */
        Map<Long, Long> expected = new HashMap<>();
        for (StructurePiece piece : start.getPieces()) {
            BoundingBox p = piece.getBoundingBox();
            for (int cx = p.minX() >> 4; cx <= p.maxX() >> 4; cx++) {
                for (int cz = p.minZ() >> 4; cz <= p.maxZ() >> 4; cz++) {
                    long key = StructureChunkRecorder.packFootprint(cx, cz, Math.max(p.minX(), cx << 4) & 15,
                            Math.max(p.minZ(), cz << 4) & 15, Math.min(p.maxX(), (cx << 4) + 15) & 15,
                            Math.min(p.maxZ(), (cz << 4) + 15) & 15);
                    expected.merge(key, StructureChunkRecorder.packRange(p.minY(), p.maxY()), RoadWeaverHarness::unionRange);
                }
            }
        }
        // Footprints of this structure in every region it covers.
        Map<Long, Long> recorded = new HashMap<>();
        Set<Long> regions = new HashSet<>();
        for (long key : expected.keySet()) {
            regions.add(CoordinateKeys.regionOfChunk(StructureChunkRecorder.chunkXOf(key), StructureChunkRecorder.chunkZOf(key)));
        }
        for (long region : regions) {
            for (long[] e : H.recordedFootprintsOf(chunk, new ChunkPos(CoordinateKeys.unpackA(region) << CoordinateKeys.REGION_CHUNK_SHIFT,
                    CoordinateKeys.unpackB(region) << CoordinateKeys.REGION_CHUNK_SHIFT))) {
                recorded.merge(e[0], StructureChunkRecorder.packRange((int) e[1], (int) e[2]), RoadWeaverHarness::unionRange);
            }
        }
        H.check(!recorded.isEmpty(), "committed structure was not recorded");
        H.check(recorded.equals(expected), "recorded %d footprints, expected %d (or ranges differ)", recorded.size(),
                expected.size());
        H.log("recording: {} pieces, {} footprints recorded", start.getPieces().size(), recorded.size());
        helper.succeed();
    }

    /**
     * A structure fully below SURFACE_MIN_Y isn't recorded.
     *
     * @param helper the test's helper
     * @throws Exception when tryGenerateStructure can't be called
     */
    @GameTest(template = HarnessSetup.TEMPLATE)
    public static void goalBUndergroundStructureIsNotRecorded(GameTestHelper helper) throws Exception {
        ServerLevel level = helper.getLevel();
        ChunkPos chunk = chunkIn(H.area());
        H.check(tryGenerateStructure(level, H.structure(level, LOW), chunk), "tryGenerateStructure did not commit");
        H.check(H.recordedFootprintsOf(chunk, chunk).isEmpty(), "a structure below y = %d was recorded",
                StructureChunkRecorder.SURFACE_MIN_Y);
        helper.succeed();
    }

    /**
     * A structure listed in structuresAllowedOnRoad isn't recorded.
     *
     * @param helper the test's helper
     * @throws Exception when tryGenerateStructure can't be called
     */
    @GameTest(template = HarnessSetup.TEMPLATE)
    public static void goalBAllowedStructureIsNotRecorded(GameTestHelper helper) throws Exception {
        ServerLevel level = helper.getLevel();
        ChunkPos chunk = chunkIn(H.area());
        H.setAllowedOnRoad(List.of(HIGH));
        try {
            H.check(tryGenerateStructure(level, H.structure(level, HIGH), chunk), "tryGenerateStructure did not commit");
        } finally {
            H.setAllowedOnRoad(List.of());
        }
        H.check(H.recordedFootprintsOf(chunk, chunk).isEmpty(), "a structuresAllowedOnRoad structure was recorded");
        helper.succeed();
    }

    /**
     * Buried treasure's placeholder box (y = 90) must not turn it into a surface obstacle. Recorder
     * called directly: worldgen's real biome check only commits buried treasure on beaches.
     *
     * @param helper the test's helper
     */
    @GameTest(template = HarnessSetup.TEMPLATE)
    public static void goalBBuriedTreasureIsNotRecorded(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Holder<Structure> treasure = H.structure(level, "minecraft:buried_treasure");
        ChunkPos chunk = chunkIn(H.area());
        StructureStart start = baseline(level, treasure, chunk);
        H.check(start.getBoundingBox().maxY() >= StructureChunkRecorder.SURFACE_MIN_Y,
                "harness: buried treasure's box %s isn't the y = 90 placeholder", start.getBoundingBox());
        ChunkAccess access = level.getChunk(chunk.x, chunk.z);
        access.setStartForStructure(treasure.value(), start);
        StructureChunkRecorder.onStructureCommitted(access, chunk, treasure.value(), treasure.unwrapKey().get().location(),
                level.getChunkSource().getGenerator(), level.getChunkSource().randomState());
        H.check(H.recordedFootprintsOf(chunk, chunk).isEmpty(), "buried treasure was recorded as a surface structure");
        helper.succeed();
    }

    /**
     * Entries survive a flush, and a new store (a restart) finds them from the folder listing.
     *
     * @param helper the test's helper
     */
    @GameTest(template = HarnessSetup.TEMPLATE)
    public static void goalBStoreSurvivesFlushAndReload(GameTestHelper helper) {
        Path dir = RoadLevel.dataDir(helper.getLevel()).resolve("harness_store_" + System.nanoTime());
        StructureChunkStorage first = new StructureChunkStorage(dir, "harness");
        long region = CoordinateKeys.pack(-3, 7);
        first.append(region, 11L, 22L, -5, 70);
        first.append(region, 11L, 22L, -5, 70); // duplicate: written once
        first.append(region, 33L, 44L, 64, 99);
        first.flush();
        first.append(region, 55L, 66L, 1, 2); // pending only
        H.check(Arrays.equals(sorted(first.read(region)), sorted(new long[]{11, 22, -5, 70, 33, 44, 64, 99, 55, 66, 1, 2})),
                "read after flush: %s", Arrays.toString(first.read(region)));
        StructureChunkStorage second = new StructureChunkStorage(dir, "harness");
        H.check(second.hasRegion(region) && second.hasAnyRegion(), "reloaded store doesn't know the region");
        H.check(Arrays.equals(sorted(second.read(region)), sorted(new long[]{11, 22, -5, 70, 33, 44, 64, 99})),
                "reloaded entries: %s", Arrays.toString(second.read(region)));
        helper.succeed();
    }

    // ---- Keeping roads off structures: roads route around recorded structures ----

    /**
     * RoadWeaver's own path search, through the addon's findRawPath wrapper, detours around a structure.
     *
     * @param helper the test's helper
     */
    @GameTest(template = HarnessSetup.TEMPLATE)
    public static void goalBRoadSearchDetoursAroundRecordedStructure(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        /*
            On land only: RoadWeaver straightens every bridge stretch over water after the search
            (PathPostProcessor.straightenBridgeRuns), obstacles or not.
         */
        TerrainSamplingCache cache = new TerrainSamplingCache();
        BlockPos a = dryRoute(level, cache, 180, 620, 1000);
        int z = a.getZ() + 1000;
        BlockPos from = new BlockPos(a.getX() + 200, cache.height(level, a.getX() + 200, z), z);
        BlockPos to = new BlockPos(a.getX() + 600, cache.height(level, a.getX() + 600, z), z);
        RoadGenerationConfig cfg = RoadGenerationConfig.from(ConfigService.get());

        List<RoadSegmentPlacement> before = RoadPathCalculator.calculateAStarRoadPathDetailed(from, to, H.WIDTH, level,
                MAX_STEPS, cache, cfg).segments();
        H.check(before != null && before.size() >= 5, "harness: RoadWeaver found no path on open terrain");

        /*
            The wall stands where the open-terrain path really crosses x + 400 (terrain can bend it far
            off the straight line), centred on that crossing.
         */
        int wallChunkX = (a.getX() + 400) >> 4;
        int crossZ = z;
        int best = Integer.MAX_VALUE;
        for (RoadSegmentPlacement s : before) {
            int d = Math.abs(s.middlePos().getX() - (a.getX() + 400));
            if (d < best) {
                best = d;
                crossZ = s.middlePos().getZ();
            }
        }
        z = crossZ;
        H.recordStructure(new ChunkPos(wallChunkX, z >> 4), H.wallAlongZ(wallChunkX, (z - 64) >> 4, (z + 64) >> 4),
                level.getMinBuildHeight(), level.getMaxBuildHeight()); // full height: only the detour is tested here
        RoadObstacles obstacles = RoadObstacles.forRoute(from.getX(), from.getZ(), to.getX(), to.getZ(),
                RoadWeaverAccessor.configuredRoadMargin());
        H.check(obstacles != null, "no obstacles found for the route");
        H.check(blockedMiddles(before, obstacles) > 0, "harness: the open-terrain path never crosses the structure");

        List<RoadSegmentPlacement> after = RoadPathCalculator.calculateAStarRoadPathDetailed(from, to, H.WIDTH, level,
                MAX_STEPS, new TerrainSamplingCache(), cfg).segments();
        H.check(after != null && after.size() >= 5, "no path found around the structure");
        int crossing = blockedMiddles(after, obstacles);
        if (crossing > 0) {
            List<String> where = new ArrayList<>();
            for (RoadSegmentPlacement s : after) {
                if (blockedAt(obstacles, s.middlePos()) && where.size() < 6) {
                    where.add(s.middlePos().toShortString());
                }
            }
            H.check(false, "rerouted path still crosses the structure at %d of %d segments (wall x %d..%d, z %d..%d, first: %s)",
                    crossing, after.size(), wallChunkX << 4, (wallChunkX << 4) + 15, ((z - 64) >> 4) << 4,
                    (((z + 64) >> 4) << 4) + 15, where);
        }
        BlockPos end = after.get(after.size() - 1).middlePos();
        H.check(Math.abs(end.getX() - to.getX()) <= 16 && Math.abs(end.getZ() - to.getZ()) <= 16,
                "rerouted path ends at %s, not near %s", end, to);
        H.log("reroute: {} segments straight, {} around the structure", before.size(), after.size());
        helper.succeed();
    }

    /**
     * A cached coarse path through a recorded structure is dropped, one around it is kept.
     *
     * @param helper the test's helper
     */
    @GameTest(template = HarnessSetup.TEMPLATE)
    public static void goalBCachedCoarsePathCrossingStructureIsDropped(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos a = H.area();
        int z = a.getZ() + 1000;
        int wallChunkX = (a.getX() + 400) >> 4;
        H.recordStructure(new ChunkPos(wallChunkX, z >> 4), H.wallAlongZ(wallChunkX, (z - 64) >> 4, (z + 64) >> 4), 70, 100);
        BlockPos from = new BlockPos(a.getX() + 200, 70, z);
        BlockPos to = new BlockPos(a.getX() + 600, 70, z);
        StructureConnection connection = new StructureConnection(from, to);
        List<BlockPos> straight = List.of(from, to);
        List<BlockPos> around = List.of(from, new BlockPos(from.getX(), 70, z + 200), new BlockPos(to.getX(), 70, z + 200), to);
        H.check(RoadRerouter.dropIfBlocked(level, connection, straight) == null, "a cached path through the structure was kept");
        H.check(RoadRerouter.dropIfBlocked(level, connection, around) == around, "a cached path around the structure was dropped");
        helper.succeed();
    }

    /**
     * End to end through Road.generateRoad: both endpoints sit inside recorded destination
     * "villages" (3x3 chunks around each locate position). No saved road piece enters either village,
     * and the road still reaches both of their edges.
     *
     * @param helper the test's helper
     */
    @GameTest(template = HarnessSetup.TEMPLATE)
    public static void destinationRoadEndsAtVillageEdge(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        TerrainSamplingCache cache = new TerrainSamplingCache();
        BlockPos a = dryRoute(level, cache, 150, 650, 1000);
        int z = a.getZ() + 1000;
        BlockPos from = new BlockPos(a.getX() + 200, cache.height(level, a.getX() + 200, z), z);
        BlockPos to = new BlockPos(a.getX() + 600, cache.height(level, a.getX() + 600, z), z);
        int[] fromVillage = recordVillageAround(level, from);
        int[] toVillage = recordVillageAround(level, to);

        RoadData road = new Road(level, new StructureConnection(from, to), new PathFeatureConfig()).generateRoad(MAX_STEPS);
        H.check(road != null, "generateRoad returned no road");
        List<RoadData> saved = RoadShardStorage.queryRect(level, from.getX() - 200, z - 400, to.getX() + 200, z + 400);
        H.check(!saved.isEmpty(), "no road saved");
        int nearFrom = Integer.MAX_VALUE;
        int nearTo = Integer.MAX_VALUE;
        for (RoadData r : saved) {
            for (RoadSegmentPlacement s : r.roadSegmentList()) {
                for (BlockPos p : columns(s)) {
                    H.check(!inside(fromVillage, p) && !inside(toVillage, p),
                            "road column %s inside a destination village (%s / %s)", p.toShortString(),
                            Arrays.toString(fromVillage), Arrays.toString(toVillage));
                    nearFrom = Math.min(nearFrom, distance(fromVillage, p));
                    nearTo = Math.min(nearTo, distance(toVillage, p));
                }
            }
        }
        H.check(nearFrom <= 64 && nearTo <= 64, "the road doesn't reach the villages' edges (%d / %d blocks away)",
                nearFrom, nearTo);
        H.log("destination edges: road ends {} / {} blocks from the villages", nearFrom, nearTo);
        helper.succeed();
    }

    /**
     * The search still works with both endpoints inside their destinations: it starts and ends at
     * their edges, so it still detours around a third structure in between (without the endpoint move
     * the search fails and falls back to the straight path through the wall). A control search with
     * the wall alone first proves the terrain allows a detour at all, otherwise another area is tried.
     *
     * @param helper the test's helper
     */
    @GameTest(template = HarnessSetup.TEMPLATE, timeoutTicks = 400)
    public static void destinationEndpointsStillDetourAroundOtherStructures(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        RoadGenerationConfig cfg = RoadGenerationConfig.from(ConfigService.get());
        int m = H.MARGIN;
        for (int attempt = 0; attempt < 6; attempt++) {
            TerrainSamplingCache cache = new TerrainSamplingCache();
            BlockPos a = dryRoute(level, cache, 150, 650, 1000);
            int z = a.getZ() + 1000;
            BlockPos from = new BlockPos(a.getX() + 200, cache.height(level, a.getX() + 200, z), z);
            BlockPos to = new BlockPos(a.getX() + 600, cache.height(level, a.getX() + 600, z), z);
            List<RoadSegmentPlacement> open = RoadPathCalculator.calculateAStarRoadPathDetailed(from, to, H.WIDTH, level,
                    MAX_STEPS, cache, cfg).segments();
            if (open == null || open.size() < 5) {
                continue;
            }
            int crossZ = z;
            int best = Integer.MAX_VALUE;
            for (RoadSegmentPlacement s : open) {
                int d = Math.abs(s.middlePos().getX() - (a.getX() + 400));
                if (d < best) {
                    best = d;
                    crossZ = s.middlePos().getZ();
                }
            }
            int wallChunkX = (a.getX() + 400) >> 4;
            H.recordStructure(new ChunkPos(wallChunkX, crossZ >> 4), H.wallAlongZ(wallChunkX, (crossZ - 64) >> 4, (crossZ + 64) >> 4),
                    level.getMinBuildHeight(), level.getMaxBuildHeight());
            int[] wall = {(wallChunkX << 4) - m, (((crossZ - 64) >> 4) << 4) - m, (wallChunkX << 4) + 15 + m,
                    (((crossZ + 64) >> 4) << 4) + 15 + m};
            // Control: the wall alone, endpoints free.
            if (crossesRect(RoadPathCalculator.calculateAStarRoadPathDetailed(from, to, H.WIDTH, level, MAX_STEPS,
                    new TerrainSamplingCache(), cfg).segments(), wall)) {
                H.log("destination detour: no detour around the wall on this terrain, trying another area");
                continue;
            }
            recordVillageAround(level, from);
            recordVillageAround(level, to);
            List<RoadSegmentPlacement> after = RoadPathCalculator.calculateAStarRoadPathDetailed(from, to, H.WIDTH, level,
                    MAX_STEPS, new TerrainSamplingCache(), cfg).segments();
            H.check(after != null && after.size() >= 5, "no path found");
            H.check(!crossesRect(after, wall), "with both endpoints inside their destinations the search fell back "
                    + "and crosses the wall (the same search detoured with the endpoints free)");
            helper.succeed();
            return;
        }
        H.check(false, "harness: no area where the terrain allows a detour around the wall in 6 tries");
    }

    /**
     * Tells whether a path's segment middles enter a rectangle.
     *
     * @param segments the path, may be null (counts as crossing)
     * @param rect     {minX, minZ, maxX, maxZ} (blocks, inclusive)
     * @return true if a middle lies inside, or the path is null
     */
    private static boolean crossesRect(List<RoadSegmentPlacement> segments, int[] rect) {
        if (segments == null) {
            return true;
        }
        for (RoadSegmentPlacement s : segments) {
            if (s.middlePos() != null && inside(rect, s.middlePos())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Records a 3x3-chunk "village" (full height) around a locate position.
     *
     * @param level  the level, for its height limits
     * @param locate the village's locate position, its chunk is the start chunk
     * @return the village's block rectangle {minX, minZ, maxX, maxZ}
     */
    private static int[] recordVillageAround(ServerLevel level, BlockPos locate) {
        ChunkPos c = new ChunkPos(locate);
        List<ChunkPos> chunks = new ArrayList<>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                chunks.add(new ChunkPos(c.x + dx, c.z + dz));
            }
        }
        H.recordStructure(c, chunks, level.getMinBuildHeight(), level.getMaxBuildHeight());
        return new int[]{(c.x - 1) << 4, (c.z - 1) << 4, ((c.x + 2) << 4) - 1, ((c.z + 2) << 4) - 1};
    }

    /**
     * Tells whether a column lies in a rectangle.
     *
     * @param rect {minX, minZ, maxX, maxZ} (blocks, inclusive)
     * @param p    the column
     * @return true if inside
     */
    private static boolean inside(int[] rect, BlockPos p) {
        return p.getX() >= rect[0] && p.getZ() >= rect[1] && p.getX() <= rect[2] && p.getZ() <= rect[3];
    }

    /**
     * Measures a column's distance to a rectangle.
     *
     * @param rect {minX, minZ, maxX, maxZ} (blocks, inclusive)
     * @param p    the column
     * @return the Chebyshev distance (blocks), 0 inside
     */
    private static int distance(int[] rect, BlockPos p) {
        int dx = Math.max(0, Math.max(rect[0] - p.getX(), p.getX() - rect[2]));
        int dz = Math.max(0, Math.max(rect[1] - p.getZ(), p.getZ() - rect[3]));
        return Math.max(dx, dz);
    }

    /**
     * Lists a segment's columns.
     *
     * @param s the segment
     * @return its middle and positions, a new list
     */
    private static List<BlockPos> columns(RoadSegmentPlacement s) {
        List<BlockPos> out = new ArrayList<>();
        if (s.middlePos() != null) {
            out.add(s.middlePos());
        }
        if (s.positions() != null) {
            out.addAll(s.positions());
        }
        return out;
    }

    // ---- Missing roads: pieces of trimmed roads must not become RoadWeaver structures ----

    /**
     * RoadWeaver's LegacyRoadDataRepairService (run when it plans around a player, and on server start)
     * takes every road without an owner pair for legacy data: it registers an "unknown" structure at both
     * of its ends, and its planner then connects those to real structures with new roads, planned after
     * the land there generated (so never paved, though on RoadWeaver's map). A piece RWSA splits off a
     * trimmed road therefore keeps the connection's owner pair, and the repair adds nothing at its ends.
     *
     * @param helper the test's helper
     */
    @GameTest(template = HarnessSetup.TEMPLATE)
    public static void missingRoadsSplitPieceBecomesRoadWeaverStructure(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos a = H.area();
        int z = a.getZ() + 2000;
        TerrainSamplingCache cache = new TerrainSamplingCache();
        BlockPos from = new BlockPos(a.getX() + 500, cache.height(level, a.getX() + 500, z), z);
        BlockPos to = new BlockPos(a.getX() + 900, cache.height(level, a.getX() + 900, z), z);
        ChunkPos toChunk = new ChunkPos(to);
        List<ChunkPos> ring = new ArrayList<>();
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) == 4) {
                    ring.add(new ChunkPos(toChunk.x + dx, toChunk.z + dz));
                }
            }
        }
        H.recordStructure(new ChunkPos(toChunk.x + 4, toChunk.z + 4), ring, level.getMinBuildHeight(), level.getMaxBuildHeight());
        new Road(level, new StructureConnection(from, to), new PathFeatureConfig()).generateRoad(MAX_STEPS);
        RoadData piece = innerPiece(level, from, to);
        H.check(piece != null, "harness: RWSA split no piece off the road (no trim happened)");
        H.check(piece.ownerA2dKey() == H.pos2dKey(from) && piece.ownerB2dKey() == H.pos2dKey(to),
                "the split piece doesn't carry the connection's owners (it would look like legacy data to RoadWeaver)");
        BlockPos start = piece.roadSegmentList().get(0).middlePos();

        LegacyRoadDataRepairService.repairRoadMetadataInRect(level, from.getX() - 64, z - 400, to.getX() + 64, z + 400);
        List<StructureInfo> nodes = StructureFileStorage.queryRect(level, start.getX() - 2, start.getZ() - 2,
                start.getX() + 2, start.getZ() + 2, new int[0]);
        H.check(nodes == null || nodes.isEmpty(), "RoadWeaver registered structure(s) %s at the split piece's free end %s: "
                        + "its planner will plan new roads to that spot, later than the chunks around it generate",
                nodes == null ? "-" : ids(nodes), start.toShortString());
        helper.succeed();
    }

    /**
     * Finds the piece of a road trimmed at a ring of structure chunks around its end that lies inside the
     * ring: a stored road reaching near {@code to} that doesn't start at {@code from} (the first part does,
     * and ends outside the ring).
     *
     * @param level the level
     * @param from  the road's start
     * @param to    the road's end, inside the ring
     * @return the inner piece, null if none was saved
     */
    private static RoadData innerPiece(ServerLevel level, BlockPos from, BlockPos to) {
        RoadData piece = null;
        for (RoadData stored : RoadShardStorage.queryRect(level, to.getX() - 40, to.getZ() - 40, to.getX() + 40, to.getZ() + 40)) {
            BlockPos first = stored.roadSegmentList().get(0).middlePos();
            if (Math.abs(first.getX() - from.getX()) > 100 || Math.abs(first.getZ() - from.getZ()) > 100) {
                piece = stored;
            }
        }
        return piece;
    }

    // ---- Fallback: tunnel under, or trim before, structures the road still crosses ----

    /**
     * A blocked stretch on the road's own destination is trimmed, never dug (the tunnel would surface inside it).
     * The same structure that isn't a destination is dug (tunnelPlannerDigsUnderShallowStructure).
     *
     * @param helper the test's helper
     */
    @GameTest(template = HarnessSetup.TEMPLATE)
    public static void tunnelNotDugUnderDestination(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos area = H.area();
        int x0 = area.getX() + 64;
        int z = area.getZ() + 1000;
        List<RoadSegmentPlacement> segments = H.segmentsAlongX(x0, 80, 4, z, 100);
        List<Integer> targetY = new ArrayList<>();
        segments.forEach(s -> targetY.add(100));
        BlockPos to = segments.get(segments.size() - 1).middlePos();
        StructureConnection connection = new StructureConnection(segments.get(0).middlePos(), to);
        // Shallow (diggable) wall at x0 + 160, belonging to the structure whose locate position is `to`.
        int wallChunkX = (x0 + 160) >> 4;
        H.recordStructure(new ChunkPos(to), H.wallAlongZ(wallChunkX, (z - 48) >> 4, (z + 48) >> 4), 98, 130);
        try {
            RoadTunnelPlanner.plan(level, connection, segments, List.of(), targetY, RoadGenerationConfig.from(ConfigService.get()));
            TunnelPlan plan = RoadTunnelPlanner.planFor(segments);
            H.check(plan != null, "no plan for a road crossing its destination");
            H.check(plan.tunnelBoxes.isEmpty(), "a tunnel was planned under the road's own destination");
            H.check(plan.parts.size() == 2, "expected the blocked stretch on the destination trimmed (2 parts), got %d", plan.parts.size());
        } finally {
            RoadTunnelPlanner.clearPlan();
        }
        helper.succeed();
    }

    /**
     * A road lying entirely on its own destination (both ends inside one recorded village) isn't
     * saved, so nothing is paved through the village. generateRoad still returns normally.
     *
     * @param helper the test's helper
     */
    @GameTest(template = HarnessSetup.TEMPLATE)
    public static void roadEntirelyOnItsDestinationIsNotBuilt(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        TerrainSamplingCache cache = new TerrainSamplingCache();
        BlockPos a = dryRoute(level, cache, 1000, 1020, 1000);
        int z = a.getZ() + 1000;
        // x 1000 is local x 8 of its chunk: the 3x3-chunk village spans x 976..1023 around it.
        BlockPos from = new BlockPos(a.getX() + 1000, cache.height(level, a.getX() + 1000, z), z);
        BlockPos to = new BlockPos(a.getX() + 1020, cache.height(level, a.getX() + 1020, z), z);
        int[] village = recordVillageAround(level, from);
        H.check(inside(village, to), "harness: both ends must lie in the village");
        RoadData road = new Road(level, new StructureConnection(from, to), new PathFeatureConfig()).generateRoad(MAX_STEPS);
        H.check(road != null, "generateRoad returned no road");
        List<RoadData> saved = RoadShardStorage.queryRect(level, village[0] - 32, village[1] - 32, village[2] + 32,
                village[3] + 32);
        H.check(saved.isEmpty(), "%d road(s) saved inside their own destination", saved.size());
        helper.succeed();
    }

    // ---- Road snap junctions (cut-off roads) ----

    /**
     * Cut-off roads: a side road ending beside a longer road to the same destination is truncated by
     * RoadWeaver's RoadSnapService at that shared end. Its paving must still reach the road it joins
     * (RoadWeaver's own junction segments, restored by RoadSnapServiceMixin), with no hole on the way.
     *
     * @param helper the test's helper
     */
    @GameTest(template = HarnessSetup.TEMPLATE)
    public static void snapJunctionIsPaved(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos a = H.area();
        int x0 = a.getX() + 200;
        int z = a.getZ() + 1000;
        int y = 100;
        long dest = H.pos2dKey(new BlockPos(x0 + 200, y, z));
        long sideStart = H.pos2dKey(new BlockPos(x0 + 60, y, z + 60));
        RoadData main = H.roadAlongX(x0, x0 + 200, z, y, H.pos2dKey(new BlockPos(x0, y, z)), dest);
        RoadData side = roadThrough(sideRoadMiddles(x0 + 60, z, y), sideStart, dest);
        RoadShardStorage.addRoad(level, main);
        RoadShardStorage.addRoad(level, side);
        RoadSnapService.snapAllRoads(level, x0, z, x0 + 200, z + 60);

        RoadData snapped = storedRoadOwnedBy(level, sideStart, x0 - 30, z - 30, x0 + 230, z + 90);
        H.check(snapped != null, "the side road is gone from storage after snapping");
        List<RoadSegmentPlacement> segments = snapped.roadSegmentList();
        H.check(isAnchor(segments.get(segments.size() - 1)),
                "harness: RoadWeaver didn't truncate the side road at its shared end (%d of %d segments kept)",
                segments.size(), side.roadSegmentList().size());
        Set<Long> mainColumns = new HashSet<>();
        for (RoadSegmentPlacement s : main.roadSegmentList()) {
            for (BlockPos p : s.positions()) {
                mainColumns.add(CoordinateKeys.pack(p.getX(), p.getZ()));
            }
        }
        boolean joins = false;
        int nearest = Integer.MAX_VALUE;
        List<BlockPos> previous = null;
        for (RoadSegmentPlacement s : segments) {
            if (isAnchor(s)) {
                continue;
            }
            if (previous != null) {
                H.check(chebyshev(previous, s.positions()) <= 6, "hole in the side road before %s",
                        s.middlePos().toShortString());
            }
            for (BlockPos p : s.positions()) {
                joins |= touches(mainColumns, p.getX(), p.getZ());
                nearest = Math.min(nearest, Math.max(Math.abs(p.getZ() - z) - 1, 0));
            }
            previous = s.positions();
        }
        H.check(joins, "cut-off road: the side road's paving stops %d block(s) short of the road it joins", nearest);
        helper.succeed();
    }

    /**
     * Without a shared destination RoadWeaver snaps without truncating. The addon changes nothing there.
     *
     * @param helper the test's helper
     */
    @GameTest(template = HarnessSetup.TEMPLATE)
    public static void snapWithoutSharedOwnerUnchanged(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos a = H.area();
        int x0 = a.getX() + 200;
        int z = a.getZ() + 1000;
        int y = 100;
        long sideStart = H.pos2dKey(new BlockPos(x0 + 60, y, z + 60));
        RoadData main = H.roadAlongX(x0, x0 + 200, z, y, H.pos2dKey(new BlockPos(x0, y, z)),
                H.pos2dKey(new BlockPos(x0 + 200, y, z)));
        RoadData side = roadThrough(sideRoadMiddles(x0 + 60, z, y), sideStart, H.pos2dKey(new BlockPos(x0 + 900, y, z)));
        RoadShardStorage.addRoad(level, main);
        RoadShardStorage.addRoad(level, side);
        RoadSnapService.snapAllRoads(level, x0, z, x0 + 200, z + 60);

        RoadData snapped = storedRoadOwnedBy(level, sideStart, x0 - 30, z - 30, x0 + 230, z + 90);
        H.check(snapped != null, "the side road is gone from storage after snapping");
        H.check(snapped.roadSegmentList().size() == side.roadSegmentList().size(), "segment count changed: %d -> %d",
                side.roadSegmentList().size(), snapped.roadSegmentList().size());
        H.check(snapped.roadSegmentList().stream().noneMatch(RoadWeaverHarness::isAnchor),
                "a positionless segment appeared in a road that wasn't truncated");
        helper.succeed();
    }

    /**
     * The same junction through real worldgen (the 576,340 case): RoadWeaver's PathFeature never paves a
     * road's last ~7 segments and puts its end ("Welcome") sign 10 segments before its stored end. The
     * truncated side road must still be built into the main road, with no end sign at the junction, and
     * stay so when RoadWeaver snaps the area again.
     *
     * @param helper the test's helper
     */
    @GameTest(template = HarnessSetup.TEMPLATE, timeoutTicks = 200)
    public static void snapJunctionPavedInWorld(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos a = dryRoute(level, new TerrainSamplingCache(), 160, 420, 1000);
        int x0 = a.getX() + 200;
        int z = a.getZ() + 1000;
        List<BlockPos> mainMiddles = new ArrayList<>();
        for (int x = x0; x <= x0 + 200; x += 2) {
            mainMiddles.add(new BlockPos(x, H.surfaceY(level, x, z), z));
        }
        List<BlockPos> sideMiddles = new ArrayList<>();
        for (BlockPos p : sideRoadMiddles(x0 + 60, z, 0)) {
            sideMiddles.add(new BlockPos(p.getX(), H.surfaceY(level, p.getX(), p.getZ()), p.getZ()));
        }
        long dest = H.pos2dKey(new BlockPos(x0 + 200, 0, z));
        long sideStart = H.pos2dKey(sideMiddles.get(0));
        RoadData main = roadThrough(mainMiddles, H.pos2dKey(mainMiddles.get(0)), dest);
        RoadShardStorage.addRoad(level, main);
        RoadShardStorage.addRoad(level, roadThrough(sideMiddles, sideStart, dest));
        RoadSnapService.snapAllRoads(level, x0, z, x0 + 200, z + 60);
        int firstSize = storedRoadOwnedBy(level, sideStart, x0 - 30, z - 30, x0 + 230, z + 90).roadSegmentList().size();
        RoadSnapService.snapAllRoads(level, x0, z, x0 + 200, z + 60); // RoadWeaver snaps an area again later
        RoadData side = storedRoadOwnedBy(level, sideStart, x0 - 30, z - 30, x0 + 230, z + 90);
        H.check(side != null, "the side road is gone from storage after snapping");
        List<RoadSegmentPlacement> segments = side.roadSegmentList();
        H.check(JunctionRepair.isAnchor(segments.get(segments.size() - 1)), "harness: the side road wasn't truncated");
        H.check(segments.size() == firstSize, "a second snap changed the truncated road (%d -> %d segments)",
                firstSize, segments.size());

        Set<Long> mainColumns = new HashSet<>();
        for (RoadSegmentPlacement s : main.roadSegmentList()) {
            for (BlockPos p : s.positions()) {
                mainColumns.add(CoordinateKeys.pack(p.getX(), p.getZ()));
            }
        }
        // Junction segments: the side road's positioned segments that reach into the main road's columns.
        List<Integer> junction = new ArrayList<>();
        for (int i = 0; i < segments.size(); i++) {
            for (BlockPos p : segments.get(i).positions()) {
                if (touches(mainColumns, p.getX(), p.getZ())) {
                    junction.add(i);
                    break;
                }
            }
        }
        H.check(!junction.isEmpty(), "harness: no junction segment reaches the main road");

        Set<Long> chunks = new HashSet<>();
        for (RoadData road : List.of(main, side)) {
            for (RoadSegmentPlacement s : road.roadSegmentList()) {
                for (BlockPos p : s.positions()) {
                    chunks.add(ChunkPos.asLong(p.getX() >> 4, p.getZ() >> 4));
                }
            }
        }
        for (long c : chunks) {
            level.getChunk(ChunkPos.getX(c), ChunkPos.getZ(c));
        }

        Set<BlockState> materials = new HashSet<>(side.materials());
        List<String> unpaved = new ArrayList<>();
        for (int i : junction) {
            boolean paved = false;
            int y = side.targetY().get(i);
            for (BlockPos p : segments.get(i).positions()) {
                if (mainColumns.contains(CoordinateKeys.pack(p.getX(), p.getZ()))) {
                    continue; // the main road paves its own columns anyway
                }
                for (int dy = -3; dy <= 1 && !paved; dy++) {
                    paved = materials.contains(level.getBlockState(new BlockPos(p.getX(), y + dy, p.getZ())));
                }
            }
            if (!paved) {
                unpaved.add(i + " " + segments.get(i).middlePos().toShortString());
            }
        }
        H.check(unpaved.isEmpty(), "junction segment(s) not built into the main road: %s (of %d segments)", unpaved,
                segments.size());

        BlockPos joint = segments.get(junction.get(0)).middlePos();
        List<String> signs = new ArrayList<>();
        for (int x = joint.getX() - 16; x <= joint.getX() + 16; x++) {
            for (int zz = joint.getZ() - 16; zz <= joint.getZ() + 16; zz++) {
                for (int y = joint.getY() - 8; y <= joint.getY() + 8; y++) {
                    if (level.getBlockState(new BlockPos(x, y, zz)).getBlock() instanceof SignBlock) {
                        signs.add(x + "," + y + "," + zz);
                    }
                }
            }
        }
        H.check(signs.isEmpty(), "end-of-road sign(s) at the junction (the road doesn't end there): %s", signs);
        helper.succeed();
    }

    /**
     * The x 24, z 97 case: a side road on high ground, 19 blocks above a main road it runs within 12 blocks of,
     * both leading to the same destination. RoadWeaver's snap cuts the side road at their shared end and blends a
     * junction onto the main road. After worldgen a walk must exist from the side road's first paved segment, down its own
     * route and through the junction, into the main road: over edge-adjacent paved or cleared columns (the cleared
     * ground between RoadWeaver's junction pieces), each passable from its
     * floor up to the higher of the two floors + 1. Both roads have one segment per block, as RoadWeaver's own roads
     * do, so their normal stretches are continuous.
     *
     * @param helper the test's helper
     */
    @GameTest(template = HarnessSetup.TEMPLATE, timeoutTicks = 300)
    public static void snapJunctionAcrossHeightIsConnected(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        AcrossHeight layout = acrossHeight(level);
        RoadData main = layout.main();
        int x0 = layout.x0();
        int z = layout.z();
        RoadSnapService.snapAllRoads(level, x0, z, x0 + 200, z + 60);

        RoadData side = storedRoadOwnedBy(level, layout.sideStart(), x0 - 30, z - 30, x0 + 230, z + 90);
        H.check(side != null, "the side road is gone from storage after snapping");
        List<RoadSegmentPlacement> segments = side.roadSegmentList();
        H.check(isAnchor(segments.get(segments.size() - 1)), "harness: RoadWeaver didn't cut the side road at its shared end");
        int[] ys = side.targetY().stream().mapToInt(Integer::intValue).toArray();
        int lastPaved = segments.size() - 1;
        while (lastPaved > 0 && isAnchor(segments.get(lastPaved))) {
            lastPaved--;
        }
        int from = 10; // PathFeature never paves a road's first ~9 segments
        List<String> junction = new ArrayList<>();
        for (int i = Math.max(from, lastPaved - 40); i < segments.size(); i++) {
            RoadSegmentPlacement s = segments.get(i);
            junction.add(i + " " + s.middlePos().toShortString() + " y " + ys[i] + " "
                    + (isAnchor(s) ? "no positions" : s.positions().size() + " col"));
        }

        Set<Long> chunks = new HashSet<>();
        for (RoadData road : List.of(main, side)) {
            for (RoadSegmentPlacement s : road.roadSegmentList()) {
                for (BlockPos p : s.positions()) {
                    for (int dx = -1; dx <= 1; dx++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            chunks.add(ChunkPos.asLong((p.getX() >> 4) + dx, (p.getZ() >> 4) + dz));
                        }
                    }
                }
            }
        }
        for (long c : chunks) {
            level.getChunk(ChunkPos.getX(c), ChunkPos.getZ(c));
        }

        /*
            Floors of the paved columns, computed as SegmentPaver does, then of the cleared ones between the
            junction pieces (TunnelCarver's). The main road keeps its own columns.
         */
        Map<Long, Integer> floors = new HashMap<>();
        floorsOf(segments, ys, from, lastPaved, floors);
        TunnelCarver.roadFloors(List.of(side), Integer.MIN_VALUE / 2, Integer.MIN_VALUE / 2, Integer.MAX_VALUE / 2,
                Integer.MAX_VALUE / 2).forEach(floors::putIfAbsent);
        int[] mainYs = main.targetY().stream().mapToInt(Integer::intValue).toArray();
        Set<Long> mainColumns = floorsOf(main.roadSegmentList(), mainYs, 0, mainYs.length - 1, floors);

        // Walk from the side road's own path over edge-adjacent paved columns.
        ArrayDeque<Long> queue = new ArrayDeque<>();
        Set<Long> reached = new HashSet<>();
        for (BlockPos p : segments.get(from).positions()) {
            long key = CoordinateKeys.pack(p.getX(), p.getZ());
            if (floors.containsKey(key) && reached.add(key)) {
                queue.add(key);
            }
        }
        List<String> blocked = new ArrayList<>();
        boolean joined = false;
        while (!queue.isEmpty() && !joined) {
            long key = queue.poll();
            int x = CoordinateKeys.unpackA(key);
            int zz = CoordinateKeys.unpackB(key);
            joined = mainColumns.contains(key);
            for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                long next = CoordinateKeys.pack(x + d[0], zz + d[1]);
                Integer nextFloor = floors.get(next);
                if (nextFloor == null || reached.contains(next)) {
                    continue;
                }
                int top = Math.max(floors.get(key), nextFloor) + 1;
                String wall = firstBlocking(level, x, zz, floors.get(key), top);
                wall = wall != null ? wall : firstBlocking(level, x + d[0], zz + d[1], nextFloor, top);
                if (wall == null) {
                    reached.add(next);
                    queue.add(next);
                } else if (blocked.size() < 8) {
                    blocked.add(wall);
                }
            }
        }
        if (!joined) {
            H.log("junction across height: {}", junction);
        }
        H.check(joined, "main road not reachable from the side road: %d column(s) reached, blocked at %s",
                reached.size(), blocked);
        helper.succeed();
    }

    /**
     * Road B's stretch 104..146 case: a side road passing 6 blocks beside a main road and 19 blocks above it, then
     * leaving again (no shared destination, so no cut). Inside the snapped stretch, past RoadWeaver's junction
     * segments at each end, the side road must be the main road: no column of its own beside it. The junction
     * segments must be RoadWeaver's own pieces kept off the main road: each paves exactly its own cross-section moved
     * as far as its middle, minus the main road's columns (one column when nothing is left). The pieces, the side road's own route and the main road must be joined through the
     * side road's paved and cleared columns. A second snap of the area must record no tunnel box twice.
     *
     * @param helper the test's helper
     */
    @GameTest(template = HarnessSetup.TEMPLATE)
    public static void snapStretchStaysOnMainRoad(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MiddleStretch layout = middleStretch(level);
        SnappedStretch snapped = snapMiddleStretch(level, layout);
        RoadData side = snapped.side();
        int runStart = snapped.runStart();
        int runEnd = snapped.runEnd();
        List<RoadSegmentPlacement> segments = side.roadSegmentList();
        int trans = SnapJunctionShaper.TRANSITION;
        List<String> beside = new ArrayList<>();
        int count = 0;
        for (int i = runStart + trans; i <= runEnd - trans; i++) {
            for (BlockPos p : segments.get(i).positions()) {
                if (Math.abs(p.getZ() - layout.z()) > H.WIDTH / 2) {
                    count++;
                    if (beside.size() < 8) {
                        beside.add(i + " " + p.toShortString());
                    }
                    break;
                }
            }
        }
        H.check(count == 0, "%d snapped segment(s) of stretch %d..%d pave a road of their own beside the main road "
                + "(only %d junction segments at each end may): %s", count, runStart, runEnd, trans, beside);
        List<RoadSegmentPlacement> before = layout.side().roadSegmentList();
        List<String> notPieces = new ArrayList<>();
        for (int i = runStart; i <= runEnd; i++) {
            if (i >= runStart + trans && i <= runEnd - trans) {
                continue;
            }
            BlockPos was = before.get(i).middlePos();
            BlockPos now = segments.get(i).middlePos();
            Set<Long> piece = new HashSet<>();
            for (BlockPos p : before.get(i).positions()) {
                piece.add(CoordinateKeys.pack(p.getX() + now.getX() - was.getX(), p.getZ() + now.getZ() - was.getZ()));
            }
            piece.removeIf(c -> Math.abs(CoordinateKeys.unpackB(c) - layout.z()) <= H.WIDTH / 2); // off the main road
            Set<Long> paved = columnsOf(segments.get(i));
            boolean ok = piece.isEmpty() ? paved.size() == 1 : paved.equals(piece);
            if (!ok && notPieces.size() < 8) {
                long onMain = paved.stream().filter(c -> Math.abs(CoordinateKeys.unpackB(c) - layout.z()) <= H.WIDTH / 2)
                        .count();
                notPieces.add(i + " (" + paved.size() + " columns, " + onMain + " on the main road, own piece off it "
                        + piece.size() + ")");
            }
        }
        H.check(notPieces.isEmpty(), "junction segment(s) aren't RoadWeaver's own pieces kept off the main road: %s",
                notPieces);

        // Paved or cleared side road columns, walked from the side road's own route into the main road.
        Set<Long> open = new HashSet<>(TunnelCarver.roadFloors(List.of(side), Integer.MIN_VALUE / 2,
                Integer.MIN_VALUE / 2, Integer.MAX_VALUE / 2, Integer.MAX_VALUE / 2).keySet());
        for (RoadSegmentPlacement s : segments) {
            open.addAll(columnsOf(s));
        }
        List<String> cutOff = new ArrayList<>();
        for (int outside : new int[]{runStart - 1, runEnd + 1}) {
            ArrayDeque<Long> queue = new ArrayDeque<>(columnsOf(segments.get(outside)));
            Set<Long> reached = new HashSet<>(queue);
            boolean joined = false;
            while (!queue.isEmpty() && !joined) {
                long c = queue.poll();
                int x = CoordinateKeys.unpackA(c);
                int zz = CoordinateKeys.unpackB(c);
                joined = Math.abs(zz - layout.z()) <= H.WIDTH / 2;
                for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                    long next = CoordinateKeys.pack(x + d[0], zz + d[1]);
                    if (open.contains(next) && reached.add(next)) {
                        queue.add(next);
                    }
                }
            }
            if (!joined) {
                cutOff.add("from segment " + outside + " (" + reached.size() + " columns reached)");
            }
        }
        H.check(cutOff.isEmpty(), "the main road can't be reached over the side road's paved and cleared columns: %s",
                cutOff);

        Set<Long> chunks = chunksOf(side);
        int boxes = tunnelBoxInts(chunks);
        RoadSnapService.snapAllRoads(level, layout.x0(), layout.z() - 10, layout.x0() + 300, layout.z() + 90);
        H.check(tunnelBoxInts(chunks) == boxes, "a second snap of the area recorded tunnel boxes again (%d -> %d ints)",
                boxes, tunnelBoxInts(chunks));
        helper.succeed();
    }

    /**
     * The same layout: the side road's height difference to the main road is spread over its own route outside the
     * snapped stretch, as RoadWeaver's slope setting allows. Right outside each edge it is near the main road's
     * height, every two segments it changes by at most maxSlopeStepPerTwoSegments, and its far ends keep their own
     * height. Its columns outside the stretch stay where they were: the stretch is found as the segments whose
     * columns moved, and must lie well inside the road.
     *
     * @param helper the test's helper
     */
    @GameTest(template = HarnessSetup.TEMPLATE)
    public static void snapRampFollowsSlopeSetting(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MiddleStretch layout = middleStretch(level);
        SnappedStretch snapped = snapMiddleStretch(level, layout);
        int runStart = snapped.runStart();
        int runEnd = snapped.runEnd();
        List<Integer> ys = snapped.side().targetY();
        int n = ys.size();
        int step2 = RoadWeaverAccessor.slopeLimitEnabled() ? RoadWeaverAccessor.maxSlopeStepPerTwoSegments()
                : RoadWeaverAccessor.UNLIMITED_SLOPE_STEP;
        int trans = SnapJunctionShaper.TRANSITION;

        int near = (step2 * (trans + 1) + 1) / 2;
        int atStart = ys.get(runStart - 1);
        int atEnd = ys.get(runEnd + 1);
        H.check(Math.abs(atStart - layout.mainY()) <= near && Math.abs(atEnd - layout.mainY()) <= near,
                "no ramp outside the stretch: the side road is at y %d and %d right outside it, the main road at y %d "
                        + "(at most %d apart allowed with a slope step of %d per two segments)", atStart, atEnd,
                layout.mainY(), near, step2);
        List<String> steep = new ArrayList<>();
        for (int i = 0; i + 2 < n; i++) {
            boolean outside = i + 2 < runStart || i > runEnd;
            if (outside && Math.abs(ys.get(i) - ys.get(i + 2)) > step2 && steep.size() < 8) {
                steep.add(i + ": y " + ys.get(i) + " -> " + ys.get(i + 2));
            }
        }
        H.check(steep.isEmpty(), "the ramp is steeper than %d per two segments: %s", step2, steep);
        H.check(ys.get(0) == layout.sideY() && ys.get(n - 1) == layout.sideY(),
                "the side road's far ends lost their own height %d: y %d and %d", layout.sideY(), ys.get(0), ys.get(n - 1));
        helper.succeed();
    }

    /**
     * The x 24, z 97 layout: the cut side road's junction, and every segment of its own route lowered toward the
     * main road, carry a tunnel stretch with tunnel boxes, so worldgen clears them like RWSA's own tunnels. After
     * worldgen, nothing solid is left above any of their road columns, up to RoadWeaver's clear height. A second
     * snap of the area records no tunnel box twice.
     *
     * @param helper the test's helper
     */
    @GameTest(template = HarnessSetup.TEMPLATE, timeoutTicks = 300)
    public static void snapJunctionIsCleared(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        AcrossHeight layout = acrossHeight(level);
        int x0 = layout.x0();
        int z = layout.z();
        RoadSnapService.snapAllRoads(level, x0, z, x0 + 200, z + 60);

        RoadData side = storedRoadOwnedBy(level, layout.sideStart(), x0 - 30, z - 30, x0 + 230, z + 90);
        H.check(side != null, "the side road is gone from storage after snapping");
        List<RoadSegmentPlacement> segments = side.roadSegmentList();
        H.check(isAnchor(segments.get(segments.size() - 1)), "harness: RoadWeaver didn't cut the side road at its shared end");
        int lastPaved = segments.size() - 1;
        while (lastPaved > 0 && isAnchor(segments.get(lastPaved))) {
            lastPaved--;
        }
        boolean[] tunnel = TunnelCarver.getTunnelSegmentsByRoadData(side);
        H.check(tunnel != null, "the snapped side road has no tunnel stretch, so its junction isn't cleared (stretches %s)",
                side.spans().stream().map(s -> s.type() + "@" + s.start().toShortString()).toList());
        List<Integer> original = layout.side().targetY();
        int trans = SnapJunctionShaper.TRANSITION;
        List<String> uncleared = new ArrayList<>();
        int lowered = 0;
        for (int i = 0; i <= lastPaved; i++) {
            boolean junction = i > lastPaved - trans;
            boolean dug = !junction && side.targetY().get(i) < original.get(i);
            lowered += dug ? 1 : 0;
            if ((junction || dug) && !tunnel[i] && uncleared.size() < 8) {
                uncleared.add(i + (junction ? " junction" : " lowered to y " + side.targetY().get(i)));
            }
        }
        H.check(uncleared.isEmpty(), "segment(s) without the tunnel clearing: %s", uncleared);
        H.check(lowered > 0, "harness: no segment of the side road's own route was lowered toward the main road");

        Set<Long> chunks = new HashSet<>();
        for (int i = 0; i < tunnel.length; i++) {
            if (tunnel[i]) {
                for (BlockPos p : segments.get(i).positions()) {
                    chunks.add(ChunkPos.asLong(p.getX() >> 4, p.getZ() >> 4));
                }
            }
        }
        int boxes = tunnelBoxInts(chunks);
        H.check(boxes > 0, "no tunnel box recorded for the cleared segments, so worldgen won't clear them");
        RoadSnapService.snapAllRoads(level, x0, z, x0 + 200, z + 60);
        H.check(tunnelBoxInts(chunks) == boxes, "a second snap of the area recorded tunnel boxes again (%d -> %d ints)",
                boxes, tunnelBoxInts(chunks));

        Set<Long> around = new HashSet<>();
        for (long c : chunks) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    around.add(ChunkPos.asLong(ChunkPos.getX(c) + dx, ChunkPos.getZ(c) + dz));
                }
            }
        }
        for (long c : around) {
            level.getChunk(ChunkPos.getX(c), ChunkPos.getZ(c));
        }
        int clear = RoadWeaverAccessor.roadClearHeight();
        // With the main road too: its columns at about the junction's height are its own, never cleared.
        Long2IntOpenHashMap floors = TunnelCarver.roadFloors(List.of(side, layout.main()), Integer.MIN_VALUE / 2,
                Integer.MIN_VALUE / 2, Integer.MAX_VALUE / 2, Integer.MAX_VALUE / 2);
        H.check(!floors.isEmpty(), "no cleared road columns");
        int solid = 0;
        List<String> samples = new ArrayList<>();
        for (Long2IntMap.Entry e : floors.long2IntEntrySet()) {
            int x = CoordinateKeys.unpackA(e.getLongKey());
            int zz = CoordinateKeys.unpackB(e.getLongKey());
            for (int y = e.getIntValue(); y < e.getIntValue() + clear; y++) {
                BlockState state = level.getBlockState(new BlockPos(x, y, zz));
                if (!state.isAir() && state.getFluidState().isEmpty()) {
                    solid++;
                    if (samples.size() < 8) {
                        samples.add(x + "," + y + "," + zz + " " + state.getBlock() + " (floor " + e.getIntValue() + ")");
                    }
                }
            }
        }
        H.check(solid == 0, "%d solid block(s) above %d cleared road column(s): %s", solid, floors.size(), samples);

        /*
            The main road's surface under and next to the junction is still its own (stone bricks): the clearing never
            digs it out, and the junction pieces never pave the side road's gravel over it.
         */
        RoadData main = layout.main();
        List<RoadSegmentPlacement> mainSegments = main.roadSegmentList();
        int[] mainYs = main.targetY().stream().mapToInt(Integer::intValue).toArray();
        List<BlockPos> junctionMiddles = new ArrayList<>();
        for (int i = Math.max(0, lastPaved - trans + 1); i <= lastPaved; i++) {
            junctionMiddles.add(segments.get(i).middlePos());
        }
        Map<Long, Integer> mainFloors = new HashMap<>();
        for (int i = 0; i < mainSegments.size(); i++) {
            BlockPos m = mainSegments.get(i).middlePos();
            boolean near = false;
            for (BlockPos j : junctionMiddles) {
                near |= Math.max(Math.abs(m.getX() - j.getX()), Math.abs(m.getZ() - j.getZ())) <= 12;
            }
            if (near) {
                floorsOf(mainSegments, mainYs, i, i, mainFloors);
            }
        }
        H.check(!mainFloors.isEmpty(), "harness: no main road column near the junction");
        List<String> dug = new ArrayList<>();
        for (Map.Entry<Long, Integer> e : mainFloors.entrySet()) {
            BlockPos surface = new BlockPos(CoordinateKeys.unpackA(e.getKey()), e.getValue() - 1,
                    CoordinateKeys.unpackB(e.getKey()));
            BlockState state = level.getBlockState(surface);
            if (!state.is(Blocks.STONE_BRICKS) && dug.size() < 8) {
                dug.add(surface.toShortString() + " " + state.getBlock());
            }
        }
        H.check(dug.isEmpty(), "the main road's surface next to the junction isn't its own: %s (of %d columns)", dug,
                mainFloors.size());
        helper.succeed();
    }

    /**
     * Spawn's case: the chunks where the side road's junction falls were decorated before the roads were saved, so
     * RoadWeaver never paves the roads there. Nothing may be cleared there either: no TUNNEL segment has its middle
     * in those chunks, and after everything around them generates, no block that was solid there turned into air,
     * except next to segments RoadWeaver paves from a neighbouring chunk (their paving reaches a few columns across).
     *
     * @param helper the test's helper
     */
    @GameTest(template = HarnessSetup.TEMPLATE, timeoutTicks = 300)
    public static void snapJunctionNotClearedWhereNotPaved(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        AcrossHeight layout = acrossHeight(level, true);
        int x0 = layout.x0();
        int z = layout.z();
        int lowY = Integer.MAX_VALUE;
        int highY = Integer.MIN_VALUE;
        for (RoadData road : List.of(layout.main(), layout.side())) {
            for (int y : road.targetY()) {
                lowY = Math.min(lowY, y - 2);
                highY = Math.max(highY, y + RoadWeaverAccessor.roadClearHeight() + 2);
            }
        }
        Map<Long, List<Integer>> solidBefore = new HashMap<>();
        for (long c : layout.pregenerated()) {
            for (int x = ChunkPos.getX(c) << 4; x < (ChunkPos.getX(c) + 1) << 4; x++) {
                for (int zz = ChunkPos.getZ(c) << 4; zz < (ChunkPos.getZ(c) + 1) << 4; zz++) {
                    List<Integer> solid = new ArrayList<>();
                    for (int y = lowY; y <= highY; y++) {
                        BlockState state = level.getBlockState(new BlockPos(x, y, zz));
                        if (!state.isAir() && state.getFluidState().isEmpty()) {
                            solid.add(y);
                        }
                    }
                    solidBefore.put(CoordinateKeys.pack(x, zz), solid);
                }
            }
        }

        RoadSnapService.snapAllRoads(level, x0, z, x0 + 200, z + 60);
        RoadData side = storedRoadOwnedBy(level, layout.sideStart(), x0 - 30, z - 30, x0 + 230, z + 90);
        H.check(side != null, "the side road is gone from storage after snapping");
        List<RoadSegmentPlacement> segments = side.roadSegmentList();
        boolean[] tunnel = TunnelCarver.getTunnelSegmentsByRoadData(side);
        List<String> marked = new ArrayList<>();
        for (int i = 0; tunnel != null && i < segments.size(); i++) {
            BlockPos m = segments.get(i).middlePos();
            if (tunnel[i] && layout.pregenerated().contains(ChunkPos.asLong(m.getX() >> 4, m.getZ() >> 4))
                    && marked.size() < 8) {
                marked.add(i + " " + m.toShortString());
            }
        }
        H.check(marked.isEmpty(), "TUNNEL segment(s) in chunks decorated before the road was saved: %s", marked);

        // Columns RoadWeaver paves from a neighbouring chunk, and a few columns around them, may change.
        Set<Long> pavedNearby = new HashSet<>();
        Set<Long> chunks = new HashSet<>();
        for (RoadData road : List.of(layout.main(), side)) {
            for (RoadSegmentPlacement s : road.roadSegmentList()) {
                BlockPos m = s.middlePos();
                boolean pavedElsewhere = !layout.pregenerated().contains(ChunkPos.asLong(m.getX() >> 4, m.getZ() >> 4));
                for (BlockPos p : s.positions()) {
                    for (int dx = -1; dx <= 1; dx++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            chunks.add(ChunkPos.asLong((p.getX() >> 4) + dx, (p.getZ() >> 4) + dz));
                        }
                    }
                    if (pavedElsewhere) {
                        for (int dx = -4; dx <= 4; dx++) {
                            for (int dz = -4; dz <= 4; dz++) {
                                pavedNearby.add(CoordinateKeys.pack(p.getX() + dx, p.getZ() + dz));
                            }
                        }
                    }
                }
            }
        }
        for (long c : chunks) {
            level.getChunk(ChunkPos.getX(c), ChunkPos.getZ(c));
        }
        List<String> cleared = new ArrayList<>();
        int count = 0;
        for (Map.Entry<Long, List<Integer>> e : solidBefore.entrySet()) {
            if (pavedNearby.contains(e.getKey())) {
                continue;
            }
            int x = CoordinateKeys.unpackA(e.getKey());
            int zz = CoordinateKeys.unpackB(e.getKey());
            for (int y : e.getValue()) {
                if (level.getBlockState(new BlockPos(x, y, zz)).isAir()) {
                    count++;
                    if (cleared.size() < 8) {
                        cleared.add(x + "," + y + "," + zz);
                    }
                }
            }
        }
        H.check(count == 0, "%d block(s) cleared in chunks where no road is paved: %s", count, cleared);
        helper.succeed();
    }

    /**
     * RoadWeaver's PathFeature never paves a road's first 9 segments nor its last 6. A tunnel stretch over them must
     * clear nothing there: TunnelCarver.roadFloors returns no column of those segments.
     *
     * @param helper the test's helper
     */
    @GameTest(template = HarnessSetup.TEMPLATE)
    public static void tunnelNotClearedAtUnpavedRoadEnds(GameTestHelper helper) {
        BlockPos a = H.area();
        int x0 = a.getX() + 200;
        int z = a.getZ() + 1000;
        int n = 60;
        List<BlockPos> middles = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            middles.add(new BlockPos(x0 + i, 100, z));
        }
        RoadData plain = roadThrough(middles, H.pos2dKey(middles.get(0)), H.pos2dKey(middles.get(n - 1)));
        List<RoadSpan> spans = List.of(new RoadSpan(middles.get(0), middles.get(29), SpanType.TUNNEL),
                new RoadSpan(middles.get(45), middles.get(n - 1), SpanType.TUNNEL));
        RoadData road = new RoadData(plain.width(), plain.roadType(), plain.materials(), plain.slabMaterials(),
                plain.roadSegmentList(), spans, plain.targetY(), plain.ownerA2dKey(), plain.ownerB2dKey());
        Set<Long> cleared = TunnelCarver.roadFloors(List.of(road), Integer.MIN_VALUE / 2, Integer.MIN_VALUE / 2,
                Integer.MAX_VALUE / 2, Integer.MAX_VALUE / 2).keySet();
        H.check(!cleared.isEmpty(), "harness: the tunnel stretches clear nothing at all");
        List<Integer> unpaved = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (i >= 9 && i < n - 6) {
                continue;
            }
            for (BlockPos p : road.roadSegmentList().get(i).positions()) {
                if (cleared.contains(CoordinateKeys.pack(p.getX(), p.getZ()))) {
                    unpaved.add(i);
                    break;
                }
            }
        }
        H.check(unpaved.isEmpty(), "segment(s) RoadWeaver never paves are cleared: %s", unpaved);
        helper.succeed();
    }

    /**
     * Road B's stretch 104..146 layout with a recorded structure on the side road's own route, where its ramp goes
     * down toward the main road. The snapped segments (junction pieces and ramp segments) avoid structures by
     * trimming only: every side road segment in the structure's chunk is left without columns (paves nothing) and
     * nothing is cleared there. The ramp and junction outside it keep their columns, and the stretch is still
     * snapped onto the main road.
     *
     * @param helper the test's helper
     */
    @GameTest(template = HarnessSetup.TEMPLATE)
    public static void snapStretchTrimmedAtStructure(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MiddleStretch layout = middleStretch(level);
        int xs = layout.x0() + 100;
        ChunkPos blocked = new ChunkPos(new BlockPos(xs, 0, layout.z() + 24));
        H.recordStructure(blocked, List.of(blocked), level.getMinBuildHeight(), level.getMaxBuildHeight());
        RoadSnapService.snapAllRoads(level, layout.x0(), layout.z() - 10, layout.x0() + 300, layout.z() + 90);
        RoadData side = storedRoadOwnedBy(level, layout.sideStart(), layout.x0() - 30, layout.z() - 30,
                layout.x0() + 330, layout.z() + 120);
        H.check(side != null, "the side road is gone from storage after snapping");
        List<RoadSegmentPlacement> segments = side.roadSegmentList();
        List<RoadSegmentPlacement> before = layout.side().roadSegmentList();
        H.check(segments.size() == before.size(), "harness: the side road was cut (%d -> %d segments)", before.size(),
                segments.size());
        BlockPos inside = segments.get(74 + 30).middlePos();
        H.check(Math.abs(inside.getZ() - layout.z()) <= H.WIDTH / 2, "the stretch isn't snapped anymore (%s)",
                inside.toShortString());

        List<String> paved = new ArrayList<>();
        List<String> keptOutside = new ArrayList<>();
        for (int i = 0; i < segments.size(); i++) {
            BlockPos was = before.get(i).middlePos();
            boolean inBlocked = new ChunkPos(was).equals(blocked);
            boolean changed = !side.targetY().get(i).equals(layout.side().targetY().get(i));
            if (inBlocked && !isAnchor(segments.get(i)) && paved.size() < 8) {
                paved.add(i + " " + was.toShortString());
            }
            if (!inBlocked && changed && !isAnchor(segments.get(i))) {
                keptOutside.add(i + "");
            }
        }
        H.check(paved.isEmpty(), "side road segment(s) still paved inside the structure: %s", paved);
        H.check(!keptOutside.isEmpty(), "no ramp segment outside the structure kept its columns");
        int clearedThere = 0;
        for (long key : TunnelCarver.roadFloors(List.of(side), Integer.MIN_VALUE / 2, Integer.MIN_VALUE / 2,
                Integer.MAX_VALUE / 2, Integer.MAX_VALUE / 2).keySet()) {
            if (CoordinateKeys.unpackA(key) >> 4 == blocked.x && CoordinateKeys.unpackB(key) >> 4 == blocked.z) {
                clearedThere++;
            }
        }
        H.check(clearedThere == 0, "%d column(s) cleared inside the structure", clearedThere);
        helper.succeed();
    }

    /**
     * The x 24, z 97 layout, saved but not snapped yet.
     *
     * @param main      the main road, along X, 19 blocks below the surface, one segment per block
     * @param side      the side road as saved: down along Z on the surface, then 8 blocks beside the main road
     * @param sideStart    the side road's first owner (2D key)
     * @param x0           the main road's first X (block)
     * @param z            the main road's Z (block)
     * @param pregenerated ChunkPos.asLong of the chunks generated before the roads were saved, empty if none
     */
    private record AcrossHeight(RoadData main, RoadData side, long sideStart, int x0, int z, Set<Long> pregenerated) {
    }

    /**
     * Builds and saves the x 24, z 97 layout, with no chunk generated before the roads are saved.
     *
     * @param level the level, its road storage is changed
     * @return the saved roads and where they lie
     */
    private static AcrossHeight acrossHeight(ServerLevel level) {
        return acrossHeight(level, false);
    }

    /**
     * Builds and saves the x 24, z 97 layout: a main road 19 blocks below the surface, and a side road on the
     * surface that comes down along Z, then runs 8 blocks beside it along X. Both lead to the same destination, and
     * both climb at most 1 block per segment. The main road is paved in stone bricks, the side road in gravel.
     * <p>
     * With {@code pregenerate}, the chunks around where the side road meets the main road (its junction) are fully
     * generated before the roads are saved, like spawn's chunks: RoadWeaver never paves the roads there.
     *
     * @param level       the level, its road storage and (with pregenerate) chunks are changed
     * @param pregenerate true to generate the junction's chunks before the roads are saved
     * @return the saved roads and where they lie
     */
    private static AcrossHeight acrossHeight(ServerLevel level, boolean pregenerate) {
        BlockPos a = dryRoute(level, new TerrainSamplingCache(), 160, 420, 1000);
        int x0 = a.getX() + 200;
        int z = a.getZ() + 1000;
        int drop = 19;
        List<BlockPos> mainMiddles = new ArrayList<>();
        for (int x = x0; x <= x0 + 200; x++) {
            mainMiddles.add(new BlockPos(x, H.surfaceY(level, x, z) - drop, z));
        }
        int xs = x0 + 60;
        List<BlockPos> sideMiddles = new ArrayList<>();
        for (int zz = z + 60; zz > z + 8; zz--) {
            sideMiddles.add(new BlockPos(xs, H.surfaceY(level, xs, zz), zz));
        }
        for (int x = xs; x <= xs + 60; x++) {
            sideMiddles.add(new BlockPos(x, H.surfaceY(level, x, z + 8), z + 8));
        }
        mainMiddles = slopeLimited(mainMiddles);
        sideMiddles = slopeLimited(sideMiddles);
        long dest = H.pos2dKey(new BlockPos(x0 + 200, 0, z));
        long sideStart = H.pos2dKey(sideMiddles.get(0));
        RoadData main = roadThrough(mainMiddles, H.pos2dKey(mainMiddles.get(0)), dest);
        RoadData side = roadThrough(sideMiddles, sideStart, dest, Blocks.GRAVEL.defaultBlockState());
        Set<Long> pregenerated = new HashSet<>();
        if (pregenerate) {
            for (int cx = (xs - 8) >> 4; cx <= (xs + 8) >> 4; cx++) {
                for (int cz = (z - 4) >> 4; cz <= (z + 20) >> 4; cz++) {
                    level.getChunk(cx, cz);
                    pregenerated.add(ChunkPos.asLong(cx, cz));
                }
            }
        }
        RoadShardStorage.addRoad(level, main);
        RoadShardStorage.addRoad(level, side);
        return new AcrossHeight(main, side, sideStart, x0, z, pregenerated);
    }

    /**
     * Road B's stretch 104..146 layout, saved but not snapped yet.
     *
     * @param side      the side road as saved
     * @param sideStart the side road's first owner (2D key)
     * @param x0        the main road's first X (block)
     * @param z         the main road's Z (block)
     * @param mainY     the main road's height
     * @param sideY     the side road's own height
     */
    private record MiddleStretch(RoadData side, long sideStart, int x0, int z, int mainY, int sideY) {
    }

    /**
     * Road B's stretch 104..146 layout after RoadWeaver's snap.
     *
     * @param side     the snapped side road, as stored
     * @param runStart the first segment RoadWeaver snapped (the first whose columns moved)
     * @param runEnd   the last segment it snapped (the last whose columns moved)
     */
    private record SnappedStretch(RoadData side, int runStart, int runEnd) {
    }

    /**
     * Builds and saves road B's stretch 104..146 layout: a flat main road along X, and a flat side road 19 blocks
     * higher that comes down along Z from 80 blocks away, runs 6 blocks beside the main road for 60 blocks, then
     * leaves along Z again, paved in gravel (the main road in stone bricks). Different destinations, so RoadWeaver
     * snaps it without cutting it. One segment per
     * block, and the main road has more segments, so RoadWeaver snaps the side road onto it.
     *
     * @param level the level, its road storage is changed
     * @return the saved side road, its snapped stretch and where it lies
     */
    private static MiddleStretch middleStretch(ServerLevel level) {
        BlockPos a = H.area();
        int x0 = a.getX() + 200;
        int z = a.getZ() + 1000;
        int mainY = 100;
        int sideY = mainY + 19;
        List<BlockPos> mainMiddles = new ArrayList<>();
        for (int x = x0; x <= x0 + 300; x++) {
            mainMiddles.add(new BlockPos(x, mainY, z));
        }
        int xs = x0 + 100;
        List<BlockPos> sideMiddles = new ArrayList<>();
        for (int zz = z + 80; zz > z + 6; zz--) {
            sideMiddles.add(new BlockPos(xs, sideY, zz));
        }
        for (int x = xs; x <= xs + 60; x++) {
            sideMiddles.add(new BlockPos(x, sideY, z + 6));
        }
        for (int zz = z + 7; zz <= z + 80; zz++) {
            sideMiddles.add(new BlockPos(xs + 60, sideY, zz));
        }
        long sideStart = H.pos2dKey(sideMiddles.get(0));
        RoadData side = roadThrough(sideMiddles, sideStart, H.pos2dKey(sideMiddles.get(sideMiddles.size() - 1)),
                Blocks.GRAVEL.defaultBlockState());
        RoadShardStorage.addRoad(level, roadThrough(mainMiddles, H.pos2dKey(mainMiddles.get(0)),
                H.pos2dKey(mainMiddles.get(mainMiddles.size() - 1))));
        RoadShardStorage.addRoad(level, side);
        return new MiddleStretch(side, sideStart, x0, z, mainY, sideY);
    }

    /**
     * Snaps road B's stretch 104..146 layout, reads the side road back and finds the stretch RoadWeaver snapped: the
     * segments whose columns moved (a ramp only changes heights).
     *
     * @param level  the level
     * @param layout the saved layout
     * @return the snapped side road and its snapped stretch, checked to be stored, snapped once and not cut
     */
    private static SnappedStretch snapMiddleStretch(ServerLevel level, MiddleStretch layout) {
        RoadSnapService.snapAllRoads(level, layout.x0(), layout.z() - 10, layout.x0() + 300, layout.z() + 90);
        RoadData side = storedRoadOwnedBy(level, layout.sideStart(), layout.x0() - 30, layout.z() - 30,
                layout.x0() + 330, layout.z() + 120);
        H.check(side != null, "the side road is gone from storage after snapping");
        List<RoadSegmentPlacement> segments = side.roadSegmentList();
        List<RoadSegmentPlacement> before = layout.side().roadSegmentList();
        H.check(segments.size() == before.size(), "harness: the side road was cut (%d -> %d segments)", before.size(),
                segments.size());
        int runStart = -1;
        int runEnd = -1;
        for (int i = 0; i < segments.size(); i++) {
            if (!columnsOf(segments.get(i)).equals(columnsOf(before.get(i)))) {
                runStart = runStart < 0 ? i : runStart;
                runEnd = i;
            }
        }
        H.check(runStart > 0 && runEnd < segments.size() - 1, "harness: RoadWeaver didn't snap the side road (moved %d..%d)",
                runStart, runEnd);
        int middle = (runStart + runEnd) / 2;
        BlockPos inside = segments.get(middle).middlePos();
        H.check(Math.abs(inside.getZ() - layout.z()) <= H.WIDTH / 2,
                "harness: RoadWeaver didn't snap stretch %d..%d onto the main road (segment %d at %s)", runStart, runEnd,
                middle, inside.toShortString());
        return new SnappedStretch(side, runStart, runEnd);
    }

    /**
     * Collects a segment's columns.
     *
     * @param segment the segment, not changed
     * @return CoordinateKeys.pack(x, z) of its positions
     */
    private static Set<Long> columnsOf(RoadSegmentPlacement segment) {
        Set<Long> columns = new HashSet<>();
        for (BlockPos p : segment.positions()) {
            columns.add(CoordinateKeys.pack(p.getX(), p.getZ()));
        }
        return columns;
    }

    /**
     * Collects the chunks a road paves in.
     *
     * @param road the road, not changed
     * @return ChunkPos.asLong of every chunk holding one of its columns
     */
    private static Set<Long> chunksOf(RoadData road) {
        Set<Long> chunks = new HashSet<>();
        for (RoadSegmentPlacement s : road.roadSegmentList()) {
            for (BlockPos p : s.positions()) {
                chunks.add(ChunkPos.asLong(p.getX() >> 4, p.getZ() >> 4));
            }
        }
        return chunks;
    }

    /**
     * Counts the recorded tunnel boxes over some chunks.
     *
     * @param chunks ChunkPos.asLong of the chunks, not changed
     * @return the ints of every tunnel box clipped to each chunk, summed
     */
    private static int tunnelBoxInts(Set<Long> chunks) {
        int ints = 0;
        for (long c : chunks) {
            ints += TunnelCarver.boxesForChunk(ChunkPos.getX(c), ChunkPos.getZ(c)).length;
        }
        return ints;
    }

    /**
     * Limits a hand-made road's climb to 1 block per segment, as RoadWeaver's slope limit does for its own roads
     * (raw surface heights can drop 10+ blocks between neighbouring columns).
     *
     * @param middles the road's middles, in order, not changed
     * @return new middles, same X and Z, each Y within 1 of the previous one's and as close to its own as that allows
     */
    private static List<BlockPos> slopeLimited(List<BlockPos> middles) {
        List<BlockPos> out = new ArrayList<>(middles.size());
        int previous = middles.get(0).getY();
        for (BlockPos m : middles) {
            int y = Math.max(previous - 1, Math.min(previous + 1, m.getY()));
            out.add(new BlockPos(m.getX(), y, m.getZ()));
            previous = y;
        }
        return out;
    }

    /**
     * Tells whether a column is one of a set of columns or edge-adjacent to one.
     *
     * @param columns CoordinateKeys.pack(x, z) of the columns, not changed
     * @param x       the column's X (block)
     * @param z       the column's Z (block)
     * @return true if the column or one of its 4 neighbours is in {@code columns}
     */
    private static boolean touches(Set<Long> columns, int x, int z) {
        return columns.contains(CoordinateKeys.pack(x, z)) || columns.contains(CoordinateKeys.pack(x + 1, z))
                || columns.contains(CoordinateKeys.pack(x - 1, z)) || columns.contains(CoordinateKeys.pack(x, z + 1))
                || columns.contains(CoordinateKeys.pack(x, z - 1));
    }

    /**
     * Adds the paved columns of a road's segments with their floors, computed as RoadWeaver's SegmentPaver does.
     *
     * @param segments the road's segments, not changed
     * @param ys       the road's saved heights, not changed
     * @param from     the first segment index (inclusive)
     * @param to       the last segment index (inclusive)
     * @param floors   CoordinateKeys.pack(x, z) to the first air Y, mutated (a later segment overwrites)
     * @return the columns added, CoordinateKeys.pack(x, z)
     */
    private static Set<Long> floorsOf(List<RoadSegmentPlacement> segments, int[] ys, int from, int to,
                                      Map<Long, Integer> floors) {
        List<BlockPos> middles = new ArrayList<>();
        for (RoadSegmentPlacement s : segments) {
            middles.add(s.middlePos());
        }
        Set<Long> columns = new HashSet<>();
        for (int i = from; i <= to; i++) {
            List<BlockPos> positions = segments.get(i).positions();
            if (positions == null || positions.isEmpty()) {
                continue;
            }
            int[] heights = RoadHeightInterpolator.batchInterpolate(positions, i, middles, ys);
            for (int p = 0; p < positions.size(); p++) {
                long key = CoordinateKeys.pack(positions.get(p).getX(), positions.get(p).getZ());
                floors.put(key, heights[p]);
                columns.add(key);
            }
        }
        return columns;
    }

    /**
     * Finds the first block in a column that a player can't pass.
     *
     * @param level the level
     * @param x     the column's X (block)
     * @param z     the column's Z (block)
     * @param from  the first Y checked (inclusive)
     * @param to    the last Y checked (inclusive)
     * @return a description of the blocking block, null if the column is passable (leaves and logs don't count)
     */
    private static String firstBlocking(ServerLevel level, int x, int z, int from, int to) {
        for (int y = from; y <= to; y++) {
            BlockState state = level.getBlockState(new BlockPos(x, y, z));
            if (state.blocksMotion() && !state.is(BlockTags.LEAVES) && !state.is(BlockTags.LOGS)) {
                return x + "," + y + "," + z + " " + state.getBlock() + " (floor " + from + ", up to " + to + ")";
            }
        }
        return null;
    }

    /**
     * Spawn's case (log: 13 segments without positions in one road): two side roads to the same destination as
     * a main road, both cut onto it by RoadWeaver's snap. The second one's unpaved lead segments then lie beside
     * the first one's, so a later snap against the first side road would cut it a second time and "restore"
     * its unpaved lead segments as its junction. Each side road must be cut once only: at most one anchor plus
     * JunctionRepair's lead segments without positions, all at its end.
     *
     * @param helper the test's helper
     */
    @GameTest(template = HarnessSetup.TEMPLATE)
    public static void snapDoesNotCutTwice(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos a = H.area();
        int x0 = a.getX() + 200;
        int z = a.getZ() + 1000;
        int y = 100;
        long dest = H.pos2dKey(new BlockPos(x0 + 200, y, z));
        int xA = x0 + 60;
        List<BlockPos> aMiddles = besideRoadMiddles(xA, z, y, -1, 50);
        List<BlockPos> bMiddles = besideRoadMiddles(xA + 14, z, y, 1, 70); // longer: RoadWeaver snaps it before A
        long aStart = H.pos2dKey(aMiddles.get(0));
        long bStart = H.pos2dKey(bMiddles.get(0));
        RoadData main = H.roadAlongX(x0, x0 + 200, z, y, H.pos2dKey(new BlockPos(x0, y, z)), dest);
        RoadShardStorage.addRoad(level, main);
        RoadShardStorage.addRoad(level, roadThrough(aMiddles, aStart, dest));
        RoadShardStorage.addRoad(level, roadThrough(bMiddles, bStart, dest));
        RoadSnapService.snapAllRoads(level, x0, z - 70, x0 + 200, z + 70);

        Set<BlockPos> mainMiddles = new HashSet<>();
        for (RoadSegmentPlacement s : main.roadSegmentList()) {
            mainMiddles.add(s.middlePos());
        }
        List<String> problems = new ArrayList<>();
        for (long owner : new long[]{bStart, aStart}) {
            RoadData road = storedRoadOwnedBy(level, owner, x0 - 30, z - 100, x0 + 230, z + 100);
            H.check(road != null, "a side road is gone from storage after snapping");
            List<RoadSegmentPlacement> segments = road.roadSegmentList();
            String name = owner == aStart ? "side road A" : "side road B";
            H.check(isAnchor(segments.get(segments.size() - 1)), "harness: %s wasn't cut at its shared end", name);
            List<String> tail = new ArrayList<>();
            for (int i = Math.max(0, segments.size() - 16); i < segments.size(); i++) {
                tail.add(i + " " + segments.get(i).middlePos().toShortString() + (isAnchor(segments.get(i)) ? " -" : " +"));
            }
            H.log("{} end after snapping (+ paved, - no positions): {}", name, tail);
            int trailing = 0;
            while (trailing < segments.size() && isAnchor(segments.get(segments.size() - 1 - trailing))) {
                trailing++;
            }
            int total = (int) segments.stream().filter(RoadWeaverHarness::isAnchor).count();
            BlockPos anchor = segments.get(segments.size() - 1).middlePos();
            if (!mainMiddles.contains(anchor) || total != trailing) {
                problems.add(name + ": anchor " + anchor.toShortString() + (mainMiddles.contains(anchor) ? " on" : " not on")
                        + " the main road, " + trailing + " segments without positions at its end, " + total + " in all");
            }
        }
        H.check(problems.isEmpty(), "a side road was cut more than once: %s", problems);
        helper.succeed();
    }

    /**
     * Builds a side road's middles beside a main road along X: along Z toward the main road, then 8 blocks
     * beside it (along X) for 30 blocks.
     *
     * @param x       the side road's X (block)
     * @param z       the main road's Z (block)
     * @param y       the middles' Y
     * @param side    1 for the +Z side of the main road, -1 for the -Z side
     * @param descent how far from the main road the side road starts (blocks along Z)
     * @return the middles, every 2 blocks
     */
    private static List<BlockPos> besideRoadMiddles(int x, int z, int y, int side, int descent) {
        List<BlockPos> middles = new ArrayList<>();
        for (int d = descent; d > 8; d -= 2) {
            middles.add(new BlockPos(x, y, z + side * d));
        }
        for (int xx = x; xx <= x + 30; xx += 2) {
            middles.add(new BlockPos(xx, y, z + side * 8));
        }
        return middles;
    }

    /**
     * Builds the side road's middles: down along Z at x from z + 60, then 8 blocks beside the main road
     * (along X) for 30 blocks.
     *
     * @param x the side road's X (block)
     * @param z the main road's Z (block)
     * @param y the middles' Y
     * @return the middles, every 2 blocks
     */
    private static List<BlockPos> sideRoadMiddles(int x, int z, int y) {
        List<BlockPos> middles = new ArrayList<>();
        for (int zz = z + 60; zz > z + 8; zz -= 2) {
            middles.add(new BlockPos(x, y, zz));
        }
        for (int xx = x; xx <= x + 30; xx += 2) {
            middles.add(new BlockPos(xx, y, z + 8));
        }
        return middles;
    }

    /**
     * Builds a road through the given middles, H.WIDTH columns across its local direction.
     *
     * @param middles the segment middles, their Y is the road height
     * @param ownerA  one destination (2D key)
     * @param ownerB  the other destination (2D key)
     * @return the road, not saved
     */
    private static RoadData roadThrough(List<BlockPos> middles, long ownerA, long ownerB) {
        return roadThrough(middles, ownerA, ownerB, Blocks.STONE_BRICKS.defaultBlockState());
    }

    /**
     * Builds a road through the given middles, H.WIDTH columns across its local direction, paved in one material.
     *
     * @param middles  the segment middles, their Y is the road height
     * @param ownerA   one destination (2D key)
     * @param ownerB   the other destination (2D key)
     * @param material the road's only material
     * @return the road, not saved
     */
    private static RoadData roadThrough(List<BlockPos> middles, long ownerA, long ownerB, BlockState material) {
        List<RoadSegmentPlacement> segments = new ArrayList<>();
        List<Integer> ys = new ArrayList<>();
        int half = H.WIDTH / 2;
        for (int i = 0; i < middles.size(); i++) {
            BlockPos prev = middles.get(Math.max(0, i - 1));
            BlockPos next = middles.get(Math.min(middles.size() - 1, i + 1));
            boolean alongX = Math.abs(next.getX() - prev.getX()) >= Math.abs(next.getZ() - prev.getZ());
            BlockPos m = middles.get(i);
            List<BlockPos> positions = new ArrayList<>();
            for (int d = -half; d <= half; d++) {
                positions.add(alongX ? m.offset(0, 0, d) : m.offset(d, 0, 0));
            }
            segments.add(new RoadSegmentPlacement(m, positions));
            ys.add(m.getY());
        }
        return new RoadData(H.WIDTH, 0, List.of(material), List.of(), segments, List.of(), ys, ownerA, ownerB);
    }

    /**
     * Finds a stored road by its first owner.
     *
     * @param level  the level
     * @param ownerA the road's first owner (2D key)
     * @param minX   the searched area's min X (block)
     * @param minZ   the searched area's min Z (block)
     * @param maxX   the searched area's max X (block)
     * @param maxZ   the searched area's max Z (block)
     * @return the road, null if none in the area has that owner
     */
    private static RoadData storedRoadOwnedBy(ServerLevel level, long ownerA, int minX, int minZ, int maxX, int maxZ) {
        for (RoadData road : RoadShardStorage.queryRect(level, minX, minZ, maxX, maxZ)) {
            if (road.ownerA2dKey() == ownerA) {
                return road;
            }
        }
        return null;
    }

    /**
     * Tells whether a segment has no positions.
     *
     * @param s the segment
     * @return true for a truncation anchor or lead segment
     */
    private static boolean isAnchor(RoadSegmentPlacement s) {
        return s.positions() == null || s.positions().isEmpty();
    }

    /**
     * Measures the gap between two sets of columns.
     *
     * @param a one set
     * @param b the other set
     * @return the smallest Chebyshev distance between them (blocks)
     */
    private static int chebyshev(List<BlockPos> a, List<BlockPos> b) {
        int best = Integer.MAX_VALUE;
        for (BlockPos p : a) {
            for (BlockPos q : b) {
                best = Math.min(best, Math.max(Math.abs(p.getX() - q.getX()), Math.abs(p.getZ() - q.getZ())));
            }
        }
        return best;
    }

    // ---- zonesWithNoRoads (test zones stand in for Zones' own, harness 2 checks the real lookup) ----

    private static final String NO_ROAD_ZONE = "rwstructuresawareness:harness_no_roads";

    /**
     * Runs a body with stand-in zones and zonesWithNoRoads = [NO_ROAD_ZONE], then restores both.
     *
     * @param zones the zones to use in place of Zones' own
     * @param body  the code to run
     */
    private static void withNoRoadZones(List<ZonePerimeter> zones, Runnable body) {
        List<? extends String> old = CommonConfig.ZONES_WITH_NO_ROADS.get();
        ZonesCompat.setZonesForTests(zones);
        CommonConfig.ZONES_WITH_NO_ROADS.set(List.of(NO_ROAD_ZONE));
        CommonConfig.refresh();
        try {
            body.run();
        } finally {
            ZonesCompat.setZonesForTests(null);
            CommonConfig.ZONES_WITH_NO_ROADS.set(old);
            CommonConfig.refresh();
        }
    }

    /**
     * Destinations inside zones stay allowed by default. Inside a listed zone they are dropped, even
     * when RoadWeaver's own selection names the structure. Other zones keep theirs.
     *
     * @param helper the test's helper
     */
    @GameTest(template = HarnessSetup.TEMPLATE)
    public static void noRoadZoneDropsDestination(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos a = H.area();
        BlockPos inZone = a.offset(1000, 0, 1000);
        BlockPos inOther = a.offset(2000, 0, 1000);
        BlockPos outside = a.offset(3000, 0, 1000);
        List<StructureInfo> candidates = List.of(new StructureInfo(inZone, "minecraft:village_plains"),
                new StructureInfo(inOther, "minecraft:village_plains"), new StructureInfo(outside, "minecraft:village_plains"));
        List<ZonePerimeter> zones = List.of(
                new ZonePerimeter(NO_ROAD_ZONE, List.of(), inZone.getX() + 10, inZone.getZ(), 64),
                new ZonePerimeter("rwstructuresawareness:harness_other_zone", List.of(), inOther.getX(), inOther.getZ(), 64));

        H.check(CommonConfig.zonesWithNoRoads().isEmpty(), "zonesWithNoRoads should default to empty");
        ZonesCompat.setZonesForTests(zones);
        try {
            H.check(RoadDestinationsFilter.filter(level, candidates).size() == 3,
                    "with zonesWithNoRoads empty, a destination inside a zone was dropped");
        } finally {
            ZonesCompat.setZonesForTests(null);
        }

        Set<String> explicit = ((StructureSelectionConfigAccessor) (Object) StructureSelectionConfig.get())
                .rwstructuresawareness$getExplicitStructures();
        boolean added = explicit.add("minecraft:village_plains");
        try {
            withNoRoadZones(zones, () -> {
                List<StructureInfo> kept = RoadDestinationsFilter.filter(level, candidates);
                H.check(kept.size() == 2 && kept.stream().noneMatch(i -> i.pos().equals(inZone)),
                        "kept destinations: %s", kept.stream().map(i -> i.pos().toShortString()).toList());
            });
        } finally {
            if (added) {
                explicit.remove("minecraft:village_plains");
            }
        }
        helper.succeed();
    }

    /**
     * RoadWeaver's own path search detours around a no-road zone on its open-terrain path.
     *
     * @param helper the test's helper
     */
    @GameTest(template = HarnessSetup.TEMPLATE)
    public static void noRoadZoneDetour(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        TerrainSamplingCache cache = new TerrainSamplingCache();
        BlockPos a = dryRoute(level, cache, 180, 620, 1000);
        int z = a.getZ() + 1000;
        BlockPos from = new BlockPos(a.getX() + 200, cache.height(level, a.getX() + 200, z), z);
        BlockPos to = new BlockPos(a.getX() + 600, cache.height(level, a.getX() + 600, z), z);
        RoadGenerationConfig cfg = RoadGenerationConfig.from(ConfigService.get());
        List<RoadSegmentPlacement> before = RoadPathCalculator.calculateAStarRoadPathDetailed(from, to, H.WIDTH, level,
                MAX_STEPS, cache, cfg).segments();
        H.check(before != null && before.size() >= 5, "harness: RoadWeaver found no path on open terrain");
        int crossZ = z;
        int best = Integer.MAX_VALUE;
        for (RoadSegmentPlacement s : before) {
            int d = Math.abs(s.middlePos().getX() - (a.getX() + 400));
            if (d < best) {
                best = d;
                crossZ = s.middlePos().getZ();
            }
        }
        ZonePerimeter zone = new ZonePerimeter(NO_ROAD_ZONE, List.of(), a.getX() + 400, crossZ, 40);
        withNoRoadZones(List.of(zone), () -> {
            RoadObstacles obstacles = RoadObstacles.forRoute(from.getX(), from.getZ(), to.getX(), to.getZ(),
                    RoadWeaverAccessor.configuredRoadMargin());
            H.check(obstacles != null && NO_ROAD_ZONE.equals(obstacles.noRoadZoneAt(zone.centerX(), zone.centerZ())),
                    "the no-road zone isn't an obstacle");
            List<RoadSegmentPlacement> after = RoadPathCalculator.calculateAStarRoadPathDetailed(from, to, H.WIDTH, level,
                    MAX_STEPS, new TerrainSamplingCache(), cfg).segments();
            H.check(after != null && after.size() >= 5, "no path found around the no-road zone");
            for (RoadSegmentPlacement s : after) {
                H.check(!zone.contains(s.middlePos().getX(), s.middlePos().getZ(), 0),
                        "the path enters the no-road zone at %s", s.middlePos().toShortString());
            }
            BlockPos end = after.get(after.size() - 1).middlePos();
            H.check(Math.abs(end.getX() - to.getX()) <= 16 && Math.abs(end.getZ() - to.getZ()) <= 16,
                    "the path around the zone ends at %s, not near %s", end, to);
        });
        helper.succeed();
    }

    /**
     * A blocked stretch inside a no-road zone is trimmed at the zone's edge, never tunnelled under it.
     *
     * @param helper the test's helper
     */
    @GameTest(template = HarnessSetup.TEMPLATE)
    public static void noRoadZoneTrimNotTunnel(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos area = H.area();
        int x0 = area.getX() + 64;
        int z = area.getZ() + 1000;
        List<RoadSegmentPlacement> segments = H.segmentsAlongX(x0, 80, 4, z, 100);
        List<Integer> targetY = new ArrayList<>();
        segments.forEach(s -> targetY.add(100));
        StructureConnection connection = new StructureConnection(segments.get(0).middlePos(),
                segments.get(segments.size() - 1).middlePos());
        ZonePerimeter zone = new ZonePerimeter(NO_ROAD_ZONE, List.of(), x0 + 160, z, 24);
        withNoRoadZones(List.of(zone), () -> {
            try {
                RoadTunnelPlanner.plan(level, connection, segments, List.of(), targetY,
                        RoadGenerationConfig.from(ConfigService.get()));
                TunnelPlan plan = RoadTunnelPlanner.planFor(segments);
                H.check(plan != null, "no plan for a road crossing a no-road zone");
                H.check(plan.tunnelBoxes.isEmpty(), "a tunnel was planned under a no-road zone");
                H.check(plan.parts.size() == 2, "expected the blocked stretch in the zone trimmed (2 parts), got %d", plan.parts.size());
                for (RoadSegmentPlacement s : plan.keptSegments) {
                    for (BlockPos p : columns(s)) {
                        H.check(!zone.contains(p.getX(), p.getZ(), 0), "a kept road column %s lies in the zone",
                                p.toShortString());
                    }
                }
            } finally {
                RoadTunnelPlanner.clearPlan();
            }
        });
        helper.succeed();
    }

    /**
     * A zone nested in a listed no-road zone (its parentZone chain, here two levels deep) is covered without
     * being listed: a destination inside it is dropped, it blocks a road, the debug name says what it is nested
     * in, and an unrelated zone with the same nesting depth stays untouched.
     *
     * @param helper the test's helper
     */
    @GameTest(template = HarnessSetup.TEMPLATE)
    public static void noRoadZoneCoversNestedZone(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos a = H.area();
        BlockPos inChild = a.offset(1000, 0, 1000);
        BlockPos inOther = a.offset(2000, 0, 1000);
        /*
            The parent is listed and lies far away, so only the nesting can cover the child and grandchild.
            Zones keeps a child inside its parent, the stand-ins don't need to.
         */
        ZonePerimeter parent = new ZonePerimeter(NO_ROAD_ZONE, List.of(), a.getX() + 3500, a.getZ() + 3500, 32);
        ZonePerimeter child = new ZonePerimeter("rwstructuresawareness:harness_child", List.of(NO_ROAD_ZONE),
                inChild.getX(), inChild.getZ(), 48);
        ZonePerimeter grandchild = new ZonePerimeter("rwstructuresawareness:harness_grandchild",
                List.of("rwstructuresawareness:harness_child", NO_ROAD_ZONE), inChild.getX() + 200, inChild.getZ(), 16);
        ZonePerimeter unrelated = new ZonePerimeter("rwstructuresawareness:harness_unrelated_child",
                List.of("rwstructuresawareness:harness_other_zone"), inOther.getX(), inOther.getZ(), 48);
        List<StructureInfo> candidates = List.of(new StructureInfo(inChild, "minecraft:village_plains"),
                new StructureInfo(inOther, "minecraft:village_plains"));
        withNoRoadZones(List.of(parent, child, grandchild, unrelated), () -> {
            ZonePerimeter at = ZonesCompat.noRoadZoneAt(inChild.getX(), inChild.getZ());
            H.check(at != null && at.zoneId().equals(child.zoneId()), "the child of a listed zone isn't a no-road zone");
            H.check(NO_ROAD_ZONE.equals(at.matchedId(CommonConfig.zonesWithNoRoads())),
                    "the child matched %s, not its listed parent", at.matchedId(CommonConfig.zonesWithNoRoads()));
            ZonePerimeter deep = ZonesCompat.noRoadZoneAt(inChild.getX() + 200, inChild.getZ());
            H.check(deep != null && deep.zoneId().equals(grandchild.zoneId()),
                    "a grandchild of a listed zone isn't a no-road zone");
            H.check(ZonesCompat.noRoadZoneAt(inOther.getX(), inOther.getZ()) == null,
                    "an unrelated nested zone blocks roads");

            List<StructureInfo> kept = RoadDestinationsFilter.filter(level, candidates);
            H.check(kept.size() == 1 && kept.get(0).pos().equals(inOther),
                    "kept destinations: %s", kept.stream().map(i -> i.pos().toShortString()).toList());

            RoadObstacles obstacles = RoadObstacles.forRoute(inChild.getX() - 200, inChild.getZ(), inChild.getX() + 200,
                    inChild.getZ(), RoadWeaverAccessor.configuredRoadMargin());
            H.check(obstacles != null && obstacles.isBlocked(inChild.getX(), inChild.getZ(), -64, 320),
                    "the nested zone doesn't block a road");
        });
        helper.succeed();
    }

    // ---- Destinations: underground structures ----

    /**
     * Builds three destination candidates at one position.
     *
     * @param at the candidates' position
     * @return a mineshaft, a plains village and RoadWeaver's "village" category
     */
    private static List<StructureInfo> destinationCandidates(BlockPos at) {
        return List.of(new StructureInfo(at, "minecraft:mineshaft"), new StructureInfo(at, "minecraft:village_plains"),
                new StructureInfo(at, "village"));
    }

    /**
     * Lists candidates' structure ids.
     *
     * @param infos the candidates
     * @return their ids, in order
     */
    private static List<String> ids(List<StructureInfo> infos) {
        return infos.stream().map(StructureInfo::structureId).toList();
    }

    /**
     * An underground structure isn't a road destination, the others are kept.
     *
     * @param helper the test's helper
     */
    @GameTest(template = HarnessSetup.TEMPLATE)
    public static void undergroundStructureIsNotADestination(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        H.check(CommonConfig.isBlockUndergroundDestinations(), "blockUndergroundStructuresDestinations should default to true");
        Set<String> explicit = ((StructureSelectionConfigAccessor) (Object) StructureSelectionConfig.get())
                .rwstructuresawareness$getExplicitStructures();
        boolean wasExplicit = explicit.remove("minecraft:mineshaft");
        try {
            List<String> kept = ids(RoadDestinationsFilter.filter(level, destinationCandidates(H.area())));
            H.check(kept.equals(List.of("minecraft:village_plains", "village")), "kept destinations: %s", kept);
        } finally {
            if (wasExplicit) {
                explicit.add("minecraft:mineshaft");
            }
        }
        helper.succeed();
    }

    /**
     * RoadWeaver's own selection takes priority: a mineshaft selected one by one stays a destination.
     *
     * @param helper the test's helper
     */
    @GameTest(template = HarnessSetup.TEMPLATE)
    public static void customDestinationOverridesUndergroundRule(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Set<String> explicit = ((StructureSelectionConfigAccessor) (Object) StructureSelectionConfig.get())
                .rwstructuresawareness$getExplicitStructures();
        boolean added = explicit.add("minecraft:mineshaft");
        try {
            List<String> kept = ids(RoadDestinationsFilter.filter(level, destinationCandidates(H.area())));
            H.check(kept.contains("minecraft:mineshaft"), "a mineshaft selected in RoadWeaver was dropped: %s", kept);
        } finally {
            if (added) {
                explicit.remove("minecraft:mineshaft");
            }
        }
        helper.succeed();
    }

    /**
     * With blockUndergroundStructuresDestinations off, nothing is dropped.
     *
     * @param helper the test's helper
     */
    @GameTest(template = HarnessSetup.TEMPLATE)
    public static void undergroundRuleOff(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        CommonConfig.BLOCK_UNDERGROUND_DESTINATIONS.set(false);
        CommonConfig.refresh();
        try {
            List<String> kept = ids(RoadDestinationsFilter.filter(level, destinationCandidates(H.area())));
            H.check(kept.size() == 3, "with the rule off, destinations were dropped: %s", kept);
        } finally {
            CommonConfig.BLOCK_UNDERGROUND_DESTINATIONS.set(true);
            CommonConfig.refresh();
        }
        helper.succeed();
    }

    /**
     * A blocked stretch under a shallow structure is dug below it, with ramps within the slope limit and a tunnel stretch.
     *
     * @param helper the test's helper
     */
    @GameTest(template = HarnessSetup.TEMPLATE)
    public static void tunnelPlannerDigsUnderShallowStructure(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        TunnelCase t = new TunnelCase(level, H.area(), 98, 130);
        try {
            List<Integer> out = t.plan();
            TunnelPlan plan = RoadTunnelPlanner.planFor(t.segments);
            int clear = RoadWeaverAccessor.roadClearHeight();
            int step2 = t.slopeStep();
            H.check(!t.blocked.isEmpty(), "harness: no segment crosses the structure");
            if (step2 <= 0) {
                H.check(plan != null && plan.parts.size() == 2, "slope limit 0: the blocked stretch should be trimmed");
                helper.succeed();
                return;
            }
            H.check(plan != null, "no plan for a road crossing a structure");
            H.check(plan.parts.size() == 1 && out.size() == t.segments.size(), "a diggable blocked stretch was trimmed (%d parts)",
                    plan.parts.size());
            int ceiling = 98 - clear - 2;
            for (int i : t.blocked) {
                H.check(out.get(i) <= ceiling, "segment %d at y %d, must be <= %d under the structure", i, out.get(i), ceiling);
            }
            for (int i = 0; i + 2 < out.size(); i++) {
                H.check(Math.abs(out.get(i + 2) - out.get(i)) <= step2, "slope %d..%d exceeds %d per two segments: %s",
                        i, i + 2, step2, out);
            }
            H.check(out.get(0) == 100 && out.get(out.size() - 1) == 100, "road ends moved: %s", out);
            H.check(plan.keptSpans.stream().anyMatch(s -> s.type() == SpanType.TUNNEL), "no tunnel stretch");
            H.check(!plan.tunnelBoxes.isEmpty(), "no tunnel dig volume");
            for (int[] box : plan.tunnelBoxes) {
                H.check(box[5] == box[4] + clear && box[4] < 100, "tunnel box %s", Arrays.toString(box));
            }
            H.log("tunnel: blocked segments {}, heights {}", t.blocked, out);
        } finally {
            RoadTunnelPlanner.clearPlan();
        }
        helper.succeed();
    }

    /**
     * A blocked stretch under a structure reaching too deep is trimmed, the road split in two parts.
     *
     * @param helper the test's helper
     */
    @GameTest(template = HarnessSetup.TEMPLATE)
    public static void tunnelPlannerTrimsWhenTooDeep(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        TunnelCase t = new TunnelCase(level, H.area(), 30, 130);
        try {
            List<Integer> out = t.plan();
            TunnelPlan plan = RoadTunnelPlanner.planFor(t.segments);
            H.check(plan != null, "no plan for a road crossing a structure");
            H.check(plan.parts.size() == 2, "expected the road split in 2 parts, got %d", plan.parts.size());
            H.check(plan.keptSegments.size() == t.segments.size() - t.blocked.size(),
                    "kept %d segments, expected %d", plan.keptSegments.size(), t.segments.size() - t.blocked.size());
            H.check(out.size() == plan.keptSegments.size(), "heights not aligned with kept segments");
            H.check(plan.tunnelBoxes.isEmpty(), "a too-deep blocked stretch was dug");
            H.check(plan.reachesEnd, "last part should reach the road end");
            for (int i : t.blocked) {
                H.check(!plan.keptSegments.contains(t.segments.get(i)), "blocked segment %d was kept", i);
            }
        } finally {
            RoadTunnelPlanner.clearPlan();
        }
        helper.succeed();
    }

    /**
     * End to end through Road.generateRoad (RoadMixin): the destination sits inside a closed ring of
     * structure chunks, so no detour exists and the road can't be dug that deep. The road must stop
     * before the ring, and the piece inside it is saved on its own, still leading to the destination.
     *
     * @param helper the test's helper
     */
    @GameTest(template = HarnessSetup.TEMPLATE)
    public static void tunnelGenerateRoadTrimsAroundEnclosedDestination(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos a = H.area();
        int z = a.getZ() + 2000;
        TerrainSamplingCache cache = new TerrainSamplingCache();
        BlockPos from = new BlockPos(a.getX() + 500, cache.height(level, a.getX() + 500, z), z);
        BlockPos to = new BlockPos(a.getX() + 900, cache.height(level, a.getX() + 900, z), z);
        ChunkPos toChunk = new ChunkPos(to);
        List<ChunkPos> ring = new ArrayList<>();
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) == 4) {
                    ring.add(new ChunkPos(toChunk.x + dx, toChunk.z + dz));
                }
            }
        }
        // Full height: blocks at any terrain height, and too deep to dig under (lowest y = world bottom).
        H.recordStructure(new ChunkPos(toChunk.x + 4, toChunk.z + 4), ring, level.getMinBuildHeight(), level.getMaxBuildHeight());

        RoadData road = new Road(level, new StructureConnection(from, to), new PathFeatureConfig()).generateRoad(MAX_STEPS);
        H.check(road != null, "generateRoad returned no road");
        RoadObstacles obstacles = RoadObstacles.forArea(from.getX(), from.getZ() - 200, to.getX(), to.getZ() + 200,
                from.getX(), from.getZ(), to.getX(), to.getZ(), RoadWeaverAccessor.configuredRoadMargin());
        H.check(obstacles != null, "no obstacles");
        H.check(blockedSegments(road.roadSegmentList(), obstacles) == 0, "the saved road still crosses the ring");
        H.check(road.ownerA2dKey() == H.pos2dKey(from) && road.ownerB2dKey() == H.pos2dKey(to),
                "the first part lost its connection owners");
        RoadData inner = innerPiece(level, from, to);
        H.check(inner != null, "no separate road piece inside the ring leading to the destination");
        H.check(blockedSegments(inner.roadSegmentList(), obstacles) == 0, "the inner piece crosses the ring");
        H.check(inner.ownerA2dKey() == H.pos2dKey(from) && inner.ownerB2dKey() == H.pos2dKey(to),
                "the inner piece doesn't carry the connection's owners (RoadWeaver would take it for legacy data)");
        H.log("generateRoad: outer part {} segments, inner part {} segments",
                road.roadSegmentList().size(), inner.roadSegmentList().size());
        helper.succeed();
    }

    /**
     * A blocked stretch inside a water crossing (bridge stretch) is trimmed, never dug: the tunnel would flood.
     *
     * @param helper the test's helper
     */
    @GameTest(template = HarnessSetup.TEMPLATE)
    public static void tunnelNotDugUnderWaterCrossing(GameTestHelper helper) {
        TunnelCase t = new TunnelCase(helper.getLevel(), H.area(), 98, 130); // diggable on dry land
        try {
            RoadSpan bridge = new RoadSpan(t.segments.get(30).middlePos(), t.segments.get(55).middlePos(), SpanType.BRIDGE);
            RoadTunnelPlanner.plan(t.level, t.connection, t.segments, List.of(bridge), t.targetY, t.cfg);
            TunnelPlan plan = RoadTunnelPlanner.planFor(t.segments);
            H.check(plan != null && plan.tunnelBoxes.isEmpty() && plan.parts.size() == 2,
                    "a blocked stretch under a water crossing was dug (boxes %s, parts %s)",
                    plan == null ? "-" : plan.tunnelBoxes.size(), plan == null ? "-" : plan.parts.size());
            H.check(plan.keptSpans.stream().noneMatch(s -> s.type() == SpanType.TUNNEL), "a tunnel stretch over water");
        } finally {
            RoadTunnelPlanner.clearPlan();
        }
        helper.succeed();
    }

    /**
     * A structure entirely below the road's height band (e.g. buried ruins) doesn't make it tunnel.
     *
     * @param helper the test's helper
     */
    @GameTest(template = HarnessSetup.TEMPLATE)
    public static void tunnelNotPlannedUnderStructureBelowRoad(GameTestHelper helper) {
        TunnelCase t = new TunnelCase(helper.getLevel(), H.area(), 40, 90);
        try {
            List<Integer> out = t.plan();
            H.check(out == t.targetY && RoadTunnelPlanner.planFor(t.segments) == null,
                    "the road (y 100) was changed for a structure at y 40..90 below it");
        } finally {
            RoadTunnelPlanner.clearPlan();
        }
        helper.succeed();
    }

    /**
     * Footprints are per piece, not per chunk: a piece in the neighbouring chunk row, farther from the
     * road than its margin, doesn't block it. The same chunk fully covered (control) does.
     *
     * @param helper the test's helper
     */
    @GameTest(template = HarnessSetup.TEMPLATE)
    public static void tunnelNotPlannedForNeighbourPieceOutsideMargin(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int margin = RoadWeaverAccessor.configuredRoadMargin();
        int localZ0 = Math.min(15, margin + 6); // road columns reach z + 1 = row start, this keeps > margin away
        for (boolean control : new boolean[]{false, true}) {
            BlockPos a = H.area();
            int x0 = a.getX() + 64;
            int z = a.getZ() + 1023; // local z 15 of its chunk row: the next row starts at z + 1
            List<RoadSegmentPlacement> segments = H.segmentsAlongX(x0, 80, 4, z, 100);
            List<Integer> targetY = new ArrayList<>();
            segments.forEach(s -> targetY.add(100));
            StructureConnection connection = new StructureConnection(segments.get(0).middlePos(),
                    segments.get(segments.size() - 1).middlePos());
            ChunkPos piece = new ChunkPos((x0 + 160) >> 4, (z >> 4) + 1);
            H.recordFootprint(new ChunkPos(piece.x, piece.z + 3), piece, 0, control ? 0 : localZ0, 15, 15, 90, 130);
            try {
                RoadTunnelPlanner.plan(level, connection, segments, List.of(), targetY,
                        RoadGenerationConfig.from(ConfigService.get()));
                boolean planned = RoadTunnelPlanner.planFor(segments) != null;
                H.check(planned == control, control ? "a piece touching the road didn't block it"
                        : "a piece %d+ blocks from the road (margin %d) blocked it", localZ0, margin);
            } finally {
                RoadTunnelPlanner.clearPlan();
            }
        }
        helper.succeed();
    }

    /**
     * After real worldgen, a dug tunnel has nothing left inside and no lamps over it.
     *
     * @param helper the test's helper
     */
    @GameTest(template = HarnessSetup.TEMPLATE, timeoutTicks = 400)
    public static void tunnelClearAfterWorldgen(GameTestHelper helper) {
        tunnelWorldgenCase(helper, false);
    }

    /**
     * Same, with the chunks already terrain-generated when the road is planned (no Beardifier carve).
     *
     * @param helper the test's helper
     */
    @GameTest(template = HarnessSetup.TEMPLATE, timeoutTicks = 400)
    public static void tunnelClearAfterWorldgenOfPregeneratedChunks(GameTestHelper helper) {
        tunnelWorldgenCase(helper, true);
    }

    /**
     * Plans a tunnel through a ring of structure chunks, generates its chunks, then checks the tunnel is clear
     * and undecorated.
     *
     * @param helper      the test's helper, succeeded at the end
     * @param pregenerate true to generate the chunks to NOISE before the road is planned
     */
    private static void tunnelWorldgenCase(GameTestHelper helper, boolean pregenerate) {
        ServerLevel level = helper.getLevel();
        TerrainSamplingCache cache = new TerrainSamplingCache();
        /*
            A closed ring of structure chunks around the destination. The ring chunks RoadWeaver's own
            open-terrain path runs through (computed first: with no route around the ring, the road falls
            back to that path) start just above their lowest ground. Every other ring chunk blocks at
            all heights. So the road can only get in by a shallow dig where it crosses.
         */
        RoadGenerationConfig cfg = RoadGenerationConfig.from(ConfigService.get());
        int margin = RoadWeaverAccessor.configuredRoadMargin();
        BlockPos from = null;
        BlockPos to = null;
        Set<Long> crossing = new HashSet<>();
        int ringMin = 0;
        int ringMax = 0;
        for (int attempt = 0; attempt < 30 && to == null; attempt++) {
            BlockPos a = dryRoute(level, cache, 480, 920, 2000);
            int z = a.getZ() + 2000;
            BlockPos f = new BlockPos(a.getX() + 500, cache.height(level, a.getX() + 500, z), z);
            BlockPos t = new BlockPos(a.getX() + 900, cache.height(level, a.getX() + 900, z), z);
            ChunkPos tc = new ChunkPos(t);
            List<RoadSegmentPlacement> open = RoadPathCalculator.calculateAStarRoadPathDetailed(f, t, H.WIDTH, level,
                    MAX_STEPS, new TerrainSamplingCache(), cfg).segments();
            if (open == null || open.size() < 5) {
                continue;
            }
            crossing.clear();
            for (RoadSegmentPlacement s : open) {
                for (BlockPos p : s.positions() == null ? List.of(s.middlePos()) : s.positions()) {
                    for (int dx = -margin - 1; dx <= margin + 1; dx += margin + 1) {
                        for (int dz = -margin - 1; dz <= margin + 1; dz += margin + 1) {
                            int cx = (p.getX() + dx) >> 4;
                            int cz = (p.getZ() + dz) >> 4;
                            if (Math.max(Math.abs(cx - tc.x), Math.abs(cz - tc.z)) == RING) {
                                crossing.add(CoordinateKeys.chunkKey(cx, cz));
                            }
                        }
                    }
                }
            }
            ringMin = Integer.MAX_VALUE;
            ringMax = Integer.MIN_VALUE;
            for (long key : crossing) {
                for (int x = CoordinateKeys.unpackA(key) << 4; x < (CoordinateKeys.unpackA(key) + 1) << 4; x += 4) {
                    for (int zz = CoordinateKeys.unpackB(key) << 4; zz < (CoordinateKeys.unpackB(key) + 1) << 4; zz += 4) {
                        int h = cache.height(level, x, zz);
                        ringMin = Math.min(ringMin, h);
                        ringMax = Math.max(ringMax, h);
                    }
                }
            }
            /*
                The destination near the ring's ground too: the tunnel needs room inside the ring for its
                exit ramp, which RoadWeaver's slope limit can't give it up a steep rise (then it trims instead).
             */
            if (!crossing.isEmpty() && ringMax - ringMin <= 20 && ringMin > level.getSeaLevel()
                    && Math.abs(t.getY() - ringMin) <= 12) {
                from = f;
                to = t;
            }
        }
        H.check(to != null, "harness: no dry, fairly even ring crossing in 30 tries");
        int z = from.getZ();
        ChunkPos toChunk = new ChunkPos(to);
        ChunkPos ringStart = new ChunkPos(toChunk.x + RING, toChunk.z + RING);
        for (int dx = -RING; dx <= RING; dx++) {
            for (int dz = -RING; dz <= RING; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) == RING) {
                    ChunkPos c = new ChunkPos(toChunk.x + dx, toChunk.z + dz);
                    boolean crossed = crossing.contains(CoordinateKeys.chunkKey(c.x, c.z));
                    H.recordStructure(ringStart, List.of(c), crossed ? ringMin + 2 : level.getMinBuildHeight(),
                            level.getMaxBuildHeight());
                }
            }
        }
        if (pregenerate) {
            for (int cx = (from.getX() >> 4) - 1; cx <= (to.getX() >> 4) + 1; cx++) {
                for (int cz = (z >> 4) - 2; cz <= (z >> 4) + 2; cz++) {
                    level.getChunkSource().getChunk(cx, cz, ChunkStatus.NOISE, true);
                }
            }
        }

        RoadData road = new Road(level, new StructureConnection(from, to), new PathFeatureConfig()).generateRoad(MAX_STEPS);
        H.check(road != null, "generateRoad returned no road");
        boolean[] tunnel = TunnelCarver.getTunnelSegmentsByRoadData(road);
        H.check(tunnel != null, "no tunnel was planned through the ring (crossed ring chunks from y %d, ground %d..%d), "
                        + "returned road: %d segments, tunnel and bridge stretches %s, y %d..%d", ringMin + 2, ringMin, ringMax,
                road.roadSegmentList().size(), road.spans().stream().map(s -> s.type() + "@" + s.start().toShortString()).toList(),
                road.targetY().stream().mapToInt(Integer::intValue).min().orElse(0),
                road.targetY().stream().mapToInt(Integer::intValue).max().orElse(0));

        // Generate every chunk the tunnel touches (and its neighbours) to FULL: paving + carving run.
        Set<Long> chunks = new HashSet<>();
        for (int i = 0; i < tunnel.length; i++) {
            if (tunnel[i]) {
                BlockPos m = road.roadSegmentList().get(i).middlePos();
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        chunks.add(CoordinateKeys.chunkKey((m.getX() >> 4) + dx, (m.getZ() >> 4) + dz));
                    }
                }
            }
        }
        for (long c : chunks) {
            level.getChunk(CoordinateKeys.unpackA(c), CoordinateKeys.unpackB(c));
        }

        /*
            Inside: every road column of the tunnel RWSA dug, from the paved road's first air block up to the
            clearance. Columns of a tunnel stretch outside RWSA's tunnel boxes are RoadWeaver's own tunnels (kept
            spans), which RoadWeaver digs and paves itself only where it paves, so they aren't checked here.
         */
        int clear = RoadWeaverAccessor.roadClearHeight();
        Long2IntOpenHashMap floors = TunnelCarver.roadFloors(List.of(road), Integer.MIN_VALUE / 2, Integer.MIN_VALUE / 2,
                Integer.MAX_VALUE / 2, Integer.MAX_VALUE / 2);
        H.check(!floors.isEmpty(), "no tunnel road columns");
        int solid = 0;
        int checked = 0;
        int roadWeaverOwn = 0;
        List<String> samples = new ArrayList<>();
        for (Long2IntMap.Entry e : floors.long2IntEntrySet()) {
            int x = CoordinateKeys.unpackA(e.getLongKey());
            int zz = CoordinateKeys.unpackB(e.getLongKey());
            if (!inTunnelBoxColumn(x, zz)) {
                roadWeaverOwn++;
                continue;
            }
            checked++;
            for (int y = e.getIntValue(); y < e.getIntValue() + clear; y++) {
                BlockState state = level.getBlockState(new BlockPos(x, y, zz));
                if (!state.isAir() && state.getFluidState().isEmpty()) {
                    solid++;
                    if (samples.size() < 8) {
                        int[] boxes = TunnelCarver.boxesForChunk(x >> 4, zz >> 4);
                        samples.add(x + "," + y + "," + zz + " " + state.getBlock() + " (floor " + e.getIntValue()
                                + ", in box " + TunnelCarver.insideTunnel(boxes, x, y, zz) + ")");
                    }
                }
            }
        }
        H.check(checked > 0, "none of the %d tunnel stretch road columns lies in RWSA's tunnel boxes", floors.size());
        H.check(solid == 0, "%d solid blocks inside the tunnel over %d road columns: %s", solid, checked, samples);
        if (roadWeaverOwn > 0) {
            H.log("tunnel worldgen: {} road columns of RoadWeaver's own tunnel stretches skipped", roadWeaverOwn);
        }

        // No lamps, posts or signs over the inner tunnel (ramp ends excluded: lamps of the open road may stand there).
        int reach = RoadWeaverAccessor.configuredRoadMargin() + 4;
        int lit = 0;
        List<String> litSamples = new ArrayList<>();
        for (int i = 6; i < tunnel.length - 6; i++) {
            if (!tunnel[i] || !tunnel[i - 6] || !tunnel[i + 6]) {
                continue;
            }
            BlockPos m = road.roadSegmentList().get(i).middlePos();
            for (int x = m.getX() - reach; x <= m.getX() + reach; x++) {
                for (int zz = m.getZ() - reach; zz <= m.getZ() + reach; zz++) {
                    int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, zz) + 6;
                    for (int y = road.targetY().get(i) - 1; y <= top; y++) {
                        BlockPos at = new BlockPos(x, y, zz);
                        Block block = level.getBlockState(at).getBlock();
                        if ((block instanceof LanternBlock || block instanceof FenceBlock || block instanceof SignBlock)
                                && !insideStructurePiece(level, at)) {
                            // (a structure's own fences, e.g. a village pen, aren't RoadWeaver's)
                            lit++;
                            if (litSamples.size() < 6) {
                                litSamples.add(x + "," + y + "," + zz + " " + block + " (segment " + i + " middle "
                                        + m.toShortString() + ", road y " + road.targetY().get(i) + ")");
                            }
                        }
                    }
                }
            }
        }
        H.check(lit == 0, "%d lamp/post/sign blocks over the tunnel: %s", lit, litSamples);
        H.log("tunnel worldgen ({}): {} road columns clear, no decorations", pregenerate ? "pre-generated chunks" : "fresh chunks",
                checked);
        helper.succeed();
    }

    /**
     * Tells whether any of RWSA's recorded tunnel boxes covers a column, at any height.
     *
     * @param x the column's X (block)
     * @param z the column's Z (block)
     * @return true if a box's XZ rectangle contains the column
     */
    private static boolean inTunnelBoxColumn(int x, int z) {
        int[] boxes = TunnelCarver.boxesForChunk(x >> 4, z >> 4);
        for (int i = 0; i + TunnelStore.BOX_INTS - 1 < boxes.length; i += TunnelStore.BOX_INTS) {
            if (x >= boxes[i] && x <= boxes[i + 2] && z >= boxes[i + 1] && z <= boxes[i + 3]) {
                return true;
            }
        }
        return false;
    }

    /**
     * Tells whether a block lies in a generated structure's piece.
     *
     * @param level the level
     * @param pos   the block
     * @return true if a structure piece contains it
     */
    private static boolean insideStructurePiece(ServerLevel level, BlockPos pos) {
        for (Structure structure : level.structureManager().getAllStructuresAt(pos).keySet()) {
            if (level.structureManager().getStructureWithPieceAt(pos, structure).isValid()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Finds a test area whose straight route is dry land.
     *
     * @param level the level
     * @param cache RoadWeaver's terrain cache
     * @param x0    the route's start X offset in the area (blocks)
     * @param x1    the route's end X offset in the area (blocks)
     * @param dz    the route's Z offset in the area (blocks)
     * @return the area's corner
     * @throws net.minecraft.gametest.framework.GameTestAssertException when none of 60 areas is dry
     */
    private static BlockPos dryRoute(ServerLevel level, TerrainSamplingCache cache, int x0, int x1, int dz) {
        for (int attempt = 0; attempt < 60; attempt++) {
            BlockPos candidate = H.area();
            boolean dry = true;
            for (int x = candidate.getX() + x0; x <= candidate.getX() + x1 && dry; x += 16) {
                dry = !cache.isColumnWater(level, x, candidate.getZ() + dz);
            }
            if (dry) {
                return candidate;
            }
        }
        H.check(false, "harness: no dry land route found in 60 areas");
        return null;
    }

    /**
     * A tunnel is dug like RoadWeaver's own tunnels, at the terrain stage (Beardifier
     * density) and the decoration stage (carving): on the road's own columns only, from the road's
     * first air block up RoadWeaver's clear height. Beside the road (inside the tunnel's margin box)
     * nothing is dug, so no wide trench or shelves.
     *
     * @param helper the test's helper
     */
    @GameTest(template = HarnessSetup.TEMPLATE)
    public static void tunnelCarvingIsRoadWideAtBothStages(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos a = H.area();
        int x0 = a.getX() + 100;
        int z0 = a.getZ() + 100;
        int z = z0 + 2; // road columns z - 1 .. z + 1
        int floor = 100;
        int clear = TunnelCarver.clearHeight();
        int margin = H.MARGIN;
        /*
            Long enough for x0 .. x0 + 8 to lie in the segments PathFeature paves (not its first 9 nor its last 6,
            one segment every 2 blocks): RoadWeaver never paves a road's ends, so they are never cleared.
         */
        RoadShardStorage.addRoad(level, H.tunnelRoadAlongX(x0 - 30, x0 + 40, z, floor));
        TunnelStore.add(new int[]{x0 - 30 - margin, z - 1 - margin, x0 + 40 + margin, z + 1 + margin, floor,
                floor + clear});
        ChunkPos chunk = new ChunkPos(x0 >> 4, z0 >> 4);
        H.check(TunnelCarver.boxesForChunk(chunk.x, chunk.z).length > 0, "harness: no tunnel box in the chunk");
        int roadX = x0 + 4;
        int besideZ = z + 2; // one past the outermost road column

        // Terrain stage. floor is the road's first air block (RoadWeaver's road Y), the bed is floor - 1.
        Beardifier beardifier = Beardifier.forStructuresInChunk(level.structureManager(), chunk);
        for (int y = floor; y < floor + clear; y++) {
            H.check(beardifier.compute(new DensityFunction.SinglePointContext(roadX, y, z)) <= TunnelCarver.TUNNEL_DENSITY,
                    "road column not dug at y %d (clear height %d)", y, clear);
        }
        H.check(beardifier.compute(new DensityFunction.SinglePointContext(roadX, floor - 1, z)) > TunnelCarver.TUNNEL_DENSITY,
                "the road bed is dug");
        H.check(beardifier.compute(new DensityFunction.SinglePointContext(roadX, floor + clear, z)) > TunnelCarver.TUNNEL_DENSITY,
                "dug above RoadWeaver's clear height");
        H.check(beardifier.compute(new DensityFunction.SinglePointContext(roadX, floor, besideZ)) > TunnelCarver.TUNNEL_DENSITY,
                "dug beside the road (margin box)");

        // Decoration stage.
        for (int x = x0; x <= x0 + 8; x++) {
            for (int zz = z - 1 - margin; zz <= z + 1 + margin; zz++) {
                for (int y = floor - 1; y <= floor + clear + 1; y++) {
                    level.setBlock(new BlockPos(x, y, zz), Blocks.STONE.defaultBlockState(), 2);
                }
            }
        }
        TunnelCarver.carveAround(level, level, chunk);
        for (int y = floor; y < floor + clear; y++) {
            H.check(level.getBlockState(new BlockPos(roadX, y, z)).isAir(), "road column y %d not carved", y);
        }
        H.check(level.getBlockState(new BlockPos(roadX, floor - 1, z)).is(Blocks.STONE), "the road bed was carved");
        H.check(level.getBlockState(new BlockPos(roadX, floor + clear, z)).is(Blocks.STONE),
                "carved above RoadWeaver's clear height");
        H.check(level.getBlockState(new BlockPos(roadX, floor, besideZ)).is(Blocks.STONE), "carved beside the road");
        helper.succeed();
    }

    // ---- Helpers ----

    /** A 80-segment road at y = 100 crossing a one-chunk wall (z +-48) whose lowest Y is `structureMinY`. */
    private static final class TunnelCase {
        final ServerLevel level;
        final List<RoadSegmentPlacement> segments;
        final List<Integer> targetY = new ArrayList<>();
        final StructureConnection connection;
        final RoadGenerationConfig cfg = RoadGenerationConfig.from(ConfigService.get());
        final List<Integer> blocked = new ArrayList<>();

        /**
         * Records the wall and finds the blocked segments.
         *
         * @param level         the level
         * @param area          a fresh test area's corner
         * @param structureMinY the wall's lowest Y
         * @param structureMaxY the wall's highest Y
         */
        TunnelCase(ServerLevel level, BlockPos area, int structureMinY, int structureMaxY) {
            this.level = level;
            int x0 = area.getX() + 64;
            int z = area.getZ() + 1000;
            segments = H.segmentsAlongX(x0, 80, 4, z, 100);
            for (int i = 0; i < segments.size(); i++) {
                targetY.add(100);
            }
            connection = new StructureConnection(segments.get(0).middlePos(), segments.get(segments.size() - 1).middlePos());
            int wallChunkX = (x0 + 160) >> 4;
            H.recordStructure(new ChunkPos(wallChunkX, z >> 4), H.wallAlongZ(wallChunkX, (z - 48) >> 4, (z + 48) >> 4),
                    structureMinY, structureMaxY);
            RoadObstacles obstacles = RoadObstacles.forArea(x0 - 8, z - 8, x0 + 400, z + 8, connection.from().getX(),
                    connection.from().getZ(), connection.to().getX(), connection.to().getZ(),
                    RoadWeaverAccessor.configuredRoadMargin());
            for (int i = 0; i < segments.size(); i++) {
                if (obstacles != null && segmentBlocked(segments.get(i), obstacles)) {
                    blocked.add(i);
                }
            }
        }

        /**
         * Runs RoadTunnelPlanner on the case's road.
         *
         * @return the planner's heights
         */
        List<Integer> plan() {
            return RoadTunnelPlanner.plan(level, connection, segments, List.<RoadSpan>of(), targetY, cfg);
        }

        /**
         * Returns the slope step the planner uses.
         *
         * @return the height step allowed per two segments
         */
        int slopeStep() {
            return !cfg.slopeLimitEnabled() ? 8 : Math.max(0, Math.min(8, cfg.maxSlopeStepPerTwoSegments()));
        }
    }

    /**
     * Picks a chunk in the middle of a test area.
     *
     * @param area the area's corner
     * @return the chunk 1024 blocks into the area on both axes
     */
    private static ChunkPos chunkIn(BlockPos area) {
        return new ChunkPos((area.getX() + 1024) >> 4, (area.getZ() + 1024) >> 4);
    }

    /**
     * Returns the jigsaw start piece's box: the one piece the placer hook can't trim, so the one
     * a structure on a road is rejected on.
     *
     * @param start the start
     * @return its first piece's bounding box
     */
    private static BoundingBox startPiece(StructureStart start) {
        return start.getPieces().get(0).getBoundingBox();
    }

    /**
     * Generates a structure with no road around, as the baseline a test changes.
     *
     * @param level     the level
     * @param structure the structure
     * @param chunk     the start chunk
     * @return the valid start
     * @throws net.minecraft.gametest.framework.GameTestAssertException when it doesn't generate
     */
    private static StructureStart baseline(ServerLevel level, Holder<Structure> structure, ChunkPos chunk) {
        StructureStart start = H.generate(level, structure, chunk);
        H.check(H.isValid(start), "harness: %s doesn't generate at %s even without a road (%s)",
                structure.unwrapKey().map(k -> k.location().toString()).orElse("?"), chunk, H.diagnose(level, structure, chunk));
        return start;
    }

    /**
     * Saves a road along X through the middle of a box, 24 blocks past each side.
     *
     * @param level  the level
     * @param box    the box
     * @param y      the road's height (first air Y)
     * @param ownerA the road's first owner, H.NO_OWNER for none
     */
    private static void addRoadAcross(ServerLevel level, BoundingBox box, int y, long ownerA) {
        int z = (box.minZ() + box.maxZ()) / 2;
        RoadShardStorage.addRoad(level, H.roadAlongX(evenFloor(box.minX() - 24), box.maxX() + 24, z, y, ownerA, H.NO_OWNER));
    }

    /**
     * Rounds down to an even number.
     *
     * @param v the value
     * @return v with its lowest bit cleared
     */
    private static int evenFloor(int v) {
        return v & ~1;
    }

    /**
     * States the conflict rule for a road built by H.roadAlongX(x0, x1, z, y), independently of the addon.
     *
     * @param b     a piece's box
     * @param x0    the road's first X (block)
     * @param x1    the road's last X (block)
     * @param z     the road's Z (block)
     * @param y     the road's height (first air Y)
     * @param clear the road's clearance (blocks)
     * @return true if the box overlaps the road's columns (widened by the margin) and its height band
     */
    private static boolean onRoad(BoundingBox b, int x0, int x1, int z, int y, int clear) {
        int m = H.MARGIN;
        int half = H.WIDTH / 2;
        boolean xz = b.maxX() >= x0 - m && b.minX() <= x1 + m && b.maxZ() >= z - half - m && b.minZ() <= z + half + m;
        return xz && b.maxY() >= y - 1 && b.minY() <= y + clear;
    }

    /**
     * Tells whether the road maps have a road in a chunk.
     *
     * @param chunkX the chunk's X (chunk coordinates)
     * @param chunkZ the chunk's Z (chunk coordinates)
     * @return true if the chunk's region map has entries for it
     */
    private static boolean hasEntries(int chunkX, int chunkZ) {
        RoadInsideRegionChunks region = RoadInsideRegionChunksRecorder.regionAt(chunkX, chunkZ);
        return region != null && region.entriesAt(CoordinateKeys.chunkKey(chunkX, chunkZ)) != null;
    }

    /**
     * Tells whether RoadWeaver's storage has a road segment middle near a column.
     *
     * @param level the level
     * @param x     the column's X (block)
     * @param z     the column's Z (block)
     * @return true if a stored middle lies within 2 blocks
     */
    private static boolean storageHasRoadAt(ServerLevel level, int x, int z) {
        for (RoadData road : RoadShardStorage.queryRect(level, x - 2, z - 2, x + 2, z + 2)) {
            for (RoadSegmentPlacement s : road.roadSegmentList()) {
                if (s.middlePos() != null && Math.abs(s.middlePos().getX() - x) <= 2 && Math.abs(s.middlePos().getZ() - z) <= 2) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Counts the segments whose middle is blocked.
     *
     * @param segments  the path
     * @param obstacles the route's obstacles
     * @return the number of blocked middles
     */
    private static int blockedMiddles(List<RoadSegmentPlacement> segments, RoadObstacles obstacles) {
        int n = 0;
        for (RoadSegmentPlacement s : segments) {
            if (s != null && s.middlePos() != null && blockedAt(obstacles, s.middlePos())) {
                n++;
            }
        }
        return n;
    }

    /**
     * Applies RoadTunnelPlanner's rule for a road whose first air block is pos.getY(): band [y - 1, y + clear].
     *
     * @param obstacles the route's obstacles
     * @param pos       the column, its Y is the road's first air block
     * @return true if blocked
     */
    private static boolean blockedAt(RoadObstacles obstacles, BlockPos pos) {
        return blockedAt(obstacles, pos.getX(), pos.getY(), pos.getZ());
    }

    /**
     * Applies RoadTunnelPlanner's rule for a road whose first air block is y: band [y - 1, y + clear].
     *
     * @param obstacles the route's obstacles
     * @param x         the column's X (block)
     * @param y         the road's first air Y
     * @param z         the column's Z (block)
     * @return true if blocked
     */
    private static boolean blockedAt(RoadObstacles obstacles, int x, int y, int z) {
        return obstacles.isBlocked(x, z, y - 1, y + RoadWeaverAccessor.roadClearHeight());
    }

    /**
     * Merges two packed Y ranges.
     *
     * @param a a packRange value
     * @param b a packRange value
     * @return the range covering both
     */
    private static long unionRange(long a, long b) {
        return StructureChunkRecorder.packRange(Math.min(StructureChunkRecorder.rangeMin(a), StructureChunkRecorder.rangeMin(b)),
                Math.max(StructureChunkRecorder.rangeMax(a), StructureChunkRecorder.rangeMax(b)));
    }

    /**
     * Counts the segments with any column (middle or position) blocked: RoadTunnelPlanner's own test.
     *
     * @param segments  the road's segments
     * @param obstacles the road's obstacles
     * @return the number of blocked segments
     */
    private static int blockedSegments(List<RoadSegmentPlacement> segments, RoadObstacles obstacles) {
        int n = 0;
        for (RoadSegmentPlacement s : segments) {
            if (segmentBlocked(s, obstacles)) {
                n++;
            }
        }
        return n;
    }

    /**
     * Tells whether any column of a segment is blocked, at the segment's own height (its middle's Y).
     *
     * @param s         the segment, may be null
     * @param obstacles the road's obstacles
     * @return true if a column is blocked, false for null
     */
    private static boolean segmentBlocked(RoadSegmentPlacement s, RoadObstacles obstacles) {
        if (s == null || s.middlePos() == null) {
            return false;
        }
        int y = s.middlePos().getY();
        if (blockedAt(obstacles, s.middlePos())) {
            return true;
        }
        if (s.positions() != null) {
            for (BlockPos p : s.positions()) {
                if (p != null && blockedAt(obstacles, p.getX(), y, p.getZ())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Sorts a copy of an array.
     *
     * @param values the values, not changed
     * @return a sorted copy
     */
    private static long[] sorted(long[] values) {
        long[] copy = values.clone();
        Arrays.sort(copy);
        return copy;
    }

    /**
     * Calls ChunkGenerator.tryGenerateStructure (private): the commit point ChunkGeneratorMixin hooks.
     *
     * @param level     the level
     * @param structure the structure to try
     * @param pos       the start chunk
     * @return true if the start was committed to the chunk
     * @throws Exception when the method can't be called
     */
    private static boolean tryGenerateStructure(ServerLevel level, Holder<Structure> structure, ChunkPos pos) throws Exception {
        ChunkAccess chunk = level.getChunk(pos.x, pos.z);
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        Method method = ChunkGenerator.class.getDeclaredMethod("tryGenerateStructure", StructureSet.StructureSelectionEntry.class,
                StructureManager.class, RegistryAccess.class, RandomState.class, StructureTemplateManager.class, long.class,
                ChunkAccess.class, ChunkPos.class, SectionPos.class);
        method.setAccessible(true);
        return (Boolean) method.invoke(generator, new StructureSet.StructureSelectionEntry(structure, 1),
                level.structureManager(), level.registryAccess(), level.getChunkSource().randomState(),
                level.getStructureManager(), level.getSeed(), chunk, pos, SectionPos.bottomOf(chunk));
    }
}
