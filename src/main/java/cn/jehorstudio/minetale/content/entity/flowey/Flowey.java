package cn.jehorstudio.minetale.content.entity.flowey;

import cn.jehorstudio.minetale.MineTale;
import cn.jehorstudio.minetale.narrative.runtime.DialogueSessionManager;
import cn.jehorstudio.minetale.narrative.runtime.DialogueTarget;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jetbrains.annotations.Nullable;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animatable.manager.AnimatableManager;
import software.bernie.geckolib.util.GeckoLibUtil;

public final class Flowey extends Mob implements GeoEntity, DialogueTarget {
    private static final String DIALOGUE_PROFILE_TAG = "DialogueProfile";
    private static final ResourceLocation DEFAULT_DIALOGUE_PROFILE =
            ResourceLocation.fromNamespaceAndPath(MineTale.MODID, "flowey");
    public static final float REST_FACE_X = -75.0F;
    public static final float MIN_FACE_X = -75.0F;
    public static final float MAX_FACE_X = 30.0F;
    public static final float MAX_FACE_YAW = 45.0F;
    public static final float MAX_STEM_YAW = 20.0F;
    public static final float MAX_TOTAL_YAW = MAX_FACE_YAW + MAX_STEM_YAW;
    public static final float FACE_SPEED = 5.0F;
    public static final float STEM_SPEED = 2.0F;
    public static final double ATTENTION_RANGE = 8.0;

    private static final int ANGLE_GRACE_TICKS = 10;
    private static final float IDLE_LOOK_CHANCE = 0.02F;
    private static final int MIN_IDLE_LOOK_TICKS = 20;
    private static final int IDLE_LOOK_TICK_VARIATION = 21;
    private static final float ROTATION_EPSILON = 0.001F;

    private static final EntityDataAccessor<Float> DATA_FACE_X =
            SynchedEntityData.defineId(Flowey.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_FACE_YAW =
            SynchedEntityData.defineId(Flowey.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_STEM_YAW =
            SynchedEntityData.defineId(Flowey.class, EntityDataSerializers.FLOAT);

    private static final TargetingConditions PLAYER_ATTENTION_CONDITIONS =
            TargetingConditions.forNonCombat()
                    .range(ATTENTION_RANGE)
                    .ignoreLineOfSight();

    private final AnimatableInstanceCache animationCache = GeckoLibUtil.createInstanceCache(this);
    private ResourceLocation dialogueProfileId = DEFAULT_DIALOGUE_PROFILE;

    @Nullable
    private ServerPlayer attentionTarget;
    private int angleGraceTicks;
    private int idleLookTicks;
    private float idleLookYaw;

    private float clientFaceX = REST_FACE_X;
    private float clientFaceXOld = REST_FACE_X;
    private float clientFaceYaw;
    private float clientFaceYawOld;
    private float clientStemYaw;
    private float clientStemYawOld;

    public Flowey(EntityType<? extends Flowey> entityType, Level level) {
        super(entityType, level);
        this.setPersistenceRequired();
        this.setInvulnerable(true);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MOVEMENT_SPEED, 0.0)
                .add(Attributes.FOLLOW_RANGE, ATTENTION_RANGE)
                .add(Attributes.KNOCKBACK_RESISTANCE, 1.0);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_FACE_X, REST_FACE_X);
        builder.define(DATA_FACE_YAW, 0.0F);
        builder.define(DATA_STEM_YAW, 0.0F);
    }

    @Override
    protected void customServerAiStep(ServerLevel level) {
        GazePose desiredPose = updateAttention(level);
        if (desiredPose == null) {
            desiredPose = updateIdleGaze();
        } else {
            this.idleLookTicks = 0;
        }

        advanceGaze(desiredPose);
    }

    @Nullable
    private GazePose updateAttention(ServerLevel level) {
        if (this.attentionTarget != null) {
            if (!isBasicAttentionCandidate(level, this.attentionTarget)) {
                clearAttentionTarget();
            } else {
                GazePose pose = solveGaze(this.attentionTarget);
                if (pose.reachable()) {
                    this.angleGraceTicks = 0;
                    return pose;
                }

                this.angleGraceTicks++;
                if (this.angleGraceTicks <= ANGLE_GRACE_TICKS) {
                    return pose;
                }

                clearAttentionTarget();
            }
        }

        ServerPlayer nearest = null;
        GazePose nearestPose = null;
        double nearestDistance = Double.MAX_VALUE;

        for (ServerPlayer player : level.players()) {
            if (!isBasicAttentionCandidate(level, player)) {
                continue;
            }

            GazePose pose = solveGaze(player);
            if (!pose.reachable()) {
                continue;
            }

            double distance = this.distanceToSqr(player);
            if (distance < nearestDistance) {
                nearest = player;
                nearestPose = pose;
                nearestDistance = distance;
            }
        }

        this.attentionTarget = nearest;
        this.angleGraceTicks = 0;
        return nearestPose;
    }

    private boolean isBasicAttentionCandidate(ServerLevel level, ServerPlayer player) {
        return player.isAlive()
                && !player.isSpectator()
                && player.level() == level
                && PLAYER_ATTENTION_CONDITIONS.test(level, this, player)
                && this.hasLineOfSight(
                        player,
                        ClipContext.Block.VISUAL,
                        ClipContext.Fluid.NONE,
                        player.getEyeY()
                );
    }

    private GazePose solveGaze(Player player) {
        double dx = player.getX() - this.getX();
        double dy = player.getEyeY() - this.getEyeY();
        double dz = player.getZ() - this.getZ();
        double horizontalDistance = Math.sqrt(dx * dx + dz * dz);

        float relativeYaw;
        if (horizontalDistance < 1.0E-5) {
            relativeYaw = 0.0F;
        } else {
            float worldYaw = (float)(Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90.0F;
            relativeYaw = Mth.wrapDegrees(worldYaw - this.getYRot());
        }

        float elevation = (float)(Mth.atan2(dy, horizontalDistance) * Mth.RAD_TO_DEG);
        float requestedFaceX = REST_FACE_X + elevation;
        boolean reachable = Math.abs(relativeYaw) <= MAX_TOTAL_YAW
                && requestedFaceX >= MIN_FACE_X
                && requestedFaceX <= MAX_FACE_X;

        return distributeGaze(
                Mth.clamp(relativeYaw, -MAX_TOTAL_YAW, MAX_TOTAL_YAW),
                Mth.clamp(requestedFaceX, MIN_FACE_X, MAX_FACE_X),
                reachable
        );
    }

    private GazePose updateIdleGaze() {
        if (this.idleLookTicks > 0) {
            this.idleLookTicks--;
            return distributeGaze(this.idleLookYaw, REST_FACE_X, true);
        }

        if (this.random.nextFloat() < IDLE_LOOK_CHANCE) {
            this.idleLookTicks = MIN_IDLE_LOOK_TICKS + this.random.nextInt(IDLE_LOOK_TICK_VARIATION);
            this.idleLookYaw = Mth.lerp(this.random.nextFloat(), -MAX_TOTAL_YAW, MAX_TOTAL_YAW);
            return distributeGaze(this.idleLookYaw, REST_FACE_X, true);
        }

        return GazePose.rest();
    }

    private static GazePose distributeGaze(float totalYaw, float faceX, boolean reachable) {
        float faceYaw = Mth.clamp(totalYaw, -MAX_FACE_YAW, MAX_FACE_YAW);
        float stemYaw = Mth.clamp(totalYaw - faceYaw, -MAX_STEM_YAW, MAX_STEM_YAW);
        return new GazePose(faceX, faceYaw, stemYaw, reachable);
    }

    private void advanceGaze(GazePose desiredPose) {
        float nextFaceX = approach(this.entityData.get(DATA_FACE_X), desiredPose.faceX(), FACE_SPEED);
        float nextFaceYaw = approach(this.entityData.get(DATA_FACE_YAW), desiredPose.faceYaw(), FACE_SPEED);
        boolean faceFinished = approximately(nextFaceX, desiredPose.faceX())
                && approximately(nextFaceYaw, desiredPose.faceYaw());
        float nextStemYaw = faceFinished
                ? approach(this.entityData.get(DATA_STEM_YAW), desiredPose.stemYaw(), STEM_SPEED)
                : this.entityData.get(DATA_STEM_YAW);

        this.entityData.set(DATA_FACE_X, nextFaceX);
        this.entityData.set(DATA_FACE_YAW, nextFaceYaw);
        this.entityData.set(DATA_STEM_YAW, nextStemYaw);
    }

    private static float approach(float current, float target, float maximumChange) {
        return current + Mth.clamp(target - current, -maximumChange, maximumChange);
    }

    private static boolean approximately(float first, float second) {
        return Math.abs(first - second) <= ROTATION_EPSILON;
    }

    private void clearAttentionTarget() {
        this.attentionTarget = null;
        this.angleGraceTicks = 0;
    }

    @Override
    public void tick() {
        if (this.level().isClientSide()) {
            this.clientFaceXOld = this.clientFaceX;
            this.clientFaceYawOld = this.clientFaceYaw;
            this.clientStemYawOld = this.clientStemYaw;
            this.clientFaceX = this.entityData.get(DATA_FACE_X);
            this.clientFaceYaw = this.entityData.get(DATA_FACE_YAW);
            this.clientStemYaw = this.entityData.get(DATA_STEM_YAW);
        }

        super.tick();
        this.yBodyRot = this.getYRot();
        this.yHeadRot = this.getYRot();
        this.setXRot(0.0F);
    }

    public void setRootFacingTowards(Entity source) {
        double dx = source.getX() - this.getX();
        double dz = source.getZ() - this.getZ();
        if (dx * dx + dz * dz < 1.0E-5) {
            return;
        }

        float yaw = (float)(Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90.0F;
        this.setYRot(yaw);
        this.yRotO = yaw;
        this.yBodyRot = yaw;
        this.yBodyRotO = yaw;
        this.yHeadRot = yaw;
        this.yHeadRotO = yaw;
    }

    public float getFaceX(float partialTick) {
        return Mth.lerp(partialTick, this.clientFaceXOld, this.clientFaceX);
    }

    public float getFaceYaw(float partialTick) {
        return Mth.lerp(partialTick, this.clientFaceYawOld, this.clientFaceYaw);
    }

    public float getStemYaw(float partialTick) {
        return Mth.lerp(partialTick, this.clientStemYawOld, this.clientStemYaw);
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public void push(double x, double y, double z) {
        super.push(0.0, y, 0.0);
    }

    @Override
    public void knockback(double strength, double x, double z) {
    }

    @Override
    public boolean hurtServer(ServerLevel level, DamageSource damageSource, float amount) {
        return false;
    }

    @Override
    public void kill(ServerLevel level) {
        this.remove(RemovalReason.KILLED);
        this.gameEvent(GameEvent.ENTITY_DIE);
    }

    @Override
    public boolean removeWhenFarAway(double distanceToClosestPlayer) {
        return false;
    }

    @Override
    public boolean requiresCustomPersistence() {
        return true;
    }

    @Override
    public boolean canBeLeashed() {
        return false;
    }

    @Override
    protected InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (this.level().isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResult.PASS;
        }
        return switch (DialogueSessionManager.tryStart(serverPlayer, this, this.dialogueProfileId)) {
            case STARTED, BUSY, FAILED -> InteractionResult.SUCCESS;
            case NO_MATCH -> InteractionResult.PASS;
        };
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        super.addAdditionalSaveData(output);
        output.putString(DIALOGUE_PROFILE_TAG, this.dialogueProfileId.toString());
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);
        ResourceLocation stored = ResourceLocation.tryParse(
                input.getStringOr(DIALOGUE_PROFILE_TAG, DEFAULT_DIALOGUE_PROFILE.toString())
        );
        this.dialogueProfileId = stored == null ? DEFAULT_DIALOGUE_PROFILE : stored;
    }

    @Override
    public ResourceLocation dialogueProfileId() {
        return this.dialogueProfileId;
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.animationCache;
    }

    private record GazePose(
            float faceX,
            float faceYaw,
            float stemYaw,
            boolean reachable
    ) {
        private static GazePose rest() {
            return new GazePose(REST_FACE_X, 0.0F, 0.0F, true);
        }
    }
}
