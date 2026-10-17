"""冷启动库存模拟（忠实迁移自 notebook cell-1 的 simulate_bootstrap_inventory）。

========================================================
转写后优化方向（用户 2026-10-17 决策，见 ANALYSIS.md §12）
========================================================

本模块的定位将重定义为 **"完美循环物质检测器"**：

- 对可完美循环（守恒）的物质——稳态下净流量为 0、循环内自产自耗——
  检测其启动所需的最小种子库存，这是有意义的结果；
- 对循环中必然消耗的物质，"零库存启动需求"没有意义
  （总归要有外部物质流输入），不应出现在结果里。

当前实现忠实保留 notebook 行为（顺序敏感的依赖展开式不动点迭代，
对消耗品也会给出"零启动库存"），重写计划见 ANALYSIS.md §12。
"""

from __future__ import annotations

from collections import defaultdict
from typing import Dict, List

from .models import EPS_DEFICIT


def simulate_bootstrap_inventory(
    recipe_list: List[dict],
    recipe_rates: List[float],
    target_products: Dict[str, float],
    eps: float = EPS_DEFICIT,
) -> Dict[str, float]:
    """模拟系统从零库存启动所需的最小启动库存（不影响 LP）。

    方法：不做严格时间模拟，做"依赖展开"式不动点迭代——
    遍历活跃配方，不可执行则按需补足缺口并记录历史最低库存，
    直到无变化或达最大迭代数。顺序敏感，结果为启发式估计。
    """
    # ---- steady-state 总净物流（目标产出算最终消耗）----
    net_flow = defaultdict(float)
    for idx, rate in enumerate(recipe_rates):
        if rate <= eps:
            continue
        recipe = recipe_list[idx]
        for item, amount in recipe["productList"].items():
            net_flow[item] += amount * rate
        for item, amount in recipe["sourceList"].items():
            net_flow[item] -= amount * rate
    for item, amount in target_products.items():
        net_flow[item] -= amount

    # ---- 活跃配方 ----
    active_recipes = [
        (idx, rate) for idx, rate in enumerate(recipe_rates) if rate > eps
    ]

    # ---- 库存模拟（多轮传播）----
    inventory = defaultdict(float)
    min_inventory = defaultdict(float)

    changed = True
    max_iter = 1000
    iteration = 0

    while changed and iteration < max_iter:
        changed = False
        iteration += 1

        for idx, rate in active_recipes:
            recipe = recipe_list[idx]

            # 是否可执行
            executable = True
            for item, amount in recipe["sourceList"].items():
                needed = amount * rate
                if inventory[item] + eps < needed:
                    executable = False
                    break

            # 不可执行：记录缺口并补足
            if not executable:
                for item, amount in recipe["sourceList"].items():
                    needed = amount * rate
                    deficit = needed - inventory[item]
                    if deficit > 0:
                        min_inventory[item] = min(
                            min_inventory[item], inventory[item] - needed
                        )
                        inventory[item] += deficit
                        changed = True

            # 执行配方
            for item, amount in recipe["sourceList"].items():
                inventory[item] -= amount * rate
                min_inventory[item] = min(
                    min_inventory[item], inventory[item]
                )
            for item, amount in recipe["productList"].items():
                inventory[item] += amount * rate

    # ---- 提取 bootstrap seed：历史最低点的相反数 ----
    bootstrap_inventory: Dict[str, float] = {}
    for item, value in min_inventory.items():
        if value < -eps:
            bootstrap_inventory[item] = -value

    return bootstrap_inventory
