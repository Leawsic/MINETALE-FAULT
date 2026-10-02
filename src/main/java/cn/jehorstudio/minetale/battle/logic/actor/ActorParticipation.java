package cn.jehorstudio.minetale.battle.logic.actor;

public enum ActorParticipation {
    LOCAL_LOGIC(true, true, true),
    NETWORK_PROXY(false, false, true);

    private final boolean logicParticipant;
    private final boolean collisionParticipant;
    private final boolean renderable;

    ActorParticipation(boolean logicParticipant, boolean collisionParticipant, boolean renderable) {
        this.logicParticipant = logicParticipant;
        this.collisionParticipant = collisionParticipant;
        this.renderable = renderable;
    }

    public boolean logicParticipant() {
        return this.logicParticipant;
    }

    public boolean collisionParticipant() {
        return this.collisionParticipant;
    }

    public boolean renderable() {
        return this.renderable;
    }
}
