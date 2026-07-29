package com.dsmp.profitcalc.client.flipfinder;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.lang.reflect.Type;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class FlipRecipeRegistry {
    private static final Logger LOGGER = LoggerFactory.getLogger("donut-smp-profit-calc/FlipRecipeRegistry");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static class JsonRecipe {
        String output;
        double outputQty = 1;
        List<JsonIngredient> ingredients;
    }

    private static class JsonIngredient {
        String item;
        double qty = 1;
    }

    public static List<FlipRecipe> getAll() {
        Map<String, FlipRecipe> recipeMap = new LinkedHashMap<>();

        // Load Custom JSON Recipes (which includes the 7 default plugin/vanilla flips + any user additions)
        List<FlipRecipe> customRecipes = loadCustomRecipes();
        for (FlipRecipe r : customRecipes) {
            recipeMap.put(r.outputItem, r);
        }

        LOGGER.info("[FlipRecipeRegistry] Loaded {} total candidate recipes.", recipeMap.size());
        return new ArrayList<>(recipeMap.values());
    }

    private static List<FlipRecipe> loadCustomRecipes() {
        List<FlipRecipe> result = new ArrayList<>();
        Path configDir = FabricLoader.getInstance().getConfigDir().resolve("dsmp-profitcalc");
        File jsonFile = configDir.resolve("custom_recipes.json").toFile();

        try {
            if (!jsonFile.exists()) {
                configDir.toFile().mkdirs();
                seedDefaultCustomRecipes(jsonFile);
            }

            try (FileReader reader = new FileReader(jsonFile)) {
                Type listType = new TypeToken<List<JsonRecipe>>() {}.getType();
                List<JsonRecipe> jsonList = GSON.fromJson(reader, listType);
                if (jsonList != null) {
                    for (JsonRecipe jr : jsonList) {
                        if (jr.output == null || jr.output.trim().isEmpty() || jr.ingredients == null || jr.ingredients.isEmpty()) {
                            continue;
                        }
                        String outItem = jr.output.trim().toLowerCase();
                        double outQty = Math.max(1.0, jr.outputQty);
                        List<FlipRecipe.Ingredient> ings = new ArrayList<>();

                        for (JsonIngredient ji : jr.ingredients) {
                            if (ji.item != null && !ji.item.trim().isEmpty()) {
                                ings.add(new FlipRecipe.Ingredient(ji.item.trim().toLowerCase(), Math.max(1.0, ji.qty)));
                            }
                        }

                        if (!ings.isEmpty()) {
                            result.add(new FlipRecipe(outItem, outQty, ings));
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.error("Failed to load custom recipes from {}", jsonFile.getAbsolutePath(), e);
        }

        return result;
    }

    private static void seedDefaultCustomRecipes(File file) {
        String seedJson = """
        [
          {
            "output": "bone_block",
            "outputQty": 1,
            "ingredients": [
              { "item": "bone", "qty": 9 }
            ]
          },
          {
            "output": "dried_kelp_block",
            "outputQty": 1,
            "ingredients": [
              { "item": "dried_kelp", "qty": 9 }
            ]
          },
          {
            "output": "dried_kelp",
            "outputQty": 1,
            "ingredients": [
              { "item": "kelp", "qty": 1 }
            ]
          },
          {
            "output": "oak_planks",
            "outputQty": 4,
            "ingredients": [
              { "item": "oak_log", "qty": 1 }
            ]
          },
          {
            "output": "sticky_piston",
            "outputQty": 1,
            "ingredients": [
              { "item": "piston", "qty": 1 },
              { "item": "slimeball", "qty": 1 }
            ]
          },
          {
            "output": "golden_apple",
            "outputQty": 1,
            "ingredients": [
              { "item": "gold_ingot", "qty": 8 },
              { "item": "apple", "qty": 1 }
            ]
          },
          {
            "output": "bookshelf",
            "outputQty": 1,
            "ingredients": [
              { "item": "oak_planks", "qty": 6 },
              { "item": "book", "qty": 3 }
            ]
          }
        ]
        """;

        try (FileWriter writer = new FileWriter(file)) {
            writer.write(seedJson);
            LOGGER.info("[FlipRecipeRegistry] Seeded default custom recipes at {}", file.getAbsolutePath());
        } catch (Exception e) {
            LOGGER.error("Failed to seed custom_recipes.json", e);
        }
    }
}
