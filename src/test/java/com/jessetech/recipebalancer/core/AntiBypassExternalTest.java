package com.jessetech.recipebalancer.core;

import com.jessetech.recipebalancer.core.model.Objective;
import com.jessetech.recipebalancer.core.model.Recipe;
import com.jessetech.recipebalancer.core.model.Scenario;
import com.jessetech.recipebalancer.core.model.SolveOptions;
import com.jessetech.recipebalancer.core.model.SolveResult;
import com.jessetech.recipebalancer.core.report.TextReporter;
import com.jessetech.recipebalancer.core.solve.Balancer;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 中间产物不得被「买断」：外部采购若让它的生产配方停摆，求解器要改为买它的原料并重启该配方。
 *
 * <p>场景：TiO2 → TiCl4 → Ti 链，电解 3 MgCl2 只出 1.3 Cl2（氯亏损）。
 * 修复前会把 TiCl4 当循环亏损外部输入直接买进来，氯化配方停摆（rate=0）；
 * 修复后只买 TiO2 / C（真原料）与 Cl2（循环亏损补充），三条配方全部运行。
 */
class AntiBypassExternalTest {

    private static Recipe recipe(Map<String, Double> in, Map<String, Double> out,
                                 double time, String machine) {
        return new Recipe(in, out, time, machine);
    }

    @Test
    void intermediateWithProducerIsNotPurchased() {
        Recipe chlorination = recipe(
                new LinkedHashMap<>(Map.of("TiO2", 1.0, "Cl2", 2.0, "C", 1.0)),
                new LinkedHashMap<>(Map.of("TiCl4", 1.0, "CO2", 1.0)), 1.0, "chemical_oxidizer");
        Recipe reduction = recipe(
                new LinkedHashMap<>(Map.of("TiCl4", 1.0, "Mg", 2.0)),
                new LinkedHashMap<>(Map.of("Ti", 1.0, "MgCl2", 2.0)), 1.0, "chemical_reaction");
        Recipe electrolysis = recipe(
                new LinkedHashMap<>(Map.of("MgCl2", 3.0)),
                new LinkedHashMap<>(Map.of("Mg", 3.0, "Cl2", 1.3)), 1.0, "electrolytic_separator");

        SolveOptions options = new SolveOptions(Set.of(), Map.of(), Set.of(), Set.of(),
                Objective.MACHINE, true, true, false);
        SolveResult result = Balancer.solve(new Scenario(
                List.of(chlorination, reduction, electrolysis),
                new LinkedHashMap<>(Map.of("Ti", 1.0)), options));

        assertTrue(result.success(), "有氯亏损补充应可解");
        assertFalse(result.externalInputs().containsKey("TiCl4"),
                "TiCl4 有上级配方，不应被当成外部输入");
        assertTrue(result.externalInputs().getOrDefault("Cl2", 0.0) > 0.0,
                "循环亏损应通过补充 Cl2 解决");
        // 三条配方都应在运行（没有被买断的工序）
        for (Double rate : result.recipeRates()) {
            assertTrue(rate > 1e-8, "配方不应因外部采购而停摆: " + result.recipeRates());
        }
        assertEquals(3, result.recipeRates().size());

        // 文本结果里要能看到循环亏损的参考信息
        String report = TextReporter.formatPerTick(result,
                List.of(chlorination, reduction, electrolysis));
        assertTrue(report.contains(">>循环亏损物质（检测结果）"), "缺少循环亏损物质段落");
        assertTrue(report.contains(">>亏损环涉及的物质"), "缺少亏损环物质段落");
        assertTrue(report.contains("Cl2"), "亏损环里应列出 Cl2");
        try {
            java.nio.file.Files.writeString(java.nio.file.Path.of("build/report.txt"), report);
        } catch (java.io.IOException ignored) {
            // 仅调试用
        }
    }
}
