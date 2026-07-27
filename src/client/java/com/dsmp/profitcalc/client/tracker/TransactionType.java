package com.dsmp.profitcalc.client.tracker;

public enum TransactionType {
    BUY("Posted Buy Order", "Spent", 0xFFFF5555), // Red
    SELL("Fulfilled Order", "Gained", 0xFF55FF55); // Green

    private final String displayName;
    private final String actionName;
    private final int colorHex;

    TransactionType(String displayName, String actionName, int colorHex) {
        this.displayName = displayName;
        this.actionName = actionName;
        this.colorHex = colorHex;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getActionName() {
        return actionName;
    }

    public int getColorHex() {
        return colorHex;
    }
}
