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
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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
     * Restarts Minecraft by reconstructing the full JVM launch command.
     * Works on both Linux and Windows with launchers like Prism/MultiMC.
     */
    public static void restartGame() {
        Minecraft mc = Minecraft.getInstance();
        try {
            List<String> cmd = buildRestartCommand();
            if (cmd != null && !cmd.isEmpty()) {
                LOGGER.info("[Donut Restart] Launching restart command: {}", String.join(" ", cmd));
                ProcessBuilder builder = new ProcessBuilder(cmd);
                builder.directory(new File(System.getProperty("user.dir")));
                builder.inheritIO();
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
     * Reconstructs the full JVM launch command from the current running process.
     * Uses /proc/self/cmdline on Linux, and ProcessHandle + RuntimeMXBean on Windows.
     */
    private static List<String> buildRestartCommand() {
        // Method 1: On Linux, read /proc/self/cmdline (most reliable)
        try {
            Path cmdlinePath = Path.of("/proc/self/cmdline");
            if (Files.exists(cmdlinePath)) {
                byte[] bytes = Files.readAllBytes(cmdlinePath);
                String full = new String(bytes);
                String[] args = full.split("\0");
                if (args.length > 0) {
                    List<String> cmd = new ArrayList<>(List.of(args));
                    LOGGER.info("[Donut Restart] Built command from /proc/self/cmdline ({} args)", cmd.size());
                    return cmd;
                }
            }
        } catch (Exception e) {
            LOGGER.warn("[Donut Restart] Failed to read /proc/self/cmdline: {}", e.getMessage());
        }

        // Method 2: Use RuntimeMXBean (cross-platform fallback for Windows)
        try {
            String javaHome = System.getProperty("java.home");
            String os = System.getProperty("os.name", "").toLowerCase();
            String javaBin = javaHome + File.separator + "bin" + File.separator + (os.contains("win") ? "javaw.exe" : "java");

            List<String> cmd = new ArrayList<>();
            cmd.add(javaBin);

            // Add all JVM arguments (like -Xmx, -XX:, -D, etc.)
            List<String> jvmArgs = ManagementFactory.getRuntimeMXBean().getInputArguments();
            cmd.addAll(jvmArgs);

            // Add classpath
            String classpath = System.getProperty("java.class.path");
            if (classpath != null && !classpath.isEmpty()) {
                cmd.add("-cp");
                cmd.add(classpath);
            }

            // Add main class and program arguments
            String sunCommand = System.getProperty("sun.java.command");
            if (sunCommand != null && !sunCommand.isEmpty()) {
                // sun.java.command contains main class + args separated by spaces
                String[] parts = sunCommand.split("\\s+");
                for (String part : parts) {
                    cmd.add(part);
                }
            }

            if (cmd.size() > 1) {
                LOGGER.info("[Donut Restart] Built command from RuntimeMXBean ({} args)", cmd.size());
                return cmd;
            }
        } catch (Exception e) {
            LOGGER.error("[Donut Restart] Failed to build command from RuntimeMXBean", e);
        }

        return null;
    }
}
