package cn.jehorstudio.minetale.dimension.ebott.transition.client;

import cn.jehorstudio.minetale.MineTale;
import cn.jehorstudio.minetale.dimension.ebott.transition.mixin.client.staging.MinecraftRendererAccessor;
import cn.jehorstudio.minetale.dimension.ebott.transition.mixin.client.staging.PreparedLevelRendererAccessor;
import cn.jehorstudio.minetale.dimension.worldgen.registry.ModWorldgenKeys;
import cn.jehorstudio.minetale.dimension.ebott.transition.TargetFootprint;
import cn.jehorstudio.minetale.dimension.ebott.transition.TransitionBarrier;
import cn.jehorstudio.minetale.dimension.ebott.transition.payload.AbortTransitionPayload;
import cn.jehorstudio.minetale.dimension.ebott.transition.payload.BarrierStatePayload;
import cn.jehorstudio.minetale.dimension.ebott.transition.payload.TargetChunkStreamFinishedPayload;
import cn.jehorstudio.minetale.dimension.ebott.transition.payload.TargetPrewarmReadyPayload;
import cn.jehorstudio.minetale.dimension.ebott.transition.payload.TransitionBeginPayload;
import cn.jehorstudio.minetale.dimension.ebott.transition.payload.VisualCommitPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.LevelLoadTracker;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.CommonPlayerSpawnInfo;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

// 持有无 Screen 换维的客户端会话：准备目标世界、暂存 Chunk，并在授权后交给真实 Respawn。
public final class TransitionClient {
    private static final int MAX_TARGET_CHUNK_INSTALLS_PER_TICK = 2;
    private static final long TARGET_CHUNK_INSTALL_BUDGET_NANOS = 1_250_000L;
    public static final TransitionClient INSTANCE = new TransitionClient();

    private UUID sessionId;
    private LevelLoadTracker tracker;
    private boolean visualCommitAuthorized;
    private boolean barrierPassable;
    private int targetCenterBlockX;
    private int targetCenterBlockZ;
    private int targetPriorityBlockX;
    private int targetPriorityBlockZ;
    private int sourceCenterBlockX;
    private int sourceCenterBlockZ;
    private double sourceSeamY;
    private double targetSeamY;
    private int targetPreviewRadius;
    private double apertureCenterOffsetX;
    private double apertureCenterOffsetZ;
    private double apertureRadius;
    private BarrierRenderFacts barrierRenderFacts;
    private boolean preparedTargetDataReady;
    private final TargetChunkStagingState stagingState = new TargetChunkStagingState();
    private ClientLevel preparedTargetLevel;
    private LevelRenderer preparedTargetRenderer;
    private long preparationStartedNanos;
    private long preparedWorldBuildNanos;
    private long targetChunkInstallNanos;
    private long slowestTargetChunkInstallNanos;
    private boolean preparedTargetAdopted;
    private LevelRenderer retiredSourceRenderer;
    private RetiredRendererCleanup retiredRendererCleanup;
    private long targetPreparationNanos;
    private long respawnStartedNanos;
    private long respawnToTrackerNanos;
    private PreparedTargetSectionPrecompiler targetSectionPrecompiler;
    private final ArrayDeque<Runnable> pendingTargetChunkInstalls = new ArrayDeque<>();

    private TransitionClient() {
    }

    public void begin(TransitionBeginPayload payload) {
        releaseTargetSectionPrecompiler();
        this.sessionId = payload.sessionId();
        this.tracker = null;
        this.visualCommitAuthorized = false;
        this.barrierPassable = false;
        this.targetCenterBlockX = payload.targetCenterBlockX();
        this.targetCenterBlockZ = payload.targetCenterBlockZ();
        this.targetPriorityBlockX = payload.targetCenterBlockX();
        this.targetPriorityBlockZ = payload.targetCenterBlockZ();
        this.sourceCenterBlockX = payload.sourceCenterBlockX();
        this.sourceCenterBlockZ = payload.sourceCenterBlockZ();
        this.sourceSeamY = payload.sourceSeamY();
        this.targetSeamY = payload.targetSeamY();
        this.targetPreviewRadius = payload.targetPreviewRadius();
        this.apertureCenterOffsetX = payload.apertureCenterOffsetX();
        this.apertureCenterOffsetZ = payload.apertureCenterOffsetZ();
        this.apertureRadius = payload.apertureRadius();
        this.barrierRenderFacts = new BarrierRenderFacts(
                payload.sourceCenterBlockX() + 0.5,
                payload.sourceCenterBlockZ() + 0.5,
                payload.sourceSeamY(),
                payload.targetCenterBlockX() + 0.5,
                payload.targetCenterBlockZ() + 0.5,
                payload.targetSeamY(),
                payload.apertureCenterOffsetX(),
                payload.apertureCenterOffsetZ(),
                payload.apertureRadius()
        );
        this.preparedTargetDataReady = false;
        this.preparedTargetAdopted = false;
        this.retiredSourceRenderer = null;
        disposePreparedTarget();
        this.preparationStartedNanos = System.nanoTime();
        this.preparedWorldBuildNanos = 0L;
        this.targetChunkInstallNanos = 0L;
        this.slowestTargetChunkInstallNanos = 0L;
        this.targetPreparationNanos = 0L;
        this.respawnStartedNanos = 0L;
        this.respawnToTrackerNanos = 0L;
        this.pendingTargetChunkInstalls.clear();
        this.stagingState.begin(payload.sessionId(), TargetFootprint.create(
                payload.targetCenterBlockX(),
                payload.targetCenterBlockZ(),
                payload.targetPreviewRadius()
        ));
    }

    public boolean shouldInterceptPreparationRespawn(CommonPlayerSpawnInfo spawnInfo) {
        return this.sessionId != null
                && this.stagingState.accepting(this.sessionId)
                && this.preparedTargetLevel == null
                && ModWorldgenKeys.UNDERGROUND_LEVEL.equals(spawnInfo.dimension());
    }

    public void prepareTargetWorld(
            ClientPacketListener connection,
            CommonPlayerSpawnInfo spawnInfo,
            ClientLevel.ClientLevelData sourceLevelData,
            int serverChunkRadius,
            int serverSimulationDistance
    ) {
        if (!shouldInterceptPreparationRespawn(spawnInfo)) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        long started = System.nanoTime();
        LevelRenderer renderer = new LevelRenderer(
                minecraft,
                minecraft.getEntityRenderDispatcher(),
                minecraft.getBlockEntityRenderDispatcher(),
                minecraft.renderBuffers(),
                minecraft.gameRenderer.getLevelRenderState(),
                minecraft.gameRenderer.getFeatureRenderDispatcher()
        );
        renderer.onResourceManagerReload(minecraft.getResourceManager());
        ClientLevel.ClientLevelData targetLevelData = new ClientLevel.ClientLevelData(
                sourceLevelData.getDifficulty(),
                sourceLevelData.isHardcore(),
                spawnInfo.isFlat()
        );
        this.captureTargetPriority(minecraft);
        TargetFootprint targetFootprint = TargetFootprint.create(
                this.targetCenterBlockX,
                this.targetCenterBlockZ,
                this.targetPreviewRadius,
                this.targetPriorityBlockX,
                this.targetPriorityBlockZ
        );
        int priorityChunkX = Math.floorDiv(this.targetPriorityBlockX, 16);
        int priorityChunkZ = Math.floorDiv(this.targetPriorityBlockZ, 16);
        int preparedChunkRadius = Math.max(
                serverChunkRadius,
                targetFootprint.dataChunkRadius(priorityChunkX, priorityChunkZ)
        );
        ClientLevel level = new ClientLevel(
                connection,
                targetLevelData,
                spawnInfo.dimension(),
                spawnInfo.dimensionType(),
                preparedChunkRadius,
                serverSimulationDistance,
                renderer,
                spawnInfo.isDebug(),
                spawnInfo.seed(),
                spawnInfo.seaLevel()
        );
        renderer.setLevel(level);
        // 安装目标 Chunk 前先把 ViewArea 移到目标接缝，否则超出范围的 dirty 通知会被丢弃。
        ((PreparedLevelRendererAccessor) renderer).minetale$getViewArea().repositionCamera(
                SectionPos.of(BlockPos.containing(
                        this.targetPriorityBlockX + 0.5,
                        this.targetSeamY,
                        this.targetPriorityBlockZ + 0.5
                ))
        );
        PreparedSectionPoolRegistry.attach(renderer);
        level.getChunkSource().updateViewCenter(priorityChunkX, priorityChunkZ);
        this.preparedTargetLevel = level;
        this.preparedTargetRenderer = renderer;
        this.preparedWorldBuildNanos = System.nanoTime() - started;
    }

    public boolean interceptTargetChunk(ClientboundLevelChunkWithLightPacket packet) {
        if (this.sessionId == null || !this.stagingState.accepting(this.sessionId)
                || this.preparedTargetLevel == null
                || !this.stagingState.contains(packet.getX(), packet.getZ())) {
            return false;
        }
        this.stagingState.accept(this.sessionId, packet.getX(), packet.getZ());
        return true;
    }

    public void enqueueTargetChunkInstall(Runnable install) {
        this.pendingTargetChunkInstalls.addLast(install);
    }

    public void markTargetChunkInstalled(ClientboundLevelChunkWithLightPacket packet, long elapsedNanos) {
        if (this.sessionId == null) {
            return;
        }
        this.targetChunkInstallNanos += elapsedNanos;
        this.slowestTargetChunkInstallNanos = Math.max(this.slowestTargetChunkInstallNanos, elapsedNanos);
    }

    public void finishTargetChunkStream(TargetChunkStreamFinishedPayload payload) {
        if (!payload.sessionId().equals(this.sessionId)) {
            return;
        }
        this.stagingState.finish(payload.sessionId());
    }

    public void authorizeVisualCommit(VisualCommitPayload payload) {
        if (payload.sessionId().equals(this.sessionId)
                && this.stagingState.readyToCommit()
                && this.preparedTargetDataReady) {
            this.visualCommitAuthorized = true;
        }
    }

    public void applyBarrierState(BarrierStatePayload payload) {
        if (!payload.sessionId().equals(this.sessionId)) {
            return;
        }
        this.barrierPassable = payload.passable();
        if (!payload.passable() && Minecraft.getInstance().level != null
                && Level.OVERWORLD.equals(Minecraft.getInstance().level.dimension())) {
            this.visualCommitAuthorized = false;
        }
    }

    public ClientLevel adoptPreparedTargetWorld(ResourceKey<Level> dimension) {
        if (!this.visualCommitAuthorized || !this.preparedTargetDataReady
                || this.preparedTargetLevel == null
                || !dimension.equals(this.preparedTargetLevel.dimension())) {
            return null;
        }
        this.preparedTargetAdopted = true;
        return this.preparedTargetLevel;
    }

    public void markActualRespawnStarted() {
        if (this.visualCommitAuthorized && this.preparedTargetDataReady) {
            this.respawnStartedNanos = System.nanoTime();
            MineTale.LOGGER.info(
                    "[DEBUG-transition-flicker] respawn-start session={} sourceRenderer={}",
                    this.sessionId,
                    rendererIdentity(Minecraft.getInstance().levelRenderer));
        }
    }

    public boolean activatePreparedTargetWorld(ClientLevel level, Minecraft minecraft) {
        if (!this.preparedTargetAdopted || level != this.preparedTargetLevel
                || this.preparedTargetRenderer == null) {
            return false;
        }
        this.retiredSourceRenderer = minecraft.levelRenderer;
        ((MinecraftRendererAccessor) minecraft).minetale$setLevelRenderer(this.preparedTargetRenderer);
        if (this.targetSectionPrecompiler != null) {
            this.targetSectionPrecompiler.prepareForAdoption();
        }
        MineTale.LOGGER.info(
                "[DEBUG-transition-flicker] renderer-activated session={} sourceRenderer={} targetRenderer={} renderedSections={}",
                this.sessionId,
                rendererIdentity(this.retiredSourceRenderer),
                rendererIdentity(this.preparedTargetRenderer),
                this.preparedTargetRenderer.countRenderedSections());
        return true;
    }

    public boolean shouldKeepPreparedRenderState(LevelRenderer renderer, ClientLevel level) {
        return this.preparedTargetAdopted
                && renderer == this.preparedTargetRenderer
                && level == this.preparedTargetLevel;
    }

    public void abort(AbortTransitionPayload payload) {
        if (payload.sessionId().equals(this.sessionId)) {
            clear();
        }
    }

    // true 表示 Tracker 已接管等待流程，原版 Screen 选择尾段应被跳过。
    public boolean beginTargetLoad(
            @Nullable ResourceKey<Level> toDimension,
            @Nullable ResourceKey<Level> fromDimension,
            LevelLoadTracker tracker,
            Minecraft minecraft
    ) {
        if (!ModWorldgenKeys.UNDERGROUND_LEVEL.equals(toDimension)
                || !Level.OVERWORLD.equals(fromDimension)) {
            return false;
        }
        if (this.sessionId == null || !this.visualCommitAuthorized) {
            return false;
        }
        this.tracker = tracker;
        if (this.respawnStartedNanos != 0L) {
            this.respawnToTrackerNanos = System.nanoTime() - this.respawnStartedNanos;
        }
        if (minecraft.screen instanceof LevelLoadingScreen) {
            minecraft.setScreen(null);
        }
        MineTale.LOGGER.info(
                "[DEBUG-transition-flicker] tracker-started session={} renderer={} renderedSections={}",
                this.sessionId,
                rendererIdentity(minecraft.levelRenderer),
                minecraft.levelRenderer.countRenderedSections());
        return true;
    }

    public void tick() {
        PreparedSectionPoolRegistry.tick();
        if (this.retiredRendererCleanup != null && this.retiredRendererCleanup.tick()) {
            this.retiredRendererCleanup = null;
        }
        installTargetChunksWithinBudget();
        tickTargetSectionPrecompilerIfPossible();
        markTargetDataInstalledIfPossible();
        if (this.sessionId == null || this.tracker == null || !this.tracker.isLevelReady()) {
            return;
        }
        if (this.preparedTargetAdopted
                && this.targetSectionPrecompiler != null
                && this.targetSectionPrecompiler.hasRenderableSections()
                && this.preparedTargetRenderer != null
                && this.preparedTargetRenderer.countRenderedSections() == 0) {
            return;
        }
        clear();
    }

    private void installTargetChunksWithinBudget() {
        long deadline = System.nanoTime() + TARGET_CHUNK_INSTALL_BUDGET_NANOS;
        for (int installed = 0; installed < MAX_TARGET_CHUNK_INSTALLS_PER_TICK; installed++) {
            Runnable targetChunkInstall = this.pendingTargetChunkInstalls.pollFirst();
            if (targetChunkInstall == null) {
                return;
            }
            targetChunkInstall.run();
            if (System.nanoTime() >= deadline) {
                return;
            }
        }
    }

    private void markTargetDataInstalledIfPossible() {
        if (this.sessionId == null || this.preparedTargetDataReady
                || !this.pendingTargetChunkInstalls.isEmpty()
                || this.preparedTargetLevel == null || this.preparedTargetRenderer == null) {
            return;
        }
        if (!this.stagingState.readyToCommit()) {
            return;
        }
        this.preparedTargetDataReady = true;
        this.targetPreparationNanos = System.nanoTime() - this.preparationStartedNanos;
        ClientPacketDistributor.sendToServer(new TargetPrewarmReadyPayload(this.sessionId));
    }

    private void tickTargetSectionPrecompilerIfPossible() {
        if (this.sessionId == null
                || !this.pendingTargetChunkInstalls.isEmpty()
                || this.preparedTargetLevel == null || this.preparedTargetRenderer == null
                || !this.stagingState.readyToCommit()) {
            return;
        }
        // prepared level 不参与主渲染循环，mesh 编译前必须主动提交其光照更新。
        this.preparedTargetLevel.pollLightUpdates();
        this.preparedTargetLevel.getChunkSource().getLightEngine().runLightUpdates();
        if (this.targetSectionPrecompiler == null) {
            this.targetSectionPrecompiler = PreparedTargetSectionPrecompiler.start(
                    this.preparedTargetLevel,
                    this.preparedTargetRenderer,
                    this.targetCenterBlockX,
                    this.targetCenterBlockZ,
                    this.targetPreviewRadius,
                    this.targetPriorityBlockX,
                    (int) Math.floor(this.targetSeamY),
                    this.targetPriorityBlockZ
            );
        }
        if (this.targetSectionPrecompiler != null) {
            this.targetSectionPrecompiler.tick();
        }
    }

    public void clear() {
        UUID endingSessionId = this.sessionId;
        releaseTargetSectionPrecompiler();
        if (this.preparedTargetAdopted && this.retiredSourceRenderer != null) {
            this.retiredRendererCleanup = RetiredRendererCleanup.create(this.retiredSourceRenderer);
        }
        if (endingSessionId != null && this.respawnStartedNanos != 0L) {
            MineTale.LOGGER.info(
                    "[TransitionPerf] preparedWorldMs={}, targetChunksTotalMs={}, targetChunkMaxMs={}, targetPrewarmReadyMs={}, respawnToTrackerMs={}, respawnToReadyMs={}",
                    millis(this.preparedWorldBuildNanos),
                    millis(this.targetChunkInstallNanos),
                    millis(this.slowestTargetChunkInstallNanos),
                    millis(this.targetPreparationNanos),
                    millis(this.respawnToTrackerNanos),
                    millis(System.nanoTime() - this.respawnStartedNanos)
            );
        }
        this.sessionId = null;
        this.tracker = null;
        this.visualCommitAuthorized = false;
        this.barrierPassable = false;
        this.preparedTargetDataReady = false;
        if (!this.preparedTargetAdopted) {
            disposePreparedTarget();
        } else {
            this.preparedTargetLevel = null;
            this.preparedTargetRenderer = null;
        }
        this.preparedTargetAdopted = false;
        this.retiredSourceRenderer = null;
        this.preparationStartedNanos = 0L;
        this.preparedWorldBuildNanos = 0L;
        this.targetChunkInstallNanos = 0L;
        this.slowestTargetChunkInstallNanos = 0L;
        this.targetPreparationNanos = 0L;
        this.respawnStartedNanos = 0L;
        this.respawnToTrackerNanos = 0L;
        this.pendingTargetChunkInstalls.clear();
        this.stagingState.clear();
    }

    public void shutdown() {
        clear();
        this.barrierRenderFacts = null;
        if (this.retiredRendererCleanup != null) {
            this.retiredRendererCleanup.closeNow();
            this.retiredRendererCleanup = null;
        }
    }

    private void captureTargetPriority(Minecraft minecraft) {
        if (minecraft.player == null) {
            this.targetPriorityBlockX = this.targetCenterBlockX;
            this.targetPriorityBlockZ = this.targetCenterBlockZ;
            return;
        }
        this.targetPriorityBlockX = (int) Math.floor(
                this.targetCenterBlockX + minecraft.player.getX() - this.sourceCenterBlockX
        );
        this.targetPriorityBlockZ = (int) Math.floor(
                this.targetCenterBlockZ + minecraft.player.getZ() - this.sourceCenterBlockZ
        );
    }

    // 在 mesh 阶段裁掉目标接缝以上方块，避免依赖可能被 Iris 替换的 shader 裁剪。
    public boolean shouldClipPreparedTargetMesh(Level level, int blockY) {
        return !this.preparedTargetAdopted
                && level == this.preparedTargetLevel
                && blockY >= this.targetSeamY;
    }

    public ClientLevel preparedTargetLevel() {
        return this.preparedTargetLevel;
    }

    public LevelRenderer preparedTargetRenderer() {
        return this.preparedTargetRenderer;
    }

    // 返回预编译器持有的稳定 Section 快照，不借用 Sodium 可重建的可见列表。
    public List<SectionRenderDispatcher.RenderSection> preparedTargetSectionsForRender(
            int requiredVertexSize
    ) {
        if (this.targetSectionPrecompiler == null) {
            return List.of();
        }
        this.targetSectionPrecompiler.prepareForRender(requiredVertexSize);
        return this.targetSectionPrecompiler.sections();
    }

    // F3 分列报告各模块事实
    public String debugLine() {
        String stage;
        if (this.sessionId == null) {
            stage = "IDLE";
        } else if (this.tracker != null) {
            stage = "COMMITTING";
        } else if (this.barrierPassable) {
            stage = "PASSABLE";
        } else if (this.preparedTargetDataReady) {
            stage = "PREWARM_READY_SENT";
        } else {
            stage = "PREWARMING";
        }
        int sectionPending = this.targetSectionPrecompiler == null
                ? 0 : this.targetSectionPrecompiler.pendingCount();
        int sectionInFlight = this.targetSectionPrecompiler == null
                ? 0 : this.targetSectionPrecompiler.inFlightCount();
        return "[MineTale/Transition] stage=" + stage
                + " dataInstalled=" + this.preparedTargetDataReady
                + " barrierPassable=" + this.barrierPassable
                + " sectionPending=" + sectionPending
                + " sectionInFlight=" + sectionInFlight;
    }

    // 客户端预测复用服务端几何：源端读取会话开关，目标端始终保留阻挡。
    public VoxelShape barrierCollisionShape(AABB queryBox) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return Shapes.empty();
        }
        boolean sourceSide = Level.OVERWORLD.equals(minecraft.level.dimension());
        boolean targetSide = ModWorldgenKeys.UNDERGROUND_LEVEL.equals(minecraft.level.dimension());
        BarrierRenderFacts facts = this.barrierRenderFacts;
        if ((!sourceSide && !targetSide)
                || (sourceSide && (this.sessionId == null || this.barrierPassable))
                || facts == null) {
            return Shapes.empty();
        }
        return TransitionBarrier.collisionShape(
                sourceSide ? facts.sourceCenterX() : facts.targetCenterX(),
                sourceSide ? facts.sourceCenterZ() : facts.targetCenterZ(),
                sourceSide ? facts.sourceSeamY() : facts.targetSeamY(),
                facts.apertureCenterOffsetX(),
                facts.apertureCenterOffsetZ(),
                facts.apertureRadius(),
                queryBox
        );
    }

    // 返回当前可渲染目标的只读快照
    public RenderableDestination renderableDestination() {
        if (this.sessionId == null
                || this.preparedTargetLevel == null
                || this.preparedTargetRenderer == null
                || !this.preparedTargetDataReady
                || !this.barrierPassable) {
            return null;
        }
        return new RenderableDestination(
                this.preparedTargetLevel,
                this.targetCenterBlockX,
                this.targetCenterBlockZ,
                this.targetSeamY,
                this.sourceCenterBlockX,
                this.sourceCenterBlockZ,
                this.sourceSeamY,
                this.apertureCenterOffsetX,
                this.apertureCenterOffsetZ,
                this.apertureRadius,
                this.preparedTargetAdopted
        );
    }

    // 视觉层只读取当前侧结界快照
    public RenderableBarrier renderableBarrier() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return null;
        }
        boolean sourceSide = Level.OVERWORLD.equals(minecraft.level.dimension());
        boolean targetSide = ModWorldgenKeys.UNDERGROUND_LEVEL.equals(minecraft.level.dimension());
        BarrierRenderFacts facts = this.barrierRenderFacts;
        if ((!sourceSide && !targetSide)
                || (sourceSide && this.sessionId == null)
                || facts == null) {
            return null;
        }
        return new RenderableBarrier(
                sourceSide ? facts.sourceCenterX() : facts.targetCenterX(),
                sourceSide ? facts.sourceCenterZ() : facts.targetCenterZ(),
                sourceSide ? facts.sourceSeamY() : facts.targetSeamY(),
                facts.apertureCenterOffsetX(),
                facts.apertureCenterOffsetZ(),
                facts.apertureRadius(),
                sourceSide && this.barrierPassable,
                targetSide
        );
    }

    private void disposePreparedTarget() {
        if (this.preparedTargetRenderer != null) {
            this.preparedTargetRenderer.setLevel(null);
            this.preparedTargetRenderer.close();
        }
        this.preparedTargetLevel = null;
        this.preparedTargetRenderer = null;
    }

    private void releaseTargetSectionPrecompiler() {
        if (this.targetSectionPrecompiler == null) {
            return;
        }
        this.targetSectionPrecompiler.close(this.preparedTargetAdopted);
        this.targetSectionPrecompiler = null;
    }

    private static double millis(long nanos) {
        return Math.round(nanos / 10_000.0) / 100.0;
    }

    private static String rendererIdentity(LevelRenderer renderer) {
        return renderer == null
                ? "null"
                : Integer.toHexString(System.identityHashCode(renderer));
    }

    // prepared target 资源的生命周期始终由 TransitionClient 管理。
    public record RenderableDestination(
            ClientLevel level,
            int targetCenterX,
            int targetCenterZ,
            double targetSeamY,
            int sourceCenterX,
            int sourceCenterZ,
            double sourceSeamY,
            double apertureCenterOffsetX,
            double apertureCenterOffsetZ,
            double apertureRadius,
            boolean adopted
    ) {
        public RenderableDestination {
            Objects.requireNonNull(level, "level");
            if (!Double.isFinite(targetSeamY) || !Double.isFinite(sourceSeamY)
                    || !Double.isFinite(apertureCenterOffsetX)
                    || !Double.isFinite(apertureCenterOffsetZ)
                    || !Double.isFinite(apertureRadius)
                    || !(apertureRadius > 0.0)) {
                throw new IllegalArgumentException("renderable destination 参数无效");
            }
        }
    }

    // 单侧结界表现所需的不可变事实。
    public record RenderableBarrier(
            double blockGridCenterX,
            double blockGridCenterZ,
            double seamY,
            double apertureCenterOffsetX,
            double apertureCenterOffsetZ,
            double radius,
            boolean passable,
            boolean targetSide
    ) {
        public RenderableBarrier {
            if (!Double.isFinite(blockGridCenterX) || !Double.isFinite(blockGridCenterZ)
                    || !Double.isFinite(seamY)
                    || !Double.isFinite(apertureCenterOffsetX)
                    || !Double.isFinite(apertureCenterOffsetZ)
                    || !Double.isFinite(radius)
                    || !(radius > 0.0)) {
                throw new IllegalArgumentException("renderable barrier 参数无效");
            }
        }
    }

    // 同一次穿越的两侧共享该几何，普通客户端会话清理不会将其提前丢弃。
    private record BarrierRenderFacts(
            double sourceCenterX,
            double sourceCenterZ,
            double sourceSeamY,
            double targetCenterX,
            double targetCenterZ,
            double targetSeamY,
            double apertureCenterOffsetX,
            double apertureCenterOffsetZ,
            double apertureRadius
    ) {
        private BarrierRenderFacts {
            if (!Double.isFinite(sourceCenterX) || !Double.isFinite(sourceCenterZ)
                    || !Double.isFinite(sourceSeamY)
                    || !Double.isFinite(targetCenterX) || !Double.isFinite(targetCenterZ)
                    || !Double.isFinite(targetSeamY)
                    || !Double.isFinite(apertureCenterOffsetX)
                    || !Double.isFinite(apertureCenterOffsetZ)
                    || !Double.isFinite(apertureRadius)
                    || !(apertureRadius > 0.0)) {
                throw new IllegalArgumentException("barrier render facts 参数无效");
            }
        }
    }
}
