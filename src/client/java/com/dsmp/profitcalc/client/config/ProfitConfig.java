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

    // Saved Calculator Inputs & Active Tab State
    private int savedSelectedTab = 0;
    private String savedBonePrice = "1";
    private String savedBlockPrice = "400";
    private String savedRawKelpPrice = "160";
    private String savedDriedKelpPrice = "200";
    private String savedCharcoalPrice = "50";
    private String savedBonesQty = "100k";

    private String savedOakLogPrice = "24";
    private String savedOakPlanksPrice = "8";
    private String savedPistonPrice = "260";
    private String savedSlimeballPrice = "150";
    private String savedStickyPistonPrice = "500";
    private String savedGoldIngotPrice = "15";
    private String savedApplePrice = "10";
    private String savedGapplePrice = "200";
    private String savedBookPrice = "50";
    private String savedBookshelfPrice = "500";

    private String savedTrapdoorOffset = "100";
    private int savedTrapdoorStackSizeIndex = 3; // Index 3 in [4, 8, 12, 16, 24] -> default 16
    private String savedTrapdoorBaselinePrice = "0";
    private String savedTrapdoorPage1Ceiling = "0";

    public String getSavedOakLogPrice() { return savedOakLogPrice; }
    public void setSavedOakLogPrice(String val) { this.savedOakLogPrice = val; save(); }

    public String getSavedOakPlanksPrice() { return savedOakPlanksPrice; }
    public void setSavedOakPlanksPrice(String val) { this.savedOakPlanksPrice = val; save(); }

    public String getSavedPistonPrice() { return savedPistonPrice; }
    public void setSavedPistonPrice(String val) { this.savedPistonPrice = val; save(); }

    public String getSavedSlimeballPrice() { return savedSlimeballPrice; }
    public void setSavedSlimeballPrice(String val) { this.savedSlimeballPrice = val; save(); }

    public String getSavedStickyPistonPrice() { return savedStickyPistonPrice; }
    public void setSavedStickyPistonPrice(String val) { this.savedStickyPistonPrice = val; save(); }

    public String getSavedGoldIngotPrice() { return savedGoldIngotPrice; }
    public void setSavedGoldIngotPrice(String val) { this.savedGoldIngotPrice = val; save(); }

    public String getSavedApplePrice() { return savedApplePrice; }
    public void setSavedApplePrice(String val) { this.savedApplePrice = val; save(); }

    public String getSavedGapplePrice() { return savedGapplePrice; }
    public void setSavedGapplePrice(String val) { this.savedGapplePrice = val; save(); }

    public String getSavedBookPrice() { return savedBookPrice; }
    public void setSavedBookPrice(String val) { this.savedBookPrice = val; save(); }

    public String getSavedBookshelfPrice() { return savedBookshelfPrice; }
    public void setSavedBookshelfPrice(String val) { this.savedBookshelfPrice = val; save(); }

    public String getSavedTrapdoorOffset() { return savedTrapdoorOffset; }
    public void setSavedTrapdoorOffset(String val) { this.savedTrapdoorOffset = val; save(); }

    public int getSavedTrapdoorStackSizeIndex() { return savedTrapdoorStackSizeIndex; }
    public void setSavedTrapdoorStackSizeIndex(int val) { this.savedTrapdoorStackSizeIndex = val; save(); }

    public String getSavedTrapdoorBaselinePrice() { return savedTrapdoorBaselinePrice; }
    public void setSavedTrapdoorBaselinePrice(String val) { this.savedTrapdoorBaselinePrice = val; save(); }

    public String getSavedTrapdoorPage1Ceiling() { return savedTrapdoorPage1Ceiling; }
    public void setSavedTrapdoorPage1Ceiling(String val) { this.savedTrapdoorPage1Ceiling = val; save(); }

    // Unified Configurable Command Delays (Custom Min/Max Textboxes, default 400-600ms)
    private int commandMinDelayMs = 400;
    private int commandMaxDelayMs = 600;
    private boolean useGuiSearchBypass = true;

    public int getCommandMinDelayMs() { return commandMinDelayMs > 0 ? commandMinDelayMs : 400; }
    public void setCommandMinDelayMs(int ms) { this.commandMinDelayMs = Math.max(100, ms); save(); }

    public int getCommandMaxDelayMs() { return commandMaxDelayMs > 0 ? commandMaxDelayMs : 600; }
    public void setCommandMaxDelayMs(int ms) { this.commandMaxDelayMs = Math.max(commandMinDelayMs, ms); save(); }

    public int getRandomizedCommandDelayMs() {
        int min = getCommandMinDelayMs();
        int max = getCommandMaxDelayMs();
        if (max <= min) return min;
        return min + java.util.concurrent.ThreadLocalRandom.current().nextInt(max - min + 1);
    }

    public boolean isUseGuiSearchBypass() { return useGuiSearchBypass; }
    public void setUseGuiSearchBypass(boolean val) { this.useGuiSearchBypass = val; save(); }

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

    // Calculator Persistence Getters & Setters
    public int getSavedSelectedTab() {
        return savedSelectedTab;
    }

    public void setSavedSelectedTab(int tab) {
        this.savedSelectedTab = tab;
        save();
    }

    public String getSavedBonePrice() {
        return savedBonePrice != null ? savedBonePrice : "1";
    }

    public void setSavedBonePrice(String val) {
        this.savedBonePrice = val;
        save();
    }

    public String getSavedBlockPrice() {
        return savedBlockPrice != null ? savedBlockPrice : "400";
    }

    public void setSavedBlockPrice(String val) {
        this.savedBlockPrice = val;
        save();
    }

    public String getSavedRawKelpPrice() {
        return savedRawKelpPrice != null ? savedRawKelpPrice : "160";
    }

    public void setSavedRawKelpPrice(String val) {
        this.savedRawKelpPrice = val;
        save();
    }

    public String getSavedDriedKelpPrice() {
        return savedDriedKelpPrice != null ? savedDriedKelpPrice : "200";
    }

    public void setSavedDriedKelpPrice(String val) {
        this.savedDriedKelpPrice = val;
        save();
    }

    public String getSavedCharcoalPrice() {
        return savedCharcoalPrice != null ? savedCharcoalPrice : "50";
    }

    public void setSavedCharcoalPrice(String val) {
        this.savedCharcoalPrice = val;
        save();
    }

    public String getSavedBonesQty() {
        return savedBonesQty != null ? savedBonesQty : "100k";
    }

    public void setSavedBonesQty(String val) {
        this.savedBonesQty = val;
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

                INSTANCE.savedSelectedTab = loaded.savedSelectedTab;
                if (loaded.savedBonePrice != null) INSTANCE.savedBonePrice = loaded.savedBonePrice;
                if (loaded.savedBlockPrice != null) INSTANCE.savedBlockPrice = loaded.savedBlockPrice;
                if (loaded.savedRawKelpPrice != null) INSTANCE.savedRawKelpPrice = loaded.savedRawKelpPrice;
                if (loaded.savedDriedKelpPrice != null) INSTANCE.savedDriedKelpPrice = loaded.savedDriedKelpPrice;
                if (loaded.savedCharcoalPrice != null) INSTANCE.savedCharcoalPrice = loaded.savedCharcoalPrice;
                if (loaded.savedBonesQty != null) INSTANCE.savedBonesQty = loaded.savedBonesQty;
                if (loaded.savedOakLogPrice != null) INSTANCE.savedOakLogPrice = loaded.savedOakLogPrice;
                if (loaded.savedOakPlanksPrice != null) INSTANCE.savedOakPlanksPrice = loaded.savedOakPlanksPrice;
                if (loaded.savedPistonPrice != null) INSTANCE.savedPistonPrice = loaded.savedPistonPrice;
                if (loaded.savedSlimeballPrice != null) INSTANCE.savedSlimeballPrice = loaded.savedSlimeballPrice;
                if (loaded.savedStickyPistonPrice != null) INSTANCE.savedStickyPistonPrice = loaded.savedStickyPistonPrice;
                if (loaded.savedGoldIngotPrice != null) INSTANCE.savedGoldIngotPrice = loaded.savedGoldIngotPrice;
                if (loaded.savedApplePrice != null) INSTANCE.savedApplePrice = loaded.savedApplePrice;
                if (loaded.savedGapplePrice != null) INSTANCE.savedGapplePrice = loaded.savedGapplePrice;
                if (loaded.savedBookPrice != null) INSTANCE.savedBookPrice = loaded.savedBookPrice;
                if (loaded.savedBookshelfPrice != null) INSTANCE.savedBookshelfPrice = loaded.savedBookshelfPrice;
                if (loaded.commandMinDelayMs > 0) INSTANCE.commandMinDelayMs = loaded.commandMinDelayMs;
                if (loaded.commandMaxDelayMs > 0) INSTANCE.commandMaxDelayMs = loaded.commandMaxDelayMs;
                INSTANCE.useGuiSearchBypass = loaded.useGuiSearchBypass;
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
