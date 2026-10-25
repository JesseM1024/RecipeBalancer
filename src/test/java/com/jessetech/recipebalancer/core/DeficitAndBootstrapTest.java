package com.jessetech.recipebalancer.core;

import com.jessetech.recipebalancer.core.bootstrap.PerfectCycleBootstrapper;
import com.jessetech.recipebalancer.core.detect.CyclicDeficitDetector;
import com.jessetech.recipebalancer.core.detect.RawDetector;
import com.jessetech.recipebalancer.core.detect.SeedDetector;
import com.jessetech.recipebalancer.core.matrix.MaterialMatrix;
import com.jessetech.recipebalancer.core.model.Recipe;
import com.jessetech.recipebalancer.core.model.Scenario;
import com.jessetech.recipebalancer.core.model.SolveOptions;
import com.jessetech.recipebalancer.core.model.SolveResult;
import com.jessetech.recipebalancer.core.solve.Balancer;
import com.jessetech.recipebalancer.core.solve.CommonsMath3LpSolver;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 检测器与完美循环启动投料（对应 Python {@code tests/test_units.py} 相关段落）。 */
class DeficitAndBootstrapTest {

    private static final Set<String> NO_WHITELIST = Set.of();

    // ------------------------------------------------------------------
    // 原料 / 种子 / 亏损检测
    // ------------------------------------------------------------------

    @Test
    void rawMaterialsDetectedFromMatrix() {
        Scenario scenario = TestSupport.scenario("simple");
        List<String> items = MaterialMatrix.collectItems(scenario.recipes(), scenario.targetProducts(), Map.of());
        MaterialMatrix matrix = MaterialMatrix.build(scenario.recipes(), items);
        assertEquals(Set.of("空"), RawDetector.detect(matrix));
    }

    @Test
    void autocatalyticSeedsDetected() {
        assertEquals(Map.of("锻造模版", 1.0), SeedDetector.detect(TestSupport.scenario("autocatalytic").recipes()));
        assertEquals(Map.of("SST", 1.0), SeedDetector.detect(TestSupport.scenario("dual_line").recipes()));
    }

    @Test
    void deficitOfPerfectCycleIsEmpty() {
        assertEquals(Set.of(), CyclicDeficitDetector.detect(
                TestSupport.scenario("ebf_cycle").recipes(), NO_WHITELIST, new CommonsMath3LpSolver()));
    }

    @Test
    void deficitOfLossyCycleMatchesPython() {
        // 已知量纲效应（P0-D 忠实保留）：实测亏损集为 {C, MgCl2, TiCl4}，而非直觉的 {C, Cl2, Mg}
        assertEquals(Set.of("C", "MgCl2", "TiCl4"), CyclicDeficitDetector.detect(
                TestSupport.scenario("deficit").recipes(), NO_WHITELIST, new CommonsMath3LpSolver()));
    }

    @Test
    void deficitWithWhitelistOnlyReportsWhitelistedItems() {
        assertEquals(Set.of("Cl2"), CyclicDeficitDetector.detect(
                TestSupport.scenario("multi_loop").recipes(), Set.of("Cl2"),
                new CommonsMath3LpSolver()));
    }

    // ------------------------------------------------------------------
    // 完美循环启动投料
    // ------------------------------------------------------------------

    @Test
    void ebfBootstrapMinimalSeedSet() {
        Scenario scenario = TestSupport.scenario("ebf_cycle");
        SolveResult solution = Balancer.solve(scenario);
        assertTrue(solution.success());
        Map<String, Double> cycles = PerfectCycleBootstrapper.detect(
                scenario.recipes(), solution.recipeRates(), solution.externalInputs().keySet());
        assertEquals(Map.of("C", 2.0, "MgCl2", 3.0), cycles);
    }

    @Test
    void bootstrapViaSolveFlag() {
        Scenario scenario = TestSupport.scenario("ebf_cycle");
        SolveResult off = Balancer.solve(scenario);
        assertEquals(Map.of(), off.bootstrapInventory());

        SolveOptions options = scenario.options();
        SolveOptions on = new SolveOptions(options.rawMaterials(), options.initialInventory(),
                options.disabledRecipes(), options.allowedExternalCycInputs(), options.objective(),
                options.autoDetectRaw(), options.autoDetectSeed(), true);
        SolveResult result = Balancer.solve(new Scenario(scenario.recipes(), scenario.targetProducts(), on));
        assertEquals(Map.of("C", 2.0, "MgCl2", 3.0), result.bootstrapInventory());
        assertTrue(!result.bootstrapInventory().containsKey("TiO2"), "外部输入不应出现");
        assertTrue(!result.bootstrapInventory().containsKey("TiCl4"), "过程料不应作为启动项");
    }

    @Test
    void autocatalyticBootstrapIsOneBatch() {
        Scenario scenario = TestSupport.scenario("autocatalytic");
        SolveResult solution = Balancer.solve(scenario);
        assertTrue(solution.success());
        assertEquals(Map.of("锻造模版", 1.0), PerfectCycleBootstrapper.detect(
                scenario.recipes(), solution.recipeRates(), solution.externalInputs().keySet()));
    }

    @Test
    void dualLineBootstrapIsSst() {
        Scenario scenario = TestSupport.scenario("dual_line");
        SolveResult solution = Balancer.solve(scenario);
        assertTrue(solution.success());
        assertEquals(Map.of("SST", 1.0), PerfectCycleBootstrapper.detect(
                scenario.recipes(), solution.recipeRates(), solution.externalInputs().keySet()));
    }

    @Test
    void multiLoopBootstrapIsThreePairs() {
        Scenario scenario = TestSupport.scenario("multi_loop");
        SolveResult solution = Balancer.solve(scenario);
        assertTrue(solution.success());
        assertEquals(Map.of("C", 2.0, "Mg", 2.0, "Na", 2.0), PerfectCycleBootstrapper.detect(
                scenario.recipes(), solution.recipeRates(), solution.externalInputs().keySet()));
    }

    @Test
    void acyclicScenariosHaveNoBootstrap() {
        for (String name : List.of("simple", "mek_fission", "uu")) {
            Scenario scenario = TestSupport.scenario(name);
            SolveResult solution = Balancer.solve(scenario);
            assertTrue(solution.success(), name);
            assertEquals(Map.of(), PerfectCycleBootstrapper.detect(
                    scenario.recipes(), solution.recipeRates(), solution.externalInputs().keySet()), name);
        }
    }

    @Test
    void inactiveRecipesDoNotCreatePaperCycles() {
        Scenario scenario = TestSupport.scenario("autocatalytic");
        SolveResult solution = Balancer.solve(scenario);
        List<Double> rates = new ArrayList<>(solution.recipeRates());
        rates.set(0, 0.0);
        assertEquals(Map.of(), PerfectCycleBootstrapper.detect(
                scenario.recipes(), rates, solution.externalInputs().keySet()));
    }

    @Test
    void structureOnlyIgnoresItemNames() {
        Recipe a = new Recipe(Map.of("源岩", 1.0), Map.of("幻想甲", 2.0), 1.0, "祭坛");
        Recipe b = new Recipe(Map.of("幻想甲", 2.0), Map.of("幻想乙", 1.0), 1.0, "熔炉");
        Recipe c = new Recipe(new LinkedHashMap<>(Map.of("幻想乙", 1.0, "源岩", 1.0)),
                new LinkedHashMap<>(Map.of("幻想甲", 1.0, "终点", 1.0)), 1.0, "反应釜");
        List<Recipe> recipes = List.of(a, b, c);
        Scenario scenario = new Scenario(recipes, Map.of("终点", 1.0), SolveOptions.defaults());
        SolveResult solution = Balancer.solve(scenario);
        assertTrue(solution.success(), solution.message());

        // 幻想乙 单批用量 1 < 幻想甲 单批用量 2 => 只投 幻想乙
        assertEquals(Map.of("幻想乙", 1.0), PerfectCycleBootstrapper.detect(
                recipes, solution.recipeRates(), solution.externalInputs().keySet()));
    }

    @Test
    void externalInputsBreakCycles() {
        Scenario scenario = TestSupport.scenario("deficit");
        SolveResult solution = Balancer.solve(scenario);
        assertEquals(Map.of(), PerfectCycleBootstrapper.detect(
                scenario.recipes(), solution.recipeRates(), solution.externalInputs().keySet()));
    }

    @Test
    void allRecipesInactiveYieldsNoBootstrap() {
        Scenario scenario = TestSupport.scenario("ebf_cycle");
        List<Double> zeros = new ArrayList<>();
        for (int i = 0; i < scenario.recipes().size(); i++) {
            zeros.add(0.0);
        }
        assertEquals(Map.of(), PerfectCycleBootstrapper.detect(
                scenario.recipes(), zeros, new LinkedHashSet<>()));
    }
}
