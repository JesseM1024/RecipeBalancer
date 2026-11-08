# RecipeBalancer × JEI 兼容说明（1.18.2 / Forge）

## 支持区间

| 项目 | 版本 |
|---|---|
| 主源码集编译基线（`compileOnly`） | `jei-1.18.2-9.4.1.113` |
| `src/jeiModern` 源码集编译目标 | `jei-1.18.2-forge-10.81.0.1029` |
| 运行期声明（`mods.toml` 可选依赖） | `[9.4.1.113,)`，`mandatory=false` |

判定方式不是版本号，而是运行期能力探测（`mc/jei/JeiCapabilities`）：直接问 JVM「这个类/方法在不在」，
结果只算一次。因此同一份 jar 在 9.4 ~ 10.81 的整个区间都能加载，且新增能力可自动生效。

## 功能矩阵

| 功能 | 实现 | 9.x | 10.x | 备注 |
|---|---|---|---|---|
| 面板定位（JEI 面板不压住界面） | `JeiGuiProperties` + `addGuiScreenHandler` | ✔ | ✔ | 二级界面用；一级界面改用容器路径 |
| 一级界面额外区域（左右图标列） | `RbContainerGuiHandler#getGuiExtraAreas` | ✔ | ✔ | 容器化后的主路径 |
| 悬停 R/U/A | `RbContainerGuiHandler#getIngredientUnderMouse` | ✔ | ✔ | 返回裸配料，由 JEI 包装 |
| 悬停兜底 | `JeiHoverHandler`（`IGlobalGuiHandler`） | ✔ | ✔ | 容器回调不可用时仍能工作 |
| 拖拽录入（物品/流体/化学品） | `JeiGhostHandler` / `JeiKeySetGhostHandler` | ✔ | ✔ | 编码格、设备、目标、物料清单 |
| 右键「自动撰写」 | `JeiAutoRecipe` + `JeiPatternImporter` | ✔ | ✔ | 单配方直接写入，多配方打开 JEI |
| 书签导入 | `JeiBookmarks` / `JeiBookmarkReader`（反射内部列表） | ✔ | ✔ | 9.x 读 `getIngredientList`，10.x 读 `getElements` |
| 非物品配料渲染 / 名称 | `JeiIngredientRenderer` | ✔ | ✔ | 流体、化学品按 JEI 渲染器绘制 |
| 配方页「编码此配方」按钮 | 10.x：`src/jeiModern` 的 `RbRecipeButtonController`；9.x：`JeiRecipeOverlay` 客户端叠加层 | ✔ | ✔ | 两条路只启用一条，见下节 |

## 配方页「编码此配方」按钮（9.x / 10.x 统一）

交互与 AE 样板编码终端一致：**在 JEI 配方页上点按钮 → 该配方被转换成编码样板 → 直接打开编码终端
（已预填，复核后点「写入」）**。两条实现按能力自动选择，同一页只会出现一个按钮：

| 版本 | 实现 | 说明 |
|---|---|---|
| JEI 10.x | `RbRecipeButtonController`（官方 `IRecipeButtonControllerFactory`） | 由 JEI 负责按钮位置/图标/点击分发 |
| JEI 9.x | `JeiRecipeOverlay`（客户端叠加层） | 在每份配方布局右上角自绘 13×13 按钮；`DrawScreenEvent.Post` 绘制、`MouseClickedEvent.Pre` 接管点击 |

9.x 叠加层的配方来源：反射读 `RecipesGui.recipeLayouts`（当前页的 `RecipeLayout` 列表），
逐份取 `getPosX/getPosY`（配方背景左上角）、`getRecipeCategory()`、`getRecipeSlots().getView()`。
槽位矩形由 `JeiSlots` 统一反射获取（9.x 的 `RecipeSlot` / 10.x 的 `IRecipeSlotDrawable` 都有
`public Rect2i getRect()`），因此两代都能还原合成配方的九宫格形状。

**外观与摆位与 1.20.1 严格一致**：

- 图案来自 `JeiButtonSkin`（13×13 逐像素表 + 悬停 1px 白框），与 10.x 官方按钮共用同一份像素表；
- 摆位沿用 1.20.1 `JeiLegacyRecipeButton#place`：优先放在配方右侧的按钮列（JEI 自带按钮上方一格），
  放不下时退到配方外上方，最后才是配方内部的两个角落；每个候选位置都会用槽位矩形做重叠检测；
- 按钮**不压在槽位上**，因此悬停时 JEI 不会再叠一层物品提示（此前的「tooltip 两层文字」就是
  按钮压住槽位导致的）。点击切屏排在主线程下一 tick 执行，避免 JEI 渲染栈错位。

日志会明确当前走哪条路：

```
[RecipeBalancer] JEI 配方页按钮：已注册（按钮 API，<版本>）          # 10.x
[RecipeBalancer] JEI 配方页按钮：官方 API 不可用（<版本>），改用客户端叠加层  # 9.x
```

若 9.x 叠加层不可用（JEI 内部字段改名），会打印一次 warn，不影响其它 JEI 功能。

## 为什么 9.x 不能直接用「配方转移」按钮

JEI 9.x 只有两种实现途径，均不可行（本模组是**纯客户端**，多人游戏服务端可能未安装）：

1. **配方转移处理器**（`IRecipeTransferRegistration#addRecipeTransferHandler`）：
   `IRecipeTransferHandler#transferRecipe(...)` 由 **JEI 在服务端执行** —— JEI 把
   `RecipeTransferPacket` 发给服务端，服务端查表并操作服务端菜单。服务端没装本模组时，
   既没有该菜单也没有该处理器，按钮点了也无效。
2. **类别装饰器 / 按钮工厂**：`IRecipeCategoryDecorator`、`IRecipeButtonControllerFactory`
   都是 **JEI 10.x 才引入**，9.x 根本不存在。

因此本次容器化的收益边界是：**拿到 JEI 只对容器界面开放的客户端 API**
（`IGuiContainerHandler` 的悬停配料、额外区域、可点击区域），
而不是配方页按钮。10.x 上按钮由官方 API 提供，与容器化无关。

## 容器化设计（方案 A：外壳容器化）

```
BalancerScreen (AbstractContainerScreen)
├── ClientEditorMenu        零槽位、MenuType=null、containerId=0x7FFF、纯客户端
├── RbContainerScreen       tooltip 可见性提升 + 中和原版容器行为
│   ├── slotClicked(...)    空实现 → 绝不触发 gameMode.handleInventoryMouseClick
│   ├── renderLabels(...)   空实现 → 文字由界面自绘
│   └── onClose()           只 setScreen(null)，不调 player.closeContainer()
├── renderBg(...)           原「背景 + 面板 + 内容」绘制（自带鼠标坐标，供悬停高亮）
└── render(...)             updateOptionStates → super.render（renderBg + 控件）→ 手持 key → tooltip
```

- **零网络交互**：没有服务端 `MenuType`、没有槽位同步、不发送任何容器包；
  多人游戏服务端未装本模组时行为与普通界面一致。
- **交互不变**：编码区/目标/配方库/背包仍为自绘，`carriedKey` 字符串手持、中键改量、
  右键自动撰写、小数用量、化学品与自定义物质（如 `MgCl2`）全部保留。
- **视觉不变**：面板坐标仍由 `panelX/panelY/panelW/panelH` 决定，
  `imageWidth/imageHeight` 与 `leftPos/topPos` 只是同步给原版容器基类。

## 实机验证清单

JEI 9.4.1.113 与 10.81.0.1029 各跑一轮：

- [ ] 打开一级界面：布局、分界线、左右 18×18 图标列位置不变
- [ ] JEI 书签/物品面板不再盖住左右图标列（额外区域生效）
- [ ] 槽位悬停按 R/U/A 能查配方（容器处理器路径）
- [ ] 从 JEI 拖物品/流体到编码格、设备格、目标格、物料清单
- [ ] 编码区右键「自动撰写」：单配方直接写入，多配方打开 JEI
- [ ] 物料清单「读取书签」
- [ ] 点击/按键均不产生容器包（无回弹、无幽灵物品、日志无 `handleInventoryMouseClick` 相关异常）
- [ ] GUI 缩放自适应：小窗口降级、放大还原、关闭还原
- [ ] 9.x：配方页每份配方右上角出现「+」按钮，点击后编码终端已预填该配方
- [ ] 10.x：官方按钮出现且点击后编码终端已预填该配方（与 9.x 行为一致）
