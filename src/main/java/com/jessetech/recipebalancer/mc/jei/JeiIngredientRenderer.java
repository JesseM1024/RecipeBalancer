package com.jessetech.recipebalancer.mc.jei;

import com.jessetech.recipebalancer.mc.i18n.Lang;
import com.jessetech.recipebalancer.mc.itemmap.IngredientKey;
import com.jessetech.recipebalancer.mc.itemmap.ItemKeyMapper;
import com.jessetech.recipebalancer.mc.ui.Chat;
import com.jessetech.recipebalancer.mc.ui.Draw;
import mezz.jei.api.ingredients.IIngredientHelper;
import mezz.jei.api.ingredients.IIngredientRenderer;
import mezz.jei.api.ingredients.IIngredientRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.Style;
import net.minecraft.util.text.TextFormatting;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 用 JEI 的 {@code IIngredientRenderer} 绘制流体 / 化学品等非物品配料（1.12.2 版）。
 *
 * <p>4.x 的渲染器签名是 {@code render(Minecraft, int x, int y, T)}（自带绝对坐标），
 * {@code getTooltip(Minecraft, T, ITooltipFlag)} 返回 {@code List<String>}。
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
        IIngredientRegistry registry = JeiIngredientMapper.registry();
        if (ingredient == null || registry == null) {
            return false;
        }
        try {
            IIngredientRenderer<Object> renderer = rendererOf(registry, ingredient);
            if (renderer == null) {
                return false;
            }
            renderer.render(Minecraft.getMinecraft(), x + 1, y + 1, ingredient);
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
        IIngredientRegistry registry = JeiIngredientMapper.registry();
        if (ingredient == null || registry == null) {
            return false;
        }
        try {
            IIngredientRenderer<Object> renderer = rendererOf(registry, ingredient);
            if (renderer == null) {
                return false;
            }
            // JEI 的 getTooltip 对流体/化学品常常返回「空行」或干脆空表，
            // 直接用它会出现「只有单位提示」的 tooltip。这里自己组首行信息：
            // 显示名 + 容量（mB），再把 JEI 的非空附加行接在后面。
            List<ITextComponent> lines = new ArrayList<>();
            String name = displayNameOf(key);
            if (name != null && !name.trim().isEmpty()) {
                lines.add(Chat.literal(name));
            }
            List<String> extra = renderer.getTooltip(Minecraft.getMinecraft(), ingredient,
                    ITooltipFlag.TooltipFlags.NORMAL);
            if (extra != null) {
                for (String line : extra) {
                    if (line == null || line.trim().isEmpty()) {
                        continue;
                    }
                    if (name != null && line.contains(name)) {
                        continue;   // 与首行重复
                    }
                    if (line.contains("mB") || line.contains("mb")) {
                        continue;   // 不显示物质量（Create 会给出放大后的 437 mB，反而误导）
                    }
                    lines.add(Chat.literal(line));
                }
            }
            if (lines.isEmpty()) {
                lines.add(Chat.styled(IngredientKey.describe(key),
                        new Style().setColor(TextFormatting.GRAY)));
            }
            lines.add(Chat.styled(Lang.t("RecipeBalancer 数量单位: B"),
                    new Style().setColor(TextFormatting.DARK_GRAY)));
            graphics.renderComponentTooltip(font, lines, mouseX, mouseY);
            return true;
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    /** 供 R/U 查询：把 ingredient key 还原成 JEI 的裸配料对象。 */
    public static Object resolveIngredient(String key) {
        IIngredientRegistry registry = JeiIngredientMapper.registry();
        if (registry == null || key == null || !IngredientKey.isIngredientKey(key)) {
            return null;
        }
        // 先查会话缓存：本次拖拽/导入时记下的对象最可靠
        Object remembered = JeiIngredientMapper.recall(key);
        if (remembered != null) {
            return remembered;
        }
        return resolveCached(registry, key).orElse(null);
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

    private static Optional<Object> resolveCached(IIngredientRegistry registry, String key) {
        Optional<Object> cached = RESOLVED.get(key);
        if (cached != null) {
            return cached;
        }
        Optional<Object> resolved;
        try {
            resolved = lookup(registry, key);
        } catch (RuntimeException | LinkageError e) {
            resolved = Optional.empty();
        }
        RESOLVED.put(key, resolved);
        return resolved;
    }

    /**
     * 4.x 没有按 uid 取配料的 API：按类型遍历全部配料，用 helper 的 uniqueId 匹配。
     * 结果按 key 缓存，重复绘制不会每次都全量遍历。
     */
    private static Optional<Object> lookup(IIngredientRegistry registry, String key) {
        Class<?> type = JeiIngredientMapper.findType(registry, IngredientKey.typeUid(key));
        if (type == null) {
            return Optional.empty();
        }
        Collection<Object> all = JeiIngredientMapper.allIngredients(registry, cast(type));
        IIngredientHelper<Object> helper = JeiIngredientMapper.helperOfClass(registry, cast(type));
        if (helper == null) {
            return Optional.empty();
        }
        String uniqueId = IngredientKey.uniqueId(key);
        for (Object candidate : all) {
            if (uniqueId.equals(helper.getUniqueId(candidate))) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    @SuppressWarnings("unchecked")
    private static <T> Class<T> cast(Class<?> type) {
        return (Class<T>) type;
    }

    @SuppressWarnings("unchecked")
    private static IIngredientRenderer<Object> rendererOf(IIngredientRegistry registry,
                                                          Object ingredient) {
        if (registry == null || ingredient == null) {
            return null;
        }
        return (IIngredientRenderer<Object>) registry.getIngredientRenderer(ingredient);
    }
}
