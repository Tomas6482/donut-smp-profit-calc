package com.dsmp.profitcalc.client.ui;

import com.dsmp.profitcalc.DonutSmpProfitCalc;
import com.dsmp.profitcalc.client.tracker.OrderState;
import com.dsmp.profitcalc.client.tracker.OrderStatusTracker;
import io.wispforest.owo.ui.component.LabelComponent;
import io.wispforest.owo.ui.component.UIComponents;
import io.wispforest.owo.ui.container.FlowLayout;
import io.wispforest.owo.ui.container.UIContainers;
import io.wispforest.owo.ui.core.*;

import net.minecraft.network.chat.Component;

public class StatusHudOverlay {

    private static LabelComponent statusLabel;
    private static FlowLayout rootLayout;

    public static void initialize() {
        // Register top-center status HUD component with owo-lib Hud system
        io.wispforest.owo.ui.hud.Hud.add(DonutSmpProfitCalc.id("status_hud"), StatusHudOverlay::createStatusHudComponent);

        // Subscribe to status updates
        OrderStatusTracker.getInstance().addStatusListener(StatusHudOverlay::updateStatus);
    }

    private static UIComponent createStatusHudComponent() {
        rootLayout = UIContainers.horizontalFlow(Sizing.content(), Sizing.content());
        rootLayout.padding(Insets.of(4, 12, 4, 12));
        rootLayout.margins(Insets.of(6, 0, 0, 0));
        rootLayout.surface(Surface.DARK_PANEL);
        rootLayout.positioning(Positioning.relative(50, 0));
        rootLayout.alignment(HorizontalAlignment.CENTER, VerticalAlignment.CENTER);

        OrderStatusTracker tracker = OrderStatusTracker.getInstance();
        statusLabel = UIComponents.label(Component.literal(tracker.getDisplayText()));
        statusLabel.color(Color.ofRgb(tracker.getCurrentState().getColorHex()));
        statusLabel.shadow(true);

        rootLayout.child(statusLabel);
        return rootLayout;
    }

    public static void updateStatus() {
        if (statusLabel == null) return;
        OrderStatusTracker tracker = OrderStatusTracker.getInstance();
        statusLabel.text(Component.literal(tracker.getDisplayText()));
        statusLabel.color(Color.ofRgb(tracker.getCurrentState().getColorHex()));
    }
}
