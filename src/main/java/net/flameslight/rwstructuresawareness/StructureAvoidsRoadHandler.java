package net.flameslight.rwstructuresawareness;

import net.flameslight.rwstructuresawareness.types.road.RoadLevel;
import net.flameslight.rwstructuresawareness.config.CommonConfig;
import net.flameslight.rwstructuresawareness.roadweaver.RoadWeaverAccessor;
import net.flameslight.rwstructuresawareness.utils.YPlaceholderUtils;
import net.flameslight.rwstructuresawareness.types.road.RoadChunkEntry;
import net.flameslight.rwstructuresawareness.types.road.RoadInsideRegionChunks;
import net.flameslight.rwstructuresawareness.utils.CoordinateKeys;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Keeps structures from generating on RoadWeaver roads. For the structure being generated on each worker
 * thread it remembers the start chunk, and answers whether a jigsaw piece (the placer hook, which then
 * skips the piece so the structure grows around the road) or the finished start (Structure#generate RETURN,
 * which then rejects the whole structure) sits on a road. The check starts at Structure#generate HEAD and
 * ends at RETURN.
 *
 * A piece conflicts with a road when it overlaps a road column (widened by the road margin)
 * horizontally AND overlaps the road vertically, from just below the road surface up to its
 * clearance. So underground structures under a road, structures above a tunnel and structures under
 * a bridge are all fine. A road leading to the structure (its RoadWeaver destination) ends at the
 * structure's locate position, inside its start piece, so the structure as a whole may sit on it, but
 * a jigsaw's other pieces (a village's houses and streets) avoid it like any road, so the village
 * grows around it.
 */
public final class StructureAvoidsRoadHandler {
    /** Chunks searched around a box, covers any road margin (half-width + 1). */
    private static final int SEARCH_PAD = 16;

    /** The structure being checked on this worker thread. */
    private static final ThreadLocal<StructureAvoidsRoadHandler> CURRENT = ThreadLocal.withInitial(StructureAvoidsRoadHandler::new);

    private boolean active;
    private int startChunkX;
    private int startChunkZ;
    private int clearHeight = -1;

    /** Last chunk looked up: a jigsaw tests thousands of candidate pieces in the same few chunks. */
    private long lastChunkKey = Long.MIN_VALUE;
    private RoadInsideRegionChunks lastRegion;
    private RoadChunkEntry[] lastEntries;

    /** Creates a worker thread's check state. Only CURRENT creates one. */
    private StructureAvoidsRoadHandler() {}

    /**
     * Starts the road check for a structure about to generate. Called at Structure#generate HEAD.
     * <p>
     * No check runs for other dimensions, for structures in structuresAllowedOnRoad, and while no road exists.
     *
     * @param generator   the chunk generator, tells the dimension
     * @param structureId the structure's id
     * @param chunkPos    the structure's start chunk
     */
    public static void begin(ChunkGenerator generator, ResourceLocation structureId, ChunkPos chunkPos) {
        StructureAvoidsRoadHandler current = CURRENT.get();
        current.clear();
        if (!RoadLevel.isRoadGenerator(generator) || CommonConfig.isAllowedOnRoad(structureId)
                || !RoadInsideRegionChunksRecorder.mayRoadsExist()) {
            return;
        }
        current.active = true;
        current.startChunkX = chunkPos.x;
        current.startChunkZ = chunkPos.z;
    }

    /** Ends the road check of this thread's structure. Called at Structure#generate RETURN. */
    public static void end() {
        CURRENT.get().clear();
    }

    /**
     * Tells whether a jigsaw candidate piece would sit on a road. Called from the jigsaw placer hook.
     * <p>
     * Candidates are never the start piece, so a road leading to this structure blocks them too.
     *
     * @param candidate vanilla's test shape of the candidate (its box deflated by 0.25)
     * @return true if the piece would sit on a road, false while no structure is being checked
     */
    public static boolean blocksCandidate(VoxelShape candidate) {
        StructureAvoidsRoadHandler current = CURRENT.get();
        if (!current.active) {
            return false;
        }
        // vanilla's test shape is the piece box deflated by 0.25, flooring gives back the block box
        return current.hits(Mth.floor(candidate.min(Direction.Axis.X)), Mth.floor(candidate.min(Direction.Axis.Y)),
                Mth.floor(candidate.min(Direction.Axis.Z)), Mth.floor(candidate.max(Direction.Axis.X)),
                Mth.floor(candidate.max(Direction.Axis.Y)), Mth.floor(candidate.max(Direction.Axis.Z)), false, false);
    }

    /**
     * Tells whether any piece of a generated start sits on a road. Called at Structure#generate RETURN.
     * <p>
     * A placeholder-Y piece (YPlaceholderUtils) lands on the ground later, wherever that is, so it conflicts at
     * any road height. Buried treasure always ends up underground and never does. Roads leading to this
     * structure don't count here: a jigsaw's other pieces were already kept off them (blocksCandidate), and
     * a non-jigsaw structure can't drop pieces, so rejecting it would lose the road's destination.
     *
     * @param start the generated start, not changed
     * @return true if the start must be rejected, false while no structure is being checked
     */
    public static boolean violates(StructureStart start) {
        StructureAvoidsRoadHandler current = CURRENT.get();
        if (!current.active) {
            return false;
        }
        for (StructurePiece piece : start.getPieces()) {
            if (YPlaceholderUtils.isBuriedPiece(piece)) {
                continue;
            }
            BoundingBox b = piece.getBoundingBox();
            if (current.hits(b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ(), YPlaceholderUtils.hasPlaceholderY(piece),
                    true)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Tells whether a block box overlaps a road.
     *
     * @param minX            the box's min X (block)
     * @param minY            the box's min Y
     * @param minZ            the box's min Z (block)
     * @param maxX            the box's max X (block)
     * @param maxY            the box's max Y
     * @param maxZ            the box's max Z (block)
     * @param anyY            true to ignore heights (a placeholder-Y piece)
     * @param ownRoadsAllowed true to skip roads leading to this structure
     * @return true if the box overlaps a road column (widened by the margin) and the road's height band
     */
    private boolean hits(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, boolean anyY,
                         boolean ownRoadsAllowed) {
        for (int cx = (minX - SEARCH_PAD) >> 4; cx <= (maxX + SEARCH_PAD) >> 4; cx++) {
            for (int cz = (minZ - SEARCH_PAD) >> 4; cz <= (maxZ + SEARCH_PAD) >> 4; cz++) {
                long key = CoordinateKeys.chunkKey(cx, cz);
                RoadInsideRegionChunks region;
                RoadChunkEntry[] entries;
                if (key == lastChunkKey) {
                    region = lastRegion;
                    entries = lastEntries;
                } else {
                    region = RoadInsideRegionChunksRecorder.regionAt(cx, cz);
                    entries = region == null ? null : region.entriesAt(key);
                    lastChunkKey = key;
                    lastRegion = region;
                    lastEntries = entries;
                }
                if (entries == null) {
                    continue;
                }
                int m = region.margin();
                int chunkMinX = cx << 4;
                int chunkMinZ = cz << 4;
                int lx0 = Math.max(minX - m, chunkMinX) - chunkMinX;
                int lz0 = Math.max(minZ - m, chunkMinZ) - chunkMinZ;
                int lx1 = Math.min(maxX + m, chunkMinX + 15) - chunkMinX;
                int lz1 = Math.min(maxZ + m, chunkMinZ + 15) - chunkMinZ;
                if (lx0 > lx1 || lz0 > lz1) {
                    continue;
                }
                for (RoadChunkEntry e : entries) {
                    if (!anyY && (maxY < e.minY() - 1 || minY > e.maxY() + clearHeight())) {
                        continue; // above the road's clearance or below its bed
                    }
                    if (ownRoadsAllowed && region.roadLeadsTo(e.road(), startChunkX, startChunkZ)) {
                        continue; // this structure is that road's destination
                    }
                    if (RoadInsideRegionChunks.maskIntersects(e.mask(), lx0, lz0, lx1, lz1)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Returns RoadWeaver's road clearance, read once per structure.
     *
     * @return the clear height above a road surface (blocks)
     */
    private int clearHeight() {
        if (clearHeight < 0) {
            clearHeight = RoadWeaverAccessor.roadClearHeight();
        }
        return clearHeight;
    }

    /** Stops checking and drops the cached lookups. */
    private void clear() {
        active = false;
        clearHeight = -1;
        lastChunkKey = Long.MIN_VALUE;
        lastRegion = null; // don't pin a region map to this worker thread
        lastEntries = null;
    }
}
