package com.dsmp.profitcalc.client.handler;

import com.dsmp.profitcalc.client.config.ProfitConfig;
import com.dsmp.profitcalc.client.ui.ProfitDetailsScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.DecimalFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AutoFlipCalcHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger("donut-smp-profit-calc/AutoFlip");
    private static final DecimalFormat DEC_FMT = new DecimalFormat("#,##0.##");

    private static final long INITIAL_COMMAND_DELAY_MS = 350;
    private static final long PAGE_TURN_DELAY_MS = 300;
    private static final long TIMEOUT_LIMIT_MS = 15000;

    public enum FlipMode {
        BONE,
        KELP
    }

    private enum State {
        IDLE,
        SCANNING_BONE_MODE,
        SCANNING_KELP_BONE_PHASE,
        TRANSITIONING_TO_KELP,
        SCANNING_KELP_PHASE,
        TRANSITIONING_TO_CHARCOAL,
        SCANNING_CHARCOAL_PHASE
    }

    private static State currentState = State.IDLE;
    public static FlipMode activeMode = FlipMode.BONE;

    private static double capturedBlockPrice = 0.0;
    private static double capturedBonePrice = 0.0;
    private static double capturedRawKelpPrice = 0.0;
    private static double capturedDriedKelpPrice = 0.0;
    private static double capturedCharcoalPrice = 0.0;

    private static int pagesScanned = 0;
    private static long lastActionTime = 0;

    public static double autoBonePrice = 0.0;
    public static double autoBlockPrice = 0.0;
    public static double autoRawKelpPrice = 0.0;
    public static double autoDriedKelpPrice = 0.0;
    public static double autoCharcoalPrice = 0.0;

    public static void start() {
        start(FlipMode.BONE);
    }

    public static void start(FlipMode mode) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        activeMode = mode;
        capturedBlockPrice = 0.0;
        capturedBonePrice = 0.0;
        capturedRawKelpPrice = 0.0;
        capturedDriedKelpPrice = 0.0;
        capturedCharcoalPrice = 0.0;
        pagesScanned = 0;
        lastActionTime = System.currentTimeMillis();

        if (mc.screen != null) {
            mc.player.closeContainer();
        }

        if (mode == FlipMode.BONE) {
            currentState = State.SCANNING_BONE_MODE;
            if (mc.player.connection != null) {
                mc.player.connection.sendCommand("order bone");
            }
            mc.player.displayClientMessage(net.minecraft.network.chat.Component.literal("§a[Auto Flip] Running /order bone... Scanning prices!"), true);
        } else {
            currentState = State.SCANNING_KELP_BONE_PHASE;
            if (mc.player.connection != null) {
                mc.player.connection.sendCommand("order bone");
            }
            mc.player.displayClientMessage(net.minecraft.network.chat.Component.literal("§a[Auto Flip] Step 1/3: Running /order bone for Kelp Flip..."), true);
        }
    }

    public static void onTick(Minecraft mc) {
        if (currentState == State.IDLE || mc == null || mc.player == null) return;

        // Handle Transitioning to /order kelp
        if (currentState == State.TRANSITIONING_TO_KELP) {
            if (System.currentTimeMillis() - lastActionTime >= INITIAL_COMMAND_DELAY_MS) {
                currentState = State.SCANNING_KELP_PHASE;
                pagesScanned = 0;
                lastActionTime = System.currentTimeMillis();
                if (mc.player.connection != null) {
                    mc.player.connection.sendCommand("order kelp");
                }
                mc.player.displayClientMessage(net.minecraft.network.chat.Component.literal("§a[Auto Flip] Step 2/3: Running /order kelp for Raw & Dried Kelp..."), true);
            }
            return;
        }

        // Handle Transitioning to /order charcoal
        if (currentState == State.TRANSITIONING_TO_CHARCOAL) {
            if (System.currentTimeMillis() - lastActionTime >= INITIAL_COMMAND_DELAY_MS) {
                currentState = State.SCANNING_CHARCOAL_PHASE;
                pagesScanned = 0;
                lastActionTime = System.currentTimeMillis();
                if (mc.player.connection != null) {
                    mc.player.connection.sendCommand("order charcoal");
                }
                mc.player.displayClientMessage(net.minecraft.network.chat.Component.literal("§a[Auto Flip] Step 3/3: Running /order charcoal for Smelting cost..."), true);
            }
            return;
        }

        // Timeout check (15s max)
        if (System.currentTimeMillis() - lastActionTime > TIMEOUT_LIMIT_MS) {
            currentState = State.IDLE;
            mc.player.displayClientMessage(net.minecraft.network.chat.Component.literal("§c[Auto Flip] Timed out waiting for /order response."), true);
            return;
        }

        if (mc.screen instanceof AbstractContainerScreen<?> containerScreen) {
            String title = containerScreen.getTitle() != null ? containerScreen.getTitle().getString().toLowerCase() : "";

            long delayRequired = (pagesScanned == 0) ? INITIAL_COMMAND_DELAY_MS : PAGE_TURN_DELAY_MS;
            if (System.currentTimeMillis() - lastActionTime < delayRequired) return;

            if (currentState == State.SCANNING_BONE_MODE) {
                if (title.contains("orders") || title.contains("bone")) {
                    scanBoneContainer(mc, containerScreen, false);
                }
            } else if (currentState == State.SCANNING_KELP_BONE_PHASE) {
                if (title.contains("orders") || title.contains("bone")) {
                    scanBoneContainer(mc, containerScreen, true);
                }
            } else if (currentState == State.SCANNING_KELP_PHASE) {
                if (title.contains("orders") || title.contains("kelp")) {
                    scanKelpContainer(mc, containerScreen);
                }
            } else if (currentState == State.SCANNING_CHARCOAL_PHASE) {
                if (title.contains("orders") || title.contains("charcoal")) {
                    scanCharcoalContainer(mc, containerScreen);
                }
            }
        }
    }

    private static void scanBoneContainer(Minecraft mc, AbstractContainerScreen<?> containerScreen, boolean isKelpMode) {
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

                    if (isKelpMode) {
                        advanceToKelpPhase(mc);
                    } else {
                        finishAutoScan(mc);
                    }
                    return;
                }
            }
        }

        // 3. Next Page
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

        if (isKelpMode) {
            advanceToKelpPhase(mc);
        } else {
            finishAutoScan(mc);
        }
    }

    private static void advanceToKelpPhase(Minecraft mc) {
        if (mc.player != null) {
            mc.player.closeContainer();
        }
        currentState = State.TRANSITIONING_TO_KELP;
        lastActionTime = System.currentTimeMillis();
    }

    private static void scanKelpContainer(Minecraft mc, AbstractContainerScreen<?> containerScreen) {
        if (containerScreen.getMenu() == null) return;
        List<Slot> slots = containerScreen.getMenu().slots;
        if (slots.size() < 54) return;

        // Scan slots 0..44 for Raw Kelp vs Dried Kelp Block
        for (int i = 0; i < Math.min(45, slots.size()); i++) {
            ItemStack stack = slots.get(i).getItem();
            if (stack.isEmpty()) continue;
            String itemName = stack.getHoverName().getString().trim().toLowerCase();

            if (itemName.contains("dried kelp block") || (itemName.contains("dried") && itemName.contains("kelp"))) {
                double price = parsePriceFromStack(stack);
                if (price > capturedDriedKelpPrice) {
                    capturedDriedKelpPrice = price;
                }
            } else if (itemName.contains("kelp")) {
                double price = parsePriceFromStack(stack);
                if (price > capturedRawKelpPrice) {
                    capturedRawKelpPrice = price;
                }
            }
        }

        // If both or either captured, advance to Charcoal
        if (capturedDriedKelpPrice > 0 || capturedRawKelpPrice > 0 || pagesScanned >= 3) {
            advanceToCharcoalPhase(mc);
            return;
        }

        // Next Page
        if (pagesScanned < 5) {
            Slot nextSlot = slots.get(53);
            if (nextSlot != null && !nextSlot.getItem().isEmpty()) {
                pagesScanned++;
                lastActionTime = System.currentTimeMillis();
                mc.gameMode.handleInventoryMouseClick(containerScreen.getMenu().containerId, 53, 0, ClickType.PICKUP, mc.player);
                LOGGER.info("[Auto Flip] Safely clicked Next Page (Slot 53) in /order kelp. Page count: {}", pagesScanned);
                return;
            }
        }

        advanceToCharcoalPhase(mc);
    }

    private static void advanceToCharcoalPhase(Minecraft mc) {
        if (mc.player != null) {
            mc.player.closeContainer();
        }
        currentState = State.TRANSITIONING_TO_CHARCOAL;
        lastActionTime = System.currentTimeMillis();
    }

    private static void scanCharcoalContainer(Minecraft mc, AbstractContainerScreen<?> containerScreen) {
        if (containerScreen.getMenu() == null) return;
        List<Slot> slots = containerScreen.getMenu().slots;
        if (slots.size() < 54) return;

        for (int i = 0; i < Math.min(45, slots.size()); i++) {
            ItemStack stack = slots.get(i).getItem();
            if (stack.isEmpty()) continue;
            String itemName = stack.getHoverName().getString().trim().toLowerCase();

            if (itemName.contains("charcoal") || itemName.equalsIgnoreCase("charcoal")) {
                double price = parsePriceFromStack(stack);
                if (price > 0) {
                    capturedCharcoalPrice = price;
                    finishAutoScan(mc);
                    return;
                }
            }
        }

        // Next Page
        if (pagesScanned < 3) {
            Slot nextSlot = slots.get(53);
            if (nextSlot != null && !nextSlot.getItem().isEmpty()) {
                pagesScanned++;
                lastActionTime = System.currentTimeMillis();
                mc.gameMode.handleInventoryMouseClick(containerScreen.getMenu().containerId, 53, 0, ClickType.PICKUP, mc.player);
                LOGGER.info("[Auto Flip] Safely clicked Next Page (Slot 53) in /order charcoal. Page count: {}", pagesScanned);
                return;
            }
        }

        finishAutoScan(mc);
    }

    private static void finishAutoScan(Minecraft mc) {
        currentState = State.IDLE;

        if (capturedBonePrice > 0) {
            autoBonePrice = capturedBonePrice;
            ProfitConfig.getInstance().setSavedBonePrice(DEC_FMT.format(autoBonePrice));
        }
        if (capturedBlockPrice > 0) {
            autoBlockPrice = capturedBlockPrice;
            ProfitConfig.getInstance().setSavedBlockPrice(DEC_FMT.format(autoBlockPrice));
        }
        if (capturedRawKelpPrice > 0) {
            autoRawKelpPrice = capturedRawKelpPrice;
            ProfitConfig.getInstance().setSavedRawKelpPrice(DEC_FMT.format(autoRawKelpPrice));
        }
        if (capturedDriedKelpPrice > 0) {
            autoDriedKelpPrice = capturedDriedKelpPrice;
            ProfitConfig.getInstance().setSavedDriedKelpPrice(DEC_FMT.format(autoDriedKelpPrice));
        }
        if (capturedCharcoalPrice > 0) {
            autoCharcoalPrice = capturedCharcoalPrice;
            ProfitConfig.getInstance().setSavedCharcoalPrice(DEC_FMT.format(autoCharcoalPrice));
        }

        if (mc.player != null) {
            mc.player.closeContainer();
            if (activeMode == FlipMode.BONE) {
                mc.player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                        String.format("§a[Auto Flip] Success! Bone: $%.2f | Block: $%.2f", autoBonePrice, autoBlockPrice)), true);
            } else {
                mc.player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                        String.format("§a[Auto Flip] Success! Bone: $%.2f | Raw Kelp: $%.2f | Dried Kelp: $%.2f | Charcoal: $%.2f",
                                autoBonePrice, autoRawKelpPrice, autoDriedKelpPrice, autoCharcoalPrice)), true);
            }
        }

        mc.execute(() -> {
            ProfitDetailsScreen.selectedTab = (activeMode == FlipMode.KELP) ? 1 : 0;
            ProfitConfig.getInstance().setSavedSelectedTab(ProfitDetailsScreen.selectedTab);
            mc.setScreen(new ProfitDetailsScreen());
        });
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
