"""命令行入口。

用法::

    recipe-balancer solve <scenario.json> [选项]

选项::

    --draw <out.html>   生成 pyvis 交互流程图（需要 pyvis）
    --json <out.json>   把解导出为 JSON
    --objective {machine,recipes}
    --raw A,B,C         追加外部原料（覆盖场景 options）
    --disable 0,2       追加禁用配方下标
    --quiet             不打印文本报告
"""

from __future__ import annotations

import argparse
import sys
from typing import List, Optional

from .io import load_scenario, save_solution
from .report import print_solution
from .solver import solve_production


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="recipe-balancer",
        description="MC 整合包配方树稳态配平求解器（LP）",
    )
    sub = parser.add_subparsers(dest="command", required=True)

    p_solve = sub.add_parser("solve", help="求解一个场景 JSON")
    p_solve.add_argument("scenario", help="场景 JSON 路径")
    p_solve.add_argument("--draw", metavar="FILE", default=None, help="输出 pyvis HTML 图")
    p_solve.add_argument("--json", metavar="FILE", default=None, help="输出解 JSON")
    p_solve.add_argument(
        "--objective", choices=("machine", "recipes"), default=None, help="目标函数"
    )
    p_solve.add_argument("--raw", default=None, help="逗号分隔的外部原料，覆盖 options")
    p_solve.add_argument("--disable", default=None, help="逗号分隔的禁用配方下标")
    p_solve.add_argument("--quiet", action="store_true", help="不打印文本报告")

    sub.add_parser("gui", help="打开图形界面（需要 tkinter）")

    return parser


def main(argv: Optional[List[str]] = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)

    if args.command == "gui":
        from .gui import main as gui_main

        return gui_main()

    if args.command == "solve":
        recipe_list, target_products, kwargs = load_scenario(args.scenario)

        if args.objective is not None:
            kwargs["objective"] = args.objective
        if args.raw is not None:
            kwargs["raw_materials"] = {
                s.strip() for s in args.raw.split(",") if s.strip()
            }
        if args.disable is not None:
            kwargs["disabled_recipes"] = {
                int(s) for s in args.disable.split(",") if s.strip()
            }

        solution = solve_production(recipe_list, target_products, **kwargs)

        if not args.quiet:
            print_solution(solution, recipe_list)

        if args.draw:
            from .visualize import draw_production_graph_pyvis

            draw_production_graph_pyvis(
                solution,
                recipe_list,
                target_products=target_products,
                output_file=args.draw,
            )

        if args.json:
            save_solution(solution, args.json)
            print(f"解已导出: {args.json}")

        return 0 if solution.success else 1

    parser.error(f"未知命令: {args.command}")
    return 2


if __name__ == "__main__":
    sys.exit(main())
