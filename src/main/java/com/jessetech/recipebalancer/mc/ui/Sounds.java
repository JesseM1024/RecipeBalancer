package com.jessetech.recipebalancer.mc.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.PositionedSoundRecord;
import net.minecraft.util.SoundEvent;

/**
 * 界面音效（1.12.2 没有 {@code SimpleSound}）。
 *
 * <p>按钮点击音仍然走 master 通道，与 {@code SimpleSound.master(...)} 的语义一致。
 */
public final class Sounds {

    private Sounds() {
    }

    public static void master(SoundEvent event, float pitch) {
        Minecraft.getMinecraft().getSoundHandler()
                .playSound(PositionedSoundRecord.getMasterRecord(event, pitch));
    }
}
