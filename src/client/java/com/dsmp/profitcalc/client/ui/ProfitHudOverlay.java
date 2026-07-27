package com.dsmp.profitcalc.client.ui;

import com.dsmp.profitcalc.DonutSmpProfitCalc;
import com.dsmp.profitcalc.client.config.ProfitConfig;
import com.dsmp.profitcalc.client.tracker.ProfitTracker;
import com.dsmp.profitcalc.client.tracker.Transaction;
import io.wispforest.owo.ui.component.LabelComponent;
import io.wispforest.owo.ui.component.UIComponents;
import io.wispforest.owo.ui.container.FlowLayout;
import io.wispforest.owo.ui.container.UIContainers;
import io.wispforest.owo.ui.core.*;
import io.wispforest.owo.ui.hud.Hud;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

public class ProfitHudOverlay {

    private static final Identifier HUD_ID = DonutSmpProfitCalc.id("profit_hud");
    private static LabelComponent gainedLabel;
    private static LabelComponent spentLabel;
    private static LabelComponent netProfitLabel;
    private static LabelComponent latestTxLabel;

    public static void initialize() {
        // Subscribe to profit tracker updates
        ProfitTracker.getInstance().addUpdateListener(ProfitHudOverlay::updateValues);
        refreshHud();
    }

    public static void refreshHud() {
        if (Hud.hasComponent(HUD_ID)) {
            Hud.remove(HUD_ID);
        }

        if (ProfitConfig.getInstance().isHudEnabled()) {
            Hud.add(HUD_ID, ProfitHudOverlay::createHudComponent);
        }
    }

    private static UIComponent createHudComponent() {
        FlowLayout rootLayout = UIContainers.verticalFlow(Sizing.content(), Sizing.content());
        rootLayout.padding(Insets.of(8));
        rootLayout.surface(Surface.DARK_PANEL);

        int hudX = ProfitConfig.getInstance().getHudX();
        int hudY = ProfitConfig.getInstance().getHudY();
        rootLayout.positioning(Positioning.absolute(hudX, hudY));

        // Header Title
        LabelComponent headerLabel = UIComponents.label(Component.literal("🍩 DONUT SMP PROFIT"));
        headerLabel.color(Color.ofRgb(0xFFD700)); // Gold
        headerLabel.shadow(true);
        headerLabel.margins(Insets.bottom(6));

        // Stat Labels
        gainedLabel = UIComponents.label(Component.literal("Gained:  " + ProfitTracker.getInstance().getFormattedGained()));
        gainedLabel.color(Color.ofRgb(0x55FF55)); // Emerald Green
        gainedLabel.shadow(true);

        spentLabel = UIComponents.label(Component.literal("Spent:   " + ProfitTracker.getInstance().getFormattedSpent()));
        spentLabel.color(Color.ofRgb(0xFF5555)); // Rose Red
        spentLabel.shadow(true);

        netProfitLabel = UIComponents.label(Component.literal("Profit:  " + ProfitTracker.getInstance().getFormattedNetProfit()));
        int profitColor = ProfitTracker.getInstance().getNetProfit() >= 0 ? 0x55FF55 : 0xFF5555;
        netProfitLabel.color(Color.ofRgb(profitColor));
        netProfitLabel.shadow(true);
        netProfitLabel.margins(Insets.bottom(4));

        // Latest Transaction Activity
        Transaction latest = ProfitTracker.getInstance().getLatestTransaction();
        String latestText = latest != null ? "Latest: " + latest.getSummaryText() : "Latest: No orders yet";
        latestTxLabel = UIComponents.label(Component.literal(latestText));
        latestTxLabel.color(Color.ofRgb(0xAAAAAA)); // Light Gray
        latestTxLabel.shadow(true);

        // Assemble Layout
        rootLayout.child(headerLabel);
        rootLayout.child(gainedLabel);
        rootLayout.child(spentLabel);
        rootLayout.child(netProfitLabel);
        rootLayout.child(latestTxLabel);

        return rootLayout;
    }

    public static void updateValues() {
        if (!ProfitConfig.getInstance().isHudEnabled()) return;
        if (gainedLabel == null || spentLabel == null || netProfitLabel == null) {
            refreshHud();
            return;
        }

        gainedLabel.text(Component.literal("Gained:  " + ProfitTracker.getInstance().getFormattedGained()));
        spentLabel.text(Component.literal("Spent:   " + ProfitTracker.getInstance().getFormattedSpent()));
        netProfitLabel.text(Component.literal("Profit:  " + ProfitTracker.getInstance().getFormattedNetProfit()));

        int profitColor = ProfitTracker.getInstance().getNetProfit() >= 0 ? 0x55FF55 : 0xFF5555;
        netProfitLabel.color(Color.ofRgb(profitColor));

        Transaction latest = ProfitTracker.getInstance().getLatestTransaction();
        String latestText = latest != null ? "Latest: " + latest.getSummaryText() : "Latest: No orders yet";
        latestTxLabel.text(Component.literal(latestText));
    }
}
