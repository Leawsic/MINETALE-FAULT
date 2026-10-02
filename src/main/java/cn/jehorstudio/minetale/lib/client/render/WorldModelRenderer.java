package cn.jehorstudio.minetale.lib.client.render;

import cn.jehorstudio.minetale.lib.ObjLoader;
import cn.jehorstudio.minetale.lib.ObjModels;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;

// 通过原版 RenderType 绘制具有自身深度关系的世界空间自发光 OBJ。
public final class WorldModelRenderer {
    private final ObjModels model;
    private final float scale;
    private final float red;
    private final float green;
    private final float blue;
    private final float opacity;
    private final RenderType depthRenderType;
    private final RenderType emissiveRenderType;

    public WorldModelRenderer(
            ObjModels model,
            float scale,
            float red,
            float green,
            float blue,
            float opacity
    ) {
        this.model = Objects.requireNonNull(model, "model");
        requirePositiveFinite(scale, "scale");
        requireUnit(red, "red");
        requireUnit(green, "green");
        requireUnit(blue, "blue");
        requireUnit(opacity, "opacity");
        this.scale = scale;
        this.red = red;
        this.green = green;
        this.blue = blue;
        this.opacity = opacity;
        this.depthRenderType = RenderType.entityCutoutNoCull(model.getTexturePath(), false);
        this.emissiveRenderType = RenderType.entityTranslucentEmissive(model.getTexturePath(), false);
    }

    public void render(
            RenderLevelStageEvent.AfterEntities event,
            Vec3 worldPosition,
            Matrix4f orientation
    ) {
        Minecraft minecraft = Minecraft.getInstance();
        Vec3 cameraPosition = event.getLevelRenderState().cameraRenderState.pos;
        Vec3 relative = Objects.requireNonNull(worldPosition, "worldPosition").subtract(cameraPosition);
        Objects.requireNonNull(orientation, "orientation");
        PoseStack poseStack = event.getPoseStack();
        ObjLoader.LocalBounds bounds = ObjLoader.getOrLoad(this.model).getLocalBounds();

        poseStack.pushPose();
        Matrix4fStack modelViewStack = RenderSystem.getModelViewStack();
        modelViewStack.pushMatrix();
        try {
            modelViewStack.set(event.getModelViewMatrix());
            poseStack.translate(relative.x, relative.y, relative.z);
            poseStack.mulPose(orientation);
            poseStack.scale(this.scale, this.scale, this.scale);
            poseStack.translate(-bounds.centerX(), -bounds.centerY(), -bounds.centerZ());
            MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
            drawLayer(poseStack, buffers, this.depthRenderType);
            drawLayer(poseStack, buffers, this.emissiveRenderType);
        } finally {
            modelViewStack.popMatrix();
            poseStack.popPose();
        }
    }

    private void drawLayer(
            PoseStack poseStack,
            MultiBufferSource.BufferSource buffers,
            RenderType renderType
    ) {
        ObjLoader.render(
                poseStack,
                buffers.getBuffer(renderType),
                this.model,
                LightTexture.FULL_BRIGHT,
                this.red,
                this.green,
                this.blue,
                this.opacity
        );
        buffers.endBatch(renderType);
    }

    private static void requirePositiveFinite(float value, String name) {
        if (!Float.isFinite(value) || value <= 0.0F) {
            throw new IllegalArgumentException(name + " 必须是有限正数");
        }
    }

    private static void requireUnit(float value, String name) {
        if (!Float.isFinite(value) || value < 0.0F || value > 1.0F) {
            throw new IllegalArgumentException(name + " 必须在 0 到 1 之间");
        }
    }
}
