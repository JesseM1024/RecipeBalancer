package com.jessetech.recipebalancer.mc.ui.graph;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.lwjgl.opengl.GL11;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 逐物品图标纹理缓存。
 *
 * <p>只缓存物品图标本身，不缓存整张流程图；节点拖动时直接 blit 图标，
 * 不会出现整图旧帧。动态图标（附魔光效、时钟/指南针、动画贴图等）不缓存，
 * 继续走原 {@code ItemRenderer}。
 */
public final class ItemIconTextureCache {

    public static final ItemIconTextureCache INSTANCE = new ItemIconTextureCache();

    private static final int ICON_SIZE = 64;
    private static final float RENDER_SCALE = 4.0f;
    private static final long STATIC_REFRESH_NANOS = 1_000_000_000L;

    private final Map<String, IconEntry> icons = new HashMap<>();
    private final Set<String> dynamic = new HashSet<>();
    private final Set<String> failed = new HashSet<>();

    private ItemIconTextureCache() {
    }

    /** 在开始绘制图之前准备所有静态图标，避免在节点循环中切换 FBO。 */
    public void prepare(GuiGraphics graphics, String key, ItemStack stack) {
        if (stack.isEmpty() || dynamic.contains(key) || failed.contains(key)) {
            return;
        }
        long now = System.nanoTime();
        IconEntry existing = icons.get(key);
        if (existing != null && now - existing.renderedAt < STATIC_REFRESH_NANOS) {
            return;
        }
        if (isDynamic(stack)) {
            dynamic.add(key);
            icons.remove(key);
            return;
        }
        if (graphics == null) {
            return;
        }
        try {
            TextureTarget target = existing == null ? createTarget() : existing.target;
            graphics.flush();
            renderToTarget(target, stack);
            icons.put(key, new IconEntry(target, now));
        } catch (RuntimeException e) {
            failed.add(key);
        }
    }

    /**
     * 尝试绘制缓存图标；返回 false 表示调用方应回退到实时 item 渲染。
     */
    public boolean draw(GuiGraphics graphics, String key,
                        double x, double y, double size) {
        IconEntry entry = icons.get(key);
        if (entry == null || dynamic.contains(key) || failed.contains(key)) {
            return false;
        }
        try {
            drawTexture(graphics, entry.target, x, y, size);
            return true;
        } catch (RuntimeException e) {
            failed.add(key);
            return false;
        }
    }

    public void close() {
        for (IconEntry entry : icons.values()) {
            entry.target.destroyBuffers();
        }
        icons.clear();
        dynamic.clear();
        failed.clear();
    }

    private TextureTarget createTarget() {
        TextureTarget target = new TextureTarget(ICON_SIZE, ICON_SIZE, true, false);
        target.setFilterMode(GL11.GL_NEAREST);
        return target;
    }

    private void renderToTarget(TextureTarget target, ItemStack stack) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.getMainRenderTarget() == null) {
            throw new IllegalStateException("no main render target");
        }
        target.bindWrite(true);
        target.setClearColor(0.0f, 0.0f, 0.0f, 0.0f);
        target.clear(Minecraft.ON_OSX);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.backupProjectionMatrix();
        RenderSystem.setProjectionMatrix(
                new Matrix4f().setOrtho(0, ICON_SIZE, ICON_SIZE, 0, 1000, 21000),
                com.mojang.blaze3d.vertex.VertexSorting.ORTHOGRAPHIC_Z);
        Matrix4fStack modelView = RenderSystem.getModelViewStack();
        modelView.pushMatrix();
        modelView.identity();
        try {
            GuiGraphics offscreen = new GuiGraphics(minecraft,
                    minecraft.renderBuffers().bufferSource());
            offscreen.pose().scale(RENDER_SCALE, RENDER_SCALE, 1.0f);
            offscreen.renderItem(stack.copyWithCount(1), 0, 0);
            offscreen.flush();
        } finally {
            modelView.popMatrix();
            RenderSystem.restoreProjectionMatrix();
            target.unbindWrite();
            minecraft.getMainRenderTarget().bindWrite(true);
        }
    }

    private void drawTexture(GuiGraphics graphics, TextureTarget target,
                             double x, double y, double size) {
        // 先提交此前的节点背景 fill，避免随后直接绘制的图标被延迟批处理覆盖。
        graphics.flush();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.setShaderTexture(0, target.getColorTextureId());
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);

        Matrix4f matrix = graphics.pose().last().pose();
        BufferBuilder builder = Tesselator.getInstance().begin(
                VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        // FBO 纹理原点在左下；翻转 V，使图标正向显示。
        builder.addVertex(matrix, (float) x, (float) y, 0.0f).setUv(0.0f, 1.0f);
        builder.addVertex(matrix, (float) x, (float) (y + size), 0.0f).setUv(0.0f, 0.0f);
        builder.addVertex(matrix, (float) (x + size), (float) (y + size), 0.0f).setUv(1.0f, 0.0f);
        builder.addVertex(matrix, (float) (x + size), (float) y, 0.0f).setUv(1.0f, 1.0f);
        BufferUploader.drawWithShader(builder.buildOrThrow());
    }

    private boolean isDynamic(ItemStack stack) {
        if (stack.hasFoil()) {
            return true;
        }
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (id != null && (id.equals(ResourceLocation.withDefaultNamespace("clock"))
                || id.equals(ResourceLocation.withDefaultNamespace("compass"))
                || id.equals(ResourceLocation.withDefaultNamespace("recovery_compass"))
                || id.equals(ResourceLocation.withDefaultNamespace("filled_map")))) {
            return true;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            return true;
        }
        try {
            BakedModel model = minecraft.getItemRenderer().getModel(stack,
                    minecraft.level, minecraft.player, 0);
            if (model == null || model.isCustomRenderer()) {
                return true;
            }
            // 动画贴图通常有多帧；只检测一次，避免缓存冻结动画。
            return model.getParticleIcon().contents()
                    .getUniqueFrames().limit(2).count() > 1;
        } catch (RuntimeException e) {
            return true;
        }
    }

    private record IconEntry(TextureTarget target, long renderedAt) {
    }
}
