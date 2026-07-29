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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AutoFlipCalcHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger("donut-smp-profit-calc/AutoFlip");
    private static final DecimalFormat DEC_FMT = new DecimalFormat("#,##0.##");

    private static final long INITIAL_COMMAND_DELAY_MS = 600;
    private static final long PAGE_TURN_DELAY_MS = 300;
    private static final long TIMEOUT_LIMIT_MS = 15000;
    private static final int MAX_PAGE_LIMIT = 20;

    public enum FlipMode {
        BONE,
        KELP,
        OAK_LOG,
        STICKY_PISTON,
        GOLDEN_APPLE,
        BOOKSHELF,
        TRAPDOOR
    }

    public static class FlipProfitResult {
        public final FlipMode mode;
        public final String displayName;
        public final double profit;
        public final double marginPct;
        public final boolean hasData;
        public final String detailText;

        public FlipProfitResult(FlipMode mode, String displayName, double profit, double marginPct, boolean hasData, String detailText) {
            this.mode = mode;
            this.displayName = displayName;
            this.profit = profit;
            this.marginPct = marginPct;
            this.hasData = hasData;
            this.detailText = detailText;
        }
    }

    public static class Listing {
        public final double price;
        public final double quantity;
        public Listing(double price, double quantity) {
            this.price = price;
            this.quantity = quantity;
        }
        @Override
        public String toString() {
            return String.format("$%.2f (qty: %.0f)", price, quantity);
        }
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

    private static boolean batchModeActive = false;
    private static final List<FlipMode> batchModesList = new ArrayList<>();
    private static int batchCurrentIndex = 0;
    private static Runnable batchOnCompleteCallback = null;

    private static ScanTask currentTask = null;
    private static int pagesScanned = 0;
    private static int consecutiveEmptyPages = 0;
    private static long lastActionTime = 0;

    private static final Map<String, List<Listing>> accumulatedListings = new HashMap<>();
    private static final Map<String, Double> finalComputedPrices = new HashMap<>();

    public static void stop() {
        running = false;
        batchModeActive = false;
        taskQueue.clear();
        batchModesList.clear();
        batchCurrentIndex = 0;
        batchOnCompleteCallback = null;
        currentTask = null;
        pagesScanned = 0;
        consecutiveEmptyPages = 0;
        accumulatedListings.clear();
        finalComputedPrices.clear();
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.player != null) {
            mc.player.displayClientMessage(net.minecraft.network.chat.Component.literal("§e[Auto Flip] Cancelled."), true);
        }
    }

    public static boolean isRunning() {
        return running;
    }

    public static boolean isBatchModeActive() {
        return batchModeActive;
    }

    public static int getBatchProgressIndex() {
        return batchCurrentIndex;
    }

    public static int getBatchTotalCount() {
        return batchModesList.size();
    }

    public static void startBatch(List<FlipMode> modes, Runnable onAllComplete) {
        Minecraft mc = Minecraft.getInstance();
        if (running) {
            if (mc != null && mc.player != null) {
                mc.player.displayClientMessage(net.minecraft.network.chat.Component.literal("§c[Auto Flip] A scan is already in progress!"), true);
            }
            return;
        }

        batchModeActive = true;
        batchModesList.clear();
        batchModesList.addAll(modes);
        batchCurrentIndex = 0;
        batchOnCompleteCallback = onAllComplete;

        startNextBatchStep(mc);
    }

    private static void startNextBatchStep(Minecraft mc) {
        if (batchCurrentIndex >= batchModesList.size()) {
            batchModeActive = false;
            running = false;
            if (mc != null && mc.player != null) {
                mc.player.displayClientMessage(net.minecraft.network.chat.Component.literal("§a[Auto Flip] Check All Flips complete!"), true);
            }
            mc.execute(() -> {
                ProfitDetailsScreen screen = new ProfitDetailsScreen();
                mc.setScreen(screen);
                screen.openCheckAllResultsModal();
            });
            if (batchOnCompleteCallback != null) {
                mc.execute(batchOnCompleteCallback);
            }
            return;
        }

        FlipMode nextMode = batchModesList.get(batchCurrentIndex);
        if (mc != null && mc.player != null) {
            mc.player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                    String.format("§a[Auto Flip] Checking All Flips... (%d/%d) %s",
                            batchCurrentIndex + 1, batchModesList.size(), nextMode.name())), true);
        }

        startInternal(nextMode);
    }

    public static void start() {
        start(FlipMode.BONE);
    }

    public static void start(FlipMode mode) {
        batchModeActive = false;
        batchModesList.clear();
        batchCurrentIndex = 0;
        batchOnCompleteCallback = null;
        startInternal(mode);
    }

    private static void startInternal(FlipMode mode) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        activeMode = mode;
        taskQueue.clear();
        currentTask = null;
        pagesScanned = 0;
        consecutiveEmptyPages = 0;
        lastActionTime = System.currentTimeMillis();

        accumulatedListings.clear();
        finalComputedPrices.clear();

        switch (mode) {
            case BONE:
                taskQueue.add(new ScanTask("order bone", "bone"));
                taskQueue.add(new ScanTask("order bone_block", "bone_block"));
                break;
            case KELP:
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
            case TRAPDOOR:
                taskQueue.add(new ScanTask("order oak log", "oak_log"));
                taskQueue.add(new ScanTask("ah trapdoor", "ah_trapdoor_64"));
                taskQueue.add(new ScanTask("ah trapdoor", "ah_trapdoor_page1"));
                break;
        }

        running = true;

        if (mc.screen != null) {
            mc.player.closeContainer();
        }

        if (!batchModeActive) {
            mc.player.displayClientMessage(net.minecraft.network.chat.Component.literal("§a[Auto Flip] Auto checking prices for " + mode.name() + "..."), true);
        }
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
            consecutiveEmptyPages = 0;
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
            processAndStoreTaskResults(currentTask.targetItemKey);
            currentTask = null;
            lastActionTime = now + 200;
            return;
        }

        if (mc.screen instanceof AbstractContainerScreen<?> containerScreen) {
            long delayRequired = (pagesScanned == 0) ? INITIAL_COMMAND_DELAY_MS : PAGE_TURN_DELAY_MS;
            if (now - lastActionTime < delayRequired) return;

            // Page 1 ceiling task: scan Page 1 ONLY, no pagination!
            if (currentTask.targetItemKey.equals("ah_trapdoor_page1")) {
                scanCurrentContainer(containerScreen, currentTask.targetItemKey);
                processAndStoreTaskResults(currentTask.targetItemKey);
                currentTask = null;
                lastActionTime = System.currentTimeMillis();
                return;
            }

            // Scan current page
            int matchesOnThisPage = scanCurrentContainer(containerScreen, currentTask.targetItemKey);
            int totalMatchesForTask = getAccumulatedMatchCount(currentTask.targetItemKey);

            if (totalMatchesForTask > 0) {
                if (matchesOnThisPage == 0) {
                    consecutiveEmptyPages++;
                } else {
                    consecutiveEmptyPages = 0;
                }
            } else {
                consecutiveEmptyPages = 0;
            }

            boolean hasNextPage = isNextPageAvailable(containerScreen);

            // Cap for 64x trapdoor scan is 9 matches (instead of 3)
            int matchTargetCap = currentTask.targetItemKey.equals("ah_trapdoor_64") ? 9 : 3;

            boolean stopConditionMet;
            if (totalMatchesForTask == 0) {
                stopConditionMet = false;
            } else {
                stopConditionMet = (totalMatchesForTask >= matchTargetCap) || (consecutiveEmptyPages >= 5);
            }

            boolean shouldContinuePaging = hasNextPage && pagesScanned < MAX_PAGE_LIMIT && !stopConditionMet;

            if (shouldContinuePaging) {
                pagesScanned++;
                lastActionTime = now;
                int containerId = containerScreen.getMenu().containerId;
                mc.gameMode.handleInventoryMouseClick(containerId, 53, 0, ClickType.PICKUP, mc.player);
                LOGGER.info("[Auto Flip] Paging forward for /{} (Page {}, Total Matches: {}, Empty Pages After Match: {})...",
                        currentTask.command, pagesScanned + 1, totalMatchesForTask, consecutiveEmptyPages);
            } else {
                LOGGER.info("[Auto Flip] Finishing scan for /{} after {} pages. Total Matches: {}, Empty Pages After Match: {}",
                        currentTask.command, pagesScanned + 1, totalMatchesForTask, consecutiveEmptyPages);
                processAndStoreTaskResults(currentTask.targetItemKey);
                currentTask = null;
                lastActionTime = System.currentTimeMillis();
            }
        }
    }

    private static int getAccumulatedMatchCount(String targetKey) {
        if (targetKey.equals("kelp")) {
            int raw = accumulatedListings.getOrDefault("raw_kelp", Collections.emptyList()).size();
            int dried = accumulatedListings.getOrDefault("dried_kelp", Collections.emptyList()).size();
            int block = accumulatedListings.getOrDefault("dried_kelp_block", Collections.emptyList()).size();
            return Math.max(raw, Math.max(dried, block));
        }
        if (targetKey.equals("ah_trapdoor_64")) {
            return accumulatedListings.getOrDefault("ah_trapdoor_64", Collections.emptyList()).size();
        }
        if (targetKey.equals("ah_trapdoor_page1")) {
            return accumulatedListings.getOrDefault("ah_trapdoor_page1", Collections.emptyList()).size();
        }
        return accumulatedListings.getOrDefault(targetKey, Collections.emptyList()).size();
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

    private static int scanCurrentContainer(AbstractContainerScreen<?> containerScreen, String targetKey) {
        if (containerScreen.getMenu() == null) return 0;
        List<Slot> slots = containerScreen.getMenu().slots;
        if (slots.size() < 45) return 0;

        int newMatchesCount = 0;

        for (int i = 0; i < Math.min(45, slots.size()); i++) {
            ItemStack stack = slots.get(i).getItem();
            if (stack.isEmpty()) continue;

            Identifier loc = BuiltInRegistries.ITEM.getKey(stack.getItem());
            String path = (loc != null) ? loc.getPath().toLowerCase() : "";
            String name = stack.getHoverName().getString().trim().toLowerCase();

            double price = parsePriceFromStack(stack);
            if (price <= 0) continue;

            // Part 1: For AH listings, read stack.getCount() directly (not lore parser)
            double quantity = stack.getCount();

            String matchKey = null;

            if (targetKey.equals("ah_trapdoor_64")) {
                if ((path.contains("trapdoor") || name.contains("trapdoor")) && stack.getCount() == 64) {
                    matchKey = "ah_trapdoor_64";
                    double unitPrice = price / 64.0;
                    accumulatedListings.computeIfAbsent(matchKey, k -> new ArrayList<>()).add(new Listing(unitPrice, 64.0));
                    newMatchesCount++;
                }
                continue;
            } else if (targetKey.equals("ah_trapdoor_page1")) {
                if (path.contains("trapdoor") || name.contains("trapdoor")) {
                    matchKey = "ah_trapdoor_page1";
                    accumulatedListings.computeIfAbsent(matchKey, k -> new ArrayList<>()).add(new Listing(price, quantity));
                    newMatchesCount++;
                }
                continue;
            }

            // Standard order listings quantity parsing
            quantity = parseQuantityFromStack(stack);

            if (targetKey.equals("bone")) {
                if (path.equals("bone") || name.equalsIgnoreCase("bone")) {
                    matchKey = "bone";
                }
            } else if (targetKey.equals("bone_block")) {
                if (path.equals("bone_block") || name.contains("bone block")) {
                    matchKey = "bone_block";
                }
            } else if (targetKey.equals("kelp")) {
                if (path.equals("dried_kelp_block") || name.contains("dried kelp block")) {
                    matchKey = "dried_kelp_block";
                } else if (path.equals("dried_kelp") || (name.contains("dried") && name.contains("kelp") && !name.contains("block"))) {
                    matchKey = "dried_kelp";
                } else if ((path.equals("kelp") || name.contains("kelp")) && !name.contains("dried")) {
                    matchKey = "raw_kelp";
                }
            } else if (targetKey.equals("charcoal")) {
                if (path.equals("charcoal") || name.contains("charcoal")) {
                    matchKey = "charcoal";
                }
            } else if (targetKey.equals("oak_log")) {
                if (path.equals("oak_log") || name.contains("oak log")) {
                    matchKey = "oak_log";
                }
            } else if (targetKey.equals("oak_planks")) {
                if (path.equals("oak_planks") || name.contains("oak planks")) {
                    matchKey = "oak_planks";
                }
            } else if (targetKey.equals("piston")) {
                if (path.equals("piston") || name.contains("piston")) {
                    matchKey = "piston";
                }
            } else if (targetKey.equals("slimeball")) {
                if (path.equals("slime_ball") || path.equals("slimeball") || name.contains("slimeball") || name.contains("slime ball")) {
                    matchKey = "slimeball";
                }
            } else if (targetKey.equals("sticky_piston")) {
                if (path.equals("sticky_piston") || name.contains("sticky piston")) {
                    matchKey = "sticky_piston";
                }
            } else if (targetKey.equals("gold_ingot")) {
                if (path.equals("gold_ingot") || name.contains("gold ingot")) {
                    matchKey = "gold_ingot";
                }
            } else if (targetKey.equals("apple")) {
                if (path.equals("apple") || name.equals("apple")) {
                    matchKey = "apple";
                }
            } else if (targetKey.equals("golden_apple")) {
                if (path.equals("golden_apple") || name.contains("golden apple")) {
                    matchKey = "golden_apple";
                }
            } else if (targetKey.equals("book")) {
                if (path.equals("book") || name.equals("book")) {
                    matchKey = "book";
                }
            } else if (targetKey.equals("bookshelf")) {
                if (path.equals("bookshelf") || name.contains("bookshelf")) {
                    matchKey = "bookshelf";
                }
            }

            if (matchKey != null) {
                accumulatedListings.computeIfAbsent(matchKey, k -> new ArrayList<>()).add(new Listing(price, quantity));
                newMatchesCount++;
            }
        }

        return newMatchesCount;
    }

    private static void processAndStoreTaskResults(String targetKey) {
        if (targetKey.equals("ah_trapdoor_64")) {
            List<Listing> matches = accumulatedListings.getOrDefault("ah_trapdoor_64", Collections.emptyList());
            if (matches.size() < 3) {
                LOGGER.warn("[Auto Flip] Insufficient data for 64x Trapdoor Baseline: found {} matches (minimum 3 required)", matches.size());
                finalComputedPrices.put("ah_trapdoor_64", -1.0);
            } else {
                double sum = 0.0;
                for (Listing l : matches) {
                    sum += l.price;
                }
                double avgUnitBaseline = sum / matches.size();
                finalComputedPrices.put("ah_trapdoor_64", avgUnitBaseline);
                LOGGER.info("[Auto Flip] Summary for 64x Trapdoor Baseline:");
                LOGGER.info("  Matched 64x Listings ({}) : {}", matches.size(), matches);
                LOGGER.info("  Baseline Price/Unit       : ${}", String.format("%.2f", avgUnitBaseline));
            }
            return;
        } else if (targetKey.equals("ah_trapdoor_page1")) {
            List<Listing> matches = accumulatedListings.getOrDefault("ah_trapdoor_page1", Collections.emptyList());
            double maxCeiling = 0.0;
            for (Listing l : matches) {
                maxCeiling = Math.max(maxCeiling, l.price);
            }
            finalComputedPrices.put("ah_trapdoor_page1", maxCeiling);
            LOGGER.info("[Auto Flip] Summary for Trapdoor Page 1 Ceiling:");
            LOGGER.info("  Page 1 Listings ({})      : {}", matches.size(), matches);
            LOGGER.info("  Page 1 Max Ceiling Total  : ${}", String.format("%.2f", maxCeiling));
            return;
        }

        List<String> keysToProcess = new ArrayList<>();
        if (targetKey.equals("kelp")) {
            keysToProcess.add("raw_kelp");
            keysToProcess.add("dried_kelp");
            keysToProcess.add("dried_kelp_block");
        } else {
            keysToProcess.add(targetKey);
        }

        for (String key : keysToProcess) {
            List<Listing> rawListings = accumulatedListings.getOrDefault(key, Collections.emptyList());
            if (rawListings.isEmpty()) {
                LOGGER.warn("[Auto Flip] Zero matches found for key '{}'", key);
                continue;
            }

            // 1. Calculate Median Quantity
            List<Double> quantities = new ArrayList<>();
            for (Listing l : rawListings) {
                quantities.add(l.quantity);
            }
            Collections.sort(quantities);
            double medianQty;
            int n = quantities.size();
            if (n % 2 == 1) {
                medianQty = quantities.get(n / 2);
            } else {
                medianQty = (quantities.get(n / 2 - 1) + quantities.get(n / 2)) / 2.0;
            }

            double cutoffQty = 0.25 * medianQty;

            // 2. Filter listings below 25% of median quantity
            List<Listing> filteredListings = new ArrayList<>();
            List<Listing> outlierListings = new ArrayList<>();

            for (Listing l : rawListings) {
                if (l.quantity >= cutoffQty) {
                    filteredListings.add(l);
                } else {
                    outlierListings.add(l);
                }
            }

            if (filteredListings.isEmpty()) {
                filteredListings = rawListings;
                outlierListings.clear();
            }

            // 3. Sort DESCENDING by price (highest buy order prices first)
            filteredListings.sort((a, b) -> Double.compare(b.price, a.price));

            int top3Count = Math.min(3, filteredListings.size());
            double sumTop3 = 0.0;
            List<Listing> top3List = new ArrayList<>();
            for (int i = 0; i < top3Count; i++) {
                Listing l = filteredListings.get(i);
                top3List.add(l);
                sumTop3 += l.price;
            }
            double avgTop3 = sumTop3 / top3Count;
            finalComputedPrices.put(key, avgTop3);

            // Comprehensive logging
            LOGGER.info("[Auto Flip] Summary for key '{}':", key);
            LOGGER.info("  Raw Matches ({})       : {}", rawListings.size(), rawListings);
            LOGGER.info("  Median Qty            : {} (Cutoff >= {})", medianQty, cutoffQty);
            LOGGER.info("  Outliers Dropped ({})  : {}", outlierListings.size(), outlierListings);
            LOGGER.info("  Filtered Set ({})      : {}", filteredListings.size(), filteredListings);
            LOGGER.info("  Sort Order            : DESCENDING (Highest Buy Orders First)");
            LOGGER.info("  Top 3 Used (HIGHEST)  : {}", top3List);
            LOGGER.info("  Final Top-3 Avg Price : ${}", String.format("%.2f", avgTop3));
        }
    }

    private static void finishAutoScan(Minecraft mc) {
        currentTask = null;
        ProfitConfig config = ProfitConfig.getInstance();

        Double boneP = finalComputedPrices.get("bone");
        Double blockP = finalComputedPrices.get("bone_block");

        Double rawKelpP = finalComputedPrices.get("raw_kelp");
        Double driedKelpP = finalComputedPrices.get("dried_kelp");
        Double driedKelpBlockP = finalComputedPrices.get("dried_kelp_block");
        Double charcoalP = finalComputedPrices.get("charcoal");

        Double oakLogP = finalComputedPrices.get("oak_log");
        Double oakPlanksP = finalComputedPrices.get("oak_planks");

        Double pistonP = finalComputedPrices.get("piston");
        Double slimeballP = finalComputedPrices.get("slimeball");
        Double stickyPistonP = finalComputedPrices.get("sticky_piston");

        Double goldIngotP = finalComputedPrices.get("gold_ingot");
        Double appleP = finalComputedPrices.get("apple");
        Double gappleP = finalComputedPrices.get("golden_apple");

        Double bookP = finalComputedPrices.get("book");
        Double bookshelfP = finalComputedPrices.get("bookshelf");

        Double trapdoorBaselineP = finalComputedPrices.get("ah_trapdoor_64");
        Double trapdoorCeilingP = finalComputedPrices.get("ah_trapdoor_page1");

        if (boneP != null && boneP > 0) config.setSavedBonePrice(DEC_FMT.format(boneP));
        if (blockP != null && blockP > 0) config.setSavedBlockPrice(DEC_FMT.format(blockP));

        if (rawKelpP != null && rawKelpP > 0) config.setSavedRawKelpPrice(DEC_FMT.format(rawKelpP));
        if (driedKelpBlockP != null && driedKelpBlockP > 0) config.setSavedDriedKelpPrice(DEC_FMT.format(driedKelpBlockP));
        if (charcoalP != null && charcoalP > 0) config.setSavedCharcoalPrice(DEC_FMT.format(charcoalP));

        if (oakLogP != null && oakLogP > 0) config.setSavedOakLogPrice(DEC_FMT.format(oakLogP));
        if (oakPlanksP != null && oakPlanksP > 0) config.setSavedOakPlanksPrice(DEC_FMT.format(oakPlanksP));

        if (pistonP != null && pistonP > 0) config.setSavedPistonPrice(DEC_FMT.format(pistonP));
        if (slimeballP != null && slimeballP > 0) config.setSavedSlimeballPrice(DEC_FMT.format(slimeballP));
        if (stickyPistonP != null && stickyPistonP > 0) config.setSavedStickyPistonPrice(DEC_FMT.format(stickyPistonP));

        if (goldIngotP != null && goldIngotP > 0) config.setSavedGoldIngotPrice(DEC_FMT.format(goldIngotP));
        if (appleP != null && appleP > 0) config.setSavedApplePrice(DEC_FMT.format(appleP));
        if (gappleP != null && gappleP > 0) config.setSavedGapplePrice(DEC_FMT.format(gappleP));

        if (bookP != null && bookP > 0) config.setSavedBookPrice(DEC_FMT.format(bookP));
        if (bookshelfP != null && bookshelfP > 0) config.setSavedBookshelfPrice(DEC_FMT.format(bookshelfP));

        if (trapdoorBaselineP != null && trapdoorBaselineP > 0) {
            config.setSavedTrapdoorBaselinePrice(DEC_FMT.format(trapdoorBaselineP));
            
            double offset = parseDouble(config.getSavedTrapdoorOffset(), 100.0);
            double askPricePerUnit = Math.ceil(trapdoorBaselineP) + offset;
            double ceiling = (trapdoorCeilingP != null && trapdoorCeilingP > 0) ? trapdoorCeilingP : 0.0;
            
            int[] STACK_SIZES = {4, 8, 12, 16, 24};
            int suggestedIdx = 0;
            int suggestedSize = 4;
            
            if (ceiling > 0) {
                for (int i = 0; i < STACK_SIZES.length; i++) {
                    if (askPricePerUnit * STACK_SIZES[i] < ceiling) {
                        suggestedIdx = i;
                        suggestedSize = STACK_SIZES[i];
                    }
                }
            } else {
                suggestedIdx = 3;
                suggestedSize = 16;
            }

            config.setSavedTrapdoorStackSizeIndex(suggestedIdx);
            LOGGER.info("[Auto Flip] Suggested stack size: {}x (Total Ask: ${}, Ceiling: ${})",
                    suggestedSize, String.format("%.2f", askPricePerUnit * suggestedSize), String.format("%.2f", ceiling));
        } else {
            config.setSavedTrapdoorBaselinePrice("-1");
        }

        if (trapdoorCeilingP != null && trapdoorCeilingP > 0) {
            config.setSavedTrapdoorPage1Ceiling(DEC_FMT.format(trapdoorCeilingP));
        } else {
            config.setSavedTrapdoorPage1Ceiling("0");
        }

        if (batchModeActive) {
            batchCurrentIndex++;
            startNextBatchStep(mc);
            return;
        }

        running = false;

        int targetTab = 0;
        switch (activeMode) {
            case BONE: targetTab = 0; break;
            case KELP: targetTab = 1; break;
            case OAK_LOG: targetTab = 2; break;
            case STICKY_PISTON: targetTab = 3; break;
            case GOLDEN_APPLE: targetTab = 4; break;
            case BOOKSHELF: targetTab = 5; break;
            case TRAPDOOR: targetTab = 6; break;
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

    public static FlipProfitResult computeProfitForMode(FlipMode mode) {
        ProfitConfig config = ProfitConfig.getInstance();
        String name = switch (mode) {
            case BONE -> "Bone Flip";
            case KELP -> "Kelp Flip";
            case OAK_LOG -> "Oak Log Flip";
            case STICKY_PISTON -> "Sticky Piston Flip";
            case GOLDEN_APPLE -> "Golden Apple Flip";
            case BOOKSHELF -> "Bookshelf Flip";
            case TRAPDOOR -> "Trapdoor Flip";
        };

        try {
            switch (mode) {
                case BONE: {
                    double boneP = parseDouble(config.getSavedBonePrice(), 0.0);
                    double blockP = parseDouble(config.getSavedBlockPrice(), 0.0);
                    double qty = parseDouble(config.getSavedBonesQty(), 100000.0);
                    if (boneP <= 0 || blockP <= 0) return new FlipProfitResult(mode, name, 0, 0, false, "Missing saved prices");

                    double cost = boneP * qty;
                    double blocksCraft = qty / 3.0;
                    double revenue = blocksCraft * blockP;
                    double profit = revenue - cost;
                    double marginPct = cost > 0 ? (profit / cost) * 100.0 : 0.0;
                    return new FlipProfitResult(mode, name, profit, marginPct, true, "");
                }
                case KELP: {
                    double boneP = parseDouble(config.getSavedBonePrice(), 0.0);
                    double rawKelpP = parseDouble(config.getSavedRawKelpPrice(), 0.0);
                    double driedKelpP = parseDouble(config.getSavedDriedKelpPrice(), 0.0);
                    double charcoalP = parseDouble(config.getSavedCharcoalPrice(), 0.0);
                    double qty = parseDouble(config.getSavedBonesQty(), 100000.0);
                    if (boneP <= 0 || (rawKelpP <= 0 && driedKelpP <= 0)) return new FlipProfitResult(mode, name, 0, 0, false, "Missing saved prices");

                    double cost = boneP * qty;
                    double kelpBlocks = qty * 3.0;
                    double revenue = kelpBlocks * (driedKelpP > 0 ? driedKelpP : rawKelpP);
                    double charcoalCost = (driedKelpP > 0) ? (kelpBlocks * 1.125 * charcoalP) : 0.0;
                    double totalCost = cost + charcoalCost;
                    double profit = revenue - totalCost;
                    double marginPct = totalCost > 0 ? (profit / totalCost) * 100.0 : 0.0;
                    return new FlipProfitResult(mode, name, profit, marginPct, true, "");
                }
                case OAK_LOG: {
                    double logP = parseDouble(config.getSavedOakLogPrice(), 0.0);
                    double plankP = parseDouble(config.getSavedOakPlanksPrice(), 0.0);
                    double qty = parseDouble(config.getSavedBonesQty(), 100000.0);
                    if (logP <= 0 || plankP <= 0) return new FlipProfitResult(mode, name, 0, 0, false, "Missing saved prices");

                    double cost = qty * logP;
                    double revenue = (qty * 4.0) * plankP;
                    double profit = revenue - cost;
                    double marginPct = cost > 0 ? (profit / cost) * 100.0 : 0.0;
                    return new FlipProfitResult(mode, name, profit, marginPct, true, "");
                }
                case STICKY_PISTON: {
                    double pistonP = parseDouble(config.getSavedPistonPrice(), 0.0);
                    double slimeP = parseDouble(config.getSavedSlimeballPrice(), 0.0);
                    double stickyP = parseDouble(config.getSavedStickyPistonPrice(), 0.0);
                    double qty = parseDouble(config.getSavedBonesQty(), 1000.0);
                    if (pistonP <= 0 || slimeP <= 0 || stickyP <= 0) return new FlipProfitResult(mode, name, 0, 0, false, "Missing saved prices");

                    double cost = (pistonP + slimeP) * qty;
                    double revenue = stickyP * qty;
                    double profit = revenue - cost;
                    double marginPct = cost > 0 ? (profit / cost) * 100.0 : 0.0;
                    return new FlipProfitResult(mode, name, profit, marginPct, true, "");
                }
                case GOLDEN_APPLE: {
                    double goldP = parseDouble(config.getSavedGoldIngotPrice(), 0.0);
                    double appleP = parseDouble(config.getSavedApplePrice(), 0.0);
                    double gappleP = parseDouble(config.getSavedGapplePrice(), 0.0);
                    double qty = parseDouble(config.getSavedBonesQty(), 1000.0);
                    if (goldP <= 0 || gappleP <= 0) return new FlipProfitResult(mode, name, 0, 0, false, "Missing saved prices");

                    double cost = ((goldP * 8.0) + appleP) * qty;
                    double revenue = gappleP * qty;
                    double profit = revenue - cost;
                    double marginPct = cost > 0 ? (profit / cost) * 100.0 : 0.0;
                    return new FlipProfitResult(mode, name, profit, marginPct, true, "");
                }
                case BOOKSHELF: {
                    double plankP = parseDouble(config.getSavedOakPlanksPrice(), 0.0);
                    double bookP = parseDouble(config.getSavedBookPrice(), 0.0);
                    double bookshelfP = parseDouble(config.getSavedBookshelfPrice(), 0.0);
                    double qty = parseDouble(config.getSavedBonesQty(), 1000.0);
                    if (plankP <= 0 || bookshelfP <= 0) return new FlipProfitResult(mode, name, 0, 0, false, "Missing saved prices");

                    double cost = ((plankP * 6.0) + (bookP * 3.0)) * qty;
                    double revenue = bookshelfP * qty;
                    double profit = revenue - cost;
                    double marginPct = cost > 0 ? (profit / cost) * 100.0 : 0.0;
                    return new FlipProfitResult(mode, name, profit, marginPct, true, "");
                }
                case TRAPDOOR: {
                    double logP = parseDouble(config.getSavedOakLogPrice(), 0.0);
                    double offset = parseDouble(config.getSavedTrapdoorOffset(), 100.0);
                    double baselineUnit = parseDouble(config.getSavedTrapdoorBaselinePrice(), -1.0);

                    if (logP <= 0 || baselineUnit <= 0) return new FlipProfitResult(mode, name, 0, 0, false, "Missing log price or baseline");

                    int[] STACK_SIZES = {4, 8, 12, 16, 24};
                    int stackIdx = Math.min(STACK_SIZES.length - 1, Math.max(0, config.getSavedTrapdoorStackSizeIndex()));
                    int selectedStackSize = STACK_SIZES[stackIdx];

                    double askPricePerUnit = Math.ceil(baselineUnit) + offset;
                    double logsNeeded = selectedStackSize * 0.75;
                    double totalCost = logsNeeded * logP;
                    double totalRevenue = askPricePerUnit * selectedStackSize;
                    double profit = totalRevenue - totalCost;
                    double marginPct = totalCost > 0 ? (profit / totalCost) * 100.0 : 0.0;
                    return new FlipProfitResult(mode, name, profit, marginPct, true, selectedStackSize + "x Stack");
                }
            }
        } catch (Exception e) {
            return new FlipProfitResult(mode, name, 0, 0, false, "Error: " + e.getMessage());
        }

        return new FlipProfitResult(mode, name, 0, 0, false, "No data");
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

    private static double parseDouble(String str, double fallback) {
        if (str == null) return fallback;
        try {
            return Double.parseDouble(str.replace(",", ""));
        } catch (Exception e) {
            return fallback;
        }
    }
}
