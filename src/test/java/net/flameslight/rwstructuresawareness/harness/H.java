package net.flameslight.rwstructuresawareness.harness;

import net.flameslight.rwstructuresawareness.RWStructuresAwareness;
import net.flameslight.rwstructuresawareness.config.CommonConfig;
import net.flameslight.rwstructuresawareness.StructureChunkRecorder;
import net.flameslight.rwstructuresawareness.types.structure.StructureChunkStorage;
import net.flameslight.rwstructuresawareness.utils.CoordinateKeys;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.shiroha233.roadweaver.core.model.RoadData;
import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;
import net.shiroha233.roadweaver.core.model.RoadSpan;
import net.shiroha233.roadweaver.core.model.SpanType;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Shared harness helpers. Everything runs on the server thread, one test at a time. */
final class H {
    /** Each test area is its own 4096-block square (8x8 regions), so tests never see each other's data. */
    static final int AREA_SIZE = 4096;
    /** Randomized per run: the gametest world may be reused, and old runs' files must not matter. */
    static final int BASE = 1_000_000 + new Random().nextInt(1500) * AREA_SIZE;
    static final long NO_OWNER = RoadData.NO_OWNER_2D;
    static final int WIDTH = 3;
    /** RoadWeaverAccessor.marginForWidth(WIDTH). */
    static final int MARGIN = WIDTH / 2 + 1;

    private static int nextArea = 0;

    /**
     * Hands out a fresh test area.
     *
     * @return the area's corner (block coordinates, Y = 0)
     */
    static BlockPos area() {
        int i = nextArea++;
        return new BlockPos(BASE + (i % 64) * AREA_SIZE, 0, BASE + (i / 64) * AREA_SIZE);
    }

    /**
     * Fails the test unless a condition holds.
     *
     * @param condition the condition
     * @param message   a String.format message for the failure
     * @param args      the message's arguments
     * @throws GameTestAssertException when the condition is false
     */
    static void check(boolean condition, String message, Object... args) {
        if (!condition) {
            throw new GameTestAssertException(String.format(message, args));
        }
    }

    /**
     * Logs a harness line.
     *
     * @param message an SLF4J message
     * @param args    its placeholder values
     */
    static void log(String message, Object... args) {
        RWStructuresAwareness.LOGGER.info("[harness] " + message, args);
    }

    // ---- Roads ----

    /**
     * Builds a straight road along X, one segment every 2 blocks, WIDTH columns across.
     *
     * @param x0     the first segment's X (block)
     * @param x1     the last X a segment may have (block)
     * @param z      the road's Z (block)
     * @param y      the road's height (first air Y)
     * @param ownerA one destination, NO_OWNER for none
     * @param ownerB the other destination, NO_OWNER for none
     * @return the road, not saved
     */
    static RoadData roadAlongX(int x0, int x1, int z, int y, long ownerA, long ownerB) {
        List<RoadSegmentPlacement> segments = new ArrayList<>();
        List<Integer> ys = new ArrayList<>();
        int half = WIDTH / 2;
        for (int x = x0; x <= x1; x += 2) {
            List<BlockPos> positions = new ArrayList<>();
            for (int dz = -half; dz <= half; dz++) {
                positions.add(new BlockPos(x, y, z + dz));
            }
            segments.add(new RoadSegmentPlacement(new BlockPos(x, y, z), positions));
            ys.add(y);
        }
        return new RoadData(WIDTH, 0, List.of(Blocks.STONE_BRICKS.defaultBlockState()), List.of(), segments,
                List.of(), ys, ownerA, ownerB);
    }

    /**
     * Builds roadAlongX with one tunnel stretch over the whole road (as RoadTunnelPlanner saves a dug stretch).
     *
     * @param x0 the first segment's X (block)
     * @param x1 the last X a segment may have (block)
     * @param z  the road's Z (block)
     * @param y  the road's height (first air Y)
     * @return the road, not saved, without owners
     */
    static RoadData tunnelRoadAlongX(int x0, int x1, int z, int y) {
        RoadData road = roadAlongX(x0, x1, z, y, NO_OWNER, NO_OWNER);
        List<RoadSegmentPlacement> segments = road.roadSegmentList();
        RoadSpan tunnel = new RoadSpan(segments.get(0).middlePos(), segments.get(segments.size() - 1).middlePos(),
                SpanType.TUNNEL);
        return new RoadData(road.width(), 0, List.of(Blocks.STONE_BRICKS.defaultBlockState()), List.of(), segments,
                List.of(tunnel), road.targetY(), NO_OWNER, NO_OWNER);
    }

    /**
     * Builds segments along X (no RoadData), for the tunnel planner.
     *
     * @param x0    the first segment's X (block)
     * @param count the number of segments
     * @param step  blocks between two segments
     * @param z     the segments' Z (block)
     * @param y     the segments' Y
     * @return the segments, WIDTH positions across each
     */
    static List<RoadSegmentPlacement> segmentsAlongX(int x0, int count, int step, int z, int y) {
        List<RoadSegmentPlacement> segments = new ArrayList<>();
        int half = WIDTH / 2;
        for (int i = 0; i < count; i++) {
            int x = x0 + i * step;
            List<BlockPos> positions = new ArrayList<>();
            for (int dz = -half; dz <= half; dz++) {
                positions.add(new BlockPos(x, y, z + dz));
            }
            segments.add(new RoadSegmentPlacement(new BlockPos(x, y, z), positions));
        }
        return segments;
    }

    /**
     * Returns a road owner at a chunk's corner.
     *
     * @param chunk the chunk
     * @return CoordinateKeys.pack of the chunk's min block X and Z
     */
    static long ownerAtChunk(ChunkPos chunk) {
        return CoordinateKeys.pack(chunk.getMinBlockX(), chunk.getMinBlockZ());
    }

    /**
     * Computes RoadWeaver's PlanningUtils.pos2dKey.
     *
     * @param pos the position
     * @return CoordinateKeys.pack(x, z)
     */
    static long pos2dKey(BlockPos pos) {
        return CoordinateKeys.pack(pos.getX(), pos.getZ());
    }

    // ---- Structures ----

    /**
     * Looks up a registered structure.
     *
     * @param level the level, for its registries
     * @param id    the structure's id
     * @return its holder
     */
    static Holder<Structure> structure(ServerLevel level, String id) {
        return level.registryAccess().registryOrThrow(Registries.STRUCTURE)
                .getHolderOrThrow(ResourceKey.create(Registries.STRUCTURE, new ResourceLocation(id)));
    }

    /**
     * Runs Structure#generate exactly as ChunkGenerator.tryGenerateStructure calls it (any biome accepted).
     *
     * @param level     the level
     * @param structure the structure
     * @param chunk     the start chunk
     * @return generate's start, possibly INVALID_START
     */
    static StructureStart generate(ServerLevel level, Holder<Structure> structure, ChunkPos chunk) {
        ServerChunkCache source = level.getChunkSource();
        ChunkGenerator generator = source.getGenerator();
        return structure.value().generate(level.registryAccess(), generator, generator.getBiomeSource(),
                source.randomState(), level.getStructureManager(), level.getSeed(), chunk, 0, level, h -> true);
    }

    /**
     * Explains why a structure didn't generate.
     *
     * @param level     the level
     * @param structure the structure
     * @param chunk     the start chunk
     * @return whether vanilla found a generation point, and its piece count
     */
    static String diagnose(ServerLevel level, Holder<Structure> structure, ChunkPos chunk) {
        ServerChunkCache source = level.getChunkSource();
        ChunkGenerator generator = source.getGenerator();
        Structure.GenerationContext context = new Structure.GenerationContext(level.registryAccess(), generator,
                generator.getBiomeSource(), source.randomState(), level.getStructureManager(), level.getSeed(), chunk,
                level, h -> true);
        try {
            var stub = structure.value().findValidGenerationPoint(context);
            if (stub.isEmpty()) {
                return "no generation point";
            }
            return "generation point " + stub.get().position() + ", "
                    + stub.get().getPiecesBuilder().build().pieces().size() + " pieces";
        } catch (RuntimeException e) {
            return "threw " + e;
        }
    }

    /**
     * Tells whether a start generated.
     *
     * @param start the start, may be null
     * @return true for a valid start that isn't INVALID_START
     */
    static boolean isValid(StructureStart start) {
        return start != null && start != StructureStart.INVALID_START && start.isValid();
    }

    /**
     * Returns the generator's surface at a column.
     *
     * @param level the level
     * @param x     the column's X (block)
     * @param z     the column's Z (block)
     * @return the first air Y above the terrain (the road surface goes one below)
     */
    static int surfaceY(ServerLevel level, int x, int z) {
        ServerChunkCache source = level.getChunkSource();
        return source.getGenerator().getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, level, source.randomState());
    }

    /**
     * Returns a start's whole bounding box.
     *
     * @param start the start
     * @return its bounding box
     */
    static BoundingBox bounds(StructureStart start) {
        return start.getBoundingBox();
    }

    // ---- Config ----

    /**
     * Sets structuresAllowedOnRoad (in memory) and re-parses it.
     *
     * @param ids the structure ids
     */
    static void setAllowedOnRoad(List<String> ids) {
        CommonConfig.STRUCTURES_ALLOWED_ON_ROAD.set(ids);
        CommonConfig.refresh();
    }

    // ---- Recorded structure chunks ----

    /**
     * Returns the open footprint store.
     *
     * @return the store
     * @throws GameTestAssertException when the overworld load hook never opened one
     */
    static StructureChunkStorage store() {
        StructureChunkStorage store = StructureChunkRecorder.store();
        check(store != null, "StructureChunkRecorder has no store: the overworld load hook (ServerEvents) never ran");
        return store;
    }

    /**
     * Records a fake structure covering whole chunks.
     *
     * @param startChunk the structure's start chunk
     * @param chunks     the chunks it covers
     * @param minY       its lowest Y
     * @param maxY       its highest Y
     */
    static void recordStructure(ChunkPos startChunk, List<ChunkPos> chunks, int minY, int maxY) {
        for (ChunkPos c : chunks) {
            recordFootprint(startChunk, c, 0, 0, 15, 15, minY, maxY);
        }
    }

    /**
     * Records one footprint.
     *
     * @param startChunk the structure's start chunk
     * @param chunk      the covered chunk
     * @param x0         the rectangle's min X (chunk-local, 0..15)
     * @param z0         the rectangle's min Z (chunk-local, 0..15)
     * @param x1         the rectangle's max X (chunk-local, 0..15)
     * @param z1         the rectangle's max Z (chunk-local, 0..15)
     * @param minY       the piece's lowest Y
     * @param maxY       the piece's highest Y
     */
    static void recordFootprint(ChunkPos startChunk, ChunkPos chunk, int x0, int z0, int x1, int z1, int minY, int maxY) {
        store().append(CoordinateKeys.regionOfChunk(chunk.x, chunk.z), CoordinateKeys.chunkKey(startChunk.x, startChunk.z),
                StructureChunkRecorder.packFootprint(chunk.x, chunk.z, x0, z0, x1, z1), minY, maxY);
    }

    /**
     * Lists the chunks of a one-chunk-thick wall along Z.
     *
     * @param chunkX  the wall's chunk X
     * @param chunkZ0 the first chunk Z (inclusive)
     * @param chunkZ1 the last chunk Z (inclusive)
     * @return the chunks
     */
    static List<ChunkPos> wallAlongZ(int chunkX, int chunkZ0, int chunkZ1) {
        List<ChunkPos> out = new ArrayList<>();
        for (int cz = chunkZ0; cz <= chunkZ1; cz++) {
            out.add(new ChunkPos(chunkX, cz));
        }
        return out;
    }

    /**
     * Lists the recorded entries of one structure in a region.
     *
     * @param startChunk the structure's start chunk
     * @param inRegionOf any chunk of the region to read
     * @return {footprint, minY, maxY} per entry
     */
    static List<long[]> recordedFootprintsOf(ChunkPos startChunk, ChunkPos inRegionOf) {
        long[] entries = store().read(CoordinateKeys.regionOfChunk(inRegionOf.x, inRegionOf.z));
        long startKey = CoordinateKeys.chunkKey(startChunk.x, startChunk.z);
        List<long[]> out = new ArrayList<>();
        for (int i = 0; i + StructureChunkStorage.STRIDE - 1 < entries.length; i += StructureChunkStorage.STRIDE) {
            if (entries[i] == startKey) {
                out.add(new long[]{entries[i + 1], entries[i + 2], entries[i + 3]});
            }
        }
        return out;
    }
}
