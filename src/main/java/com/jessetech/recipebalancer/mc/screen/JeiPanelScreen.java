package com.jessetech.recipebalancer.mc.screen;

/** 供 JEI 注册非 ContainerScreen 的面板区域（guiLeft/guiTop/尺寸）。 */
public interface JeiPanelScreen {

    int jeiGuiLeft();

    int jeiGuiTop();

    int jeiGuiWidth();

    int jeiGuiHeight();
}
