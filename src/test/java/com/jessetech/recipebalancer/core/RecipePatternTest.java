package com.jessetech.recipebalancer.core;

import com.jessetech.recipebalancer.core.model.Recipe;
import com.jessetech.recipebalancer.core.model.RecipePattern;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 编码终端草稿：tick/秒换算、格子合并、9 格上限。 */
class RecipePatternTest {

    @Test
    void loadsRecipeAndConvertsSecondsToTicks() {
        Recipe recipe = new Recipe(Map.of("ore", 2.0), Map.of("ingot", 1.0), 1.5, "minecraft:furnace");
        RecipePattern pattern = new RecipePattern();
        pattern.load(recipe);

        assertEquals("ore", pattern.inputKey(0));
        assertEquals(2.0, pattern.inputAmount(0), 1e-12);
        assertEquals("ingot", pattern.outputKey(0));
        assertEquals(30.0, pattern.timeTicks(), 1e-12);
        assertEquals("minecraft:furnace", pattern.equipmentKey());

        Recipe roundTrip = pattern.toRecipe();
        assertEquals(recipe.sourceList(), roundTrip.sourceList());
        assertEquals(recipe.productList(), roundTrip.productList());
        assertEquals(1.5, roundTrip.time(), 1e-12);
        assertEquals(recipe.equType(), roundTrip.equType());
    }

    @Test
    void mergesSameItemAndReplacesDifferentItem() {
        RecipePattern pattern = new RecipePattern();
        pattern.setEquipmentKey("minecraft:furnace");
        pattern.putInput(0, "ore", 1.0);
        pattern.putInput(0, "ore", 2.0);
        assertEquals(3.0, pattern.inputAmount(0), 1e-12);

        pattern.putInput(0, "dust", 4.0);
        assertEquals("dust", pattern.inputKey(0));
        assertEquals(4.0, pattern.inputAmount(0), 1e-12);

        pattern.removeInput(0);
        assertEquals(null, pattern.inputKey(0));
    }

    @Test
    void outputOverSixteenMarksTruncated() {
        Map<String, Double> products = new LinkedHashMap<>();
        for (int i = 0; i < 17; i++) {
            products.put("item" + i, 1.0);
        }
        RecipePattern pattern = new RecipePattern();
        pattern.load(new Recipe(Map.of(), products, 1.0, "x"));
        assertTrue(pattern.truncated());
        assertEquals(RecipePattern.MAX_OUTPUTS, pattern.outputCount());
        assertTrue(pattern.hasContent());
    }

    @Test
    void toRecipeRequiresEquipmentAndOutput() {
        RecipePattern pattern = new RecipePattern();
        assertThrows(IllegalArgumentException.class, pattern::toRecipe);

        pattern.setEquipmentKey("x");
        assertThrows(IllegalArgumentException.class, pattern::toRecipe);

        pattern.putOutput(0, "ingot", 1.0);
        assertEquals(1.0, pattern.toRecipe().time(), 1e-12);
    }
}
