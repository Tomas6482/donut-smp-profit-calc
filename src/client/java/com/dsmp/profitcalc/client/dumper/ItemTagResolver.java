package com.dsmp.profitcalc.client.dumper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class ItemTagResolver {

    private static final List<String> LOGS = Arrays.asList(
            "oak_log", "spruce_log", "birch_log", "jungle_log", "acacia_log",
            "dark_oak_log", "mangrove_log", "cherry_log", "pale_oak_log",
            "crimson_stem", "warped_stem"
    );

    private static final List<String> PLANKS = Arrays.asList(
            "oak_planks", "spruce_planks", "birch_planks", "jungle_planks", "acacia_planks",
            "dark_oak_planks", "mangrove_planks", "cherry_planks", "pale_oak_planks",
            "crimson_planks", "warped_planks"
    );

    private static final List<String> ORES = Arrays.asList(
            "coal", "iron_ingot", "gold_ingot", "diamond", "emerald", "netherite_ingot",
            "raw_iron", "raw_gold", "raw_copper", "lapis_lazuli", "redstone", "quartz"
    );

    private static final List<String> REDSTONE = Arrays.asList(
            "redstone", "repeater", "comparator", "piston", "sticky_piston",
            "observer", "dropper", "dispenser", "hopper", "target", "tnt"
    );

    public static List<String> resolveInputLines(String rawInput) {
        Set<String> resultSet = new LinkedHashSet<>();
        if (rawInput == null || rawInput.trim().isEmpty()) return new ArrayList<>();

        String[] tokens = rawInput.split("[,;\\r\\n]+");
        for (String token : tokens) {
            String clean = token.trim().toLowerCase();
            if (clean.isEmpty()) continue;

            if (clean.startsWith("#")) {
                expandTag(clean, resultSet);
            } else {
                String canonicalId = clean.replace(" ", "_").trim();
                if (canonicalId.startsWith("minecraft:")) {
                    canonicalId = canonicalId.substring(10);
                }
                if (!canonicalId.isEmpty()) {
                    resultSet.add(canonicalId);
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
                String cleanTag = tag.substring(1).toLowerCase().replace(" ", "_").trim();
                if (cleanTag.startsWith("minecraft:")) {
                    cleanTag = cleanTag.substring(10);
                }
                if (!cleanTag.isEmpty()) {
                    resultSet.add(cleanTag);
                }
                break;
        }
    }
}
