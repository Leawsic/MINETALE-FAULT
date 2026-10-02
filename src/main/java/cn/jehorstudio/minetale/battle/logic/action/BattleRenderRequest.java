package cn.jehorstudio.minetale.battle.logic.action;

import java.util.Objects;

public record BattleRenderRequest(
        BattleRenderRequestType type,
        BattleRenderRequestPayload payload
) {
    public BattleRenderRequest {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(payload, "payload");
        if (!type.accepts(payload)) {
            throw new IllegalArgumentException("Render request payload does not match type " + type + ".");
        }
    }

    public static BattleRenderRequest sceneTransition(double durationSeconds) {
        return new BattleRenderRequest(
                BattleRenderRequestType.SCENE_TRANSITION,
                new BattleRenderRequestPayload.SceneTransition(durationSeconds)
        );
    }

    public static BattleRenderRequest screenShake(double durationSeconds, double intensity) {
        return new BattleRenderRequest(
                BattleRenderRequestType.SCREEN_SHAKE,
                new BattleRenderRequestPayload.ScreenShake(durationSeconds, intensity)
        );
    }

    public static BattleRenderRequest screenFlash(double durationSeconds, double intensity, int rgb) {
        return new BattleRenderRequest(
                BattleRenderRequestType.SCREEN_FLASH,
                new BattleRenderRequestPayload.ScreenFlash(durationSeconds, intensity, rgb)
        );
    }

    public static BattleRenderRequest environmentBackgroundOpacity(
            double durationSeconds,
            double opacity
    ) {
        return new BattleRenderRequest(
                BattleRenderRequestType.ENVIRONMENT_BACKGROUND_OPACITY,
                new BattleRenderRequestPayload.EnvironmentBackgroundOpacity(durationSeconds, opacity)
        );
    }

    public static BattleRenderRequest foregroundOverlay(
            double durationSeconds,
            double opacity,
            BattleRenderRequestPayload.ForegroundOverlaySource source,
            int rgb
    ) {
        return new BattleRenderRequest(
                BattleRenderRequestType.FOREGROUND_OVERLAY,
                new BattleRenderRequestPayload.ForegroundOverlay(
                        durationSeconds,
                        opacity,
                        source,
                        rgb
                )
        );
    }

    public <T extends BattleRenderRequestPayload> T payload(Class<T> payloadType) {
        return payloadType.cast(this.payload);
    }

    public double durationSeconds() {
        return this.payload.durationSeconds();
    }
}
