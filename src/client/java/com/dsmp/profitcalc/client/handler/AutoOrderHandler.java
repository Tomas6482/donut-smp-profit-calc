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
     * 1. Any order with totalQty < 1,000 is ALWAYS filtered out (marked red), regardless of price or position.
     * 2. Outlier price jumps (difference > $20 or > 20% jump) with totalQty < 10,000 are also filtered out (marked red).
     * 3. The highest remaining valid listing (totalQty >= 1,000 and not an outlier jump) is picked (marked green).
     * 4. Target Price = (Highest valid order price) + priceAboveHighest.
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

        // Step 1: Mark all listings with totalQty < 1,000 as filtered out (low quantity rule)
        boolean hasFiltered = false;
        for (OrderListing listing : top10) {
            if (listing.totalQty < 1000) {
                listing.isFiltered = true;
                hasFiltered = true;
            }
        }

        // Step 2: Top-down jump filtering on top valid listings
        int chosenIndex = -1;
        for (int i = 0; i < top10.size(); i++) {
            OrderListing curr = top10.get(i);
            
            // Skip already filtered (<1k qty)
            if (curr.isFiltered) continue;

            // Find next lower valid listing to compare price jump
            OrderListing nextLower = null;
            for (int j = i + 1; j < top10.size(); j++) {
                if (!top10.get(j).isFiltered) {
                    nextLower = top10.get(j);
                    break;
                }
            }

            if (nextLower != null) {
                double diff = curr.price - nextLower.price;
                boolean isJump = (diff > 20.0) || (nextLower.price > 0 && (diff / nextLower.price) > 0.20);
                if (isJump && curr.totalQty < 10000) {
                    curr.isFiltered = true;
                    hasFiltered = true;
                    continue; // Skip this jump outlier and check next lower
                }
            }

            // Found highest valid listing!
            chosenIndex = i;
            break;
        }

        // If all top 10 were filtered out, fallback to the top listing
        if (chosenIndex == -1) {
            chosenIndex = 0;
        }

        OrderListing chosenListing = top10.get(chosenIndex);
        chosenListing.isFiltered = false; // ensure picked item is not marked red
        chosenListing.isPicked = true;     // mark as green picked highest

        double highestFiltered = chosenListing.price;
        double targetPrice = Math.max(1.0, highestFiltered + priceAboveHighest);
        double totalCost = targetPrice * requestedAmount;

        return new CalculationResult(targetPrice, highestFiltered, hasFiltered, totalCost, top10);
    }
}
