package com.jessetech.recipebalancer.core.detect;

import com.jessetech.recipebalancer.core.graph.DirectedGraph;
import com.jessetech.recipebalancer.core.model.Constants;
import com.jessetech.recipebalancer.core.model.Recipe;
import com.jessetech.recipebalancer.core.solve.LpSolver;
import com.jessetech.recipebalancer.core.solve.LpSolver.LpConstraint;
import com.jessetech.recipebalancer.core.solve.LpSolver.LpResult;
import com.jessetech.recipebalancer.core.solve.LpSolver.Relation;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * 循环亏损检测（Python {@code detection.detect_deficit_items}）：
 * SCC 分解 + 每个环 SCC 一个子 LP。
 *
 * <pre>
 * min 1^T d
 * s.t. A_sub x + d = 0
 *      1^T x = 1              （排除全零平凡解）
 *      x >= 0, d >= 0
 *      非白名单物品: d_i = 0    （实现为**删列**）
 * </pre>
 *
 * <p>{@code allowedExternalInputs} 语义：非空 = 限制；空 = 不限制（全部亏损放行）。
 * P0-B 忠实保留：接收全量配方列表（含禁用配方），不感知 disabled。
 */
public final class CyclicDeficitDetector {

    private CyclicDeficitDetector() {
    }

    public static Set<String> detect(List<Recipe> recipes,
                                     Set<String> allowedExternalInputs,
                                     LpSolver solver) {
        Set<String> allowed = allowedExternalInputs == null ? Set.of() : allowedExternalInputs;
        boolean restricted = !allowed.isEmpty();

        DirectedGraph<String> graph = new DirectedGraph<>();
        for (Recipe recipe : recipes) {
            for (String source : recipe.sourceList().keySet()) {
                for (String product : recipe.productList().keySet()) {
                    graph.addEdge(source, product);
                }
            }
        }

        Set<String> deficits = new LinkedHashSet<>();
        for (Set<String> scc : graph.stronglyConnectedComponents()) {
            if (!isCycle(graph, scc)) {
                continue;
            }

            List<Integer> internalRecipes = new ArrayList<>();
            for (int index = 0; index < recipes.size(); index++) {
                Recipe recipe = recipes.get(index);
                Set<String> involved = new LinkedHashSet<>(recipe.sourceList().keySet());
                involved.addAll(recipe.productList().keySet());
                involved.retainAll(scc);
                if (!involved.isEmpty()) {
                    internalRecipes.add(index);
                }
            }
            if (internalRecipes.isEmpty()) {
                continue;
            }

            Set<String> produced = new LinkedHashSet<>();
            Set<String> consumed = new LinkedHashSet<>();
            for (int recipeIndex : internalRecipes) {
                Recipe recipe = recipes.get(recipeIndex);
                for (String item : recipe.productList().keySet()) {
                    if (scc.contains(item)) {
                        produced.add(item);
                    }
                }
                for (String item : recipe.sourceList().keySet()) {
                    if (scc.contains(item)) {
                        consumed.add(item);
                    }
                }
            }
            produced.retainAll(consumed);
            List<String> cyclicItems = new ArrayList<>(new TreeSet<>(produced));
            if (cyclicItems.isEmpty()) {
                continue;
            }

            detectInCycle(recipes, internalRecipes, cyclicItems, allowed, restricted, solver, deficits);
        }
        return deficits;
    }

    private static void detectInCycle(List<Recipe> recipes,
                                      List<Integer> internalRecipes,
                                      List<String> cyclicItems,
                                      Set<String> allowed,
                                      boolean restricted,
                                      LpSolver solver,
                                      Set<String> deficits) {
        int itemCount = cyclicItems.size();
        int recipeCount = internalRecipes.size();

        double[][] sub = new double[itemCount][recipeCount];
        for (int j = 0; j < recipeCount; j++) {
            Recipe recipe = recipes.get(internalRecipes.get(j));
            for (int i = 0; i < itemCount; i++) {
                String item = cyclicItems.get(i);
                Double produced = recipe.productList().get(item);
                if (produced != null) {
                    sub[i][j] += produced;
                }
                Double consumed = recipe.sourceList().get(item);
                if (consumed != null) {
                    sub[i][j] -= consumed;
                }
            }
        }

        // 变量 z = [x, d]；非白名单物品的 d 直接删列（等价 bounds (0,0)）。
        int[] deficitVar = new int[itemCount];
        int variableCount = recipeCount;
        java.util.Arrays.fill(deficitVar, -1);
        for (int i = 0; i < itemCount; i++) {
            if (!restricted || allowed.contains(cyclicItems.get(i))) {
                deficitVar[i] = variableCount++;
            }
        }

        double[] objective = new double[variableCount];
        for (int i = 0; i < itemCount; i++) {
            if (deficitVar[i] >= 0) {
                objective[deficitVar[i]] = 1.0;
            }
        }

        List<LpConstraint> constraints = new ArrayList<>(itemCount + 1);
        for (int i = 0; i < itemCount; i++) {
            double[] row = new double[variableCount];
            System.arraycopy(sub[i], 0, row, 0, recipeCount);
            if (deficitVar[i] >= 0) {
                row[deficitVar[i]] = 1.0;
            }
            constraints.add(new LpConstraint(row, Relation.EQ, 0.0));
        }
        double[] sumX = new double[variableCount];
        for (int j = 0; j < recipeCount; j++) {
            sumX[j] = 1.0;
        }
        constraints.add(new LpConstraint(sumX, Relation.EQ, 1.0));

        LpResult result = solver.minimize(objective, constraints);
        if (!result.success()) {
            // 非白名单亏损导致不可行 => 跳过该 SCC（该物品不进外部白名单）
            return;
        }
        for (int i = 0; i < itemCount; i++) {
            int variable = deficitVar[i];
            if (variable >= 0 && result.point()[variable] > Constants.EPS_DEFICIT) {
                deficits.add(cyclicItems.get(i));
            }
        }
    }

    private static boolean isCycle(DirectedGraph<String> graph, Set<String> scc) {
        if (scc.size() > 1) {
            return true;
        }
        String node = scc.iterator().next();
        return graph.hasEdge(node, node);
    }
}
