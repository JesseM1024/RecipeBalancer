"""物品收集与化学计量矩阵构建（忠实迁移自 notebook cell-1）。"""

from __future__ import annotations

from typing import Dict, List, Tuple

import numpy as np


def collect_all_items(
    recipe_list: List[dict],
    target_products: Dict[str, float],
    initial_inventory: Dict[str, float],
) -> List[str]:
    """收集全部物品名并排序（排序保证矩阵构建的确定性）。"""
    all_items = set()
    for recipe in recipe_list:
        all_items.update(recipe["sourceList"].keys())
        all_items.update(recipe["productList"].keys())
    all_items.update(target_products.keys())
    all_items.update(initial_inventory.keys())
    return sorted(all_items)


def build_matrix(
    recipe_list: List[dict],
    all_items: List[str],
) -> Tuple[np.ndarray, Dict[str, int]]:
    """化学计量矩阵 ``A``：``A[i, j]`` = 配方 j 对物品 i 的（产量 − 消耗量）。"""
    item_index = {item: idx for idx, item in enumerate(all_items)}
    A = np.zeros((len(all_items), len(recipe_list)))
    for j, recipe in enumerate(recipe_list):
        for item, amount in recipe["productList"].items():
            A[item_index[item], j] += amount
        for item, amount in recipe["sourceList"].items():
            A[item_index[item], j] -= amount
    return A, item_index


def build_target_vector(
    all_items: List[str],
    item_index: Dict[str, int],
    target_products: Dict[str, float],
) -> np.ndarray:
    """目标向量 b。"""
    b = np.zeros(len(all_items))
    for item, amount in target_products.items():
        b[item_index[item]] = amount
    return b


def build_inventory_vector(
    all_items: List[str],
    item_index: Dict[str, int],
    inventory: Dict[str, float],
) -> np.ndarray:
    """库存向量 s。"""
    s = np.zeros(len(all_items))
    for item, amount in inventory.items():
        s[item_index[item]] = amount
    return s
