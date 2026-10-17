"""数据模型与数值常量（忠实迁移自 notebook cell-1）。

配方在本版本仍以裸 dict 为运行时格式（保证与 notebook 行为完全一致）::

    {
        "sourceList":  Dict[str, float],   # 原料 -> 单次用量
        "productList": Dict[str, float],   # 产物 -> 单次产量（可为小数 = 概率产量）
        "time":        float,              # 单次耗时（秒），按目标机器实际耗时手工填写
        "equType":     str,                # 设备类型
    }

schema 校验见 :mod:`recipe_balancer.io`。
"""

from __future__ import annotations

from dataclasses import asdict, dataclass
from typing import Any, Dict, List, Set

# ---------------------------------------------------------------------------
# 数值常量（数值与 notebook 中散落的字面量逐一对应；集中化见 ANALYSIS.md P1-H）
# ---------------------------------------------------------------------------

#: 外部输入 big-M 惩罚（P0-C：尺度敏感，转写后优化项）
EXTERNAL_PENALTY: float = 1e6

#: 原料检测：矩阵行内正项阈值
EPS_RAW: float = 1e-12

#: 配方速率视为 0 的阈值
EPS_RATE: float = 1e-10

#: 循环亏损 / bootstrap 模拟阈值
EPS_DEFICIT: float = 1e-9

#: 外部输入 / 净物流阈值
EPS_FLOW: float = 1e-8


@dataclass
class RecipeSolution:
    """``solve_production`` 的返回结果。"""

    recipe_rates: List[float]
    net_flow: Dict[str, float]
    external_inputs: Dict[str, float]
    final_outputs: Dict[str, float]
    equipment_usage: Dict[str, float]
    total_machine_count: int
    inferred_raw_materials: Set[str]
    inferred_initial_inventory: Dict[str, float]
    bootstrap_inventory: Dict[str, float]
    success: bool
    message: str

    def to_dict(self) -> Dict[str, Any]:
        """序列化为可 JSON 化的 dict（set 转 sorted list）。"""
        data = asdict(self)
        data["inferred_raw_materials"] = sorted(self.inferred_raw_materials)
        return data
