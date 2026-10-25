package com.jessetech.recipebalancer.mc.screen;

import com.jessetech.recipebalancer.core.model.ScenarioDraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** 目标产物编辑屏（名称 + 每秒目标量）。 */
public final class TargetEditScreen extends Screen {

    private final Screen parent;
    private final ScenarioDraft draft;
    private final String original;

    private EditBox itemBox;
    private EditBox amountBox;
    private String status = "";
    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;

    public TargetEditScreen(Screen parent, ScenarioDraft draft, String original) {
        super(Component.literal(original == null ? "添加目标产物" : "编辑目标产物"));
        this.parent = parent;
        this.draft = draft;
        this.original = original;
    }

    @Override
    protected void init() {
        panelW = Math.min(320, width - 16);
        panelH = 130;
        panelX = (width - panelW) / 2;
        panelY = (height - panelH) / 2;

        itemBox = new EditBox(font, panelX + 70, panelY + 28, panelW - 80, 18,
                Component.literal("物品"));
        itemBox.setMaxLength(64);
        itemBox.setValue(original == null ? "" : original);
        addRenderableWidget(itemBox);

        Double amount = original == null ? null : draft.targets().get(original);
        amountBox = new EditBox(font, panelX + 70, panelY + 54, 90, 18,
                Component.literal("数量"));
        amountBox.setMaxLength(16);
        amountBox.setValue(amount == null ? "1" : Double.toString(amount));
        addRenderableWidget(amountBox);

        addRenderableWidget(Button.builder(Component.literal("保存"), b -> save())
                .bounds(panelX + panelW - 150, panelY + panelH - 28, 70, 20).build());
        addRenderableWidget(Button.builder(Component.literal("取消"), b -> minecraft.setScreen(parent))
                .bounds(panelX + panelW - 76, panelY + panelH - 28, 70, 20).build());
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0xE0101010);
        graphics.fill(panelX, panelY, panelX + panelW, panelY + panelH, 0xC0202020);
        graphics.drawString(font, title, panelX + 10, panelY + 8, 0xFFE0A0, false);
        graphics.drawString(font, "物品", panelX + 10, panelY + 33, 0xA0A0A0, false);
        graphics.drawString(font, "每秒目标", panelX + 10, panelY + 59, 0xA0A0A0, false);
        graphics.drawString(font, "留空数量=1；名称需与配方内完全一致", panelX + 10, panelY + 82,
                0x808080, false);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        if (!status.isEmpty()) {
            graphics.drawString(font, status, panelX, panelY + panelH + 4, 0xFF7070, false);
        }
    }

    private void save() {
        try {
            String item = itemBox.getValue().trim();
            double amount;
            try {
                amount = Double.parseDouble(amountBox.getValue().trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("数量必须是数字");
            }
            if (!(amount > 0) || Double.isInfinite(amount)) {
                throw new IllegalArgumentException("数量必须为正数");
            }
            if (original != null && !original.equals(item)) {
                draft.removeTarget(original);
            }
            draft.putTarget(item, amount);
            minecraft.setScreen(parent);
        } catch (RuntimeException e) {
            status = e.getMessage() == null ? "输入不合法" : e.getMessage();
        }
    }
}
