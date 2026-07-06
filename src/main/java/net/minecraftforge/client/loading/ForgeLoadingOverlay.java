/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.minecraftforge.client.loading;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.LoadingOverlay;
import net.minecraft.server.packs.resources.ReloadInstance;
import net.minecraft.util.Mth;
import net.minecraftforge.client.loading.earlydisplay.Blaze3DRenderBackend;
import net.minecraftforge.fml.StartupMessageManager;
import net.minecraftforge.fml.earlydisplay.DisplayWindow;
import net.minecraftforge.fml.loading.progress.ProgressMeter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

@SuppressWarnings("unused")
public class ForgeLoadingOverlay extends LoadingOverlay {
    private static final boolean ENABLE = true;
    private static final Logger LOGGER = LoggerFactory.getLogger(ForgeLoadingOverlay.class);

    private final Minecraft minecraft;
    private final ReloadInstance reload;
    private final DisplayWindow displayWindow;
    private final ProgressMeter progress;
    private final Blaze3DRenderBackend blazeBackend;
    private final GpuSampler sampler;
    private boolean closed;
    private long fadeOutStart = -1L;

    public ForgeLoadingOverlay(final Minecraft mc, final ReloadInstance reloader, final Consumer<Optional<Throwable>> errorConsumer, DisplayWindow displayWindow) {
        super(mc, reloader, errorConsumer, false);
        this.minecraft = mc;
        this.reload = reloader;
        this.displayWindow = displayWindow;

        this.blazeBackend = new Blaze3DRenderBackend();
        this.sampler = RenderSystem.getSamplerCache().getClampToEdge(com.mojang.blaze3d.textures.FilterMode.NEAREST);
        displayWindow.setBackend(blazeBackend);

        try {
            blazeBackend.setMojangTextureSupplier(() ->
                mc.getTextureManager().getTexture(MOJANG_STUDIOS_LOGO_LOCATION).getTextureView());
            displayWindow.addMojangTexture(Blaze3DRenderBackend.MOJANG_TEXTURE_ID);
        } catch (Throwable t) {
            LOGGER.debug("Could not hand off Mojang logo texture to early display", t);
        }

        this.progress = StartupMessageManager.prependProgressBar("Minecraft Progress", 100);
    }

    public static Supplier<LoadingOverlay> newInstance(Supplier<Minecraft> mc, Supplier<ReloadInstance> ri, Consumer<Optional<Throwable>> handler, DisplayWindow window) {
        return () -> new ForgeLoadingOverlay(mc.get(), ri.get(), handler, window);
    }

    @Override
    public void extractRenderState(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float a) {
        if (!ENABLE) {
            super.extractRenderState(graphics, mouseX, mouseY, a);
            return;
        }

        progress.setAbsolute(Mth.clamp((int) (this.reload.getActualProgress() * 100f), 0, 100));

        long now = System.currentTimeMillis();
        if (this.fadeOutStart == -1L && this.reload.isDone()) {
            this.fadeOutStart = now;
        }
        float fadeOutAnim = this.fadeOutStart > -1L ? (float)(now - this.fadeOutStart) / 1000.0F : -1.0F;
        float logoAlpha = fadeOutAnim >= 1.0F
            ? 1.0F - Mth.clamp(fadeOutAnim - 1.0F, 0.0F, 1.0F)
            : 1.0F;

        int width = graphics.guiWidth();
        int height = graphics.guiHeight();

        GpuTextureView view = blazeBackend.renderElements(() -> displayWindow.render(255));
        graphics.blit(view, sampler, 0, 0, width, height, 0f, 1f, 1f, 0f);

        int fadeAlpha = (int) ((1.0F - logoAlpha) * 255);
        if (fadeAlpha > 0) {
            graphics.fill(0, 0, width, height, (fadeAlpha << 24));
        }

        if (fadeOutAnim >= 2.0F) {
            this.minecraft.gui.setOverlay(null);
            if (!closed) {
                closed = true;
                blazeBackend.close();
            }
        }
    }
}
