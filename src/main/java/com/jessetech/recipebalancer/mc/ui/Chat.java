package com.jessetech.recipebalancer.mc.ui;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.network.chat.TranslatableComponent;

/**
 * 聊天组件的静态工厂。
 *
 * <p>{@code Component.literal(...)} / {@code Component.empty()} 是 1.19+ 才有的写法；
 * 1.18.2 只有 {@code TextComponent} / {@code TranslatableComponent} 构造器。
 * 界面代码统一走这里，调用形状与 1.20 线保持一致，再移植时只改本文件。
 */
public final class Chat {

    private Chat() {
    }

    public static MutableComponent literal(String text) {
        return new TextComponent(text);
    }

    public static MutableComponent empty() {
        return new TextComponent("");
    }

    public static MutableComponent translatable(String key, Object... args) {
        return new TranslatableComponent(key, args);
    }

    /** 原样返回：便于写 {@code Chat.of(component)} 这类兼容代码。 */
    public static Component of(Component component) {
        return component;
    }
}
