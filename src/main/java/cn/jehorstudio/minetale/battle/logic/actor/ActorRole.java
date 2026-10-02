package cn.jehorstudio.minetale.battle.logic.actor;

import java.util.Locale;

// HYBRID 同时参与玩法空间与表现布局，但两套坐标职责仍由各自系统处理。
public enum ActorRole {
    GAMEPLAY(true, false),
    PRESENTATION(false, true),
    HYBRID(true, true);

    private final boolean gameplayBody;
    private final boolean presentationLayout;

    ActorRole(boolean gameplayBody, boolean presentationLayout) {
        this.gameplayBody = gameplayBody;
        this.presentationLayout = presentationLayout;
    }

    public boolean gameplayBody() {
        return this.gameplayBody;
    }

    public boolean presentationLayout() {
        return this.presentationLayout;
    }

    public static ActorRole parse(String id) {
        return valueOf(id.toUpperCase(Locale.ROOT));
    }
}
