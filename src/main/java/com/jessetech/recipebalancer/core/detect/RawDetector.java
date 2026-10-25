package com.jessetech.recipebalancer.core.detect;

import com.jessetech.recipebalancer.core.matrix.MaterialMatrix;
import com.jessetech.recipebalancer.core.model.Constants;

import java.util.LinkedHashSet;
import java.util.Set;

/** 原料检测（Python {@code auto_detect_raw_materials}）：矩阵行内无正项 ⇒ 无配方可产出 ⇒ 原料。 */
public final class RawDetector {

    private RawDetector() {
    }

    public static Set<String> detect(MaterialMatrix matrix) {
        Set<String> raw = new LinkedHashSet<>();
        double[][] a = matrix.matrix();
        for (int i = 0; i < matrix.itemCount(); i++) {
            boolean producible = false;
            for (int j = 0; j < matrix.recipeCount(); j++) {
                if (a[i][j] > Constants.EPS_RAW) {
                    producible = true;
                    break;
                }
            }
            if (!producible) {
                raw.add(matrix.items().get(i));
            }
        }
        return raw;
    }
}
