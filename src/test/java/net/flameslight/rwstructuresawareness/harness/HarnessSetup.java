package net.flameslight.rwstructuresawareness.harness;

import net.flameslight.rwstructuresawareness.RWStructuresAwareness;
import net.flameslight.rwstructuresawareness.config.CommonConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Harness only (src/test, loaded by the gameTestServer run alone). Every test runs its checks
 * synchronously and needs no blocks of its own, so they share one empty 3x3x3 template, built in
 * memory once the server started (before GameTestServer's first tick spawns the tests).
 */
@Mod.EventBusSubscriber(modid = RWStructuresAwareness.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class HarnessSetup {
    public static final String TEMPLATE = "empty";

    /**
     * Builds the shared empty template and turns on the addon's debug log lines.
     *
     * @param event the server started event
     */
    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        ServerLevel level = event.getServer().overworld();
        StructureTemplate template = level.getStructureManager()
                .getOrCreate(new ResourceLocation(RWStructuresAwareness.MOD_ID, TEMPLATE));
        template.fillFromWorld(level, new BlockPos(0, 200, 0), new Vec3i(3, 3, 3), false, null);
        // The addon's own [DEBUG] lines explain every rejection and reroute in the harness log.
        CommonConfig.DEBUG_MOD.set(true);
        CommonConfig.refresh();
        RWStructuresAwareness.LOGGER.info("[harness] template ready, test areas start at x = z = {}", H.BASE);
    }
}
