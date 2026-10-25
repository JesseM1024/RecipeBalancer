package com.jessetech.recipebalancer.core.report;

import com.jessetech.recipebalancer.core.model.Constants;
import com.jessetech.recipebalancer.core.model.Recipe;
import com.jessetech.recipebalancer.core.model.SolveResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 文本报告（Python {@code report.format_solution} 的移植）。
 *
 * <p>机器数口径与 notebook 一致：
 * <ul>
 *   <li>"最小总设备数" = sum_e ceil(该设备类型并行数和)——同设备共享时的最小值；</li>
 *   <li>"最小单配方总设备数" = sum_r ceil(单配方并行数)——逐配方向上取整的保守值。</li>
 * </ul>
 *
 * <p>已知差异：Python 的 {@code f"{x:.3f}"} 用 round-half-even，Java
 * {@code String.format} 用 half-up——只在恰好 0.0005 边界上不同，报告不做逐字符对拍。
 */
public final class TextReporter {

    private static final String RULE = "=".repeat(60);

    private TextReporter() {
    }

    public static String format(SolveResult solution, List<Recipe> recipes) {
        if (!solution.success()) {
            return "\n求解失败:\n" + solution.message() + "\n";
        }

        List<String> lines = new ArrayList<>();
        lines.add("");
        lines.add(RULE);
        lines.add("配方运行方案");
        lines.add(RULE);

        Map<String, List<MachineDetail>> machineDetail = new LinkedHashMap<>();
        int totalMachineCount = 0;

        for (int index = 0; index < solution.recipeRates().size(); index++) {
            double rate = solution.recipeRates().get(index);
            if (rate < Constants.EPS_RATE) {
                continue;
            }
            Recipe recipe = recipes.get(index);

            double parallel = rate * recipe.time();
            int required = (int) Math.ceil(parallel);
            totalMachineCount += required;

            machineDetail.computeIfAbsent(recipe.equType(), ignored -> new ArrayList<>())
                    .add(new MachineDetail(index, parallel, required));

            lines.add("");
            lines.add("[R" + index + "]");
            lines.add("设备类型: " + recipe.equType());
            lines.add("设备并行数: " + fixed3(parallel));
            lines.add("需要设备数: " + required);

            for (Map.Entry<String, Double> entry : recipe.sourceList().entrySet()) {
                lines.add("  - " + entry.getKey() + ": " + fixed3(entry.getValue() * rate));
            }
            for (Map.Entry<String, Double> entry : recipe.productList().entrySet()) {
                lines.add("  + " + entry.getKey() + ": " + fixed3(entry.getValue() * rate));
            }
        }

        lines.add("");
        lines.add(RULE);
        lines.add("汇总数据");
        lines.add(RULE);

        lines.add("");
        lines.add(">>自动检测外部原料");
        lines.add("");
        if (solution.inferredRawMaterials().isEmpty()) {
            lines.add("无");
        } else {
            for (String item : new TreeSet<>(solution.inferredRawMaterials())) {
                lines.add("  " + item);
            }
        }

        lines.add("");
        lines.add(">>自增殖配方启动库存");
        lines.add("");
        appendAmounts(lines, solution.inferredInitialInventory());

        lines.add("");
        lines.add(">>完美循环启动投料（最小集合）");
        lines.add("");
        appendAmounts(lines, solution.bootstrapInventory());

        lines.add("");
        lines.add(">>原料输入");
        lines.add("");
        appendAmounts(lines, solution.externalInputs());

        lines.add("");
        lines.add(">>副产物产出");
        lines.add("");
        appendAmounts(lines, solution.finalOutputs());

        lines.add("");
        lines.add(">>设备统计");
        int minEquipmentParallel = 0;
        for (String equipment : new TreeSet<>(machineDetail.keySet())) {
            lines.add("");
            lines.add("> " + equipment);

            double parallelSum = 0.0;
            int requiredSum = 0;
            List<MachineDetail> details = machineDetail.get(equipment);
            for (MachineDetail detail : details) {
                parallelSum += detail.parallel();
                requiredSum += detail.required();
                lines.add("");
                lines.add("   [R" + detail.recipeId() + "]");
                lines.add("      实际并行数: " + fixed3(detail.parallel()));
                lines.add("      需要设备数: " + detail.required());
            }
            if (details.size() > 1) {
                lines.add("");
                lines.add("   实际并行数和: " + fixed3(parallelSum));
                lines.add("");
                lines.add("   最小设备数和: " + (int) Math.ceil(parallelSum));
                lines.add("   单配方设备数和: " + requiredSum);
            }
            minEquipmentParallel += (int) Math.ceil(parallelSum);
        }

        lines.add("");
        lines.add(">>最小总设备数: " + minEquipmentParallel);
        lines.add(">>最小单配方总设备数: " + totalMachineCount);

        StringBuilder builder = new StringBuilder();
        for (String line : lines) {
            builder.append(line).append('\n');
        }
        return builder.toString();
    }

    private static void appendAmounts(List<String> lines, Map<String, Double> amounts) {
        if (amounts.isEmpty()) {
            lines.add("无");
            return;
        }
        for (Map.Entry<String, Double> entry : new TreeMap<>(amounts).entrySet()) {
            lines.add("  " + entry.getKey() + ": " + fixed3(entry.getValue()));
        }
    }

    private static String fixed3(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private record MachineDetail(int recipeId, double parallel, int required) {
    }
}
