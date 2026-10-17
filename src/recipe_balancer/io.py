"""场景 / 配方 JSON 的加载、校验与结果导出。

场景 JSON 格式::

    {
      "recipes": [
        {
          "sourceList": {"圆石": 1},
          "productList": {"沙砾": 1},
          "time": 2.22,
          "equType": "粉碎"
        }
      ],
      "target_products": {"铁质机械外壳": 64},
      "options": {
        "raw_materials": ["圆石"],
        "initial_inventory": {"锻造模版": 1},
        "disabled_recipes": [0, 2],
        "allowed_external_cyc_inputs": ["Cl2"],
        "objective": "machine",
        "auto_detect_raw": true,
        "auto_detect_seed": true,
        "auto_bootstrap": false
      }
    }

``options`` 全部可省略，键名与 ``solve_production`` 参数一一对应。
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any, Dict, List, Tuple

from .models import RecipeSolution

REQUIRED_RECIPE_KEYS = ("sourceList", "productList", "time", "equType")

_KNOWN_OPTIONS = {
    "raw_materials",
    "initial_inventory",
    "disabled_recipes",
    "allowed_external_cyc_inputs",
    "objective",
    "auto_detect_raw",
    "auto_detect_seed",
    "auto_bootstrap",
}


class RecipeSchemaError(ValueError):
    """配方 / 场景数据不合法。"""


def validate_recipe_list(recipe_list: List[dict]) -> None:
    """校验配方列表结构，不合法时抛出 RecipeSchemaError。"""
    if not isinstance(recipe_list, list) or not recipe_list:
        raise RecipeSchemaError("recipes 必须为非空列表")

    for idx, recipe in enumerate(recipe_list):
        if not isinstance(recipe, dict):
            raise RecipeSchemaError(f"配方 [{idx}] 必须为 dict")
        for key in REQUIRED_RECIPE_KEYS:
            if key not in recipe:
                raise RecipeSchemaError(f"配方 [{idx}] 缺少键: {key}")
        for key in ("sourceList", "productList"):
            if not isinstance(recipe[key], dict) or not recipe[key]:
                raise RecipeSchemaError(
                    f"配方 [{idx}].{key} 必须为非空 dict（物品名 -> 数量）"
                )
            for item, amount in recipe[key].items():
                if not isinstance(item, str) or not item:
                    raise RecipeSchemaError(
                        f"配方 [{idx}].{key} 存在非法物品名: {item!r}"
                    )
                if not isinstance(amount, (int, float)) or amount <= 0:
                    raise RecipeSchemaError(
                        f"配方 [{idx}].{key}[{item}] 数量必须为正数: {amount!r}"
                    )
        if not isinstance(recipe["time"], (int, float)) or recipe["time"] <= 0:
            raise RecipeSchemaError(f"配方 [{idx}].time 必须为正数")
        if not isinstance(recipe["equType"], str) or not recipe["equType"]:
            raise RecipeSchemaError(f"配方 [{idx}].equType 必须为非空字符串")


def parse_options(options: Dict[str, Any]) -> Dict[str, Any]:
    """把 JSON options 转为 solve_production 的 kwargs。"""
    unknown = set(options) - _KNOWN_OPTIONS
    if unknown:
        raise RecipeSchemaError(f"未知 options 键: {sorted(unknown)}")

    kwargs: Dict[str, Any] = {}
    if "raw_materials" in options:
        kwargs["raw_materials"] = set(options["raw_materials"])
    if "initial_inventory" in options:
        kwargs["initial_inventory"] = dict(options["initial_inventory"])
    if "disabled_recipes" in options:
        kwargs["disabled_recipes"] = set(int(i) for i in options["disabled_recipes"])
    if "allowed_external_cyc_inputs" in options:
        kwargs["allowed_external_cyc_inputs"] = set(
            options["allowed_external_cyc_inputs"]
        )
    if "objective" in options:
        kwargs["objective"] = options["objective"]
    for key in ("auto_detect_raw", "auto_detect_seed", "auto_bootstrap"):
        if key in options:
            kwargs[key] = bool(options[key])
    return kwargs


def load_scenario(path: str | Path) -> Tuple[List[dict], Dict[str, float], Dict[str, Any]]:
    """加载场景 JSON，返回 (recipe_list, target_products, solve_kwargs)。"""
    with open(path, "r", encoding="utf-8") as f:
        data = json.load(f)

    for key in ("recipes", "target_products"):
        if key not in data:
            raise RecipeSchemaError(f"场景缺少必需键: {key}")

    recipe_list = data["recipes"]
    target_products = dict(data["target_products"])
    if not target_products:
        raise RecipeSchemaError("target_products 不能为空")

    validate_recipe_list(recipe_list)
    kwargs = parse_options(data.get("options", {}))
    return recipe_list, target_products, kwargs


def save_solution(solution: RecipeSolution, path: str | Path) -> None:
    """把解导出为 JSON。"""
    with open(path, "w", encoding="utf-8") as f:
        json.dump(solution.to_dict(), f, ensure_ascii=False, indent=2)


def _jsonable(value: Any) -> Any:
    """把 set / 嵌套结构转为可 JSON 化的形式。"""
    if isinstance(value, (set, frozenset)):
        return sorted(value)
    if isinstance(value, dict):
        return {k: _jsonable(v) for k, v in value.items()}
    if isinstance(value, (list, tuple)):
        return [_jsonable(v) for v in value]
    return value


def save_scenario(
    path: str | Path,
    recipe_list: List[dict],
    target_products: Dict[str, float],
    options: Dict[str, Any] | None = None,
) -> None:
    """把配方 / 目标 / 选项保存为场景 JSON（格式与 :func:`load_scenario` 对应）。

    不做结构校验；需要校验时先调用 :func:`validate_recipe_list`。
    """
    data = {
        "recipes": recipe_list,
        "target_products": target_products,
        "options": _jsonable(options or {}),
    }
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)
