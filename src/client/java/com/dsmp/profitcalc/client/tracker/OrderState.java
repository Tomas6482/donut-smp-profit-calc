package com.dsmp.profitcalc.client.tracker;

public enum OrderState {
    IDLE("Not in Order GUI", 0xFF888888, "⚪"),
    BROWSING_ORDERS("Browsing /order", 0xFF55FFFF, "🔍"),
    CREATING_BUY_ORDER("Creating Buy Order", 0xFFFFAA00, "✏️"),
    DELIVERING_ORDER("Fulfilling Order", 0xFF55FF55, "📦");

    private final String label;
    private final int colorHex;
    private final String icon;

    OrderState(String label, int colorHex, String icon) {
        this.label = label;
        this.colorHex = colorHex;
        this.icon = icon;
    }

    public String getLabel() {
        return label;
    }

    public int getColorHex() {
        return colorHex;
    }

    public String getIcon() {
        return icon;
    }
}
