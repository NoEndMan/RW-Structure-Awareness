package net.flameslight.rwstructuresawareness.mixins;

import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Debug only (RoadDebug): which chunks a saved road crosses are already loaded and generated. */
@Mixin(ChunkMap.class)
public interface ChunkMapAccessor {
    /**
     * Calls ChunkMap.getVisibleChunkIfPresent: reads the published (visible) holder map, safe off the server
     * thread.
     *
     * @param chunkKey ChunkPos.asLong of the chunk
     * @return the chunk's holder, null if it isn't loaded
     */
    @Invoker("getVisibleChunkIfPresent")
    ChunkHolder rwstructuresawareness$getVisibleChunkIfPresent(long chunkKey);
}
