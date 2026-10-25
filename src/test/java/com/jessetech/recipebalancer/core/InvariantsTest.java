package com.jessetech.recipebalancer.core;

import com.jessetech.recipebalancer.core.detect.CyclicDeficitDetector;
import com.jessetech.recipebalancer.core.detect.RawDetector;
import com.jessetech.recipebalancer.core.matrix.StoichMatrix;
import com.jessetech.recipebalancer.core.model.Constants;
import com.jessetech.recipebalancer.core.model.Recipe;
import com.jessetech.recipebalancer.core.model.Scenario;
import com.jessetech.recipebalancer.core.model.SolveOptions;
import com.jessetech.recipebalancer.core.model.SolveResult;
import com.jessetech.recipebalancer.core.solve.Balancer;
import com.jessetech.recipebalancer.core.solve.CommonsMath3LpSolver;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 不变量：不依赖具体期望值，任何场景都必须成立（对应 Python {@code tests/test_invariants.py}）。
 *
 * <p>金标准锁定"与 Python 一致"，不变量锁定"数学上自洽"；两者互补。
 */
class InvariantsTest {

    private static final double TOL = 1e-6;

    @ParameterizedTest(name = "invariants:{0}")
    @ValueSource(strings = {
            "simple", "autocatalytic", "multi_product", "ebf_cycle", "deficit",
            "multi_loop", "dual_line", "mek_fission", "uu"
    })
    void massBalanceAndEquipmentAreConsistent(String name) {
        Scenario scenario = TestSupport.scenario(name);
        SolveResult result = Balancer.solve(scenario);
        assertTrue(result.success(), name + ": " + result.message());

        // ---- 净物流恒等式：net = A·x + ext − target（库存是催化式语义，在 b 与 s 中抵消）----
        List<String> items = StoichMatrix.collectItems(scenario.recipes(), scenario.targetProducts(), Map.of());
        StoichMatrix matrix = StoichMatrix.build(scenario.recipes(), items);
        double[][] a = matrix.matrix();
        double[] x = new double[scenario.recipes().size()];
        for (int i = 0; i < x.length; i++) {
            x[i] = result.recipeRates().get(i);
        }
        double[] external = new double[items.size()];
        for (Map.Entry<String, Double> entry : result.externalInputs().entrySet()) {
            external[matrix.itemIndex().get(entry.getKey())] = entry.getValue();
        }

        for (int row = 0; row < items.size(); row++) {
            double net = 0.0;
            for (int column = 0; column < x.length; column++) {
                net += a[row][column] * x[column];
            }
            net += external[row];
            net -= scenario.targetProducts().getOrDefault(items.get(row), 0.0);

            assertEquals(net, result.netFlow().getOrDefault(items.get(row), 0.0), TOL,
                    name + " 净物流恒等式: " + items.get(row));
            assertTrue(net >= -TOL, name + " 物品亏损: " + items.get(row) + " = " + net);
        }
        for (double value : result.netFlow().values()) {
            assertTrue(Math.abs(value) >= Constants.EPS_FLOW - 1e-12,
                    name + " 净物流阈值过滤失效: " + value);
        }

        // ---- 设备统计可由速率重算 ----
        Map<String, Double> recomputed = new LinkedHashMap<>();
        for (int index = 0; index < x.length; index++) {
            if (x[index] < Constants.EPS_RATE) {
                continue;
            }
            Recipe recipe = scenario.recipes().get(index);
            recomputed.merge(recipe.equType(), x[index] * recipe.time(), Double::sum);
        }
        assertEquals(recomputed.keySet(), result.equipmentUsage().keySet(), name + " 设备集合");
        for (Map.Entry<String, Double> entry : recomputed.entrySet()) {
            assertEquals(entry.getValue(), result.equipmentUsage().get(entry.getKey()), TOL,
                    name + " 设备并行量: " + entry.getKey());
        }
        int total = 0;
        for (double usage : result.equipmentUsage().values()) {
            total += (int) Math.ceil(usage);
        }
        assertEquals(total, result.totalMachineCount(), name + " 总设备数");
    }

    @ParameterizedTest(name = "whitelist:{0}")
    @ValueSource(strings = {
            "simple", "autocatalytic", "multi_product", "ebf_cycle", "deficit",
            "multi_loop", "dual_line", "mek_fission", "uu"
    })
    void externalInputsStayInsideWhitelist(String name) {
        Scenario scenario = TestSupport.scenario(name);
        SolveResult result = Balancer.solve(scenario);
        assertTrue(result.success(), name + ": " + result.message());

        List<String> items = StoichMatrix.collectItems(scenario.recipes(), scenario.targetProducts(), Map.of());
        StoichMatrix matrix = StoichMatrix.build(scenario.recipes(), items);
        Set<String> allowed = new LinkedHashSet<>(scenario.options().rawMaterials());
        allowed.addAll(RawDetector.detect(matrix));
        allowed.addAll(CyclicDeficitDetector.detect(scenario.recipes(),
                scenario.options().allowedExternalCycInputs(), new CommonsMath3LpSolver()));
        allowed.removeAll(scenario.targetProducts().keySet());

        for (String item : result.externalInputs().keySet()) {
            assertTrue(allowed.contains(item), name + " 非白名单外部输入: " + item);
        }
    }

    /** P0-A 现状文档化：库存是催化剂式语义，对求解结果零影响（修复须单独立项）。 */
    @org.junit.jupiter.api.Test
    void initialInventoryIsInert() {
        Scenario base = TestSupport.scenario("simple");
        SolveOptions defaults = base.options();
        SolveOptions withInventory = new SolveOptions(defaults.rawMaterials(),
                Map.of("圆石", 100.0, "铁锭", 50.0), defaults.disabledRecipes(),
                defaults.allowedExternalCycInputs(), defaults.objective(),
                defaults.autoDetectRaw(), defaults.autoDetectSeed(), defaults.autoBootstrap());

        SolveResult a = Balancer.solve(base);
        SolveResult b = Balancer.solve(new Scenario(base.recipes(), base.targetProducts(), withInventory));

        assertEquals(a.recipeRates().size(), b.recipeRates().size());
        for (int i = 0; i < a.recipeRates().size(); i++) {
            assertEquals(a.recipeRates().get(i), b.recipeRates().get(i), 1e-9);
        }
        assertEquals(a.externalInputs().keySet(), b.externalInputs().keySet());
    }
}
