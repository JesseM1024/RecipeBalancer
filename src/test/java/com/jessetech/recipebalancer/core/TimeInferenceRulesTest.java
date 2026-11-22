package com.jessetech.recipebalancer.core;

import com.jessetech.recipebalancer.core.time.TimeInferenceRules;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** JEI 耗时预填的规则表：值域、模组白名单、通用候选。 */
class TimeInferenceRulesTest {

    @Test
    void acceptsReasonableTickCounts() {
        assertEquals(1, TimeInferenceRules.sanitizeTicks(1));
        assertEquals(200, TimeInferenceRules.sanitizeTicks(200));
        assertEquals(72_000, TimeInferenceRules.sanitizeTicks(72_000));
    }

    @Test
    void rejectsNonPositiveAndOversizedValues() {
        assertEquals(-1, TimeInferenceRules.sanitizeTicks(0));
        assertEquals(-1, TimeInferenceRules.sanitizeTicks(-1));
        assertEquals(-1, TimeInferenceRules.sanitizeTicks(72_001));
        assertEquals(-1, TimeInferenceRules.sanitizeTicks(Long.MAX_VALUE));
    }

    @Test
    void mapsKnownModPackagesByPrefix() {
        assertEquals(List.of("getProcessingDuration", "getProcessingTime"),
                TimeInferenceRules.methodsFor("com.simibubi.create.content.processing.Foo"));
        assertEquals(List.of("getTicksRequired"),
                TimeInferenceRules.methodsFor("mekanism.common.recipe.Bar"));
        assertEquals(List.of("getTotalProcessTime", "getTime"),
                TimeInferenceRules.methodsFor("blusunrize.immersiveengineering.common.crafting.Baz"));
    }

    @Test
    void unknownOrMissingClassHasNoModRule() {
        assertTrue(TimeInferenceRules.methodsFor("com.example.other.Recipe").isEmpty());
        assertTrue(TimeInferenceRules.methodsFor(null).isEmpty());
    }

    @Test
    void genericMethodsAvoidAmbiguousGetters() {
        // getTime / getDuration 语义太泛（世界时间、剩余时长），不参与通用探测
        assertFalse(TimeInferenceRules.genericMethods().contains("getTime"));
        assertFalse(TimeInferenceRules.genericMethods().contains("getDuration"));
        assertTrue(TimeInferenceRules.genericMethods().contains("getCookingTime"));
    }

    @Test
    void acceptsPrimitiveAndBoxedNumbersOnly() {
        assertTrue(TimeInferenceRules.isNumeric(int.class));
        assertTrue(TimeInferenceRules.isNumeric(long.class));
        assertTrue(TimeInferenceRules.isNumeric(Integer.class));
        assertTrue(TimeInferenceRules.isNumeric(Number.class));
        assertFalse(TimeInferenceRules.isNumeric(String.class));
        assertFalse(TimeInferenceRules.isNumeric(void.class));
    }
}
