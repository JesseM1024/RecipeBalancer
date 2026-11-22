package com.jessetech.recipebalancer.core;

import com.jessetech.recipebalancer.core.model.Recipe;
import com.jessetech.recipebalancer.core.model.RecipePattern;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
        // 新配方默认耗时 1 tick = 0.05 秒
        assertEquals(0.05, pattern.toRecipe().time(), 1e-12);
    }

    @Test
    void timeHintIsOnlyAHintAndNeverEntersRecipe() {
        RecipePattern pattern = new RecipePattern();
        pattern.setEquipmentKey("x");
        pattern.putOutput(0, "ingot", 1.0);
        pattern.setTimeHint(200, "minecraft:smelting");

        assertEquals(200.0, pattern.timeHint().ticks(), 1e-12);
        assertEquals("minecraft:smelting", pattern.timeHint().source());
        // 参考值不进 core 配方：toRecipe 仍按手动设置的 1t 换算成 0.05 秒
        assertEquals(0.05, pattern.toRecipe().time(), 1e-12);

        // 整体拷贝会带上参考值（JEI 自动撰写走 copyFrom）
        RecipePattern copy = new RecipePattern();
        copy.copyFrom(pattern);
        assertNotNull(copy.timeHint());

        // 清空/从配方库载入后不再带参考标记
        copy.clear();
        assertNull(copy.timeHint());
        assertEquals(1.0, copy.timeTicks(), 1e-12);
    }
}
