package com.dsmp.profitcalc.client.flipfinder;

import java.util.List;

public class FlipRecipe {
    public final String outputItem;        // Canonical item ID, e.g. "bookshelf"
    public final double outputQtyPerCraft; // Usually 1, but some recipes yield >1
    public final List<Ingredient> ingredients;

    public FlipRecipe(String outputItem, double outputQtyPerCraft, List<Ingredient> ingredients) {
        this.outputItem = outputItem;
        this.outputQtyPerCraft = outputQtyPerCraft;
        this.ingredients = ingredients;
    }

    public record Ingredient(String itemId, double qtyPerCraft) {}
}
