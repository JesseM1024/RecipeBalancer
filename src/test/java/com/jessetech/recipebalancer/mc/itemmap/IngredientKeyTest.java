package com.jessetech.recipebalancer.mc.itemmap;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IngredientKeyTest {

    @Test
    void encodesAndDecodesNonItemIdentity() {
        String key = IngredientKey.encode("fluid_stack", "minecraft:water", "Water");
        assertTrue(IngredientKey.isIngredientKey(key));
        assertEquals("fluid_stack", IngredientKey.typeUid(key));
        assertEquals("minecraft:water", IngredientKey.uniqueId(key));
        assertEquals("Water", IngredientKey.displayName(key));
        assertEquals("fluid_stack:minecraft:water", IngredientKey.describe(key));
    }
}
