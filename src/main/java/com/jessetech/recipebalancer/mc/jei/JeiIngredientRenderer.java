package com.jessetech.recipebalancer.mc.jei;

import com.jessetech.recipebalancer.mc.i18n.Lang;
import com.jessetech.recipebalancer.mc.itemmap.IngredientKey;
import com.jessetech.recipebalancer.mc.itemmap.ItemKeyMapper;
import com.jessetech.recipebalancer.mc.ui.Chat;
import com.jessetech.recipebalancer.mc.ui.Draw;
import mezz.jei.api.ingredients.IIngredientHelper;
import mezz.jei.api.ingredients.IIngredientRenderer;
import mezz.jei.api.ingredients.IIngredientType;
import mezz.jei.api.runtime.IIngredientManager;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextFormatting;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 用 JEI 的 {@code IIngredientRenderer} 绘制流体 / 化学品等非物品配料（1.16.5 版）。
 *
 * <p>7.x 的渲染器签名是 {@code render(MatrixStack, int x, int y, T)}（自带绝对坐标），
 * 与 15.x 的 {@code render(Draw, T)} 不同，这里直接传坐标，不再自己平移。
 *
 * <p>7.x 没有 {@code ITypedIngredient}：配料就是裸对象，类型用
 * {@code IIngredientManager#getIngredientType(ingredient)} 现查。
 */
public final class JeiIngredientRenderer
        implements ItemKeyMapper.IngredientRenderHook, ItemKeyMapper.IngredientNameHook {

    /** key → 配料对象。JEI 重启/切换 runtime 时本对象会重建，缓存随之失效。 */
    private static final Map<String, Optional<Object>> RESOLVED = new ConcurrentHashMap<>();

    @Override
    public String displayName(String key) {
        return JeiIngredientRenderer.displayNameOf(key);
    }

    @Override
    public boolean render(Draw graphics, FontRenderer font, String key, int x, int y,
                          String amount) {
        Object ingredient = resolveIngredient(key);
        IJeiRuntime runtime = JeiIngredientMapper.runtime();
        if (ingredient == null || runtime == null) {
            return false;
        }
        try {
            IIngredientRenderer<Object> renderer = rendererOf(runtime.getIngredientManager(),
                    ingredient);
            if (renderer == null) {
                return false;
            }
            renderer.render(graphics.pose(), x + 1, y + 1, ingredient);
            ItemKeyMapper.renderAmountOverlay(graphics, font, amount, x, y);
            return true;
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    @Override
    public boolean renderTooltip(Draw graphics, FontRenderer font, String key,
                                 int mouseX, int mouseY) {
        Object ingredient = resolveIngredient(key);
        IJeiRuntime runtime = JeiIngredientMapper.runtime();
        if (ingredient == null || runtime == null) {
            return false;
        }
        try {
            IIngredientRenderer<Object> renderer = rendererOf(runtime.getIngredientManager(),
                    ingredient);
            if (renderer == null) {
                return false;
            }
            List<ITextComponent> lines = new ArrayList<>(
                    renderer.getTooltip(ingredient, ITooltipFlag.TooltipFlags.NORMAL));
            lines.add(Chat.literal(Lang.t("RecipeBalancer 数量单位: B"))
                    .mergeStyle(TextFormatting.DARK_GRAY));
            graphics.renderComponentTooltip(font, lines, mouseX, mouseY);
            return true;
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    /** 供 R/U 查询：把 ingredient key 还原成 JEI 的裸配料对象。 */
    public static Object resolveIngredient(String key) {
        IJeiRuntime runtime = JeiIngredientMapper.runtime();
        if (runtime == null || key == null || !IngredientKey.isIngredientKey(key)) {
            return null;
        }
        // 先查会话缓存：本次拖拽/导入时记下的对象最可靠
        // （部分配料 uniqueId 不含完整 NBT，反查会失败，表现为编码成自定义物质、R/U 失效）
        Object remembered = JeiIngredientMapper.recall(key);
        if (remembered != null) {
            return remembered;
        }
        return resolveCached(runtime, key).orElse(null);
    }

    /** JEI runtime 重建时清空反查缓存。 */
    public static void clearCache() {
        RESOLVED.clear();
    }

    /** 当前语言下的显示名。 */
    public static String displayNameOf(String key) {
        Object ingredient = resolveIngredient(key);
        return ingredient == null ? null : JeiIngredientMapper.displayNameOf(ingredient);
    }

    private static Optional<Object> resolveCached(IJeiRuntime runtime, String key) {
        Optional<Object> cached = RESOLVED.get(key);
        if (cached != null) {
            return cached;
        }
        Optional<Object> resolved;
        try {
            resolved = lookup(runtime, key);
        } catch (RuntimeException | LinkageError e) {
            resolved = Optional.empty();
        }
        RESOLVED.put(key, resolved);
        return resolved;
    }

    /**
     * 7.x 没有按 uid 取配料的 API：按类型遍历全部配料，用 helper 的 uniqueId 匹配。
     * 结果按 key 缓存，重复绘制不会每次都全量遍历。
     */
    private static Optional<Object> lookup(IJeiRuntime runtime, String key) {
        IIngredientManager manager = runtime.getIngredientManager();
        Optional<IIngredientType<?>> type =
                JeiIngredientMapper.findType(manager, IngredientKey.typeUid(key));
        if (!type.isPresent()) {
            return Optional.empty();
        }
        IIngredientType rawType = type.get();
        IIngredientHelper<Object> helper = manager.getIngredientHelper(rawType);
        if (helper == null) {
            return Optional.empty();
        }
        String uniqueId = IngredientKey.uniqueId(key);
        for (Object candidate : manager.getAllIngredients(rawType)) {
            if (uniqueId.equals(helper.getUniqueId(candidate))) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    @SuppressWarnings("unchecked")
    private static IIngredientRenderer<Object> rendererOf(IIngredientManager manager,
                                                          Object ingredient) {
        if (manager == null || ingredient == null) {
            return null;
        }
        IIngredientRenderer<?> renderer = manager.getIngredientRenderer(ingredient);
        return (IIngredientRenderer<Object>) renderer;
    }
}
