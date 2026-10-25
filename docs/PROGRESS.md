# RecipeBalancer · 逐轮进度记录

> 记录规则：每轮先记录实测结果（环境/构建/断言），改动在结果之后描述，便于回滚到"已知可用"。
> 历史轮次过多时按惯例归纳进 `PROGRESS-HISTORY.md`。

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
   - `matrix/StoichMatrix`、`graph/DirectedGraph`（Tarjan SCC + networkx 同序拓扑分代）
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
