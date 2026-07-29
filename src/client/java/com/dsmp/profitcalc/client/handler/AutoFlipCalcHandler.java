package com.dsmp.profitcalc.client.handler;

import com.dsmp.profitcalc.client.config.ProfitConfig;
import com.dsmp.profitcalc.client.ui.ProfitDetailsScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.DecimalFormat;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
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
        KELP,
        OAK_LOG,
        STICKY_PISTON,
        GOLDEN_APPLE,
        BOOKSHELF
    }

    private static class ScanTask {
        final String command;
        final String targetItemKey;
        ScanTask(String command, String targetItemKey) {
            this.command = command;
            this.targetItemKey = targetItemKey;
        }
    }

    private static boolean running = false;
    public static FlipMode activeMode = FlipMode.BONE;
    private static final Queue<ScanTask> taskQueue = new LinkedList<>();

    private static ScanTask currentTask = null;
    private static int pagesScanned = 0;
    private static long lastActionTime = 0;

    // Captured prices
    private static double capturedBonePrice = 0.0;
    private static double capturedBlockPrice = 0.0;

    private static double capturedRawKelpPrice = 0.0;
    private static double capturedDriedKelpPrice = 0.0;
    private static double capturedCharcoalPrice = 0.0;

    private static double capturedOakLogPrice = 0.0;
    private static double capturedOakPlanksPrice = 0.0;

    private static double capturedPistonPrice = 0.0;
    private static double capturedSlimeballPrice = 0.0;
    private static double capturedStickyPistonPrice = 0.0;

    private static double capturedGoldIngotPrice = 0.0;
    private static double capturedApplePrice = 0.0;
    private static double capturedGapplePrice = 0.0;

    private static double capturedBookPrice = 0.0;
    private static double capturedBookshelfPrice = 0.0;

    public static void stop() {
        running = false;
        taskQueue.clear();
        currentTask = null;
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.player != null) {
            mc.player.displayClientMessage(net.minecraft.network.chat.Component.literal("§e[Auto Flip] Cancelled."), true);
        }
    }

    public static boolean isRunning() {
        return running;
    }

    public static void start() {
        start(FlipMode.BONE);
    }

    public static void start(FlipMode mode) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        activeMode = mode;
        taskQueue.clear();
        currentTask = null;
        pagesScanned = 0;
        lastActionTime = System.currentTimeMillis();

        // Reset captured prices
        capturedBonePrice = 0.0; capturedBlockPrice = 0.0;
        capturedRawKelpPrice = 0.0; capturedDriedKelpPrice = 0.0; capturedCharcoalPrice = 0.0;
        capturedOakLogPrice = 0.0; capturedOakPlanksPrice = 0.0;
        capturedPistonPrice = 0.0; capturedSlimeballPrice = 0.0; capturedStickyPistonPrice = 0.0;
        capturedGoldIngotPrice = 0.0; capturedApplePrice = 0.0; capturedGapplePrice = 0.0;
        capturedBookPrice = 0.0; capturedBookshelfPrice = 0.0;

        switch (mode) {
            case BONE:
                taskQueue.add(new ScanTask("order bone", "bone"));
                break;
            case KELP:
                taskQueue.add(new ScanTask("order bone", "bone"));
                taskQueue.add(new ScanTask("order kelp", "kelp"));
                taskQueue.add(new ScanTask("order charcoal", "charcoal"));
                break;
            case OAK_LOG:
                taskQueue.add(new ScanTask("order oak log", "oak_log"));
                taskQueue.add(new ScanTask("order oak planks", "oak_planks"));
                break;
            case STICKY_PISTON:
                taskQueue.add(new ScanTask("order piston", "piston"));
                taskQueue.add(new ScanTask("order slimeball", "slimeball"));
                taskQueue.add(new ScanTask("order sticky piston", "sticky_piston"));
                break;
            case GOLDEN_APPLE:
                taskQueue.add(new ScanTask("order gold ingot", "gold_ingot"));
                taskQueue.add(new ScanTask("order apple", "apple"));
                taskQueue.add(new ScanTask("order golden apple", "golden_apple"));
                break;
            case BOOKSHELF:
                taskQueue.add(new ScanTask("order oak planks", "oak_planks"));
                taskQueue.add(new ScanTask("order book", "book"));
                taskQueue.add(new ScanTask("order bookshelf", "bookshelf"));
                break;
        }

        running = true;

        if (mc.screen != null) {
            mc.player.closeContainer();
        }

        mc.player.displayClientMessage(net.minecraft.network.chat.Component.literal("§a[Auto Flip] Auto checking prices for " + mode.name() + "..."), true);
    }

    public static void onTick(Minecraft mc) {
        if (!running || mc == null || mc.player == null) return;

        long now = System.currentTimeMillis();
        int delayMs = ProfitConfig.getInstance().getRandomizedCommandDelayMs();

        if (currentTask == null) {
            if (taskQueue.isEmpty()) {
                finishAutoScan(mc);
                return;
            }

            if (now - lastActionTime < delayMs) return;

            currentTask = taskQueue.poll();
            pagesScanned = 0;
            lastActionTime = now;

            if (mc.screen != null) {
                mc.player.closeContainer();
            }

            LOGGER.info("[Auto Flip] Running /{} for item key: {}", currentTask.command, currentTask.targetItemKey);
            if (mc.player.connection != null) {
                mc.player.connection.sendCommand(currentTask.command);
            }
            return;
        }

        // Timeout check (15s max per command step)
        if (now - lastActionTime > TIMEOUT_LIMIT_MS) {
            LOGGER.warn("[Auto Flip] Step timed out for /{}", currentTask.command);
            currentTask = null;
            lastActionTime = now + 200;
            return;
        }

        if (mc.screen instanceof AbstractContainerScreen<?> containerScreen) {
            long delayRequired = (pagesScanned == 0) ? INITIAL_COMMAND_DELAY_MS : PAGE_TURN_DELAY_MS;
            if (now - lastActionTime < delayRequired) return;

            boolean scannedAny = scanCurrentContainer(mc, containerScreen, currentTask.targetItemKey);

            if (scannedAny || pagesScanned >= 3) {
                currentTask = null;
                lastActionTime = System.currentTimeMillis();
                return;
            }

            // Next Page if available
            List<Slot> slots = containerScreen.getMenu().slots;
            if (pagesScanned < 3 && slots.size() > 53 && !slots.get(53).getItem().isEmpty()) {
                pagesScanned++;
                lastActionTime = now;
                mc.gameMode.handleInventoryMouseClick(containerScreen.getMenu().containerId, 53, 0, ClickType.PICKUP, mc.player);
                LOGGER.info("[Auto Flip] Safely clicked Next Page (Slot 53) in /{}. Page count: {}", currentTask.command, pagesScanned);
            } else {
                currentTask = null;
                lastActionTime = System.currentTimeMillis();
            }
        }
    }

    private static boolean scanCurrentContainer(Minecraft mc, AbstractContainerScreen<?> containerScreen, String targetKey) {
        if (containerScreen.getMenu() == null) return false;
        List<Slot> slots = containerScreen.getMenu().slots;
        if (slots.size() < 45) return false;

        boolean foundTarget = false;

        for (int i = 0; i < Math.min(45, slots.size()); i++) {
            ItemStack stack = slots.get(i).getItem();
            if (stack.isEmpty()) continue;

            Identifier loc = BuiltInRegistries.ITEM.getKey(stack.getItem());
            String path = (loc != null) ? loc.getPath().toLowerCase() : "";
            String name = stack.getHoverName().getString().trim().toLowerCase();

            double price = parsePriceFromStack(stack);
            if (price <= 0) continue;

            if (targetKey.equals("bone")) {
                if (path.equals("bone") || name.equalsIgnoreCase("bone")) {
                    capturedBonePrice = price;
                    foundTarget = true;
                } else if (path.equals("bone_block") || name.contains("bone block")) {
                    capturedBlockPrice = price;
                }
            } else if (targetKey.equals("kelp")) {
                if (path.equals("dried_kelp_block") || name.contains("dried kelp block")) {
                    if (price > capturedDriedKelpPrice) capturedDriedKelpPrice = price;
                    foundTarget = true;
                } else if (path.equals("kelp") || name.contains("kelp")) {
                    if (price > capturedRawKelpPrice) capturedRawKelpPrice = price;
                    foundTarget = true;
                }
            } else if (targetKey.equals("charcoal")) {
                if (path.equals("charcoal") || name.contains("charcoal")) {
                    capturedCharcoalPrice = price;
                    foundTarget = true;
                }
            } else if (targetKey.equals("oak_log")) {
                if (path.equals("oak_log") || name.contains("oak log")) {
                    capturedOakLogPrice = price;
                    foundTarget = true;
                } else if (path.equals("oak_planks") || name.contains("oak planks")) {
                    capturedOakPlanksPrice = price;
                }
            } else if (targetKey.equals("oak_planks")) {
                if (path.equals("oak_planks") || name.contains("oak planks")) {
                    capturedOakPlanksPrice = price;
                    foundTarget = true;
                }
            } else if (targetKey.equals("piston")) {
                if (path.equals("piston") || name.contains("piston")) {
                    capturedPistonPrice = price;
                    foundTarget = true;
                }
            } else if (targetKey.equals("slimeball")) {
                if (path.equals("slime_ball") || path.equals("slimeball") || name.contains("slimeball") || name.contains("slime ball")) {
                    capturedSlimeballPrice = price;
                    foundTarget = true;
                }
            } else if (targetKey.equals("sticky_piston")) {
                if (path.equals("sticky_piston") || name.contains("sticky piston")) {
                    capturedStickyPistonPrice = price;
                    foundTarget = true;
                }
            } else if (targetKey.equals("gold_ingot")) {
                if (path.equals("gold_ingot") || name.contains("gold ingot")) {
                    capturedGoldIngotPrice = price;
                    foundTarget = true;
                }
            } else if (targetKey.equals("apple")) {
                if (path.equals("apple") || name.equals("apple")) {
                    capturedApplePrice = price;
                    foundTarget = true;
                }
            } else if (targetKey.equals("golden_apple")) {
                if (path.equals("golden_apple") || name.contains("golden apple")) {
                    capturedGapplePrice = price;
                    foundTarget = true;
                }
            } else if (targetKey.equals("book")) {
                if (path.equals("book") || name.equals("book")) {
                    capturedBookPrice = price;
                    foundTarget = true;
                }
            } else if (targetKey.equals("bookshelf")) {
                if (path.equals("bookshelf") || name.contains("bookshelf")) {
                    capturedBookshelfPrice = price;
                    foundTarget = true;
                }
            }
        }

        return foundTarget;
    }

    private static void finishAutoScan(Minecraft mc) {
        running = false;
        currentTask = null;
        ProfitConfig config = ProfitConfig.getInstance();

        if (capturedBonePrice > 0) config.setSavedBonePrice(DEC_FMT.format(capturedBonePrice));
        if (capturedBlockPrice > 0) config.setSavedBlockPrice(DEC_FMT.format(capturedBlockPrice));

        if (capturedRawKelpPrice > 0) config.setSavedRawKelpPrice(DEC_FMT.format(capturedRawKelpPrice));
        if (capturedDriedKelpPrice > 0) config.setSavedDriedKelpPrice(DEC_FMT.format(capturedDriedKelpPrice));
        if (capturedCharcoalPrice > 0) config.setSavedCharcoalPrice(DEC_FMT.format(capturedCharcoalPrice));

        if (capturedOakLogPrice > 0) config.setSavedOakLogPrice(DEC_FMT.format(capturedOakLogPrice));
        if (capturedOakPlanksPrice > 0) config.setSavedOakPlanksPrice(DEC_FMT.format(capturedOakPlanksPrice));

        if (capturedPistonPrice > 0) config.setSavedPistonPrice(DEC_FMT.format(capturedPistonPrice));
        if (capturedSlimeballPrice > 0) config.setSavedSlimeballPrice(DEC_FMT.format(capturedSlimeballPrice));
        if (capturedStickyPistonPrice > 0) config.setSavedStickyPistonPrice(DEC_FMT.format(capturedStickyPistonPrice));

        if (capturedGoldIngotPrice > 0) config.setSavedGoldIngotPrice(DEC_FMT.format(capturedGoldIngotPrice));
        if (capturedApplePrice > 0) config.setSavedApplePrice(DEC_FMT.format(capturedApplePrice));
        if (capturedGapplePrice > 0) config.setSavedGapplePrice(DEC_FMT.format(capturedGapplePrice));

        if (capturedBookPrice > 0) config.setSavedBookPrice(DEC_FMT.format(capturedBookPrice));
        if (capturedBookshelfPrice > 0) config.setSavedBookshelfPrice(DEC_FMT.format(capturedBookshelfPrice));

        int targetTab = 0;
        switch (activeMode) {
            case BONE: targetTab = 0; break;
            case KELP: targetTab = 1; break;
            case OAK_LOG: targetTab = 2; break;
            case STICKY_PISTON: targetTab = 3; break;
            case GOLDEN_APPLE: targetTab = 4; break;
            case BOOKSHELF: targetTab = 5; break;
        }

        final int tabIdx = targetTab;
        if (mc.player != null) {
            mc.player.closeContainer();
            mc.player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                    "§a[Auto Flip] Auto check complete for " + activeMode.name() + "! Prices updated."), true);
        }

        mc.execute(() -> {
            ProfitDetailsScreen.selectedTab = tabIdx;
            ProfitConfig.getInstance().setSavedSelectedTab(tabIdx);
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

        try {
            List<net.minecraft.network.chat.Component> tooltip = stack.getTooltipLines(
                    net.minecraft.world.item.Item.TooltipContext.EMPTY,
                    Minecraft.getInstance().player,
                    net.minecraft.world.item.TooltipFlag.NORMAL
            );
            for (net.minecraft.network.chat.Component lineComponent : tooltip) {
                Matcher m = pricePattern.matcher(lineComponent.getString());
                if (m.find()) {
                    return parseFormattedMoney(m.group(1));
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
