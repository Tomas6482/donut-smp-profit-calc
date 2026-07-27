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

public class RestartModalScreen extends BaseOwoScreen<FlowLayout> {

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

    private void restartGame() {
        Minecraft mc = Minecraft.getInstance();
        try {
            String javaBin = System.getProperty("java.home") + "/bin/java";
            String command = System.getProperty("sun.java.command");
            if (command != null && !command.isEmpty()) {
                ProcessBuilder builder = new ProcessBuilder(javaBin, "-cp", System.getProperty("java.class.path"), command);
                builder.directory(new java.io.File("."));
                builder.start();
            }
        } catch (Exception ignored) {}
        if (mc != null) {
            mc.execute(mc::stop);
        }
    }
}
