"""自动检测器：原料 / 自增殖种子 / 循环亏损（忠实迁移自 notebook cell-1）。

NOTE(P0-B): 检测函数接收全量配方列表（含 disabled 配方），
与 notebook 行为一致——禁用配方仍参与依赖图与原料判定；
修复计划见 ANALYSIS.md §6 P0-B / §12。
"""

from __future__ import annotations

import logging
from typing import Dict, List, Optional, Set

import networkx as nx
import numpy as np
from scipy.optimize import linprog

from .models import EPS_DEFICIT, EPS_RAW

logger = logging.getLogger(__name__)


def auto_detect_raw_materials(A: np.ndarray, all_items: List[str]) -> Set[str]:
    """原料检测：矩阵行内无正项 => 无任何配方可产出 => 原料。"""
    raw: Set[str] = set()
    for i, item in enumerate(all_items):
        if not np.any(A[i] > EPS_RAW):
            raw.add(item)
    return raw


def auto_detect_seed_inventory(recipe_list: List[dict]) -> Dict[str, float]:
    """单配方自催化检测（A -> 2A）。

    物品同时出现在某配方的 sourceList 与 productList 时，
    seed = 该物品在各配方消耗量的最小值。
    NOTE(P2-L): 不区分净增（1→2）与净耗（5→2），忠实保留。
    """
    seed: Dict[str, float] = {}
    for recipe in recipe_list:
        for item in recipe["productList"]:
            if item in recipe["sourceList"]:
                needed = recipe["sourceList"][item]
                if item not in seed:
                    seed[item] = needed
                else:
                    seed[item] = min(seed[item], needed)
    return seed


def detect_deficit_items(
    recipe_list: List[dict],
    allowed_external_inputs: Optional[Set[str]] = None,
    eps: float = EPS_DEFICIT,
) -> Set[str]:
    """检测循环系统中必然亏损的物质（SCC 分解 + 每 SCC 一个子 LP）。

    数学模型（对每个环 SCC）::

        min 1^T d
        s.t. A_sub x + d = 0
             1^T x = 1                # 排除全零平凡解
             x >= 0, d >= 0
             非白名单物品: d_i = 0     # 在 bounds 中直接禁止外部补充

    ``d_i > eps`` => 物品 i 在该环内必然亏损（稳态下守恒律无法满足）。

    ``allowed_external_inputs`` 语义：非空 = 限制（仅这些循环亏损可外部补充）；
    空 / None = 不限制（检测出的全部亏损均放行）。
    """
    allowed_external_inputs = set(allowed_external_inputs or set())

    # 依赖图：src -> dst（多重边由 add_edge 合并，仅用于连通性，无影响）
    G = nx.DiGraph()
    for r_idx, recipe in enumerate(recipe_list):
        for src in recipe["sourceList"]:
            for dst in recipe["productList"]:
                G.add_edge(src, dst, recipe=r_idx)

    sccs = list(nx.strongly_connected_components(G))
    deficits: Set[str] = set()

    for scc in sccs:
        # ---- 环判定：|SCC|>1 或 自环 ----
        if len(scc) > 1:
            is_cycle = True
        elif len(scc) == 1:
            node = next(iter(scc))
            is_cycle = G.has_edge(node, node)
        else:
            is_cycle = False
        if not is_cycle:
            continue

        # ---- 触及该 SCC 的配方 ----
        internal_recipes: List[int] = []
        for r_idx, recipe in enumerate(recipe_list):
            involved = set(recipe["sourceList"].keys()) | set(
                recipe["productList"].keys()
            )
            if involved & scc:
                internal_recipes.append(r_idx)
        if not internal_recipes:
            continue

        # ---- 环内既产又耗的物品 ----
        produced: Set[str] = set()
        consumed: Set[str] = set()
        for r_idx in internal_recipes:
            recipe = recipe_list[r_idx]
            for item in recipe["productList"]:
                if item in scc:
                    produced.add(item)
            for item in recipe["sourceList"]:
                if item in scc:
                    consumed.add(item)
        cyclic_items = sorted(produced & consumed)
        if not cyclic_items:
            continue

        logger.debug(
            "SCC cyclic_items = %s (allowed_external = %s)",
            cyclic_items,
            sorted(allowed_external_inputs) or "<unrestricted>",
        )

        # ---- 子矩阵 A_sub ----
        item_index = {item: idx for idx, item in enumerate(cyclic_items)}
        num_items = len(cyclic_items)
        num_recipes = len(internal_recipes)
        A_sub = np.zeros((num_items, num_recipes))
        for j, r_idx in enumerate(internal_recipes):
            recipe = recipe_list[r_idx]
            for item, amount in recipe["productList"].items():
                if item in item_index:
                    A_sub[item_index[item], j] += amount
            for item, amount in recipe["sourceList"].items():
                if item in item_index:
                    A_sub[item_index[item], j] -= amount

        # ---- 变量 z = [x, d]；目标 min 1^T d ----
        total_vars = num_recipes + num_items
        c = np.concatenate([np.zeros(num_recipes), np.ones(num_items)])

        # A_sub x + d = 0
        A_eq = np.hstack([A_sub, np.eye(num_items)])
        b_eq = np.zeros(num_items)

        # sum(x) = 1
        extra_eq = np.zeros(total_vars)
        extra_eq[:num_recipes] = 1
        A_eq = np.vstack([A_eq, extra_eq])
        b_eq = np.concatenate([b_eq, [1.0]])

        # ---- bounds：非白名单物品 d_i = 0（关键修复，忠实保留）----
        bounds = [(0, None)] * num_recipes
        for item in cyclic_items:
            if allowed_external_inputs and item not in allowed_external_inputs:
                bounds.append((0, 0))
            else:
                bounds.append((0, None))

        result = linprog(c=c, A_eq=A_eq, b_eq=b_eq, bounds=bounds, method="highs")
        if not result.success:
            # 非白名单亏损导致不可行 => 跳过该 SCC（该物品不进外部白名单）
            continue

        d = result.x[num_recipes:]
        for idx, item in enumerate(cyclic_items):
            if d[idx] > eps:
                deficits.add(item)

    if deficits:
        logger.info("循环亏损物质: %s", sorted(deficits))
    return deficits
