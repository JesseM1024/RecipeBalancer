"""GUI 逻辑与窗口冒烟测试（tkinter 不可用时整体跳过）。"""

from __future__ import annotations

import pytest

tk = pytest.importorskip("tkinter")

from recipe_balancer.gui import (  # noqa: E402
    GuiState,
    RecipeBalancerApp,
    rate_from_display,
    rate_to_display,
    seconds_from_display,
)
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


# =====================================================================
# 模型层
# =====================================================================


def test_state_validate_ok():
    assert _demo_state().validate() == []


def test_state_validate_reports_problems():
    state = GuiState()
    state.recipes = [{"sourceList": {}, "productList": {}, "time": 0, "equType": ""}]
    errors = state.validate()
    assert any("设备类型不能为空" in e for e in errors)
    assert any("产物列表为空" in e for e in errors)
    # 原料允许为空：不应报错
    assert not any("原料列表为空" in e for e in errors)
    assert any("目标产物" in e for e in errors)


def test_state_allows_empty_source_and_solves():
    state = GuiState()
    state.recipes = [
        {"sourceList": {}, "productList": {"空气": 1}, "time": 1, "equType": "发电机"}
    ]
    state.targets = {"空气": 1}
    assert state.validate() == []
    sol = state.solve()
    assert sol.success
    assert sol.external_inputs == {}


def test_state_solve():
    sol = _demo_state().solve()
    assert sol.success
    # 铁锭 2/s，筛子 0.5/次 => 4 次/s => 圆石 4/s
    assert sol.external_inputs["圆石"] == pytest.approx(4.0)


def test_state_scenario_roundtrip(tmp_path):
    state = _demo_state()
    state.objective = "recipes"
    state.auto_bootstrap = True
    state.allowed_cyc_inputs = {"Cl2"}

    path = tmp_path / "scenario.json"
    save_scenario(path, state.recipes, state.targets, state.options_for_save())

    recipes, targets, kwargs = load_scenario(path)
    restored = GuiState.from_scenario(recipes, targets, kwargs)

    assert restored.targets == state.targets
    assert restored.raw_materials == state.raw_materials
    assert restored.allowed_cyc_inputs == {"Cl2"}
    assert restored.objective == "recipes"
    assert restored.auto_bootstrap is True
    assert restored.validate() == []


# =====================================================================
# 单位换算
# =====================================================================


def test_unit_conversion():
    # t 为基准：20 t = 1 秒
    assert seconds_from_display(20, "t") == pytest.approx(1.0)
    assert seconds_from_display(2, "分") == pytest.approx(120.0)
    assert rate_from_display(20, "t") == pytest.approx(400.0)
    assert rate_to_display(400.0, "t") == pytest.approx(20.0)
    # 每单位独立：0.01/t 与 0.2/s 等价
    assert rate_from_display(0.01, "t") == pytest.approx(0.2)


# =====================================================================
# 窗口
# =====================================================================


@pytest.fixture
def app():
    try:
        instance = RecipeBalancerApp()
    except tk.TclError as exc:  # 无图形环境
        pytest.skip(f"无图形环境: {exc}")
    instance.withdraw()
    instance.update_idletasks()
    try:
        yield instance
    finally:
        instance.destroy()


def test_window_builds_cards_and_solves(app):
    assert len(app.cards) == 1
    app.model.recipes[0].update(
        {"sourceList": {"a": 1}, "productList": {"b": 1}, "time": 1, "equType": "测试"}
    )
    app.model.targets = {"b": 1.0}
    app._refresh_targets()
    assert app._on_solve() is True
    assert app.model.last_solution is not None
    assert app.model.last_solution.success
    assert "配方运行方案" in app.result_text.get("1.0", "end")


def test_card_enter_adds_item(app):
    card = app.cards[0]
    card.item_vars["sourceList"]["name"].set("矿石")
    card.item_vars["sourceList"]["amount"].set("3")
    card.add_item("sourceList")

    assert app.model.recipes[0]["sourceList"]["矿石"] == 3.0
    # 输入框已清空，条目以可编辑行呈现
    assert card.item_vars["sourceList"]["name"].get() == ""
    rows = card.item_rows["sourceList"]
    assert len(rows) == 1
    assert rows[0]["name_var"].get() == "矿石"
    assert rows[0]["amount_var"].get() == "3"


def test_card_inline_rename_and_amount(app):
    card = app.cards[0]
    card.item_vars["sourceList"]["name"].set("旧名")
    card.item_vars["sourceList"]["amount"].set("1")
    card.add_item("sourceList")
    record = card.item_rows["sourceList"][0]

    # 改名（实时写回，无需删除重加）
    record["name_var"].set("新名")
    card._on_item_edit("sourceList", record)
    assert app.model.recipes[0]["sourceList"] == {"新名": 1.0}
    assert record["item"] == "新名"

    # 改数量
    record["amount_var"].set("7.5")
    card._on_item_edit("sourceList", record)
    assert app.model.recipes[0]["sourceList"] == {"新名": 7.5}


def test_card_inline_rename_collision_blocked(app):
    card = app.cards[0]
    for name in ("甲", "乙"):
        card.item_vars["sourceList"]["name"].set(name)
        card.item_vars["sourceList"]["amount"].set("1")
        card.add_item("sourceList")

    record = next(r for r in card.item_rows["sourceList"] if r["item"] == "乙")
    record["name_var"].set("甲")  # 与已有条目重名
    card._on_item_edit("sourceList", record)

    assert app.model.recipes[0]["sourceList"] == {"甲": 1.0, "乙": 1.0}
    assert "已存在" in app.status_var.get()


def test_card_inline_edit_empty_name_reverts(app):
    card = app.cards[0]
    card.item_vars["sourceList"]["name"].set("保留名")
    card.item_vars["sourceList"]["amount"].set("2")
    card.add_item("sourceList")
    record = card.item_rows["sourceList"][0]

    record["name_var"].set("")
    card._finish_item_edit("sourceList", record)
    assert record["name_var"].get() == "保留名"
    # 非法数量也还原
    record["amount_var"].set("abc")
    card._finish_item_edit("sourceList", record)
    assert record["amount_var"].get() == "2"


def test_card_time_edits_follow_own_unit(app):
    card = app.cards[0]
    card.unit_var.set("t")
    card.refresh_fields()
    card.time_var.set("40")  # 40 t = 2 秒
    assert app.model.recipes[0]["time"] == pytest.approx(2.0)

    # 切换该卡片自己的单位（不影响其它卡片）
    card.unit_var.set("秒")
    card._on_unit_changed()
    assert card.time_var.get() == "2"
    assert app.model.recipes[0]["time"] == pytest.approx(2.0)
    assert app.recipe_units[0] == "秒"


def test_target_uses_its_own_unit(app):
    app.target_unit_var.set("秒")
    app.target_name_var.set("产物Z")
    app.target_rate_var.set("2")
    app._add_target()
    assert app.model.targets["产物Z"] == pytest.approx(2.0)  # 每秒
    assert app.target_units["产物Z"] == "秒"

    app.target_unit_var.set("t")
    app.target_name_var.set("产物Y")
    app.target_rate_var.set("40")  # 40/t = 800/s? 40/0.05 = 800
    app._add_target()
    assert app.model.targets["产物Y"] == pytest.approx(800.0)


def test_filter_hides_non_matching_cards(app):
    app.model.recipes.append(
        {"sourceList": {"独特物品": 1}, "productList": {"产物X": 1}, "time": 1, "equType": "机器A"}
    )
    app._rebuild_cards()
    assert len(app.cards) == 2

    app.filter_var.set("独特")
    app._apply_filter()
    assert app.cards[0].grid_info() == {}
    assert app.cards[1].grid_info() != {}

    app.filter_var.set("")
    app._apply_filter()
    assert app.cards[0].grid_info() != {}
