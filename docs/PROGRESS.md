# RecipeBalancer · 逐轮进度记录

> 记录规则：每轮先记录实测结果（环境/构建/断言），改动在结果之后描述，便于回滚到"已知可用"。
> 历史轮次过多时按惯例归纳进 `PROGRESS-HISTORY.md`。

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
