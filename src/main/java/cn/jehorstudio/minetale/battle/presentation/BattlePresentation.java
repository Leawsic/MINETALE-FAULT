package cn.jehorstudio.minetale.battle.presentation;

import cn.jehorstudio.minetale.MineTale;
import cn.jehorstudio.minetale.battle.logic.BattleLogicStateSnapshot;
import cn.jehorstudio.minetale.battle.logic.action.ActionStack;
import cn.jehorstudio.minetale.battle.logic.action.BattleActionResult;
import cn.jehorstudio.minetale.battle.logic.action.actions.RequestSmoothCameraAction;
import cn.jehorstudio.minetale.battle.logic.actor.Actor;
import cn.jehorstudio.minetale.battle.logic.actor.BattleArenaBounds;
import cn.jehorstudio.minetale.battle.logic.actor.ActorLifecycle;
import cn.jehorstudio.minetale.battle.logic.actor.ActorRef;
import cn.jehorstudio.minetale.battle.logic.coordinate.BattleViewMode;
import cn.jehorstudio.minetale.battle.logic.coordinate.BattleCoordinateStateCache;
import cn.jehorstudio.minetale.battle.logic.coordinate.CanonicalTransform;
import cn.jehorstudio.minetale.battle.logic.coordinate.CanonicalVec3;
import cn.jehorstudio.minetale.battle.logic.coordinate.ControlPolicy;
import cn.jehorstudio.minetale.battle.logic.input.BattleInputKey;
import cn.jehorstudio.minetale.battle.logic.actor.ActorType;
import cn.jehorstudio.minetale.battle.logic.actor.VisualRef;
import cn.jehorstudio.minetale.battle.logic.actor.actors.PlayerSoul;
import cn.jehorstudio.minetale.battle.logic.actor.states.ActorSingleSnapshot;
import cn.jehorstudio.minetale.battle.logic.actor.states.types.NetworkProxyStateSnapshot;
import cn.jehorstudio.minetale.battle.logic.actor.states.types.PlayerSoulMode;
import cn.jehorstudio.minetale.battle.logic.actor.states.types.PlayerSoulStateSnapshot;
import cn.jehorstudio.minetale.battle.network.NetworkObjectId;
import cn.jehorstudio.minetale.battle.network.client.NetworkProxyIndex;
import cn.jehorstudio.minetale.battle.network.client.SourceSequenceTracker;
import cn.jehorstudio.minetale.battle.network.payload.BattleAliveState;
import cn.jehorstudio.minetale.battle.network.payload.BattleParticipant;
import cn.jehorstudio.minetale.battle.network.payload.BattleResultReportPayload;
import cn.jehorstudio.minetale.battle.network.payload.BattleResultState;
import cn.jehorstudio.minetale.battle.network.payload.BattleStartPayload;
import cn.jehorstudio.minetale.battle.network.payload.BattleStartFailedPayload;
import cn.jehorstudio.minetale.battle.network.payload.PlayerSoulLowFrequencyPatchPayload;
import cn.jehorstudio.minetale.battle.network.payload.PlayerSoulPositionSnapshotPayload;
import cn.jehorstudio.minetale.battle.network.payload.PlayerStateInitialSnapshot;
import cn.jehorstudio.minetale.battle.network.payload.RelayedPlayerSoulLowFrequencyPatchPayload;
import cn.jehorstudio.minetale.battle.network.payload.RelayedPlayerSoulPositionSnapshotPayload;
import cn.jehorstudio.minetale.battle.presentation.debug.DebugLocalBattleController;
import cn.jehorstudio.minetale.battle.presentation.screen.render.environment.EnvironmentCaptureService;
import cn.jehorstudio.minetale.battle.presentation.screen.render.environment.EnvironmentCaptureSnapshot;
import cn.jehorstudio.minetale.battle.logic.rules.RuleResolvedView;
import cn.jehorstudio.minetale.battle.presentation.screen.BattleScreen;
import cn.jehorstudio.minetale.battle.presentation.screen.render.BattleScene;
import cn.jehorstudio.minetale.battle.logic.BattleInstance;
import cn.jehorstudio.minetale.battle.presentation.states.InputStateCache;
import cn.jehorstudio.minetale.battle.presentation.sound.BattleSoundPlayback;
import cn.jehorstudio.minetale.battle.presentation.states.PresentationStateCache;
import cn.jehorstudio.minetale.battle.presentation.states.ScreenEffectSnapshot;
import cn.jehorstudio.minetale.battle.presentation.states.PresentationStateCache.NetworkHealthReason;
import cn.jehorstudio.minetale.battle.presentation.states.PresentationStateCache.NetworkHealthState;
import cn.jehorstudio.minetale.battle.presentation.states.PresentationStateCache.Snapshot;
import cn.jehorstudio.minetale.battle.presentation.timeline.InterpolatedField;
import cn.jehorstudio.minetale.battle.presentation.timeline.InterpolatedValue;
import cn.jehorstudio.minetale.battle.presentation.timeline.InterpolationAlgorithm;
import cn.jehorstudio.minetale.battle.presentation.timeline.RenderTrackDiagnostics;
import cn.jehorstudio.minetale.battle.presentation.timeline.TrackSource;
import cn.jehorstudio.minetale.battle.script.BattleDefinition;
import cn.jehorstudio.minetale.battle.script.BattleScriptCatalog;
import cn.jehorstudio.minetale.battle.script.BattleScriptBootstrap;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;

public final class BattlePresentation {
    private static BattlePresentation active;
    private static final int LOW_FREQUENCY_REFRESH_TICKS = 40;
    private static final int CAMERA_TRANSITION_PRIORITY = 500;

    private final BattleInstance instance;
    private final DebugLocalBattleController debugController;
    private final UUID localPlayerId;
    private final NetworkObjectId localNetworkObjectId;
    private final NetworkProxyIndex proxyIndex = new NetworkProxyIndex();
    private final SourceSequenceTracker sourceSequences = new SourceSequenceTracker();
    private final Map<NetworkObjectId, RemotePlayerSoulStatus> remoteSoulStatuses = new HashMap<>();
    private final InputStateCache input = new InputStateCache();
    private final PresentationStateCache state = new PresentationStateCache();
    private final RenderTimeline renderTimeline;
    private final BattleSoundPlayback soundPlayback;
    private final Set<ActorRef> submittedRenderTracks = new HashSet<>();
    private final Map<ActorRef, Long> submittedTransformDiscontinuities = new HashMap<>();
    private final Set<String> screenHeldInputKeys = new LinkedHashSet<>();
    private final Set<String> pendingScreenPressedInputKeys = new LinkedHashSet<>();
    private Snapshot renderStartStateSnapshot;
    private Snapshot currentStateSnapshot;
    private int lowFrequencyRefreshCountdown;
    private boolean inputSnapshotInitialized;
    private boolean closed;
    private UUID environmentCaptureId;
    private double debugOrbitYawDeg = VisualConfig.DEBUG_ORBIT_INITIAL_YAW_DEGREES();
    private double debugOrbitPitchDeg = VisualConfig.DEBUG_ORBIT_INITIAL_PITCH_DEGREES();

    public BattlePresentation(BattleInstance instance) {
        this(instance, null, null, null);
    }

    private BattlePresentation(
            BattleInstance instance,
            DebugLocalBattleController debugController,
            UUID localPlayerId,
            NetworkObjectId localNetworkObjectId
    ) {
        this.instance = Objects.requireNonNull(instance, "instance");
        this.debugController = debugController;
        this.localPlayerId = localPlayerId;
        this.localNetworkObjectId = localNetworkObjectId;
        this.renderTimeline = new RenderTimeline();
        this.soundPlayback = new BattleSoundPlayback(this.instance);
        this.currentStateSnapshot = this.state.snapshot();
        this.renderStartStateSnapshot = this.currentStateSnapshot;
        submitLocalCommittedSamples(this.instance.currentStateSnapshot());
    }

    public static BattlePresentation startDebugBattle(Minecraft minecraft) {
        DebugLocalBattleController debugController = new DebugLocalBattleController();
        BattlePresentation presentation = new BattlePresentation(debugController.createInstance(), debugController, null, null);
        BattlePreparation.INSTANCE.beginInitial(presentation.instance().battleId());
        BattlePreparation.INSTANCE.attach(minecraft, presentation, null);
        MineTale.LOGGER.info("Debug Battle 已进入 Preparation: {}", presentation.instance().battleId());
        return presentation;
    }

    public static BattlePresentation startFromBattleStart(Minecraft minecraft, BattleStartPayload payload) {
        PlayerStateInitialSnapshot privateState = payload.recipientPrivatePlayerState()
                .orElseThrow(() -> new IllegalArgumentException("BattleStart for local client must contain private player state."));
        BattleDefinition definition = BattleScriptCatalog.get(payload.shared().battleDefinitionId())
                .orElseThrow(() -> new IllegalStateException("Missing BattleScript definition: " + payload.shared().battleDefinitionId()));
        if (definition.schemaVersion() != payload.shared().schemaVersion()) {
            throw new IllegalStateException("BattleScript schema mismatch for " + definition.id());
        }
        if (!definition.hash().equals(payload.shared().definitionHash())) {
            throw new IllegalStateException("BattleScript hash mismatch for " + definition.id());
        }
        UUID localPlayerId = privateState.recipientPlayerId();
        BattleInstance instance = new BattleInstance(
                payload.battleId(),
                BattleScriptBootstrap.create(definition, payload.shared().seed()),
                payload.shared().startBattleTick(),
                false
        );
        instance.stateCache().players().applyInitialSnapshot(privateState.playerState());

        NetworkObjectId localNetworkObjectId = NetworkObjectId.playerSoul(payload.battleId(), localPlayerId);
        BattlePresentation presentation = new BattlePresentation(instance, null, localPlayerId, localNetworkObjectId);
        presentation.createNetworkProxyActors(payload);
        BattlePreparation.INSTANCE.attach(minecraft, presentation, definition);
        MineTale.LOGGER.info(
                "Network Battle 已进入 Preparation: {}, script={}",
                payload.battleId(),
                definition.id()
        );
        return presentation;
    }

    public static BattlePresentation active() {
        return active;
    }

    void activate(Minecraft minecraft, PreparedBattle prepared) {
        Objects.requireNonNull(minecraft, "minecraft");
        Objects.requireNonNull(prepared, "prepared");
        if (prepared.presentation() != this) {
            throw new IllegalArgumentException("Prepared Battle 不属于当前 BattlePresentation");
        }
        if (this.closed) {
            throw new IllegalStateException("已关闭 BattlePresentation 不能激活");
        }
        if (active != null) {
            throw new IllegalStateException("已有 active BattlePresentation");
        }
        EnvironmentCaptureService.Outcome environment = EnvironmentCaptureService.INSTANCE.outcome(
                this.instance.battleId(),
                prepared.environmentCaptureId()
        );
        if (environment == EnvironmentCaptureService.Outcome.CAPTURING) {
            throw new IllegalStateException("环境捕获尚未完成，不能激活 Battle");
        }
        boolean activationFrameReady = EnvironmentCaptureService.INSTANCE.commitActivationFrame(
                minecraft,
                this.instance.battleId(),
                prepared.environmentCaptureId()
        );
        if (!activationFrameReady && environment == EnvironmentCaptureService.Outcome.READY) {
            throw new IllegalStateException("环境捕获已就绪但无法提交 Activation Frame");
        }
        this.environmentCaptureId = prepared.environmentCaptureId();
        active = this;
        minecraft.setScreen(new BattleScreen(this));
    }

    void failPreparation(RuntimeException exception) {
        if (this.closed) {
            return;
        }
        this.closed = true;
        this.soundPlayback.detachAll();
        if (active == this) {
            active = null;
        }
        if (networked()) {
            ClientPacketDistributor.sendToServer(new BattleStartFailedPayload(
                    this.instance.battleId(),
                    this.localPlayerId,
                    exception.getMessage() == null
                            ? exception.getClass().getSimpleName()
                            : exception.getMessage()
            ));
        }
    }

    public BattleInstance instance() {
        return this.instance;
    }

    public PresentationStateCache presentationState() {
        return this.state;
    }

    public RenderTimeline renderTimeline() {
        return this.renderTimeline;
    }

    public InputStateCache input() {
        return this.input;
    }

    public void clearInput() {
        this.input.clearAll();
        this.instance.input().clear();
        this.screenHeldInputKeys.clear();
        this.pendingScreenPressedInputKeys.clear();
        this.inputSnapshotInitialized = false;
    }

    // 只接收已归一化的 KeyMapping ID。
    public void setScreenInputKeyHeld(String key, boolean held) {
        String validatedKey = BattleInputKey.require(key, "screen input key");
        if (held) {
            if (this.screenHeldInputKeys.add(validatedKey)) {
                this.pendingScreenPressedInputKeys.add(validatedKey);
            }
        } else {
            this.screenHeldInputKeys.remove(validatedKey);
        }
    }

    // 只允许在 3D 场景切换投影。
    public void toggleProjection() {
        var coordinates = this.instance.stateCache().coordinates();
        if (coordinates.sceneMode() == BattleCoordinateStateCache.SceneMode.TWO_D) {
            return;
        }
        requestViewTransition(
                projectionCounterpart(coordinates.viewMode()),
                coordinates.sceneMode(),
                coordinates.viewScale(),
                VisualConfig.DEBUG_PROJECTION_TRANSITION_SECONDS()
        );
    }

    // 本地视图切换统一通过 Action Stack 推进。
    public BattleActionResult requestViewTransition(
            BattleViewMode targetView,
            BattleCoordinateStateCache.SceneMode targetScene,
            double targetScale,
            double durationSeconds
    ) {
        ActionStack stack = this.instance.actionStacks().createStack(CAMERA_TRANSITION_PRIORITY);
        return this.instance.actionStacks().push(stack.stackId(), new RequestSmoothCameraAction(
                durationSeconds,
                targetView,
                targetScene,
                targetScale,
                new RequestSmoothCameraAction.RenderModeTransitionSpec(
                        VisualConfig.BATTLE_RENDER_MODE() == VisualConfig.BattleRenderMode.RENDERED,
                        VisualConfig.SCENE_RENDER_MODE_ENTER_DELAY_SECONDS(),
                        VisualConfig.SCENE_RENDER_MODE_CROSSFADE_SECONDS()
                )
        ));
    }

    public BattleScene.Snapshot captureSceneSnapshot() {
        float partial = this.renderTimeline.renderPartialTick();
        return captureSceneSnapshot(this.renderTimeline.renderBattleTime(partial), partial);
    }

    // 场景与屏幕效果必须共享同一 render time，否则可能导致单次提交内出现时间撕裂。
    public RenderSnapshot captureRenderSnapshot() {
        float partial = this.renderTimeline.renderPartialTick();
        double displayTime = this.renderTimeline.renderBattleTime(partial);
        return new RenderSnapshot(
                captureSceneSnapshot(displayTime, partial),
                this.state.screenEffectSnapshot(displayTime, this.renderTimeline.battleTicksPerSecond()),
                EnvironmentCaptureService.INSTANCE.snapshot(
                        this.instance.battleId(),
                        this.environmentCaptureId
                )
        );
    }

    BattleScene.Snapshot captureSceneSnapshot(double displayTime, float partial) {
        BattleLogicStateSnapshot logic = this.instance.currentStateSnapshot();
        Map<ActorRef, CanonicalTransform> transforms = new HashMap<>();
        for (ActorSingleSnapshot actor : logic.actors().activeRenderableActors()) {
            CanonicalTransform committed = actor.transform();
            CanonicalVec3 position = this.renderTimeline.sample(actor.ref(), InterpolatedField.POSITION, displayTime)
                    .filter(InterpolatedValue.VectorValue.class::isInstance)
                    .map(InterpolatedValue.VectorValue.class::cast)
                    .map(InterpolatedValue.VectorValue::value)
                    .orElse(committed.position());
            double yaw = this.renderTimeline.sample(actor.ref(), InterpolatedField.YAW_Y, displayTime)
                    .filter(InterpolatedValue.DoubleValue.class::isInstance)
                    .map(InterpolatedValue.DoubleValue.class::cast)
                    .map(InterpolatedValue.DoubleValue::value)
                    .orElse(committed.yawDeg());
            double pitch = this.renderTimeline.sample(actor.ref(), InterpolatedField.PITCH_X, displayTime)
                    .filter(InterpolatedValue.DoubleValue.class::isInstance)
                    .map(InterpolatedValue.DoubleValue.class::cast)
                    .map(InterpolatedValue.DoubleValue::value)
                    .orElse(committed.pitchDeg());
            double roll = this.renderTimeline.sample(actor.ref(), InterpolatedField.ROLL_Z, displayTime)
                    .filter(InterpolatedValue.DoubleValue.class::isInstance)
                    .map(InterpolatedValue.DoubleValue.class::cast)
                    .map(InterpolatedValue.DoubleValue::value)
                    .orElse(committed.rollDeg());
            transforms.put(actor.ref(), new CanonicalTransform(
                    position,
                    yaw,
                    pitch,
                    roll,
                    committed.scale()
            ));
        }
        BattleLogicStateSnapshot renderStart = this.instance.renderStartStateSnapshot();
        BattleViewMode fromView = RuleResolvedView.resolve(renderStart.rules(), renderStart.coordinates()).viewMode();
        BattleViewMode toView = RuleResolvedView.resolve(logic.rules(), logic.coordinates()).viewMode();
        BattleViewMode viewMode = RequestSmoothCameraAction.sampleView(fromView, toView, partial);
        BattleCoordinateStateCache.SceneMode sceneMode = RequestSmoothCameraAction.sampleSceneMode(
                renderStart.coordinates().sceneMode(),
                logic.coordinates().sceneMode(),
                partial
        );
        double viewScale = lerp(renderStart.coordinates().viewScale(), logic.coordinates().viewScale(), partial);
        return new BattleScene.Snapshot(
                logic,
                transforms,
                viewMode,
                sceneMode,
                viewScale,
                lerp(
                        renderStart.coordinates().soulModelRotationBlend(),
                        logic.coordinates().soulModelRotationBlend(),
                        partial
                ),
                lerp(
                        renderStart.coordinates().renderedBlend(),
                        logic.coordinates().renderedBlend(),
                        partial
                )
        );
    }

    public void toggleDebugSceneMode() {
        var coordinates = this.instance.stateCache().coordinates();
        boolean toThreeDimensional = coordinates.sceneMode() == BattleCoordinateStateCache.SceneMode.TWO_D;
        requestViewTransition(
                toThreeDimensional ? debugOrbitView() : BattleViewMode.ORTHO_2D,
                toThreeDimensional
                        ? BattleCoordinateStateCache.SceneMode.THREE_D
                        : BattleCoordinateStateCache.SceneMode.TWO_D,
                toThreeDimensional ? VisualConfig.DEBUG_3D_VIEW_SCALE() : 1.0D,
                VisualConfig.DEBUG_SCENE_TRANSITION_SECONDS()
        );
    }

    public void rotateDebugCamera(double yawDeltaDeg, double pitchDeltaDeg) {
        if (this.instance.stateCache().coordinates().sceneMode()
                != BattleCoordinateStateCache.SceneMode.THREE_D) {
            return;
        }
        this.debugOrbitYawDeg = wrapDegrees(this.debugOrbitYawDeg + yawDeltaDeg);
        this.debugOrbitPitchDeg = Math.max(
                -VisualConfig.DEBUG_ORBIT_MAX_PITCH_DEGREES(),
                Math.min(VisualConfig.DEBUG_ORBIT_MAX_PITCH_DEGREES(), this.debugOrbitPitchDeg + pitchDeltaDeg)
        );
        var coordinates = this.instance.stateCache().coordinates();
        BattleViewMode current = coordinates.viewMode();
        requestViewTransition(
                current.orthographic()
                        ? debugOrbitOrthographicView(current.orthoHeight())
                        : debugOrbitView(),
                coordinates.sceneMode(),
                coordinates.viewScale(),
                VisualConfig.DEBUG_CAMERA_ROTATION_SECONDS()
        );
    }

    public Snapshot renderStartStateSnapshot() {
        return this.renderStartStateSnapshot;
    }

    public Snapshot currentStateSnapshot() {
        return this.currentStateSnapshot;
    }

    public void onClientTick() {
        if (this.instance.ended()) {
            closeWithResult(this.instance.requestedResultState().orElse(BattleResultState.VICTORY));
            if (Minecraft.getInstance().screen instanceof BattleScreen) {
                Minecraft.getInstance().setScreen(null);
            }
            return;
        }

        sampleInputState();
        applyLocalPlayerMovement();
        if (this.debugController != null) {
            this.debugController.applyClientMovementToLogicState(this);
        }
        this.renderStartStateSnapshot = this.currentStateSnapshot;
        int advancedSteps = this.instance.advanceForGameTick();
        this.renderTimeline.beginRenderWindow(
                this.instance.renderStartStateSnapshot().battleTick(),
                advancedSteps,
                this.instance.timeline().secondsPerBattleStep(),
                this.instance.timeline().battleTicksPerSecond()
        );
        submitLocalCommittedSamples(this.instance.currentStateSnapshot());
        if (this.debugController != null) {
            this.debugController.submitRemoteNetworkSamples(this);
        }
        cleanupRenderTimelineTracks(this.instance.currentStateSnapshot());
        updateNetworkHealth(this.instance.currentStateSnapshot());
        this.state.acceptScreenEffectRequests(
                this.instance.drainRequestedRenderActions(),
                this.instance.timeline().battleTick()
        );
        this.soundPlayback.accept(this.instance.drainRequestedSounds());
        this.currentStateSnapshot = this.state.snapshot();
        sendLocalNetworkSamples();
    }

    public void submitRemoteNetworkSample(ActorSingleSnapshot actor, double displayTime) {
        Objects.requireNonNull(actor, "actor");
        if (actor.type() != ActorType.NETWORK_PROXY) {
            throw new IllegalArgumentException("Only NETWORK_PROXY actors can be submitted as remote network samples.");
        }
        submitActorTransformSample(actor, TrackSource.REMOTE_NETWORK, displayTime);
    }

    public void applyRelayedPosition(RelayedPlayerSoulPositionSnapshotPayload payload) {
        if (!networked() || payload.sourcePlayerId().equals(this.localPlayerId)) {
            return;
        }
        this.proxyIndex.resolve(payload.snapshot().networkObjectId())
                .flatMap(ref -> this.instance.stateCache().actors().resolve(ref))
                .ifPresent(actor -> {
                    CanonicalTransform old = actor.transform();
                    actor.setTransform(new CanonicalTransform(
                            payload.snapshot().position(),
                            payload.snapshot().yawYDeg(),
                            old.pitchDeg(),
                            old.rollDeg(),
                            old.scale()
                    ));
                    double displayTime = this.renderTimeline.renderBattleTime()
                            + VisualConfig.REMOTE_SAMPLE_DELAY_SECONDS() * this.instance.timeline().battleTicksPerSecond();
                    submitRemoteNetworkSample(actor.snapshot(), displayTime);
                });
    }

    public void applyRelayedLowFrequencyPatch(RelayedPlayerSoulLowFrequencyPatchPayload payload) {
        if (!networked() || payload.sourcePlayerId().equals(this.localPlayerId)) {
            return;
        }
        RemotePlayerSoulStatus oldStatus = this.remoteSoulStatuses.getOrDefault(
                payload.patch().networkObjectId(),
                RemotePlayerSoulStatus.DEFAULT
        );
        this.remoteSoulStatuses.put(payload.patch().networkObjectId(), oldStatus.apply(payload.patch()));
        this.proxyIndex.resolve(payload.patch().networkObjectId())
                .flatMap(ref -> this.instance.stateCache().actors().resolve(ref))
                .ifPresent(actor -> payload.patch().mode().ifPresent(mode -> actor.ensureNetworkProxyState()
                        .setProxySnapshot(ActorType.PLAYER_SOUL, new PlayerSoulStateSnapshot(mode))));
    }

    public void closeWithResult(BattleResultState resultState) {
        if (this.closed) {
            return;
        }
        this.closed = true;
        this.soundPlayback.detachAll();
        if (networked()) {
            ClientPacketDistributor.sendToServer(new BattleResultReportPayload(
                    this.instance.battleId(),
                    this.localPlayerId,
                    resultState,
                    this.instance.timeline().battleTick()
            ));
            this.proxyIndex.clear();
            this.sourceSequences.clear();
            this.remoteSoulStatuses.clear();
        }
        EnvironmentCaptureService.INSTANCE.close(
                Minecraft.getInstance(),
                this.instance.battleId(),
                this.environmentCaptureId
        );
        this.environmentCaptureId = null;
        if (active == this) {
            active = null;
        }
    }

    void submitLocalCommittedSamples(BattleLogicStateSnapshot snapshot) {
        for (ActorSingleSnapshot actor : snapshot.actors().activeRenderableActors()) {
            if (actor.type() == ActorType.NETWORK_PROXY) {
                continue;
            }
            resetTrackAfterTransformDiscontinuity(actor);
            submitActorTransformSample(actor, TrackSource.LOCAL_COMMITTED, snapshot.battleTick());
        }
    }

    private void resetTrackAfterTransformDiscontinuity(ActorSingleSnapshot actor) {
        Long previous = this.submittedTransformDiscontinuities.put(
                actor.ref(), actor.transformDiscontinuity());
        if (previous != null && previous.longValue() != actor.transformDiscontinuity()) {
            this.renderTimeline.removeTrack(actor.ref());
            this.submittedRenderTracks.remove(actor.ref());
        }
    }

    private void createNetworkProxyActors(BattleStartPayload payload) {
        for (BattleParticipant participant : payload.finalParticipants()) {
            UUID participantId = participant.playerId();
            if (participantId.equals(this.localPlayerId)) {
                continue;
            }
            NetworkObjectId networkObjectId = NetworkObjectId.playerSoul(payload.battleId(), participantId);
            Actor proxy = this.instance.stateCache().actors().createNetworkProxyActor(
                    networkObjectId,
                    participantId,
                    ActorType.PLAYER_SOUL,
                    VisualRef.special("player_soul"),
                    ActorLifecycle.ACTIVE
            );
            proxy.setCollisionShape(PlayerSoul.collisionShapeAtModelCenter());
            proxy.setTransform(new CanonicalTransform(
                    CanonicalVec3.ZERO,
                    0.0D,
                    0.0D,
                    0.0D,
                    CanonicalTransform.IDENTITY.scale()
            ));
            this.proxyIndex.bind(networkObjectId, proxy.ref());
        }
    }

    private void applyLocalPlayerMovement() {
        Actor soul = this.instance.stateCache().players().snapshot()
                .flatMap(snapshot -> this.instance.stateCache().actors().resolve(snapshot.soulRef()))
                .orElse(null);
        if (soul == null) {
            return;
        }

        InputStateCache input = this.input;
        RuleResolvedView resolvedRules = RuleResolvedView.resolve(
                this.instance.stateCache().rules().snapshot(),
                this.instance.stateCache().coordinates().snapshot()
        );
        RuleResolvedView.PlayerControl playerControl = resolvedRules.playerControl();
        if (!playerControl.movementEnabled()) {
            return;
        }
        BattleViewMode viewMode = resolvedRules.viewMode();
        ControlPolicy policy = this.instance.stateCache().coordinates().sceneMode()
                == BattleCoordinateStateCache.SceneMode.THREE_D
                ? ControlPolicy.free3d(resolvedRules.controlPolicy().speedBuPerSecond())
                : resolvedRules.controlPolicy();
        CanonicalVec3 direction = policy.movementDirection(
                axis(input.isMoveEastHeld(), input.isMoveWestHeld()),
                axis(input.isMoveNorthHeld(), input.isMoveSouthHeld()),
                axis(input.isMoveRiseHeld(), input.isMoveFallHeld()),
                viewMode
        );
        if (direction.lengthSquared() <= 1.0E-9D) {
            return;
        }

        double step = policy.speedBuPerSecond() * this.instance.timeline().secondsPerBattleStep()
                * this.instance.timeline().battleStepsPerGameTick();
        CanonicalVec3 position = policy.constrainPosition(soul.transform().position().add(direction.scale(step)));
        if (playerControl.clampToBattleBox()) {
            position = BattleArenaBounds.resolve(this.instance.stateCache().actors()).clamp(soul, position);
            position = policy.constrainPosition(position);
        }
        CanonicalTransform old = soul.transform();
        soul.setTransform(new CanonicalTransform(position, old.yawDeg(), old.pitchDeg(), old.rollDeg(), old.scale()));
    }

    private void sendLocalNetworkSamples() {
        if (!networked()) {
            return;
        }
        Actor soul = this.instance.stateCache().players().snapshot()
                .flatMap(snapshot -> this.instance.stateCache().actors().resolve(snapshot.soulRef()))
                .orElse(null);
        if (soul == null) {
            return;
        }
        ClientPacketDistributor.sendToServer(new PlayerSoulPositionSnapshotPayload(
                this.instance.battleId(),
                this.localNetworkObjectId,
                this.localPlayerId,
                this.sourceSequences.next(this.localNetworkObjectId),
                this.instance.timeline().battleTick(),
                soul.transform().position(),
                soul.transform().yawDeg()
        ));
        if (this.lowFrequencyRefreshCountdown-- <= 0) {
            this.lowFrequencyRefreshCountdown = LOW_FREQUENCY_REFRESH_TICKS;
            ClientPacketDistributor.sendToServer(new PlayerSoulLowFrequencyPatchPayload(
                    this.instance.battleId(),
                    this.localNetworkObjectId,
                    this.localPlayerId,
                    this.sourceSequences.next(this.localNetworkObjectId),
                    this.instance.timeline().battleTick(),
                    OptionalInt.of(this.instance.stateCache().players().hp()),
                    OptionalInt.of(this.instance.stateCache().players().maxHp()),
                    Optional.of(BattleAliveState.ALIVE),
                    Optional.of(PlayerSoulMode.NORMAL)
            ));
        }
    }

    private boolean networked() {
        return this.localPlayerId != null && this.localNetworkObjectId != null;
    }

    private void submitActorTransformSample(ActorSingleSnapshot actor, TrackSource source, double displayTime) {
        boolean submittedPosition = this.renderTimeline.submitSample(
                actor.ref(),
                source,
                InterpolatedField.POSITION,
                displayTime,
                InterpolationAlgorithm.VECTOR_LERP,
                new InterpolatedValue.VectorValue(actor.transform().position())
        );
        boolean submittedYaw = this.renderTimeline.submitSample(
                actor.ref(),
                source,
                InterpolatedField.YAW_Y,
                displayTime,
                InterpolationAlgorithm.ANGLE_SHORTEST_PATH_LERP,
                new InterpolatedValue.DoubleValue(actor.transform().yawDeg())
        );
        boolean submittedPitch = this.renderTimeline.submitSample(
                actor.ref(),
                source,
                InterpolatedField.PITCH_X,
                displayTime,
                InterpolationAlgorithm.ANGLE_SHORTEST_PATH_LERP,
                new InterpolatedValue.DoubleValue(actor.transform().pitchDeg())
        );
        boolean submittedRoll = this.renderTimeline.submitSample(
                actor.ref(),
                source,
                InterpolatedField.ROLL_Z,
                displayTime,
                InterpolationAlgorithm.ANGLE_SHORTEST_PATH_LERP,
                new InterpolatedValue.DoubleValue(actor.transform().rollDeg())
        );
        if (submittedPosition || submittedYaw || submittedPitch || submittedRoll) {
            this.submittedRenderTracks.add(actor.ref());
        }
    }

    private void cleanupRenderTimelineTracks(BattleLogicStateSnapshot snapshot) {
        Set<ActorRef> activeRenderableRefs = new HashSet<>();
        for (ActorSingleSnapshot actor : snapshot.actors().activeRenderableActors()) {
            activeRenderableRefs.add(actor.ref());
        }
        this.submittedTransformDiscontinuities.keySet().retainAll(activeRenderableRefs);
        this.submittedRenderTracks.removeIf(ref -> {
            if (activeRenderableRefs.contains(ref)) {
                return false;
            }
            this.renderTimeline.removeTrack(ref);
            return true;
        });
    }

    private void updateNetworkHealth(BattleLogicStateSnapshot snapshot) {
        double nowRenderTime = this.renderTimeline.renderBattleTime();
        this.state.setLocalNetworkHealth(NetworkHealthState.stable(nowRenderTime));

        Set<NetworkObjectId> activeRemoteObjects = new HashSet<>();
        for (ActorSingleSnapshot actor : snapshot.actors().actors(ActorType.NETWORK_PROXY)) {
            if (!(actor.typeSnapshot() instanceof NetworkProxyStateSnapshot proxySnapshot)) {
                continue;
            }
            activeRemoteObjects.add(proxySnapshot.networkObjectId());
            this.state.setRemoteNetworkHealth(
                    proxySnapshot.networkObjectId(),
                    remoteNetworkHealth(actor, nowRenderTime)
            );
        }

        for (NetworkObjectId known : snapshotRemoteHealthIds()) {
            if (!activeRemoteObjects.contains(known)) {
                this.state.removeRemoteNetworkHealth(known);
            }
        }
    }

    private Set<NetworkObjectId> snapshotRemoteHealthIds() {
        return this.state.snapshot().remoteNetworkHealth().keySet();
    }

    private NetworkHealthState remoteNetworkHealth(ActorSingleSnapshot actor, double nowRenderTime) {
        Optional<RenderTrackDiagnostics> diagnostics = this.renderTimeline.diagnostics(actor.ref(), InterpolatedField.POSITION);
        if (diagnostics.isEmpty()) {
            return NetworkHealthState.lost(0.0D, NetworkHealthReason.TIMEOUT);
        }

        RenderTrackDiagnostics value = diagnostics.get();
        double lastSampleTime = Double.isFinite(value.latestSampleDisplayTime()) ? value.latestSampleDisplayTime() : 0.0D;
        double age = Math.max(0.0D, nowRenderTime - lastSampleTime);
        double degradedTicks = VisualConfig.REMOTE_HEALTH_DEGRADED_AFTER_SECONDS()
                * this.instance.timeline().battleTicksPerSecond();
        double lostTicks = VisualConfig.REMOTE_HEALTH_LOST_AFTER_SECONDS()
                * this.instance.timeline().battleTicksPerSecond();

        if (age >= lostTicks) {
            return NetworkHealthState.lost(lastSampleTime, NetworkHealthReason.STALE_REMOTE_UPDATE);
        }
        if (age >= degradedTicks) {
            return NetworkHealthState.degraded(lastSampleTime, NetworkHealthReason.STALE_REMOTE_UPDATE);
        }
        if (value.bufferUnderfilled()) {
            return NetworkHealthState.degraded(lastSampleTime, NetworkHealthReason.BUFFER_UNDERFILLED);
        }
        return NetworkHealthState.stable(lastSampleTime);
    }

    private void sampleInputState() {
        Minecraft minecraft = Minecraft.getInstance();
        sampleInputState(Arrays.asList(minecraft.options.keyMappings));
    }

    // 保持包内可见。
    void sampleInputState(Iterable<KeyMapping> keyMappings) {
        LinkedHashSet<String> heldKeys = new LinkedHashSet<>();
        for (KeyMapping mapping : keyMappings) {
            if (BattleInputKey.contains(mapping.getName()) && mapping.isDown()) {
                heldKeys.add(mapping.getName());
            }
        }
        heldKeys.addAll(this.screenHeldInputKeys);
        if (this.inputSnapshotInitialized) {
            this.instance.input().updateHeldKeys(heldKeys, this.pendingScreenPressedInputKeys);
        } else {
            this.instance.input().synchronizeHeldKeys(heldKeys);
            if (!this.pendingScreenPressedInputKeys.isEmpty()) {
                this.instance.input().updateHeldKeys(heldKeys, this.pendingScreenPressedInputKeys);
            }
            this.inputSnapshotInitialized = true;
        }
        this.pendingScreenPressedInputKeys.clear();
        this.input.setMoveNorthHeld(this.instance.input().isHeld(BattleInputKey.MOVE_FORWARD));
        this.input.setMoveSouthHeld(this.instance.input().isHeld(BattleInputKey.MOVE_BACK));
        this.input.setMoveWestHeld(this.instance.input().isHeld(BattleInputKey.MOVE_LEFT));
        this.input.setMoveEastHeld(this.instance.input().isHeld(BattleInputKey.MOVE_RIGHT));
        this.input.setMoveRiseHeld(this.instance.input().isHeld(BattleInputKey.JUMP));
        this.input.setMoveFallHeld(this.instance.input().isHeld(BattleInputKey.SNEAK));
    }

    private static double axis(boolean positive, boolean negative) {
        if (positive == negative) {
            return 0.0D;
        }
        return positive ? 1.0D : -1.0D;
    }

    private BattleViewMode debugOrbitView() {
        return BattleViewMode.perspective3d(BattleViewMode.orbitCamera(
                this.debugOrbitYawDeg,
                this.debugOrbitPitchDeg,
                VisualConfig.DEBUG_ORBIT_DISTANCE(),
                VisualConfig.DEBUG_ORBIT_FOV_DEGREES()
        ));
    }

    private BattleViewMode debugOrbitOrthographicView(double orthoHeight) {
        BattleViewMode perspective = debugOrbitView();
        return new BattleViewMode(
                BattleViewMode.Type.ORTHO_2_5D,
                perspective.viewDirection(),
                perspective.cameraUp(),
                cn.jehorstudio.minetale.battle.logic.coordinate.BattleCoordinateSpace.Axis.Y,
                0.0D,
                orthoHeight,
                0.0D,
                null,
                BattleViewMode.Framing.fixed()
        );
    }

    private static BattleViewMode projectionCounterpart(BattleViewMode current) {
        if (current.orthographic()) {
            double fov = VisualConfig.DEBUG_ORBIT_FOV_DEGREES();
            double distance = current.orthoHeight()
                    / (2.0D * Math.tan(Math.toRadians(fov * 0.5D)));
            CanonicalVec3 target = CanonicalVec3.ZERO;
            CanonicalVec3 position = target.subtract(current.viewDirection().scale(distance));
            return BattleViewMode.perspective3d(new BattleViewMode.Camera(position, target, fov));
        }
        BattleViewMode.Camera camera = current.camera();
        CanonicalVec3 radial = camera.position().subtract(camera.target());
        double orthoHeight = 2.0D * radial.length()
                * Math.tan(Math.toRadians(camera.fovDegrees() * 0.5D));
        return new BattleViewMode(
                BattleViewMode.Type.ORTHO_2_5D,
                current.viewDirection(),
                current.cameraUp(),
                cn.jehorstudio.minetale.battle.logic.coordinate.BattleCoordinateSpace.Axis.Y,
                0.0D,
                orthoHeight,
                0.0D,
                null,
                BattleViewMode.Framing.fixed()
        );
    }

    private static double lerp(double from, double to, double progress) {
        return from + (to - from) * progress;
    }

    private static double wrapDegrees(double degrees) {
        double wrapped = degrees % 360.0D;
        return wrapped < -180.0D ? wrapped + 360.0D : (wrapped >= 180.0D ? wrapped - 360.0D : wrapped);
    }

    public record RenderSnapshot(
            BattleScene.Snapshot scene,
            ScreenEffectSnapshot effects,
            EnvironmentCaptureSnapshot environment
    ) {
        public RenderSnapshot {
            Objects.requireNonNull(scene, "scene");
            Objects.requireNonNull(effects, "effects");
            Objects.requireNonNull(environment, "environment");
        }
    }

    private record RemotePlayerSoulStatus(
            int hpDisplay,
            int maxHpDisplay,
            BattleAliveState aliveState,
            PlayerSoulMode mode
    ) {
        private static final RemotePlayerSoulStatus DEFAULT = new RemotePlayerSoulStatus(
                20,
                20,
                BattleAliveState.ALIVE,
                PlayerSoulMode.NORMAL
        );

        private RemotePlayerSoulStatus apply(PlayerSoulLowFrequencyPatchPayload patch) {
            return new RemotePlayerSoulStatus(
                    patch.hpDisplay().orElse(this.hpDisplay),
                    patch.maxHpDisplay().orElse(this.maxHpDisplay),
                    patch.aliveState().orElse(this.aliveState),
                    patch.mode().orElse(this.mode)
            );
        }
    }
}
