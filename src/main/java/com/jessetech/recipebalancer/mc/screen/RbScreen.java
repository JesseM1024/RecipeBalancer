package com.jessetech.recipebalancer.mc.screen;

import com.jessetech.recipebalancer.mc.ui.Draw;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * 本模组所有界面的基类。
 *
 * <p>存在的唯一理由是 <b>tooltip</b>：1.18.2 的 {@code Screen#renderTooltip(PoseStack, ItemStack, ...)}
 * 是 {@code protected}，绘制辅助类（{@link Draw}、{@code ItemKeyMapper}、{@code SolveGraphView}）
 * 调不到它；1.20+ 则是 {@code GuiGraphics} 的公开方法，随处可调。
 *
 * <p>这里把它提升为 public 并直接实现 {@link Draw.Tooltips}，于是上方那些绘制代码
 * 仍然写 {@code graphics.renderTooltip(...)}，与 1.20 线保持同一套调用方式。
 *
 * <p>另一个职责是 <b>补画背景</b>：1.18.2 的 {@code Screen#render} 只遍历 widget，
 * 不会自己调 {@code renderBackground}（1.20 才会），各界面必须在 render 开头自己调；
 * 放在基类里统一处理，子类照旧只覆写 {@link #renderBackground(PoseStack)} 即可。
 */
public abstract class RbScreen extends Screen implements Draw.Tooltips {

    protected RbScreen(Component title) {
        super(title);
    }

    @Override
    public void render(PoseStack pose, int mouseX, int mouseY, float partialTick) {
        // 1.18.2 的 Screen#render 不会自动画背景，必须由界面自己先画
        renderBackground(pose);
        super.render(pose, mouseX, mouseY, partialTick);
    }

    /** 由 protected 提升为 public（覆写 Screen 的同签名方法，实现 {@link Draw.Tooltips}）。 */
    @Override
    public void renderTooltip(PoseStack pose, ItemStack stack, int mouseX, int mouseY) {
        super.renderTooltip(pose, stack, mouseX, mouseY);
    }

    @Override
    public void renderComponentTooltip(PoseStack pose, List<Component> lines, Font font,
                                       int mouseX, int mouseY) {
        super.renderComponentTooltip(pose, lines, mouseX, mouseY, font);
    }
}
