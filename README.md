# RecipeBalancer

MC 1.21.1 + NeoForge 21.1.209 **纯客户端**模组：产线配平自动计算器。
按下快捷键打开游戏内原生图形界面（输入自动保存到 `config/recipebalancer/scenarios/autosave.json`，
**没填完目标产物也会保存**，下次打开自动恢复），可视化录入配方（物品可从背包 / JEI 标记，或从 JEI
配方一键写入），用线性规划求稳态配方速率与机器并行数，**支持循环配方**
（SCC 亏损检测 + 完美循环最小启动投料）；流程图用 `GuiGraphics` 绘制，可导出自包含 HTML。

> 项目当前 **M4 已完成**：核心求解 + AE 样板编码终端 + JEI ghost 拖拽 / 配方页加号导入，
> 80 项测试全绿（9 场景与 Python 金标准对拍）。下一步 M5：流程图 + 完美循环投料展示。
> 上游参考：本机 Python 项目《配方树自动配平》（算法语义以此为准）。

## 文档

| 文档 | 内容 |
|---|---|
| [docs/GOALS.md](docs/GOALS.md) | 目标、验收标准、里程碑 |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | **架构与接口文档**（分层、渲染选型、移植映射、协议、风险） |
| [docs/PROGRESS.md](docs/PROGRESS.md) | 逐轮改动记录 |

## 开发环境

| 项 | 值 |
|---|---|
| MC / 加载器 | 1.21.1 / NeoForge 21.1.209 |
| Java | 21（`org.gradle.java.home` 已锁定，勿用系统默认 JDK 25） |
| Gradle | 9.2.1 wrapper（腾讯镜像直连；发行版已缓存于 `~/.gradle/wrapper/dists`） |
| 构建 | `./gradlew build` |
| 纯 JVM 测试 | `./gradlew test`（金标准对拍 / 不变量 / 单元，不启动游戏；结束后自动把 jar 复制到 `test_instance_dir/mods`） |
| 运行客户端 | `./gradlew runClient`（dev 环境自带 JEI 19.39.0.372 runtimeOnly） |

构建骨架复制自 `D:\dev\logiscope`（版本组合一致，全量复用 `~/.gradle` 依赖缓存），
但**两项目完全独立**，无任何构建引用。

## 界面操作（M3/R11）

- 顶部：当前**工作区名称**输入框 + 右侧一行 `载入` / `存储`（高度压缩）；下一行是四个参数开关和 `求解`。
- 新配方默认耗时 **1t**；一级显示 3 位小数，编辑框保留 double 的完整精度
  （如 0.00000000001 可完整看到并保存）。
- 一级大/小数字使用 AE 风格紧凑单位 `f / p / n / μ / m / k / M / G / T / P / E`
  （如 30k、1M、1n），点进编辑框看精确值；用量输入支持 `+ - * / ^` 表达式。
- 配方耗时一级也使用同一套紧凑后缀（如 `30kt`）。
- 上次选择的耗时单位与目标速率单位记在 `config/recipebalancer/ui_units.properties`。
- **目标**：一级界面内联 **9 列 × 1~2 行**（与背包同宽），可滚轮翻动，最多 **32 个**；
  左键编辑、右键删除、空槽自定义/手持添加；没有二级「目标」按钮。
  目标编辑可选速率单位 `/t`、`/s`、`/m`、`/h`；草稿内部统一每 tick，一级统一显示 `/t`。
- **配方库**：**9 列**，行数随窗口高度增加（剩余高度优先给配方库）；
  左键载入编码区，**右键两次删除**。
- **编码区**：原料 3 列、**产物 2 列** × **最多 4 行可视**，滚轮查看，最多 **256 原料 / 16 产物**；
  `写入` / `新建` 上下排列在耗时格下方（40×14）。
- 左键从背包拿起物品点格子放置（同物品累加），左键已有格子移除；
  **空手左键空格直接自定义**；中键改量。
- 整合包没有的物品用彩色方块 + 首字占位，tooltip 显示注册名。
- 设备格：左键放置手持物品，中键输入注册名；耗时格支持 `+ - * / ^` 与 `t/秒/分/时`。
- 二级编辑页统一 **取消在左、确定在右**；物品页图标在注册名框左侧，真名/注册名在其下方。
- 输入自动保存；面板接近满屏，原版物品栏配色（`#C6C6C6` 面板、`#8B8B8B` 18px 槽）。
- 面板宽度按内容计算（9 格列表/背包与编码列宽取最大），不再写死固定宽度。
- 装了 JEI 时，首次打开主界面及用量/耗时/配方集界面都会渲染右侧物品面板。
- 支持从 JEI 物品列表**拖 ghost** 到编码格/设备格/目标格；JEI 配方页侧边加号可把
  当前配方原料/产物一键导入编码终端（数量默认 1，设备优先取催化剂，需人工复核）。

> **网络注意**：本机 Gradle 全局代理锁在 `~/.gradle/gradle.properties`（127.0.0.1:7890，
> Clash）。代理未运行时构建会 `Connection refused`；临时绕过（不动全局配置）：
> ```bash
> ./gradlew build -Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=<可用代理端口> \
>                 -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=<可用代理端口>
> ```

## 目录

```
docs/                 # GOALS / ARCHITECTURE / PROGRESS
src/main/java/        # com.jessetech.recipebalancer（core/ 求解核心 + mc/ 界面）
src/test/java/        # JUnit 5：金标准对拍 / 不变量 / 单元
src/test/resources/   # scenarios/*.json（输入）+ golden/*.json（Python 快照）
src/main/templates/   # neoforge.mods.toml 模板（UTF-8 占位符替换）
src/main/resources/   # web/：仅 HTML 导出模板
```
