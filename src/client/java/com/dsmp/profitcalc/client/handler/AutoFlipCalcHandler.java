package com.dsmp.profitcalc.client.handler;

import com.dsmp.profitcalc.client.ui.ProfitDetailsScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AutoFlipCalcHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger("donut-smp-profit-calc/AutoFlip");

    private static final long INITIAL_COMMAND_DELAY_MS = 350;
    private static final long PAGE_TURN_DELAY_MS = 300;
    private static final long TIMEOUT_LIMIT_MS = 10000;

    private enum State {
        IDLE,
        WAITING_FOR_CONTAINER,
        SCANNING_PAGE,
        FINISHING
    }

    private static State currentState = State.IDLE;
    private static double capturedBlockPrice = 0.0;
    private static double capturedBonePrice = 0.0;
    private static int pagesScanned = 0;
    private static long lastActionTime = 0;

    public static double autoBonePrice = 0.0;
    public static double autoBlockPrice = 0.0;

    public static void start() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        currentState = State.WAITING_FOR_CONTAINER;
        capturedBlockPrice = 0.0;
        capturedBonePrice = 0.0;
        pagesScanned = 0;
        lastActionTime = System.currentTimeMillis();

        if (mc.player.connection != null) {
            mc.player.connection.sendCommand("order bone");
        }
        mc.player.displayClientMessage(net.minecraft.network.chat.Component.literal("§a[Auto Flip] Running /order bone... Scanning prices!"), true);
    }

    public static void onTick(Minecraft mc) {
        if (currentState == State.IDLE || mc == null || mc.player == null) return;

        // Timeout check (10s max)
        if (System.currentTimeMillis() - lastActionTime > TIMEOUT_LIMIT_MS) {
            currentState = State.IDLE;
            mc.player.displayClientMessage(net.minecraft.network.chat.Component.literal("§c[Auto Flip] Timed out waiting for /order response."), true);
            return;
        }

        if (mc.screen instanceof AbstractContainerScreen<?> containerScreen) {
            String title = containerScreen.getTitle() != null ? containerScreen.getTitle().getString().toLowerCase() : "";
            if (!title.contains("orders") && !title.contains("bone")) return;

            long delayRequired = (pagesScanned == 0) ? INITIAL_COMMAND_DELAY_MS : PAGE_TURN_DELAY_MS;
            if (System.currentTimeMillis() - lastActionTime < delayRequired) return;

            scanContainer(mc, containerScreen);
        }
    }

    private static void scanContainer(Minecraft mc, AbstractContainerScreen<?> containerScreen) {
        if (containerScreen.getMenu() == null) return;
        List<Slot> slots = containerScreen.getMenu().slots;
        if (slots.size() < 54) return;

        // 1. Grab Bone Block price from slot #0 on page 1 if not captured yet
        if (capturedBlockPrice <= 0 && !slots.get(0).getItem().isEmpty()) {
            ItemStack stack0 = slots.get(0).getItem();
            capturedBlockPrice = parsePriceFromStack(stack0);
            if (capturedBlockPrice > 0) {
                LOGGER.info("[Auto Flip] Captured Bone Block Price from slot #0: ${}/ea", capturedBlockPrice);
            }
        }

        // 2. Scan current page slots 0..44 for single Bone order
        for (int i = 0; i < Math.min(45, slots.size()); i++) {
            ItemStack stack = slots.get(i).getItem();
            if (stack.isEmpty()) continue;
            String itemName = stack.getHoverName().getString().trim();

            if (itemName.equalsIgnoreCase("Bone") || (itemName.toLowerCase().contains("bone") && !itemName.toLowerCase().contains("block"))) {
                double price = parsePriceFromStack(stack);
                if (price > 0) {
                    capturedBonePrice = price;
                    LOGGER.info("[Auto Flip] Captured Single Bone Price from slot #{}: ${}/ea", i, capturedBonePrice);
                    finishAutoScan(mc);
                    return;
                }
            }
        }

        // 3. If Bone price not found on this page, click Slot 53 ("Next Page" arrow)
        if (pagesScanned < 5) {
            Slot nextSlot = slots.get(53);
            if (nextSlot != null && !nextSlot.getItem().isEmpty()) {
                pagesScanned++;
                lastActionTime = System.currentTimeMillis();
                mc.gameMode.handleInventoryMouseClick(containerScreen.getMenu().containerId, 53, 0, ClickType.PICKUP, mc.player);
                LOGGER.info("[Auto Flip] Safely clicked Next Page (Slot 53). Page count: {}", pagesScanned);
                return;
            }
        }

        finishAutoScan(mc);
    }

    private static void finishAutoScan(Minecraft mc) {
        currentState = State.IDLE;

        if (capturedBonePrice > 0) autoBonePrice = capturedBonePrice;
        if (capturedBlockPrice > 0) autoBlockPrice = capturedBlockPrice;

        if (mc.player != null) {
            mc.player.closeContainer();
            mc.player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                    String.format("§a[Auto Flip] Success! Bone: $%.2f | Block: $%.2f", autoBonePrice, autoBlockPrice)), true);
        }

        mc.execute(() -> mc.setScreen(new ProfitDetailsScreen()));
    }

    private static double parsePriceFromStack(ItemStack stack) {
        if (stack.isEmpty()) return 0.0;
        Pattern pricePattern = Pattern.compile("\\$\\s*([0-9.,]+(?:e[+-]?[0-9]+)?\\s*[kmbKMB]?)\\s*(?:each|/each|per item)", Pattern.CASE_INSENSITIVE);

        try {
            var loreComponent = stack.get(net.minecraft.core.component.DataComponents.LORE);
            if (loreComponent != null) {
                for (net.minecraft.network.chat.Component comp : loreComponent.lines()) {
                    Matcher m = pricePattern.matcher(comp.getString());
                    if (m.find()) {
                        return parseFormattedMoney(m.group(1));
                    }
                }
            }
        } catch (Exception ignored) {}
        return 0.0;
    }

    private static double parseFormattedMoney(String str) {
        if (str == null) return 0.0;
        String clean = str.replace("$", "").trim().toLowerCase();
        if (clean.isEmpty()) return 0.0;

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
            return 0.0;
        }
    }
}
