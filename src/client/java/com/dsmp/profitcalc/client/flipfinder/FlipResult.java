package com.dsmp.profitcalc.client.flipfinder;

public class FlipResult {
    public final String outputItem;
    public final double craftQtyRequested;    // Tried craft quantity (e.g. 100)
    public final double maxRealisticCraftQty; // Fillable craft quantity based on depth
    public final double totalCost;
    public final double totalRevenue;
    public final double totalProfit;
    public final double marginPct;            // (totalProfit / totalCost) * 100
    public final boolean lowConfidence;       // true if sample count < 5

    public FlipResult(String outputItem, double craftQtyRequested, double maxRealisticCraftQty,
                      double totalCost, double totalRevenue, double totalProfit,
                      double marginPct, boolean lowConfidence) {
        this.outputItem = outputItem;
        this.craftQtyRequested = craftQtyRequested;
        this.maxRealisticCraftQty = maxRealisticCraftQty;
        this.totalCost = totalCost;
        this.totalRevenue = totalRevenue;
        this.totalProfit = totalProfit;
        this.marginPct = marginPct;
        this.lowConfidence = lowConfidence;
    }
}
