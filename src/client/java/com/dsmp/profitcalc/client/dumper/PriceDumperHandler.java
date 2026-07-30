package com.dsmp.profitcalc.client.dumper;

import com.dsmp.profitcalc.client.config.ProfitConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;

public class PriceDumperHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger("donut-smp-profit-calc/PriceDumper");

    private enum ScanStep {
        SEND_COMMAND,
        WAITING_FOR_SCREEN,
        SCANNING_PAGE_1
    }

    private static class DumpTask {
        final String query;
        final String source; // "ORDER" or "AH"
        DumpTask(String query, String source) {
            this.query = query;
            this.source = source;
        }
    }

    private static boolean running = false;
    private static final Queue<DumpTask> taskQueue = new LinkedList<>();
    private static final List<DumpResult> latestResults = new ArrayList<>();

    private static int totalTasksInitial = 0;
    private static DumpTask currentTask = null;
    private static ScanStep scanStep = ScanStep.SEND_COMMAND;
    private static long lastActionTime = 0;
    private static int waitScreenTicks = 0;

    public static void start(List<String> items, boolean order, boolean ah) {
        taskQueue.clear();
        latestResults.clear();

        if (order) {
            for (String item : items) {
                taskQueue.add(new DumpTask(item, "ORDER"));
            }
        }
        if (ah) {
            for (String item : items) {
                taskQueue.add(new DumpTask(item, "AH"));
            }
        }

        totalTasksInitial = taskQueue.size();
        if (totalTasksInitial == 0) return;

        running = true;
        currentTask = null;
        scanStep = ScanStep.SEND_COMMAND;
        lastActionTime = System.currentTimeMillis();
        LOGGER.info("[Price Dumper] Started price dump scan with {} tasks.", totalTasksInitial);
    }

    public static void stop() {
        running = false;
        taskQueue.clear();
        currentTask = null;
        scanStep = ScanStep.SEND_COMMAND;
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.player != null) {
            mc.player.displayClientMessage(net.minecraft.network.chat.Component.literal("§e[Price Dumper] Dump cancelled by user."), true);
        }
    }

    public static boolean isRunning() {
        return running;
    }

    public static List<DumpResult> getLatestResults() {
        return latestResults;
    }

    public static void onTick(Minecraft mc) {
        if (!running || mc == null || mc.player == null) return;

        long now = System.currentTimeMillis();
        int delayMs = ProfitConfig.getInstance().getRandomizedCommandDelayMs();
        if (now - lastActionTime < delayMs) {
            return;
        }

        if (currentTask == null) {
            if (taskQueue.isEmpty()) {
                finishDump(mc);
                return;
            }

            currentTask = taskQueue.poll();
            waitScreenTicks = 0;
            scanStep = ScanStep.SEND_COMMAND;
            lastActionTime = now;

            if (mc.screen != null) {
                mc.player.closeContainer();
            }

            int processed = totalTasksInitial - taskQueue.size();
            String cmd = currentTask.source.equalsIgnoreCase("ORDER") ? "order " + currentTask.query : "ah " + currentTask.query;

            mc.player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                    String.format("§a[Price Dumper] (%d/%d) Running /%s...", processed, totalTasksInitial, cmd)), true);

            if (mc.player.connection != null) {
                mc.player.connection.sendCommand(cmd);
            }
            scanStep = ScanStep.WAITING_FOR_SCREEN;
            return;
        }

        waitScreenTicks++;

        // Step 1: Waiting for container screen to open
        if (scanStep == ScanStep.WAITING_FOR_SCREEN) {
            if (mc.screen instanceof AbstractContainerScreen<?> containerScreen) {
                scanStep = ScanStep.SCANNING_PAGE_1;
                waitScreenTicks = 0;
                lastActionTime = now + 150;
                return;
            }

            if (waitScreenTicks > 25) {
                LOGGER.warn("[Price Dumper] Timed out waiting for screen for [{}] {}", currentTask.source, currentTask.query);
                latestResults.add(new DumpResult(currentTask.query, currentTask.source, Collections.emptyList()));
                if (mc.screen != null) {
                    mc.player.closeContainer();
                }
                currentTask = null;
                lastActionTime = now + 200;
            }
            return;
        }

        // Step 2: Dump first 15 items on page 1
        if (scanStep == ScanStep.SCANNING_PAGE_1) {
            if (mc.screen instanceof AbstractContainerScreen<?> containerScreen) {
                List<DumpResult.ItemDumpInfo> dumpedItems = dumpFirst15Items(containerScreen);
                latestResults.add(new DumpResult(currentTask.query, currentTask.source, dumpedItems));

                LOGGER.info("[Price Dumper] Captured [{}] {}: dumped {} items from page 1",
                        currentTask.source, currentTask.query, dumpedItems.size());

                mc.player.closeContainer();
                currentTask = null;
                lastActionTime = System.currentTimeMillis();
            } else {
                scanStep = ScanStep.WAITING_FOR_SCREEN;
            }
        }
    }

    private static List<DumpResult.ItemDumpInfo> dumpFirst15Items(AbstractContainerScreen<?> screen) {
        List<DumpResult.ItemDumpInfo> list = new ArrayList<>();
        if (screen == null || screen.getMenu() == null) return list;
        List<Slot> slots = screen.getMenu().slots;
        if (slots.isEmpty()) return list;

        int count = 0;
        int[] STACK_MULTIPLIERS = {1, 4, 8, 12, 16, 24, 32, 48, 64};

        for (int i = 0; i < Math.min(45, slots.size()) && count < 15; i++) {
            ItemStack stack = slots.get(i).getItem();
            if (stack.isEmpty()) continue;

            Identifier loc = BuiltInRegistries.ITEM.getKey(stack.getItem());
            String itemId = loc != null ? loc.toString() : "unknown";
            String displayName = stack.getHoverName().getString().trim();

            int maxStackSize = stack.getMaxStackSize();
            int qty = stack.getCount();
            double unitPrice = parsePriceFromStack(stack);

            if (unitPrice <= 0) continue;

            Map<Integer, Double> stackPrices = new LinkedHashMap<>();
            for (int mult : STACK_MULTIPLIERS) {
                if (mult <= maxStackSize) {
                    stackPrices.put(mult, unitPrice * mult);
                }
            }

            list.add(new DumpResult.ItemDumpInfo(displayName, itemId, maxStackSize, qty, unitPrice, stackPrices));
            count++;
        }

        return list;
    }

    private static double parsePriceFromStack(ItemStack stack) {
        if (stack.isEmpty()) return 0.0;

        try {
            var loreComponent = stack.get(net.minecraft.core.component.DataComponents.LORE);
            if (loreComponent != null) {
                for (net.minecraft.network.chat.Component comp : loreComponent.lines()) {
                    String line = comp.getString();
                    if (line.contains("$")) {
                        double p = extractPriceFromLine(line);
                        if (p > 0) return p;
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
                String line = lineComponent.getString();
                if (line.contains("$")) {
                    double p = extractPriceFromLine(line);
                    if (p > 0) return p;
                }
            }
        } catch (Exception ignored) {}

        return 0.0;
    }

    private static double extractPriceFromLine(String line) {
        int idx = line.indexOf('$');
        if (idx == -1) return 0.0;
        String rest = line.substring(idx + 1).trim();

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < rest.length(); i++) {
            char c = rest.charAt(i);
            if (Character.isDigit(c) || c == '.' || c == ',') {
                sb.append(c);
            } else {
                break;
            }
        }

        String raw = sb.toString().replace(",", "");
        try {
            return Double.parseDouble(raw);
        } catch (Exception e) {
            return 0.0;
        }
    }

    private static void finishDump(Minecraft mc) {
        running = false;
        currentTask = null;

        PriceExporter.exportToTxt(latestResults);
        PriceExporter.exportToJson(latestResults);

        if (mc.player != null) {
            mc.player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                    String.format("§a[Price Dumper] Accurate 15-item dump complete! Scanned %d queries.", latestResults.size())), false);
        }
    }
}
