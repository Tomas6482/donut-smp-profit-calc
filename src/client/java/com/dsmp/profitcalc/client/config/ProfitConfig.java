package com.dsmp.profitcalc.client.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.nio.file.Path;

public class ProfitConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger("donut-smp-profit-calc/Config");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("donut_profit_calc_config.json");

    private static final ProfitConfig INSTANCE = new ProfitConfig();

    public enum HudPosition {
        TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT, TOP_CENTER
    }

    private boolean hudEnabled = true;
    private HudPosition hudPosition = HudPosition.TOP_LEFT;
    private int hudX = 10;
    private int hudY = 10;
    private int hudOffsetX = 10;
    private int hudOffsetY = 10;
    private float hudScale = 1.0f;
    private boolean playSoundOnTransaction = true;
    private boolean persistDataOnRestart = true;
    private boolean showStatusHud = true;
    private boolean verboseLogging = false;
    private int themeColorHex = 0xD0101216; // Default Dark Glass

    private ProfitConfig() {}

    public static ProfitConfig getInstance() {
        return INSTANCE;
    }

    public boolean isHudEnabled() {
        return hudEnabled;
    }

    public void setHudEnabled(boolean hudEnabled) {
        this.hudEnabled = hudEnabled;
        save();
    }

    public HudPosition getHudPosition() {
        return hudPosition != null ? hudPosition : HudPosition.TOP_LEFT;
    }

    public void setHudPosition(HudPosition hudPosition) {
        this.hudPosition = hudPosition;
        save();
    }

    public int getHudX() {
        return hudX;
    }

    public void setHudX(int hudX) {
        this.hudX = hudX;
        save();
    }

    public int getHudY() {
        return hudY;
    }

    public void setHudY(int hudY) {
        this.hudY = hudY;
        save();
    }

    public int getHudOffsetX() {
        return hudOffsetX;
    }

    public void setHudOffsetX(int hudOffsetX) {
        this.hudOffsetX = hudOffsetX;
        save();
    }

    public int getHudOffsetY() {
        return hudOffsetY;
    }

    public void setHudOffsetY(int hudOffsetY) {
        this.hudOffsetY = hudOffsetY;
        save();
    }

    public float getHudScale() {
        return hudScale;
    }

    public void setHudScale(float hudScale) {
        this.hudScale = hudScale;
        save();
    }

    public boolean isPlaySoundOnTransaction() {
        return playSoundOnTransaction;
    }

    public void setPlaySoundOnTransaction(boolean playSoundOnTransaction) {
        this.playSoundOnTransaction = playSoundOnTransaction;
        save();
    }

    public boolean isPersistDataOnRestart() {
        return persistDataOnRestart;
    }

    public void setPersistDataOnRestart(boolean persistDataOnRestart) {
        this.persistDataOnRestart = persistDataOnRestart;
        save();
    }

    public boolean isShowStatusHud() {
        return showStatusHud;
    }

    public void setShowStatusHud(boolean showStatusHud) {
        this.showStatusHud = showStatusHud;
        save();
    }

    public boolean isVerboseLogging() {
        return verboseLogging;
    }

    public void setVerboseLogging(boolean verboseLogging) {
        this.verboseLogging = verboseLogging;
        save();
    }

    public int getThemeColorHex() {
        return themeColorHex;
    }

    public void setThemeColorHex(int themeColorHex) {
        this.themeColorHex = themeColorHex;
        save();
    }

    public static void load() {
        File file = CONFIG_PATH.toFile();
        if (!file.exists()) {
            INSTANCE.save();
            return;
        }

        try (FileReader reader = new FileReader(file)) {
            ProfitConfig loaded = GSON.fromJson(reader, ProfitConfig.class);
            if (loaded != null) {
                INSTANCE.hudEnabled = loaded.hudEnabled;
                if (loaded.hudPosition != null) INSTANCE.hudPosition = loaded.hudPosition;
                INSTANCE.hudX = loaded.hudX;
                INSTANCE.hudY = loaded.hudY;
                INSTANCE.hudOffsetX = loaded.hudOffsetX;
                INSTANCE.hudOffsetY = loaded.hudOffsetY;
                INSTANCE.hudScale = loaded.hudScale;
                INSTANCE.playSoundOnTransaction = loaded.playSoundOnTransaction;
                INSTANCE.persistDataOnRestart = loaded.persistDataOnRestart;
                INSTANCE.showStatusHud = loaded.showStatusHud;
                INSTANCE.verboseLogging = loaded.verboseLogging;
                if (loaded.themeColorHex != 0) INSTANCE.themeColorHex = loaded.themeColorHex;
            }
        } catch (Exception e) {
            LOGGER.error("Failed to load config file", e);
        }
    }

    public void save() {
        try {
            File dir = CONFIG_PATH.getParent().toFile();
            if (!dir.exists()) dir.mkdirs();

            try (FileWriter writer = new FileWriter(CONFIG_PATH.toFile())) {
                GSON.toJson(this, writer);
            }
        } catch (Exception e) {
            LOGGER.error("Failed to save config file", e);
        }
    }
}
