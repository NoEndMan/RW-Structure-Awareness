package net.flameslight.rwstructuresawareness.tunnel;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import org.jetbrains.annotations.Nullable;

/**
 * Added to vanilla's Beardifier by BeardifierMixin: the tunnel road columns of the chunk it was built
 * for (CoordinateKeys.pack(x, z) -> the road's first air Y), and how many blocks above it are cleared.
 */
public interface TunnelBeardifierAccess {
    /**
     * Gives the Beardifier its chunk's tunnel road columns.
     *
     * @param floors      CoordinateKeys.pack(x, z) to the road's first air Y, kept, not copied
     * @param clearHeight blocks dug above the floor
     */
    void rwstructuresawareness$setTunnelColumns(Long2IntOpenHashMap floors, int clearHeight);

    /**
     * Returns the Beardifier's tunnel road columns.
     *
     * @return CoordinateKeys.pack(x, z) to the road's first air Y, null if its chunk has no tunnel
     */
    @Nullable
    Long2IntOpenHashMap rwstructuresawareness$getTunnelColumns();
}
