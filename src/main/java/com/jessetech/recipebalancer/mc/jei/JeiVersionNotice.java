package com.jessetech.recipebalancer.mc.jei;

import com.jessetech.recipebalancer.mc.i18n.Lang;
import net.minecraft.client.Minecraft;
import net.minecraft.util.text.TextComponentString;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/**
 * 低版本 JEI 提示（首次进入世界时在聊天栏提示一次）。
 *
 * <p>1.12.2 的 JEI 4.x 跨度很大：4.7.8 ~ 4.8.4 能加载本模组，但缺少
 * {@code IGhostIngredientHandler} / {@code IGuiScreenHandler}，拖拽与普通面板让位不可用。
 * 这类"能跑但少功能"的情况必须让玩家看得见，否则只会以为是模组坏了。
 *
 * <p>只在客户端、只在第一次进入世界时判定一次：语言文件键
 * {@code 检测到低版本 JEI（%s）……}（中文环境下直接用 key 文本）。
 */
public final class JeiVersionNotice {

    private boolean warned;

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (warned || event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft.world == null || minecraft.player == null) {
            return;
        }
        warned = true;
        if (!JeiCapabilities.installed() || JeiCapabilities.hasFullApi()) {
            return;
        }
        String message = Lang.t(
                "检测到低版本 JEI（%s）：拖拽、配方页按钮与书签导入需要 4.8.5.160 及以上，请升级 JEI。",
                JeiCapabilities.version());
        minecraft.player.sendMessage(new TextComponentString("[RecipeBalancer] " + message));
    }
}
