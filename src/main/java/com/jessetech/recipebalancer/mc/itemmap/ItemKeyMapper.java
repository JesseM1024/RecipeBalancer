package com.jessetech.recipebalancer.mc.itemmap;

import com.jessetech.recipebalancer.core.util.J8;
import com.jessetech.recipebalancer.mc.i18n.Lang;
import com.jessetech.recipebalancer.mc.ui.Chat;
import com.jessetech.recipebalancer.mc.ui.Draw;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.item.Item;
import net.minecraft.item.ItemPotion;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.JsonToNBT;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.Style;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.fml.common.registry.ForgeRegistries;

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
 * 这里负责与 {@link ItemStack} 互转，以及"未注册物品"的占位渲染。
 *
 * <p>带附加数据的物品用整份 NBT 走 {@code stack|base64(snbt)} 保存，
 * 语义与其它版本线一致 —— 没有 NBT 的普通物品仍然只存 registry id。
 *
 * <p>1.12.2 的差异：NBT 类是 {@code NBTTagCompound} / {@code JsonToNbt}，
 * {@code ItemStack} 由 NBT 构造（没有 {@code read/write} 静态对），
 * 显示名与 tooltip 都是 {@code String} 而不是组件。
 */
public final class ItemKeyMapper {

    private static final String STACK_PREFIX = "stack|";
    /** 1.12.2 的物品变体用 metadata 区分：key 写成 {@code minecraft:fish#3}。 */
    private static final String META_SEPARATOR = "#";
    private static final Map<String, ItemStack> STACK_CACHE = new ConcurrentHashMap<>();
    private static final Pattern STACK_KEY_PATTERN = Pattern.compile("stack\\|[A-Za-z0-9_-]+");
    private static final Pattern INGREDIENT_KEY_PATTERN =
            Pattern.compile("ingredient\\|[A-Za-z0-9_-]+(?:\\|[A-Za-z0-9_-]+){2}");

    /** ingredient key 里固化的是编码当时的名称，换语言后需重新解析。 */
    private static final Map<String, String> INGREDIENT_NAME_CACHE = new ConcurrentHashMap<>();

    /** 非物品 ingredient 的渲染钩子；JEI 适配层在运行期安装，避免无 JEI 环境加载 JEI 类。 */
    public interface IngredientRenderHook {
        boolean render(Draw graphics, FontRenderer font, String key, int x, int y, String amount);

        boolean renderTooltip(Draw graphics, FontRenderer font, String key, int mouseX, int mouseY);
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
     * 普通物品只存 registry id；带 NBT（药水、附魔、自定义名等）时
     * 用 {@code stack|<base64(snbt)>} 保存完整 ItemStack。
     *
     * <p><b>1.12.2 特有</b>：物品变体靠 metadata 区分（河豚 = {@code minecraft:fish} 的
     * meta 3，生鱼是 meta 0），所以 meta ≠ 0 时必须写进 key，否则不同变体会撞成同一个物品。
     * 形如 {@code minecraft:fish#3}。
     */
    public static String of(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "";
        }
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (id == null) {
            return "";
        }
        ItemStack single = stack.copy();
        single.setCount(1);
        int meta = single.getMetadata();
        if (!single.hasTagCompound()) {
            return meta == 0 ? id.toString() : id + META_SEPARATOR + meta;
        }
        try {
            NBTTagCompound tag = single.writeToNBT(new NBTTagCompound());
            // writeToNBT 不写 Damage（metadata 是独立字段）：补上，解码才不会丢掉变体
            tag.setShort("Damage", (short) meta);
            String encoded = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(tag.toString().getBytes(StandardCharsets.UTF_8));
            return STACK_PREFIX + encoded;
        } catch (RuntimeException e) {
            // 极端环境退化到 registry id，至少不崩
            return meta == 0 ? id.toString() : id + META_SEPARATOR + meta;
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
        int meta = metaOf(id);
        String plain = baseIdOf(id);
        ResourceLocation location = parseLocation(plain);
        if (location == null) {
            return ItemStack.EMPTY;
        }
        Item item = ForgeRegistries.ITEMS.getValue(location);
        return item == null ? ItemStack.EMPTY : new ItemStack(item, 1, meta);
    }

    /** key 里带的 metadata（{@code minecraft:fish#3}，也接受旧写法 {@code minecraft:fish:3}）。 */
    private static int metaOf(String id) {
        int hash = id.indexOf(META_SEPARATOR);
        if (hash >= 0) {
            return parseInt(id.substring(hash + META_SEPARATOR.length()));
        }
        int colon = id.lastIndexOf(':');
        if (colon > 0 && colon < id.length() - 1) {
            String tail = id.substring(colon + 1);
            if (isNumeric(tail)) {
                return parseInt(tail);
            }
        }
        return 0;
    }

    /** 去掉 key 里的 metadata，只留 registry id。 */
    private static String baseIdOf(String id) {
        int hash = id.indexOf(META_SEPARATOR);
        if (hash >= 0) {
            return id.substring(0, hash);
        }
        int colon = id.lastIndexOf(':');
        if (colon > 0 && colon < id.length() - 1 && isNumeric(id.substring(colon + 1))) {
            return id.substring(0, colon);
        }
        return id;
    }

    private static boolean isNumeric(String text) {
        if (text.isEmpty()) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            if (!Character.isDigit(text.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static int parseInt(String text) {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 1.12.2 没有 {@code ResourceLocation.tryCreate}：非法 id 会抛异常。 */
    private static ResourceLocation parseLocation(String id) {
        try {
            return new ResourceLocation(id);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static ItemStack decodeStack(String key) {
        try {
            String snbt = new String(Base64.getUrlDecoder()
                    .decode(key.substring(STACK_PREFIX.length())), StandardCharsets.UTF_8);
            NBTTagCompound tag = JsonToNBT.getTagFromJson(snbt);
            ItemStack stack = new ItemStack(tag);
            if (stack.isEmpty()) {
                return ItemStack.EMPTY;
            }
            stack.setCount(1);
            return stack;
        } catch (Exception e) {
            return ItemStack.EMPTY;
        }
    }

    private static String baseItemId(String key) {
        ItemStack stack = stack(key);
        if (!stack.isEmpty()) {
            ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
            if (id != null) {
                return id.toString();
            }
        }
        // 解析不出物品时也要去掉 metadata 再当注册名用（占位色/短标签要稳定）
        return baseIdOf(normalize(key));
    }

    public static boolean exists(String key) {
        return !stack(key).isEmpty();
    }

    /** key 是否为已知物质（注册表物品或非物品 ingredient）；否则按自定义物质处理。 */
    public static boolean isKnown(String key) {
        if (key == null || J8.isBlank(key)) {
            return false;
        }
        return IngredientKey.isIngredientKey(key) || exists(key);
    }

    public static String displayName(String key) {
        if (IngredientKey.isIngredientKey(key)) {
            return ingredientDisplayName(key);
        }
        ItemStack stack = stack(key);
        if (!stack.isEmpty()) {
            return stack.getDisplayName();
        }
        return normalize(key);
    }

    /**
     * 非物品 ingredient 的显示名：优先按当前语言向适配层重新解析，
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
        if (resolved == null || J8.isBlank(resolved)) {
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
        List<String> matches = findMatches(raw, 4);
        return matches.size() == 1 ? matches.get(0) : null;
    }

    /**
     * 严格解析用户输入，只接受**完全一致**的写法，不做任何模糊/前缀匹配。
     *
     * @return 空列表表示没有精确匹配；多个表示同名物品（极少见），此时要求改用注册名
     */
    public static List<String> findMatches(String raw, int limit) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            return J8.list();
        }
        if (IngredientKey.isIngredientKey(value)) {
            return J8.list(value);
        }
        String normalized = normalize(value);
        if (!normalized.isEmpty() && exists(normalized)) {
            return J8.list(normalized);
        }
        List<String> byName = new ArrayList<>();
        for (ResourceLocation id : ForgeRegistries.ITEMS.getKeys()) {
            Item item = ForgeRegistries.ITEMS.getValue(id);
            if (item == null) {
                continue;
            }
            if (item.getHasSubtypes()) {
                // 1.12.2 的变体是 metadata（河豚/生鱼都是 minecraft:fish）：逐个变体比名字
                try {
                    net.minecraft.util.NonNullList<ItemStack> subs =
                            net.minecraft.util.NonNullList.create();
                    item.getSubItems(net.minecraft.creativetab.CreativeTabs.SEARCH, subs);
                    for (ItemStack sub : subs) {
                        String subName = sub.getDisplayName();
                        if (subName.equalsIgnoreCase(value) || of(sub).equalsIgnoreCase(value)) {
                            byName.add(of(sub));
                            if (byName.size() >= limit + 1) {
                                break;
                            }
                        }
                    }
                } catch (RuntimeException | LinkageError ignored) {
                    // 某些模组的变体枚举会抛异常：忽略该物品
                }
                continue;
            }
            String name = new ItemStack(item).getDisplayName();
            if (name.equalsIgnoreCase(value) || id.toString().equalsIgnoreCase(value)) {
                byName.add(id.toString());
            }
            if (byName.size() >= limit + 1) {
                break;
            }
        }
        if (byName.size() <= limit) {
            return J8.listCopy(byName);
        }
        return J8.listCopy(byName.subList(0, limit));
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
        String name = stack.getDisplayName();
        if (!(stack.getItem() instanceof ItemPotion)) {
            return name;
        }
        // 直接用物品自身的 tooltip：Lingering 会按 0.25 时长因子、Splash 按原版规则显示
        Minecraft minecraft = Minecraft.getMinecraft();
        List<String> tooltip = stack.getTooltip(
                minecraft == null ? null : minecraft.player, ITooltipFlag.TooltipFlags.NORMAL);
        List<String> effects = new ArrayList<>();
        for (int i = 1; i < tooltip.size(); i++) {
            String line = tooltip.get(i);
            if (!J8.isBlank(line)) {
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

    /** 注册名（tooltip/用量界面展示）；带 NBT 的物品只显示底层 registry id。 */
    public static String registryName(String key) {
        if (IngredientKey.isIngredientKey(key)) {
            return IngredientKey.describe(key);
        }
        String id = baseItemId(key);
        if (id.isEmpty()) {
            return normalize(key);
        }
        // 1.12.2 的习惯写法是 mod:name:meta（例如 minecraft:fish:3）
        int meta = metaOf(normalize(key));
        return meta == 0 ? id : id + ":" + meta;
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
            case 0:
                r = brightness;
                g = t;
                b = p;
                break;
            case 1:
                r = q;
                g = brightness;
                b = p;
                break;
            case 2:
                r = p;
                g = brightness;
                b = t;
                break;
            case 3:
                r = p;
                g = q;
                b = brightness;
                break;
            case 4:
                r = t;
                g = p;
                b = brightness;
                break;
            default:
                r = brightness;
                g = p;
                b = q;
                break;
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
    public static void renderIcon(Draw graphics, FontRenderer font, String key, int x, int y, String amount) {
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
            graphics.drawString(font, label, x + 9 - font.getStringWidth(label) / 2, y + 5,
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
        int textX = x + 9 - font.getStringWidth(label) / 2;
        int textY = y + 5;
        graphics.drawString(font, label, textX, textY, 0xFF101010, false);
        renderAmount(graphics, font, amount, x, y);
    }

    /**
     * 在当前位姿变换下画 18px 槽内图标（流程图节点用）。
     *
     * <p>与 {@link #renderIcon} 的区别：物品走 {@code renderItemPosed}，位置与颜色都吃调用方的
     * 位姿变换 —— 调用方先 {@code translate} 到节点左上角，再调本方法。
     */
    public static void renderIconPosed(Draw graphics, FontRenderer font, String key,
                                       String amount) {
        String id = normalize(key);
        if (id.isEmpty()) {
            return;
        }
        if (IngredientKey.isIngredientKey(id)) {
            IngredientRenderHook hook = ingredientRenderHook;
            if (hook != null && hook.render(graphics, font, id, 0, 0, amount)) {
                return;
            }
            drawPlaceholderPosed(graphics, font, id, amount);
            return;
        }
        ItemStack stack = stack(id);
        if (!stack.isEmpty()) {
            graphics.renderItemPosed(stack, 1.0f, 1.0f);
            renderAmount(graphics, font, amount, 0, 0);
            return;
        }
        drawPlaceholderPosed(graphics, font, id, amount);
    }

    /** 未注册物品/配料的占位块（位姿版，坐标以 0,0 为左上角）。 */
    private static void drawPlaceholderPosed(Draw graphics, FontRenderer font, String id,
                                             String amount) {
        int color = placeholderColor(id);
        graphics.fill(1, 1, 17, 17, color);
        graphics.fill(1, 1, 17, 2, 0x40FFFFFF);
        graphics.fill(1, 16, 17, 17, 0x60000000);
        String label = shortLabel(id);
        graphics.drawString(font, label, 9 - font.getStringWidth(label) / 2, 5,
                0xFF101010, false);
        renderAmount(graphics, font, amount, 0, 0);
    }

    /** 渲染钩子复用：在 18px 槽内画数量文本。 */
    public static void renderAmountOverlay(Draw graphics, FontRenderer font, String amount,
                                           int x, int y) {
        renderAmount(graphics, font, amount, x, y);
    }

    /** 紧凑数量文本：过长时等比缩小，避免像 100000 一样溢出格子。 */
    private static void renderAmount(Draw graphics, FontRenderer font, String amount, int x, int y) {
        if (amount == null || J8.isBlank(amount)) {
            return;
        }
        int width = font.getStringWidth(amount);
        int maxWidth = 14;
        Draw.Pose pose = graphics.pose();
        pose.push();
        if (width <= maxWidth) {
            graphics.drawString(font, amount, x + 17 - width, y + 9, 0xFFFFFFFF, true);
        } else {
            float scale = (float) maxWidth / width;
            pose.translate(x + 17 - width * scale, y + 9, 0);
            pose.scale(scale, scale, 1.0f);
            graphics.drawString(font, amount, 0, 0, 0xFFFFFFFF, true);
        }
        pose.pop();
    }

    public static void renderTooltip(Draw graphics, FontRenderer font, String key, int mouseX, int mouseY) {
        String id = normalize(key);
        if (id.isEmpty()) {
            return;
        }
        ItemStack stack = stack(id);
        if (!stack.isEmpty()) {
            // 原生完整 tooltip：药水效果/时长、附魔、NBT 信息都会显示
            graphics.renderTooltip(font, stack, mouseX, mouseY);
            return;
        }
        List<ITextComponent> lines = new ArrayList<>();
        if (IngredientKey.isIngredientKey(id)) {
            IngredientRenderHook hook = ingredientRenderHook;
            if (hook != null && hook.renderTooltip(graphics, font, id, mouseX, mouseY)) {
                return;
            }
            lines.add(Chat.literal(ingredientDisplayName(id)));
            lines.add(Chat.styled(IngredientKey.describe(id),
                    new Style().setColor(TextFormatting.GRAY)));
            lines.add(Chat.styled(Lang.t("非物品 ingredient；数量单位 B（500 mB = 0.5）"),
                    new Style().setColor(TextFormatting.DARK_GRAY)));
        } else {
            lines.add(Chat.styled(shortLabel(id), new Style().setColor(TextFormatting.GOLD)));
            lines.add(Chat.styled(id, new Style().setColor(TextFormatting.GRAY)));
            lines.add(Chat.styled(Lang.t("当前整合包未找到此物品（占位显示）"),
                    new Style().setColor(TextFormatting.DARK_GRAY)));
        }
        graphics.renderComponentTooltip(font, lines, mouseX, mouseY);
    }
}
