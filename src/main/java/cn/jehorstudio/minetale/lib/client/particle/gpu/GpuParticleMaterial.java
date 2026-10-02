package cn.jehorstudio.minetale.lib.client.particle.gpu;

import cn.jehorstudio.minetale.MineTale;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.event.RegisterRenderPipelinesEvent;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryUtil;

// 将共享粒子 Mask 作为 Cutout 材质提交；Iris 下复用 terrain 材质身份与 PBR 分量。
final class GpuParticleMaterial implements AutoCloseable {
    private static final String LABEL = "MineTale GPU particle material";
    private static final ResourceLocation MASK_TEXTURE = ResourceLocation.fromNamespaceAndPath(
            MineTale.MODID,
            "dynamic/particle/mask"
    );
    private static final RenderPipeline FALLBACK_PIPELINE = RenderPipeline
            .builder(RenderPipelines.ENTITY_EMISSIVE_SNIPPET)
            .withLocation(ResourceLocation.fromNamespaceAndPath(
                    MineTale.MODID,
                    "pipeline/gpu_particle/material_cutout"
            ))
            .withShaderDefine("ALPHA_CUTOUT", 0.1F)
            .withShaderDefine("NO_OVERLAY")
            .withShaderDefine("NO_CARDINAL_LIGHTING")
            .withCull(false)
            // 代理位于近裁剪面；世界遮挡已经在 Mask 阶段完成
            .withDepthWrite(false)
            .build();
    private static final int VERTEX_SIZE = 64;
    private static final int VERTICES_PER_QUAD = 4;
    private static final int INDICES_PER_QUAD = 6;
    private static final int VERTEX_USAGE = GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_MAP_WRITE;
    private static final List<String> TERRAIN_ATTRIBUTES = List.of(
            "Position",
            "Color",
            "UV0",
            "UV2",
            "Normal",
            "mc_Entity",
            "mc_midTexCoord",
            "at_tangent",
            "at_midBlock"
    );
    private static final Vector4f WHITE = new Vector4f(1.0F, 1.0F, 1.0F, 1.0F);
    private static final Vector3f ZERO = new Vector3f();
    private static final Matrix4f IDENTITY = new Matrix4f();

    private final BorrowedRenderTargetTexture maskTexture = new BorrowedRenderTargetTexture();
    private final RenderType fallbackRenderType = RenderType.create(
            "minetale_gpu_particle_material_cutout",
            RenderType.SMALL_BUFFER_SIZE,
            false,
            false,
            FALLBACK_PIPELINE,
            RenderType.CompositeState.builder()
                    .setTextureState(new RenderStateShard.TextureStateShard(MASK_TEXTURE, false))
                    .createCompositeState(false)
    );
    private final GpuParticleIrisBridge iris = GpuParticleIrisBridge.load();
    private RenderPipeline terrainPipeline;
    private MappableRingBuffer terrainVertices;
    private int terrainVertexCapacity;
    private boolean maskTextureRegistered;
    private boolean unsupported;

    void registerPipeline(RegisterRenderPipelinesEvent event) {
        event.registerPipeline(FALLBACK_PIPELINE);
        if (this.iris == null) {
            return;
        }
        try {
            this.iris.copyFallbackPipeline(FALLBACK_PIPELINE);
        } catch (ReflectiveOperationException failure) {
            MineTale.LOGGER.warn("Iris GPU particle fallback pipeline assignment failed", failure);
        }
    }

    void attach(RenderTarget target) {
        this.maskTexture.attach(target);
        // Iris 按底层纹理 ID 查找 PBR holder；getTexture() 会让其跟踪刚创建或缩放后的纹理。
        this.maskTexture.getTexture();
        if (!this.maskTextureRegistered) {
            Minecraft.getInstance().getTextureManager().register(MASK_TEXTURE, this.maskTexture);
            this.maskTextureRegistered = true;
        }
    }

    void draw(List<GpuParticleRenderer.ScreenBounds> bounds, Matrix4f projectionMatrix) {
        if (prepareTerrainMaterial()) {
            drawTerrain(bounds, projectionMatrix);
        } else {
            drawFallback(bounds, projectionMatrix);
        }
    }

    private boolean prepareTerrainMaterial() {
        if (this.iris == null || this.unsupported) {
            return false;
        }
        try {
            if (!this.iris.active()) {
                return false;
            }
            if (this.terrainPipeline == null) {
                VertexFormat terrain = RenderPipelines.CUTOUT.getVertexFormat();
                if (terrain.getVertexSize() != 52
                        || !terrain.getElementAttributeNames().equals(TERRAIN_ATTRIBUTES)) {
                    return unsupported("Unsupported Iris terrain cutout layout: " + terrain, null);
                }

                // Iris terrain 的两个 GENERIC 输入声明为浮点；独立格式避免 Blaze3D 采用整数绑定。
                VertexFormatElement material = VertexFormatElement.register(
                        VertexFormatElement.findNextId(),
                        0,
                        VertexFormatElement.Type.FLOAT,
                        VertexFormatElement.Usage.GENERIC,
                        2
                );
                VertexFormatElement midpoint = VertexFormatElement.register(
                        VertexFormatElement.findNextId(),
                        0,
                        VertexFormatElement.Type.FLOAT,
                        VertexFormatElement.Usage.GENERIC,
                        3
                );
                VertexFormat format = VertexFormat.builder()
                        .add("Position", VertexFormatElement.POSITION)
                        .add("Color", VertexFormatElement.COLOR)
                        .add("UV0", VertexFormatElement.UV0)
                        .add("UV2", VertexFormatElement.UV2)
                        .add("Normal", VertexFormatElement.NORMAL)
                        .padding(1)
                        .add("mc_Entity", material)
                        .add("mc_midTexCoord", terrain.getElements().get(6))
                        .add("at_tangent", terrain.getElements().get(7))
                        .add("at_midBlock", midpoint)
                        .build();
                this.terrainPipeline = RenderPipeline.builder(RenderPipelines.TERRAIN_SNIPPET)
                        .withLocation(ResourceLocation.fromNamespaceAndPath(
                                MineTale.MODID,
                                "pipeline/gpu_particle/material_terrain_cutout"
                        ))
                        .withShaderDefine("ALPHA_CUTOUT", 0.1F)
                        .withCull(false)
                        .withDepthWrite(false)
                        .withVertexFormat(format, VertexFormat.Mode.QUADS)
                        .build();
                this.iris.assignTerrainCutout(this.terrainPipeline);
                this.iris.registerPbrLoader(BorrowedRenderTargetTexture.class);
            }
            return true;
        } catch (ReflectiveOperationException failure) {
            return unsupported("Iris GPU particle terrain material unavailable", failure);
        }
    }

    private boolean unsupported(String reason, Throwable failure) {
        this.unsupported = true;
        MineTale.LOGGER.warn("{}; using the emissive Cutout fallback", reason, failure);
        return false;
    }

    private void drawTerrain(
            List<GpuParticleRenderer.ScreenBounds> bounds,
            Matrix4f projectionMatrix
    ) {
        int quadCount = bounds.size();
        int indexCount = Math.multiplyExact(quadCount, INDICES_PER_QUAD);
        ensureTerrainVertices(quadCount);
        int materialId;
        try {
            materialId = this.iris.materialId();
        } catch (ReflectiveOperationException failure) {
            unsupported("Iris block material mapping unavailable", failure);
            drawFallback(bounds, projectionMatrix);
            return;
        }

        Matrix4f inverseProjection = new Matrix4f(projectionMatrix).invert();
        Vector3f normal = new Vector3f(0.0F, 0.0F, 1.0F);
        Vector3f tangent = new Vector3f(1.0F, 0.0F, 0.0F);

        GpuBuffer vertexBuffer = this.terrainVertices.currentBuffer();
        try (GpuBuffer.MappedView mapped = RenderSystem.getDevice().createCommandEncoder()
                .mapBuffer(vertexBuffer, false, true)) {
            long address = MemoryUtil.memAddress(mapped.data());
            for (GpuParticleRenderer.ScreenBounds bound : bounds) {
                writeTerrainQuad(address, inverseProjection, bound, normal, tangent, materialId);
                address += (long) VERTEX_SIZE * VERTICES_PER_QUAD;
            }
        }

        // setShaderTexture 触发 Iris 为动态基础纹理装载并绑定运行时 PBR 分量；必须早于 RenderPass。
        RenderSystem.setShaderTexture(0, this.maskTexture.getTextureView());
        var transformSlice = RenderSystem.getDynamicUniforms().writeTransform(
                IDENTITY,
                WHITE,
                ZERO,
                IDENTITY,
                1.0F
        );
        var indices = RenderSystem.getSequentialBuffer(VertexFormat.Mode.QUADS);
        var indexBuffer = indices.getBuffer(indexCount);
        Minecraft minecraft = Minecraft.getInstance();
        RenderTarget target = minecraft.getMainRenderTarget();
        try (var pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                () -> LABEL,
                target.getColorTextureView(),
                OptionalInt.empty(),
                target.getDepthTextureView(),
                OptionalDouble.empty()
        )) {
            pass.setPipeline(this.terrainPipeline);
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("DynamicTransforms", transformSlice);
            pass.bindSampler("Sampler0", this.maskTexture.getTextureView());
            pass.bindSampler("Sampler2", minecraft.gameRenderer.lightTexture().getTextureView());
            pass.setVertexBuffer(0, vertexBuffer);
            pass.setIndexBuffer(indexBuffer, indices.type());
            pass.drawIndexed(0, 0, indexCount, 1);
        } finally {
            this.terrainVertices.rotate();
        }
    }

    private void drawFallback(
            List<GpuParticleRenderer.ScreenBounds> bounds,
            Matrix4f projectionMatrix
    ) {
        Matrix4f inverseProjection = new Matrix4f(projectionMatrix).invert();

        BufferBuilder vertices = Tesselator.getInstance().begin(
                this.fallbackRenderType.mode(),
                this.fallbackRenderType.format()
        );
        for (GpuParticleRenderer.ScreenBounds bound : bounds) {
            putFallbackQuad(vertices, inverseProjection, bound);
        }

        Matrix4fStack modelViewStack = RenderSystem.getModelViewStack();
        modelViewStack.pushMatrix();
        try {
            modelViewStack.identity();
            this.fallbackRenderType.draw(vertices.buildOrThrow());
        } finally {
            modelViewStack.popMatrix();
        }
    }

    private void ensureTerrainVertices(int quadCount) {
        int requiredCapacity = Math.multiplyExact(
                Math.multiplyExact(quadCount, VERTICES_PER_QUAD),
                VERTEX_SIZE
        );
        if (this.terrainVertices == null || this.terrainVertexCapacity < requiredCapacity) {
            if (this.terrainVertices != null) {
                this.terrainVertices.close();
            }
            this.terrainVertices = new MappableRingBuffer(
                    () -> LABEL + " proxy vertices",
                    VERTEX_USAGE,
                    requiredCapacity
            );
            this.terrainVertexCapacity = requiredCapacity;
        }
    }

    private static void writeTerrainQuad(
            long address,
            Matrix4f inverseProjection,
            GpuParticleRenderer.ScreenBounds bounds,
            Vector3f normal,
            Vector3f tangent,
            int materialId
    ) {
        Vector3f bottomLeft = unproject(inverseProjection, bounds.minX(), bounds.minY());
        Vector3f bottomRight = unproject(inverseProjection, bounds.maxX(), bounds.minY());
        Vector3f topRight = unproject(inverseProjection, bounds.maxX(), bounds.maxY());
        Vector3f topLeft = unproject(inverseProjection, bounds.minX(), bounds.maxY());
        writeTerrainVertex(address, bottomLeft, bounds.minU(), bounds.minV(), normal, tangent, materialId);
        writeTerrainVertex(address + VERTEX_SIZE, bottomRight, bounds.maxU(), bounds.minV(),
                normal, tangent, materialId);
        writeTerrainVertex(address + VERTEX_SIZE * 2L, topRight, bounds.maxU(), bounds.maxV(),
                normal, tangent, materialId);
        writeTerrainVertex(address + VERTEX_SIZE * 3L, topLeft, bounds.minU(), bounds.maxV(),
                normal, tangent, materialId);
    }

    private static void writeTerrainVertex(
            long address,
            Vector3f position,
            float u,
            float v,
            Vector3f normal,
            Vector3f tangent,
            int materialId
    ) {
        MemoryUtil.memSet(address, 0, VERTEX_SIZE);
        MemoryUtil.memPutFloat(address, position.x);
        MemoryUtil.memPutFloat(address + 4, position.y);
        MemoryUtil.memPutFloat(address + 8, position.z);
        MemoryUtil.memPutInt(address + 12, -1);
        MemoryUtil.memPutFloat(address + 16, u);
        MemoryUtil.memPutFloat(address + 20, v);
        MemoryUtil.memPutShort(address + 24, (short) 240);
        MemoryUtil.memPutShort(address + 26, (short) 240);
        putNormalizedByte3(address + 28, normal);
        MemoryUtil.memPutFloat(address + 32, materialId);
        MemoryUtil.memPutFloat(address + 36, -1.0F);
        MemoryUtil.memPutFloat(address + 40, 0.5F);
        MemoryUtil.memPutFloat(address + 44, 0.5F);
        putNormalizedByte3(address + 48, tangent);
        MemoryUtil.memPutByte(address + 51, (byte) 127);
    }

    private static void putNormalizedByte3(long address, Vector3f value) {
        MemoryUtil.memPutByte(address, (byte) Math.round(value.x * 127.0F));
        MemoryUtil.memPutByte(address + 1, (byte) Math.round(value.y * 127.0F));
        MemoryUtil.memPutByte(address + 2, (byte) Math.round(value.z * 127.0F));
    }

    private static Vector3f unproject(Matrix4f inverseProjection, float x, float y) {
        Vector4f position = new Vector4f(x, y, -0.99F, 1.0F);
        inverseProjection.transform(position);
        position.div(position.w);
        return new Vector3f(position.x, position.y, position.z);
    }

    private static void putFallbackQuad(
            BufferBuilder vertices,
            Matrix4f inverseProjection,
            GpuParticleRenderer.ScreenBounds bounds
    ) {
        putFallbackVertex(vertices, unproject(inverseProjection, bounds.minX(), bounds.minY()),
                bounds.minU(), bounds.minV());
        putFallbackVertex(vertices, unproject(inverseProjection, bounds.maxX(), bounds.minY()),
                bounds.maxU(), bounds.minV());
        putFallbackVertex(vertices, unproject(inverseProjection, bounds.maxX(), bounds.maxY()),
                bounds.maxU(), bounds.maxV());
        putFallbackVertex(vertices, unproject(inverseProjection, bounds.minX(), bounds.maxY()),
                bounds.minU(), bounds.maxV());
    }

    private static void putFallbackVertex(BufferBuilder vertices, Vector3f position, float u, float v) {
        vertices.addVertex(position.x, position.y, position.z)
                .setColor(255, 255, 255, 255)
                .setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(LightTexture.FULL_BRIGHT)
                .setNormal(0.0F, 0.0F, 1.0F);
    }

    @Override
    public void close() {
        if (this.maskTextureRegistered) {
            Minecraft.getInstance().getTextureManager().release(MASK_TEXTURE);
            this.maskTextureRegistered = false;
        }
        if (this.terrainVertices != null) {
            this.terrainVertices.close();
            this.terrainVertices = null;
            this.terrainVertexCapacity = 0;
        }
    }

    private static final class BorrowedRenderTargetTexture extends AbstractTexture {
        void attach(RenderTarget target) {
            this.texture = java.util.Objects.requireNonNull(
                    target.getColorTexture(),
                    "粒子 Mask 颜色纹理未创建"
            );
            this.textureView = java.util.Objects.requireNonNull(
                    target.getColorTextureView(),
                    "粒子 Mask 颜色纹理视图未创建"
            );
        }

        @Override
        public void close() {
            this.texture = null;
            this.textureView = null;
        }
    }

}
