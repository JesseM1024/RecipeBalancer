RecipeBalancer is a client-side planning instrument for production lines in modded Minecraft. Given a set of recipes and a set of desired output rates, it determines the rate at which each recipe must operate, the number of machines required at each step, the materials that must be supplied from outside the line, and the byproducts that arise.

## Problem

A production line is a system of simultaneous material constraints: the byproduct of one process is the input of another, a step may consume its own output, and a target rate fixes the scale of every operation upstream. Resolution by successive substitution is adequate for acyclic chains and fails as soon as a chain contains a cycle.

## Method

The line is formulated as a constrained optimisation problem: one variable per recipe, denoting its operating rate, and one balance constraint per material, stating that production plus imports equals consumption plus target and byproduct output. Materials declared as raw may be imported without bound; all others must be produced within the line. The objective is either the total number of machine parallels, weighted by process duration, or the total recipe activity.

The system is solved as a whole, so cyclic structures require no special treatment. A cycle that returns less material than it consumes is identified as deficient, and the external supply it requires is reported; a cycle that conserves material admits no steady-state start, and its minimum bootstrap inventory is computed. Where the optimum is not unique, one optimal solution is reported; machine counts remain invariant across the optimal set.

## Input and output

Recipes are entered as inputs, outputs, duration, and machine. Materials may be selected from the inventory or, where JEI is installed, transferred from its item list or recipe pages; duration and machine type are always specified by the user, since neither is reliably recoverable from a recipe. Targets are given as a quantity per unit time.

Results are presented as a report — operating rate and machine count per recipe, external inputs, byproducts — and as a dependency-ordered flowchart, in which colour denotes the role of a node and edge width denotes material flow. The flowchart can be exported as a standalone HTML document.

## Limitations

- The model is steady-state; buffers, warm-up, and transient behaviour lie outside its scope.
- Recipe data are not read from other mods' registries. JEI is the only automated entry path, and it supplies materials rather than durations.
- Material names are not interpreted; only the structure of a recipe is used.
- The mod is client-side only: no server component, no network traffic, no modification of world state.
- Computation time increases with the number of recipes.

## Compatibility

| Minecraft | Loader |
|---|---|
| 1.21.1 | NeoForge 21.1.x |

JEI 19.39 or later is optional.

## Installation

Place the jar in the `mods` directory. No dependencies or initial configuration are required.

## License

MIT. Source and issue tracker: https://github.com/JesseM1024/RecipeBalancer

Includes Apache Commons Math 3.6.1 (Apache-2.0).

---

## 中文说明

RecipeBalancer 是面向模组化 Minecraft 产线的客户端规划工具。给定一组配方与一组目标产出速率，它给出每条配方应有的运行速率、各环节所需的机器数量、必须由产线外部供给的物料，以及目标之外的副产物。

产线本质是一组联立的物料约束：某一工序的副产物是另一工序的输入，某一步可能消耗自身产出，而目标速率决定了全部上游工序的规模。对于无环链条，逐次代入尚可手算；一旦出现循环，代入不再收敛。

本工具将产线表述为一个约束优化问题：每条配方对应一个运行速率变量，每种物料对应一条平衡约束——产出与输入之和等于消耗与目标、副产物输出之和。声明为原料的物料可无限输入，其余须由产线自产。目标函数为最小化机器并行总量（按工序时长加权），或最小化配方活动总量。由于整体求解，循环结构无需特殊处理：回流量小于消耗量的循环判为亏损，并给出其所需的外部供给；物料守恒的循环没有稳态起点，工具计算其最小启动投料。最优解不唯一时只报告其中一个，机器数量在最优解集内保持不变。

配方以原料、产物、时长、设备四项录入；物料可从背包选取，装有 JEI 时亦可自其物品列表或配方页导入，时长与设备种类始终由用户填写。目标以单位时间产量给出。结果以文本报告与流程图呈现：后者按依赖关系分层，节点颜色表示其在产线中的角色，连线宽度表示物料流量，可导出为独立 HTML 文件。

限制：仅适用稳态，不涉及缓冲、预热与瞬态；不读取其它模组的配方库，JEI 是唯一的自动录入通道，且只提供物料；不解析物料名称，只使用配方结构；纯客户端运行，无服务端组件、无网络通信、不修改世界状态；计算耗时随配方数量增长。适用于 Minecraft 1.21.1 与 NeoForge 21.1.x，JEI 19.39 及以上为可选。MIT 许可。
