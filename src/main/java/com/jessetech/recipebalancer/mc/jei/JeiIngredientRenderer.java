package com.jessetech.recipebalancer.mc.jei;

import com.jessetech.recipebalancer.mc.i18n.Lang;

import com.jessetech.recipebalancer.mc.itemmap.IngredientKey;
import com.jessetech.recipebalancer.mc.itemmap.ItemKeyMapper;
import mezz.jei.api.ingredients.IIngredientRenderer;
import mezz.jei.api.ingredients.IIngredientType;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.runtime.IIngredientManager;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.TooltipFlag;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** 用 JEI 的 IIngredientRenderer 绘制流体/化学品等非物品。 */
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
        } catch (RuntimeException e) {
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
            lines.add(Lang.c("RecipeBalancer 数量单位: B").withStyle(
                    net.minecraft.ChatFormatting.DARK_GRAY));
            graphics.renderComponentTooltip(font, lines, mouseX, mouseY);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** 供 R/U 查询：把 ingredient key 还原成 JEI ITypedIngredient。 */
    public static Optional<ITypedIngredient<?>> resolveTyped(String key) {
        IJeiRuntime runtime = JeiIngredientMapper.runtime();
        if (runtime == null || !IngredientKey.isIngredientKey(key)) {
            return Optional.empty();
        }
        try {
            IIngredientManager manager = runtime.getIngredientManager();
            Optional<IIngredientType<?>> type = manager.getIngredientTypeForUid(
                    IngredientKey.typeUid(key));
            if (type.isEmpty()) {
                return Optional.empty();
            }
            return resolveWithType(manager, type.get(), IngredientKey.uniqueId(key));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    // 新 API 要改成 codec 序列化（会改变存档里的 ingredient key 编码），
    // 现阶段保持 uid 查表，先把「待删除」告警压住。
    @SuppressWarnings({"rawtypes", "unchecked", "removal"})
    private static Optional<ITypedIngredient<?>> resolveWithType(
            IIngredientManager manager, IIngredientType<?> type, String uniqueId) {
        return (Optional) manager.getTypedIngredientByUid((IIngredientType) type, uniqueId);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void renderRaw(IIngredientRenderer renderer, GuiGraphics graphics,
                                  Object ingredient, int x, int y) {
        renderer.render(graphics, ingredient, x, y);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static List<Component> tooltipRaw(IIngredientRenderer renderer, Object ingredient) {
        return renderer.getTooltip(ingredient, TooltipFlag.NORMAL);
    }
}
