package net.flameslight.rwstructuresawareness.io;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.flameslight.rwstructuresawareness.logger.ModLogger;
import net.flameslight.rwstructuresawareness.utils.CoordinateKeys;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.Set;

/**
 * The structure footprint files of one world: one append-only file per 512x512-block region
 * (<prefix>_<rx>_<rz>.bin), 24 bytes per entry (start chunk key, footprint, minY, maxY).
 * <p>
 * Not thread-safe on its own: StructureChunkStore serializes every call under its file lock, which also
 * guards its decoded-file cache.
 */
public final class StructureChunkIO {
    /** Longs per entry in the arrays read and written here: startChunk, footprint, minY, maxY. */
    public static final int STRIDE = 4;
    private static final String SUFFIX = ".bin";

    private final Path dir;
    private final String prefix;

    /**
     * Creates the IO for one world's footprint folder.
     *
     * @param dir    the footprint folder, created on the first write
     * @param prefix the file name prefix (the dimension id)
     */
    public StructureChunkIO(Path dir, String prefix) {
        this.dir = dir;
        this.prefix = prefix;
    }

    /**
     * Lists the regions that have a footprint file.
     *
     * @return the region keys (CoordinateKeys.pack(regionX, regionZ)), empty if the folder is missing or unreadable
     */
    public Set<Long> listRegions() {
        Set<Long> out = new HashSet<>();
        if (!Files.isDirectory(dir)) {
            return out;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, prefix + "_*" + SUFFIX)) {
            for (Path path : stream) {
                String name = path.getFileName().toString();
                String[] parts = name.substring(prefix.length() + 1, name.length() - SUFFIX.length()).split("_");
                if (parts.length != 2) {
                    continue;
                }
                try {
                    out.add(CoordinateKeys.pack(Integer.parseInt(parts[0]), Integer.parseInt(parts[1])));
                } catch (NumberFormatException ignored) {
                    // not one of ours
                }
            }
        } catch (IOException e) {
            ModLogger.error("Could not list recorded structure footprint files in {}", dir, e);
        }
        return out;
    }

    /**
     * Appends entries to one region's file, creating the folder and file as needed.
     *
     * @param regionKey CoordinateKeys.pack(regionX, regionZ)
     * @param entries   STRIDE longs per entry (startChunk, footprint, minY, maxY), not changed
     */
    public void appendRegion(long regionKey, long[] entries) {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            ModLogger.error("Could not create the structure footprint folder {}", dir, e);
        }
        Path file = file(regionKey);
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(file,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)))) {
            for (int i = 0; i + STRIDE - 1 < entries.length; i += STRIDE) {
                out.writeLong(entries[i]);
                out.writeLong(entries[i + 1]);
                out.writeInt((int) entries[i + 2]);
                out.writeInt((int) entries[i + 3]);
            }
        } catch (IOException e) {
            ModLogger.error("Could not append recorded structure footprints to {}", file, e);
        }
    }

    /**
     * Reads every entry of one region's file.
     *
     * @param regionKey CoordinateKeys.pack(regionX, regionZ)
     * @return STRIDE longs per entry, in file order. Empty if the file is missing (nothing flushed yet) or
     *         unreadable. A torn last entry after a crash is left out
     */
    public long[] readRegion(long regionKey) {
        LongArrayList out = new LongArrayList();
        Path file = file(regionKey);
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            while (true) {
                long start = in.readLong();
                long footprint = in.readLong();
                int minY = in.readInt();
                int maxY = in.readInt(); // a torn last entry after a crash ends at EOF here
                out.add(start);
                out.add(footprint);
                out.add(minY);
                out.add(maxY);
            }
        } catch (EOFException | NoSuchFileException e) {
            // end of the file, or nothing flushed yet
        } catch (IOException e) {
            ModLogger.error("Could not read recorded structure footprints from {}", file, e);
        }
        return out.toLongArray();
    }

    /**
     * Names one region's file.
     *
     * @param regionKey CoordinateKeys.pack(regionX, regionZ)
     * @return the region's file path, which may not exist
     */
    private Path file(long regionKey) {
        return dir.resolve(prefix + "_" + CoordinateKeys.unpackA(regionKey) + "_" + CoordinateKeys.unpackB(regionKey) + SUFFIX);
    }
}
