# JEI 兼容说明（1.21.1 / NeoForge / JEI 19.x）

适用分支：`main`（产物 `recipebalancer-0.3.0-1.21.1.jar`）

- 编译基线：`libs/jei-api/jei-1.21.1-common-api-19.8.4.110.jar`（只允许直接引用该版本已有的 API）；
  19.39+ 才有的能力（配方页按钮 API）放在 `src/jeiNew` 源码集，运行期按类是否存在反射加载。
- 开发运行期 JEI：`19.39.0.372`；实机实例 `1.21.1-NeoForge` 为 `19.70.0.467`。
- 未装 JEI 时本模组必须照常工作：所有 JEI 相关类都只经插件入口加载，界面与求解不依赖 JEI。

## 1. 文件与职责

| 文件 | 作用 | 是否含 `mezz.jei.*` |
|---|---|---|
| `JeiRuntimeHolder` | 持有 `IJeiRuntime`（`Object` 存），`installed()/ready()` | 否 |
| `JeiScreenSupport` | 补发 `ScreenEvent.Init.Post` + 反射刷新 JEI 面板属性缓存 | 否（纯反射） |
| `JeiBookmarks` / `JeiBookmarkReader` | 书签导入（反射内部列表，失败返回空表） | 是 |
| `RecipeBalancerJeiPlugin` | `@JeiPlugin` 注册入口 | 是 |
| `ClientEditorMenu` | 纯客户端「零槽位空菜单」（`MenuType` 自造不注册、`containerId=0x7FFF`） | 否 |
| `RbContainerScreen` | 容器界面基类：中和点槽/标签/关闭链路 | 否 |
| `JeiGuiProperties` | 二级界面（仍是普通 `Screen`）的 `IGuiProperties` | 是 |
| `RbContainerGuiHandler` | 一级界面的 `IGuiContainerHandler`：额外区域 + 悬停配料 | 是 |
| `JeiGhostHandler` / `JeiKeySetGhostHandler` | JEI → 槽位拖拽判定（高亮交给 `JeiGhostHighlights` 自绘） | 是 |
| `JeiGhostHighlights` | 拖拽高亮矩形快照（绝对坐标），`draw(GuiGraphics,...)` | 否 |
| `JeiHoverKeys` / `JeiLegacyClickHandler` | 悬停槽位 → 可点击配料（容器处理器为主，全局处理器兜底） | 是 |
| `JeiCraftableSlots` / `JeiButtonSkin` / `JeiLegacyRecipeButton` | 配方库标蓝、配方页按钮（装饰器兜底路线） | 是 |

## 2. 一级界面容器化

**动机**：JEI 只对 `AbstractContainerScreen` 开放容器处理器（额外区域 / 悬停配料）；
同时与 1.18.2、1.20.1 统一结构，并为将来服务端也装模组时的配方转移留出菜单骨架。

**做法**：`BalancerScreen` 从普通 `Screen` 改为 `RbContainerScreen`
（`AbstractContainerScreen<ClientEditorMenu>`），内容仍在 `renderBackground(...)` 里整屏自绘，
面板坐标即 `leftPos/topPos`、`imageWidth/imageHeight = panelW/panelH`。

**纯客户端保证**（逐条中和原版容器行为）：

| 原版行为 | 触发点 | 本模组处理 |
|---|---|---|
| 点槽发包 `gameMode.handleInventoryMouseClick` | `AbstractContainerScreen#slotClicked` | `RbContainerScreen#slotClicked` 空实现；空菜单没有槽位，`hoveredSlot` 恒为 null |
| 点击面板外丢弃物品（`slotId=-999`） | `mouseClicked` → `slotClicked` | 同上，被空实现吞掉 |
| E 键关闭（`player.closeContainer()`） | `keyPressed` → `onClose` | `BalancerScreen#keyPressed` 在输入框未聚焦时直接吞掉背包键 |
| 关闭界面 `player.closeContainer()` | `onClose` | 覆写为只 `setScreen(null)`（落库/草稿/命名/缩放还原顺序不变） |
| 容器标题/「物品栏」标签 | `renderLabels` | 空实现（界面自绘） |
| 快捷栏交换 / 丢弃 / 拾取 | `keyPressed`、`checkHotbarMouseClicked` | 都要求 `hoveredSlot != null`，空菜单永远走不到 |
| 玩家死亡时 `closeContainer()` | `AbstractContainerScreen#tick`（1.21.1 为 final） | 原版行为：死亡本身就会关界面，保留 |

菜单不注册 `MenuType`/`MenuScreens`，`containerId` 取 `0x7FFF`（服务端 id 从 1 递增，撞不到），
从不发送任何容器包，因此多人游戏、服务端未装本模组时照常工作。

**JEI 对接变化**：

| 项目 | 容器化前 | 容器化后 |
|---|---|---|
| 面板属性 | `addGuiScreenHandler(BalancerScreen, JeiGuiProperties)`，`guiXSize` 虚报为 `panelW + 2*SIDE_PAD` | JEI 自带的 `AbstractContainerScreen` 默认处理器按 `leftPos/topPos/imageWidth/imageHeight` 推导 |
| 左右图标列 | 靠虚报宽度让面板让开 | `RbContainerGuiHandler#getGuiExtraAreas` 报两块额外区域 |
| 悬停 R/U/A | `jeiNew` 的 `IScreenHandler`（19.39+）或全局处理器 | `RbContainerGuiHandler#getClickableIngredientUnderMouse` 为主（JEI 19 会把容器处理器并入查询流），全局处理器 `JeiLegacyClickHandler` 保留兜底 |
| 菜单/生命周期 | 普通 `Screen` | 空菜单容器界面；`getType()` 有值（自造不注册类型），不会空指针 |

二级界面（`AmountScreen`/`KeySetScreen`/`TimeScreen`/`ScenarioListScreen`/`SolveScreen`/`FactorScreen`）
仍是普通 `Screen`，继续走 `addGuiScreenHandler` + `JeiGuiProperties`，行为不变。

## 3. 幽灵拖拽高亮：改由本界面自绘

JEI 对普通 `Screen` 会经 `drawTooltips` 与 `drawOnForeground` 两条路径各画一遍幽灵高亮，
实机上表现为「同一块区域画了两次」（两层叠加更亮、边缘更糊）；
容器界面又按版本改变绘制通道与坐标空间。

因此本模组只让 JEI 负责**拖拽判定**：`Target#getArea()` 一律返回绝对屏幕坐标
（鼠标命中、投放都按绝对坐标走），高亮本身由 `JeiGhostHandler` / `JeiKeySetGhostHandler`
把矩形交给 `JeiGhostHighlights`，再由界面在内容画完后自绘（颜色取 JEI 默认：
普通目标 `0x4013C90A`、鼠标悬停 `0x804CC959`），两个处理器的
`shouldHighlightTargets()` 都返回 `false`。

- 主界面：`BalancerScreen#render` 在 `super.render` 之后自绘，`init()` / `onClose()` 清理；
- 物料清单屏：`KeySetScreen#render` 在网格与背包画完后自绘，`init()` / `onClose()` 清理。

拖拽开始时会打一行诊断日志，便于排查坐标问题：

```
[RecipeBalancer] JEI ghost 开始: 共 39 个, 首=(243,52 18x18), 中=…, 末=…, GUI 原点=(235,4), 屏幕=640x377
```

## 4. 版本差异要点（已逐 jar 核实）

| 能力 | 19.8.4（编译基线） | 19.39+ | 19.70（实机） |
|---|---|---|---|
| `IGuiContainerHandler` | `getGuiExtraAreas` + 老式悬停重载 | 同左 | 新增工厂重载，**默认实现转调老式重载** |
| `IGlobalGuiHandler` | 老式悬停重载 | 同左 | 新增工厂重载，默认转调老式重载 |
| `IScreenHandler` | 只有 `apply`（面板属性） | 新增 `getClickableIngredientUnderMouse`（工厂版） | 同左 |
| 容器界面面板属性 | JEI 自带 `AbstractContainerScreen` 默认处理器 | 同左 | 同左 |

> 结论：容器处理器只实现老式重载即可覆盖 19.8.4 ~ 19.70；
> 全局处理器同理，不需要再按版本分支注册。

## 5. 已知限制

- `runClient` 无法验证 JEI：JEI 的 config 注册在 dev 环境会抛异常，带 JEI 的验证只能在实机实例做。
- 书签列表、面板属性刷新靠反射 JEI 内部字段/方法，跨版本失败时降级（空表 / 退回 `Init.Post`），不影响其它功能。
- 空菜单没有真实槽位，因此**没有**服务端配方转移（`IRecipeTransferHandler`）；
  将来若服务端也装本模组，需要补真实槽位与转移处理器，本轮不做。
- 玩家死亡时 `AbstractContainerScreen#tick` 会调用 `player.closeContainer()`：这是原版行为，未做额外处理。

## 6. 实机验证清单（1.21.1-NeoForge）

| 用例 | 结果 | 备注 |
|---|---|---|
| 启动无 `NoClassDefFound` / `NoSuchMethod` | | |
| 日志出现「JEI 容器处理器：已注册（一级界面）」「悬停查询：容器处理器 + 全局兜底」 | | |
| 主界面面板位置、分界线、左右图标列与改造前一致 | | |
| 点界面无物品回弹/幽灵物品；日志无 `handleInventoryMouseClick`/`closeContainer` 异常 | | |
| JEI 物品/书签面板不再压住左右图标列 | | |
| 悬停编码格/目标格/配方库/背包：R/U/A 正常 | | |
| 从 JEI 拖物品到格子：绿色高亮正好压在格子上且只有一层 | | |
| 物料清单屏（无限外部供应/循环亏损补充）拖拽：高亮只画一次 | | |
| 配方库标蓝、右键自动撰写、书签导入 | | |
| 配方页「编码此配方」按钮（19.39+ 工厂 / 低版本装饰器） | | |
| 窗口缩放往返、关闭界面后落库/草稿/命名/缩放还原 | | |
