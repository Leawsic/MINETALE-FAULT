package cn.jehorstudio.minetale.battle.presentation.states;

import cn.jehorstudio.minetale.battle.logic.action.BattleRenderRequestOccurrence;
import cn.jehorstudio.minetale.battle.logic.action.BattleRenderRequestPayload;
import cn.jehorstudio.minetale.battle.logic.action.CubicBezierEasing;
import cn.jehorstudio.minetale.battle.network.NetworkObjectId;
import cn.jehorstudio.minetale.battle.presentation.VisualConfig;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

// 只持有不影响玩法结果的客户端状态。
// Camera 与 ViewMode 属于逻辑状态，因为2D或3D会直接影响碰撞语义。
public final class PresentationStateCache {
    private NetworkHealthState localNetworkHealth = NetworkHealthState.STABLE;
    private final Map<NetworkObjectId, NetworkHealthState> remoteNetworkHealth = new HashMap<>();
    private final List<TimedEffect<BattleRenderRequestPayload.ScreenShake>> shakes = new ArrayList<>();
    private final List<TimedEffect<BattleRenderRequestPayload.ScreenFlash>> flashes = new ArrayList<>();
    private TimedEffect<BattleRenderRequestPayload.SceneTransition> transition;
    private double environmentBackgroundOpacity = 1.0D;
    private EnvironmentOpacityTransition environmentOpacityTransition;
    private BattleRenderRequestPayload.ForegroundOverlaySource foregroundOverlaySource =
            BattleRenderRequestPayload.ForegroundOverlaySource.COLOR;
    private int foregroundOverlayRgb;
    private double foregroundOverlayOpacity;
    private ForegroundOverlayTransition foregroundOverlayTransition;
    private long nextEffectId;

    public NetworkHealthState localNetworkHealth() {
        return this.localNetworkHealth;
    }

    public void setLocalNetworkHealth(NetworkHealthState localNetworkHealth) {
        this.localNetworkHealth = Objects.requireNonNull(localNetworkHealth, "localNetworkHealth");
    }

    public void setRemoteNetworkHealth(NetworkObjectId networkObjectId, NetworkHealthState health) {
        this.remoteNetworkHealth.put(
                Objects.requireNonNull(networkObjectId, "networkObjectId"),
                Objects.requireNonNull(health, "health")
        );
    }

    public Optional<NetworkHealthState> remoteNetworkHealth(NetworkObjectId networkObjectId) {
        return Optional.ofNullable(this.remoteNetworkHealth.get(Objects.requireNonNull(networkObjectId, "networkObjectId")));
    }

    public void removeRemoteNetworkHealth(NetworkObjectId networkObjectId) {
        this.remoteNetworkHealth.remove(Objects.requireNonNull(networkObjectId, "networkObjectId"));
    }

    // 按入队顺序登记；transition 替换单通道，shake 与 flash 可并行叠加。
    public void acceptScreenEffectRequests(List<BattleRenderRequestOccurrence> occurrences, long currentBattleTick) {
        Objects.requireNonNull(occurrences, "occurrences");
        if (currentBattleTick < 0L) throw new IllegalArgumentException("currentBattleTick must be >= 0.");
        for (BattleRenderRequestOccurrence occurrence : occurrences) {
            Objects.requireNonNull(occurrence, "occurrence");
            long endBattleTick = endBattleTick(occurrence);
            BattleRenderRequestPayload payload = occurrence.request().payload();
            if (payload instanceof BattleRenderRequestPayload.EnvironmentBackgroundOpacity value) {
                acceptEnvironmentBackgroundOpacity(occurrence, endBattleTick, currentBattleTick, value);
                continue;
            }
            if (payload instanceof BattleRenderRequestPayload.ForegroundOverlay value) {
                acceptForegroundOverlay(occurrence, endBattleTick, currentBattleTick, value);
                continue;
            }
            if (occurrence.durationBattleTicks() == 0L || endBattleTick <= currentBattleTick) {
                continue;
            }
            long id = this.nextEffectId++;
            if (payload instanceof BattleRenderRequestPayload.SceneTransition value) {
                this.transition = new TimedEffect<>(id, occurrence.requestedAtBattleTick(), endBattleTick, value);
            } else if (payload instanceof BattleRenderRequestPayload.ScreenShake value) {
                this.shakes.add(new TimedEffect<>(id, occurrence.requestedAtBattleTick(), endBattleTick, value));
            } else if (payload instanceof BattleRenderRequestPayload.ScreenFlash value) {
                this.flashes.add(new TimedEffect<>(id, occurrence.requestedAtBattleTick(), endBattleTick, value));
            }
        }
    }

    // displayBattleTick 可含 partial tick；结果必须与可变缓存完全解耦。
    public ScreenEffectSnapshot screenEffectSnapshot(double displayBattleTick, int battleTicksPerSecond) {
        if (!Double.isFinite(displayBattleTick) || displayBattleTick < 0.0D) {
            throw new IllegalArgumentException("displayBattleTick must be finite and >= 0.");
        }
        if (battleTicksPerSecond <= 0) throw new IllegalArgumentException("battleTicksPerSecond must be > 0.");

        pruneExpired(displayBattleTick);

        Optional<ScreenEffectSnapshot.Transition> transitionSnapshot = Optional.empty();
        if (active(this.transition, displayBattleTick)) {
            transitionSnapshot = Optional.of(new ScreenEffectSnapshot.Transition(
                    this.transition.id(), progress(this.transition, displayBattleTick)
            ));
        }

        double shakeX = 0.0D;
        double shakeY = 0.0D;
        for (TimedEffect<BattleRenderRequestPayload.ScreenShake> shake : this.shakes) {
            if (!active(shake, displayBattleTick)) continue;
            double elapsedSeconds = (displayBattleTick - shake.startBattleTick()) / battleTicksPerSecond;
            double samplePosition = elapsedSeconds * VisualConfig.SCREEN_SHAKE_SAMPLES_PER_SECOND();
            long sampleIndex = (long) Math.floor(samplePosition);
            double interpolation = smoothstep(samplePosition - sampleIndex);
            double angle0 = randomAngle(shake.id(), sampleIndex);
            double angle1 = randomAngle(shake.id(), sampleIndex + 1L);
            double directionX = lerp(Math.cos(angle0), Math.cos(angle1), interpolation);
            double directionY = lerp(Math.sin(angle0), Math.sin(angle1), interpolation);
            double amplitude = VisualConfig.SCREEN_SHAKE_VIEWPORT_SCALE()
                    * shake.payload().intensity()
                    * (1.0D - progress(shake, displayBattleTick));
            shakeX += directionX * amplitude;
            shakeY += directionY * amplitude;
        }

        double compositeRed = 0.0D;
        double compositeGreen = 0.0D;
        double compositeBlue = 0.0D;
        double compositeAlpha = 0.0D;
        for (TimedEffect<BattleRenderRequestPayload.ScreenFlash> flash : this.flashes) {
            if (!active(flash, displayBattleTick)) continue;
            double alpha = flash.payload().intensity() * (1.0D - progress(flash, displayBattleTick));
            int rgb = flash.payload().rgb();
            double red = ((rgb >>> 16) & 0xFF) / 255.0D;
            double green = ((rgb >>> 8) & 0xFF) / 255.0D;
            double blue = (rgb & 0xFF) / 255.0D;
            compositeRed = compositeRed * (1.0D - alpha) + red * alpha;
            compositeGreen = compositeGreen * (1.0D - alpha) + green * alpha;
            compositeBlue = compositeBlue * (1.0D - alpha) + blue * alpha;
            compositeAlpha = compositeAlpha * (1.0D - alpha) + alpha;
        }
        ScreenEffectSnapshot.Flash flashSnapshot = compositeAlpha <= 0.0D
                ? ScreenEffectSnapshot.Flash.NONE
                : new ScreenEffectSnapshot.Flash(
                        (float) (compositeRed / compositeAlpha),
                        (float) (compositeGreen / compositeAlpha),
                        (float) (compositeBlue / compositeAlpha),
                        (float) compositeAlpha
                );
        double foregroundOpacity = foregroundOverlayOpacityAt(displayBattleTick);
        int foregroundRgb = this.foregroundOverlayRgb;
        ScreenEffectSnapshot.ForegroundOverlay foregroundOverlay =
                new ScreenEffectSnapshot.ForegroundOverlay(
                        this.foregroundOverlaySource
                                == BattleRenderRequestPayload.ForegroundOverlaySource.ENVIRONMENT
                                ? ScreenEffectSnapshot.ForegroundOverlay.Source.ENVIRONMENT
                                : ScreenEffectSnapshot.ForegroundOverlay.Source.COLOR,
                        ((foregroundRgb >>> 16) & 0xFF) / 255.0F,
                        ((foregroundRgb >>> 8) & 0xFF) / 255.0F,
                        (foregroundRgb & 0xFF) / 255.0F,
                        (float) foregroundOpacity
                );
        return new ScreenEffectSnapshot(
                transitionSnapshot,
                shakeX,
                shakeY,
                flashSnapshot,
                foregroundOverlay,
                (float) environmentBackgroundOpacityAt(displayBattleTick)
        );
    }

    public Snapshot snapshot() {
        return new Snapshot(this.localNetworkHealth, this.remoteNetworkHealth);
    }

    private void pruneExpired(double displayBattleTick) {
        if (this.transition != null && displayBattleTick >= this.transition.endBattleTick()) {
            this.transition = null;
        }
        this.shakes.removeIf(effect -> displayBattleTick >= effect.endBattleTick());
        this.flashes.removeIf(effect -> displayBattleTick >= effect.endBattleTick());
    }

    private static boolean active(TimedEffect<?> effect, double displayBattleTick) {
        return effect != null
                && displayBattleTick >= effect.startBattleTick()
                && displayBattleTick < effect.endBattleTick();
    }

    private static float progress(TimedEffect<?> effect, double displayBattleTick) {
        double duration = effect.endBattleTick() - effect.startBattleTick();
        return (float) Math.max(0.0D, Math.min(1.0D,
                (displayBattleTick - effect.startBattleTick()) / duration));
    }

    private static long endBattleTick(BattleRenderRequestOccurrence occurrence) {
        try {
            return Math.addExact(occurrence.requestedAtBattleTick(), occurrence.durationBattleTicks());
        } catch (ArithmeticException ignored) {
            return Long.MAX_VALUE;
        }
    }

    private void acceptEnvironmentBackgroundOpacity(
            BattleRenderRequestOccurrence occurrence,
            long endBattleTick,
            long currentBattleTick,
            BattleRenderRequestPayload.EnvironmentBackgroundOpacity request
    ) {
        double targetOpacity = request.opacity();
        if (occurrence.durationBattleTicks() == 0L || endBattleTick <= currentBattleTick) {
            this.environmentBackgroundOpacity = targetOpacity;
            this.environmentOpacityTransition = null;
            return;
        }
        long startBattleTick = occurrence.requestedAtBattleTick();
        double startOpacity = environmentBackgroundOpacityAt(startBattleTick);
        this.environmentOpacityTransition = new EnvironmentOpacityTransition(
                startBattleTick,
                endBattleTick,
                startOpacity,
                targetOpacity
        );
    }

    private double environmentBackgroundOpacityAt(double displayBattleTick) {
        EnvironmentOpacityTransition active = this.environmentOpacityTransition;
        if (active == null) {
            return this.environmentBackgroundOpacity;
        }
        if (displayBattleTick <= active.startBattleTick()) {
            return active.startOpacity();
        }
        if (displayBattleTick >= active.endBattleTick()) {
            this.environmentBackgroundOpacity = active.targetOpacity();
            this.environmentOpacityTransition = null;
            return this.environmentBackgroundOpacity;
        }
        double rawProgress = (displayBattleTick - active.startBattleTick())
                / (active.endBattleTick() - active.startBattleTick());
        double easedProgress = CubicBezierEasing.BATTLE_TRANSITION.map(rawProgress);
        return lerp(active.startOpacity(), active.targetOpacity(), easedProgress);
    }

    private void acceptForegroundOverlay(
            BattleRenderRequestOccurrence occurrence,
            long endBattleTick,
            long currentBattleTick,
            BattleRenderRequestPayload.ForegroundOverlay request
    ) {
        double targetOpacity = request.opacity();
        long startBattleTick = occurrence.requestedAtBattleTick();
        double startOpacity = foregroundOverlayOpacityAt(startBattleTick);
        this.foregroundOverlaySource = request.source();
        this.foregroundOverlayRgb = request.rgb();
        if (occurrence.durationBattleTicks() == 0L || endBattleTick <= currentBattleTick) {
            this.foregroundOverlayOpacity = targetOpacity;
            this.foregroundOverlayTransition = null;
            return;
        }
        this.foregroundOverlayTransition = new ForegroundOverlayTransition(
                startBattleTick,
                endBattleTick,
                startOpacity,
                targetOpacity
        );
    }

    private double foregroundOverlayOpacityAt(double displayBattleTick) {
        ForegroundOverlayTransition active = this.foregroundOverlayTransition;
        if (active == null) {
            return this.foregroundOverlayOpacity;
        }
        if (displayBattleTick <= active.startBattleTick()) {
            return active.startOpacity();
        }
        if (displayBattleTick >= active.endBattleTick()) {
            this.foregroundOverlayOpacity = active.targetOpacity();
            this.foregroundOverlayTransition = null;
            return this.foregroundOverlayOpacity;
        }
        double rawProgress = (displayBattleTick - active.startBattleTick())
                / (active.endBattleTick() - active.startBattleTick());
        double easedProgress = CubicBezierEasing.BATTLE_TRANSITION.map(rawProgress);
        return lerp(active.startOpacity(), active.targetOpacity(), easedProgress);
    }

    private static double randomAngle(long effectId, long sampleIndex) {
        long value = effectId * 0x9E3779B97F4A7C15L + sampleIndex * 0xD1B54A32D192ED03L;
        value ^= value >>> 30;
        value *= 0xBF58476D1CE4E5B9L;
        value ^= value >>> 27;
        value *= 0x94D049BB133111EBL;
        value ^= value >>> 31;
        double unit = (value >>> 11) * 0x1.0p-53;
        return unit * Math.PI * 2.0D;
    }

    private static double smoothstep(double value) {
        return value * value * (3.0D - 2.0D * value);
    }

    private static double lerp(double from, double to, double progress) {
        return from + (to - from) * progress;
    }

    private record TimedEffect<T extends BattleRenderRequestPayload>(
            long id,
            long startBattleTick,
            long endBattleTick,
            T payload
    ) {
        private TimedEffect {
            Objects.requireNonNull(payload, "payload");
        }
    }

    private record EnvironmentOpacityTransition(
            long startBattleTick,
            long endBattleTick,
            double startOpacity,
            double targetOpacity
    ) {
        private EnvironmentOpacityTransition {
            if (startBattleTick < 0L || endBattleTick <= startBattleTick) {
                throw new IllegalArgumentException("环境背景透明度过渡的 tick 区间无效");
            }
            requireUnit(startOpacity, "startOpacity");
            requireUnit(targetOpacity, "targetOpacity");
        }

        private static void requireUnit(double value, String name) {
            if (!Double.isFinite(value) || value < 0.0D || value > 1.0D) {
                throw new IllegalArgumentException(name + " must be between 0 and 1.");
            }
        }
    }

    private record ForegroundOverlayTransition(
            long startBattleTick,
            long endBattleTick,
            double startOpacity,
            double targetOpacity
    ) {
        private ForegroundOverlayTransition {
            if (startBattleTick < 0L || endBattleTick <= startBattleTick) {
                throw new IllegalArgumentException("前景覆盖透明度过渡的 tick 区间无效");
            }
            requireUnit(startOpacity, "startOpacity");
            requireUnit(targetOpacity, "targetOpacity");
        }

        private static void requireUnit(double value, String name) {
            if (!Double.isFinite(value) || value < 0.0D || value > 1.0D) {
                throw new IllegalArgumentException(name + " must be between 0 and 1.");
            }
        }
    }

    public record Snapshot(
            NetworkHealthState localNetworkHealth,
            Map<NetworkObjectId, NetworkHealthState> remoteNetworkHealth
    ) {
        public Snapshot {
            Objects.requireNonNull(localNetworkHealth, "localNetworkHealth");
            remoteNetworkHealth = Map.copyOf(remoteNetworkHealth);
        }

        public Optional<NetworkHealthState> remoteNetworkHealth(NetworkObjectId networkObjectId) {
            return Optional.ofNullable(this.remoteNetworkHealth.get(Objects.requireNonNull(networkObjectId, "networkObjectId")));
        }
    }

    public enum NetworkHealthLevel { STABLE, DEGRADED, LOST }

    public enum NetworkHealthReason {
        NONE,
        SEND_BACKLOG,
        ACK_DELAY,
        TIMEOUT,
        STALE_REMOTE_UPDATE,
        BUFFER_UNDERFILLED
    }

    public record NetworkHealthState(
            NetworkHealthLevel level,
            double lastValidUpdateRenderTime,
            double ackDelaySeconds,
            Set<NetworkHealthReason> reasons
    ) {
        public static final NetworkHealthState STABLE = new NetworkHealthState(
                NetworkHealthLevel.STABLE, 0.0D, 0.0D, Set.of(NetworkHealthReason.NONE)
        );

        public NetworkHealthState {
            Objects.requireNonNull(level, "level");
            Objects.requireNonNull(reasons, "reasons");
            if (!Double.isFinite(lastValidUpdateRenderTime) || lastValidUpdateRenderTime < 0.0D) {
                throw new IllegalArgumentException("lastValidUpdateRenderTime must be finite and >= 0.");
            }
            if (!Double.isFinite(ackDelaySeconds) || ackDelaySeconds < 0.0D) {
                throw new IllegalArgumentException("ackDelaySeconds must be finite and >= 0.");
            }
            EnumSet<NetworkHealthReason> copy = reasons.isEmpty()
                    ? EnumSet.of(NetworkHealthReason.NONE)
                    : EnumSet.copyOf(reasons);
            if (copy.size() > 1) copy.remove(NetworkHealthReason.NONE);
            reasons = Set.copyOf(copy);
        }

        public static NetworkHealthState stable(double time) {
            return new NetworkHealthState(NetworkHealthLevel.STABLE, time, 0.0D, Set.of(NetworkHealthReason.NONE));
        }

        public static NetworkHealthState degraded(double time, NetworkHealthReason reason) {
            return new NetworkHealthState(NetworkHealthLevel.DEGRADED, time, 0.0D, Set.of(reason));
        }

        public static NetworkHealthState lost(double time, NetworkHealthReason reason) {
            return new NetworkHealthState(NetworkHealthLevel.LOST, time, 0.0D, Set.of(reason));
        }
    }
}
