package cn.jehorstudio.minetale.battle.logic.actor.states;

import cn.jehorstudio.minetale.battle.logic.actor.Actor;
import cn.jehorstudio.minetale.battle.logic.actor.ActorLifecycle;
import cn.jehorstudio.minetale.battle.logic.actor.ActorRef;
import cn.jehorstudio.minetale.battle.logic.actor.ActorType;
import cn.jehorstudio.minetale.battle.logic.actor.VisualRef;
import cn.jehorstudio.minetale.battle.network.NetworkObjectId;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class ActorsStatesCache {
    private final Map<ActorType, SlotTable> actorsByType = new EnumMap<>(ActorType.class);

    public ActorsStatesCache() {
        for (ActorType type : ActorType.values()) {
            this.actorsByType.put(type, new SlotTable(type));
        }
    }

    public Actor createActor(ActorType type) {
        if (type == ActorType.NETWORK_PROXY) {
            throw new IllegalArgumentException("Use createNetworkProxyActor to create NETWORK_PROXY actors.");
        }
        return table(type).createActor();
    }

    public Actor createNetworkProxyActor(
            NetworkObjectId networkObjectId,
            UUID ownerPlayerId,
            ActorType proxyType,
            VisualRef visual,
            ActorLifecycle lifecycle
    ) {
        return table(ActorType.NETWORK_PROXY).createNetworkProxyActor(
                networkObjectId,
                ownerPlayerId,
                proxyType,
                visual,
                lifecycle
        );
    }

    public Optional<Actor> resolve(ActorRef ref) {
        Objects.requireNonNull(ref, "ref");
        return table(ref.type()).resolve(ref);
    }

    public List<Actor> actors(ActorType type) {
        return table(type).actors();
    }

    public List<Actor> activeActors(ActorType type) {
        return table(type).activeActors();
    }

    public List<Actor> activeLogicActors() {
        return activeActorsByParticipation(ParticipationSelector.LOGIC);
    }

    public List<Actor> activeCollisionActors() {
        return activeActorsByParticipation(ParticipationSelector.COLLISION);
    }

    public List<Actor> activeRenderableActors() {
        return activeActorsByParticipation(ParticipationSelector.RENDERABLE);
    }

    public void cleanupRemovedActors() {
        for (SlotTable table : this.actorsByType.values()) {
            table.cleanupRemovedActors();
        }
    }

    public ActorsStatesSnapshot snapshot() {
        EnumMap<ActorType, List<ActorSingleSnapshot>> snapshotsByType = new EnumMap<>(ActorType.class);
        for (Map.Entry<ActorType, SlotTable> entry : this.actorsByType.entrySet()) {
            snapshotsByType.put(entry.getKey(), entry.getValue().snapshot());
        }
        return new ActorsStatesSnapshot(snapshotsByType);
    }

    private SlotTable table(ActorType type) {
        return this.actorsByType.get(Objects.requireNonNull(type, "type"));
    }

    private List<Actor> activeActorsByParticipation(ParticipationSelector selector) {
        List<Actor> result = new ArrayList<>();
        for (Map.Entry<ActorType, SlotTable> entry : this.actorsByType.entrySet()) {
            if (!selector.includes(entry.getKey())) {
                continue;
            }
            result.addAll(entry.getValue().activeActors());
        }
        return List.copyOf(result);
    }

    private enum ParticipationSelector {
        LOGIC {
            @Override
            boolean includes(ActorType type) {
                return type.participation().logicParticipant();
            }
        },
        COLLISION {
            @Override
            boolean includes(ActorType type) {
                return type.participation().collisionParticipant();
            }
        },
        RENDERABLE {
            @Override
            boolean includes(ActorType type) {
                return type.participation().renderable();
            }
        };

        abstract boolean includes(ActorType type);
    }

    private static final class SlotTable {
        private final ActorType type;
        private final List<Actor> slots = new ArrayList<>();
        private final List<Integer> generations = new ArrayList<>();
        private final ArrayDeque<Integer> reusableIndexes = new ArrayDeque<>();

        private SlotTable(ActorType type) {
            this.type = type;
        }

        private Actor createActor() {
            if (this.type == ActorType.NETWORK_PROXY) {
                throw new IllegalArgumentException("Use createNetworkProxyActor to create NETWORK_PROXY actors.");
            }
            return createActor(allocateRef());
        }

        private Actor createNetworkProxyActor(
                NetworkObjectId networkObjectId,
                UUID ownerPlayerId,
                ActorType proxyType,
                VisualRef visual,
                ActorLifecycle lifecycle
        ) {
            if (this.type != ActorType.NETWORK_PROXY) {
                throw new IllegalStateException("Network proxy actors must be created in the NETWORK_PROXY slot table.");
            }
            validateNetworkProxyArgs(networkObjectId, ownerPlayerId, proxyType, visual, lifecycle);
            return createActor(Actor.createNetworkProxy(
                    allocateRef(),
                    networkObjectId,
                    ownerPlayerId,
                    proxyType,
                    visual,
                    lifecycle
            ));
        }

        private static void validateNetworkProxyArgs(
                NetworkObjectId networkObjectId,
                UUID ownerPlayerId,
                ActorType proxyType,
                VisualRef visual,
                ActorLifecycle lifecycle
        ) {
            Objects.requireNonNull(networkObjectId, "networkObjectId");
            Objects.requireNonNull(ownerPlayerId, "ownerPlayerId");
            Objects.requireNonNull(proxyType, "proxyType");
            Objects.requireNonNull(visual, "visual");
            Objects.requireNonNull(lifecycle, "lifecycle");
            if (proxyType == ActorType.NETWORK_PROXY) {
                throw new IllegalArgumentException("proxyType cannot be NETWORK_PROXY.");
            }
        }

        private ActorRef allocateRef() {
            int index;
            int generation;
            if (this.reusableIndexes.isEmpty()) {
                index = this.slots.size();
                generation = 0;
                this.slots.add(null);
                this.generations.add(generation);
            } else {
                index = this.reusableIndexes.removeFirst();
                generation = this.generations.get(index) + 1;
                this.generations.set(index, generation);
            }
            return ActorRef.of(this.type, index, generation);
        }

        private Actor createActor(ActorRef ref) {
            Actor actor = new Actor(ref);
            return createActor(actor);
        }

        private Actor createActor(Actor actor) {
            this.slots.set(actor.ref().index(), actor);
            return actor;
        }

        private Optional<Actor> resolve(ActorRef ref) {
            int index = ref.index();
            if (index >= this.slots.size()) {
                return Optional.empty();
            }

            Actor actor = this.slots.get(index);
            if (actor == null || !actor.ref().equals(ref)) {
                return Optional.empty();
            }

            return Optional.of(actor);
        }

        private List<Actor> actors() {
            List<Actor> result = new ArrayList<>();
            for (Actor actor : this.slots) {
                if (actor != null) {
                    result.add(actor);
                }
            }
            return List.copyOf(result);
        }

        private List<Actor> activeActors() {
            List<Actor> result = new ArrayList<>();
            for (Actor actor : this.slots) {
                if (actor != null && actor.active()) {
                    result.add(actor);
                }
            }
            return List.copyOf(result);
        }

        private void cleanupRemovedActors() {
            for (int i = 0; i < this.slots.size(); i++) {
                Actor actor = this.slots.get(i);
                if (actor != null && actor.lifecycle() == ActorLifecycle.REMOVED) {
                    this.slots.set(i, null);
                    this.reusableIndexes.addLast(i);
                }
            }
        }

        private List<ActorSingleSnapshot> snapshot() {
            List<ActorSingleSnapshot> snapshots = new ArrayList<>();
            for (Actor actor : this.slots) {
                if (actor != null) {
                    snapshots.add(actor.snapshot());
                }
            }
            return List.copyOf(snapshots);
        }
    }
}
