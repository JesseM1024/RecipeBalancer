# RecipeBalancer 0.1.6 (1.20.1)

## GitHub Release 说明

首个带 JEI 集成的 1.20.1 版本；同一个 jar 同时适用于 **Forge 1.20.1** 与 **NeoForge 1.20.1**（纯客户端）。

- **JEI 集成（可选依赖）**：面板定位、JEI → 槽位拖拽、书签一键导入、配方页「编码此配方」按钮、配方页悬停 R/U/A、右键自动撰写样板。
- **JEI 兼容 15.0.0.12 ~ 15.74**：按运行期「类/方法存在性」探测选择实现，不做版本号硬判断；15.0 ~ 15.2 缺配方页按钮接口时自动降级。
- **物料清单编辑屏支持 JEI 拖拽**：「无限外部供应物质」「循环亏损补充物质」的网格可直接从 JEI 拖入物品。
- **配方页高亮**：悬停「编码此配方」时，配方库已有配方的物质会叠加深蓝高亮，配色与层级与 1.21.1 线一致。
- **修复**：方块图标「下亮上暗」的法线翻转问题。

未安装 JEI 时相关功能整体降级，其余录入 / 求解功能不受影响。

需要 **Java 17**。

## Modrinth / CurseForge 更新描述（纯英文）

```
- Added optional JEI integration: drag ingredients into slots, import bookmarks, "Encode this recipe" button on JEI recipe pages, R/U/A lookups on hover, auto-fill from a recipe
- Supports JEI 15.0 - 15.74 (runtime capability detection; missing APIs degrade gracefully)
- JEI drag & drop is now supported on the material list screens (infinite supply / cyclic deficit)
- Ingredients that already have a recipe in the library are highlighted on JEI recipe pages
- Fixed inverted shading on block item icons
```
