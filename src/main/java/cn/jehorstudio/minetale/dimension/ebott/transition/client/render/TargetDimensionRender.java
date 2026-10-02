package cn.jehorstudio.minetale.dimension.ebott.transition.client.render;

import cn.jehorstudio.minetale.MineTale;
import cn.jehorstudio.minetale.dimension.ebott.transition.client.render.destination.DestinationBarrierMask;
import cn.jehorstudio.minetale.dimension.ebott.transition.client.render.destination.DestinationPipelines;
import cn.jehorstudio.minetale.dimension.ebott.transition.client.TransitionClient;
import cn.jehorstudio.minetale.dimension.ebott.transition.mixin.client.render.TargetCameraAccessor;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.SectionBuffers;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.OptionalInt;

// 在源世界 opaque pass 中直接绘制 prepared target 持有的 Section mesh。
public final class TargetDimensionRender implements AutoCloseable {
    public static final TargetDimensionRender INSTANCE = new TargetDimensionRender();

    private static final List<ChunkSectionLayer> LAYERS = List.of(
            ChunkSectionLayer.SOLID,
            ChunkSectionLayer.CUTOUT_MIPPED,
            ChunkSectionLayer.CUTOUT
    );
    private static final Vector4f WHITE = new Vector4f(1.0F, 1.0F, 1.0F, 1.0F);
    private static final Matrix4f IDENTITY = new Matrix4f();
    private static ClientLevel activeLightLevel;

    private final Camera targetCamera = new Camera();
    private int lastSectionCount;
    private int lastDrawCount;
    private int lastVertexSize;
    private int lastFormatMismatchCount;
    private boolean successLogged;
    private boolean failureLogged;
    private LightTexture targetLightTexture;
    private FogRenderer targetFogRenderer;

    private TargetDimensionRender() {
    }

    public void render(RenderLevelStageEvent.AfterOpaqueBlocks event) {
        Minecraft minecraft = Minecraft.getInstance();
        TransitionClient transition = TransitionClient.INSTANCE;
        TransitionClient.RenderableDestination destination = transition.renderableDestination();
        if (destination == null
                || minecraft.level == null
                || (!Level.OVERWORLD.equals(minecraft.level.dimension())
                && (!destination.adopted() || minecraft.level != destination.level()
                || minecraft.levelRenderer.countRenderedSections() > 0))
                || event.getLevelRenderer() != minecraft.levelRenderer) {
            clearFrameDiagnostics();
            return;
        }

        double visibleSeamY = destination.adopted()
                ? destination.targetSeamY()
                : destination.sourceSeamY();
        if (minecraft.gameRenderer.getMainCamera().getPosition().y < visibleSeamY) {
            clearFrameDiagnostics();
            return;
        }

        try {
            drawPreparedSections(minecraft, event, transition, destination, visibleSeamY);
            if (this.lastDrawCount > 0 && !this.successLogged) {
                MineTale.LOGGER.info(
                        "目标维度地形已提交: sections={}, draws={}",
                        this.lastSectionCount, this.lastDrawCount);
                this.successLogged = true;
            }
            this.failureLogged = false;
        } catch (RuntimeException | LinkageError failure) {
            this.lastSectionCount = 0;
            this.lastDrawCount = 0;
            if (!this.failureLogged) {
                MineTale.LOGGER.error("目标维度地形绘制失败", failure);
                this.failureLogged = true;
            }
        }
    }

    public String debugLine() {
        return "[MineTale/Destination] sections=" + this.lastSectionCount
                + " draws=" + this.lastDrawCount
                + " vertexSize=" + this.lastVertexSize
                + " formatMismatch=" + this.lastFormatMismatchCount
                + (this.failureLogged ? " failed" : "");
    }

    // 仅在目标 lightmap 更新期间暴露 prepared level，避免改写全局 Minecraft.level。
    public static ClientLevel lightLevel(ClientLevel original) {
        return activeLightLevel != null ? activeLightLevel : original;
    }

    @Override
    public void close() {
        clearFrameDiagnostics();
        this.successLogged = false;
        this.failureLogged = false;
        if (this.targetLightTexture != null) {
            this.targetLightTexture.close();
            this.targetLightTexture = null;
        }
        if (this.targetFogRenderer != null) {
            this.targetFogRenderer.close();
            this.targetFogRenderer = null;
        }
        DestinationBarrierMask.INSTANCE.close();
    }

    private void drawPreparedSections(
            Minecraft minecraft,
            RenderLevelStageEvent.AfterOpaqueBlocks event,
            TransitionClient transition,
            TransitionClient.RenderableDestination destination,
            double visibleSeamY
    ) {
        int requiredVertexSize = DestinationPipelines.CLIPPED_SOLID
                .getVertexFormat()
                .getVertexSize();
        List<SectionRenderDispatcher.RenderSection> sections =
                transition.preparedTargetSectionsForRender(requiredVertexSize);
        int drawCount = 0;
        int maxSequentialIndices = 0;
        int formatMismatchCount = 0;
        for (SectionRenderDispatcher.RenderSection section : sections) {
            SectionMesh mesh = section.getSectionMesh();
            for (ChunkSectionLayer layer : LAYERS) {
                SectionBuffers buffers = mesh.getBuffers(layer);
                if (buffers == null) {
                    continue;
                }
                if (!hasExpectedVertexSize(buffers, requiredVertexSize)) {
                    formatMismatchCount++;
                    continue;
                }
                drawCount++;
                if (buffers.getIndexBuffer() == null) {
                    maxSequentialIndices = Math.max(maxSequentialIndices, buffers.getIndexCount());
                }
            }
        }

        this.lastSectionCount = sections.size();
        this.lastDrawCount = drawCount;
        this.lastVertexSize = requiredVertexSize;
        this.lastFormatMismatchCount = formatMismatchCount;
        if (drawCount == 0) {
            return;
        }

        RenderSystem.AutoStorageIndexBuffer sequential =
                RenderSystem.getSequentialBuffer(VertexFormat.Mode.QUADS);
        GpuBuffer sequentialIndices = maxSequentialIndices == 0
                ? null
                : sequential.getBuffer(maxSequentialIndices);
        Camera sourceCamera = minecraft.gameRenderer.getMainCamera();
        Vec3 cameraPosition;
        Camera fogCamera;
        if (destination.adopted() && this.targetCamera.isInitialized()) {
            cameraPosition = this.targetCamera.getPosition();
            fogCamera = this.targetCamera;
        } else {
            cameraPosition = sourceCamera.getPosition();
            configureTargetCamera(minecraft, sourceCamera, destination);
            fogCamera = this.targetCamera;
        }
        RenderTarget target = minecraft.getMainRenderTarget();
        ensureEnvironmentResources(minecraft);
        updateLightTextureForLevel(
                this.targetLightTexture,
                destination.level(),
                minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(true)
        );
        Map<SectionRenderDispatcher.RenderSection, GpuBufferSlice> transforms =
                createTransforms(event, destination, sections, cameraPosition);

        boolean foggy = destination.level().effects().isFoggyAt(
                fogCamera.getBlockPosition().getX(),
                fogCamera.getBlockPosition().getZ()
        ) || minecraft.gui.getBossOverlay().shouldCreateWorldFog();
        GpuBufferSlice sourceFog = Objects.requireNonNull(
                RenderSystem.getShaderFog(), "sourceFog");
        try {
            this.targetFogRenderer.setupFog(
                    fogCamera,
                    minecraft.options.getEffectiveRenderDistance(),
                    foggy,
                    minecraft.getDeltaTracker(),
                    minecraft.gameRenderer.getDarkenWorldAmount(
                            minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(true)),
                    destination.level()
            );
            RenderSystem.setShaderFog(this.targetFogRenderer.getBuffer(FogRenderer.FogMode.WORLD));
            GpuBufferSlice sourceFogForDraw = destination.adopted()
                    ? this.targetFogRenderer.getBuffer(FogRenderer.FogMode.NONE)
                    : sourceFog;
            double visibleBlockGridCenterX = (destination.adopted()
                    ? destination.targetCenterX()
                    : destination.sourceCenterX()) + 0.5;
            double visibleBlockGridCenterZ = (destination.adopted()
                    ? destination.targetCenterZ()
                    : destination.sourceCenterZ()) + 0.5;
            DestinationBarrierMask.INSTANCE.begin(
                    visibleSeamY,
                    visibleBlockGridCenterX,
                    visibleBlockGridCenterZ,
                    destination.apertureCenterOffsetX(),
                    destination.apertureCenterOffsetZ(),
                    destination.apertureRadius(),
                    cameraPosition
            );
            try {
                drawLayers(
                        target, sections, transforms, sequentialIndices, sequential,
                        requiredVertexSize, sourceFogForDraw);
            } finally {
                DestinationBarrierMask.INSTANCE.end();
            }
        } finally {
            RenderSystem.setShaderFog(sourceFog);
            this.targetFogRenderer.endFrame();
        }
    }

    private Map<SectionRenderDispatcher.RenderSection, GpuBufferSlice> createTransforms(
            RenderLevelStageEvent.AfterOpaqueBlocks event,
            TransitionClient.RenderableDestination destination,
            List<SectionRenderDispatcher.RenderSection> sections,
            Vec3 cameraPosition
    ) {
        Map<SectionRenderDispatcher.RenderSection, GpuBufferSlice> transforms =
                new IdentityHashMap<>();
        for (SectionRenderDispatcher.RenderSection section : sections) {
            if (!hasOpaqueBuffers(section.getSectionMesh())) {
                continue;
            }
            BlockPos targetOrigin = section.getRenderOrigin();
            float sourceRelativeX = (float) (targetOrigin.getX() - cameraPosition.x);
            float sourceRelativeY = (float) (targetOrigin.getY() - cameraPosition.y);
            float sourceRelativeZ = (float) (targetOrigin.getZ() - cameraPosition.z);
            if (!destination.adopted()) {
                sourceRelativeX += destination.sourceCenterX() - destination.targetCenterX();
                sourceRelativeY += destination.sourceSeamY() - destination.targetSeamY();
                sourceRelativeZ += destination.sourceCenterZ() - destination.targetCenterZ();
            }
            transforms.put(section, RenderSystem.getDynamicUniforms().writeTransform(
                    event.getModelViewMatrix(), WHITE,
                    new Vector3f(sourceRelativeX, sourceRelativeY, sourceRelativeZ),
                    IDENTITY, 1.0F
            ));
        }
        return transforms;
    }

    private void drawLayers(
            RenderTarget target,
            List<SectionRenderDispatcher.RenderSection> sections,
            Map<SectionRenderDispatcher.RenderSection, GpuBufferSlice> transforms,
            GpuBuffer sequentialIndices,
            RenderSystem.AutoStorageIndexBuffer sequential,
            int requiredVertexSize,
            GpuBufferSlice sourceFog
    ) {
        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                () -> "MineTale target dimension terrain",
                target.getColorTextureView(), OptionalInt.empty(),
                target.getDepthTextureView(), OptionalDouble.empty())) {
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("EbottSourceFog", sourceFog);
            pass.bindSampler("Sampler2", this.targetLightTexture.getTextureView());

            for (ChunkSectionLayer layer : LAYERS) {
                pass.setPipeline(DestinationPipelines.clippedTerrain(layer.pipeline()));
                DestinationBarrierMask.INSTANCE.bind(pass);
                pass.bindSampler("Sampler0", layer.textureView());
                for (SectionRenderDispatcher.RenderSection section : sections) {
                    SectionBuffers buffers = section.getSectionMesh().getBuffers(layer);
                    if (buffers == null || !hasExpectedVertexSize(buffers, requiredVertexSize)) {
                        continue;
                    }
                    pass.setUniform("DynamicTransforms", transforms.get(section));
                    pass.setVertexBuffer(0, buffers.getVertexBuffer());
                    if (buffers.getIndexBuffer() == null) {
                        pass.setIndexBuffer(sequentialIndices, sequential.type());
                    } else {
                        pass.setIndexBuffer(buffers.getIndexBuffer(), buffers.getIndexType());
                    }
                    pass.drawIndexed(0, 0, buffers.getIndexCount(), 1);
                }
            }
        }
    }

    private void ensureEnvironmentResources(Minecraft minecraft) {
        if (this.targetLightTexture == null) {
            this.targetLightTexture = new LightTexture(minecraft.gameRenderer, minecraft);
        }
        if (this.targetFogRenderer != null) {
            return;
        }
        var sourceFog = RenderSystem.getShaderFog();
        try {
            this.targetFogRenderer = new FogRenderer();
        } finally {
            RenderSystem.setShaderFog(sourceFog);
        }
    }

    private void configureTargetCamera(
            Minecraft minecraft,
            Camera sourceCamera,
            TransitionClient.RenderableDestination destination
    ) {
        this.targetCamera.setup(
                destination.level(),
                Objects.requireNonNull(sourceCamera.getEntity(), "sourceCameraEntity"),
                sourceCamera.isDetached(),
                minecraft.options.getCameraType().isMirrored(),
                sourceCamera.getPartialTickTime()
        );
        Vec3 source = sourceCamera.getPosition();
        Vec3 target = new Vec3(
                destination.targetCenterX() + source.x - destination.sourceCenterX(),
                destination.targetSeamY() + source.y - destination.sourceSeamY(),
                destination.targetCenterZ() + source.z - destination.sourceCenterZ()
        );
        TargetCameraAccessor accessor = (TargetCameraAccessor) this.targetCamera;
        accessor.minetale$setRotation(
                sourceCamera.getYRot(),
                sourceCamera.getXRot(),
                sourceCamera.getRoll()
        );
        accessor.minetale$setPosition(target);
    }

    private void updateLightTextureForLevel(
            LightTexture lightTexture,
            ClientLevel level,
            float partialTick
    ) {
        if (activeLightLevel != null) {
            throw new IllegalStateException("目标 lightmap 更新不允许嵌套");
        }
        activeLightLevel = Objects.requireNonNull(level, "level");
        try {
            lightTexture.tick();
            lightTexture.updateLightTexture(partialTick);
        } finally {
            activeLightLevel = null;
        }
    }

    private void clearFrameDiagnostics() {
        this.lastSectionCount = 0;
        this.lastDrawCount = 0;
        this.lastVertexSize = 0;
        this.lastFormatMismatchCount = 0;
    }

    private static boolean hasOpaqueBuffers(SectionMesh mesh) {
        for (ChunkSectionLayer layer : LAYERS) {
            if (mesh.getBuffers(layer) != null) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasExpectedVertexSize(SectionBuffers buffers, int requiredVertexSize) {
        if (buffers.getIndexBuffer() != null || buffers.getIndexCount() % 6 != 0) {
            return true;
        }
        int vertexCount = buffers.getIndexCount() / 6 * 4;
        return vertexCount == 0
                || buffers.getVertexBuffer().size() == vertexCount * requiredVertexSize;
    }
}
