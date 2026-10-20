package net.flameslight.rwstructuresawareness;

import com.mojang.logging.LogUtils;
import net.flameslight.rwstructuresawareness.compat.zones.ZonesCompat;
import net.flameslight.rwstructuresawareness.config.CommonConfig;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * RoadWeaver addon with two jobs:
 *  - structures never generate on a RoadWeaver road: a jigsaw piece on a road is left out, and any other
 *    structure on a road is rejected.
 *  - roads RoadWeaver plans avoid the structures that already generated: they are rerouted around them, else
 *    tunnelled under them, else stopped before them.
 */
@Mod(RWStructuresAwareness.MOD_ID)
public class RWStructuresAwareness {
    public static final String MOD_ID = "rwstructuresawareness";
    public static final Logger LOGGER = LogUtils.getLogger();

    /** Creates the config folder, registers the config and decides whether Zones is usable. */
    @SuppressWarnings("removal")
    public RWStructuresAwareness() {
        /*
            NightConfig writes common-config.toml into this folder. Create it first so the very first
            launch can't race the folder into existence.
         */
        Path configFolder = FMLPaths.CONFIGDIR.get().resolve(MOD_ID);
        try {
            Files.createDirectories(configFolder);
        } catch (IOException e) {
            LOGGER.error("Could not create the config folder {}", configFolder, e);
        }

        FMLJavaModLoadingContext.get().registerConfig(
                ModConfig.Type.COMMON, CommonConfig.COMMON_SPEC, MOD_ID + "/common-config.toml");

        ZonesCompat.init();
    }
}
