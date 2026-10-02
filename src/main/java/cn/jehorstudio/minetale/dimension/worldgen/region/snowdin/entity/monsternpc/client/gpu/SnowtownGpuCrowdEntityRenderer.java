package cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.entity.monsternpc.client.gpu;

import cn.jehorstudio.minetale.content.entity.monster_npc.MonsterNpcAppearance;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.ScissorState;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.OptionalInt;

// 在 GPU 内将人群状态物化为实体顶点字节并直接绘制
final class SnowtownGpuCrowdEntityRenderer implements AutoCloseable {
    private static final Vector4f WHITE = new Vector4f(1.0F, 1.0F, 1.0F, 1.0F);
    private static final Vector3f ZERO = new Vector3f();
    private static final Matrix4f IDENTITY = new Matrix4f();
    private static final int BYTE_ALIGNMENT_PIXELS = 27;
    private static final int INITIAL_ATLAS_WIDTH = 1024;
    private static final int MAX_ATLAS_WIDTH = 4096;
    private static final int MAX_SECTORS_PER_FRAME = SnowtownGpuCrowdStateAtlas.MAX_PAGES;
    private static final int DIRECTORY_WIDTH = 1024;
    private static final int APPEARANCE_COUNT = 28;
    private static final int VEC4_BYTES = 16;
    private static final int BAKE_UNIFORM_BYTES = (2 + APPEARANCE_COUNT) * VEC4_BYTES;
    private static final int APPEARANCE_UNIFORM_BYTES = APPEARANCE_COUNT * 5 * VEC4_BYTES;
    private static final int MACRO_PAGE_UNIFORM_BYTES =
            (2 + SnowtownGpuCrowdStateAtlas.MAX_PAGES * 2) * VEC4_BYTES;
    private static final Runnable NOOP = () -> { };

    private final SnowtownGpuCrowdStateAtlas stateAtlas;
    private TextureTarget vertexByteAtlas;
    private GpuBuffer entityVertices;
    private MappableRingBuffer bakeUniforms;
    private MappableRingBuffer macroPageUniforms;
    private DynamicTexture renderAgentDirectory;
    private SnowtownGpuCrowdAppearanceAtlas appearanceAtlas;
    private GpuBuffer appearanceAtlasUniform;
    private EntityVertexLayout allocatedLayout;
    private int atlasWidth;
    private int atlasHeight;
    private int directoryHeight;
    private long submissionTotalNanos;
    private long submissionMaxNanos;
    private long planningTotalNanos;
    private long setupTotalNanos;
    private long bakeTotalNanos;
    private long copyTotalNanos;
    private long drawTotalNanos;
    private int submissionSamples;
    private long copiedBytesLastFrame;
    private int expandedVerticesLastFrame;
    private int logicalBatchesLastFrame;
    private int directoryEntriesLastFrame;
    private int bakeDrawsLastFrame;
    private int entityDrawsLastFrame;

    SnowtownGpuCrowdEntityRenderer(SnowtownGpuCrowdStateAtlas stateAtlas) {
        this.stateAtlas = Objects.requireNonNull(stateAtlas);
    }

    void render(
            RenderLevelStageEvent.AfterEntities event,
            List<SectorDraw> sectors
    ) {
        if (sectors.isEmpty()) {
            this.copiedBytesLastFrame = 0L;
            this.expandedVerticesLastFrame = 0;
            this.logicalBatchesLastFrame = 0;
            this.directoryEntriesLastFrame = 0;
            this.bakeDrawsLastFrame = 0;
            this.entityDrawsLastFrame = 0;
            return;
        }
        if (sectors.size() > MAX_SECTORS_PER_FRAME) {
            throw new IllegalStateException("单帧实体顶点物化扇区超过 " + MAX_SECTORS_PER_FRAME);
        }

        long started = System.nanoTime();
        List<SnowtownGpuCrowdAppearanceMesh> meshes =
                SnowtownGpuCrowdRenderer.entityAppearanceMeshes();
        EntityVertexLayout layout = EntityVertexLayout.detect(
                RenderPipelines.ENTITY_CUTOUT_NO_CULL.getVertexFormat());
        ensureAtlasWidth();
        FramePlan candidatePlan;
        int maximumTextureSize = RenderSystem.getDevice().getMaxTextureSize();
        while (true) {
            candidatePlan = planFrame(sectors, meshes, layout);
            if (candidatePlan.requiredHeight() <= maximumTextureSize) {
                break;
            }
            if (!growAtlasWidth()) {
                throw new IllegalStateException(
                        "实体顶点 atlas 高度 " + candidatePlan.requiredHeight()
                                + " 超过 GPU 上限 " + maximumTextureSize);
            }
        }
        FramePlan framePlan = candidatePlan;
        boolean rotateMacroUniform = false;
        boolean rotateBakeUniform = false;
        try {
            int requiredHeight = framePlan.requiredHeight();
            int expandedVertices = framePlan.expandedVertices();
            this.logicalBatchesLastFrame = framePlan.logicalBatches();
            this.directoryEntriesLastFrame = framePlan.directoryEntries().length;
            this.bakeDrawsLastFrame = expandedVertices == 0 ? 0 : 1;
            this.entityDrawsLastFrame = expandedVertices == 0 ? 0 : 1;
            long planningNanos = System.nanoTime() - started;
            if (expandedVertices == 0) {
                this.copiedBytesLastFrame = 0L;
                this.expandedVerticesLastFrame = 0;
                this.bakeDrawsLastFrame = 0;
                this.entityDrawsLastFrame = 0;
                return;
            }

            long phaseStarted = System.nanoTime();
            ensureAppearanceAtlas(meshes);
            ensureResources(layout, requiredHeight, framePlan.directoryEntries().length);
            GpuBuffer bakeUniformBuffer = this.bakeUniforms.currentBuffer();
            rotateBakeUniform = true;
            GpuBuffer macroUniformBuffer = this.macroPageUniforms.currentBuffer();
            rotateMacroUniform = true;
            writeMacroPageUniforms(macroUniformBuffer, framePlan.sectors());
            writeBakeUniforms(bakeUniformBuffer, framePlan, layout);
            uploadRenderAgentDirectory(framePlan.directoryEntries());
            long setupNanos = System.nanoTime() - phaseStarted;

            phaseStarted = System.nanoTime();
            bakeMacro(framePlan, bakeUniformBuffer, macroUniformBuffer);
            long bakeNanos = System.nanoTime() - phaseStarted;

            phaseStarted = System.nanoTime();
            RenderSystem.getDevice().createCommandEncoder().copyTextureToBuffer(
                    Objects.requireNonNull(this.vertexByteAtlas.getColorTexture()),
                    this.entityVertices,
                    0,
                    NOOP,
                    0,
                    0,
                    0,
                    this.atlasWidth,
                    requiredHeight);
            long copyNanos = System.nanoTime() - phaseStarted;
            long copiedBytes = (long)this.atlasWidth * requiredHeight * 4L;

            phaseStarted = System.nanoTime();
            drawMacro(event, framePlan, layout);
            long drawNanos = System.nanoTime() - phaseStarted;
            this.copiedBytesLastFrame = copiedBytes;
            this.expandedVerticesLastFrame = expandedVertices;
            recordSubmission(
                    System.nanoTime() - started,
                    planningNanos,
                    setupNanos,
                    bakeNanos,
                    copyNanos,
                    drawNanos);
        } finally {
            if (rotateBakeUniform) {
                this.bakeUniforms.rotate();
            }
            if (rotateMacroUniform) {
                this.macroPageUniforms.rotate();
            }
        }
    }

    private void ensureAtlasWidth() {
        if (this.atlasWidth != 0) {
            return;
        }
        int maximum = Math.min(
                MAX_ATLAS_WIDTH,
                RenderSystem.getDevice().getMaxTextureSize());
        int initial = Math.min(INITIAL_ATLAS_WIDTH, maximum);
        this.atlasWidth = alignAtlasWidth(initial);
        if (this.atlasWidth < BYTE_ALIGNMENT_PIXELS) {
            throw new IllegalStateException("GPU 最大纹理宽度不足以物化实体顶点");
        }
    }

    private boolean growAtlasWidth() {
        int maximum = alignAtlasWidth(Math.min(
                MAX_ATLAS_WIDTH,
                RenderSystem.getDevice().getMaxTextureSize()));
        if (this.atlasWidth >= maximum) {
            return false;
        }
        int doubled = Math.multiplyExact(this.atlasWidth, 2);
        int next = alignAtlasWidth(Math.min(doubled, maximum));
        if (next <= this.atlasWidth) {
            next = maximum;
        }
        releaseAtlas();
        this.atlasWidth = next;
        return true;
    }

    private static int alignAtlasWidth(int width) {
        return width / BYTE_ALIGNMENT_PIXELS * BYTE_ALIGNMENT_PIXELS;
    }

    private FramePlan planFrame(
            List<SectorDraw> sectors,
            List<SnowtownGpuCrowdAppearanceMesh> meshes,
            EntityVertexLayout layout
    ) {
        if (meshes.size() != APPEARANCE_COUNT) {
            throw new IllegalStateException("实体顶点聚合外观数量不一致");
        }
        int verticesPerRow = Math.multiplyExact(this.atlasWidth, 4) / layout.stride();
        if (verticesPerRow * layout.stride() != this.atlasWidth * 4) {
            throw new IllegalStateException("实体顶点 atlas 行没有按 stride 对齐");
        }

        int[] appearanceInstanceCounts = new int[APPEARANCE_COUNT];
        int[] directoryStarts = new int[APPEARANCE_COUNT];
        int directoryEntries = 0;
        int logicalBatches = 0;
        for (int appearanceId = 0; appearanceId < APPEARANCE_COUNT; appearanceId++) {
            SnowtownGpuCrowdAppearanceMesh mesh = meshes.get(appearanceId);
            if (mesh.appearance().id() != appearanceId) {
                throw new IllegalStateException("外观 Mesh 未按 appearanceId 排列");
            }
            directoryStarts[appearanceId] = directoryEntries;
            for (SectorDraw sector : sectors) {
                int instanceCount = mesh.instanceCount(sector.agentCount());
                if (instanceCount == 0) {
                    continue;
                }
                appearanceInstanceCounts[appearanceId] = Math.addExact(
                        appearanceInstanceCounts[appearanceId],
                        instanceCount);
                directoryEntries = Math.addExact(directoryEntries, instanceCount);
                logicalBatches++;
            }
        }

        int[] directory = new int[directoryEntries];
        int directoryCursor = 0;
        int[] appearanceEnds = new int[APPEARANCE_COUNT];
        int expandedVertices = 0;
        for (int appearanceId = 0; appearanceId < APPEARANCE_COUNT; appearanceId++) {
            SnowtownGpuCrowdAppearanceMesh mesh = meshes.get(appearanceId);
            for (SectorDraw sector : sectors) {
                int statePage = sector.sector().statePage();
                if (statePage < 0 || statePage >= SnowtownGpuCrowdStateAtlas.MAX_PAGES) {
                    throw new IllegalStateException("可见GPU人群扇区没有有效状态页");
                }
                for (int localAgentId = appearanceId;
                     localAgentId < sector.agentCount();
                     localAgentId += APPEARANCE_COUNT) {
                    directory[directoryCursor++] = encodeDirectoryEntry(
                            statePage,
                            localAgentId);
                }
            }
            int vertexCount = Math.multiplyExact(
                    mesh.vertexCount(),
                    appearanceInstanceCounts[appearanceId]);
            expandedVertices = Math.addExact(expandedVertices, vertexCount);
            appearanceEnds[appearanceId] = expandedVertices;
        }
        if (directoryCursor != directory.length) {
            throw new IllegalStateException("实体顶点可见居民目录不完整");
        }
        int requiredHeight = expandedVertices == 0
                ? 0
                : Math.ceilDiv(expandedVertices, verticesPerRow);
        return new FramePlan(
                List.copyOf(sectors),
                appearanceEnds,
                directoryStarts,
                directory,
                requiredHeight,
                expandedVertices,
                logicalBatches,
                VertexFormat.Mode.QUADS.indexCount(expandedVertices));
    }

    private static int encodeDirectoryEntry(int statePage, int localAgentId) {
        if (localAgentId < 0 || localAgentId > 0xFFFF) {
            throw new IllegalArgumentException("GPU人群局部居民编号不能写入目录");
        }
        return statePage
                | (localAgentId >>> 8 & 0xFF) << 8
                | (localAgentId & 0xFF) << 16
                | 0xFF << 24;
    }

    private void ensureResources(
            EntityVertexLayout layout,
            int requiredHeight,
            int directoryEntryCount
    ) {
        int maximumTextureSize = RenderSystem.getDevice().getMaxTextureSize();
        if (requiredHeight > maximumTextureSize) {
            throw new IllegalStateException(
                    "实体顶点 atlas 高度 " + requiredHeight
                            + " 超过 GPU 上限 " + maximumTextureSize);
        }
        if (this.allocatedLayout != null && !this.allocatedLayout.equals(layout)) {
            releaseAtlas();
        }
        if (this.vertexByteAtlas == null || this.atlasHeight < requiredHeight) {
            releaseAtlas();
            this.atlasHeight = growHeight(requiredHeight, maximumTextureSize);
            int byteCount = Math.multiplyExact(
                    Math.multiplyExact(this.atlasWidth, this.atlasHeight),
                    4);
            this.vertexByteAtlas = new TextureTarget(
                    "Snowtown GPU crowd entity vertex bytes",
                    this.atlasWidth,
                    this.atlasHeight,
                    false);
            this.entityVertices = RenderSystem.getDevice().createBuffer(
                    () -> "Snowtown GPU crowd materialized entity vertices",
                    GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_VERTEX,
                    byteCount);
            this.allocatedLayout = layout;
        }
        if (this.bakeUniforms == null) {
            this.bakeUniforms = new MappableRingBuffer(
                    () -> "Snowtown GPU crowd entity bake uniforms",
                    GpuBuffer.USAGE_MAP_WRITE | GpuBuffer.USAGE_UNIFORM,
                    BAKE_UNIFORM_BYTES);
        }
        if (this.macroPageUniforms == null) {
            ensureMacroPageUniforms();
        }
        ensureDirectoryCapacity(directoryEntryCount);
    }

    private void ensureMacroPageUniforms() {
        if (this.macroPageUniforms == null) {
            this.macroPageUniforms = new MappableRingBuffer(
                    () -> "Snowtown GPU crowd macro page uniforms",
                    GpuBuffer.USAGE_MAP_WRITE | GpuBuffer.USAGE_UNIFORM,
                    MACRO_PAGE_UNIFORM_BYTES);
        }
    }

    private void ensureDirectoryCapacity(int entryCount) {
        int requiredHeight = Math.max(1, Math.ceilDiv(entryCount, DIRECTORY_WIDTH));
        if (requiredHeight > SnowtownGpuCrowdStateAtlas.MAX_PAGES) {
            throw new IllegalStateException("GPU人群可见居民目录超过固定宏扇区容量");
        }
        if (this.renderAgentDirectory != null && this.directoryHeight >= requiredHeight) {
            return;
        }
        releaseDirectory();
        this.directoryHeight = growHeight(
                requiredHeight,
                SnowtownGpuCrowdStateAtlas.MAX_PAGES);
        this.renderAgentDirectory = new DynamicTexture(
                "Snowtown GPU crowd visible agent directory",
                DIRECTORY_WIDTH,
                this.directoryHeight,
                true);
        this.renderAgentDirectory.setClamp(true);
        this.renderAgentDirectory.setFilter(false, false);
    }

    private static int growHeight(int requiredHeight, int maximum) {
        int height = 1;
        while (height < requiredHeight && height <= maximum / 2) {
            height *= 2;
        }
        return Math.max(requiredHeight, height);
    }

    private void ensureAppearanceAtlas(List<SnowtownGpuCrowdAppearanceMesh> meshes) {
        if (this.appearanceAtlas != null) {
            return;
        }
        if (MonsterNpcAppearance.count() != APPEARANCE_COUNT) {
            throw new IllegalStateException(
                    "实体顶点聚合 shader 需要同步更新外观数量：Java="
                            + MonsterNpcAppearance.count() + " shader=" + APPEARANCE_COUNT);
        }
        SnowtownGpuCrowdAppearanceAtlas createdAtlas =
                SnowtownGpuCrowdAppearanceAtlas.create(meshes);
        GpuBuffer createdUniform = null;
        try {
            createdUniform = createAppearanceAtlasUniform(meshes, createdAtlas);
            this.appearanceAtlas = createdAtlas;
            this.appearanceAtlasUniform = createdUniform;
        } catch (RuntimeException | LinkageError failure) {
            if (createdUniform != null) {
                createdUniform.close();
            }
            createdAtlas.close();
            throw failure;
        }
    }

    private static GpuBuffer createAppearanceAtlasUniform(
            List<SnowtownGpuCrowdAppearanceMesh> meshes,
            SnowtownGpuCrowdAppearanceAtlas atlas
    ) {
        int layoutsOffset = 0;
        int motionsOffset = layoutsOffset + APPEARANCE_COUNT * VEC4_BYTES;
        int geometryOffset = motionsOffset + APPEARANCE_COUNT * VEC4_BYTES;
        int animationOffset = geometryOffset + APPEARANCE_COUNT * VEC4_BYTES;
        int diffuseOffset = animationOffset + APPEARANCE_COUNT * VEC4_BYTES;
        ByteBuffer data = ByteBuffer.allocateDirect(APPEARANCE_UNIFORM_BYTES)
                .order(ByteOrder.nativeOrder());
        for (SnowtownGpuCrowdAppearanceMesh mesh : meshes) {
            int id = mesh.appearance().id();
            int entryOffset = id * VEC4_BYTES;
            putVec4(
                    data,
                    layoutsOffset + entryOffset,
                    id,
                    APPEARANCE_COUNT,
                    mesh.idleFrames(),
                    mesh.walkFrames());
            putVec4(
                    data,
                    motionsOffset + entryOffset,
                    mesh.appearance().walkingSpeedBlocksPerSecond(),
                    mesh.positionRange(),
                    mesh.idleSeconds(),
                    mesh.walkSeconds());
            SnowtownGpuCrowdAppearanceAtlas.Entry entry = atlas.entry(id);
            SnowtownGpuCrowdAppearanceAtlas.Region geometry = entry.geometry();
            putVec4(
                    data,
                    geometryOffset + entryOffset,
                    geometry.x(),
                    geometry.y(),
                    geometry.width(),
                    geometry.height());
            SnowtownGpuCrowdAppearanceAtlas.Region animation = entry.animation();
            putVec4(
                    data,
                    animationOffset + entryOffset,
                    animation.x(),
                    animation.y(),
                    animation.width(),
                    animation.height());
            SnowtownGpuCrowdAppearanceAtlas.Region diffuse = entry.diffuse();
            putVec4(
                    data,
                    diffuseOffset + entryOffset,
                    diffuse.normalizedX(),
                    diffuse.normalizedY(),
                    diffuse.normalizedWidth(),
                    diffuse.normalizedHeight());
        }
        data.position(APPEARANCE_UNIFORM_BYTES);
        data.flip();
        return RenderSystem.getDevice().createBuffer(
                () -> "Snowtown GPU crowd appearance atlas uniforms",
                GpuBuffer.USAGE_UNIFORM,
                data);
    }

    private void writeBakeUniforms(
            GpuBuffer uniformBuffer,
            FramePlan plan,
            EntityVertexLayout layout
    ) {
        try (GpuBuffer.MappedView mapped = RenderSystem.getDevice().createCommandEncoder()
                .mapBuffer(uniformBuffer, false, true)) {
            ByteBuffer data = mapped.data().order(ByteOrder.nativeOrder());
            putVec4(
                    data,
                    0,
                    this.atlasWidth,
                    0.0F,
                    layout.stride(),
                    layout.kind());
            putVec4(
                    data,
                    VEC4_BYTES,
                    plan.expandedVertices(),
                    0.0F,
                    0.0F,
                    0.0F);
            for (int appearanceId = 0;
                 appearanceId < APPEARANCE_COUNT;
                 appearanceId++) {
                int rangeStart = appearanceId == 0
                        ? 0
                        : plan.appearanceEnds()[appearanceId - 1];
                int rangeEnd = plan.appearanceEnds()[appearanceId];
                putVec4(
                        data,
                        (2 + appearanceId) * VEC4_BYTES,
                        rangeStart,
                        rangeEnd,
                        plan.directoryStarts()[appearanceId],
                        0.0F);
            }
            if (plan.appearanceEnds()[APPEARANCE_COUNT - 1]
                    != plan.expandedVertices()) {
                throw new IllegalStateException("实体顶点批次范围不完整");
            }
        }
    }

    private void writeMacroPageUniforms(
            GpuBuffer uniformBuffer,
            List<SectorDraw> sectors
    ) {
        try (GpuBuffer.MappedView mapped = RenderSystem.getDevice().createCommandEncoder()
                .mapBuffer(uniformBuffer, false, true)) {
            ByteBuffer data = mapped.data().order(ByteOrder.nativeOrder());
            putVec4(
                    data,
                    0,
                    SnowtownGpuCrowdUniforms.EXTENT_X,
                    SnowtownGpuCrowdUniforms.EXTENT_Z,
                    SnowtownGpuCrowdUniforms.WALK_SPEED,
                    DIRECTORY_WIDTH);
            putVec4(
                    data,
                    VEC4_BYTES,
                    SnowtownGpuCrowdStaticField.SIZE,
                    SnowtownGpuCrowdStaticField.HEIGHT_RANGE,
                    SnowtownGpuCrowdStaticField.MAX_CLEARANCE,
                    SnowtownGpuCrowdStateAtlas.PAGE_COLUMNS);
            int anchorsOffset = 2 * VEC4_BYTES;
            int paramsOffset = anchorsOffset
                    + SnowtownGpuCrowdStateAtlas.MAX_PAGES * VEC4_BYTES;
            for (int page = 0; page < SnowtownGpuCrowdStateAtlas.MAX_PAGES; page++) {
                putVec4(data, anchorsOffset + page * VEC4_BYTES,
                        0.0F, 0.0F, 0.0F, 1.0F);
                putVec4(data, paramsOffset + page * VEC4_BYTES,
                        0.0F, 1.0F, 1.0F, 0.0F);
            }
            for (SectorDraw sector : sectors) {
                SnowtownGpuCrowdSector gpuSector = sector.sector();
                SnowtownCrowdClient.FrameState frame = sector.frame();
                int page = gpuSector.statePage();
                putVec4(
                        data,
                        anchorsOffset + page * VEC4_BYTES,
                        frame.anchorX(),
                        frame.anchorY(),
                        frame.anchorZ(),
                        gpuSector.renderInterpolation());
                putVec4(
                        data,
                        paramsOffset + page * VEC4_BYTES,
                        frame.elapsedSeconds(),
                        gpuSector.stateTextureSize(),
                        gpuSector.currentStateIsA() ? 1.0F : 0.0F,
                        gpuSector.previousStateAvailable() ? 1.0F : 0.0F);
            }
        }
    }

    private void uploadRenderAgentDirectory(int[] entries) {
        DynamicTexture directory = Objects.requireNonNull(this.renderAgentDirectory);
        NativeImage image = Objects.requireNonNull(
                directory.getPixels(),
                "GPU人群可见居民目录已释放");
        MemoryUtil.memIntBuffer(
                image.getPointer(),
                Math.multiplyExact(DIRECTORY_WIDTH, this.directoryHeight))
                .put(0, entries);
        directory.upload();
    }

    private static void putVec4(
            ByteBuffer data,
            int offset,
            float x,
            float y,
            float z,
            float w
    ) {
        data.putFloat(offset, x);
        data.putFloat(offset + 4, y);
        data.putFloat(offset + 8, z);
        data.putFloat(offset + 12, w);
    }

    private void bakeMacro(
            FramePlan plan,
            GpuBuffer bakeUniformBuffer,
            GpuBuffer macroUniformBuffer
    ) {
        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                () -> "Snowtown GPU crowd materialize entity vertices",
                Objects.requireNonNull(this.vertexByteAtlas.getColorTextureView()),
                OptionalInt.empty(),
                null,
                OptionalDouble.empty())) {
            pass.setPipeline(SnowtownGpuCrowdPipelines.BAKE_ENTITY_VERTICES);
            pass.setUniform(
                    "CrowdAppearanceAtlas",
                    Objects.requireNonNull(this.appearanceAtlasUniform));
            pass.setUniform("CrowdMacroPages", macroUniformBuffer);
            pass.bindSampler(
                    "AnimationFrames",
                    Objects.requireNonNull(this.appearanceAtlas)
                            .animationTexture().getTextureView());
            pass.bindSampler(
                    "StaticGeometry",
                    this.appearanceAtlas.geometryTexture().getTextureView());
            pass.bindSampler(
                    "PositionStateA",
                    Objects.requireNonNull(this.stateAtlas.positionA().getColorTextureView()));
            pass.bindSampler(
                    "PositionStateB",
                    Objects.requireNonNull(this.stateAtlas.positionB().getColorTextureView()));
            pass.bindSampler(
                    "VelocityStateA",
                    Objects.requireNonNull(this.stateAtlas.velocityA().getColorTextureView()));
            pass.bindSampler(
                    "VelocityStateB",
                    Objects.requireNonNull(this.stateAtlas.velocityB().getColorTextureView()));
            pass.bindSampler(
                    "BehaviorStateA",
                    Objects.requireNonNull(this.stateAtlas.behaviorA().getColorTextureView()));
            pass.bindSampler(
                    "BehaviorStateB",
                    Objects.requireNonNull(this.stateAtlas.behaviorB().getColorTextureView()));
            pass.bindSampler("MacroStaticFields", this.stateAtlas.staticFieldView());
            pass.bindSampler("MacroLightFields", this.stateAtlas.lightFieldView());
            pass.bindSampler(
                    "RenderAgentDirectory",
                    Objects.requireNonNull(this.renderAgentDirectory).getTextureView());
            pass.setViewport(0, 0, this.atlasWidth, plan.requiredHeight());
            pass.setUniform("CrowdVertexBake", bakeUniformBuffer);
            pass.draw(0, 3);
        }
    }

    private void drawMacro(
            RenderLevelStageEvent.AfterEntities event,
            FramePlan plan,
            EntityVertexLayout layout
    ) {
        RenderType renderType = RenderType.entityCutoutNoCull(
                MonsterNpcAppearance.ADULT_CAMEL.textureResource(),
                false);
        renderType.setupRenderState();
        try {
            if (!EntityVertexLayout.detect(renderType.pipeline().getVertexFormat()).equals(layout)) {
                throw new IllegalStateException("实体 RenderType 的运行时顶点布局在同帧内发生变化");
            }
            RenderTarget target = Minecraft.getInstance().getMainRenderTarget();
            GpuTextureView color = RenderSystem.outputColorTextureOverride != null
                    ? RenderSystem.outputColorTextureOverride
                    : Objects.requireNonNull(target.getColorTextureView());
            GpuTextureView depth = target.useDepth
                    ? (RenderSystem.outputDepthTextureOverride != null
                    ? RenderSystem.outputDepthTextureOverride
                    : target.getDepthTextureView())
                    : null;
            var transform = RenderSystem.getDynamicUniforms().writeTransform(
                    event.getModelViewMatrix(), WHITE, ZERO, IDENTITY, 1.0F);
            RenderSystem.AutoStorageIndexBuffer sequential =
                    RenderSystem.getSequentialBuffer(VertexFormat.Mode.QUADS);
            GpuBuffer indices = sequential.getBuffer(plan.indexCount());

            try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                    () -> "Snowtown GPU crowd vanilla entity draw",
                    color,
                    OptionalInt.empty(),
                    depth,
                    OptionalDouble.empty())) {
                pass.setPipeline(renderType.pipeline());
                ScissorState scissor = RenderSystem.getScissorStateForRenderTypeDraws();
                if (scissor.enabled()) {
                    pass.enableScissor(
                            scissor.x(), scissor.y(), scissor.width(), scissor.height());
                }
                RenderSystem.bindDefaultUniforms(pass);
                pass.setUniform("DynamicTransforms", transform);
                pass.setVertexBuffer(0, this.entityVertices);
                GpuTextureView overlay = RenderSystem.getShaderTexture(1);
                GpuTextureView lightMap = RenderSystem.getShaderTexture(2);
                if (overlay != null) {
                    pass.bindSampler("Sampler1", overlay);
                }
                if (lightMap != null) {
                    pass.bindSampler("Sampler2", lightMap);
                }
                pass.bindSampler(
                        "Sampler0",
                        Objects.requireNonNull(this.appearanceAtlas)
                                .diffuseTexture().getTextureView());
                pass.setIndexBuffer(indices, sequential.type());
                pass.drawIndexed(0, 0, plan.indexCount(), 1);
            }
        } finally {
            renderType.clearRenderState();
        }
    }

    String debugLine() {
        String layout = this.allocatedLayout == null
                ? "uninitialized"
                : this.allocatedLayout.description();
        long bytesMiB = this.copiedBytesLastFrame / (1024L * 1024L);
        long usefulBytes = this.allocatedLayout == null
                ? 0L
                : (long)this.expandedVerticesLastFrame * this.allocatedLayout.stride();
        long fillPercent = this.copiedBytesLastFrame == 0L
                ? 0L
                : Math.round(usefulBytes * 100.0D / this.copiedBytesLastFrame);
        return " materialized=" + layout
                + " atlas=" + this.atlasWidth + "x" + this.atlasHeight
                + (this.appearanceAtlas == null
                ? ""
                : " resources=" + this.appearanceAtlas.dimensions())
                + " vertices=" + this.expandedVerticesLastFrame
                + " visibleDirectory=" + this.directoryEntriesLastFrame
                + "@" + DIRECTORY_WIDTH + "x" + this.directoryHeight
                + " batches=" + this.logicalBatchesLastFrame
                + " draws=" + this.bakeDrawsLastFrame + "+" + this.entityDrawsLastFrame
                + " copy=" + bytesMiB + "MiB/frame"
                + " fill=" + fillPercent + "%"
                + " cpuAvg(p/s/b/c/d)="
                + averageMicros(this.planningTotalNanos, this.submissionSamples) + "/"
                + averageMicros(this.setupTotalNanos, this.submissionSamples) + "/"
                + averageMicros(this.bakeTotalNanos, this.submissionSamples) + "/"
                + averageMicros(this.copyTotalNanos, this.submissionSamples) + "/"
                + averageMicros(this.drawTotalNanos, this.submissionSamples) + "us"
                + " submit(avg/max)="
                + averageMicros(this.submissionTotalNanos, this.submissionSamples)
                + "/" + nanosToMicros(this.submissionMaxNanos) + "us";
    }

    private void recordSubmission(
            long totalNanos,
            long planningNanos,
            long setupNanos,
            long bakeNanos,
            long copyNanos,
            long drawNanos
    ) {
        this.submissionTotalNanos += totalNanos;
        this.submissionMaxNanos = Math.max(this.submissionMaxNanos, totalNanos);
        this.planningTotalNanos += planningNanos;
        this.setupTotalNanos += setupNanos;
        this.bakeTotalNanos += bakeNanos;
        this.copyTotalNanos += copyNanos;
        this.drawTotalNanos += drawNanos;
        this.submissionSamples++;
    }

    private static long nanosToMicros(long nanos) {
        return Math.round(nanos / 1_000.0D);
    }

    private static long averageMicros(long totalNanos, int samples) {
        return samples == 0 ? 0L : nanosToMicros(totalNanos / samples);
    }

    private void releaseAtlas() {
        if (this.vertexByteAtlas != null) {
            this.vertexByteAtlas.destroyBuffers();
            this.vertexByteAtlas = null;
        }
        if (this.entityVertices != null) {
            this.entityVertices.close();
            this.entityVertices = null;
        }
        this.allocatedLayout = null;
        this.atlasHeight = 0;
    }

    private void releaseAppearanceAtlas() {
        if (this.appearanceAtlasUniform != null) {
            this.appearanceAtlasUniform.close();
            this.appearanceAtlasUniform = null;
        }
        if (this.appearanceAtlas != null) {
            this.appearanceAtlas.close();
            this.appearanceAtlas = null;
        }
    }

    private void releaseDirectory() {
        if (this.renderAgentDirectory != null) {
            this.renderAgentDirectory.close();
            this.renderAgentDirectory = null;
        }
        this.directoryHeight = 0;
    }

    @Override
    public void close() {
        releaseAtlas();
        releaseAppearanceAtlas();
        releaseDirectory();
        if (this.bakeUniforms != null) {
            this.bakeUniforms.close();
            this.bakeUniforms = null;
        }
        if (this.macroPageUniforms != null) {
            this.macroPageUniforms.close();
            this.macroPageUniforms = null;
        }
        this.atlasWidth = 0;
        this.submissionTotalNanos = 0L;
        this.submissionMaxNanos = 0L;
        this.planningTotalNanos = 0L;
        this.setupTotalNanos = 0L;
        this.bakeTotalNanos = 0L;
        this.copyTotalNanos = 0L;
        this.drawTotalNanos = 0L;
        this.submissionSamples = 0;
        this.copiedBytesLastFrame = 0L;
        this.expandedVerticesLastFrame = 0;
        this.logicalBatchesLastFrame = 0;
        this.directoryEntriesLastFrame = 0;
        this.bakeDrawsLastFrame = 0;
        this.entityDrawsLastFrame = 0;
    }

    record SectorDraw(
            SnowtownGpuCrowdSector sector,
            SnowtownCrowdClient.FrameState frame,
            int agentCount
    ) {
    }

    private record FramePlan(
            List<SectorDraw> sectors,
            int[] appearanceEnds,
            int[] directoryStarts,
            int[] directoryEntries,
            int requiredHeight,
            int expandedVertices,
            int logicalBatches,
            int indexCount
    ) {
    }

    private record EntityVertexLayout(int stride, int kind, String description) {
        private static final List<String> VANILLA_NAMES = List.of(
                "Position", "Color", "UV0", "UV1", "UV2", "Normal");
        private static final List<String> IRIS_NAMES = List.of(
                "Position", "Color", "UV0", "UV1", "UV2", "Normal",
                "iris_Entity", "mc_midTexCoord", "at_tangent");

        static EntityVertexLayout detect(VertexFormat format) {
            List<String> names = format.getElementAttributeNames();
            if (format.getVertexSize() == 36 && names.equals(VANILLA_NAMES)) {
                return new EntityVertexLayout(36, 0, "NEW_ENTITY/36");
            }
            if (format.getVertexSize() == 54 && names.equals(IRIS_NAMES)) {
                return new EntityVertexLayout(54, 1, "Iris_ENTITY/54");
            }
            throw new IllegalStateException(
                    "不支持的实体顶点布局：stride=" + format.getVertexSize()
                            + " attributes=" + names);
        }
    }
}
