package com.jessetech.recipebalancer.core.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * 与 Python {@code RecipeSolution} 逐字段对应（字段顺序保持 {@code asdict} 顺序）。
 *
 * <p>{@code netFlow} / {@code externalInputs} / {@code finalOutputs} / {@code equipmentUsage}
 * 均为插入有序的 map：Python 侧 dict 顺序参与报告输出，Java 侧沿用。
 *
 * <p>1.16.5 线是 Java 8，没有 record：等价手写值类，访问器名保持与原来一致。
 */
public final class SolveResult {

    private final List<Double> recipeRates;
    private final Map<String, Double> netFlow;
    private final Map<String, Double> externalInputs;
    private final Map<String, Double> finalOutputs;
    private final Map<String, Double> equipmentUsage;
    private final long totalMachineCount;
    private final Set<String> inferredRawMaterials;
    private final Map<String, Double> inferredInitialInventory;
    private final Map<String, Double> bootstrapInventory;
    private final Set<String> deficitItems;
    private final List<Set<String>> deficitCycles;
    private final boolean success;
    private final String message;

    public SolveResult(List<Double> recipeRates,
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
        this.recipeRates = Collections.unmodifiableList(new ArrayList<>(recipeRates));
        this.netFlow = ordered(netFlow);
        this.externalInputs = ordered(externalInputs);
        this.finalOutputs = ordered(finalOutputs);
        this.equipmentUsage = ordered(equipmentUsage);
        this.totalMachineCount = totalMachineCount;
        this.inferredRawMaterials =
                Collections.unmodifiableSet(new TreeSet<>(inferredRawMaterials));
        this.inferredInitialInventory = ordered(inferredInitialInventory);
        this.bootstrapInventory = ordered(bootstrapInventory);
        this.deficitItems = Collections.unmodifiableSet(new TreeSet<>(deficitItems));
        this.deficitCycles = Collections.unmodifiableList(new ArrayList<>(deficitCycles));
        this.success = success;
        this.message = message;
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
                bootstrapInventory, Collections.<String>emptySet(),
                Collections.<Set<String>>emptyList(), success, message);
    }

    private static Map<String, Double> ordered(Map<String, Double> source) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    public List<Double> recipeRates() {
        return recipeRates;
    }

    public Map<String, Double> netFlow() {
        return netFlow;
    }

    public Map<String, Double> externalInputs() {
        return externalInputs;
    }

    public Map<String, Double> finalOutputs() {
        return finalOutputs;
    }

    public Map<String, Double> equipmentUsage() {
        return equipmentUsage;
    }

    public long totalMachineCount() {
        return totalMachineCount;
    }

    public Set<String> inferredRawMaterials() {
        return inferredRawMaterials;
    }

    public Map<String, Double> inferredInitialInventory() {
        return inferredInitialInventory;
    }

    public Map<String, Double> bootstrapInventory() {
        return bootstrapInventory;
    }

    public Set<String> deficitItems() {
        return deficitItems;
    }

    public List<Set<String>> deficitCycles() {
        return deficitCycles;
    }

    public boolean success() {
        return success;
    }

    public String message() {
        return message;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SolveResult)) {
            return false;
        }
        SolveResult other = (SolveResult) o;
        return totalMachineCount == other.totalMachineCount
                && success == other.success
                && recipeRates.equals(other.recipeRates)
                && netFlow.equals(other.netFlow)
                && externalInputs.equals(other.externalInputs)
                && finalOutputs.equals(other.finalOutputs)
                && equipmentUsage.equals(other.equipmentUsage)
                && inferredRawMaterials.equals(other.inferredRawMaterials)
                && inferredInitialInventory.equals(other.inferredInitialInventory)
                && bootstrapInventory.equals(other.bootstrapInventory)
                && deficitItems.equals(other.deficitItems)
                && deficitCycles.equals(other.deficitCycles)
                && Objects.equals(message, other.message);
    }

    @Override
    public int hashCode() {
        return Objects.hash(recipeRates, netFlow, externalInputs, finalOutputs, equipmentUsage,
                totalMachineCount, inferredRawMaterials, inferredInitialInventory,
                bootstrapInventory, deficitItems, deficitCycles, success, message);
    }

    @Override
    public String toString() {
        return "SolveResult[success=" + success + ", message=" + message
                + ", recipeRates=" + recipeRates + ", totalMachineCount=" + totalMachineCount + "]";
    }
}
