package com.jessetech.recipebalancer.mc.itemmap;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 物品身份与图标：core 里物品是字符串（registry id 或自定义名），
 * 这里负责与 {@link ItemStack} 互转，以及“未注册物品”的占位渲染。
 */
public final class ItemKeyMapper {

    private ItemKeyMapper() {
    }

    public static String normalize(String key) {
        if (key == null) {
            return "";
        }
        String value = key.trim();
        return value.startsWith("item|") ? value.substring("item|".length()) : value;
    }

    public static String of(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "";
        }
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return id == null ? "" : id.toString();
    }

    public static ItemStack stack(String key) {
        String id = normalize(key);
        if (id.isEmpty()) {
            return ItemStack.EMPTY;
        }
        ResourceLocation location = ResourceLocation.tryParse(id);
        if (location == null) {
            return ItemStack.EMPTY;
        }
        return BuiltInRegistries.ITEM.getOptional(location).map(ItemStack::new).orElse(ItemStack.EMPTY);
    }

    public static boolean exists(String key) {
        return !stack(key).isEmpty();
    }

    public static String displayName(String key) {
        ItemStack stack = stack(key);
        if (!stack.isEmpty()) {
            return stack.getHoverName().getString();
        }
        return normalize(key);
    }

    /** 注册名（tooltip/用量界面展示）。 */
    public static String registryName(String key) {
        return normalize(key);
    }

    /** 未注册物品的短标签：ASCII 取前 3 个字母，中文取第一个字。 */
    public static String shortLabel(String key) {
        String id = normalize(key);
        int colon = id.indexOf(':');
        String path = colon >= 0 ? id.substring(colon + 1) : id;
        if (path.isEmpty()) {
            return "?";
        }
        int first = path.codePointAt(0);
        if (first < 128 && Character.isLetterOrDigit(first)) {
            String letters = path.length() >= 3 ? path.substring(0, 3) : path;
            return letters.toUpperCase(Locale.ROOT);
        }
        return new String(Character.toChars(first));
    }

    /** 未注册物品的确定性占位色。 */
    public static int placeholderColor(String key) {
        int hash = normalize(key).hashCode();
        float hue = Math.floorMod(hash, 3600) / 3600.0f;
        float saturation = 0.48f;
        float brightness = 0.78f;
        int i = (int) (hue * 6.0f);
        float f = hue * 6.0f - i;
        float p = brightness * (1.0f - saturation);
        float q = brightness * (1.0f - saturation * f);
        float t = brightness * (1.0f - saturation * (1.0f - f));
        float r;
        float g;
        float b;
        switch (i % 6) {
            case 0 -> {
                r = brightness;
                g = t;
                b = p;
            }
            case 1 -> {
                r = q;
                g = brightness;
                b = p;
            }
            case 2 -> {
                r = p;
                g = brightness;
                b = t;
            }
            case 3 -> {
                r = p;
                g = q;
                b = brightness;
            }
            case 4 -> {
                r = t;
                g = p;
                b = brightness;
            }
            default -> {
                r = brightness;
                g = p;
                b = q;
            }
        }
        int rgb = ((int) (r * 255) << 16) | ((int) (g * 255) << 8) | (int) (b * 255);
        return 0xFF000000 | rgb;
    }

    /**
     * 画一个 18px 物品槽的内区图标（物品 16×16 居中，1px 边距）。
     * 已注册物品用真实图标；未注册画彩色方块 + 短标签。
     *
     * @param amount 右下角数量文本；null/空 = 不画
     */
    public static void renderIcon(GuiGraphics graphics, Font font, String key, int x, int y, String amount) {
        String id = normalize(key);
        if (id.isEmpty()) {
            return;
        }
        ItemStack stack = stack(id);
        if (!stack.isEmpty()) {
            graphics.renderItem(stack, x + 1, y + 1);
            renderAmount(graphics, font, amount, x, y);
            return;
        }
        int color = placeholderColor(id);
        graphics.fill(x + 1, y + 1, x + 17, y + 17, color);
        graphics.fill(x + 1, y + 1, x + 17, y + 2, 0x40FFFFFF);
        graphics.fill(x + 1, y + 16, x + 17, y + 17, 0x60000000);
        String label = shortLabel(id);
        int textX = x + 9 - font.width(label) / 2;
        int textY = y + 5;
        graphics.drawString(font, label, textX, textY, 0xFF101010, false);
        renderAmount(graphics, font, amount, x, y);
    }

    /** 紧凑数量文本：过长时等比缩小，避免像 100000 一样溢出格子。 */
    private static void renderAmount(GuiGraphics graphics, Font font, String amount, int x, int y) {
        if (amount == null || amount.isBlank()) {
            return;
        }
        int width = font.width(amount);
        int maxWidth = 14;
        if (width <= maxWidth) {
            graphics.drawString(font, amount, x + 17 - width, y + 9, 0xFFFFFFFF, true);
            return;
        }
        float scale = (float) maxWidth / width;
        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(x + 17 - width * scale, y + 9, 0);
        pose.scale(scale, scale, 1.0f);
        graphics.drawString(font, amount, 0, 0, 0xFFFFFFFF, true);
        pose.popPose();
    }

    public static void renderTooltip(GuiGraphics graphics, Font font, String key, int mouseX, int mouseY) {
        String id = normalize(key);
        if (id.isEmpty()) {
            return;
        }
        List<Component> lines = new ArrayList<>();
        ItemStack stack = stack(id);
        if (!stack.isEmpty()) {
            lines.add(stack.getHoverName());
            lines.add(Component.literal(id).withStyle(ChatFormatting.GRAY));
        } else {
            lines.add(Component.literal(shortLabel(id)).withStyle(ChatFormatting.GOLD));
            lines.add(Component.literal(id).withStyle(ChatFormatting.GRAY));
            lines.add(Component.literal("当前整合包未找到此物品（占位显示）")
                    .withStyle(ChatFormatting.DARK_GRAY));
        }
        graphics.renderComponentTooltip(font, lines, mouseX, mouseY);
    }
}
