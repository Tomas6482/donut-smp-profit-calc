package com.dsmp.profitcalc.client.tracker;

import com.dsmp.profitcalc.client.config.ProfitConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class OrderStatusTracker {
    private static final Logger LOGGER = LoggerFactory.getLogger("donut-smp-profit-calc/StatusTracker");
    private static final OrderStatusTracker INSTANCE = new OrderStatusTracker();

    private OrderState currentState = OrderState.IDLE;
    private String activeItemName = "";
    private double activeUnitPrice = 0.0;
    private int activeAmount = 0;
    private String detailInfo = "Idle";
    private final List<Runnable> statusListeners = new CopyOnWriteArrayList<>();

    public static OrderStatusTracker getInstance() {
        return INSTANCE;
    }

    public synchronized void updateStatus(OrderState newState, String item, double price, int amount, String detail) {
        boolean changed = (currentState != newState) || !activeItemName.equals(item) || activeUnitPrice != price || activeAmount != amount || !detailInfo.equals(detail);
        this.currentState = newState;
        this.activeItemName = item != null ? item : "";
        this.activeUnitPrice = price;
        this.activeAmount = amount;
        this.detailInfo = detail != null ? detail : "";

        if (changed) {
            if (ProfitConfig.getInstance().isVerboseLogging()) {
                LOGGER.info("[DONUT PROFIT/STATUS] State Changed -> State: {} | Item: '{}' | Price: ${}/ea | Amount: {} | Detail: '{}'",
                        newState, activeItemName, activeUnitPrice, activeAmount, detailInfo);
            }
            notifyListeners();
        }
    }

    public void addStatusListener(Runnable listener) {
        if (listener != null && !statusListeners.contains(listener)) {
            statusListeners.add(listener);
        }
    }

    public void removeStatusListener(Runnable listener) {
        statusListeners.remove(listener);
    }

    private void notifyListeners() {
        for (Runnable listener : statusListeners) {
            try {
                listener.run();
            } catch (Exception e) {
                LOGGER.error("Error notifying status listener", e);
            }
        }
    }

    public OrderState getCurrentState() {
        return currentState;
    }

    public String getActiveItemName() {
        return activeItemName;
    }

    public double getActiveUnitPrice() {
        return activeUnitPrice;
    }

    public int getActiveAmount() {
        return activeAmount;
    }

    public String getDetailInfo() {
        return detailInfo;
    }

    public String getDisplayText() {
        if (currentState == OrderState.IDLE) {
            return currentState.getIcon() + " Not in Order GUI";
        }
        if (activeItemName.isEmpty()) {
            return String.format("%s %s", currentState.getIcon(), currentState.getLabel());
        }
        if (activeUnitPrice > 0) {
            return String.format("%s %s: %s ($%.2f/ea)", currentState.getIcon(), currentState.getLabel(), activeItemName, activeUnitPrice);
        }
        return String.format("%s %s: %s", currentState.getIcon(), currentState.getLabel(), activeItemName);
    }
}
