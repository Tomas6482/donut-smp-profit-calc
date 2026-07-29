package com.dsmp.profitcalc.client.ui;

import io.wispforest.owo.ui.base.BaseOwoScreen;
import io.wispforest.owo.ui.component.ButtonComponent;
import io.wispforest.owo.ui.component.LabelComponent;
import io.wispforest.owo.ui.component.UIComponents;
import io.wispforest.owo.ui.container.FlowLayout;
import io.wispforest.owo.ui.container.UIContainers;
import io.wispforest.owo.ui.core.Color;
import io.wispforest.owo.ui.core.HorizontalAlignment;
import io.wispforest.owo.ui.core.Insets;
import io.wispforest.owo.ui.core.OwoUIAdapter;
import io.wispforest.owo.ui.core.Sizing;
import io.wispforest.owo.ui.core.Surface;
import io.wispforest.owo.ui.core.VerticalAlignment;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class RestartModalScreen extends BaseOwoScreen<FlowLayout> {

    private static final Logger LOGGER = LoggerFactory.getLogger("donut-smp-profit-calc/Restart");

    private final String newVersion;

    public RestartModalScreen(String newVersion) {
        this.newVersion = newVersion;
    }

    @Override
    protected @NotNull OwoUIAdapter<FlowLayout> createAdapter() {
        return OwoUIAdapter.create(this, UIContainers::verticalFlow);
    }

    @Override
    protected void build(FlowLayout rootComponent) {
        rootComponent.surface(Surface.blur(8, 12))
                .horizontalAlignment(HorizontalAlignment.CENTER)
                .verticalAlignment(VerticalAlignment.CENTER);

        FlowLayout modalCard = UIContainers.verticalFlow(Sizing.fixed(320), Sizing.content());
        modalCard.surface(Surface.flat(0xFA12151D).and(Surface.outline(0xFF10B981)))
                .padding(Insets.of(14));

        LabelComponent title = UIComponents.label(Component.literal("🍩 Update v" + newVersion + " Installed!"));
        title.color(Color.ofRgb(0x10B981)).margins(Insets.bottom(6));
        modalCard.child(title);

        LabelComponent descLabel = UIComponents.label(Component.literal("Old mod file deleted. Version v" + newVersion + " is ready. Would you like to restart Minecraft now?"));
        descLabel.color(Color.ofRgb(0xD1D5DB)).margins(Insets.bottom(14));
        modalCard.child(descLabel);

        FlowLayout btnRow = UIContainers.horizontalFlow(Sizing.fill(100), Sizing.content());
        btnRow.horizontalAlignment(HorizontalAlignment.CENTER);

        ButtonComponent restartNowBtn = UIComponents.button(Component.literal("🔄 Restart Now"), btn -> {
            restartGame();
        });
        restartNowBtn.margins(Insets.right(8));

        ButtonComponent laterBtn = UIComponents.button(Component.literal("Later"), btn -> {
            this.onClose();
        });

        btnRow.child(restartNowBtn);
        btnRow.child(laterBtn);
        modalCard.child(btnRow);

        rootComponent.child(modalCard);
    }

    /**
     * Restarts Minecraft by detecting the launcher and re-launching through it.
     * Works on both Linux and Windows with PrismLauncher / MultiMC.
     * Falls back to just shutting down if restart isn't possible.
     */
    public static void restartGame() {
        Minecraft mc = Minecraft.getInstance();
        try {
            List<String> cmd = buildRestartCommand();
            if (cmd != null && !cmd.isEmpty()) {
                LOGGER.info("[Donut Restart] Launching restart command: {}", String.join(" ", cmd));
                ProcessBuilder builder = new ProcessBuilder(cmd);
                builder.directory(new File(System.getProperty("user.dir")));
                // Detach the child process so it outlives us
                builder.redirectErrorStream(true);
                builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
                builder.start();

                LOGGER.info("[Donut Restart] New process launched, shutting down current instance...");
                if (mc != null) {
                    mc.execute(mc::stop);
                }
                return;
            } else {
                LOGGER.warn("[Donut Restart] Could not build restart command, just shutting down.");
            }
        } catch (Exception e) {
            LOGGER.error("[Donut Restart] Failed to restart game", e);
        }
        // Fallback: just stop the game
        if (mc != null) {
            mc.execute(mc::stop);
        }
    }

    /**
     * Detects the launcher (PrismLauncher/MultiMC) and builds a command to
     * re-launch the current instance through it.
     */
    private static List<String> buildRestartCommand() {
        String nativesPath = System.getProperty("java.library.path", "");
        String os = System.getProperty("os.name", "").toLowerCase();

        // --- Detect PrismLauncher ---
        // Native library path looks like: .../PrismLauncher/instances/<name>/natives
        String instanceName = extractPrismInstanceName(nativesPath);
        if (instanceName != null) {
            LOGGER.info("[Donut Restart] Detected PrismLauncher instance: '{}'", instanceName);

            // Find the prismlauncher executable
            String launcherExe = findPrismLauncher(os);
            if (launcherExe != null) {
                List<String> cmd = new ArrayList<>();
                cmd.add(launcherExe);
                cmd.add("--launch");
                cmd.add(instanceName);
                LOGGER.info("[Donut Restart] Built PrismLauncher restart command: {}", cmd);
                return cmd;
            } else {
                LOGGER.warn("[Donut Restart] PrismLauncher instance detected but could not find launcher executable");
            }
        }

        // --- Detect MultiMC ---
        // Native library path looks like: .../MultiMC/instances/<name>/natives
        String multiMcInstance = extractMultiMcInstanceName(nativesPath);
        if (multiMcInstance != null) {
            LOGGER.info("[Donut Restart] Detected MultiMC instance: '{}'", multiMcInstance);
            String launcherExe = findMultiMc(os);
            if (launcherExe != null) {
                List<String> cmd = new ArrayList<>();
                cmd.add(launcherExe);
                cmd.add("--launch");
                cmd.add(multiMcInstance);
                return cmd;
            }
        }

        LOGGER.warn("[Donut Restart] Could not detect launcher. Restart not supported — will just shut down.");
        return null;
    }

    /**
     * Extracts the Prism instance name from the java.library.path.
     * Pattern: .../PrismLauncher/instances/<name>/natives
     */
    private static String extractPrismInstanceName(String nativesPath) {
        // Handle both forward and backslashes
        Pattern pattern = Pattern.compile("PrismLauncher[/\\\\]instances[/\\\\]([^/\\\\]+)[/\\\\]natives");
        Matcher matcher = pattern.matcher(nativesPath);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    /**
     * Extracts the MultiMC instance name from the java.library.path.
     */
    private static String extractMultiMcInstanceName(String nativesPath) {
        Pattern pattern = Pattern.compile("MultiMC[/\\\\]instances[/\\\\]([^/\\\\]+)[/\\\\]natives");
        Matcher matcher = pattern.matcher(nativesPath);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    /**
     * Finds the PrismLauncher executable on the system.
     */
    private static String findPrismLauncher(String os) {
        if (os.contains("win")) {
            // Check common Windows locations
            String[] candidates = {
                    System.getenv("LOCALAPPDATA") + "\\Programs\\PrismLauncher\\prismlauncher.exe",
                    System.getenv("PROGRAMFILES") + "\\PrismLauncher\\prismlauncher.exe",
                    "prismlauncher.exe" // hope it's on PATH
            };
            for (String path : candidates) {
                if (path != null && new File(path).exists()) return path;
            }
            // Last resort: just try the name and hope it's on PATH
            return "prismlauncher.exe";
        } else {
            // Linux: check common locations
            String[] candidates = {
                    "/usr/bin/prismlauncher",
                    "/usr/local/bin/prismlauncher",
                    System.getProperty("user.home") + "/.local/bin/prismlauncher",
                    "/usr/share/PrismLauncher/prismlauncher",
                    "/app/bin/prismlauncher" // Flatpak
            };
            for (String path : candidates) {
                if (new File(path).exists()) return path;
            }
            // Also try the Flatpak command
            try {
                Process p = new ProcessBuilder("which", "prismlauncher").start();
                p.waitFor();
                if (p.exitValue() == 0) {
                    String result = new String(p.getInputStream().readAllBytes()).trim();
                    if (!result.isEmpty()) return result;
                }
            } catch (Exception ignored) {}

            // Fallback: just use the name
            return "prismlauncher";
        }
    }

    /**
     * Finds the MultiMC executable on the system.
     */
    private static String findMultiMc(String os) {
        if (os.contains("win")) {
            return "MultiMC.exe";
        } else {
            String[] candidates = {
                    "/usr/bin/multimc",
                    "/usr/local/bin/multimc"
            };
            for (String path : candidates) {
                if (new File(path).exists()) return path;
            }
            return "multimc";
        }
    }
}
