package com.jessetech.recipebalancer.core.solve;

import com.jessetech.recipebalancer.core.bootstrap.PerfectCycleBootstrapper;
import com.jessetech.recipebalancer.core.detect.CyclicDeficitDetector;
import com.jessetech.recipebalancer.core.detect.RawDetector;
import com.jessetech.recipebalancer.core.detect.SeedDetector;
import com.jessetech.recipebalancer.core.matrix.StoichMatrix;
import com.jessetech.recipebalancer.core.model.Constants;
import com.jessetech.recipebalancer.core.model.Objective;
import com.jessetech.recipebalancer.core.model.Recipe;
import com.jessetech.recipebalancer.core.model.Scenario;
import com.jessetech.recipebalancer.core.model.SolveOptions;
import com.jessetech.recipebalancer.core.model.SolveResult;
import com.jessetech.recipebalancer.core.solve.LpSolver.LpConstraint;
import com.jessetech.recipebalancer.core.solve.LpSolver.LpResult;
import com.jessetech.recipebalancer.core.solve.LpSolver.Relation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 求解唯一入口（契约 C3），逐分支对应 Python {@code solver.solve_production}。
 *
 * <p>刻意保留的既有语义：
 * <ul>
 *   <li>P0-A：库存同时加进 b 与 s，在约束与净物流中抵消（"催化剂式"，对结果零影响）；</li>
 *   <li>P0-B：亏损检测使用全量配方列表（含禁用配方）；</li>
 *   <li>P0-C：外部输入 big-M = 1e6，直接写进目标向量；</li>
 *   <li>禁用配方 = 删列（等价 x_j 固定为 0）。</li>
 * </ul>
 */
public final class Balancer {

    private final LpSolver solver;

    public Balancer(LpSolver solver) {
        this.solver = solver;
    }

    /** 默认后端（commons-math3）入口。 */
    public static SolveResult solve(Scenario scenario) {
        return new Balancer(new CommonsMath3LpSolver()).solveScenario(scenario);
    }

    public SolveResult solveScenario(Scenario scenario) {
        List<Recipe> recipes = scenario.recipes();
        SolveOptions options = scenario.options();

        Set<String> userRaw = new LinkedHashSet<>(options.rawMaterials());
        Map<String, Double> userInventory = new LinkedHashMap<>(options.initialInventory());
        Set<Integer> disabled = options.disabledRecipes();

        // ---- 第 1 遍：初始物品与矩阵 ----
        List<String> items = StoichMatrix.collectItems(recipes, scenario.targetProducts(), userInventory);
        StoichMatrix matrix = StoichMatrix.build(recipes, items);

        // ---- 自动检测原料 / 自增殖种子 ----
        Set<String> inferredRaw = options.autoDetectRaw() ? RawDetector.detect(matrix) : Set.of();
        Map<String, Double> inferredSeed = options.autoDetectSeed()
                ? SeedDetector.detect(recipes) : Map.of();

        // ---- 库存 = 种子 ∪ 用户库存；目标 += 库存（P0-A：与 s 抵消）----
        Map<String, Double> inventory = new LinkedHashMap<>(inferredSeed);
        inventory.putAll(userInventory);

        Map<String, Double> effectiveTarget = new LinkedHashMap<>(scenario.targetProducts());
        for (Map.Entry<String, Double> entry : inventory.entrySet()) {
            effectiveTarget.merge(entry.getKey(), entry.getValue(), Double::sum);
        }

        // ---- 第 2 遍：种子可能引入新物品 ----
        items = StoichMatrix.collectItems(recipes, effectiveTarget, inventory);
        matrix = StoichMatrix.build(recipes, items);

        // ---- 循环亏损检测（P0-B：全量配方，不感知 disabled）----
        Set<String> deficits = CyclicDeficitDetector.detect(
                recipes, options.allowedExternalCycInputs(), solver);

        // ---- 外部输入白名单 = 用户原料 ∪ 自动原料 ∪ 循环亏损 − 目标产物 ----
        Set<String> allowedExternal = new LinkedHashSet<>();
        allowedExternal.addAll(userRaw);
        allowedExternal.addAll(inferredRaw);
        allowedExternal.addAll(deficits);
        allowedExternal.removeAll(scenario.targetProducts().keySet());

        double[] b = matrix.targetVector(effectiveTarget);
        double[] s = matrix.inventoryVector(inventory);

        // ---- LP 装配：变量 = 未被禁用的配方列 + 外部输入列 ----
        List<Integer> activeRecipes = new ArrayList<>();
        for (int index = 0; index < recipes.size(); index++) {
            if (!disabled.contains(index)) {
                activeRecipes.add(index);
            }
        }
        List<String> externalItems = new ArrayList<>(new TreeSet<>(allowedExternal));
        int variableCount = activeRecipes.size() + externalItems.size();

        double[] objective = new double[variableCount];
        for (int column = 0; column < activeRecipes.size(); column++) {
            Recipe recipe = recipes.get(activeRecipes.get(column));
            objective[column] = options.objective() == Objective.MACHINE ? recipe.time() : 1.0;
        }
        for (int index = 0; index < externalItems.size(); index++) {
            objective[activeRecipes.size() + index] = Constants.EXTERNAL_PENALTY;
        }

        Map<String, Integer> externalColumn = new LinkedHashMap<>();
        for (int index = 0; index < externalItems.size(); index++) {
            externalColumn.put(externalItems.get(index), index);
        }

        double[][] a = matrix.matrix();
        List<LpConstraint> constraints = new ArrayList<>(items.size());
        int externalOffset = activeRecipes.size();
        for (int row = 0; row < items.size(); row++) {
            double[] coefficients = new double[variableCount];
            for (int column = 0; column < activeRecipes.size(); column++) {
                coefficients[column] = -a[row][activeRecipes.get(column)];
            }
            Integer externalIndex = externalColumn.get(items.get(row));
            if (externalIndex != null) {
                coefficients[externalOffset + externalIndex] = -1.0;
            }
            constraints.add(new LpConstraint(coefficients, Relation.LE, s[row] - b[row]));
        }

        LpResult result = solver.minimize(objective, constraints);
        if (!result.success()) {
            return failure(inferredRaw, inferredSeed, result.message());
        }

        // ---- 解析：展开被删掉的禁用列 ----
        double[] rates = new double[recipes.size()];
        for (int column = 0; column < activeRecipes.size(); column++) {
            rates[activeRecipes.get(column)] = result.point()[column];
        }
        double[] externalValues = new double[externalItems.size()];
        for (int index = 0; index < externalItems.size(); index++) {
            externalValues[index] = result.point()[externalOffset + index];
        }

        Map<String, Double> externalInputs = new LinkedHashMap<>();
        for (int index = 0; index < externalItems.size(); index++) {
            if (externalValues[index] > Constants.EPS_FLOW) {
                externalInputs.put(externalItems.get(index), externalValues[index]);
            }
        }

        // ---- 完美循环启动投料（可选；只依赖活跃配方 + 外部输入）----
        Map<String, Double> bootstrap = Map.of();
        if (options.autoBootstrap()) {
            bootstrap = PerfectCycleBootstrapper.detect(recipes, boxed(rates), externalInputs.keySet());
        }

        // ---- 净物流：net = A·x + s + ext − b ----
        double[] externalVector = new double[items.size()];
        for (int index = 0; index < externalItems.size(); index++) {
            externalVector[matrix.itemIndex().get(externalItems.get(index))] = externalValues[index];
        }
        Map<String, Double> netFlow = new LinkedHashMap<>();
        Map<String, Double> finalOutputs = new LinkedHashMap<>();
        for (int row = 0; row < items.size(); row++) {
            // 与 numpy 的 A @ x + s + ext - b 保持同一累加顺序
            double net = 0.0;
            for (int column = 0; column < recipes.size(); column++) {
                net += a[row][column] * rates[column];
            }
            net += s[row];
            net += externalVector[row];
            net -= b[row];
            if (Math.abs(net) < Constants.EPS_FLOW) {
                continue;
            }
            String item = items.get(row);
            netFlow.put(item, net);
            if (net > 0) {
                finalOutputs.put(item, net);
            }
        }

        // ---- 设备统计：同设备类型共享口径 sum_e ceil(usage_e) ----
        Map<String, Double> equipmentUsage = new LinkedHashMap<>();
        for (int index = 0; index < recipes.size(); index++) {
            if (rates[index] < Constants.EPS_RATE) {
                continue;
            }
            Recipe recipe = recipes.get(index);
            equipmentUsage.merge(recipe.equType(), rates[index] * recipe.time(), Double::sum);
        }
        int totalMachineCount = 0;
        for (double usage : equipmentUsage.values()) {
            totalMachineCount += (int) Math.ceil(usage);
        }

        return new SolveResult(boxed(rates), netFlow, externalInputs, finalOutputs,
                equipmentUsage, totalMachineCount, inferredRaw, inferredSeed, bootstrap,
                true, "OK");
    }

    private static SolveResult failure(Set<String> inferredRaw,
                                       Map<String, Double> inferredSeed,
                                       String message) {
        return new SolveResult(List.of(), Map.of(), Map.of(), Map.of(), Map.of(), 0,
                inferredRaw, inferredSeed, Map.of(), false, message);
    }

    private static List<Double> boxed(double[] values) {
        List<Double> boxed = new ArrayList<>(values.length);
        for (double value : values) {
            boxed.add(value);
        }
        return boxed;
    }
}
