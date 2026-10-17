# 《配方树自动配平》Notebook 代码分析

> **源文件**: `C:\Users\JesseTech\Desktop\JesseTech\code\jupyter\minecraft\配方树自动配平.ipynb`
> **用途**: 为转写为工程化 Python 项目（本目录）提供依据
> **日期**: 2026-10-17

---

## 0. 结论先行

| 维度 | 结论 |
|---|---|
| 问题本质 | **单目标线性规划（LP）求稳态配方速率**：给定配方集合与目标产物，求各配方运行速率，使机器总量最小且物流自洽 |
| 核心链路 | 化学计量矩阵 $A$ → 外部输入白名单合成（3 个自动检测器）→ 主 LP（scipy/HiGHS）→ 解析 → 报告 + pyvis 交互图 |
| 亮点算法 | ① **SCC 分解 + 子 LP 检测循环亏损物质**（detect_deficit_items，数学上干净且正确）；② 催化式库存语义（库存加进目标使其不被消耗）；③ big-M 惩罚外部输入实现"尽量自给自足" |
| 主要风险 | **P0-A**：初始库存/自增殖种子在 LP 中**数学上完全抵消**（同时加进 $b$ 和 $s$），对求解结果零影响——当前是无害死逻辑，但转写时必须显式决策语义；**P0-B**：`disabled_recipes` 不被 `detect_deficit_items` / `auto_detect_raw` 感知，禁用配方仍参与依赖图与原料判定；**P0-C**：外部输入惩罚 big-M=1e6 尺度敏感 |
| 代码形态 | 单 cell 函数库（约 1500 行）+ 9 个测试场景 cell；无 schema 校验、调试 print 散布、可视化代码大段重复 |
| 转写策略 | 按 `models / matrix / detection / solver / bootstrap / report / visualize / io` 八模块拆分；**9 个 notebook 场景全部固化为 pytest 金标准**（详见 §9） |

---

## 1. 输入数据契约

### 1.1 配方（recipe dict，无校验的裸字典）

| 键 | 类型 | 语义 | 备注 |
|---|---|---|---|
| `sourceList` | `Dict[str, float]` | 原料 → 单次用量 | 物品名即 ID，拼错会静默变成"原料" |
| `productList` | `Dict[str, float]` | 产物 → 单次产量 | 允许概率产量（如筛子 0.1 钻石） |
| `time` | `float` | 单次耗时（秒） | 机器并行数 = 速率 × time |
| `equType` | `str` | 设备类型 | 设备统计的聚合键 |

### 1.2 顶层输入

| 参数 | 类型 | 语义 |
|---|---|---|
| `target_products` | `Dict[str, float]` | 目标产物速率（个/秒） |
| `raw_materials` | `Set[str]` | 用户声明的无限外部输入 |
| `initial_inventory` | `Dict[str, float]` | 初始库存（当前实现下见 P0-A） |
| `disabled_recipes` | `Set[int]` | 禁用配方（**按列表下标**，脆弱） |
| `allowed_external_cyc_inputs` | `Set[str]` | 循环亏损白名单；**空/None = 完全不限制**（与"白名单"直觉相反） |
| `objective` | `"machine" \| "recipes"` | 目标函数 |
| `auto_detect_raw/seed/bootstrap` | `bool` | 三个自动检测开关 |

---

## 2. 代码块功能清单

### 2.1 cell-1：函数库（核心）

| 函数 | 输入 → 输出 | 职责 | 层次 |
|---|---|---|---|
| `RecipeSolution` (dataclass) | — | 求解结果容器（11 字段） | 模型 |
| `collect_all_items` | recipes+target+inv → `List[str]` | 收集全体物品名（排序保证确定性） | 矩阵 |
| `build_matrix` | recipes+items → `(A, item_index)` | 化学计量矩阵：产出 +、消耗 − | 矩阵 |
| `auto_detect_raw_materials` | `(A, items)` → `Set[str]` | 行内无正项 → 无配方可生产 → 原料 | 检测 |
| `auto_detect_seed_inventory` | recipes → `Dict[str, float]` | 同配方既消耗又产出某物品（A→2A）→ 自增殖种子 = min(消耗量) | 检测 |
| `build_target_vector` / `build_inventory_vector` | → `b`, `s` | 目标 / 库存向量 | 矩阵 |
| `build_augmented_lp` | A,b,s,objective,白名单 → `(A_ub, b_ub, c, external_items)` | 装配主 LP：变量 = [配方速率 x, 外部输入 r] | 求解 |
| `detect_deficit_items` | recipes+白名单 → `Set[str]` | **SCC 分解 + 每 SCC 一个子 LP**，找循环中必然亏损的物质 | 检测 |
| `simulate_bootstrap_inventory` | recipes+rates+target → `Dict[str, float]` | 冷启动不动点模拟，估算循环系统最小启动库存 | 模拟 |
| `solve_production` | 全部参数 → `RecipeSolution` | **主编排**：检测 → 白名单 → LP → 解析 | 求解 |
| `print_solution` | solution+recipes → stdout | 文本报告（配方方案/汇总/设备统计） | 报告 |
| `draw_production_graph_pyvis` | solution+recipes → `.html` | 交互式流程图（物品/配方双节点、力导向、hover） | 可视化 |

### 2.2 测试场景 cell（4/6/8/10/12/14/16/18/20）

见 §9 测试场景清单。

---

## 3. 数学模型与算法逻辑

### 3.1 记号

- $I$：物品集合；$R$：配方集合，$n=|R|$
- $A \in \mathbb{R}^{|I|\times n}$：化学计量矩阵，$A_{ij} = \text{产出}_{ij} - \text{消耗}_{ij}$
- $x \in \mathbb{R}^n_{\ge 0}$：配方运行速率（次/秒）——**决策变量**
- $r \in \mathbb{R}^m_{\ge 0}$：外部输入速率——**决策变量**（仅白名单物品有分量）
- $t_j$：配方耗时；机器并行数 $= x_j t_j$
- $b$：目标向量；$s$：库存向量

### 3.2 主 LP（build_augmented_lp + linprog）

$$
\min_z \; c^\top z, \quad z = [x,\, r] \in \mathbb{R}^{n+m}_{\ge 0}
$$

$$
\text{s.t.} \quad -Ax - Er \le s - b \iff Ax + Er \ge b - s
$$

$$
c = [\underbrace{t_1,\dots,t_n}_{\text{machine}} \text{ 或 } \underbrace{1,\dots,1}_{\text{recipes}},\; \underbrace{10^6,\dots,10^6}_{m}]
$$

要点：

1. **不等式方向**：只约束"净产出 ≥ 需求"，允许过剩 → 过剩即副产物（`final_outputs`）。
2. **big-M 惩罚**：外部输入每个罚 $10^6$，实现"先用生产、外部输入只兜底"的软层级。
3. **目标产物禁入**：白名单显式减去 `target_products` 的键，防止直接"买答案"。
4. **禁用配方**：通过 bounds $(0,0)$ 实现，不改建矩阵。

### 3.3 关键发现：初始库存的完全抵消（P0-A）

`solve_production` 中：

```python
inventory = inferred_seed ∪ user_inventory
effective_target = target + inventory      # 库存加进目标
b = vector(effective_target)
s = vector(inventory)
# LP 约束: Ax + Er ≥ b - s = (target + inv) - inv = target   ← 抵消
# 净物流:  net = Ax + s + Er - b = Ax + Er - target          ← 抵消
```

即**当前实现里 `initial_inventory` 与自增殖种子对 LP 结果零影响**（既不放松约束、也不出现在净物流里），仅存于报告字段 `inferred_initial_inventory`。其隐含语义是"库存是催化剂、不可消耗、稳态下自动守恒"——这在数学上成立（自增殖配方 A→2A 稳态净产出自动覆盖种子），但"用户给了 1000 圆石库存想当免费原料"这种**可消耗语义**当前不支持。转写时必须二选一或做成模式开关（见 §7.2）。

### 3.4 三个自动检测器

| 检测器 | 方法 | 数学判据 |
|---|---|---|
| 原料检测 | 矩阵行扫描 | $\neg\exists j: A_{ij} > 10^{-12}$（无任何配方产出） |
| 自增殖种子 | 逐配方扫描 | $i \in \text{source}_j \cap \text{product}_j$，种子 $= \min_j \text{source}_j[i]$（不区分净增/净耗，见 P2-L） |
| **循环亏损** | networkx SCC + 子 LP | 见下 |

### 3.5 循环亏损检测（detect_deficit_items，算法亮点）

1. 建物品依赖有向图：每个配方对每对 (源→产物) 加边；
2. Tarjan/SCC 分解；只处理**环**（|SCC|>1 或自环）；
3. 收集"触及该 SCC 的配方"与 SCC 内"既产又耗"的循环物品集 $C$；
4. 对每个环解子 LP：

$$
\min_{x,d} \; \mathbf{1}^\top d \quad
\text{s.t.} \quad A_C x + d = 0, \quad \mathbf{1}^\top x = 1, \quad x \ge 0,\; d \ge 0
$$

- $\sum x = 1$ 排除全零平凡解（归一化活动水平）；
- **非白名单物品直接令 $d_i = 0$**（在 bounds 里禁止，而非解后过滤）——这是注释中强调的关键修复，正确；
- $d_i > \varepsilon$ → 物品 $i$ 在该环内必然亏损 → 加入主 LP 的外部输入白名单，让主 LP 决定补充量。

若非白名单物品在环内亏损，子 LP 不可行 → 跳过该 SCC → 该物品进不了白名单 → 主 LP 要么绕开该环、要么不可行（报错信息会体现）。

### 3.6 冷启动模拟（simulate_bootstrap_inventory）

- 语义：LP 给的是**稳态**速率；此模块独立估算"从零库存启动"所需的最小启动库存。
- 方法：**不做时间模拟**，做"依赖展开"式不动点迭代（≤1000 轮）：遍历活跃配方 → 不可执行则按需"赠送"缺口（记录最深负库存 `min_inventory`）→ 执行；直到无变化。
- 性质：**遍历顺序敏感的启发式**，结果非最小、非严格物理意义（作者注释自评"由于随机生成，不太好用"，默认关闭）。

### 3.7 后处理与设备统计

- 净物流：$net = Ax + s + Er - b$，$|net|<10^{-8}$ 视为平衡；正值 → 副产物。
- 设备并行：$usage(e) = \sum_{j \in e} x_j t_j$；**三种口径并存**（见 P1-G）：
  - `total_machine_count`（solve 内）$= \sum_e \lceil usage(e) \rceil$（同设备共享，最小值）
  - print 的"最小单配方总设备数" $= \sum_j \lceil x_j t_j \rceil$（逐配方向上取整，保守值）
  - LP 目标最小化的是 $\sum_j x_j t_j$（未取整的连续值）——是上面两者的下界

---

## 4. 架构与数据流

```
recipe_list, target_products, SolverOptions
        │
        ▼
[矩阵层] collect_all_items ──► build_matrix(A, item_index)          (第1遍)
        │
        ▼
[检测层] auto_detect_raw ─┐
         auto_detect_seed ─┼─► inventory = seed ∪ user_inv
        │                 └─► effective_target = target + inventory
        ▼
[矩阵层] collect_all_items + build_matrix                            (第2遍, 因 seed 可能引入新物品)
        │
        ▼
[检测层] detect_deficit_items   (SCC + 子LP, 不感知 disabled_recipes ← P0-B)
        │
        ▼
allowed_external = user_raw ∪ inferred_raw ∪ deficit − target键
        │
        ▼
[求解层] build_augmented_lp ──► linprog(HiGHS) ──► 成功?
        │                                            │否 → RecipeSolution(success=False, message)
        ▼
[解析层] rates / external_inputs / net_flow / equipment_usage
        │
        ▼ (可选 auto_bootstrap)
[模拟层] simulate_bootstrap_inventory
        │
        ▼
RecipeSolution ──► [报告] print_solution
                └─► [可视化] draw_production_graph_pyvis (.html)
```

依赖栈：`numpy`（矩阵）、`scipy.optimize.linprog`（HiGHS 单纯形/内点）、`networkx`（仅用 SCC）、`pyvis`（vis.js 封装）。

---

## 5. 接口清单

### 5.1 `solve_production(...)` → `RecipeSolution`

见 §1.2 参数表。返回值字段：

| 字段 | 类型 | 语义 |
|---|---|---|
| `recipe_rates` | `List[float]` | 全体配方速率（含 ~0 项，下游用 1e-10 过滤） |
| `net_flow` | `Dict[str, float]` | 非零净物流（+盈/−亏） |
| `external_inputs` | `Dict[str, float]` | 实际启用的外部输入（>1e-8） |
| `final_outputs` | `Dict[str, float]` | 副产物（净物流 > 0 部分） |
| `equipment_usage` | `Dict[str, float]` | 设备类型 → 并行数和 |
| `total_machine_count` | `int` | $\sum_e \lceil usage(e) \rceil$ |
| `inferred_raw_materials` | `Set[str]` | 自动检测的原料 |
| `inferred_initial_inventory` | `Dict[str, float]` | 自动检测的自增殖种子 |
| `bootstrap_inventory` | `Dict[str, float]` | 冷启动库存（可选） |
| `success` / `message` | `bool` / `str` | 求解状态 |

### 5.2 输出函数

- `print_solution(solution, recipe_list)`：文本报告；**内部重复计算机器统计**（P1-G）。
- `draw_production_graph_pyvis(solution, recipe_list, target_products, output_file, min_flow, height, width, show_edge_flow)`：生成 HTML；节点配色语义：绿=外部输入、蓝=中间产物、黄=副产物、红=启动库存、紫=目标产物；配方节点=橙色方块。

---

## 6. 问题清单（转写前必须决策/修复）

### P0 —— 语义级，转写时必须显式决策

| # | 问题 | 影响 | 建议 |
|---|---|---|---|
| A | **初始库存/种子在 LP 中完全抵消**（§3.3） | `initial_inventory` 参数实际无效；"库存当免费原料"语义缺失 | 引入 `inventory_mode: "catalytic" \| "consumable"`；consumable 模式下 $b$ 不加库存、净物流含 $s$ |
| B | **`disabled_recipes` 不被检测层感知**：`detect_deficit_items` 建图、`auto_detect_raw` 扫矩阵、`auto_detect_seed` 扫配方均用全量配方 | 禁用配方仍会造成虚假依赖环/虚假可生产性 | 求解一开始就过滤出 active_recipes，检测层只见 active 集 |
| C | **big-M=1e6 尺度敏感**：目标函数里机器秒总量 ≥1e6（大型工厂完全可能，如 MEK 场景 960s 配方）时外部输入惩罚失效，可能出现"直接外购中间品" | 大规模场景结果错误 | 两阶段字典序 LP：Phase1 min Σr，固定 r*；Phase2 min 机器目标。或按问题尺度归一化惩罚系数 |
| D | **亏损检测的 Σd 无权重混合量纲**（转写实测发现）：子 LP 目标 min Σd 对 Cl2（亏 2×10³）与 Mg（亏 0.6）同权，LP 倾向把亏损安置在"小量纲"物品上。实测 deficit 场景报 `{C, MgCl2, TiCl4}` 而非物理亏损 `{C, Cl2, Mg}`；主 LP 随后选择**外购 MgCl2 1.2、副产 Mg 0.16**，而非直觉的买 Mg 0.12 + Cl2 400 | 物料平衡仍正确，但"亏损判定/外部补充选择"不可解释，规划可读性下降 | 修复三选一：① 行归一化（每行除以该物品特征流量）；② 按用户单价加权 d；③ 改为求解后在实际速率下逐物品检查收支（见 §12 U2） |

### P1 —— 一致性/工程债

| # | 问题 | 建议 |
|---|---|---|
| D | `allowed_external_cyc_inputs` 类型标注 `Set[int]`，实际是 `Set[str]`；且空集=不限制（直觉相反） | 修正类型；语义文档化或改为 `restrict_cyc_inputs: bool + whitelist` |
| E | `detect_deficit_items` 的参数 `A, all_items, target_products` 完全未使用 | 删掉死参数 |
| F | 死 import：`deque`、`scipy.linalg.null_space`（早期代数法的残留） | 删除 |
| G | 机器数三种口径并存且 `print_solution` 重复计算 | `machine_detail` 进 `RecipeSolution`；字段明确命名 `machines_shared_min` / `machines_per_recipe` |
| H | ε 常量散落：1e-12（原料）、1e-10（速率）、1e-9（亏损/bootstrap）、1e-8（外部输入/净物流） | 集中为 `SolverConfig` 中的命名常量，注释各自语义 |
| I | `simulate_bootstrap_inventory` 顺序敏感启发式，作者自评不可靠 | 重写为离散事件模拟（确定性调度策略）或时间展开 LP（见 §7.2） |
| J | 调试 `print` 散布在 `detect_deficit_items` | 换 logging，检测结果进 `Diagnostics` 结构 |
| K | 配方为裸 dict 无校验；`disabled_recipes` 用列表下标，插删配方即错位 | `Recipe` dataclass + pydantic 校验；配方带稳定字符串 ID，禁用按 ID |

### P2 —— 可选改进

| # | 问题 | 建议 |
|---|---|---|
| L | `auto_detect_seed` 对"净耗型"同物品进出（耗5产2）也给出种子=5 | 按 $产出-消耗>0$ 过滤，仅对净增型给种子 |
| M | 两次 `collect_all_items` + `build_matrix`（为 seed 引入的新物品） | 第1遍收集时把全部"同配方进出物品"候选并入 |
| N | 可视化中 source/product 两个分支的建节点代码 ~100 行近乎复制 | 提取 `add_material_node` / `add_flow_edge` helper |
| O | networkx 仅为 SCC，引入重依赖 | 可换 `scipy.sparse.csgraph.connected_components(connection='strong')`；或保留 nx 但注明 |
| P | LP 并列最优无 tie-break，HiGHS 任取顶点 → 同输入可能得到等价但不同的方案 | 第二目标微扰（ε·Σx）或字典序第三阶段 |
| Q | 物品名即 ID，无拼写校验（拼错静默变原料，反而"能跑通"） | 校验：未声明的原料给出 warning 进 Diagnostics |
| R | 数值尺度跨度大（MEK 场景 1e5 vs 0.1），固定 ε=1e-8 对大数量级物品可能误判"平衡" | 净物流判定用相对容差 $\varepsilon \cdot \max(1, |b_i|)$ |

---

## 7. 优化方案

### 7.1 架构层（对应 §8 结构）

1. **八模块拆分**，`solve_production` 保留为唯一门面（facade），内部只做编排；
2. `RecipeSolution` 拆成 `solution`（数值结果）+ `Diagnostics`（检测过程、警告、亏损清单）；
3. 所有可调参数收敛进 `SolverConfig`（objective 枚举、ε 集、auto 开关、inventory_mode）；
4. 报告与可视化只消费 `RecipeSolution`，**不再重算**任何统计量（修 P1-G）。

### 7.2 算法层

1. **两阶段字典序 LP**（修 P0-C）：
   - Phase 1：$\min \mathbf{1}^\top r$ s.t. 原约束 → 得 $r^*$；
   - Phase 2：$\min t^\top x$ s.t. 原约束 + $\mathbf{1}^\top r \le r^*$（+微容差）。
2. **库存语义开关**（修 P0-A）：
   - `catalytic`（现状）：库存只出现在报告里；
   - `consumable`：$b = target$（不加库存），净物流 $= Ax + s + Er - b$（期末剩余库存）。
3. **bootstrap 重写**：
   - 方案 a（推荐）：离散事件模拟——机器为离散资源、配方按确定性优先级调度（如拓扑序+环内轮转），统计各物品历史最低点 → 启动库存 = $\max(0, -\min)$。确定性、可解释；
   - 方案 b（精确）：时间展开 LP——把 $[0,T]$ 分箱，最小化 $\sum \text{seed}_i$；重但给出最优解。
4. **tie-break**（修 P2-P）：Phase 2 后追加 $\min \varepsilon \mathbf{1}^\top x$ 微目标。

### 7.3 数值层

1. ε 集中化 + 相对容差（P1-H / P2-R）；
2. 大规模（几百配方）时 `A` 改 `scipy.sparse`，`linprog` 原生支持稀疏；
3. 主 LP 不可行时给出诊断：对每个物品报告"仅消耗无生产"或"环内非白名单亏损"，而不是只透传 HiGHS 的 message。

### 7.4 工程化层

1. **IO**：配方/目标从 JSON/YAML 加载（schema 校验），结果可 `to_dict()`/JSON 导出；
2. **CLI**（typer）：`solve`（解+文本报告）、`draw`（HTML 图）、`report`（JSON）；
3. **logging** 替换全部 print（P1-J）；
4. **pytest**：9 场景金标准 + 单元测试（§9）；
5. pyproject.toml + 可选依赖分组（`[viz]` = pyvis，`[graph]` = networkx），核心求解只依赖 numpy/scipy。

---

## 8. 建议项目结构

```
配方树自动配平/
├── pyproject.toml            # 依赖: numpy, scipy; 可选组: viz(pyvis), graph(networkx)
├── README.md
├── src/recipe_balancer/
│   ├── __init__.py           # 门面: solve_production 兼容旧签名
│   ├── models.py             # Recipe, SolverConfig, RecipeSolution, Diagnostics   ← P0-A/D/K
│   ├── matrix.py             # collect_all_items, build_matrix, 向量构建           ← P2-M
│   ├── detection.py          # raw / seed / deficit(SCC+子LP), 只见 active 集      ← P0-B/E/F/J/L/Q
│   ├── solver.py             # 两阶段 LP + 解析 + 机器统计                          ← P0-C/P/G/H/R
│   ├── bootstrap.py          # 离散事件冷启动模拟                                   ← P1-I
│   ├── report.py             # 文本报告(纯消费 solution)
│   ├── visualize.py          # pyvis 图(重构节点创建)                               ← P2-N/O
│   ├── io.py                 # JSON/YAML 配方加载与结果导出
│   └── cli.py                # typer 入口
├── examples/                 # 9 个 notebook 场景 → .json
└── tests/
    ├── conftest.py           # 场景 fixtures
    ├── test_scenarios.py     # 金标准(§9)
    └── test_units.py         # 检测器/矩阵/解析 单元测试
```

---

## 9. 测试场景清单（notebook → pytest 金标准）

| # | cell | 场景 | 考察点 | 关键断言 |
|---|---|---|---|---|
| 1 | 4 | 简单链（造石→筛→装配） | 基础链式求解、外部原料 | 铁质机械外壳 64/s 可达；圆石外购量正确 |
| 2 | 6 | 自增殖（锻造模版 1→2） | seed 检测、催化语义 | inferred_seed={锻造模版:1}；净产出守恒 |
| 3 | 8 | 自增殖+多产物+封包产物 | 多目标、目标即循环物质 | 目标三物品同时满足 |
| 4 | 10 | EBF 烧钛闭环 | 完美循环（无亏损） | deficit=∅；external_inputs 仅 TiO2 类原料 |
| 5 | 12 | 魔改电解（Mg 0.7） | **循环亏损检测**（空 whitelist=全放行） | 实测 deficits={C, MgCl2, TiCl4}（量纲效应 P0-D）；外部输入=C 0.2 / MgCl2 1.2 / TiO2 0.2，副产 Mg 0.16 |
| 6 | 14 | 多路循环（TiCl2 路线 + Cl2 白名单） | whitelist 限制语义 | deficits={Cl2}；外部输入=TiO2 0.3 + Cl2 600；Mg/Na/CO/C 环全部平衡 |
| 7 | 16 | 双线配平（HDPE + SST 催化 1→8） | 催化倍增、多产物比 | SST 不出现在外部输入 |
| 8 | 18 | MEK 裂变全链（20 配方） | 规模、大数值跨度(1e5)、耗时长短不一 | 可行解 + 各环节速率自洽（相对容差断言） |
| 9 | 20 | NAST UU 物质（16 配方） | 深链、多级回旋/蒸馏 | 同上 + 副产物集合符合预期 |

另加单元测试：`auto_detect_raw`（空图/含"空"物品）、`auto_detect_seed`（净耗型，P2-L）、`detect_deficit_items`（禁用配方后环消失，P0-B 回归）、`build_augmented_lp`（目标键禁入白名单）、ε 边界。

---

## 10. 转写实施状态（2026-10-17）

忠实转写已完成并入库（本目录，`src/recipe_balancer/`，46 项测试全绿）：

- 行为与 notebook 完全对齐（P0-A/B/C 现状语义均保留，并有测试文档化）；
- 八模块结构落地（models / matrix / detection / solver / bootstrap / report / visualize / io），新增 cli 与场景 JSON 格式；
- 9 个 notebook 场景固化为 pytest 金标准（`tests/conftest.py`，数值表达式逐字转录）；
- 转写实测新发现 P0-D（亏损检测量纲效应），见 §6。

后续优化按 §12 执行。

---

## 11. 与主流工业/模拟求解器的差距评估（2026-10-17 补）

> 背景：配方来源于 MC 整合包，做产线规划。结论：**核心算法（稳态物料衡算 LP）与主流同构，不需要更换**；差距集中在建模面与数据管线，均为增量扩展。

### 11.1 与主流范式对照

| 范式 | 代表 | 关系 |
|---|---|---|
| 流程网络综合（P-graph） | P-graph Studio | 本 LP 经成本最小化隐式完成路线选择；缺"N-best 备选结构"枚举（可选功能） |
| 稳态 flowsheet 模拟 | Aspen Plus、SuperPro | 它们需 tearing + 收敛迭代处理循环流；本方案全局 LP 一次解出，对纯物料网络**更干净** |
| LP/MILP 生产计划（APS） | Gurobi/CPLEX 类 | 本模型 = 其"速率规划层"；上层整数层（整数机器/上限/排产）缺失 |
| 离散事件仿真 | AnyLogic、FlexSim | `bootstrap_inventory` 是雏形；正规化为确定性 DES |
| 游戏同类计算器 | FactorioLab、YAFC（LP，同构）；Helmod（Leontiel 逆+启发式） | Helmod 类遇副产物回路会算错，本 LP 严格正确——已领先 |

### 11.2 MC 产线规划实际差距（按优先级）

| # | 差距 | 处置 | 优先级 |
|---|---|---|---|
| 1 | **机器速度修正**：`time` 非配方固有属性（MEK 速度升级、GT 超频） | **不做变体机制**（用户 2026-10-17 决策）：由配方录入时按目标机器实际耗时**手工填写** `time` —— 数据录入责任，不是求解器责任 | 已关闭 |
| 2 | **能量作为商品**：EU/t、FE/t 预算 | 发电/耗电入矩阵（发电 = 产 EU 的配方）；`models.py` 加 `ENERGY_CARRIER` 约定 | **P1** |
| 3 | **配方数据管线**：手写 dict 不可持续 | EMI/REI 导出、KubeJS/GTCEu dump → JSON → schema 校验；`io.py` 新增导入器 | **P1** |
| 4 | 副产物处置：当前 surplus 免费消失 | 显式 void 配方（销毁塔）；或 surplus 加惩罚成本 | P2 |
| 5 | 设备上限/整数机器：LP 分数解 + 后置 ceil 够规划用；硬上限（"只有 2 台聚变堆"）不支持 | 先做解后校验；真需要再升 MILP（`scipy.optimize.milp`，同走 HiGHS，零新依赖） | P2 |
| 6 | 概率产出方差：稳态期望正确，但循环内概率产出环节实际间歇卡停 | 重写 bootstrap 为 DES 的第二个动机（稳态期望 + 动态验证） | P2 |
| 7 | 库存/副产品优先级（先用库存再外购） | 两阶段 LP（§7.2）顺带覆盖 | P2 |

### 11.3 明确不做的

- 联立方程法（equation-oriented）、夹点分析（pinch）、时间索引排产 MILP——对 MC 产线规划属过度设计；稳态 LP + 可选 DES 冷启动验证即正确规模。
- 变体配方 / 机器等级机制——`time` 由录入时手工填写（见上表 #1）。

---

## 12. 转写后优化清单（按执行顺序）

### U1. bootstrap 重定义为「完美循环物质检测器」（用户 2026-10-17 决策，最高优先）

**动机**：现有 `simulate_bootstrap_inventory` 对全部活跃配方做零库存依赖展开，
消耗品（TiO2、Cl2、H2O…）总归需要外部物流输入，其"零启动库存"没有规划意义，
且结果顺序敏感。目标功能：只检测**可完美循环（守恒）的物质**——
稳态净流量为 0、循环内自产自耗的物质——并给出最小启动种子。

**实现建议**：

1. 复用 `detect_deficit_items` 的 SCC + 子 LP：环内 `d_i ≈ 0` 的物品 = 完美循环物质；
2. 种子上界取该物品在一个循环周期内的在途量（稳态速率 × 相关配方耗时），
   或简化为单批次消耗量（如 1 个锻造模版 / 1 个 SST）；
3. 消耗品（`d_i > 0` 或净流量 ≠ 0）一律不进结果；
4. 输出 `{item: seed}` 并标注所属 SCC，替换现有 `bootstrap_inventory` 字段语义。

### U2. 修复亏损检测的量纲效应（P0-D）

三选一：
1. **行归一化**：子 LP 中每行除以该物品的主 LP 特征流量（或配方中的最大用量），使 d 无量纲；
2. **单价加权**：用户为物品提供权重（如 GT 的 EU 成本），目标 min Σw_i·d_i；
3. **改为事后检查**：跳过子 LP，直接在求得的主 LP 速率下逐物品检查收支，
   盈亏不足且非原料者即为"实际亏损"（最贴近规划语义，但失去"结构性亏损"的先验判断）。

推荐 1（改动最小、可解释），2 作为可选增强。

### U3. 库存语义模式（P0-A）

`inventory_mode: "catalytic" | "consumable"`：
- `catalytic`（现状）：库存只进报告，不进 LP（b、s 抵消）；
- `consumable`：`b = target`（不加库存），净物流 = Ax + s + Er − b（期末剩余库存）。

### U4. 检测层过滤禁用配方（P0-B）

`solve_production` 入口先构造 `active_recipes`（剔除 disabled），
依赖图 / 原料判定 / 亏损检测全部只见 active 集。注意下标映射需保持与原列表一致。

### U5. 两阶段字典序 LP（P0-C）

Phase1：min Σr（外部输入总量），得 r*；Phase2：min 机器目标，约束加 `Σr ≤ r* + τ`。
消除 big-M 的尺度敏感，且给出"外部输入最少"的第一优先级语义。

### U6. 配方数据管线

EMI/REI 导出、KubeJS / GTCEu dump → JSON（`io.validate_recipe_list` 已就位）。
这是从"手写场景"到"整合包全量"的分水岭，优先级高于任何数值优化。

### U7. 能量作为商品

EU/t、FE/t 入矩阵：发电 = 产 EU 配方，耗电 = 消耗 EU 配方；矩阵法天然支持。

### U8. 其余 P2 项

void 配方（副产物强制销毁）、设备上限（`scipy.optimize.milp`，零新依赖）、
并列最优 tie-break、净物流相对容差（P2-R）、可视化布局选项。
