"""GUI 逻辑与窗口冒烟测试（tkinter 不可用时整体跳过）。"""

from __future__ import annotations

import pytest

tk = pytest.importorskip("tkinter")

from recipe_balancer.gui import GuiState, RecipeBalancerApp  # noqa: E402
from recipe_balancer.io import load_scenario, save_scenario  # noqa: E402


def _demo_state() -> GuiState:
    state = GuiState()
    state.recipes = [
        {"sourceList": {"圆石": 1}, "productList": {"沙砾": 1}, "time": 2, "equType": "粉碎"},
        {"sourceList": {"沙砾": 1}, "productList": {"铁锭": 0.5}, "time": 3, "equType": "筛子"},
    ]
    state.targets = {"铁锭": 2}
    state.raw_materials = {"圆石"}
    return state


def test_state_validate_ok():
    assert _demo_state().validate() == []


def test_state_validate_reports_problems():
    state = GuiState()
    state.recipes = [{"sourceList": {}, "productList": {}, "time": 0, "equType": ""}]
    errors = state.validate()
    assert any("设备类型不能为空" in e for e in errors)
    assert any("原料列表为空" in e for e in errors)
    assert any("产物列表为空" in e for e in errors)
    assert any("目标产物" in e for e in errors)


def test_state_solve():
    sol = _demo_state().solve()
    assert sol.success
    # 铁锭 2/s，筛子 0.5/次 => 4 次/s => 圆石 4/s
    assert sol.external_inputs["圆石"] == pytest.approx(4.0)


def test_state_scenario_roundtrip(tmp_path):
    state = _demo_state()
    state.objective = "recipes"
    state.auto_bootstrap = True

    path = tmp_path / "scenario.json"
    save_scenario(path, state.recipes, state.targets, state.options_for_save())

    recipes, targets, kwargs = load_scenario(path)
    restored = GuiState.from_scenario(recipes, targets, kwargs)

    assert restored.targets == state.targets
    assert restored.raw_materials == state.raw_materials
    assert restored.objective == "recipes"
    assert restored.auto_bootstrap is True
    assert restored.validate() == []


def test_window_constructs_and_edits():
    try:
        app = RecipeBalancerApp()
    except tk.TclError as exc:  # 无图形环境
        pytest.skip(f"无图形环境: {exc}")

    try:
        app.withdraw()
        app.update_idletasks()
        assert len(app.model.recipes) == 1  # 初始模板

        app.model.add_recipe(
            {"sourceList": {"a": 1}, "productList": {"b": 1}, "time": 1, "equType": "测试"}
        )
        app._refresh_all(select=1)
        app.update_idletasks()
        assert len(app.recipe_tree.get_children()) == 2
        assert app.current_index == 1
        assert app.equ_var.get() == "测试"

        # 添加目标 + 求解走一遍
        app.model.targets = {"b": 1}
        app._refresh_targets()
        assert app._on_solve() is True
        assert app.model.last_solution is not None
        assert app.model.last_solution.success
    finally:
        app.destroy()
