package cn.jehorstudio.minetale.battle.logic.action;

public enum BattleRenderRequestType {
    SCENE_TRANSITION(BattleRenderRequestPayload.SceneTransition.class),
    SCREEN_SHAKE(BattleRenderRequestPayload.ScreenShake.class),
    SCREEN_FLASH(BattleRenderRequestPayload.ScreenFlash.class),
    ENVIRONMENT_BACKGROUND_OPACITY(BattleRenderRequestPayload.EnvironmentBackgroundOpacity.class),
    FOREGROUND_OVERLAY(BattleRenderRequestPayload.ForegroundOverlay.class);

    private final Class<? extends BattleRenderRequestPayload> payloadType;

    BattleRenderRequestType(Class<? extends BattleRenderRequestPayload> payloadType) {
        this.payloadType = payloadType;
    }

    public boolean accepts(BattleRenderRequestPayload payload) {
        return this.payloadType.isInstance(payload);
    }
}
