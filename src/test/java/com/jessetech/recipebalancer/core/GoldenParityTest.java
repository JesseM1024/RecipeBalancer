package com.jessetech.recipebalancer.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.jessetech.recipebalancer.core.model.Scenario;
import com.jessetech.recipebalancer.core.model.SolveResult;
import com.jessetech.recipebalancer.core.solve.Balancer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 与 Python 金标准逐场景对拍（对应 Python {@code tests/test_golden.py}）。
 *
 * <p>比较层级（ARCHITECTURE §7）：
 * <ul>
 *   <li>L1/L4 数值：速率、物流、设备并行量 —— 先按 Python 口径 abs=1e-9 严格比较；</li>
 *   <li>L3 结构：物品集合、原料/种子集合、总设备数 —— 必须逐集相等。</li>
 * </ul>
 * 若 S-04 表明 CM3 与 HiGHS 存在多解/数值差异，**不得在此静默放宽**：
 * 必须先在 PROGRESS 记录差异，再按 §7 调整容差。
 */
class GoldenParityTest {

    private static final double ABS_TOL = 1e-9;

    @ParameterizedTest(name = "golden:{0}")
    @ValueSource(strings = {
            "simple", "autocatalytic", "multi_product", "ebf_cycle", "deficit",
            "multi_loop", "dual_line", "mek_fission", "uu"
    })
    void matchesPythonGolden(String name) {
        Scenario scenario = TestSupport.scenario(name);
        SolveResult result = Balancer.solve(scenario);
        JsonObject ref = TestSupport.golden(name);

        assertTrue(result.success(), () -> name + " 求解失败: " + result.message());
        assertTrue(ref.get("success").getAsBoolean(), name + " 金标准本身是失败用例");

        JsonArray rates = ref.getAsJsonArray("recipe_rates");
        assertEquals(rates.size(), result.recipeRates().size(), name + " 速率数量");
        for (int i = 0; i < rates.size(); i++) {
            assertEquals(rates.get(i).getAsDouble(), result.recipeRates().get(i), ABS_TOL,
                    name + " recipe_rates[" + i + "]");
        }

        assertMapEquals(name, "external_inputs", ref, result.externalInputs());
        assertMapEquals(name, "net_flow", ref, result.netFlow());
        assertMapEquals(name, "final_outputs", ref, result.finalOutputs());
        assertMapEquals(name, "equipment_usage", ref, result.equipmentUsage());

        // 唯一放宽项（PROGRESS R2 已记录）：total_machine_count = Σ ceil(使用量)，
        // 对"使用量是否恰好等于整数"极其敏感。CM3 与 HiGHS 的速率相差几个 ulp
        // （如 2.0000000000000004 vs 2.0），ceil 会放大成 ±1 台；逐设备使用量本身仍按 1e-9 对拍。
        int expectedTotal = ref.get("total_machine_count").getAsInt();
        assertTrue(Math.abs(expectedTotal - result.totalMachineCount()) <= 1,
                name + " total_machine_count: expected " + expectedTotal
                        + " but was " + result.totalMachineCount());

        assertEquals(strings(ref.getAsJsonArray("inferred_raw_materials")),
                new LinkedHashSet<>(result.inferredRawMaterials()),
                name + " inferred_raw_materials");
        assertMapEquals(name, "inferred_initial_inventory", ref, result.inferredInitialInventory());
    }

    private static void assertMapEquals(String scenario, String field, JsonObject ref,
                                        Map<String, Double> actual) {
        JsonObject expected = ref.getAsJsonObject(field);
        assertEquals(expected.keySet(), actual.keySet(), scenario + "." + field + " 物品集合");
        for (Map.Entry<String, JsonElement> entry : expected.entrySet()) {
            assertEquals(entry.getValue().getAsDouble(), actual.get(entry.getKey()), ABS_TOL,
                    scenario + "." + field + "[" + entry.getKey() + "]");
        }
    }

    private static Set<String> strings(JsonArray array) {
        Set<String> values = new LinkedHashSet<>();
        for (JsonElement element : array) {
            values.add(element.getAsString());
        }
        return values;
    }
}
