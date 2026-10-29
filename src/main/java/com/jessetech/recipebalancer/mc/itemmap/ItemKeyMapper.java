package com.jessetech.recipebalancer.mc.itemmap;

import com.google.gson.JsonElement;
import com.jessetech.recipebalancer.mc.i18n.Lang;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.serialization.JsonOps;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.alchemy.PotionContents;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 物品身份与图标：core 里物品是字符串（registry id 或自定义名），
 * 这里负责与 {@link ItemStack} 互转，以及“未注册物品”的占位渲染。
 */
public final class ItemKeyMapper {

    private static final String STACK_PREFIX = "stack|";
    private static final Map<String, ItemStack> STACK_CACHE = new ConcurrentHashMap<>();
    private static final Pattern STACK_KEY_PATTERN = Pattern.compile("stack\\|[A-Za-z0-9_-]+");
    private static final Pattern INGREDIENT_KEY_PATTERN =
            Pattern.compile("ingredient\\|[A-Za-z0-9_-]+(?:\\|[A-Za-z0-9_-]+){2}");

    /** ingredient key 里固化的是编码当时的名称，换语言后需重新问 JEI。 */
    private static final Map<String, String> INGREDIENT_NAME_CACHE = new ConcurrentHashMap<>();

    /** JEI 渲染钩子；由 RecipeBalancerJeiPlugin 在 JEI 可用时安装，避免无 JEI 环境加载 JEI 类。 */
    public interface IngredientRenderHook {
        boolean render(GuiGraphics graphics, Font font, String key, int x, int y, String amount);

        boolean renderTooltip(GuiGraphics graphics, Font font, String key, int mouseX, int mouseY);
    }

    /** 按当前语言取 ingredient 显示名的钩子；返回 null 表示解析不了，回退到固化名。 */
    public interface IngredientNameHook {
        String displayName(String key);
    }

    private static volatile IngredientRenderHook ingredientRenderHook;
    private static volatile IngredientNameHook ingredientNameHook;

    public static void setIngredientRenderHook(IngredientRenderHook hook) {
        ingredientRenderHook = hook;
    }

    public static void setIngredientNameHook(IngredientNameHook hook) {
        ingredientNameHook = hook;
        INGREDIENT_NAME_CACHE.clear();
    }

    private ItemKeyMapper() {
    }

    public static String normalize(String key) {
        if (key == null) {
            return "";
        }
        String value = key.trim();
        return value.startsWith("item|") ? value.substring("item|".length()) : value;
    }

    /**
     * 普通物品只存 registry id；带组件（药水、附魔、自定义名等）时
     * 用 `stack|<base64(ItemStack JSON)>` 保存完整 ItemStack。
     */
    public static String of(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "";
        }
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (id == null) {
            return "";
        }
        ItemStack single = stack.copyWithCount(1);
        if (single.isComponentsPatchEmpty()) {
            return id.toString();
        }
        try {
            RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, registryAccess());
            JsonElement json = ItemStack.CODEC.encodeStart(ops, single).getOrThrow();
            String encoded = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(json.toString().getBytes(StandardCharsets.UTF_8));
            return STACK_PREFIX + encoded;
        } catch (RuntimeException e) {
            // 极端环境（无 registry access）退化到 registry id，至少不崩
            return id.toString();
        }
    }

    public static ItemStack stack(String key) {
        String id = normalize(key);
        if (id.isEmpty()) {
            return ItemStack.EMPTY;
        }
        if (id.startsWith(STACK_PREFIX)) {
            return STACK_CACHE.computeIfAbsent(id, ItemKeyMapper::decodeStack);
        }
        return BuiltInRegistries.ITEM.getOptional(ResourceLocation.tryParse(id))
                .map(ItemStack::new).orElse(ItemStack.EMPTY);
    }

    private static ItemStack decodeStack(String key) {
        try {
            String jsonText = new String(Base64.getUrlDecoder()
                    .decode(key.substring(STACK_PREFIX.length())), StandardCharsets.UTF_8);
            JsonElement json = JsonParser.parseString(jsonText);
            RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, registryAccess());
            return ItemStack.CODEC.parse(ops, json).result()
                    .map(stack -> stack.copyWithCount(1)).orElse(ItemStack.EMPTY);
        } catch (RuntimeException e) {
            return ItemStack.EMPTY;
        }
    }

    private static RegistryAccess registryAccess() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft != null && minecraft.level != null
                ? minecraft.level.registryAccess() : RegistryAccess.EMPTY;
    }

    private static String baseItemId(String key) {
        ItemStack stack = stack(key);
        if (!stack.isEmpty()) {
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
            if (id != null) {
                return id.toString();
            }
        }
        return normalize(key);
    }

    public static boolean exists(String key) {
        return !stack(key).isEmpty();
    }

    public static String displayName(String key) {
        if (IngredientKey.isIngredientKey(key)) {
            return ingredientDisplayName(key);
        }
        ItemStack stack = stack(key);
        if (!stack.isEmpty()) {
            return stack.getHoverName().getString();
        }
        return normalize(key);
    }

    /**
     * 非物品 ingredient 的显示名：优先按当前语言向 JEI 重新解析，
     * 解析不到才回退到 key 里固化的名称（按语言缓存，切换语言自然失效）。
     */
    public static String ingredientDisplayName(String key) {
        IngredientNameHook hook = ingredientNameHook;
        if (hook == null) {
            return IngredientKey.displayName(key);
        }
        String cacheKey = Lang.languageCode() + '|' + IngredientKey.identity(key);
        String cached = INGREDIENT_NAME_CACHE.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        String resolved = hook.displayName(key);
        if (resolved == null || resolved.isBlank()) {
            return IngredientKey.displayName(key);
        }
        INGREDIENT_NAME_CACHE.put(cacheKey, resolved);
        return resolved;
    }

    /**
     * 把手输文本解析成物品 key：注册名直接使用，否则按真名/注册名短名在所有已注册物品里找。
     *
     * @return 解析不到返回 null
     */
    public static String resolveKey(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            return null;
        }
        if (IngredientKey.isIngredientKey(value)) {
            return value;
        }
        String normalized = normalize(value);
        if (exists(normalized)) {
            return normalized;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        String partial = null;
        for (ResourceLocation id : BuiltInRegistries.ITEM.keySet()) {
            String path = id.getPath();
            if (path.equalsIgnoreCase(lower)) {
                return id.toString();
            }
            String name = new ItemStack(BuiltInRegistries.ITEM.get(id)).getHoverName().getString();
            if (name.equalsIgnoreCase(value)) {
                return id.toString();
            }
            if (partial == null && (name.toLowerCase(Locale.ROOT).contains(lower)
                    || path.toLowerCase(Locale.ROOT).contains(lower))) {
                partial = id.toString();
            }
        }
        return partial;
    }

    /** 结果报告中的可读名称：物品名 + 药水效果（含时长）；非物品用显示名。 */
    public static String reportName(String key) {
        if (IngredientKey.isIngredientKey(key)) {
            return ingredientDisplayName(key);
        }
        ItemStack stack = stack(key);
        if (stack.isEmpty()) {
            return registryName(key);
        }
        String name = stack.getHoverName().getString();
        PotionContents contents = stack.get(DataComponents.POTION_CONTENTS);
        if (contents == null || !contents.hasEffects()) {
            return name;
        }
        // 直接用物品自身的 tooltip：Lingering 会按 0.25 时长因子、Splash 按原版规则显示
        Minecraft minecraft = Minecraft.getInstance();
        net.minecraft.world.item.Item.TooltipContext context =
                minecraft != null && minecraft.level != null
                        ? net.minecraft.world.item.Item.TooltipContext.of(minecraft.level)
                        : net.minecraft.world.item.Item.TooltipContext.EMPTY;
        List<Component> tooltip = stack.getTooltipLines(context,
                minecraft == null ? null : minecraft.player,
                net.minecraft.world.item.TooltipFlag.NORMAL);
        List<String> effects = new ArrayList<>();
        for (int i = 1; i < tooltip.size(); i++) {
            String line = tooltip.get(i).getString();
            if (!line.isBlank()) {
                effects.add(line);
            }
        }
        if (effects.isEmpty()) {
            return name;
        }
        return name + " [" + String.join(" / ", effects) + "]";
    }

    /** 把结果报告中的 stack|base64 替换成可读名称（不影响写入磁盘的数据）。 */
    public static String prettyText(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String replaced = replaceKeys(text, STACK_KEY_PATTERN);
        return replaceKeys(replaced, INGREDIENT_KEY_PATTERN);
    }

    private static String replaceKeys(String text, Pattern pattern) {
        Matcher matcher = pattern.matcher(text);
        StringBuffer output = new StringBuffer(text.length());
        while (matcher.find()) {
            matcher.appendReplacement(output,
                    Matcher.quoteReplacement(reportName(matcher.group())));
        }
        matcher.appendTail(output);
        return output.toString();
    }

    /** 注册名（tooltip/用量界面展示）；组件物品只显示底层 registry id。 */
    public static String registryName(String key) {
        if (IngredientKey.isIngredientKey(key)) {
            return IngredientKey.describe(key);
        }
        String id = baseItemId(key);
        return id.isEmpty() ? normalize(key) : id;
    }

    /** 未注册物品的短标签：ASCII 取前 3 个字母，中文取第一个字。 */
    public static String shortLabel(String key) {
        if (IngredientKey.isIngredientKey(key)) {
            return IngredientKey.shortLabelOf(ingredientDisplayName(key));
        }
        String id = baseItemId(key);
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
        int hash = baseItemId(key).hashCode();
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
        if (IngredientKey.isIngredientKey(id)) {
            IngredientRenderHook hook = ingredientRenderHook;
            if (hook != null && hook.render(graphics, font, id, x, y, amount)) {
                return;
            }
            int color = placeholderColor(id);
            graphics.fill(x + 1, y + 1, x + 17, y + 17, color);
            graphics.fill(x + 1, y + 1, x + 17, y + 2, 0x40FFFFFF);
            graphics.fill(x + 1, y + 16, x + 17, y + 17, 0x60000000);
            String label = shortLabel(id);
            graphics.drawString(font, label, x + 9 - font.width(label) / 2, y + 5,
                    0xFF101010, false);
            renderAmount(graphics, font, amount, x, y);
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

    /** JEI 渲染钩子复用：在 18px 槽内画数量文本。 */
    public static void renderAmountOverlay(GuiGraphics graphics, Font font, String amount,
                                           int x, int y) {
        renderAmount(graphics, font, amount, x, y);
    }

    /** 紧凑数量文本：过长时等比缩小，避免像 100000 一样溢出格子。 */
    private static void renderAmount(GuiGraphics graphics, Font font, String amount, int x, int y) {
        if (amount == null || amount.isBlank()) {
            return;
        }
        int width = font.width(amount);
        int maxWidth = 14;
        // 抬高 z，避免数量文字被物品图标/槽位盖住
        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(0, 0, 200);
        if (width <= maxWidth) {
            graphics.drawString(font, amount, x + 17 - width, y + 9, 0xFFFFFFFF, true);
        } else {
            float scale = (float) maxWidth / width;
            pose.translate(x + 17 - width * scale, y + 9, 0);
            pose.scale(scale, scale, 1.0f);
            graphics.drawString(font, amount, 0, 0, 0xFFFFFFFF, true);
        }
        pose.popPose();
    }

    public static void renderTooltip(GuiGraphics graphics, Font font, String key, int mouseX, int mouseY) {
        String id = normalize(key);
        if (id.isEmpty()) {
            return;
        }
        ItemStack stack = stack(id);
        if (!stack.isEmpty()) {
            // 原生完整 tooltip：药水效果/时长、附魔、组件信息都会显示
            graphics.renderTooltip(font, stack, mouseX, mouseY);
            return;
        }
        List<Component> lines = new ArrayList<>();
        if (IngredientKey.isIngredientKey(id)) {
            IngredientRenderHook hook = ingredientRenderHook;
            if (hook != null && hook.renderTooltip(graphics, font, id, mouseX, mouseY)) {
                return;
            }
            lines.add(Component.literal(ingredientDisplayName(id)));
            lines.add(Component.literal(IngredientKey.describe(id))
                    .withStyle(ChatFormatting.GRAY));
            lines.add(Lang.c("非物品 ingredient；数量单位 B（500 mB = 0.5）")
                    .withStyle(ChatFormatting.DARK_GRAY));
        } else {
            lines.add(Component.literal(shortLabel(id)).withStyle(ChatFormatting.GOLD));
            lines.add(Component.literal(id).withStyle(ChatFormatting.GRAY));
            lines.add(Lang.c("当前整合包未找到此物品（占位显示）")
                    .withStyle(ChatFormatting.DARK_GRAY));
        }
        graphics.renderComponentTooltip(font, lines, mouseX, mouseY);
    }
}
