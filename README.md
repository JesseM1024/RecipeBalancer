# RecipeBalancer

MC 1.21.1 + NeoForge 21.1.209 **纯客户端**模组：产线配平自动计算器。
按下快捷键打开游戏内 HTML 界面，可视化录入配方（物品可从背包 / JEI 标记，或从 JEI
配方一键写入），用线性规划求稳态配方速率与机器并行数，**支持循环配方**
（SCC 亏损检测 + 完美循环最小启动投料）。

> 项目当前处于**架构阶段**：环境已初始化、架构与接口已定（见 [docs/](docs/)），尚未开始编码。
> 上游参考：本机 Python 项目《配方树自动配平》（架构与算法语义以此为准）。

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
| 运行客户端 | `./gradlew runClient`（dev 环境自带 JEI 19.44.0.405 runtimeOnly） |

构建骨架复制自 `D:\dev\logiscope`（版本组合一致，全量复用 `~/.gradle` 依赖缓存），
但**两项目完全独立**，无任何构建引用。

> **网络注意**：本机 Gradle 全局代理锁在 `~/.gradle/gradle.properties`（127.0.0.1:7890，
> Clash）。代理未运行时构建会 `Connection refused`；临时绕过（不动全局配置）：
> ```bash
> ./gradlew build -Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=<可用代理端口> \
>                 -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=<可用代理端口>
> ```

## 目录

```
docs/                 # GOALS / ARCHITECTURE / PROGRESS
src/main/java/        # com.jessetech.recipebalancer（当前仅骨架主类）
src/main/templates/   # neoforge.mods.toml 模板（UTF-8 占位符替换）
src/main/resources/   # 未来：web/ 前端资产（rb:// 根）
```
