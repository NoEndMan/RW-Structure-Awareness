package net.flameslight.rwstructuresawareness.io;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.flameslight.rwstructuresawareness.logger.ModLogger;
import net.flameslight.rwstructuresawareness.types.tunnel.TunnelStore;
import net.flameslight.rwstructuresawareness.utils.CoordinateKeys;
import net.minecraft.server.level.ServerLevel;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * The tunnel box files: one append-only file per region (<prefix>_<rx>_<rz>.bin, 24 bytes per box) in the
 * world's tunnels folder. TunnelStore keeps the boxes in memory and calls this for every disk access.
 */
public final class TunnelIO {
    private static final String FOLDER = "tunnels";
    private static final String SUFFIX = ".bin";
    private static final Object FILE_LOCK = new Object();

    private static volatile Path dir;
    private static volatile String prefix;

    /**
     * Points the files at a world.
     *
     * @param overworld the overworld, its dimension id names the files
     * @param dataDir   the world's data folder, the tunnels folder is created inside it on the first write
     */
    public static void open(ServerLevel overworld, Path dataDir) {
        dir = dataDir.resolve(FOLDER);
        prefix = overworld.dimension().location().toString().replace(':', '_');
    }

    /** Detaches from the world. Reads and writes do nothing until the next open. */
    public static void close() {
        dir = null;
    }

    /**
     * Tells whether a world is open.
     *
     * @return true between open and close
     */
    public static boolean isOpen() {
        return dir != null;
    }

    /**
     * Reads every box filed under one region.
     *
     * @param regionKey CoordinateKeys.pack(regionX, regionZ)
     * @return the boxes, TunnelStore.BOX_INTS ints each, in file order. Empty when no world is open, the file
     *         is missing (no tunnel there) or unreadable. A torn last box after a crash is left out
     */
    public static IntArrayList readRegion(long regionKey) {
        IntArrayList all = new IntArrayList();
        Path d = dir;
        if (d == null) {
            return all;
        }
        synchronized (FILE_LOCK) {
            Path file = file(d, regionKey);
            try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
                int[] box = new int[TunnelStore.BOX_INTS];
                while (true) {
                    for (int i = 0; i < TunnelStore.BOX_INTS; i++) {
                        box[i] = in.readInt(); // a torn last box after a crash ends at EOF here
                    }
                    all.addElements(all.size(), box);
                }
            } catch (EOFException | NoSuchFileException e) {
                // end of the file, or no tunnel here
            } catch (IOException e) {
                ModLogger.error("Could not read tunnels from {}", file, e);
            }
        }
        return all;
    }

    /**
     * Appends boxes to their region files, creating the folder and files as needed.
     *
     * @param batch region key (CoordinateKeys.pack(regionX, regionZ)) to the boxes to append, TunnelStore.BOX_INTS ints
     *              each. Read only. Nothing is written when no world is open
     */
    public static void appendRegions(Long2ObjectOpenHashMap<IntArrayList> batch) {
        Path d = dir;
        if (d == null) {
            return;
        }
        synchronized (FILE_LOCK) {
            try {
                Files.createDirectories(d);
            } catch (IOException e) {
                ModLogger.error("Could not create the tunnel folder {}", d, e);
            }
            for (Long2ObjectMap.Entry<IntArrayList> region : batch.long2ObjectEntrySet()) {
                Path file = file(d, region.getLongKey());
                try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(file,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND)))) {
                    IntArrayList ints = region.getValue();
                    for (int i = 0; i < ints.size(); i++) {
                        out.writeInt(ints.getInt(i));
                    }
                } catch (IOException e) {
                    ModLogger.error("Could not append tunnels to {}", file, e);
                }
            }
        }
    }

    /**
     * Names one region's file.
     *
     * @param d         the tunnels folder
     * @param regionKey CoordinateKeys.pack(regionX, regionZ)
     * @return the region's file path, which may not exist
     */
    private static Path file(Path d, long regionKey) {
        return d.resolve(prefix + "_" + CoordinateKeys.unpackA(regionKey) + "_" + CoordinateKeys.unpackB(regionKey) + SUFFIX);
    }
}
