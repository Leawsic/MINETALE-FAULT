package cn.jehorstudio.minetale.battle.presentation.screen.render.environment;

import cn.jehorstudio.minetale.MineTale;
import cn.jehorstudio.minetale.battle.presentation.screen.BattleScreen;
import cn.jehorstudio.minetale.battle.presentation.screen.render.environment.EnvironmentCaptureGpu.CaptureFace;
import cn.jehorstudio.minetale.battle.presentation.screen.render.environment.EnvironmentCaptureGpu.DebugMode;
import com.mojang.blaze3d.pipeline.RenderTarget;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ViewportEvent;

// 物理客户端唯一的环境捕获会话所有者
public final class EnvironmentCaptureService implements ResourceManagerReloadListener {
    public static final EnvironmentCaptureService INSTANCE = new EnvironmentCaptureService();
    private static final int CAPTURE_TIMEOUT_TICKS = 20 * 20;

    private Session session;
    private long captureRevisionSequence;
    private long fieldRevisionSequence;

    private EnvironmentCaptureService() {
    }

    // 初始化失败退化为黑背景，不会阻断 Battle 激活。
    public UUID request(Minecraft minecraft, UUID battleId) {
        Objects.requireNonNull(minecraft, "minecraft");
        Objects.requireNonNull(battleId, "battleId");
        if (this.session != null) {
            this.session.close(minecraft, "被新的 Battle 环境捕获替换");
        }
        Session created = new Session(UUID.randomUUID(), battleId, DebugMode.configured());
        this.session = created;
        created.begin(minecraft);
        return created.captureId;
    }

    public EnvironmentCaptureSnapshot snapshot(UUID battleId, UUID captureId) {
        Session current = this.session;
        if (current == null || captureId == null
                || !current.battleId.equals(battleId)
                || !current.captureId.equals(captureId)) {
            return EnvironmentCaptureSnapshot.idle();
        }
        return current.snapshot();
    }

    public Outcome outcome(UUID battleId, UUID captureId) {
        Session current = this.session;
        if (current == null || captureId == null
                || !current.battleId.equals(battleId)
                || !current.captureId.equals(captureId)) {
            return Outcome.FAILED;
        }
        return switch (current.phase) {
            case CAPTURING -> Outcome.CAPTURING;
            case READY -> Outcome.READY;
            case FAILED -> Outcome.FAILED;
        };
    }

    // 在 Field 可用时冻结 Activation Frame；失败由调用方继续退化路径。
    public boolean commitActivationFrame(Minecraft minecraft, UUID battleId, UUID captureId) {
        Objects.requireNonNull(minecraft, "minecraft");
        Session current = this.session;
        if (current == null || captureId == null
                || !current.battleId.equals(battleId)
                || !current.captureId.equals(captureId)
                || current.phase != CapturePhase.READY) {
            return false;
        }
        current.commitActivationFrame(minecraft);
        return true;
    }

    public void tick(Minecraft minecraft) {
        Session current = this.session;
        if (current == null || !current.captureInProgress()) {
            return;
        }
        if (!current.validate(minecraft, false)) {
            return;
        }
        current.elapsedTicks++;
        if (current.elapsedTicks > CAPTURE_TIMEOUT_TICKS) {
            current.fail(minecraft, "环境捕获超时", null);
        }
    }

    // renderLevel HEAD 边界：启动稳定/捕获帧；Field 就绪后改为抑制普通世界渲染。
    public boolean beginWorldFrameAndShouldSuppress(Minecraft minecraft) {
        Session current = this.session;
        if (current == null || current.closed || current.phase == CapturePhase.FAILED) {
            return false;
        }
        if (!current.validate(minecraft, false)) {
            return false;
        }
        if (current.phase == CapturePhase.READY) {
            current.worldFrameStarted = false;
            return current.matchesActiveScreen(minecraft);
        }
        if (!current.hostFramePending) {
            current.ensurePanoramicMode(minecraft);
        }
        current.worldFrameStarted = true;
        return false;
    }

    // 必须在世界 post chain 完成且 GUI 尚未绘制时捕获最终世界颜色。
    public void captureFinalWorldColor(Minecraft minecraft, boolean renderLevelRequested) {
        Session current = this.session;
        if (current == null || !renderLevelRequested || !current.worldFrameStarted) {
            return;
        }
        current.worldFrameStarted = false;
        if (!current.validate(minecraft, false) || !current.captureInProgress()) {
            return;
        }
        RenderTarget mainTarget = minecraft.getMainRenderTarget();
        boolean hostRestored = false;
        try {
            if (current.hostFramePending) {
                current.captureHostFrame(mainTarget);
                current.hostFramePending = false;
                return;
            }

            if (current.settleFramePending) {
                current.settleFramePending = false;
            } else {
                current.captureFace(mainTarget);
                current.captureRevision = nextCaptureRevision();
                current.faceIndex++;
                if (current.faceIndex < CaptureFace.values().length) {
                    current.settleFramePending = true;
                } else {
                    current.gpu.extractField();
                    current.fieldRevision = nextFieldRevision();
                    current.phase = CapturePhase.READY;
                }
            }

            current.restoreHostFrame(mainTarget);
            current.restoreHostCamera(minecraft);
            current.restorePanoramicMode(minecraft);
            hostRestored = true;
            if (current.phase == CapturePhase.READY) {
                current.gpu.releaseTransientResources(current.debugMode);
                MineTale.LOGGER.info(
                        "Battle 环境场就绪: battle={}, capture={}, captureRevision={}, fieldRevision={}, debug={}",
                        current.battleId,
                        current.captureId,
                        current.captureRevision,
                        current.fieldRevision,
                        current.debugMode
                );
            }
        } catch (RuntimeException exception) {
            if (!current.hostFramePending && !hostRestored) {
                try {
                    current.restoreHostFrame(mainTarget);
                    current.restoreHostCamera(minecraft);
                    current.restorePanoramicMode(minecraft);
                } catch (RuntimeException restoreFailure) {
                    exception.addSuppressed(restoreFailure);
                }
            }
            current.fail(minecraft, "环境捕获或 Field GPU Pass 失败", exception);
        }
    }

    public EnvironmentCameraOverride cameraOverride() {
        Session current = this.session;
        if (current == null || !current.worldFrameStarted
                || current.hostFramePending || current.restoringHostCamera
                || !current.captureInProgress() || current.eyePosition == null) {
            return null;
        }
        CaptureFace face = current.face();
        return new EnvironmentCameraOverride(
                current.eyePosition,
                face.yaw(current.baseYawDegrees),
                face.pitchDegrees(),
                face.rollDegrees()
        );
    }

    // NeoForge 事件先覆盖标准角度路径，Camera.setup TAIL Mixin 负责覆盖其余分支。
    public void applyCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        EnvironmentCameraOverride override = cameraOverride();
        if (override == null) {
            return;
        }
        event.setYaw(override.yawDegrees);
        event.setPitch(override.pitchDegrees);
        event.setRoll(override.rollDegrees);
    }

    public boolean suppressTransientWorldEffects() {
        Session current = this.session;
        return current != null
                && current.worldFrameStarted
                && !current.hostFramePending
                && current.captureInProgress();
    }

    public void close(Minecraft minecraft, UUID battleId, UUID captureId) {
        Session current = this.session;
        if (current == null || captureId == null
                || !current.battleId.equals(battleId)
                || !current.captureId.equals(captureId)) {
            return;
        }
        current.close(minecraft, "Battle 已关闭");
        this.session = null;
    }

    @Override
    public void onResourceManagerReload(ResourceManager resourceManager) {
        Session current = this.session;
        if (current != null && !current.closed && current.phase != CapturePhase.FAILED) {
            current.fail(Minecraft.getInstance(), "客户端资源或 shader 已重载", null);
        }
    }

    private long nextCaptureRevision() {
        this.captureRevisionSequence = Math.incrementExact(this.captureRevisionSequence);
        return this.captureRevisionSequence;
    }

    private long nextFieldRevision() {
        this.fieldRevisionSequence = Math.incrementExact(this.fieldRevisionSequence);
        return this.fieldRevisionSequence;
    }

    public record EnvironmentCameraOverride(
            Vec3 position,
            float yawDegrees,
            float pitchDegrees,
            float rollDegrees
    ) {
        public EnvironmentCameraOverride {
            Objects.requireNonNull(position, "position");
        }
    }

    public enum Outcome {
        CAPTURING,
        READY,
        FAILED
    }

    private enum CapturePhase {
        CAPTURING,
        READY,
        FAILED
    }

    private record CaptureBasis(Vec3 right, Vec3 up, Vec3 forward) {
        private CaptureBasis {
            Objects.requireNonNull(right, "right");
            Objects.requireNonNull(up, "up");
            Objects.requireNonNull(forward, "forward");
        }

        static CaptureBasis fromHorizontalYaw(float yawDegrees) {
            double yaw = Math.toRadians(yawDegrees);
            Vec3 forward = new Vec3(-Math.sin(yaw), 0.0D, Math.cos(yaw));
            Vec3 right = new Vec3(-Math.cos(yaw), 0.0D, -Math.sin(yaw));
            return new CaptureBasis(right, new Vec3(0.0D, 1.0D, 0.0D), forward);
        }
    }

    private final class Session {
        private final UUID captureId;
        private final UUID battleId;
        private final DebugMode debugMode;
        private CapturePhase phase = CapturePhase.CAPTURING;
        private CaptureBasis captureBasis;
        private ResourceKey<Level> dimension;
        private Vec3 eyePosition;
        private float baseYawDegrees;
        private int sourceWidth;
        private int sourceHeight;
        private int faceIndex;
        private int elapsedTicks;
        private long captureRevision;
        private long fieldRevision;
        private EnvironmentCaptureGpu gpu;
        private boolean settleFramePending = true;
        private boolean previousPanoramicMode;
        private boolean panoramicModeApplied;
        private boolean worldFrameStarted;
        private boolean hostFramePending = true;
        private boolean restoringHostCamera;
        private boolean activationFrameCommitted;
        private boolean closed;

        private Session(UUID captureId, UUID battleId, DebugMode debugMode) {
            this.captureId = captureId;
            this.battleId = battleId;
            this.debugMode = debugMode;
        }

        private void begin(Minecraft minecraft) {
            try {
                if (minecraft.player == null || minecraft.level == null) {
                    fail(minecraft, "玩家或世界不存在", null);
                    return;
                }
                float partialTick = minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(true);
                this.eyePosition = minecraft.player.getEyePosition(partialTick);
                this.baseYawDegrees = minecraft.player.getViewYRot(partialTick);
                this.captureBasis = CaptureBasis.fromHorizontalYaw(this.baseYawDegrees);
                this.dimension = minecraft.level.dimension();
                RenderTarget mainTarget = minecraft.getMainRenderTarget();
                this.sourceWidth = mainTarget.width;
                this.sourceHeight = mainTarget.height;
                if (this.sourceWidth <= 0 || this.sourceHeight <= 0) {
                    fail(minecraft, "主渲染目标尺寸无效", null);
                    return;
                }

                this.gpu = EnvironmentCaptureGpu.create(this.sourceWidth, this.sourceHeight);
                this.previousPanoramicMode = minecraft.gameRenderer.isPanoramicMode();
            } catch (RuntimeException exception) {
                fail(minecraft, "无法初始化环境捕获资源", exception);
            }
        }

        private boolean validate(Minecraft minecraft, boolean requireScreen) {
            if (this.closed || this.phase == CapturePhase.FAILED) {
                return false;
            }
            if (minecraft.player == null || minecraft.level == null) {
                fail(minecraft, "玩家或世界不存在", null);
                return false;
            }
            if (this.dimension == null || !this.dimension.equals(minecraft.level.dimension())) {
                fail(minecraft, "捕获期间切换了维度", null);
                return false;
            }
            if (requireScreen && !matchesActiveScreen(minecraft)) {
                fail(minecraft, "对应的 BattleScreen 已不再活动", null);
                return false;
            }

            RenderTarget mainTarget = minecraft.getMainRenderTarget();
            if (mainTarget.width != this.sourceWidth || mainTarget.height != this.sourceHeight) {
                return handleResize(minecraft, mainTarget);
            }
            return true;
        }

        private boolean handleResize(Minecraft minecraft, RenderTarget mainTarget) {
            if (mainTarget.width <= 0 || mainTarget.height <= 0) {
                fail(minecraft, "resize 后主渲染目标尺寸无效", null);
                return false;
            }
            this.sourceWidth = mainTarget.width;
            this.sourceHeight = mainTarget.height;
            if (this.phase == CapturePhase.READY) {
                return true;
            }
            try {
                safelyCloseGpu();
                safelyRestorePanoramicMode(minecraft);
                this.gpu = EnvironmentCaptureGpu.create(this.sourceWidth, this.sourceHeight);
                this.faceIndex = 0;
                this.elapsedTicks = 0;
                this.fieldRevision = 0L;
                this.settleFramePending = true;
                this.worldFrameStarted = false;
                this.hostFramePending = true;
                MineTale.LOGGER.info(
                        "Battle 环境捕获因 resize 从首面重启: battle={}, capture={}, size={}x{}",
                        this.battleId,
                        this.captureId,
                        this.sourceWidth,
                        this.sourceHeight
                );
            } catch (RuntimeException exception) {
                fail(minecraft, "resize 后无法重建环境捕获资源", exception);
            }
            return false;
        }

        private boolean matchesActiveScreen(Minecraft minecraft) {
            return minecraft.screen instanceof BattleScreen battleScreen
                    && battleScreen.presentation().instance().battleId().equals(this.battleId);
        }

        private void ensurePanoramicMode(Minecraft minecraft) {
            if (!minecraft.gameRenderer.isPanoramicMode()) {
                minecraft.gameRenderer.setPanoramicMode(true);
            }
            this.panoramicModeApplied = true;
        }

        private CaptureFace face() {
            return CaptureFace.at(Math.min(this.faceIndex, CaptureFace.values().length - 1));
        }

        private void captureFace(RenderTarget source) {
            if (this.gpu == null) {
                throw new IllegalStateException("环境捕获缺少 GPU 模块");
            }
            this.gpu.capture(face(), source);
        }

        private void captureHostFrame(RenderTarget source) {
            if (this.gpu == null) {
                throw new IllegalStateException("环境捕获缺少 GPU 模块");
            }
            this.gpu.captureHostFrame(source);
        }

        private void commitActivationFrame(Minecraft minecraft) {
            if (this.activationFrameCommitted) {
                return;
            }
            if (this.closed || this.phase != CapturePhase.READY || this.gpu == null) {
                throw new IllegalStateException("环境捕获未就绪，不能提交 Activation Frame");
            }
            RenderTarget mainTarget = minecraft.getMainRenderTarget();
            if (mainTarget.width <= 0 || mainTarget.height <= 0) {
                throw new IllegalStateException("Activation Frame 主目标尺寸无效");
            }
            this.gpu.resizeHostFrame(mainTarget.width, mainTarget.height);
            this.gpu.captureHostFrame(mainTarget);
            this.sourceWidth = mainTarget.width;
            this.sourceHeight = mainTarget.height;
            this.activationFrameCommitted = true;
        }

        private void restoreHostFrame(RenderTarget destination) {
            if (this.gpu == null) {
                throw new IllegalStateException("环境捕获缺少 GPU 模块");
            }
            this.gpu.restoreHostFrame(destination);
        }

        private void restoreHostCamera(Minecraft minecraft) {
            Entity cameraEntity = minecraft.getCameraEntity();
            if (cameraEntity == null) {
                cameraEntity = minecraft.player;
            }
            if (cameraEntity == null || minecraft.level == null) {
                return;
            }
            float partialTick = minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(true);
            float cameraPartialTick = minecraft.level.tickRateManager().isEntityFrozen(cameraEntity)
                    ? 1.0F
                    : partialTick;
            this.restoringHostCamera = true;
            try {
                minecraft.gameRenderer.getMainCamera().setup(
                        minecraft.level,
                        cameraEntity,
                        !minecraft.options.getCameraType().isFirstPerson(),
                        minecraft.options.getCameraType().isMirrored(),
                        cameraPartialTick
                );
            } finally {
                this.restoringHostCamera = false;
            }
        }

        private EnvironmentCaptureSnapshot snapshot() {
            if (this.closed
                    || this.phase != CapturePhase.READY
                    || !this.activationFrameCommitted
                    || this.gpu == null) {
                return EnvironmentCaptureSnapshot.idle();
            }
            EnvironmentField field = this.gpu.field(
                    this.captureBasis.right(),
                    this.captureBasis.up(),
                    this.captureBasis.forward()
            );
            EnvironmentCaptureSnapshot.DebugTexture debugTexture =
                    this.debugMode == DebugMode.NORMAL
                            ? null
                            : this.gpu.debugTexture(this.debugMode);
            return new EnvironmentCaptureSnapshot(
                    this.battleId,
                    this.gpu.activationFrame(),
                    field,
                    debugTexture
            );
        }

        private boolean captureInProgress() {
            return !this.closed && this.phase == CapturePhase.CAPTURING;
        }

        private void fail(Minecraft minecraft, String reason, RuntimeException exception) {
            if (this.closed || this.phase == CapturePhase.FAILED) {
                return;
            }
            this.phase = CapturePhase.FAILED;
            this.worldFrameStarted = false;
            safelyRestorePanoramicMode(minecraft);
            safelyCloseGpu();
            if (exception == null) {
                MineTale.LOGGER.warn(
                        "Battle 环境捕获失败并回退黑背景: battle={}, capture={}, reason={}",
                        this.battleId,
                        this.captureId,
                        reason
                );
            } else {
                MineTale.LOGGER.warn(
                        "Battle 环境捕获失败并回退黑背景: battle={}, capture={}, reason={}",
                        this.battleId,
                        this.captureId,
                        reason,
                        exception
                );
            }
        }

        private void close(Minecraft minecraft, String reason) {
            if (this.closed) {
                return;
            }
            this.closed = true;
            this.worldFrameStarted = false;
            safelyRestorePanoramicMode(minecraft);
            safelyCloseGpu();
            MineTale.LOGGER.debug(
                    "关闭 Battle 环境捕获: battle={}, capture={}, reason={}",
                    this.battleId,
                    this.captureId,
                    reason
            );
        }

        private void restorePanoramicMode(Minecraft minecraft) {
            if (!this.panoramicModeApplied) {
                return;
            }
            minecraft.gameRenderer.setPanoramicMode(this.previousPanoramicMode);
            this.panoramicModeApplied = false;
        }

        private void safelyRestorePanoramicMode(Minecraft minecraft) {
            try {
                restorePanoramicMode(minecraft);
            } catch (RuntimeException exception) {
                this.panoramicModeApplied = false;
                MineTale.LOGGER.error(
                        "恢复环境捕获 panoramic mode 失败: battle={}, capture={}",
                        this.battleId,
                        this.captureId,
                        exception
                );
            }
        }

        private void safelyCloseGpu() {
            EnvironmentCaptureGpu current = this.gpu;
            this.gpu = null;
            if (current == null) {
                return;
            }
            try {
                current.close();
            } catch (RuntimeException exception) {
                MineTale.LOGGER.error(
                        "释放环境捕获 GPU 模块失败: battle={}, capture={}",
                        this.battleId,
                        this.captureId,
                        exception
                );
            }
        }
    }
}
