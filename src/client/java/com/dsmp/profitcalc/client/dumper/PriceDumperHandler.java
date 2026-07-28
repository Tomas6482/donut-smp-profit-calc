package com.dsmp.profitcalc.client.dumper;

import com.dsmp.profitcalc.client.config.ProfitConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;

public class PriceDumperHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger("donut-smp-profit-calc/PriceDumper");

    // Configurable quantity thresholds
    public static final int DEFAULT_AH_MIN_QTY = 64;
    public static final int FALLBACK_AH_MIN_QTY = 1;

    public static final int DEFAULT_ORDER_MIN_QTY = 16;
    public static final int FALLBACK_ORDER_MIN_QTY = 1;

    // Pagination constants
    public static final int MAX_PAGES_PER_QUERY = 5;
    public static final int NEXT_PAGE_SLOT = 53; // Arrow in container GUI slot 53 (6x9 inventory bottom-right)

    private enum ScanStep {
        SEND_COMMAND,
        WAITING_FOR_SCREEN,
        SCANNING_PAGE,
        PAGING_CLICK
    }

    private static class DumpTask {
        final String query; // Canonical item ID, e.g. "quartz_block"
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

    // Page aggregation state
    private static int currentPageIndex = 0;
    private static int consecutivePagesWithoutMatches = 0;
    private static final List<Double> currentRawPrices = new ArrayList<>();
    private static final List<Double> currentFallbackPrices = new ArrayList<>();

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
        LOGGER.info("[Price Dumper] Started accurate price dump scan with {} tasks.", totalTasksInitial);
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
            currentPageIndex = 0;
            consecutivePagesWithoutMatches = 0;
            currentRawPrices.clear();
            currentFallbackPrices.clear();
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
                scanStep = ScanStep.SCANNING_PAGE;
                waitScreenTicks = 0;
                lastActionTime = now + 100;
                return;
            }

            if (waitScreenTicks > 20) {
                LOGGER.warn("[Price Dumper] Timed out waiting for screen for [{}] {}", currentTask.source, currentTask.query);
                recordResult(currentTask.query, currentTask.source, Collections.emptyList(), Collections.emptyList());
                if (mc.screen != null) {
                    mc.player.closeContainer();
                }
                currentTask = null;
                lastActionTime = now + 200;
            }
            return;
        }

        // Step 2: Scan current page
        if (scanStep == ScanStep.SCANNING_PAGE) {
            if (mc.screen instanceof AbstractContainerScreen<?> containerScreen) {
                int matchedThisPage = scanCurrentPage(containerScreen, currentTask.query, currentTask.source);
                currentPageIndex++;

                if (matchedThisPage == 0) {
                    consecutivePagesWithoutMatches++;
                } else {
                    consecutivePagesWithoutMatches = 0;
                }

                // Check pagination conditions
                boolean hasNextPage = isNextPageAvailable(containerScreen);
                boolean stopPagination = !hasNextPage || consecutivePagesWithoutMatches >= 2 || currentPageIndex >= MAX_PAGES_PER_QUERY;

                if (stopPagination) {
                    recordResult(currentTask.query, currentTask.source, currentRawPrices, currentFallbackPrices);
                    mc.player.closeContainer();
                    currentTask = null;
                    lastActionTime = System.currentTimeMillis();
                } else {
                    // Click Next Page button
                    int containerId = containerScreen.getMenu().containerId;
                    mc.gameMode.handleInventoryMouseClick(containerId, NEXT_PAGE_SLOT, 0, ClickType.PICKUP, mc.player);
                    scanStep = ScanStep.PAGING_CLICK;
                    waitScreenTicks = 0;
                    lastActionTime = now + 150;
                }
            } else {
                scanStep = ScanStep.WAITING_FOR_SCREEN;
            }
            return;
        }

        // Step 3: Wait briefly after clicking next page button
        if (scanStep == ScanStep.PAGING_CLICK) {
            if (mc.screen instanceof AbstractContainerScreen<?>) {
                scanStep = ScanStep.SCANNING_PAGE;
                waitScreenTicks = 0;
                lastActionTime = now + 100;
            } else if (waitScreenTicks > 15) {
                recordResult(currentTask.query, currentTask.source, currentRawPrices, currentFallbackPrices);
                currentTask = null;
                lastActionTime = now + 200;
            }
        }
    }

    private static int scanCurrentPage(AbstractContainerScreen<?> screen, String targetCanonicalId, String source) {
        if (screen == null || screen.getMenu() == null) return 0;
        List<Slot> slots = screen.getMenu().slots;
        if (slots.isEmpty()) return 0;

        boolean isOrder = source.equalsIgnoreCase("ORDER");
        int minQtyThreshold = isOrder ? DEFAULT_ORDER_MIN_QTY : DEFAULT_AH_MIN_QTY;
        int matchedCount = 0;

        for (int i = 0; i < Math.min(45, slots.size()); i++) {
            ItemStack stack = slots.get(i).getItem();
            if (stack.isEmpty()) continue;

            Identifier loc = BuiltInRegistries.ITEM.getKey(stack.getItem());
            if (loc == null) continue;

            String stackCanonicalId = loc.getPath().toLowerCase();

            // Strict exact ID matching
            if (stackCanonicalId.equals(targetCanonicalId.toLowerCase())) {
                double price = parsePriceFromStack(stack);
                if (price > 0) {
                    matchedCount++;
                    int count = stack.getCount();
                    if (count >= minQtyThreshold) {
                        currentRawPrices.add(price);
                    } else {
                        currentFallbackPrices.add(price);
                    }
                }
            }
        }

        return matchedCount;
    }

    private static boolean isNextPageAvailable(AbstractContainerScreen<?> screen) {
        if (screen == null || screen.getMenu() == null) return false;
        List<Slot> slots = screen.getMenu().slots;
        if (slots.size() <= NEXT_PAGE_SLOT) return false;

        ItemStack stack = slots.get(NEXT_PAGE_SLOT).getItem();
        if (stack.isEmpty()) return false;

        String name = stack.getHoverName().getString().toLowerCase();
        Identifier loc = BuiltInRegistries.ITEM.getKey(stack.getItem());
        String itemId = loc != null ? loc.getPath().toLowerCase() : "";

        return name.contains("next") || name.contains("page") || name.contains("-->") || itemId.contains("arrow");
    }

    private static void recordResult(String query, String source, List<Double> primaryPrices, List<Double> fallbackPrices) {
        boolean isOrder = source.equalsIgnoreCase("ORDER");
        int defaultThreshold = isOrder ? DEFAULT_ORDER_MIN_QTY : DEFAULT_AH_MIN_QTY;

        List<Double> activePrices = primaryPrices;

        if (primaryPrices.isEmpty()) {
            if (!fallbackPrices.isEmpty()) {
                LOGGER.warn("[Price Dumper] Query '{}' [{}] returned 0 listings with qty >= {}. Falling back to {} listings with qty < {}.",
                        query, source, defaultThreshold, fallbackPrices.size(), defaultThreshold);
                activePrices = fallbackPrices;
            } else {
                LOGGER.warn("[Price Dumper] Query '{}' [{}] returned ZERO valid matches across all scanned pages.", query, source);
            }
        }

        if (activePrices.isEmpty()) {
            latestResults.add(new DumpResult(query, source, Collections.emptyList(), 0.0, 0.0, 0.0, 0));
            return;
        }

        // Sorting logic:
        // ORDER (buy orders): higher prices are top priority to sell to (sort descending)
        // AH (sell listings): lower prices are top priority to buy from (sort ascending)
        if (isOrder) {
            activePrices.sort(Collections.reverseOrder());
        } else {
            Collections.sort(activePrices);
        }

        int sampleSize = activePrices.size();
        double lowest = Double.MAX_VALUE;
        double highest = Double.MIN_VALUE;

        for (double p : activePrices) {
            if (p < lowest) lowest = p;
            if (p > highest) highest = p;
        }

        int top5Count = Math.min(5, sampleSize);
        List<Double> top5 = new ArrayList<>(activePrices.subList(0, top5Count));

        int top10Count = Math.min(10, sampleSize);
        double sumTop10 = 0;
        for (int i = 0; i < top10Count; i++) {
            sumTop10 += activePrices.get(i);
        }
        double avgTop10 = sumTop10 / top10Count;

        latestResults.add(new DumpResult(query, source, top5, avgTop10, highest, lowest, sampleSize));
        LOGGER.info("[Price Dumper] Captured [{}] {}: SampleSize={}, AvgTop10=${}, Range=[${} - ${}]",
                source, query, sampleSize, String.format("%.2f", avgTop10), String.format("%.2f", lowest), String.format("%.2f", highest));
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
                    String.format("§a[Price Dumper] Accurate dump complete! Scanned %d queries.", latestResults.size())), false);
        }
    }
}
