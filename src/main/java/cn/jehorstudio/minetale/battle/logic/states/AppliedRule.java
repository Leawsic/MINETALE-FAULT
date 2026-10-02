package cn.jehorstudio.minetale.battle.logic.states;

import com.google.gson.JsonObject;

import java.util.Objects;

public record AppliedRule(
        String source,
        String rulesetId,
        String domain,
        JsonObject data
) {
    public AppliedRule {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(rulesetId, "rulesetId");
        Objects.requireNonNull(domain, "domain");
        Objects.requireNonNull(data, "data");
        if (source.isBlank() || rulesetId.isBlank() || domain.isBlank()) {
            throw new IllegalArgumentException("Applied rule ids must not be blank.");
        }
        data = data.deepCopy();
    }

    public JsonObject data() {
        return this.data.deepCopy();
    }
}
