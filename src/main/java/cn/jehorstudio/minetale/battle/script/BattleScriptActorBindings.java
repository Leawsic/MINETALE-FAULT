package cn.jehorstudio.minetale.battle.script;

import cn.jehorstudio.minetale.battle.logic.actor.ActorRef;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class BattleScriptActorBindings {
    private final Map<String, ActorRef> actorsById = new LinkedHashMap<>();
    private final Map<String, Set<ActorRef>> actorsByTag = new LinkedHashMap<>();

    public void register(ActorRef ref, Optional<String> scriptId, List<String> tags) {
        Objects.requireNonNull(ref, "ref");
        Objects.requireNonNull(scriptId, "scriptId");
        Objects.requireNonNull(tags, "tags");
        scriptId.ifPresent(id -> this.actorsById.put(id, ref));
        for (String tag : tags) {
            this.actorsByTag.computeIfAbsent(tag, ignored -> new LinkedHashSet<>()).add(ref);
        }
    }

    public Optional<ActorRef> byId(String id) {
        return Optional.ofNullable(this.actorsById.get(Objects.requireNonNull(id, "id")));
    }

    public List<ActorRef> byTag(String tag) {
        Set<ActorRef> refs = this.actorsByTag.get(Objects.requireNonNull(tag, "tag"));
        if (refs == null) {
            return List.of();
        }
        return List.copyOf(refs);
    }

    public List<ActorRef> resolveTarget(JsonObjectLike target) {
        if (target.hasString("id")) {
            return byId(target.string("id")).map(List::of).orElseGet(List::of);
        }
        if (target.hasString("tag")) {
            return byTag(target.string("tag"));
        }
        return List.of();
    }

    public int tagCount(String tag) {
        return byTag(tag).size();
    }

    public void remove(ActorRef ref) {
        Objects.requireNonNull(ref, "ref");
        this.actorsById.values().removeIf(ref::equals);
        for (Set<ActorRef> refs : this.actorsByTag.values()) {
            refs.remove(ref);
        }
        this.actorsByTag.values().removeIf(Set::isEmpty);
    }

    public void removeTag(String tag) {
        this.actorsByTag.remove(Objects.requireNonNull(tag, "tag"));
    }

    public interface JsonObjectLike {
        boolean hasString(String member);

        String string(String member);
    }
}
