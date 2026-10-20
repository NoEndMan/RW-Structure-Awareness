package net.flameslight.rwstructuresawareness.types.tunnel;

/** One region's tunnel boxes held in TunnelStore's cache, with its LRU stamp. */
public final class CachedTunnelRegion {
    /** The region's boxes, TunnelStore.BOX_INTS ints each. Replaced whole when a box is added. */
    private volatile int[] boxes;
    /** TunnelStore's LRU clock value when this region was last used. */
    private volatile long lastUsed;

    /**
     * Creates a cache entry.
     *
     * @param boxes    the region's boxes, TunnelStore.BOX_INTS ints each, not copied
     * @param lastUsed the LRU clock value to start with
     */
    public CachedTunnelRegion(int[] boxes, long lastUsed) {
        this.boxes = boxes;
        this.lastUsed = lastUsed;
    }

    /**
     * Returns the region's boxes.
     *
     * @return TunnelStore.BOX_INTS ints per box, shared, callers must not change it
     */
    public int[] getBoxes() {
        return boxes;
    }

    /**
     * Replaces the region's boxes.
     *
     * @param boxes TunnelStore.BOX_INTS ints per box, not copied
     */
    public void setBoxes(int[] boxes) {
        this.boxes = boxes;
    }

    /**
     * Returns when the region was last used.
     *
     * @return TunnelStore's LRU clock value
     */
    public long getLastUsed() {
        return lastUsed;
    }

    /**
     * Stamps the region as used.
     *
     * @param lastUsed TunnelStore's LRU clock value
     */
    public void setLastUsed(long lastUsed) {
        this.lastUsed = lastUsed;
    }
}
