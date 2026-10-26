package com.jessetech.recipebalancer.core.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 编码终端的当前配方草稿：3×3 原料格 + 3×3 产物格 + 设备 + 耗时。
 *
 * <p>耗时在 UI 内部一律用 tick 保存；{@link #toRecipe()} 写入 core 模型时除以 20
 * 转成秒，保证场景 JSON 与 Python 的 schema 仍然同构。
 */
public final class RecipePattern {

    public static final int SLOTS = 9;

    private final String[] inputKeys = new String[SLOTS];
    private final double[] inputAmounts = new double[SLOTS];
    private final String[] outputKeys = new String[SLOTS];
    private final double[] outputAmounts = new double[SLOTS];
    private String equipmentKey = "";
    private double timeTicks = 20.0;
    private boolean truncated;

    public void clear() {
        for (int i = 0; i < SLOTS; i++) {
            inputKeys[i] = null;
            inputAmounts[i] = 0;
            outputKeys[i] = null;
            outputAmounts[i] = 0;
        }
        equipmentKey = "";
        timeTicks = 20.0;
        truncated = false;
    }

    public void load(Recipe recipe) {
        clear();
        int index = 0;
        for (Map.Entry<String, Double> entry : recipe.sourceList().entrySet()) {
            if (index >= SLOTS) {
                truncated = true;
                break;
            }
            inputKeys[index] = entry.getKey();
            inputAmounts[index] = entry.getValue();
            index++;
        }
        index = 0;
        for (Map.Entry<String, Double> entry : recipe.productList().entrySet()) {
            if (index >= SLOTS) {
                truncated = true;
                break;
            }
            outputKeys[index] = entry.getKey();
            outputAmounts[index] = entry.getValue();
            index++;
        }
        equipmentKey = recipe.equType() == null ? "" : recipe.equType();
        timeTicks = recipe.time() * 20.0;
    }

    public String inputKey(int index) {
        return inputKeys[index];
    }

    public double inputAmount(int index) {
        return inputAmounts[index];
    }

    public String outputKey(int index) {
        return outputKeys[index];
    }

    public double outputAmount(int index) {
        return outputAmounts[index];
    }

    public void putInput(int index, String key, double amount) {
        put(inputKeys, inputAmounts, index, key, amount);
    }

    public void putOutput(int index, String key, double amount) {
        put(outputKeys, outputAmounts, index, key, amount);
    }

    private static void put(String[] keys, double[] amounts, int index, String key, double amount) {
        if (index < 0 || index >= SLOTS || key == null || key.isBlank() || !(amount > 0)) {
            return;
        }
        if (key.equals(keys[index])) {
            amounts[index] += amount;
        } else {
            keys[index] = key;
            amounts[index] = amount;
        }
    }

    public void removeInput(int index) {
        inputKeys[index] = null;
        inputAmounts[index] = 0;
    }

    public void removeOutput(int index) {
        outputKeys[index] = null;
        outputAmounts[index] = 0;
    }

    public void setInputAmount(int index, double amount) {
        setAmount(inputKeys, inputAmounts, index, amount);
    }

    public void setOutputAmount(int index, double amount) {
        setAmount(outputKeys, outputAmounts, index, amount);
    }

    private static void setAmount(String[] keys, double[] amounts, int index, double amount) {
        if (keys[index] == null) {
            return;
        }
        if (amount > 0) {
            amounts[index] = amount;
        } else {
            keys[index] = null;
            amounts[index] = 0;
        }
    }

    public int firstEmptyInput() {
        return firstEmpty(inputKeys);
    }

    public int firstEmptyOutput() {
        return firstEmpty(outputKeys);
    }

    private static int firstEmpty(String[] keys) {
        for (int i = 0; i < SLOTS; i++) {
            if (keys[i] == null) {
                return i;
            }
        }
        return -1;
    }

    public String equipmentKey() {
        return equipmentKey;
    }

    public void setEquipmentKey(String equipmentKey) {
        this.equipmentKey = equipmentKey == null ? "" : equipmentKey.trim();
    }

    public double timeTicks() {
        return timeTicks;
    }

    public void setTimeTicks(double timeTicks) {
        if (timeTicks > 0 && Double.isFinite(timeTicks)) {
            this.timeTicks = timeTicks;
        }
    }

    public boolean truncated() {
        return truncated;
    }

    public boolean hasContent() {
        if (!equipmentKey.isBlank()) {
            return true;
        }
        for (int i = 0; i < SLOTS; i++) {
            if (inputKeys[i] != null || outputKeys[i] != null) {
                return true;
            }
        }
        return false;
    }

    /** 生成 core 配方；时间从 tick 转回秒。 */
    public Recipe toRecipe() {
        if (equipmentKey.isBlank()) {
            throw new IllegalArgumentException("请先标记设备");
        }
        if (!(timeTicks > 0)) {
            throw new IllegalArgumentException("耗时必须为正数");
        }
        Map<String, Double> inputs = new LinkedHashMap<>();
        Map<String, Double> outputs = new LinkedHashMap<>();
        for (int i = 0; i < SLOTS; i++) {
            if (inputKeys[i] != null) {
                inputs.merge(inputKeys[i], inputAmounts[i], Double::sum);
            }
            if (outputKeys[i] != null) {
                outputs.merge(outputKeys[i], outputAmounts[i], Double::sum);
            }
        }
        if (outputs.isEmpty()) {
            throw new IllegalArgumentException("至少标记 1 个产物");
        }
        return new Recipe(inputs, outputs, timeTicks / 20.0, equipmentKey);
    }
}
