/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.minecraftforge.client.loading.earlydisplay;

import net.minecraftforge.fml.earlydisplay.VertexDataBuilder;
import org.lwjgl.system.MemoryUtil;

public class Blaze3DVertexBuilder extends VertexDataBuilder {
    private final Blaze3DRenderBackend backend;

    public Blaze3DVertexBuilder(Blaze3DRenderBackend backend) {
        super(1);
        this.backend = backend;
    }

    @Override
    public void draw() {
        if (!building) throw new IllegalStateException("Not building.");
        try {
            if (vertices == 0 || mode != Mode.QUADS) return;
            backend.recordDraw(MemoryUtil.memByteBuffer(bufferAddr, index), vertices);
        } finally {
            building = false;
            vertices = 0;
            index = 0;
        }
    }
}
