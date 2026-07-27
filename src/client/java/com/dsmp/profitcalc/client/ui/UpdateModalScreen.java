package com.dsmp.profitcalc.client.ui;

import com.dsmp.profitcalc.client.updater.AutoUpdater;
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
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.NotNull;

public class UpdateModalScreen extends BaseOwoScreen<FlowLayout> {

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
        modalCard.surface(Surface.flat(0xFA12151D).and(Surface.outline(0xFFF59E0B)))
                .padding(Insets.of(14));

        LabelComponent title = UIComponents.label(Component.literal("🍩 Donut Profit Update Available!"));
        title.color(Color.ofRgb(0xF59E0B)).margins(Insets.bottom(6));
        modalCard.child(title);

        String verText = "Installed: v" + AutoUpdater.getCurrentVersion() + "  ➔  Latest: v" + AutoUpdater.getLatestVersion();
        LabelComponent verLabel = UIComponents.label(Component.literal(verText));
        verLabel.color(Color.ofRgb(0x10B981)).margins(Insets.bottom(10));
        modalCard.child(verLabel);

        LabelComponent descLabel = UIComponents.label(Component.literal("A new update is available on GitHub. Would you like to download and install it automatically?"));
        descLabel.color(Color.ofRgb(0x9CA3AF)).margins(Insets.bottom(14));
        modalCard.child(descLabel);

        FlowLayout btnRow = UIContainers.horizontalFlow(Sizing.fill(100), Sizing.content());
        btnRow.horizontalAlignment(HorizontalAlignment.CENTER);

        ButtonComponent updateNowBtn = UIComponents.button(Component.literal("⚡ Update Now"), btn -> {
            AutoUpdater.downloadAndInstall();
            this.onClose();
        });
        updateNowBtn.margins(Insets.right(8));

        ButtonComponent laterBtn = UIComponents.button(Component.literal("Remind Me Later"), btn -> {
            this.onClose();
        });

        btnRow.child(updateNowBtn);
        btnRow.child(laterBtn);
        modalCard.child(btnRow);

        rootComponent.child(modalCard);
    }
}
