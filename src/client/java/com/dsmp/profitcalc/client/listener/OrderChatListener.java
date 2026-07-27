package com.dsmp.profitcalc.client.listener;

import com.dsmp.profitcalc.client.config.ProfitConfig;
import com.dsmp.profitcalc.client.tracker.ProfitTracker;
import com.dsmp.profitcalc.client.tracker.Transaction;
import com.dsmp.profitcalc.client.tracker.TransactionType;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class OrderChatListener {
    private static final Logger LOGGER = LoggerFactory.getLogger("donut-smp-profit-calc/ChatListener");

    private static final Pattern SELL_CHAT_PATTERN_1 = Pattern.compile("(?:sold|delivered)\\s+([0-9,]+x?)\\s+(.+?)\\s+(?:and\\s+)?(?:for|received)\\s+\\$?([0-9,]+(?:\\.[0-9]{1,3})?(?:e[+-]?[0-9]+)?\\s*[kmbKMB]?)", Pattern.CASE_INSENSITIVE);
    private static final Pattern SELL_CHAT_PATTERN_2 = Pattern.compile("received\\s+\\$?([0-9,]+(?:\\.[0-9]{1,3})?(?:e[+-]?[0-9]+)?\\s*[kmbKMB]?)\\s+for\\s+(?:delivering|selling)\\s+([0-9,]+x?)\\s+(.+)", Pattern.CASE_INSENSITIVE);

    private static final Pattern BUY_CONFIRM_CHAT_PATTERN = Pattern.compile("you\\s+(?:have\\s+)?ordered\\s+([0-9,]+x?)\\s*(.*)", Pattern.CASE_INSENSITIVE);
    private static final Pattern BUY_CHAT_PATTERN_1 = Pattern.compile("created\\s+(?:buy\\s+)?order\\s+for\\s+([0-9,]+x?)\\s+(.+?)\\s+for\\s+\\$?([0-9,]+(?:\\.[0-9]{1,3})?(?:e[+-]?[0-9]+)?\\s*[kmbKMB]?)", Pattern.CASE_INSENSITIVE);

    public void register() {
        ClientReceiveMessageEvents.GAME.register(this::onGameMessage);
    }

    private void onGameMessage(Component message, boolean overlay) {
        if (message == null) return;
        String rawText = message.getString();
        if (rawText == null || rawText.isEmpty()) return;

        String text = stripColorCodes(rawText);

        if (!text.toLowerCase().contains("order") && !text.toLowerCase().contains("sold") && !text.toLowerCase().contains("delivered") && !text.toLowerCase().contains("received")) {
            return;
        }

        if (ProfitConfig.getInstance().isVerboseLogging()) {
            LOGGER.info("[DONUT PROFIT/CHAT] Server Chat Message: '{}'", text);
        }

        try {
            // Check BUY Confirmation ("You ordered 1 Bone")
            Matcher mBuyConfirm = BUY_CONFIRM_CHAT_PATTERN.matcher(text);
            if (mBuyConfirm.find()) {
                int amount = parseAmount(mBuyConfirm.group(1));
                String item = cleanItemName(mBuyConfirm.group(2));

                OrderScreenHandler.PendingReviewOrder pending = OrderScreenHandler.getPendingReviewOrder();
                double totalPrice = 0.0;
                double pricePerItem = 0.0;

                if (pending != null && (pending.totalPrice > 0 || pending.pricePerItem > 0)) {
                    totalPrice = pending.totalPrice;
                    pricePerItem = pending.pricePerItem;
                    if (!pending.itemName.isEmpty()) item = cleanItemName(pending.itemName);
                    if (ProfitConfig.getInstance().isVerboseLogging()) {
                        LOGGER.info("[DONUT PROFIT/CHAT] Matched Pending Review Order: Item = '{}' | Amount = {} | Price/ea = ${} | Total = ${}",
                                item, amount, pricePerItem, totalPrice);
                    }
                }

                if (pricePerItem <= 0) {
                    pricePerItem = OrderScreenHandler.getLastListingPrice();
                    if (ProfitConfig.getInstance().isVerboseLogging()) {
                        LOGGER.info("[DONUT PROFIT/CHAT] Fallback to last listing price: ${}/ea", pricePerItem);
                    }
                }

                if (pricePerItem <= 0 && totalPrice > 0 && amount > 0) {
                    pricePerItem = totalPrice / amount;
                }

                if (totalPrice <= 0 && pricePerItem > 0) {
                    totalPrice = amount * pricePerItem;
                }

                if (totalPrice <= 0) {
                    pricePerItem = 1.0;
                    totalPrice = amount * pricePerItem;
                }

                ProfitTracker.getInstance().addTransaction(new Transaction(TransactionType.BUY, item, amount, pricePerItem, totalPrice));
                OrderScreenHandler.clearPendingReviewOrder();
                if (ProfitConfig.getInstance().isVerboseLogging()) {
                    LOGGER.info("[DONUT PROFIT/CHAT] Confirmed BUY Order spending: {} x{} @ ${}/ea (Total: ${})", item, amount, pricePerItem, totalPrice);
                }
                return;
            }

            // Check SELL Messages: If GUI delivery already recorded exact numbers within 5s, skip chat parsing completely!
            long guiTimeDiff = System.currentTimeMillis() - OrderScreenHandler.getLastGuiDeliveryTime();
            if (guiTimeDiff < 5000) {
                if (ProfitConfig.getInstance().isVerboseLogging()) {
                    LOGGER.info("[DONUT PROFIT/CHAT] Ignored rounded chat sell message because precise GUI delivery was recorded {}ms ago.", guiTimeDiff);
                }
                return;
            }

            // Check SELL Pattern 1 ("You delivered 14 Bone Blocks and received $5.6K")
            Matcher mSell1 = SELL_CHAT_PATTERN_1.matcher(text);
            if (mSell1.find()) {
                int amount = parseAmount(mSell1.group(1));
                String item = cleanItemName(mSell1.group(2));
                double total = parsePrice(mSell1.group(3));
                if (total > 0 && amount > 0) {
                    double unitPrice = total / amount;
                    if (ProfitConfig.getInstance().isVerboseLogging()) {
                        LOGGER.info("[DONUT PROFIT/CHAT] Matched SELL Pattern 1 Fallback: {} x{} @ ${}/ea (Total: ${})", item, amount, unitPrice, total);
                    }
                    ProfitTracker.getInstance().addTransaction(new Transaction(TransactionType.SELL, item, amount, unitPrice, total));
                    return;
                }
            }

            // Check SELL Pattern 2
            Matcher mSell2 = SELL_CHAT_PATTERN_2.matcher(text);
            if (mSell2.find()) {
                double total = parsePrice(mSell2.group(1));
                int amount = parseAmount(mSell2.group(2));
                String item = cleanItemName(mSell2.group(3));
                if (total > 0 && amount > 0) {
                    double unitPrice = total / amount;
                    if (ProfitConfig.getInstance().isVerboseLogging()) {
                        LOGGER.info("[DONUT PROFIT/CHAT] Matched SELL Pattern 2 Fallback: {} x{} @ ${}/ea (Total: ${})", item, amount, unitPrice, total);
                    }
                    ProfitTracker.getInstance().addTransaction(new Transaction(TransactionType.SELL, item, amount, unitPrice, total));
                    return;
                }
            }

            // Check BUY Pattern 1
            Matcher mBuy1 = BUY_CHAT_PATTERN_1.matcher(text);
            if (mBuy1.find()) {
                int amount = parseAmount(mBuy1.group(1));
                String item = cleanItemName(mBuy1.group(2));
                double total = parsePrice(mBuy1.group(3));
                if (total > 0 && amount > 0) {
                    double unitPrice = total / amount;
                    if (ProfitConfig.getInstance().isVerboseLogging()) {
                        LOGGER.info("[DONUT PROFIT/CHAT] Matched BUY Pattern 1 Fallback: {} x{} @ ${}/ea (Total: ${})", item, amount, unitPrice, total);
                    }
                    ProfitTracker.getInstance().addTransaction(new Transaction(TransactionType.BUY, item, amount, unitPrice, total));
                }
            }
        } catch (Exception e) {
            LOGGER.error("Error parsing chat order message", e);
        }
    }

    private String cleanItemName(String item) {
        if (item == null) return "";
        String clean = item.trim();
        if (clean.toLowerCase().endsWith(" and")) {
            clean = clean.substring(0, clean.length() - 4).trim();
        }
        return clean;
    }

    private String stripColorCodes(String input) {
        if (input == null) return "";
        return input.replaceAll("§[0-9a-fk-orA-FK-OR]", "").trim();
    }

    private int parseAmount(String str) {
        if (str == null) return 1;
        String clean = str.toLowerCase().replace("x", "").replace(",", "").trim();
        try {
            return Integer.parseInt(clean);
        } catch (Exception e) {
            return 1;
        }
    }

    private double parsePrice(String str) {
        if (str == null) return 0.0;
        String clean = str.replace("$", "").replace(",", "").trim().toLowerCase();
        if (clean.isEmpty()) return 0.0;

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
