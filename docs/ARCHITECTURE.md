# RecipeBalancer · 架构与接口文档

本文档描述四件事：**分层与层间契约**、**渲染层选型结论**、**核心接口草案**、**风险与核实清单**。
实施顺序按 [GOALS.md](GOALS.md) 里程碑 M1→M5；接口草案在开发中可细化，但**契约（C1–C8）不可破坏**。

> 上游参考：Python 项目《配方树自动配平》（`C:\Users\JesseTech\Desktop\JesseTech\code\py\配方树自动配平`）。
> 本文档的算法语义均以其 `ANALYSIS.md` / 源码为准；已知语义问题（P0-A/B/C/D）**忠实移植**，
> 修复须单独立项并经确认（见 §8 开放问题）。

> **决策记录**
> - 2026-10-25 R1：渲染主线定为 JCEF 离屏合成；原生界面仅作降级屏。
> - 2026-10-25 R2：**渲染主线改为原生 `GuiGraphics`（静态流程图 + 原生表单）；JCEF 降为备选、暂缓实施**。
>   理由与恢复条件见 §二；`cef/` 设计原文保留（冻结），恢复时直接沿用。

---

## 一、总体分层

```
┌────────────────────────────────────────────────────────────────────────┐
│ MC 集成层  mc/                                                            │
│   keybind/RbKeyMappings      快捷键 → 打开/关闭界面                     │
│   screen/BalancerScreen      主界面：编辑 / 结果 / 流程图三页签          │
│   screen/InventoryPickerScreen 背包拾取小屏（点选物品标记）             │
│   ui/widget/*                数值框 / 物品槽 / 滚动列表 / 按钮条        │
│   ui/graph/GraphView         流程图绘制（GuiGraphics：节点/边/图标）    │
│   itemmap/ItemStackKeyMapper  ItemStack/FluidStack ↔ ItemKey(String)   │
│   jei/JeiBridge              JEI 软依赖隔离层（装了才激活）            │
├────────────────────────────────────────────────────────────────────────┤
│ 渲染层  cef/（备选·暂缓，不在主链路；恢复条件见 §二）                     │
│   CefHost / RbRenderHandler / FrameTexture / InputForwarder / JsBridge │
├────────────────────────────────────────────────────────────────────────┤
│ 前端资产  resources/web/（仅 HTML 导出模板：自包含 vis-network）          │
├────────────────────────────────────────────────────────────────────────┤
│ 求解核心  core/（★ 纯 Java，零 MC/JEI 依赖，M1 先行）                    │
│   model/    Recipe, Scenario, SolveOptions, SolveResult                │
│   matrix/   MaterialMatrix（装配，物品索引确定性排序）                 │
│   detect/   RawDetector, SeedDetector, CyclicDeficitDetector(+Tarjan)  │
│   solve/    LpSolver(接口) → CommonsMath3LpSolver；Balancer(编排)       │
│   bootstrap/PerfectCycleBootstrapper（最小反馈顶点集→启动投料）        │
│   layout/   LayeredLayout（SCC 凝聚 → 分层 → 交叉最少化，M5）          │
│   io/       ScenarioJson（Gson；schema 与 Python io.py 同构）          │
│   report/   TextReporter（文本报告，界面与导出复用）                   │
├────────────────────────────────────────────────────────────────────────┤
│ 数据层  config/recipebalancer/scenarios/*.json（场景持久化，跨版本兼容）│
└────────────────────────────────────────────────────────────────────────┘
```

### 层间契约（改代码必须守住）

| 契约 | 内容 | 违反的后果 |
|---|---|---|
| **C1** | `core/` 只允许 import `java.*` / `javax.*` / gson / commons-math3 —— **零 MC 依赖** | 金标准测试必须能脱离游戏在纯 JVM 跑；引入 MC 类会把求解测试绑到游戏进程 |
| **C2** | 物品身份在 `core/` 中是**不透明字符串**（`ItemKey`，如 `item\|minecraft:iron_ingot`）；MC 侧负责 `ItemStack↔String` 映射 | core 被拉进 MC 类型系统；场景 JSON 无法与 Python 版互导 |
| **C3** | 求解唯一入口 `Balancer.solve(Scenario, SolveOptions)`（对应 Python `solve_production`）；UI 不得旁路装配 LP | 语义分叉，对拍失效 |
| **C4** | 可视化渲染异常/场景过大 → 退文本报告 + 导出 HTML；编辑/求解/持久化不依赖渲染层 | 渲染层故障导致模组不可用 |
| **C5** | JEI 未安装时全部功能照常（仅缺拖拽与配方写入两条录入通道） | 软依赖变硬依赖 |
| **C6** | `ui/` 只做绘制与事件分发，**不含求解与布局逻辑**（同 tkinter 版"GUI 无逻辑"）；所有计算在 `core/` | 两套实现结果不一致，违背数据保真 |
| **C7** | `core/` 数值常量与 Python `models.py` 逐一对应（`EXTERNAL_PENALTY=1e6`、`EPS_RAW=1e-12`、`EPS_RATE=1e-10`、`EPS_DEFICIT=1e-9`、`EPS_FLOW=1e-8`、`MAX_EXACT_FVS=12`），禁止"顺手优化" | 任何常量偏移都会使金标准对拍失真 |
| **C8** | `core/layout/` 只允许 `java.*`（与 C1 同级）：布局结果可由纯 JVM 单测断言 | 布局绑死 MC 类型后无法脱离游戏验证 |

---

## 二、渲染层选型（结论：原生 `GuiGraphics` 主线；JCEF 备选·暂缓）

需求原文："可视化部分也要在游戏内进行渲染，达到 html 的效果，这可能需要一个高效的
渲染器，而不能使用原生渲染器。"——即**游戏内**渲染（非外部浏览器窗口）。

**2026-10-25 R2 决策**：先按原生 `GuiGraphics` 交付（静态流程图 + 原生表单），
JCEF 降为备选并暂缓。判据：当前需求是"游戏内看见流程图 + 表单录入"，静态分层图即可满足；
而 CEF 的代价是每实例 ~100MB natives 前置 + ~300MB 内存 + 子进程与 IME 风险（R-01/R-04/R-05）。

| 方案 | HTML/CSS/JS 保真 | 体积/依赖 | 主要风险 | 结论 |
|---|---|---|---|---|
| **原生 `GuiGraphics`（vanilla Screen）** | 非 HTML；位图字体 + 矩形/线段，流程图语义完整，无 CSS/JS 生态 | **0 依赖、0 下载** | 布局可读性、边交叉、字体缩放（R-13~R-16） | ✅ **主线（M2/M5）** |
| JCEF（Chromium Embedded）离屏模式 | **完整 Chromium**（可用 vis-network/ECharts 等全部 JS 生态） | natives ≈100MB（首启下载到实例缓存目录；jar 内只带 ~2MB 绑定层） | 子进程模型、事件转发、IME（R-02/03/04） | ⏸ **备选·暂缓**（恢复条件见下） |
| Ultralight Java 绑定 | CSS 子集 + 自有 JS（无完整生态） | ~40MB | 绑定维护停滞、许可变化 | ❌ 否决 |
| JavaFX WebView（离屏快照） | WebKit | +整个 JavaFX runtime | 快照每帧太慢，无法 60fps 合成 | ❌ 否决 |
| 自研 UI 框架（immediate-mode/HTML 子集） | 非 HTML（仿制） | 0 | 开发量巨大；无法复用现有 HTML 资产；"HTML 效果"是仿的 | ❌ 否决；仅其**事件模型**可参考 |

### 原生渲染的保真度边界（明确接受）

- **无 CSS / 无 JS 生态**：流程图用固定模板绘制，只保证**配色语义、方向、数量标签**与 pyvis 版一致，不追求观感一致。
- **边是 1px 线段**：长边可能穿越节点（R-16）；用分层顺序 + 段间绕行偏移缓解，不做曲线。
- **文本是 MC 位图字体**：CJK 由字体 provider 提供；缩放时可能发虚（R-15），标签统一在 1.0 缩放下绘制。
- **无 IME 问题**：输入走原生 `EditBox`，R-04 不适用。
- **规模上限**：建议 ≤200 节点；超出时退文本报告 + 导出 HTML（C4）。

### 渲染管线（原生，M5）

```
SolveResult ──> core/layout/LayeredLayout ──> GraphLayout（纯数据：节点矩形 + 边折线 + 标签）
                                                       │
MC 渲染线程（Screen.render）                            ▼
  ui/graph/GraphView：
    pose 平移/缩放 → enableScissor(可视区)
    → fill 节点底/边框 → drawString 标签 → RenderType.lines 画边
    → renderItem 画物品图标（不走 PNG/dataUri）
```

- **节点配色**（ARGB 常量，语义锁定，与 pyvis 版一致）：
  绿 `#2ECC71`=外部输入、蓝 `#3498DB`=中间产物、黄 `#F1C40F`=副产物、
  红 `#E74C3C`=启动库存、紫 `#9B59B6`=目标、橙 `#E67E22`=配方。
- **布局**：SCC 凝聚 → 分层（最长路径分层，长边跨层拆虚拟节点）→ 层内重心排序减少交叉 → 坐标归一化。
  纯数据输出，可在无 MC 的 JUnit 中断言（C8）。
- **标注**：节点显示物品名 + 净流量/速率（`%.3f`）；启动库存节点追加「投料 x」。

### JCEF 备选（暂缓）：恢复触发条件与步骤

**恢复触发条件（任一成立）**：

1. 静态图无法表达需求（需要拖拽节点、缩放细节、富文本/Hover 卡片）；
2. 需要 vis-network / ECharts 等 JS 生态（大型图自动布局、动画）；
3. 明确要求 HTML/CSS 保真度。

**恢复步骤**：① 引入 `me.friwi:jcefmaven:152.0.6`；② 选 natives 分发形态
（平台伴生 jar / 整合包 `overrides` 预置 / 在线回退，见 §九 R-01）；③ 执行 S-01~S-03；
④ 恢复 `cef/` 与 §六协议。JCEF 设计原文保留在下方（冻结，恢复时按此实施）。

### 附：JCEF 备选设计（冻结，暂缓实施）

#### 帧合成路径

```
CEF renderer/gpu 子进程（Chromium 排版/绘制）
   └─ onPaint(BGRA int[], w, h)  ──CEF UI 线程回调（RbRenderHandler 自实现，不经 JOGL）
        └─ FrameQueue（容量 1 的交换槽：只保留最新帧，天然丢帧不堆积）
MC 渲染线程（Screen.render 内）
   ├─ CefApp.doMessageLoopWork()  ← 每帧驱动一次消息泵（JCEF 离屏模式要求）
   ├─ 取最新帧 → NativeImage 整块写入 → DynamicTexture 上传
   │    ★ BGRA→RGBA：小端平台上 BGRA 字节序读出的 int == NativeImage 的 RGBA
   │      表示（A<<24 \| B<<16 \| G<<8 \| R）——**预期免转换直拷**，spike S-03 核实；
   │      不成立则整行 byte-swap（预算 ~1–2ms @1080p，可接受）
   └─ guiGraphics.blit(资源位置 recipebalancer:cef_frame, 全屏区域)
输入反向路径：
Screen.mouse*/key* / charTyped → InputForwarder（坐标×guiScale 换算）
   → browser.sendMouseEvent / sendKeyEvent
```

尺寸约定：CEF 浏览器按**物理像素**建（窗口宽高 × 1，不除 guiScale），CSS 侧用
`devicePixelRatio` 对齐；鼠标坐标换算在 Java 侧完成。1920×1080×60fps 的上传带宽
≈ 500MB/s 内存复制，桌面平台无压力；30fps 下限作为验收底线。

#### 生命周期要点

- **natives 安装**：`CefAppBuilder.installDir` = 游戏实例目录下 `recipebalancer/jcef/`
  （放游戏目录而非 config，~100MB 不污染配置区）；用户可禁用（配置项 `useCef=false`）→ 走 C4。
- **natives 分发（2026-10-25 源码核实，恢复时按此实施）**：`install()` 先查 classpath 上是否有
  `me.friwi:jcef-natives-<platform>` 构件——**有则不访问网络**；`installDir/install.lock` 命中则跳过
  下载/解压；`setSkipInstallation(true)` 可完全自管（自行解压到 `installDir`）。默认镜像顺序
  GitHub Releases → Maven Central。三种分发形态：平台伴生 jar（`mods/`）/ 整合包 `overrides`
  预置 / 在线回退。下载与解压无哈希校验，自管时须自行校验 SHA-256。
- **进程模型**：browser 进程即 MC 进程；renderer/gpu 崩溃经 `onBrowserClosed` 回调 →
  自动切降级屏并提示，MC 不崩。
- **资源加载**：注册自定义 scheme `rb://`，`rb://app/index.html` 直接读 jar 内
  `resources/web/`（零解压、零临时文件、模组升级自动生效）。
- **线程**：所有 JS 桥回调 `post` 回 MC 主线程再触碰 MC 数据（CefMessageRouter 回调线程 ≠ MC 线程）。

#### 接口草案（冻结）

```java
package com.jessetech.recipebalancer.cef;

public final class CefHost {
    public static boolean tryInit();       // natives 缺失/失败 → false → 降级
    public static void shutdown();         // 关游戏时 CefApp.shutdown
    public static CefBrowser browser();    // 单例浏览器（面板即网页）
}

/** 自实现离屏 RenderHandler（绕开 jcefmaven 的 JOGL/Swing OSR 路径）。 */
final class RbRenderHandler implements CefRenderHandler {
    // getRect() → 物理像素尺寸；onPaint(BGRA) → FrameQueue.offer(帧)
}

/** 帧纹理：NativeImage ← BGRA int[]；注册 recipebalancer:cef_frame 到 TextureManager。 */
final class FrameTexture { void update(int[] bgra, int w, int h); }

/** op 分发见 §6；所有 MC 数据操作先 post 到主线程。 */
public final class JsBridge implements CefMessageRouterHandler {}
```

---

## 三、求解核心：Python → Java 移植映射

### 3.1 数学模型（与 Python 逐式对应）

**主 LP**（变量 `z = [x ∈ R^m 配方速率, r ∈ R^k 外部输入]`）：

```
min  c^T z
s.t. -A·x - E·r ≤ s - b        （每物品 i：净产出 + 外部输入 ≥ 目标 − 库存）
      x_j = 0（j ∈ 禁用集），其余 x_j ≥ 0，r ≥ 0

c = [ t_j (objective=machine) 或 1 (objective=recipes) ；  M=10^6（外部输入惩罚）]
A[i,j] = 产量_ij − 消耗量_ij   （物料矩阵：只描述物品数量关系，与化学无关；物品按名称确定性排序）
E = 白名单物品的单位选择矩阵（外部输入白名单 = 用户原料 ∪ 自动原料 ∪ 循环亏损 − 目标产物）
b = 目标向量；  s = 库存向量（自增殖种子 ∪ 用户库存）
```

**循环亏损子 LP**（对每个环 SCC，变量 `[x, d]`）：

```
min 1^T d
s.t. A_sub·x + d = 0
     1^T x = 1          （排除全零平凡解）
     x ≥ 0, d ≥ 0
     d_i = 0（i ∉ 白名单）
```
`d_i > eps` ⇒ 物品 i 在该环内必然亏损。

**完美循环启动投料**（`bootstrap.py`）：活跃配方（rate>EPS_RATE）→ 移除外部输入 →
SCC 分解 → 每 SCC **最小反馈顶点集**（|SCC|≤12 精确枚举，按（节点数, 总投料量, 字典序）取优；
否则贪心回退）→ 按稳态速率跑 1 秒统计缺口 → 向上取整到整批投料。

### 3.2 模块映射表

| Python（recipe_balancer/） | Java（core/） | 移植要点 |
|---|---|---|
| `models.py`（常量 + RecipeSolution） | `model/` | 常量**逐字面量**移植（见 C7）；Solution→`SolveResult` record |
| `matrix.py` | `matrix/MaterialMatrix` | 不引矩阵库：`double[][]` 或列稀疏数组即可（问题规模 ≤ 数百×数百） |
| `detection.py`（原料/种子/亏损） | `detect/` | networkx SCC → **自写 Tarjan**（约 80 行，避免引 JGraphT）；子 LP 用 `LpSolver` |
| `solver.py`（主 LP + 编排） | `solve/Balancer` + `solve/CommonsMath3LpSolver` | `scipy.linprog(method="highs")` → commons-math3 `SimplexSolver`（见 3.3） |
| `bootstrap.py` | `bootstrap/PerfectCycleBootstrapper` | 组合枚举 `itertools.combinations` → Java 组合迭代器；行为逐分支对齐 |
| `io.py`（场景 JSON） | `io/ScenarioJson` | **schema 完全同构**（`recipes/sourceList/productList/time/equType` + `options`），键名一字不改 → 与 Python 互导 |
| （新增）场景仓库 | `io/ScenarioStore` | 纯 Java、目录注入（MC 侧 `config/recipebalancer/scenarios`）；文件名白名单防目录穿越；可脱离游戏单测 |
| `report.py` | `report/TextReporter` | 文本报告移植（主界面与导出复用） |
| `visualize.py`（pyvis） | **不移植图形库** → `core/layout/LayeredLayout` + `mc/ui/graph/GraphView` | 配色语义对齐；HTML 导出单独走模板（C5） |
| `gui.py`（tkinter） | **不移植** → `mc/screen/BalancerScreen` + `mc/ui/widget/*` | 交互约定（600ms 防抖、单位归一化、回车添加）在原生界面复刻 |

### 3.3 LP 后端：commons-math3（备选 ojAlgo）

- **选型**：`org.apache.commons:commons-math3:3.6.1` 的
  `SimplexSolver`（成熟、纯 Java、零 native、API 稳定）。本问题规模（配方数十~数百）
  求解毫秒级，性能不构成瓶颈。
- scipy 映射：`A_ub/b_ub`（≤ 约束）→ `LinearConstraint(LEQ)`；等式约束 → `EQ`；
  **变量只有下界 0**（`restrictToNonNegative=true`），禁用配方用**删列**实现
  `x_j = 0`——commons-math3 无任意上下界 API，不做 bounds 适配层；big-M 直接写进目标向量（语义同 P0-C）。
- **分发形态（R-06 已定）**：commons-math3 **直接把 class 解包进模组 jar**
  （Gradle `shaded` 配置 + `jar { from(zipTree) }`），不走 JarJar。
  2026-10-25 实测：JarJar 版在实机/IDE 仍报 `NoClassDefFoundError`；改为解包后，
  **仅用模组 jar 启动独立 Java 进程即可求解**（无任何外部 math3）。
- **多解问题（R-10）**：LP 最优解可退化不唯一，CM3 与 HiGHS 可能给出不同顶点。
  对拍按 §7 分层策略执行，**不追求逐位速率恒等**；差异必须记录。
- **S-04 实测（2026-10-25）**：`epsilon=1e-10`、`maxIterations=200000`、变量非负 + 删列后，
  9 个场景的速率/物流/设备并行量均在 **1e-9** 内，结构集合（外部输入、亏损、启动投料）完全一致。
  无需更换后端（ojAlgo / HiGHS-JNI 不启用）。

### 3.4 数据保真：确定性来源清单

| 环节 | Python | Java | 一致性 |
|---|---|---|---|
| 物品排序 | `sorted(all_items)`（code point 序） | `String.compareTo`（UTF-16 code unit 序） | registry id 全 ASCII，两者等价；core 测试断言排序稳定 |
| 浮点 | float64 | `double` | 位相同 |
| 矩阵装配 | dict 迭代（量级累加） | 同一配方内先加产量再减消耗，顺序一致 | 逐位 |
| EPS 常量 | 6 个字面量 | 同值移植（C7） | 逐位 |
| LP 顶点 | HiGHS | CM3 simplex | **不保证逐位**（多解），按 §7 分层 |
| 拓扑序（bootstrap） | networkx 3.4.2 `topological_generations` | `DirectedGraph.topologicalGenerations` 同序复刻 | 分代 + 插入序一致 |
| `total_machine_count` | `Σ ceil(usage)` | 同式，但速率差几个 ulp 会在整数边界翻 1 | **允许 ±1**（唯一放宽项）；逐设备 usage 仍按 1e-9 对拍 |

---

## 四、核心接口草案

> 草案：开发阶段可细化签名/包内组织，但入口方法、参数语义、JSON 字段名是契约的一部分。
> 所有接口位于 `com.jessetech.recipebalancer.*`。

### 4.1 求解核心（core/，M1）

```java
package com.jessetech.recipebalancer.core.model;

/**
 * 与 Python recipe dict 逐字段对应（键名即 JSON 键名）；time 单位 = 秒。
 * 物品身份是**不透明字符串**（契约 C2）：MC 层负责 "item|<registry_id>" 映射，core 只做排序/比较。
 */
public record Recipe(Map<String, Double> sourceList,
                     Map<String, Double> productList,
                     double time,
                     String equType) {
    public boolean produces(String item);
    public boolean consumes(String item);
}

public record SolveOptions(Set<String> rawMaterials,
                           Map<String, Double> initialInventory,
                           Set<Integer> disabledRecipes,
                           Set<String> allowedExternalCycInputs,   // 空 = 不限制
                           Objective objective,                    // MACHINE | RECIPES
                           boolean autoDetectRaw,
                           boolean autoDetectSeed,
                           boolean autoBootstrap) {
    public static SolveOptions defaults();   // 与 Python 默认参数一致
}

/** 场景 = Python 场景 JSON 的内存形态（io.py schema 同构）。 */
public record Scenario(List<Recipe> recipes,
                       Map<String, Double> targetProducts,
                       SolveOptions options) {}

/** 与 RecipeSolution 逐字段对应（字段顺序保持 Python asdict 顺序）。 */
public record SolveResult(List<Double> recipeRates,
                          Map<String, Double> netFlow,
                          Map<String, Double> externalInputs,
                          Map<String, Double> finalOutputs,
                          Map<String, Double> equipmentUsage,
                          int totalMachineCount,
                          Set<String> inferredRawMaterials,
                          Map<String, Double> inferredInitialInventory,
                          Map<String, Double> bootstrapInventory,
                          boolean success,
                          String message) {}
```

```java
package com.jessetech.recipebalancer.core.solve;

/** LP 后端抽象：为替换求解器（ojAlgo / 未来 HiGHS-JNI）留缝。所有变量假定 >= 0。 */
public interface LpSolver {
    LpResult minimize(double[] c, List<LpConstraint> constraints);

    enum Relation { LE, GE, EQ }
    record LpConstraint(double[] coefficients, Relation relation, double rhs) {}
    record LpResult(Status status, double[] point, String message) {
        public enum Status { OPTIMAL, INFEASIBLE, UNBOUNDED, FAILED }
    }
}

/** 求解唯一入口（契约 C3），对应 solve_production 的两遍收集 + 检测器编排。 */
public final class Balancer {
    public static SolveResult solve(Scenario scenario);
}
```

```java
package com.jessetech.recipebalancer.core.io;

/** 场景 JSON 读写；字段名与 Python io.py 一字不差（跨项目互导的根基）。 */
public final class ScenarioJson {
    public static Scenario load(String json);              // 严格：recipes/targets 均非空
    public static Scenario load(java.nio.file.Path path);
    /** 宽松：允许空 recipes/targets（autosave 草稿）；已存在的配方仍逐条校验。 */
    public static Scenario loadDraft(String json);
    public static Scenario loadDraft(java.nio.file.Path path);
    public static String dump(Scenario scenario);
}

/** 场景文件仓库（M2）：目录注入，文件名清洗，供界面保存/载入。 */
public final class ScenarioStore {
    public ScenarioStore(Path directory);
    public Path save(String name, Scenario scenario);
    public Scenario load(String name);          // 严格（手动载入）
    public Scenario loadDraft(String name);     // 宽松（autosave，允许空目标/配方）
    public List<String> names();
    public boolean delete(String name);
    public static String sanitize(String name);
}
```

```java
package com.jessetech.recipebalancer.core.model;

/** 界面唯一可变状态（契约 C6）；删除配方时同步平移 disabled 下标，可纯 JVM 单测。 */
public final class ScenarioDraft {
    public static ScenarioDraft from(Scenario scenario);
    public void addRecipe(Recipe recipe);
    public void removeRecipe(int index);
    public void toggleRecipeEnabled(int index);
    public void putTarget(String item, double amount);
    public Scenario toScenario();
}
```

### 4.2 LP 后端实现（core/solve/，M1）

```java
/** scipy.linprog(method="highs") → commons-math3 SimplexSolver 的适配。 */
public final class CommonsMath3LpSolver implements LpSolver {
    public CommonsMath3LpSolver();                       // 默认 epsilon / maxIterations
    public CommonsMath3LpSolver(double epsilon, int maxIterations);
}
```

### 4.3 MC 集成（mc/，M2+）

```java
package com.jessetech.recipebalancer.mc.keybind;
public final class RbKeyMappings {
    /** 注册 openBalancer（默认键位进键位设置菜单），模组总线上注册。 */
    public static void register(IEventBus modBus);
    /** Screen 开着时 vanilla KeyMapping 不走全局输入 → 拦 ScreenEvent。 */
    public static void onScreenKeyPressed(ScreenEvent.KeyPressed.Pre e);
}

package com.jessetech.recipebalancer.mc.screen;
/** 主界面（原生）：配方/目标编辑 + 求解 + 文本结果 + 场景保存载入。
 *  每次打开用 loadDraft 读取 `scenarios/autosave.json`（允许未填目标/配方），
 *  关闭/修改时自动写回（记住上次输入）。 */
public final class BalancerScreen extends Screen { /* M2 */ }
/** 配方编辑：原料/产物两列动态行（手动输入）。 */
public final class RecipeEditScreen extends Screen { /* M2 */ }
/** 目标产物编辑。 */
public final class TargetEditScreen extends Screen { /* M2 */ }
/** 背包拾取小屏（点选物品标记）。 */
public final class InventoryPickerScreen extends Screen { /* M4 */ }

package com.jessetech.recipebalancer.mc.itemmap;
public final class ItemStackKeyMapper {
    public static String of(ItemStack stack);        // v1: "item|<registry_id>"；组件指纹见 O-2
    public static ItemStack toStack(String itemKey);
}
```

### 4.4 渲染层（mc/ui/ + core/layout/，M5）

```java
package com.jessetech.recipebalancer.core.layout;

/** 纯数据布局输出：节点矩形 + 边折线 + 标签；零 MC 依赖（契约 C8）。 */
public record GraphLayout(List<Node> nodes, List<Edge> edges) {
    public record Node(String itemKey, double x, double y, double w, double h,
                       NodeKind kind, String label) {}
    public record Edge(double[] xs, double[] ys) {}
    public enum NodeKind { EXTERNAL, INTERMEDIATE, BYPRODUCT, BOOTSTRAP, TARGET, RECIPE }
}

public interface LayoutEngine {
    GraphLayout layout(SolveResult result, List<Recipe> recipes,
                       Map<String, Double> targets);
}
```

```java
package com.jessetech.recipebalancer.mc.ui.graph;

/** GuiGraphics 绘制：pose 平移/缩放 + scissor 裁剪；不含任何计算（契约 C6）。 */
final class GraphView {
    void render(GuiGraphics g, GraphLayout layout, int x, int y, int w, int h);
}
```

> `cef/` 接口草案（CefHost / RbRenderHandler / FrameTexture / JsBridge）冻结在 §二
> 「附：JCEF 备选设计」中，恢复 JCEF 时按原文实施。

---

## 五、JEI 集成（mc/jei/，M4）

| 通道 | 用法 | JEI API（开发 19.39.0.372 / 实机 19.70.0.460，**逐项待核实 R-08**） |
|---|---|---|
| G-拖拽 | 从 JEI 物品列表把物品拖到界面配方槽 | `IGhostIngredientHandler<Screen>`：Screen 提供目标矩形集；落点回调拿 `ITypedIngredient` |
| G-配方写入 | 界面内对某物品点"从 JEI 查配方"→ JEI lookup → 自动填原料/产物（数量带入，**耗时/设备手填**） | `IJeiRuntime.getRecipeManager()` → recipe lookup → `IRecipeWrapper` 的输入/输出 ingredient 列表 |
| G-兼容声明 | mods.toml 已声明 `optional`；运行期 `ModList.get().isLoaded("jei")` 决定 JeiBridge 是否激活 | — |

隔离规则：`mc/jei/` 包外**任何类不得 import `mezz.jei.*`**（compileOnly 类进入运行期
未装 JEI 的实例会 NoClassDefFoundError）。JeiBridge 只通过接口回调把
`ItemKey + 数量 + 显示名` 抛给界面层，不外泄 JEI 类型。

ghost 靶区动态化：原生 widget 把"当前可投放槽的屏幕矩形"注册给 `BalancerScreen`
（滚动/页签切换时更新），Screen 据此返回 JEI 目标矩形 → 实现"拖到槽位上"的效果。
CEF 恢复时改由 JS 上报（见 §六，暂缓）。

---

## 六、Java ↔ JS 消息协议（CEF MessageRouter，**暂缓·仅 JCEF 恢复时生效**）

请求-响应（JS → Java，`window.cefQuery`，JSON 单参）：

```json
{ "id": 17, "op": "solve", "payload": { ...场景 JSON（io.py schema）... } }
→ { "id": 17, "ok": true, "data": { ...SolveResult JSON... } }
或 { "id": 17, "ok": false, "error": "..." }
```

| op | payload | data / 推送 | 说明 |
|---|---|---|---|
| `solve` | 场景 JSON | SolveResult JSON | 求解丢工作线程（core 纯 Java 线程安全），完成后响应；表单防抖 600ms 由 JS 控制（同 tkinter 版） |
| `itemIcon` | `{key, size}` | `{dataUri}` | 图标 LRU 缓存；M3 后期可优化为 atlas |
| `listScenarios` / `loadScenario` / `saveScenario` / `deleteScenario` | `{name?}` / 场景 JSON | 列表 / 场景 JSON | `config/recipebalancer/scenarios/*.json` |
| `pickItem` | `{target}` | 推送 `itemPicked` | 唤起 `InventoryPickerScreen`（背包点选） |
| `getEnv` | — | `{jei: bool, cef: bool, gameDir, …}` | 前端据此隐藏不可用通道 |

Java → JS 推送（`executeJavaScript`）：

| type | payload | 说明 |
|---|---|---|
| `jeiRecipeDraft` | 配方草稿（原料/产物 ItemKey+数量+显示名） | G-配方写入结果落到当前编辑卡片 |
| `ghostTargetRects` | `[{rect}]` | JS 申报投放靶区（G-拖拽） |
| `itemPicked` | `{target, key, count}` | 背包拾取回填 |
| `solveProgress` | 阶段文本 | 大场景反馈（可选） |

约束：协议字段名一旦发布即冻结（前端资产可能滞后于 jar 升级）；新增字段允许，改名/删名禁止。

---

## 七、数据保真与对拍策略（金标准）

测试数据：Python `tests/golden/` 9 场景（`simple` / `deficit` / `ebf_cycle` /
`mek_fission` / `multi_loop` / `multi_product` / `dual_line` / `autocatalytic` / `uu`）
**原样拷贝**为 Java 测试资源，不改动一个数字。JUnit（无 MC，纯 JVM）分四层断言：

| 层 | 内容 | 判定 |
|---|---|---|
| L1 | 目标函数值 | 与 Python 金标准相对差 ≤ 1e-6 |
| L2 | 物料守恒恒等式 `net = A·x + ext − target` 逐物品 + 外部输入 ⊆ 白名单 + 设备统计可由速率重算（对应 test_invariants.py） | 必须恒等成立 |
| L3 | 结构集合：活跃配方集、外部输入集、副产物集、亏损集、种子集、bootstrap 投料集 | 逐集相等 |
| L4 | 逐位速率 `x` | **尽力对拍**：一致则记录"逐位一致"；不一致但 L1–L3 全过 → 判定"LP 多解差异"，差异样本记入 `VERIFY.md`，不判失败 |

> 这是对"数据保真"的精确化：恒等性锚定在守恒律与结构上（物理意义），
> 而不是强制两个不同求解器落在同一退化顶点上（无物理意义的偶然）。
> 若用户要求 L4 也逐位恒等，则需更换 LP 后端（HiGHS-JNI），代价是引入 native 依赖（见 O-3）。

---

## 八、开放问题（须用户拍板，不默认）

| # | 问题 | 备选 |
|---|---|---|
| O-1 | Python 已知语义 P0-A（库存催化剂式抵消）/ P0-B（禁用配方参与依赖图）是否在本轮一并修复？ | 默认：**忠实移植**（对拍基线干净）；修复立项另谈 |
| O-2 | ItemKey v1 忽略数据组件（同名即同物）；是否需要组件级区分（如同名不同 NBT 的产物）？ | 默认：忽略；预留 `componentFingerprint` 扩展位 |
| O-3 | L4 逐位恒等是否强需求？ | 默认：按 §7 L1–L3；强需求则评估 HiGHS-JNI |
| O-4 | JEI 配方写入的触发交互（界面内按钮 vs 在 JEI 界面按键） | 开发期按 R-08 核实结果选 |
| O-5 | 流程图交互程度：当前只做**静态图**（无平移/缩放/悬停） | 默认：静态；升级交互须单独立项（M5 之后） |

## 九、风险与核实清单

风险（实现时盯着；标 ⏸ 者随 JCEF 暂缓）：

| R | 风险 | 等级 | 缓解 |
|---|---|---|---|
| R-01 | CEF natives ~100MB 首启下载（国内网络） | ⏸ 暂缓 | 恢复时：classpath 平台伴生 jar / 整合包 `overrides` 预置 / 在线回退（`setMirrors` 加 Maven Central） |
| R-02 | JDK16+ 的 OSR JOGL JVM flags 问题 | ⏸ 暂缓 | 恢复时自实现 RenderHandler 不走 JOGL；S-01 验证免 flags |
| R-03 | 无 AWT toolkit 进程里构造 KeyEvent | ⏸ 暂缓 | 恢复时 S-02；键码自映射 + CefKeyEvent 直填 |
| R-04 | 中文 IME 输入（OSR 已知痛点） | ✅ 已消除 | 原生 `EditBox` 由 MC 处理 IME/粘贴 |
| R-05 | CEF 内存 +~300MB | ⏸ 暂缓 | 恢复时配置开关 + 文档告知 |
| R-06 | commons-math3 / gson 分发冲突（其他模组可能内嵌） | 低 | commons-math3 已 **直接 shade 进模组 jar**（单 jar 2.27MB，无嵌套依赖）；gson 用加载器自带（`compileOnly`） |
| R-07 | GUI scale 与坐标换算错位（点击偏） | 低 | 原生控件统一用 GUI 缩放坐标；验收含 150% DPI |
| R-08 | JEI 19.39 / 19.70 API 逐行核实（lookup/ghost 签名） | 中 | 沿用 logiscope 的"逐行核实"纪律，动工前先读 API jar；以 dev 19.39 为编译基准 |
| R-09 | CEF 帧位序（BGRA/RGBA） | ⏸ 暂缓 | 恢复时 S-03 |
| R-10 | **LP 多解 / 数值条件差异导致与 Python 不一致** | 已降至低 | S-04 实测：9 场景数值 1e-9 内、结构集合一致；余留 `total_machine_count` ±1（ceil 边界），已记录并在测试中单列 |
| R-11 | CEF 子进程崩溃 | ⏸ 暂缓 | 恢复时 `onBrowserClosed` → 降级 + 提示 |
| R-12 | CEF 消息泵驱动节奏 | ⏸ 暂缓 | 恢复时每帧驱动 + 限频 |
| R-13 | 分层布局可读性（SCC 凝聚后仍拥挤 / 长边穿越） | 中 | 跨层拆虚拟节点 + 层内重心排序；节点数超阈值退文本报告 |
| R-14 | 节点数性能（每帧 quad 预算） | 低 | ≤200 节点目标；`GuiGraphics` 批绘制，超限降级 |
| R-15 | 位图字体 CJK/缩放发虚 | 低 | 标签固定 1.0 缩放绘制；只缩放几何图形 |
| R-16 | 只有 1px 直线边（无曲线/虚线） | 低 | 折线 + 绕行偏移；不追求 pyvis 观感 |

Spikes（M1/M3 动工前先做，每个 ≤ 半天；⏸ 随 JCEF 暂缓）：

- **S-01** 纯 JVM 模拟 MC 线程模型跑 JCEF OSR —— ⏸ 暂缓（R-02）。
- **S-02** KeyEvent 构造与 sendKeyEvent 转发 —— ⏸ 暂缓（R-03）。
- **S-03** onPaint 帧位序直拷验证 + 帧率实测 —— ⏸ 暂缓（R-09）。
- **S-04** ✅ **已完成（2026-10-25）**：commons-math3 对 9 场景对拍，epsilon=1e-10；
  数值 1e-9 内、结构集合一致，唯一偏差 `total_machine_count` ±1（详见 PROGRESS R2）。
- **S-05** JEI 19.39 API jar（实机 19.70 向后兼容）逐行核实（lookup / ghost / ITypedIngredient）——输出到本文档附录。
- **S-06** `GuiGraphics` 画布试验：300 节点 60fps、`RenderType.lines` 边宽、pose/scissor 平移缩放、CJK 标签裁剪 —— 决定 R-13/R-14/R-15。

## 十、目录结构（目标态）

```
RecipeBalancer/
├── build.gradle / settings.gradle / gradle.properties     # 骨架复制自 logiscope（版本组合一致）
├── gradlew(.bat) / gradle/wrapper/                       # Gradle 9.2.1（腾讯镜像直连）
├── docs/
│   ├── GOALS.md / ARCHITECTURE.md / PROGRESS.md
├── tools/
│   ├── gen_test_fixtures.py                               # 从上游 conftest 导出 9 个场景 JSON
├── src/main/java/com/jessetech/recipebalancer/
│   ├── RecipeBalancerMod.java
│   ├── core/          # ★ C1：零 MC 依赖（model/matrix/detect/solve/bootstrap/io/report/layout）
│   ├── mc/            # keybind / screen / ui / itemmap / jei
│   └── cef/           # ⏸ 备选（host / render / input / scheme / bridge）
├── src/main/resources/
│   ├── assets/recipebalancer/lang/   # 键位名（en_us / zh_cn）
│   └── web/            # 仅 HTML 导出模板（自包含 vis-network）
├── src/test/java/      # JUnit 5：金标准 / 不变量 / 单元
├── src/test/resources/ # scenarios/*.json（输入）+ golden/*.json（Python 快照）
└── src/main/templates/META-INF/neoforge.mods.toml
```

## 十一、环境事实（2026-10-25 初始化记录，构建已验证 BUILD SUCCESSFUL）

- 环境骨架复制自 `D:\dev\logiscope`（gradlew / wrapper 9.2.1 / MDG 2.0.147 /
  NeoForge 21.1.209 / Java 21 锁定 / UTF-8 元数据陷阱规避）——**复制而非引用**，
  两个模组完全独立；共享的只有 `~/.gradle` 全局缓存与 wrapper 发行版缓存。
- JEI 版本策略（2026-10-25 R5）：
  - **开发环境 19.39.0.372**：jar 内 `neoforge.mods.toml` 只要求 `neoforge [21.0.118-beta,)`，
    是最后一个兼容 dev 锁定的 21.1.209 的版本；19.42.0.389 起要求 `[21.1.238,)`。
  - **实机实例 19.70.0.460**：实例已升级 **NeoForge 21.1.246**（从崩溃栈确认），满足 JEI 的
    `[21.1.238,)`，也满足本模组可选依赖范围 `[19.39.0,)`。
  - Maven 的 `Last-Modified` 是该镜像的重传时间（JEI 19.x 多为 2026-07），**不能当发布日期用**；
    版本选型以 jar 内 `neoforge.mods.toml` 的 loader 门槛为准。
  **groupId 已逐目录核实 = `mezz.jei`（不是老文档的 `mezz`）**；blamejared 上三件套齐全。
  仓库声明用 `exclusiveContent` 把 `mezz.jei` 组钉死在 blamejared（JEI 解析不再
  发请求给其它仓库，消除 neoforged 仓库波动的影响）。
- commons-math3 3.6.1（Maven Central，普通声明）。
- **仓库过滤教训（勿重蹈）**：曾尝试用 `exclusiveContent` 把 org.apache / org.junit
  钉死到 central、给 neoforged 自动仓库挂 include 白名单——都被证伪：
  exclusiveContent 是**双向独占**（被包进 exclusiveContent 的仓库从此只提供 filter
  列出的组），白名单则切断 neoform 管线对 `org.ow2.asm` 等镜像组的解析 →
  `Could not find`。最终形态：**只保留 blamejared 的 mezz.jei 独占，其余走默认
  解析顺序**（neoforged 仓库对第三方组返回 404 → 跳过 → central 200）。
- 网络事实：`~/.gradle/gradle.properties` 全局代理指向 127.0.0.1:7890（2026-10-18 配置）
  ——该端口**已过时**（2026-10-25 实测本机监听的是 7993，端口随代理软件切换变化）。
  构建临时覆盖（不动全局配置）：
  `./gradlew build -Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=<当前端口> -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=<当前端口>`。
  代理端口失效的典型症状：`Connect to 127.0.0.1:7890 failed: Connection refused`。
- JCEF 坐标备查：`me.friwi:jcefmaven:152.0.6`（**暂缓，未引入**；恢复条件见 §二）。
  源码事实（2026-10-25 核实）：`install()` 先查 classpath 的 `jcef-natives-<platform>`，
  有则**完全不联网**；`installDir/install.lock` 命中即跳过下载/解压；`setSkipInstallation(true)`
  由调用方全权负责；默认镜像 GitHub Releases → Maven Central；下载/解压无哈希校验。
- 上游 Python 参考环境：`.venv`（Python 3.10.11 / numpy 2.2.6 / scipy 1.15.3 / pytest 9.1.1），
  用于生成测试夹具与跑金标准基线；**测试输入浮点必须由 Python 生成，禁止手抄**。
- 产物已验证：`recipebalancer-0.1.0-1.21.1.jar`，mods.toml 占位符替换正确、中文 UTF-8 无损。
