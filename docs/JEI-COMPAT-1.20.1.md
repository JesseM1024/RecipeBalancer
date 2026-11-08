# 1.20.1 分支的 JEI 兼容说明（JEI 15.x）

适用分支：`1.20.1-neo`（产物 `recipebalancer-0.3.0-1.20.1.jar`）
JEI 在 1.20.1 **只有 Forge 侧发布**，本模组的 jar 同时可用于 Forge 1.20.1 与 NeoForge 1.20.1；
NeoForge 1.20.1 侧没有可用的 JEI，因此那一边 JEI 相关功能整体不启用。

## 1. 支持范围与策略

- **运行期最低支持：JEI 15.0.0.12**（`mods.toml` 的可选依赖范围就是它起步）。
- 编译基线：**JEI 15.3.0.8**（`libs/jei-api/jei-1.20.1-forge-15.3.0.8.jar`，`compileOnly`）。
  这是我们用到的最「年轻」的公共 API 集合：`IRecipeCategoryDecorator` 从 15.3 才有，
  15.0~15.2 没有配方页按钮能力，其余功能（面板定位/拖拽/书签/自动撰写）不受影响。
- 实机验证版本：15.74.0.233（`libs/jei-api/jei-1.20.1-forge-15.74.0.233.jar`），
  同时也是 `src/jeiModern` 源码集的编译目标。
- **不硬编码版本号分水岭**：更高版本才有的能力靠运行期「类/方法存在性」探测
  （`mc/jei/JeiCapabilities.java`），版本号只用于日志与提示。
- 未安装 JEI 时：常驻代码只调用 `JeiCapabilities` / `JeiRuntimeHolder`（零 `mezz.jei.*` 引用），
  插件类不会加载；进世界会提示一次 `[RecipeBalancer] JEI 未安装，快捷功能不可用`。

### 配方页按钮的三条路径

判定依据是**运行期能否加载到 API**，不是版本号：

| 判定条件 | 路径 | 说明 |
|---|---|---|
| 能加载 `IRecipeButtonControllerFactory` 且 `addRecipeButtonFactory` 存在 | `src/jeiModern` 源码集 | JEI 自己排布按钮与底板，和它的书签按钮同一列，**不会重叠** |
| 不能，但能加载 `IRecipeCategoryDecorator` | 装饰器自绘 + 左键边沿判定 | 自助绘制图案，点击靠鼠标边沿判定 |
| 都不能 | 无 | 日志打印「JEI 配方页按钮不可用」 |

## 2. 已核实的 API 差异（javap 实测本地 8 个 jar）

本地可测版本：15.0.0.12 / 15.3.0.8 / 15.12.2.51 / 15.20.0.127 / 15.21.0.148 / 15.48.0.179 / 15.56.0.205 / 15.74.0.233

**只有存在性是被验证的，精确引入版本没有验证**：样本之间是散的，只能把界限「夹住」。
表里写的是「实测无」和「实测有」两端，中间的真实引入版本未知（离线，拿不到中间版本 jar）。

| 能力 | 实测无 | 实测有 | 本模组对策 |
|---|---|---|---|
| `IGuiHandlerRegistration` 全部注册方法 | — | 15.0 ~ 15.74 全有 | 直接使用 |
| `IGlobalGuiHandler#getClickableIngredientUnderMouse(double,double)` | — | 15.0 ~ 15.74 全有 | R/U/A 悬停路径（始终注册） |
| `IRecipeCategoryDecorator#draw(...)` | 15.0.0.12 | 15.3.0.8 | 按钮自绘路径（缺则无按钮） |
| `ITooltipBuilder` / `IRecipeCategory#getTooltip(...)` | 15.3.0.8 | 15.12.2.51 | 基线用 `getTooltipStrings(...)` |
| `IJeiUserInput` | 15.3.0.8 | 15.12.2.51 | 基线用 `handleInput(T,double,double,Key)` |
| `IClickableIngredientFactory` / `IButtonState` / `IIconButtonController` / `IRecipeButtonControllerFactory` / `addRecipeButtonFactory` | 15.21.0.148 | 15.48.0.179 | 官方按钮路径（只在探测到时加载） |
| `IScreenHandler#getClickableIngredientUnderMouse(IClickableIngredientFactory,…)` | 15.21.0.148 | 15.48.0.179 | 不实现；悬停走全局处理器 |
| `IJeiRuntime#getBookmarkManager()` | 15.56.0.205 | 15.74.0.233 | 该接口没有「列出全部书签」，仍走反射；探测结果只写日志 |
| `IRecipeCategory#getBackground()` | 15.3.0.8（抽象） | 15.20.0.127（default） | 我们只读取，不实现 |
| `IIngredientHelper#getAmount` | 15.0 ~ 15.74（无） | — | 反射读配料自身的 `getAmount()` |
| `IIngredientType#getUid()` | 15.0 ~ 15.74（无） | — | 类型标识改用 `getIngredientClass().getName()` |
| `IIngredientManager#getIngredientTypeForUid` / `getTypedIngredientByUid` | 15.21.0.148 | 15.48.0.179 | 不使用（避免抬高基线） |
| `IIngredientManager#createTypedIngredient(type,value)` | — | 15.0 ~ 15.74 全有 | 统一用 2 参重载 |
| `IIngredientManager#getIngredientByUid(type,uid)` | — | 15.0 ~ 15.74 全有 | ingredient key 反查 |
| `IIngredientRenderer#render(GuiGraphics,T)` | — | 15.0 ~ 15.74 全有 | 位姿平移适配（19.x 才带 x,y 参数） |
| `IRecipeManager#createRecipeSlotDrawable(…,int,int,int)` | 15.3（抽象） | 15.48（default） | 只读取，不实现 |
| `IBookmarkOverlay#getIngredientUnderMouse()` | — | 15.0 ~ 15.74 全有 | 只能拿单个，故书签列表用反射 |

> 运行期一律按「能力探测」决定分支，因此上表的空白区间（例如按钮 API 到底从 15.2x 还是 15.3x
> 开始）不影响正确性：实例装什么版本，探测结果就跟着变。若后续联网，可补 15.22~15.47 的 jar
> 把「实测无/实测有」收敛成精确版本，并同步更新代码注释与本文。

## 3. 实现结构

| 文件 | 作用 | 是否含 `mezz.jei.*` |
|---|---|---|
| `JeiCapabilities` | 运行期能力探测 + 版本读取（只用于日志） | 否 |
| `JeiRuntimeHolder` | 持有 `IJeiRuntime`（`Object` 存），`installed()/ready()` | 否 |
| `JeiScreenSupport` | 补发 `ScreenEvent.Init.Post` + 反射刷新 JEI 面板属性 | 否（纯反射） |
| `JeiBookmarks` | 书签导入入口（先 `ready()` 短路） | 否 |
| `JeiBookmarkReader` | 书签反射实现（仅 JEI 就绪后加载） | 是 |
| `RecipeBalancerJeiPlugin` | `@JeiPlugin` 注册入口 | 是 |
| `ClientEditorMenu` | 纯客户端「零槽位空菜单」（`MenuType` 自造不注册、`containerId=0x7FFF`） | 否 |
| `RbContainerScreen` | 容器界面基类：中和点槽/标签/关闭链路 | 否 |
| `JeiGuiProperties` | 二级界面（仍是普通 `Screen`）的 `IGuiProperties` | 是 |
| `RbContainerGuiHandler` | 一级界面的 `IGuiContainerHandler`：额外区域 + 悬停配料 | 是 |
| `JeiGhostHandler` | JEI → 槽位拖拽判定（高亮交给 `JeiGhostHighlights` 自绘） | 是 |
| `JeiGhostHighlights` | 拖拽高亮矩形快照（绝对坐标） | 否 |
| `JeiHoverKeys` / `JeiLegacyClickHandler` | 悬停槽位 → 可点击配料（容器处理器为主，全局处理器兜底） | 是 |
| `JeiIngredientMapper` / `JeiIngredientRenderer` | 配料 ↔ `ingredient|…` key、非物品渲染与提示 | 是 |
| `JeiPatternImporter` / `JeiAutoRecipe` | JEI 配方 → 编码样板；「自动撰写」钩子 | 是 |
| `JeiLegacyRecipeButton` / `JeiCraftableSlots` | 15.3~15.47 的配方页按钮（装饰器自绘 + 左键边沿判定） | 是 |
| `JeiButtonSkin` | 13×13 按钮图案，**与 main 分支同一份逐像素表** | 否 |
| `src/jeiModern/RbRecipeButtonFactory` / `RbRecipeButtonController` / `JeiModernRegister` | 官方按钮 API 实现（用 JEI 15.74 编译，运行期按能力探测加载） | 是 |

## 3.1 一级界面容器化（0.3.0）

**动机**：与 1.18.2 分支统一结构；拿到 JEI 只对容器界面开放的
`IGuiContainerHandler`；为将来服务端也装模组时的配方转移留出菜单骨架。

**做法**：`BalancerScreen` 从普通 `Screen` 改为
`RbContainerScreen`（`AbstractContainerScreen<ClientEditorMenu>`），内容仍在
`renderBg(GuiGraphics, float, int, int)` 里整屏自绘，面板坐标即 `leftPos/topPos`、
`imageWidth/imageHeight = panelW/panelH`。

**纯客户端保证**（逐条中和原版容器行为，已按 1.20.1 源码核实）：

| 原版行为 | 触发点 | 本模组处理 |
|---|---|---|
| 点槽发包 `gameMode.handleInventoryMouseClick` | `AbstractContainerScreen#slotClicked` | `RbContainerScreen#slotClicked` 空实现；空菜单没有槽位，`hoveredSlot` 恒为 null |
| 点击面板外丢弃物品（`slotId=-999`） | `mouseClicked` → `slotClicked` | 同上，被空实现吞掉 |
| E 键关闭（`player.closeContainer()`） | `keyPressed` → `onClose` | `BalancerScreen#keyPressed` 在输入框未聚焦时直接吞掉背包键 |
| 关闭界面 `player.closeContainer()` | `onClose` | 覆写为只 `setScreen(null)`（落库/草稿/命名/缩放还原顺序不变） |
| 容器标题/「物品栏」标签 | `renderLabels` | 空实现（界面自绘） |
| 快捷栏交换 / 丢弃 / 拾取 | `keyPressed`、`checkHotbarMouseClicked` | 都要求 `hoveredSlot != null`，空菜单永远走不到 |
| 玩家死亡时 `closeContainer()` | `AbstractContainerScreen#tick` | 原版行为：死亡本身就会关界面，保留 |

菜单不注册 `MenuType`/`MenuScreens`，`containerId` 取 `0x7FFF`（服务端 id 从 1 递增，撞不到），
从不发送任何容器包，因此多人游戏、服务端未装本模组时照常工作。

**JEI 对接变化**：

| 项目 | 容器化前 | 容器化后 |
|---|---|---|
| 面板属性 | `addGuiScreenHandler(BalancerScreen, JeiGuiProperties)`，`guiXSize` 虚报为 `panelW + 2*SIDE_PAD` | JEI 自带的 `AbstractContainerScreen` 默认处理器按 `leftPos/topPos/imageWidth/imageHeight` 推导 |
| 左右图标列 | 靠虚报宽度让面板让开 | `RbContainerGuiHandler#getGuiExtraAreas` 报两块额外区域 |
| 悬停 R/U/A | 全局处理器 `JeiLegacyClickHandler` | `RbContainerGuiHandler#getClickableIngredientUnderMouse` 为主（JEI 传绝对鼠标坐标），全局处理器保留兜底 |
| 拖拽高亮 | 交给 JEI 画 | `shouldHighlightTargets()=false`，界面按同一份绝对坐标自绘（普通 `0x4013C90A`、悬停 `0x804CC959`） |

二级界面（`AmountScreen`/`KeySetScreen`/`TimeScreen`/`ScenarioListScreen`/`SolveScreen`/`FactorScreen`）
仍是普通 `Screen`，继续走 `addGuiScreenHandler` + `JeiGuiProperties`，行为不变。

**拖拽高亮统一自绘**：`KeySetScreen`（无限外部供应 / 循环亏损补充的物料清单）虽然还是普通 `Screen`，
但 JEI 对非容器界面会经 `drawTooltips` 与 `drawOnForeground` 两条路径各画一遍幽灵高亮，
实机上就是「同一块网格画了两次」。因此它和一级界面共用 `JeiGhostHighlights#draw`：
`shouldHighlightTargets()` 返回 `false`，界面在内容画完后自己画一次。

## 4. 实机验证矩阵（1.20.1-Forge 实例）

做法：把目标版本的 `jei-1.20.1-forge-*.jar` 放进
`...\.minecraft\versions\1.20.1-Forge\mods\`（备份在 `mods\JEI-verBackup\`，同目录只保留一个 `jei-*.jar`），
启动后按清单点检，并抓 `logs/latest.log` 中 `[RecipeBalancer]` 与 `jei` 的 WARN/ERROR。

**已实机确认（0.1.6-1.20.1）**：15.3.0.8、15.12.2.51、15.74.0.233 —— 面板定位、JEI 拖拽录入、
书签导入、配方页按钮（两种路径）与配方库高亮均正常；其余样本（15.0.0.12 / 15.20 / 15.21 / 15.48 / 15.56）未跑完整清单。

> 15.0.0.12：除「配方页按钮」外与 15.3 相同（15.0~15.2 没有 `IRecipeCategoryDecorator`，
> 日志会打印 `JEI 配方页按钮不可用`，属预期降级）。

| 用例 | 15.0.0.12 | 15.3.0.8 | 15.12.2.51 | 15.20/15.21 | 15.48 | 15.56 | 15.74 | 无 JEI |
|---|---|---|---|---|---|---|---|
| 启动无 `NoClassDefFound` / `NoSuchMethod` | | | | | | | ✔（dev 已验证） |
| 日志出现 `[RecipeBalancer] JEI 15.x…`、「容器处理器：已注册」与「全局处理器兜底」 | | | | | | | n/a |
| JEI 面板不遮挡本模组 GUI（左右图标列让位） | | | | | | | n/a |
| 从 JEI 拖物品到槽位（绿色高亮正好压在格子上） | | | | | | | n/a |
| 书签一键导入 | | | | | | | n/a |
| 配方页出现「编码此配方」按钮并可用 | | | | | | | n/a |
| 悬停槽位 R/U/A | | | | | | | n/a |
| 未装 JEI 时入口提示「JEI 未安装」、其余功能正常 | n/a | n/a | n/a | n/a | n/a | n/a | |

## 5. 已知限制

- `runClient` 无法验证 JEI：把 JEI 放进 `localRuntime` 后，JEI 自身的 config 注册在 userdev 会抛异常
  （JEI 只支持正式环境），因此带 JEI 的验证只能在实机实例做。
- 书签列表靠反射 JEI 内部字段，跨版本失败时返回空表（界面提示「JEI 书签为空」），不影响其它功能。
- JEI 面板属性刷新同样靠反射，失败时退回 `ScreenEvent.Init.Post`（只告警一次）。
- 容器处理器实现的是 15.x 全区间都有的
  `getClickableIngredientUnderMouse(T, double, double)`（老式重载）；更新的
  「工厂重载」没有实现，其默认实现会转调老式重载，因此不需要按版本分支。
- 容器化后 `AbstractContainerScreen#tick` 在玩家死亡时会调用 `player.closeContainer()`：
  这是原版行为（死亡本来就会关界面），未做额外处理。
- 上表版本号只表示「该样本里有 / 没有」，不代表精确引入版本；判定始终走运行期能力探测。
