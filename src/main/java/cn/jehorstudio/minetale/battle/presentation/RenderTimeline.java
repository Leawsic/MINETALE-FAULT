package cn.jehorstudio.minetale.battle.presentation;

import cn.jehorstudio.minetale.battle.logic.actor.ActorRef;
import cn.jehorstudio.minetale.battle.logic.LogicTimelineSettings;
import cn.jehorstudio.minetale.battle.presentation.timeline.*;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class RenderTimeline {
    private final Map<ActorRef, ActorTrack> tracks = new HashMap<>();
    private long renderWindowStartBattleTick;
    private int renderWindowSteps;
    private long renderWindowStartedAtNanos;
    private double secondsPerBattleStep = 1.0D / (LogicTimelineSettings.DEFAULT_BATTLE_STEPS_PER_GAME_TICK * 20.0D);
    private int battleTicksPerSecond = LogicTimelineSettings.DEFAULT_BATTLE_STEPS_PER_GAME_TICK * 20;

    public void beginRenderWindow(long startBattleTick, int steps, double secondsPerBattleStep, int battleTicksPerSecond) {
        if (startBattleTick < 0L) {
            throw new IllegalArgumentException("startBattleTick must be >= 0.");
        }
        if (steps < 0) {
            throw new IllegalArgumentException("steps must be >= 0.");
        }
        if (!Double.isFinite(secondsPerBattleStep) || secondsPerBattleStep <= 0.0D) {
            throw new IllegalArgumentException("secondsPerBattleStep must be finite and > 0.");
        }
        if (battleTicksPerSecond <= 0) {
            throw new IllegalArgumentException("battleTicksPerSecond must be > 0.");
        }
        this.renderWindowStartBattleTick = startBattleTick;
        this.renderWindowSteps = steps;
        this.secondsPerBattleStep = secondsPerBattleStep;
        this.battleTicksPerSecond = battleTicksPerSecond;
        this.renderWindowStartedAtNanos = System.nanoTime();
    }

    public double renderBattleTime() {
        return renderBattleTime(renderPartialTick());
    }

    public double renderBattleTime(float partialTick) {
        if (!Float.isFinite(partialTick) || partialTick < 0.0F || partialTick > 1.0F) {
            throw new IllegalArgumentException("partialTick must be between 0 and 1.");
        }
        return this.renderWindowStartBattleTick + partialTick * this.renderWindowSteps;
    }

    public double renderBattleTimeSeconds() {
        return renderBattleTime() / this.battleTicksPerSecond;
    }

    public int battleTicksPerSecond() {
        return this.battleTicksPerSecond;
    }

    public float renderPartialTick() {
        if (this.renderWindowSteps <= 0) {
            return 0.0F;
        }
        double elapsedSeconds = (System.nanoTime() - this.renderWindowStartedAtNanos) / 1_000_000_000.0D;
        double expectedSeconds = this.renderWindowSteps * this.secondsPerBattleStep;
        if (expectedSeconds <= 0.0D) {
            return 0.0F;
        }
        return (float) Math.max(0.0D, Math.min(1.0D, elapsedSeconds / expectedSeconds));
    }

    public boolean submitSample(
            ActorRef actor,
            TrackSource source,
            InterpolatedField field,
            double displayTime,
            InterpolationAlgorithm algorithm,
            InterpolatedValue value
    ) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(algorithm, "algorithm");
        Objects.requireNonNull(value, "value");
        if (!Double.isFinite(displayTime)) {
            throw new IllegalArgumentException("displayTime must be finite.");
        }
        ActorTrack track = this.tracks.computeIfAbsent(actor, ignored -> new ActorTrack(source));
        if (track.source() != source) {
            return false;
        }
        return track.submit(field, displayTime, algorithm, value);
    }

    public Optional<InterpolatedValue> sample(ActorRef actor, InterpolatedField field, double displayTime) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(field, "field");
        if (!Double.isFinite(displayTime)) {
            throw new IllegalArgumentException("displayTime must be finite.");
        }
        ActorTrack track = this.tracks.get(actor);
        if (track == null) {
            return Optional.empty();
        }
        return track.sample(field, displayTime);
    }

    public Optional<InterpolatedValue> sampleNow(ActorRef actor, InterpolatedField field) {
        return sample(actor, field, renderBattleTime());
    }

    public Optional<RenderTrackDiagnostics> diagnostics(ActorRef actor, InterpolatedField field) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(field, "field");
        ActorTrack track = this.tracks.get(actor);
        return track == null ? Optional.empty() : track.diagnostics(actor, field);
    }

    public void removeTrack(ActorRef actor) {
        this.tracks.remove(Objects.requireNonNull(actor, "actor"));
    }

    public void clear() {
        this.tracks.clear();
    }
}

final class ActorTrack {
    private final TrackSource source;
    private final EnumMap<InterpolatedField, FieldTrack> fields = new EnumMap<>(InterpolatedField.class);

    ActorTrack(TrackSource source) {
        this.source = Objects.requireNonNull(source, "source");
    }

    TrackSource source() {
        return this.source;
    }

    boolean submit(InterpolatedField field, double displayTime, InterpolationAlgorithm algorithm, InterpolatedValue value) {
        return this.fields
                .computeIfAbsent(field, ignored -> new FieldTrack(algorithm))
                .submit(displayTime, algorithm, value);
    }

    Optional<InterpolatedValue> sample(InterpolatedField field, double displayTime) {
        FieldTrack track = this.fields.get(field);
        return track == null ? Optional.empty() : track.sample(displayTime);
    }

    Optional<RenderTrackDiagnostics> diagnostics(ActorRef actor, InterpolatedField field) {
        FieldTrack track = this.fields.get(field);
        return track == null ? Optional.empty() : Optional.of(track.diagnostics(actor, field, this.source));
    }
}

final class FieldTrack {
    private final InterpolationAlgorithm algorithm;
    private final java.util.List<Sample> samples = new java.util.ArrayList<>(VisualConfig.REMOTE_TRACK_MAX_SAMPLES());
    private double latestRenderedDisplayTime = Double.NEGATIVE_INFINITY;

    FieldTrack(InterpolationAlgorithm algorithm) {
        this.algorithm = Objects.requireNonNull(algorithm, "algorithm");
    }

    boolean submit(double displayTime, InterpolationAlgorithm algorithm, InterpolatedValue value) {
        if (this.algorithm != algorithm) {
            return false;
        }
        if (displayTime <= this.latestRenderedDisplayTime) {
            return false;
        }

        Sample sample = new Sample(displayTime, value);
        int index = 0;
        while (index < this.samples.size() && this.samples.get(index).displayTime() < displayTime) {
            index++;
        }
        if (index < this.samples.size() && Double.compare(this.samples.get(index).displayTime(), displayTime) == 0) {
            this.samples.set(index, sample);
        } else {
            this.samples.add(index, sample);
        }
        while (this.samples.size() > VisualConfig.REMOTE_TRACK_MAX_SAMPLES()) {
            this.samples.removeFirst();
        }
        return true;
    }

    Optional<InterpolatedValue> sample(double displayTime) {
        if (this.samples.isEmpty()) {
            return Optional.empty();
        }
        this.latestRenderedDisplayTime = Math.max(this.latestRenderedDisplayTime, displayTime);

        Sample first = this.samples.getFirst();
        if (displayTime < first.displayTime()) {
            return Optional.empty();
        }

        Sample previous = first;
        Sample next = null;
        for (Sample sample : this.samples) {
            if (sample.displayTime() <= displayTime) {
                previous = sample;
                continue;
            }
            next = sample;
            break;
        }
        if (next == null || previous == next) {
            return Optional.of(previous.value());
        }

        double span = next.displayTime() - previous.displayTime();
        if (span <= 0.0D) {
            return Optional.of(previous.value());
        }
        double t = Math.max(0.0D, Math.min(1.0D, (displayTime - previous.displayTime()) / span));
        return Optional.of(interpolate(previous.value(), next.value(), t));
    }

    RenderTrackDiagnostics diagnostics(ActorRef actor, InterpolatedField field, TrackSource source) {
        double oldest = this.samples.isEmpty() ? Double.NaN : this.samples.getFirst().displayTime();
        double latest = this.samples.isEmpty() ? Double.NaN : this.samples.getLast().displayTime();
        double rendered = this.latestRenderedDisplayTime == Double.NEGATIVE_INFINITY
                ? Double.NaN
                : this.latestRenderedDisplayTime;
        return new RenderTrackDiagnostics(
                actor,
                field,
                source,
                this.algorithm,
                this.samples.size(),
                oldest,
                latest,
                rendered,
                this.samples.size() < 2
        );
    }

    private InterpolatedValue interpolate(InterpolatedValue from, InterpolatedValue to, double t) {
        return switch (this.algorithm) {
            case VECTOR_LERP -> vectorLerp(from, to, t);
            case ANGLE_SHORTEST_PATH_LERP -> angleLerp(from, to, t);
            case STEP_HOLD -> from;
        };
    }

    private static InterpolatedValue vectorLerp(InterpolatedValue from, InterpolatedValue to, double t) {
        if (!(from instanceof InterpolatedValue.VectorValue fromVector)
                || !(to instanceof InterpolatedValue.VectorValue toVector)) {
            return from;
        }
        return new InterpolatedValue.VectorValue(fromVector.value().lerp(toVector.value(), t));
    }

    private static InterpolatedValue angleLerp(InterpolatedValue from, InterpolatedValue to, double t) {
        if (!(from instanceof InterpolatedValue.DoubleValue fromDouble)
                || !(to instanceof InterpolatedValue.DoubleValue toDouble)) {
            return from;
        }
        double delta = wrapDegrees(toDouble.value() - fromDouble.value());
        return new InterpolatedValue.DoubleValue(fromDouble.value() + delta * t);
    }

    private static double wrapDegrees(double degrees) {
        double wrapped = degrees % 360.0D;
        if (wrapped >= 180.0D) {
            wrapped -= 360.0D;
        }
        if (wrapped < -180.0D) {
            wrapped += 360.0D;
        }
        return wrapped;
    }

    private record Sample(double displayTime, InterpolatedValue value) {
        private Sample {
            Objects.requireNonNull(value, "value");
        }
    }
}
