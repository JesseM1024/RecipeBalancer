package com.jessetech.recipebalancer.mc.jei;

import com.jessetech.recipebalancer.mc.i18n.Lang;
import com.jessetech.recipebalancer.mc.itemmap.IngredientKey;
import com.jessetech.recipebalancer.mc.itemmap.ItemKeyMapper;
import mezz.jei.api.ingredients.IIngredientRenderer;
import mezz.jei.api.ingredients.IIngredientType;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.runtime.IIngredientManager;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.TooltipFlag;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 用 JEI 的 {@code IIngredientRenderer} 绘制流体 / 化学品等非物品配料。
 *
 * <p>15.x 的渲染器是 {@code render(GuiGraphics, T)}（坐标由调用方平移），
 * 与 19.x 的 {@code render(GuiGraphics, T, x, y)} 不同，这里用位姿平移适配。
 */
public final class JeiIngredientRenderer
        implements ItemKeyMapper.IngredientRenderHook, ItemKeyMapper.IngredientNameHook {

    @Override
    public String displayName(String key) {
        return resolveTyped(key).map(JeiIngredientMapper::displayNameOf).orElse(null);
    }

    @Override
    public boolean render(GuiGraphics graphics, Font font, String key, int x, int y, String amount) {
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
    public boolean renderTooltip(GuiGraphics graphics, Font font, String key,
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
        // 1.20.1 分支未缓存反查结果；保留方法以便插件统一调用
    }

    /** 供 R/U 查询：把 ingredient key 还原成 JEI {@code ITypedIngredient}。 */
    public static Optional<ITypedIngredient<?>> resolveTyped(String key) {
        IJeiRuntime runtime = JeiIngredientMapper.runtime();
        if (runtime == null || !IngredientKey.isIngredientKey(key)) {
            return Optional.empty();
        }
        // 编码时记住的对象最可靠（uniqueId 不含完整 NBT 的配料反查会落空）
        ITypedIngredient<?> remembered = JeiIngredientMapper.recall(key);
        if (remembered != null) {
            return Optional.of(remembered);
        }
        try {
            IIngredientManager manager = runtime.getIngredientManager();
            Optional<IIngredientType<?>> type =
                    JeiIngredientMapper.findType(manager, IngredientKey.typeUid(key));
            if (type.isEmpty()) {
                return Optional.empty();
            }
            String uniqueId = IngredientKey.uniqueId(key);
            int hash = uniqueId.indexOf('#');
            if (hash > 0) {
                return resolveWithFingerprint(manager, type.get(),
                        uniqueId.substring(0, hash), uniqueId.substring(hash + 1));
            }
            return resolveWithType(manager, type.get(), uniqueId);
        } catch (RuntimeException | LinkageError e) {
            return Optional.empty();
        }
    }

    /**
     * 带 NBT 摘要的 uid 解析：先按基础 uid 查，再比对摘要；
     * 不一致时在同类配料里按摘要找（药水这种「同流体不同 NBT」的场景）。
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Optional<ITypedIngredient<?>> resolveWithFingerprint(
            IIngredientManager manager, IIngredientType<?> type,
            String baseUid, String fingerprint) {
        Optional<ITypedIngredient<?>> direct = resolveWithType(manager, type, baseUid);
        if (direct.isPresent()
                && fingerprint.equals(JeiIngredientMapper.nbtFingerprint(direct.get().getIngredient()))) {
            return direct;
        }
        for (Object candidate : manager.getAllIngredients((IIngredientType) type)) {
            if (!fingerprint.equals(JeiIngredientMapper.nbtFingerprint(candidate))) {
                continue;
            }
            Optional<ITypedIngredient<?>> typed = manager.createTypedIngredient(
                    (IIngredientType) type, candidate);
            if (typed.isPresent()) {
                return typed;
            }
        }
        return Optional.empty();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Optional<ITypedIngredient<?>> resolveWithType(
            IIngredientManager manager, IIngredientType<?> type, String uniqueId) {
        Optional<Object> value = manager.getIngredientByUid((IIngredientType) type, uniqueId);
        if (value.isEmpty()) {
            return Optional.empty();
        }
        return (Optional) manager.createTypedIngredient((IIngredientType) type, value.get());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void renderRaw(IIngredientRenderer renderer, GuiGraphics graphics,
                                  Object ingredient, int x, int y) {
        graphics.pose().pushPose();
        graphics.pose().translate(x, y, 0);
        try {
            renderer.render(graphics, ingredient);
        } finally {
            graphics.pose().popPose();
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static List<Component> tooltipRaw(IIngredientRenderer renderer, Object ingredient) {
        return renderer.getTooltip(ingredient, TooltipFlag.Default.NORMAL);
    }
}
