package com.dsmp.profitcalc.client.listener;

import com.dsmp.profitcalc.client.DonutSmpProfitCalcClient;
import com.dsmp.profitcalc.client.config.ProfitConfig;
import com.dsmp.profitcalc.client.tracker.OrderState;
import com.dsmp.profitcalc.client.tracker.OrderStatusTracker;
import com.dsmp.profitcalc.client.tracker.ProfitTracker;
import com.dsmp.profitcalc.client.tracker.Transaction;
import com.dsmp.profitcalc.client.tracker.TransactionType;
import io.wispforest.owo.mixin.ui.layers.AbstractContainerScreenAccessor;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.server.dialog.Dialog;
import net.minecraft.server.dialog.body.DialogBody;
import net.minecraft.server.dialog.body.ItemBody;
import net.minecraft.server.dialog.body.PlainMessage;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class OrderScreenHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger("donut-smp-profit-calc/ScreenHandler");

    private static final Pattern PRICE_EACH_PATTERN = Pattern.compile("\\$\\s*([0-9,]+(?:\\.[0-9]{1,3})?(?:e[+-]?[0-9]+)?\\s*[kmbKMB]?)\\s*(?:each|/each|per item)", Pattern.CASE_INSENSITIVE);
    private static final Pattern TOTAL_PRICE_PATTERN = Pattern.compile("Total:\\s*\\$\\s*([0-9,]+(?:\\.[0-9]{1,3})?(?:e[+-]?[0-9]+)?\\s*[kmbKMB]?)", Pattern.CASE_INSENSITIVE);
    private static final Pattern AMOUNT_PATTERN = Pattern.compile("Amount:\\s*([0-9,]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern ITEM_PATTERN = Pattern.compile("Item:\\s*(.+)", Pattern.CASE_INSENSITIVE);

    public static class PendingReviewOrder {
        public String itemName = "";
        public int amount = 0;
        public double pricePerItem = 0.0;
        public double totalPrice = 0.0;
        public long timestamp = System.currentTimeMillis();
    }

    public static class StagedDelivery {
        public String itemName = "";
        public int amount = 0;
        public double pricePerItem = 0.0;
        public long timestamp = System.currentTimeMillis();
    }

    private static PendingReviewOrder pendingReviewOrder = null;
    private static StagedDelivery stagedDelivery = null;

    private static String lastListingItem = "";
    private static double lastListingPrice = 0.0;
    private static long lastListingTime = 0;

    public static PendingReviewOrder getPendingReviewOrder() {
        if (pendingReviewOrder != null && System.currentTimeMillis() - pendingReviewOrder.timestamp > 300000) {
            pendingReviewOrder = null;
        }
        return pendingReviewOrder;
    }

    public static void clearPendingReviewOrder() {
        pendingReviewOrder = null;
    }

    public static StagedDelivery getStagedDelivery() {
        if (stagedDelivery != null && System.currentTimeMillis() - stagedDelivery.timestamp > 30000) {
            stagedDelivery = null;
        }
        return stagedDelivery;
    }

    public static void clearStagedDelivery() {
        stagedDelivery = null;
    }

    public static double getLastListingPrice() {
        return lastListingPrice;
    }

    public static String getLastListingItem() {
        return lastListingItem;
    }

    private int initialInventoryCount = -1;
    private String deliveringItemName = "";
    private Screen activeDeliveryScreen = null;
    private int maxDeliveryItemsSeen = 0;

    public void register() {
        ScreenEvents.AFTER_INIT.register(this::onScreenInit);
        ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);
    }

    private void onScreenInit(Minecraft client, Screen screen, int scaledWidth, int scaledHeight) {
        if (screen == null) return;
        String title = screen.getTitle() != null ? screen.getTitle().getString().toLowerCase() : "";
        if (ProfitConfig.getInstance().isVerboseLogging()) {
            LOGGER.info("[DONUT PROFIT/SCREEN] Screen Opened: Title = '{}' | Class = {}", screen.getTitle() != null ? screen.getTitle().getString() : "None", screen.getClass().getSimpleName());
        }

        // 1. When "Review Order" dialogue opens, capture Total & Details once
        if (title.contains("review order")) {
            ParsedOrderInfo info = parseReviewOrderFromScreen(screen);
            if (ProfitConfig.getInstance().isVerboseLogging()) {
                LOGGER.info("[DONUT PROFIT/REVIEW] Parsed Review Order Screen: Item = '{}' | Amount = {} | Price/ea = ${} | Total = ${}",
                        info.itemName, info.amount, info.pricePerItem, info.totalPrice);
            }

            if (info.totalPrice > 0 || info.pricePerItem > 0) {
                PendingReviewOrder pending = new PendingReviewOrder();
                pending.itemName = info.itemName;
                pending.amount = info.amount;
                pending.pricePerItem = info.pricePerItem;
                pending.totalPrice = info.totalPrice > 0 ? info.totalPrice : (info.amount * info.pricePerItem);
                pendingReviewOrder = pending;
                if (ProfitConfig.getInstance().isVerboseLogging()) {
                    LOGGER.info("[DONUT PROFIT/REVIEW] Stored Pending Review Order: {} x{} @ ${}/ea (Total: ${})", pending.itemName, pending.amount, pending.pricePerItem, pending.totalPrice);
                }
            }
            OrderStatusTracker.getInstance().updateStatus(OrderState.CREATING_BUY_ORDER, info.itemName, info.pricePerItem, info.amount, "Review Order");
        } else if (title.contains("deliver items") || title.contains("confirm delivery") || title.contains("deliver")) {
            double price = (System.currentTimeMillis() - lastListingTime < 300000) ? lastListingPrice : 0.0;
            String item = !lastListingItem.isEmpty() ? lastListingItem : "Item";
            OrderStatusTracker.getInstance().updateStatus(OrderState.DELIVERING_ORDER, item, price, 0, "Fulfilling Order");

            activeDeliveryScreen = screen;
            deliveringItemName = item;
            if (client.player != null) {
                initialInventoryCount = countItemInInventory(client.player, item);
                if (ProfitConfig.getInstance().isVerboseLogging()) {
                    LOGGER.info("[DONUT PROFIT/DELIVER] Delivery GUI opened for '{}'. Initial player inventory count: {}", item, initialInventoryCount);
                }
            }
        } else if (title.contains("choose item") || title.contains("how many") || title.contains("price per item") || title.contains("your orders")) {
            OrderStatusTracker.getInstance().updateStatus(OrderState.CREATING_BUY_ORDER, lastListingItem, 0, 0, title);
        } else if (title.contains("orders")) {
            OrderStatusTracker.getInstance().updateStatus(OrderState.BROWSING_ORDERS, lastListingItem, lastListingPrice, 0, "Browsing Orders");
            if (lastListingPrice <= 0 && screen instanceof AbstractContainerScreen<?> containerScreen) {
                scanSlot0Listing(containerScreen);
            }
        }

        ScreenMouseEvents.beforeMouseClick(screen).register((scr, clickEvent) -> {
            try {
                handleMouseClick(scr, clickEvent.x(), clickEvent.y(), clickEvent.button());
            } catch (Exception e) {
                LOGGER.error("Error handling screen click", e);
            }
        });

        ScreenKeyboardEvents.beforeKeyPress(screen).register((scr, keyEvent) -> {
            if (keyEvent.key() == GLFW.GLFW_KEY_F6) {
                if (ProfitConfig.getInstance().isVerboseLogging()) {
                    LOGGER.info("[DONUT PROFIT/DEBUG] F6 pressed inside active screen: {}", scr.getClass().getName());
                }
                DonutSmpProfitCalcClient.logDialogueScreenDetails(scr);
            }
        });
    }

    private void onClientTick(Minecraft client) {
        if (client == null) return;
        Screen screen = client.screen;

        // Continuously scan top 36 container slots (4x9 grid) while inside Delivery GUI
        if (screen instanceof AbstractContainerScreen<?> containerScreen) {
            String title = screen.getTitle() != null ? screen.getTitle().getString().toLowerCase() : "";
            if (title.contains("deliver")) {
                activeDeliveryScreen = screen;
                int currentSeen = countItemsInDeliverySlots(containerScreen, deliveringItemName);
                if (currentSeen > maxDeliveryItemsSeen) {
                    maxDeliveryItemsSeen = currentSeen;
                    if (ProfitConfig.getInstance().isVerboseLogging()) {
                        LOGGER.info("[DONUT PROFIT/DELIVER] Scanned Delivery GUI 4x9 slots: updated max items seen -> {}", maxDeliveryItemsSeen);
                    }
                }
            }
        }

        if (screen == null) {
            if (client.player != null && client.player.containerMenu != client.player.inventoryMenu) {
                client.player.closeContainer();
            }
            if (OrderStatusTracker.getInstance().getCurrentState() != OrderState.IDLE) {
                if (OrderStatusTracker.getInstance().getCurrentState() == OrderState.DELIVERING_ORDER) {
                    checkFinalDelivery(client);
                }
                OrderStatusTracker.getInstance().updateStatus(OrderState.IDLE, "", 0, 0, "Idle");
            }
            initialInventoryCount = -1;
            deliveringItemName = "";
            activeDeliveryScreen = null;
            maxDeliveryItemsSeen = 0;
        }
    }

    private void scanSlot0Listing(AbstractContainerScreen<?> screen) {
        if (screen.getMenu() == null || screen.getMenu().slots.isEmpty()) return;
        Slot slot0 = screen.getMenu().slots.get(0);
        if (slot0 != null && !slot0.getItem().isEmpty()) {
            parseListingTooltip(slot0.getItem());
        }
    }

    private void handleMouseClick(Screen screen, double mouseX, double mouseY, int button) {
        if (screen == null) return;
        String title = screen.getTitle() != null ? screen.getTitle().getString().toLowerCase() : "";

        if (screen instanceof AbstractContainerScreen<?> containerScreen) {
            if (title.contains("orders") && !title.contains("deliver")) {
                Slot clickedSlot = getSlotAt(containerScreen, mouseX, mouseY);
                if (clickedSlot != null && !clickedSlot.getItem().isEmpty()) {
                    parseListingTooltip(clickedSlot.getItem());
                    if (ProfitConfig.getInstance().isVerboseLogging()) {
                        LOGGER.info("[DONUT PROFIT/CLICK] User selected order listing slot #{}: '{}' @ ${}/ea", clickedSlot.index, lastListingItem, lastListingPrice);
                    }
                }
            }
        }
    }

    public Slot getSlotAt(AbstractContainerScreen<?> screen, double mouseX, double mouseY) {
        if (screen == null || screen.getMenu() == null) return null;
        int guiLeft = 0;
        int guiTop = 0;
        if (screen instanceof AbstractContainerScreenAccessor accessor) {
            guiLeft = accessor.owo$getRootX();
            guiTop = accessor.owo$getRootY();
        }

        for (Slot slot : screen.getMenu().slots) {
            int x = guiLeft + slot.x;
            int y = guiTop + slot.y;
            if (mouseX >= x && mouseX < x + 16 && mouseY >= y && mouseY < y + 16) {
                return slot;
            }
        }
        return null;
    }

    private ParsedOrderInfo parseReviewOrderFromScreen(Screen screen) {
        ParsedOrderInfo info = new ParsedOrderInfo();
        List<String> allText = extractAllTextFromScreen(screen);

        for (String line : allText) {
            Matcher mItem = ITEM_PATTERN.matcher(line);
            if (mItem.find()) info.itemName = mItem.group(1).trim();

            Matcher mAmt = AMOUNT_PATTERN.matcher(line);
            if (mAmt.find()) {
                try { info.amount = Integer.parseInt(mAmt.group(1).replace(",", "")); } catch (Exception ignored) {}
            }

            Matcher mPrice = PRICE_EACH_PATTERN.matcher(line);
            if (mPrice.find()) {
                info.pricePerItem = parseFormattedMoney(mPrice.group(1));
            }

            Matcher mTotal = TOTAL_PRICE_PATTERN.matcher(line);
            if (mTotal.find()) {
                info.totalPrice = parseFormattedMoney(mTotal.group(1));
            }
        }

        if (info.totalPrice <= 0 && info.pricePerItem > 0) {
            info.totalPrice = info.pricePerItem * info.amount;
        }

        if (!info.itemName.isEmpty() && !info.itemName.equalsIgnoreCase("Unknown Item")) {
            lastListingItem = info.itemName;
        }

        return info;
    }

    private static Dialog findDialogInScreen(Screen screen) {
        if (screen == null) return null;
        Class<?> clazz = screen.getClass();
        while (clazz != null && clazz != Object.class && clazz != Screen.class) {
            for (Field f : clazz.getDeclaredFields()) {
                if (Dialog.class.isAssignableFrom(f.getType())) {
                    try {
                        f.setAccessible(true);
                        Object val = f.get(screen);
                        if (val instanceof Dialog d) {
                            return d;
                        }
                    } catch (Exception ignored) {}
                }
            }
            clazz = clazz.getSuperclass();
        }
        return null;
    }

    public static List<String> extractAllTextFromScreen(Screen screen) {
        List<String> textLines = new ArrayList<>();
        if (screen == null) return textLines;

        if (screen.getTitle() != null) {
            textLines.add(screen.getTitle().getString());
        }

        Dialog dialog = findDialogInScreen(screen);
        if (dialog != null && dialog.common() != null) {
            List<DialogBody> bodyList = dialog.common().body();
            if (bodyList != null) {
                for (DialogBody body : bodyList) {
                    if (body instanceof PlainMessage plain) {
                        if (plain.contents() != null) {
                            for (String sub : plain.contents().getString().split("\n")) {
                                if (!sub.trim().isEmpty()) {
                                    textLines.add(sub.trim());
                                    if (ProfitConfig.getInstance().isVerboseLogging()) {
                                        LOGGER.info("[DONUT PROFIT/TEXT] Dialog PlainMessage line: '{}'", sub.trim());
                                    }
                                }
                            }
                        }
                    } else if (body instanceof ItemBody itemBody) {
                        if (itemBody.item() != null && !itemBody.item().isEmpty()) {
                            textLines.add(itemBody.item().getHoverName().getString());
                            if (ProfitConfig.getInstance().isVerboseLogging()) {
                                LOGGER.info("[DONUT PROFIT/TEXT] Dialog ItemBody item: '{}'", itemBody.item().getHoverName().getString());
                            }
                        }
                        if (itemBody.description() != null && itemBody.description().isPresent()) {
                            PlainMessage descMsg = itemBody.description().get();
                            if (descMsg.contents() != null) {
                                for (String sub : descMsg.contents().getString().split("\n")) {
                                    if (!sub.trim().isEmpty()) {
                                        textLines.add(sub.trim());
                                        if (ProfitConfig.getInstance().isVerboseLogging()) {
                                            LOGGER.info("[DONUT PROFIT/TEXT] Dialog ItemBody description line: '{}'", sub.trim());
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        for (var child : screen.children()) {
            if (child instanceof AbstractWidget widget) {
                if (widget.getMessage() != null) {
                    textLines.add(widget.getMessage().getString());
                }
                if (widget instanceof EditBox editBox) {
                    textLines.add(editBox.getValue());
                }
            }
        }

        if (screen instanceof AbstractContainerScreen<?> containerScreen) {
            for (Slot slot : containerScreen.getMenu().slots) {
                ItemStack stack = slot.getItem();
                if (!stack.isEmpty()) {
                    textLines.add(stack.getHoverName().getString());
                    textLines.addAll(getLoreLines(stack));
                }
            }
        }

        return textLines;
    }

    private void checkFinalDelivery(Minecraft client) {
        if (lastListingPrice <= 0) return;

        int delivered = maxDeliveryItemsSeen;

        // Method A: Direct inspection of top 36 container slots (slots 0..35) in delivery GUI
        if (delivered <= 0 && activeDeliveryScreen instanceof AbstractContainerScreen<?> containerScreen) {
            delivered = countItemsInDeliverySlots(containerScreen, deliveringItemName);
        }

        // Method B: Fallback to player inventory difference (initial - current)
        if (delivered <= 0 && client.player != null && initialInventoryCount >= 0) {
            int currentCount = countItemInInventory(client.player, deliveringItemName);
            delivered = initialInventoryCount - currentCount;
        }

        if (delivered > 0) {
            StagedDelivery staged = new StagedDelivery();
            staged.itemName = deliveringItemName;
            staged.amount = delivered;
            staged.pricePerItem = lastListingPrice;
            staged.timestamp = System.currentTimeMillis();
            stagedDelivery = staged;

            if (ProfitConfig.getInstance().isVerboseLogging()) {
                LOGGER.info("[DONUT PROFIT/DELIVER] Staged 4x9 GUI Delivery: {} x{} @ ${}/ea. Awaiting server chat confirmation!",
                        deliveringItemName, delivered, lastListingPrice);
            }
        } else if (ProfitConfig.getInstance().isVerboseLogging()) {
            LOGGER.info("[DONUT PROFIT/DELIVER] Delivery screen closed with 0 items delivered.");
        }
    }

    /**
     * Inspects slots 0-35 (top 4x9 container grid) of the delivery GUI including Shulker Boxes
     */
    private int countItemsInDeliverySlots(AbstractContainerScreen<?> containerScreen, String targetName) {
        if (containerScreen == null || containerScreen.getMenu() == null) return 0;
        int maxSlots = Math.min(36, containerScreen.getMenu().slots.size());
        int count = 0;

        for (int i = 0; i < maxSlots; i++) {
            Slot slot = containerScreen.getMenu().slots.get(i);
            if (slot == null || slot.getItem().isEmpty()) continue;

            ItemStack stack = slot.getItem();
            String name = stack.getHoverName().getString();

            if (isItemMatch(name, targetName)) {
                count += stack.getCount();
            }

            // Inspect Shulker Box contents in slot
            count += countShulkerBoxContents(stack, targetName);
        }
        return count;
    }

    private int countShulkerBoxContents(ItemStack stack, String targetName) {
        if (stack.isEmpty()) return 0;
        int count = 0;

        // Check DataComponents.CONTAINER
        try {
            var containerComponent = stack.get(net.minecraft.core.component.DataComponents.CONTAINER);
            if (containerComponent != null) {
                for (ItemStack inner : containerComponent.nonEmptyItems()) {
                    if (inner.isEmpty()) continue;
                    String innerName = inner.getHoverName().getString();
                    if (isItemMatch(innerName, targetName)) {
                        count += inner.getCount();
                    }
                }
            }
        } catch (Exception ignored) {}

        return count;
    }

    private static boolean isItemMatch(String itemName, String targetName) {
        if (targetName == null || targetName.trim().isEmpty()) return true;
        if (itemName == null || itemName.trim().isEmpty()) return false;

        String cleanItem = itemName.toLowerCase().trim();
        String cleanTarget = targetName.toLowerCase().trim();

        if (cleanItem.endsWith("s")) cleanItem = cleanItem.substring(0, cleanItem.length() - 1);
        if (cleanTarget.endsWith("s")) cleanTarget = cleanTarget.substring(0, cleanTarget.length() - 1);

        if (cleanItem.contains(cleanTarget) || cleanTarget.contains(cleanItem)) return true;
        if (cleanItem.contains("bone") && cleanTarget.contains("bone")) return true;

        return false;
    }

    private int countItemInInventory(net.minecraft.client.player.LocalPlayer player, String targetName) {
        if (player == null) return 0;
        int count = 0;

        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.isEmpty()) continue;

            String name = stack.getHoverName().getString();
            if (isItemMatch(name, targetName)) {
                count += stack.getCount();
            }

            count += countShulkerBoxContents(stack, targetName);
        }
        return count;
    }

    private void parseListingTooltip(ItemStack stack) {
        List<String> loreLines = getLoreLines(stack);
        String displayName = stack.getHoverName() != null ? stack.getHoverName().getString() : "";

        for (String line : loreLines) {
            Matcher m = PRICE_EACH_PATTERN.matcher(line);
            if (m.find()) {
                double price = parseFormattedMoney(m.group(1));
                if (price > 0) {
                    lastListingItem = displayName;
                    lastListingPrice = price;
                    lastListingTime = System.currentTimeMillis();
                    if (ProfitConfig.getInstance().isVerboseLogging()) {
                        LOGGER.info("[DONUT PROFIT/LISTING] Parsed listing price: '{}' @ ${}/ea", displayName, price);
                    }
                }
            }
        }
    }

    private static double parseFormattedMoney(String str) {
        if (str == null) return 0.0;
        String clean = str.replace("$", "").replace(",", "").trim().toLowerCase();
        if (clean.isEmpty()) return 0.0;

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
            return 0.0;
        }
    }

    private static class ParsedOrderInfo {
        String itemName = "Unknown Item";
        int amount = 1;
        double pricePerItem = 0.0;
        double totalPrice = 0.0;
    }

    private static List<String> getLoreLines(ItemStack stack) {
        List<String> lines = new ArrayList<>();
        if (stack.isEmpty()) return lines;
        try {
            var loreComponent = stack.get(net.minecraft.core.component.DataComponents.LORE);
            if (loreComponent != null) {
                for (Component component : loreComponent.lines()) {
                    lines.add(component.getString());
                }
            }
        } catch (Exception ignored) {}
        return lines;
    }
}
