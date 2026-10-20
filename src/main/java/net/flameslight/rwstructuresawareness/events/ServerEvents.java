package net.flameslight.rwstructuresawareness.events;

import net.flameslight.rwstructuresawareness.RWStructuresAwareness;
import net.flameslight.rwstructuresawareness.types.road.RoadLevel;
import net.flameslight.rwstructuresawareness.RoadInsideRegionChunksRecorder;
import net.flameslight.rwstructuresawareness.StructureChunkRecorder;
import net.flameslight.rwstructuresawareness.types.tunnel.TunnelStore;
import net.minecraft.Util;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.nio.file.Path;

@Mod.EventBusSubscriber(modid = RWStructuresAwareness.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class ServerEvents {
    /**
     * Opens the overworld's road data (footprints and tunnels) when it loads.
     */
    @SubscribeEvent
    public static void onLevelLoad(LevelEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel serverLevel && Level.OVERWORLD.equals(serverLevel.dimension())) {
            Path dataDir = RoadLevel.dataDir(serverLevel);
            RoadLevel.open(serverLevel);
            StructureChunkRecorder.open(serverLevel, dataDir);
            TunnelStore.open(serverLevel, dataDir);
        }
    }

    /**
     * Writes and closes the road data when the road level unloads.
     */
    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel serverLevel && RoadLevel.isRoadLevel(serverLevel)) {
            StructureChunkRecorder.close();
            TunnelStore.close();
            RoadInsideRegionChunksRecorder.reset();
            RoadLevel.close();
        }
    }

    /**
     * Writes what is pending and forgets every world's state once the server stopped.
     */
    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        StructureChunkRecorder.flushPending();
        TunnelStore.flush();
        StructureChunkRecorder.reset();
        TunnelStore.reset();
        RoadInsideRegionChunksRecorder.reset();
        RoadLevel.close();
        flushCountdown = FLUSH_INTERVAL_TICKS;
    }

    /** 15 seconds. */
    private static final int FLUSH_INTERVAL_TICKS = 300;
    private static int flushCountdown = FLUSH_INTERVAL_TICKS;

    /**
     * Every 15 seconds, writes the pending structure footprints and tunnels on the IO pool.
     *
     */
    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || RoadLevel.level() == null || --flushCountdown > 0) {
            return;
        }
        flushCountdown = FLUSH_INTERVAL_TICKS;
        if (StructureChunkRecorder.hasPending() || TunnelStore.hasPending()) {
            Util.ioPool().execute(() -> {
                StructureChunkRecorder.flushPending();
                TunnelStore.flush();
            });
        }
    }
}
