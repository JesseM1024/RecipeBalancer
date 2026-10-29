package com.jessetech.recipebalancer.mc.screen;

import com.jessetech.recipebalancer.mc.i18n.Lang;
import com.jessetech.recipebalancer.mc.itemmap.ItemKeyMapper;
import com.jessetech.recipebalancer.mc.jei.JeiBookmarks;
import com.jessetech.recipebalancer.mc.jei.JeiRuntimeHolder;
import com.jessetech.recipebalancer.mc.jei.JeiScreenSupport;
import com.jessetech.recipebalancer.mc.ui.AeStyle;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 物料清单编辑屏：只维护「物品种类」，不涉及数量。
 *
 * <p>用于 Python notebook 的 {@code raw_materials} 与
 * {@code allowed_external_cyc_inputs}。交互与主界面「目标产物」一致：
 * <ul>
 *   <li>上方是 9 列格子，点空格自定义物质，点已有格编辑，右键移除；</li>
 *   <li>下方是背包 4 行，点格子即把该物品加进清单；</li>
 *   <li>底部纵向堆叠「读取书签」「返回」，一键读 JEI 右侧书签。</li>
 * </ul>
 */
public final class KeySetScreen extends Screen implements JeiPanelScreen {

    public interface Callback {
        void accept(Set<String> keys);
    }

    private static final int PANEL_W = 178;
    private static final int PANEL_H = 252;
    private static final int SLOT = 18;
    private static final int COLS = 9;
    private static final int ROWS = 4;
    private static final int INV_ROWS = 4;
    private static final int MAX_ENTRIES = 64;
    /** 清空需要两次点击，第二次必须在这个时间内。 */
    private static final long CLEAR_CONFIRM_MS = 2000L;

    private final Screen parent;
    private final String title;
    private final Callback callback;
    /** 清单（保持加入顺序，格子按顺序填充）。 */
    private final List<String> entries = new ArrayList<>();
    private int scrollRow;
    private String status = "";
    private boolean clearArmed;
    private long clearArmedAt;

    private int panelX = 20;
    private int panelY = 10;
    private int gridY;
    private int inventoryY;

    public KeySetScreen(Screen parent, String title, Collection<String> initial,
                        Callback callback) {
        super(Lang.c(title));
        this.parent = parent;
        this.title = title;
        this.callback = callback;
        if (initial != null) {
            entries.addAll(initial);
        }
    }

    @Override
    protected void init() {
        panelX = (width - PANEL_W) / 2;
        panelY = (height - PANEL_H) / 2;
        gridY = panelY + 24;
        inventoryY = panelY + 122;
        scrollRow = Math.max(0, Math.min(scrollRow, maxScroll()));

        // 半宽：左边清空（按两下确认），右边读 JEI 书签
        int halfW = (PANEL_W - 16 - 4) / 2;
        addRenderableWidget(Button.builder(Lang.c("清空列表"), b -> clearWithConfirm())
                .bounds(panelX + 8, panelY + 200, halfW, 20).build());
        addRenderableWidget(Button.builder(Lang.c("读取书签"), b -> addBookmarks())
                .bounds(panelX + 8 + halfW + 4, panelY + 200, halfW, 20).build());
        addRenderableWidget(Button.builder(Lang.c("返回"), b -> minecraft.setScreen(parent))
                .bounds(panelX + 8, panelY + 224, PANEL_W - 16, 20).build());
        JeiScreenSupport.notifyGuiInit(this);
    }

    private int maxScroll() {
        return Math.max(0, (entries.size() + COLS - 1) / COLS - ROWS);
    }

    private void notifyChanged() {
        callback.accept(new LinkedHashSet<>(entries));
    }

    /** 加入一个 key（重复则提示）。 */
    private void addKey(String key) {
        if (key == null || key.isBlank()) {
            return;
        }
        if (entries.contains(key)) {
            status = Lang.t("已在列表中: %s", ItemKeyMapper.displayName(key));
            return;
        }
        if (entries.size() >= MAX_ENTRIES) {
            status = Lang.t("最多 %s 个", MAX_ENTRIES);
            return;
        }
        entries.add(key);
        notifyChanged();
        status = Lang.t("已添加: %s", ItemKeyMapper.displayName(key));
    }

    private void removeAt(int slot) {
        if (slot < 0 || slot >= entries.size()) {
            return;
        }
        String key = entries.remove(slot);
        scrollRow = Math.max(0, Math.min(scrollRow, maxScroll()));
        notifyChanged();
        status = Lang.t("已移除: %s", ItemKeyMapper.displayName(key));
    }

    /** 点已有格：改这一格的物质（自定义输入）。 */
    private void editAt(int slot) {
        String current = entries.get(slot);
        minecraft.setScreen(new AmountScreen(this, title, current, 1.0, false,
                (newKey, ignored) -> {
                    String resolved = resolve(newKey);
                    if (resolved.isEmpty()) {
                        return;
                    }
                    int existing = entries.indexOf(resolved);
                    if (existing >= 0 && existing != slot) {
                        status = Lang.t("已在列表中: %s",
                                ItemKeyMapper.displayName(resolved));
                        return;
                    }
                    entries.set(slot, resolved);
                    notifyChanged();
                    status = Lang.t("已添加: %s", ItemKeyMapper.displayName(resolved));
                }));
    }

    /** 点空格：自定义物质加入清单。 */
    private void customAdd() {
        if (entries.size() >= MAX_ENTRIES) {
            status = Lang.t("最多 %s 个", MAX_ENTRIES);
            return;
        }
        minecraft.setScreen(new AmountScreen(this, title, "", 1.0, false,
                (newKey, ignored) -> {
                    String resolved = resolve(newKey);
                    if (!resolved.isEmpty()) {
                        addKey(resolved);
                    }
                }));
    }

    /** 允许输注册名，也允许输真名。 */
    private static String resolve(String raw) {
        String resolved = ItemKeyMapper.resolveKey(raw);
        return resolved == null ? ItemKeyMapper.normalize(raw) : resolved;
    }

    private void addFromInventory(int index) {
        if (minecraft == null || minecraft.player == null) {
            return;
        }
        ItemStack stack = minecraft.player.getInventory().getItem(index);
        if (!stack.isEmpty()) {
            addKey(ItemKeyMapper.of(stack));
        }
    }

    /** 清空要按两下：第一下只提示，2 秒内再点一次才真的清空。 */
    private void clearWithConfirm() {
        long now = System.currentTimeMillis();
        if (!clearArmed || now - clearArmedAt > CLEAR_CONFIRM_MS) {
            clearArmed = true;
            clearArmedAt = now;
            status = Lang.t("再次点击「清空列表」确认清空");
            return;
        }
        clearArmed = false;
        entries.clear();
        scrollRow = 0;
        notifyChanged();
        status = Lang.t("已清空列表");
    }

    /** 一键读 JEI 右侧书签。 */
    private void addBookmarks() {
        // 先判 JEI 是否存在：未安装时只提示、不触发，也不能让 NoClassDefFoundError 冒出来
        if (!JeiRuntimeHolder.installed()) {
            status = Lang.t("JEI 未安装");
            return;
        }
        if (!JeiRuntimeHolder.ready()) {
            status = Lang.t("JEI 不可用");
            return;
        }
        Set<String> found;
        try {
            found = JeiBookmarks.readKeys();
        } catch (RuntimeException | LinkageError e) {
            status = Lang.t("JEI 不可用");
            return;
        }
        if (found.isEmpty()) {
            status = Lang.t("JEI 书签为空");
            return;
        }
        int before = entries.size();
        for (String key : found) {
            if (entries.size() >= MAX_ENTRIES) {
                break;
            }
            if (!entries.contains(key)) {
                entries.add(key);
            }
        }
        notifyChanged();
        status = Lang.t("已读取 %s 个书签，新增 %s 个", found.size(),
                entries.size() - before);
    }

    private int gridSlotAt(double mouseX, double mouseY) {
        if (mouseX < panelX + 8 || mouseX >= panelX + 8 + COLS * SLOT
                || mouseY < gridY || mouseY >= gridY + ROWS * SLOT) {
            return -1;
        }
        int col = (int) ((mouseX - panelX - 8) / SLOT);
        int row = (int) ((mouseY - gridY) / SLOT);
        int slot = (scrollRow + row) * COLS + col;
        return slot < MAX_ENTRIES ? slot : -1;
    }

    private int inventoryIndexAt(double mouseX, double mouseY) {
        for (int row = 0; row < INV_ROWS; row++) {
            for (int col = 0; col < COLS; col++) {
                int x = panelX + 8 + col * SLOT;
                int y = inventoryY + row * SLOT;
                if (mouseX >= x && mouseX < x + SLOT && mouseY >= y && mouseY < y + SLOT) {
                    return row < 3 ? 9 + row * 9 + col : col;
                }
            }
        }
        return -1;
    }

    private void handleGridClick(int slot, int button) {
        if (button == 1) {
            removeAt(slot);
            return;
        }
        if (button == 0 || button == 2) {
            if (slot < entries.size()) {
                editAt(slot);
            } else {
                customAdd();
            }
        }
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0xB0101010);
        AeStyle.panel(graphics, panelX, panelY, PANEL_W, PANEL_H);
        AeStyle.header(graphics, panelX, panelY, PANEL_W, 20);
        graphics.drawString(font, Lang.t(title), panelX + 8, panelY + 6, AeStyle.TEXT, false);
        String count = entries.size() + "/" + MAX_ENTRIES;
        graphics.drawString(font, count, panelX + PANEL_W - 8 - font.width(count),
                panelY + 6, AeStyle.MUTED, false);
        graphics.drawString(font, Lang.t("背包"), panelX + 8, panelY + 110,
                AeStyle.MUTED, false);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);

        scrollRow = Math.max(0, Math.min(scrollRow, maxScroll()));
        for (int row = 0; row < ROWS; row++) {
            for (int col = 0; col < COLS; col++) {
                int slot = (scrollRow + row) * COLS + col;
                int x = panelX + 8 + col * SLOT;
                int y = gridY + row * SLOT;
                AeStyle.slot(graphics, x, y);
                if (slot < entries.size()) {
                    ItemKeyMapper.renderIcon(graphics, font, entries.get(slot), x, y, null);
                }
            }
        }
        drawInventory(graphics);

        if (!status.isEmpty()) {
            graphics.drawString(font, font.plainSubstrByWidth(status, PANEL_W - 16),
                    panelX + 8, panelY + 98, AeStyle.ACCENT, false);
        } else {
            graphics.drawString(font, Lang.t("左键添加 · 右键移除"),
                    panelX + 8, panelY + 98, AeStyle.MUTED, false);
        }

        // 悬停提示：格里是哪种物质 / 空格可以自定义
        int slot = gridSlotAt(mouseX, mouseY);
        if (slot >= 0 && slot < entries.size()) {
            String key = entries.get(slot);
            graphics.renderComponentTooltip(font, List.of(
                    Component.literal(ItemKeyMapper.displayName(key)),
                    Component.literal(ItemKeyMapper.registryName(key))
                            .withStyle(ChatFormatting.GRAY)), mouseX, mouseY);
        } else if (slot >= 0) {
            graphics.renderComponentTooltip(font,
                    List.of(Lang.c("空格：左键/中键自定义物质")), mouseX, mouseY);
        }
    }

    /** 背包 4 行（主背包 3 行 + 快捷栏），9 格宽度＝面板内容宽度。 */
    private void drawInventory(GuiGraphics graphics) {
        Inventory inventory = minecraft == null || minecraft.player == null
                ? null : minecraft.player.getInventory();
        for (int row = 0; row < INV_ROWS; row++) {
            for (int col = 0; col < COLS; col++) {
                int x = panelX + 8 + col * SLOT;
                int y = inventoryY + row * SLOT;
                AeStyle.slot(graphics, x, y);
                if (inventory == null) {
                    continue;
                }
                int index = row < 3 ? 9 + row * 9 + col : col;
                ItemStack stack = inventory.getItem(index);
                if (!stack.isEmpty()) {
                    graphics.renderItem(stack, x + 1, y + 1);
                    graphics.renderItemDecorations(font, stack, x + 1, y + 1);
                }
            }
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int slot = gridSlotAt(mouseX, mouseY);
        if (slot >= 0) {
            handleGridClick(slot, button);
            return true;
        }
        int index = inventoryIndexAt(mouseX, mouseY);
        if (index >= 0) {
            if (button == 0) {
                addFromInventory(index);
            }
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (mouseX >= panelX && mouseX < panelX + PANEL_W
                && mouseY >= gridY && mouseY < gridY + ROWS * SLOT) {
            scrollRow = Math.max(0, Math.min(
                    scrollRow + (scrollY < 0 ? 1 : -1), maxScroll()));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void onClose() {
        if (minecraft != null) {
            minecraft.setScreen(parent);
        }
    }

    @Override
    public int jeiGuiLeft() {
        return panelX;
    }

    @Override
    public int jeiGuiTop() {
        return panelY;
    }

    @Override
    public int jeiGuiWidth() {
        return PANEL_W;
    }

    @Override
    public int jeiGuiHeight() {
        return PANEL_H;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
