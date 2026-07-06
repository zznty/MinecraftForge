/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.minecraftforge.client.loading.earlydisplay;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraftforge.fml.earlydisplay.BaseFramebuffer;
import net.minecraftforge.fml.earlydisplay.BaseRenderBackend;
import net.minecraftforge.fml.earlydisplay.BaseShader;
import net.minecraftforge.fml.earlydisplay.ColourScheme;
import net.minecraftforge.fml.earlydisplay.PerformanceInfo;
import net.minecraftforge.fml.earlydisplay.RenderElement;
import net.minecraftforge.fml.earlydisplay.VertexDataBuilder;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

public class Blaze3DRenderBackend extends BaseRenderBackend {
    public static final long MOJANG_TEXTURE_ID = 42884691L;
    private static final int MAX_TEXTURES = 16;

    private Blaze3DShader vkShader;
    private final GpuTexture[] textures = new GpuTexture[MAX_TEXTURES];
    private final GpuTextureView[] textureViews = new GpuTextureView[MAX_TEXTURES];
    private GpuTextureView boundTextureView;

    private GpuSampler sampler;
    private GpuTexture placeholder;
    private GpuTextureView placeholderView;
    private ProjectionMatrixBuffer projectionBuffer;
    private Supplier<GpuTextureView> mojangTextureSupplier;

    private GpuTexture target;
    private GpuTextureView targetView;
    private int targetWidth = -1, targetHeight = -1;

    private final List<DrawCommand> drawCommands = new ArrayList<>();
    private ByteBuffer vertexStaging = ByteBuffer.allocateDirect(65536);
    private int vertexStagingLength;
    private GpuBuffer vertexBuffer;

    record DrawCommand(BaseShader.RenderType renderType, GpuTextureView texture, int offset, int vertexCount) {}

    @Override public void applyWindowHints() {}
    @Override public long createWindow(int winWidth, int winHeight, String title) {
        throw new UnsupportedOperationException("Blaze3DRenderBackend does not own the window");
    }

    @Override
    public void initialize(long window, ColourScheme colours, int fbScale, PerformanceInfo perfInfo, String mcVersion) {
        this.colourScheme = colours;
        this.version = "Blaze3D";
        this.vkShader = new Blaze3DShader();
        this.shader = vkShader;

        var device = RenderSystem.getDevice();
        this.sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);

        this.placeholder = device.createTexture("forge_early_placeholder",
            GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST, GpuFormat.RGBA8_UNORM, 1, 1, 1, 1);
        this.placeholderView = device.createTextureView(placeholder);
        var white = ByteBuffer.allocateDirect(4);
        white.put((byte) -1).put((byte) -1).put((byte) -1).put((byte) -1).flip();
        device.createCommandEncoder().writeToTexture(placeholder, white, 0, 0, 0, 0, 1, 1);

        this.projectionBuffer = new ProjectionMatrixBuffer("forge_early_display");
        this.context = new RenderElement.DisplayContext(854, 480, fbScale, shader, colours, perfInfo, this, this::createBufferBuilder);
    }

    public GpuTextureView renderElements(Runnable paintElements) {
        int w = context.scaledWidth();
        int h = context.scaledHeight();
        ensureTarget(w, h);

        var device = RenderSystem.getDevice();
        device.createCommandEncoder().submit();

        drawCommands.clear();
        vertexStaging.clear();
        vertexStagingLength = 0;
        paintElements.run();

        ensureVertexBuffer(device, vertexStagingLength);
        if (vertexStagingLength > 0) {
            vertexStaging.limit(vertexStagingLength).position(0);
            device.createCommandEncoder().writeToBuffer(vertexBuffer.slice(0, vertexStagingLength), vertexStaging);
        }

        var proj = new Matrix4f().setOrtho(0.0F, w, h, 0.0F, 1000.0F, 11000.0F);
        RenderSystem.setProjectionMatrix(projectionBuffer.getBuffer(proj), ProjectionType.ORTHOGRAPHIC);
        var dynamicTransforms = RenderSystem.getDynamicUniforms().writeTransform(new Matrix4f().setTranslation(0.0F, 0.0F, -11000.0F));

        var bg = colourScheme.background();
        var clearColor = new Vector4f(bg.redf(), bg.greenf(), bg.bluef(), 1.0F);

        var indexBuffer = RenderSystem.getSequentialBuffer(com.mojang.blaze3d.PrimitiveTopology.QUADS);
        int maxIndexCount = 0;
        for (var cmd : drawCommands) {
            int ic = cmd.vertexCount() / 4 * 6;
            if (ic > maxIndexCount) maxIndexCount = ic;
        }
        if (maxIndexCount > 0) indexBuffer.getBuffer(maxIndexCount);

        try (RenderPass pass = device.createCommandEncoder().createRenderPass(() -> "Forge Early Display Overlay", targetView, Optional.of(clearColor))) {
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("DynamicTransforms", dynamicTransforms);
            for (var cmd : drawCommands) {
                pass.setPipeline(cmd.renderType() == BaseShader.RenderType.FONT
                    ? RenderPipelines.GUI_TEXTURED_PREMULTIPLIED_ALPHA
                    : RenderPipelines.GUI_TEXTURED);
                pass.bindTexture("Sampler0", cmd.texture(), sampler);
                pass.setVertexBuffer(0, vertexBuffer.slice(cmd.offset(), cmd.vertexCount() * 24));
                int indexCount = cmd.vertexCount() / 4 * 6;
                pass.setIndexBuffer(indexBuffer.getBuffer(indexCount), indexBuffer.type());
                pass.drawIndexed(indexCount, 1, 0, 0, 0);
            }
        }
        device.createCommandEncoder().submit();

        drawCommands.clear();
        return targetView;
    }

    void recordDraw(ByteBuffer vertexBytes, int vertexCount) {
        int needed = vertexCount * 24;
        if (vertexStagingLength + needed > vertexStaging.capacity()) {
            int newCap = Integer.highestOneBit(vertexStagingLength + needed) << 2;
            var grown = ByteBuffer.allocateDirect(newCap);
            vertexStaging.limit(vertexStagingLength).position(0);
            grown.put(vertexStaging);
            vertexStaging = grown;
        }
        int offset = vertexStagingLength;
        vertexStaging.limit(vertexStaging.capacity()).position(vertexStagingLength);
        vertexStaging.put(vertexBytes);
        vertexStagingLength += needed;
        drawCommands.add(new DrawCommand(vkShader.renderType, textureViewFor(vkShader.textureNumber), offset, vertexCount));
    }

    private void ensureVertexBuffer(com.mojang.blaze3d.systems.GpuDevice device, int needed) {
        if (vertexBuffer != null && vertexBuffer.size() >= needed) return;
        if (vertexBuffer != null) vertexBuffer.close();
        int cap = Math.max(65536, Integer.highestOneBit(needed - 1) << 2);
        vertexBuffer = device.createBuffer(() -> "forge_early_vertices",
            GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST, cap);
    }

    private void ensureTarget(int w, int h) {
        if (target != null && targetWidth == w && targetHeight == h) return;
        var device = RenderSystem.getDevice();
        if (targetView != null) targetView.close();
        if (target != null) target.close();
        this.target = device.createTexture("forge_early_target",
            GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING,
            GpuFormat.RGBA8_UNORM, w, h, 1, 1);
        this.targetView = device.createTextureView(target);
        this.targetWidth = w;
        this.targetHeight = h;
    }

    private GpuTextureView textureViewFor(int slot) {
        if (slot == 0 && boundTextureView != null) return boundTextureView;
        if (slot >= 0 && slot < MAX_TEXTURES && textureViews[slot] != null) return textureViews[slot];
        return placeholderView;
    }

    @Override public void close() {
        if (vertexBuffer != null) vertexBuffer.close();
        if (projectionBuffer != null) projectionBuffer.close();
        if (targetView != null) targetView.close();
        if (target != null) target.close();
        if (placeholderView != null) placeholderView.close();
        if (placeholder != null) placeholder.close();
        for (int i = 0; i < MAX_TEXTURES; i++) {
            if (textureViews[i] != null) textureViews[i].close();
            if (textures[i] != null) textures[i].close();
        }
    }

    @Override public void makeCurrent(long window) {}
    @Override public void releaseCurrent() {}
    @Override public void setVsync(boolean enabled) {}
    @Override public void beginFrame() {}
    @Override public void endFrame(int fbWidth, int fbHeight) {}
    @Override public void beginOverlay(int alpha) { RenderElement.globalAlpha = alpha; }
    @Override public void endOverlay() {}
    @Override public BaseShader createShader() { return new Blaze3DShader(); }
    @Override public BaseFramebuffer createFramebuffer(int width, int height, int scale, ColourScheme colours) { return null; }
    @Override public VertexDataBuilder createBufferBuilder() { return new Blaze3DVertexBuilder(this); }

    @Override public void uploadTexture(ByteBuffer pixels, int width, int height, int slot) {
        uploadRGBA(pixels, width, height, slot);
    }

    @Override public void uploadFontTexture(ByteBuffer alphaBitmap, int width, int height, int slot) {
        var rgba = ByteBuffer.allocateDirect(width * height * 4);
        for (int i = 0; i < width * height; i++) {
            rgba.put((byte) -1).put((byte) -1).put((byte) -1).put(alphaBitmap.get(i));
        }
        rgba.flip();
        uploadRGBA(rgba, width, height, slot);
    }

    private void uploadRGBA(ByteBuffer pixels, int width, int height, int slot) {
        var device = RenderSystem.getDevice();
        if (textureViews[slot] != null) textureViews[slot].close();
        if (textures[slot] != null) textures[slot].close();
        var tex = device.createTexture(() -> "forge_early_tex_" + slot,
            GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST, GpuFormat.RGBA8_UNORM, width, height, 1, 1);
        device.createCommandEncoder().writeToTexture(tex, pixels, 0, 0, 0, 0, width, height);
        textures[slot] = tex;
        textureViews[slot] = device.createTextureView(tex);
    }

    public void setMojangTextureSupplier(Supplier<GpuTextureView> supplier) {
        this.mojangTextureSupplier = supplier;
    }

    @Override public void bindTexture(long handle) {
        if (handle != MOJANG_TEXTURE_ID) return;
        GpuTextureView view = null;
        if (mojangTextureSupplier != null) {
            view = mojangTextureSupplier.get();
        }
        this.boundTextureView = view != null ? view : placeholderView;
    }

    @Override public void unbindTexture() { this.boundTextureView = null; }
    @Override public void prepareHandoff(long window) {}
    @Override public String getVersion() { return version; }
    @Override public long getFramebufferTextureHandle() { return 0; }
}
