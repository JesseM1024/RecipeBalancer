"""tkinter 图形界面：配方卡片流 / 目标设置 / 实时结果 / 一键可视化。

布局参考用户草图：

    ┌───────────────────────────────┬───────────────┐
    │ 目标产物 / 速率 / 单位 / 求解选项 │               │
    ├───────────────────────────────┤               │
    │ 筛选框                         │   文本结果     │
    │ ┌ 配方卡片 1 ───────────────┐  │               │
    │ │ 原料（回车添加）          │  │               │
    │ │ 产物（回车添加）          │  │               │
    │ │ 机器类型 / 耗时            │  │               │
    │ └───────────────────────────┘  │               │
    │ ┌ 配方卡片 2 …（可滚动）     │  │               │
    ├───────────────────────────────┤               │
    │ 新增 / 求解 / 导入 / 导出       │ 启动浏览器按钮 │
    └───────────────────────────────┴───────────────┘

交互约定：

- **改动即保存**：所有输入实时写入内存模型，无需"应用"按钮；
- **自动求解**：模型合法时，改动后 600ms 自动求解并刷新右侧结果；
- **回车添加**：每张卡片的原料/产物各有一组「名称 + 数量」输入框，
  回车即加入对应列表（条目可点 ✕ 删除）；
- 单位（tick / 秒 / 分 / 时）只影响显示与录入，模型内部统一用秒；
- 配方列表放在 Canvas 上，可滚动；筛选框按物品名 / 设备名过滤卡片。

启动：``python -m recipe_balancer.gui``（需要带 tkinter 的 Python，见 README）。
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

TEMPLATE_RECIPE = {
    "sourceList": {"输入物品": 1},
    "productList": {"输出物品": 1},
    "time": 1.0,
    "equType": "设备",
}

OBJECTIVE_LABELS = {
    "machine": "机器数最少",
    "recipes": "配方数最少",
}
LABEL_TO_OBJECTIVE = {v: k for k, v in OBJECTIVE_LABELS.items()}

#: 显示单位 -> 每单位对应的秒数（GT 中 1 tick = 1/20 秒）
UNIT_FACTORS = {"tick": 0.05, "秒": 1.0, "分": 60.0, "时": 3600.0}


def seconds_from_display(value: float, unit: str) -> float:
    return value * UNIT_FACTORS[unit]


def display_from_seconds(seconds: float, unit: str) -> float:
    return seconds / UNIT_FACTORS[unit]


def rate_from_display(value: float, unit: str) -> float:
    """每单位时间产量 -> 每秒产量。"""
    return value / UNIT_FACTORS[unit]


def rate_to_display(per_second: float, unit: str) -> float:
    return per_second * UNIT_FACTORS[unit]


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
            for key, cn in (("sourceList", "原料"), ("productList", "产物")):
                mapping = recipe.get(key) or {}
                if not mapping:
                    errors.append(f"{label}: {cn}列表为空")
                    continue
                for item, amount in mapping.items():
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
    """一张配方卡片：原料 / 产物 / 设备 / 耗时，改动即时写回模型。"""

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
        self.unit_label = tk.Label(row, text="t", bg=CARD_BG, fg=MUTED)
        self.unit_label.pack(side="left")

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
        tk.Label(row, text="回车添加", bg=CARD_BG, fg="#9a9a9a").pack(
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
            self.time_var.set(_fmt(display_from_seconds(seconds, app.unit)))
            self.unit_label.configure(text=app.unit)
        finally:
            app.syncing = False

    def _render_items(self, which: str) -> None:
        box = self.item_boxes[which]
        for child in box.winfo_children():
            child.destroy()
        mapping = self.recipe.get(which) or {}
        if not mapping:
            tk.Label(box, text="（空）", bg=CARD_BG, fg="#a0a0a0").pack(anchor="w")
            return
        for item, amount in sorted(mapping.items()):
            row = tk.Frame(box, bg=CARD_BG)
            row.pack(fill="x")
            tk.Label(row, text=f"· {item}  × {_fmt(float(amount))}", bg=CARD_BG).pack(
                side="left"
            )
            tk.Button(
                row,
                text="✕",
                bd=0,
                bg=CARD_BG,
                fg="#aa3333",
                cursor="hand2",
                command=lambda i=item: self.remove_item(which, i),
            ).pack(side="left", padx=4)
        self.app.bind_wheel_recursive(box)

    # ---- 编辑 ----
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

    def remove_item(self, which: str, item: str) -> None:
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
                self.recipe["time"] = seconds_from_display(value, self.app.unit)
        self.app.on_model_changed(schedule=True, silent=True)

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
        self.geometry("1280x860")
        self.minsize(1040, 680)

        self.model = GuiState()
        self.cards: List[RecipeCard] = []
        self.syncing = False
        self._auto_job: Optional[str] = None

        self.unit = "tick"
        self.unit_var = tk.StringVar(value=self.unit)
        self.filter_var = tk.StringVar()
        self.raw_var = tk.StringVar()
        self.cyc_var = tk.StringVar()
        self.objective_var = tk.StringVar(value=OBJECTIVE_LABELS["machine"])
        self.bootstrap_var = tk.BooleanVar(value=False)
        self.status_var = tk.StringVar(value="就绪")
        self.target_name_var = tk.StringVar()
        self.target_rate_var = tk.StringVar()

        self._init_fonts()
        self._build_ui()
        self._bind_shortcuts()

        self.model.add_recipe()
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

        # 用 grid 的列权重固定左右比例（3:2），避免右侧 Text 的请求宽度反压左栏
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
        self.rate_unit_label = ttk.Label(row, text=f"/{self.unit}")
        self.rate_unit_label.pack(side="left", padx=(2, 6))

        self.unit_box = ttk.Combobox(
            row,
            textvariable=self.unit_var,
            values=list(UNIT_FACTORS),
            state="readonly",
            width=5,
        )
        self.unit_box.pack(side="left")
        self.unit_box.bind("<<ComboboxSelected>>", self._on_unit_changed)

        ttk.Button(row, text="添加目标", command=self._add_target).pack(
            side="left", padx=8
        )

        self.targets_frame = ttk.Frame(frame)
        self.targets_frame.pack(fill="x")

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

        line3 = ttk.Frame(frame)
        line3.pack(fill="x", pady=(4, 0))
        ttk.Label(line3, text="外部原料").pack(side="left")
        raw_entry = ttk.Entry(line3, textvariable=self.raw_var, width=8)
        raw_entry.pack(side="left", fill="x", expand=True, padx=(4, 12))
        ttk.Label(line3, text="循环亏损表").pack(side="left")
        cyc_entry = ttk.Entry(line3, textvariable=self.cyc_var, width=10)
        cyc_entry.pack(side="left", padx=4)

        for var in (self.raw_var, self.cyc_var):
            var.trace_add("write", self._on_options_text_changed)

        self.extra_label = ttk.Label(frame, text="", foreground="#777777")
        self.extra_label.pack(fill="x", pady=(4, 0))

    # ---- 筛选框 ----
    def _build_filter(self, parent) -> None:
        frame = ttk.Frame(parent)
        frame.pack(side="top", fill="x", pady=(8, 4))
        ttk.Label(frame, text="筛选").pack(side="left")
        entry = ttk.Entry(frame, textvariable=self.filter_var)
        entry.pack(side="left", fill="x", expand=True, padx=(6, 0))
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
        self._rebuild_cards()
        self.cards[index].equ_entry.focus_set()
        self.on_model_changed()

    def duplicate_recipe(self, index: int) -> None:
        index_new = self.model.add_recipe(self.model.recipes[index], at=index + 1)
        self._rebuild_cards()
        self.cards[index_new].equ_entry.focus_set()
        self.on_model_changed()

    def delete_recipe(self, index: int) -> None:
        self.model.remove_recipe(index)
        self._rebuild_cards()
        self.on_model_changed()

    def move_recipe(self, index: int, delta: int) -> None:
        new_index = self.model.move_recipe(index, delta)
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
        self.model.targets[name] = rate_from_display(value, self.unit)
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
            return
        for item, per_second in sorted(self.model.targets.items()):
            row = ttk.Frame(self.targets_frame)
            row.pack(fill="x")
            ttk.Label(row, text=item, width=10).pack(side="left")
            var = tk.StringVar(value=_fmt(rate_to_display(per_second, self.unit)))
            entry = ttk.Entry(row, textvariable=var, width=8)
            entry.pack(side="left")
            entry.bind(
                "<KeyRelease>",
                lambda _e, i=item, v=var: self._update_target_rate(i, v),
            )
            ttk.Label(row, text=f"/{self.unit}", foreground=MUTED).pack(
                side="left", padx=(2, 8)
            )
            ttk.Button(
                row, text="✕", width=2, command=lambda i=item: self._remove_target(i)
            ).pack(side="left")

    def _update_target_rate(self, item: str, var: tk.StringVar) -> None:
        try:
            value = float(var.get())
        except ValueError:
            return
        if value <= 0:
            return
        self.model.targets[item] = rate_from_display(value, self.unit)
        self.on_model_changed(schedule=True, silent=True)

    def _remove_target(self, item: str) -> None:
        self.model.targets.pop(item, None)
        self._refresh_targets()
        self.on_model_changed()

    # ---------------------------------------------------------------
    # 选项
    # ---------------------------------------------------------------

    def _on_unit_changed(self, _event=None) -> None:
        new_unit = self.unit_var.get()
        if new_unit not in UNIT_FACTORS:
            return
        self.unit = new_unit
        self.rate_unit_label.configure(text=f"/{self.unit}")
        self._refresh_targets()
        for card in self.cards:
            card.refresh_fields()
        self.on_model_changed(schedule=True, silent=True)

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
            "求解成功"
            if solution.success
            else f"求解失败：{solution.message}"
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
