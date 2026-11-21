# JEI 兼容说明（1.12.2 / Forge 14.23.5 / JEI 4.7.8 ~ 4.24）

适用分支：`1.12.2-forge`（产物 `recipebalancer-0.3.1-1.12.2.jar`）

- **编译基线**：`libs/jei-api/jei-1.12.2-4.8.5.160.jar`（只允许直接引用该版本已有的 API）。
- **加载下限**：`4.7.8`（`mcmod.info` 声明的可选依赖是 `after:jei@[4.7.8,)`）。
- **完整能力下限**：`4.8.5.160`；低于它仍能启动，但拖拽、普通界面面板让位、书签导入逐项降级，
  并在**首次进入世界时于聊天栏提示一次**（语言文件键：`检测到低版本 JEI（%s）…`）。
- 未装 JEI 时全部功能照常：JEI 相关类只经 `@JEIPlugin` 加载链出现，界面层不直接引用 `mezz.jei.*`。

> 本文件的版本边界全部由本机 `.jei-backup` 里 180+ 个 1.12.2 jar 逐版本 `javap` / `jar tf` 核实，
> 不是凭记忆写的。

## 1. 类存在性（首次出现的版本）

| 类 | 路径 | 出现版本 |
|---|---|---|
| `IModPlugin` / `@JEIPlugin`（**大写 I**）/ `BlankModPlugin` | `mezz.jei.api` | 全版本（4.7.9.96 起） |
| `IJeiRuntime` / `IRecipeRegistry` / `IRecipesGui` / `IModRegistry` | `mezz.jei.api` | 全版本（**不是** `api.runtime`） |
| `IAdvancedGuiHandler` | `mezz.jei.api.gui` | 全版本 |
| `IGuiIngredient` / `IGuiIngredientGroup` / `IRecipeLayout` / `IRecipeLayoutDrawable` / `IDrawable` | `mezz.jei.api.gui` | 全版本（`IGuiIngredient` 始终在 `api.gui`） |
| `IIngredientRegistry` / `IIngredientHelper` / `IIngredientRenderer` | `mezz.jei.api.ingredients` | 全版本 |
| `IRecipeCategory` / `IRecipeWrapper` / `IFocus`(+`Mode`) | `mezz.jei.api.recipe` | 全版本 |
| `IGhostIngredientHandler`(+`Target`) / `IGuiScreenHandler` / `IGuiProperties` | `mezz.jei.api.gui` | **4.8.4.131** 起 |
| `IIngredientType` | `mezz.jei.api.recipe` | **4.12.0.216** 起 |
| `BookmarkList` / `BookmarkOverlay`（内部） | `mezz.jei.bookmarks` / `mezz.jei.gui.overlay.bookmarks` | **4.13.1.227** 起 |
| `IGlobalGuiHandler` | `mezz.jei.api.gui` | **4.14.0.232** 起 |
| `IBookmarkOverlay` | `mezz.jei.api` | **4.15.0.276** 起 |
| `IIngredientHelper$IFocusFactory` | `mezz.jei.api.ingredients` | 4.16.1.301 起 |
| `IIconButtonController` / `IRecipeButtonControllerFactory` / `IRecipeSlotView` / `VanillaTypes` / `IIngredientManager` / `IRecipeManager` / `registration.*` | —— | **全版本都不存在** |

> 结论：**1.12.2 线没有配方页按钮 API**，也没有 9.x/10.x 的 `ITypedIngredient`；`IModRegistry` 是唯一注册入口。

## 2. 与 1.16.5（JEI 7.x）的接口对照

| 能力 | 1.16.5 / JEI 7.x | 1.12.2 / JEI 4.x |
|---|---|---|
| 插件注解 | `@JeiPlugin` | `@JEIPlugin`（大写 I） |
| 插件 uid | `getPluginUid()` 返回 `ResourceLocation` | **没有该方法** |
| 注册入口 | `registerGuiHandlers(IGuiHandlerRegistration)` | `register(IModRegistry)` |
| 容器处理器 | `addGuiContainerHandler(Class, IGuiContainerHandler)` | `addAdvancedGuiHandlers(IAdvancedGuiHandler...)`（按 handler 自己报的类注册） |
| 普通界面让位 | `addGuiScreenHandler(Class, IScreenHandler)` | `addGuiScreenHandler(Class, IGuiScreenHandler)`（4.8.4+，`IGuiScreenHandler` 是 `Function<T, IGuiProperties>`） |
| 拖拽 | `addGhostIngredientHandler` | 同名（4.8.4+） |
| 全局悬停 | `addGlobalGuiHandler`（单数） | `addGlobalGuiHandlers`（**复数**，4.14+） |
| 额外区域类型 | `List<Rectangle2d>` | `List<java.awt.Rectangle>` |
| 界面属性 | `getScreenClass()` | `getGuiClass()` |
| 配料管理器 | `IJeiRuntime#getIngredientManager()` | **没有**；只能 `IModRegistry#getIngredientRegistry()`（`IIngredientRegistry`） |
| 配方管理器 | `IJeiRuntime#getRecipeManager()` | `IJeiRuntime#getRecipeRegistry()`（`IRecipeRegistry`） |
| 配方对象 | 原始配方对象 | `IRecipeWrapper` |
| 分类 id | `ResourceLocation getUid()` | **`String getUid()`**，`getModName()` 是必填方法 |
| 配料类型 | `IIngredientType` | 全版本另有 **`Class` 系重载**（见 §3） |
| 渲染器签名 | `render(MatrixStack, int, int, T)` | `render(Minecraft, int, int, T)` |
| 渲染器 tooltip | `getTooltip(T, ITooltipFlag)` → `List<ITextComponent>` | `getTooltip(Minecraft, T, ITooltipFlag)` → **`List<String>`** |
| 数量 | 反射 `IIngredientHelper#getAmount` | **没有 `getAmount`**；流体用 `FluidStack.amount` 字段 |
| 书签 | `IJeiRuntime#getBookmarkOverlay()`（7.x 就有） | **4.15.0.276** 才有；且 `IBookmarkOverlay` 只暴露 `getIngredientUnderMouse()`，列全部书签仍要反射 `BookmarkList` |
| 焦点构造 | `IRecipeManager#createFocus(...)` | 4.16 前无公开工厂 → 反射 `mezz.jei.gui.Focus(IFocus.Mode, V)` |

## 3. 关键签名（4.8.5.160 与 4.24.0.1043 逐字一致的部分）

```java
// 配料注册表：Class 系重载在 4.8.5 → 4.24 全版本存在
<V> Collection<V> getAllIngredients(Class<V>);
<V> IIngredientHelper<V> getIngredientHelper(V);
<V> IIngredientHelper<V> getIngredientHelper(Class<? extends V>);
<V> IIngredientRenderer<V> getIngredientRenderer(Class<? extends V>);
Collection<Class> getRegisteredIngredientClasses();
// 4.12+ 才有：getRegisteredIngredientTypes() / getIngredientType(...)
```

```java
// 布局：Class 系重载全版本可用；IIngredientType 重载 4.12+
<T> IGuiIngredientGroup<T> getIngredientsGroup(Class<T>);
<T> IGuiIngredientGroup<T> getIngredientsGroup(IIngredientType<T>);   // 4.12+
```

**因此本模组统一走 `Class` 系 API**，一份代码覆盖 4.8.5 ~ 4.24，不写 `IIngredientType` 分支。

## 4. 反射锚点（`mc/jei/JeiReflect`，逐版本 `javap -p` 核实）

| 目标 | 锚点 | 稳定区间 |
|---|---|---|
| `mezz.jei.gui.recipes.RecipesGui` | `private final List<RecipeLayout> recipeLayouts` | 4.7.9 → 4.24 |
| `mezz.jei.gui.ingredients.GuiIngredient` | `private final java.awt.Rectangle rect`（**比 `getRect()` 稳**，后者 4.8.5 才有） | 4.7.9 → 4.24 |
| `mezz.jei.gui.recipes.RecipeLayout` | `private final IRecipeWrapper recipeWrapper`（无公开 `getRecipe()`） | 4.7.9 → 4.24 |
| `mezz.jei.gui.recipes.RecipeLayout` | 公开 `getPosX/getPosY/getRecipeCategory/getItemStacks/getFluidStacks/getIngredientsGroup(Class)` | 全版本 |
| `mezz.jei.bookmarks.BookmarkList` | `List<IIngredientListElement> getIngredientList()`（**无参**） | 4.13.1 + |
| `mezz.jei.gui.Focus` | `public Focus(IFocus.Mode, V)` | 4.7.9 → 4.24 |

**不要反射的东西**：`RecipeLayout#guiIngredientGroups` 的键类型在 4.16 → 4.22 之间由 `Class` 变成了
`IIngredientType` —— 用公开的 `getIngredientsGroup(Class)` 即可，别碰这个 Map。

## 5. 逐能力降级表（运行期探测见 `JeiCapabilities`）

| 能力 | 依赖 | 缺失时的行为 |
|---|---|---|
| 一级界面额外区域 + R/U/A 悬停 | `IAdvancedGuiHandler`（全版本） | 不应缺失 |
| 二级界面面板让位 | `IGuiScreenHandler` / `IGuiProperties`（4.8.4+） | 不注册，JEI 面板照旧覆盖二级界面 |
| 拖拽录入 | `IGhostIngredientHandler`（4.8.4+） | 不注册；高亮逻辑本身不依赖 JEI |
| `IGlobalGuiHandler` 兜底 | 4.14+（用**动态代理**注册，避免编译期依赖） | 不注册，只靠容器处理器 |
| 书签导入 | 4.15.0.276+（公开 accessor + 反射 `BookmarkList`） | 不做反射兜底：按钮文字转金橙色，进世界时聊天栏提示需要 4.15.0.276 |
| R/U/A 跳转 | `mezz.jei.gui.Focus` 构造器 | 构造失败则只打一次警告，不跳转 |
| 配方页「编码此配方」 | `RecipesGui#recipeLayouts` + `GuiIngredient#rect` | 反射失败则整个叠加层降级（告警一次），主界面与求解不受影响 |

## 6. 1.12.2 平台差异（移植时必看的坑）

| 1.16.5 | 1.12.2 |
|---|---|
| `MatrixStack` / `RenderSystem` | 无（`Draw` 用 `GlStateManager` + `Gui.drawRect`） |
| `Screen` / `ContainerScreen` | `GuiScreen` / `GuiContainer` + `Container(InventoryPlayer)` |
| `ContainerType` / `IForgeContainerType` | **不存在**：容器不注册，只 new 一个本地实例 |
| `render(pose, mouseX, mouseY, partialTicks)` | `drawScreen(mouseX, mouseY, partialTicks)` |
| `mouseClicked(double,double,int)` (boolean) | `mouseClicked(int,int,int)`（void）+ `mouseClickMove` / `mouseReleased` |
| `mouseScrolled(...)` | 没有：`handleMouseInput()` 读 `Mouse.getEventDWheel()` |
| `keyPressed(int,int,int)` | `keyTyped(char,int)`（基类 `RbScreen` 做了 GLFW ↔ LWJGL2 的桥接，子类代码保持 1.16.5 形状） |
| `onClose()` / `isPauseScreen()` | `onGuiClosed()` / `doesGuiPauseGame()` |
| `TextFieldWidget` | `GuiTextField`（本模组用 `RbTextField` 补了占位符与 `setResponder`） |
| `IReorderingProcessor`（换行缓存） | `font.listFormattedStringToWidth(String, int)` |
| `ForgeConfigSpec` | `net.minecraftforge.common.config.Configuration` |
| `FMLJavaModLoadingContext` / `@Mod.EventBusSubscriber` | `@Mod.EventHandler` + `MinecraftForge.EVENT_BUS.register(...)` |
| `FMLPaths.GAMEDIR/CONFIGDIR` | preInit 的 `getModConfigurationDirectory()`（`mc/io/GamePaths`） |
| `Component#getString()` / `appendSibling` / `mergeStyle` | `getUnformattedText()` / `appendSibling` / `setStyle(new Style().setColor(...))`（`appendString` → `appendText`） |
| `Minecraft#getMainWindow()` / `updateWindowSize()` | `displayWidth/displayHeight` + `ScaledResolution` + `resize(w,h)` |
| `Util.getOSType().openFile(...)` | `java.awt.Desktop` |
| `NBTTagCompound` / `JsonToNbt` / `ItemPotion` / `hasTag` | `NBTTagCompound` / `JsonToNBT` / `ItemPotion` / `hasTagCompound` |
| `ForgeRegistries` 在 `net.minecraftforge.registries` | `net.minecraftforge.fml.common.registry.ForgeRegistries` |
| `FluidStack#getAmount()` | **`public int amount` 字段**（少了这条，所有流体量都会回落到默认 1 B） |
| 物品变体靠 NBT/组件 | **靠 metadata**：key 必须写成 `minecraft:fish#3`，否则河豚会变成生鱼 |
| `KeyBinding#matchesKey(key, scan)` | `getKeyCode() == 原始 LWJGL 键码`（基类暴露 `lastKeyCode`） |

## 7. 构建期经验（写给下一个移植）

1. **Gradle 4.9 + FG3 的报错会被注解处理轮次截断**：只有 `compileJava.options.compilerArgs += ['-proc:none']`
   才能看到完整错误清单，否则会得到"某些类找不到符号"的假错误（本轮为此浪费了很多时间）。
2. **`-Xmaxerrs 2000`** 也要一起加，默认 100 条会把后面的文件全部挡住。
3. **PowerShell 的 `-replace` 默认不区分大小写**：批量把 `Screen` 改 `GuiScreen` 时，
   会把 `package ...mc.screen;` 里的 `screen` 一起改掉，导致整个包"消失"。批量改名后必须回读包声明。
4. `gradle.properties` 里 `org.gradle.jvmargs` 加 `-Duser.language=en` 可以让 javac 用英文报错，
   避免中文 Windows 下 GBK/UTF-8 混排导致的乱码诊断。

## 8. 实机验证清单（1.12.2-Forge 实例，人工执行）

| 用例 | 备注 |
|---|---|
| 启动无 `NoClassDefFoundError` / `NoSuchMethodError` | 日志应出现「JEI 容器处理器：已注册」「JEI 面板定位/拖拽录入：已注册」 |
| 不带 JEI 启动 | 全部功能可用，仅少拖拽与配方页按钮 |
| K 打开界面、ESC 关闭、重复打开 | 关闭路径不递归 |
| 面板位置/左右图标列/背包区 | 与 1.16.5 线一致 |
| 名称框、筛选框输入 | `RbTextField` 的占位符与 responder 生效 |
| 求解 → 结果页（含流程图拖拽/缩放/点选） | |
| 悬停编码格/目标格/配方库/背包 → R/U/A | 需要 JEI ≥ 4.8.5 |
| 从 JEI 拖物品到格子/清单网格 | 绿色高亮只画一层 |
| 配方页「编码此配方」按钮 | 按钮在配方右上角，悬停时配方库能自产的槽位标蓝 |
| 书签批量导入 | 仅在 JEI ≥ 4.15.0.276 可用；低版本按钮为金橙色并给出提示 |
| 低版本 JEI（如 4.7.9.96）首次进世界 | 聊天栏出现「检测到低版本 JEI」提示 |

## 9. 已知限制

- 没有服务端配方转移（`IRecipeTransferHandler`）：编码只是本地草稿。
- 配方页按钮与书签列表都依赖 JEI 内部字段，跨大版本仍可能失效（有一/两次告警，功能降级但不崩）。\n- 书签在 4.13/4.14 上**明确不支持**：`BookmarkOverlay` 被藏在 `ILeftAreaContent` 里，实测反射与对象图搜索都不可靠，故按版本判能力而不是猜结构。
- `runClient` 不能验证 JEI（JEI 的 config 注册在 dev 环境会抛异常），JEI 相关验证一律在实机实例做。
