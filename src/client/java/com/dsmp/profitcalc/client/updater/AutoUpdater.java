package com.dsmp.profitcalc.client.updater;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

public class AutoUpdater {
    private static final Logger LOGGER = LoggerFactory.getLogger("donut-smp-profit-calc/AutoUpdater");
    private static final String UPDATE_MANIFEST_URL = "https://raw.githubusercontent.com/Tomas6482/donut-smp-profit-calc/main/update.json";
    private static final String CURRENT_VERSION = "1.0.0";
    private static final Gson GSON = new Gson();

    private static String latestVersion = "";
    private static String latestDownloadUrl = "";
    private static boolean updateAvailable = false;

    public static void checkOnStartup() {
        CompletableFuture.runAsync(() -> {
            try {
                HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(UPDATE_MANIFEST_URL))
                        .timeout(Duration.ofSeconds(5))
                        .GET()
                        .build();

                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200 && response.body() != null) {
                    JsonObject json = GSON.fromJson(response.body(), JsonObject.class);
                    if (json != null && json.has("version")) {
                        latestVersion = json.get("version").getAsString();
                        latestDownloadUrl = json.has("download_url") ? json.get("download_url").getAsString() : "";

                        if (isNewer(latestVersion, CURRENT_VERSION)) {
                            updateAvailable = true;
                            LOGGER.info("[Donut Profit Updater] New update available: v{} (Current: v{})", latestVersion, CURRENT_VERSION);

                            Minecraft mc = Minecraft.getInstance();
                            if (mc != null && mc.player != null) {
                                mc.player.displayClientMessage(Component.literal(
                                        "§a§l[Donut Profit] §fNew update §e" + latestVersion + " §fis available! Type §b/profit update §fto install."), false);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                LOGGER.debug("Update check skipped or offline", e);
            }
        });
    }

    public static void downloadAndInstall() {
        if (!updateAvailable || latestDownloadUrl.isEmpty()) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                mc.player.displayClientMessage(Component.literal("§c[Donut Profit] No updates currently available to install."), false);
            }
            return;
        }

        CompletableFuture.runAsync(() -> {
            Minecraft mc = Minecraft.getInstance();
            try {
                if (mc.player != null) {
                    mc.player.displayClientMessage(Component.literal("§a[Donut Profit] Downloading v" + latestVersion + "... Please wait!"), false);
                }

                Path modsDir = FabricLoader.getInstance().getGameDir().resolve("mods");
                Path newJarPath = modsDir.resolve("donut-smp-profit-calc-" + latestVersion + ".jar");

                URL url = URI.create(latestDownloadUrl).toURL();
                try (InputStream in = url.openStream()) {
                    Files.copy(in, newJarPath, StandardCopyOption.REPLACE_EXISTING);
                }

                if (mc.player != null) {
                    mc.player.displayClientMessage(Component.literal("§a§l[Donut Profit] Update v" + latestVersion + " downloaded successfully! Please restart Minecraft to apply."), false);
                }
            } catch (Exception e) {
                LOGGER.error("Failed to download mod update", e);
                if (mc.player != null) {
                    mc.player.displayClientMessage(Component.literal("§c[Donut Profit] Failed to download update. Check console for details."), false);
                }
            }
        });
    }

    private static boolean isNewer(String latest, String current) {
        try {
            String[] partsLatest = latest.split("\\.");
            String[] partsCurrent = current.split("\\.");
            for (int i = 0; i < Math.max(partsLatest.length, partsCurrent.length); i++) {
                int l = i < partsLatest.length ? Integer.parseInt(partsLatest[i].replaceAll("[^0-9]", "")) : 0;
                int c = i < partsCurrent.length ? Integer.parseInt(partsCurrent[i].replaceAll("[^0-9]", "")) : 0;
                if (l > c) return true;
                if (l < c) return false;
            }
        } catch (Exception ignored) {}
        return false;
    }
}
