package com.qsteam.toml_config;

import com.qsteam.toml_config.api.ConfigCategory;
import com.qsteam.toml_config.api.ConfigValue;
import com.qsteam.toml_config.api.TOMLConfig;

@TOMLConfig(modId = Tags.MOD_ID, name = Tags.MOD_NAME)
public class TOMLConfigCfg {

    @ConfigCategory(name = "main")
    public static final MainCategory MAIN = new MainCategory();

    public static class MainCategory {
        @ConfigValue(comment = "When true, caches are stored under <minecraft>/cache/toml_config")
        public boolean caching = true;
    }

}
