package net.flameslight.rwstructuresawareness;

import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import net.flameslight.rwstructuresawareness.config.CommonConfig;
import net.flameslight.rwstructuresawareness.types.structure.StructureChunkStorage;
import net.flameslight.rwstructuresawareness.utils.CoordinateKeys;
import net.flameslight.rwstructuresawareness.utils.YPlaceholderUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;

/**
 * Records the footprint of every committed overworld structure, so roads RoadWeaver plans
 * later route around them (or tunnel under them). Included zone structures.
 * The only exceptions are:
 *  1. The structures configured in structuresAllowedOnRoad are not recorded.
 *  2. a structure that none of its pieces reaches y >= SURFACE_MIN_Y.
 *  3. Buried treasure.
 *  Every piece of a recorded structure (jigsaw or not) is recorded, split per chunk: the piece's rectangle inside that
 *  chunk and its own Y range, so a road or tunnel only avoids what it would really touch.
 * Entries reach disk every 15 seconds (ServerEvents). Nothing is kept in memory after.
 */
public final class StructureChunkRecorder {
    /** A structure whose pieces all top out below this is underground and never blocks a road. */
    public static final int SURFACE_MIN_Y = 64;
    /** Footprint format (v2): a new folder, so footprints never mix with the old whole-chunk entries. */
    private static final String FOLDER = "structure_footprints";

    private static final int CHUNK_BITS = 22;
    private static final long CHUNK_MASK = (1L << CHUNK_BITS) - 1;
    private static final int RECT_BITS = 16;
    private static final int CHUNK_X_SHIFT = RECT_BITS + CHUNK_BITS; // X in bits 38..59, Z in 16..37
    private static final long NO_RANGE = packRange(Integer.MAX_VALUE, Integer.MIN_VALUE);

    private static volatile StructureChunkStorage store;

    // ---- Lifecycle ----

    /**
     * Opens a world's footprints.
     *
     * @param overworld the overworld, its dimension id names the files
     * @param dataDir   the world's data folder
     */
    public static void open(ServerLevel overworld, Path dataDir) {
        store = new StructureChunkStorage(dataDir.resolve(FOLDER),
                overworld.dimension().location().toString().replace(':', '_'));
    }

    /** Writes the pending footprints and closes the store. */
    public static void close() {
        StructureChunkStorage s = store;
        store = null;
        if (s != null) {
            s.flush();
        }
    }

    /** Drops the store without writing. */
    public static void reset() {
        store = null;
    }

    /**
     * Tells whether footprints are waiting to be written.
     *
     * @return true if a store is open and has pending entries
     */
    public static boolean hasPending() {
        StructureChunkStorage s = store;
        return s != null && s.hasPending();
    }

    /** Writes the pending footprints, if a store is open. */
    public static void flushPending() {
        StructureChunkStorage s = store;
        if (s != null) {
            s.flush();
        }
    }

    /**
     * Returns the open store.
     *
     * @return the store, null when no world is open
     */
    @Nullable
    public static StructureChunkStorage store() {
        return store;
    }

    // ---- Recording ----

    /**
     * Records a structure start once it has been committed to its chunk. Called from ChunkGeneratorMixin.
     *
     * @param chunk       the chunk holding the start
     * @param startChunk  the start's chunk position
     * @param structure   the structure
     * @param structureId the structure's id
     * @param generator   estimates where placeholder-Y pieces will land (YPlaceholderUtils.getYRanges)
     * @param randomState estimates where placeholder-Y pieces will land (YPlaceholderUtils.getYRanges)
     */
    public static void onStructureCommitted(ChunkAccess chunk, ChunkPos startChunk, Structure structure,
                                            ResourceLocation structureId, ChunkGenerator generator,
                                            RandomState randomState) {
        StructureChunkStorage s = store;
        if (s == null || CommonConfig.isAllowedOnRoad(structureId)) {
            return;
        }
        StructureStart start = chunk.getStartForStructure(structure);
        if (start == null || !start.isValid()) {
            return;
        }

        // footprint (chunk + rectangle) -> Y range, identical rectangles merge their ranges.
        Long2LongOpenHashMap footprints = new Long2LongOpenHashMap();
        footprints.defaultReturnValue(NO_RANGE);
        boolean reachesSurface = false;
        for (StructurePiece piece : start.getPieces()) {
            if (YPlaceholderUtils.isBuriedPiece(piece)) {
                continue; // always underground, whatever its placeholder Y says
            }
            BoundingBox b = piece.getBoundingBox();
            int minY = b.minY();
            int maxY = b.maxY();
            if (YPlaceholderUtils.hasPlaceholderY(piece)) {
                int[] range = YPlaceholderUtils.getYRanges(piece, generator, chunk, randomState);
                minY = range[0];
                maxY = range[1];
            }
            reachesSurface |= maxY >= SURFACE_MIN_Y;
            for (int cx = b.minX() >> 4; cx <= b.maxX() >> 4; cx++) {
                for (int cz = b.minZ() >> 4; cz <= b.maxZ() >> 4; cz++) {
                    int x0 = Math.max(b.minX(), cx << 4) & 15;
                    int z0 = Math.max(b.minZ(), cz << 4) & 15;
                    int x1 = Math.min(b.maxX(), (cx << 4) + 15) & 15;
                    int z1 = Math.min(b.maxZ(), (cz << 4) + 15) & 15;
                    long key = packFootprint(cx, cz, x0, z0, x1, z1);
                    long merged = footprints.get(key);
                    footprints.put(key, packRange(Math.min(rangeMin(merged), minY), Math.max(rangeMax(merged), maxY)));
                }
            }
        }
        if (!reachesSurface) {
            return; // fully underground
        }

        long startKey = CoordinateKeys.chunkKey(startChunk.x, startChunk.z);
        for (Long2LongMap.Entry e : footprints.long2LongEntrySet()) {
            long footprint = e.getLongKey();
            s.append(CoordinateKeys.regionOfChunk(chunkXOf(footprint), chunkZOf(footprint)), startKey, footprint,
                    rangeMin(e.getLongValue()), rangeMax(e.getLongValue()));
        }
    }

    // ---- Footprint packing: chunk X and Z (22 bits each, signed) + local rectangle (4 bits per edge) ----

    /**
     * Packs a chunk and a rectangle inside it.
     *
     * @param chunkX    the chunk's X (chunk coordinates, 22 bits signed)
     * @param chunkZ    the chunk's Z (chunk coordinates, 22 bits signed)
     * @param localMinX the rectangle's min X (chunk-local, 0..15)
     * @param localMinZ the rectangle's min Z (chunk-local, 0..15)
     * @param localMaxX the rectangle's max X (chunk-local, 0..15)
     * @param localMaxZ the rectangle's max Z (chunk-local, 0..15)
     * @return the packed footprint
     */
    public static long packFootprint(int chunkX, int chunkZ, int localMinX, int localMinZ, int localMaxX, int localMaxZ) {
        long rect = (localMinX & 15) | ((localMinZ & 15) << 4) | ((localMaxX & 15) << 8) | ((long) (localMaxZ & 15) << 12);
        return ((chunkX & CHUNK_MASK) << CHUNK_X_SHIFT) | ((chunkZ & CHUNK_MASK) << RECT_BITS) | rect;
    }

    /**
     * Unpacks a footprint's chunk X.
     *
     * @param footprint a packFootprint value
     * @return the chunk X (chunk coordinates)
     */
    public static int chunkXOf(long footprint) {
        return (int) (footprint << (64 - CHUNK_X_SHIFT - CHUNK_BITS) >> (64 - CHUNK_BITS));
    }

    /**
     * Unpacks a footprint's chunk Z.
     *
     * @param footprint a packFootprint value
     * @return the chunk Z (chunk coordinates)
     */
    public static int chunkZOf(long footprint) {
        return (int) (footprint << (64 - RECT_BITS - CHUNK_BITS) >> (64 - CHUNK_BITS));
    }

    /**
     * Unpacks a footprint's west edge.
     *
     * @param footprint a packFootprint value
     * @return the rectangle's min X (block)
     */
    public static int minXOf(long footprint) {
        return (chunkXOf(footprint) << 4) + (int) (footprint & 15);
    }

    /**
     * Unpacks a footprint's north edge.
     *
     * @param footprint a packFootprint value
     * @return the rectangle's min Z (block)
     */
    public static int minZOf(long footprint) {
        return (chunkZOf(footprint) << 4) + (int) ((footprint >> 4) & 15);
    }

    /**
     * Unpacks a footprint's east edge.
     *
     * @param footprint a packFootprint value
     * @return the rectangle's max X (block)
     */
    public static int maxXOf(long footprint) {
        return (chunkXOf(footprint) << 4) + (int) ((footprint >> 8) & 15);
    }

    /**
     * Unpacks a footprint's south edge.
     *
     * @param footprint a packFootprint value
     * @return the rectangle's max Z (block)
     */
    public static int maxZOf(long footprint) {
        return (chunkZOf(footprint) << 4) + (int) ((footprint >> 12) & 15);
    }

    // ---- Y ranges packed in a long: min in the high half, max in the low half ----

    /**
     * Packs a Y range.
     *
     * @param minY the lowest Y
     * @param maxY the highest Y
     * @return min in the high 32 bits, max in the low 32 bits
     */
    public static long packRange(int minY, int maxY) {
        return ((long) minY << 32) | (maxY & 0xffffffffL);
    }

    /**
     * Unpacks a range's lowest Y.
     *
     * @param range a packRange value
     * @return the lowest Y
     */
    public static int rangeMin(long range) {
        return (int) (range >> 32);
    }

    /**
     * Unpacks a range's highest Y.
     *
     * @param range a packRange value
     * @return the highest Y
     */
    public static int rangeMax(long range) {
        return (int) range;
    }
}
