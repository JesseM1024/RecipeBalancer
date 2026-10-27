package com.jessetech.recipebalancer.mc.itemmap;

import com.jessetech.recipebalancer.core.model.Recipe;
import com.jessetech.recipebalancer.core.model.Scenario;
import com.jessetech.recipebalancer.core.model.SolveOptions;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ScenarioKeyNormalizerTest {

    @Test
    void targetAndRecipeUseSameIdentityDespiteDisplayName() {
        String english = IngredientKey.encode("chemical",
                "chemical:mekanism:fissile_fuel", "Fissile Fuel");
        String chinese = IngredientKey.encode("chemical",
                "chemical:mekanism:fissile_fuel", "裂变燃料");

        Map<String, Double> source = new LinkedHashMap<>();
        source.put(english, 0.001);
        Map<String, Double> product = new LinkedHashMap<>();
        product.put(english, 0.001);
        Recipe recipe = new Recipe(source, product, 0.001, "test:machine");

        Map<String, Double> targets = new LinkedHashMap<>();
        targets.put(chinese, 1.0);
        Scenario normalized = ScenarioKeyNormalizer.normalize(
                new Scenario(List.of(recipe), targets, SolveOptions.defaults()));

        // 目标键优先作为代表，配方产物键应被改写为同一个 key。
        assertEquals(chinese, normalized.recipes().get(0).productList().keySet().iterator().next());
        assertEquals(chinese, normalized.targetProducts().keySet().iterator().next());
        assertEquals(1, normalized.recipes().get(0).productList().size());
    }
}
