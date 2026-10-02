package cn.jehorstudio.minetale.battle.logic;

import cn.jehorstudio.minetale.MineTale;
import cn.jehorstudio.minetale.battle.logic.action.ActionStackManager;
import cn.jehorstudio.minetale.battle.logic.action.BattleActionRunner;
import cn.jehorstudio.minetale.battle.logic.action.BattleRenderRequest;
import cn.jehorstudio.minetale.battle.logic.action.BattleRenderRequestDiagnostics;
import cn.jehorstudio.minetale.battle.logic.action.BattleRenderRequestOccurrence;
import cn.jehorstudio.minetale.battle.logic.action.BattleSoundRequest;
import cn.jehorstudio.minetale.battle.logic.action.BattleSoundRequestOccurrence;
import cn.jehorstudio.minetale.battle.logic.actor.actors.BattleBox;
import cn.jehorstudio.minetale.battle.logic.actor.actors.Bullet;
import cn.jehorstudio.minetale.battle.logic.actor.actors.PlayerSoul;
import cn.jehorstudio.minetale.battle.logic.event.LogicEventDispatcher;
import cn.jehorstudio.minetale.battle.logic.event.LogicEventListenerRegistry;
import cn.jehorstudio.minetale.battle.logic.event.LogicEvents;
import cn.jehorstudio.minetale.battle.logic.input.BattleInputState;
import cn.jehorstudio.minetale.battle.network.payload.BattleResultState;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class BattleInstance {
    private final UUID battleId;
    private final LogicTimeline timeline;
    private final BattleLogicStateCache stateCache;
    private final LogicEventListenerRegistry eventListeners;
    private final LogicEventDispatcher eventDispatcher;
    private final BattleActionRunner actionRunner;
    private final ActionStackManager actionStacks;
    private final BattleInputState input = new BattleInputState();
    private final List<BattleRenderRequestOccurrence> requestedRenderActions = new ArrayList<>();
    private final List<BattleSoundRequestOccurrence> requestedSounds = new ArrayList<>();
    private long nextSoundRequestSequence;
    private int renderRequestBudget = 64;
    private int renderRequestPendingLimit;
    private long renderRequestBudgetTick = -1L;
    private long lastRenderRequestWarningTick = Long.MIN_VALUE;
    private int acceptedRenderRequestsThisTick;
    private int droppedRenderRequestsThisTick;
    private final BattleBox battleBox;
    private final PlayerSoul playerSoul;
    private final Bullet bullet;
    private BattleLogicStateSnapshot lastStateSnapshot;
    private BattleLogicStateSnapshot currentStateSnapshot;
    private BattleLogicStateSnapshot renderStartStateSnapshot;

    private boolean ended;
    private BattleResultState requestedResultState;

    // 无参入口保留内置调试初始化；自定义注册或禁用测试子弹应使用完整构造。
    public BattleInstance() {
        this(UUID.randomUUID());
    }

    public BattleInstance(UUID battleId) {
        this(battleId, BattleRuntimeBootstrap.NONE, 0L, true);
    }

    public BattleInstance(UUID battleId, BattleRuntimeBootstrap bootstrap, long initialBattleTick, boolean spawnInitialTestBullets) {
        this.battleId = Objects.requireNonNull(battleId, "battleId");
        Objects.requireNonNull(bootstrap, "bootstrap");
        this.timeline = new LogicTimeline(
                this.battleId,
                initialBattleTick,
                LogicTimelineSettings.DEFAULT_BATTLE_STEPS_PER_GAME_TICK,
                LogicTimelineSettings.DEFAULT_MAX_BATTLE_STEPS_PER_GAME_TICK
        );
        this.renderRequestPendingLimit = pendingRenderRequestLimit(this.renderRequestBudget);
        this.stateCache = new BattleLogicStateCache();
        this.eventListeners = new LogicEventListenerRegistry();
        this.eventDispatcher = new LogicEventDispatcher(this.battleId, this.eventListeners);
        this.actionRunner = new BattleActionRunner(this);
        this.actionStacks = new ActionStackManager(this);
        this.battleBox = new BattleBox(this.stateCache);
        this.playerSoul = new PlayerSoul(this.stateCache);
        this.bullet = new Bullet(this.stateCache, this.timeline, this.eventDispatcher, this.actionRunner, spawnInitialTestBullets);
        registerEventListener(this);
        registerEventListener(this.battleBox);
        registerEventListener(this.playerSoul);
        registerEventListener(this.bullet);
        bootstrap.register(this);
        this.eventDispatcher.send(LogicEvents.ON_INITIALIZE);
        this.eventDispatcher.drain(this.timeline.battleTick());
        this.currentStateSnapshot = this.stateCache.snapshot(this.timeline.battleTick());
        this.lastStateSnapshot = this.currentStateSnapshot;
        this.renderStartStateSnapshot = this.currentStateSnapshot;
    }

    public UUID battleId() {
        return this.battleId;
    }

    public LogicTimeline timeline() {
        return this.timeline;
    }

    public BattleLogicStateCache stateCache() {
        return this.stateCache;
    }

    public LogicEventDispatcher events() {
        return this.eventDispatcher;
    }

    public BattleActionRunner actions() {
        return this.actionRunner;
    }

    public ActionStackManager actionStacks() {
        return this.actionStacks;
    }

    public BattleInputState input() {
        return this.input;
    }

    public boolean requestRenderAction(BattleRenderRequest request) {
        Objects.requireNonNull(request, "request");
        long battleTick = this.timeline.battleTick();
        if (this.renderRequestBudgetTick != battleTick) {
            this.renderRequestBudgetTick = battleTick;
            this.acceptedRenderRequestsThisTick = 0;
            this.droppedRenderRequestsThisTick = 0;
        }
        if (this.acceptedRenderRequestsThisTick >= this.renderRequestBudget) {
            return dropRenderRequest(battleTick, "per-tick", this.renderRequestBudget);
        }
        if (this.requestedRenderActions.size() >= this.renderRequestPendingLimit) {
            return dropRenderRequest(battleTick, "pending", this.renderRequestPendingLimit);
        }
        this.requestedRenderActions.add(new BattleRenderRequestOccurrence(
                request,
                battleTick,
                this.timeline.secondsToBattleTicks(request.durationSeconds())
        ));
        this.acceptedRenderRequestsThisTick++;
        return true;
    }

    public void setRenderRequestBudget(int renderRequestBudget) {
        if (renderRequestBudget <= 0) {
            throw new IllegalArgumentException("renderRequestBudget must be positive.");
        }
        this.renderRequestBudget = renderRequestBudget;
        this.renderRequestPendingLimit = pendingRenderRequestLimit(renderRequestBudget);
    }

    public BattleRenderRequestDiagnostics renderRequestDiagnostics() {
        long currentBattleTick = this.timeline.battleTick();
        boolean currentTickHasRequests = this.renderRequestBudgetTick == currentBattleTick;
        return new BattleRenderRequestDiagnostics(
                currentBattleTick,
                this.renderRequestBudget,
                currentTickHasRequests ? this.acceptedRenderRequestsThisTick : 0,
                currentTickHasRequests ? this.droppedRenderRequestsThisTick : 0,
                this.requestedRenderActions.size(),
                this.renderRequestPendingLimit
        );
    }

    private boolean dropRenderRequest(long battleTick, String limitType, int limit) {
        this.droppedRenderRequestsThisTick++;
        long warningInterval = this.timeline.battleTicksPerSecond();
        if (this.lastRenderRequestWarningTick == Long.MIN_VALUE
                || battleTick - this.lastRenderRequestWarningTick >= warningInterval) {
            this.lastRenderRequestWarningTick = battleTick;
            MineTale.LOGGER.warn(
                    "Battle render request limit exceeded. battleId={}, battleTick={}, limitType={}, limit={}, pending={}",
                    this.battleId,
                    battleTick,
                    limitType,
                    limit,
                    this.requestedRenderActions.size()
            );
        }
        return false;
    }

    private int pendingRenderRequestLimit(int perTickBudget) {
        long limit = (long) perTickBudget * this.timeline.maxBattleStepsPerGameTick();
        return (int) Math.min(Integer.MAX_VALUE, limit);
    }

    public List<BattleRenderRequestOccurrence> drainRequestedRenderActions() {
        List<BattleRenderRequestOccurrence> drained = List.copyOf(this.requestedRenderActions);
        this.requestedRenderActions.clear();
        return drained;
    }

    public void registerEventListener(Object listener) {
        this.eventDispatcher.register(listener);
    }

    public BattleLogicStateSnapshot lastStateSnapshot() {
        return this.lastStateSnapshot;
    }

    public BattleLogicStateSnapshot currentStateSnapshot() {
        return this.currentStateSnapshot;
    }

    public BattleLogicStateSnapshot renderStartStateSnapshot() {
        return this.renderStartStateSnapshot;
    }

    public int advanceForGameTick() {
        if (this.ended) {
            return 0;
        }

        int steps = this.timeline.stepsForNextGameTickWithCatchUp();
        this.renderStartStateSnapshot = this.currentStateSnapshot;

        for (int i = 0; i < steps; i++) {
            if (!this.advanceOneStep()) {
                break;
            }
        }
        return steps;
    }

    private boolean advanceOneStep() {
        if (!this.timeline.advanceOneStep()) {
            return false;
        }

        onStep();

        return true;
    }

    private void captureStateSnapshot() {
        this.lastStateSnapshot = this.currentStateSnapshot;
        this.currentStateSnapshot = this.stateCache.snapshot(this.timeline.battleTick());
    }

    private void onStep() {
        this.actionStacks.advance();
        dispatchStepEvents();
        captureStateSnapshot();
        dispatchSnapshotEvents();
        this.stateCache.cleanupBattleTickEnd();
    }

    private void dispatchStepEvents() {
        this.eventDispatcher.send(LogicEvents.ON_STEP);
        this.eventDispatcher.drain(this.timeline.battleTick());
    }

    private void dispatchSnapshotEvents() {
        this.eventDispatcher.enqueueSnapshotEvents(LogicEvents.dispatchOrder(), this.currentStateSnapshot);
        this.eventDispatcher.drain(this.timeline.battleTick());
    }

    public boolean ended() {
        return this.ended;
    }

    public void requestSound(BattleSoundRequest request) {
        this.requestedSounds.add(new BattleSoundRequestOccurrence(
                Objects.requireNonNull(request, "request"),
                this.timeline.battleTick(),
                this.nextSoundRequestSequence++
        ));
    }

    public List<BattleSoundRequestOccurrence> drainRequestedSounds() {
        List<BattleSoundRequestOccurrence> drained = List.copyOf(this.requestedSounds);
        this.requestedSounds.clear();
        return drained;
    }

    public void endBattle() {
        endBattle(BattleResultState.VICTORY);
    }

    public void endBattle(BattleResultState resultState) {
        this.requestedResultState = Objects.requireNonNull(resultState, "resultState");
        this.ended = true;
    }

    public Optional<BattleResultState> requestedResultState() {
        return Optional.ofNullable(this.requestedResultState);
    }
}
