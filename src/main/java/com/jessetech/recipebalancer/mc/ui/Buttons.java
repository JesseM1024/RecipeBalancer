package com.jessetech.recipebalancer.mc.ui;

import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/**
 * 按钮工厂。
 *
 * <p>{@code Button.builder(...).bounds(x, y, w, h).build()} 是 1.19+ 的写法；
 * 1.18.2 只能 {@code new Button(x, y, w, h, message, onPress)}。
 * 本类的 {@link Builder} 保持与 1.20 线同样的调用形状，
 * 界面代码只需把 {@code Button.builder} 换成 {@link Buttons#builder}。
 */
public final class Buttons {

    private Buttons() {
    }

    public static Builder builder(Component message, Button.OnPress onPress) {
        return new Builder(message, onPress);
    }

    /** 与 {@code Button.Builder} 同名同形的极简建造器。 */
    public static final class Builder {

        private final Component message;
        private final Button.OnPress onPress;
        private int x;
        private int y;
        private int width = 150;
        private int height = 20;

        private Builder(Component message, Button.OnPress onPress) {
            this.message = message;
            this.onPress = onPress;
        }

        public Builder bounds(int x, int y, int width, int height) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            return this;
        }

        public Button build() {
            return new Button(x, y, width, height, message, onPress);
        }
    }
}
