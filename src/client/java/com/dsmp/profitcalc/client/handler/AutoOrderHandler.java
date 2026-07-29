package com.dsmp.profitcalc.client.handler;

import java.util.ArrayList;
import java.util.List;

public class AutoOrderHandler {

    public static class OrderListing {
        public final double price;
        public final int totalQty;
        public final int deliveredQty;
        public boolean isFiltered = false;
        public boolean isPicked = false;

        public OrderListing(double price, int deliveredQty, int totalQty) {
            this.price = price;
            this.deliveredQty = deliveredQty;
            this.totalQty = totalQty;
        }

        public int getRemainingQty() {
            return Math.max(0, totalQty - deliveredQty);
        }
    }

    public static class CalculationResult {
        public final double targetPrice;
        public final double highestFilteredOrderPrice;
        public final boolean jumpFiltered;
        public final double totalCost;
        public final List<OrderListing> top10Listings;

        public CalculationResult(double targetPrice, double highestFilteredOrderPrice, boolean jumpFiltered, double totalCost, List<OrderListing> top10Listings) {
            this.targetPrice = targetPrice;
            this.highestFilteredOrderPrice = highestFilteredOrderPrice;
            this.jumpFiltered = jumpFiltered;
            this.totalCost = totalCost;
            this.top10Listings = top10Listings;
        }
    }

    /**
     * Calculates target order unit price by checking top 10 listings (ordered descending by price).
     *
     * Rules:
     * - Parse top 10 listings (highest price at index 0).
     * - Filter out large jumps IF total requested amount (totalQty) is < 10,000.
     * - If totalQty >= 10,000, do NOT filter out the jump (keep it as highest).
     * - A "jump" occurs if current listing price > previous (lower) price by more than 20% OR by more than $20.
     * - Target Price = (Highest filtered order price) + priceAboveHighest.
     */
    public static CalculationResult calculateTargetPrice(List<OrderListing> listings, double priceAboveHighest, int requestedAmount) {
        if (listings == null || listings.isEmpty()) {
            double target = Math.max(1.0, priceAboveHighest);
            return new CalculationResult(target, 0.0, false, target * requestedAmount, new ArrayList<>());
        }

        // Take top 10 listings
        List<OrderListing> top10 = new ArrayList<>(listings.subList(0, Math.min(10, listings.size())));
        
        // Ensure sorted descending by price
        top10.sort((a, b) -> Double.compare(b.price, a.price));

        int chosenIndex = 0;
        boolean jumpFiltered = false;

        // Iterate top-down to check if the top listing (or subsequent high ones) is an outlier jump with <10k totalQty
        for (int i = 0; i < top10.size() - 1; i++) {
            OrderListing curr = top10.get(i);
            OrderListing nextLower = top10.get(i + 1);

            double diff = curr.price - nextLower.price;
            boolean isJump = (diff > 20.0) || (nextLower.price > 0 && (diff / nextLower.price) > 0.20);

            // Filter out jump ONLY if totalQty < 10,000
            if (isJump && curr.totalQty < 10000) {
                jumpFiltered = true;
                curr.isFiltered = true; // Mark as red filtered outlier
                chosenIndex = i + 1;
            } else {
                // Not a jump, or totalQty >= 10,000 -> keep this as highest valid
                break;
            }
        }

        OrderListing chosenListing = top10.get(chosenIndex);
        chosenListing.isPicked = true; // Mark as green picked highest

        double highestFiltered = chosenListing.price;
        double targetPrice = Math.max(1.0, highestFiltered + priceAboveHighest);
        double totalCost = targetPrice * requestedAmount;

        return new CalculationResult(targetPrice, highestFiltered, jumpFiltered, totalCost, top10);
    }
}
