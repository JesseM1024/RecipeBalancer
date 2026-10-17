"""tkinter 图形界面：配方录入 / 目标选择 / 结果查看 / 一键可视化。

启动方式::

    python -m recipe_balancer.gui
    python -m recipe_balancer gui

说明：tkinter 属于标准库，但部分精简版 Python（如 WorkBuddy 托管环境）
不包含 ``_tkinter``。本项目在根目录提供基于官方 Python 3.10 的 ``.venv``，
GUI 请用该解释器启动（见 README「图形界面」一节）。
"""

from __future__ import annotations

import copy
import tempfile
import time
import webbrowser
from pathlib import Path
from tkinter import filedialog, font as tkfont, messagebox, ttk
import re
import tkinter as tk
from typing import Any, Dict, List, Optional, Set

from .io import load_scenario, save_scenario
from .models import RecipeSolution
from .report import format_solution
from .solver import solve_production

APP_TITLE = "配方树自动配平"
TEMPLATE_RECIPE = {
    "sourceList": {"输入物品": 1},
    "productList": {"输出物品": 1},
    "time": 1.0,
    "equType": "设备",
}

OBJECTIVE_LABELS = {
    "machine": "machine｜最小机器数",
    "recipes": "recipes｜最小配方数",
}
LABEL_TO_OBJECTIVE = {v: k for k, v in OBJECTIVE_LABELS.items()}


# =====================================================================
# 界面状态（与控件解耦，便于测试）
# =====================================================================


class GuiState:
    """GUI 的内存模型：配方列表 / 目标 / 选项 / 上次求解结果。"""

    def __init__(self) -> None:
        self.recipes: List[dict] = []
        self.targets: Dict[str, float] = {}
        self.raw_materials: Set[str] = set()
        self.objective: str = "machine"
        self.auto_bootstrap: bool = False
        #: 从 JSON 导入但界面未暴露的选项（disabled_recipes / 白名单 / auto_detect_* 等）
        self.extra_options: Dict[str, Any] = {}
        self.last_solution: Optional[RecipeSolution] = None

    # ---- 场景读写 ----
    @classmethod
    def from_scenario(
        cls,
        recipes: List[dict],
        targets: Dict[str, float],
        kwargs: Dict[str, Any],
    ) -> "GuiState":
        state = cls()
        state.recipes = copy.deepcopy(recipes)
        state.targets = dict(targets)
        state.raw_materials = set(kwargs.get("raw_materials") or set())
        state.objective = kwargs.get("objective", "machine")
        state.auto_bootstrap = bool(kwargs.get("auto_bootstrap", False))
        state.extra_options = {
            k: v
            for k, v in kwargs.items()
            if k not in ("raw_materials", "objective", "auto_bootstrap")
        }
        return state

    def options_for_save(self) -> Dict[str, Any]:
        options: Dict[str, Any] = {
            "raw_materials": sorted(self.raw_materials),
            "objective": self.objective,
            "auto_bootstrap": self.auto_bootstrap,
        }
        options.update(self.extra_options)
        return options

    # ---- 编辑 ----
    def add_recipe(self, recipe: Optional[dict] = None) -> int:
        self.recipes.append(copy.deepcopy(recipe or TEMPLATE_RECIPE))
        return len(self.recipes) - 1

    def remove_recipe(self, index: int) -> Optional[int]:
        if 0 <= index < len(self.recipes):
            del self.recipes[index]
            return min(index, len(self.recipes) - 1) if self.recipes else None
        return None

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
            errors.append("未设置目标产物：请在「目标与选项」页添加")

        for i, recipe in enumerate(self.recipes):
            label = f"配方 #{i + 1}"
            equ = str(recipe.get("equType", "")).strip()
            if not equ:
                errors.append(f"{label}: 设备类型不能为空")
            try:
                t = float(recipe.get("time", 0))
                if t <= 0:
                    errors.append(f"{label}: 单次耗时必须大于 0")
            except (TypeError, ValueError):
                errors.append(f"{label}: 单次耗时不是有效数字")
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
        return solve_production(self.recipes, self.targets, **kwargs)


# =====================================================================
# 主窗口
# =====================================================================


class RecipeBalancerApp(tk.Tk):
    def __init__(self) -> None:
        super().__init__()
        self.title(APP_TITLE)
        self.geometry("1240x800")
        self.minsize(1000, 640)

        self.model = GuiState()
        self.current_index: Optional[int] = None

        self._init_fonts()
        self._init_vars()
        self._build_ui()
        self._bind_shortcuts()

        # 初始给一个模板配方，界面立即可编辑
        self.model.add_recipe()
        self._refresh_all(select=0)

    # ---------------------------------------------------------------
    # 初始化
    # ---------------------------------------------------------------

    def _init_fonts(self) -> None:
        for name in ("TkDefaultFont", "TkTextFont", "TkMenuFont", "TkHeadingFont"):
            try:
                tkfont.nametofont(name).configure(family="Microsoft YaHei UI", size=10)
            except tk.TclError:
                pass
        self.report_font = ("Consolas", 10)

    def _init_vars(self) -> None:
        self.equ_var = tk.StringVar()
        self.time_var = tk.StringVar()
        self.src_name_var = tk.StringVar()
        self.src_amount_var = tk.StringVar()
        self.prod_name_var = tk.StringVar()
        self.prod_amount_var = tk.StringVar()
        self.target_name_var = tk.StringVar()
        self.target_rate_var = tk.StringVar()
        self.raw_var = tk.StringVar()
        self.objective_var = tk.StringVar(value=OBJECTIVE_LABELS["machine"])
        self.bootstrap_var = tk.BooleanVar(value=False)
        self.status_var = tk.StringVar(value="就绪")

    def _build_ui(self) -> None:
        root = ttk.Frame(self, padding=8)
        root.pack(fill="both", expand=True)

        paned = ttk.Panedwindow(root, orient="horizontal")
        paned.pack(fill="both", expand=True)

        self._build_recipe_panel(paned)

        notebook = ttk.Notebook(paned)
        paned.add(notebook, weight=4)
        self._build_editor_tab(notebook)
        self._build_target_tab(notebook)
        self._build_result_tab(notebook)

        self._build_action_bar(root)

    # ---- 左侧：配方列表 ----
    def _build_recipe_panel(self, parent) -> None:
        frame = ttk.LabelFrame(parent, text="配方列表", padding=6)
        parent.add(frame, weight=1)

        columns = ("no", "equ", "time", "src", "prod")
        self.recipe_tree = ttk.Treeview(
            frame, columns=columns, show="headings", selectmode="browse", height=24
        )
        headings = {
            "no": ("#", 40),
            "equ": ("设备", 110),
            "time": ("耗时", 60),
            "src": ("原料", 50),
            "prod": ("产物", 50),
        }
        for key, (text, width) in headings.items():
            self.recipe_tree.heading(key, text=text)
            self.recipe_tree.column(key, width=width, anchor="center", stretch=False)
        self.recipe_tree.column("equ", stretch=True, anchor="w")

        scroll = ttk.Scrollbar(frame, orient="vertical", command=self.recipe_tree.yview)
        self.recipe_tree.configure(yscrollcommand=scroll.set)
        self.recipe_tree.pack(side="left", fill="both", expand=True)
        scroll.pack(side="right", fill="y")
        self.recipe_tree.bind("<<TreeviewSelect>>", self._on_recipe_select)

        buttons = ttk.Frame(frame)
        buttons.pack(side="bottom", fill="x", pady=(6, 0))
        ttk.Button(buttons, text="新增", width=6, command=self._on_new_recipe).pack(
            side="left", padx=2
        )
        ttk.Button(buttons, text="复制", width=6, command=self._on_duplicate_recipe).pack(
            side="left", padx=2
        )
        ttk.Button(buttons, text="删除", width=6, command=self._on_delete_recipe).pack(
            side="left", padx=2
        )
        ttk.Button(buttons, text="↑", width=3, command=lambda: self._on_move(-1)).pack(
            side="left", padx=2
        )
        ttk.Button(buttons, text="↓", width=3, command=lambda: self._on_move(1)).pack(
            side="left", padx=2
        )

    # ---- 右侧：配方编辑 ----
    def _build_editor_tab(self, notebook) -> None:
        tab = ttk.Frame(notebook, padding=8)
        notebook.add(tab, text="配方编辑")

        top = ttk.Frame(tab)
        top.pack(fill="x")
        ttk.Label(top, text="设备类型").pack(side="left")
        self.ent_equ = ttk.Entry(top, textvariable=self.equ_var, width=18)
        self.ent_equ.pack(side="left", padx=(4, 16))
        ttk.Label(top, text="单次耗时（秒）").pack(side="left")
        self.ent_time = ttk.Entry(top, textvariable=self.time_var, width=12)
        self.ent_time.pack(side="left", padx=4)

        self.editor_widgets = [self.ent_equ, self.ent_time]

        hint = ttk.Label(
            tab,
            text="提示：修改设备/耗时后切换到别的配方或点击求解会自动保存；物品表双击可回填到输入框。",
            foreground="#666666",
        )
        hint.pack(fill="x", pady=(4, 8))

        body = ttk.Frame(tab)
        body.pack(fill="both", expand=True)

        self.src_tree = self._build_item_section(
            body,
            title="原料（单次消耗）",
            name_var=self.src_name_var,
            amount_var=self.src_amount_var,
            which="sourceList",
        )
        self.prod_tree = self._build_item_section(
            body,
            title="产物（单次产出）",
            name_var=self.prod_name_var,
            amount_var=self.prod_amount_var,
            which="productList",
        )

    def _build_item_section(self, parent, title, name_var, amount_var, which) -> ttk.Treeview:
        frame = ttk.LabelFrame(parent, text=title, padding=6)
        frame.pack(side="left", fill="both", expand=True, padx=4)

        tree = ttk.Treeview(
            frame, columns=("item", "amount"), show="headings", selectmode="browse", height=14
        )
        tree.heading("item", text="物品")
        tree.heading("amount", text="数量")
        tree.column("item", width=180, anchor="w")
        tree.column("amount", width=80, anchor="e")
        tree.pack(fill="both", expand=True)

        entry_row = ttk.Frame(frame)
        entry_row.pack(fill="x", pady=(6, 0))
        ttk.Entry(entry_row, textvariable=name_var, width=16).pack(side="left", padx=(0, 4))
        ttk.Entry(entry_row, textvariable=amount_var, width=8).pack(side="left", padx=(0, 4))
        ttk.Button(
            entry_row, text="添加", width=5, command=lambda: self._add_item(which)
        ).pack(side="left")
        ttk.Button(
            entry_row,
            text="删除",
            width=5,
            command=lambda: self._remove_item(which),
        ).pack(side="left", padx=4)

        tree.bind(
            "<Double-1>",
            lambda _e: self._fill_item_entries(tree, name_var, amount_var),
        )
        self.editor_widgets.extend(
            [w for w in frame.winfo_children() if isinstance(w, ttk.Frame)]
        )
        return tree

    # ---- 右侧：目标与选项 ----
    def _build_target_tab(self, notebook) -> None:
        tab = ttk.Frame(notebook, padding=8)
        notebook.add(tab, text="目标与选项")

        target_frame = ttk.LabelFrame(tab, text="目标产物（速率 = 个/秒）", padding=6)
        target_frame.pack(fill="both", expand=True)

        self.target_tree = ttk.Treeview(
            target_frame,
            columns=("item", "rate"),
            show="headings",
            selectmode="browse",
            height=8,
        )
        self.target_tree.heading("item", text="产物")
        self.target_tree.heading("rate", text="速率")
        self.target_tree.column("item", width=220, anchor="w")
        self.target_tree.column("rate", width=100, anchor="e")
        self.target_tree.pack(fill="both", expand=True)

        row = ttk.Frame(target_frame)
        row.pack(fill="x", pady=(6, 0))
        ttk.Entry(row, textvariable=self.target_name_var, width=18).pack(side="left", padx=(0, 4))
        ttk.Entry(row, textvariable=self.target_rate_var, width=10).pack(side="left", padx=(0, 4))
        ttk.Button(row, text="添加", width=5, command=self._add_target).pack(side="left")
        ttk.Button(row, text="删除", width=5, command=self._remove_target).pack(
            side="left", padx=4
        )
        self.target_tree.bind("<Double-1>", self._fill_target_entries)

        options = ttk.LabelFrame(tab, text="求解选项", padding=8)
        options.pack(fill="x", pady=(10, 0))

        line1 = ttk.Frame(options)
        line1.pack(fill="x", pady=2)
        ttk.Label(line1, text="求解目标").pack(side="left")
        self.objective_box = ttk.Combobox(
            line1,
            textvariable=self.objective_var,
            values=list(OBJECTIVE_LABELS.values()),
            state="readonly",
            width=22,
        )
        self.objective_box.pack(side="left", padx=(4, 16))
        ttk.Checkbutton(
            line1,
            text="检测完美循环启动投料",
            variable=self.bootstrap_var,
        ).pack(side="left")

        line2 = ttk.Frame(options)
        line2.pack(fill="x", pady=2)
        ttk.Label(line2, text="外部原料").pack(side="left")
        ttk.Entry(line2, textvariable=self.raw_var).pack(
            side="left", fill="x", expand=True, padx=4
        )
        ttk.Label(line2, text="多个用逗号分隔").pack(side="left")

        self.extra_label = ttk.Label(options, text="", foreground="#666666")
        self.extra_label.pack(fill="x", pady=(4, 0))

    # ---- 右侧：结果 ----
    def _build_result_tab(self, notebook) -> None:
        tab = ttk.Frame(notebook, padding=8)
        notebook.add(tab, text="结果")

        wrap = ttk.Frame(tab)
        wrap.pack(fill="both", expand=True)
        self.result_text = tk.Text(
            wrap, wrap="none", font=self.report_font, state="disabled", background="#fbfbfb"
        )
        yscroll = ttk.Scrollbar(wrap, orient="vertical", command=self.result_text.yview)
        xscroll = ttk.Scrollbar(wrap, orient="horizontal", command=self.result_text.xview)
        self.result_text.configure(yscrollcommand=yscroll.set, xscrollcommand=xscroll.set)
        self.result_text.grid(row=0, column=0, sticky="nsew")
        yscroll.grid(row=0, column=1, sticky="ns")
        xscroll.grid(row=1, column=0, sticky="ew")
        wrap.rowconfigure(0, weight=1)
        wrap.columnconfigure(0, weight=1)

    # ---- 底部操作条 ----
    def _build_action_bar(self, parent) -> None:
        bar = ttk.Frame(parent)
        bar.pack(fill="x", pady=(8, 0))

        ttk.Button(bar, text="求解", command=self._on_solve).pack(side="left")
        ttk.Button(
            bar, text="生成可视化并打开浏览器", command=self._on_visualize
        ).pack(side="left", padx=6)
        ttk.Button(bar, text="导入 JSON…", command=self._on_import).pack(side="left")
        ttk.Button(bar, text="导出 JSON…", command=self._on_export).pack(side="left", padx=6)

        ttk.Label(bar, textvariable=self.status_var, foreground="#444444").pack(
            side="right"
        )

    def _bind_shortcuts(self) -> None:
        self.bind("<Control-Return>", lambda _e: self._on_solve())
        self.bind("<F5>", lambda _e: self._on_solve())
        self.bind("<Control-g>", lambda _e: self._on_visualize())
        self.bind("<Control-o>", lambda _e: self._on_import())
        self.bind("<Control-s>", lambda _e: self._on_export())

    # ---------------------------------------------------------------
    # 数据同步
    # ---------------------------------------------------------------

    def _current_recipe(self) -> Optional[dict]:
        idx = self.current_index
        if idx is None or not (0 <= idx < len(self.model.recipes)):
            return None
        return self.model.recipes[idx]

    def _commit_editor(self) -> None:
        recipe = self._current_recipe()
        if recipe is None:
            return
        equ = self.equ_var.get().strip()
        if equ:
            recipe["equType"] = equ
        try:
            t = float(self.time_var.get())
            if t > 0:
                recipe["time"] = t
        except ValueError:
            pass
        self._refresh_recipe_list()

    def _refresh_all(self, select: Optional[int] = None) -> None:
        self._refresh_recipe_list()
        self._refresh_item_tables()
        self._refresh_targets()
        if select is not None and self.model.recipes:
            self._select_recipe(select)

    def _refresh_recipe_list(self) -> None:
        self.recipe_tree.delete(*self.recipe_tree.get_children())
        for idx, recipe in enumerate(self.model.recipes):
            self.recipe_tree.insert(
                "",
                "end",
                iid=str(idx),
                values=(
                    idx + 1,
                    recipe.get("equType", ""),
                    f"{float(recipe.get('time', 0)):g}",
                    len(recipe.get("sourceList") or {}),
                    len(recipe.get("productList") or {}),
                ),
            )
        if self.current_index is not None and 0 <= self.current_index < len(self.model.recipes):
            self.recipe_tree.selection_set(str(self.current_index))

    def _refresh_item_tables(self) -> None:
        recipe = self._current_recipe()
        self.src_tree.delete(*self.src_tree.get_children())
        self.prod_tree.delete(*self.prod_tree.get_children())
        if recipe is None:
            return
        for item, amount in sorted((recipe.get("sourceList") or {}).items()):
            self.src_tree.insert("", "end", values=(item, f"{float(amount):g}"))
        for item, amount in sorted((recipe.get("productList") or {}).items()):
            self.prod_tree.insert("", "end", values=(item, f"{float(amount):g}"))

    def _refresh_targets(self) -> None:
        self.target_tree.delete(*self.target_tree.get_children())
        for item, rate in sorted(self.model.targets.items()):
            self.target_tree.insert("", "end", values=(item, f"{float(rate):g}"))
        self.extra_label.configure(
            text=(
                f"已保留 {len(self.model.extra_options)} 个来自 JSON 的高级选项"
                f"（如禁用配方 / 循环亏损白名单）"
                if self.model.extra_options
                else ""
            )
        )

    def _select_recipe(self, index: int) -> None:
        if not self.model.recipes:
            self.current_index = None
            return
        index = max(0, min(index, len(self.model.recipes) - 1))
        self.current_index = index
        self.recipe_tree.selection_set(str(index))
        self.recipe_tree.focus(str(index))
        self._load_recipe(index)

    def _load_recipe(self, index: int) -> None:
        recipe = self.model.recipes[index]
        self.equ_var.set(str(recipe.get("equType", "")))
        self.time_var.set(f"{float(recipe.get('time', 0)):g}")
        self._refresh_item_tables()
        self.status_var.set(f"正在编辑配方 #{index + 1}")

    # ---------------------------------------------------------------
    # 左侧操作
    # ---------------------------------------------------------------

    def _on_recipe_select(self, _event=None) -> None:
        selection = self.recipe_tree.selection()
        if not selection:
            return
        new_index = int(selection[0])
        if self.current_index is not None and new_index != self.current_index:
            self._commit_editor()
        self.current_index = new_index
        self._load_recipe(new_index)

    def _on_new_recipe(self) -> None:
        self._commit_editor()
        idx = self.model.add_recipe()
        self._refresh_all(select=idx)

    def _on_duplicate_recipe(self) -> None:
        recipe = self._current_recipe()
        if recipe is None:
            messagebox.showinfo("提示", "请先选择一个配方")
            return
        self._commit_editor()
        idx = self.model.add_recipe(recipe)
        self._refresh_all(select=idx)

    def _on_delete_recipe(self) -> None:
        if self.current_index is None:
            messagebox.showinfo("提示", "请先选择一个配方")
            return
        self.model.remove_recipe(self.current_index)
        if not self.model.recipes:
            self.current_index = None
            self.equ_var.set("")
            self.time_var.set("")
            self._refresh_all()
            return
        self._refresh_all(select=max(0, self.current_index - 1))

    def _on_move(self, delta: int) -> None:
        if self.current_index is None:
            return
        self._commit_editor()
        new_index = self.model.move_recipe(self.current_index, delta)
        self._refresh_all(select=new_index)

    # ---------------------------------------------------------------
    # 编辑操作
    # ---------------------------------------------------------------

    def _add_item(self, which: str) -> None:
        recipe = self._current_recipe()
        if recipe is None:
            messagebox.showinfo("提示", "请先选择一个配方")
            return
        name_var = self.src_name_var if which == "sourceList" else self.prod_name_var
        amount_var = self.src_amount_var if which == "sourceList" else self.prod_amount_var
        name = name_var.get().strip()
        if not name:
            messagebox.showwarning("输入无效", "物品名不能为空")
            return
        try:
            amount = float(amount_var.get())
        except ValueError:
            messagebox.showwarning("输入无效", "数量必须是数字")
            return
        if amount <= 0:
            messagebox.showwarning("输入无效", "数量必须大于 0")
            return
        recipe.setdefault(which, {})[name] = amount
        name_var.set("")
        amount_var.set("")
        self._refresh_item_tables()
        self._refresh_recipe_list()

    def _remove_item(self, which: str) -> None:
        recipe = self._current_recipe()
        if recipe is None:
            return
        tree = self.src_tree if which == "sourceList" else self.prod_tree
        selection = tree.selection()
        if not selection:
            messagebox.showinfo("提示", "请先在表格中选择要删除的物品")
            return
        item = tree.item(selection[0], "values")[0]
        recipe.get(which, {}).pop(item, None)
        self._refresh_item_tables()
        self._refresh_recipe_list()

    def _fill_item_entries(self, tree, name_var, amount_var) -> None:
        selection = tree.selection()
        if not selection:
            return
        item, amount = tree.item(selection[0], "values")
        name_var.set(item)
        amount_var.set(str(amount))

    # ---------------------------------------------------------------
    # 目标操作
    # ---------------------------------------------------------------

    def _read_target_inputs(self) -> Optional[tuple[str, float]]:
        name = self.target_name_var.get().strip()
        if not name:
            messagebox.showwarning("输入无效", "目标产物名不能为空")
            return None
        try:
            rate = float(self.target_rate_var.get())
        except ValueError:
            messagebox.showwarning("输入无效", "目标速率必须是数字")
            return None
        if rate <= 0:
            messagebox.showwarning("输入无效", "目标速率必须大于 0")
            return None
        return name, rate

    def _add_target(self) -> None:
        parsed = self._read_target_inputs()
        if parsed is None:
            return
        name, rate = parsed
        self.model.targets[name] = rate
        self.target_name_var.set("")
        self.target_rate_var.set("")
        self._refresh_targets()

    def _remove_target(self) -> None:
        selection = self.target_tree.selection()
        if not selection:
            messagebox.showinfo("提示", "请先在表格中选择目标产物")
            return
        item = self.target_tree.item(selection[0], "values")[0]
        self.model.targets.pop(item, None)
        self._refresh_targets()

    def _fill_target_entries(self, _event=None) -> None:
        selection = self.target_tree.selection()
        if not selection:
            return
        item, rate = self.target_tree.item(selection[0], "values")
        self.target_name_var.set(item)
        self.target_rate_var.set(str(rate))

    # ---------------------------------------------------------------
    # 求解 / 可视化 / 导入导出
    # ---------------------------------------------------------------

    def _sync_options_from_widgets(self) -> None:
        self._commit_editor()
        self.model.raw_materials = {
            token for token in re.split(r"[,，;；\s]+", self.raw_var.get()) if token
        }
        self.model.objective = LABEL_TO_OBJECTIVE.get(
            self.objective_var.get(), "machine"
        )
        self.model.auto_bootstrap = bool(self.bootstrap_var.get())

    def _on_solve(self) -> bool:
        self._sync_options_from_widgets()
        errors = self.model.validate()
        if errors:
            messagebox.showerror("无法求解", "\n".join(errors))
            return False
        try:
            solution = self.model.solve()
        except Exception as exc:  # noqa: BLE001 - 界面需要展示任意异常
            messagebox.showerror("求解异常", f"{type(exc).__name__}: {exc}")
            return False

        self.model.last_solution = solution
        self._show_report(solution)
        if solution.success:
            self.status_var.set(
                f"求解成功：{len(self.model.recipes)} 个配方，"
                f"{len(solution.external_inputs)} 项外部输入"
            )
        else:
            self.status_var.set(f"求解失败：{solution.message}")
        return True

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
        if solution is None or not solution.success:
            messagebox.showwarning(
                "无法可视化", f"求解未成功：{solution.message if solution else '无结果'}"
            )
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
        self.status_var.set(f"已生成并在浏览器打开：{out_file}")

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
        self._refresh_all(select=0)
        self.status_var.set(f"已导入：{path}")

    def _on_export(self) -> None:
        self._sync_options_from_widgets()
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
        self.status_var.set(f"已导出：{path}")

    def _apply_state_to_widgets(self) -> None:
        self.raw_var.set("，".join(sorted(self.model.raw_materials)))
        self.objective_var.set(
            OBJECTIVE_LABELS.get(self.model.objective, OBJECTIVE_LABELS["machine"])
        )
        self.bootstrap_var.set(self.model.auto_bootstrap)
        self.current_index = None
        self.equ_var.set("")
        self.time_var.set("")
        self._refresh_targets()


def main() -> int:
    app = RecipeBalancerApp()
    app.mainloop()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
