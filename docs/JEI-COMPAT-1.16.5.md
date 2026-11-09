# JEI 兼容说明（1.16.5 / Forge 36.2.x / JEI 7.6 ~ 7.8）

适用分支：`1.16.5-forge`（产物 `recipebalancer-0.3.0-1.16.5.jar`）

- 编译基线：`libs/jei-api/jei-1.16.5-7.6.1.75.jar`（只允许直接引用该版本已有的 API）；
  实机验证样本：`7.8.1.1018`（`mods\.jei-ver` 里还有 7.6.1 ~ 7.8.1 的完整矩阵）。
- 构建：ForgeGradle **5.1.77** + Gradle **7.6.4** + MCP `snapshot 20210309-1.16.5` + Forge `1.16.5-36.2.42`，
  编译目标 **Java 8**（1.16.5 世代的上限）；Gradle 守护进程用 JDK 17。
- 未装 JEI 时本模组照常工作：JEI 相关类只经 `@JeiPlugin` 加载链出现，界面层不直接引用 `mezz.jei.*`。

## 1. 与 1.18.2 的接口差异（逐 jar 核实）

| 能力 | 1.18.2（JEI 9.x/10.x） | 1.16.5（JEI 7.6 ~ 7.8） |
|---|---|---|
| 容器处理器 | `IGuiContainerHandler#getGuiExtraAreas` 返回 `List<Rect2i>` | 返回 `List<net.minecraft.client.renderer.Rectangle2d>` |
| 悬停配料 | `Optional<IClickableIngredient<?>>`（9.x）/ 工厂重载（10.x） | **`Object`**（裸配料对象，如 `ItemStack`/`FluidStack`） |
| 全局悬停 | `IGlobalGuiHandler#getClickableIngredientUnderMouse` | `IGlobalGuiHandler#getIngredientUnderMouse` → `Object` |
| 拖拽 | `getTargetsTyped(T, ITypedIngredient<I>, boolean)` | `getTargets(T, I, boolean)`（无 `ITypedIngredient`） |
| 配方页按钮 | 10.81+ 官方 `IIconButtonController`；9.x 用 `ScreenEvent` 叠加层 | **没有**按钮 API，也没有 `IRecipeCategoryDecorator`（7.6/7.8 都没有）→ 反射叠加层 |
| 配方查询 | `getRecipes(category, focus, true)`（带 includeHidden） | `getRecipes(category, focus)`（7.6 只有单参；7.8 用 default 方法补齐） |
| 面板属性 | `addGuiScreenHandler(Class, IScreenHandler)` | 同名，`IScreenHandler` 仍是 `Function<T, IGuiProperties>` |
| 事件 | `ScreenEvent.Init.Post` / `Render.Post` | `GuiScreenEvent.InitGuiEvent.Post` / `DrawScreenEvent.Post` / `MouseClickedEvent.Post` |

> 结论：容器处理器与全局处理器都只需返回「裸配料对象」，实现一份即可覆盖 7.6.1 ~ 7.8.1。

## 2. 一级界面容器化（1.16.5 版）

`BalancerScreen` 从普通 `Screen` 改为 `ContainerScreen<ClientEditorContainer>`：

- `ClientEditorContainer`：零槽位空容器。`ContainerType` 用 Forge 的
  `IForgeContainerType.create(...)` **自造但不注册**（1.16.5 的 `ContainerType` 构造器是 protected），
  `containerId = 0x7FFF`、`canInteractWith` 恒 true、`transferStackInSlot` 返回空、`onContainerClosed` 空；
- `RbContainerScreen`：中和原版容器行为 —— `handleMouseClick` 空实现（不发
  `handleInventoryMouseClick`，也吞掉点面板外的 `slotId=-999` 丢弃）、
  `drawGuiContainerForegroundLayer` 空实现（不画标题/「物品栏」）、
  `drawGuiContainerBackgroundLayer` 空实现（背景由子类整屏自绘）；
- `xSize/ySize = panelW/panelH`，`super.init()` 之后覆写 `guiLeft/guiTop = panelX/panelY`；
- 背包键在 `keyPressed` 里被吞掉（输入框聚焦时不吞），避免 `ContainerScreen#keyPressed` 触发 `onClose()`→`closeContainer()`；
- JEI 侧：面板让位改用 `RbContainerGuiHandler#getGuiExtraAreas`（左右两列图标），
  R/U/A 走 `getIngredientUnderMouse`，全局 `JeiHoverHandler` 保留兜底。

## 3. 关闭语义（1.16.5 的第一风险点）

1.16.5 的 `Minecraft#displayGuiScreen(newScreen)` 会调用**旧屏**的 `onClose()`
（`Minecraft.java` 864 行；`popGuiLayer` 同理）。因此：

| 位置 | 规则 |
|---|---|
| `onClose()` | **只做收尾**（落库/草稿/命名/缩放还原/清高亮），**绝不切屏** |
| 「返回 / 取消 / 确定」按钮 | 直接 `minecraft.displayGuiScreen(parent)`（此时旧屏的 `onClose` 会被回调一次，正常收尾） |
| ESC | 主界面交给 `Screen#closeScreen()`；二级界面在自己的 `keyPressed` 里拦截 256 并 `displayGuiScreen(parent)` |

任一处在 `onClose()` 里再次切屏都会造成 `displayGuiScreen ↔ onClose` 无限递归 → `StackOverflowError`。

## 4. 配方页「编码此配方」按钮（叠加层）

7.6 ~ 7.8 没有任何官方的配方页扩展点，实现方式：

1. 反射 `mezz.jei.gui.recipes.RecipesGui` 的私有字段 `recipeLayouts`（`List<RecipeLayout<?>>`）；
2. 每个布局的矩形 = `getPosX()/getPosY()` + 类别背景图尺寸；
3. 槽位 = `getItemStacks()/getFluidStacks()` 的 `IGuiIngredient` → 实现类 `GuiIngredient#getRect()`（绝对坐标）；
4. 按钮摆位：右上角内侧 → 右侧外侧 → 左侧外侧 → 左上角，**任一候选压住槽位就跳过**（按钮压槽会让 JEI 叠两层物品提示）；
5. 绘制时机：`GuiScreenEvent.DrawScreenEvent.Post`；悬停时把「配方库能自产」的槽位标蓝
   （填充 `0x40004688`、1px 边框 `0x9F004E96`，与 1.20.1 / 1.18.2 严格一致）；
6. 点击：`GuiScreenEvent.MouseClickedEvent.Post` → `JeiPatternImporter.build(...)` →
   `BalancerScreen.withPattern(...)`；配方仍需玩家复核后点「写入」。

所有反射都是「取到就用、取不到只告警一次」，异常不会影响主界面与求解。

## 5. Java 8 降级清单

| 原写法 | 1.16.5 写法 |
|---|---|
| `record X(...)` | 手写 `final class` + 同名访问器 + `equals/hashCode/toString`（数组字段按引用比较，与 record 一致） |
| `List.of/Set.of/Map.of`、`List.copyOf` | `core/util/J8` 的 `list/set/map/listCopy/mapCopy`（不可变；`map` 用 `LinkedHashMap` 保序） |
| `String#isBlank` | `J8.isBlank`（逐字符 `Character.isWhitespace`，**不是** `trim().isEmpty()`——后者漏全角空格 U+3000） |
| `String#repeat` | `J8.repeat` |
| `switch` 箭头 | 传统 `switch` + `break` |
| 模式匹配 `instanceof` | 显式强转 |
| `Stream#toList` | `collect(Collectors.toList())` |
| `Files.readString/writeString` | `readAllBytes` / `write(getBytes)` |
| `InputStream.readAllBytes` | 手写 `ByteArrayOutputStream` 循环 |
| `Path.of` | `Paths.get` |
| 文本块 `"""` | 拼接 + `\n` |

`core` 的行为一致性由 `src/test` 的金标准对拍保证：`gradlew test -PcoreOnly` 只编译 core，
11 个测试套件、**84 个用例全绿**（`GoldenParityTest` / `InvariantsTest` / `IoAndReportTest` 等）。

## 6. 平台层映射对照（易错点）

| 1.18.2 | 1.16.5 |
|---|---|
| `GuiGraphics` | `mc/ui/Draw`（内部 `MatrixStack`） |
| `PoseStack` | `com.mojang.blaze3d.matrix.MatrixStack`，`getLast().getMatrix()` |
| `Component` / `MutableComponent` | `ITextComponent` / `IFormattableTextComponent`（`StringTextComponent`） |
| `withStyle(...)` | `mergeStyle(...)`（`Style.EMPTY.setColor(Color.fromInt(argb))`） |
| `font.width(s)` | `font.getStringWidth(s)`；`font.lineHeight` → `font.FONT_HEIGHT` |
| `Screen#renderBackground(GuiGraphics,int,int,float)` | 只有 `renderBackground(MatrixStack)`；主界面背景改由 `drawGuiContainerBackgroundLayer` 绘制 |
| `AbstractContainerScreen` / `MenuType` | `ContainerScreen` / `ContainerType` |
| `stillValid/quackMoveStack/removed` | `canInteractWith/transferStackInSlot/onContainerClosed` |
| `slotClicked` | `handleMouseClick` |
| `ScreenEvent.*` | `GuiScreenEvent.*` |
| `Mth` | `MathHelper` |
| `EditBox`（`getValue/setValue`） | `TextFieldWidget`（`getText/setText`、`setMaxStringLength`） |
| `font.plainSubstrByWidth` | `font.trimStringToWidth` |
| `SimpleSoundInstance.forUI` | `SimpleSound.master` |
| `Util.getPlatform()` | `Util.getOSType()` |
| `Minecraft#getWindow/options/font` | `getMainWindow()/gameSettings/fontRenderer` |
| `resizeDisplay()` | `updateWindowSize()` |
| `ClientRegistry`（`net.minecraftforge.client`） | `net.minecraftforge.fml.client.registry.ClientRegistry` |

## 7. 实机验证清单（1.16.5-Forge 实例）

| 用例 | 结果 | 备注 |
|---|---|---|
| 启动无 `NoClassDefFoundError` / `NoSuchMethodError` | | 日志应出现「JEI 容器处理器：已注册」「JEI 配方页叠加层：已注册」 |
| K 打开界面、ESC 关闭、再次打开 | | 不应出现 `StackOverflowError` |
| 面板位置 / 分界线 / 左右图标列与 1.18.2 一致 | | |
| 点界面无物品回弹/幽灵物品；日志无 `handleInventoryMouseClick` 异常 | | 空容器没有槽位 |
| 名称框输入、目标编辑（中键）、配方库载入/删除 | | |
| 求解 → 结果页（分段配色、显示名/注册名切换、复制文本） | | |
| 流程图拖拽/缩放/点选节点 | | 图标放大缩小是否正常（`renderItemPosed` 走的是 GUI 平面渲染） |
| 返回按钮 / ESC 从二级页回到主界面 | | 四种关闭路径都不递归 |
| JEI 悬停编码格/目标格/配方库/背包 → R/U/A | | |
| 从 JEI 拖物品到格子／物料清单屏网格 | | 绿色高亮只画一层、正好压在格子上 |
| 配方页「编码此配方」按钮（按钮在配方右上角，不压槽位） | | 悬停时配方库能自产的槽位标蓝 |
| 书签导入、配方导入、右键自动撰写 | | |
| 分别用 `.jei-ver` 的 7.6.1.75 / 7.7.1.x / 7.8.1.1018 各跑一轮 | | 7.6 只保证编译基线 API |

## 8. 已知限制

- 没有服务端配方转移（`IRecipeTransferHandler`）：编码只是本地草稿。
- `JeiRecipeOverlay` 依赖 JEI 内部字段 `RecipesGui#recipeLayouts`，跨大版本会失效（有一次性告警，功能降级但不崩）。
- 书签读取同样是反射 JEI 内部结构，失败时返回空表。
- `runClient` 不能验证 JEI（JEI 的 config 注册在 userdev 会抛异常），JEI 相关验证一律在实机实例做。
