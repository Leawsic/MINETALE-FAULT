package cn.jehorstudio.minetale.battle.script;

enum ScriptedActorEventType {
    ON_SPAWN("onSpawn"),
    ON_TICK("onTick"),
    ON_COLLIDE_PLAYER("onCollidePlayer"),
    ON_COLLIDE_BATTLE_BOX("onCollideBattleBox"),
    ON_DESTROY("onDestroy"),
    ON_SIGNAL("onSignal"),
    ON_KEY_PRESSED("onKeyPressed");

    private final String jsonKey;

    ScriptedActorEventType(String jsonKey) {
        this.jsonKey = jsonKey;
    }

    String jsonKey() {
        return this.jsonKey;
    }
}
