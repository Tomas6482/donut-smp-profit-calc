package com.dsmp.profitcalc.client.dumper;

public class DumpResult {
    private final String itemName;
    private final String source; // "ORDER" or "AH"
    private final double price;
    private final String formattedPrice;
    private final long timestamp;

    public DumpResult(String itemName, String source, double price, String formattedPrice) {
        this.itemName = itemName;
        this.source = source;
        this.price = price;
        this.formattedPrice = formattedPrice;
        this.timestamp = System.currentTimeMillis();
    }

    public String getItemName() {
        return itemName;
    }

    public String getSource() {
        return source;
    }

    public double getPrice() {
        return price;
    }

    public String getFormattedPrice() {
        return formattedPrice;
    }

    public long getTimestamp() {
        return timestamp;
    }
}
