package com.jessetech.recipebalancer.core;

import com.jessetech.recipebalancer.core.io.SchemaException;
import com.jessetech.recipebalancer.core.io.ScenarioJson;
import com.jessetech.recipebalancer.core.model.Recipe;
import com.jessetech.recipebalancer.core.model.Scenario;
import com.jessetech.recipebalancer.core.model.SolveOptions;
import com.jessetech.recipebalancer.core.model.SolveResult;
import com.jessetech.recipebalancer.core.report.TextReporter;
import com.jessetech.recipebalancer.core.solve.Balancer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 场景 JSON 互导（A2）与文本报告。 */
class IoAndReportTest {

    @ParameterizedTest(name = "roundtrip:{0}")
    @ValueSource(strings = {
            "simple", "autocatalytic", "multi_product", "ebf_cycle", "deficit",
            "multi_loop", "dual_line", "mek_fission", "uu"
    })
    void scenarioSurvivesJsonRoundTrip(String name) {
        Scenario original = TestSupport.scenario(name);
        Scenario reloaded = ScenarioJson.load(ScenarioJson.dump(original));

        assertEquals(original.recipes().size(), reloaded.recipes().size());
        for (int i = 0; i < original.recipes().size(); i++) {
            Recipe expected = original.recipes().get(i);
            Recipe actual = reloaded.recipes().get(i);
            assertEquals(expected.sourceList(), actual.sourceList(), name + " sourceList[" + i + "]");
            assertEquals(expected.productList(), actual.productList(), name + " productList[" + i + "]");
            assertEquals(expected.time(), actual.time(), name + " time[" + i + "]");
            assertEquals(expected.equType(), actual.equType(), name + " equType[" + i + "]");
        }
        assertEquals(original.targetProducts(), reloaded.targetProducts(), name + " targets");
        assertEquals(original.options(), reloaded.options(), name + " options");
    }

    /** 落盘供 A2 反向验证（上游 Python {@code load_scenario} 读取）。 */
    @Test
    void dumpArtifactsForPythonInteropCheck() throws IOException {
        Path directory = Path.of("build", "test-artifacts", "scenarios");
        Files.createDirectories(directory);
        for (String name : TestSupport.SCENARIOS) {
            String json = ScenarioJson.dump(TestSupport.scenario(name));
            Files.writeString(directory.resolve(name + ".json"), json, StandardCharsets.UTF_8);
        }
    }

    @Test
    void schemaRejectsMalformedRecipes() {
        String missingKey = """
                {"recipes":[{"sourceList":{"a":1},"productList":{"b":1},"time":1}],
                 "target_products":{"b":1}}
                """;
        assertThrows(SchemaException.class, () -> ScenarioJson.load(missingKey));

        String zeroTime = """
                {"recipes":[{"sourceList":{"a":1},"productList":{"b":1},"time":0,"equType":"x"}],
                 "target_products":{"b":1}}
                """;
        assertThrows(SchemaException.class, () -> ScenarioJson.load(zeroTime));

        String emptyProducts = """
                {"recipes":[{"sourceList":{"a":1},"productList":{},"time":1,"equType":"x"}],
                 "target_products":{"b":1}}
                """;
        assertThrows(SchemaException.class, () -> ScenarioJson.load(emptyProducts));

        String negativeAmount = """
                {"recipes":[{"sourceList":{"a":-1},"productList":{"b":1},"time":1,"equType":"x"}],
                 "target_products":{"b":1}}
                """;
        assertThrows(SchemaException.class, () -> ScenarioJson.load(negativeAmount));

        String unknownOption = """
                {"recipes":[{"sourceList":{"a":1},"productList":{"b":1},"time":1,"equType":"x"}],
                 "target_products":{"b":1},"options":{"nope":true}}
                """;
        assertThrows(SchemaException.class, () -> ScenarioJson.load(unknownOption));

        String emptyRecipes = """
                {"recipes":[],"target_products":{"b":1}}
                """;
        assertThrows(SchemaException.class, () -> ScenarioJson.load(emptyRecipes));
    }

    @Test
    void emptySourceListIsAllowed() {
        String json = """
                {"recipes":[{"sourceList":{},"productList":{"b":1},"time":1,"equType":"x"}],
                 "target_products":{"b":1}}
                """;
        Scenario scenario = ScenarioJson.load(json);
        assertEquals(1, scenario.recipes().size());
        assertTrue(scenario.recipes().get(0).sourceList().isEmpty());
    }

    @Test
    void reportContainsAllSections() {
        Scenario scenario = TestSupport.scenario("simple");
        SolveResult result = Balancer.solve(scenario);
        String report = TextReporter.format(result, scenario.recipes());

        for (String section : List.of("配方运行方案", "汇总数据", "原料输入", "副产物产出", "设备统计",
                "最小总设备数", "最小单配方总设备数")) {
            assertTrue(report.contains(section), "缺少段落: " + section);
        }
        assertTrue(report.contains("圆石"), "原料输入应包含圆石");
    }

    @Test
    void reportFailureShowsMessage() {
        SolveResult failure = new SolveResult(List.of(), Map.of(), Map.of(), Map.of(), Map.of(), 0,
                java.util.Set.of(), Map.of(), Map.of(), false, "infeasible");
        assertTrue(TextReporter.format(failure, List.of()).contains("求解失败"));
    }

    /** 目标物品既无产线、也不在白名单（关闭自动原料）=> LP 不可行。 */
    @Test
    void infeasibleScenarioReturnsFailure() {
        Recipe recipe = new Recipe(Map.of("a", 1.0), Map.of("b", 1.0), 1.0, "x");
        SolveOptions options = new SolveOptions(java.util.Set.of(), Map.of(), java.util.Set.of(),
                java.util.Set.of(), com.jessetech.recipebalancer.core.model.Objective.MACHINE,
                false, false, false);
        Scenario scenario = new Scenario(List.of(recipe), Map.of("c", 1.0), options);

        SolveResult result = Balancer.solve(scenario);
        assertFalse(result.success());
        assertTrue(result.recipeRates().isEmpty());
        assertTrue(result.externalInputs().isEmpty());
    }
}
