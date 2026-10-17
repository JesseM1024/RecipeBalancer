"""完美循环物质检测（2026-10-17 重定义，替代原 simulate_bootstrap_inventory）。

========================================================
为什么重定义
========================================================

原实现模拟"系统从零库存启动"时每个物品的历史最低点。问题：

- 对**消耗品**（TiO2、Cl2、H2O…）而言，"零启动库存"没有规划意义——
  它们总归需要外部物质流输入，算出来的数只会误导；
- 结果依赖配方遍历顺序，顺序稍变数字就变（作者自评"不太好用"）。

新定位：只回答"哪些物质可以**完美循环**（稳态下完全由系统内部供给），
以及启动它们各需要先投多少料"。

========================================================
判定条件（三者同时满足）
========================================================

1. 处于某个**环 SCC** 内：该物品由环内配方产出、又被环内配方消耗
   （自增殖配方 A -> 2A 视为自环）；
2. 物流仅由**活跃配方**承担（LP 速率 > ``EPS_RATE``）——
   未被选中的配方不参与判定，避免"纸面循环"；
3. **不是外部输入**：稳态下不需要持续外购/外供。

========================================================
启动量语义
========================================================

``单批次投料量`` = 消耗该物品的环内活跃配方中，**单次执行消耗量的最大值**。
即"让第一台机器跑一次，先要放进去多少"。

这是冷启动的**下界/投料口径**：真实在途库存还取决于管道与缓冲布局，
不属于求解器职责（MC 里通常就是"先扔一组进去"）。它不随产线规模缩放，
所以结果与目标速率无关、与配方遍历顺序无关——确定且可解释。
"""

from __future__ import annotations

import logging
from typing import Dict, List, Optional, Set

import networkx as nx

from .models import EPS_RATE

logger = logging.getLogger(__name__)


def detect_perfect_cycles(
    recipe_list: List[dict],
    recipe_rates: Optional[List[float]] = None,
    external_inputs: Optional[Set[str]] = None,
) -> Dict[str, float]:
    """检测可完美循环的物质，返回 ``{物品: 单批次启动量}``。

    Parameters
    ----------
    recipe_list : 配方列表。
    recipe_rates : LP 解出的配方速率；提供时只考虑速率 > ``EPS_RATE`` 的活跃配方。
    external_inputs : 外部输入集合（稳态下持续供给的物品），这些物品不算完美循环。
    """
    external = set(external_inputs or set())

    def is_active(idx: int) -> bool:
        if recipe_rates is None:
            return True
        return recipe_rates[idx] > EPS_RATE

    # ---- 依赖图（仅活跃配方）----
    G = nx.DiGraph()
    for idx, recipe in enumerate(recipe_list):
        if not is_active(idx):
            continue
        for src in recipe["sourceList"]:
            for dst in recipe["productList"]:
                G.add_edge(src, dst, recipe=idx)

    seeds: Dict[str, float] = {}

    for scc in nx.strongly_connected_components(G):
        # ---- 环判定：|SCC| > 1 或 自环 ----
        if len(scc) > 1:
            is_cycle = True
        elif len(scc) == 1:
            node = next(iter(scc))
            is_cycle = G.has_edge(node, node)
        else:
            is_cycle = False
        if not is_cycle:
            continue

        # ---- 环内配方（活跃且触及该 SCC）----
        cycle_recipes: List[int] = []
        for idx, recipe in enumerate(recipe_list):
            if not is_active(idx):
                continue
            involved = set(recipe["sourceList"].keys()) | set(
                recipe["productList"].keys()
            )
            if involved & scc:
                cycle_recipes.append(idx)
        if not cycle_recipes:
            continue

        # ---- 环内既产又耗的物品 ----
        produced: Set[str] = set()
        consumed: Set[str] = set()
        for idx in cycle_recipes:
            recipe = recipe_list[idx]
            for item in recipe["productList"]:
                if item in scc:
                    produced.add(item)
            for item in recipe["sourceList"]:
                if item in scc:
                    consumed.add(item)
        cyclic_items = sorted(produced & consumed)
        if not cyclic_items:
            continue

        # ---- 排除需要持续外部供给的物品 ----
        perfect_items = [item for item in cyclic_items if item not in external]
        if not perfect_items:
            continue

        # ---- 单批次启动量 = 环内配方单次消耗量的最大值 ----
        for item in perfect_items:
            one_batch = max(
                recipe_list[idx]["sourceList"][item]
                for idx in cycle_recipes
                if item in recipe_list[idx]["sourceList"]
            )
            seeds[item] = one_batch

        logger.debug(
            "完美循环 SCC: items=%s, seeds=%s",
            perfect_items,
            {i: seeds[i] for i in perfect_items},
        )

    if seeds:
        logger.info("完美循环物质: %s", sorted(seeds))
    return seeds
