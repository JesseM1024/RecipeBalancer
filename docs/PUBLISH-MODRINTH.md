# 发布到 Modrinth · 逐字段填写清单

> 做法沿用 Logiscope：**正文与 changelog 先落成本地文档，网页表单只做复制粘贴**。
> 正文 → `docs/MODRINTH.md`；每版变更 → `docs/RELEASE-<版本>.md`。
> 本文件是 0.1.2 这一版的填写单，之后每版照抄结构、只换版本号与 changelog。

## 0. 发布前准备的七样东西

| 项 | 值 / 路径 | 状态 |
|---|---|---|
| 图标 | `draft/RecipeBalancer512x512.png`（512×512 PNG） | ✅ |
| 构建产物 | `build/libs/recipebalancer-0.1.2-1.21.1.jar`（≈2.3 MB） | ✅ |
| 许可证文件 | `LICENSE`（MIT） | ✅ |
| 源码仓库 | https://github.com/JesseM1024/RecipeBalancer | ✅ |
| 项目正文 | `docs/MODRINTH.md`（英文 + 中文说明） | ✅ |
| 版本 changelog | `docs/RELEASE-0.1.2.md` | ✅ |
| 截图（Gallery） | 建议 3 张：主编辑界面 / 求解结果文本 / 流程图 | ⬜ 可选 |

---

## 1. 创建项目（Create a project）

入口：Modrinth 右上角头像 → **Projects** → **Create a project**。

| 字段 | 填什么 | 注意 |
|---|---|---|
| Project type | **Mod** | 不是 Plugin / Datapack |
| Name | `RecipeBalancer` | 与 `mod_name` 一致，别写 "Recipe Balancer" 造成检索分裂 |
| Slug | `recipebalancer` | 生成 `modrinth.com/mod/recipebalancer`；被占用则用 `recipe-balancer`，之后不要改（旧链接会断） |
| Summary | `Client-side production line balancer: enter recipes and target rates, solve steady-state rates, machine counts and byproducts with linear programming — cyclic loops included.` | 一句话、≤256 字符；搜索卡片上显示的就是它，别写空话 |
| Description | 先粘贴 `docs/MODRINTH.md` 的开头两段 | 正式正文在创建后补全，这里只是占位 |
| Visibility | Draft（默认） | 补齐必填项后再提交审核 |

> 创建后项目处于 **Draft**，只有你自己可见；此时即可用 **Unlisted** 链接自测页面观感。

---

## 2. 项目设置（Settings）

| 字段 | 填什么 | 理由 |
|---|---|---|
| Icon | 上传 `draft/RecipeBalancer512x512.png` | 512×512 是推荐尺寸；Modrinth 会用图标主色生成项目主题色 |
| Categories | **Technology**、**Utility**（最多 3 个，可再加 **Management**） | 求解器 + 规划工具；不要勾 Game Mechanics（不改进游戏机制） |
| Environment | **Client-side only** | `@Mod(dist = Dist.CLIENT)`，服务端不装也能进服。新版 Environments 系统按版本记录，项目页显示并集 |
| License | **MIT** | 与 `gradle.properties` 的 `mod_license` 一致；不要选 "ARR" 后又在正文写 MIT |
| Source | https://github.com/JesseM1024/RecipeBalancer | 必填项级别的重要度（玩家与审核都看） |
| Issues | https://github.com/JesseM1024/RecipeBalancer/issues | 与 Source 分开填，别用同一个根链接 |
| Wiki | 留空，或填 https://github.com/JesseM1024/RecipeBalancer#readme | 本项目文档在仓库里，不必单开 Wiki |
| Discord | 留空 | 没有服务器就不填 |
| Donation links | 留空 | 可选，不影响审核 |
| Disclosures | 按实情勾选（代码 / 贴图 / 文本是否有 AI 参与；广告、遥测、付费功能一律不勾） | 现在是硬性合规项，漏填比勾错更麻烦 |
| Gallery | 可选：主界面、结果页、流程图各一张，第一张设 featured | 转化率最高的位置 |

---

## 3. 项目正文（Description 页签）

- 全文粘贴 `docs/MODRINTH.md`（含末尾 `## 中文说明`），Markdown 标题、表格、列表都可用。
- 审核红线：**正文里不要放任何外站下载链接**；不要出现"去某某网盘下载"之类导流。
- 中英双段是刻意的：英文段面向检索与海外玩家，中文段面向国内读者，不要合并成一段混排。

---

## 4. 创建版本（Versions → Create version）

| 字段 | 填什么 | 注意 |
|---|---|---|
| Name | `RecipeBalancer 0.1.2 for NeoForge 1.21.1` | 可自动生成，改成这个更易读 |
| Version number | `0.1.2-1.21.1` | **后缀必须带 MC 版本**（与 `mod_version`、jar 文件名三者一致），将来多版本线不会混装 |
| Channel | **Beta** | 0.1.x 且 M5 未完成；M5 收尾后发 Release |
| Loaders | **NeoForge** | 1.21.1 不要勾 Forge，那是 1.20.1 及更早的线 |
| Game versions | **1.21.1**（只勾这一个） | 多勾会让 1.21.2+ 的玩家装到不兼容的 jar |
| Environment | **Client-side only** | 与项目设置保持一致；这是审核最常挑的项 |
| Dependencies | 搜 `JEI`（Just Enough Items）→ 依赖类型 **Optional**，版本留空（任意版本） | **绝不能标 Required**：没装 JEI 的玩家会被 Modrinth 拦下，而本模组不装 JEI 也能用 |
| Files | 上传 `build/libs/recipebalancer-0.1.2-1.21.1.jar`，设为 primary | 只传一个 jar；sources jar 可另传并标 `sources-jar` |
| Changelog | 全文粘贴 `docs/RELEASE-0.1.2.md` | Markdown，版本页会原样渲染 |

---

## 5. 提交审核

1. 项目页 / 设置里把可见性从 **Draft** 改为 **Public**（或点 **Submit for review**）。
2. 审核通常数小时到数天；期间项目页显示 Processing，可在 Projects 里看状态。
3. 可能被打回的三类原因，逐条自查：
   - **Environment 标错**（纯客户端标成 client_and_server）→ 改成 Client-side only；
   - **正文含外链下载 / 广告**→ 删；
   - **License 与仓库 LICENSE 不一致** → 统一成 MIT。
4. 通过后 `modrinth.com/mod/recipebalancer` 生效。此时把链接补进 `README.md` 与仓库 About。

---

## 6. 之后每发一版

1. 改 `gradle.properties` 的 `mod_version`（如 `0.1.3-1.21.1`）→ `gradlew build`。
2. 写 `docs/RELEASE-0.1.3.md`。
3. Modrinth → 项目 → **Versions → Create version**，重复第 4 步的字段（版本号、changelog、jar、依赖）。
4. **不要覆盖旧版本文件**：Modrinth 版本不可替换，玩家要能回退。
5. 若升级了 JEI 依赖下限（`[19.39.0,)` 变化），同步改版本依赖项与正文的兼容表。

---

## 7. 易踩的坑（从 Logiscope 那轮整理）

| 坑 | 后果 | 做法 |
|---|---|---|
| 版本号不带 MC 后缀 | 将来开 1.20.1 线时两个 jar 同名混淆 | `0.1.2-1.21.1`，jar 文件名与版本号一致 |
| 依赖标成 Required | 没装 JEI 的人被拦，报"缺前置" | JEI 只标 Optional |
| Loader 勾成 Forge | 1.21.1 玩家装上直接崩 | NeoForge 单独一项 |
| Game versions 多勾 | 不兼容版本被推送 | 只勾实测过的 1.21.1 |
| Summary 写中文长句 | 英文检索命中率低 | 英文一句话，正文里再放中文说明 |
| slug 后期修改 | 旧链接与外部引用断链 | 创建时就定好，之后不动 |
