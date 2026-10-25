# RecipeBalancer · 逐轮进度记录

> 记录规则：每轮先记录实测结果（环境/构建/断言），改动在结果之后描述，便于回滚到"已知可用"。
> 历史轮次过多时按惯例归纳进 `PROGRESS-HISTORY.md`。

---

## R1 · 2026-10-25 · 环境初始化 + 架构设计（本轮）

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
