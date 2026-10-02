package cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.entity.monsternpc.client.gpu;

import cn.jehorstudio.minetale.MineTale;
import cn.jehorstudio.minetale.content.entity.monster_npc.MonsterNpcAppearance;
import cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.entity.monsternpc.network.SnowtownCrowdInteractPayload;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import org.lwjgl.system.MemoryUtil;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

// 拥有跨扇区 GPU 调度与共享外观资源
final class SnowtownGpuCrowdRenderer implements AutoCloseable {
    static final SnowtownGpuCrowdRenderer INSTANCE = new SnowtownGpuCrowdRenderer();

    private static final float MAX_FOOTPRINT_HALF_EXTENT = 4.0F;
    private static final int MAX_PICK_CANDIDATES = 16;
    private static final int MAX_HOVER_AGE_TICKS = 10;
    private static final double CLIENT_INTERACTION_MARGIN = 0.75D;

    private static List<SnowtownGpuCrowdAppearanceMesh> appearanceMeshes = List.of();
    private static OccupancyMesh occupancyMesh;
    private static DynamicTexture appearanceDataTexture;

    private final Map<SnowtownCrowdClient.SectorKey, SnowtownGpuCrowdSector> sectors;
    private SnowtownGpuCrowdStateAtlas stateAtlas;
    private TextureTarget interactionPickTarget;
    private boolean interactionReadbackPending;
    private boolean interactionCloseRequested;
    private boolean interactionFailureLogged;
    private long lastInteractionPickTick = Long.MIN_VALUE;
    private HoveredAgent hoveredAgent;
    private SnowtownGpuCrowdEntityRenderer entityRenderer;
    private boolean entityRenderFailureLogged;
    private int sectorAdmissionsLastFrame;
    private int sectorEvictionsLastFrame;
    private int simulationPassesLastFrame;
    private SnowtownGpuCrowdRenderer() {
        this.sectors = new LinkedHashMap<>();
    }

    // 模拟状态不因后端改变，最终顶点按当前原版或 Iris 实体管线物化。
    public void renderResidents(
            RenderLevelStageEvent.AfterEntities event,
            List<SnowtownCrowdClient.FrameState> frames
    ) {
        Minecraft minecraft = Minecraft.getInstance();
        if (this.interactionCloseRequested) {
            return;
        }
        if (frames == null || minecraft.level == null
                || event.getLevelRenderer() != minecraft.levelRenderer) {
            close();
            return;
        }
        ensureStateAtlas();
        this.sectorAdmissionsLastFrame = 0;
        this.simulationPassesLastFrame = 0;
        Set<SnowtownCrowdClient.SectorKey> retained = new HashSet<>();
        for (SnowtownCrowdClient.FrameState frame : frames) {
            retained.add(frame.sectorKey());
        }
        int sectorsBeforeRetention = this.sectors.size();
        this.sectors.entrySet().removeIf(entry -> {
            if (retained.contains(entry.getKey())) {
                return false;
            }
            entry.getValue().close();
            return true;
        });
        this.sectorEvictionsLastFrame = sectorsBeforeRetention - this.sectors.size();

        List<SnowtownGpuCrowdEntityRenderer.SectorDraw> draws = new ArrayList<>();
        for (SnowtownCrowdClient.FrameState frame : frames) {
            SnowtownGpuCrowdSector sector = this.sectors.get(frame.sectorKey());
            if (sector == null) {
                sector = new SnowtownGpuCrowdSector(
                        SnowtownGpuCrowdSector.stateTextureSize(frame.targetAgentCount()),
                        this.stateAtlas);
                this.sectors.put(frame.sectorKey(), sector);
                this.sectorAdmissionsLastFrame++;
            }
            sector.simulate(frame);
            this.simulationPassesLastFrame += sector.lastSimulationPasses();
            if (!sector.ready() || !frame.draw()) {
                continue;
            }
            draws.add(new SnowtownGpuCrowdEntityRenderer.SectorDraw(
                    sector,
                    frame,
                    Math.min(frame.visibleAgentCount(), sector.initializedAgentCount())));
        }
        try {
            if (!this.entityRenderFailureLogged && !draws.isEmpty()) {
                if (this.entityRenderer == null) {
                    this.entityRenderer = new SnowtownGpuCrowdEntityRenderer(this.stateAtlas);
                }
                this.entityRenderer.render(event, draws);
            }
        } catch (RuntimeException | LinkageError failure) {
            if (!this.entityRenderFailureLogged) {
                MineTale.LOGGER.error(
                        "Snowtown GPU 人群实体顶点物化绘制失败；本次运行已锁存",
                        failure);
                this.entityRenderFailureLogged = true;
            }
            if (this.entityRenderer != null) {
                this.entityRenderer.close();
                this.entityRenderer = null;
            }
        }
        updateInteractionPick(event, frames);
    }

    private void updateInteractionPick(
            RenderLevelStageEvent.AfterEntities event,
            List<SnowtownCrowdClient.FrameState> frames
    ) {
        Minecraft minecraft = Minecraft.getInstance();
        if (this.interactionCloseRequested || this.interactionReadbackPending) {
            return;
        }
        if (minecraft.player == null
                || minecraft.level == null
                || minecraft.screen != null
                || minecraft.player.isSpectator()
                || minecraft.hitResult instanceof EntityHitResult) {
            this.hoveredAgent = null;
            return;
        }
        long gameTime = minecraft.level.getGameTime();
        if (gameTime == this.lastInteractionPickTick) {
            return;
        }
        this.lastInteractionPickTick = gameTime;

        Vec3 cameraPosition = minecraft.gameRenderer.getMainCamera().getPosition();
        Vec3 cameraLook = new Vec3(
                minecraft.gameRenderer.getMainCamera().getLookVector()).normalize();
        double reach = minecraft.player.entityInteractionRange() + CLIENT_INTERACTION_MARGIN;
        List<PickCandidate> candidates = new ArrayList<>();
        for (SnowtownCrowdClient.FrameState frame : frames) {
            SnowtownGpuCrowdSector sector = this.sectors.get(frame.sectorKey());
            if (!frame.draw()
                    || frame.visibleAgentCount() <= 0
                    || sector == null
                    || !sector.ready()
                    || !frame.staticField().mayIntersectInteractionRay(
                            cameraPosition,
                            cameraLook,
                            reach)) {
                continue;
            }
            int agentCount = Math.min(
                    frame.visibleAgentCount(),
                    sector.initializedAgentCount()
            );
            if (agentCount > 0) {
                candidates.add(new PickCandidate(0, frame, sector, agentCount));
            }
        }
        candidates.sort(Comparator.comparingDouble(candidate ->
                candidate.frame().staticField().worldAnchor().distanceToSqr(cameraPosition)));
        if (candidates.size() > MAX_PICK_CANDIDATES) {
            candidates = new ArrayList<>(candidates.subList(0, MAX_PICK_CANDIDATES));
        }
        for (int index = 0; index < candidates.size(); index++) {
            PickCandidate candidate = candidates.get(index);
            candidates.set(index, new PickCandidate(
                    index + 1,
                    candidate.frame(),
                    candidate.sector(),
                    candidate.agentCount()
            ));
        }
        if (candidates.isEmpty()) {
            this.hoveredAgent = null;
            return;
        }

        try {
            submitInteractionPick(
                    event,
                    List.copyOf(candidates),
                    (float)reach,
                    new PickSample(minecraft.level)
            );
            this.interactionFailureLogged = false;
        } catch (RuntimeException | LinkageError failure) {
            this.hoveredAgent = null;
            if (!this.interactionFailureLogged) {
                MineTale.LOGGER.warn("Snowtown GPU 居民拾取暂时不可用", failure);
                this.interactionFailureLogged = true;
            }
        }
    }

    private void submitInteractionPick(
            RenderLevelStageEvent.AfterEntities event,
            List<PickCandidate> candidates,
            float interactionReach,
            PickSample sample
    ) {
        Minecraft minecraft = Minecraft.getInstance();
        RenderTarget mainTarget = minecraft.getMainRenderTarget();
        if (mainTarget.getDepthTexture() == null) {
            this.hoveredAgent = null;
            return;
        }
        if (this.interactionPickTarget == null) {
            this.interactionPickTarget = new TextureTarget(
                    "Snowtown GPU crowd interaction pick",
                    1,
                    1,
                    true
            );
        }
        if (this.interactionPickTarget.getColorTexture() == null
                || this.interactionPickTarget.getDepthTexture() == null) {
            throw new IllegalStateException("Snowtown GPU 拾取目标未完整创建");
        }

        var encoder = RenderSystem.getDevice().createCommandEncoder();
        encoder.clearColorTexture(this.interactionPickTarget.getColorTexture(), 0);
        encoder.copyTextureToTexture(
                mainTarget.getDepthTexture(),
                this.interactionPickTarget.getDepthTexture(),
                0,
                0,
                0,
                mainTarget.width / 2,
                mainTarget.height / 2,
                1,
                1
        );
        for (PickCandidate candidate : candidates) {
            candidate.sector().drawInteractionPick(
                    this.interactionPickTarget,
                    event,
                    candidate.frame(),
                    candidate.agentCount(),
                    candidate.token(),
                    interactionReach
            );
        }

        GpuBuffer readback = RenderSystem.getDevice().createBuffer(
                () -> "Snowtown GPU crowd pick readback",
                GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST,
                Integer.BYTES
        );
        this.interactionReadbackPending = true;
        try {
            encoder.copyTextureToBuffer(
                    this.interactionPickTarget.getColorTexture(),
                    readback,
                    0,
                    () -> readInteractionPick(readback, candidates, sample),
                    0,
                    0,
                    0,
                    1,
                    1
            );
        } catch (RuntimeException | LinkageError failure) {
            readback.close();
            this.interactionReadbackPending = false;
            throw failure;
        }
    }

    private void readInteractionPick(
            GpuBuffer readback,
            List<PickCandidate> candidates,
            PickSample sample
    ) {
        try (readback;
             GpuBuffer.MappedView mapped = RenderSystem.getDevice().createCommandEncoder()
                     .mapBuffer(readback, true, false)) {
            int packedIdentity = unsignedByte(mapped.data().get(0))
                    | unsignedByte(mapped.data().get(1)) << 8;
            int encodedAgent = packedIdentity & 0xFFF;
            int candidateIndex = packedIdentity >>> 12;
            if (encodedAgent == 0 || candidateIndex >= candidates.size()) {
                this.hoveredAgent = null;
                return;
            }
            PickCandidate candidate = candidates.get(candidateIndex);
            int agentId = encodedAgent - 1;
            if (candidate.token() != candidateIndex + 1 || agentId >= candidate.agentCount()) {
                this.hoveredAgent = null;
                return;
            }
            // 从 256 个格心解码，避免端值落到 +64 后被服务端判入相邻扇区。
            double localX = (unsignedByte(mapped.data().get(2)) + 0.5D) / 256.0D
                    * SnowtownGpuCrowdStaticField.SIZE
                    - SnowtownGpuCrowdStaticField.HALF_SIZE;
            double localZ = (unsignedByte(mapped.data().get(3)) + 0.5D) / 256.0D
                    * SnowtownGpuCrowdStaticField.SIZE
                    - SnowtownGpuCrowdStaticField.HALF_SIZE;
            SnowtownGpuCrowdStaticField field = candidate.frame().staticField();
            Vec3 anchor = field.worldAnchor();
            SnowtownCrowdClient.SectorKey key = candidate.frame().sectorKey();
            SnowtownCrowdInteractPayload payload = new SnowtownCrowdInteractPayload(
                    key.areaX(),
                    key.areaZ(),
                    key.componentIndex(),
                    key.sectorX(),
                    key.sectorZ(),
                    key.surfaceId(),
                    agentId,
                    anchor.x + localX,
                    field.renderedSurfaceHeight(localX, localZ),
                    anchor.z + localZ
            );
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.level != sample.level()) {
                this.hoveredAgent = null;
                return;
            }
            this.hoveredAgent = new HoveredAgent(
                    sample.level(),
                    minecraft.level.getGameTime(),
                    payload
            );
            this.interactionFailureLogged = false;
        } catch (RuntimeException | LinkageError failure) {
            this.hoveredAgent = null;
            if (!this.interactionFailureLogged) {
                MineTale.LOGGER.warn("Snowtown GPU 居民拾取结果回读失败", failure);
                this.interactionFailureLogged = true;
            }
        } finally {
            finishInteractionReadback();
        }
    }

    boolean tryInteractHovered() {
        Minecraft minecraft = Minecraft.getInstance();
        HoveredAgent hovered = this.hoveredAgent;
        if (hovered == null
                || minecraft.player == null
                || minecraft.level == null
                || minecraft.screen != null
                || minecraft.player.isSpectator()
                || minecraft.level != hovered.level()
                || minecraft.hitResult instanceof EntityHitResult) {
            return false;
        }
        long gameTime = minecraft.level.getGameTime();
        long age = gameTime - hovered.completedGameTime();
        if (age < 0L || age > MAX_HOVER_AGE_TICKS) {
            this.hoveredAgent = null;
            return false;
        }

        ClientPacketDistributor.sendToServer(hovered.payload());
        this.hoveredAgent = null;
        return true;
    }

    private void finishInteractionReadback() {
        this.interactionReadbackPending = false;
        if (this.interactionCloseRequested) {
            this.interactionCloseRequested = false;
            close();
        }
    }

    private void releaseInteractionResources() {
        this.hoveredAgent = null;
        this.lastInteractionPickTick = Long.MIN_VALUE;
        this.interactionFailureLogged = false;
        if (this.interactionPickTarget != null) {
            this.interactionPickTarget.destroyBuffers();
            this.interactionPickTarget = null;
        }
    }

    private static int unsignedByte(byte value) {
        return value & 0xFF;
    }

    public String debugLine() {
        int simulated = 0;
        int visible = 0;
        int walkable = 0;
        for (SnowtownGpuCrowdSector sector : this.sectors.values()) {
            simulated += sector.lastAgentCount();
            visible += sector.lastVisibleAgentCount();
            walkable += sector.lastWalkableCount();
        }
        return "[MineTale/GPU Crowd] sectors=" + this.sectors.size()
                + " churn=+" + this.sectorAdmissionsLastFrame
                + "/-" + this.sectorEvictionsLastFrame
                + " simPasses=" + this.simulationPassesLastFrame
                + " agents=" + visible + "/" + simulated
                + (this.stateAtlas == null
                ? " pages=0/" + SnowtownGpuCrowdStateAtlas.MAX_PAGES
                : " pages=" + this.stateAtlas.occupiedPageCount() + "/"
                + SnowtownGpuCrowdStateAtlas.MAX_PAGES
                + " pageAtlas=" + this.stateAtlas.dimensions())
                + " walkable=" + walkable
                + " field=" + SnowtownGpuCrowdStaticField.SIZE + "x"
                + SnowtownGpuCrowdStaticField.SIZE
                + " flow=cycle+tributaries"
                + " spawnPoints/sector=" + SnowtownGpuCrowdStaticField.SPAWN_POINT_COUNT
                + (this.entityRenderer == null
                ? (this.entityRenderFailureLogged ? " materialized=failed" : "")
                : this.entityRenderer.debugLine());
    }

    @Override
    public void close() {
        if (this.interactionReadbackPending) {
            this.interactionCloseRequested = true;
            this.hoveredAgent = null;
            return;
        }
        this.sectors.values().forEach(SnowtownGpuCrowdSector::close);
        this.sectors.clear();
        if (this.entityRenderer != null) {
            this.entityRenderer.close();
            this.entityRenderer = null;
        }
        if (this.stateAtlas != null) {
            this.stateAtlas.close();
            this.stateAtlas = null;
        }
        this.entityRenderFailureLogged = false;
        this.sectorAdmissionsLastFrame = 0;
        this.sectorEvictionsLastFrame = 0;
        this.simulationPassesLastFrame = 0;
        releaseInteractionResources();
        releaseSharedResources();
    }

    private void ensureStateAtlas() {
        if (this.stateAtlas == null) {
            this.stateAtlas = new SnowtownGpuCrowdStateAtlas();
        }
    }

    static void ensureSimulationSharedResources() {
        if (appearanceDataTexture == null) {
            appearanceDataTexture = createAppearanceDataTexture(
                    SnowtownGpuCrowdAppearanceMesh.measureFootprints()
            );
        }
        if (occupancyMesh == null) {
            occupancyMesh = OccupancyMesh.create();
        }
    }

    private static void ensureAppearanceMeshes() {
        ensureSimulationSharedResources();
        if (appearanceMeshes.isEmpty()) {
            appearanceMeshes = SnowtownGpuCrowdAppearanceMesh.createAll();
        }
    }

    static List<SnowtownGpuCrowdAppearanceMesh> entityAppearanceMeshes() {
        ensureAppearanceMeshes();
        return appearanceMeshes;
    }

    static OccupancyMesh occupancyMesh() {
        ensureSimulationSharedResources();
        return occupancyMesh;
    }

    static DynamicTexture appearanceDataTexture() {
        ensureSimulationSharedResources();
        return appearanceDataTexture;
    }

    private static void releaseSharedResources() {
        appearanceMeshes.forEach(SnowtownGpuCrowdAppearanceMesh::close);
        appearanceMeshes = List.of();
        if (appearanceDataTexture != null) {
            appearanceDataTexture.close();
            appearanceDataTexture = null;
        }
        if (occupancyMesh != null) {
            occupancyMesh.close();
            occupancyMesh = null;
        }
    }

    private static DynamicTexture createStaticTexture(String label, int[] pixels, int width, int height) {
        if (pixels.length != Math.multiplyExact(width, height)) {
            throw new IllegalArgumentException(label + " 的像素数量与纹理尺寸不一致");
        }
        NativeImage image = new NativeImage(width, height, false);
        DynamicTexture texture = null;
        try {
            // 静态纹理整批写入，避免逐像素 JNI 检查放大换页开销。
            MemoryUtil.memIntBuffer(image.getPointer(), pixels.length).put(pixels);
            texture = new DynamicTexture(() -> label, image);
            texture.setClamp(true);
            texture.setFilter(false, false);
            return texture;
        } catch (RuntimeException | LinkageError failure) {
            if (texture != null) {
                texture.close();
            } else {
                image.close();
            }
            throw failure;
        }
    }

    private static DynamicTexture createAppearanceDataTexture(
            List<SnowtownGpuCrowdAppearanceMesh.Footprint> footprints
    ) {
        int appearanceCount = MonsterNpcAppearance.count();
        int[] pixels = new int[appearanceCount];
        float maximumSpeed = SnowtownGpuCrowdUniforms.WALK_SPEED * 1.25F;
        for (SnowtownGpuCrowdAppearanceMesh.Footprint footprint : footprints) {
            if (!Float.isFinite(footprint.halfWidth())
                    || !Float.isFinite(footprint.halfLength())
                    || footprint.halfWidth() > MAX_FOOTPRINT_HALF_EXTENT
                    || footprint.halfLength() > MAX_FOOTPRINT_HALF_EXTENT) {
                throw new IllegalStateException(
                        "GPU 人群外观占地超过纹理编码范围："
                                + footprint.appearance().name()
                                + " halfWidth=" + footprint.halfWidth()
                                + " halfLength=" + footprint.halfLength());
            }
            MonsterNpcAppearance appearance = footprint.appearance();
            int halfWidth = encodeUnsigned8(
                    footprint.halfWidth() / MAX_FOOTPRINT_HALF_EXTENT);
            int halfLength = encodeUnsigned8(
                    footprint.halfLength() / MAX_FOOTPRINT_HALF_EXTENT);
            int speed = encodeUnsigned16(appearance.walkingSpeedBlocksPerSecond() / maximumSpeed);
            pixels[appearance.id()] = halfWidth
                    | halfLength << 8
                    | highByte(speed) << 16
                    | lowByte(speed) << 24;
        }
        return createStaticTexture("Snowtown GPU crowd appearance data", pixels, appearanceCount, 1);
    }

    private static int encodeUnsigned8(float normalized) {
        return Math.clamp(Math.round(normalized * 255.0F), 0, 255);
    }

    private static int encodeUnsigned16(float normalized) {
        return Math.clamp(Math.round(normalized * 65535.0F), 0, 65535);
    }

    private static int highByte(int value) {
        return value >>> 8;
    }

    private static int lowByte(int value) {
        return value & 255;
    }

    // 所有扇区的 occupancy pass 共享同一单位四边形。
    static final class OccupancyMesh implements AutoCloseable {
        private static final int VERTEX_COUNT = 4;

        private final GpuBuffer vertices;
        private final int indexCount;

        private OccupancyMesh(GpuBuffer vertices, int indexCount) {
            this.vertices = vertices;
            this.indexCount = indexCount;
        }

        private static OccupancyMesh create() {
            int capacity = VERTEX_COUNT * DefaultVertexFormat.POSITION.getVertexSize();
            try (ByteBufferBuilder bytes = ByteBufferBuilder.exactlySized(capacity)) {
                BufferBuilder builder = new BufferBuilder(
                        bytes,
                        VertexFormat.Mode.QUADS,
                        DefaultVertexFormat.POSITION
                );
                builder.addVertex(0.0F, 0.0F, 0.0F);
                builder.addVertex(0.0F, 1.0F, 0.0F);
                builder.addVertex(1.0F, 1.0F, 0.0F);
                builder.addVertex(1.0F, 0.0F, 0.0F);
                try (MeshData mesh = builder.buildOrThrow()) {
                    GpuBuffer vertices = RenderSystem.getDevice().createBuffer(
                            () -> "Snowtown GPU crowd occupancy quad",
                            GpuBuffer.USAGE_VERTEX,
                            mesh.vertexBuffer()
                    );
                    return new OccupancyMesh(vertices, mesh.drawState().indexCount());
                }
            }
        }

        GpuBuffer vertices() {
            return this.vertices;
        }

        int indexCount() {
            return this.indexCount;
        }

        @Override
        public void close() {
            this.vertices.close();
        }
    }

    private record PickCandidate(
            int token,
            SnowtownCrowdClient.FrameState frame,
            SnowtownGpuCrowdSector sector,
            int agentCount
    ) {
    }

    private record PickSample(ClientLevel level) {
    }

    private record HoveredAgent(
            ClientLevel level,
            long completedGameTime,
            SnowtownCrowdInteractPayload payload
    ) {
    }
}
