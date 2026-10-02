package cn.jehorstudio.minetale.battle.logic.actor;

import java.util.Objects;

// 保留 BattleScript actors 本地模板名或导入后的全局模板引用，不在此层解析其来源。
public record ActorTemplateRef(String value) {
    public ActorTemplateRef {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("Actor template ref must not be blank.");
        }
    }

    public static ActorTemplateRef of(String value) {
        return new ActorTemplateRef(value);
    }

    @Override
    public String toString() {
        return this.value;
    }
}
