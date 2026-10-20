package net.flameslight.rwstructuresawareness.types.road;

/**
 * One road's footprint in one chunk.
 *
 * @param road the road's index in its RoadInsideRegionChunks
 * @param mask 256-bit column mask, 4 longs, bit (localZ << 4) | localX
 * @param minY the lowest road surface Y inside the chunk
 * @param maxY the highest road surface Y inside the chunk
 */
public record RoadChunkEntry(int road, long[] mask, int minY, int maxY) {
}
