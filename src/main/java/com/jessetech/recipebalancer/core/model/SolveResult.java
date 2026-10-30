package com.jessetech.recipebalancer.core.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 与 Python {@code RecipeSolution} 逐字段对应（字段顺序保持 {@code asdict} 顺序）。
 *
 * <p>{@code netFlow} / {@code externalInputs} / {@code finalOutputs} / {@code equipmentUsage}
 * 均为插入有序的 map：Python 侧 dict 顺序参与报告输出，Java 侧沿用。
 * 注意不能用 {@link Map#copyOf}（不保证迭代顺序）。
 */
public record SolveResult(List<Double> recipeRates,
                          Map<String, Double> netFlow,
                          Map<String, Double> externalInputs,
                          Map<String, Double> finalOutputs,
                          Map<String, Double> equipmentUsage,
                          long totalMachineCount,
                          Set<String> inferredRawMaterials,
                          Map<String, Double> inferredInitialInventory,
                          Map<String, Double> bootstrapInventory,
                          Set<String> deficitItems,
                          List<Set<String>> deficitCycles,
                          boolean success,
                          String message) {

    public SolveResult {
        recipeRates = List.copyOf(recipeRates);
        netFlow = ordered(netFlow);
        externalInputs = ordered(externalInputs);
        finalOutputs = ordered(finalOutputs);
        equipmentUsage = ordered(equipmentUsage);
        inferredRawMaterials = Collections.unmodifiableSet(new TreeSet<>(inferredRawMaterials));
        inferredInitialInventory = ordered(inferredInitialInventory);
        bootstrapInventory = ordered(bootstrapInventory);
        deficitItems = Collections.unmodifiableSet(new TreeSet<>(deficitItems));
        deficitCycles = List.copyOf(deficitCycles);
    }

    /** 旧签名（不含循环亏损信息）便于失败路径与测试构造。 */
    public SolveResult(List<Double> recipeRates, Map<String, Double> netFlow,
                       Map<String, Double> externalInputs, Map<String, Double> finalOutputs,
                       Map<String, Double> equipmentUsage, long totalMachineCount,
                       Set<String> inferredRawMaterials,
                       Map<String, Double> inferredInitialInventory,
                       Map<String, Double> bootstrapInventory,
                       boolean success, String message) {
        this(recipeRates, netFlow, externalInputs, finalOutputs, equipmentUsage,
                totalMachineCount, inferredRawMaterials, inferredInitialInventory,
                bootstrapInventory, Set.of(), List.of(), success, message);
    }

    private static Map<String, Double> ordered(Map<String, Double> source) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }
}
