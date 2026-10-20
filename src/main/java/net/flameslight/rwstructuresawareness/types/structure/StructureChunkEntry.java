package net.flameslight.rwstructuresawareness.types.structure;

/**
 * One recorded structure footprint entry, 24 bytes on disk.
 *
 * @param startChunk the structure's start chunk key (ChunkPos.toLong)
 * @param footprint  the covered chunk with the piece's rectangle inside it (StructureChunkRecorder.packFootprint)
 * @param minY       the piece's lowest Y
 * @param maxY       the piece's highest Y
 */
public record StructureChunkEntry(long startChunk, long footprint, int minY, int maxY) {
}
