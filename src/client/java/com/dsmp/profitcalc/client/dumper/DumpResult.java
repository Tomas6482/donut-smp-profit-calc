package com.dsmp.profitcalc.client.dumper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class DumpResult {

    public static class ItemDumpInfo {
        private final String name;
        private final String itemId;
        private final int maxStackSize;
        private final int quantity;
        private final double unitPrice;
        private final Map<Integer, Double> stackPrices; // e.g. 1 -> price, 4 -> price, 8 -> price, etc.

        public ItemDumpInfo(String name, String itemId, int maxStackSize, int quantity, double unitPrice, Map<Integer, Double> stackPrices) {
            this.name = name;
            this.itemId = itemId;
            this.maxStackSize = maxStackSize;
            this.quantity = quantity;
            this.unitPrice = unitPrice;
            this.stackPrices = stackPrices != null ? Collections.unmodifiableMap(new LinkedHashMap<>(stackPrices)) : Collections.emptyMap();
        }

        public String getName() { return name; }
        public String getItemId() { return itemId; }
        public int getMaxStackSize() { return maxStackSize; }
        public int getQuantity() { return quantity; }
        public double getUnitPrice() { return unitPrice; }
        public Map<Integer, Double> getStackPrices() { return stackPrices; }
    }

    private final String query;
    private final String source; // "ORDER" or "AH"
    private final List<ItemDumpInfo> items;
    private final long timestamp;

    public DumpResult(String query, String source, List<ItemDumpInfo> items) {
        this.query = query;
        this.source = source;
        this.items = items != null ? Collections.unmodifiableList(new ArrayList<>(items)) : Collections.emptyList();
        this.timestamp = System.currentTimeMillis();
    }

    public String getQuery() { return query; }
    public String getItemName() { return query; }
    public String getSource() { return source; }
    public List<ItemDumpInfo> getItems() { return items; }
    public int getSampleSize() { return items.size(); }
    public long getTimestamp() { return timestamp; }

    public double getAvgTop10() {
        if (items.isEmpty()) return 0.0;
        double sum = 0.0;
        for (ItemDumpInfo info : items) {
            sum += info.getUnitPrice();
        }
        return sum / items.size();
    }

    public double getHighest() {
        if (items.isEmpty()) return 0.0;
        double high = 0.0;
        for (ItemDumpInfo info : items) {
            if (info.getUnitPrice() > high) high = info.getUnitPrice();
        }
        return high;
    }

    public double getLowest() {
        if (items.isEmpty()) return 0.0;
        double low = Double.MAX_VALUE;
        for (ItemDumpInfo info : items) {
            if (info.getUnitPrice() < low) low = info.getUnitPrice();
        }
        return low == Double.MAX_VALUE ? 0.0 : low;
    }
}
