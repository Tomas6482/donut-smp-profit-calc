package com.dsmp.profitcalc.client.handler;

import com.dsmp.profitcalc.client.config.ProfitConfig;
import com.dsmp.profitcalc.client.dumper.ItemTagResolver;
import com.dsmp.profitcalc.client.ui.ProfitDetailsScreen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AutoOrderRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger("donut-smp-profit-calc/AutoOrder");

    private static final Pattern ORDER_PRICE_PATTERN = Pattern.compile("\\$\\s*([0-9,]+(?:\\.[0-9]{1,3})?(?:e[+-]?[0-9]+)?\\s*[kmbKMB]?)\\s*(?:each|/each|per item)", Pattern.CASE_INSENSITIVE);
    private static final Pattern DELIVERED_PATTERN = Pattern.compile("([0-9,]+(?:\\.[0-9]+)?\\s*[kmbKMB]?)\\s*/\\s*([0-9,]+(?:\\.[0-9]+)?\\s*[kmbKMB]?)\\s*Delivered", Pattern.CASE_INSENSITIVE);

    public static class OrderRunResult {
        public final String itemStr;
        public final int amount;
        public final double offset;
        public final double highestFilteredPrice;
        public final boolean jumpFiltered;
        public final AutoOrderHandler.CalculationResult calcResult;

        public OrderRunResult(String itemStr, int amount, double offset, double highestFilteredPrice, boolean jumpFiltered, AutoOrderHandler.CalculationResult calcResult) {
            this.itemStr = itemStr;
            this.amount = amount;
            this.offset = offset;
            this.highestFilteredPrice = highestFilteredPrice;
            this.jumpFiltered = jumpFiltered;
            this.calcResult = calcResult;
        }
    }

    private enum State {
        IDLE,
        SEND_ORDER_CMD,
        WAITING_FOR_SCREEN,
        PARSING_SCREEN
    }

    private static boolean running = false;
    private static String itemStr = "";
    private static int amount = 0;
    private static double offset = 5.0;
    private static Consumer<OrderRunResult> completionCallback = null;

    private static State state = State.IDLE;
    private static long stateStartTime = 0;
    private static int waitTicks = 0;

    public static void start(String itemInput, int requestedAmount, double priceOffset, Consumer<OrderRunResult> onComplete) {
        List<String> resolved = ItemTagResolver.resolveInputLines(itemInput);
        itemStr = resolved.isEmpty() ? itemInput.trim().replace(" ", "_") : resolved.get(0);
        amount = Math.max(1, requestedAmount);
        offset = priceOffset;
        completionCallback = onComplete;

        running = true;
        state = State.SEND_ORDER_CMD;
        stateStartTime = System.currentTimeMillis();
        waitTicks = 0;

        LOGGER.info("[Auto Order] Starting scan for item '{}', amount={}, offset=${}", itemStr, amount, offset);
    }

    public static boolean isRunning() {
        return running;
    }

    public static void stop() {
        running = false;
        state = State.IDLE;
        completionCallback = null;
    }

    public static void onTick(Minecraft mc) {
        if (!running || mc == null) return;

        long now = System.currentTimeMillis();

        switch (state) {
            case SEND_ORDER_CMD -> {
                if (mc.player != null) {
                    mc.player.closeContainer();
                    String cmd = "order " + itemStr;
                    mc.player.connection.sendCommand(cmd);
                    mc.player.displayClientMessage(Component.literal("§a[Auto Order] Scanning highest order prices for " + itemStr + "..."), true);
                }
                state = State.WAITING_FOR_SCREEN;
                stateStartTime = now;
                waitTicks = 0;
            }
            case WAITING_FOR_SCREEN -> {
                waitTicks++;
                if (mc.screen instanceof AbstractContainerScreen<?>) {
                    String title = mc.screen.getTitle() != null ? mc.screen.getTitle().getString().toLowerCase() : "";
                    if (title.contains("orders") || title.contains("order")) {
                        state = State.PARSING_SCREEN;
                        stateStartTime = now;
                        waitTicks = 0;
                        return;
                    }
                }
                if (waitTicks > 60 || (now - stateStartTime > 3000)) {
                    LOGGER.warn("[Auto Order] Timed out waiting for Orders container screen.");
                    finishWithEmpty(mc);
                }
            }
            case PARSING_SCREEN -> {
                if (mc.screen instanceof AbstractContainerScreen<?> screen) {
                    List<AutoOrderHandler.OrderListing> listings = parseListingsFromScreen(screen);
                    LOGGER.info("[Auto Order] Parsed {} listings from Orders GUI for {}", listings.size(), itemStr);

                    AutoOrderHandler.CalculationResult calcRes = AutoOrderHandler.calculateTargetPrice(listings, offset, amount);

                    OrderRunResult result = new OrderRunResult(
                            itemStr, amount, offset,
                            calcRes.highestFilteredOrderPrice,
                            calcRes.jumpFiltered,
                            calcRes
                    );

                    mc.player.closeContainer();
                    stop();

                    mc.execute(() -> {
                        ProfitDetailsScreen detailsScreen = new ProfitDetailsScreen();
                        mc.setScreen(detailsScreen);
                        if (completionCallback != null) {
                            completionCallback.accept(result);
                        }
                    });
                } else {
                    finishWithEmpty(mc);
                }
            }
        }
    }

    private static void finishWithEmpty(Minecraft mc) {
        stop();
        if (mc.player != null) {
            mc.player.displayClientMessage(Component.literal("§c[Auto Order] Could not open Orders page for " + itemStr), false);
        }
    }

    private static List<AutoOrderHandler.OrderListing> parseListingsFromScreen(AbstractContainerScreen<?> screen) {
        List<AutoOrderHandler.OrderListing> listings = new ArrayList<>();
        if (screen == null || screen.getMenu() == null) return listings;

        for (int i = 0; i < Math.min(45, screen.getMenu().slots.size()); i++) {
            var slot = screen.getMenu().slots.get(i);
            if (slot == null || slot.getItem().isEmpty()) continue;

            List<String> tooltipLines = new ArrayList<>();
            try {
                var rawLines = slot.getItem().getTooltipLines(
                        net.minecraft.world.item.Item.TooltipContext.EMPTY,
                        Minecraft.getInstance().player,
                        net.minecraft.world.item.TooltipFlag.NORMAL
                );
                for (var line : rawLines) {
                    tooltipLines.add(line.getString());
                }
            } catch (Exception ignored) {}

            double price = 0.0;
            int delivered = 0;
            int total = 0;

            for (String text : tooltipLines) {
                Matcher mPrice = ORDER_PRICE_PATTERN.matcher(text);
                if (mPrice.find()) {
                    price = parseFormattedMoney(mPrice.group(1));
                }

                Matcher mDelivered = DELIVERED_PATTERN.matcher(text);
                if (mDelivered.find()) {
                    delivered = (int) parseFormattedQty(mDelivered.group(1));
                    total = (int) parseFormattedQty(mDelivered.group(2));
                }
            }

            if (price > 0 && total > 0) {
                listings.add(new AutoOrderHandler.OrderListing(price, delivered, total));
            }
        }

        listings.sort((a, b) -> Double.compare(b.price, a.price));
        return listings;
    }

    private static double parseFormattedMoney(String str) {
        if (str == null) return 0.0;
        String clean = str.trim().toLowerCase().replace("$", "").replace(",", "");
        double mult = 1.0;
        if (clean.endsWith("k")) { mult = 1000.0; clean = clean.substring(0, clean.length() - 1); }
        else if (clean.endsWith("m")) { mult = 1000000.0; clean = clean.substring(0, clean.length() - 1); }
        else if (clean.endsWith("b")) { mult = 1000000000.0; clean = clean.substring(0, clean.length() - 1); }
        try { return Double.parseDouble(clean.trim()) * mult; } catch (Exception e) { return 0.0; }
    }

    private static double parseFormattedQty(String str) {
        if (str == null) return 0.0;
        String clean = str.trim().toLowerCase().replace(",", "");
        double mult = 1.0;
        if (clean.endsWith("k")) { mult = 1000.0; clean = clean.substring(0, clean.length() - 1); }
        else if (clean.endsWith("m")) { mult = 1000000.0; clean = clean.substring(0, clean.length() - 1); }
        else if (clean.endsWith("b")) { mult = 1000000000.0; clean = clean.substring(0, clean.length() - 1); }
        try { return Double.parseDouble(clean.trim()) * mult; } catch (Exception e) { return 0.0; }
    }
}
