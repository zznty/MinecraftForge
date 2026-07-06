/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.minecraftforge.client.loading.earlydisplay;

import net.minecraftforge.fml.earlydisplay.BaseShader;

public class Blaze3DShader extends BaseShader {
    RenderType renderType = RenderType.TEXTURE;
    int textureNumber = 0;
    int screenWidth = 1;
    int screenHeight = 1;

    @Override public void init() {}
    @Override public void activate() {}
    @Override public void clear() {}
    @Override public void updateTextureUniform(int textureNumber) { this.textureNumber = textureNumber; }
    @Override public void updateScreenSizeUniform(int width, int height) { this.screenWidth = width; this.screenHeight = height; }
    @Override public void updateRenderTypeUniform(RenderType type) { this.renderType = type; }
    @Override public void close() {}
}
