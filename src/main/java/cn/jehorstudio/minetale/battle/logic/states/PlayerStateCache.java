package cn.jehorstudio.minetale.battle.logic.states;

import cn.jehorstudio.minetale.battle.logic.actor.ActorRef;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class PlayerStateCache {
    public static final UUID DEBUG_LOCAL_PLAYER_ID = new UUID(0L, 1L);

    private UUID playerId;
    private ActorRef soulRef;
    private int hp = 20;
    private int maxHp = 20;
    private int level = 1;
    private boolean invincible;
    private double invincibleTimeSeconds = 1.0D;
    private long invincibleStartedAtBattleTick;
    private List<String> equipmentIds = List.of();
    private List<String> itemIds = List.of();

    public void initializeLocalPlayer(UUID playerId, ActorRef soulRef) {
        this.playerId = Objects.requireNonNull(playerId, "playerId");
        this.soulRef = Objects.requireNonNull(soulRef, "soulRef");
    }

    public void applyInitialSnapshot(PlayerStateSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        initializeLocalPlayer(snapshot.playerId(), snapshot.soulRef());
        this.hp = snapshot.hp();
        this.maxHp = snapshot.maxHp();
        this.level = snapshot.level();
        this.invincible = snapshot.invincible();
        this.invincibleTimeSeconds = snapshot.invincibleTimeSeconds();
        this.invincibleStartedAtBattleTick = snapshot.invincibleStartedAtBattleTick();
        this.equipmentIds = List.copyOf(snapshot.equipmentIds());
        this.itemIds = List.copyOf(snapshot.itemIds());
    }

    public boolean initialized() {
        return this.playerId != null && this.soulRef != null;
    }

    public UUID playerId() {
        requireInitialized();
        return this.playerId;
    }

    public ActorRef soulRef() {
        requireInitialized();
        return this.soulRef;
    }

    public boolean matchesSoul(ActorRef soulRef) {
        Objects.requireNonNull(soulRef, "soulRef");
        return initialized() && this.soulRef.equals(soulRef);
    }

    public int hp() {
        return this.hp;
    }

    public void setHp(int hp) {
        this.hp = Math.max(0, Math.min(this.maxHp, hp));
    }

    public int maxHp() {
        return this.maxHp;
    }

    public void setMaxHp(int maxHp) {
        this.maxHp = Math.max(1, maxHp);
        this.hp = Math.min(this.hp, this.maxHp);
    }

    public int level() {
        return this.level;
    }

    public void setLevel(int level) {
        this.level = Math.max(1, level);
    }

    public boolean invincible() {
        return this.invincible;
    }

    public void setInvincible(boolean invincible) {
        this.invincible = invincible;
    }

    public double invincibleTimeSeconds() {
        return this.invincibleTimeSeconds;
    }

    public void setInvincibleTimeSeconds(double invincibleTimeSeconds) {
        if (!Double.isFinite(invincibleTimeSeconds)) {
            throw new IllegalArgumentException("invincibleTimeSeconds must be finite.");
        }
        this.invincibleTimeSeconds = Math.max(0.0D, invincibleTimeSeconds);
    }

    public long invincibleStartedAtBattleTick() {
        return this.invincibleStartedAtBattleTick;
    }

    public void setInvincibleStartedAtBattleTick(long invincibleStartedAtBattleTick) {
        this.invincibleStartedAtBattleTick = Math.max(0L, invincibleStartedAtBattleTick);
    }

    public List<String> equipmentIds() {
        return this.equipmentIds;
    }

    public void setEquipmentIds(List<String> equipmentIds) {
        this.equipmentIds = List.copyOf(equipmentIds);
    }

    public List<String> itemIds() {
        return this.itemIds;
    }

    public void setItemIds(List<String> itemIds) {
        this.itemIds = List.copyOf(itemIds);
    }

    public Optional<PlayerStateSnapshot> snapshot() {
        if (!initialized()) {
            return Optional.empty();
        }
        return Optional.of(new PlayerStateSnapshot(
                this.playerId,
                this.soulRef,
                this.hp,
                this.maxHp,
                this.level,
                this.invincible,
                this.invincibleTimeSeconds,
                this.invincibleStartedAtBattleTick,
                this.equipmentIds,
                this.itemIds
        ));
    }

    private void requireInitialized() {
        if (!initialized()) {
            throw new IllegalStateException("PlayerStateCache has not been initialized.");
        }
    }
}
