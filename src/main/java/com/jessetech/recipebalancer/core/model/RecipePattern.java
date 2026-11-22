package com.jessetech.recipebalancer.core.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 编码终端的当前配方草稿：最多 256 种原料 + 16 种产物 + 设备 + 耗时。
 *
 * <p>耗时在 UI 内部一律用 tick 保存；{@link #toRecipe()} 写入 core 模型时除以 20
 * 转成秒，保证场景 JSON 与 Python 的 schema 仍然同构。
 *
 * <p>格子用稀疏数组保存：空格保留位置，删除不会让后面的物品前移；
 * 这样滚动浏览时格子的位置稳定，玩家不会误点。
 */
public final class RecipePattern {

    public static final int MAX_INPUTS = 256;
    public static final int MAX_OUTPUTS = 16;

    private final String[] inputKeys = new String[MAX_INPUTS];
    private final double[] inputAmounts = new double[MAX_INPUTS];
    private final String[] outputKeys = new String[MAX_OUTPUTS];
    private final double[] outputAmounts = new double[MAX_OUTPUTS];
    private String equipmentKey = "";
    private double timeTicks = 1.0;
    private boolean truncated;

    /** 用另一份样板整体覆盖当前内容（JEI 自动撰写用）。 */
    public void copyFrom(RecipePattern other) {
        clear();
        for (int i = 0; i < MAX_INPUTS; i++) {
            if (other.inputKeys[i] != null) {
                inputKeys[i] = other.inputKeys[i];
                inputAmounts[i] = other.inputAmounts[i];
            }
        }
        for (int i = 0; i < MAX_OUTPUTS; i++) {
            if (other.outputKeys[i] != null) {
                outputKeys[i] = other.outputKeys[i];
                outputAmounts[i] = other.outputAmounts[i];
            }
        }
        equipmentKey = other.equipmentKey;
        timeTicks = other.timeTicks;
        truncated = other.truncated;
    }

    public void clear() {
        clear(inputKeys, inputAmounts);
        clear(outputKeys, outputAmounts);
        equipmentKey = "";
        timeTicks = 1.0;
        truncated = false;
    }

    private static void clear(String[] keys, double[] amounts) {
        for (int i = 0; i < keys.length; i++) {
            keys[i] = null;
            amounts[i] = 0;
        }
    }

    public void load(Recipe recipe) {
        clear();
        int index = 0;
        for (Map.Entry<String, Double> entry : recipe.sourceList().entrySet()) {
            if (index >= MAX_INPUTS) {
                truncated = true;
                break;
            }
            inputKeys[index] = entry.getKey();
            inputAmounts[index] = entry.getValue();
            index++;
        }
        index = 0;
        for (Map.Entry<String, Double> entry : recipe.productList().entrySet()) {
            if (index >= MAX_OUTPUTS) {
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
        return index >= 0 && index < MAX_INPUTS ? inputKeys[index] : null;
    }

    public double inputAmount(int index) {
        return index >= 0 && index < MAX_INPUTS ? inputAmounts[index] : 0;
    }

    public String outputKey(int index) {
        return index >= 0 && index < MAX_OUTPUTS ? outputKeys[index] : null;
    }

    public double outputAmount(int index) {
        return index >= 0 && index < MAX_OUTPUTS ? outputAmounts[index] : 0;
    }

    public void putInput(int index, String key, double amount) {
        put(inputKeys, inputAmounts, index, key, amount);
    }

    public void putOutput(int index, String key, double amount) {
        put(outputKeys, outputAmounts, index, key, amount);
    }

    private static void put(String[] keys, double[] amounts, int index, String key, double amount) {
        if (index < 0 || index >= keys.length || key == null || key.isBlank() || !(amount > 0)) {
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
        if (index >= 0 && index < MAX_INPUTS) {
            inputKeys[index] = null;
            inputAmounts[index] = 0;
        }
    }

    public void removeOutput(int index) {
        if (index >= 0 && index < MAX_OUTPUTS) {
            outputKeys[index] = null;
            outputAmounts[index] = 0;
        }
    }

    public void setInputAmount(int index, double amount) {
        setAmount(inputKeys, inputAmounts, index, amount);
    }

    public void setOutputAmount(int index, double amount) {
        setAmount(outputKeys, outputAmounts, index, amount);
    }

    private static void setAmount(String[] keys, double[] amounts, int index, double amount) {
        if (index < 0 || index >= keys.length) {
            return;
        }
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
        for (int i = 0; i < keys.length; i++) {
            if (keys[i] == null) {
                return i;
            }
        }
        return -1;
    }

    public int inputCount() {
        return count(inputKeys);
    }

    public int outputCount() {
        return count(outputKeys);
    }

    private static int count(String[] keys) {
        int count = 0;
        for (String key : keys) {
            if (key != null) {
                count++;
            }
        }
        return count;
    }

    public int maxInputIndex() {
        return maxIndex(inputKeys);
    }

    public int maxOutputIndex() {
        return maxIndex(outputKeys);
    }

    private static int maxIndex(String[] keys) {
        for (int i = keys.length - 1; i >= 0; i--) {
            if (keys[i] != null) {
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
        return maxInputIndex() >= 0 || maxOutputIndex() >= 0;
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
        for (int i = 0; i < MAX_INPUTS; i++) {
            if (inputKeys[i] != null) {
                inputs.merge(inputKeys[i], inputAmounts[i], Double::sum);
            }
        }
        for (int i = 0; i < MAX_OUTPUTS; i++) {
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
