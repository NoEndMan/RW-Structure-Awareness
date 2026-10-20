package net.flameslight.rwstructuresawareness.types.road;

/** One loaded region's road map, as RoadInsideRegionChunksRecorder keeps it, with its LRU stamp. */
public final class RoadRegionHolder {
    /** The region's road map. Replaced whole when a road is added. */
    private volatile RoadInsideRegionChunks chunks;
    /** The recorder's LRU clock value when this region was last used. */
    private volatile long lastUsed;

    /**
     * Creates a holder.
     *
     * @param chunks   the region's road map
     * @param lastUsed the LRU clock value to start with
     */
    public RoadRegionHolder(RoadInsideRegionChunks chunks, long lastUsed) {
        this.chunks = chunks;
        this.lastUsed = lastUsed;
    }

    /**
     * Returns the region's road map.
     *
     * @return the current map, never null
     */
    public RoadInsideRegionChunks getChunks() {
        return chunks;
    }

    /**
     * Replaces the region's road map.
     *
     * @param chunks the new map, not null
     */
    public void setChunks(RoadInsideRegionChunks chunks) {
        this.chunks = chunks;
    }

    /**
     * Returns when the region was last used.
     *
     * @return the recorder's LRU clock value
     */
    public long getLastUsed() {
        return lastUsed;
    }

    /**
     * Stamps the region as used.
     *
     * @param lastUsed the recorder's LRU clock value
     */
    public void setLastUsed(long lastUsed) {
        this.lastUsed = lastUsed;
    }
}
