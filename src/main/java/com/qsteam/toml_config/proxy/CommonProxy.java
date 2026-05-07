package com.qsteam.toml_config.proxy;

import com.qsteam.toml_config.core.ConfigManager;
import com.qsteam.toml_config.command.CommandTomlConfig;
import net.minecraft.launchwrapper.Launch;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPostInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;

import static com.qsteam.toml_config.TOMLConfigMod.LOGGER;
import static com.qsteam.toml_config.core.TOMLLoadingPlugin.discoveredConfigs;

public class CommonProxy {

    public void preInit(FMLPreInitializationEvent event) {}

    public void init(FMLInitializationEvent event) {}

    public void serverStarting(FMLServerStartingEvent event) {
        event.registerServerCommand(new CommandTomlConfig());
    }

    /**
     * Force-loads all discovered configuration classes to trigger their
     * static initializers and apply pending headers.
     */
    public void postInit(FMLPostInitializationEvent event) {
        for (String className : discoveredConfigs) {
            try {
                Class.forName(className, true, Launch.classLoader);
            } catch (ClassNotFoundException e) {
                LOGGER.error("Could not find discovered config class: {}", className);
            } catch (Exception e) {
                LOGGER.error("Failed to force-load config: {}", className, e);
            }
        }

        discoveredConfigs.clear();
        ConfigManager.applyPendingHeaders();
        ConfigManager.trimCaches();
    }

}
