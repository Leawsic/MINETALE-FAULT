package cn.jehorstudio.minetale.lib.client.particle.gpu;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RegisterRenderPipelinesEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

// 拥有程序化粒子共享 Mask；同帧动作共用深度并合成为一次材质提交。
final class GpuParticleRenderer implements AutoCloseable {
    private static final String LABEL = "MineTale GPU particle mask";
    private static final int PROXY_PADDING_PIXELS = 4;
    private static final int UNIFORM_USAGE = GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE;
    private static final Vector4f WHITE = new Vector4f(1.0F, 1.0F, 1.0F, 1.0F);
    private static final Vector3f ZERO = new Vector3f();
    private static final Matrix4f IDENTITY = new Matrix4f();

    private final GpuParticleMaterial material = new GpuParticleMaterial();
    private final List<ParticleDraw> queuedDraws = new ArrayList<>();
    private MappableRingBuffer uniform;
    private TextureTarget particleMaskTarget;
    private ScreenBounds combinedBounds;

    void registerPipeline(RegisterRenderPipelinesEvent event) {
        this.material.registerPipeline(event);
    }

    void beginFrame() {
        this.queuedDraws.clear();
        this.combinedBounds = null;
    }

    void queue(
            RenderLevelStageEvent.AfterEntities event,
            ParticleDefinition definition,
            RenderPipeline pipeline,
            ParticleFrame frame,
            Matrix4f projectionMatrix
    ) {
        Minecraft minecraft = Minecraft.getInstance();
        Vec3 cameraPosition = event.getLevelRenderState().cameraRenderState.pos;
        GpuTextureView depthTexture = effectiveDepthTexture(minecraft);
        int width = depthTexture.getWidth(0);
        int height = depthTexture.getHeight(0);
        ScreenBounds bounds = computeParticleBounds(
                event,
                frame,
                projectionMatrix,
                cameraPosition,
                width,
                height
        );
        if (bounds == null) {
            return;
        }
        this.queuedDraws.add(new ParticleDraw(definition, pipeline, frame, bounds));
        this.combinedBounds = this.combinedBounds == null
                ? bounds
                : this.combinedBounds.union(bounds);
    }

    void endFrame(RenderLevelStageEvent.AfterEntities event, Matrix4f projectionMatrix) {
        try {
            if (this.queuedDraws.isEmpty()) {
                return;
            }
            Minecraft minecraft = Minecraft.getInstance();
            GpuTextureView depthTexture = effectiveDepthTexture(minecraft);
            ensureParticleMask(minecraft, depthTexture.getWidth(0), depthTexture.getHeight(0));
            clearParticleMask(this.combinedBounds);
            Vec3 cameraPosition = event.getLevelRenderState().cameraRenderState.pos;
            for (ParticleDraw draw : this.queuedDraws) {
                drawParticleMask(
                        minecraft,
                        event,
                        draw.definition(),
                        draw.pipeline(),
                        draw.frame(),
                        cameraPosition,
                        depthTexture,
                        draw.bounds()
                );
            }
            this.material.draw(materialBounds(), projectionMatrix);
        } finally {
            this.queuedDraws.clear();
            this.combinedBounds = null;
        }
    }

    private void drawParticleMask(
            Minecraft minecraft,
            RenderLevelStageEvent.AfterEntities event,
            ParticleDefinition definition,
            RenderPipeline pipeline,
            ParticleFrame frame,
            Vec3 cameraPosition,
            GpuTextureView depthTexture,
            ScreenBounds bounds
    ) {
        ensureUniform();
        uploadUniform(definition, frame, cameraPosition);

        var spriteTexture = minecraft.getTextureManager().getTexture(definition.spriteTexture());
        spriteTexture.setUseMipmaps(false);
        GpuTextureView particleTexture = spriteTexture.getTextureView();
        try {
            GpuBufferSlice transforms = RenderSystem.getDynamicUniforms().writeTransform(
                    event.getModelViewMatrix(),
                    WHITE,
                    ZERO,
                    IDENTITY,
                    1.0F
            );
            try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                    () -> "MineTale GPU particle " + definition.id(),
                    this.particleMaskTarget.getColorTextureView(),
                    OptionalInt.empty(),
                    this.particleMaskTarget.getDepthTextureView(),
                    OptionalDouble.empty()
            )) {
                pass.setViewport(0, 0, this.particleMaskTarget.width, this.particleMaskTarget.height);
                pass.enableScissor(
                        bounds.pixelX(this.particleMaskTarget.width),
                        bounds.pixelY(this.particleMaskTarget.height),
                        bounds.pixelWidth(this.particleMaskTarget.width),
                        bounds.pixelHeight(this.particleMaskTarget.height)
                );
                pass.setPipeline(pipeline);
                RenderSystem.bindDefaultUniforms(pass);
                pass.setUniform("DynamicTransforms", transforms);
                pass.setUniform(GpuParticleUniform.NAME, this.uniform.currentBuffer());
                pass.bindSampler("Sampler0", particleTexture);
                pass.bindSampler("SceneDepthSampler", depthTexture);
                pass.draw(0, Math.multiplyExact(
                        definition.particleCount(),
                        ParticleDefinition.VERTICES_PER_PARTICLE
                ));
            }
        } finally {
            this.uniform.rotate();
        }
    }

    private void uploadUniform(
            ParticleDefinition definition,
            ParticleFrame frame,
            Vec3 cameraPosition
    ) {
        try (GpuBuffer.MappedView view = RenderSystem.getDevice().createCommandEncoder()
                .mapBuffer(this.uniform.currentBuffer(), false, true)) {
            GpuParticleUniform.write(
                    com.mojang.blaze3d.buffers.Std140Builder.intoBuffer(view.data()),
                    definition,
                    frame,
                    cameraPosition
            );
        }
    }

    private static GpuTextureView effectiveDepthTexture(Minecraft minecraft) {
        if (RenderSystem.outputDepthTextureOverride != null) {
            return RenderSystem.outputDepthTextureOverride;
        }
        return Objects.requireNonNull(
                minecraft.getMainRenderTarget().getDepthTextureView(),
                "程序化粒子需要世界深度纹理"
        );
    }

    private void ensureParticleMask(Minecraft minecraft, int width, int height) {
        if (this.particleMaskTarget == null) {
            this.particleMaskTarget = new TextureTarget(LABEL, width, height, true);
        } else if (this.particleMaskTarget.width != width || this.particleMaskTarget.height != height) {
            this.particleMaskTarget.resize(width, height);
        }

        this.material.attach(this.particleMaskTarget);
    }

    private void clearParticleMask(ScreenBounds bounds) {
        int x = bounds.pixelX(this.particleMaskTarget.width);
        int y = bounds.pixelY(this.particleMaskTarget.height);
        int width = bounds.pixelWidth(this.particleMaskTarget.width);
        int height = bounds.pixelHeight(this.particleMaskTarget.height);
        RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(
                Objects.requireNonNull(this.particleMaskTarget.getColorTexture()),
                0x00000000,
                Objects.requireNonNull(this.particleMaskTarget.getDepthTexture()),
                1.0,
                x,
                y,
                width,
                height
        );
    }

    private static ScreenBounds computeParticleBounds(
            RenderLevelStageEvent.AfterEntities event,
            ParticleFrame frame,
            Matrix4f projectionMatrix,
            Vec3 cameraPosition,
            int viewportWidth,
            int viewportHeight
    ) {
        Matrix4f viewProjection = new Matrix4f(projectionMatrix).mul(event.getModelViewMatrix());
        Vec3 anchor = frame.anchor().subtract(cameraPosition);
        float minX = Float.POSITIVE_INFINITY;
        float minY = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY;
        float maxY = Float.NEGATIVE_INFINITY;
        int frontCount = 0;
        int behindCount = 0;
        Vector4f clip = new Vector4f();

        for (int axialIndex = 0; axialIndex < 2; axialIndex++) {
            float axial = axialIndex == 0
                    ? frame.bounds().minimumAxial()
                    : frame.bounds().maximumAxial();
            for (int corner = 0; corner < 4; corner++) {
                float rightSign = (corner & 1) == 0 ? -1.0F : 1.0F;
                float upSign = (corner & 2) == 0 ? -1.0F : 1.0F;
                clip.set(
                        (float) anchor.x
                                + (float) frame.axis().x * axial
                        + (float) frame.radialRight().x * frame.bounds().radialExtent() * rightSign
                        + (float) frame.radialUp().x * frame.bounds().radialExtent() * upSign,
                        (float) anchor.y
                                + (float) frame.axis().y * axial
                        + (float) frame.radialRight().y * frame.bounds().radialExtent() * rightSign
                        + (float) frame.radialUp().y * frame.bounds().radialExtent() * upSign,
                        (float) anchor.z
                                + (float) frame.axis().z * axial
                        + (float) frame.radialRight().z * frame.bounds().radialExtent() * rightSign
                        + (float) frame.radialUp().z * frame.bounds().radialExtent() * upSign,
                        1.0F
                );
                viewProjection.transform(clip);
                if (clip.w <= 1.0E-4F) {
                    behindCount++;
                    continue;
                }

                float inverseW = 1.0F / clip.w;
                float ndcX = clip.x * inverseW;
                float ndcY = clip.y * inverseW;
                minX = Math.min(minX, ndcX);
                minY = Math.min(minY, ndcY);
                maxX = Math.max(maxX, ndcX);
                maxY = Math.max(maxY, ndcY);
                frontCount++;
            }
        }

        if (frontCount == 0) {
            return null;
        }
        if (behindCount > 0) {
            return ScreenBounds.FULL_SCREEN;
        }
        if (maxX < -1.0F || minX > 1.0F || maxY < -1.0F || minY > 1.0F) {
            return null;
        }

        float paddingX = PROXY_PADDING_PIXELS * 2.0F / viewportWidth;
        float paddingY = PROXY_PADDING_PIXELS * 2.0F / viewportHeight;
        return new ScreenBounds(
                clamp(minX - paddingX, -1.0F, 1.0F),
                clamp(minY - paddingY, -1.0F, 1.0F),
                clamp(maxX + paddingX, -1.0F, 1.0F),
                clamp(maxY + paddingY, -1.0F, 1.0F)
        );
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private List<ScreenBounds> materialBounds() {
        float separateArea = 0.0F;
        for (ParticleDraw draw : this.queuedDraws) {
            separateArea += draw.bounds().area();
        }
        if (this.combinedBounds.area() <= separateArea) {
            return List.of(this.combinedBounds);
        }

        List<ScreenBounds> bounds = new ArrayList<>(this.queuedDraws.size());
        for (ParticleDraw draw : this.queuedDraws) {
            bounds.add(draw.bounds());
        }
        return bounds;
    }

    private void ensureUniform() {
        if (this.uniform == null) {
            this.uniform = new MappableRingBuffer(
                    () -> LABEL + " uniforms",
                    UNIFORM_USAGE,
                    GpuParticleUniform.BUFFER_SIZE
            );
        }
    }

    record ScreenBounds(float minX, float minY, float maxX, float maxY) {
        private static final ScreenBounds FULL_SCREEN = new ScreenBounds(-1.0F, -1.0F, 1.0F, 1.0F);

        ScreenBounds union(ScreenBounds other) {
            return new ScreenBounds(
                    Math.min(this.minX, other.minX),
                    Math.min(this.minY, other.minY),
                    Math.max(this.maxX, other.maxX),
                    Math.max(this.maxY, other.maxY)
            );
        }

        float area() {
            return (maxX - minX) * (maxY - minY);
        }

        float minU() {
            return minX * 0.5F + 0.5F;
        }

        float minV() {
            return minY * 0.5F + 0.5F;
        }

        float maxU() {
            return maxX * 0.5F + 0.5F;
        }

        float maxV() {
            return maxY * 0.5F + 0.5F;
        }

        int pixelX(int width) {
            return Math.max(0, Math.min(width - 1, (int) Math.floor(minU() * width)));
        }

        int pixelY(int height) {
            return Math.max(0, Math.min(height - 1, (int) Math.floor(minV() * height)));
        }

        int pixelWidth(int width) {
            int right = Math.max(1, Math.min(width, (int) Math.ceil(maxU() * width)));
            return Math.max(1, right - pixelX(width));
        }

        int pixelHeight(int height) {
            int top = Math.max(1, Math.min(height, (int) Math.ceil(maxV() * height)));
            return Math.max(1, top - pixelY(height));
        }
    }

    private record ParticleDraw(
            ParticleDefinition definition,
            RenderPipeline pipeline,
            ParticleFrame frame,
            ScreenBounds bounds
    ) {
    }

    @Override
    public void close() {
        this.material.close();
        if (this.particleMaskTarget != null) {
            this.particleMaskTarget.destroyBuffers();
            this.particleMaskTarget = null;
        }
        if (this.uniform != null) {
            this.uniform.close();
            this.uniform = null;
        }
    }
}
