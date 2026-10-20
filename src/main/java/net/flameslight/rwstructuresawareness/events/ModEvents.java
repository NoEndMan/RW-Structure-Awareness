package net.flameslight.rwstructuresawareness.events;

import net.flameslight.rwstructuresawareness.RWStructuresAwareness;
import net.flameslight.rwstructuresawareness.config.CommonConfig;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.config.ModConfigEvent;

@Mod.EventBusSubscriber(modid = RWStructuresAwareness.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
public class ModEvents {
    /**
     * Re-reads this mod's config when it loads or reloads - Forge fires ModConfigEvent.Loading when the
     * config file is first read, and ModConfigEvent.Reloading when the file changes while the game runs.
     * Both need to re-run CommonConfig.refresh().
     *
     * @param event any config event, Unloading and other mods' configs are ignored
     */
    @SubscribeEvent
    public static void onConfigLoaded(ModConfigEvent event) {
        if (event instanceof ModConfigEvent.Unloading
                || event.getConfig().getSpec() != CommonConfig.COMMON_SPEC) {
            return;
        }
        CommonConfig.refresh();
    }
}
