package net.flameslight.rwstructuresawareness.types.road;

import net.flameslight.rwstructuresawareness.utils.CoordinateKeys;

/**
 * One RoadWeaver road reduced to what the road check needs to keep structures off roads: its block columns with the
 * road surface Y at each, its XZ bounds, its half-width margin, and its two destinations (the positions RoadWeaver
 * connected), used so a destination structure never avoids its own road.
 *
 * @param packedColumns the road's block columns, CoordinateKeys.pack(x, z)
 * @param surfaceY      the road surface Y of each column, same order as packedColumns
 * @param minX          the lowest column X (block)
 * @param minZ          the lowest column Z (block)
 * @param maxX          the highest column X (block)
 * @param maxZ          the highest column Z (block)
 * @param margin        the road's half-width + 1 (blocks)
 * @param ownerA        CoordinateKeys.pack(x, z) of one destination, Long.MIN_VALUE if unknown
 * @param ownerB        CoordinateKeys.pack(x, z) of the other destination, Long.MIN_VALUE if unknown
 */
public record RoadColumns(long[] packedColumns, int[] surfaceY, int minX, int minZ, int maxX, int maxZ,
                          int margin, long ownerA, long ownerB) {
    public static final RoadColumns EMPTY = new RoadColumns(new long[0], new int[0], 0, 0, 0, 0, 0,
            Long.MIN_VALUE, Long.MIN_VALUE);

    /**
     * How far a road destination may lie outside its structure's start chunk (blocks). RoadWeaver's destination
     * positions are StructurePlacement.getLocatePos(startChunk), the start chunk's corner plus a locateOffset of
     * at most 16 blocks.
     */
    public static final int DESTINATION_MARGIN = 16;

    /**
     * Tells whether the road has no column.
     *
     * @return true if packedColumns is empty
     */
    public boolean isEmpty() {
        return packedColumns.length == 0;
    }

    /**
     * Tells whether one of this road's destinations is the structure starting in a chunk.
     *
     * @param startChunkX the structure's start chunk X (chunk coordinates)
     * @param startChunkZ the structure's start chunk Z (chunk coordinates)
     * @return true if either destination is near that start chunk
     */
    public boolean leadsTo(int startChunkX, int startChunkZ) {
        return ownerNear(ownerA, startChunkX, startChunkZ) || ownerNear(ownerB, startChunkX, startChunkZ);
    }

    /**
     * Tells whether a road destination is the structure starting in a chunk.
     *
     * @param owner       CoordinateKeys.pack(x, z) of the destination, Long.MIN_VALUE if unknown
     * @param startChunkX the structure's start chunk X (chunk coordinates)
     * @param startChunkZ the structure's start chunk Z (chunk coordinates)
     * @return true if the destination is known and near that start chunk
     */
    public static boolean ownerNear(long owner, int startChunkX, int startChunkZ) {
        return owner != Long.MIN_VALUE && CoordinateKeys.isColumnLiesOnChunk(CoordinateKeys.unpackA(owner),
                CoordinateKeys.unpackB(owner), startChunkX, startChunkZ, DESTINATION_MARGIN);
    }
}
