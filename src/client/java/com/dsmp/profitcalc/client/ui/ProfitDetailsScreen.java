package com.dsmp.profitcalc.client.ui;

import com.dsmp.profitcalc.client.config.ProfitConfig;
import com.dsmp.profitcalc.client.dumper.*;
import com.dsmp.profitcalc.client.handler.AutoFlipCalcHandler;
import com.dsmp.profitcalc.client.handler.AutoFlipCalcHandler.FlipMode;
import com.dsmp.profitcalc.client.tracker.ProfitTracker;
import com.dsmp.profitcalc.client.tracker.Transaction;
import com.dsmp.profitcalc.client.tracker.TransactionType;
import io.wispforest.owo.ui.base.BaseOwoScreen;
import io.wispforest.owo.ui.component.ButtonComponent;
import io.wispforest.owo.ui.component.CheckboxComponent;
import io.wispforest.owo.ui.component.DropdownComponent;
import io.wispforest.owo.ui.component.LabelComponent;
import io.wispforest.owo.ui.component.TextAreaComponent;
import io.wispforest.owo.ui.component.TextBoxComponent;
import io.wispforest.owo.ui.component.UIComponents;
import io.wispforest.owo.ui.container.FlowLayout;
import io.wispforest.owo.ui.container.OverlayContainer;
import io.wispforest.owo.ui.container.ScrollContainer;
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

import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;

public class ProfitDetailsScreen extends BaseOwoScreen<FlowLayout> {

    private static final NumberFormat CURRENCY_FORMAT = NumberFormat.getCurrencyInstance(Locale.US);
    private static final DecimalFormat DEC_FMT = new DecimalFormat("#,##0.##");

    public static int selectedTab = -1; // -1 means load from config
    private static boolean isDropdownOpen = false;

    private FlowLayout mainCard;
    private FlowLayout leftPanel;
    private FlowLayout transactionListContainer;
    private LabelComponent gainedValueLabel;
    private LabelComponent spentValueLabel;
    private LabelComponent netProfitValueLabel;
    private ButtonComponent loggingToggleButton;
    private OverlayContainer<FlowLayout> activeOverlayModal;

    // Calculator UI Components
    private TextBoxComponent bonePriceInput;
    private TextBoxComponent targetPriceInput;
    private TextBoxComponent driedKelpPriceInput;
    private TextBoxComponent charcoalPriceInput;
    private TextBoxComponent qtyInput;

    private LabelComponent calcCostLabel;
    private LabelComponent calcOutputLabel;
    private LabelComponent calcRevenueBreakevenLabel;
    private LabelComponent calcProfitLabel;
    private LabelComponent calcPctLabel;
    private LabelComponent calcSmeltedProfitLabel;
    private LabelComponent calcSmeltedPctLabel;

    @Override
    protected @NotNull OwoUIAdapter<FlowLayout> createAdapter() {
        return OwoUIAdapter.create(this, UIContainers::verticalFlow);
    }

    @Override
    protected void build(FlowLayout rootComponent) {
        rootComponent.surface(Surface.blur(8, 12))
                .horizontalAlignment(HorizontalAlignment.CENTER)
                .verticalAlignment(VerticalAlignment.CENTER);

        if (selectedTab < 0) {
            selectedTab = ProfitConfig.getInstance().getSavedSelectedTab();
        }

        int currentThemeHex = ProfitConfig.getInstance().getThemeColorHex();

        mainCard = UIContainers.verticalFlow(Sizing.fill(90), Sizing.fill(85));
        mainCard.surface(Surface.flat(currentThemeHex).and(Surface.outline(0xFF262C36)))
                .padding(Insets.of(12));

        FlowLayout headerBar = UIContainers.horizontalFlow(Sizing.fill(100), Sizing.content());
        headerBar.verticalAlignment(VerticalAlignment.CENTER);

        LabelComponent title = UIComponents.label(Component.literal("Donut SMP Profit Dashboard"));
        title.color(Color.ofRgb(0xF59E0B)).margins(Insets.left(4));

        FlowLayout headerRight = UIContainers.horizontalFlow(Sizing.content(), Sizing.content());
        headerRight.margins(Insets.left(20));

        boolean verbose = ProfitConfig.getInstance().isVerboseLogging();
        loggingToggleButton = UIComponents.button(Component.literal(verbose ? "Logging: ON" : "Logging: OFF"), btn -> {
            boolean newState = !ProfitConfig.getInstance().isVerboseLogging();
            ProfitConfig.getInstance().setVerboseLogging(newState);
            loggingToggleButton.setMessage(Component.literal(newState ? "Logging: ON" : "Logging: OFF"));
        });
        loggingToggleButton.margins(Insets.right(4));

        ButtonComponent settingsBtn = UIComponents.button(Component.literal("Settings"), btn -> openSettingsModal());
        settingsBtn.margins(Insets.right(4));

        ButtonComponent dumperBtn = UIComponents.button(Component.literal("Price Dumper"), btn -> openPriceDumperSetupModal());
        dumperBtn.margins(Insets.right(4));

        ButtonComponent undoLastBtn = UIComponents.button(Component.literal("Undo"), btn -> {
            ProfitTracker.getInstance().removeLatestTransaction();
            refreshData();
        });
        undoLastBtn.margins(Insets.right(4));

        ButtonComponent resetBtn = UIComponents.button(Component.literal("Reset"), btn -> {
            ProfitTracker.getInstance().resetSession();
            refreshData();
        });

        headerRight.child(loggingToggleButton);
        headerRight.child(settingsBtn);
        headerRight.child(dumperBtn);
        headerRight.child(undoLastBtn);
        headerRight.child(resetBtn);

        headerBar.child(title);
        headerBar.child(headerRight);
        mainCard.child(headerBar);

        FlowLayout statsSummaryBar = UIContainers.horizontalFlow(Sizing.fill(100), Sizing.content());
        statsSummaryBar.margins(Insets.of(8, 8, 0, 0));
        statsSummaryBar.surface(Surface.flat(0x90191D24).and(Surface.outline(0xFF333B48)))
                .padding(Insets.of(8));

        gainedValueLabel = UIComponents.label(Component.literal("+$0.00"));
        gainedValueLabel.color(Color.ofRgb(0x10B981));

        spentValueLabel = UIComponents.label(Component.literal("-$0.00"));
        spentValueLabel.color(Color.ofRgb(0xEF4444));

        netProfitValueLabel = UIComponents.label(Component.literal("+$0.00"));
        netProfitValueLabel.color(Color.ofRgb(0x3B82F6));

        statsSummaryBar.child(UIComponents.label(Component.literal("Gained: ")).color(Color.ofRgb(0x9CA3AF)));
        statsSummaryBar.child(gainedValueLabel);
        statsSummaryBar.child(UIComponents.label(Component.literal("   |   Spent: ")).color(Color.ofRgb(0x9CA3AF)));
        statsSummaryBar.child(spentValueLabel);
        statsSummaryBar.child(UIComponents.label(Component.literal("   |   Net Profit: ")).color(Color.ofRgb(0x9CA3AF)));
        statsSummaryBar.child(netProfitValueLabel);

        mainCard.child(statsSummaryBar);

        FlowLayout splitBody = UIContainers.horizontalFlow(Sizing.fill(100), Sizing.fill(76));
        splitBody.margins(Insets.top(8));

        leftPanel = UIContainers.verticalFlow(Sizing.fill(32), Sizing.fill(100));
        leftPanel.surface(Surface.flat(0x9015181F).and(Surface.outline(0xFF2A313D)))
                .padding(Insets.of(8))
                .margins(Insets.right(8));

        rebuildLeftPanel();

        splitBody.child(leftPanel);

        FlowLayout rightPanel = UIContainers.verticalFlow(Sizing.fill(68), Sizing.fill(100));
        rightPanel.surface(Surface.flat(0x9015181F).and(Surface.outline(0xFF2A313D)))
                .padding(Insets.of(8));

        FlowLayout tableHeader = UIContainers.horizontalFlow(Sizing.fill(100), Sizing.content());
        tableHeader.padding(Insets.of(6)).surface(Surface.flat(0x80232936));

        tableHeader.child(UIComponents.label(Component.literal("Type")).color(Color.ofRgb(0x9CA3AF)).sizing(Sizing.fixed(45), Sizing.content()));
        tableHeader.child(UIComponents.label(Component.literal("Item")).color(Color.ofRgb(0x9CA3AF)).sizing(Sizing.fixed(120), Sizing.content()));
        tableHeader.child(UIComponents.label(Component.literal("Qty")).color(Color.ofRgb(0x9CA3AF)).sizing(Sizing.fixed(40), Sizing.content()));
        tableHeader.child(UIComponents.label(Component.literal("Unit Price")).color(Color.ofRgb(0x9CA3AF)).sizing(Sizing.fixed(75), Sizing.content()));
        tableHeader.child(UIComponents.label(Component.literal("Total")).color(Color.ofRgb(0x9CA3AF)).sizing(Sizing.fixed(75), Sizing.content()));
        tableHeader.child(UIComponents.label(Component.literal("Action")).color(Color.ofRgb(0x9CA3AF)).sizing(Sizing.fixed(50), Sizing.content()));

        rightPanel.child(tableHeader);

        transactionListContainer = UIContainers.verticalFlow(Sizing.fill(100), Sizing.content());
        ScrollContainer<FlowLayout> scrollList = UIContainers.verticalScroll(Sizing.fill(100), Sizing.fill(90), transactionListContainer);
        rightPanel.child(scrollList);

        splitBody.child(rightPanel);
        mainCard.child(splitBody);

        rootComponent.child(mainCard);

        refreshData();
        updateCalc();
    }

    private void rebuildLeftPanel() {
        if (leftPanel == null) return;
        leftPanel.clearChildren();

        ProfitConfig config = ProfitConfig.getInstance();

        // 1. Flip Selection Dropdown Button
        FlowLayout flipRow = UIContainers.horizontalFlow(Sizing.fill(100), Sizing.content());
        flipRow.margins(Insets.bottom(4));
        flipRow.verticalAlignment(VerticalAlignment.CENTER);

        String activeFlipTitle;
        switch (selectedTab) {
            case 1: activeFlipTitle = "Kelp Flip"; break;
            case 2: activeFlipTitle = "Oak Log Flip"; break;
            case 3: activeFlipTitle = "Sticky Piston Flip"; break;
            case 4: activeFlipTitle = "Golden Apple Flip"; break;
            case 5: activeFlipTitle = "Bookshelf Flip"; break;
            case 0:
            default: activeFlipTitle = "Bone Flip"; break;
        }

        String arrow = isDropdownOpen ? " ▲" : " ▼";
        ButtonComponent flipDropdownBtn = UIComponents.button(Component.literal("Flip: " + activeFlipTitle + arrow), btn -> {
            isDropdownOpen = !isDropdownOpen;
            Minecraft.getInstance().setScreen(new ProfitDetailsScreen());
        });
        flipDropdownBtn.sizing(Sizing.fill(100), Sizing.fixed(18));
        flipRow.child(flipDropdownBtn);
        leftPanel.child(flipRow);

        // If Dropdown is open, render options list directly under the button matching its exact width!
        if (isDropdownOpen) {
            FlowLayout dropdownMenu = UIContainers.verticalFlow(Sizing.fill(100), Sizing.content());
            dropdownMenu.surface(Surface.flat(0xF0101216).and(Surface.outline(0xFF333B48)))
                    .padding(Insets.of(4))
                    .margins(Insets.bottom(6));

            String[] options = {
                "Bone Flip", "Kelp Flip", "Oak Log Flip",
                "Sticky Piston Flip", "Golden Apple Flip", "Bookshelf Flip"
            };

            for (int i = 0; i < options.length; i++) {
                final int tabIdx = i;
                String optText = options[i];
                boolean isSelected = (selectedTab == tabIdx);

                ButtonComponent optBtn = UIComponents.button(
                    Component.literal((isSelected ? "✔ " : "   ") + optText),
                    b -> {
                        selectedTab = tabIdx;
                        config.setSavedSelectedTab(tabIdx);
                        isDropdownOpen = false;
                        Minecraft.getInstance().setScreen(new ProfitDetailsScreen());
                    }
                );
                optBtn.sizing(Sizing.fill(100), Sizing.fixed(16));
                optBtn.margins(Insets.vertical(1));
                dropdownMenu.child(optBtn);
            }

            leftPanel.child(dropdownMenu);
        }

        // 2. Dynamic Input Fields based on Flip Selection
        if (selectedTab == 0) {
            // --- BONE FLIP ---
            leftPanel.child(UIComponents.label(Component.literal("Bone Price ($/bone):")).color(Color.ofRgb(0x9CA3AF)));
            bonePriceInput = UIComponents.textBox(Sizing.fill(100));
            bonePriceInput.setTextColorUneditable(0xE0E0E0);
            bonePriceInput.setMaxLength(16);
            bonePriceInput.text(config.getSavedBonePrice());
            bonePriceInput.onChanged().subscribe(s -> { config.setSavedBonePrice(s); updateCalc(); });
            bonePriceInput.margins(Insets.bottom(2));
            leftPanel.child(bonePriceInput);

            leftPanel.child(UIComponents.label(Component.literal("Block Price ($/block):")).color(Color.ofRgb(0x9CA3AF)));
            targetPriceInput = UIComponents.textBox(Sizing.fill(100));
            targetPriceInput.setTextColorUneditable(0xE0E0E0);
            targetPriceInput.setMaxLength(16);
            targetPriceInput.text(config.getSavedBlockPrice());
            targetPriceInput.onChanged().subscribe(s -> { config.setSavedBlockPrice(s); updateCalc(); });
            targetPriceInput.margins(Insets.bottom(2));
            leftPanel.child(targetPriceInput);

            leftPanel.child(UIComponents.label(Component.literal("Bones Qty:")).color(Color.ofRgb(0x9CA3AF)));
            qtyInput = UIComponents.textBox(Sizing.fill(100));
            qtyInput.setTextColorUneditable(0xE0E0E0);
            qtyInput.setMaxLength(16);
            qtyInput.text(config.getSavedBonesQty());
            qtyInput.onChanged().subscribe(s -> { config.setSavedBonesQty(s); updateCalc(); });
            qtyInput.margins(Insets.bottom(4));
            leftPanel.child(qtyInput);

        } else if (selectedTab == 1) {
            // --- KELP FLIP ---
            leftPanel.child(UIComponents.label(Component.literal("Bone Price ($/bone):")).color(Color.ofRgb(0x9CA3AF)));
            bonePriceInput = UIComponents.textBox(Sizing.fill(100));
            bonePriceInput.setTextColorUneditable(0xE0E0E0);
            bonePriceInput.setMaxLength(16);
            bonePriceInput.text(config.getSavedBonePrice());
            bonePriceInput.onChanged().subscribe(s -> { config.setSavedBonePrice(s); updateCalc(); });
            bonePriceInput.margins(Insets.bottom(2));
            leftPanel.child(bonePriceInput);

            leftPanel.child(UIComponents.label(Component.literal("Raw Kelp Price ($/bl):")).color(Color.ofRgb(0x9CA3AF)));
            targetPriceInput = UIComponents.textBox(Sizing.fill(100));
            targetPriceInput.setTextColorUneditable(0xE0E0E0);
            targetPriceInput.setMaxLength(16);
            targetPriceInput.text(config.getSavedRawKelpPrice());
            targetPriceInput.onChanged().subscribe(s -> { config.setSavedRawKelpPrice(s); updateCalc(); });
            targetPriceInput.margins(Insets.bottom(2));
            leftPanel.child(targetPriceInput);

            leftPanel.child(UIComponents.label(Component.literal("Dried Kelp Price ($/bl):")).color(Color.ofRgb(0x9CA3AF)));
            driedKelpPriceInput = UIComponents.textBox(Sizing.fill(100));
            driedKelpPriceInput.setTextColorUneditable(0xE0E0E0);
            driedKelpPriceInput.setMaxLength(16);
            driedKelpPriceInput.text(config.getSavedDriedKelpPrice());
            driedKelpPriceInput.onChanged().subscribe(s -> { config.setSavedDriedKelpPrice(s); updateCalc(); });
            driedKelpPriceInput.margins(Insets.bottom(2));
            leftPanel.child(driedKelpPriceInput);

            leftPanel.child(UIComponents.label(Component.literal("Charcoal Price ($/ea):")).color(Color.ofRgb(0x9CA3AF)));
            charcoalPriceInput = UIComponents.textBox(Sizing.fill(100));
            charcoalPriceInput.setTextColorUneditable(0xE0E0E0);
            charcoalPriceInput.setMaxLength(16);
            charcoalPriceInput.text(config.getSavedCharcoalPrice());
            charcoalPriceInput.onChanged().subscribe(s -> { config.setSavedCharcoalPrice(s); updateCalc(); });
            charcoalPriceInput.margins(Insets.bottom(2));
            leftPanel.child(charcoalPriceInput);

            leftPanel.child(UIComponents.label(Component.literal("Bones Qty:")).color(Color.ofRgb(0x9CA3AF)));
            qtyInput = UIComponents.textBox(Sizing.fill(100));
            qtyInput.setTextColorUneditable(0xE0E0E0);
            qtyInput.setMaxLength(16);
            qtyInput.text(config.getSavedBonesQty());
            qtyInput.onChanged().subscribe(s -> { config.setSavedBonesQty(s); updateCalc(); });
            qtyInput.margins(Insets.bottom(4));
            leftPanel.child(qtyInput);

        } else if (selectedTab == 2) {
            // --- OAK LOG FLIP ---
            leftPanel.child(UIComponents.label(Component.literal("Oak Log Price ($/log):")).color(Color.ofRgb(0x9CA3AF)));
            bonePriceInput = UIComponents.textBox(Sizing.fill(100));
            bonePriceInput.setTextColorUneditable(0xE0E0E0);
            bonePriceInput.setMaxLength(16);
            bonePriceInput.text(config.getSavedOakLogPrice());
            bonePriceInput.onChanged().subscribe(s -> { config.setSavedOakLogPrice(s); updateCalc(); });
            bonePriceInput.margins(Insets.bottom(2));
            leftPanel.child(bonePriceInput);

            leftPanel.child(UIComponents.label(Component.literal("Oak Planks Price ($/plank):")).color(Color.ofRgb(0x9CA3AF)));
            targetPriceInput = UIComponents.textBox(Sizing.fill(100));
            targetPriceInput.setTextColorUneditable(0xE0E0E0);
            targetPriceInput.setMaxLength(16);
            targetPriceInput.text(config.getSavedOakPlanksPrice());
            targetPriceInput.onChanged().subscribe(s -> { config.setSavedOakPlanksPrice(s); updateCalc(); });
            targetPriceInput.margins(Insets.bottom(2));
            leftPanel.child(targetPriceInput);

            leftPanel.child(UIComponents.label(Component.literal("Oak Logs Qty:")).color(Color.ofRgb(0x9CA3AF)));
            qtyInput = UIComponents.textBox(Sizing.fill(100));
            qtyInput.setTextColorUneditable(0xE0E0E0);
            qtyInput.setMaxLength(16);
            qtyInput.text(config.getSavedBonesQty());
            qtyInput.onChanged().subscribe(s -> { config.setSavedBonesQty(s); updateCalc(); });
            qtyInput.margins(Insets.bottom(4));
            leftPanel.child(qtyInput);

        } else if (selectedTab == 3) {
            // --- STICKY PISTON FLIP ---
            leftPanel.child(UIComponents.label(Component.literal("Piston Price ($/ea):")).color(Color.ofRgb(0x9CA3AF)));
            bonePriceInput = UIComponents.textBox(Sizing.fill(100));
            bonePriceInput.setTextColorUneditable(0xE0E0E0);
            bonePriceInput.setMaxLength(16);
            bonePriceInput.text(config.getSavedPistonPrice());
            bonePriceInput.onChanged().subscribe(s -> { config.setSavedPistonPrice(s); updateCalc(); });
            bonePriceInput.margins(Insets.bottom(2));
            leftPanel.child(bonePriceInput);

            leftPanel.child(UIComponents.label(Component.literal("Slimeball Price ($/ea):")).color(Color.ofRgb(0x9CA3AF)));
            driedKelpPriceInput = UIComponents.textBox(Sizing.fill(100));
            driedKelpPriceInput.setTextColorUneditable(0xE0E0E0);
            driedKelpPriceInput.setMaxLength(16);
            driedKelpPriceInput.text(config.getSavedSlimeballPrice());
            driedKelpPriceInput.onChanged().subscribe(s -> { config.setSavedSlimeballPrice(s); updateCalc(); });
            driedKelpPriceInput.margins(Insets.bottom(2));
            leftPanel.child(driedKelpPriceInput);

            leftPanel.child(UIComponents.label(Component.literal("Sticky Piston Price ($/ea):")).color(Color.ofRgb(0x9CA3AF)));
            targetPriceInput = UIComponents.textBox(Sizing.fill(100));
            targetPriceInput.setTextColorUneditable(0xE0E0E0);
            targetPriceInput.setMaxLength(16);
            targetPriceInput.text(config.getSavedStickyPistonPrice());
            targetPriceInput.onChanged().subscribe(s -> { config.setSavedStickyPistonPrice(s); updateCalc(); });
            targetPriceInput.margins(Insets.bottom(2));
            leftPanel.child(targetPriceInput);

            leftPanel.child(UIComponents.label(Component.literal("Craft Qty:")).color(Color.ofRgb(0x9CA3AF)));
            qtyInput = UIComponents.textBox(Sizing.fill(100));
            qtyInput.setTextColorUneditable(0xE0E0E0);
            qtyInput.setMaxLength(16);
            qtyInput.text(config.getSavedBonesQty());
            qtyInput.onChanged().subscribe(s -> { config.setSavedBonesQty(s); updateCalc(); });
            qtyInput.margins(Insets.bottom(4));
            leftPanel.child(qtyInput);

        } else if (selectedTab == 4) {
            // --- GOLDEN APPLE FLIP ---
            leftPanel.child(UIComponents.label(Component.literal("Gold Ingot Price ($/ingot):")).color(Color.ofRgb(0x9CA3AF)));
            bonePriceInput = UIComponents.textBox(Sizing.fill(100));
            bonePriceInput.setTextColorUneditable(0xE0E0E0);
            bonePriceInput.setMaxLength(16);
            bonePriceInput.text(config.getSavedGoldIngotPrice());
            bonePriceInput.onChanged().subscribe(s -> { config.setSavedGoldIngotPrice(s); updateCalc(); });
            bonePriceInput.margins(Insets.bottom(2));
            leftPanel.child(bonePriceInput);

            leftPanel.child(UIComponents.label(Component.literal("Apple Price ($/apple):")).color(Color.ofRgb(0x9CA3AF)));
            driedKelpPriceInput = UIComponents.textBox(Sizing.fill(100));
            driedKelpPriceInput.setTextColorUneditable(0xE0E0E0);
            driedKelpPriceInput.setMaxLength(16);
            driedKelpPriceInput.text(config.getSavedApplePrice());
            driedKelpPriceInput.onChanged().subscribe(s -> { config.setSavedApplePrice(s); updateCalc(); });
            driedKelpPriceInput.margins(Insets.bottom(2));
            leftPanel.child(driedKelpPriceInput);

            leftPanel.child(UIComponents.label(Component.literal("Golden Apple Price ($/ea):")).color(Color.ofRgb(0x9CA3AF)));
            targetPriceInput = UIComponents.textBox(Sizing.fill(100));
            targetPriceInput.setTextColorUneditable(0xE0E0E0);
            targetPriceInput.setMaxLength(16);
            targetPriceInput.text(config.getSavedGapplePrice());
            targetPriceInput.onChanged().subscribe(s -> { config.setSavedGapplePrice(s); updateCalc(); });
            targetPriceInput.margins(Insets.bottom(2));
            leftPanel.child(targetPriceInput);

            leftPanel.child(UIComponents.label(Component.literal("Craft Qty:")).color(Color.ofRgb(0x9CA3AF)));
            qtyInput = UIComponents.textBox(Sizing.fill(100));
            qtyInput.setTextColorUneditable(0xE0E0E0);
            qtyInput.setMaxLength(16);
            qtyInput.text(config.getSavedBonesQty());
            qtyInput.onChanged().subscribe(s -> { config.setSavedBonesQty(s); updateCalc(); });
            qtyInput.margins(Insets.bottom(4));
            leftPanel.child(qtyInput);

        } else if (selectedTab == 5) {
            // --- BOOKSHELF FLIP ---
            leftPanel.child(UIComponents.label(Component.literal("Planks Price ($/plank):")).color(Color.ofRgb(0x9CA3AF)));
            bonePriceInput = UIComponents.textBox(Sizing.fill(100));
            bonePriceInput.setTextColorUneditable(0xE0E0E0);
            bonePriceInput.setMaxLength(16);
            bonePriceInput.text(config.getSavedOakPlanksPrice());
            bonePriceInput.onChanged().subscribe(s -> { config.setSavedOakPlanksPrice(s); updateCalc(); });
            bonePriceInput.margins(Insets.bottom(2));
            leftPanel.child(bonePriceInput);

            leftPanel.child(UIComponents.label(Component.literal("Book Price ($/book):")).color(Color.ofRgb(0x9CA3AF)));
            driedKelpPriceInput = UIComponents.textBox(Sizing.fill(100));
            driedKelpPriceInput.setTextColorUneditable(0xE0E0E0);
            driedKelpPriceInput.setMaxLength(16);
            driedKelpPriceInput.text(config.getSavedBookPrice());
            driedKelpPriceInput.onChanged().subscribe(s -> { config.setSavedBookPrice(s); updateCalc(); });
            driedKelpPriceInput.margins(Insets.bottom(2));
            leftPanel.child(driedKelpPriceInput);

            leftPanel.child(UIComponents.label(Component.literal("Bookshelf Price ($/ea):")).color(Color.ofRgb(0x9CA3AF)));
            targetPriceInput = UIComponents.textBox(Sizing.fill(100));
            targetPriceInput.setTextColorUneditable(0xE0E0E0);
            targetPriceInput.setMaxLength(16);
            targetPriceInput.text(config.getSavedBookshelfPrice());
            targetPriceInput.onChanged().subscribe(s -> { config.setSavedBookshelfPrice(s); updateCalc(); });
            targetPriceInput.margins(Insets.bottom(2));
            leftPanel.child(targetPriceInput);

            leftPanel.child(UIComponents.label(Component.literal("Craft Qty:")).color(Color.ofRgb(0x9CA3AF)));
            qtyInput = UIComponents.textBox(Sizing.fill(100));
            qtyInput.setTextColorUneditable(0xE0E0E0);
            qtyInput.setMaxLength(16);
            qtyInput.text(config.getSavedBonesQty());
            qtyInput.onChanged().subscribe(s -> { config.setSavedBonesQty(s); updateCalc(); });
            qtyInput.margins(Insets.bottom(4));
            leftPanel.child(qtyInput);
        }

        // Auto Check Button
        ButtonComponent autoCheckBtn = UIComponents.button(Component.literal("Auto Check Prices"), btn -> {
            if (selectedTab == 0) {
                AutoFlipCalcHandler.start(FlipMode.BONE);
            } else if (selectedTab == 1) {
                AutoFlipCalcHandler.start(FlipMode.KELP);
            } else {
                if (Minecraft.getInstance().player != null) {
                    Minecraft.getInstance().player.displayClientMessage(Component.literal("§a[Auto Flip] Use Price Dumper for custom item scanning!"), true);
                }
            }
        });
        autoCheckBtn.sizing(Sizing.fill(100), Sizing.fixed(18));
        autoCheckBtn.margins(Insets.bottom(4));
        leftPanel.child(autoCheckBtn);

        // Dynamic Calculation Results Container
        FlowLayout calcResultsBox = UIContainers.verticalFlow(Sizing.fill(100), Sizing.content());
        calcResultsBox.surface(Surface.flat(0x600C0D0B).and(Surface.outline(0xFF333B48)))
                .padding(Insets.of(6));

        calcCostLabel = UIComponents.label(Component.literal("Cost: —")).color(Color.ofRgb(0xD1D5DB));
        calcOutputLabel = UIComponents.label(Component.literal("Output: —")).color(Color.ofRgb(0xD1D5DB));
        calcRevenueBreakevenLabel = UIComponents.label(Component.literal("Breakeven: —")).color(Color.ofRgb(0x9CA3AF));
        calcProfitLabel = UIComponents.label(Component.literal("Profit: $0")).color(Color.ofRgb(0x10B981));
        calcPctLabel = UIComponents.label(Component.literal("Margin: 0.0%")).color(Color.ofRgb(0x10B981));

        calcResultsBox.child(calcCostLabel);
        calcResultsBox.child(calcOutputLabel);
        calcResultsBox.child(calcRevenueBreakevenLabel);

        calcResultsBox.child(UIComponents.box(Sizing.fill(100), Sizing.fixed(1)).margins(Insets.vertical(2)));
        calcResultsBox.child(calcProfitLabel);
        calcResultsBox.child(calcPctLabel);

        if (selectedTab == 1) {
            calcSmeltedProfitLabel = UIComponents.label(Component.literal("Smelted Profit: $0")).color(Color.ofRgb(0x3B82F6));
            calcSmeltedPctLabel = UIComponents.label(Component.literal("Smelted ROI: 0.0%")).color(Color.ofRgb(0x3B82F6));

            calcResultsBox.child(UIComponents.box(Sizing.fill(100), Sizing.fixed(1)).margins(Insets.vertical(2)));
            calcResultsBox.child(calcSmeltedProfitLabel);
            calcResultsBox.child(calcSmeltedPctLabel);
        }

        leftPanel.child(calcResultsBox);
    }

    private void updateCalc() {
        if (bonePriceInput == null || targetPriceInput == null || qtyInput == null) return;
        try {
            double p1 = parseDouble(bonePriceInput.getValue(), 0.0);
            double p2 = parseDouble(targetPriceInput.getValue(), 0.0);
            double qty = parseDouble(qtyInput.getValue(), 0.0);

            if (selectedTab == 0) {
                // --- BONE FLIP ---
                double cost = p1 * qty;
                double blocksCraft = qty / 3.0;
                double revenue = blocksCraft * p2;
                double profit = revenue - cost;
                double breakeven = p1 * 3.0;
                double marginPct = cost > 0 ? (profit / cost) * 100.0 : 0.0;

                calcCostLabel.text(Component.literal("Cost: $" + DEC_FMT.format(cost)));
                calcOutputLabel.text(Component.literal("Blocks: " + DEC_FMT.format(blocksCraft)));
                calcRevenueBreakevenLabel.text(Component.literal("Breakeven: $" + DEC_FMT.format(breakeven) + "/bl"));

                boolean isPos = profit >= 0;
                String sign = isPos ? "+" : "-";
                calcProfitLabel.text(Component.literal("Profit: " + sign + "$" + DEC_FMT.format(Math.abs(profit))));
                calcProfitLabel.color(Color.ofRgb(isPos ? 0x10B981 : 0xEF4444));

                calcPctLabel.text(Component.literal("Margin: " + (isPos ? "+" : "") + String.format("%.1f%%", marginPct)));
                calcPctLabel.color(Color.ofRgb(isPos ? 0x10B981 : 0xEF4444));
            } else if (selectedTab == 1) {
                // --- KELP FLIP ---
                double cost = p1 * qty;
                double rawKelpPrice = p2;
                double driedKelpPrice = driedKelpPriceInput != null ? parseDouble(driedKelpPriceInput.getValue(), 0.0) : 0.0;
                double charcoalPrice = charcoalPriceInput != null ? parseDouble(charcoalPriceInput.getValue(), 0.0) : 0.0;

                double kelpBlocks = qty * 3.0;
                double revenueRaw = kelpBlocks * rawKelpPrice;
                double profitRaw = revenueRaw - cost;
                double roiRawPct = cost > 0 ? (profitRaw / cost) * 100.0 : 0.0;

                double charcoalNeeded = kelpBlocks * 1.125;
                double charcoalCost = charcoalNeeded * charcoalPrice;
                double totalSmeltedCost = cost + charcoalCost;
                double revenueDried = kelpBlocks * driedKelpPrice;
                double profitDried = revenueDried - totalSmeltedCost;
                double roiDriedPct = totalSmeltedCost > 0 ? (profitDried / totalSmeltedCost) * 100.0 : 0.0;

                calcCostLabel.text(Component.literal("Bone Cost: $" + DEC_FMT.format(cost)));
                calcOutputLabel.text(Component.literal("Kelp Blocks: " + DEC_FMT.format(kelpBlocks)));
                calcRevenueBreakevenLabel.text(Component.literal("Raw Rev: $" + DEC_FMT.format(revenueRaw)));

                boolean isPosRaw = profitRaw >= 0;
                String signRaw = isPosRaw ? "+" : "-";
                calcProfitLabel.text(Component.literal("Raw Profit: " + signRaw + "$" + DEC_FMT.format(Math.abs(profitRaw))));
                calcProfitLabel.color(Color.ofRgb(isPosRaw ? 0x10B981 : 0xEF4444));

                calcPctLabel.text(Component.literal("Raw ROI: " + (isPosRaw ? "+" : "") + String.format("%.1f%%", roiRawPct)));
                calcPctLabel.color(Color.ofRgb(isPosRaw ? 0x10B981 : 0xEF4444));

                if (calcSmeltedProfitLabel != null && calcSmeltedPctLabel != null) {
                    boolean isPosDried = profitDried >= 0;
                    String signDried = isPosDried ? "+" : "-";
                    calcSmeltedProfitLabel.text(Component.literal("Smelted Profit: " + signDried + "$" + DEC_FMT.format(Math.abs(profitDried))));
                    calcSmeltedProfitLabel.color(Color.ofRgb(isPosDried ? 0x10B981 : 0xEF4444));

                    calcSmeltedPctLabel.text(Component.literal("Smelted ROI: " + (isPosDried ? "+" : "") + String.format("%.1f%% (Coal: $" + DEC_FMT.format(charcoalCost) + ")", roiDriedPct)));
                    calcSmeltedPctLabel.color(Color.ofRgb(isPosDried ? 0x10B981 : 0xEF4444));
                }
            } else if (selectedTab == 2) {
                // --- OAK LOG FLIP ---
                double logPrice = p1;
                double plankPrice = p2;
                double logs = qty;
                double planks = logs * 4.0;
                double cost = logs * logPrice;
                double revenue = planks * plankPrice;
                double profit = revenue - cost;
                double breakeven = logPrice / 4.0;
                double margin = cost > 0 ? (profit / cost) * 100.0 : 0.0;

                calcCostLabel.text(Component.literal("Log Cost: $" + DEC_FMT.format(cost)));
                calcOutputLabel.text(Component.literal("Planks Out: " + DEC_FMT.format(planks)));
                calcRevenueBreakevenLabel.text(Component.literal("Breakeven: $" + DEC_FMT.format(breakeven) + "/plank"));

                boolean isPos = profit >= 0;
                String sign = isPos ? "+" : "-";
                calcProfitLabel.text(Component.literal("Profit: " + sign + "$" + DEC_FMT.format(Math.abs(profit))));
                calcProfitLabel.color(Color.ofRgb(isPos ? 0x10B981 : 0xEF4444));

                calcPctLabel.text(Component.literal("Margin: " + (isPos ? "+" : "") + String.format("%.1f%%", margin)));
                calcPctLabel.color(Color.ofRgb(isPos ? 0x10B981 : 0xEF4444));

            } else if (selectedTab == 3) {
                // --- STICKY PISTON FLIP ---
                double pistonPrice = p1;
                double slimePrice = driedKelpPriceInput != null ? parseDouble(driedKelpPriceInput.getValue(), 0.0) : 0.0;
                double stickyPrice = p2;
                double cost = (pistonPrice + slimePrice) * qty;
                double revenue = stickyPrice * qty;
                double profit = revenue - cost;
                double margin = cost > 0 ? (profit / cost) * 100.0 : 0.0;

                calcCostLabel.text(Component.literal("Mats Cost: $" + DEC_FMT.format(cost)));
                calcOutputLabel.text(Component.literal("Sticky Pistons: " + DEC_FMT.format(qty)));
                calcRevenueBreakevenLabel.text(Component.literal("Breakeven: $" + DEC_FMT.format(pistonPrice + slimePrice) + "/ea"));

                boolean isPos = profit >= 0;
                String sign = isPos ? "+" : "-";
                calcProfitLabel.text(Component.literal("Profit: " + sign + "$" + DEC_FMT.format(Math.abs(profit))));
                calcProfitLabel.color(Color.ofRgb(isPos ? 0x10B981 : 0xEF4444));

                calcPctLabel.text(Component.literal("Margin: " + (isPos ? "+" : "") + String.format("%.1f%%", margin)));
                calcPctLabel.color(Color.ofRgb(isPos ? 0x10B981 : 0xEF4444));

            } else if (selectedTab == 4) {
                // --- GOLDEN APPLE FLIP ---
                double goldPrice = p1;
                double applePrice = driedKelpPriceInput != null ? parseDouble(driedKelpPriceInput.getValue(), 0.0) : 0.0;
                double gapplePrice = p2;
                double matCost = (goldPrice * 8.0) + applePrice;
                double cost = matCost * qty;
                double revenue = gapplePrice * qty;
                double profit = revenue - cost;
                double margin = cost > 0 ? (profit / cost) * 100.0 : 0.0;

                calcCostLabel.text(Component.literal("Mats Cost: $" + DEC_FMT.format(cost)));
                calcOutputLabel.text(Component.literal("Gapples Out: " + DEC_FMT.format(qty)));
                calcRevenueBreakevenLabel.text(Component.literal("Breakeven: $" + DEC_FMT.format(matCost) + "/gapple"));

                boolean isPos = profit >= 0;
                String sign = isPos ? "+" : "-";
                calcProfitLabel.text(Component.literal("Profit: " + sign + "$" + DEC_FMT.format(Math.abs(profit))));
                calcProfitLabel.color(Color.ofRgb(isPos ? 0x10B981 : 0xEF4444));

                calcPctLabel.text(Component.literal("Margin: " + (isPos ? "+" : "") + String.format("%.1f%%", margin)));
                calcPctLabel.color(Color.ofRgb(isPos ? 0x10B981 : 0xEF4444));

            } else if (selectedTab == 5) {
                // --- BOOKSHELF FLIP ---
                double plankPrice = p1;
                double bookPrice = driedKelpPriceInput != null ? parseDouble(driedKelpPriceInput.getValue(), 0.0) : 0.0;
                double bookshelfPrice = p2;
                double matCost = (plankPrice * 6.0) + (bookPrice * 3.0);
                double cost = matCost * qty;
                double revenue = bookshelfPrice * qty;
                double profit = revenue - cost;
                double margin = cost > 0 ? (profit / cost) * 100.0 : 0.0;

                calcCostLabel.text(Component.literal("Mats Cost: $" + DEC_FMT.format(cost)));
                calcOutputLabel.text(Component.literal("Bookshelves: " + DEC_FMT.format(qty)));
                calcRevenueBreakevenLabel.text(Component.literal("Breakeven: $" + DEC_FMT.format(matCost) + "/shelf"));

                boolean isPos = profit >= 0;
                String sign = isPos ? "+" : "-";
                calcProfitLabel.text(Component.literal("Profit: " + sign + "$" + DEC_FMT.format(Math.abs(profit))));
                calcProfitLabel.color(Color.ofRgb(isPos ? 0x10B981 : 0xEF4444));

                calcPctLabel.text(Component.literal("Margin: " + (isPos ? "+" : "") + String.format("%.1f%%", margin)));
                calcPctLabel.color(Color.ofRgb(isPos ? 0x10B981 : 0xEF4444));
            }
        } catch (Exception ignored) {}
    }

    private void openSettingsModal() {
        if (uiAdapter == null) return;
        closeSettingsModal();

        FlowLayout modalCard = UIContainers.verticalFlow(Sizing.fixed(320), Sizing.content());
        modalCard.surface(Surface.flat(0xFA12151D).and(Surface.outline(0xFFF59E0B)))
                .padding(Insets.of(12));

        FlowLayout modalHeader = UIContainers.horizontalFlow(Sizing.fill(100), Sizing.content());
        modalHeader.verticalAlignment(VerticalAlignment.CENTER);

        LabelComponent modalTitle = UIComponents.label(Component.literal("Settings & Colors"));
        modalTitle.color(Color.ofRgb(0xF59E0B));

        FlowLayout closeSpacer = UIContainers.horizontalFlow(Sizing.fill(100), Sizing.content());
        closeSpacer.horizontalAlignment(HorizontalAlignment.RIGHT);
        ButtonComponent closeBtn = UIComponents.button(Component.literal("✕"), btn -> closeSettingsModal());
        closeBtn.sizing(Sizing.fixed(18), Sizing.fixed(16));
        closeSpacer.child(closeBtn);

        modalHeader.child(modalTitle);
        modalHeader.child(closeSpacer);
        modalCard.child(modalHeader);

        LabelComponent hudLabel = UIComponents.label(Component.literal("HUD Position Presets:"));
        hudLabel.color(Color.ofRgb(0x9CA3AF)).margins(Insets.of(8, 0, 4, 0));
        modalCard.child(hudLabel);

        FlowLayout posRow1 = UIContainers.horizontalFlow(Sizing.fill(100), Sizing.content());
        ButtonComponent btnTL = UIComponents.button(Component.literal("Top Left"), b -> applyPreset(ProfitConfig.HudPosition.TOP_LEFT));
        btnTL.margins(Insets.right(4));
        ButtonComponent btnTR = UIComponents.button(Component.literal("Top Right"), b -> applyPreset(ProfitConfig.HudPosition.TOP_RIGHT));
        posRow1.child(btnTL);
        posRow1.child(btnTR);
        modalCard.child(posRow1);

        FlowLayout posRow2 = UIContainers.horizontalFlow(Sizing.fill(100), Sizing.content());
        posRow2.margins(Insets.top(4));
        ButtonComponent btnBL = UIComponents.button(Component.literal("Bottom Left"), b -> applyPreset(ProfitConfig.HudPosition.BOTTOM_LEFT));
        btnBL.margins(Insets.right(4));
        ButtonComponent btnBR = UIComponents.button(Component.literal("Bottom Right"), b -> applyPreset(ProfitConfig.HudPosition.BOTTOM_RIGHT));
        posRow2.child(btnBL);
        posRow2.child(btnBR);
        modalCard.child(posRow2);

        LabelComponent themeLabel = UIComponents.label(Component.literal("Background Color Theme:"));
        themeLabel.color(Color.ofRgb(0x9CA3AF)).margins(Insets.of(10, 0, 4, 0));
        modalCard.child(themeLabel);

        FlowLayout themeRow1 = UIContainers.horizontalFlow(Sizing.fill(100), Sizing.content());
        ButtonComponent tDark = UIComponents.button(Component.literal("Dark"), b -> applyTheme(0xD0101216));
        tDark.margins(Insets.right(4));
        ButtonComponent tRed = UIComponents.button(Component.literal("Red"), b -> applyTheme(0xD02B0F14));
        tRed.margins(Insets.right(4));
        ButtonComponent tGreen = UIComponents.button(Component.literal("Green"), b -> applyTheme(0xD00E2618));
        tGreen.margins(Insets.right(4));
        ButtonComponent tBlue = UIComponents.button(Component.literal("Blue"), b -> applyTheme(0xD00E1C2B));
        themeRow1.child(tDark);
        themeRow1.child(tRed);
        themeRow1.child(tGreen);
        themeRow1.child(tBlue);
        modalCard.child(themeRow1);

        FlowLayout themeRow2 = UIContainers.horizontalFlow(Sizing.fill(100), Sizing.content());
        themeRow2.margins(Insets.top(4));
        ButtonComponent tPurple = UIComponents.button(Component.literal("Purple"), b -> applyTheme(0xD0210E2B));
        tPurple.margins(Insets.right(4));
        ButtonComponent tPink = UIComponents.button(Component.literal("Pink"), b -> applyTheme(0xD02B0E20));
        tPink.margins(Insets.right(4));
        ButtonComponent tYellow = UIComponents.button(Component.literal("Yellow"), b -> applyTheme(0xD02B210E));
        tYellow.margins(Insets.right(4));
        ButtonComponent tOrange = UIComponents.button(Component.literal("Orange"), b -> applyTheme(0xD02B190E));
        themeRow2.child(tPurple);
        themeRow2.child(tPink);
        themeRow2.child(tYellow);
        themeRow2.child(tOrange);
        modalCard.child(themeRow2);

        FlowLayout themeRow3 = UIContainers.horizontalFlow(Sizing.fill(100), Sizing.content());
        themeRow3.margins(Insets.top(4));
        ButtonComponent tCyan = UIComponents.button(Component.literal("Cyan"), b -> applyTheme(0xD00E2B29));
        tCyan.margins(Insets.right(4));
        ButtonComponent tObsidian = UIComponents.button(Component.literal("Obsidian"), b -> applyTheme(0xD008080A));
        themeRow3.child(tCyan);
        themeRow3.child(tObsidian);
        modalCard.child(themeRow3);

        LabelComponent delayHeader = UIComponents.label(Component.literal("Command Delay Range (ms):"));
        delayHeader.color(Color.ofRgb(0x9CA3AF)).margins(Insets.of(10, 0, 4, 0));
        modalCard.child(delayHeader);

        FlowLayout delayInputsRow = UIContainers.horizontalFlow(Sizing.fill(100), Sizing.content());
        delayInputsRow.verticalAlignment(VerticalAlignment.CENTER);

        LabelComponent minLbl = UIComponents.label(Component.literal("Min:"));
        minLbl.color(Color.ofRgb(0xD1D5DB)).margins(Insets.right(4));

        TextBoxComponent minDelayInput = UIComponents.textBox(Sizing.fixed(60));
        minDelayInput.setTextColorUneditable(0xE0E0E0);
        minDelayInput.setMaxLength(6);
        minDelayInput.text(String.valueOf(ProfitConfig.getInstance().getCommandMinDelayMs()));
        minDelayInput.onChanged().subscribe(s -> {
            try {
                int val = Integer.parseInt(s.trim());
                ProfitConfig.getInstance().setCommandMinDelayMs(val);
            } catch (Exception ignored) {}
        });
        minDelayInput.margins(Insets.right(12));

        LabelComponent maxLbl = UIComponents.label(Component.literal("Max:"));
        maxLbl.color(Color.ofRgb(0xD1D5DB)).margins(Insets.right(4));

        TextBoxComponent maxDelayInput = UIComponents.textBox(Sizing.fixed(60));
        maxDelayInput.setTextColorUneditable(0xE0E0E0);
        maxDelayInput.setMaxLength(6);
        maxDelayInput.text(String.valueOf(ProfitConfig.getInstance().getCommandMaxDelayMs()));
        maxDelayInput.onChanged().subscribe(s -> {
            try {
                int val = Integer.parseInt(s.trim());
                ProfitConfig.getInstance().setCommandMaxDelayMs(val);
            } catch (Exception ignored) {}
        });

        delayInputsRow.child(minLbl);
        delayInputsRow.child(minDelayInput);
        delayInputsRow.child(maxLbl);
        delayInputsRow.child(maxDelayInput);
        modalCard.child(delayInputsRow);

        activeOverlayModal = UIContainers.overlay(modalCard);
        activeOverlayModal.closeOnClick(true);
        uiAdapter.rootComponent.child(activeOverlayModal);
    }

    private void closeSettingsModal() {
        if (activeOverlayModal != null && uiAdapter != null) {
            uiAdapter.rootComponent.removeChild(activeOverlayModal);
            activeOverlayModal = null;
        }
    }

    private void applyTheme(int colorHex) {
        ProfitConfig.getInstance().setThemeColorHex(colorHex);
        if (mainCard != null) {
            mainCard.surface(Surface.flat(colorHex).and(Surface.outline(0xFF262C36)));
        }
    }

    private void applyPreset(ProfitConfig.HudPosition preset) {
        Minecraft mc = Minecraft.getInstance();
        int width = mc.getWindow() != null ? mc.getWindow().getGuiScaledWidth() : 800;
        int height = mc.getWindow() != null ? mc.getWindow().getGuiScaledHeight() : 600;

        int hudX = 10;
        int hudY = 10;

        switch (preset) {
            case TOP_RIGHT:
                hudX = Math.max(10, width - 180);
                hudY = 10;
                break;
            case BOTTOM_LEFT:
                hudX = 10;
                hudY = Math.max(10, height - 75);
                break;
            case BOTTOM_RIGHT:
                hudX = Math.max(10, width - 180);
                hudY = Math.max(10, height - 75);
                break;
            case TOP_LEFT:
            default:
                hudX = 10;
                hudY = 10;
                break;
        }

        ProfitConfig.getInstance().setHudPosition(preset);
        ProfitConfig.getInstance().setHudX(hudX);
        ProfitConfig.getInstance().setHudY(hudY);

        ProfitHudOverlay.refreshHud();
    }

    private double parseDouble(String str, double def) {
        if (str == null || str.trim().isEmpty()) return def;
        String clean = str.trim().toLowerCase().replace("$", "");
        if (clean.isEmpty()) return def;

        boolean hasComma = clean.contains(",");
        boolean hasDot = clean.contains(".");

        if (hasComma && hasDot) {
            int firstComma = clean.indexOf(',');
            int firstDot = clean.indexOf('.');
            if (firstComma < firstDot) {
                clean = clean.replace(",", "");
            } else {
                clean = clean.replace(".", "").replace(",", ".");
            }
        } else if (hasComma) {
            int commaIdx = clean.indexOf(',');
            String afterComma = clean.substring(commaIdx + 1);
            String digitsAfter = afterComma.replaceAll("[^0-9]", "");

            if (digitsAfter.length() == 3 && !afterComma.endsWith("k") && !afterComma.endsWith("m") && !afterComma.endsWith("b")) {
                clean = clean.replace(",", "");
            } else {
                clean = clean.replace(",", ".");
            }
        }

        double multiplier = 1.0;
        if (clean.endsWith("k")) {
            multiplier = 1_000.0;
            clean = clean.substring(0, clean.length() - 1).trim();
        } else if (clean.endsWith("m")) {
            multiplier = 1_000_000.0;
            clean = clean.substring(0, clean.length() - 1).trim();
        } else if (clean.endsWith("b")) {
            multiplier = 1_000_000_000.0;
            clean = clean.substring(0, clean.length() - 1).trim();
        }

        try {
            return Double.parseDouble(clean) * multiplier;
        } catch (Exception e) {
            return def;
        }
    }

    private void refreshData() {
        ProfitTracker tracker = ProfitTracker.getInstance();

        gainedValueLabel.text(Component.literal("+" + tracker.getFormattedGained()));
        spentValueLabel.text(Component.literal("-" + tracker.getFormattedSpent()));

        double net = tracker.getNetProfit();
        netProfitValueLabel.text(Component.literal(tracker.getFormattedNetProfit()));
        netProfitValueLabel.color(Color.ofRgb(net >= 0 ? 0x10B981 : 0xEF4444));

        if (transactionListContainer == null) return;
        transactionListContainer.clearChildren();

        List<Transaction> list = tracker.getTransactions();
        if (list.isEmpty()) {
            LabelComponent empty = UIComponents.label(Component.literal("No transactions recorded yet in this session."));
            empty.color(Color.ofRgb(0x6B7280)).margins(Insets.of(12));
            transactionListContainer.child(empty);
            return;
        }

        int rowIdx = 0;
        for (Transaction tx : list) {
            FlowLayout row = UIContainers.horizontalFlow(Sizing.fill(100), Sizing.content());
            row.padding(Insets.of(4, 6, 4, 6));

            int bg = (rowIdx % 2 == 0) ? 0x40181C24 : 0x2011141A;
            row.surface(Surface.flat(bg));

            boolean isSell = tx.getType() == TransactionType.SELL;
            LabelComponent typeLbl = UIComponents.label(Component.literal(isSell ? "SELL" : "BUY"));
            typeLbl.color(Color.ofRgb(isSell ? 0x10B981 : 0xEF4444)).sizing(Sizing.fixed(45), Sizing.content());

            LabelComponent itemLbl = UIComponents.label(Component.literal(tx.getItemName()));
            itemLbl.color(Color.ofRgb(0xF3F4F6)).sizing(Sizing.fixed(120), Sizing.content());

            LabelComponent amtLbl = UIComponents.label(Component.literal(String.valueOf(tx.getAmount())));
            amtLbl.color(Color.ofRgb(0xD1D5DB)).sizing(Sizing.fixed(40), Sizing.content());

            LabelComponent unitLbl = UIComponents.label(Component.literal(CURRENCY_FORMAT.format(tx.getPricePerItem())));
            unitLbl.color(Color.ofRgb(0x9CA3AF)).sizing(Sizing.fixed(75), Sizing.content());

            LabelComponent totalLbl = UIComponents.label(Component.literal((isSell ? "+" : "-") + CURRENCY_FORMAT.format(tx.getTotalPrice())));
            totalLbl.color(Color.ofRgb(isSell ? 0x10B981 : 0xEF4444)).sizing(Sizing.fixed(75), Sizing.content());

            ButtonComponent undoRowBtn = UIComponents.button(Component.literal("Undo"), btn -> {
                ProfitTracker.getInstance().removeTransaction(tx);
                refreshData();
            });
            undoRowBtn.sizing(Sizing.fixed(50), Sizing.fixed(16));

            row.child(typeLbl);
            row.child(itemLbl);
            row.child(amtLbl);
            row.child(unitLbl);
            row.child(totalLbl);
            row.child(undoRowBtn);

            transactionListContainer.child(row);
            rowIdx++;
        }
    }

    public void openPriceDumperSetupModal() {
        if (uiAdapter == null) return;
        closeSettingsModal();

        int currentThemeHex = ProfitConfig.getInstance().getThemeColorHex();

        FlowLayout modalCard = UIContainers.verticalFlow(Sizing.fixed(340), Sizing.content());
        modalCard.surface(Surface.flat(currentThemeHex).and(Surface.outline(0xFF262C36)))
                .padding(Insets.of(12));

        FlowLayout modalHeader = UIContainers.horizontalFlow(Sizing.fill(100), Sizing.content());
        modalHeader.verticalAlignment(VerticalAlignment.CENTER);

        LabelComponent modalTitle = UIComponents.label(Component.literal("Price Dumper"));
        modalTitle.color(Color.ofRgb(0xF59E0B));

        FlowLayout closeSpacer = UIContainers.horizontalFlow(Sizing.fill(100), Sizing.content());
        closeSpacer.horizontalAlignment(HorizontalAlignment.RIGHT);
        ButtonComponent closeBtn = UIComponents.button(Component.literal("✕"), btn -> closeSettingsModal());
        closeBtn.sizing(Sizing.fixed(18), Sizing.fixed(16));
        closeSpacer.child(closeBtn);

        modalHeader.child(modalTitle);
        modalHeader.child(closeSpacer);
        modalCard.child(modalHeader);

        LabelComponent inputLabel = UIComponents.label(Component.literal("Items (one per line or IDs/tags like #logs, #ores):"));
        inputLabel.color(Color.ofRgb(0x9CA3AF)).margins(Insets.of(8, 0, 4, 0));
        modalCard.child(inputLabel);

        // Native owo-lib TextAreaComponent for multi-line item list input
        TextAreaComponent dumperInput = UIComponents.textArea(Sizing.fill(100), Sizing.fixed(80));
        dumperInput.text("bone\nbone_block\n#logs\n#ores");
        dumperInput.margins(Insets.bottom(8));
        modalCard.child(dumperInput);

        FlowLayout checkRow = UIContainers.horizontalFlow(Sizing.fill(100), Sizing.content());
        checkRow.margins(Insets.bottom(10));
        checkRow.verticalAlignment(VerticalAlignment.CENTER);

        CheckboxComponent orderCb = UIComponents.checkbox(Component.literal(" /Order"));
        orderCb.checked(true);
        orderCb.margins(Insets.right(16));

        CheckboxComponent ahCb = UIComponents.checkbox(Component.literal(" /Ah"));
        ahCb.checked(true);

        checkRow.child(orderCb);
        checkRow.child(ahCb);
        modalCard.child(checkRow);

        FlowLayout actionRow = UIContainers.horizontalFlow(Sizing.fill(100), Sizing.content());

        ButtonComponent startBtn = UIComponents.button(Component.literal("Start Price Dump"), b -> {
            String text = dumperInput.getValue();
            List<String> items = ItemTagResolver.resolveInputLines(text);
            if (items.isEmpty()) {
                if (Minecraft.getInstance().player != null) {
                    Minecraft.getInstance().player.displayClientMessage(Component.literal("§c[Price Dumper] Please enter at least one item name or tag!"), true);
                }
                return;
            }
            closeSettingsModal();
            Minecraft.getInstance().setScreen(null);
            PriceDumperHandler.start(items, orderCb.selected(), ahCb.selected());
        });
        startBtn.sizing(Sizing.fill(60), Sizing.fixed(20));
        startBtn.margins(Insets.right(6));

        ButtonComponent viewResultsBtn = UIComponents.button(Component.literal("Results"), b -> {
            openPriceDumperResultsModal();
        });
        viewResultsBtn.sizing(Sizing.fill(36), Sizing.fixed(20));

        actionRow.child(startBtn);
        actionRow.child(viewResultsBtn);
        modalCard.child(actionRow);

        activeOverlayModal = UIContainers.overlay(modalCard);
        activeOverlayModal.closeOnClick(true);
        uiAdapter.rootComponent.child(activeOverlayModal);
    }

    public void openPriceDumperResultsModal() {
        if (uiAdapter == null) return;
        closeSettingsModal();

        int currentThemeHex = ProfitConfig.getInstance().getThemeColorHex();
        List<DumpResult> results = PriceDumperHandler.getLatestResults();

        FlowLayout modalCard = UIContainers.verticalFlow(Sizing.fixed(360), Sizing.fixed(260));
        modalCard.surface(Surface.flat(currentThemeHex).and(Surface.outline(0xFF262C36)))
                .padding(Insets.of(12));

        FlowLayout modalHeader = UIContainers.horizontalFlow(Sizing.fill(100), Sizing.content());
        modalHeader.verticalAlignment(VerticalAlignment.CENTER);

        LabelComponent modalTitle = UIComponents.label(Component.literal("Price Dump Results (" + results.size() + ")"));
        modalTitle.color(Color.ofRgb(0xF59E0B));

        FlowLayout closeSpacer = UIContainers.horizontalFlow(Sizing.fill(100), Sizing.content());
        closeSpacer.horizontalAlignment(HorizontalAlignment.RIGHT);
        ButtonComponent closeBtn = UIComponents.button(Component.literal("✕"), btn -> closeSettingsModal());
        closeBtn.sizing(Sizing.fixed(18), Sizing.fixed(16));
        closeSpacer.child(closeBtn);

        modalHeader.child(modalTitle);
        modalHeader.child(closeSpacer);
        modalCard.child(modalHeader);

        FlowLayout tableHeader = UIContainers.horizontalFlow(Sizing.fill(100), Sizing.content());
        tableHeader.padding(Insets.of(4)).surface(Surface.flat(0x80232936)).margins(Insets.vertical(4));

        tableHeader.child(UIComponents.label(Component.literal("Source")).color(Color.ofRgb(0x9CA3AF)).sizing(Sizing.fixed(55), Sizing.content()));
        tableHeader.child(UIComponents.label(Component.literal("Item Name")).color(Color.ofRgb(0x9CA3AF)).sizing(Sizing.fixed(170), Sizing.content()));
        tableHeader.child(UIComponents.label(Component.literal("Price")).color(Color.ofRgb(0x9CA3AF)).sizing(Sizing.fixed(80), Sizing.content()));

        modalCard.child(tableHeader);

        FlowLayout listContainer = UIContainers.verticalFlow(Sizing.fill(100), Sizing.content());
        if (results.isEmpty()) {
            LabelComponent empty = UIComponents.label(Component.literal("No dumped results yet. Run a Price Dump first!"));
            empty.color(Color.ofRgb(0x6B7280)).margins(Insets.of(12));
            listContainer.child(empty);
        } else {
            int idx = 0;
            for (DumpResult res : results) {
                FlowLayout row = UIContainers.horizontalFlow(Sizing.fill(100), Sizing.content());
                row.padding(Insets.of(3, 4, 3, 4));
                int bg = (idx % 2 == 0) ? 0x40181C24 : 0x2011141A;
                row.surface(Surface.flat(bg));

                boolean isOrder = res.getSource().equalsIgnoreCase("ORDER");
                LabelComponent srcLbl = UIComponents.label(Component.literal("[" + res.getSource() + "]"));
                srcLbl.color(Color.ofRgb(isOrder ? 0x3B82F6 : 0xF59E0B)).sizing(Sizing.fixed(55), Sizing.content());

                LabelComponent itemLbl = UIComponents.label(Component.literal(res.getItemName()));
                itemLbl.color(Color.ofRgb(0xF3F4F6)).sizing(Sizing.fixed(170), Sizing.content());

                LabelComponent priceLbl = UIComponents.label(Component.literal(res.getFormattedPrice()));
                priceLbl.color(Color.ofRgb(res.getPrice() > 0 ? 0x10B981 : 0xEF4444)).sizing(Sizing.fixed(80), Sizing.content());

                row.child(srcLbl);
                row.child(itemLbl);
                row.child(priceLbl);

                listContainer.child(row);
                idx++;
            }
        }

        ScrollContainer<FlowLayout> scrollList = UIContainers.verticalScroll(Sizing.fill(100), Sizing.fixed(140), listContainer);
        scrollList.margins(Insets.bottom(6));
        modalCard.child(scrollList);

        FlowLayout actionRow1 = UIContainers.horizontalFlow(Sizing.fill(100), Sizing.content());
        actionRow1.margins(Insets.bottom(4));

        ButtonComponent txtBtn = UIComponents.button(Component.literal("Dump to TXT"), b -> {
            PriceExporter.exportToTxt(results);
        });
        txtBtn.sizing(Sizing.fill(48), Sizing.fixed(18));
        txtBtn.margins(Insets.right(4));

        ButtonComponent jsonBtn = UIComponents.button(Component.literal("Dump to JSON"), b -> {
            PriceExporter.exportToJson(results);
        });
        jsonBtn.sizing(Sizing.fill(48), Sizing.fixed(18));

        actionRow1.child(txtBtn);
        actionRow1.child(jsonBtn);
        modalCard.child(actionRow1);

        FlowLayout actionRow2 = UIContainers.horizontalFlow(Sizing.fill(100), Sizing.content());

        ButtonComponent copyTxtBtn = UIComponents.button(Component.literal("Copy TXT"), b -> {
            PriceExporter.copyTxtToClipboard(results);
        });
        copyTxtBtn.sizing(Sizing.fill(32), Sizing.fixed(18));
        copyTxtBtn.margins(Insets.right(4));

        ButtonComponent copyJsonBtn = UIComponents.button(Component.literal("Copy JSON"), b -> {
            PriceExporter.copyJsonToClipboard(results);
        });
        copyJsonBtn.sizing(Sizing.fill(32), Sizing.fixed(18));
        copyJsonBtn.margins(Insets.right(4));

        ButtonComponent closeBottomBtn = UIComponents.button(Component.literal("Close"), b -> {
            closeSettingsModal();
        });
        closeBottomBtn.sizing(Sizing.fill(30), Sizing.fixed(18));

        actionRow2.child(copyTxtBtn);
        actionRow2.child(copyJsonBtn);
        actionRow2.child(closeBottomBtn);
        modalCard.child(actionRow2);

        activeOverlayModal = UIContainers.overlay(modalCard);
        activeOverlayModal.closeOnClick(true);
        uiAdapter.rootComponent.child(activeOverlayModal);
    }
}
