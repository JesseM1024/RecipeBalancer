"""recipe_balancer —— MC 整合包配方树稳态配平求解器。

忠实迁移自 notebook《配方树自动配平》，架构与算法分析见项目根目录 ANALYSIS.md。
"""

from __future__ import annotations

from .bootstrap import detect_perfect_cycles
from .detection import (
    auto_detect_raw_materials,
    auto_detect_seed_inventory,
    detect_deficit_items,
)
from .matrix import build_matrix, collect_all_items
from .models import RecipeSolution
from .report import format_solution, print_solution
from .solver import build_augmented_lp, solve_production

__version__ = "0.1.0"

__all__ = [
    "RecipeSolution",
    "solve_production",
    "build_augmented_lp",
    "build_matrix",
    "collect_all_items",
    "auto_detect_raw_materials",
    "auto_detect_seed_inventory",
    "detect_deficit_items",
    "detect_perfect_cycles",
    "print_solution",
    "format_solution",
    "draw_production_graph_pyvis",
    "load_scenario",
    "save_solution",
]


def __getattr__(name: str):
    # pyvis 相关惰性导出，避免核心求解依赖 pyvis
    if name == "draw_production_graph_pyvis":
        from .visualize import draw_production_graph_pyvis

        return draw_production_graph_pyvis
    if name in ("load_scenario", "save_solution"):
        from . import io

        return getattr(io, name)
    raise AttributeError(f"module {__name__!r} has no attribute {name!r}")
