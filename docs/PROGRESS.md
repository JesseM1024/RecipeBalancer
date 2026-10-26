# RecipeBalancer · 逐轮进度记录

> 记录规则：每轮先记录实测结果（环境/构建/断言），改动在结果之后描述，便于回滚到"已知可用"。
> 历史轮次过多时按惯例归纳进 `PROGRESS-HISTORY.md`。

---

## R38 · 2026-10-26 · 节点间距/箭头长度纳入配置

### 实测结果

| 项 | 结果 |
|---|---|
| `gradlew test` | **82 tests, 0 failures** |
| 产物 | `recipebalancer-0.1.0-1.21.1.jar` **2,371,711 B** |
| 实机部署 | 已复制到 `1.21.1-NeoForge/mods/`（23:11:12） |
| 未验证 | 实机配置效果 |

### 新增配置

`config/recipebalancer/graph-animation.properties` 新增：

```properties
nodeGap=38
edgeLength=84
```

- `nodeGap`：节点最小间距的附加量；
- `edgeLength`：边/箭头弹簧的自然长度；
- 创建流程图时读取，重新打开结果页生效；
- 原有动画三项继续按 1 秒热读取。

### 拖动时是否计算交叉

- **不计算**。
- 拖动阶段只跑空间哈希邻域内的：
  - 节点斥力；
  - 相连边弹簧；
  - 碰撞推开；
- 松手后 10 帧 `settleStep` 也只做局部弹簧/斥力/碰撞；
- 边交叉、边穿节点只出现在**初始布局**的 `minimizeCrossings/localCost` 中。

原因：拖动时每帧做全局交叉扫描是 O(E²)，会破坏交互帧率；
交叉优化只在打开流程图时做一次。

---

## R37 · 2026-10-26 · 提高箭头交叉/重叠惩罚

### 实测结果

| 项 | 结果 |
|---|---|
| `gradlew test` | **82 tests, 0 failures** |
| 产物 | `recipebalancer-0.1.0-1.21.1.jar` **2,371,337 B** |
| 实机部署 | 已复制到 `1.21.1-NeoForge/mods/`（23:05:54） |
| 未验证 | 实机箭头分离效果 |

### 代价函数调整

| 项 | 旧 | 新 |
|---|---:|---:|
| 边交叉 | 800 | **6000** |
| 边穿过节点 | 350 | **1500** |
| 近平行/重叠箭头 | 未惩罚 | 距离 < 14 时按 `(14-gap) × 180` 惩罚 |
| 局部搜索轮数 | 4 | **6**（大图 3） |

- 交叉惩罚提高 7.5 倍；
- 新增 segment-to-segment 距离：
  - 两条边不交叉但贴得很近/平行重叠时也会被推开；
  - 共享端点的边不参与该惩罚，避免破坏正常汇聚。
- 每轮仍做碰撞松弛，防止优化后节点重叠。

---

## R36 · 2026-10-26 · 流程图改为圆盘填充

### 实测结果

| 项 | 结果 |
|---|---|
| `gradlew test` | **82 tests, 0 failures** |
| 产物 | `recipebalancer-0.1.0-1.21.1.jar` **2,371,135 B** |
| 实机部署 | 已复制到 `1.21.1-NeoForge/mods/`（23:03:03） |
| 未验证 | 实机圆盘填充效果 |

### 圆环的根因

不是节点数或斥力问题，而是布局里有**三个圆环目标**：

1. 初始位置 `r = targetRadius * (0.65~1.0)`，天然避开中心；
2. 力导向中的 `radial = (dist - targetRadius) * 0.09`，把节点拉回圆环；
3. 局部交叉搜索的代价 `(dist - targetRadius)^2 * 0.002`，再次强化圆环。

### 修复

- 初始位置改为**均匀圆盘采样**：
  `angle = 2πu`，`r = R * sqrt(v)`，中心也会出现节点；
- 向心项改为较弱但足够填充的线性向心；
- 径向力只在 `dist > R` 时施加，内部不再有“贴近半径”的目标；
- 局部搜索代价改为：
  - 弱向心 `dist² * 0.0012`；
  - 只有超出圆盘时才额外惩罚；
- 碰撞松弛继续负责把内部节点均匀推开，形成圆饼而不是空心环。

---

## R35 · 2026-10-26 · 启动动画热配置 + 中英文语言文件

### 实测结果

| 项 | 结果 |
|---|---|
| `gradlew test` | **82 tests, 0 failures** |
| 产物 | `recipebalancer-0.1.0-1.21.1.jar` **2,371,115 B** |
| 实机部署 | 已复制到 `1.21.1-NeoForge/mods/`（22:54:14） |
| 未验证 | 实机热配置/英文界面 |

### 热配置

文件：

`config/recipebalancer/graph-animation.properties`

```properties
stepsPerSecond=100
durationSeconds=2.0
stepDistance=6.0
```

- 启动时读取；
- 动画期间每 1 秒检查一次文件修改时间；
- `stepsPerSecond`、`stepDistance` 可在动画中生效；
- `durationSeconds` 在下一次打开流程图时生效；
- 数值自动限制范围并回退默认值，无需重启。

### 中英文语言文件

- `assets/recipebalancer/lang/en_us.json`
- `assets/recipebalancer/lang/zh_cn.json`

覆盖：

- 主编码界面：按钮、标签、状态栏、tooltip；
- 用量/耗时/配方集/求解结果页；
- 流程图：图例、tooltip、提示；
- 文本报告的静态段落和设备统计字段；
- JEI 按钮与物品 tooltip。

实现：`mc/i18n/Lang`，以中文源文本为 key；
中文环境直接使用源文本，英文环境查 `en_us.json`。
物品名/自定义名称仍由 Minecraft 自身语言系统处理。

---

## R34 · 2026-10-26 · 启动动画步长提升

| 项 | 结果 |
|---|---|
| `gradlew test` | **82 tests, 0 failures** |
| 产物 | `recipebalancer-0.1.0-1.21.1.jar` **2,359,104 B** |
| 实机部署 | 已复制到 `1.21.1-NeoForge/mods/`（22:46:31） |

- 启动锚点动画的单步最大位移 **2px → 6px**（3 倍）；
- 步频保持 **100 步/s × 2s**；
- 只在启动动画使用大步长；手动拖动/松手回弹仍为 2px。

---

## R33 · 2026-10-26 · 启动动画提速

| 项 | 结果 |
|---|---|
| `gradlew test` | **82 tests, 0 failures** |
| 产物 | `recipebalancer-0.1.0-1.21.1.jar` **2,359,028 B** |
| 实机部署 | 已复制到 `1.21.1-NeoForge/mods/`（22:38:43） |

- 启动锚点求解从 **60 步/s × 1.5s** 改为 **100 步/s × 2s**；
- 单帧最多追赶步数从 4 提到 6，避免低帧率时累积丢失；
- 总收缩步数约从 90 提到 200，收缩更明显。

---

## R32 · 2026-10-26 · 启动原地拖动动画 + LOD 回调

### 实测结果

| 项 | 结果 |
|---|---|
| `gradlew test` | **82 tests, 0 failures** |
| 产物 | `recipebalancer-0.1.0-1.21.1.jar` **2,359,029 B** |
| 实机部署 | 已复制到 `1.21.1-NeoForge/mods/`（22:35:24） |
| 未验证 | 实机动画时长/收缩幅度 |

### 启动动画

不再在构造时一次性压缩，也不改边自然长度（恢复为 84）。

打开流程图后：

- 选取一个**度数最高、且靠近中心**的节点作为“按住点”；
- 该节点固定在原位置，不跟随鼠标；
- 按固定 **60 步/秒**运行 1.5 秒：
  - 局部邻居斥力/弹簧；
  - 全局边弹簧继续使图收缩；
  - 每 8 步碰撞松弛一次，并恢复锚点位置；
- 动画结束后停止求解。

相机在动画期间**不重新 fit**，所以能直接看到图逐渐收缩；
收缩后图占屏幕更小，FPS 也更高。

### LOD 回调

| 元素 | 阈值 |
|---|---:|
| 边标签 | `zoom < 0.33` 隐藏 |
| 节点标签 | `zoom < 0.26` 隐藏 |
| 物品图标 | `zoom < 0.22` 隐藏 |

比上一版（0.24 标签 / 0.16 图标）更容易在缩小时隐藏。

---

## R31 · 2026-10-26 · 启动自动收缩加强 + LOD 阈值放低

### 实测结果

| 项 | 结果 |
|---|---|
| `gradlew test` | **82 tests, 0 failures** |
| 产物 | `recipebalancer-0.1.0-1.21.1.jar` **2,357,940 B** |
| 实机部署 | 已复制到 `1.21.1-NeoForge/mods/`（22:29:57） |
| 未验证 | 实机收缩幅度/LOD |

### 启动收缩加强

之前只跑 8 轮 `settleStep`，每轮位移上限 2px，最多约 16px，视觉上几乎没收缩。

现在：

- 自然边长度 `84 → 68`；
- 自动执行 **90 轮**局部弹簧 + 斥力（大图 50 轮）；
- 每 8 轮才做一次全量碰撞松弛，降低启动耗时；
- 最后再做一次碰撞松弛保证最小间距。

效果应接近“手动拖动后松手”的收缩图。

### LOD 阈值放低

| 元素 | 旧阈值 | 新阈值 |
|---|---:|---:|
| 节点/边标签 | `zoom < 0.42` 隐藏 | `zoom < 0.24` 隐藏 |
| 物品图标 | `zoom < 0.30` 隐藏 | `zoom < 0.16` 隐藏 |

只有缩到非常小才省略标签/图标，正常查看和轻微缩放时保持完整。

---

## R30 · 2026-10-26 · 箭头恢复 + 启动收缩 + 视口裁剪

### 实测结果

| 项 | 结果 |
|---|---|
| `gradlew test` | **82 tests, 0 failures** |
| 产物 | `recipebalancer-0.1.0-1.21.1.jar` **2,357,793 B** |
| 实机部署 | 已复制到 `1.21.1-NeoForge/mods/`（22:23:13） |
| 未验证 | 实机箭头/FPS |

### 箭头不可见的原因

`RenderType.gui()` 默认开启背面剔除（`CULL`）。
合批四边形的绕序与 `GuiGraphics.fill` 相反，被整体剔除，所以只剩标签。

修复：

- 顶点顺序改为与 `GuiGraphics.fill` 一致：
  `p1(top-left) → p2(bottom-left) → p3(bottom-right) → p4(top-right)`；
- 边、箭头共用同一个合批缓冲，修复后应同时恢复。

### 启动收缩

打开流程图时在布局末尾自动执行：

- 8 轮 `settleStep`：边弹簧 + 局部斥力，把图向自然长度收缩；
- 最后 28 轮碰撞松弛，保证最小间距；
- 等价于“原地拖动节点后松手”，但不需要用户操作。

### 进一步渲染优化

- **视口裁剪**：
  - 计算当前相机可见世界范围；
  - 完全在视口外的节点不绘制；
  - 边按包围盒裁剪，标签同理。
- **LOD**：
  - `zoom < 0.42` 时不画节点/边标签；
  - `zoom < 0.30` 时不画图标，改用首字，减少物品渲染。
- 缩小/放大或平移后，只绘制屏幕内内容。

---

## R29 · 2026-10-26 · 节点最小间距增大 + 边渲染合批

### 实测结果

| 项 | 结果 |
|---|---|
| `gradlew test` | **82 tests, 0 failures** |
| 产物 | `recipebalancer-0.1.0-1.21.1.jar` **2,357,154 B** |
| 实机部署 | 已复制到 `1.21.1-NeoForge/mods/`（22:17:42） |
| 未验证 | 实机 FPS/间距手感 |

### 最小距离

- `NODE_GAP` 从 **24 → 38**：
  - 配方-物质：`20 + 11 + 38 = 69`
  - 配方-配方：`20 + 20 + 38 = 78`
  - 物质-物质：`11 + 11 + 38 = 60`
- 边弹簧自然长度 **72 → 84**；
- 碰撞松弛轮数 **12 → 28**，布局结束后再强制分离；
- 圆环基础半径提高，节点多时自然向外扩，不再互相挤。

### 渲染性能：边合批

之前每条边用 `GuiGraphics.fill` 逐像素画线，箭头再画两遍；
一条 300px 边约 300 次 fill，几十条边就是上万次 GUI 绘制调用。

现在：

- 所有边与箭头统一写入一个 `RenderType.gui()` 的 `VertexConsumer`；
- 每条边由 1 个四边形（thickness 宽）表示，箭头 2 个四边形；
- 所有边一次 `graphics.flush()`，再单独画标签；
- 绘制调用从“每像素一次”降为“每条边常数次 + 一次合批提交”。

### 其它性能措施

- 拖拽/回弹仍只走空间哈希局部邻域；
- 静止挂机时不跑弹性迭代；
- 标签、节点仍逐帧绘制，但数量级远小于旧的逐像素线。

---

## R28 · 2026-10-26 · 流程图节点拖动 + 局部弹性模拟

### 实测结果

| 项 | 结果 |
|---|---|
| `gradlew test` | **82 tests, 0 failures** |
| 产物 | `recipebalancer-0.1.0-1.21.1.jar` **2,356,400 B** |
| 实机部署 | 已复制到 `1.21.1-NeoForge/mods/`（22:06:27） |
| 未验证 | 实机拖动/回弹手感 |

### 交互

- 左键命中**节点** → 拖动节点，显示蓝色外框；
- 左键命中空白 → 平移画布；
- 松开后局部回弹 10 帧；
- 拖动节点期间暂停 tooltip，避免遮挡。

### 性能友好的局部弹性

- **空间哈希网格**（96px 单元格）：
  - 拖动时只查询鼠标节点附近 5×5 网格；
  - 不跑全图 O(n²)。
- 每帧局部力：
  - 邻居斥力：低于最小距离 1.3 倍时推开；
  - 相连边弹簧：自然长度 72，拉近/推开相邻节点；
  - 被拖节点固定在鼠标位置，不参与弹簧。
- 松开后：
  - `settleStep` 只做局部弹簧 + 斥力回弹；
  - 10 帧收敛，位移上限 2px/帧；
  - 最后做一次碰撞松弛。

---

## R26 · 2026-10-26 · M5 流程图重写：物质节点 + 类别配色 + 拖动缩放

### 实测结果

| 项 | 结果 |
|---|---|
| `gradlew test` | **81 tests, 0 failures** |
| 产物 | `recipebalancer-0.1.0-1.21.1.jar` **2,353,558 B** |
| 实机部署 | 已复制到 `1.21.1-NeoForge/mods/`（22:00:52） |
| 未验证 | 实机流程图视觉/拖动/缩放 |

### 图形模型

- **橙色方形** = 配方节点：
  - `R#`、设备图标（无图标渲染设备名首字）、设备台数 `xN`；
- **圆角矩形** = 物质节点：
  - 绿 = 外部输入（`externalInputs`）
  - 蓝 = 中间产物
  - 红 = 启动库存（仅 `autoBootstrap` 开启且 `bootstrapInventory` 有该物质）
  - 黄 = 副产物（`finalOutputs`）
  - 紫 = 最终目标（Scenario targets 优先级最高）
- 物质有 ItemStack/ingredient 图标时渲染真实图标，否则首字。

### 流程图布局与交互

- **势能最小化布局**（确定性种子，非随机每次变化）：
  - 节点斥力 + 最短距离约束（按节点尺寸 + 标签留白）；
  - 边弹簧使用**固定自然长度**，避免箭头过长/节点挤在一起；
  - 向心重力 + 圆环径向势能，整体收束成圆形簇；
  - 碰撞松弛：多轮把重叠节点推开，保证最小间距；
  - **局部能量搜索**：
    - 每个节点尝试 16 方向 × 3 距离的候选位置；
    - 代价 = 边交叉数 × 800 + 边穿节点数 × 350 + 边长² + 节点重叠² + 圆环偏差；
    - 只接受降低能量的移动，迭代 2~4 轮；比全局模拟退火快。
  - 相机按内容自动 fit。
- 边：
  - 方向：物质→配方（输入）、配方→物质（输出）；
  - 线宽随每 tick 流量 sqrt 变化；
  - 箭头明确指向目标节点；
  - 标签 `formatCompact(flow)`。
- **单位**：所有物质流速统一 **/t**：
  - 求解器 `recipeRates` 是每秒，图内全部除以 20；
  - 边标签显式带 `/t`（如 `0.2/t`），tooltip 也全部 `/t`；
  - 文本输出改用 `TextReporter.formatPerTick`，输入/输出/外部/副产物同样 ÷20，
    与流程图数值一致（20/s 显示 1/t）。
  - `TextReporter.format` 默认每秒语义保留，Python 金标准/导出不受影响。
- 布局：力导向 + 圆环径向约束 + 向心重力，整体收束成圆形簇；
  节点间距压缩、相机自动 fit，箭头随之变短。
- 交互：
  - 鼠标左键拖动平移；
  - 滚轮以鼠标位置为中心缩放（0.25×~3×）；
  - 图内顶部图例、底部操作提示。
- **悬停详情**：
  - 配方：设备/并行数/设备数/运行速率、输入与输出流量（全部 /t）；
  - 物质：类别、生成/消耗流量、目标速率、外部输入、启动库存、副产物；
  - 箭头：物质名、两端节点、流量 /t。

### 已知限制

| # | 限制 | 说明 |
|---|---|---|
| 1 | 力导向布局非正交 | 边可能交叉；后续可加正交/分层模式 |
| 2 | 节点拖动不回写布局 | 手动拖动只影响当前会话，重新求解会重新布局 |
| 3 | 外部输入无独立源点 | 作为绿色物质节点，边从其指向配方 |

---

## R25 · 2026-10-26 · M5 起步：求解结果文本/流程图切换

### 实测结果

| 项 | 结果 |
|---|---|
| `gradlew test` | **81 tests, 0 failures** |
| 产物 | `recipebalancer-0.1.0-1.21.1.jar` **2,345,246 B** |
| 实机部署 | 已复制到 `1.21.1-NeoForge/mods/`（20:00:42） |
| 未验证 | 实机流程图渲染/滚动 |

### 结果页

- 求解结果面板扩大：宽 `min(width-16, 760)`、高 `height-16`。
- 底部按钮：
  - 左：`返回编码`
  - 右：`流程图` / `文本输出` 切换（成功时显示；失败仍只显示文本）。
- 文本模式保留原滚动报告；切换流程图时重置滚动。

### 流程图（初版）

新增 `mc/ui/graph/SolveGraphView`：

- 节点 = 运行速率 ≥ `EPS_RATE` 的配方：
  - `R#` + 设备图标 + 产物图标
  - 运行速率 `x/s`、设备台数
- 边 = 生产者 → 消费者的物品物流：
  - 同一对配方多个物品合并为一条边，标签显示“物品名 +N”
  - 直角折线 + 箭头
- 分层：
  - 配方依赖图先做 SCC（复用 `DirectedGraph`），再对凝聚图拓扑分代；
  - 同一 SCC 内配方同层，循环/回流边用棕色下绕线。
- 画布：
  - 滚轮纵向滚动；`Shift+滚轮` 横向滚动；
  - 裁剪在结果面板内，不溢出。

### 已知限制

| # | 限制 | 说明 |
|---|---|---|
| 1 | 配方级流程图 | 尚未把外部原料/目标产物画成独立 item 节点 |
| 2 | 无缩放/拖拽 | 只有滚轮/Shift+滚轮 |
| 3 | 边标签只显示首个物品 | 多物品用 +N 表示，详细看文本报告 |

---

## R24 · 2026-10-26 · 非物品 ingredient（流体/化学品）与 B 单位

### 实测结果

| 项 | 结果 |
|---|---|
| `gradlew test` | **81 tests, 0 failures**（新增 IngredientKey 编码测试） |
| 产物 | `recipebalancer-0.1.0-1.21.1.jar` **2,334,389 B** |
| 实机部署 | 已复制到 `1.21.1-NeoForge/mods/`（18:45:17） |
| 未验证 | 实机流体/化学品 ghost 与加号 |

### 身份

- 新增 `IngredientKey`：
  `ingredient|base64(typeUid)|base64(uniqueId)|base64(displayName)`
- 非物品 key 与物品 key 一样参与 RecipePattern / Scenario JSON / 求解，
  只是 UI 用彩色占位块 + 显示名首字渲染。
- `displayName` / `registryName` / tooltip / 求解报告可读名均支持非物品。

### B 单位

- JEI item：数量 = `ItemStack.getCount()`。
- 非物品：数量 = `IIngredientHelper.getAmount() / 1000.0`。
  - 500 mB 水 → **0.5**；
  - 1000 mB → 1.0。
- `AmountScreen` 对非物品显示 `用量（B）`；目标速率单位显示 `B/t`、`B/s` 等。
- 草稿/求解仍按 double 数量参与 LP，不感知具体是物品还是流体。

### JEI 接入

- Ghost：`JeiGhostHandler` 不再只接受 ItemStack，
  通过 `JeiIngredientMapper` 接受任意 `ITypedIngredient`，落点写入 B 数量。
- 配方加号：INPUT/OUTPUT 取 `getDisplayedIngredient()`（或首个 ingredient），
  流体/化学品同样带入，输出数量按 B。
- 设备：仍优先 JEI 左侧第一个物品催化剂；
  若 layout CATALYST 是非物品，也能写入设备 key。

### JEI 真实渲染与 R/U

- 新增 `JeiIngredientRenderer implements ItemKeyMapper.IngredientRenderHook`：
  - runtime 提供后由插件安装；卸载时置空。
  - 通过 `IIngredientManager.getIngredientRenderer(type)` 调 JEI 原生
    `IIngredientRenderer.render(...)`，流体/化学品使用真实贴图。
  - tooltip 用 `IIngredientRenderer.getTooltip(...)`，再追加 B 单位提示。
  - `ItemKeyMapper` 不直接依赖 JEI；无 JEI 或解析失败时回落占位色块。
- R/U：
  - `BalancerScreen.jeiHoveredKey` 返回物品 key **或** ingredient key。
  - 插件对 ingredient key 用 `JeiIngredientRenderer.resolveTyped` 还原
    `ITypedIngredient`，再交给 `IClickableIngredientFactory`，因此非物品也能 R/U。
- 非物品数量仍按 `helper.getAmount()/1000`（B）。

### 化学品 400mB → 1B 的根因

- JEI `IIngredientHelper.getAmount(V)` 的默认实现返回 **-1**。
- 流体 helper 覆写了它，所以流体正确；Mekanism 化学品 helper 未覆写，
  原逻辑 `raw <= 0 → 1.0` 因而把 400mB 导成 1B。
- 修复：`helper.getAmount()` 无效时，反射读取 ingredient 自身的
  `getAmount()`（如 ChemicalStack.getAmount()），再 `/1000`。
  - 400mB → 0.4 B。

### 限制

| # | 限制 | 说明 |
|---|---|---|
| 1 | 化学品数量按 mB 假设 | 反射到的 getAmount 按 mB 处理；若某 mod 返回非 mB 需要适配 |
| 2 | 流体 tooltip 的数量 | JEI renderer 使用还原 ingredient 的默认量；RecipeBalancer 自身数量在格子上 |

---

## R23 · 2026-10-26 · 药水按物品 tooltip / 1px 图标 / 首个催化剂

### 实测结果

| 项 | 结果 |
|---|---|
| `gradlew test` | **80 tests, 0 failures** |
| 产物 | `recipebalancer-0.1.0-1.21.1.jar` **2,324,811 B** |
| 实机部署 | 已复制到 `1.21.1-NeoForge/mods/`（18:22:32） |
| 未验证 | 实机药水/图标/加号冒烟 |

### 药水时长

- `reportName` 不再手动 `PotionContents.addPotionTooltip(..., 1.0f, 20f)`，
  改为读取物品自身的 `stack.getTooltipLines(...)`。
- 效果：
  - `LingeringPotionItem` 的 0.25 时长因子生效 → Water Breathing 显示 `00:45`；
  - Splash 按原版 tooltip 规则显示（通常为 3:00）；
  - 不再出现 Lingering 也 3:00 的错误。

### 配方按钮图标

- 对参考图按 13×13 网格**逐像素采样**，把 169 个 ARGB 值写入代码，
  绘制时每个像素 `fill(1×1)` 完全复刻；不再用近似线条。
- 尺寸 13×13，白线全部 1px。
- `IIconButtonController.drawExtras` 检测鼠标在按钮矩形内时，把最外圈 1px
  黑框覆盖为白框，行为与 JEI 原生书签按钮一致。
- hover 白框改为使用 **`drawExtras` 的 Rect2i** 定位；早期用图标 draw 坐标记录，
  会被 JEI 离屏/预绘制覆盖成 (0,0)，导致白框跑到屏幕左上角。

### 加号设备自动填充

- 优先通过 `IJeiRuntime.getRecipeManager()
  .createRecipeCatalystLookup(layout.getRecipeCategory().getRecipeType())
  .getItemStack().findFirst()` 取 JEI 左侧“可用设备”列的**第一个**。
- 没有 catalyst lookup / 没有催化剂时，退回 layout 的 `CATALYST` 槽。

---

## R22 · 2026-10-26 · 药水时长 / 13px 图标 / R-U 查询 / 加号数量

### 实测结果

| 项 | 结果 |
|---|---|
| `gradlew test` | **80 tests, 0 failures** |
| 产物 | `recipebalancer-0.1.0-1.21.1.jar` **2,323,369 B** |
| 实机部署 | 已复制到 `1.21.1-NeoForge/mods/`（18:03:42） |
| 未验证 | 实机 GUI/JEI 冒烟 |

### 1. 药水显示 1 小时

- `PotionContents.addPotionTooltip` 的第二个 float 不是倍率，而是 **tickRate**。
- 之前传 `1.0f`：3600 tick 被当成 3600 秒 → `01:00:00`。
- 改为 **20.0f**，与 `PotionItem` 的 `tooltipContext.tickRate()` 一致；
  45 秒 / 2 分钟恢复正常。

### 2. 配方按钮 13×13

- 自绘 `PatternIcon` 从 16×16 改为 **13×13**，重新排布四角/十字，
  避免 JEI 按钮里被裁切或显小。

### 3. 编码区 R/U 查询

- `BalancerScreen` 实现 `jeiHoveredItem`：返回鼠标下的 ItemStack + Rect2i。
- `RecipeBalancerJeiPlugin` 为主界面注册 `IScreenHandler`，
  重写 `getClickableIngredientUnderMouse`：
  - 编码原料/产物
  - 设备格
  - 目标格
  - 配方库产物
  - 玩家背包
- 悬停后按 R/U 即可查看 JEI 合成表/用途。

### 4. JEI 加号保留数量

- `IRecipeSlotView` 的 `ItemStack.getCount()` 现在带入 RecipePattern：
  - 输出 3 个门 → 产物数量 3；
  - 输入多格木板各自 1 → 合并后总需求正确。
- 仍然每格默认取显示物品；数量为 0/异常的槽位按 1 处理。

---

## R21 · 2026-10-26 · 完整 tooltip / 结果可读名 / 配方按钮图标

### 实测结果

| 项 | 结果 |
|---|---|
| `gradlew test` | **80 tests, 0 failures** |
| 产物 | `recipebalancer-0.1.0-1.21.1.jar` **2,319,998 B** |
| 实机部署 | 已复制到 `1.21.1-NeoForge/mods/`（17:53:38） |
| 未验证 | **实机 tooltip/求解报告/按钮图标冒烟** |

### 1. 物品槽完整 tooltip

- `ItemKeyMapper.renderTooltip` 对可解析 ItemStack 改用
  `graphics.renderTooltip(font, stack, mouseX, mouseY)`。
- 药水会显示：
  - 物品名
  - 效果列表（Water Breathing 00:45 / 02:00）
  - registry id
  - 组件数量/模组来源
- 编码区、目标格、配方库、设备格全部生效；背包原本就是原生 tooltip。

### 2. 求解结果可读化

- 新增 `ItemKeyMapper.reportName` + `prettyText`：
  - `stack|base64` → `物品名 [效果（时长）]`
  - 例如 `Lingering Potion of Water Breathing [Water Breathing (00:45)]`
- `BalancerScreen.solveNow` 在把报告交给 `SolveScreen` 前做文本替换；
  不影响 `TextReporter`/磁盘数据/Python。
- 45s 与 2min 的药水在结果中可直接区分。

### 3. JEI 配方按钮图标

- 自定义 `PatternIcon`（16×16）：
  - 深色底 + 灰内框
  - 白色中央十字
  - 四角 L 形
- 替换默认浅色 `getRecipePlusSign()`，在 JEI 灰色按钮上对比度更高。

---

## R20 · 2026-10-26 · 药水/组件物品身份修复

### 实测结果

| 项 | 结果 |
|---|---|
| `gradlew test` | **80 tests, 0 failures** |
| 产物 | `recipebalancer-0.1.0-1.21.1.jar` **2,318,083 B** |
| 实机部署 | 已复制到 `1.21.1-NeoForge/mods/`（17:41:55） |
| 未验证 | **实机药水 JEI 加号/拖拽冒烟** |

### 根因

`ItemKeyMapper.of(ItemStack)` 之前只返回 registry id：

```java
return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
```

药水的 `potion_contents`（以及附魔/自定义名等）组件全部丢失。
`stack(key)` 再构造时只剩默认 `minecraft:potion`，JEI 加号/ghost 拖入就显示为
`Lingering Uncraftable Potion`，无法参与酿造配方。

### 修复

- 组件补丁为空时仍存普通 registry id（保持旧存档/兼容）。
- 有组件时存：

```
stack|<base64( ItemStack.CODEC JSON )>
```

- 使用 `RegistryOps.create(JsonOps.INSTANCE, level.registryAccess())` 编解码，
  保留 `potion_contents`、自定义名、附魔、damage 等全部数据。
- `stack(key)` 反序列化并缓存；`displayName`/图标/ghost/JEI 加号都恢复成药水真实变体。
- `registryName` 只显示底层物品 id，tooltip 不再出现 base64。
- 组件 key 很长，`AmountScreen` 的 key 输入框上限从 128 放宽到 4096，
  避免打开用量页再确定时把 key 截断。

### 限制

- 场景 JSON 里的组件 key 是不透明 base64 字符串；Python 侧按不同物品字符串处理，
  不认识具体药水语义。求解本身不关心。
- 极端情况下没有 level registry access 时退化为普通 registry id（仅启动早期，正常游戏内不会）。

---

## R19 · 2026-10-26 · M4：JEI ghost 拖拽 + 配方加号导入

### 实测结果

| 项 | 结果 |
|---|---|
| `gradlew test` | **80 tests, 0 failures** |
| 产物 | `recipebalancer-0.1.0-1.21.1.jar` **2,316,395 B** |
| 实机部署 | 已复制到 `1.21.1-NeoForge/mods/`（17:35:09） |
| 未验证 | **实机 JEI 拖拽/加号冒烟** |

### Ghost 拖拽

- 新增 `JeiGhostHandler implements IGhostIngredientHandler<BalancerScreen>`。
- `BalancerScreen.ghostSlots()` 返回当前**可见**的：
  - 原料 3 列 × 可视行
  - 产物 2 列 × 可视行
  - 设备格
  - 目标格（可见 9 列 × 1~2 行）
- 拖入后：
  - 原料/产物：对应索引放 1 个（同物品累加）；
  - 设备：设置设备；
  - 目标：新增 1/t（受 32 上限）。
- 滚动后重新拖拽即可落到新可见行。

### JEI 配方加号

- 新增 `JeiRecipeButtonFactory`，通过 `IAdvancedRegistration.addRecipeButtonFactory`
  给 JEI 配方页追加加号按钮（图标 `getRecipePlusSign()`）。
- 点击后：
  - 读取 `IRecipeLayoutDrawable.getRecipeSlotsView()`；
  - INPUT/OUTPUT 各取第一个物品，数量默认 1；
  - CATALYST 第一项作为设备（取不到则留空，需人工补）；
  - 生成 `RecipePattern` 并打开编码终端。
- 加号在无产物配方上隐藏；按住模拟/非左键不触发。

### 已知限制

| # | 限制 | 说明 |
|---|---|---|
| 1 | JEI 槽不提供数量 | 一键导入所有数量默认 1，需人工改量 |
| 2 | 设备取催化剂 | 无催化剂的配方打开后设备为空，需手动设置 |
| 3 | 仅 ItemStack | 不支持流体 ghost/配方 |
| 4 | 加号打开终端而非直接入库 | 保留人工复核，避免错误配方直接写入 |

---

## R18 · 2026-10-26 · 紧凑数字深度修复 + 完整 SI 后缀 + 耗时紧凑显示

### 实测结果

| 项 | 结果 |
|---|---|
| `gradlew test` | **80 tests, 0 failures** |
| 产物 | `recipebalancer-0.1.0-1.21.1.jar` **2,306,721 B** |
| 实机部署 | 已复制到 `1.21.1-NeoForge/mods/`（17:16:04） |
| 未验证 | 实机 GUI 冒烟 |

### 渲染深度

- `ItemKeyMapper.renderAmount` 在绘制数量前 `pose.translate(0,0,200)`，
  数量文字不再被物品图标/槽位盖住。
- 耗时槽内数值同样抬高 z；缩放绘制时也保留 z 偏移。

### 完整 SI 后缀

`formatCompact` 现支持（从大到小）：

```
E  P  T  G  M  k   (1)   m  μ  n  p  f
```

- `1e18 → 1E`、`1e15 → 1P`、`1e12 → 1T`
- `1e-9 → 1n`、`1e-12 → 1p`、`1e-15 → 1f`
- 原料/产物/目标格的一级显示都用此格式；过长文字等比缩到 14px。

### 耗时一级显示

- 耗时格改为 `formatCompact(ticks) + "t"`，例如 `30k t` 显示为 `30kt`；
- 精确值和单位仍在编辑页/tooltip 中。

---

## R17 · 2026-10-26 · 单位记忆、目标每t、SI 紧凑数与数量表达式

### 实测结果

| 项 | 结果 |
|---|---|
| `gradlew test` | **80 tests, 0 failures** |
| 产物 | `recipebalancer-0.1.0-1.21.1.jar` **2,306,502 B** |
| 实机部署 | 已复制到 `1.21.1-NeoForge/mods/`（17:04:51） |
| 未验证 | 实机 GUI 冒烟 |

### 单位记忆

- 新增 `UnitPreferences`（`config/recipebalancer/ui_units.properties`）：
  - `time_unit=TICK/SECOND/MINUTE/HOUR`
  - `rate_unit=TICK/SECOND/MINUTE/HOUR`
- `TimeScreen` 打开时优先使用上次耗时单位；切换/确定时保存。
- `AmountScreen` 目标速率打开时优先使用上次速率单位；切换/确定时保存。
- 目标列表始终 `/t`，不随编辑单位改变。

### 目标速率统一每 t

- 一级目标格显示 `ScenarioDraft.targets` 的每 tick 原值，标签 `· /t`。
- 新目标默认 **1/t**；状态栏 `/t`。
- 速率编辑仍可选 `/t /s /m /h`，确认后换算成每 tick。
- 场景 JSON 仍在边界 ×20 写每秒（Python schema 不变）。

### SI 紧凑显示

- 新增 `TimeExpression.formatCompact`：
  - `30000 → 30k`、`100000 → 100k`、`1000000 → 1M`
  - `0.0005 → 500μ`，支持 `μ m k M G T`
- 一级编码格/目标格使用紧凑值；过长时图标数量文字等比缩小（最大 14px），不再溢出格子。
- 二级编辑框仍用 `formatEditableNumber`，显示完整精确 double。

### 数量表达式

- `AmountScreen` 的用量/速率输入框改用 `TimeExpression.evaluate`：
  - 支持 `1+2`、`2*10`、`2^3` 等；
  - `+1/+10/...` 基于表达式求值结果继续加减；
  - 非法表达式提示具体原因，不再只报“必须是数字”。
- 原料/产物/设备数量仍无量纲；目标速率在表达式求值后再做单位换算。

---

## R16 · 2026-10-26 · 二级菜单按钮/排版 + 目标速率单位

### 实测结果

| 项 | 结果 |
|---|---|
| `gradlew test` | **80 tests, 0 failures** |
| 产物 | `recipebalancer-0.1.0-1.21.1.jar` **2,303,746 B** |
| 实机部署 | 已复制到 `1.21.1-NeoForge/mods/`（16:47:40） |
| 未验证 | 实机 GUI 冒烟 |

### 二级菜单

- `取消` 固定在左，`确定` 固定在右（AmountScreen / TimeScreen）。
- 物品设置页重排：
  - 图标 18px 槽位于注册名输入框**左侧**；
  - 真名 + 注册名在图标/输入框**下方**；
  - 标题、标签、输入框不再叠字；
  - 用量行、±按钮、状态、底部按钮分区明确；
  - 面板 236×208。
- 目标产物页：数值框后加单位按钮，循环 `/t` → `/s` → `/m` → `/h`。
- 原料/产物/设备页仍为无量纲数量，不显示单位按钮。

### 速率与存储

- 新增 `core/time/RateUnit`：`1 /s = 20 /t`、`/m = 1200`、`/h = 72000`。
- `ScenarioDraft.targets` 统一存 **每 tick 数量**；`putTarget`/回调均为每 tick。
- 一级目标格按 **/s** 显示（内部 ×20），目标标签标注 `· /s`。
- 写场景 JSON 时 `toScenario()` 每 tick ×20 恢复每秒；读取时 ÷20。
  这样既满足草稿/求解内部每 tick，也保持 Python `target_products` schema（每秒）不变。
- 新目标默认 1/s（0.05/t）；目标编辑初始显示 /s。

### 新增测试

- `RateUnit` 的 /s、/m、/h 与每 tick 互转。
- `ScenarioDraft` 目标内部每 tick、写出场景恢复为每秒（2/t → 40/s）。

---

## R15 · 2026-10-26 · 编码区设备/耗时文本归位

### 实测结果

| 项 | 结果 |
|---|---|
| `gradlew test` | **79 tests, 0 failures** |
| 产物 | `recipebalancer-0.1.0-1.21.1.jar` **2,301,374 B** |
| 实机部署 | 已复制到 `1.21.1-NeoForge/mods/`（16:14:48） |

### 修复

- `设备` / `耗时` 标签分别画在设备槽、耗时槽的**右侧**（`equipmentX + SLOT + 2`）。
- 耗时数值回到**耗时槽内部**居中；`12t` 正常在格子里。
- 0.001t 等长文本按槽宽等比缩小，不溢出到按钮或标签区域。
- 标签与按钮不再互相占用：设备标签 y+5、耗时标签 y+25、写入/新建 y+38/y+54。

---

## R14 · 2026-10-26 · 载入/存储与写入/新建改为压缩上下排列 + 时间精度

### 实测结果

| 项 | 结果 |
|---|---|
| `gradlew test` | **79 tests, 0 failures**（新增亚 tick 存储回归） |
| 产物 | `recipebalancer-0.1.0-1.21.1.jar` **2,301,308 B** |
| 实机部署 | 已复制到 `1.21.1-NeoForge/mods/`（16:07:58） |
| 未验证 | 实机 GUI 冒烟 |

### 按钮

- `载入` / `存储`：保持**一行**，仅把高度压缩到 **14**（34×14）；顶部工作区行仍为 20 高。
- `写入` / `新建`：恢复**上下排列**，尺寸 **40×14**，位于耗时格下方。
- 时间值改画在耗时槽右侧空白，不再被 18px 槽宽截成 `0`。

### 时间精度与默认值

- 一级界面 `formatNumber` 使用 **3 位小数**（用户可接受）；
  编辑数据使用新增 `formatEditableNumber`：
  - `0.001` → `"0.001"`
  - `0.00000000001` → `"0.00000000001"`（完整 double 十进制，不量化）
- `TimeExpression.formatExactTicks` 也用完整值：
  - 二级预览显示 `= 0.00000000001 t`。
- `TimeScreen` 表达式初值、`AmountScreen` 用量初值/±按钮均改用 `formatEditableNumber`，
  不再因为展示格式把编辑数据截断。
- 新配方默认耗时 **1t**（`RecipePattern` 初始/clear）。
- 存储链路验证：0.00005s 亚 tick 时间经 JSON 保存/宽松读取后不变（×20 = 0.001t）。

### 新增测试

- `TimeExpressionTest`：0.001 / 0.00000000001 的 3 位展示、完整编辑值、精确 tick。
- `RecipePatternTest`：空配方默认耗时 1t（toRecipe=0.05s）。
- `ScenarioStoreTest`：0.00005s 亚 tick 时间经 JSON 保存/宽松读取后不变。

### 待验证

1. 新建配方 → 耗时显示 `1t`。
2. 耗时输入 `0.001`（单位 t）→ 主界面显示 `0.001`，写入/重开仍保留。
3. 载入/存储在名称右侧上下排列；写入/新建在耗时下方上下排列。

---

## R13 · 2026-10-26 · JEI resize/二级缓存刷新 + 小窗按钮

### 实测结果

| 项 | 结果 |
|---|---|
| `gradlew test` | **78 tests, 0 failures** |
| 产物 | `recipebalancer-0.1.0-1.21.1.jar` **2,301,057 B** |
| 实机部署 | 已复制到 `1.21.1-NeoForge/mods/`（15:46:50） |
| 未验证 | 实机 JEI 冒烟（需重启/缩放窗口） |

### JEI 位置不自动刷新的根因

- JEI 只在收到 `ScreenEvent.Init.Post` 时刷新 `GuiPropertiesCache`。
- 窗口 resize / 最大化会重新执行 `Screen.init()`，但 **不会补发** Init.Post →
  JEI 继续用旧尺寸，表现为内容压在主界面上；进二级菜单返回时因屏幕切换才刷新。
- 二级 `AmountScreen` 等此前也只在首次打开时被 JEI 拿到默认属性，居中实际面板与
  JEI 认知不一致。

### 修复

新增 `mc/jei/JeiScreenSupport.notifyGuiInit(Screen)`：在以下屏幕的 `init()` **末尾**
手动补发 `ScreenEvent.Init.Post`：

- `BalancerScreen`
- `AmountScreen`
- `TimeScreen`
- `ScenarioListScreen`

这样 resize 后和二级界面打开时 JEI 都会用**当前** `IGuiProperties` 重新布局。
（事件里的 add/remove listener 用空实现，JEI 只读取属性。）

### 二次兜底（15:09 追加）

实机反馈“小窗首次打开仍覆盖”，说明 JEI 19.70 的缓存未必响应手动事件。追加：

- **延后一 tick 再补发** Init.Post（首次 `setScreen` 时 `Minecraft.screen` 可能尚未切换）；
- 在 `RecipeBalancerJeiPlugin.onRuntimeAvailable` 保存 `IJeiRuntime`；
  `JeiScreenSupport` 反射调用 JEI 内部
  `IngredientListOverlay/BookmarkOverlay.getScreenPropertiesUpdater()`
  → `updateScreen(screen)` → `forceUpdate()`，直接驱动缓存刷新（JEI 19.x 无公开 API）。
- 反射失败时写 warn 日志并退回事件机制，便于实机定位。

### 面板居中与可选隔离

- 面板恢复**屏幕正中**：`panelX = (width - panelW) / 2`；
  JEI 由 `guiRight` 自动使用右侧剩余空间，不再靠左让位。
- 若窗口宽度不足以同时放下面板与 JEI 网格，JEI 会自行压缩/调整列数，
  而不是把配平面板推离中心。
- 新增 `JeiRuntimeHolder`（不含任何 JEI 类型）：**未装 JEI 时不会因反射类而缺类崩溃**；
  `RecipeBalancerJeiPlugin` 在 `onRuntimeAvailable` 写入运行时。
- 延迟补发事件前检查 `Minecraft.screen == screen`，避免关闭界面后把 JEI 缓存刷回旧屏。

### 小窗口按钮

`写入` / `新建` 改为 **20×16 并排两枚**，位置固定在耗时格下方 `encodingY+38`；
不再使用 40 宽堆叠，避免小窗口时“新建”压到背包标签/被误认为跑到面板外。

### 待验证

1. 小窗口打开 → 最大化：JEI 应自动移到右侧，无需进二级再返回。
2. 主界面首次打开：JEI 正常。
3. 用量/耗时/配方集二级界面：JEI 按其实际居中的面板重新定位，不覆盖面板。
4. 小窗口下 `写入/新建` 位于耗时下方，不压背包。

---

## R12 · 2026-10-26 · 动态窄宽（9 格基准）+ 产物两列 + 按钮下移

### 实测结果

| 项 | 结果 |
|---|---|
| `gradlew test` | **78 tests, 0 failures** |
| 产物 | `recipebalancer-0.1.0-1.21.1.jar` **2,297,677 B** |
| 实机部署 | 已复制到 `1.21.1-NeoForge/mods/`（14:34:56） |
| 未验证 | **实机 GUI 冒烟**（需重启） |

### 布局变化

1. **宽度不再写死**：由内容推算
   - 9 格列表/背包：`9 × 18 = 162`
   - 参数行：`4 × (30+2) + 36 = 164`
   - 编码：原料 3 列 + 箭头 + 产物 2 列 + 设备列 = 156
   - 取最大 + 8 内边距 ≈ **172**（旧版 330）
   - 顶部工作区名输入框宽度由剩余空间推算（约 66px）。
2. **目标 / 配方库 / 背包统一 9 格宽**：
   - 目标 9 列 × 1~2 行，滚轮，最多 32
   - 配方库 9 列，剩余高度优先给库
   - 背包 9 列 × 4 行
3. **编码区产物改为 2 列**：原料 3 列、产物 2 列；编码仍最多 4 行可视。
4. **写入/新建下移**：放到耗时格下方，按钮 40×16，不再占编码右侧横向空间。
5. **参数按钮压缩**：4 个 30×18 参数 + 36×18 求解；标签改为 `设备/配方`、`原料+/-` 等。
6. 高度分配：优先保证编码 4 行；不足时先减配方库、再减目标行；极小窗口才压缩编码可视行。

### 涉及文件

- `BalancerScreen`：动态宽度、9 列目标/库/背包、产物 2 列、按钮下移、紧凑参数行
- 文档/README 同步；JEI 多屏注册不变

### 待验证

1. 重启 → K：面板明显变窄（约 172 GUI px），右侧 JEI 空间更大。
2. 目标、配方库、背包三者同宽 9 格；配方库格子与背包一致。
3. 产物区两列；`写入/新建` 在耗时格下方。
4. 编码区最多 4 行，配方库占据其余高度。

---

## R11 · 2026-10-26 · 工作区头部 + 内联目标（32）+ 编码最多 4 行

### 实测结果

| 项 | 结果 |
|---|---|
| `gradlew test` | **78 tests, 0 failures** |
| 产物 | `recipebalancer-0.1.0-1.21.1.jar` **2,297,480 B** |
| 实机部署 | 已复制到 `1.21.1-NeoForge/mods/`（14:22:57） |
| 未验证 | **实机 GUI 冒烟**（需重启） |

### 新布局（按草稿）

```
工作区 [名称输入框]                         载入 存储
[目标:设备][原料:开][种子:开][投料:关][求解]
目标 (n/32)  [□×12]  ← 最多两行，可滚动
配方库 (n)   [□×8] … ← 剩余高度全部给这里
原料 n [3列] → 产物 n [3列]   设备 [□] 写入
                               耗时 [20t] 新建
背包  [9×4]
状态栏
```

- **工作区名称**：顶部 EditBox + `载入` / `存储`；`存储` 直接写当前工作区，
  `载入` 打开配方集列表（宽松读取，允许未填目标/配方）。
- **参数与求解**：四个参数选择按钮 + `求解` 同排，不再占用整块面积。
- **目标列表**：去掉「目标」按钮；一级界面内联 **12 列 × 1~2 行**，
  滚轮翻动，最多 **32 个目标**；左键编辑、右键删除、空槽自定义/手持添加。
- **配方库**：8 列，行数随高度增加；高度优先给库，编码区最多 **4 行**。
- **编码区**：仍 3 列 × 最多 4 行可视，滚动查看 256 原料 / 16 产物。
- **背包**：9×4 固定；所有槽位 18px。
- 删除 `TargetListScreen`（目标不再通过二级页）。

### 布局分配

- 固定：工作区 20 + 参数 20 + 目标标签 + 配方库标签 + 编码标签 + 背包 72 + 状态。
- 初始目标 2 行、编码 4 行、配方库 1 行；高度不足时依次压缩配方库/编码/目标；
  剩余高度全部加到配方库（最多 14 行可视，内部可滚动）。
- 小窗口（GUI scale 3）会降到 1+1+1 行，仍可滚动使用。

### 涉及文件

- `BalancerScreen`：头部工作区、参数行、内联目标、库/编码动态高度、紧凑按钮
- `ScenarioListScreen`：工作区载入改为 `loadDraft`，另存不再强制目标非空
- 删除 `TargetListScreen`；JEI 插件同步移除该屏注册

### 待验证

1. 重启 → K：顶部工作区名 / 载入 / 存储；参数与求解同排。
2. 目标区直接添加/编辑/删除，滚轮翻到第 32 个；无「目标」按钮。
3. 配方库行数明显多于编码区；编码最多 4 行。
4. JEI 首开与二级界面仍渲染。

---

## R10 · 2026-10-26 · 原版配色 + 18px 槽 + 满高布局 + JEI 首开/二级渲染

### 实测结果

| 项 | 结果 |
|---|---|
| `gradlew test` | **78 tests, 0 failures** |
| 产物 | `recipebalancer-0.1.0-1.21.1.jar` **2,301,520 B** |
| 实机部署 | 已复制到 `1.21.1-NeoForge/mods/`（13:56:21） |
| 未验证 | **实机 GUI / JEI 首开冒烟**（需重启） |

### 本轮改动

1. **原版配色**（对齐参考图）：
   - 面板 `#C6C6C6`、标题栏同色
   - 物品槽 `#8B8B8B` + 左上 `#373737` 阴影 + 右下白色高光
   - 文字 `#404040`；蓝色仅保留选中/强调
2. **槽位改为 18px**：物品 16×16 居中（+1px 边距），数量文字在槽内；
   修复“物品比格子大、64 溢出”的问题。配方库格子与下方编码/背包格统一为 18px。
3. **面板高度**：`panelH = height - 8`（基本占满屏幕），宽度仍 330 以给 JEI 留空间。
   - 配方库/编码可视行数按高度动态分配：至少 2+2 行，先各抬到 4 行，
     再交替到 8 行，剩余高度继续给配方库（最多 12 行）。
   - 配方库不再固定 2 行；1080p 常见 GUI 缩放下可显示 6 行左右。
4. **按钮收敛**：顶部 `目标/载入/求解` 40×18 右对齐；编码区 `写入/新建` 40×18；
   选项改为 46×18 的 2×2 小按钮放在背包右侧，不再占一大片。
5. **JEI 渲染修复**：
   - 根因 1：JEI 在 `Screen.init` 之前查询 `IGuiProperties`，面板字段当时为 0 → 校验失败；
     现在所有面板字段都有合法默认值。
   - 根因 2：只注册了主界面；二级 `AmountScreen`/`TimeScreen`/`TargetListScreen`/`ScenarioListScreen`
     没有 handler。现在全部注册（`JeiPanelScreen` 统一接口）。
   - `SolveScreen` 结果页不注册 JEI（纯文本报告，面板很宽，注册会干扰 JEI 定位）。
   - `screenWidth/Height` 在 `Screen.init` 前为 0 时回退到窗口 GUI 尺寸。

### 涉及文件

- `mc/ui/AeStyle`：原版配色 + 18px 槽
- `mc/itemmap/ItemKeyMapper`：18px 槽内偏移、数量/占位
- `mc/screen/BalancerScreen`：满高布局、动态行、18px 全部槽位、按钮重排
- `mc/screen/JeiPanelScreen`（新增）+ `AmountScreen`/`TimeScreen`/`TargetListScreen`/`ScenarioListScreen`
  实现面板属性
- `mc/jei/RecipeBalancerJeiPlugin`：多屏注册 + 默认值/窗口回退

### 待验证

1. 重启 → K：首次打开就应出现 JEI 右侧面板（不需要先进二级再返回）。
2. 中键/左键空格进用量编辑：二级界面内 JEI 也应显示。
3. 面板高度接近屏幕；配方库行数随窗口高度增加；物品不超出 18px 槽。
4. 配方库格子与下方物品格大小一致。

---

## R9 · 2026-10-26 · 编码区滚动（256/16）+ AE 风格 + JEI 渲染

### 实测结果

| 项 | 结果 |
|---|---|
| `gradlew test` | **78 tests, 0 failures** |
| 产物 | `recipebalancer-0.1.0-1.21.1.jar` **2,299,681 B** |
| 实机部署 | 已复制到 `1.21.1-NeoForge/mods/`（13:25:54） |
| 未验证 | **实机 GUI / JEI 面板冒烟**（需重启后按 K） |

### 本轮改动

1. **QoL**：空手左键编码空格直接打开自定义物品输入（不再要求中键）；中键仍可改量。
2. **容量**：`RecipePattern` 从固定 9 格改为
   - **256 种原料**（`MAX_INPUTS`）
   - **16 种产物**（`MAX_OUTPUTS`）
   - 稀疏数组保存，空格保留位置；载入超限时提示并截断。
3. **编码区滚动**：3 列 × 3 行可视窗口，滚轮上下翻动（共享滚动，输入/输出同步）；
   右侧细滚动条；最后一格填满后额外留一行空格，保证能继续添加。
4. **AE 美术风格**：新增 `mc/ui/AeStyle`（自绘，不依赖 AE2 资源）：
   浅灰面板、深色内嵌槽、细高光/阴影边框、深蓝文字与蓝色强调；
   主界面及用量/耗时/目标/配方集/结果页统一。
5. **JEI 渲染**：新增 `mc/jei/RecipeBalancerJeiPlugin`（`@JeiPlugin`），
   通过 `addGuiScreenHandler(BalancerScreen.class, ...)` 注册 `IGuiProperties`
   （guiLeft/guiTop/guiWidth/guiHeight/screenWidth/screenHeight）。
   JEI 只自动识别 `ContainerScreen`，这是自定义 Screen 不显示 JEI 面板的根因。

### 测试与文件

- `RecipePatternTest` 更新为 16 产物上限（17 个触发 truncated）。
- 新增：`mc/ui/AeStyle.java`、`mc/jei/RecipeBalancerJeiPlugin.java`
- 修改：`core/model/RecipePattern`（容量/稀疏存储）、`BalancerScreen`（滚动/风格/QoL）、
  `AmountScreen`/`TimeScreen`/`TargetListScreen`/`ScenarioListScreen`/`SolveScreen`（风格）

### 已知限制

| # | 限制 | 说明 |
|---|---|---|
| 1 | 3 列网格 | 256 原料需要滚轮翻很多行；属 AE 终端形态 |
| 2 | JEI 点击/拖拽仍未接入 | 本轮只解决 JEI **渲染**；ghost 拖拽/JEI 配方写入仍 M4 |
| 3 | 滚动只支持滚轮 | 不拖动滚动条；滚动条仅指示位置 |
| 4 | 未实机冒烟 | JEI 是否出现需重启实例确认 |

### 实机验收

1. 重启 → K：面板应为浅灰 AE 风格，右侧出现 JEI 物品面板（若安装 JEI）。
2. 空手左键一个空格 → 直接弹出自定义物品输入。
3. 连续填满 9 个原料后向下滚 → 出现下一行空格，可继续添加。
4. 编码区滚轮翻动，输入/输出同步；滚动条随位置移动。

---

## R8 · 2026-10-26 · M3：AE 样板编码终端式界面

### 实测结果

| 项 | 结果 |
|---|---|
| `gradlew build` | **BUILD SUCCESSFUL** |
| `gradlew test` | **78 tests, 0 failures**（新增 `TimeExpressionTest` 4 + `RecipePatternTest` 4，其余 70） |
| 产物 | `recipebalancer-0.1.0-1.21.1.jar` **2,294,758 B** |
| 实机部署 | 已复制到 `1.21.1-NeoForge/mods/`（13:12:34），旧 jar 清理 |
| 未验证 | **实机 GUI 冒烟**：需重启游戏后按 K；本轮未启动游戏窗口 |

### 界面结构（一级）

```
┌ 配方编码终端 ─────────────────────────────┐
│ 目标 [□][□][□][□][□][□]   目标 载入 求解   │
│ 配方库 (n)  [R0][R1][R2][R3]              │
│             [R4][R5]...                   │
│ 编码        原料 3×3    产物 3×3           │
│             设备 [□] 耗时 [20t]  写入 新建 │
│ 背包        9×4（热键栏 + 主背包）          │
│ 左键取物→点格子放置；中键改量；右键库两次删除 │
└──────────────────────────────────────────┘
```

- **配方库**：每条配方显示首个产物图标 + 右下角设备小图标；左键载入编码区，
  **右键两次（500ms 内）删除**；滚轮翻页。
- **编码区**：3×3 原料 / 3×3 产物，格子右下角显示数量（1 不显示）。
- **物品交互**：左键从背包格子拿起（ghost，不消耗物品）→ 点编码格放置/同物品累加；
  空手左键已标记格删除；**中键**已标记格改用量、空格子输入自定义注册名。
- **未注册物品**：彩色方块 + 中文首字/ASCII 前 3 字母（确定性颜色），tooltip 同时显示注册名。
- **设备**：同物品格；左键放置手持物品，中键输入注册名（可在列表里不存在）。
- **耗时**：格子显示 `t` 值；左键/中键打开表达式编辑，支持 `+ - * / ^` 与括号，
  单位可切 `t / 秒 / 分 / 时`；**编码草稿内部统一存 tick**。
- **目标产物**：顶部 6 个槽（左键编辑/右键删除，空槽可从手持标记或自定义）；
  超过 6 个用「目标」二级列表管理。
- **配方集**：「载入」打开已保存场景列表（载入/另存/删除）；手动保存要求至少 1 配方 + 1 目标。
- **求解**：自动提交当前编码区 → 校验 → 进入 `SolveScreen` 二级页滚动查看报告。
- **自动保存**：关闭/求解/修改后写 `autosave.json`；关闭时若编码区有效会自动写入配方库。

### 时间语义（重要）

- `RecipePattern.timeTicks` / `TimeExpression` 一律以 **tick** 计算和保存；
- 写入 `Recipe` 时 `tick / 20` 转秒，`ScenarioJson` 仍是秒（Python `io.py` 同构）；
- 读入已有配方时 `time * 20` 转回 tick。
- 这样满足“界面/草稿按 t”，同时不破坏 M1 金标准与 A2 互导。

### 新增/修改文件

- 新增：`core/time/{TimeUnit,TimeExpression}`、`core/model/RecipePattern`、
  `mc/itemmap/ItemKeyMapper`、`mc/screen/{AmountScreen,TimeScreen,TargetListScreen,ScenarioListScreen,SolveScreen}`
- 重写：`mc/screen/BalancerScreen`（编码终端）
- 删除：`RecipeEditScreen`、`TargetEditScreen`（旧三栏表单）
- 测试：`TimeExpressionTest`、`RecipePatternTest`

### 已知限制

| # | 限制 | 说明 |
|---|---|---|
| 1 | 编码格 3×3（每侧最多 9 种物品） | 与 AE 样板终端一致；超过时载入会提示，写入只保留前 9 格 |
| 2 | JEI 拖拽/点击接入未做 | M4；当前仅玩家背包取物 |
| 3 | 一级目标条只显示前 6 个 | 其余用「目标」列表 |
| 4 | 手动保存仍要求合法场景（至少 1 配方 + 1 目标） | 未填完的草稿由 autosave 负责 |
| 5 | 未启动游戏验证 | 需实机重启后按 K 做一次冒烟 |

### 实机验收步骤

1. 重启实例 → 进入世界 → K；
2. 背包左键拿物品 → 点原料/产物格；中键改量；中键空格输入一个不存在的注册名看占位；
3. 设备格放物品，耗时输入 `2*20` 并切到「秒」；
4. 「写入」→ 配方库出现图标；左键载入；右键两次删除；
5. 顶部目标槽添加目标 → 「求解」→ 二级页查看报告。

---

## R7 · 2026-10-25 · autosave 允许未填目标/配方

### 实测结果

| 项 | 结果 |
|---|---|
| `gradlew test` | **BUILD SUCCESSFUL，70 tests, 0 failures**（新增 autosave 宽松读取测试） |
| 实机部署 | 自动复制到 `1.21.1-NeoForge/mods/recipebalancer-0.1.0-1.21.1.jar`（2,267,897 B，22:59:49） |

### 根因

autosave **写入本身是成功的**（`ScenarioJson.dump` 不校验空目标/空配方），
但下次打开时 `BalancerScreen.loadAutosave()` 用的是严格 `ScenarioJson.load()`：

- `target_products` 为空 → `SchemaException("target_products 不能为空")`
- `recipes` 为空同样被拒

异常被 catch 后回落到 `blank()`，表现为“没保存/没记住”。

### 修复

1. `ScenarioJson.loadDraft(...)`：宽松读取，允许 `recipes` / `target_products` 缺失或为空；
   已存在的每条配方仍按严格规则校验（数量、耗时、设备类型、未知键）。
2. `ScenarioStore.loadDraft(name)`：仓库层的宽松入口。
3. `BalancerScreen.loadAutosave()` 改用 `loadDraft`；手动「载入」仍是严格 `load`
   （与 Python `io.py` 的合法场景 schema 保持一致）。
4. 新增测试 `autosaveToleratesEmptyTargetsAndRecipesButManualLoadStaysStrict`：
   - 空场景 autosave 可读回，严格 load 仍抛错；
   - 有配方、无目标（用户实际场景）可读回。

### 说明

- 空场景仍可被「保存」写盘，但严格「载入」会拒绝——空场景不是可求解场景，这是有意区分。
- 以后实机测试走 `gradlew test` 自动部署；dev 客户端不作为验收环境（用户已确认实例可算）。

---

## R6 · 2026-10-25 · 求解缺类终极修复（shade）+ 自动保存草稿

### 实测结果

| 项 | 结果 |
|---|---|
| `gradlew build` | **BUILD SUCCESSFUL**，69 tests, 0 failures |
| 产物 | `recipebalancer-0.1.0-1.21.1.jar` **2,267,602 B**；`org/apache/commons/math3/**` **直接位于 jar 内**，无 `META-INF/jarjar` |
| 仅 jar 验证 | 新建独立 Java 进程，classpath **只放模组 jar**：`success=true machines=1 rates=[1.0]`——证明不再依赖外部 math3 |
| 实机部署 | 已覆盖 `1.21.1-NeoForge/mods/`（22:49:59，2,267,602 B） |
| dev JEI | 22:43 的 `run/logs/latest.log` 已加载 **Just Enough Items 19.39.0.372**，无版本错误 |

### 求解失败根因（补充）

- R5 的 JarJar 版 jar 确实含 `META-INF/jarjar/commons-math3-3.6.1.jar` + metadata，
  但实机/IDE 运行仍报 `NoClassDefFoundError`（截图状态栏是 R5 新增的 LinkageError 捕获）。
- 不再依赖 JarJar 的加载时机与运行环境（Sinytra Connector / IDE run config），
  改为 Gradle `shaded` 配置 + `jar { from(zipTree) }` **把 class 直接解包进模组 jar**。
- 增加启动自检：`RecipeBalancerMod` 构造时 `Class.forName(SimplexSolver)`，日志打印
  `commons-math3 可用/不可用`；求解 catch 记录完整堆栈。

### 记住上次输入

- `BalancerScreen` 每次打开读取 `config/recipebalancer/scenarios/autosave.json`；
- `init()` / `onClose()` / 增删配方/目标/启禁/清空/求解/载入后自动写回；
- **K 键可关闭界面**（与打开同键；`EditBox` 聚焦时不抢按键，避免输入 k 被吞）；
- 手动「保存/载入」仍然独立，不影响 autosave。

### 踩坑

| # | 现象 | 根因 | 结论 |
|---|---|---|---|
| 1 | JarJar 版仍 `NoClassDefFoundError` | 嵌套 jar 已生成，但运行环境（Connector/IDE）下不可见 | 对单库依赖，直接 shade 最稳；JarJar 不是万无一失 |
| 2 | 求解失败没有崩溃、只显示状态 | R5 已补 `catch (LinkageError)` | 这是预期降级；根因仍是缺类，必须修依赖 |
| 3 | 截图里的“求解失败”与 dev 日志时间对不上 | 用户实际在 dev 客户端测试（22:43 日志）；实机日志停在 22:32 | 排查时先对齐“哪次运行”的日志，再下结论 |

### 待验证

- 重启 dev 客户端：启动日志应出现 `commons-math3 可用`；求解应成功。
- 重启实机实例：求解成功；按 K 关闭再打开，配方应保留。
- 若仍失败，日志中会有 `[RecipeBalancer] 求解失败` 的完整堆栈（R6 新增）。

---

## R5 · 2026-10-25 · 实机闪退修复（JarJar）+ dev JEI 降到 19.39

### 实测结果

| 项 | 结果 |
|---|---|
| `gradlew test` | **BUILD SUCCESSFUL**，69 tests, 0 failures |
| 产物 | `recipebalancer-0.1.0-1.21.1.jar` **2,057,067 B**；含 `META-INF/jarjar/commons-math3-3.6.1.jar` + `metadata.json`（range `[3.6.1,4.0)`） |
| 实机部署 | 已覆盖 `1.21.1-NeoForge/mods/`（22:38），旧 jar 清理 |
| 实机崩溃 | `crash-2026-10-25_22.32.43-client.txt`：`NoClassDefFoundError: org/apache/commons/math3/optim/linear/NoFeasibleSolutionException`，位于 `Balancer.solve:47`，点「求解」触发 |
| 实例版本 | NeoForge **21.1.246**（崩溃栈确认），JEI 19.70.0.460 |
| dev JEI | **19.39.0.372**（`neoforge.mods.toml` 要求 neoforge `[21.0.118-beta,)`） |

### 根因与修复

1. **闪退**：`commons-math3` 只以 `implementation` 进入 dev classpath，**未打进模组 jar**；
   实机没有该库，`Balancer.solve` 实例化 `CommonsMath3LpSolver` 时类加载失败。
   修复：`jarJar(implementation('org.apache.commons:commons-math3:3.6.1'))` + 版本区间 `[3.6.1,4.0)`；
   产物已确认包含嵌套 jar 与 JarJar metadata。
   UI 的求解入口同时补 `catch (LinkageError)`，未来再缺依赖时显示状态栏提示而不是崩游戏。
2. **dev JEI**：19.70 / 19.42+ 的 `neoforge.mods.toml` 要求 `[21.1.238,)`，与 dev 的 21.1.209 冲突。
   降到 **19.39.0.372**（要求 `[21.0.118-beta,)`），本模组可选依赖范围改为 `[19.39.0,)`；
   实机 JEI 19.70 仍满足该范围。

### 关于“发布日期”的核实

- NeoForge 21.1.209 的 Maven `Last-Modified` = **2025-09-15**。
- blamejared 上 JEI 19.x 的 `Last-Modified` 是镜像重传时间（19.27–19.39 均为 2026-07），
  **不能当发布日期用**；版本兼容性应以 jar 内 `neoforge.mods.toml` 的 `versionRange` 为准。
- 分界：**19.39.0.372 为 `[21.0.118-beta,)`；19.42.0.389 起为 `[21.1.238,)`**。

### 本轮踩坑

| # | 现象 | 根因 | 结论 |
|---|---|---|---|
| 1 | 实机点「求解」闪退 | `implementation` 不随 jar 分发 | 第三方库必须 JarJar/内嵌；dev 能跑 ≠ 实机能跑 |
| 2 | dev 仍报 JEI 需要 21.1.238 | 19.70 的 loader 门槛高于 dev 版本 | 以 `mods.toml` 门槛选型，不能只看版本号或下载时间 |
| 3 | UI 只捕获 RuntimeException | `NoClassDefFoundError` 是 `Error` | 求解入口补 `catch (LinkageError)`，降级为状态栏提示 |

### 待验证

- 重启实机后点「求解」：预期出报告且不闪退。
- dev 客户端 JEI 19.39 的加载（本轮未成功自动启动 dev 客户端，仅核实依赖范围）。

---

## R4 · 2026-10-25 · JEI 对齐实机 + 测试后自动部署 + 去化学化措辞

### 实测结果

| 项 | 结果 |
|---|---|
| `gradlew test` | **BUILD SUCCESSFUL**，69 tests, 0 failures（JEI 19.70.0.460 解析通过） |
| 实机部署 | 自动复制到 `1.21.1-NeoForge/mods/recipebalancer-0.1.0-1.21.1.jar`（90,484 B），旧 `recipebalancer-*.jar` 已清理 |
| 产物元数据 | `jei` optional `[19.70.0,)`；`neoforge` `[21.1.209,)`；`minecraft` `[1.21.1]` |
| JEI 版本 | 当时 dev/实机统一 **19.70.0.460**（R5 因 dev NeoForge 21.1.209 的 loader 门槛改回 19.39.0.372） |

### 本轮完成

1. **JEI 版本修正**：19.44.0.405 → **19.70.0.460**（R5 又下调到 19.39.0.372）。19.44 的 `neoforge.mods.toml` 声明
   `requires neoforge 21.1.239 or above`，与锁定的 21.1.209 冲突（截图报错），不是"版本低"而是"门槛高"。
2. **依赖仓库补漏**：JEI 19.70 的传递依赖 `net.mezzdev.config:mezz_config-*` 只发布在 blamejared；
   在 `exclusiveContent` 白名单加入 `net.mezzdev.config`。
3. **测试后自动部署**：`gradle.properties` 新增 `test_instance_dir`；`test` 任务 `finalizedBy`
   `deployToTestInstance`（`Copy`），复制前删除**其它版本**的 `recipebalancer-*.jar`（当前版本由 Copy 覆盖）；实例目录不存在时跳过。
4. **去化学化**：`StoichMatrix` → **`MaterialMatrix`**；文档/注释中的"化学计量矩阵" → **"物料矩阵
   （只描述物品数量关系，与化学无关）"**；ARCHITECTURE/GOALS/代码引用同步。
5. **文档**：ARCHITECTURE §五/§九/§十一 的 JEI 版本与 API 核实对象改为 19.70；GOALS 参考实例改为
   实机 `1.21.1-NeoForge`。

### 本轮踩坑（根因与结论）

| # | 现象 | 根因 | 结论/修复 |
|---|---|---|---|
| 1 | dev 启动即报 `Mod jei requires neoforge 21.1.239 or above` | JEI 19.44 的依赖门槛高于项目锁定版本 | 对齐实机 19.70；版本号不是越大越好，要看 loader 门槛 |
| 2 | `Could not find net.mezzdev.config:mezz_config-...:0.6.6` | `exclusiveContent` 只放行 `mezz.jei`，而 19.70 起新增 `net.mezzdev.config` 传递依赖 | 白名单加入该组（仍在 blamejared） |
| 3 | `:test` 失败时 `deployToTestInstance` 仍执行 | Gradle `finalizedBy` 语义 | 符合预期，保留失败现场；要避免需改为 `mustRunAfter` + 成功判定，暂不需要 |

### 说明

- 实机实例路径：`C:\Users\JesseTech\Desktop\JesseTech\exe\waterdrop\.minecraft\versions\1.21.1-NeoForge`；
  换实例只改 `gradle.properties` 一行。
- 部署发生在测试结束；游戏正在运行时需重启后生效。
- 实机加载日志验证（是否出现 Mixin/版本错误）仍待进入游戏确认。

---

## R3 · 2026-10-25 · M2 原生主界面（手动编辑 + 求解 + 文本结果）

### 实测结果

| 项 | 结果 |
|---|---|
| `gradlew build` | **BUILD SUCCESSFUL**（含 69 项测试与 jar 打包） |
| `gradlew test` | **69 tests, 0 failures**（M1 58 + `ScenarioDraftTest` 6 + `ScenarioStoreTest` 5） |
| 产物 | jar 内含 `mc/keybind/RbKeyMappings`、`mc/screen/{BalancerScreen,RecipeEditScreen,TargetEditScreen}`、`core/model/ScenarioDraft`、`core/io/ScenarioStore`、`assets/recipebalancer/lang/{en_us,zh_cn}.json` |
| 未验证 | **游戏内冒烟**：需图形会话并进入世界后按 K。本轮未自动启动游戏窗口，B1 的人工验收步骤见下 |

### 本轮完成

1. **快捷键**（`RbKeyMappings`）：K 键（可在「按键绑定」改），主菜单不响应、已有 Screen 时不抢输入；模组总线注册、游戏总线 `ClientTickEvent.Post` 打开。
2. **主界面**（`BalancerScreen`）：左栏配方列表（点击选中 / 滚轮 / 启禁 / 删除）+ 目标产物列表（加/改/删）；右栏四个选项开关（目标=设备/配方、自动原料、自增殖种子、启动投料）+ 求解/清空；下方文本报告滚动区；底部场景名 + 保存/载入；状态栏。
3. **配方编辑屏**（`RecipeEditScreen`）：原料/产物两列动态行（物品名 + 数量 + 删除），耗时与设备类型；空行忽略、重复物品合并；保存时逐项校验。
4. **目标编辑屏**（`TargetEditScreen`）：物品名 + 每秒目标量；改名时删除旧键。
5. **场景持久化**：`core/io/ScenarioStore`（纯 Java，目录注入）+ MC 侧落到 `config/recipebalancer/scenarios/*.json`；文件名白名单（去路径分隔符、Windows 保留名、首尾点/空格）。
6. **可测的编辑状态**：`core/model/ScenarioDraft`——添加/替换/删除配方、目标增删、选项切换；删除配方时**同步平移 disabled 下标**（单测锁定）。
7. **语言文件**：键位类别与名称中英文。

### 交互约定（M2）

- K 打开，ESC 关闭；`isPauseScreen()=false`（不暂停单人世界）。
- 配方行显示 `R{idx} 设备 耗时 原料 -> 产物`，禁用配方灰显并带 `[禁用]` 前缀。
- 求解在主线程直接执行（当前规模毫秒级）；异常被界面捕获并显示，不崩游戏。
- 保存/载入使用「场景名」输入框；场景目录首次保存时自动创建。

### 本轮踩坑（根因与结论）

| # | 现象 | 根因 | 结论/修复 |
|---|---|---|---|
| 1 | 动态行增删存在 CME 风险 | 按钮回调里清空/重建控件会改写 `Screen.renderables`，而事件分发正在遍历它 | 统一 `minecraft.execute(...)` 延迟到下一 tick 重建，并检查 `minecraft.screen == this` |
| 2 | 1.21.1 API 与旧笔记不一致 | `EditBox` 构造器带 `Component`；`Button.builder`；`mouseScrolled` 为 4 参；`Screen.render` 内部先调 `renderBackground` | 动工前用 `javap` 对 `build/moddev/artifacts/neoforge-21.1.209.jar` 逐项核实，而不是照抄 1.20 教程 |
| 3 | 载入后场景名回退 `default` | `init()` 每次都用常量重建 `EditBox` | 引入 `scenarioName` 字段，`init()` 从字段取值 |
| 4 | 编辑子屏返回后旧解可能越界 | 旧 `SolveResult` 的速率数组长度与新配方数可能不一致 | 打开编辑器即清空 `result`；`rebuildReport()` 再做长度防御 |

### 下一步（M3）

主界面页签化/三栏细化、600ms 防抖自动求解、结果表与筛选、场景列表 UI、导出自包含 HTML（C5）。
`core/layout` 与 `GraphView` 仍属 M5，不在本轮。

---

## R2 · 2026-10-25 · 渲染主线切换（CEF → GuiGraphics）+ M1 求解核心

### 决策

| 项 | 结论 | 理由 |
|---|---|---|
| 渲染主线 | **原生 `GuiGraphics`**（静态流程图 + 原生表单），M2/M3/M5 交付 | 需求只需"游戏内看流程图 + 表单录入"；CEF 代价是每实例 ~100MB natives 前置 + ~300MB 内存 + 子进程/IME 风险 |
| JCEF | **备选·暂缓**，`cef/` 设计与 S-01~S-03 冻结保留 | 恢复触发条件：需要拖拽/缩放/富文本、需要 JS 生态、明确要求 HTML 保真 |
| HTML 通道 | 保留**导出自包含 HTML**（内嵌 vis-network） | 不占运行期依赖，外部浏览器可看 |
| 交付顺序 | M2 主界面先行，M3 完善，M5 流程图 | 主界面同时是 C3 的保底交付物 |

### 文档改动

- `ARCHITECTURE.md`：新增决策记录；§一 分层图改为 `mc/ui` + `core/layout`；契约 C4/C6 重写、新增 C8；
  §二 重写为"原生主线 + JCEF 备选"（保留冻结的 JCEF 设计、恢复条件、natives 分发源码事实）；
  §3.2/3.3 映射更新；§4.1–4.4 接口草案改为实际签名；§五 ghost 靶区改原生；§六 标注暂缓；
  §八 新增 O-5；§九 新增 R-13~R-16 / S-06，CEF 风险与 spike 标 ⏸；§十 目录更新；§十一 补环境事实。
- `GOALS.md`：§一 目标改为原生渲染；§三 C1–C4 重写、新增 C5（导出 HTML）；非目标补一条；里程碑 M2/M3/M5 重排。
- `README.md`：描述与目录说明同步。

### M1 实测结果

| 项 | 结果 |
|---|---|
| 上游 Python 基线 | `.venv` 跑 `test_golden.py` + `test_invariants.py`：**36 passed**（Python 3.10.11 / numpy 2.2.6 / scipy 1.15.3 / networkx 3.4.2） |
| `gradlew test` | **58 tests, 0 failures**（Golden 9 / Invariants 19 / Deficit+Bootstrap 15 / Io+Report 15） |
| 金标准对拍 | 9/9 场景 `success`、`recipe_rates`、`external_inputs`/`net_flow`/`final_outputs`/`equipment_usage`、原料/种子集合全过（abs **1e-9**） |
| `total_machine_count` | 7/9 精确；`mek_fission` 57→**58**、`uu` 76→**77**（±1，原因见踩坑 #2） |
| A2 反向互导 | Java `dump` 的 9 个场景由上游 `load_scenario` + `solve_production` 读回：rates 与 golden 差 **< 1e-12**，外部输入集合一致，ALL_OK |
| `gradlew build` | **BUILD SUCCESSFUL**（jar 正常产出） |
| 新增依赖 | gson 2.10.1（`compileOnly` + `testImplementation`）、JUnit 5.10.2（首次联网下载，已入缓存） |

### M1 本轮完成

1. **渲染主线文档切换**（见上）：ARCHITECTURE / GOALS / README 全部改为原生 `GuiGraphics`，JCEF 转备选。
2. **`core/` 求解核心**（纯 Java，零 MC 依赖）：
   - `model/`：`Recipe`、`Scenario`、`SolveOptions`、`SolveResult`、`Objective`、`Constants`（C7 逐字面量）
   - `matrix/MaterialMatrix`（R4 前名 `StoichMatrix`）、`graph/DirectedGraph`（Tarjan SCC + networkx 同序拓扑分代）
   - `detect/`：`RawDetector`、`SeedDetector`、`CyclicDeficitDetector`（SCC + 子 LP）
   - `solve/`：`LpSolver` 接口、`CommonsMath3LpSolver`、`Balancer`（两遍收集 + 编排，P0-A/B/C 忠实保留）
   - `bootstrap/PerfectCycleBootstrapper`（外部输入移除 → 最小反馈顶点集 → 1 秒缺口 → 整批取整）
   - `io/ScenarioJson`（Gson，schema 与 `io.py` 同构 + 校验规则一致）、`report/TextReporter`
3. **测试夹具**：`tools/gen_test_fixtures.py` 从上游 `conftest.py` 导出 9 个场景 JSON（浮点逐位保真）+ 复制 9 个 golden；`src/test/java` 58 项测试。
4. **S-04 结论**：commons-math3 单纯形（epsilon=1e-10）在 9 个场景上**数值可对拍**——速率/物流/设备并行量均在 1e-9 内，结构集合完全一致；唯一偏差是 `ceil` 边界（下一条）。

### 本轮踩坑（根因与结论）

| # | 现象 | 根因 | 结论/修复 |
|---|---|---|---|
| 1 | `SimplexSolver.optimize(f, constraints, goal, true)` 编译失败 | commons-math3 3.6.1 只提供 `optimize(OptimizationData...)`，没有旧版 4 参重载 | 改 `optimize(f, new LinearConstraintSet(list), GoalType.MINIMIZE, new NonNegativeConstraint(true))` |
| 2 | `total_machine_count` 差 1（mek 58 vs 57，uu 77 vs 76） | CM3 速率比精确值高几个 ulp（如 `2.0000000000000004`），×耗时后 `ceil` 放大成 +1；而 HiGHS 自身也会产生 `35.00000000000003`→`ceil` 36。两个方向的噪声无法同时用"吸附"消除 | **忠实保留 `ceil`**，不放宽速率/物流断言；`total_machine_count` 允许 ±1（仅此项），原因记入 ARCHITECTURE §3.4 |
| 3 | 测试输入浮点不能手抄 | `20/9`、`1/6*9.6`、`ceil(800/65)` 的 double 与十进制字面量差 1 ulp，会改变 LP 顶点 | `tools/gen_test_fixtures.py` 用上游 venv 生成 JSON（repr 保真），并回读断言逐位相等 |
| 4 | bootstrap 缺口累加顺序依赖拓扑序 | networkx **3.4.2** 的 `topological_sort` 已改为 `topological_generations`（分代 BFS），不是旧版 LIFO Kahn | 按 3.4.2 源码复刻：分代 + 节点/邻接插入序；`DirectedGraph.topologicalGenerations()` |
| 5 | 代理端口再次切换 | 代理软件可换端口；全局 `~/.gradle/gradle.properties` 当前为 7993，代理关闭时 `Connection refused` | 构建时 `-Dhttp(s).proxyPort=<实测端口>` 覆盖；实测 Maven Central 可直连，但 MDG 仍需代理 |

---

## R1 · 2026-10-25 · 环境初始化 + 架构设计（历史）

### 实测结果

| 项 | 结果 |
|---|---|
| `gradlew build` | **BUILD SUCCESSFUL**（12s，全缓存命中，JEI/commons-math3 走 7993 代理下载） |
| 产物 | `build/libs/recipebalancer-0.1.0-1.21.1.jar`：主类 class + mods.toml，占位符替换正确，中文 UTF-8 无损 |
| 元数据 | modId=recipebalancer / neoforge `[21.1.209,)` / minecraft `[1.21.1]` / jei `[19.44.0,)` optional |

### 本轮完成

1. **环境初始化**（`D:\dev\RecipeBalancer`，与 logiscope 完全独立，仅复制骨架）：
   gradlew + wrapper 9.2.1（腾讯镜像）、build.gradle（MDG 2.0.147 / NeoForge 21.1.209 /
   Java 21 锁定 / UTF-8 元数据陷阱规避）、`neoforge.mods.toml` 模板、最小主类骨架
   （`RecipeBalancerMod`，无业务逻辑）、.gitignore。
2. **依赖落地**：JEI 19.44.0.405（compileOnly common-api + neoforge-api，runtimeOnly 完整
   jar，mods.toml optional）、commons-math3 3.6.1。JCEF（`me.friwi:jcefmaven:152.0.6`）
   暂不引入，坐标注释备查。
3. **架构与文档**：`docs/GOALS.md`（验收 A1–D3 + 里程碑 M1–M5）、
   `docs/ARCHITECTURE.md`（分层 C1–C7 / 渲染选型 / 移植映射 / LP 数学模型 / 接口草案 /
   JS 桥协议 / 风险 R-01~R-12 / Spikes S-01~S-05 / 开放问题 O-1~O-4）。

### 本轮踩坑（根因与结论）

| # | 现象 | 根因 | 结论/修复 |
|---|---|---|---|
| 1 | JEI POM 404 | JEI 1.21.1 的 groupId 是 **`mezz.jei`**，老文档的 `mezz` 布局已废弃（blamejared 逐目录核实：`/mezz/jei/jei-1.21.1-common-api/…`） | 改 groupId；同时用 `exclusiveContent` 把 mezz.jei 钉死 blamejared |
| 2 | 第三方依赖解析报 neoforged 仓库 `502 Bad Gateway` | MDG 自动仓库 maven.neoforged.net 经不可用代理通道时返回 502；Gradle 视 502 为仓库错误（非 404"没有"），炸掉整个解析 | 代理通道恢复后自然解决；JEI 已通过独占过滤免疫此问题 |
| 3 | `Could not find org.ow2.asm:asm-commons` | 试过的两层过滤均被证伪：① `exclusiveContent(central)` 是**双向独占**——central 从此只提供 filter 列的组，asm 被 central 拒收；② neoforged 仓库 include 白名单切断 neoform 管线对其镜像组（asm 等）的解析 | **全部回退**：只保留 blamejared 的 mezz.jei 独占，其余默认解析顺序（neoforged 404 → 跳过 → central）。教训已写入 build.gradle 注释与 ARCHITECTURE.md |
| 4 | `Connect to 127.0.0.1:7890 Connection refused` | 全局 `~/.gradle/gradle.properties` 的代理端口 7890 过时——实测本机代理软件监听 **7993**（端口随代理软件切换变化） | 构建时命令行覆盖 `-Dhttp(s).proxyPort=7993`（不动全局配置）；事实记入 ARCHITECTURE.md §十一。用户可考虑把全局配置端口改回 7993 或改用动态探测 |
| 5 | Groovy `Could not find method content()` | `configureEach {}` 隐式闭包里 `content {}` 被解析到 Project（委托歧义） | 该代码块已随 #3 回退删除；如再用，须显式参数 `configureEach { repo -> repo.content {...} }` |

### 下一步（M1 入口）

按 GOALS.md 里程碑：M1 求解核心移植（`core/` 纯 Java + Python 金标准 9 场景对拍）。
动工前先做 Spikes S-04（commons-math3 对 ebf_cycle 场景快速对拍）与 S-05（JEI API 逐行核实）。
**待用户拍板**：ARCHITECTURE.md §八 开放问题 O-1~O-4（P0 语义修复与否 / ItemKey 组件 /
L4 逐位恒等 / JEI 写入交互）与渲染主线（JCEF）确认。
→ **R2 已拍板**：渲染主线改为原生 `GuiGraphics`，JCEF 暂缓为备选；O-1~O-4 未变，仍按默认值执行。
