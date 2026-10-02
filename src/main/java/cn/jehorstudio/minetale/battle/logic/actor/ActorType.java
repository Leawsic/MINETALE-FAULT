package cn.jehorstudio.minetale.battle.logic.actor;

// 仅表示运行时存储形态；数据驱动的玩法类别由 ActorKindRef 表示，不应扩充此 enum。
public enum ActorType {
    BATTLE_BOX(ActorParticipation.LOCAL_LOGIC),
    PLAYER_SOUL(ActorParticipation.LOCAL_LOGIC),
    BULLET(ActorParticipation.LOCAL_LOGIC),
    SCRIPTED_ACTOR(ActorParticipation.LOCAL_LOGIC),
    NETWORK_PROXY(ActorParticipation.NETWORK_PROXY);

    private final ActorParticipation participation;

    ActorType(ActorParticipation participation) {
        this.participation = participation;
    }

    public ActorParticipation participation() {
        return this.participation;
    }
}
