package com.dsmp.profitcalc.client.tracker;

import com.dsmp.profitcalc.client.config.ProfitConfig;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.nio.file.Path;
import java.text.NumberFormat;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

public class ProfitTracker {
    private static final Logger LOGGER = LoggerFactory.getLogger("donut-smp-profit-calc/Tracker");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path DATA_FILE = FabricLoader.getInstance().getConfigDir().resolve("donut_profit_calc_data.json");
    private static final NumberFormat CURRENCY_FORMAT = NumberFormat.getCurrencyInstance(Locale.US);

    private static final ProfitTracker INSTANCE = new ProfitTracker();

    private double totalGained = 0.0;
    private double totalSpent = 0.0;
    private long sessionStartTime = System.currentTimeMillis();
    private final List<Transaction> transactions = new CopyOnWriteArrayList<>();
    private final List<Runnable> updateListeners = new CopyOnWriteArrayList<>();

    public static class ItemSummary {
        private final String itemName;
        private int totalBuyCount;
        private int totalSellCount;
        private double totalSpent;
        private double totalGained;

        public ItemSummary(String itemName) {
            this.itemName = itemName;
        }

        public String getItemName() {
            return itemName;
        }

        public int getTotalBuyCount() {
            return totalBuyCount;
        }

        public int getTotalSellCount() {
            return totalSellCount;
        }

        public double getTotalSpent() {
            return totalSpent;
        }

        public double getTotalGained() {
            return totalGained;
        }

        public double getNetProfit() {
            return totalGained - totalSpent;
        }

        public String getFormattedNetProfit() {
            double net = getNetProfit();
            String sign = net >= 0 ? "+" : "";
            return sign + CURRENCY_FORMAT.format(net);
        }
    }

    private ProfitTracker() {
        if (ProfitConfig.getInstance().isPersistDataOnRestart()) {
            loadData();
        }
    }

    public static ProfitTracker getInstance() {
        return INSTANCE;
    }

    public synchronized void addTransaction(Transaction tx) {
        if (tx == null) return;

        // Prevent identical duplicate packet firings within 10ms
        if (!transactions.isEmpty()) {
            Transaction last = transactions.get(0);
            if (last.getType() == tx.getType() &&
                last.getItemName().equalsIgnoreCase(tx.getItemName()) &&
                last.getAmount() == tx.getAmount() &&
                Math.abs(last.getTotalPrice() - tx.getTotalPrice()) < 0.01 &&
                System.currentTimeMillis() - last.getTimestamp() < 10) {
                if (ProfitConfig.getInstance().isVerboseLogging()) {
                    LOGGER.info("[DONUT PROFIT/TRACKER] Skipped duplicate transaction: {}", tx.getSummaryText());
                }
                return;
            }
        }

        transactions.add(0, tx);

        if (tx.getType() == TransactionType.SELL) {
            totalGained += tx.getTotalPrice();
        } else if (tx.getType() == TransactionType.BUY) {
            totalSpent += tx.getTotalPrice();
        }

        if (ProfitConfig.getInstance().isVerboseLogging()) {
            LOGGER.info("[DONUT PROFIT/TRACKER] Added {} Transaction: {} | New Gained: {} | New Spent: {} | Net Profit: {}",
                    tx.getType(), tx.getSummaryText(), getFormattedGained(), getFormattedSpent(), getFormattedNetProfit());
        }

        if (ProfitConfig.getInstance().isPlaySoundOnTransaction()) {
            try {
                Minecraft mc = Minecraft.getInstance();
                if (mc != null && mc.getSoundManager() != null) {
                    float pitch = tx.getType() == TransactionType.SELL ? 1.2f : 0.8f;
                    mc.execute(() -> mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, pitch, 0.6f)));
                }
            } catch (Exception ignored) {}
        }

        saveData();
        notifyListeners();
    }

    public synchronized void removeTransaction(Transaction tx) {
        if (tx == null) return;
        if (transactions.remove(tx)) {
            recalculateTotals();
            if (ProfitConfig.getInstance().isVerboseLogging()) {
                LOGGER.info("[DONUT PROFIT/TRACKER] Removed Transaction: {} | New Net Profit: {}", tx.getSummaryText(), getFormattedNetProfit());
            }
            saveData();
            notifyListeners();
        }
    }

    public synchronized void removeLatestTransaction() {
        if (!transactions.isEmpty()) {
            Transaction removed = transactions.remove(0);
            recalculateTotals();
            if (ProfitConfig.getInstance().isVerboseLogging()) {
                LOGGER.info("[DONUT PROFIT/TRACKER] Undid Latest Transaction: {} | New Net Profit: {}", removed.getSummaryText(), getFormattedNetProfit());
            }
            saveData();
            notifyListeners();
        }
    }

    private void recalculateTotals() {
        double gained = 0.0;
        double spent = 0.0;
        for (Transaction tx : transactions) {
            if (tx.getType() == TransactionType.SELL) {
                gained += tx.getTotalPrice();
            } else if (tx.getType() == TransactionType.BUY) {
                spent += tx.getTotalPrice();
            }
        }
        this.totalGained = gained;
        this.totalSpent = spent;
    }

    public synchronized void resetSession() {
        totalGained = 0.0;
        totalSpent = 0.0;
        sessionStartTime = System.currentTimeMillis();
        transactions.clear();
        saveData();
        notifyListeners();
        if (ProfitConfig.getInstance().isVerboseLogging()) {
            LOGGER.info("[DONUT PROFIT/TRACKER] Session stats reset successfully.");
        }
    }

    public void addUpdateListener(Runnable listener) {
        if (listener != null && !updateListeners.contains(listener)) {
            updateListeners.add(listener);
        }
    }

    public void removeUpdateListener(Runnable listener) {
        updateListeners.remove(listener);
    }

    private void notifyListeners() {
        for (Runnable listener : updateListeners) {
            try {
                listener.run();
            } catch (Exception e) {
                LOGGER.error("Error notifying profit update listener", e);
            }
        }
    }

    public double getTotalGained() {
        return totalGained;
    }

    public double getTotalSpent() {
        return totalSpent;
    }

    public double getNetProfit() {
        return totalGained - totalSpent;
    }

    public String getFormattedGained() {
        return CURRENCY_FORMAT.format(totalGained);
    }

    public String getFormattedSpent() {
        return CURRENCY_FORMAT.format(totalSpent);
    }

    public String getFormattedNetProfit() {
        double net = getNetProfit();
        String sign = net >= 0 ? "+" : "";
        return sign + CURRENCY_FORMAT.format(net);
    }

    public long getSessionStartTime() {
        return sessionStartTime;
    }

    public List<Transaction> getTransactions() {
        return Collections.unmodifiableList(transactions);
    }

    public Transaction getLatestTransaction() {
        return transactions.isEmpty() ? null : transactions.get(0);
    }

    public Map<String, ItemSummary> getItemSummaries() {
        Map<String, ItemSummary> map = new LinkedHashMap<>();
        for (Transaction tx : transactions) {
            ItemSummary summary = map.computeIfAbsent(tx.getItemName(), ItemSummary::new);
            if (tx.getType() == TransactionType.SELL) {
                summary.totalSellCount += tx.getAmount();
                summary.totalGained += tx.getTotalPrice();
            } else {
                summary.totalBuyCount += tx.getAmount();
                summary.totalSpent += tx.getTotalPrice();
            }
        }
        return map;
    }

    public synchronized void saveData() {
        if (!ProfitConfig.getInstance().isPersistDataOnRestart()) return;
        try {
            File dir = DATA_FILE.getParent().toFile();
            if (!dir.exists()) dir.mkdirs();

            Map<String, Object> data = new HashMap<>();
            data.put("totalGained", totalGained);
            data.put("totalSpent", totalSpent);
            data.put("sessionStartTime", sessionStartTime);
            data.put("transactions", transactions);

            try (FileWriter writer = new FileWriter(DATA_FILE.toFile())) {
                GSON.toJson(data, writer);
            }
        } catch (Exception e) {
            LOGGER.error("Failed to save profit tracker data", e);
        }
    }

    @SuppressWarnings("unchecked")
    public synchronized void loadData() {
        File file = DATA_FILE.toFile();
        if (!file.exists()) return;
        try (FileReader reader = new FileReader(file)) {
            Map<String, Object> data = GSON.fromJson(reader, new TypeToken<Map<String, Object>>(){}.getType());
            if (data != null) {
                if (data.containsKey("totalGained")) totalGained = ((Number) data.get("totalGained")).doubleValue();
                if (data.containsKey("totalSpent")) totalSpent = ((Number) data.get("totalSpent")).doubleValue();
                if (data.containsKey("sessionStartTime")) sessionStartTime = ((Number) data.get("sessionStartTime")).longValue();

                if (data.containsKey("transactions")) {
                    String jsonTx = GSON.toJson(data.get("transactions"));
                    List<Transaction> loadedTx = GSON.fromJson(jsonTx, new TypeToken<List<Transaction>>(){}.getType());
                    if (loadedTx != null) {
                        transactions.clear();
                        transactions.addAll(loadedTx);
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.error("Failed to load profit tracker data", e);
        }
    }
}
