"""主 LP 装配与求解编排（忠实迁移自 notebook cell-1 的 build_augmented_lp / solve_production）。

主 LP 数学模型（变量 z = [配方速率 x, 外部输入 r]）::

    min c^T z
    s.t. -A x - E r <= s - b        (每物品: 净产出 + 外部输入 >= 目标 - 库存)
         x_j = 0 (禁用配方), 其余 >= 0

    c = [time (machine) 或 1 (recipes), 1e6 (外部输入 big-M 惩罚)]

已知语义（忠实保留，修复计划见 ANALYSIS.md）：

- P0-A: 库存同时加进 b 与 s，在约束和净物流中恰好抵消——
  initial_inventory / 自增殖种子对求解结果零影响（"催化剂式"语义）。
- P0-B: detect_deficit_items 使用全量配方列表（含禁用配方）。
- P0-C: 外部输入 big-M = 1e6，尺度敏感。
"""

from __future__ import annotations

import math
from collections import defaultdict
from typing import Dict, List, Optional, Set, Tuple

import numpy as np
from scipy.optimize import linprog

from .bootstrap import detect_perfect_cycles
from .detection import (
    auto_detect_raw_materials,
    auto_detect_seed_inventory,
    detect_deficit_items,
)
from .matrix import (
    build_inventory_vector,
    build_matrix,
    build_target_vector,
    collect_all_items,
)
from .models import (
    EPS_FLOW,
    EPS_RATE,
    EXTERNAL_PENALTY,
    RecipeSolution,
)


def build_augmented_lp(
    A: np.ndarray,
    b: np.ndarray,
    s: np.ndarray,
    recipe_list: List[dict],
    objective: str,
    all_items: List[str],
    allowed_external_items: Set[str],
) -> Tuple[np.ndarray, np.ndarray, np.ndarray, List[str]]:
    """装配 selective-slack LP。

    变量 z = [x (配方速率), r (外部输入)]，r 仅对 allowed_external_items 有分量。
    """
    num_items = len(all_items)
    num_recipes = len(recipe_list)

    external_items = sorted(allowed_external_items)
    external_index = {item: idx for idx, item in enumerate(external_items)}

    # 配方部分
    left = -A

    # 外部输入部分
    right = np.zeros((num_items, len(external_items)))
    for i, item in enumerate(all_items):
        if item in external_index:
            right[i, external_index[item]] = -1

    A_ub = np.hstack([left, right])
    b_ub = s - b

    # 目标函数
    if objective == "machine":
        recipe_cost = np.array([recipe["time"] for recipe in recipe_list])
    elif objective == "recipes":
        recipe_cost = np.ones(len(recipe_list))
    else:
        raise ValueError(f"未知 objective: {objective}")

    # 外部输入高惩罚（P0-C：big-M，转写后优化为两阶段字典序 LP）
    external_cost = np.full(len(external_items), EXTERNAL_PENALTY)

    c = np.concatenate([recipe_cost, external_cost])

    return A_ub, b_ub, c, external_items


def solve_production(
    recipe_list: List[dict],
    target_products: Dict[str, float],
    raw_materials: Optional[Set[str]] = None,
    initial_inventory: Optional[Dict[str, float]] = None,
    disabled_recipes: Optional[Set[int]] = None,
    allowed_external_cyc_inputs: Optional[Set[str]] = None,
    objective: str = "machine",
    auto_detect_raw: bool = True,
    auto_detect_seed: bool = True,
    auto_bootstrap: bool = False,
) -> RecipeSolution:
    """求解稳态配方运行方案。

    Parameters
    ----------
    recipe_list : 配方列表（dict 结构见 :mod:`recipe_balancer.models`），必填。
    target_products : 目标产物 -> 速率（个/秒），必填。
    raw_materials : 用户声明的无限外部输入原料。
    initial_inventory : 初始库存（当前为催化剂式语义，见模块 docstring P0-A）。
    disabled_recipes : 禁用配方的下标集合。
    allowed_external_cyc_inputs : 循环亏损物质白名单；空/None = 不限制。
    objective : "machine"（最小化机器并行总量）或 "recipes"（最小化配方活动总量）。
    auto_detect_raw / auto_detect_seed / auto_bootstrap : 三个自动检测开关。
        ``auto_bootstrap`` 控制"完美循环物质检测"（{物品: 单批次启动量}，
        见 :mod:`recipe_balancer.bootstrap`）。
    """
    user_raw = set(raw_materials or set())
    user_inventory = dict(initial_inventory or {})

    if disabled_recipes is None:
        disabled_recipes = set()

    # ---- 初始物品与矩阵（第 1 遍）----
    all_items = collect_all_items(recipe_list, target_products, user_inventory)
    A, item_index = build_matrix(recipe_list, all_items)

    # ---- 自动检测原料 ----
    inferred_raw: Set[str] = set()
    if auto_detect_raw:
        inferred_raw = auto_detect_raw_materials(A, all_items)

    # ---- 自动检测自增殖种子 ----
    inferred_seed: Dict[str, float] = {}
    if auto_detect_seed:
        inferred_seed = auto_detect_seed_inventory(recipe_list)

    # ---- 库存 = 种子 ∪ 用户库存；目标 += 库存（P0-A：与 s 抵消）----
    inventory = dict(inferred_seed)
    inventory.update(user_inventory)

    effective_target = dict(target_products)
    for item, amount in inventory.items():
        effective_target[item] = effective_target.get(item, 0) + amount

    # ---- 第 2 遍物品收集与矩阵（种子可能引入新物品）----
    all_items = collect_all_items(recipe_list, effective_target, inventory)
    A, item_index = build_matrix(recipe_list, all_items)

    # ---- 循环亏损检测（P0-B：全量配方列表，不感知 disabled）----
    deficit_items = detect_deficit_items(
        recipe_list=recipe_list,
        allowed_external_inputs=allowed_external_cyc_inputs,
    )

    # ---- 外部输入白名单 = 用户原料 ∪ 自动原料 ∪ 循环亏损 − 目标产物 ----
    allowed_external_items = user_raw | inferred_raw | deficit_items
    allowed_external_items -= set(target_products.keys())

    # ---- 向量 ----
    b = build_target_vector(all_items, item_index, effective_target)
    s = build_inventory_vector(all_items, item_index, inventory)

    # ---- LP 装配 ----
    A_ub, b_ub, c, external_items = build_augmented_lp(
        A, b, s, recipe_list, objective, all_items, allowed_external_items
    )

    # ---- bounds：禁用配方固定为 0 ----
    bounds = []
    for idx in range(len(recipe_list)):
        if idx in disabled_recipes:
            bounds.append((0, 0))
        else:
            bounds.append((0, None))
    for _ in external_items:
        bounds.append((0, None))

    # ---- 求解 ----
    result = linprog(c=c, A_ub=A_ub, b_ub=b_ub, bounds=bounds, method="highs")

    if not result.success:
        return RecipeSolution(
            recipe_rates=[],
            net_flow={},
            external_inputs={},
            final_outputs={},
            equipment_usage={},
            total_machine_count=0,
            inferred_raw_materials=inferred_raw,
            inferred_initial_inventory=inferred_seed,
            bootstrap_inventory={},
            success=False,
            message=result.message,
        )

    # ---- 解析 ----
    z = result.x
    num_recipes = len(recipe_list)
    x = z[:num_recipes]
    r = z[num_recipes:]

    # ---- 外部输入 ----
    external_inputs: Dict[str, float] = {}
    for idx, item in enumerate(external_items):
        if r[idx] > EPS_FLOW:
            external_inputs[item] = r[idx]

    # ---- 完美循环物质检测（可选）----
    # 判定只依赖"活跃配方 + 外部输入"，在外部输入确定之后执行。
    bootstrap_inventory: Dict[str, float] = {}
    if auto_bootstrap:
        bootstrap_inventory = detect_perfect_cycles(
            recipe_list=recipe_list,
            recipe_rates=list(x),
            external_inputs=set(external_inputs),
        )

    # ---- 净物流 ----
    external_vec = np.zeros(len(all_items))
    for idx, item in enumerate(external_items):
        external_vec[item_index[item]] = r[idx]

    net = A @ x + s + external_vec - b

    net_flow: Dict[str, float] = {}
    final_outputs: Dict[str, float] = {}
    for item in all_items:
        value = net[item_index[item]]
        if abs(value) < EPS_FLOW:
            continue
        net_flow[item] = value
        if value > 0:
            final_outputs[item] = value

    # ---- 设备统计（同设备类型共享口径：sum_e ceil(usage_e)）----
    equipment_usage: Dict[str, float] = defaultdict(float)
    for idx, rate in enumerate(x):
        if rate < EPS_RATE:
            continue
        recipe = recipe_list[idx]
        equipment_usage[recipe["equType"]] += rate * recipe["time"]

    total_machine_count = sum(math.ceil(v) for v in equipment_usage.values())

    return RecipeSolution(
        recipe_rates=list(x),
        net_flow=net_flow,
        external_inputs=external_inputs,
        final_outputs=final_outputs,
        equipment_usage=dict(equipment_usage),
        total_machine_count=total_machine_count,
        inferred_raw_materials=inferred_raw,
        inferred_initial_inventory=inferred_seed,
        bootstrap_inventory=bootstrap_inventory,
        success=True,
        message="OK",
    )
