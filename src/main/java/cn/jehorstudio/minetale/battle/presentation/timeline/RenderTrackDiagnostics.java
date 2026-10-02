package cn.jehorstudio.minetale.battle.presentation.timeline;

import cn.jehorstudio.minetale.battle.logic.actor.ActorRef;

public record RenderTrackDiagnostics(
        ActorRef actor,
        InterpolatedField field,
        TrackSource source,
        InterpolationAlgorithm algorithm,
        int sampleCount,
        double oldestSampleDisplayTime,
        double latestSampleDisplayTime,
        double latestRenderedDisplayTime,
        boolean bufferUnderfilled
) {
    public RenderTrackDiagnostics {
        if (sampleCount < 0) {
            throw new IllegalArgumentException("sampleCount must be >= 0.");
        }
    }
}
