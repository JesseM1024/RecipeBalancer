"""数值金标准：与 notebook 原函数逐位对拍（2026-10-17 快照）。

金标准由 ``tools/snapshot_golden.py`` 直接从 notebook cell-1 的原函数生成，
覆盖每个场景的**完整数值输出**：

- 全部配方速率（recipe_rates）
- 外部输入 / 净物流 / 副产物（逐物品、逐数值）
- 设备并行量（equipment_usage）与总设备数
- 自动检测的原料与自增殖种子

这比"关键点位断言"严格得多：任何一条配方的速率偏移、任何一个物品的
物流量变化都会失败。若未来升级 scipy/HiGHS 导致并列最优解的选择变化，
先确认物料守恒（test_invariants.py）成立，再重新生成金标准并在提交说明中注明。
"""

from __future__ import annotations

import json
import pathlib

import pytest

from recipe_balancer import solve_production

from conftest import SCENARIOS

GOLDEN_DIR = pathlib.Path(__file__).parent / "golden"
ABS_TOL = 1e-9

_DICT_FIELDS = ("external_inputs", "net_flow", "final_outputs", "equipment_usage")


@pytest.mark.parametrize("name", list(SCENARIOS))
def test_matches_notebook_golden(name):
    scenario = SCENARIOS[name]
    sol = solve_production(
        scenario["recipes"], scenario["target"], **scenario["options"]
    )

    ref = json.loads((GOLDEN_DIR / f"{name}.json").read_text(encoding="utf-8"))

    assert sol.success == ref["success"]
    assert sol.recipe_rates == pytest.approx(ref["recipe_rates"], abs=ABS_TOL, rel=0)

    for field in _DICT_FIELDS:
        got = getattr(sol, field)
        expected = ref[field]
        assert set(got) == set(expected), f"{name}.{field} 物品集合不一致"
        assert got == pytest.approx(expected, abs=ABS_TOL, rel=0), f"{name}.{field} 数值不一致"

    assert sol.total_machine_count == ref["total_machine_count"]
    assert sorted(sol.inferred_raw_materials) == ref["inferred_raw_materials"]
    assert sol.inferred_initial_inventory == pytest.approx(
        ref["inferred_initial_inventory"], abs=ABS_TOL, rel=0
    )
