package com.dsmp.profitcalc.client.dumper;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileWriter;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class PriceExporter {
    private static final Logger LOGGER = LoggerFactory.getLogger("donut-smp-profit-calc/PriceExporter");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final DecimalFormat CURRENCY_FMT = new DecimalFormat("$#,##0.00");
    private static final SimpleDateFormat DATE_FMT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);

    public static File exportToTxt(List<DumpResult> results) {
        Minecraft mc = Minecraft.getInstance();
        File exportFile = FabricLoader.getInstance().getGameDir().resolve("donut_price_dump.txt").toFile();

        try (FileWriter writer = new FileWriter(exportFile)) {
            writer.write(generateTxtReport(results));

            LOGGER.info("[Price Dumper] Dumped TXT report to: {}", exportFile.getAbsolutePath());
            if (mc.player != null) {
                mc.player.displayClientMessage(Component.literal("§a[Price Dumper] Saved TXT report to: §e" + exportFile.getName()), false);
            }
            return exportFile;
        } catch (Exception e) {
            LOGGER.error("Failed to export price dump to TXT", e);
            if (mc.player != null) {
                mc.player.displayClientMessage(Component.literal("§c[Price Dumper] Failed to save TXT report: " + e.getMessage()), false);
            }
            return null;
        }
    }

    public static File exportToJson(List<DumpResult> results) {
        Minecraft mc = Minecraft.getInstance();
        File exportFile = FabricLoader.getInstance().getGameDir().resolve("donut_price_dump.json").toFile();

        try (FileWriter writer = new FileWriter(exportFile)) {
            GSON.toJson(results, writer);
            LOGGER.info("[Price Dumper] Dumped JSON report to: {}", exportFile.getAbsolutePath());
            if (mc.player != null) {
                mc.player.displayClientMessage(Component.literal("§a[Price Dumper] Saved JSON report to: §e" + exportFile.getName()), false);
            }
            return exportFile;
        } catch (Exception e) {
            LOGGER.error("Failed to export price dump to JSON", e);
            if (mc.player != null) {
                mc.player.displayClientMessage(Component.literal("§c[Price Dumper] Failed to save JSON report: " + e.getMessage()), false);
            }
            return null;
        }
    }

    public static void copyTxtToClipboard(List<DumpResult> results) {
        Minecraft mc = Minecraft.getInstance();
        String txt = generateTxtReport(results);
        if (mc.keyboardHandler != null) {
            mc.keyboardHandler.setClipboard(txt);
        }
        if (mc.player != null) {
            mc.player.displayClientMessage(Component.literal("§a[Price Dumper] Copied TXT report to clipboard!"), false);
        }
    }

    public static void copyJsonToClipboard(List<DumpResult> results) {
        Minecraft mc = Minecraft.getInstance();
        String json = GSON.toJson(results);
        if (mc.keyboardHandler != null) {
            mc.keyboardHandler.setClipboard(json);
        }
        if (mc.player != null) {
            mc.player.displayClientMessage(Component.literal("§a[Price Dumper] Copied JSON report to clipboard!"), false);
        }
    }

    private static String generateTxtReport(List<DumpResult> results) {
        StringBuilder sb = new StringBuilder();
        sb.append("======================================================\n");
        sb.append("DONUT SMP ACCURATE 15-ITEM PRICE DUMP REPORT\n");
        sb.append("Generated: ").append(DATE_FMT.format(new Date())).append("\n");
        sb.append("Total Queries Scanned: ").append(results.size()).append("\n");
        sb.append("======================================================\n\n");

        for (DumpResult res : results) {
            sb.append(String.format("[%s] Query: %s\n", res.getSource(), res.getQuery()));
            List<DumpResult.ItemDumpInfo> items = res.getItems();

            if (items.isEmpty()) {
                sb.append("  Items Scanned: 0 (No valid matches found on Page 1)\n\n");
                continue;
            }

            sb.append("  First ").append(items.size()).append(" Items Scanned:\n");
            int idx = 1;
            for (DumpResult.ItemDumpInfo item : items) {
                sb.append(String.format("  --------------------------------------------------\n"));
                sb.append(String.format("  #%d: %s (%s)\n", idx++, item.getName(), item.getItemId()));
                sb.append(String.format("      Max Stack Size : %d\n", item.getMaxStackSize()));
                sb.append(String.format("      Listed Qty     : %d\n", item.getQuantity()));
                sb.append(String.format("      Unit Price     : %s\n", CURRENCY_FMT.format(item.getUnitPrice())));
                sb.append("      Stack Multiplier Prices:\n");

                for (Map.Entry<Integer, Double> entry : item.getStackPrices().entrySet()) {
                    sb.append(String.format("        %-4dx : %s\n", entry.getKey(), CURRENCY_FMT.format(entry.getValue())));
                }
            }
            sb.append("  --------------------------------------------------\n\n");
        }

        sb.append("======================================================\n");
        return sb.toString();
    }
}
