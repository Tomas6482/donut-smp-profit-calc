package com.dsmp.profitcalc.client.dumper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class ItemTagResolver {

    private static final List<String> LOGS = Arrays.asList(
            "oak log", "spruce log", "birch log", "jungle log", "acacia log",
            "dark oak log", "mangrove log", "cherry log", "pale oak log",
            "crimson stem", "warped stem"
    );

    private static final List<String> PLANKS = Arrays.asList(
            "oak planks", "spruce planks", "birch planks", "jungle planks", "acacia planks",
            "dark oak planks", "mangrove planks", "cherry planks", "pale oak planks",
            "crimson planks", "warped planks"
    );

    private static final List<String> ORES = Arrays.asList(
            "coal", "iron ingot", "gold ingot", "diamond", "emerald", "netherite ingot",
            "raw iron", "raw gold", "raw copper", "lapis lazuli", "redstone", "quartz"
    );

    private static final List<String> REDSTONE = Arrays.asList(
            "redstone", "repeater", "comparator", "piston", "sticky piston",
            "observer", "dropper", "dispenser", "hopper", "target", "tnt"
    );

    public static List<String> resolveInputLines(String rawInput) {
        Set<String> resultSet = new LinkedHashSet<>();
        if (rawInput == null || rawInput.trim().isEmpty()) return new ArrayList<>();

        // Split by commas, newlines, or semicolons
        String[] tokens = rawInput.split("[,;\\r\\n]+");
        for (String token : tokens) {
            String clean = token.trim().toLowerCase();
            if (clean.isEmpty()) continue;

            if (clean.startsWith("#")) {
                expandTag(clean, resultSet);
            } else {
                // Normalize snake_case IDs to space-separated names (e.g. oak_log -> oak log)
                String normalized = clean.replace("_", " ").trim();
                if (!normalized.isEmpty()) {
                    resultSet.add(normalized);
                }
            }
        }

        return new ArrayList<>(resultSet);
    }

    private static void expandTag(String tag, Set<String> resultSet) {
        switch (tag.toLowerCase()) {
            case "#logs":
                resultSet.addAll(LOGS);
                break;
            case "#planks":
                resultSet.addAll(PLANKS);
                break;
            case "#wood":
                resultSet.addAll(LOGS);
                resultSet.addAll(PLANKS);
                break;
            case "#ores":
            case "#minerals":
                resultSet.addAll(ORES);
                break;
            case "#redstone":
                resultSet.addAll(REDSTONE);
                break;
            case "#all":
                resultSet.addAll(LOGS);
                resultSet.addAll(PLANKS);
                resultSet.addAll(ORES);
                resultSet.addAll(REDSTONE);
                break;
            default:
                String cleanTag = tag.substring(1).replace("_", " ").trim();
                if (!cleanTag.isEmpty()) {
                    resultSet.add(cleanTag);
                }
                break;
        }
    }
}
