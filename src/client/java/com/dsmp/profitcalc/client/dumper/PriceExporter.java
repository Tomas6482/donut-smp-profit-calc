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
        sb.append("DONUT SMP PRICE DUMP REPORT\n");
        sb.append("Generated: ").append(DATE_FMT.format(new Date())).append("\n");
        sb.append("Total Items Scanned: ").append(results.size()).append("\n");
        sb.append("======================================================\n\n");

        for (DumpResult res : results) {
            sb.append(String.format("[%-5s] %s\n", res.getSource(), res.getItemName()));
            if (res.getSampleSize() == 0) {
                sb.append("  Sample Size : 0 listings (No valid matches)\n\n");
                continue;
            }

            sb.append(String.format("  Sample Size : %d listings\n", res.getSampleSize()));
            sb.append(String.format("  Avg Top 10  : %s\n", CURRENCY_FMT.format(res.getAvgTop10())));

            sb.append("  Top 5 Prices: ");
            List<Double> top5 = res.getTop5();
            for (int i = 0; i < top5.size(); i++) {
                sb.append(CURRENCY_FMT.format(top5.get(i)));
                if (i < top5.size() - 1) sb.append(", ");
            }
            sb.append("\n");

            sb.append(String.format("  Range       : Min %s - Max %s\n\n",
                    CURRENCY_FMT.format(res.getLowest()), CURRENCY_FMT.format(res.getHighest())));
        }

        sb.append("======================================================\n");
        return sb.toString();
    }
}
