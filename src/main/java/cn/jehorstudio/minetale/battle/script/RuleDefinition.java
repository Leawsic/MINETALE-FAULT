package cn.jehorstudio.minetale.battle.script;

import com.google.gson.JsonObject;

import java.util.Objects;

public record RuleDefinition(
        String domain,
        JsonObject data
) {
    public RuleDefinition {
        Objects.requireNonNull(domain, "domain");
        Objects.requireNonNull(data, "data");
        if (domain.isBlank()) {
            throw new IllegalArgumentException("Rule domain must not be blank.");
        }
        data = data.deepCopy();
    }

    public JsonObject data() {
        return this.data.deepCopy();
    }
}
