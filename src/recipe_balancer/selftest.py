"""打包自检：在冻结环境里实跑一遍「求解 → 文本报告 → pyvis 可视化」。

为什么需要它：PyInstaller 的 ``--windowed`` 进程没有 stdout，而 scipy 的
二进制依赖、pyvis 的 HTML 模板很容易漏打——漏了只会在用户点按钮时才炸。
``RecipeBalancer.exe --selftest`` 会把结果写进程序目录的 ``selftest.log``
并弹窗告知，方便打包后（或用户报障时）一条命令定位问题。

退出码：0 = 全部通过，1 = 有失败项。
"""

from __future__ import annotations

import sys
import time
import traceback
from pathlib import Path
from typing import List

from .config import app_dir

#: 自检用的最小场景（含一次「原料 → 中间品 → 产物」的两级链）
RECIPES = [
    {
        "sourceList": {"原矿": 1},
        "productList": {"金属锭": 1},
        "time": 1.0,
        "equType": "熔炉",
    },
    {
        "sourceList": {"金属锭": 1},
        "productList": {"齿轮": 1},
        "time": 2.0,
        "equType": "装配机",
    },
]
TARGETS = {"齿轮": 2.0}
RAW_MATERIALS = {"原矿"}


def _run_checks(lines: List[str]) -> bool:
    from .report import format_solution
    from .solver import solve_production

    solution = solve_production(
        RECIPES, dict(TARGETS), raw_materials=set(RAW_MATERIALS)
    )
    if not solution.success:
        lines.append(f"求解: 失败（{solution.message}）")
        return False
    lines.append("求解: 通过")

    report = format_solution(solution, RECIPES)
    if "配方运行方案" not in report:
        lines.append("文本报告: 失败（报告内容异常）")
        return False
    lines.append(f"文本报告: 通过（{len(report)} 字符）")

    from .visualize import draw_production_graph_pyvis

    html_path = app_dir() / "selftest_graph.html"
    draw_production_graph_pyvis(
        solution,
        RECIPES,
        target_products=dict(TARGETS),
        output_file=str(html_path),
        cdn_resources="in_line",
    )
    html = html_path.read_text(encoding="utf-8")
    if "vis-network" not in html:
        lines.append("可视化: 失败（HTML 里没有内联的 vis-network）")
        return False
    lines.append(f"可视化: 通过（{len(html)} 字符 → {html_path.name}）")
    return True


def run_selftest(notify: bool = True) -> int:
    """执行自检，日志写入程序目录；返回进程退出码。

    ``notify=False`` 只写日志、不弹窗（自动化/CI 用 ``--selftest --quiet``）。
    """
    base = app_dir()
    from . import __version__

    lines: List[str] = [
        f"版本: {__version__}",
        f"时间: {time.strftime('%Y-%m-%d %H:%M:%S')}",
        f"Python: {sys.version.split()[0]}",
        f"打包运行: {bool(getattr(sys, 'frozen', False))}",
        f"程序目录: {base}",
        "",
    ]
    try:
        ok = _run_checks(lines)
    except Exception:  # noqa: BLE001 - 自检要把任何异常都记录下来
        ok = False
        lines.append("异常:")
        lines.append(traceback.format_exc())

    lines.append("")
    lines.append("结论: " + ("全部通过" if ok else "存在失败项"))
    text = "\n".join(lines)

    log_path = base / "selftest.log"
    try:
        log_path.write_text(text, encoding="utf-8")
    except OSError as exc:
        text += f"\n\n（日志写入失败: {exc}）"

    _notify(ok, text, log_path) if notify else print(text)
    return 0 if ok else 1


def _notify(ok: bool, text: str, log_path: Path) -> None:
    """有图形环境就弹窗；没有则退回 stdout（源码运行时看得到）。"""
    title = "打包自检：通过" if ok else "打包自检：失败"
    body = text if len(text) < 1200 else text[-1200:]
    body += f"\n\n日志: {log_path}"
    try:
        import tkinter as tk
        from tkinter import messagebox

        root = tk.Tk()
        root.withdraw()
        if ok:
            messagebox.showinfo(title, body)
        else:
            messagebox.showerror(title, body)
        root.destroy()
    except Exception:  # noqa: BLE001 - 无显示环境
        print(body)
