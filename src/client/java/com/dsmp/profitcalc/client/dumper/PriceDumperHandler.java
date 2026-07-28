package com.dsmp.profitcalc.client.dumper;

import com.dsmp.profitcalc.client.config.ProfitConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;

public class PriceDumperHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger("donut-smp-profit-calc/PriceDumper");
    private static final DecimalFormat DEC_FMT = new DecimalFormat("#,##0.##");

    private static class DumpTask {
        final String query;
        final String source;
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
        lastActionTime = System.currentTimeMillis();
        LOGGER.info("[Price Dumper] Started price dump scan with {} tasks.", totalTasksInitial);
    }

    public static void stop() {
        running = false;
        taskQueue.clear();
        currentTask = null;
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
            return;
        }

        waitScreenTicks++;

        if (mc.screen instanceof AbstractContainerScreen<?> containerScreen) {
            double priceFound = scanContainerPrices(containerScreen, currentTask.query);
            String formatted = priceFound > 0 ? "$" + DEC_FMT.format(priceFound) : "N/A";

            latestResults.add(new DumpResult(currentTask.query, currentTask.source, priceFound, formatted));
            LOGGER.info("[Price Dumper] Captured [{}] {}: {}", currentTask.source, currentTask.query, formatted);

            mc.player.closeContainer();
            currentTask = null;
            lastActionTime = System.currentTimeMillis();
            return;
        }

        if (waitScreenTicks > 15) {
            LOGGER.warn("[Price Dumper] Timed out waiting for screen for [{}] {}", currentTask.source, currentTask.query);
            latestResults.add(new DumpResult(currentTask.query, currentTask.source, 0.0, "N/A"));
            if (mc.screen != null) {
                mc.player.closeContainer();
            }
            currentTask = null;
            lastActionTime = now + 200;
        }
    }

    private static double scanContainerPrices(AbstractContainerScreen<?> screen, String query) {
        if (screen == null || screen.getMenu() == null) return 0.0;
        List<Slot> slots = screen.getMenu().slots;
        if (slots.isEmpty()) return 0.0;

        double lowestPrice = Double.MAX_VALUE;

        for (int i = 0; i < Math.min(45, slots.size()); i++) {
            ItemStack stack = slots.get(i).getItem();
            if (stack.isEmpty()) continue;

            String itemName = stack.getHoverName().getString().trim().toLowerCase();
            String q = query.trim().toLowerCase();

            if (itemName.contains(q) || q.contains(itemName) || isWordMatch(itemName, q)) {
                double price = parsePriceFromStack(stack);
                if (price > 0 && price < lowestPrice) {
                    lowestPrice = price;
                }
            }
        }

        return lowestPrice == Double.MAX_VALUE ? 0.0 : lowestPrice;
    }

    private static boolean isWordMatch(String text, String query) {
        String[] words = query.split("\\s+");
        for (String w : words) {
            if (!text.contains(w)) return false;
        }
        return true;
    }

    private static double parsePriceFromStack(ItemStack stack) {
        List<net.minecraft.network.chat.Component> tooltip = stack.getTooltipLines(
                net.minecraft.world.item.Item.TooltipContext.EMPTY,
                Minecraft.getInstance().player,
                net.minecraft.world.item.TooltipFlag.NORMAL
        );

        for (net.minecraft.network.chat.Component lineComponent : tooltip) {
            String line = lineComponent.getString();
            if (line.contains("$")) {
                double price = extractPriceFromLine(line);
                if (price > 0) return price;
            }
        }
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
                    String.format("§a[Price Dumper] Dump complete! Scanned %d items.", latestResults.size())), false);
        }
    }
}
