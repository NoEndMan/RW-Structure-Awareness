package net.flameslight.rwstructuresawareness.types.structure;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.flameslight.rwstructuresawareness.io.StructureChunkIO;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Recorded structure footprints, persisted by StructureChunkIO (one append-only file per 512x512-block
 * region). Each entry is the structure's start chunk key, the covered chunk with the piece's rectangle
 * inside it (StructureChunkRecorder.packFootprint), and the piece's lowest and highest Y.
 *
 * In memory: what isn't flushed yet (appends land in a per-region pending set, so an entry recorded
 * twice is written once per flush), and the decoded files of the last MAX_CACHED_REGIONS regions
 * read (dropped when a flush appends to them), so road planning doesn't re-read files per road.
 * Appends come from worldgen threads, flushes from the IO pool and reads from RoadWeaver's threads,
 * so the pending sets and the files have separate locks. A flush moves pending into inFlight and only
 * clears it once the file write finished, so a read always finds every entry in memory or on disk.
 */
public final class StructureChunkStorage {
    /** Longs per entry in read()'s result: startChunk, footprint, minY, maxY. */
    public static final int STRIDE = StructureChunkIO.STRIDE;
    /** Regions whose decoded file stays in memory (24 bytes on disk -> 32 in memory per entry). */
    private static final int MAX_CACHED_REGIONS = 64;
    private static final long[] EMPTY = new long[0];

    private final StructureChunkIO io;
    private final Object lock = new Object();
    /** Guards the files and fileCache together, so a flush can't slip between a read and its caching. */
    private final Object fileLock = new Object();

    private Long2ObjectOpenHashMap<Set<StructureChunkEntry>> pending = new Long2ObjectOpenHashMap<>();
    private Long2ObjectOpenHashMap<Set<StructureChunkEntry>> inFlight = null;
    /** Decoded file contents of recently read regions. Only touched while holding fileLock. */
    private final Map<Long, long[]> fileCache = new LinkedHashMap<>(MAX_CACHED_REGIONS, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, long[]> eldest) {
            return size() > MAX_CACHED_REGIONS;
        }
    };
    /** Regions that have entries (on disk or pending). Read from one folder listing per session. */
    private final Set<Long> knownRegions = ConcurrentHashMap.newKeySet();

    /**
     * Opens one world's footprints and lists the regions already on disk.
     *
     * @param dir    the footprint folder
     * @param prefix the file name prefix (the dimension id)
     */
    public StructureChunkStorage(Path dir, String prefix) {
        this.io = new StructureChunkIO(dir, prefix);
        knownRegions.addAll(io.listRegions());
    }

    /**
     * Records one footprint entry. Written on the next flush.
     *
     * @param regionKey  CoordinateKeys.pack(regionX, regionZ) of the covered chunk
     * @param startChunk the structure's start chunk key
     * @param footprint  StructureChunkRecorder.packFootprint of the covered chunk and rectangle
     * @param minY       the piece's lowest Y
     * @param maxY       the piece's highest Y
     */
    public void append(long regionKey, long startChunk, long footprint, int minY, int maxY) {
        synchronized (lock) {
            pending.computeIfAbsent(regionKey, k -> new LinkedHashSet<>())
                    .add(new StructureChunkEntry(startChunk, footprint, minY, maxY));
        }
        knownRegions.add(regionKey);
    }

    /**
     * Tells whether a region has any recorded entry.
     *
     * @param regionKey CoordinateKeys.pack(regionX, regionZ)
     * @return true if the region has entries on disk or pending
     */
    public boolean hasRegion(long regionKey) {
        return knownRegions.contains(regionKey);
    }

    /**
     * Tells whether anything is recorded at all.
     *
     * @return true if any region has entries
     */
    public boolean hasAnyRegion() {
        return !knownRegions.isEmpty();
    }

    /**
     * Tells whether entries are waiting to be written.
     *
     * @return true if a flush has something to write
     */
    public boolean hasPending() {
        synchronized (lock) {
            return !pending.isEmpty();
        }
    }

    /** Writes the pending entries. Safe from any thread, the disk work runs outside the append lock. */
    public void flush() {
        Long2ObjectOpenHashMap<Set<StructureChunkEntry>> batch;
        synchronized (lock) {
            if (pending.isEmpty()) {
                return;
            }
            batch = pending;
            pending = new Long2ObjectOpenHashMap<>();
            inFlight = batch;
        }
        synchronized (fileLock) {
            for (Long2ObjectMap.Entry<Set<StructureChunkEntry>> region : batch.long2ObjectEntrySet()) {
                LongArrayList data = new LongArrayList(region.getValue().size() * STRIDE);
                addAll(data, region.getValue());
                io.appendRegion(region.getLongKey(), data.toLongArray());
                fileCache.remove(region.getLongKey()); // the file changed
            }
        }
        synchronized (lock) {
            if (inFlight == batch) {
                inFlight = null;
            }
        }
    }

    /**
     * Returns every entry of a region, on disk and not yet flushed.
     *
     * @param regionKey CoordinateKeys.pack(regionX, regionZ)
     * @return STRIDE longs per entry (startChunk, footprint, minY, maxY), a new array. Empty if the region has
     *         nothing recorded
     */
    public long[] read(long regionKey) {
        if (!knownRegions.contains(regionKey)) {
            return EMPTY;
        }
        LongArrayList out = new LongArrayList();
        synchronized (lock) {
            addAll(out, pending.get(regionKey));
            if (inFlight != null) {
                addAll(out, inFlight.get(regionKey));
            }
        }
        long[] fromFile;
        synchronized (fileLock) {
            fromFile = fileCache.get(regionKey);
            if (fromFile == null) {
                fromFile = io.readRegion(regionKey);
                fileCache.put(regionKey, fromFile); // under fileLock: a flush can't slip in between
            }
        }
        out.addElements(out.size(), fromFile);
        return out.toLongArray();
    }

    /**
     * Appends entries as STRIDE longs each.
     *
     * @param out     the list, mutated
     * @param entries the entries, may be null (nothing is added)
     */
    private static void addAll(LongArrayList out, Set<StructureChunkEntry> entries) {
        if (entries == null) {
            return;
        }
        for (StructureChunkEntry entry : entries) {
            out.add(entry.startChunk());
            out.add(entry.footprint());
            out.add(entry.minY());
            out.add(entry.maxY());
        }
    }
}
