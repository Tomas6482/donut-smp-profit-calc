package com.dsmp.profitcalc.client.flipfinder;

import com.dsmp.profitcalc.client.config.ProfitConfig;
import com.dsmp.profitcalc.client.dumper.DumpResult;
import com.dsmp.profitcalc.client.dumper.PriceExporter;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class FlipFinderHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger("donut-smp-profit-calc/FlipFinder");
    private static final DecimalFormat DEC_FMT = new DecimalFormat("#,##0.##");

    public static final int MAX_PAGES_SAFETY = 25;
    public static final int DEFAULT_ORDER_MIN_QTY = 16;
    public static final int DEFAULT_AH_MIN_QTY = 64;

    private static final long INITIAL_COMMAND_DELAY_MS = 350;
    private static final long PAGE_TURN_DELAY_MS = 300;
    private static final long TIMEOUT_LIMIT_MS = 15000;

    public static class Listing {
        public final double price;
        public final double quantity;
        public Listing(double price, double quantity) {
            this.price = price;
            this.quantity = quantity;
        }
    }

    private static class FinderTask {
        final String command;
        final String itemId;
        final String source; // "ORDER" or "AH"
        FinderTask(String command, String itemId, String source) {
            this.command = command;
            this.itemId = itemId;
            this.source = source;
        }
    }

    private enum ScanStep {
        SEND_COMMAND,
        WAITING_FOR_SCREEN,
        SCANNING_PAGE,
        PAGING_CLICK
    }

    private static boolean running = false;
    private static final Queue<FinderTask> taskQueue = new LinkedList<>();
    private static final List<FlipRecipe> activeRecipes = new ArrayList<>();
    private static final List<FlipResult> latestResults = new ArrayList<>();

    private static int totalTasksInitial = 0;
    private static FinderTask currentTask = null;
    private static ScanStep scanStep = ScanStep.SEND_COMMAND;
    private static long lastActionTime = 0;
    private static int waitScreenTicks = 0;

    private static int pagesScanned = 0;
    private static int consecutivePagesWithoutMatches = 0;
    private static int totalMatchesFoundForTask = 0;

    // Stored raw price listings per item per source
    private static final Map<String, List<Listing>> orderListingsMap = new HashMap<>();
    private static final Map<String, List<Listing>> ahListingsMap = new HashMap<>();

    public static void start(List<FlipRecipe> recipes) {
        if (recipes == null || recipes.isEmpty()) return;

        taskQueue.clear();
        activeRecipes.clear();
        activeRecipes.addAll(recipes);
        latestResults.clear();
        orderListingsMap.clear();
        ahListingsMap.clear();

        Set<String> orderItems = new LinkedHashSet<>();
        Set<String> ahItems = new LinkedHashSet<>();

        for (FlipRecipe r : recipes) {
            orderItems.add(r.outputItem);
            for (FlipRecipe.Ingredient ing : r.ingredients) {
                ahItems.add(ing.itemId());
            }
        }

        for (String item : orderItems) {
            taskQueue.add(new FinderTask("order " + item, item, "ORDER"));
        }
        for (String item : ahItems) {
            taskQueue.add(new FinderTask("ah " + item, item, "AH"));
        }

        totalTasksInitial = taskQueue.size();
        if (totalTasksInitial == 0) return;

        running = true;
        currentTask = null;
        scanStep = ScanStep.SEND_COMMAND;
        lastActionTime = System.currentTimeMillis();
        LOGGER.info("[Flip Finder] Started scan for {} recipes ({} total scan tasks).", recipes.size(), totalTasksInitial);
    }

    public static void stop() {
        running = false;
        taskQueue.clear();
        currentTask = null;
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.player != null) {
            mc.player.displayClientMessage(net.minecraft.network.chat.Component.literal("§e[Flip Finder] Cancelled by user."), true);
        }
    }

    public static boolean isRunning() {
        return running;
    }

    public static List<FlipResult> getLatestResults() {
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
                finishScanAndComputeProfits(mc);
                return;
            }

            currentTask = taskQueue.poll();
            waitScreenTicks = 0;
            pagesScanned = 0;
            consecutivePagesWithoutMatches = 0;
            totalMatchesFoundForTask = 0;
            scanStep = ScanStep.SEND_COMMAND;
            lastActionTime = now;

            if (mc.screen != null) {
                mc.player.closeContainer();
            }

            int processed = totalTasksInitial - taskQueue.size();
            mc.player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                    String.format("§a[Flip Finder] (%d/%d) Running /%s...", processed, totalTasksInitial, currentTask.command)), true);

            if (mc.player.connection != null) {
                mc.player.connection.sendCommand(currentTask.command);
            }
            scanStep = ScanStep.WAITING_FOR_SCREEN;
            return;
        }

        waitScreenTicks++;

        if (scanStep == ScanStep.WAITING_FOR_SCREEN) {
            if (mc.screen instanceof AbstractContainerScreen<?> containerScreen) {
                scanStep = ScanStep.SCANNING_PAGE;
                waitScreenTicks = 0;
                lastActionTime = now + 100;
                return;
            }

            if (waitScreenTicks > 20) {
                LOGGER.warn("[Flip Finder] Timed out waiting for screen for /{}", currentTask.command);
                if (mc.screen != null) mc.player.closeContainer();
                currentTask = null;
                lastActionTime = now + 200;
            }
            return;
        }

        if (scanStep == ScanStep.SCANNING_PAGE) {
            if (mc.screen instanceof AbstractContainerScreen<?> containerScreen) {
                int matchedThisPage = scanCurrentPage(containerScreen, currentTask.itemId, currentTask.source);
                pagesScanned++;
                totalMatchesFoundForTask += matchedThisPage;

                if (matchedThisPage == 0) {
                    consecutivePagesWithoutMatches++;
                } else {
                    consecutivePagesWithoutMatches = 0;
                }

                boolean hasNextPage = isNextPageAvailable(containerScreen);
                boolean stopPagination = !hasNextPage || totalMatchesFoundForTask >= 3 || consecutivePagesWithoutMatches >= 2 || pagesScanned >= MAX_PAGES_SAFETY;

                if (stopPagination) {
                    mc.player.closeContainer();
                    currentTask = null;
                    lastActionTime = System.currentTimeMillis();
                } else {
                    int containerId = containerScreen.getMenu().containerId;
                    mc.gameMode.handleInventoryMouseClick(containerId, 53, 0, ClickType.PICKUP, mc.player);
                    scanStep = ScanStep.PAGING_CLICK;
                    waitScreenTicks = 0;
                    lastActionTime = now + 150;
                }
            } else {
                scanStep = ScanStep.WAITING_FOR_SCREEN;
            }
            return;
        }

        if (scanStep == ScanStep.PAGING_CLICK) {
            if (mc.screen instanceof AbstractContainerScreen<?>) {
                scanStep = ScanStep.SCANNING_PAGE;
                waitScreenTicks = 0;
                lastActionTime = now + 100;
            } else if (waitScreenTicks > 15) {
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

        Map<String, List<Listing>> targetMap = isOrder ? orderListingsMap : ahListingsMap;

        for (int i = 0; i < Math.min(45, slots.size()); i++) {
            ItemStack stack = slots.get(i).getItem();
            if (stack.isEmpty()) continue;

            Identifier loc = BuiltInRegistries.ITEM.getKey(stack.getItem());
            if (loc == null) continue;

            String stackCanonicalId = loc.getPath().toLowerCase();

            if (stackCanonicalId.equals(targetCanonicalId.toLowerCase())) {
                double price = parsePriceFromStack(stack);
                if (price > 0) {
                    matchedCount++;
                    double quantity = parseQuantityFromStack(stack);
                    targetMap.computeIfAbsent(targetCanonicalId, k -> new ArrayList<>()).add(new Listing(price, quantity));
                }
            }
        }

        return matchedCount;
    }

    private static boolean isNextPageAvailable(AbstractContainerScreen<?> screen) {
        if (screen == null || screen.getMenu() == null) return false;
        List<Slot> slots = screen.getMenu().slots;
        if (slots.size() <= 53) return false;

        ItemStack stack = slots.get(53).getItem();
        if (stack.isEmpty()) return false;

        String name = stack.getHoverName().getString().toLowerCase();
        Identifier loc = BuiltInRegistries.ITEM.getKey(stack.getItem());
        String itemId = loc != null ? loc.getPath().toLowerCase() : "";

        return name.contains("next") || name.contains("page") || name.contains("-->") || itemId.contains("arrow");
    }

    private static void finishScanAndComputeProfits(Minecraft mc) {
        running = false;
        currentTask = null;
        latestResults.clear();

        double craftQtyRequested = 100.0;

        for (FlipRecipe recipe : activeRecipes) {
            String outputItem = recipe.outputItem;

            // Get AH listings for all ingredients (buy side)
            boolean missingData = false;
            boolean lowConfidence = false;
            double maxCraftsFromIngredients = craftQtyRequested;

            Map<String, Double> ingredientCostsPaid = new HashMap<>();

            for (FlipRecipe.Ingredient ing : recipe.ingredients) {
                List<Listing> ahList = ahListingsMap.getOrDefault(ing.itemId(), Collections.emptyList());
                if (ahList.isEmpty()) {
                    LOGGER.warn("[Flip Finder] Zero AH matches for ingredient '{}' in recipe '{}'", ing.itemId(), outputItem);
                    missingData = true;
                    break;
                }
                if (ahList.size() < 5) lowConfidence = true;

                // Sort AH listings ascending (cheapest first)
                List<Listing> sortedAh = new ArrayList<>(ahList);
                sortedAh.sort((a, b) -> Double.compare(a.price, b.price));

                double neededQty = ing.qtyPerCraft() * craftQtyRequested;
                double filledQty = 0.0;
                double costPaid = 0.0;

                for (Listing l : sortedAh) {
                    double supply = Math.max(1.0, l.quantity);
                    double take = Math.min(neededQty - filledQty, supply);
                    costPaid += take * l.price;
                    filledQty += take;
                    if (filledQty >= neededQty) break;
                }

                double craftsPossible = filledQty / ing.qtyPerCraft();
                if (craftsPossible < maxCraftsFromIngredients) {
                    maxCraftsFromIngredients = craftsPossible;
                }

                ingredientCostsPaid.put(ing.itemId(), costPaid);
            }

            if (missingData) continue;

            // Get ORDER listings for output item (sell side)
            List<Listing> orderList = orderListingsMap.getOrDefault(outputItem, Collections.emptyList());
            if (orderList.isEmpty()) {
                LOGGER.warn("[Flip Finder] Zero ORDER matches for output item '{}'", outputItem);
                continue;
            }
            if (orderList.size() < 5) lowConfidence = true;

            // Sort ORDER listings descending (highest buy orders first)
            List<Listing> sortedOrders = new ArrayList<>(orderList);
            sortedOrders.sort((a, b) -> Double.compare(b.price, a.price));

            double neededOutputQty = recipe.outputQtyPerCraft * craftQtyRequested;
            double filledOutputQty = 0.0;
            double totalRevenue = 0.0;

            for (Listing l : sortedOrders) {
                double demand = Math.max(1.0, l.quantity);
                double fill = Math.min(neededOutputQty - filledOutputQty, demand);
                totalRevenue += fill * l.price;
                filledOutputQty += fill;
                if (filledOutputQty >= neededOutputQty) break;
            }

            double maxCraftsFromOutput = filledOutputQty / recipe.outputQtyPerCraft;
            double maxRealisticCraftQty = Math.min(craftQtyRequested, Math.min(maxCraftsFromIngredients, maxCraftsFromOutput));

            if (maxRealisticCraftQty <= 0) continue;

            // Scale total cost and revenue to maxRealisticCraftQty
            double scaleRatio = maxRealisticCraftQty / craftQtyRequested;
            double totalCost = 0.0;
            for (double c : ingredientCostsPaid.values()) {
                totalCost += c * scaleRatio;
            }
            totalRevenue *= scaleRatio;

            double totalProfit = totalRevenue - totalCost;
            double marginPct = totalCost > 0 ? (totalProfit / totalCost) * 100.0 : 0.0;

            latestResults.add(new FlipResult(outputItem, craftQtyRequested, maxRealisticCraftQty,
                    totalCost, totalRevenue, totalProfit, marginPct, lowConfidence));
        }

        // Sort results descending by totalProfit by default
        latestResults.sort((a, b) -> Double.compare(b.totalProfit, a.totalProfit));

        LOGGER.info("[Flip Finder] Computed profit for {} valid recipes.", latestResults.size());

        if (mc.player != null) {
            mc.player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                    String.format("§a[Flip Finder] Scan complete! Found %d profitable flips.", latestResults.size())), false);
        }
    }

    private static double parseQuantityFromStack(ItemStack stack) {
        if (stack.isEmpty()) return 1.0;
        double stackCount = stack.getCount();

        Pattern[] qtyPatterns = new Pattern[] {
            Pattern.compile("(?:Amount|Qty|Quantity|Requesting|Count|Size|Items|Buying|Left|Total|Order|Orders|Remaining|Filled)\\s*:?\\s*([0-9.,]+[kmbKMB]?)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("([0-9.,]+[kmbKMB]?)\\s*(?:x|items|blocks|amount|qty|quantity|left|remaining|orders)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("x\\s*([0-9.,]+[kmbKMB]?)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("([0-9.,]+[kmbKMB]?)\\s*x", Pattern.CASE_INSENSITIVE)
        };

        try {
            var loreComponent = stack.get(net.minecraft.core.component.DataComponents.LORE);
            if (loreComponent != null) {
                for (net.minecraft.network.chat.Component comp : loreComponent.lines()) {
                    String line = comp.getString().trim();
                    for (Pattern p : qtyPatterns) {
                        Matcher m = p.matcher(line);
                        if (m.find()) {
                            double parsed = parseFormattedMoney(m.group(1));
                            if (parsed > 0) return Math.max(stackCount, parsed);
                        }
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
                String line = lineComponent.getString().trim();
                for (Pattern p : qtyPatterns) {
                    Matcher m = p.matcher(line);
                    if (m.find()) {
                        double parsed = parseFormattedMoney(m.group(1));
                        if (parsed > 0) return Math.max(stackCount, parsed);
                    }
                }
            }
        } catch (Exception ignored) {}

        return Math.max(1.0, stackCount);
    }

    private static double parsePriceFromStack(ItemStack stack) {
        if (stack.isEmpty()) return 0.0;
        Pattern pricePattern = Pattern.compile("\\$\\s*([0-9.,]+(?:e[+-]?[0-9]+)?\\s*[kmbKMB]?)", Pattern.CASE_INSENSITIVE);

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
