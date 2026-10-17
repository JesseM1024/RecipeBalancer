"""pyvis 交互式流程图（迁移自 notebook cell-1 的 draw_production_graph_pyvis）。

相对 notebook 的改动（P2-N）：source/product 两个分支重复的
节点创建逻辑提取为 helper，输出内容不变。pyvis 为惰性导入，
核心求解不依赖它。

节点配色语义（与 notebook 一致）::

    绿色 = 外部输入    蓝色 = 中间产物    黄色 = 副产物
    红色 = 启动库存    紫色 = 目标产物    橙色方块 = 配方
"""

from __future__ import annotations

import math
from typing import Dict, Optional, Set

from .models import RecipeSolution


def _fmt_amount(amt: float) -> str:
    """整数显示为 int，其余用 %g。"""
    if abs(amt - int(amt)) < 1e-9:
        return str(int(amt))
    return f"{amt:g}"


def _recipe_summary_text(recipe: dict) -> str:
    src_text = " + ".join(
        f"{_fmt_amount(amt)}{item}" for item, amt in recipe["sourceList"].items()
    )
    dst_text = " + ".join(
        f"{_fmt_amount(amt)}{item}" for item, amt in recipe["productList"].items()
    )
    return f"{src_text}\n→\n{dst_text}"


def _material_style(
    item: str,
    target_items: Set[str],
    external_inputs: Set[str],
    bootstrap_items: Set[str],
    final_outputs: Set[str],
):
    """返回 (color, size)——优先级：目标 > 外部输入 > 启动库存 > 副产物 > 中间产物。"""
    if item in target_items:
        return "rgba(200,120,255,0.95)", 30
    if item in external_inputs:
        return "rgba(100,255,100,0.85)", 24
    if item in bootstrap_items:
        return "rgba(255,100,100,0.85)", 24
    if item in final_outputs:
        return "rgba(255,215,0,0.90)", 24
    return "rgba(100,180,255,0.80)", 22


def _material_tooltip(
    item: str,
    target_items: Set[str],
    external_inputs: Set[str],
    bootstrap_items: Set[str],
    final_outputs: Set[str],
) -> str:
    tooltip = f"{item}"
    if item in target_items:
        tooltip += "\n\n目标产物"
    if item in external_inputs:
        tooltip += "\n外部输入"
    if item in bootstrap_items:
        tooltip += "\n需要启动库存"
    if item in final_outputs:
        tooltip += "\n最终副产物"
    return tooltip


def _edge_width(total_amount: float) -> float:
    return max(0.8, min(6, math.log10(total_amount + 1) * 1.6))


def draw_production_graph_pyvis(
    solution: RecipeSolution,
    recipe_list: list,
    target_products: Optional[Dict[str, float]] = None,
    output_file: str = "production_graph.html",
    min_flow: float = 1e-8,
    height: str = "900px",
    width: str = "100%",
    show_edge_flow: bool = True,
) -> str:
    """生成交互式 HTML 流程图，返回输出文件路径。"""
    from pyvis.network import Network  # 惰性导入

    net = Network(
        height=height,
        width=width,
        directed=True,
        bgcolor="#ffffff",
        font_color="black",
    )

    net.force_atlas_2based(
        gravity=-50,
        central_gravity=0.01,
        spring_length=220,
        spring_strength=0.02,
        damping=0.4,
    )
    net.toggle_physics(True)

    net.set_options(
        """
    var options = {
      "physics": {
        "enabled": true,
        "solver": "forceAtlas2Based",
        "stabilization": {
          "enabled": true,
          "iterations": 1500,
          "updateInterval": 25
        }
      },
      "interaction": {
        "hover": true,
        "dragNodes": true,
        "dragView": true,
        "zoomView": true
      },
      "edges": {
        "smooth": {
          "type": "dynamic"
        },
        "arrows": {
          "to": {
            "enabled": true
          }
        }
      }
    }
    """
    )

    # ---- 特殊物质集合 ----
    external_inputs = set(solution.external_inputs.keys())
    final_outputs = set(solution.final_outputs.keys())
    bootstrap_items = set(solution.bootstrap_inventory.keys())
    target_items = set((target_products or {}).keys())

    material_nodes: Set[str] = set()

    def add_material_node(item: str) -> None:
        if item in material_nodes:
            return
        material_nodes.add(item)
        color, size = _material_style(
            item, target_items, external_inputs, bootstrap_items, final_outputs
        )
        tooltip = _material_tooltip(
            item, target_items, external_inputs, bootstrap_items, final_outputs
        )
        net.add_node(
            item,
            label=item,
            title=tooltip,
            shape="dot",
            color=color,
            size=size,
            font={"size": 20},
        )

    def add_flow_edge(src: str, dst: str, total_amount: float, flow_desc: str) -> None:
        net.add_edge(
            src,
            dst,
            title=f"{flow_desc}\n\nflow: {total_amount:.6f}",
            color="rgba(120,120,120,0.35)",
            width=_edge_width(total_amount),
            label=f"{total_amount:.3f}" if show_edge_flow else "",
            font={"size": 12, "color": "rgba(60,60,60,0.7)"},
        )

    # ---- 配方节点 ----
    for idx, rate in enumerate(solution.recipe_rates):
        if rate < min_flow:
            continue
        recipe = recipe_list[idx]
        recipe_id = f"R{idx}"

        machine_parallel = rate * recipe["time"]
        machine_required = math.ceil(machine_parallel)

        node_label = f"{recipe_id}\n{recipe['equType']}\nx{machine_required}"
        tooltip = (
            f"{recipe_id}\n\n"
            f"设备类型: {recipe['equType']}\n\n"
            f"Recipe: Time Cost {recipe['time']:.3f}\n"
            f"{_recipe_summary_text(recipe)}\n\n"
            f"Recipe Rate: {rate:.6f}\n"
            f"实际并行数: {machine_parallel:.3f}\n"
            f"需要设备数: {machine_required}"
        )

        net.add_node(
            recipe_id,
            label=node_label,
            title=tooltip,
            shape="box",
            color="rgba(255,180,70,0.90)",
            borderWidth=3,
            size=35,
            font={"size": 18, "face": "arial", "multi": True},
        )

    # ---- 物品节点 + 物流边 ----
    for idx, rate in enumerate(solution.recipe_rates):
        if rate < min_flow:
            continue
        recipe = recipe_list[idx]
        recipe_id = f"R{idx}"

        # 原料 -> 配方
        for item, amount in recipe["sourceList"].items():
            total_amount = amount * rate
            if abs(total_amount) < min_flow:
                continue
            add_material_node(item)
            add_flow_edge(item, recipe_id, total_amount, f"{item} → {recipe_id}")

        # 配方 -> 产物
        for item, amount in recipe["productList"].items():
            total_amount = amount * rate
            if abs(total_amount) < min_flow:
                continue
            add_material_node(item)
            add_flow_edge(recipe_id, item, total_amount, f"{recipe_id} → {item}")

    net.save_graph(output_file)
    print("\n================================================")
    print("pyvis流程图已生成:")
    print(output_file)
    print("================================================\n")
    return output_file
