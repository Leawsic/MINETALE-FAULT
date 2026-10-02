package cn.jehorstudio.minetale.content.player.soul;

import cn.jehorstudio.minetale.content.player.soul.Soul.State;
import cn.jehorstudio.minetale.content.player.soul.SoulFlight.FrameState;
import cn.jehorstudio.minetale.content.player.soul.SoulFlight.Gesture;
import cn.jehorstudio.minetale.content.player.soul.SoulFlight.GestureProgram;
import cn.jehorstudio.minetale.content.player.soul.SoulFlight.KinematicState;
import cn.jehorstudio.minetale.content.player.soul.SoulFlight.ReferenceFrame;
import cn.jehorstudio.minetale.content.player.soul.SoulActionController.ActionFrame;
import cn.jehorstudio.minetale.content.player.soul.SoulActionController.Kind;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityReference;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.InterpolationHandler;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

// 不可见的交互代理：服务端维护位置并低频同步动作事实，客户端另行持有可见 Transform。
public final class SoulEntity extends Entity {
    public static final int NETWORK_UPDATE_INTERVAL_TICKS = 5;
    private static final String OWNER_TAG = "Owner";
    private static final String STATE_TAG = "SoulState";

    private static final EntityDataAccessor<Optional<EntityReference<LivingEntity>>> DATA_OWNER =
            SynchedEntityData.defineId(SoulEntity.class, EntityDataSerializers.OPTIONAL_LIVING_ENTITY_REFERENCE);
    private static final EntityDataAccessor<Integer> DATA_STATE =
            SynchedEntityData.defineId(SoulEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Long> DATA_PROGRAM_REVISION =
            SynchedEntityData.defineId(SoulEntity.class, EntityDataSerializers.LONG);
    private static final EntityDataAccessor<Integer> DATA_GESTURE =
            SynchedEntityData.defineId(SoulEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Long> DATA_PROGRAM_START_TICK =
            SynchedEntityData.defineId(SoulEntity.class, EntityDataSerializers.LONG);
    private static final EntityDataAccessor<Float> DATA_PROGRAM_DURATION =
            SynchedEntityData.defineId(SoulEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Integer> DATA_REFERENCE_FRAME =
            SynchedEntityData.defineId(SoulEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Vector3f> DATA_START_POSITION =
            SynchedEntityData.defineId(SoulEntity.class, EntityDataSerializers.VECTOR3);
    private static final EntityDataAccessor<Vector3f> DATA_START_VELOCITY =
            SynchedEntityData.defineId(SoulEntity.class, EntityDataSerializers.VECTOR3);
    private static final EntityDataAccessor<Vector3f> DATA_START_ACCELERATION =
            SynchedEntityData.defineId(SoulEntity.class, EntityDataSerializers.VECTOR3);
    private static final EntityDataAccessor<Vector3f> DATA_TERMINAL_POSITION =
            SynchedEntityData.defineId(SoulEntity.class, EntityDataSerializers.VECTOR3);
    private static final EntityDataAccessor<Vector3f> DATA_TERMINAL_VELOCITY =
            SynchedEntityData.defineId(SoulEntity.class, EntityDataSerializers.VECTOR3);
    private static final EntityDataAccessor<Vector3f> DATA_TERMINAL_ACCELERATION =
            SynchedEntityData.defineId(SoulEntity.class, EntityDataSerializers.VECTOR3);
    private static final EntityDataAccessor<Vector3f> DATA_SPIRAL_AXIS =
            SynchedEntityData.defineId(SoulEntity.class, EntityDataSerializers.VECTOR3);
    private static final EntityDataAccessor<Vector3f> DATA_PROGRAM_PARAMETERS =
            SynchedEntityData.defineId(SoulEntity.class, EntityDataSerializers.VECTOR3);
    private static final EntityDataAccessor<Boolean> DATA_USES_OWNER_RENDER_VELOCITY =
            SynchedEntityData.defineId(SoulEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Vector3f> DATA_COMPANION_VELOCITY =
            SynchedEntityData.defineId(SoulEntity.class, EntityDataSerializers.VECTOR3);
    private static final EntityDataAccessor<Vector3f> DATA_COMPANION_TARGET =
            SynchedEntityData.defineId(SoulEntity.class, EntityDataSerializers.VECTOR3);

    private final InterpolationHandler interpolation = new InterpolationHandler(
            this,
            NETWORK_UPDATE_INTERVAL_TICKS
    );
    private long cachedProgramRevision = Long.MIN_VALUE;
    @Nullable
    private GestureProgram cachedProgram;
    private long clientThrowProgramRevision = Long.MIN_VALUE;
    @Nullable
    private GestureProgram clientThrowProgram;

    public SoulEntity(EntityType<? extends SoulEntity> entityType, Level level) {
        super(entityType, level);
        this.noPhysics = true;
        this.setNoGravity(true);
        this.setInvulnerable(true);
        this.setInvisible(true);
    }

    void initialize(ServerPlayer owner, State state, Vec3 position, Vec3 velocity) {
        this.entityData.set(DATA_OWNER, Optional.of(EntityReference.<LivingEntity>of(owner)));
        this.setSoulState(state);
        this.setPos(position);
        this.setOldPosAndRot();
        this.setDeltaMovement(velocity);
        this.entityData.set(DATA_COMPANION_TARGET, vector(position));
        this.entityData.set(DATA_COMPANION_VELOCITY, vector(velocity));
    }

    @Nullable
    UUID ownerUuid() {
        return this.entityData.get(DATA_OWNER).map(EntityReference::getUUID).orElse(null);
    }

    State soulState() {
        return enumValue(State.values(), this.entityData.get(DATA_STATE), State.FLYING);
    }

    void setSoulState(State state) {
        this.entityData.set(DATA_STATE, state.ordinal());
    }

    // 先写完整手势字段，最后递增 revision，使客户端只重建一次完整指令。
    void installGestureProgram(GestureProgram program) {
        if (this.level().isClientSide()) {
            return;
        }
        this.entityData.set(DATA_PROGRAM_START_TICK, program.startTick());
        this.entityData.set(DATA_PROGRAM_DURATION, (float) program.durationTicks());
        this.entityData.set(DATA_REFERENCE_FRAME, program.referenceFrame().ordinal());
        this.entityData.set(DATA_START_POSITION, vector(program.start().position()));
        this.entityData.set(DATA_START_VELOCITY, vector(program.start().velocity()));
        this.entityData.set(DATA_START_ACCELERATION, vector(program.start().acceleration()));
        this.entityData.set(DATA_TERMINAL_POSITION, vector(program.terminal().position()));
        this.entityData.set(DATA_TERMINAL_VELOCITY, vector(program.terminal().velocity()));
        this.entityData.set(DATA_TERMINAL_ACCELERATION, vector(program.terminal().acceleration()));
        this.entityData.set(DATA_SPIRAL_AXIS, vector(program.spiralAxis()));
        this.entityData.set(DATA_PROGRAM_PARAMETERS, new Vector3f(
                (float) program.spiralRadius(),
                (float) program.spiralTurns(),
                (float) program.rollRadiansPerBlock()
        ));
        this.entityData.set(DATA_USES_OWNER_RENDER_VELOCITY, program.usesOwnerRenderVelocity());
        this.entityData.set(DATA_GESTURE, program.gesture().ordinal());
        this.entityData.set(DATA_PROGRAM_REVISION, program.planEpoch());
        this.cachedProgramRevision = program.planEpoch();
        this.cachedProgram = program;
    }

    void clearGestureProgram(long revision) {
        if (this.level().isClientSide()) {
            return;
        }
        this.entityData.set(DATA_GESTURE, -1);
        this.entityData.set(DATA_PROGRAM_REVISION, revision);
        this.cachedProgramRevision = revision;
        this.cachedProgram = null;
    }

    @Nullable
    GestureProgram gestureProgram() {
        long revision = this.entityData.get(DATA_PROGRAM_REVISION);
        int gestureOrdinal = this.entityData.get(DATA_GESTURE);
        if (gestureOrdinal < 0 || gestureOrdinal >= Gesture.values().length) {
            this.cachedProgramRevision = revision;
            this.cachedProgram = null;
            return null;
        }
        if (this.cachedProgram != null && this.cachedProgramRevision == revision) {
            return this.cachedProgram;
        }
        Vector3f parameters = this.entityData.get(DATA_PROGRAM_PARAMETERS);
        this.cachedProgram = new GestureProgram(
                revision,
                this.entityData.get(DATA_PROGRAM_START_TICK),
                this.entityData.get(DATA_PROGRAM_DURATION),
                Gesture.values()[gestureOrdinal],
                enumValue(
                        ReferenceFrame.values(),
                        this.entityData.get(DATA_REFERENCE_FRAME),
                        ReferenceFrame.WORLD
                ),
                new KinematicState(
                        vector(this.entityData.get(DATA_START_POSITION)),
                        vector(this.entityData.get(DATA_START_VELOCITY)),
                        vector(this.entityData.get(DATA_START_ACCELERATION))
                ),
                new KinematicState(
                        vector(this.entityData.get(DATA_TERMINAL_POSITION)),
                        vector(this.entityData.get(DATA_TERMINAL_VELOCITY)),
                        vector(this.entityData.get(DATA_TERMINAL_ACCELERATION))
                ),
                vector(this.entityData.get(DATA_SPIRAL_AXIS)),
                parameters.x(),
                parameters.y(),
                parameters.z(),
                this.entityData.get(DATA_USES_OWNER_RENDER_VELOCITY)
        );
        this.cachedProgramRevision = revision;
        return this.cachedProgram;
    }

    // Entity Transform 只表示服务端交互代理，不是客户端可见 Soul Transform。
    void applyProxySample(State state, KinematicState sample) {
        this.setSoulState(state);
        this.setDeltaMovement(sample.velocity());
        this.setPos(sample.position());
        if (!this.level().isClientSide()
                && (this.tickCount <= 1 || this.tickCount % NETWORK_UPDATE_INTERVAL_TICKS == 0)) {
            this.entityData.set(DATA_COMPANION_TARGET, vector(sample.position()));
            this.entityData.set(DATA_COMPANION_VELOCITY, vector(sample.velocity()));
        }
    }

    // 仅向客户端 Action Controller 暴露动作事实
    ActionFrame visualActionFrame(double sampleTick, float partialTick) {
        KinematicState projectionHint = new KinematicState(
                vector(this.entityData.get(DATA_COMPANION_TARGET)),
                vector(this.entityData.get(DATA_COMPANION_VELOCITY)),
                Vec3.ZERO
        );
        GestureProgram program = gestureProgram();
        LivingEntity owner = owner();
        if (program == null
                || (program.referenceFrame() == ReferenceFrame.OWNER_TRANSLATION && owner == null)) {
            return ActionFrame.companion(projectionHint);
        }
        if (program.gesture() == Gesture.THROW
                && program.usesOwnerRenderVelocity()
                && owner != null) {
            program = clientThrowProgram(program, owner);
        }
        FrameState frame = program.referenceFrame() == ReferenceFrame.WORLD
                ? FrameState.WORLD
                : ownerFrame(owner, partialTick);
        double elapsed = sampleTick - program.startTick();
        return ActionFrame.gesture(
                switch (program.gesture()) {
                    case THROW -> Kind.THROW;
                    case RETURN -> Kind.RETURN;
                    case REJECTED_BOUNCE -> Kind.REJECTED_BOUNCE;
                },
                program.gesture() == Gesture.THROW ? program.start() : projectionHint,
                program.guidanceTargetAtElapsed(elapsed, frame),
                program.spiralAxis(),
                program.rollRadiansPerBlock()
        );
    }

    private GestureProgram clientThrowProgram(GestureProgram synchronizedProgram, LivingEntity owner) {
        if (this.clientThrowProgram == null
                || this.clientThrowProgramRevision != synchronizedProgram.planEpoch()) {
            Vec3 inheritedVelocity = renderInterpolationVelocity(
                    owner.getPosition(0.0F),
                    owner.getPosition(1.0F)
            );
            this.clientThrowProgram = synchronizedProgram.withThrowInheritedVelocity(inheritedVelocity);
            this.clientThrowProgramRevision = synchronizedProgram.planEpoch();
        }
        return this.clientThrowProgram;
    }

    static Vec3 renderInterpolationVelocity(Vec3 previousEndpoint, Vec3 currentEndpoint) {
        return currentEndpoint.subtract(previousEndpoint);
    }

    @Override
    public void tick() {
        super.tick();
        if (this.level().isClientSide()) {
            this.interpolation.interpolate();
            return;
        }

        LivingEntity owner = owner();
        if (!(owner instanceof ServerPlayer serverPlayer) || serverPlayer.level() != this.level()) {
            this.discard();
            return;
        }
        Soul authoritativeSoul = Soul.get(serverPlayer);
        if (authoritativeSoul.state() == State.ITEM
                || !this.getUUID().equals(authoritativeSoul.entityUuid())) {
            this.discard();
        }
    }

    @Nullable
    private LivingEntity owner() {
        return EntityReference.getLivingEntity(
                this.entityData.get(DATA_OWNER).orElse(null),
                this.level()
        );
    }

    private static FrameState ownerFrame(LivingEntity owner, float partialTick) {
        Vec3 origin = owner instanceof Player player
                ? SoulPresentationAnchors.chestAnchor(player, partialTick)
                : owner.getEyePosition(partialTick);
        return new FrameState(origin, owner.getDeltaMovement(), Vec3.ZERO);
    }

    @Override
    public InterpolationHandler getInterpolation() {
        return this.interpolation;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(DATA_OWNER, Optional.empty());
        builder.define(DATA_STATE, State.FLYING.ordinal());
        builder.define(DATA_PROGRAM_REVISION, 0L);
        builder.define(DATA_GESTURE, -1);
        builder.define(DATA_PROGRAM_START_TICK, 0L);
        builder.define(DATA_PROGRAM_DURATION, 1.0F);
        builder.define(DATA_REFERENCE_FRAME, ReferenceFrame.WORLD.ordinal());
        builder.define(DATA_START_POSITION, new Vector3f());
        builder.define(DATA_START_VELOCITY, new Vector3f());
        builder.define(DATA_START_ACCELERATION, new Vector3f());
        builder.define(DATA_TERMINAL_POSITION, new Vector3f());
        builder.define(DATA_TERMINAL_VELOCITY, new Vector3f());
        builder.define(DATA_TERMINAL_ACCELERATION, new Vector3f());
        builder.define(DATA_SPIRAL_AXIS, new Vector3f(0.0F, 0.0F, 1.0F));
        builder.define(DATA_PROGRAM_PARAMETERS, new Vector3f());
        builder.define(DATA_USES_OWNER_RENDER_VELOCITY, false);
        builder.define(DATA_COMPANION_VELOCITY, new Vector3f());
        builder.define(DATA_COMPANION_TARGET, new Vector3f());
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        EntityReference<LivingEntity> owner = EntityReference.read(input, OWNER_TAG);
        this.entityData.set(DATA_OWNER, Optional.ofNullable(owner));
        this.entityData.set(
                DATA_STATE,
                Mth.clamp(input.getIntOr(STATE_TAG, State.FLYING.ordinal()), 0, State.values().length - 1)
        );
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        EntityReference.store(this.entityData.get(DATA_OWNER).orElse(null), output, OWNER_TAG);
        output.putInt(STATE_TAG, this.entityData.get(DATA_STATE));
    }

    @Override
    public boolean isPickable() {
        return true;
    }

    @Override
    public boolean canBeHitByProjectile() {
        return false;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public boolean skipAttackInteraction(Entity attacker) {
        return true;
    }

    @Override
    public boolean hurtServer(ServerLevel level, DamageSource damageSource, float amount) {
        return false;
    }

    @Override
    public void kill(ServerLevel level) {
    }

    @Override
    public PushReaction getPistonPushReaction() {
        return PushReaction.IGNORE;
    }

    @Override
    public boolean isIgnoringBlockTriggers() {
        return true;
    }

    private static Vector3f vector(Vec3 value) {
        return new Vector3f((float) value.x, (float) value.y, (float) value.z);
    }

    private static Vec3 vector(Vector3f value) {
        return new Vec3(value.x(), value.y(), value.z());
    }

    private static <T> T enumValue(T[] values, int ordinal, T fallback) {
        return ordinal >= 0 && ordinal < values.length ? values[ordinal] : fallback;
    }
}
