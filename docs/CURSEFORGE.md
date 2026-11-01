# CurseForge 项目文案（Description / Summary）

> 依据：Project Submission Guide（9000199552）+ Moderation Policies（9000197279，2026-10-22 版）。
> 用法：`Summary` 填到提交页 Summary 字段；`Description` 整段贴进 Markdown 编辑器。
> 英文必须在前、中文在后（政策原文：descriptions may have other languages in addition, but must have English translations that appear before other languages）。
> 与 `docs/MODRINTH.md` 的分工：那份是 Modrinth 正文原文；本份是同一内容的 CurseForge 版本，语气统一为学术体、并补上 CurseForge 独有的字段约束。

---

## Summary（一句，提交页字段）

```
A client-side production-line balancer: enter recipes and target rates, solve steady-state recipe rates, machine counts, external inputs and byproducts, cyclic loops included.
```

---

## Description（英文正文，复制这一整段）

**RecipeBalancer — a steady-state balancer for production lines**

RecipeBalancer is a client-side planning instrument for production lines in modded Minecraft. Given a set of recipes and a set of target output rates, it returns the operating rate of every recipe, the number of machines required at each step, the materials that must be supplied from outside the line, and the byproducts that arise. The mod adds no items, blocks or machines; it is an analysis tool and runs entirely on the client.

### Method

A production line is a system of simultaneous material constraints: the byproduct of one process feeds another, a step may consume its own output, and the target rate fixes the scale of every upstream operation. Successive substitution resolves acyclic chains and fails once a chain contains a cycle.

The line is therefore formulated as a constrained optimisation problem: one variable per recipe, denoting its operating rate, and one balance constraint per material, stating that production plus imports equals consumption plus target and byproduct output. Materials declared raw may be imported without bound; all others must be produced within the line. The objective is either the total number of machine parallels, weighted by process duration, or the total recipe activity.

The system is solved as a whole, so cyclic structures require no special treatment:

- a cycle that returns less material than it consumes is identified as deficient, and the external supply it requires is reported;
- a cycle that conserves material admits no steady-state start, and its minimum bootstrap inventory is computed;
- where the optimum is not unique, one optimal solution is reported; machine counts are invariant across the optimal set.

### Input

Recipes are entered through an in-game screen as inputs, outputs, duration and machine. Materials may be selected from the inventory or, where JEI is installed, transferred from its item list or from a recipe page. Duration and machine type are always specified by the user, since neither is reliably recoverable from a recipe. Targets are given as a quantity per unit time.

Work in progress is autosaved to `config/recipebalancer/scenarios/autosave.json` and restored on the next launch.

### Output

Results are presented as a report — operating rate and machine count per recipe, external inputs, byproducts — and as a dependency-ordered flowchart, in which node colour denotes the role of a node and edge width denotes material flow. The flowchart supports panning, zooming and node dragging; its layout minimises edge crossings and node overlap.

### Known limitations

- The model is steady-state; buffers, warm-up and transient behaviour lie outside its scope.
- Recipe data are not read from other mods' registries. JEI is the only automated entry path, and it supplies materials rather than durations.
- Material names are not interpreted; only the structure of a recipe is used.
- Computation time increases with the number of recipes.

### Installation

- Client-side only: no server component, no network traffic, no modification of world state.
- Requires NeoForge on Minecraft 1.21.1. JEI is optional (19.8.4 or later); without it the transfer entry path is unavailable and manual entry remains.
- Place the jar in `mods`; no initial configuration is required.

### Compatibility

JEI accelerates recipe encoding: ghost drag from the item list, transfer from a recipe page, and fluid or chemical ingredients, counted in buckets (500 mB = 0.5). The interface is localised in English and Chinese. Exact loader and Minecraft version per build are listed under Files.

### Scope and disclosure

The mod modifies no game state and performs no network communication; all computation is local. Gallery images, where present, are unmodified in-game captures.

### Links

- Source code and issue tracker: https://github.com/JesseM1024/RecipeBalancer
- Distribution: the Files tab on this page only; no external mirrors.

---

## 中文说明（放在英文之后，可整段保留或删除）

**RecipeBalancer —— 产线稳态配平工具**

RecipeBalancer 是面向模组化 Minecraft 产线的客户端规划工具。给定一组配方与一组目标产出速率，它给出每条配方应有的运行速率、各环节所需的机器数量、必须由产线外部供给的物料，以及目标之外的副产物。模组不新增物品、方块或机器，仅承担分析功能，全部运算在客户端完成。

**方法**：产线是一组联立的物料约束——某一工序的副产物是另一工序的输入，某一步可能消耗自身产出，目标速率决定全部上游工序的规模。无环链条可逐次代入，一旦成环则不再收敛。故将产线表述为约束优化问题：每条配方一个运行速率变量，每种物料一条平衡约束（产出与输入之和等于消耗与目标、副产物输出之和）。声明为原料的物料可无限输入，其余须由产线自产。目标函数为最小化按工序时长加权的机器并行总量，或最小化配方活动总量。整体求解，循环无需特殊处理：回流量小于消耗量的循环判为亏损并给出所需外部供给；物料守恒的循环没有稳态起点，工具计算其最小启动投料；最优解不唯一时只报告其中一个，机器数量在最优解集内不变。

**录入**：配方以原料、产物、时长、设备四项在游戏内界面录入；物料可从背包选取，装有 JEI 时亦可自其物品列表拖入或从配方页导入。时长与设备种类始终由用户填写——二者无法从配方可靠还原。目标以单位时间产量给出。未完成的工作自动保存至 `config/recipebalancer/scenarios/autosave.json`，下次启动恢复。

**输出**：结果以文本报告（各配方速率与机器数、外部输入、副产物）与按依赖关系分层的流程图呈现；节点颜色表示其在产线中的角色，连线宽度表示物料流量。流程图支持平移、缩放与节点拖动，布局以边交叉与节点重叠最少为目标。

**已知限制**：仅适用稳态，不涉及缓冲、预热与瞬态；不读取其它模组的配方库，JEI 是唯一的自动录入通道且只提供物料；不解析物料名称，只使用配方结构；计算耗时随配方数量增长。

**安装**：仅客户端，无服务端组件、无网络通信、不修改世界状态。需要 Minecraft 1.21.1 + NeoForge；JEI 可选（19.8.4 及以上），未安装时仅失去导入通道，手工录入照常。将 jar 放入 `mods` 即可，无需初始配置。

**兼容**：装有 JEI 时可从物品列表拖 ghost、从配方页导入，流体与化学品等非物品按桶计（500 mB = 0.5）；界面提供中文与英文。

---

## 合规自查（对着 9000197279 逐条核）

| 政策条款 | 要求 | 本文的处理 |
|---|---|---|
| Clear and Informative Description | 必须写清加了/改了什么，不能只写 "changes the core game" | 方法、录入、输出、限制、安装逐项具体化，并给出模型边界 |
| Clear Summary | 一句 tldr，避免照抄正文 | Summary 与正文首句措辞不同 |
| English content | 名称必须英文；多语言时英文必须在**前** | 英文正文在前，中文段在后 |
| Name - functional information | 名称不含游戏名 / 版本号 | 标题只用 `RecipeBalancer`，版本信息放 Files |
| Project Avatar | ≥400×400、非纯色、非侵权；**避免 webp**（已知 bug） | `src/main/resources/icon.png`（512×512 PNG）可直接用，不要转 webp |
| Third Party Downloads | 禁止外部下载链接 | 全文无 jar 下载外链，GitHub 只作源码/issue，并明写 "Distribution: the Files tab only" |
| 捐赠 / 社交 / 外站链接 | 必须放页面最底部、尺寸克制 | 只有末尾 Links 段，无捐赠、无横幅 |
| AI Misleading Content | 展示图若为 AI 生成/修饰须显著声明 | "Gallery images, where present, are unmodified in-game captures." |
| Files must include a change log | 每个文件必须写更新说明 | 见下方模板 |
| Mod Loader compatibility | 作者须为每个兼容加载器打标 | 单文件勾 **NeoForge** + 1.21.1，不要勾 Forge |
| Project Variants - Merge | 同核心变体必须一个项目多文件 | 单项目单文件，无变体 |
| Content Copyright | 使用他人素材需授权并署名 | 未打包任何他模组素材；JEI 仅编译期引用 API |
| 沟通渠道 | 建议开放评论或给替代反馈入口 | 开评论 + GitHub issue tracker |

---

## 与 Modrinth 的字段差异（照 `PUBLISH-MODRINTH.md` 走时会踩到）

| 项 | Modrinth | CurseForge |
|---|---|---|
| 客户端 / 服务端标记 | 有 **Environment** 字段，选 Client-side only | **没有该字段**，只能在正文写清"仅客户端" |
| 依赖 | 版本页里选 JEI → Optional | 文件表单里加依赖时同样选 **Optional**，绝不能选 Required |
| 版本通道 | Release / Beta / Alpha | Release type 三选一，同义；0.1.x 阶段选 **Beta** |
| 图标 | 512×512 推荐 | **强制 ≥400×400 且 1:1**，PNG（避免 webp） |
| 加载器 | Loaders 多选 | Mod Loaders 多选；本模组只勾 NeoForge |
| 正文外链 | 审核同样禁止下载外链 | 同左，且**捐赠/社交链接必须置于页面最底部** |

---

## 附录：文件 changelog 模板

```
First CurseForge release for Minecraft 1.21.1 (NeoForge).

- Enter recipes as inputs, outputs, duration and machine; import materials from the inventory or from JEI.
- Solve steady-state operating rates, machine parallels, external inputs and byproducts as one constraint system.
- Cyclic recipes: deficient cycles report their required external supply; conservative cycles report the minimum bootstrap inventory.
- Results as a text report and a dependency-ordered flowchart with pan, zoom and node dragging.
- JEI is optional (19.8.4+): ghost drag, recipe-page import, and fluid or chemical ingredients counted in buckets.
```

```
Fixes and changes since <上一版本>:

- <一句话一条，写玩家能感知到的变化>
- <修了什么现象、在什么条件下>
```
