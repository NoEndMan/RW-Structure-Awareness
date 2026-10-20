package net.flameslight.rwstructuresawareness.utils;

/**
 * Packs block, chunk and region coordinates into long keys, and unpacks them. Shared by every store and index in
 * this mod. Also tells whether a block column lies inside a chunk.
 */
public final class CoordinateKeys {
    /** 512x512-block regions (32x32 chunks), the unit every per-area store is filed under. */
    public static final int REGION_BLOCK_SHIFT = 9;
    public static final int REGION_CHUNK_SHIFT = REGION_BLOCK_SHIFT - 4;

    /**
     * Packs two ints into a long.
     *
     * @param a the high int (an X)
     * @param b the low int (a Z)
     * @return the packed key
     */
    public static long pack(int a, int b) {
        return (((long) a) << 32) ^ (b & 0xffffffffL);
    }

    /**
     * Unpacks a key's high int.
     *
     * @param key a pack value
     * @return its first int (the X)
     */
    public static int unpackA(long key) {
        return (int) (key >> 32);
    }

    /**
     * Unpacks a key's low int.
     *
     * @param key a pack value
     * @return its second int (the Z)
     */
    public static int unpackB(long key) {
        return (int) key;
    }

    /**
     * Packs a chunk position.
     *
     * @param chunkX the chunk's X (chunk coordinates)
     * @param chunkZ the chunk's Z (chunk coordinates)
     * @return the chunk key
     */
    public static long chunkKey(int chunkX, int chunkZ) {
        return pack(chunkX, chunkZ);
    }

    /**
     * Returns the region holding a chunk.
     *
     * @param chunkX the chunk's X (chunk coordinates)
     * @param chunkZ the chunk's Z (chunk coordinates)
     * @return the region key, pack(regionX, regionZ)
     */
    public static long regionOfChunk(int chunkX, int chunkZ) {
        return pack(chunkX >> REGION_CHUNK_SHIFT, chunkZ >> REGION_CHUNK_SHIFT);
    }

    /**
     * Returns the region holding a block column.
     *
     * @param blockX the column's X (block)
     * @param blockZ the column's Z (block)
     * @return the region key, pack(regionX, regionZ)
     */
    public static long regionOfBlock(int blockX, int blockZ) {
        return pack(blockX >> REGION_BLOCK_SHIFT, blockZ >> REGION_BLOCK_SHIFT);
    }

    /**
     * Tells whether a block column lies inside a chunk, optionally widened on every side.
     *
     * @param x      the column's X (block)
     * @param z      the column's Z (block)
     * @param chunkX the chunk's X (chunk coordinates)
     * @param chunkZ the chunk's Z (chunk coordinates)
     * @param margin blocks added to the chunk on every side, 0 for the chunk itself
     * @return true if the column lies inside the chunk widened by {@code margin}
     */
    public static boolean isColumnLiesOnChunk(int x, int z, int chunkX, int chunkZ, int margin) {
        int minX = (chunkX << 4) - margin;
        int minZ = (chunkZ << 4) - margin;
        int size = 16 + 2 * margin;
        return x >= minX && x < minX + size && z >= minZ && z < minZ + size;
    }
}
