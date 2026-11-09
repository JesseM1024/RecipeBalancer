package com.jessetech.recipebalancer.mc.jei;

import com.jessetech.recipebalancer.mc.i18n.Lang;
import com.jessetech.recipebalancer.mc.itemmap.IngredientKey;
import com.jessetech.recipebalancer.mc.itemmap.ItemKeyMapper;
import com.jessetech.recipebalancer.mc.ui.Draw;
import mezz.jei.api.ingredients.IIngredientHelper;
import mezz.jei.api.ingredients.IIngredientRenderer;
import mezz.jei.api.ingredients.IIngredientType;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.ingredients.subtypes.UidContext;
import mezz.jei.api.runtime.IIngredientManager;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.TooltipFlag;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 用 JEI 的 {@code IIngredientRenderer} 绘制流体 / 化学品等非物品配料。
 *
 * <p>9.x 的渲染器是 {@code render(PoseStack, int x, int y, T)}（自带绝对坐标），
 * 与 15.x 的 {@code render(GuiGraphics, T)} 不同，这里直接传坐标，不再自己平移。
 */
public final class JeiIngredientRenderer
        implements ItemKeyMapper.IngredientRenderHook, ItemKeyMapper.IngredientNameHook {

    /** key → 配料解析结果。JEI 重启/切换 runtime 时本对象会重建，缓存随之失效。 */
    private static final Map<String, Optional<ITypedIngredient<?>>> RESOLVED =
            new ConcurrentHashMap<>();

    @Override
    public String displayName(String key) {
        return resolveTyped(key).map(JeiIngredientMapper::displayNameOf).orElse(null);
    }

    @Override
    public boolean render(Draw graphics, Font font, String key, int x, int y, String amount) {
        Optional<ITypedIngredient<?>> typed = resolveTyped(key);
        if (typed.isEmpty()) {
            return false;
        }
        IJeiRuntime runtime = JeiIngredientMapper.runtime();
        if (runtime == null) {
            return false;
        }
        try {
            ITypedIngredient<?> ingredient = typed.get();
            IIngredientRenderer<?> renderer = runtime.getIngredientManager()
                    .getIngredientRenderer(ingredient.getType());
            renderRaw(renderer, graphics, ingredient.getIngredient(), x + 1, y + 1);
            ItemKeyMapper.renderAmountOverlay(graphics, font, amount, x, y);
            return true;
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    @Override
    public boolean renderTooltip(Draw graphics, Font font, String key,
                                 int mouseX, int mouseY) {
        Optional<ITypedIngredient<?>> typed = resolveTyped(key);
        IJeiRuntime runtime = JeiIngredientMapper.runtime();
        if (typed.isEmpty() || runtime == null) {
            return false;
        }
        try {
            ITypedIngredient<?> ingredient = typed.get();
            IIngredientRenderer<?> renderer = runtime.getIngredientManager()
                    .getIngredientRenderer(ingredient.getType());
            List<Component> lines = new ArrayList<>(tooltipRaw(renderer, ingredient.getIngredient()));
            lines.add(Lang.c("RecipeBalancer 数量单位: B").withStyle(ChatFormatting.DARK_GRAY));
            graphics.renderComponentTooltip(font, lines, mouseX, mouseY);
            return true;
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    /** JEI runtime 重建时清空反查缓存。 */
    public static void clearCache() {
        RESOLVED.clear();
    }

    /** 供 R/U 查询：把 ingredient key 还原成 JEI {@code ITypedIngredient}。 */
    public static Optional<ITypedIngredient<?>> resolveTyped(String key) {
        IJeiRuntime runtime = JeiIngredientMapper.runtime();
        if (runtime == null || !IngredientKey.isIngredientKey(key)) {
            return Optional.empty();
        }
        Optional<ITypedIngredient<?>> cached = RESOLVED.get(key);
        if (cached != null) {
            return cached;
        }
        // 编码时记住的对象最可靠（Create 药水流体等 uniqueId 不含完整 NBT，反查会落空）
        ITypedIngredient<?> remembered = JeiIngredientMapper.recall(key);
        if (remembered != null) {
            Optional<ITypedIngredient<?>> hit = Optional.of(remembered);
            RESOLVED.put(key, hit);
            return hit;
        }
        Optional<ITypedIngredient<?>> resolved;
        try {
            resolved = lookup(runtime, key);
        } catch (RuntimeException | LinkageError e) {
            resolved = Optional.empty();
        }
        RESOLVED.put(key, resolved);
        return resolved;
    }

    /**
     * 9.x 没有 {@code getIngredientByUid}：按类型遍历全部配料，用 helper 的 uniqueId 匹配。
     * 结果按 key 缓存，重复绘制不会每次都全量遍历。
     */
    private static Optional<ITypedIngredient<?>> lookup(IJeiRuntime runtime, String key) {
        IIngredientManager manager = runtime.getIngredientManager();
        Optional<IIngredientType<?>> type =
                JeiIngredientMapper.findType(manager, IngredientKey.typeUid(key));
        if (type.isEmpty()) {
            return Optional.empty();
        }
        return findByUniqueId(runtime, manager, type.get(), IngredientKey.uniqueId(key));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Optional<ITypedIngredient<?>> findByUniqueId(IJeiRuntime runtime,
                                                               IIngredientManager manager,
                                                               IIngredientType<?> type,
                                                               String uniqueId) {
        IIngredientType rawType = type;
        IIngredientHelper helper = manager.getIngredientHelper(rawType);
        for (Object candidate : manager.getAllIngredients(rawType)) {
            if (uniqueId.equals(helper.getUniqueId(candidate, UidContext.Ingredient))) {
                ITypedIngredient<?> typed = runtime.createTypedIngredient(rawType, candidate);
                return Optional.ofNullable(typed);
            }
        }
        return Optional.empty();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void renderRaw(IIngredientRenderer renderer, Draw graphics,
                                  Object ingredient, int x, int y) {
        renderer.render(graphics.pose(), x, y, ingredient);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static List<Component> tooltipRaw(IIngredientRenderer renderer, Object ingredient) {
        return renderer.getTooltip(ingredient, TooltipFlag.Default.NORMAL);
    }
}
