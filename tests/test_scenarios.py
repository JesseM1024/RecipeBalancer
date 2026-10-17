"""9 个 notebook 场景的金标准测试。

期望值一部分来自手工推导（物料衡算），一部分来自对 notebook
行为的忠实复现；断言失败 = 转写引入了行为偏差，需先查差异再改。
"""

from __future__ import annotations

import pytest

from recipe_balancer import solve_production

from conftest import SCENARIOS

ALL_NAMES = list(SCENARIOS)


def solve(name: str, **overrides):
    sc = SCENARIOS[name]
    opts = dict(sc["options"])
    opts.update(overrides)
    return solve_production(sc["recipes"], sc["target"], **opts)


# =====================================================================
# 通用不变量：全部场景
# =====================================================================


@pytest.mark.parametrize("scenario", ALL_NAMES, indirect=True)
def test_solve_success_and_conservation(scenario):
    sol = solve_production(
        scenario["recipes"], scenario["target"], **scenario["options"]
    )
    assert sol.success, sol.message
    # LP 约束保证：任何物品净流量不得为负（不得亏损）
    for item, v in sol.net_flow.items():
        assert v >= -1e-6, f"物品 {item} 净流量为负: {v}"
    # 净物流只收录 |值| >= 1e-8 的项（与 notebook 一致）
    assert all(abs(v) >= 1e-8 - 1e-12 for v in sol.net_flow.values())


@pytest.mark.parametrize("scenario", ALL_NAMES, indirect=True)
def test_target_covered(scenario):
    sol = solve_production(
        scenario["recipes"], scenario["target"], **scenario["options"]
    )
    assert sol.success
    # 目标产物的净流量 >= 0（允许过剩成为副产物，但不允许短缺）
    for item in scenario["target"]:
        assert sol.net_flow.get(item, 0.0) >= -1e-6, (
            f"目标产物 {item} 未满足: {sol.net_flow.get(item, 0.0)}"
        )


# =====================================================================
# 场景 1: 简单链
# =====================================================================


def test_simple_external_and_byproducts():
    sol = solve("simple")
    assert sol.success
    # 用户声明圆石为原料 => 造石机(R0) 关闭，直接外购圆石
    # (两条路线外部惩罚同为 3840e6，走造石机还要多付机器成本)
    assert sol.recipe_rates[0] == pytest.approx(0.0, abs=1e-9)
    assert set(sol.external_inputs) == {"圆石"}
    assert sol.external_inputs["圆石"] == pytest.approx(3840.0, rel=1e-6)
    # 筛子副产物：钻石 384 / 金锭 768 / 红石 1152
    assert sol.final_outputs["钻石"] == pytest.approx(384.0, rel=1e-6)
    assert sol.final_outputs["金锭"] == pytest.approx(768.0, rel=1e-6)
    assert sol.final_outputs["红石"] == pytest.approx(1152.0, rel=1e-6)
    # 目标正好满足，无过剩
    assert sol.net_flow.get("铁质机械外壳", 0.0) == pytest.approx(0.0, abs=1e-6)


def test_simple_disabled_stone_generator():
    # 禁用造石机后行为不变（LP 本来就不选它）
    sol = solve("simple", disabled_recipes={0})
    assert sol.success
    assert set(sol.external_inputs) == {"圆石"}


# =====================================================================
# 场景 2: 自增殖
# =====================================================================


def test_autocatalytic_seed_detection():
    sol = solve("autocatalytic")
    assert sol.success
    assert sol.inferred_initial_inventory == {"锻造模版": 1}
    # 种子是催化式库存：不出现在外部输入，也不该被外购
    assert "锻造模版" not in sol.external_inputs
    # 场景未声明 raw_materials={"圆石"}，圆石只能由造石机消耗"空"产出
    # => 唯一外部输入是"空" 3.525（熔岩线 0.025 + 筛子线 3.5）
    assert set(sol.external_inputs) == {"空"}
    assert sol.external_inputs["空"] == pytest.approx(3.525, rel=1e-6)


# =====================================================================
# 场景 3: 自增殖 + 多物品产出
# =====================================================================


def test_multi_product():
    sol = solve("multi_product")
    assert sol.success
    assert sol.inferred_initial_inventory == {"锻造模版": 1}
    # 目标: 锻造模版 1/20, 金锭 1, 铁锭 2；封包产物配方(R6)不激活
    assert sol.recipe_rates[6] == pytest.approx(0.0, abs=1e-9)


# =====================================================================
# 场景 4: EBF 完美循环
# =====================================================================


def test_ebf_cycle_no_deficit():
    sol = solve("ebf_cycle")
    assert sol.success
    # MgCl2 / CO / C / Cl2 全部闭环，唯一外部输入是原料 TiO2
    assert set(sol.external_inputs) == {"TiO2"}
    assert sol.external_inputs["TiO2"] == pytest.approx(0.2, rel=1e-6)
    # O2 是电解 CO 的副产物: 1e3 * 0.4 = 400? -> 每化反 1 次 CO 2e3,
    # 电解 2 次 O2 2e3 -> 化反速率 0.2 => O2 = 1e3*0.2*2/... 见 net_flow
    assert sol.final_outputs["O2"] == pytest.approx(400.0, rel=1e-6)


def test_ebf_bootstrap_detects_perfect_cycles():
    """auto_bootstrap 现在是"完美循环物质检测"：只报环内自给物质 + 单批次启动量。"""
    sol = solve("ebf_cycle", auto_bootstrap=True)
    assert sol.bootstrap_inventory == {
        "C": 2.0,
        "Cl2": 4000.0,
        "CO": 1000.0,
        "Mg": 2.0,
        "MgCl2": 3.0,
        "TiCl4": 1000.0,
    }
    # 原料 TiO2 / 副产物 O2 都不应出现
    assert "TiO2" not in sol.bootstrap_inventory
    assert "O2" not in sol.bootstrap_inventory


def test_simple_no_perfect_cycles():
    """无环场景：原实现会返回消耗品的"启动库存"，现在应为空。"""
    sol = solve("simple", auto_bootstrap=True)
    assert sol.bootstrap_inventory == {}


# =====================================================================
# 场景 5: 循环亏损（空 whitelist = 全放行）
# =====================================================================


def test_deficit_detection_and_external_inputs():
    """已知量纲效应（见 ANALYSIS.md §12 P0-D）：

    子 LP 的 Σd 对不同量纲物品同权（Cl2 亏 2e3 vs Mg 亏 0.6），
    LP 会挑"小量纲"的物品报亏损——本场景实测亏损集为
    {C, MgCl2, TiCl4}，外部输入选择也随之变成买 MgCl2、副产 Mg，
    而非直觉上的买 Mg / Cl2。忠实复现 notebook 行为。
    """
    sol = solve("deficit")
    assert sol.success
    assert set(sol.external_inputs) == {"C", "MgCl2", "TiO2"}
    # C 亏 1/次 × EBF 0.2 = 0.2
    assert sol.external_inputs["C"] == pytest.approx(0.2, rel=1e-6)
    # MgCl2 缺 6/次 × 0.2 = 1.2（电解线只跑到 Mg 恰好平衡的 0.8）
    assert sol.external_inputs["MgCl2"] == pytest.approx(1.2, rel=1e-6)
    # Mg 被过量生产：0.7×0.8 - 2×0.2 = 0.16
    assert sol.final_outputs["Mg"] == pytest.approx(0.16, rel=1e-6)
    # TiO2 是自动检测原料
    assert "TiO2" in sol.external_inputs


# =====================================================================
# 场景 6: 多路循环 + Cl2 白名单
# =====================================================================


def test_multi_loop_cl2_whitelist():
    sol = solve("multi_loop")
    assert sol.success
    # Mg/Na/CO/C 环全部平衡，唯一亏损是 Cl2（白名单放行）
    assert set(sol.external_inputs) == {"TiO2", "Cl2"}
    assert sol.external_inputs["TiO2"] == pytest.approx(0.3, rel=1e-6)
    # 化反需 8e3，环内供给 6e3（MgCl2 电解 4e3 + NaCl 电解 2e3）=> 亏 2e3 * 0.3
    assert sol.external_inputs["Cl2"] == pytest.approx(600.0, rel=1e-6)


# =====================================================================
# 场景 7: 双线配平（SST 催化）
# =====================================================================


def test_dual_line():
    sol = solve("dual_line")
    assert sol.success
    # SST 是自增殖种子（R2: 1 -> 8）
    assert sol.inferred_initial_inventory == {"SST": 1}
    # LP 实测: R2=0.1, R3=0.9, R4(电解)=0.1758
    # 外部输入: BF = 2*0.9 = 1.8; H2O = 200*0.1 + 10*0.9 + 512*0.1758 = 119
    # H2 由 R4 刚好自足（512*0.1758 = 90 = 100*0.9），O2 富余为副产物
    assert set(sol.external_inputs) == {"BF", "H2O"}
    assert sol.external_inputs["BF"] == pytest.approx(1.8, rel=1e-6)
    assert sol.external_inputs["H2O"] == pytest.approx(119.0, rel=1e-6)
    # O2 富余 30 成为副产物（R2 1 + R4 45 - R0 16）
    assert sol.final_outputs["O2"] == pytest.approx(30.0, rel=1e-6)


# =====================================================================
# 场景 8: MEK 裂变全链
# =====================================================================


def test_mek_fission():
    sol = solve("mek_fission")
    assert sol.success, sol.message
    # 花岗岩与水是原料（无配方生产）
    assert "花岗岩" in sol.external_inputs
    assert "水" in sol.external_inputs
    # 氢气是电解水副产物（无下游消费）
    assert "氢气" in sol.final_outputs
    # 目标钋球正好满足
    assert sol.net_flow.get("钋球", 0.0) == pytest.approx(0.0, abs=1e-6)


# =====================================================================
# 场景 9: UU 物质
# =====================================================================


def test_uu():
    sol = solve("uu")
    assert sol.success, sol.message
    # 原料: 空 / 水 / 圆石 / 锇粉（皆无配方可生产）
    for item in ("空", "水", "圆石", "锇粉"):
        assert item in sol.external_inputs, f"{item} 应为外部原料"
    # 目标正好满足
    assert sol.net_flow.get("UU物质", 0.0) == pytest.approx(0.0, abs=1e-6)
