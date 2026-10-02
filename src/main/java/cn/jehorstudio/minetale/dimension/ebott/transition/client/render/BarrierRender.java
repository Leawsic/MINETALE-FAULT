package cn.jehorstudio.minetale.dimension.ebott.transition.client.render;

import cn.jehorstudio.minetale.MineTale;
import cn.jehorstudio.minetale.dimension.ebott.transition.client.TransitionClient;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;


// 只消费 TransitionClient 的只读快照绘制接缝薄膜与外缘，不参与碰撞或换维判定。
public final class BarrierRender implements AutoCloseable {
    public static final BarrierRender INSTANCE = new BarrierRender();

    private static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath(
            MineTale.MODID, "dynamic/transition_barrier");
    private static final ResourceLocation EDGE_TEXTURE = ResourceLocation.fromNamespaceAndPath(
            MineTale.MODID, "dynamic/transition_barrier_edge");
    private static final RenderType RENDER_TYPE = RenderType.entityTranslucentEmissive(TEXTURE, false);
    private static final RenderType EDGE_RENDER_TYPE = RenderType.entityTranslucentEmissive(EDGE_TEXTURE, false);

    private DynamicTexture texture;
    private DynamicTexture edgeTexture;
    private float edgeTextureCenterOffsetX = Float.NaN;
    private float edgeTextureCenterOffsetZ = Float.NaN;
    private float edgeTextureRadius = Float.NaN;
    private float edgeTextureMin;
    private float edgeTextureMax;
    private float alpha = 1.0F;

    private BarrierRender() {
    }

    public void render(RenderLevelStageEvent.AfterEntities event) {
        Minecraft minecraft = Minecraft.getInstance();
        TransitionClient.RenderableBarrier barrier = TransitionClient.INSTANCE.renderableBarrier();
        if (barrier == null || event.getLevelRenderer() != minecraft.levelRenderer) {
            resetVisualState();
            return;
        }

        float elapsedSeconds = Math.clamp(
                minecraft.getDeltaTracker().getRealtimeDeltaTicks() / 20.0F,
                0.0F,
                BarrierSettings.MAX_FRAME_DELTA_SECONDS
        );
        updateAlpha(barrier.passable(), barrier.targetSide(), elapsedSeconds);
        ensureTexture(minecraft);

        Camera camera = minecraft.gameRenderer.getMainCamera();
        Vec3 cameraPosition = camera.getPosition();
        if (barrier.targetSide()
                ? cameraPosition.y > barrier.seamY()
                : cameraPosition.y < barrier.seamY()) {
            return;
        }
        float side = barrier.targetSide() ? -1.0F : 1.0F;
        float blockGridCenterX = (float) (barrier.blockGridCenterX() - cameraPosition.x);
        float centerY = (float) (barrier.seamY() - cameraPosition.y) + side * BarrierSettings.PLANE_OFFSET;
        float blockGridCenterZ = (float) (barrier.blockGridCenterZ() - cameraPosition.z);
        float apertureCenterOffsetX = (float) barrier.apertureCenterOffsetX();
        float apertureCenterOffsetZ = (float) barrier.apertureCenterOffsetZ();
        float radius = (float) barrier.radius();
        ensureEdgeTexture(minecraft, apertureCenterOffsetX, apertureCenterOffsetZ, radius);
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        VertexConsumer vertices = buffers.getBuffer(RENDER_TYPE);
        drawMembrane(
                vertices,
                blockGridCenterX,
                centerY,
                blockGridCenterZ,
                apertureCenterOffsetX,
                apertureCenterOffsetZ,
                radius,
                side
        );
        buffers.endBatch(RENDER_TYPE);

        VertexConsumer edgeVertices = buffers.getBuffer(EDGE_RENDER_TYPE);
        drawFlowingEdge(
                edgeVertices,
                blockGridCenterX,
                (float) (barrier.seamY() - cameraPosition.y) - side * BarrierSettings.EDGE_PLANE_DEPTH,
                blockGridCenterZ,
                this.edgeTextureMin,
                this.edgeTextureMax,
                this.alpha
        );
        buffers.endBatch(EDGE_RENDER_TYPE);
    }

    @Override
    public void close() {
        resetVisualState();
        if (this.texture != null) {
            Minecraft.getInstance().getTextureManager().release(TEXTURE);
            this.texture = null;
        }
        if (this.edgeTexture != null) {
            Minecraft.getInstance().getTextureManager().release(EDGE_TEXTURE);
            this.edgeTexture = null;
        }
    }

    private void updateAlpha(boolean passable, boolean targetSide, float elapsedSeconds) {
        if (targetSide || !passable) {
            this.alpha = 1.0F;
            return;
        }
        float fadePerSecond = (1.0F - BarrierSettings.PASSABLE_ALPHA)
                / BarrierSettings.PASSABLE_FADE_SECONDS;
        this.alpha = Math.max(BarrierSettings.PASSABLE_ALPHA, this.alpha - elapsedSeconds * fadePerSecond);
    }

    private void ensureTexture(Minecraft minecraft) {
        if (this.texture != null) {
            return;
        }
        int resolution = BarrierSettings.MEMBRANE_TEXTURE_RESOLUTION;
        NativeImage image = new NativeImage(resolution, resolution, false);
        for (int y = 0; y < resolution; y++) {
            for (int x = 0; x < resolution; x++) {
                image.setPixelABGR(x, y, barrierPixel(x, y));
            }
        }
        this.texture = new DynamicTexture(() -> "MineTale transition barrier", image);
        minecraft.getTextureManager().register(TEXTURE, this.texture);
        this.texture.setClamp(false);
        this.texture.getTexture().setTextureFilter(FilterMode.LINEAR, false);
    }

    private void ensureEdgeTexture(
            Minecraft minecraft,
            float centerOffsetX,
            float centerOffsetZ,
            float radius
    ) {
        if (this.edgeTexture != null
                && this.edgeTextureCenterOffsetX == centerOffsetX
                && this.edgeTextureCenterOffsetZ == centerOffsetZ
                && this.edgeTextureRadius == radius) {
            return;
        }
        if (this.edgeTexture != null) {
            minecraft.getTextureManager().release(EDGE_TEXTURE);
        }

        float extent = Mth.ceil(radius + Math.max(Math.abs(centerOffsetX), Math.abs(centerOffsetZ))
                + BarrierSettings.EDGE_WIDTH + BarrierSettings.EDGE_TEXTURE_PADDING);
        this.edgeTextureMin = -extent;
        this.edgeTextureMax = extent;
        int resolution = BarrierSettings.EDGE_DISTANCE_TEXTURE_RESOLUTION;
        float pixelSpan = (this.edgeTextureMax - this.edgeTextureMin) / resolution;
        int searchRadius = Mth.ceil(BarrierSettings.EDGE_WIDTH
                + pixelSpan * BarrierSettings.EDGE_DISTANCE_OVERSCAN_PIXELS) + 1;
        NativeImage image = new NativeImage(resolution, resolution, false);
        for (int pixelZ = 0; pixelZ < resolution; pixelZ++) {
            float localZ = Mth.lerp((pixelZ + 0.5F) / resolution,
                    this.edgeTextureMin, this.edgeTextureMax);
            for (int pixelX = 0; pixelX < resolution; pixelX++) {
                float localX = Mth.lerp((pixelX + 0.5F) / resolution,
                        this.edgeTextureMin, this.edgeTextureMax);
                int cellX = Mth.floor(localX + 0.5F);
                int cellZ = Mth.floor(localZ + 0.5F);
                int alpha;
                if (insideAperture(cellX, cellZ, centerOffsetX, centerOffsetZ, radius)) {
                    alpha = 0;
                } else {
                    int encodedDistance = encodeOutsideDistance(localX, localZ, cellX, cellZ,
                            centerOffsetX, centerOffsetZ, radius, searchRadius);
                    float progress = (encodedDistance - 128) / 127.0F;
                    progress = progress * progress * (3.0F - 2.0F * progress);
                    alpha = Math.clamp(Math.round(Mth.square(1.0F - progress) * 255.0F), 0, 255);
                }
                float u = 0.5F + (localX - centerOffsetX) / (radius * 2.0F);
                float v = 0.5F + (localZ - centerOffsetZ) / (radius * 2.0F);
                int sourceX = Math.clamp(Mth.floor(u * BarrierSettings.MEMBRANE_TEXTURE_RESOLUTION),
                        0, BarrierSettings.MEMBRANE_TEXTURE_RESOLUTION - 1);
                int sourceY = Math.clamp(Mth.floor(v * BarrierSettings.MEMBRANE_TEXTURE_RESOLUTION),
                        0, BarrierSettings.MEMBRANE_TEXTURE_RESOLUTION - 1);
                int abgr = barrierPixel(sourceX, sourceY) & 0x00FFFFFF | alpha << 24;
                image.setPixelABGR(pixelX, pixelZ, abgr);
            }
        }

        this.edgeTexture = new DynamicTexture(() -> "MineTale transition barrier edge", image);
        minecraft.getTextureManager().register(EDGE_TEXTURE, this.edgeTexture);
        this.edgeTexture.getTexture().setTextureFilter(FilterMode.LINEAR, false);
        this.edgeTextureCenterOffsetX = centerOffsetX;
        this.edgeTextureCenterOffsetZ = centerOffsetZ;
        this.edgeTextureRadius = radius;
    }

    private static int encodeOutsideDistance(
            float localX,
            float localZ,
            int cellX,
            int cellZ,
            float centerOffsetX,
            float centerOffsetZ,
            float radius,
            int searchRadius
    ) {
        float minimumSquared = BarrierSettings.EDGE_WIDTH * BarrierSettings.EDGE_WIDTH;
        for (int candidateZ = cellZ - searchRadius; candidateZ <= cellZ + searchRadius; candidateZ++) {
            for (int candidateX = cellX - searchRadius; candidateX <= cellX + searchRadius; candidateX++) {
                if (!insideAperture(candidateX, candidateZ, centerOffsetX, centerOffsetZ, radius)) {
                    continue;
                }
                float dx = Math.max(Math.abs(localX - candidateX) - 0.5F, 0.0F);
                float dz = Math.max(Math.abs(localZ - candidateZ) - 0.5F, 0.0F);
                minimumSquared = Math.min(minimumSquared, dx * dx + dz * dz);
            }
        }
        float progress = Mth.clamp(Mth.sqrt(minimumSquared) / BarrierSettings.EDGE_WIDTH, 0.0F, 1.0F);
        return 128 + Math.clamp(Math.round(progress * 127.0F), 0, 127);
    }

    private void drawMembrane(
            VertexConsumer vertices,
            float centerX,
            float centerY,
            float centerZ,
            float apertureCenterOffsetX,
            float apertureCenterOffsetZ,
            float radius,
            float side
    ) {
        int extent = Mth.ceil(radius
                + Math.max(Math.abs(apertureCenterOffsetX), Math.abs(apertureCenterOffsetZ)) + 1.0F);
        for (int localZ = -extent; localZ <= extent; localZ++) {
            for (int localX = -extent; localX <= extent; localX++) {
                float dx = localX - apertureCenterOffsetX;
                float dz = localZ - apertureCenterOffsetZ;
                if (dx * dx + dz * dz > radius * radius) {
                    continue;
                }
                // 与竖井生成共用方块中心采样规则，命中单元必须整格覆盖。
                addVertex(vertices, centerX, centerY, centerZ, apertureCenterOffsetX,
                        apertureCenterOffsetZ, radius, side, localX - 0.5F, localZ - 0.5F,
                        this.alpha);
                addVertex(vertices, centerX, centerY, centerZ, apertureCenterOffsetX,
                        apertureCenterOffsetZ, radius, side, localX - 0.5F, localZ + 0.5F,
                        this.alpha);
                addVertex(vertices, centerX, centerY, centerZ, apertureCenterOffsetX,
                        apertureCenterOffsetZ, radius, side, localX + 0.5F, localZ + 0.5F,
                        this.alpha);
                addVertex(vertices, centerX, centerY, centerZ, apertureCenterOffsetX,
                        apertureCenterOffsetZ, radius, side, localX + 0.5F, localZ - 0.5F,
                        this.alpha);
            }
        }
    }

    private static void addVertex(
            VertexConsumer vertices,
            float centerX,
            float centerY,
            float centerZ,
            float apertureCenterOffsetX,
            float apertureCenterOffsetZ,
            float radius,
            float side,
            float localX,
            float localZ,
            float baseAlpha
    ) {
        float u = 0.5F + (localX - apertureCenterOffsetX) / (radius * 2.0F);
        float v = 0.5F + (localZ - apertureCenterOffsetZ) / (radius * 2.0F);
        float normalizedRadius = Mth.sqrt(
                (localX - apertureCenterOffsetX) * (localX - apertureCenterOffsetX)
                        + (localZ - apertureCenterOffsetZ) * (localZ - apertureCenterOffsetZ)
        ) / radius;
        float edge = Mth.clamp((normalizedRadius - BarrierSettings.BODY_EDGE_START)
                / BarrierSettings.BODY_EDGE_WIDTH, 0.0F, 1.0F);
        edge = edge * edge * (3.0F - 2.0F * edge);
        int alpha = Math.clamp(Math.round((baseAlpha + edge * (1.0F - baseAlpha)
                * BarrierSettings.BODY_EDGE_ALPHA_STRENGTH) * 255.0F),
                0, 255);
        vertices.addVertex(centerX + localX, centerY, centerZ + localZ)
                .setColor(255, 255, 255, alpha)
                .setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(LightTexture.FULL_BRIGHT)
                .setNormal(0.0F, side, 0.0F);
    }

    // 边带只向开口外侧扩展，并依赖井壁深度遮挡其不可见部分。
    private static void drawFlowingEdge(
            VertexConsumer vertices,
            float centerX,
            float centerY,
            float centerZ,
            float edgeMin,
            float edgeMax,
            float baseAlpha
    ) {
        addFlowingEdgeVertex(vertices, centerX, centerY, centerZ, edgeMin, edgeMin,
                0.0F, 0.0F, baseAlpha);
        addFlowingEdgeVertex(vertices, centerX, centerY, centerZ, edgeMin, edgeMax,
                0.0F, 1.0F, baseAlpha);
        addFlowingEdgeVertex(vertices, centerX, centerY, centerZ, edgeMax, edgeMax,
                1.0F, 1.0F, baseAlpha);
        addFlowingEdgeVertex(vertices, centerX, centerY, centerZ, edgeMax, edgeMin,
                1.0F, 0.0F, baseAlpha);
    }

    private static void addFlowingEdgeVertex(
            VertexConsumer vertices,
            float centerX,
            float centerY,
            float centerZ,
            float localX,
            float localZ,
            float u,
            float v,
            float baseAlpha
    ) {
        vertices.addVertex(centerX + localX, centerY, centerZ + localZ)
                .setColor(255, 255, 255, Math.clamp(Math.round(baseAlpha * 255.0F), 0, 255))
                .setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(LightTexture.FULL_BRIGHT)
                .setNormal(0.0F, 1.0F, 0.0F);
    }

    private static boolean insideAperture(
            int localX,
            int localZ,
            float centerOffsetX,
            float centerOffsetZ,
            float radius
    ) {
        float dx = localX - centerOffsetX;
        float dz = localZ - centerOffsetZ;
        return dx * dx + dz * dz <= radius * radius;
    }

    private void resetVisualState() {
        this.alpha = 1.0F;
        this.edgeTextureCenterOffsetX = Float.NaN;
        this.edgeTextureCenterOffsetZ = Float.NaN;
        this.edgeTextureRadius = Float.NaN;
    }

    private static int barrierPixel(int x, int y) {
        return BarrierMaterial.pixelAbgr(x, y);
    }

}
