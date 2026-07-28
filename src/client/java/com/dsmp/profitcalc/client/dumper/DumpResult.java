package com.dsmp.profitcalc.client.dumper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class DumpResult {
    private final String itemName;
    private final String source; // "ORDER" or "AH"
    private final List<Double> top5;
    private final double avgTop10;
    private final double highest;
    private final double lowest;
    private final int sampleSize;
    private final long timestamp;

    public DumpResult(String itemName, String source, List<Double> top5, double avgTop10, double highest, double lowest, int sampleSize) {
        this.itemName = itemName;
        this.source = source;
        this.top5 = top5 != null ? Collections.unmodifiableList(new ArrayList<>(top5)) : Collections.emptyList();
        this.avgTop10 = avgTop10;
        this.highest = highest;
        this.lowest = lowest;
        this.sampleSize = sampleSize;
        this.timestamp = System.currentTimeMillis();
    }

    public String getItemName() {
        return itemName;
    }

    public String getSource() {
        return source;
    }

    public List<Double> getTop5() {
        return top5;
    }

    public double getAvgTop10() {
        return avgTop10;
    }

    public double getHighest() {
        return highest;
    }

    public double getLowest() {
        return lowest;
    }

    public int getSampleSize() {
        return sampleSize;
    }

    public long getTimestamp() {
        return timestamp;
    }
}
