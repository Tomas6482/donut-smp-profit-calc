package com.dsmp.profitcalc.client.handler;

import com.dsmp.profitcalc.client.ui.ProfitDetailsScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class BestDealFinderHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger("donut-smp-profit-calc/BestDealFinder");
    private static final DecimalFormat DEC_FMT = new DecimalFormat("#,##0.##");

    private static final long INITIAL_COMMAND_DELAY_MS = 600;
    private static final long PAGE_TURN_DELAY_MS = 300;
    private static final long TIMEOUT_LIMIT_MS = 15000;
    private static final int MAX_PAGE_LIMIT = 20;

    public static class BestDealResult {
        public final String targetQuery;
        public final String matchedItemName;
        public final String itemPath;
        public final double pricePerUnit;
        public final double totalPrice;
        public final int quantity;
        public final int pageNumber;
        public final int slotIndex;
        public final boolean isQualifying;
        public final int requestedQty;
        public final ItemStack matchedStack;

        public BestDealResult(String targetQuery, String matchedItemName, String itemPath,
                              double pricePerUnit, double totalPrice, int quantity,
                              int pageNumber, int slotIndex, boolean isQualifying, int requestedQty,
                              ItemStack matchedStack) {
            this.targetQuery = targetQuery;
            this.matchedItemName = matchedItemName;
            this.itemPath = itemPath;
            this.pricePerUnit = pricePerUnit;
            this.totalPrice = totalPrice;
            this.quantity = quantity;
            this.pageNumber = pageNumber;
            this.slotIndex = slotIndex;
            this.isQualifying = isQualifying;
            this.requestedQty = requestedQty;
            this.matchedStack = matchedStack;
        }
    }

    public static class BestDealBundle {
        public final String targetQuery;
        public final String itemName;
        public final List<BestDealResult> listings;
        public final double combinedTotalCost;
        public final int combinedTotalQty;
        public final double effectivePricePerUnit;
        public final boolean isQualifying;
        public final int requestedQty;
        public final boolean isMultiListing;

        public BestDealBundle(String targetQuery, String itemName, List<BestDealResult> listings,
                              double combinedTotalCost, int combinedTotalQty, double effectivePricePerUnit,
                              boolean isQualifying, int requestedQty) {
            this.targetQuery = targetQuery;
            this.itemName = itemName;
            this.listings = listings;
            this.combinedTotalCost = combinedTotalCost;
            this.combinedTotalQty = combinedTotalQty;
            this.effectivePricePerUnit = effectivePricePerUnit;
            this.isQualifying = isQualifying;
            this.requestedQty = requestedQty;
            this.isMultiListing = listings.size() > 1;
        }

        public String getSummaryText() {
            if (listings.isEmpty()) return "No listings";
            if (!isMultiListing) {
                return "Single Listing (" + listings.get(0).quantity + "x)";
            }
            Map<Integer, Integer> stackCounts = new HashMap<>();
            for (BestDealResult l : listings) {
                stackCounts.put(l.quantity, stackCounts.getOrDefault(l.quantity, 0) + 1);
            }
            List<String> parts = new ArrayList<>();
            for (Map.Entry<Integer, Integer> entry : stackCounts.entrySet()) {
                parts.add(entry.getValue() + "x " + entry.getKey() + "x");
            }
            return "Combined Listings (" + listings.size() + " listings: " + String.join(", ", parts) + ")";
        }
    }

    private enum State {
        IDLE,
        SCANNING,
        NAVIGATING_TO_BUY
    }

    private static State currentState = State.IDLE;
    private static boolean running = false;

    private static String currentQuery = "";
    private static int targetQuantity = 64;
    private static double maxPriceCap = 0.0;
    private static boolean autoBuyEnabled = false;

    private static int pagesScanned = 0;
    private static int consecutiveEmptyPages = 0;
    private static long lastActionTime = 0;

    private static final List<BestDealResult> allMatchesFound = new ArrayList<>();
    private static final List<BestDealResult> qualifyingMatchesFound = new ArrayList<>();

    private static BestDealBundle latestBundle = null;

    private static final Queue<BestDealResult> purchaseQueue = new LinkedList<>();
    private static BestDealResult currentPurchaseDeal = null;
    private static int totalPurchasesInChain = 0;
    private static int successfulPurchasesCount = 0;

    public static boolean isRunning() {
        return running;
    }

    public static BestDealBundle getLatestBundle() {
        return latestBundle;
    }

    public static void stop() {
        running = false;
        currentState = State.IDLE;
        pagesScanned = 0;
        consecutiveEmptyPages = 0;
        allMatchesFound.clear();
        qualifyingMatchesFound.clear();
        purchaseQueue.clear();
        currentPurchaseDeal = null;
        totalPurchasesInChain = 0;
        successfulPurchasesCount = 0;
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.player != null) {
            mc.player.displayClientMessage(Component.literal("§e[Best Deal Finder] Search cancelled."), true);
        }
    }

    public static int resolveMaxStackSize(String itemInput) {
        if (itemInput == null || itemInput.trim().isEmpty()) return 64;
        String clean = itemInput.trim().toLowerCase().replace(" ", "_");
        Identifier id = Identifier.tryParse(clean.contains(":") ? clean : "minecraft:" + clean);
        if (id != null) {
            var itemOpt = BuiltInRegistries.ITEM.getOptional(id);
            if (itemOpt.isPresent()) {
                return itemOpt.get().getDefaultInstance().getMaxStackSize();
            }
        }
        for (Item item : BuiltInRegistries.ITEM) {
            Identifier loc = BuiltInRegistries.ITEM.getKey(item);
            if (loc != null) {
                String path = loc.getPath().toLowerCase();
                if (path.equals(clean) || path.replace("_", "").equals(clean.replace("_", ""))) {
                    return item.getDefaultInstance().getMaxStackSize();
                }
            }
        }
        return 64;
    }

    public static void startSearch(String itemInput, int requestedQty, double maxPricePerUnit, boolean autoBuy) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        currentQuery = itemInput.trim().toLowerCase();
        targetQuantity = requestedQty > 0 ? requestedQty : resolveMaxStackSize(itemInput);
        maxPriceCap = maxPricePerUnit;
        autoBuyEnabled = autoBuy;

        allMatchesFound.clear();
        qualifyingMatchesFound.clear();
        purchaseQueue.clear();
        currentPurchaseDeal = null;
        totalPurchasesInChain = 0;
        successfulPurchasesCount = 0;
        pagesScanned = 0;
        consecutiveEmptyPages = 0;
        latestBundle = null;

        running = true;
        currentState = State.SCANNING;
        lastActionTime = System.currentTimeMillis();

        if (mc.screen != null) {
            mc.player.closeContainer();
        }

        String command = "ah " + currentQuery;
        LOGGER.info("[Best Deal Finder] Starting search: /{} (Target Qty: {}, Max Price: ${}, Auto Buy: {})",
                command, targetQuantity, maxPriceCap > 0 ? DEC_FMT.format(maxPriceCap) : "None", autoBuyEnabled);

        if (mc.player.connection != null) {
            mc.player.connection.sendCommand(command);
        }

        mc.player.displayClientMessage(Component.literal("§a[Best Deal Finder] Searching AH for '" + currentQuery + "'..."), true);
    }

    public static void onTick(Minecraft mc) {
        if (!running || mc == null || mc.player == null) return;

        long now = System.currentTimeMillis();

        // Timeout check
        if (now - lastActionTime > TIMEOUT_LIMIT_MS) {
            LOGGER.warn("[Best Deal Finder] Search timed out during state: {}", currentState);
            if (currentState == State.NAVIGATING_TO_BUY) {
                abortPurchaseQueue(mc, "Timed out waiting for container screen");
            } else {
                finishSearch(mc);
            }
            return;
        }

        if (currentState == State.SCANNING) {
            if (mc.screen instanceof AbstractContainerScreen<?> containerScreen) {
                long delayRequired = (pagesScanned == 0) ? INITIAL_COMMAND_DELAY_MS : PAGE_TURN_DELAY_MS;
                if (now - lastActionTime < delayRequired) return;

                int matchesOnThisPage = scanCurrentContainer(containerScreen);
                int totalQualifying = qualifyingMatchesFound.size();

                if (totalQualifying > 0) {
                    if (matchesOnThisPage == 0) {
                        consecutiveEmptyPages++;
                    } else {
                        consecutiveEmptyPages = 0;
                    }
                } else {
                    consecutiveEmptyPages = 0;
                }

                boolean hasNextPage = isNextPageAvailable(containerScreen);
                boolean stopConditionMet;
                if (totalQualifying == 0) {
                    stopConditionMet = false;
                } else {
                    stopConditionMet = (totalQualifying >= 3) || (consecutiveEmptyPages >= 5);
                }

                boolean shouldContinuePaging = hasNextPage && pagesScanned < MAX_PAGE_LIMIT && !stopConditionMet;

                if (shouldContinuePaging) {
                    pagesScanned++;
                    lastActionTime = now;
                    int containerId = containerScreen.getMenu().containerId;
                    mc.gameMode.handleInventoryMouseClick(containerId, 53, 0, ClickType.PICKUP, mc.player);
                    LOGGER.info("[Best Deal Finder] Paging forward for /ah {} (Page {}, Qualifying Matches: {})...",
                            currentQuery, pagesScanned + 1, totalQualifying);
                } else {
                    LOGGER.info("[Best Deal Finder] Finishing scan after {} pages. Total Matches: {}, Qualifying Matches: {}",
                            pagesScanned + 1, allMatchesFound.size(), totalQualifying);
                    finishSearch(mc);
                }
            }
        } else if (currentState == State.NAVIGATING_TO_BUY) {
            if (currentPurchaseDeal == null) {
                running = false;
                currentState = State.IDLE;
                return;
            }

            if (mc.screen instanceof AbstractContainerScreen<?> containerScreen) {
                long delayRequired = (pagesScanned == 0) ? INITIAL_COMMAND_DELAY_MS : PAGE_TURN_DELAY_MS;
                if (now - lastActionTime < delayRequired) return;

                int currentPage = pagesScanned + 1;
                if (currentPage < currentPurchaseDeal.pageNumber) {
                    if (isNextPageAvailable(containerScreen)) {
                        pagesScanned++;
                        lastActionTime = now;
                        int containerId = containerScreen.getMenu().containerId;
                        mc.gameMode.handleInventoryMouseClick(containerId, 53, 0, ClickType.PICKUP, mc.player);
                        return;
                    } else {
                        LOGGER.warn("[Best Deal Finder] Target Page {} unreachable, aborting remaining queue!", currentPurchaseDeal.pageNumber);
                        abortPurchaseQueue(mc, "Target page unreachable");
                        return;
                    }
                }

                // Execute slot re-verification & purchase click
                boolean success = executePurchaseClick(mc, containerScreen, currentPurchaseDeal);
                if (success) {
                    successfulPurchasesCount++;
                    lastActionTime = now;
                    processNextPurchaseQueueStep(mc);
                } else {
                    abortPurchaseQueue(mc, "Listing details changed or item unavailable");
                }
            }
        }
    }

    private static int scanCurrentContainer(AbstractContainerScreen<?> containerScreen) {
        if (containerScreen.getMenu() == null) return 0;
        List<Slot> slots = containerScreen.getMenu().slots;
        if (slots.size() < 45) return 0;

        int nonCount = 0;
        for (int i = 0; i < Math.min(45, slots.size()); i++) {
            if (!slots.get(i).getItem().isEmpty()) nonCount++;
        }
        if (nonCount == 0) return 0;

        int newQualifyingCount = 0;
        String cleanQuery = currentQuery.replace(" ", "_");

        for (int i = 0; i < Math.min(45, slots.size()); i++) {
            ItemStack stack = slots.get(i).getItem();
            if (stack.isEmpty()) continue;

            Identifier loc = BuiltInRegistries.ITEM.getKey(stack.getItem());
            String path = (loc != null) ? loc.getPath().toLowerCase() : "";
            String name = stack.getHoverName().getString().trim().toLowerCase();

            // Match check against query
            if (!path.contains(cleanQuery) && !cleanQuery.contains(path) && !name.contains(currentQuery) && !currentQuery.contains(name)) {
                continue;
            }

            double totalPrice = parsePriceFromStack(stack);
            if (totalPrice <= 0) continue;

            int quantity = stack.getCount();
            double pricePerUnit = totalPrice / (double) quantity;
            boolean isQualifying = quantity >= targetQuantity;

            BestDealResult deal = new BestDealResult(currentQuery, stack.getHoverName().getString().trim(),
                    path, pricePerUnit, totalPrice, quantity, pagesScanned + 1, i, isQualifying, targetQuantity, stack);

            allMatchesFound.add(deal);
            if (isQualifying) {
                qualifyingMatchesFound.add(deal);
                newQualifyingCount++;
            }
        }

        return newQualifyingCount;
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

    private static void finishSearch(Minecraft mc) {
        running = false;
        currentState = State.IDLE;

        if (allMatchesFound.isEmpty()) {
            LOGGER.warn("[Best Deal Finder] Zero matches found for item '{}'", currentQuery);
            latestBundle = null;
            if (mc.player != null) {
                mc.player.displayClientMessage(Component.literal("§c[Best Deal Finder] No listings found for '" + currentQuery + "'."), true);
            }
            mc.execute(() -> {
                ProfitDetailsScreen screen = new ProfitDetailsScreen();
                mc.setScreen(screen);
                screen.openBestDealFinderModal("⚠ No listings found for item");
            });
            return;
        }

        // 1. Calculate Median Price Per Unit
        List<Double> unitPrices = new ArrayList<>();
        for (BestDealResult r : allMatchesFound) {
            unitPrices.add(r.pricePerUnit);
        }
        Collections.sort(unitPrices);
        double medianUnitPrice;
        int n = unitPrices.size();
        if (n % 2 == 1) {
            medianUnitPrice = unitPrices.get(n / 2);
        } else {
            medianUnitPrice = (unitPrices.get(n / 2 - 1) + unitPrices.get(n / 2)) / 2.0;
        }

        double scamThreshold = medianUnitPrice * 5.0; // Scam Multiplier = 5.0

        // 2. Anti-Scam Filter
        List<BestDealResult> cleanListings = new ArrayList<>();
        for (BestDealResult r : allMatchesFound) {
            if (r.pricePerUnit <= scamThreshold) {
                cleanListings.add(r);
            } else {
                LOGGER.info("[Best Deal Finder] Filtered out scam listing: {} x{} @ ${}/ea (Baseline median: ${})",
                        r.matchedItemName, r.quantity, String.format("%.2f", r.pricePerUnit), String.format("%.2f", medianUnitPrice));
            }
        }

        if (cleanListings.isEmpty()) {
            cleanListings = allMatchesFound; // Fallback if all were flagged
        }

        // Sort clean listings ascending by unit price
        cleanListings.sort((a, b) -> Double.compare(a.pricePerUnit, b.pricePerUnit));

        // 3. Single-stack cheapest qualifying listing
        BestDealResult bestSingleQualifying = null;
        for (BestDealResult r : cleanListings) {
            if (r.quantity >= targetQuantity) {
                if (bestSingleQualifying == null || r.pricePerUnit < bestSingleQualifying.pricePerUnit) {
                    bestSingleQualifying = r;
                }
            }
        }

        // 4. Greedy Combination Bundle Solver
        List<BestDealResult> greedyListings = new ArrayList<>();
        int accumulatedQty = 0;
        double accumulatedCost = 0.0;

        for (BestDealResult r : cleanListings) {
            greedyListings.add(r);
            accumulatedQty += r.quantity;
            accumulatedCost += r.totalPrice;
            if (accumulatedQty >= targetQuantity) {
                break;
            }
        }

        double greedyEffectiveUnit = accumulatedQty > 0 ? (accumulatedCost / (double) accumulatedQty) : 0.0;
        boolean greedyQualifying = accumulatedQty >= targetQuantity;

        BestDealBundle bestBundle;

        // 5. Compare Single-Stack vs Greedy Combination
        if (bestSingleQualifying != null && bestSingleQualifying.totalPrice <= accumulatedCost) {
            // Single listing is cheaper or equal
            List<BestDealResult> singleList = Collections.singletonList(bestSingleQualifying);
            bestBundle = new BestDealBundle(currentQuery, bestSingleQualifying.matchedItemName, singleList,
                    bestSingleQualifying.totalPrice, bestSingleQualifying.quantity, bestSingleQualifying.pricePerUnit,
                    true, targetQuantity);
        } else {
            // Greedy combination bundle is cheaper
            String name = greedyListings.get(0).matchedItemName;
            bestBundle = new BestDealBundle(currentQuery, name, greedyListings,
                    accumulatedCost, accumulatedQty, greedyEffectiveUnit,
                    greedyQualifying, targetQuantity);
        }

        latestBundle = bestBundle;

        boolean capExceeded = (maxPriceCap > 0 && bestBundle.effectivePricePerUnit > maxPriceCap);

        LOGGER.info("[Best Deal Finder] Search Complete:");
        LOGGER.info("  Item Name        : {}", bestBundle.itemName);
        LOGGER.info("  Summary          : {}", bestBundle.getSummaryText());
        LOGGER.info("  Effective $/Unit : ${}", String.format("%.2f", bestBundle.effectivePricePerUnit));
        LOGGER.info("  Combined Cost    : ${}", String.format("%.2f", bestBundle.combinedTotalCost));
        LOGGER.info("  Combined Qty     : {} (Requested: {})", bestBundle.combinedTotalQty, bestBundle.requestedQty);
        LOGGER.info("  Is Qualifying    : {}", bestBundle.isQualifying);
        LOGGER.info("  Max Price Cap    : ${} (Cap Exceeded: {})", maxPriceCap > 0 ? DEC_FMT.format(maxPriceCap) : "None", capExceeded);
        LOGGER.info("  Auto Buy         : {}", autoBuyEnabled);

        if (capExceeded || !autoBuyEnabled) {
            // Require manual user confirmation modal!
            mc.execute(() -> {
                ProfitDetailsScreen screen = new ProfitDetailsScreen();
                mc.setScreen(screen);
                screen.openBestDealConfirmationModal(bestBundle, capExceeded);
            });
        } else {
            // Auto buy immediately!
            executeBuySequence(mc, bestBundle);
        }
    }

    public static void confirmAndBuyBundle(BestDealBundle bundle) {
        Minecraft mc = Minecraft.getInstance();
        if (bundle == null || bundle.listings.isEmpty() || mc.player == null) return;
        executeBuySequence(mc, bundle);
    }

    private static void executeBuySequence(Minecraft mc, BestDealBundle bundle) {
        purchaseQueue.clear();

        List<BestDealResult> sortedListings = new ArrayList<>(bundle.listings);
        sortedListings.sort((a, b) -> {
            if (a.pageNumber != b.pageNumber) return Integer.compare(a.pageNumber, b.pageNumber);
            return Integer.compare(a.slotIndex, b.slotIndex);
        });

        purchaseQueue.addAll(sortedListings);
        totalPurchasesInChain = purchaseQueue.size();
        successfulPurchasesCount = 0;
        currentPurchaseDeal = null;

        running = true;
        currentState = State.NAVIGATING_TO_BUY;
        lastActionTime = System.currentTimeMillis();

        if (mc.screen != null) {
            mc.player.closeContainer();
        }

        processNextPurchaseQueueStep(mc);
    }

    private static void processNextPurchaseQueueStep(Minecraft mc) {
        if (purchaseQueue.isEmpty()) {
            running = false;
            currentState = State.IDLE;
            currentPurchaseDeal = null;
            if (mc.player != null) {
                mc.player.displayClientMessage(Component.literal("§a[Best Deal Finder] Completed " + successfulPurchasesCount + "/" + totalPurchasesInChain + " purchases in bundle!"), true);
            }
            return;
        }

        currentPurchaseDeal = purchaseQueue.poll();
        pagesScanned = 0; // reset pagesScanned for navigation
        lastActionTime = System.currentTimeMillis();

        String command = "ah " + currentPurchaseDeal.targetQuery;
        LOGGER.info("[Best Deal Finder] Queue Step {}/{}: Navigating to /{} (Page {}, Slot {})",
                successfulPurchasesCount + 1, totalPurchasesInChain, command, currentPurchaseDeal.pageNumber, currentPurchaseDeal.slotIndex);

        if (mc.player.connection != null) {
            mc.player.connection.sendCommand(command);
        }
    }

    private static void abortPurchaseQueue(Minecraft mc, String reason) {
        LOGGER.warn("[Best Deal Finder] Multi-buy chain ABORTED after {}/{} purchases. Reason: {}",
                successfulPurchasesCount, totalPurchasesInChain, reason);
        purchaseQueue.clear();
        currentPurchaseDeal = null;
        running = false;
        currentState = State.IDLE;
        if (mc.player != null) {
            mc.player.displayClientMessage(Component.literal("§c[Best Deal Finder] Multi-buy aborted (" + successfulPurchasesCount + "/" + totalPurchasesInChain + " completed): " + reason), true);
        }
    }

    private static boolean executePurchaseClick(Minecraft mc, AbstractContainerScreen<?> containerScreen, BestDealResult deal) {
        if (containerScreen.getMenu() == null) return false;
        List<Slot> slots = containerScreen.getMenu().slots;

        if (deal.slotIndex < 0 || deal.slotIndex >= slots.size()) {
            LOGGER.warn("[Best Deal Finder] Slot index {} out of bounds, purchase aborted!", deal.slotIndex);
            return false;
        }

        ItemStack currentStack = slots.get(deal.slotIndex).getItem();

        // Safety Re-verification before purchase click
        if (currentStack.isEmpty()) {
            LOGGER.warn("[Best Deal Finder] Slot {} is empty, listing no longer available. Purchase aborted!", deal.slotIndex);
            return false;
        }

        Identifier loc = BuiltInRegistries.ITEM.getKey(currentStack.getItem());
        String currentPath = (loc != null) ? loc.getPath().toLowerCase() : "";
        double currentPrice = parsePriceFromStack(currentStack);

        if (!currentPath.equals(deal.itemPath) || currentStack.getCount() != deal.quantity || Math.abs(currentPrice - deal.totalPrice) > 0.01) {
            LOGGER.warn("[Best Deal Finder] Listing at slot {} modified (expected: {} x{} @ ${}, found: {} x{} @ ${}), purchase aborted!",
                    deal.slotIndex, deal.itemPath, deal.quantity, deal.totalPrice, currentPath, currentStack.getCount(), currentPrice);
            return false;
        }

        // Execution of purchase click
        int containerId = containerScreen.getMenu().containerId;
        mc.gameMode.handleInventoryMouseClick(containerId, deal.slotIndex, 0, ClickType.PICKUP, mc.player);

        LOGGER.info("[Best Deal Finder] AUDIT LOG - Purchase Executed ({}/{}): {} x{} for Total ${} (${}/ea) at Page {}, Slot {}",
                successfulPurchasesCount + 1, totalPurchasesInChain, deal.matchedItemName, deal.quantity,
                String.format("%.2f", deal.totalPrice), String.format("%.2f", deal.pricePerUnit), deal.pageNumber, deal.slotIndex);

        return true;
    }

    private static double parsePriceFromStack(ItemStack stack) {
        if (stack.isEmpty()) return 0.0;
        Pattern pricePattern = Pattern.compile("\\$\\s*([0-9.,]+(?:e[+-]?[0-9]+)?\\s*[kmbKMB]?)", Pattern.CASE_INSENSITIVE);

        try {
            var loreComponent = stack.get(net.minecraft.core.component.DataComponents.LORE);
            if (loreComponent != null) {
                for (Component comp : loreComponent.lines()) {
                    Matcher m = pricePattern.matcher(comp.getString());
                    if (m.find()) {
                        return parseFormattedMoney(m.group(1));
                    }
                }
            }
        } catch (Exception ignored) {}

        try {
            List<Component> tooltip = stack.getTooltipLines(
                    net.minecraft.world.item.Item.TooltipContext.EMPTY,
                    Minecraft.getInstance().player,
                    net.minecraft.world.item.TooltipFlag.NORMAL
            );
            for (Component lineComponent : tooltip) {
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
