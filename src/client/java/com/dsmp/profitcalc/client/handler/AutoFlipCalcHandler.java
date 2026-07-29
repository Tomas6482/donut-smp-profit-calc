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

    private static ScanTask currentTask = null;
    private static int pagesScanned = 0;
    private static long lastActionTime = 0;

    // Map to accumulate raw listings for each target key during multi-page scan
    private static final Map<String, List<Listing>> accumulatedListings = new HashMap<>();

    // Final computed top-3 average prices to save
    private static final Map<String, Double> finalComputedPrices = new HashMap<>();

    public static void stop() {
        running = false;
        taskQueue.clear();
        currentTask = null;
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
            processAndStoreTaskResults(currentTask.targetItemKey);
            currentTask = null;
            lastActionTime = now + 200;
            return;
        }

        if (mc.screen instanceof AbstractContainerScreen<?> containerScreen) {
            long delayRequired = (pagesScanned == 0) ? INITIAL_COMMAND_DELAY_MS : PAGE_TURN_DELAY_MS;
            if (now - lastActionTime < delayRequired) return;

            // Scan current page
            scanCurrentContainer(containerScreen, currentTask.targetItemKey);

            // Pagination logic: Scan up to 3 pages total (pages 0, 1, 2)
            if (pagesScanned < 2 && isNextPageAvailable(containerScreen)) {
                pagesScanned++;
                lastActionTime = now;
                int containerId = containerScreen.getMenu().containerId;
                mc.gameMode.handleInventoryMouseClick(containerId, 53, 0, ClickType.PICKUP, mc.player);
                LOGGER.info("[Auto Flip] Safely clicked Next Page (Slot 53) in /{}. Page count: {}", currentTask.command, pagesScanned);
            } else {
                // Done scanning 3 pages or no next page available
                processAndStoreTaskResults(currentTask.targetItemKey);
                currentTask = null;
                lastActionTime = System.currentTimeMillis();
            }
        }
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

    private static void scanCurrentContainer(AbstractContainerScreen<?> containerScreen, String targetKey) {
        if (containerScreen.getMenu() == null) return;
        List<Slot> slots = containerScreen.getMenu().slots;
        if (slots.size() < 45) return;

        for (int i = 0; i < Math.min(45, slots.size()); i++) {
            ItemStack stack = slots.get(i).getItem();
            if (stack.isEmpty()) continue;

            Identifier loc = BuiltInRegistries.ITEM.getKey(stack.getItem());
            String path = (loc != null) ? loc.getPath().toLowerCase() : "";
            String name = stack.getHoverName().getString().trim().toLowerCase();

            double price = parsePriceFromStack(stack);
            if (price <= 0) continue;

            double quantity = parseQuantityFromStack(stack);

            String matchKey = null;

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
            }
        }
    }

    private static void processAndStoreTaskResults(String targetKey) {
        // Collect all keys matching targetKey or populated during this task
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

            // Fallback if filtering dropped everything
            if (filteredListings.isEmpty()) {
                filteredListings = rawListings;
                outlierListings.clear();
            }

            // 3. Take Top 3 Lowest Prices (or highest for buy orders if desired; top 3 lowest asking prices)
            filteredListings.sort((a, b) -> Double.compare(a.price, b.price));
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

            // Detailed logging for inspection
            LOGGER.info("[Auto Flip] Summary for key '{}':", key);
            LOGGER.info("  Raw Matches ({}) : {}", rawListings.size(), rawListings);
            LOGGER.info("  Median Qty      : {} (Cutoff >= {})", medianQty, cutoffQty);
            LOGGER.info("  Outliers Dropped: {}", outlierListings);
            LOGGER.info("  Filtered Set ({}): {}", filteredListings.size(), filteredListings);
            LOGGER.info("  Top 3 Used      : {}", top3List);
            LOGGER.info("  Final Top-3 Avg : ${}", String.format("%.2f", avgTop3));
        }
    }

    private static void finishAutoScan(Minecraft mc) {
        running = false;
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

    private static double parseQuantityFromStack(ItemStack stack) {
        if (stack.isEmpty()) return 1.0;
        double stackCount = stack.getCount();

        Pattern qtyPattern = Pattern.compile("(?:Amount|Qty|Quantity|Requesting|Count|Size)\\s*:?\\s*([0-9.,]+[kmbKMB]?)", Pattern.CASE_INSENSITIVE);

        try {
            var loreComponent = stack.get(net.minecraft.core.component.DataComponents.LORE);
            if (loreComponent != null) {
                for (net.minecraft.network.chat.Component comp : loreComponent.lines()) {
                    Matcher m = qtyPattern.matcher(comp.getString());
                    if (m.find()) {
                        double parsed = parseFormattedMoney(m.group(1));
                        if (parsed > 0) return Math.max(stackCount, parsed);
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
                Matcher m = qtyPattern.matcher(lineComponent.getString());
                if (m.find()) {
                    double parsed = parseFormattedMoney(m.group(1));
                    if (parsed > 0) return Math.max(stackCount, parsed);
                }
            }
        } catch (Exception ignored) {}

        return Math.max(1.0, stackCount);
    }

    private static double parsePriceFromStack(ItemStack stack) {
        if (stack.isEmpty()) return 0.0;
        // Relaxed price pattern matching any line containing $ followed by a number
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
