"""tkinter 图形界面：配方卡片流 / 目标设置 / 实时结果 / 一键可视化。

布局（左侧编辑 / 右侧结果）：

    ┌───────────────────────────────┬───────────────┐
    │ 目标产物 / 速率 / 单位          │               │
    │ 目标列表（最多 3 行，可滚动）    │               │
    │ 求解目标 / 循环检测             │   文本结果     │
    │ 外部原料                        │               │
    │ 循环亏损表                      │               │
    ├───────────────────────────────┤               │
    │ 筛选框                         │               │
    │ ┌ 配方卡片 1（可滚动）───────┐  │               │
    │ │ 原料（回车添加）           │  │               │
    │ │ 产物（回车添加）           │  │               │
    │ │ 机器类型 / 耗时 / 单位      │  │               │
    │ └───────────────────────────┘  │               │
    ├───────────────────────────────┤               │
    │ 新增 / 求解 / 导入 / 导出        │ 启动浏览器按钮 │
    └───────────────────────────────┴───────────────┘

时间单位：

- **每个耗时字段、每个目标速率各自带单位**（t / 秒 / 分 / 时），互不影响；
- 录入后统一**归一化为 tick**，再换算成求解器使用的秒/每秒：
  ``1 t = 0.05 s``，``速率(每秒) = 速率(/单位) ÷ (单位秒数)``；
- 配方耗时的内部存储仍是秒（场景 JSON 契约不变）。

其它约定：

- 改动即时写入内存模型，合法时 600ms 后自动求解；
- 原料允许为空（视为无输入）；产物为空则配方无效；
- 新增配方不带占位物品。
"""

from __future__ import annotations

import copy
import re
import tempfile
import time
import webbrowser
from pathlib import Path
import tkinter as tk
from tkinter import filedialog, font as tkfont, messagebox, ttk
from typing import Any, Dict, List, Optional, Set

from .io import load_scenario, save_scenario
from .models import RecipeSolution
from .report import format_solution
from .solver import solve_production

APP_TITLE = "配方树自动配平"
CARD_BG = "#fbfbfb"
LIST_BG = "#efefef"
MUTED = "#6b6b6b"

#: 新配方模板：不放占位物品；耗时 1 t；设备留空待填
TEMPLATE_RECIPE = {
    "sourceList": {},
    "productList": {},
    "time": 0.05,
    "equType": "",
}

OBJECTIVE_LABELS = {
    "machine": "机器数最少",
    "recipes": "配方数最少",
}
LABEL_TO_OBJECTIVE = {v: k for k, v in OBJECTIVE_LABELS.items()}

#: 1 tick = 0.05 秒（GT 20 t/s）
TICK_SECONDS = 0.05
#: 显示单位 -> 若干 tick
UNIT_TICKS = {"t": 1.0, "秒": 20.0, "分": 1200.0, "时": 72000.0}
UNIT_CHOICES = list(UNIT_TICKS)
DEFAULT_UNIT = "t"


def unit_seconds(unit: str) -> float:
    """一个显示单位等于多少秒（经由 tick 归一化）。"""
    return UNIT_TICKS[unit] * TICK_SECONDS


def seconds_from_display(value: float, unit: str) -> float:
    """录入值（单位 unit）-> 秒。先归一到 tick：t = value × UNIT_TICKS[unit]。"""
    ticks = value * UNIT_TICKS[unit]
    return ticks * TICK_SECONDS


def display_from_seconds(seconds: float, unit: str) -> float:
    ticks = seconds / TICK_SECONDS
    return ticks / UNIT_TICKS[unit]


def rate_from_display(value: float, unit: str) -> float:
    """每 unit 产量 -> 每秒产量。"""
    per_tick = value / UNIT_TICKS[unit]
    return per_tick / TICK_SECONDS


def rate_to_display(per_second: float, unit: str) -> float:
    per_tick = per_second * TICK_SECONDS
    return per_tick * UNIT_TICKS[unit]


def _fmt(value: float) -> str:
    return f"{value:g}"


# =====================================================================
# 界面状态（与控件解耦，便于测试）
# =====================================================================


class GuiState:
    """GUI 的内存模型：配方 / 目标 / 选项 / 上次结果。"""

    def __init__(self) -> None:
        self.recipes: List[dict] = []
        self.targets: Dict[str, float] = {}
        self.raw_materials: Set[str] = set()
        self.allowed_cyc_inputs: Set[str] = set()
        self.objective: str = "machine"
        self.auto_bootstrap: bool = False
        #: 从 JSON 导入但界面未暴露的选项（disabled_recipes / auto_detect_* 等）
        self.extra_options: Dict[str, Any] = {}
        self.last_solution: Optional[RecipeSolution] = None

    # ---- 场景读写 ----
    @classmethod
    def from_scenario(
        cls, recipes: List[dict], targets: Dict[str, float], kwargs: Dict[str, Any]
    ) -> "GuiState":
        state = cls()
        state.recipes = copy.deepcopy(recipes)
        state.targets = dict(targets)
        state.raw_materials = set(kwargs.get("raw_materials") or set())
        state.allowed_cyc_inputs = set(
            kwargs.get("allowed_external_cyc_inputs") or set()
        )
        state.objective = kwargs.get("objective", "machine")
        state.auto_bootstrap = bool(kwargs.get("auto_bootstrap", False))
        state.extra_options = {
            k: v
            for k, v in kwargs.items()
            if k
            not in (
                "raw_materials",
                "objective",
                "auto_bootstrap",
                "allowed_external_cyc_inputs",
            )
        }
        return state

    def options_for_save(self) -> Dict[str, Any]:
        options: Dict[str, Any] = {
            "raw_materials": sorted(self.raw_materials),
            "objective": self.objective,
            "auto_bootstrap": self.auto_bootstrap,
        }
        if self.allowed_cyc_inputs:
            options["allowed_external_cyc_inputs"] = sorted(self.allowed_cyc_inputs)
        options.update(self.extra_options)
        return options

    # ---- 编辑 ----
    def add_recipe(self, recipe: Optional[dict] = None, at: Optional[int] = None) -> int:
        item = copy.deepcopy(recipe or TEMPLATE_RECIPE)
        if at is None or not (0 <= at <= len(self.recipes)):
            self.recipes.append(item)
            return len(self.recipes) - 1
        self.recipes.insert(at, item)
        return at

    def remove_recipe(self, index: int) -> None:
        if 0 <= index < len(self.recipes):
            del self.recipes[index]

    def move_recipe(self, index: int, delta: int) -> int:
        new_index = index + delta
        if 0 <= new_index < len(self.recipes):
            self.recipes[index], self.recipes[new_index] = (
                self.recipes[new_index],
                self.recipes[index],
            )
            return new_index
        return index

    # ---- 校验与求解 ----
    def validate(self) -> List[str]:
        errors: List[str] = []
        if not self.recipes:
            errors.append("配方列表为空：请先新增或导入配方")
        if not self.targets:
            errors.append("未设置目标产物：请在顶部「目标产物」处添加")

        for i, recipe in enumerate(self.recipes):
            label = f"配方 #{i + 1}"
            if not str(recipe.get("equType", "")).strip():
                errors.append(f"{label}: 设备类型不能为空")
            try:
                if float(recipe.get("time", 0)) <= 0:
                    errors.append(f"{label}: 耗时必须大于 0")
            except (TypeError, ValueError):
                errors.append(f"{label}: 耗时不是有效数字")

            # 原料允许为空（无输入）；产物必须非空
            products = recipe.get("productList") or {}
            if not products:
                errors.append(f"{label}: 产物列表为空（配方无效）")
            for key, cn in (("sourceList", "原料"), ("productList", "产物")):
                for item, amount in (recipe.get(key) or {}).items():
                    if not str(item).strip():
                        errors.append(f"{label}: 存在空物品名")
                    try:
                        if float(amount) <= 0:
                            errors.append(f"{label}: {cn}「{item}」数量必须大于 0")
                    except (TypeError, ValueError):
                        errors.append(f"{label}: {cn}「{item}」数量不是有效数字")

        for item, rate in self.targets.items():
            if not str(item).strip():
                errors.append("目标产物存在空物品名")
            try:
                if float(rate) <= 0:
                    errors.append(f"目标「{item}」速率必须大于 0")
            except (TypeError, ValueError):
                errors.append(f"目标「{item}」速率不是有效数字")
        return errors

    def solve(self) -> RecipeSolution:
        kwargs: Dict[str, Any] = dict(self.extra_options)
        kwargs.update(
            raw_materials=set(self.raw_materials),
            objective=self.objective,
            auto_bootstrap=self.auto_bootstrap,
        )
        if self.allowed_cyc_inputs:
            kwargs["allowed_external_cyc_inputs"] = set(self.allowed_cyc_inputs)
        return solve_production(self.recipes, self.targets, **kwargs)


# =====================================================================
# 配方卡片
# =====================================================================


class RecipeCard(tk.Frame):
    """一张配方卡片：原料 / 产物 / 设备 / 耗时（自带单位）。"""

    def __init__(self, master, app: "RecipeBalancerApp", index: int):
        super().__init__(
            master,
            bd=1,
            relief="solid",
            padx=8,
            pady=6,
            bg=CARD_BG,
            highlightbackground="#d5d5d5",
        )
        self.app = app
        self.index = index
        self.recipe = app.model.recipes[index]
        self.item_boxes: Dict[str, tk.Frame] = {}
        self.item_vars: Dict[str, Dict[str, tk.StringVar]] = {}
        self.item_entries: Dict[str, Dict[str, ttk.Entry]] = {}
        self.item_rows: Dict[str, List[dict]] = {"sourceList": [], "productList": []}
        self.unit_var = tk.StringVar(value=app.recipe_units[index])
        self._build()

    # ---- 构建 ----
    def _build(self) -> None:
        header = tk.Frame(self, bg=CARD_BG)
        header.pack(fill="x")
        self.title = tk.Label(
            header, text="", bg=CARD_BG, font=("Microsoft YaHei UI", 10, "bold")
        )
        self.title.pack(side="left")
        for text, command in (
            ("删除", self._delete),
            ("↓", lambda: self.app.move_recipe(self.index, 1)),
            ("↑", lambda: self.app.move_recipe(self.index, -1)),
            ("复制", self._duplicate),
        ):
            tk.Button(
                header,
                text=text,
                command=command,
                bd=0,
                padx=6,
                bg=CARD_BG,
                activebackground="#e8e8e8",
                cursor="hand2",
            ).pack(side="right")

        self._item_section("sourceList", "原料（单次消耗）")
        self._item_section("productList", "产物（单次产出）")

        row = tk.Frame(self, bg=CARD_BG)
        row.pack(fill="x", pady=(6, 0))
        tk.Label(row, text="机器类型", bg=CARD_BG).pack(side="left")
        self.equ_var = tk.StringVar()
        self.equ_entry = ttk.Entry(row, textvariable=self.equ_var, width=18)
        self.equ_entry.pack(side="left", padx=(4, 14))
        tk.Label(row, text="耗时", bg=CARD_BG).pack(side="left")
        self.time_var = tk.StringVar()
        self.time_entry = ttk.Entry(row, textvariable=self.time_var, width=10)
        self.time_entry.pack(side="left", padx=4)

        self.unit_box = ttk.Combobox(
            row,
            textvariable=self.unit_var,
            values=UNIT_CHOICES,
            state="readonly",
            width=4,
        )
        self.unit_box.pack(side="left")
        self.unit_box.bind("<<ComboboxSelected>>", self._on_unit_changed)

        self.equ_var.trace_add("write", self._on_fields_changed)
        self.time_var.trace_add("write", self._on_fields_changed)

    def _item_section(self, which: str, title: str) -> None:
        wrap = tk.Frame(self, bg=CARD_BG)
        wrap.pack(fill="x", pady=(6, 0))

        tk.Label(wrap, text=title, bg=CARD_BG, fg="#3a3a3a").pack(anchor="w")
        box = tk.Frame(wrap, bg=CARD_BG)
        box.pack(fill="x")
        self.item_boxes[which] = box

        row = tk.Frame(wrap, bg=CARD_BG)
        row.pack(fill="x", pady=(2, 0))
        name_var = tk.StringVar()
        amount_var = tk.StringVar()
        self.item_vars[which] = {"name": name_var, "amount": amount_var}

        name_entry = ttk.Entry(row, textvariable=name_var, width=18)
        name_entry.pack(side="left")
        amount_entry = ttk.Entry(row, textvariable=amount_var, width=8)
        amount_entry.pack(side="left", padx=4)
        name_entry.bind("<Return>", lambda _e: self.add_item(which))
        amount_entry.bind("<Return>", lambda _e: self.add_item(which))
        ttk.Button(row, text="添加", width=5, command=lambda: self.add_item(which)).pack(
            side="left"
        )
        tk.Label(row, text="回车添加 · 条目可直接改", bg=CARD_BG, fg="#9a9a9a").pack(
            side="left", padx=6
        )
        self.item_entries[which] = {"name": name_entry, "amount": amount_entry}

    # ---- 刷新 ----
    def refresh(self) -> None:
        self.title.configure(text=f"配方 {self.index + 1}")
        self._render_items("sourceList")
        self._render_items("productList")
        self.refresh_fields()

    def refresh_fields(self) -> None:
        app = self.app
        app.syncing = True
        try:
            self.equ_var.set(str(self.recipe.get("equType", "")))
            seconds = float(self.recipe.get("time", 0))
            self.time_var.set(_fmt(display_from_seconds(seconds, self.unit_var.get())))
        finally:
            app.syncing = False

    def _render_items(self, which: str) -> None:
        box = self.item_boxes[which]
        for child in box.winfo_children():
            child.destroy()
        self.item_rows[which].clear()

        mapping = self.recipe.get(which) or {}
        if not mapping:
            tk.Label(box, text="（空）", bg=CARD_BG, fg="#a0a0a0").pack(anchor="w")
            return

        for item, amount in sorted(mapping.items()):
            row = tk.Frame(box, bg=CARD_BG)
            row.pack(fill="x", pady=1)

            name_var = tk.StringVar(value=item)
            amount_var = tk.StringVar(value=_fmt(float(amount)))
            record = {"item": item, "name_var": name_var, "amount_var": amount_var}

            name_entry = ttk.Entry(row, textvariable=name_var, width=16)
            name_entry.pack(side="left")
            amount_entry = ttk.Entry(row, textvariable=amount_var, width=7)
            amount_entry.pack(side="left", padx=4)

            for entry in (name_entry, amount_entry):
                entry.bind(
                    "<KeyRelease>", lambda _e, r=record: self._on_item_edit(which, r)
                )
                entry.bind(
                    "<FocusOut>", lambda _e, r=record: self._finish_item_edit(which, r)
                )

            tk.Button(
                row,
                text="✕",
                bd=0,
                bg=CARD_BG,
                fg="#aa3333",
                cursor="hand2",
                command=lambda r=record: self.remove_item(which, r),
            ).pack(side="left", padx=4)

            self.item_rows[which].append(record)

        self.app.bind_wheel_recursive(box)

    # ---- 编辑 ----
    def _on_item_edit(self, which: str, record: dict) -> None:
        """条目内联编辑：改名 / 改数量，实时写回模型。"""
        mapping = self.recipe.setdefault(which, {})
        old = record["item"]
        if old not in mapping:
            return

        name = record["name_var"].get().strip()
        if not name:
            return  # 名称暂时为空（正在输入），等下一次按键

        try:
            amount = float(record["amount_var"].get())
        except ValueError:
            return
        if amount <= 0:
            return

        if name != old and name in mapping:
            self.app.set_status(f"「{name}」已存在，改名未生效")
            return

        if name == old and mapping.get(old) == amount:
            return  # 无变化

        if name != old:
            mapping.pop(old, None)
            mapping[name] = amount
            record["item"] = name
        else:
            mapping[old] = amount
        self.app.on_model_changed(schedule=True, silent=True)

    def _finish_item_edit(self, which: str, record: dict) -> None:
        """离开输入框时兜底：空名称 / 非法数量还原显示。"""
        mapping = self.recipe.get(which) or {}
        old = record["item"]
        if old not in mapping:
            return
        if not record["name_var"].get().strip():
            record["name_var"].set(old)
        try:
            value = float(record["amount_var"].get())
            if value <= 0:
                raise ValueError
        except ValueError:
            record["amount_var"].set(_fmt(float(mapping[old])))

    def add_item(self, which: str) -> None:
        name = self.item_vars[which]["name"].get().strip()
        raw_amount = self.item_vars[which]["amount"].get().strip()
        if not name:
            self.app.set_status("物品名不能为空")
            return
        try:
            amount = float(raw_amount)
        except ValueError:
            self.app.set_status("数量必须是数字")
            return
        if amount <= 0:
            self.app.set_status("数量必须大于 0")
            return
        self.recipe.setdefault(which, {})[name] = amount
        self.item_vars[which]["name"].set("")
        self.item_vars[which]["amount"].set("")
        self._render_items(which)
        self.app.on_model_changed()
        self.item_entries[which]["name"].focus_set()

    def remove_item(self, which: str, record) -> None:
        """删除条目；record 可以是行记录（含最新改名）或旧式物品名。"""
        item = record["item"] if isinstance(record, dict) else record
        self.recipe.get(which, {}).pop(item, None)
        self._render_items(which)
        self.app.on_model_changed()

    def _on_fields_changed(self, *_args) -> None:
        if self.app.syncing:
            return
        self.recipe["equType"] = self.equ_var.get()
        raw = self.time_var.get().strip()
        if raw:
            try:
                value = float(raw)
            except ValueError:
                value = None
            if value is not None and value > 0:
                self.recipe["time"] = seconds_from_display(
                    value, self.unit_var.get()
                )
        self.app.on_model_changed(schedule=True, silent=True)

    def _on_unit_changed(self, _event=None) -> None:
        self.app.recipe_units[self.index] = self.unit_var.get()
        self.refresh_fields()
        self.app.on_model_changed(schedule=False)

    def _delete(self) -> None:
        self.app.delete_recipe(self.index)

    def _duplicate(self) -> None:
        self.app.duplicate_recipe(self.index)


# =====================================================================
# 主窗口
# =====================================================================


class RecipeBalancerApp(tk.Tk):
    def __init__(self) -> None:
        super().__init__()
        self.title(APP_TITLE)
        self.geometry("1280x880")
        self.minsize(1040, 700)

        self.model = GuiState()
        self.cards: List[RecipeCard] = []
        #: 与 model.recipes 对齐的每张卡片的时间显示单位
        self.recipe_units: List[str] = []
        #: 每个目标产物自己的速率单位
        self.target_units: Dict[str, str] = {}
        self.syncing = False
        self._auto_job: Optional[str] = None

        self.filter_var = tk.StringVar()
        self.raw_var = tk.StringVar()
        self.cyc_var = tk.StringVar()
        self.objective_var = tk.StringVar(value=OBJECTIVE_LABELS["machine"])
        self.bootstrap_var = tk.BooleanVar(value=False)
        self.status_var = tk.StringVar(value="就绪")
        self.target_name_var = tk.StringVar()
        self.target_rate_var = tk.StringVar()
        self.target_unit_var = tk.StringVar(value=DEFAULT_UNIT)

        self._init_fonts()
        self._build_ui()
        self._bind_shortcuts()

        self.model.add_recipe()
        self.recipe_units.append(DEFAULT_UNIT)
        self._rebuild_cards()
        self._refresh_targets()
        self._schedule_auto_solve()

    # ---------------------------------------------------------------
    # 初始化与布局
    # ---------------------------------------------------------------

    def _init_fonts(self) -> None:
        for name in ("TkDefaultFont", "TkTextFont", "TkMenuFont", "TkHeadingFont"):
            try:
                tkfont.nametofont(name).configure(family="Microsoft YaHei UI", size=10)
            except tk.TclError:
                pass
        self.report_font = ("Consolas", 10)

    def _build_ui(self) -> None:
        root = ttk.Frame(self, padding=8)
        root.pack(fill="both", expand=True)

        status = ttk.Label(root, textvariable=self.status_var, foreground="#555555")
        status.pack(side="bottom", fill="x", pady=(6, 0))

        # grid 的 uniform 保证 3:2 分栏，不受子控件请求宽度影响
        body = ttk.Frame(root)
        body.pack(fill="both", expand=True)
        body.columnconfigure(0, weight=3, uniform="cols")
        body.columnconfigure(1, weight=2, uniform="cols")
        body.rowconfigure(0, weight=1)

        left = ttk.Frame(body)
        left.grid(row=0, column=0, sticky="nsew", padx=(0, 8))
        right = ttk.Frame(body)
        right.grid(row=0, column=1, sticky="nsew")
        self.body = body
        self.left_frame = left
        self.right_frame = right

        self._build_left(left)
        self._build_right(right)

    def _build_left(self, parent) -> None:
        self._build_left_buttons(parent)  # 先占底部
        self._build_settings(parent)      # 再占顶部
        self._build_filter(parent)
        self._build_recipe_list(parent)   # 剩余空间给可滚动列表

    # ---- 顶部：目标与选项 ----
    def _build_settings(self, parent) -> None:
        frame = ttk.LabelFrame(parent, text="目标与选项", padding=8)
        frame.pack(side="top", fill="x")

        row = ttk.Frame(frame)
        row.pack(fill="x", pady=(0, 4))
        ttk.Label(row, text="目标产物").pack(side="left")
        ttk.Entry(row, textvariable=self.target_name_var, width=12).pack(
            side="left", padx=(4, 6)
        )
        ttk.Entry(row, textvariable=self.target_rate_var, width=7).pack(side="left")
        ttk.Combobox(
            row,
            textvariable=self.target_unit_var,
            values=UNIT_CHOICES,
            state="readonly",
            width=4,
        ).pack(side="left", padx=(2, 6))
        ttk.Button(row, text="添加目标", command=self._add_target).pack(
            side="left", padx=4
        )

        # 目标列表：最多显示 3 行，超出滚动
        targets_wrap = ttk.Frame(frame)
        targets_wrap.pack(fill="x")
        self.targets_canvas = tk.Canvas(
            targets_wrap, height=84, highlightthickness=0, bd=0
        )
        targets_scroll = ttk.Scrollbar(
            targets_wrap, orient="vertical", command=self.targets_canvas.yview
        )
        self.targets_scroll = targets_scroll
        self.targets_canvas.configure(yscrollcommand=targets_scroll.set)
        self.targets_canvas.pack(side="left", fill="x", expand=True)
        targets_scroll.pack(side="right", fill="y")
        self.targets_frame = ttk.Frame(self.targets_canvas)
        self._targets_window = self.targets_canvas.create_window(
            (0, 0), window=self.targets_frame, anchor="nw"
        )
        self.targets_frame.bind(
            "<Configure>",
            lambda _e: self.targets_canvas.configure(
                scrollregion=self.targets_canvas.bbox("all")
            ),
        )
        self.targets_canvas.bind(
            "<Configure>",
            lambda e: self.targets_canvas.itemconfigure(
                self._targets_window, width=e.width
            ),
        )
        self.targets_canvas.bind("<MouseWheel>", self._on_targets_wheel)

        line2 = ttk.Frame(frame)
        line2.pack(fill="x", pady=(6, 0))
        ttk.Label(line2, text="求解目标").pack(side="left")
        objective_box = ttk.Combobox(
            line2,
            textvariable=self.objective_var,
            values=list(OBJECTIVE_LABELS.values()),
            state="readonly",
            width=12,
        )
        objective_box.pack(side="left", padx=(4, 14))
        objective_box.bind("<<ComboboxSelected>>", self._on_option_changed)
        ttk.Checkbutton(
            line2,
            text="循环启动库存检测",
            variable=self.bootstrap_var,
            command=self._on_option_changed,
        ).pack(side="left")

        # 外部原料、循环亏损表各占一行
        raw_line = ttk.Frame(frame)
        raw_line.pack(fill="x", pady=(4, 0))
        ttk.Label(raw_line, text="外部原料", width=10).pack(side="left")
        ttk.Entry(raw_line, textvariable=self.raw_var, width=8).pack(
            side="left", fill="x", expand=True, padx=(4, 0)
        )

        cyc_line = ttk.Frame(frame)
        cyc_line.pack(fill="x", pady=(4, 0))
        ttk.Label(cyc_line, text="循环亏损表", width=10).pack(side="left")
        ttk.Entry(cyc_line, textvariable=self.cyc_var, width=8).pack(
            side="left", fill="x", expand=True, padx=(4, 0)
        )

        for var in (self.raw_var, self.cyc_var):
            var.trace_add("write", self._on_options_text_changed)

        self.extra_label = ttk.Label(frame, text="", foreground="#777777")
        self.extra_label.pack(fill="x", pady=(4, 0))

    # ---- 筛选框 ----
    def _build_filter(self, parent) -> None:
        frame = ttk.Frame(parent)
        frame.pack(side="top", fill="x", pady=(8, 4))
        ttk.Label(frame, text="筛选").pack(side="left")
        ttk.Entry(frame, textvariable=self.filter_var).pack(
            side="left", fill="x", expand=True, padx=(6, 0)
        )
        self.filter_var.trace_add("write", lambda *_: self._apply_filter())
        ttk.Label(frame, text="按配方物品 / 设备过滤", foreground="#8a8a8a").pack(
            side="left", padx=6
        )

    # ---- 配方列表（可滚动）----
    def _build_recipe_list(self, parent) -> None:
        wrap = ttk.Frame(parent)
        wrap.pack(side="top", fill="both", expand=True)

        self.canvas = tk.Canvas(wrap, bg=LIST_BG, highlightthickness=0, bd=0)
        scrollbar = ttk.Scrollbar(wrap, orient="vertical", command=self.canvas.yview)
        self.canvas.configure(yscrollcommand=scrollbar.set)
        self.canvas.pack(side="left", fill="both", expand=True)
        scrollbar.pack(side="right", fill="y")

        self.cards_frame = tk.Frame(self.canvas, bg=LIST_BG)
        self._canvas_window = self.canvas.create_window(
            (0, 0), window=self.cards_frame, anchor="nw"
        )
        self.cards_frame.columnconfigure(0, weight=1)
        self.cards_frame.bind("<Configure>", self._on_cards_frame_configure)
        self.canvas.bind("<Configure>", self._on_canvas_configure)
        self.canvas.bind("<MouseWheel>", self._on_wheel)

    def _build_left_buttons(self, parent) -> None:
        bar = ttk.Frame(parent)
        bar.pack(side="bottom", fill="x", pady=(6, 0))
        ttk.Button(bar, text="新增配方", command=self.add_recipe).pack(side="left")
        ttk.Button(bar, text="求解", command=self._on_solve).pack(side="left", padx=6)
        ttk.Button(bar, text="导入 JSON…", command=self._on_import).pack(side="left")
        ttk.Button(bar, text="导出 JSON…", command=self._on_export).pack(
            side="left", padx=6
        )

    # ---- 右侧：结果 ----
    def _build_right(self, parent) -> None:
        header = ttk.Frame(parent)
        header.pack(fill="x")
        ttk.Label(header, text="文本结果").pack(side="left")

        wrap = ttk.Frame(parent)
        wrap.pack(fill="both", expand=True, pady=(4, 6))
        self.result_text = tk.Text(
            wrap,
            wrap="none",
            font=self.report_font,
            state="disabled",
            background="#fbfbfb",
            width=1,
            height=1,
        )
        yscroll = ttk.Scrollbar(wrap, orient="vertical", command=self.result_text.yview)
        xscroll = ttk.Scrollbar(wrap, orient="horizontal", command=self.result_text.xview)
        self.result_text.configure(
            yscrollcommand=yscroll.set, xscrollcommand=xscroll.set
        )
        self.result_text.grid(row=0, column=0, sticky="nsew")
        yscroll.grid(row=0, column=1, sticky="ns")
        xscroll.grid(row=1, column=0, sticky="ew")
        wrap.rowconfigure(0, weight=1)
        wrap.columnconfigure(0, weight=1)

        ttk.Button(
            parent, text="启动浏览器查看可视化", command=self._on_visualize
        ).pack(fill="x")

    def _bind_shortcuts(self) -> None:
        self.bind("<F5>", lambda _e: self._on_solve())
        self.bind("<Control-Return>", lambda _e: self._on_solve())
        self.bind("<Control-g>", lambda _e: self._on_visualize())
        self.bind("<Control-o>", lambda _e: self._on_import())
        self.bind("<Control-s>", lambda _e: self._on_export())

    # ---------------------------------------------------------------
    # 卡片管理
    # ---------------------------------------------------------------

    def _rebuild_cards(self) -> None:
        for card in self.cards:
            card.destroy()
        self.cards.clear()
        while len(self.recipe_units) < len(self.model.recipes):
            self.recipe_units.append(DEFAULT_UNIT)
        del self.recipe_units[len(self.model.recipes):]

        for index in range(len(self.model.recipes)):
            card = RecipeCard(self.cards_frame, self, index)
            card.grid(row=index, column=0, sticky="ew", padx=4, pady=4)
            card.refresh()
            self.bind_wheel_recursive(card)
            self.cards.append(card)

        if not self.cards:
            hint = tk.Label(
                self.cards_frame,
                text="还没有配方。点击左下角「新增配方」，或导入 JSON 场景。",
                bg=LIST_BG,
                fg="#7a7a7a",
            )
            hint.grid(row=0, column=0, pady=24)
            self.bind_wheel_recursive(hint)

        self._apply_filter()
        self.canvas.update_idletasks()
        self.canvas.configure(scrollregion=self.canvas.bbox("all"))

    def add_recipe(self) -> None:
        index = self.model.add_recipe()
        self.recipe_units.append(DEFAULT_UNIT)
        self._rebuild_cards()
        self.canvas.yview_moveto(1.0)  # 新卡片在底部，滚过去
        self.cards[index].equ_entry.focus_set()
        self.on_model_changed()

    def duplicate_recipe(self, index: int) -> None:
        new_index = self.model.add_recipe(self.model.recipes[index], at=index + 1)
        self.recipe_units.insert(index + 1, self.recipe_units[index])
        self._rebuild_cards()
        self.cards[new_index].equ_entry.focus_set()
        self.on_model_changed()

    def delete_recipe(self, index: int) -> None:
        self.model.remove_recipe(index)
        if 0 <= index < len(self.recipe_units):
            self.recipe_units.pop(index)
        self._rebuild_cards()
        self.on_model_changed()

    def move_recipe(self, index: int, delta: int) -> None:
        new_index = self.model.move_recipe(index, delta)
        if new_index != index:
            self.recipe_units[index], self.recipe_units[new_index] = (
                self.recipe_units[new_index],
                self.recipe_units[index],
            )
        self._rebuild_cards()
        if 0 <= new_index < len(self.cards):
            self.cards[new_index].equ_entry.focus_set()
        self.on_model_changed(schedule=False)

    # ---------------------------------------------------------------
    # 目标
    # ---------------------------------------------------------------

    def _add_target(self) -> None:
        name = self.target_name_var.get().strip()
        if not name:
            self.set_status("目标产物名不能为空")
            return
        try:
            value = float(self.target_rate_var.get())
        except ValueError:
            self.set_status("目标速率必须是数字")
            return
        if value <= 0:
            self.set_status("目标速率必须大于 0")
            return
        unit = self.target_unit_var.get()
        self.model.targets[name] = rate_from_display(value, unit)
        self.target_units[name] = unit
        self.target_name_var.set("")
        self.target_rate_var.set("")
        self._refresh_targets()
        self.on_model_changed()

    def _refresh_targets(self) -> None:
        for child in self.targets_frame.winfo_children():
            child.destroy()
        if not self.model.targets:
            ttk.Label(
                self.targets_frame, text="（未设置目标产物）", foreground="#999999"
            ).pack(anchor="w")
        else:
            for item, per_second in sorted(self.model.targets.items()):
                unit = self.target_units.get(item, DEFAULT_UNIT)
                row = ttk.Frame(self.targets_frame)
                row.pack(fill="x")
                ttk.Label(row, text=item, width=10).pack(side="left", padx=(0, 4))
                var = tk.StringVar(value=_fmt(rate_to_display(per_second, unit)))
                entry = ttk.Entry(row, textvariable=var, width=8)
                entry.pack(side="left")
                entry.bind(
                    "<KeyRelease>",
                    lambda _e, i=item, v=var, u=unit: self._update_target_rate(
                        i, v, u
                    ),
                )
                unit_var = tk.StringVar(value=unit)
                combo = ttk.Combobox(
                    row,
                    textvariable=unit_var,
                    values=UNIT_CHOICES,
                    state="readonly",
                    width=4,
                )
                combo.pack(side="left", padx=(2, 6))
                combo.bind(
                    "<<ComboboxSelected>>",
                    lambda _e, i=item, u=unit_var: self._on_target_unit_changed(i, u),
                )
                ttk.Button(
                    row, text="✕", width=2, command=lambda i=item: self._remove_target(i)
                ).pack(side="left")
        self.targets_frame.update_idletasks()
        # 高度随条数自适应：1~3 行按实际行数，超过 3 行固定 3 行并滚动
        visible_rows = max(1, min(3, len(self.model.targets)))
        self.targets_canvas.configure(height=visible_rows * 28 + 2)
        if len(self.model.targets) > 3:
            self.targets_scroll.pack(side="right", fill="y")
        else:
            self.targets_scroll.pack_forget()
        self.targets_canvas.configure(scrollregion=self.targets_canvas.bbox("all"))
        # 布局完成后回到顶部（过早调用会被裁剪成错误的偏移）
        self.after_idle(lambda: self.targets_canvas.yview_moveto(0))
        self._bind_targets_wheel(self.targets_frame)

    def _update_target_rate(
        self, item: str, var: tk.StringVar, unit: str
    ) -> None:
        try:
            value = float(var.get())
        except ValueError:
            return
        if value <= 0:
            return
        self.model.targets[item] = rate_from_display(value, unit)
        self.on_model_changed(schedule=True, silent=True)

    def _on_target_unit_changed(self, item: str, unit_var: tk.StringVar) -> None:
        self.target_units[item] = unit_var.get()
        # 在事件回调里直接重建会销毁正在派发事件的控件，延后到空闲时执行
        self.after_idle(self._refresh_targets)
        self.on_model_changed(schedule=False)

    def _remove_target(self, item: str) -> None:
        self.model.targets.pop(item, None)
        self.target_units.pop(item, None)
        self._refresh_targets()
        self.on_model_changed()

    # ---------------------------------------------------------------
    # 选项
    # ---------------------------------------------------------------

    def _on_options_text_changed(self, *_args) -> None:
        if self.syncing:
            return
        self.model.raw_materials = {
            token for token in re.split(r"[,，;；\s]+", self.raw_var.get()) if token
        }
        self.model.allowed_cyc_inputs = {
            token for token in re.split(r"[,，;；\s]+", self.cyc_var.get()) if token
        }
        self.on_model_changed(schedule=True, silent=True)

    def _on_option_changed(self, _event=None) -> None:
        self.model.objective = LABEL_TO_OBJECTIVE.get(
            self.objective_var.get(), "machine"
        )
        self.model.auto_bootstrap = bool(self.bootstrap_var.get())
        self.on_model_changed()

    # ---------------------------------------------------------------
    # 自动保存 / 自动求解
    # ---------------------------------------------------------------

    def on_model_changed(self, schedule: bool = True, silent: bool = False) -> None:
        if not silent:
            self.set_status("已修改")
        if schedule:
            self._schedule_auto_solve()

    def _schedule_auto_solve(self) -> None:
        if self._auto_job is not None:
            try:
                self.after_cancel(self._auto_job)
            except tk.TclError:
                pass
        self._auto_job = self.after(600, self._auto_solve)

    def _auto_solve(self) -> None:
        self._auto_job = None
        errors = self.model.validate()
        if errors:
            self.set_status("待完善：" + errors[0])
            return
        try:
            solution = self.model.solve()
        except Exception as exc:  # noqa: BLE001 - 界面展示任意异常
            self.set_status(f"求解异常：{type(exc).__name__}: {exc}")
            return
        self.model.last_solution = solution
        self._show_report(solution)
        if solution.success:
            self.set_status(
                f"已自动求解：{len(self.model.recipes)} 个配方，"
                f"{len(solution.external_inputs)} 项外部输入"
            )
        else:
            self.set_status(f"求解失败：{solution.message}")

    # ---------------------------------------------------------------
    # 求解 / 可视化 / 导入导出
    # ---------------------------------------------------------------

    def _on_solve(self) -> bool:
        errors = self.model.validate()
        if errors:
            messagebox.showerror("无法求解", "\n".join(errors))
            self.set_status("无法求解：" + errors[0])
            return False
        try:
            solution = self.model.solve()
        except Exception as exc:  # noqa: BLE001
            messagebox.showerror("求解异常", f"{type(exc).__name__}: {exc}")
            return False
        self.model.last_solution = solution
        self._show_report(solution)
        self.set_status(
            "求解成功" if solution.success else f"求解失败：{solution.message}"
        )
        return solution.success

    def _show_report(self, solution: RecipeSolution) -> None:
        text = format_solution(solution, self.model.recipes)
        self.result_text.configure(state="normal")
        self.result_text.delete("1.0", "end")
        self.result_text.insert("1.0", text)
        self.result_text.configure(state="disabled")

    def _on_visualize(self) -> None:
        if not self._on_solve():
            return
        solution = self.model.last_solution
        if solution is None:
            return
        try:
            from .visualize import draw_production_graph_pyvis
        except ImportError:
            messagebox.showerror(
                "缺少依赖", "可视化需要 pyvis，请先安装：\n\npip install pyvis"
            )
            return

        out_dir = Path(tempfile.gettempdir()) / "recipe_balancer_ui"
        out_dir.mkdir(parents=True, exist_ok=True)
        out_file = out_dir / f"production_graph_{time.strftime('%Y%m%d_%H%M%S')}.html"
        try:
            draw_production_graph_pyvis(
                solution,
                self.model.recipes,
                target_products=self.model.targets,
                output_file=str(out_file),
                cdn_resources="in_line",
            )
        except Exception as exc:  # noqa: BLE001
            messagebox.showerror("生成失败", f"{type(exc).__name__}: {exc}")
            return
        webbrowser.open(out_file.as_uri())
        self.set_status(f"已生成并在浏览器打开：{out_file}")

    def _on_import(self) -> None:
        path = filedialog.askopenfilename(
            title="导入场景 JSON",
            filetypes=[("JSON 场景", "*.json"), ("所有文件", "*.*")],
        )
        if not path:
            return
        try:
            recipes, targets, kwargs = load_scenario(path)
        except (OSError, ValueError) as exc:
            messagebox.showerror("导入失败", str(exc))
            return

        self.model = GuiState.from_scenario(recipes, targets, kwargs)
        self.recipe_units = [DEFAULT_UNIT] * len(self.model.recipes)
        self.target_units = {item: DEFAULT_UNIT for item in self.model.targets}
        self._apply_state_to_widgets()
        self._rebuild_cards()
        self._refresh_targets()
        self.on_model_changed()
        self.set_status(f"已导入：{path}")

    def _apply_state_to_widgets(self) -> None:
        self.syncing = True
        try:
            self.raw_var.set("，".join(sorted(self.model.raw_materials)))
            self.cyc_var.set("，".join(sorted(self.model.allowed_cyc_inputs)))
            self.objective_var.set(
                OBJECTIVE_LABELS.get(self.model.objective, OBJECTIVE_LABELS["machine"])
            )
            self.bootstrap_var.set(self.model.auto_bootstrap)
            self.extra_label.configure(
                text=(
                    f"已保留 {len(self.model.extra_options)} 个 JSON 高级选项"
                    f"（如禁用配方）"
                    if self.model.extra_options
                    else ""
                )
            )
        finally:
            self.syncing = False

    def _on_export(self) -> None:
        if not self.model.recipes or not self.model.targets:
            messagebox.showwarning("无法导出", "请先填写配方与目标产物")
            return
        path = filedialog.asksaveasfilename(
            title="导出场景 JSON",
            defaultextension=".json",
            initialfile="scenario.json",
            filetypes=[("JSON 场景", "*.json"), ("所有文件", "*.*")],
        )
        if not path:
            return
        try:
            save_scenario(
                path,
                self.model.recipes,
                self.model.targets,
                self.model.options_for_save(),
            )
        except OSError as exc:
            messagebox.showerror("导出失败", str(exc))
            return
        self.set_status(f"已导出：{path}")

    # ---------------------------------------------------------------
    # 杂项
    # ---------------------------------------------------------------

    def set_status(self, text: str) -> None:
        self.status_var.set(text)

    def _apply_filter(self) -> None:
        needle = self.filter_var.get().strip().lower()
        for card in self.cards:
            if not needle:
                card.grid()
                continue
            recipe = card.recipe
            haystack = " ".join(
                [
                    str(recipe.get("equType", "")),
                    *map(str, (recipe.get("sourceList") or {})),
                    *map(str, (recipe.get("productList") or {})),
                ]
            ).lower()
            if needle in haystack:
                card.grid()
            else:
                card.grid_remove()

    def _on_cards_frame_configure(self, _event=None) -> None:
        self.canvas.configure(scrollregion=self.canvas.bbox("all"))

    def _on_canvas_configure(self, event) -> None:
        self.canvas.itemconfigure(self._canvas_window, width=event.width)

    def _on_wheel(self, event) -> None:
        self.canvas.yview_scroll(int(-event.delta / 120), "units")

    def _on_targets_wheel(self, event):
        self.targets_canvas.yview_scroll(int(-event.delta / 120), "units")
        return "break"

    def _bind_targets_wheel(self, widget) -> None:
        widget.bind("<MouseWheel>", self._on_targets_wheel)
        for child in widget.winfo_children():
            self._bind_targets_wheel(child)

    def bind_wheel_recursive(self, widget) -> None:
        widget.bind("<MouseWheel>", self._on_wheel)
        for child in widget.winfo_children():
            self.bind_wheel_recursive(child)


def main() -> int:
    app = RecipeBalancerApp()
    app.mainloop()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
