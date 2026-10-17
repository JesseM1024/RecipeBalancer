"""tkinter 图形界面：配方卡片流 / 目标设置 / 实时结果 / 一键可视化。

设计要点：

- **DPI 感知**：Windows 下启动时声明 DPI awareness 并按实际 DPI 设置
  ``tk scaling``，避免 150% 缩放时被系统位图拉伸导致文字发虚（原生像素渲染）；
- **明暗双主题**：``PALETTES`` 定义两套配色，切换时重建控件（ttk clam 主题可着色）；
- **设置**：可视化输出目录、默认导出目录、主题，持久化到
  ``~/.recipe_balancer/config.json``。

布局（左侧编辑 / 右侧结果）::

    ┌───────────────────────────────┬───────────────┐
    │ 目标产物 / 速率 / 单位          │               │
    │ 目标列表（最多 3 行，可滚动）    │               │
    │ 求解目标 / 循环检测 / 夜间模式   │   文本结果     │
    │ 外部原料                        │               │
    │ 循环亏损表                      │               │
    ├───────────────────────────────┤               │
    │ 筛选框                         │               │
    │ 配方卡片（可滚动）              │               │
    ├───────────────────────────────┤               │
    │ 新增/求解/导入/导出/设置         │ 启动浏览器按钮 │
    └───────────────────────────────┴───────────────┘
"""

from __future__ import annotations

import copy
import ctypes
import re
import sys
import time
import webbrowser
from pathlib import Path
import tkinter as tk
from tkinter import filedialog, font as tkfont, messagebox, ttk
from typing import Any, Dict, List, Optional, Set

from .config import app_dir, load_config, save_config
from .io import load_scenario, save_scenario
from .models import RecipeSolution
from .report import format_solution
from .solver import solve_production

APP_TITLE = "配方树自动配平"

#: 结果区还没有任何报告时的引导文案
REPORT_PLACEHOLDER = (
    "结果区（还没有求解结果）\n"
    "\n"
    "  1. 在上方填写目标产物与速率，回车或点「添加目标」\n"
    "  2. 在配方卡片里录入原料 / 产物：名称 + 数量，回车即添加\n"
    "  3. 按 F5（或 Ctrl+Enter / 点「求解」）\n"
    "  4. 点「启动浏览器查看可视化」生成交互流程图\n"
)

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

THEME_LABELS = {"light": "浅色", "dark": "深色"}
LABEL_TO_THEME = {v: k for k, v in THEME_LABELS.items()}

#: 1 tick = 0.05 秒（GT 20 t/s）
TICK_SECONDS = 0.05
#: 显示单位 -> 若干 tick
UNIT_TICKS = {"t": 1.0, "秒": 20.0, "分": 1200.0, "时": 72000.0}
UNIT_CHOICES = list(UNIT_TICKS)
DEFAULT_UNIT = "t"

PALETTES: Dict[str, Dict[str, str]] = {
    "light": {
        "bg": "#f3f5f8",
        "panel": "#ffffff",
        "card": "#ffffff",
        "card_border": "#dfe3ea",
        "text": "#1f2430",
        "muted": "#6b7382",
        "accent": "#2f6fed",
        "accent_hover": "#245bd0",
        "accent_text": "#ffffff",
        "entry_bg": "#ffffff",
        "entry_border": "#c9d0da",
        "list_bg": "#e9edf3",
        "row_bg": "#f4f6fa",
        "button_bg": "#eef1f6",
        "button_hover": "#e2e7ef",
        "danger": "#c0392b",
        "report_bg": "#ffffff",
        "select_bg": "#cfe0ff",
    },
    "dark": {
        "bg": "#14171d",
        "panel": "#1c2028",
        "card": "#20242d",
        "card_border": "#333a46",
        "text": "#e7eaf0",
        "muted": "#98a1b0",
        "accent": "#5b8dff",
        "accent_hover": "#7aa4ff",
        "accent_text": "#0d1117",
        "entry_bg": "#12151b",
        "entry_border": "#3a424f",
        "list_bg": "#0f1216",
        "row_bg": "#1a1e26",
        "button_bg": "#2a303b",
        "button_hover": "#353d4a",
        "danger": "#ef7373",
        "report_bg": "#13161c",
        "select_bg": "#2b3d63",
    },
}


# =====================================================================
# DPI / 字体
# =====================================================================


def enable_dpi_awareness() -> None:
    """让进程按物理像素渲染（否则 Windows 会对 150% 缩放做位图拉伸，文字发虚）。

    必须在创建 ``tk.Tk()`` 之前调用；重复调用会因权限被拒，忽略即可。
    """
    if sys.platform != "win32":
        return
    try:
        ctypes.windll.shcore.SetProcessDpiAwareness(1)  # PROCESS_SYSTEM_DPI_AWARE
    except Exception:  # noqa: BLE001 - 旧系统 / 已设置
        try:
            ctypes.windll.user32.SetProcessDPIAware()
        except Exception:  # noqa: BLE001
            pass


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
    """一张配方卡片：原料 / 产物 / 设备 / 耗时（自带单位），条目可内联编辑。"""

    def __init__(self, master, app: "RecipeBalancerApp", index: int):
        self.app = app
        self.palette = app.palette
        p = self.palette
        super().__init__(
            master,
            bd=0,
            relief="flat",
            padx=10,
            pady=8,
            bg=p["card"],
            highlightthickness=1,
            highlightbackground=p["card_border"],
        )
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
        p = self.palette
        header = tk.Frame(self, bg=p["card"])
        header.pack(fill="x")
        self.title = tk.Label(
            header,
            text="",
            bg=p["card"],
            fg=p["text"],
            font=(self.app.ui_family, 10, "bold"),
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
                bg=p["card"],
                fg=p["muted"],
                activebackground=p["button_hover"],
                activeforeground=p["text"],
                cursor="hand2",
            ).pack(side="right")

        tk.Frame(self, bg=p["card_border"], height=1).pack(fill="x", pady=(6, 0))

        self._item_section("sourceList", "原料（单次消耗）")
        self._item_section("productList", "产物（单次产出）")

        row = tk.Frame(self, bg=p["card"])
        row.pack(fill="x", pady=(8, 0))
        tk.Label(row, text="机器类型", bg=p["card"], fg=p["text"]).pack(side="left")
        self.equ_var = tk.StringVar()
        self.equ_entry = ttk.Entry(row, textvariable=self.equ_var, width=18)
        self.equ_entry.pack(side="left", padx=(6, 16))
        tk.Label(row, text="耗时", bg=p["card"], fg=p["text"]).pack(side="left")
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
        p = self.palette
        wrap = tk.Frame(self, bg=p["card"])
        wrap.pack(fill="x", pady=(8, 0))

        tk.Label(wrap, text=title, bg=p["card"], fg=p["muted"]).pack(anchor="w")
        box = tk.Frame(wrap, bg=p["card"])
        box.pack(fill="x")
        self.item_boxes[which] = box

        row = tk.Frame(wrap, bg=p["card"])
        row.pack(fill="x", pady=(3, 0))
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
        tk.Label(
            row, text="回车添加 · 条目可直接改", bg=p["card"], fg=p["muted"]
        ).pack(side="left", padx=8)
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
        p = self.palette
        box = self.item_boxes[which]
        for child in box.winfo_children():
            child.destroy()
        self.item_rows[which].clear()

        mapping = self.recipe.get(which) or {}
        if not mapping:
            hint = (
                "（空 · 求解时按 1 个空原料处理）"
                if which == "sourceList"
                else "（空 · 产物为空则该配方无效）"
            )
            tk.Label(box, text=hint, bg=p["card"], fg=p["muted"]).pack(anchor="w")
            return

        for item, amount in sorted(mapping.items()):
            # 已有条目放在凹陷底色上，与下方“新增条目”输入行区分开
            row = tk.Frame(box, bg=p["row_bg"], padx=4, pady=2)
            row.pack(fill="x", pady=1)

            name_var = tk.StringVar(value=item)
            amount_var = tk.StringVar(value=_fmt(float(amount)))
            record = {"item": item, "name_var": name_var, "amount_var": amount_var}

            name_entry = ttk.Entry(row, textvariable=name_var, width=18)
            name_entry.pack(side="left")
            amount_entry = ttk.Entry(row, textvariable=amount_var, width=8)
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
                padx=4,
                bg=p["row_bg"],
                fg=p["danger"],
                activebackground=p["button_hover"],
                activeforeground=p["danger"],
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
# 设置对话框
# =====================================================================


class SettingsDialog(tk.Toplevel):
    def __init__(self, app: "RecipeBalancerApp") -> None:
        super().__init__(app)
        self.app = app
        self.title("设置")
        self.transient(app)
        self.resizable(False, False)
        self.configure(bg=app.palette["bg"])

        frame = ttk.Frame(self, padding=14)
        frame.pack(fill="both", expand=True)

        ttk.Label(frame, text="主题").grid(row=0, column=0, sticky="w", pady=4)
        self.theme_var = tk.StringVar(value=THEME_LABELS[app.theme_name])
        ttk.Combobox(
            frame,
            textvariable=self.theme_var,
            values=list(THEME_LABELS.values()),
            state="readonly",
            width=10,
        ).grid(row=0, column=1, sticky="w", padx=(8, 0))

        ttk.Label(frame, text="可视化输出目录").grid(
            row=1, column=0, sticky="w", pady=4
        )
        self.html_var = tk.StringVar(
            value=app.config_data.get("html_output_dir", "")
        )
        ttk.Entry(frame, textvariable=self.html_var, width=46).grid(
            row=1, column=1, sticky="we", padx=(8, 4)
        )
        ttk.Button(frame, text="浏览…", command=self._browse_html).grid(
            row=1, column=2
        )

        ttk.Label(frame, text="导出默认目录").grid(row=2, column=0, sticky="w", pady=4)
        self.export_var = tk.StringVar(value=app.config_data.get("export_dir", ""))
        ttk.Entry(frame, textvariable=self.export_var, width=46).grid(
            row=2, column=1, sticky="we", padx=(8, 4)
        )
        ttk.Button(frame, text="浏览…", command=self._browse_export).grid(
            row=2, column=2
        )

        ttk.Label(
            frame,
            text="目录留空时：可视化与导出都放在程序所在目录（本 exe 旁边）。",
            foreground=app.palette["muted"],
        ).grid(row=3, column=0, columnspan=3, sticky="w", pady=(6, 10))

        buttons = ttk.Frame(frame)
        buttons.grid(row=4, column=0, columnspan=3, sticky="e")
        ttk.Button(buttons, text="保存", style="Accent.TButton", command=self._save).pack(
            side="right"
        )
        ttk.Button(buttons, text="取消", command=self.destroy).pack(
            side="right", padx=(0, 8)
        )

        self.grab_set()
        self.update_idletasks()
        self._center_on_parent()

    def _center_on_parent(self) -> None:
        x = self.app.winfo_rootx() + (self.app.winfo_width() - self.winfo_width()) // 2
        y = self.app.winfo_rooty() + (self.app.winfo_height() - self.winfo_height()) // 3
        self.geometry(f"+{max(0, x)}+{max(0, y)}")

    def _browse_html(self) -> None:
        path = filedialog.askdirectory(parent=self, title="选择可视化输出目录")
        if path:
            self.html_var.set(path)

    def _browse_export(self) -> None:
        path = filedialog.askdirectory(parent=self, title="选择默认导出目录")
        if path:
            self.export_var.set(path)

    def _save(self) -> None:
        theme = LABEL_TO_THEME.get(self.theme_var.get(), "light")
        html_output_dir = self.html_var.get().strip()
        export_dir = self.export_var.get().strip()
        self.destroy()
        self.app.apply_settings(theme, html_output_dir, export_dir)


# =====================================================================
# 主窗口
# =====================================================================


class RecipeBalancerApp(tk.Tk):
    def __init__(self) -> None:
        enable_dpi_awareness()  # 必须在创建 Tk 之前
        super().__init__()
        self.title(APP_TITLE)

        dpi = self.winfo_fpixels("1i")
        self.dpi_scale = dpi / 96.0
        self.tk.call("tk", "scaling", dpi / 72.0)

        self.config_data = load_config()
        self.theme_name = self.config_data["theme"]
        self.palette = PALETTES[self.theme_name]

        self.model = GuiState()
        self.cards: List[RecipeCard] = []
        #: 与 model.recipes 对齐的每张卡片的时间显示单位
        self.recipe_units: List[str] = []
        #: 每个目标产物自己的速率单位
        self.target_units: Dict[str, str] = {}
        self.syncing = False
        self._auto_job: Optional[str] = None
        #: 结果区文本（切主题会重建控件，需要留存以便恢复）
        self.last_report_text = ""

        self.filter_var = tk.StringVar()
        self.raw_var = tk.StringVar()
        self.cyc_var = tk.StringVar()
        self.objective_var = tk.StringVar(value=OBJECTIVE_LABELS["machine"])
        self.bootstrap_var = tk.BooleanVar(value=False)
        self.dark_var = tk.BooleanVar(value=self.theme_name == "dark")
        self.status_var = tk.StringVar(value="就绪")
        self.target_name_var = tk.StringVar()
        self.target_rate_var = tk.StringVar()
        self.target_unit_var = tk.StringVar(value=DEFAULT_UNIT)

        self._init_fonts()
        self._set_window_icon()
        self._apply_ttk_style()
        self._build_ui()
        self._bind_shortcuts()

        # 窗口尺寸：设计基准 1280x880 按 DPI 放大，但不能超出屏幕（150% 缩放下
        # 1280x880 逻辑尺寸会变成 1920x1320 物理像素，比 1080p 屏幕还高）
        screen_w = self.winfo_screenwidth()
        screen_h = self.winfo_screenheight()
        width = min(int(1280 * self.dpi_scale), int(screen_w * 0.94))
        height = min(int(880 * self.dpi_scale), int(screen_h * 0.92))
        self.geometry(
            f"{width}x{height}+{max(0, (screen_w - width) // 2)}"
            f"+{max(0, (screen_h - height) // 2 - 24)}"
        )
        self.minsize(
            min(int(1040 * self.dpi_scale), width),
            min(int(660 * self.dpi_scale), height - 40),
        )

        self.model.add_recipe()
        self.recipe_units.append(DEFAULT_UNIT)
        self._rebuild_cards()
        self._refresh_targets()
        self._schedule_auto_solve()

    # ---------------------------------------------------------------
    # 主题 / 样式
    # ---------------------------------------------------------------

    def _set_window_icon(self) -> None:
        """用 assets/app.ico 替换 Tk 默认的羽毛图标（标题栏 + 任务栏）。

        打包后图标随 `--add-data` 落在 ``sys._MEIPASS/assets``；源码运行时
        从项目根目录找。找不到就保持 Tk 默认，不影响功能。
        """
        candidates = []
        bundle_dir = getattr(sys, "_MEIPASS", None)
        if bundle_dir:
            candidates.append(Path(bundle_dir) / "assets" / "app.ico")
        candidates.append(Path(__file__).resolve().parents[2] / "assets" / "app.ico")
        for path in candidates:
            if not path.exists():
                continue
            try:
                self.iconbitmap(default=str(path))
                return
            except tk.TclError:
                continue

    def _init_fonts(self) -> None:
        families = set(tkfont.families(self))
        ui_candidates = ("Microsoft YaHei UI", "Microsoft YaHei", "Segoe UI", "Arial")
        mono_candidates = ("Cascadia Mono", "Consolas", "Courier New", "Courier")
        self.ui_family = next((f for f in ui_candidates if f in families), "TkDefaultFont")
        self.mono_family = next(
            (f for f in mono_candidates if f in families), "TkFixedFont"
        )

        for name in ("TkDefaultFont", "TkTextFont", "TkMenuFont", "TkHeadingFont"):
            try:
                tkfont.nametofont(name).configure(family=self.ui_family, size=10)
            except tk.TclError:
                pass
        self.report_font = (self.mono_family, 10)
        self.ui_bold = (self.ui_family, 10, "bold")

    def _apply_ttk_style(self) -> None:
        p = self.palette
        style = ttk.Style(self)
        style.theme_use("clam")

        style.configure(".", background=p["bg"], foreground=p["text"])
        style.configure("TFrame", background=p["bg"])
        style.configure("TLabel", background=p["bg"], foreground=p["text"])
        style.configure("Muted.TLabel", background=p["bg"], foreground=p["muted"])
        style.configure(
            "TLabelframe",
            background=p["bg"],
            bordercolor=p["card_border"],
            relief="solid",
            borderwidth=1,
        )
        style.configure(
            "TLabelframe.Label",
            background=p["bg"],
            foreground=p["text"],
            font=self.ui_bold,
        )
        style.configure(
            "TEntry",
            fieldbackground=p["entry_bg"],
            foreground=p["text"],
            insertcolor=p["text"],
            bordercolor=p["entry_border"],
            lightcolor=p["entry_border"],
            darkcolor=p["entry_border"],
            padding=(5, 3),
        )
        style.configure(
            "TButton",
            background=p["button_bg"],
            foreground=p["text"],
            bordercolor=p["card_border"],
            focuscolor=p["accent"],
            padding=(11, 4),
        )
        style.map(
            "TButton",
            background=[("active", p["button_hover"])],
            foreground=[("disabled", p["muted"])],
        )
        style.configure(
            "Accent.TButton",
            background=p["accent"],
            foreground=p["accent_text"],
            bordercolor=p["accent"],
        )
        style.map(
            "Accent.TButton",
            background=[("active", p["accent_hover"])],
            foreground=[("disabled", p["muted"])],
        )
        style.configure(
            "AccentLarge.TButton",
            background=p["accent"],
            foreground=p["accent_text"],
            bordercolor=p["accent"],
            padding=(12, 7),
            font=self.ui_bold,
        )
        style.map(
            "AccentLarge.TButton",
            background=[("active", p["accent_hover"])],
            foreground=[("disabled", p["muted"])],
        )
        style.configure("TCheckbutton", background=p["bg"], foreground=p["text"])
        style.map(
            "TCheckbutton",
            background=[("active", p["bg"])],
            indicatorcolor=[("selected", p["accent"]), ("!selected", p["entry_bg"])],
        )
        style.configure(
            "TCombobox",
            fieldbackground=p["entry_bg"],
            background=p["button_bg"],
            foreground=p["text"],
            arrowcolor=p["text"],
            bordercolor=p["entry_border"],
            lightcolor=p["entry_border"],
            darkcolor=p["entry_border"],
            selectbackground=p["select_bg"],
            selectforeground=p["text"],
            padding=(5, 3),
        )
        style.map(
            "TCombobox",
            fieldbackground=[("readonly", p["entry_bg"])],
            foreground=[("readonly", p["text"])],
            bordercolor=[("focus", p["accent"])],
        )
        style.configure(
            "Vertical.TScrollbar",
            background=p["button_bg"],
            troughcolor=p["bg"],
            bordercolor=p["bg"],
            arrowcolor=p["text"],
            lightcolor=p["button_bg"],
            darkcolor=p["button_bg"],
            arrowsize=11,
        )
        style.configure(
            "Horizontal.TScrollbar",
            background=p["button_bg"],
            troughcolor=p["bg"],
            bordercolor=p["bg"],
            arrowcolor=p["text"],
            lightcolor=p["button_bg"],
            darkcolor=p["button_bg"],
            arrowsize=11,
        )
        style.configure(
            "TSeparator",
            background=p["card_border"],
        )

        # Combobox 下拉列表（Listbox 是经典控件，需用 option 数据库着色）
        self.option_add("*TCombobox*Listbox.background", p["panel"])
        self.option_add("*TCombobox*Listbox.foreground", p["text"])
        self.option_add("*TCombobox*Listbox.selectBackground", p["accent"])
        self.option_add("*TCombobox*Listbox.selectForeground", p["accent_text"])

    def apply_theme(self, name: str) -> None:
        if name not in PALETTES or name == self.theme_name:
            return
        self.theme_name = name
        self.palette = PALETTES[name]
        self.config_data["theme"] = name
        self.dark_var.set(name == "dark")
        self._apply_ttk_style()
        self._rebuild_ui()

    def _toggle_dark(self) -> None:
        self.apply_settings(theme="dark" if self.dark_var.get() else "light")

    def apply_settings(
        self,
        theme: Optional[str] = None,
        html_output_dir: Optional[str] = None,
        export_dir: Optional[str] = None,
    ) -> None:
        if theme is None:
            theme = self.theme_name
        if html_output_dir is None:
            html_output_dir = self.config_data.get("html_output_dir", "")
        if export_dir is None:
            export_dir = self.config_data.get("export_dir", "")
        self.config_data["html_output_dir"] = html_output_dir
        self.config_data["export_dir"] = export_dir
        self.config_data["theme"] = theme
        saved = save_config(self.config_data)
        if theme != self.theme_name:
            self.apply_theme(theme)
        self.set_status(
            "设置已保存" if saved else "设置已生效，但写入配置文件失败（仅本次有效）"
        )

    def _open_settings(self) -> None:
        SettingsDialog(self)

    # ---------------------------------------------------------------
    # 布局
    # ---------------------------------------------------------------

    def _build_ui(self) -> None:
        self.root_frame = ttk.Frame(self, padding=10)
        self.root_frame.pack(fill="both", expand=True)
        self._build_contents(self.root_frame)

    def _rebuild_ui(self) -> None:
        self.cards.clear()
        for child in self.root_frame.winfo_children():
            child.destroy()
        self._build_contents(self.root_frame)
        self._rebuild_cards()
        self._refresh_targets()
        self._schedule_auto_solve()

    def _build_contents(self, root) -> None:
        status = ttk.Label(root, textvariable=self.status_var, style="Muted.TLabel")
        status.pack(side="bottom", fill="x", pady=(8, 0))

        body = ttk.Frame(root)
        body.pack(fill="both", expand=True)
        body.columnconfigure(0, weight=3, uniform="cols")
        body.columnconfigure(1, weight=2, uniform="cols")
        body.rowconfigure(0, weight=1)

        left = ttk.Frame(body)
        left.grid(row=0, column=0, sticky="nsew", padx=(0, 10))
        right = ttk.Frame(body)
        right.grid(row=0, column=1, sticky="nsew")
        self.body = body
        self.left_frame = left
        self.right_frame = right

        self._build_left(left)
        self._build_right(right)

    def _build_left(self, parent) -> None:
        self._build_left_buttons(parent)
        self._build_settings(parent)
        self._build_filter(parent)
        self._build_recipe_list(parent)

    # ---- 顶部：目标与选项 ----
    def _build_settings(self, parent) -> None:
        frame = ttk.LabelFrame(parent, text="目标与选项", padding=10)
        frame.pack(side="top", fill="x")

        row = ttk.Frame(frame)
        row.pack(fill="x", pady=(0, 6))
        ttk.Label(row, text="目标产物").pack(side="left")
        ttk.Entry(row, textvariable=self.target_name_var, width=14).pack(
            side="left", padx=(8, 6)
        )
        ttk.Entry(row, textvariable=self.target_rate_var, width=8).pack(side="left")
        ttk.Combobox(
            row,
            textvariable=self.target_unit_var,
            values=UNIT_CHOICES,
            state="readonly",
            width=5,
        ).pack(side="left", padx=(4, 10))
        ttk.Button(row, text="添加目标", command=self._add_target).pack(side="left")

        # 目标列表：最多显示 3 行，超出滚动
        targets_wrap = ttk.Frame(frame)
        targets_wrap.pack(fill="x")
        self.targets_canvas = tk.Canvas(
            targets_wrap,
            height=30,
            highlightthickness=0,
            bd=0,
            bg=self.palette["bg"],
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
        line2.pack(fill="x", pady=(8, 0))
        ttk.Label(line2, text="求解目标").pack(side="left")
        objective_box = ttk.Combobox(
            line2,
            textvariable=self.objective_var,
            values=list(OBJECTIVE_LABELS.values()),
            state="readonly",
            width=14,
        )
        objective_box.pack(side="left", padx=(6, 16))
        objective_box.bind("<<ComboboxSelected>>", self._on_option_changed)
        ttk.Checkbutton(
            line2,
            text="循环启动库存检测",
            variable=self.bootstrap_var,
            command=self._on_option_changed,
        ).pack(side="left")
        ttk.Checkbutton(
            line2, text="夜间模式", variable=self.dark_var, command=self._toggle_dark
        ).pack(side="right")

        # 外部原料、循环亏损表各占一行
        raw_line = ttk.Frame(frame)
        raw_line.pack(fill="x", pady=(6, 0))
        ttk.Label(raw_line, text="外部原料", width=10).pack(side="left")
        ttk.Entry(raw_line, textvariable=self.raw_var, width=8).pack(
            side="left", fill="x", expand=True, padx=(6, 0)
        )

        cyc_line = ttk.Frame(frame)
        cyc_line.pack(fill="x", pady=(6, 0))
        ttk.Label(cyc_line, text="循环亏损表", width=10).pack(side="left")
        ttk.Entry(cyc_line, textvariable=self.cyc_var, width=8).pack(
            side="left", fill="x", expand=True, padx=(6, 0)
        )

        for var in (self.raw_var, self.cyc_var):
            var.trace_add("write", self._on_options_text_changed)

        self.extra_label = ttk.Label(frame, text="", style="Muted.TLabel")
        self.extra_label.pack(fill="x", pady=(6, 0))

    # ---- 筛选框 ----
    def _build_filter(self, parent) -> None:
        frame = ttk.Frame(parent)
        frame.pack(side="top", fill="x", pady=(10, 4))
        ttk.Label(frame, text="筛选").pack(side="left")
        ttk.Entry(frame, textvariable=self.filter_var).pack(
            side="left", fill="x", expand=True, padx=(8, 0)
        )
        self.filter_var.trace_add("write", lambda *_: self._apply_filter())
        ttk.Label(frame, text="按配方物品 / 设备过滤", style="Muted.TLabel").pack(
            side="left", padx=8
        )

    # ---- 配方列表（可滚动）----
    def _build_recipe_list(self, parent) -> None:
        wrap = ttk.Frame(parent)
        wrap.pack(side="top", fill="both", expand=True)

        self.canvas = tk.Canvas(
            wrap,
            bg=self.palette["list_bg"],
            highlightthickness=0,
            bd=0,
        )
        scrollbar = ttk.Scrollbar(wrap, orient="vertical", command=self.canvas.yview)
        self.canvas.configure(yscrollcommand=scrollbar.set)
        self.canvas.pack(side="left", fill="both", expand=True)
        scrollbar.pack(side="right", fill="y")

        self.cards_frame = tk.Frame(self.canvas, bg=self.palette["list_bg"])
        self._canvas_window = self.canvas.create_window(
            (0, 0), window=self.cards_frame, anchor="nw"
        )
        self.cards_frame.columnconfigure(0, weight=1)
        self.cards_frame.bind("<Configure>", self._on_cards_frame_configure)
        self.canvas.bind("<Configure>", self._on_canvas_configure)
        self.canvas.bind("<MouseWheel>", self._on_wheel)

    def _build_left_buttons(self, parent) -> None:
        bar = ttk.Frame(parent)
        bar.pack(side="bottom", fill="x", pady=(8, 0))
        ttk.Button(bar, text="新增配方", command=self.add_recipe).pack(side="left")
        ttk.Button(
            bar, text="求解", style="Accent.TButton", command=self._on_solve
        ).pack(side="left", padx=6)
        ttk.Button(bar, text="导入 JSON…", command=self._on_import).pack(side="left")
        ttk.Button(bar, text="导出 JSON…", command=self._on_export).pack(
            side="left", padx=6
        )
        ttk.Button(bar, text="设置…", command=self._open_settings).pack(side="left")

    def _section_title(self, parent, text: str) -> ttk.Frame:
        """带强调色竖条的小标题，用于右侧结果区等无边框区域。"""
        header = ttk.Frame(parent)
        tk.Frame(header, bg=self.palette["accent"], width=3, height=13).pack(
            side="left", padx=(0, 6)
        )
        ttk.Label(header, text=text, font=self.ui_bold).pack(side="left")
        return header

    # ---- 右侧：结果 ----
    def _build_right(self, parent) -> None:
        header = self._section_title(parent, "文本结果")
        header.pack(fill="x")

        wrap = ttk.Frame(parent)
        wrap.pack(fill="both", expand=True, pady=(6, 8))
        p = self.palette
        self.result_text = tk.Text(
            wrap,
            wrap="none",
            font=self.report_font,
            state="disabled",
            width=1,
            height=1,
            bg=p["report_bg"],
            fg=p["text"],
            insertbackground=p["text"],
            selectbackground=p["select_bg"],
            selectforeground=p["text"],
            relief="flat",
            padx=8,
            pady=6,
            highlightthickness=1,
            highlightbackground=p["card_border"],
            highlightcolor=p["accent"],
        )
        yscroll = ttk.Scrollbar(wrap, orient="vertical", command=self.result_text.yview)
        xscroll = ttk.Scrollbar(wrap, orient="horizontal", command=self.result_text.xview)
        self.result_text.configure(
            yscrollcommand=yscroll.set, xscrollcommand=xscroll.set
        )
        self.result_text.tag_configure("placeholder", foreground=p["muted"])
        self.result_text.grid(row=0, column=0, sticky="nsew")
        yscroll.grid(row=0, column=1, sticky="ns")
        xscroll.grid(row=1, column=0, sticky="ew")
        wrap.rowconfigure(0, weight=1)
        wrap.columnconfigure(0, weight=1)
        # 重建（切主题）后恢复上一次的结果，没有结果时显示引导文案
        self._render_report_text(self.last_report_text)

        ttk.Button(
            parent,
            text="启动浏览器查看可视化",
            style="AccentLarge.TButton",
            command=self._on_visualize,
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
                bg=self.palette["list_bg"],
                fg=self.palette["muted"],
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
        self.canvas.yview_moveto(1.0)
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
        p = self.palette
        for child in self.targets_frame.winfo_children():
            child.destroy()
        if not self.model.targets:
            ttk.Label(
                self.targets_frame, text="（未设置目标产物）", style="Muted.TLabel"
            ).pack(anchor="w", pady=2)
        else:
            for item, per_second in sorted(self.model.targets.items()):
                unit = self.target_units.get(item, DEFAULT_UNIT)
                row = ttk.Frame(self.targets_frame)
                row.pack(fill="x")
                # 名称列定宽对齐，末列留空吸收多余宽度 -> 数量/单位/删除始终紧跟名称
                row.columnconfigure(4, weight=1)
                ttk.Label(row, text=item, width=12, anchor="w").grid(
                    row=0, column=0, sticky="w", padx=(2, 8), pady=2
                )
                var = tk.StringVar(value=_fmt(rate_to_display(per_second, unit)))
                entry = ttk.Entry(row, textvariable=var, width=9, justify="right")
                entry.grid(row=0, column=1, sticky="w", pady=2)
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
                    width=5,
                )
                combo.grid(row=0, column=2, sticky="w", padx=(6, 8), pady=2)
                combo.bind(
                    "<<ComboboxSelected>>",
                    lambda _e, i=item, u=unit_var: self._on_target_unit_changed(i, u),
                )
                tk.Button(
                    row,
                    text="✕",
                    bd=0,
                    padx=4,
                    bg=p["bg"],
                    fg=p["danger"],
                    activebackground=p["button_hover"],
                    activeforeground=p["danger"],
                    cursor="hand2",
                    command=lambda i=item: self._remove_target(i),
                ).grid(row=0, column=3, sticky="w")
        self.targets_frame.update_idletasks()
        # 高度随条数自适应：1~3 行按实际行数，超过 3 行固定 3 行并滚动
        visible_rows = max(1, min(3, len(self.model.targets)))
        children = self.targets_frame.winfo_children()
        row_height = max((c.winfo_reqheight() for c in children), default=28) + 1
        self.targets_canvas.configure(height=visible_rows * row_height + 2)
        if len(self.model.targets) > 3:
            self.targets_scroll.pack(side="right", fill="y")
        else:
            self.targets_scroll.pack_forget()
        self.targets_canvas.configure(scrollregion=self.targets_canvas.bbox("all"))
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

    def _render_report_text(self, text: str) -> None:
        widget = self.result_text
        widget.configure(state="normal")
        widget.delete("1.0", "end")
        if text:
            widget.insert("1.0", text)
        else:
            widget.insert("1.0", REPORT_PLACEHOLDER, "placeholder")
        widget.configure(state="disabled")

    def _show_report(self, solution: RecipeSolution) -> None:
        self.last_report_text = format_solution(solution, self.model.recipes)
        self._render_report_text(self.last_report_text)

    def _html_output_dir(self) -> Path:
        """可视化 HTML 输出目录：配置优先，否则就是程序所在目录（exe 旁边）。"""
        configured = (self.config_data.get("html_output_dir") or "").strip()
        if configured:
            return Path(configured)
        return app_dir()

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

        out_dir = self._html_output_dir()
        try:
            out_dir.mkdir(parents=True, exist_ok=True)
        except OSError as exc:
            messagebox.showerror("目录不可用", f"{out_dir}\n{exc}")
            return
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
        initial_dir = (self.config_data.get("export_dir") or "").strip() or str(
            app_dir()
        )
        path = filedialog.asksaveasfilename(
            title="导出场景 JSON",
            defaultextension=".json",
            initialfile="scenario.json",
            initialdir=initial_dir,
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
        # 记住本次导出目录
        self.config_data["export_dir"] = str(Path(path).parent)
        save_config(self.config_data)
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
    enable_dpi_awareness()
    app = RecipeBalancerApp()
    app.mainloop()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
