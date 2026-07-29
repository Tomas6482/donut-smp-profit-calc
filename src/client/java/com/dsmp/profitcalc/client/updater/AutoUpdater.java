package com.dsmp.profitcalc.client.updater;

import com.dsmp.profitcalc.client.ui.RestartModalScreen;
import com.dsmp.profitcalc.client.ui.UpdateModalScreen;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
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
    private static final String GITHUB_RELEASES_API = "https://api.github.com/repos/Tomas6482/donut-smp-profit-calc/releases/latest";
    private static final String UPDATE_MANIFEST_URL = "https://raw.githubusercontent.com/Tomas6482/donut-smp-profit-calc/main/update.json";
    private static final Gson GSON = new Gson();

    private static String latestVersion = "";
    private static String latestDownloadUrl = "";
    private static boolean updateAvailable = false;
    private static boolean registeredMenuListener = false;
    private static boolean modalShownThisSession = false;

    public static String getLatestVersion() {
        return latestVersion.isEmpty() ? getCurrentVersion() : latestVersion;
    }

    public static String getCurrentVersion() {
        return FabricLoader.getInstance()
                .getModContainer("donut-smp-profit-calc")
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("1.1.1");
    }

    public static boolean isUpdateAvailable() {
        return updateAvailable;
    }

    public static void checkOnStartup() {
        if (!registeredMenuListener) {
            registeredMenuListener = true;
            ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
                if (screen instanceof TitleScreen && updateAvailable && !modalShownThisSession) {
                    modalShownThisSession = true;
                    client.execute(() -> client.setScreen(new UpdateModalScreen()));
                }
            });
        }

        fetchManifestAsync();
    }

    private static boolean fetchLatestInfo(HttpClient client) {
        // 1. Try official GitHub Releases API first
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(GITHUB_RELEASES_API))
                    .header("User-Agent", "Mozilla/5.0 DonutProfitCalcMod")
                    .header("Accept", "application/vnd.github+json")
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200 && response.body() != null) {
                JsonObject json = GSON.fromJson(response.body(), JsonObject.class);
                if (json != null && json.has("tag_name")) {
                    String tag = json.get("tag_name").getAsString();
                    latestVersion = tag.toLowerCase().startsWith("v") ? tag.substring(1) : tag;

                    if (json.has("assets") && json.get("assets").isJsonArray()) {
                        for (var elem : json.getAsJsonArray("assets")) {
                            if (elem.isJsonObject()) {
                                JsonObject asset = elem.getAsJsonObject();
                                if (asset.has("browser_download_url") && asset.get("browser_download_url").getAsString().endsWith(".jar")) {
                                    latestDownloadUrl = asset.get("browser_download_url").getAsString();
                                    break;
                                }
                            }
                        }
                    }
                    if (latestDownloadUrl.isEmpty()) {
                        latestDownloadUrl = "https://github.com/Tomas6482/donut-smp-profit-calc/releases/download/v" + latestVersion + "/donut-smp-profit-calc-" + latestVersion + ".jar";
                    }
                    return true;
                }
            }
        } catch (Exception e) {
            LOGGER.debug("GitHub API release check failed, trying fallback update.json", e);
        }

        // 2. Fallback: raw update.json
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(UPDATE_MANIFEST_URL + "?t=" + System.currentTimeMillis()))
                    .header("User-Agent", "Mozilla/5.0 DonutProfitCalcMod")
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200 && response.body() != null) {
                JsonObject json = GSON.fromJson(response.body(), JsonObject.class);
                if (json != null && json.has("version")) {
                    latestVersion = json.get("version").getAsString();
                    latestDownloadUrl = json.has("download_url") ? json.get("download_url").getAsString() : "";
                    return true;
                }
            }
        } catch (Exception e) {
            LOGGER.debug("Fallback update.json check failed", e);
        }

        return false;
    }

    private static void fetchManifestAsync() {
        CompletableFuture.runAsync(() -> {
            try {
                HttpClient client = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(5))
                        .followRedirects(HttpClient.Redirect.ALWAYS)
                        .build();

                if (fetchLatestInfo(client)) {
                    String current = getCurrentVersion();
                    if (isNewer(latestVersion, current)) {
                        updateAvailable = true;
                        LOGGER.info("[Donut Profit Updater] New update available: v{} (Current: v{})", latestVersion, current);

                        Minecraft mc = Minecraft.getInstance();
                        if (mc != null) {
                            mc.execute(() -> {
                                if (mc.screen instanceof TitleScreen && !modalShownThisSession) {
                                    modalShownThisSession = true;
                                    mc.setScreen(new UpdateModalScreen());
                                } else if (mc.player != null) {
                                    mc.player.displayClientMessage(Component.literal(
                                            "§a§l[Donut Profit] §fNew update §e" + latestVersion + " §fis available! (Installed: v" + current + "). Type §b/profit update §fto install."), false);
                                }
                            });
                        }
                    }
                }
            } catch (Exception e) {
                LOGGER.debug("Update check skipped or offline", e);
            }
        });
    }

    public static void downloadAndInstall() {
        CompletableFuture.runAsync(() -> {
            String current = getCurrentVersion();
            Minecraft mc = Minecraft.getInstance();
            if (mc != null) {
                mc.execute(() -> {
                    if (mc.player != null) {
                        mc.player.displayClientMessage(Component.literal("§e[Donut Profit] Checking GitHub... Installed: §b" + current), false);
                    }
                });
            }

            try {
                HttpClient client = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(8))
                        .followRedirects(HttpClient.Redirect.ALWAYS)
                        .build();

                boolean success = fetchLatestInfo(client);
                if (success) {
                    if (mc != null) {
                        mc.execute(() -> {
                            if (mc.player != null) {
                                mc.player.displayClientMessage(Component.literal("§e[Donut Profit] GitHub Latest: §a" + latestVersion + " §e| Installed: §b" + current), false);
                            }
                        });
                    }

                    if (isNewer(latestVersion, current)) {
                        updateAvailable = true;
                        if (mc != null) {
                            mc.execute(() -> {
                                if (mc.player != null) {
                                    mc.player.displayClientMessage(Component.literal("§a[Donut Profit] Outdated! Downloading v" + latestVersion + " from GitHub... Please wait!"), false);
                                }
                            });
                        }

                        Path modsDir = FabricLoader.getInstance().getGameDir().resolve("mods");
                        Path newJarPath = modsDir.resolve("donut-smp-profit-calc-" + latestVersion + ".jar");

                        // 1. Download new version asset
                        URL url = URI.create(latestDownloadUrl).toURL();
                        try (InputStream in = url.openStream()) {
                            Files.copy(in, newJarPath, StandardCopyOption.REPLACE_EXISTING);
                        }

                        // 2. Delete old versions of donut-smp-profit-calc-*.jar
                        try (var stream = Files.list(modsDir)) {
                            stream.filter(p -> p.getFileName().toString().toLowerCase().startsWith("donut-smp-profit-calc")
                                            && p.getFileName().toString().endsWith(".jar")
                                            && !p.getFileName().toString().equalsIgnoreCase(newJarPath.getFileName().toString()))
                                  .forEach(p -> {
                                      try {
                                          Files.deleteIfExists(p);
                                          LOGGER.info("[Auto Updater] Deleted old mod jar: {}", p.getFileName());
                                      } catch (Exception e) {
                                          p.toFile().deleteOnExit();
                                      }
                                  });
                        } catch (Exception ignored) {}

                        // 3. Open RestartModalScreen popup!
                        if (mc != null) {
                            mc.execute(() -> {
                                mc.setScreen(new RestartModalScreen(latestVersion));
                                if (mc.player != null) {
                                    mc.player.displayClientMessage(Component.literal("§a§l[Donut Profit] Update v" + latestVersion + " installed! Restart Minecraft to apply."), false);
                                }
                            });
                        }
                        return;
                    } else {
                        if (mc != null) {
                            mc.execute(() -> {
                                if (mc.player != null) {
                                    mc.player.displayClientMessage(Component.literal("§a[Donut Profit] You are up to date! Installed: v" + current + " | GitHub: v" + latestVersion), false);
                                }
                            });
                        }
                        return;
                    }
                }

                if (mc != null) {
                    mc.execute(() -> {
                        if (mc.player != null) {
                            mc.player.displayClientMessage(Component.literal("§c[Donut Profit] Could not connect to GitHub API or update manifest."), false);
                        }
                    });
                }
            } catch (Exception e) {
                LOGGER.error("Failed to check/download mod update", e);
                if (mc != null) {
                    mc.execute(() -> {
                        if (mc.player != null) {
                            mc.player.displayClientMessage(Component.literal("§c[Donut Profit] Error checking GitHub: " + e.getClass().getSimpleName() + " - " + e.getMessage()), false);
                        }
                    });
                }
            }
        });
    }

    public static boolean isNewer(String latest, String current) {
        if (latest == null || current == null) return false;
        try {
            String[] partsLatest = latest.trim().split("\\.");
            String[] partsCurrent = current.trim().split("\\.");
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
