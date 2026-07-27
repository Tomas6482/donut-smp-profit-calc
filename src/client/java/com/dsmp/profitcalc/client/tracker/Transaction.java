package com.dsmp.profitcalc.client.tracker;

import java.text.NumberFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

public class Transaction {
    private static final NumberFormat CURRENCY_FORMAT = NumberFormat.getCurrencyInstance(Locale.US);
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private final TransactionType type;
    private final String itemName;
    private final int amount;
    private final double pricePerItem;
    private final double totalPrice;
    private final long timestamp;

    public Transaction(TransactionType type, String itemName, int amount, double pricePerItem, double totalPrice) {
        this.type = type;
        this.itemName = itemName != null && !itemName.isEmpty() ? itemName : "Unknown Item";
        this.amount = Math.max(1, amount);
        this.pricePerItem = pricePerItem;
        this.totalPrice = totalPrice > 0 ? totalPrice : (pricePerItem * amount);
        this.timestamp = System.currentTimeMillis();
    }

    public Transaction(TransactionType type, String itemName, int amount, double pricePerItem, double totalPrice, long timestamp) {
        this.type = type;
        this.itemName = itemName != null && !itemName.isEmpty() ? itemName : "Unknown Item";
        this.amount = Math.max(1, amount);
        this.pricePerItem = pricePerItem;
        this.totalPrice = totalPrice;
        this.timestamp = timestamp;
    }

    public TransactionType getType() {
        return type;
    }

    public String getItemName() {
        return itemName;
    }

    public int getAmount() {
        return amount;
    }

    public double getPricePerItem() {
        return pricePerItem;
    }

    public double getTotalPrice() {
        return totalPrice;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public String getFormattedTotalPrice() {
        return CURRENCY_FORMAT.format(totalPrice);
    }

    public String getFormattedPricePerItem() {
        return CURRENCY_FORMAT.format(pricePerItem);
    }

    public String getFormattedTime() {
        return TIME_FORMATTER.format(Instant.ofEpochMilli(timestamp));
    }

    public String getSummaryText() {
        String sign = type == TransactionType.SELL ? "+" : "-";
        return String.format("%s%s (%dx %s @ %s)", sign, getFormattedTotalPrice(), amount, itemName, getFormattedPricePerItem());
    }

    @Override
    public String toString() {
        return "Transaction{" +
                "type=" + type +
                ", itemName='" + itemName + '\'' +
                ", amount=" + amount +
                ", pricePerItem=" + pricePerItem +
                ", totalPrice=" + totalPrice +
                ", timestamp=" + timestamp +
                '}';
    }
}
