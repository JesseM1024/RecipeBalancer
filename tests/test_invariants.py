"""数值不变量：不依赖具体期望值、任何场景都必须成立的关系。

金标准（test_golden.py）锁定"与 notebook 一致"，不变量锁定"数学上自洽"。
两者互补：升级求解器后金标准可能需要重生成，但不变量永远必须成立。
"""

from __future__ import annotations

import math

import numpy as np
import pytest

from recipe_balancer import build_matrix, collect_all_items, solve_production

from conftest import SCENARIOS

ALL_NAMES = list(SCENARIOS)
EPS_RATE = 1e-10


@pytest.mark.parametrize("scenario", ALL_NAMES, indirect=True)
def test_mass_balance_identity(scenario):
    """净物流恒等式：net = A·x + 外部输入 − 目标。

    库存是催化式语义，在 b 与 s 中抵消，因此恒等式里不出现库存项。
    这条能抓住索引错位、外部输入漏算、目标扣减错误等解析层 bug。
    """
    sol = solve_production(
        scenario["recipes"], scenario["target"], **scenario["options"]
    )
    assert sol.success, sol.message

    items = collect_all_items(scenario["recipes"], scenario["target"], {})
    A, item_index = build_matrix(scenario["recipes"], items)

    x = np.array(sol.recipe_rates, dtype=float)
    ext = np.zeros(len(items))
    for item, value in sol.external_inputs.items():
        ext[item_index[item]] = value

    net = A @ x + ext
    for item, amount in scenario["target"].items():
        net[item_index[item]] -= amount

    for i, item in enumerate(items):
        assert sol.net_flow.get(item, 0.0) == pytest.approx(net[i], abs=1e-6), item
        # 约束：任何物品不得亏损（净流量不小于 0）
        assert net[i] >= -1e-6, f"{item} 净流量为负: {net[i]}"


@pytest.mark.parametrize("scenario", ALL_NAMES, indirect=True)
def test_equipment_statistics_consistent(scenario):
    """设备统计可由配方速率重算：usage_e = Σ rate·time，总数 = Σ ceil(usage_e)。"""
    sol = solve_production(
        scenario["recipes"], scenario["target"], **scenario["options"]
    )
    assert sol.success

    recomputed: dict[str, float] = {}
    for idx, rate in enumerate(sol.recipe_rates):
        if rate < EPS_RATE:
            continue
        recipe = scenario["recipes"][idx]
        recomputed[recipe["equType"]] = (
            recomputed.get(recipe["equType"], 0.0) + rate * recipe["time"]
        )

    assert set(recomputed) == set(sol.equipment_usage)
    assert sol.equipment_usage == pytest.approx(recomputed, abs=1e-6)
    assert sol.total_machine_count == sum(
        math.ceil(v) for v in sol.equipment_usage.values()
    )


@pytest.mark.parametrize("scenario", ALL_NAMES, indirect=True)
def test_external_inputs_only_whitelisted(scenario):
    """外部输入必须落在「用户原料 ∪ 自动原料 ∪ 循环亏损 − 目标」内。"""
    from recipe_balancer import (
        auto_detect_raw_materials,
        detect_deficit_items,
    )

    sol = solve_production(
        scenario["recipes"], scenario["target"], **scenario["options"]
    )
    assert sol.success

    items = collect_all_items(scenario["recipes"], scenario["target"], {})
    A, _ = build_matrix(scenario["recipes"], items)
    inferred_raw = auto_detect_raw_materials(A, items)
    deficits = detect_deficit_items(
        scenario["recipes"],
        allowed_external_inputs=scenario["options"].get("allowed_external_cyc_inputs"),
    )
    allowed = (
        set(scenario["options"].get("raw_materials", set()))
        | inferred_raw
        | deficits
    ) - set(scenario["target"])

    assert set(sol.external_inputs) <= allowed
