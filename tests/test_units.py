"""单元测试：矩阵 / 检测器 / 库存语义 / bootstrap / IO / 报告 / 可视化 / CLI。"""

from __future__ import annotations

import json

import pytest

from recipe_balancer import (
    auto_detect_raw_materials,
    auto_detect_seed_inventory,
    build_matrix,
    collect_all_items,
    detect_deficit_items,
    detect_perfect_cycles,
    solve_production,
)
from recipe_balancer.io import (
    RecipeSchemaError,
    load_scenario,
    save_solution,
    validate_recipe_list,
)
from recipe_balancer.matrix import build_inventory_vector, build_target_vector
from recipe_balancer.models import RecipeSolution
from recipe_balancer.report import format_solution

from conftest import SCENARIOS


def sc(name):
    return SCENARIOS[name]


# =====================================================================
# 矩阵层
# =====================================================================


def test_collect_all_items_sorted():
    s = sc("simple")
    items = collect_all_items(s["recipes"], s["target"], {})
    assert items == sorted(items)
    assert "铁质机械外壳" in items and "空" in items


def test_build_matrix_values():
    s = sc("simple")
    items = collect_all_items(s["recipes"], s["target"], {})
    A, idx = build_matrix(s["recipes"], items)
    # R0: 空 -> 圆石
    assert A[idx["圆石"], 0] == 1
    assert A[idx["空"], 0] == -1
    # R4: 铁齿轮 1 + 铁锭 8 -> 铁质机械外壳 1
    assert A[idx["铁质机械外壳"], 4] == 1
    assert A[idx["铁锭"], 4] == -8
    assert A[idx["铁齿轮"], 4] == -1


def test_vectors():
    s = sc("simple")
    items = collect_all_items(s["recipes"], s["target"], {"圆石": 5})
    _, idx = build_matrix(s["recipes"], items)
    b = build_target_vector(items, idx, s["target"])
    sv = build_inventory_vector(items, idx, {"圆石": 5})
    assert b[idx["铁质机械外壳"]] == 64
    assert sv[idx["圆石"]] == 5


# =====================================================================
# 检测器
# =====================================================================


def test_auto_detect_raw():
    s = sc("simple")
    items = collect_all_items(s["recipes"], s["target"], {})
    A, _ = build_matrix(s["recipes"], items)
    assert auto_detect_raw_materials(A, items) == {"空"}


def test_auto_detect_seed():
    assert auto_detect_seed_inventory(sc("autocatalytic")["recipes"]) == {"锻造模版": 1}
    assert auto_detect_seed_inventory(sc("dual_line")["recipes"]) == {"SST": 1}


def test_detect_deficit_ebf_perfect_cycle():
    # 完美循环：无亏损
    assert detect_deficit_items(sc("ebf_cycle")["recipes"]) == set()


def test_detect_deficit_lossy_cycle():
    # Mg 0.7 / CO 1e3 配方。
    # 已知量纲效应（P0-D）：Σd 对不同量纲物品同权，
    # 实测亏损集为 {C, MgCl2, TiCl4}，而非直觉的 {C, Cl2, Mg}。
    assert detect_deficit_items(sc("deficit")["recipes"]) == {"C", "MgCl2", "TiCl4"}


def test_detect_deficit_whitelist_restriction():
    # 白名单 {'Cl2'}：只允许 Cl2 外部补充，仍应只报 Cl2
    assert detect_deficit_items(
        sc("multi_loop")["recipes"], allowed_external_inputs={"Cl2"}
    ) == {"Cl2"}


# =====================================================================
# 库存语义（P0-A 现状文档化：催化式库存与 LP 结果无关）
# =====================================================================


def test_initial_inventory_is_inert_current_behavior():
    """当前实现中 initial_inventory 在 b/s 中抵消，对求解结果零影响。

    这是 notebook 的既有语义（库存=催化剂不可消耗），
    修复/模式化计划见 ANALYSIS.md §12（转写后优化）。
    """
    s = sc("simple")
    sol_a = solve_production(s["recipes"], s["target"], raw_materials={"圆石"})
    sol_b = solve_production(
        s["recipes"], s["target"], raw_materials={"圆石"},
        initial_inventory={"圆石": 100, "铁锭": 50},
    )
    assert sol_a.recipe_rates == pytest.approx(sol_b.recipe_rates, abs=1e-9)
    assert sol_a.external_inputs == pytest.approx(sol_b.external_inputs, abs=1e-9)


# =====================================================================
# 完美循环物质检测（2026-10-17 重定义，替代原 bootstrap 模拟）
# =====================================================================


def test_perfect_cycle_ebf_minimal_seed_set():
    """EBF 闭环：只报"启动所需的最小投料集合"，不是所有环内物质。

    - C ⇄ CO 是一对循环：只投 C（2 个/批），CO 由化反产出；
    - Cl2→TiCl4→MgCl2→Cl2 与 Mg⇄MgCl2 共享 MgCl2：
      投 MgCl2（3 个/批）一次破两个环；
    - TiCl4 只是被 Cl 环"路过"的过程料（被 EBF 消耗），不作为启动项。
    """
    s = sc("ebf_cycle")
    sol = solve_production(s["recipes"], s["target"])
    cycles = detect_perfect_cycles(
        s["recipes"], sol.recipe_rates, set(sol.external_inputs)
    )
    assert cycles == {"C": 2.0, "MgCl2": 3.0}
    assert "TiCl4" not in cycles
    assert "CO" not in cycles and "Cl2" not in cycles and "Mg" not in cycles


def test_perfect_cycle_external_inputs_break_cycles():
    """deficit 场景：C / MgCl2 由外部持续供给 => 所有环都被外部供给打破 => 无种子。"""
    s = sc("deficit")
    sol = solve_production(s["recipes"], s["target"], **s["options"])
    cycles = detect_perfect_cycles(
        s["recipes"], sol.recipe_rates, set(sol.external_inputs)
    )
    assert cycles == {}


def test_perfect_cycle_autocatalytic():
    """自增殖配方（1 -> 2）是单物品自环，种子 = 1 批。"""
    s = sc("autocatalytic")
    sol = solve_production(s["recipes"], s["target"])
    cycles = detect_perfect_cycles(
        s["recipes"], sol.recipe_rates, set(sol.external_inputs)
    )
    assert cycles == {"锻造模版": 1.0}


def test_perfect_cycle_dual_line_sst():
    s = sc("dual_line")
    sol = solve_production(s["recipes"], s["target"])
    cycles = detect_perfect_cycles(
        s["recipes"], sol.recipe_rates, set(sol.external_inputs)
    )
    assert cycles == {"SST": 1.0}


def test_perfect_cycle_multi_loop_minimal():
    """多路循环：三对独立循环各投一个（且 Cl2 是外部输入，不参与）。"""
    s = sc("multi_loop")
    sol = solve_production(s["recipes"], s["target"], **s["options"])
    cycles = detect_perfect_cycles(
        s["recipes"], sol.recipe_rates, set(sol.external_inputs)
    )
    assert cycles == {"C": 2.0, "Mg": 2.0, "Na": 2.0}
    assert "Cl2" not in cycles and "TiCl4" not in cycles


def test_perfect_cycle_structure_only_ignores_names():
    """纯结构判定：幻想物质命名同样生效，不解析化学式。

    结构：源岩 -> 幻想甲 -> 幻想乙 -> (幻想甲 + 终点产物)
    循环 幻想甲 ⇄ 幻想乙，只投其中一批用量更小的那个。
    """
    recipes = [
        {"sourceList": {"源岩": 1}, "productList": {"幻想甲": 2},
         "time": 1, "equType": "祭坛"},
        {"sourceList": {"幻想甲": 2}, "productList": {"幻想乙": 1},
         "time": 1, "equType": "熔炉"},
        {"sourceList": {"幻想乙": 1, "源岩": 1}, "productList": {"幻想甲": 1, "终点": 1},
         "time": 1, "equType": "反应釜"},
    ]
    sol = solve_production(recipes, {"终点": 1})
    assert sol.success, sol.message
    cycles = detect_perfect_cycles(
        recipes, sol.recipe_rates, set(sol.external_inputs)
    )
    # 幻想乙 单批用量 1 < 幻想甲 单批用量 2 => 只投 幻想乙
    assert cycles == {"幻想乙": 1.0}


def test_perfect_cycle_none_in_acyclic_scenarios():
    """无环场景不应误报（原实现会返回一堆"零启动库存"的消耗品）。"""
    for name in ("simple", "mek_fission", "uu"):
        s = sc(name)
        sol = solve_production(s["recipes"], s["target"], **s["options"])
        assert detect_perfect_cycles(
            s["recipes"], sol.recipe_rates, set(sol.external_inputs)
        ) == {}, name


def test_perfect_cycle_ignores_inactive_recipes():
    """未激活配方不参与循环判定（避免纸面循环）。"""
    s = sc("autocatalytic")
    sol = solve_production(s["recipes"], s["target"])
    # 把制造锻造模版的自增殖配方 R0 速率视为 0 => 无环
    rates = list(sol.recipe_rates)
    rates[0] = 0.0
    assert detect_perfect_cycles(
        s["recipes"], rates, set(sol.external_inputs)
    ) == {}


def test_bootstrap_toggle():
    s = sc("ebf_cycle")
    off = solve_production(s["recipes"], s["target"])
    assert off.bootstrap_inventory == {}

    on = solve_production(s["recipes"], s["target"], auto_bootstrap=True)
    assert on.bootstrap_inventory == {"C": 2.0, "MgCl2": 3.0}
    assert "TiO2" not in on.bootstrap_inventory  # 外部输入不出现
    assert "TiCl4" not in on.bootstrap_inventory  # 过程料不作启动项


# =====================================================================
# IO
# =====================================================================


def _dump_scenario(tmp_path, name, options_json=None):
    s = sc(name)
    data = {
        "recipes": s["recipes"],
        "target_products": s["target"],
        "options": options_json if options_json is not None else {},
    }
    path = tmp_path / f"{name}.json"
    path.write_text(json.dumps(data, ensure_ascii=False), encoding="utf-8")
    return path


def test_io_roundtrip(tmp_path):
    path = _dump_scenario(tmp_path, "simple", {"raw_materials": ["圆石"]})
    recipes, target, kwargs = load_scenario(path)
    assert kwargs["raw_materials"] == {"圆石"}
    sol = solve_production(recipes, target, **kwargs)
    assert sol.success
    assert sol.external_inputs["圆石"] == pytest.approx(3840.0, rel=1e-6)


def test_io_rejects_bad_schema():
    with pytest.raises(RecipeSchemaError):
        validate_recipe_list([{"sourceList": {"a": 1}}])
    with pytest.raises(RecipeSchemaError):
        validate_recipe_list([{"sourceList": {"a": 1}, "productList": {"b": 1},
                              "time": 0, "equType": "x"}])


def test_solution_json_serializable(tmp_path):
    sol = solve_production(sc("simple")["recipes"], sc("simple")["target"],
                           raw_materials={"圆石"})
    out = tmp_path / "solution.json"
    save_solution(sol, out)
    data = json.loads(out.read_text(encoding="utf-8"))
    assert data["success"] is True
    assert "空" in data["inferred_raw_materials"] or "圆石" in data["inferred_raw_materials"]


# =====================================================================
# 报告
# =====================================================================


def test_report_sections():
    sol = solve_production(sc("simple")["recipes"], sc("simple")["target"],
                           raw_materials={"圆石"})
    text = format_solution(sol, sc("simple")["recipes"])
    for section in ("配方运行方案", "汇总数据", "原料输入", "副产物产出", "设备统计"):
        assert section in text
    assert "最小总设备数" in text


def test_report_failure():
    sol = RecipeSolution(
        recipe_rates=[], net_flow={}, external_inputs={}, final_outputs={},
        equipment_usage={}, total_machine_count=0, inferred_raw_materials=set(),
        inferred_initial_inventory={}, bootstrap_inventory={},
        success=False, message="infeasible",
    )
    assert "求解失败" in format_solution(sol, [])


# =====================================================================
# 可视化 / CLI
# =====================================================================


def test_visualize_writes_html(tmp_path):
    pytest.importorskip("pyvis")
    from recipe_balancer import draw_production_graph_pyvis

    s = sc("simple")
    sol = solve_production(s["recipes"], s["target"], raw_materials={"圆石"})
    out = tmp_path / "graph.html"
    draw_production_graph_pyvis(sol, s["recipes"], s["target"], output_file=str(out))
    assert out.exists() and out.stat().st_size > 1000


def test_material_tooltip_shows_bootstrap_amount():
    """红色（启动库存）节点的悬浮提示要带具体数量。"""
    from recipe_balancer.visualize import _material_tooltip

    tip = _material_tooltip("C", set(), set(), {"C": 2.0}, set())
    assert "需要启动库存: 2" in tip
    # 非启动库存物品不显示该行
    tip = _material_tooltip("圆石", set(), set(), {}, set())
    assert "启动库存" not in tip
    # 小数用量用 %g 展示
    tip = _material_tooltip("X", set(), set(), {"X": 1.25}, set())
    assert "需要启动库存: 1.25" in tip


def test_cli_solve(tmp_path):
    from recipe_balancer.cli import main

    path = _dump_scenario(tmp_path, "simple", {"raw_materials": ["圆石"]})
    out_json = tmp_path / "out.json"
    rc = main(["solve", str(path), "--json", str(out_json), "--quiet"])
    assert rc == 0
    assert out_json.exists()
