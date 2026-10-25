# RecipeBalancer · 架构与接口文档

本文档描述四件事：**分层与层间契约**、**渲染层选型结论**、**核心接口草案**、**风险与核实清单**。
实施顺序按 [GOALS.md](GOALS.md) 里程碑 M1→M5；接口草案在开发中可细化，但**契约（C1–C7）不可破坏**。

> 上游参考：Python 项目《配方树自动配平》（`C:\Users\JesseTech\Desktop\JesseTech\code\py\配方树自动配平`）。
> 本文档的算法语义均以其 `ANALYSIS.md` / 源码为准；已知语义问题（P0-A/B/C/D）**忠实移植**，
> 修复须单独立项并经确认（见 §8 开放问题）。

---

## 一、总体分层

```
┌────────────────────────────────────────────────────────────────────────┐
│ MC 集成层  mc/                                                            │
│   keybind/RbKeyMappings      快捷键 → 打开/关闭界面                     │
│   screen/BalancerHostScreen  CEF 载体屏（事件转发 + JEI ghost 靶区）    │
│   screen/NativeFallbackScreen 原生降级屏（CEF 不可用时全功能保底）      │
│   screen/InventoryPickerScreen 背包拾取小屏（点选物品标记）             │
│   itemmap/ItemStackKeyMapper  ItemStack/FluidStack ↔ ItemKey(String)   │
│   itemmap/ItemIconService    物品图标渲染 → PNG dataUri（LRU 缓存）    │
│   jei/JeiBridge              JEI 软依赖隔离层（装了才激活）            │
├────────────────────────────────────────────────────────────────────────┤
│ 渲染层  cef/（唯一碰 JCEF / GL 的层）                                    │
│   CefHost                     生命周期：install→startup→shutdown        │
│   RbRenderHandler             自实现 OSR：onPaint(BGRA) → FrameQueue    │
│   FrameTexture                 帧纹理上传（NativeImage→TextureManager） │
│   InputForwarder               MC 鼠标/键盘/滚轮 → CEF 事件             │
│   RbSchemeHandler             rb://app/… → jar 内 web/ 资源             │
│   bridge/JsBridge             CefMessageRouter：op 分发（见 §6 协议）   │
├────────────────────────────────────────────────────────────────────────┤
│ 前端资产  resources/web/（rb:// 根；HTML/CSS/JS，M3 阶段填充）           │
├────────────────────────────────────────────────────────────────────────┤
│ 求解核心  core/（★ 纯 Java，零 MC/JEI/JCEF 依赖，M1 先行）              │
│   model/    Scenario, RecipeEntry, SolveOptions, SolveResult           │
│   matrix/   StoichMatrix（稀疏装配，物品索引确定性排序）               │
│   detect/   RawDetector, SeedDetector, CyclicDeficitDetector(+Tarjan)  │
│   solve/    LpSolver(接口) → CommonsMath3LpSolver；Balancer(编排)       │
│   bootstrap/PerfectCycleBootstrapper（最小反馈顶点集→启动投料）        │
│   io/       ScenarioJson（Gson；schema 与 Python io.py 同构）          │
│   report/   TextReporter（文本报告，降级屏复用）                       │
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
| **C4** | CEF 初始化失败/被禁用 → 自动切 `NativeFallbackScreen`，编辑/求解/持久化/文本结果全功能可用（可视化降级为导出 HTML 文件） | 渲染层故障导致模组不可用 |
| **C5** | JEI 未安装时全部功能照常（仅缺拖拽与配方写入两条录入通道） | 软依赖变硬依赖 |
| **C6** | 前端 JS **不含任何求解逻辑**（与 tkinter 版"GUI 无逻辑"同构）；所有计算在 Java 侧 | 两套实现结果不一致，违背数据保真 |
| **C7** | `core/` 数值常量与 Python `models.py` 逐一对应（`EXTERNAL_PENALTY=1e6`、`EPS_RAW=1e-12`、`EPS_RATE=1e-10`、`EPS_DEFICIT=1e-9`、`EPS_FLOW=1e-8`、`MAX_EXACT_FVS=12`），禁止"顺手优化" | 任何常量偏移都会使金标准对拍失真 |

---

## 二、渲染层选型（结论：JCEF 离屏合成）

需求原文："可视化部分也要在游戏内进行渲染，达到 html 的效果，这可能需要一个高效的
渲染器，而不能使用原生渲染器。"——即**游戏内**渲染（非外部浏览器窗口），
效果达到 HTML/CSS 水平。

| 方案 | HTML/CSS/JS 保真 | 体积/依赖 | 主要风险 | 结论 |
|---|---|---|---|---|
| **JCEF（Chromium Embedded）离屏模式** | **完整 Chromium**（可用 vis-network/ECharts 等全部 JS 生态） | natives ≈100MB（首启下载到实例缓存目录；jar 内只带 ~2MB 绑定层） | 子进程模型、事件转发、IME（R-02/03/04） | ✅ **主线** |
| Ultralight Java 绑定 | CSS 子集 + 自有 JS（无完整生态） | ~40MB | 绑定维护停滞、许可变化 | ❌ 否决 |
| JavaFX WebView（离屏快照） | WebKit | +整个 JavaFX runtime | 快照每帧太慢，无法 60fps 合成 | ❌ 否决 |
| 自研 UI 框架（immediate-mode/HTML 子集） | 非 HTML（仿制） | 0 | 开发量巨大；无法复用现有 HTML 资产；"HTML 效果"是仿的 | ❌ 否决；仅其**事件模型**供降级屏参考 |
| 原生 `GuiGraphics`（vanilla Screen） | 非 HTML | 0 | 效果上限低 | 仅作 **C4 降级屏** |

**选 JCEF 的关键理由（不只是"能渲染"）：**

1. **可视化基因同源**：Python 版的 pyvis 交互图底层就是 **vis-network** JS 库；
   本模组前端直接用同一库渲染流程图，配色/交互语义（§5 C2 验收）可与 Python 产物逐像素对齐，
   且已有的 `ebf_cycle_graph.html` 等资产可近乎原样搬进 jar。
2. tkinter 版 GUI 的表单逻辑（自动求解防抖、单位归一化、内联编辑）在 HTML/JS 中
   重写的成本**低于**在原生 Screen 里仿制同等交互的成本，且效果上限高得多。
3. 渲染发生在 **CEF 独立进程**（GPU/Renderer 子进程），MC 主线程只做一次纹理上传 +
   一次 blit——这正是"高效渲染器"的含义：MC 帧循环不为排版/文本成形买单。

**事实核查（2026-10-25 已核实）**：`me.friwi:jcefmaven`（JCEF 的 Maven 化发行，
Apache-2.0，活跃维护，最新 152.0.6）在 Maven Central 可用；`windowless_rendering_enabled`
（OSR 离屏）为默认开启；natives 首启按平台从可配置镜像下载；Windows amd64 无限制。

### 渲染管线（帧合成路径）

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

### CEF 生命周期要点

- **natives 安装**：`CefAppBuilder.installDir` = 游戏实例目录下 `recipebalancer/jcef/`
  （放游戏目录而非 config，~100MB 不污染配置区）；镜像列表显式加 Maven Central
  （国内可达，已核实）；用户可禁用（配置项 `useCef=false`）→ 走 C4。
- **进程模型**：browser 进程即 MC 进程；renderer/gpu 崩溃经 `onBrowserClosed` 回调 →
  自动切降级屏并提示，MC 不崩。
- **资源加载**：注册自定义 scheme `rb://`，`rb://app/index.html` 直接读 jar 内
  `resources/web/`（零解压、零临时文件、模组升级自动生效）。
- **线程**：所有 JS 桥回调 `post` 回 MC 主线程再触碰 MC 数据（CefMessageRouter 回调线程 ≠ MC 线程）。

---

## 三、求解核心：Python → Java 移植映射

### 3.1 数学模型（与 Python 逐式对应）

**主 LP**（变量 `z = [x ∈ R^m 配方速率, r ∈ R^k 外部输入]`）：

```
min  c^T z
s.t. -A·x - E·r ≤ s - b        （每物品 i：净产出 + 外部输入 ≥ 目标 − 库存）
      x_j = 0（j ∈ 禁用集），其余 x_j ≥ 0，r ≥ 0

c = [ t_j (objective=machine) 或 1 (objective=recipes) ；  M=10^6（外部输入惩罚）]
A[i,j] = 产量_ij − 消耗量_ij   （化学计量矩阵，物品按名称确定性排序）
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
| `matrix.py` | `matrix/StoichMatrix` | 不引矩阵库：`double[][]` 或列稀疏数组即可（问题规模 ≤ 数百×数百） |
| `detection.py`（原料/种子/亏损） | `detect/` | networkx SCC → **自写 Tarjan**（约 80 行，避免引 JGraphT）；子 LP 用 `LpSolver` |
| `solver.py`（主 LP + 编排） | `solve/Balancer` + `solve/CommonsMath3LpSolver` | `scipy.linprog(method="highs")` → commons-math3 `SimplexSolver`（见 3.3） |
| `bootstrap.py` | `bootstrap/PerfectCycleBootstrapper` | 组合枚举 `itertools.combinations` → Java 组合迭代器；行为逐分支对齐 |
| `io.py`（场景 JSON） | `io/ScenarioJson` | **schema 完全同构**（`recipes/sourceList/productList/time/equType` + `options`），键名一字不改 → 与 Python 互导 |
| `report.py` | `report/TextReporter` | 文本报告移植（降级屏直接展示） |
| `visualize.py`（pyvis） | **不移植** → 前端 `web/`（vis-network） | 渲染在前端做，基因同源（见 §二） |
| `gui.py`（tkinter） | **不移植** → 前端 `web/` + `NativeFallbackScreen` | 交互约定（自动求解 600ms 防抖、单位归一化、回车添加）在前端复刻 |

### 3.3 LP 后端：commons-math3（备选 ojAlgo）

- **选型**：`org.apache.commons:commons-math3:3.6.1` 的
  `SimplexSolver`（成熟、纯 Java、零 native、API 稳定）。本问题规模（配方数十~数百）
  求解毫秒级，性能不构成瓶颈。
- scipy 映射：`A_ub/b_ub`（≤ 约束）→ `LinearConstraint(LE)`；等式约束 → `EQ`；
  `bounds` → `SimplexSolver.optimize` 的变量边界；big-M 直接写进目标向量（语义同 P0-C）。
- **分发形态待定（R-06）**：JarJar 内嵌（NeoForge JarJar，版本区间收敛）vs 独立 jar 依赖。
  开发期以 `implementation` 跑通，M1 结束时定夺并记入 PROGRESS。
- **多解问题（R-10）**：LP 最优解可退化不唯一，CM3 与 HiGHS 可能给出不同顶点。
  对拍按 §7 分层策略执行，**不追求逐位速率恒等**；差异必须记录。

### 3.4 数据保真：确定性来源清单

| 环节 | Python | Java | 一致性 |
|---|---|---|---|
| 物品排序 | `sorted(all_items)`（code point 序） | `String.compareTo`（UTF-16 code unit 序） | registry id 全 ASCII，两者等价；core 测试断言排序稳定 |
| 浮点 | float64 | `double` | 位相同 |
| 矩阵装配 | dict 迭代（量级累加） | 同一配方内先加产量再减消耗，顺序一致 | 逐位 |
| EPS 常量 | 6 个字面量 | 同值移植（C7） | 逐位 |
| LP 顶点 | HiGHS | CM3 simplex | **不保证逐位**（多解），按 §7 分层 |

---

## 四、核心接口草案

> 草案：开发阶段可细化签名/包内组织，但入口方法、参数语义、JSON 字段名是契约的一部分。
> 所有接口位于 `com.jessetech.recipebalancer.*`。

### 4.1 求解核心（core/，M1）

```java
package com.jessetech.recipebalancer.core.model;

/** 物品身份：core 层的不透明键，格式 "item|<registry_id>" 或 "fluid|<registry_id>"。 */
public record ItemKey(String id) {
    public static ItemKey item(String registryId);   // "item|minecraft:iron_ingot"
    public static ItemKey fluid(String registryId);
    public Kind kind();                               // ITEM / FLUID
}

/** 与 Python recipe dict 逐字段对应；time 单位=秒。 */
public record RecipeEntry(Map<String, Double> sourceList,
                          Map<String, Double> productList,
                          double time,
                          String equType) {}

public record SolveOptions(Set<String> rawMaterials,
                           Map<String, Double> initialInventory,
                           Set<Integer> disabledRecipes,
                           Set<String> allowedExternalCycInputs,
                           Objective objective,          // MACHINE | RECIPES
                           boolean autoDetectRaw,
                           boolean autoDetectSeed,
                           boolean autoBootstrap) {}

/** 场景 = Python 场景 JSON 的内存形态（io.py schema 同构）。 */
public record Scenario(List<RecipeEntry> recipes,
                       Map<String, Double> targetProducts,
                       SolveOptions options) {}

/** 与 RecipeSolution 逐字段对应。 */
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

/** LP 后端抽象：为替换求解器（ojAlgo / 未来 HiGHS-JNI）留缝。 */
public interface LpSolver {
    LpResult minimize(double[] c, List<LinearConstraint> constraints,
                      double[] lower, double[] upper);
    record LinearConstraint(double[] coeffs, Relation rel /*LE|GE|EQ*/, double rhs) {}
    record LpResult(boolean success, double[] x, String message) {}
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
    public static Scenario load(String json) throws SchemaError;
    public static String dump(Scenario scenario);
    public static String dumpResult(SolveResult result);   // 对应 Python 解导出格式
}
```

### 4.2 LP 后端实现（core/solve/，M1）

```java
/** scipy.linprog(method="highs") → commons-math3 SimplexSolver 的适配。 */
public final class CommonsMath3LpSolver implements LpSolver { /* M1 实现 */ }
```

### 4.3 MC 集成（mc/，M2+）

```java
package com.jessetech.recipebalancer.mc.keybind;
public final class RbKeyMappings {
    /** 注册 openBalancer（默认键位：K 键区自定，进键位设置菜单），模组总线上注册。 */
    public static void register(IEventBus modBus);
    /** Screen 开着时 vanilla KeyMapping 不走全局输入 → 拦 ScreenEvent。 */
    public static void onScreenKeyPressed(ScreenEvent.KeyPressed.Pre e);
}

package com.jessetech.recipebalancer.mc.screen;
/** CEF 载体屏：全屏 blit cef_frame 纹理 + 事件转发 + JEI ghost 靶区。 */
public final class BalancerHostScreen extends Screen { /* M3 */ }
/** 原生降级屏：M2 先行实现（也是 C4 的交付物）。 */
public final class NativeFallbackScreen extends Screen { /* M2 */ }

package com.jessetech.recipebalancer.mc.itemmap;
public final class ItemStackKeyMapper {
    public static ItemKey of(ItemStack stack);     // v1: registry id；组件指纹见 O-2
    public static ItemStack render(String itemKey, int sizePx); // 供图标/降级屏
}
public final class ItemIconService {
    /** 物品图标 → PNG dataUri（单帧渲染离屏 + LRU 缓存），给 JS <img> 用。 */
    public static String dataUri(ItemKey key, int sizePx);
}
```

### 4.4 渲染层（cef/，M3）

```java
package com.jessetech.recipebalancer.cef;

public final class CefHost {
    public static boolean tryInit();       // natives 缺失/失败 → false → C4 降级
    public static void shutdown();         // 关游戏时 CefApp.shutdown
    public static CefBrowser browser();   // 单例浏览器（面板即网页）
}

/** 自实现离屏 RenderHandler（绕开 jcefmaven 的 JOGL/Swing OSR 路径）。 */
final class RbRenderHandler implements CefRenderHandler {
    // getRect() → 物理像素尺寸；onPaint(BGRA) → FrameQueue.offer(帧)
}

/** 帧纹理：NativeImage ← BGRA int[]；注册 recipebalancer:cef_frame 到 TextureManager。 */
final class FrameTexture { void update(int[] bgra, int w, int h); }
```

### 4.5 JS 桥（cef/bridge/，M3）

```java
public final class JsBridge implements CefMessageRouterHandler {
    // op 分发见 §6；所有 MC 数据操作先 post 到主线程。
}
```

---

## 五、JEI 集成（mc/jei/，M4）

| 通道 | 用法 | JEI API（19.44.0.405，**逐项待核实 R-08**） |
|---|---|---|
| G-拖拽 | 从 JEI 物品列表把物品拖到界面配方槽 | `IGhostIngredientHandler<Screen>`：Screen 提供目标矩形集；落点回调拿 `ITypedIngredient` |
| G-配方写入 | 界面内对某物品点"从 JEI 查配方"→ JEI lookup → 自动填原料/产物（数量带入，**耗时/设备手填**） | `IJeiRuntime.getRecipeManager()` → recipe lookup → `IRecipeWrapper` 的输入/输出 ingredient 列表 |
| G-兼容声明 | mods.toml 已声明 `optional`；运行期 `ModList.get().isLoaded("jei")` 决定 JeiBridge 是否激活 | — |

隔离规则：`mc/jei/` 包外**任何类不得 import `mezz.jei.*`**（compileOnly 类进入运行期
未装 JEI 的实例会 NoClassDefFoundError）。JeiBridge 只通过接口回调把
`ItemKey + 数量 + 显示名` 抛给界面层，不外泄 JEI 类型。

ghost 靶区动态化：JS 端把"当前可投放槽的屏幕矩形"经 `push:ghostTargetRects` 报给 Java，
`BalancerHostScreen` 据此返回 JEI 目标矩形 → 实现"拖到 HTML 渲染的槽位上"的效果。

---

## 六、Java ↔ JS 消息协议（CEF MessageRouter）

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

## 九、风险与核实清单

风险（实现时盯着）：

| R | 风险 | 等级 | 缓解 |
|---|---|---|---|
| R-01 | CEF natives ~100MB 首启下载（国内网络） | 中 | jcefmaven `setMirrors` 加 Maven Central；支持手动放置目录；配置项可禁用 |
| R-02 | JDK16+ 的 OSR JOGL JVM flags 问题 | 中 | 自实现 RenderHandler 不走 JOGL；spike S-01 验证免 flags |
| R-03 | 无 AWT toolkit 进程里构造 KeyEvent（sendKeyEvent） | 中 | spike S-02；备选：键码自映射 + CefKeyEvent 直填 |
| R-04 | **中文 IME 输入**（OSR 模式 IME 是已知痛点） | 高 | 降级通道：JS 检测 IME 需求字段 → 调 Java 弹原生 EditBox（MC 原生支持 IME）→ 回填；粘贴路径始终可用 |
| R-05 | CEF 内存 +~300MB | 低 | 配置开关 + 文档告知；C4 降级保底 |
| R-06 | commons-math3 分发冲突（其他模组可能内嵌） | 低 | JarJar 版本区间 / relocate；M1 末定 |
| R-07 | GUI scale / devicePixelRatio 错位（文字糊/点击偏） | 低 | Java 侧统一换算；验收含 150% DPI |
| R-08 | JEI 19.44 API 逐行核实（lookup/ghost 签名） | 中 | 沿用 logiscope 的"逐行核实"纪律，动工前先读 API jar |
| R-09 | onPaint BGRA 与 NativeImage RGBA 位序关系 | 低 | spike S-03：预期免转换；否则整行 swap |
| R-10 | LP 多解导致与 Python 速率不一致 | 中 | §7 分层对拍 + 差异记录 |
| R-11 | CEF 子进程崩溃 | 低 | `onBrowserClosed` → 自动降级屏 + 提示 |
| R-12 | doMessageLoopWork 驱动节奏不当（卡 UI） | 中 | 每帧渲染事件里调用；必要时限频 |

Spikes（M1/M3 动工前先做，每个 ≤ 半天）：

- **S-01** 纯 JVM 模拟 MC 线程模型跑 JCEF OSR（无 JOGL 路径、无 flags）——决定 R-02。
- **S-02** KeyEvent 构造与 sendKeyEvent 转发（含焦点/组合键/字符路径）——决定 R-03 方案。
- **S-03** onPaint 帧位序直拷验证 + 帧率实测（≤1080p）——决定 R-09 与帧预算。
- **S-04** commons-math3 对 `ebf_cycle.json` 直接对拍——先于全套金标准，尽早暴露数值特性差异。
- **S-05** JEI 19.44 API jar 逐行核实（lookup / ghost / ITypedIngredient）——输出到本文档附录。

## 十、目录结构（目标态）

```
RecipeBalancer/
├── build.gradle / settings.gradle / gradle.properties     # 骨架复制自 logiscope（版本组合一致）
├── gradlew(.bat) / gradle/wrapper/                       # Gradle 9.2.1（腾讯镜像直连）
├── docs/
│   ├── GOALS.md / ARCHITECTURE.md / PROGRESS.md / VERIFY.md
├── src/main/java/com/jessetech/recipebalancer/
│   ├── RecipeBalancerMod.java
│   ├── core/          # ★ C1：零 MC 依赖
│   ├── mc/            # keybind / screen / itemmap / jei
│   └── cef/           # host / render / input / scheme / bridge
├── src/main/resources/
│   └── web/            # rb:// 根：index.html / app.css / app.js / vendor/vis-network…
└── src/main/templates/META-INF/neoforge.mods.toml
```

## 十一、环境事实（2026-10-25 初始化记录，构建已验证 BUILD SUCCESSFUL）

- 环境骨架复制自 `D:\dev\logiscope`（gradlew / wrapper 9.2.1 / MDG 2.0.147 /
  NeoForge 21.1.209 / Java 21 锁定 / UTF-8 元数据陷阱规避）——**复制而非引用**，
  两个模组完全独立；共享的只有 `~/.gradle` 全局缓存与 wrapper 发行版缓存。
- JEI 对齐实例实装版本 19.44.0.405。**groupId 已逐目录核实 = `mezz.jei`（不是老文档的
  `mezz`）**；blamejared 上三件套（common-api / neoforge-api / neoforge）齐全。
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
- JCEF 坐标备查：`me.friwi:jcefmaven:152.0.6`（**本阶段未引入**，M3 前按 S-01 结果引入）。
- 产物已验证：`recipebalancer-0.1.0-1.21.1.jar`，mods.toml 占位符替换正确、中文 UTF-8 无损。
