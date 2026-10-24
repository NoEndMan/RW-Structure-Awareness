package net.flameslight.rwstructuresawareness.types.road;

import net.flameslight.rwstructuresawareness.RWStructuresAwareness;
import net.flameslight.rwstructuresawareness.mixins.ChunkMapAccessor;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.storage.LevelResource;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;

/**
 * The one level this mod works in: RoadWeaver only builds roads in the overworld. Set on overworld
 * load, cleared on unload, so worldgen hooks can tell "is this the road dimension" with one
 * identity check on the chunk generator.
 */
public final class RoadLevel {
    private static volatile ServerLevel level;
    private static volatile ChunkGenerator generator;

    /**
     * Sets the road level.
     *
     * @param overworld the overworld that just loaded
     */
    public static void open(ServerLevel overworld) {
        level = overworld;
        generator = overworld.getChunkSource().getGenerator();
    }

    /** Clears the road level. */
    public static void close() {
        level = null;
        generator = null;
    }

    /**
     * Returns the road level.
     *
     * @return the loaded overworld, null when none is loaded
     */
    @Nullable
    public static ServerLevel level() {
        return level;
    }

    /**
     * Tells whether a chunk generator is the road level's.
     *
     * @param chunkGenerator the generator, may be null
     * @return true if it is the road level's generator (identity check)
     */
    public static boolean isRoadGenerator(ChunkGenerator chunkGenerator) {
        return chunkGenerator != null && chunkGenerator == generator;
    }

    /**
     * Tells whether a level is the road level.
     *
     * @param candidate the level, may be null
     * @return true if it is the loaded overworld (identity check)
     */
    public static boolean isRoadLevel(@Nullable Level candidate) {
        return candidate != null && candidate == level;
    }

    /**
     * Tells whether a loaded chunk already reached its decoration step (FEATURES): RoadWeaver won't pave a road
     * saved from now on there anymore. Chunks generated earlier but unloaded now aren't seen.
     *
     * @param serverLevel the level, may be null (then false)
     * @param chunkX      the chunk's X (chunk coordinates)
     * @param chunkZ      the chunk's Z (chunk coordinates)
     * @return true if the chunk is loaded and at FEATURES or later
     */
    public static boolean isDecorated(@Nullable ServerLevel serverLevel, int chunkX, int chunkZ) {
        if (serverLevel == null) {
            return false;
        }
        ChunkHolder holder = ((ChunkMapAccessor) serverLevel.getChunkSource().chunkMap)
                .rwstructuresawareness$getVisibleChunkIfPresent(ChunkPos.asLong(chunkX, chunkZ));
        ChunkAccess chunk = holder == null ? null : holder.getLastAvailable();
        return chunk != null && chunk.getStatus().isOrAfter(ChunkStatus.FEATURES);
    }

    /**
     * Returns this mod's data folder in a world.
     *
     * @param serverLevel any level of the world
     * @return {@code <world>/data/rwstructuresawareness}, which may not exist yet
     */
    public static Path dataDir(ServerLevel serverLevel) {
        return serverLevel.getServer().getWorldPath(LevelResource.ROOT).resolve("data").resolve(RWStructuresAwareness.MOD_ID);
    }
}
