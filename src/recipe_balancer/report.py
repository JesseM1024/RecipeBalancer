"""文本报告（忠实迁移自 notebook cell-1 的 print_solution）。

机器数口径说明（与 notebook 输出一致，见 ANALYSIS.md P1-G）：

- "最小总设备数" = sum_e ceil(该设备类型并行数和)——同设备共享时的最小值；
- "最小单配方总设备数" = sum_r ceil(单配方并行数)——逐配方向上取整的保守值。
"""

from __future__ import annotations

import math
from collections import defaultdict
from typing import Dict, List

from .models import EPS_RATE, RecipeSolution


def format_solution(solution: RecipeSolution, recipe_list: List[dict]) -> str:
    """把解格式化为文本报告（不打印）。"""
    if not solution.success:
        return f"\n求解失败:\n{solution.message}\n"

    lines: List[str] = []

    def out(text: str = "") -> None:
        lines.append(text)

    # ================= 配方运行方案 =================
    out("\n" + "=" * 60)
    out("配方运行方案")
    out("=" * 60)

    machine_detail: Dict[str, list] = defaultdict(list)
    total_machine_count = 0

    for idx, rate in enumerate(solution.recipe_rates):
        if rate < EPS_RATE:
            continue
        recipe = recipe_list[idx]

        machine_parallel = rate * recipe["time"]
        machine_required = math.ceil(machine_parallel)
        total_machine_count += machine_required

        machine_detail[recipe["equType"]].append(
            {
                "recipe_id": idx,
                "parallel": machine_parallel,
                "required": machine_required,
                "recipe": recipe,
            }
        )

        out(f"\n[R{idx}]")
        out(f"设备类型: {recipe['equType']}")
        out(f"设备并行数: {machine_parallel:.3f}")
        out(f"需要设备数: {machine_required}")

        for item, amount in recipe["sourceList"].items():
            out(f"  - {item}: {amount * rate:.3f}")
        for item, amount in recipe["productList"].items():
            out(f"  + {item}: {amount * rate:.3f}")

    # ================= 汇总数据 =================
    out("\n" + "=" * 60)
    out("汇总数据")
    out("=" * 60)

    out("\n>>自动检测外部原料\n")
    if solution.inferred_raw_materials:
        for item in sorted(solution.inferred_raw_materials):
            out(f"  {item}")
    else:
        out("无")

    out("\n>>自增殖配方启动库存\n")
    if solution.inferred_initial_inventory:
        for item, amount in sorted(solution.inferred_initial_inventory.items()):
            out(f"  {item}: {amount:.3f}")
    else:
        out("无")

    out("\n>>大循环配方启动库存\n")
    if solution.bootstrap_inventory:
        for item, amount in sorted(solution.bootstrap_inventory.items()):
            out(f"  {item}: {amount:.3f}")
    else:
        out("无")

    out("\n>>原料输入\n")
    if solution.external_inputs:
        for item, amount in sorted(solution.external_inputs.items()):
            out(f"  {item}: {amount:.3f}")
    else:
        out("无")

    out("\n>>副产物产出\n")
    if solution.final_outputs:
        for item, amount in sorted(solution.final_outputs.items()):
            out(f"  {item}: {amount:.3f}")
    else:
        out("无")

    # ================= 设备统计 =================
    out("\n>>设备统计")
    min_equ_parallel = 0

    for equ in sorted(machine_detail.keys()):
        out(f"\n> {equ}")

        equ_parallel_sum = 0.0
        equ_required_sum = 0

        for entry in machine_detail[equ]:
            recipe_id = entry["recipe_id"]
            parallel = entry["parallel"]
            required = entry["required"]

            equ_parallel_sum += parallel
            equ_required_sum += required

            out(f"\n   [R{recipe_id}]")
            out(f"      实际并行数: {parallel:.3f}")
            out(f"      需要设备数: {required}")

        if len(machine_detail[equ]) > 1:
            out(f"\n   实际并行数和: {equ_parallel_sum:.3f}")
            out(f"\n   最小设备数和: {math.ceil(equ_parallel_sum)}")
            out(f"   单配方设备数和: {equ_required_sum}")
        min_equ_parallel += math.ceil(equ_parallel_sum)

    out(f"\n>>最小总设备数: {min_equ_parallel}")
    out(f">>最小单配方总设备数: {total_machine_count}")

    return "\n".join(lines) + "\n"


def print_solution(solution: RecipeSolution, recipe_list: List[dict]) -> None:
    """打印文本报告（等价于 notebook 的 print_solution）。"""
    print(format_solution(solution, recipe_list), end="")
