"""完美循环物质检测：给出"启动循环所需的最小投料集合"。

========================================================
问题定义
========================================================

LP 解出的是**稳态**速率。稳态下每个中间体的净流量为零，但"零净流量"
不等于"可以启动"：循环网络里，任何一环都需要先有一点物质才能转起来。

对规划来说，有用的不是"把所有环内物质都列一遍"，而是：

    **最少往系统里放进哪些物质、各放多少，整条线就能自持？**

========================================================
判定与求解（纯结构，不看物品名）
========================================================

1. **只考虑活跃配方**（LP 速率 > ``EPS_RATE``），避免"纸面循环"；
2. **外部输入不算循环成员**：它们可以随时从外部取用，本身就是启动源，
   因此直接从依赖图中移除——含外部输入的环不需要种子；
3. 在剩余物品图上做 SCC 分解，对每个环求**最小反馈顶点集**
   （minimum feedback vertex set：删掉最少几个物品后图变为 DAG）。
   这些被删掉的物品就是必须投料的启动项。
   例：C ⇄ CO 互为一对循环 → 只需投 C 或 CO 之一，而不是两个；
4. 启动量 = 按稳态速率跑 1 秒所需的缺口，向上取整到**整批投料**
   （至少 1 批）。批 = 单次配方执行的消耗量。

集内成员按 (数量, 总投料量, 字典序) 取最优，因此会优先挑
"一批用量小"的物质（如 C 2 而不是 CO 1000）。

========================================================
明确不做
========================================================

**不解析物品名称 / 化学式**。GT 的物品名看起来像元素与化合物，
其它整合包可能是幻想物质（名称与化学计量无关）。
所有判断只依赖配方的 sourceList / productList 结构与 LP 流量，
因此对任何命名体系都成立。

同理，本模块不判断"某物质是不是催化剂"这种化学语义——
它只回答"结构上哪些物品构成了循环，最少投哪些才能启动"。
"""

from __future__ import annotations

import logging
import math
from collections import defaultdict
from itertools import combinations
from typing import Dict, Iterable, List, Optional, Sequence, Set, Tuple

import networkx as nx

from .models import EPS_RATE

logger = logging.getLogger(__name__)

#: 精确求最小反馈顶点集的规模上限（超过则用贪心启发式）
MAX_EXACT_FVS = 12

_EPS = 1e-9


def detect_perfect_cycles(
    recipe_list: List[dict],
    recipe_rates: Optional[Sequence[float]] = None,
    external_inputs: Optional[Set[str]] = None,
) -> Dict[str, float]:
    """返回启动各循环所需的最小投料集合 ``{物品: 投料量}``。

    Parameters
    ----------
    recipe_list : 配方列表。
    recipe_rates : LP 解出的配方速率；提供时只考虑速率 > ``EPS_RATE`` 的活跃配方。
    external_inputs : 外部输入集合——视为可随时取用，不参与循环判定。
    """
    external = set(external_inputs or set())
    active = [
        idx
        for idx in range(len(recipe_list))
        if recipe_rates is None or recipe_rates[idx] > EPS_RATE
    ]
    if not active:
        return {}

    seeds = _select_seed_items(recipe_list, active, external)
    if not seeds:
        return {}

    # 启动需求：按稳态速率跑一秒，统计种子物品的缺口
    required, extra = _startup_requirements(recipe_list, active, recipe_rates, seeds, external)
    if extra:
        logger.debug("按比例追加的启动项（非环内但缺量）: %s", sorted(extra))
        seeds |= extra
        required, _ = _startup_requirements(
            recipe_list, active, recipe_rates, seeds, external
        )

    result: Dict[str, float] = {}
    for item in sorted(seeds):
        batch = _one_batch_amount(recipe_list, active, item)
        result[item] = _round_up_to_batch(required.get(item, 0.0), batch)

    logger.info("完美循环启动集合: %s", result)
    return result


# =====================================================================
# 1. 选最小启动集合
# =====================================================================


def _build_item_graph(recipe_list: List[dict], active: Iterable[int]) -> nx.DiGraph:
    """物品依赖图：同一配方内，每个 source -> 每个 product 连边。"""
    G = nx.DiGraph()
    for idx in active:
        recipe = recipe_list[idx]
        for src in recipe["sourceList"]:
            for dst in recipe["productList"]:
                G.add_edge(src, dst)
    return G


def _select_seed_items(
    recipe_list: List[dict],
    active: List[int],
    external: Set[str],
) -> Set[str]:
    """每个环 SCC 求最小反馈顶点集，合并为全局启动集合。"""
    G = _build_item_graph(recipe_list, active)

    # 外部输入：可随时取用，从图中移除（含外部输入的环不需要种子）
    G.remove_nodes_from([n for n in external if n in G])

    seeds: Set[str] = set()
    for scc in nx.strongly_connected_components(G):
        if len(scc) < 2 and not (len(scc) == 1 and G.has_edge(*tuple(scc) * 2)):
            continue  # 非环

        nodes = sorted(scc)
        sub = G.subgraph(nodes)
        edges = list(sub.edges())
        weights = {n: _one_batch_amount(recipe_list, active, n) for n in nodes}

        chosen = _min_feedback_vertex_set(nodes, edges, weights)
        logger.debug("SCC %s -> 种子 %s", nodes, chosen)
        seeds.update(chosen)

    return seeds


def _min_feedback_vertex_set(
    nodes: List[str],
    edges: List[Tuple[str, str]],
    weights: Dict[str, float],
) -> List[str]:
    """删除最少的节点使有向图无环。

    小规模（<= MAX_EXACT_FVS）精确枚举，按 (节点数, 总投料量, 字典序) 取最优；
    大规模退化为贪心（每次删"环参与度"最高的节点），结果仍保证无环。
    """
    nodes = list(nodes)
    edge_set = set(edges)
    adjacency: Dict[str, Set[str]] = {n: set() for n in nodes}
    for u, v in edge_set:
        adjacency[u].add(v)

    def is_acyclic(remaining: Set[str]) -> bool:
        indeg = {n: 0 for n in remaining}
        for u, v in edge_set:
            if u in remaining and v in remaining:
                indeg[v] += 1
        stack = [n for n in remaining if indeg[n] == 0]
        seen = 0
        while stack:
            u = stack.pop()
            seen += 1
            for v in adjacency[u]:
                if v in remaining:
                    indeg[v] -= 1
                    if indeg[v] == 0:
                        stack.append(v)
        return seen == len(remaining)

    n = len(nodes)
    if n <= MAX_EXACT_FVS:
        for k in range(1, n + 1):
            best: Optional[Tuple[int, float, Tuple[str, ...]]] = None
            for comb in combinations(nodes, k):
                if is_acyclic(set(nodes) - set(comb)):
                    cost = (k, round(sum(weights[i] for i in comb), 9), comb)
                    if best is None or cost < best:
                        best = cost
            if best is not None:
                return list(best[2])

    # 贪心回退（超大 SCC）
    remaining = set(nodes)
    removed: List[str] = []
    while not is_acyclic(remaining):
        def score(x: str) -> Tuple[int, float, str]:
            indeg = sum(1 for u, v in edge_set if v == x and u in remaining)
            outdeg = sum(1 for u, v in edge_set if u == x and v in remaining)
            return (indeg * outdeg, weights.get(x, 1.0), x)

        pick = max(remaining, key=score)
        remaining.remove(pick)
        removed.append(pick)
    return removed


# =====================================================================
# 2. 计算启动量
# =====================================================================


def _one_batch_amount(recipe_list: List[dict], active: Iterable[int], item: str) -> float:
    """单批投料量：消耗该物品的活跃配方中，单次执行消耗量的最大值。"""
    amounts = [
        recipe_list[idx]["sourceList"][item]
        for idx in active
        if item in recipe_list[idx]["sourceList"]
    ]
    return max(amounts) if amounts else 1.0


def _startup_requirements(
    recipe_list: List[dict],
    active: List[int],
    recipe_rates: Optional[Sequence[float]],
    seeds: Set[str],
    external: Set[str],
) -> Tuple[Dict[str, float], Set[str]]:
    """按稳态速率跑一秒，统计种子物品的启动缺口。

    依赖图跳过种子物品与外部输入（两者在 t=0 即可用）。
    返回 ``(seed 物品 -> 缺口, 意外需要追加的物品)``。
    """
    rates = {
        idx: (recipe_rates[idx] if recipe_rates is not None else 1.0) for idx in active
    }

    # 配方级依赖：生产者的产物被消费者使用，且该物品不是种子/外部输入
    dep = nx.DiGraph()
    dep.add_nodes_from(active)
    producers: Dict[str, List[int]] = defaultdict(list)
    for idx in active:
        for item in recipe_list[idx]["productList"]:
            producers[item].append(idx)

    for idx in active:
        for item in recipe_list[idx]["sourceList"]:
            if item in seeds or item in external:
                continue
            for producer in producers.get(item, []):
                if producer != idx:
                    dep.add_edge(producer, idx)

    order: List[int]
    if nx.is_directed_acyclic_graph(dep):
        order = list(nx.topological_sort(dep))
    else:  # 理论上不会发生（种子集合已破环）
        logger.debug("配方依赖图仍含环，回退为配方顺序")
        order = sorted(active)

    inventory: Dict[str, float] = defaultdict(float)
    required: Dict[str, float] = defaultdict(float)
    extra: Set[str] = set()

    for idx in order:
        recipe = recipe_list[idx]
        rate = rates[idx]
        if rate <= EPS_RATE:
            continue

        for item, amount in recipe["sourceList"].items():
            if item in external:
                continue  # 外部输入视为无限，不计库存
            need = amount * rate
            if inventory[item] + _EPS < need:
                shortfall = need - inventory[item]
                required[item] += shortfall
                inventory[item] += shortfall
                if item not in seeds:
                    extra.add(item)

        for item, amount in recipe["sourceList"].items():
            if item in external:
                continue
            inventory[item] -= amount * rate

        for item, amount in recipe["productList"].items():
            if item in external:
                continue
            inventory[item] += amount * rate

    return dict(required), extra


def _round_up_to_batch(required: float, batch: float) -> float:
    """把启动缺口向上取整到整批（至少 1 批）。"""
    if batch <= 0:
        batch = 1.0
    if required <= _EPS:
        return batch
    batches = max(1, math.ceil(required / batch - _EPS))
    return batches * batch
