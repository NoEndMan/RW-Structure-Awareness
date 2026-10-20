package net.flameslight.rwstructuresawareness.utils;

import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.ScatteredFeaturePiece;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.structures.BuriedTreasurePieces;
import net.minecraft.world.level.levelgen.structure.structures.IglooPieces;
import net.minecraft.world.level.levelgen.structure.structures.OceanRuinPieces;
import net.minecraft.world.level.levelgen.structure.structures.ShipwreckPieces;

/**
 * Some vanilla pieces get a placeholder Y when their structure start is generated (64 for desert
 * pyramids, jungle temples and swamp huts, 90 for igloos, shipwrecks, ocean ruins and buried
 * treasure) and are only moved to the ground when the piece is placed (postProcess). Their box Y at
 * generate time says nothing about where they end up, so the road check (StructureAvoidsRoadHandler)
 * treats them as conflicting at any road height, and the footprint recorder (StructureChunkRecorder)
 * estimates their real height with getYRanges.
 */
public final class YPlaceholderUtils {
    /**
     * Tells whether a surface piece's generate-time Y is a placeholder.
     *
     * @param piece the piece
     * @return true for scattered features, igloos, shipwrecks and ocean ruins
     */
    public static boolean hasPlaceholderY(StructurePiece piece) {
        return piece instanceof ScatteredFeaturePiece
                || piece instanceof IglooPieces.IglooPiece
                || piece instanceof ShipwreckPieces.ShipwreckPiece
                || piece instanceof OceanRuinPieces.OceanRuinPiece;
    }

    /**
     * Tells whether a piece always ends up below ground (its placeholder Y of 90 would otherwise look like a
     * surface piece).
     *
     * @param piece the piece
     * @return true for buried treasure
     */
    public static boolean isBuriedPiece(StructurePiece piece) {
        return piece instanceof BuriedTreasurePieces.BuriedTreasurePiece;
    }

    /**
     * Estimates where a placeholder piece will land.
     * <p>
     * The generator's terrain height (ocean floor, so underwater pieces sit on the floor) at the box corners,
     * extended down and up by the box height. Errs low, so a tunnel planned from it passes below the piece.
     *
     * @param piece          the piece, not changed
     * @param generator      the chunk generator
     * @param heightAccessor the level's height limits
     * @param randomState    the generator's random state
     * @return {minY, maxY}
     */
    public static int[] getYRanges(StructurePiece piece, ChunkGenerator generator, LevelHeightAccessor heightAccessor,
                                   RandomState randomState) {
        BoundingBox b = piece.getBoundingBox();
        int lowest = Integer.MAX_VALUE;
        int highest = Integer.MIN_VALUE;
        int[][] corners = {{b.minX(), b.minZ()}, {b.maxX(), b.minZ()}, {b.minX(), b.maxZ()}, {b.maxX(), b.maxZ()}};
        for (int[] c : corners) {
            int h = generator.getBaseHeight(c[0], c[1], Heightmap.Types.OCEAN_FLOOR_WG, heightAccessor, randomState);
            lowest = Math.min(lowest, h);
            highest = Math.max(highest, h);
        }
        int span = b.getYSpan();
        return new int[]{lowest - span, highest + span};
    }
}
