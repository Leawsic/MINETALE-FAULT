package cn.jehorstudio.minetale.magic.spell.gasterblaster;

import cn.jehorstudio.minetale.MineTale;
import cn.jehorstudio.minetale.magic.collision.MagicCollision;
import cn.jehorstudio.minetale.magic.effect.karma.Karma;
import it.unimi.dsi.fastutil.objects.Reference2DoubleMap;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.joml.Vector3f;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animatable.manager.AnimatableManager;
import software.bernie.geckolib.animatable.processing.AnimationController;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.constant.dataticket.DataTicket;
import software.bernie.geckolib.loading.math.MolangQueries;
import software.bernie.geckolib.loading.math.value.Variable;
import software.bernie.geckolib.model.GeoModel;
import software.bernie.geckolib.util.GeckoLibUtil;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

// 一个法术实体拥有生命周期、固定射击基准和逐 Tick 命中历史
public final class GasterBlaster extends Entity implements GeoEntity {
    public static final DataTicket<AnimationFrame> ANIMATION_FRAME = DataTicket.create("minetale_gaster_frame", AnimationFrame.class);
    public record AnimationFrame(float age, int windup, int beam, int close) {
        public boolean closing() { return age >= windup + beam; }

        public double animationTick() {
            // shoot 资源末尾有静止尾段；前摇只映射到完全张嘴，保证稳定阶段恰好为 beam Tick。
            double length = closing() ? GasterAnimation.length(true) : GasterAnimation.openingEnd();
            double time = closing() ? (age - windup - beam) * length / close : age * length / windup;
            return Math.clamp(time, 0, closing() ? Math.nextDown(length) : length);
        }

        public float aperture() {
            return GasterAnimation.aperture(closing(), animationTick());
        }

        public double openingStart() { return GasterAnimation.openingStart() * windup / GasterAnimation.openingEnd(); }

        public float extension() {
            // 张嘴仅触发发射；前端在独立的 4 Tick（0.2 秒）内匀速冲出，不读取下颌缓动。
            // 极短生命周期可能尚未射完就收口，必须从已经到达的位置收回，不能跳到满长。
            float launched = Math.clamp((float) ((Math.min(age, windup + beam) - openingStart()) / 4), 0, 1);
            return launched * retraction();
        }

        public float retraction() { return closing() ? aperture() : 1; }

        public double recoilDistance() {
            // 后退动画独立于下颌曲线，收口后保持末速度滑行。
            double powered = Math.clamp(age - openingStart(), 0, windup + beam - openingStart());
            double coast = Math.clamp(age - windup - beam, 0, close);
            return 0.036 * powered * (powered * 0.5 + coast);
        }
    }
    private static final DeferredRegister.Entities TYPES = DeferredRegister.createEntities(MineTale.MODID);
    public static final DeferredHolder<EntityType<?>, EntityType<GasterBlaster>> TYPE = TYPES.registerEntityType(
            "gaster_blaster", GasterBlaster::new, MobCategory.MISC,
            builder -> builder.sized(2, 2).clientTrackingRange(32).updateInterval(20).noSave().noLootTable().fireImmune());
    private static final EntityDataAccessor<Long> START = SynchedEntityData.defineId(GasterBlaster.class, EntityDataSerializers.LONG);
    private static final EntityDataAccessor<Vector3f> SHAPE = SynchedEntityData.defineId(GasterBlaster.class, EntityDataSerializers.VECTOR3);
    private static final EntityDataAccessor<Vector3f> DURATIONS = SynchedEntityData.defineId(GasterBlaster.class, EntityDataSerializers.VECTOR3);
    private static final EntityDataAccessor<Vector3f> DIRECTION = SynchedEntityData.defineId(GasterBlaster.class, EntityDataSerializers.VECTOR3);
    private static final EntityDataAccessor<Float> RANGE = SynchedEntityData.defineId(GasterBlaster.class, EntityDataSerializers.FLOAT);
    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);
    private final Set<UUID> struck = new HashSet<>();
    private UUID ownerId;
    private UUID targetId;
    private Vec3 targetPoint = Vec3.ZERO;
    private float damage;
    private float karmaMultiplier;
    private double range;
    private float visualAge;

    public GasterBlaster(EntityType<? extends GasterBlaster> type, Level level) {
        super(type, level);
        noPhysics = true;
        setNoGravity(true);
        setInvulnerable(true);
    }

    public static void register(IEventBus bus) { TYPES.register(bus); }

    /** 瞄准意向：point 是最后有效世界坐标；target 可为空，有目标时只在张嘴前追踪。 */
    public record Target(Vec3 point, Entity target) {
        public Target {
            requirePoint(point);
        }
    }

    /**
     * 一次法术的固定参数，与技能蓄力、费用和配置无关。
     * scale 为模型倍率；radius/伤害射程 range 单位为格，视觉长度独立跟随客户端视距；damage 为每 Tick 接触伤害；时长单位为 Tick。
     * 半径上限为 4 格，伤害射程须能以有限 float 同步，所有时长必须为正。
     */
    public record Parameters(float scale, float radius, double range, float damage, float karmaMultiplier,
                             int windupTicks, int beamTicks, int closeTicks) {
        public Parameters {
            if (!Float.isFinite(scale) || scale < 0.1F || scale > 16
                    || !Float.isFinite(radius) || radius <= 0 || radius > 4
                    || !Double.isFinite(range) || range <= 0 || range > Float.MAX_VALUE
                    || !Float.isFinite(damage) || damage < 0
                    || !Float.isFinite(karmaMultiplier) || karmaMultiplier < 0
                    || windupTicks < 1 || windupTicks > 1200 || beamTicks < 1 || beamTicks > 1200
                    || closeTicks < 1 || closeTicks > 1200) {
                throw new IllegalArgumentException("GB 参数超出支持范围");
            }
        }
    }

    /**
     * 在施法者所在服务端 Level 生成独立运行的 GB；
     * @param caster 伤害归属；离开本 Level、死亡或进入旁观模式后法术销毁
     * @param aim 初始目标，非空目标必须属于同一 Level
     * @param muzzle 初始世界炮口，调用方负责选择有效且已加载的位置
     * @param parameters 已验证的固定法术参数
     * @return 已加入世界的 GB；世界拒绝加入时返回 null，不产生后续副作用
     * @throws IllegalStateException 调用不在服务端线程
     * @throws IllegalArgumentException 炮口坐标非法或目标跨 Level
     */
    public static GasterBlaster summon(Entity caster, Target aim, Vec3 muzzle, Parameters parameters) {
        if (!(caster.level() instanceof ServerLevel level) || !level.getServer().isSameThread()) {
            throw new IllegalStateException("法术生成需要服务端线程");
        }
        requirePoint(muzzle);
        if (aim.target() != null && aim.target().level() != level) {
            throw new IllegalArgumentException("法术目标必须属于同一 Level");
        }
        GasterBlaster blaster = new GasterBlaster(TYPE.get(), level);
        blaster.initialize(caster, aim, muzzle, parameters);
        return level.addFreshEntity(blaster) ? blaster : null;
    }

    private static void requirePoint(Vec3 point) {
        if (point == null || !Double.isFinite(point.x) || !Double.isFinite(point.y) || !Double.isFinite(point.z)) {
            throw new IllegalArgumentException("法术坐标必须有限");
        }
    }

    /** 供技能排列现存炮位时查询归属，不转移实体生命周期。 */
    public boolean ownedBy(Entity caster) { return caster.getUUID().equals(ownerId); }

    /** 判断同一目标组；自由瞄准点相距小于 4 格视为同组。 */
    public boolean aimsAt(Vec3 point, Entity target) {
        return target != null ? target.getUUID().equals(targetId)
                : targetId == null && point.distanceToSqr(targetPoint) < 16;
    }

    /** 返回包含模型旋转与完整出场位移的世界包络；用于生成避让 */
    public AABB occupiedBounds() { return occupiedBounds(position(), direction(), scale()); }

    /** 动画扫过的完整包络，供客户端裁剪 */
    public AABB visualBounds() {
        AABB body = occupiedBounds();
        return body.minmax(body.move(direction().scale(-maximumRecoil())));
    }

    private double maximumRecoil() {
        int windup = windupTicks(), beam = beamTicks(), close = closeTicks();
        return new AnimationFrame(windup + beam + close, windup, beam, close).recoilDistance();
    }

    /** 计算候选炮口的完整出场包络；direction 必须为单位向量，scale 使用法术参数的倍率。 */
    public static AABB occupiedBounds(Vec3 muzzle, Vec3 direction, float scale) {
        // 原模型顶点（含 jaw 全部旋转）距 root 不超过 1.43 格，使用 1.6 格保守包络。
        // root 相对校准炮口为 (0,3,10)/16；出场位移 (0,40,56)/16，保留 easeOutBack 的负向余量。
        AABB body = bodyBounds(muzzle, direction, scale);
        Vec3 motion = up(direction).scale(2.5 * scale).subtract(direction.scale(3.5 * scale));
        return body.move(motion.scale(-0.05)).minmax(body.move(motion));
    }

    /** 计算稳定炮体包络，供技能在完整出场空间不足时降低选址要求；参数约束同 occupiedBounds。 */
    public static AABB bodyBounds(Vec3 muzzle, Vec3 direction, float scale) {
        Vec3 center = muzzle.add(up(direction).scale(3.0 / 16 * scale)).subtract(direction.scale(10.0 / 16 * scale));
        return new AABB(center, center).inflate(1.6 * scale + 0.375);
    }

    private static Vec3 up(Vec3 direction) {
        double yaw = Math.atan2(-direction.x, -direction.z);
        return new Vec3(Math.sin(yaw) * direction.y, Math.hypot(direction.x, direction.z), Math.cos(yaw) * direction.y);
    }

    private void initialize(Entity caster, Target aim, Vec3 muzzle, Parameters parameters) {
        ownerId = caster.getUUID();
        targetId = aim.target() == null ? null : aim.target().getUUID();
        targetPoint = aim.point();
        damage = parameters.damage();
        karmaMultiplier = parameters.karmaMultiplier();
        range = parameters.range();
        entityData.set(RANGE, (float) range);
        entityData.set(START, level().getGameTime());
        entityData.set(SHAPE, new Vector3f(parameters.scale(), parameters.radius(), damage));
        entityData.set(DURATIONS, new Vector3f(parameters.windupTicks(), parameters.beamTicks(), parameters.closeTicks()));
        setPos(muzzle);
        aimAtTarget();
        setOldPosAndRot();
    }

    @Override protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(START, 0L);
        builder.define(SHAPE, new Vector3f(0.5F, 0.25F, 0.1F));
        builder.define(DURATIONS, new Vector3f(30, 30, 10));
        builder.define(DIRECTION, new Vector3f(0, 0, -1));
        builder.define(RANGE, 0F);
    }

    public float age(float partialTick) { return Math.max(0, level().getGameTime() - entityData.get(START) + partialTick); }
    public float scale() { return entityData.get(SHAPE).x; }
    public float radius() { return entityData.get(SHAPE).y; }
    public int windupTicks() { return (int) entityData.get(DURATIONS).x; }
    public int beamTicks() { return (int) entityData.get(DURATIONS).y; }
    public int closeTicks() { return (int) entityData.get(DURATIONS).z; }
    public Vec3 direction() { Vector3f v = entityData.get(DIRECTION); return new Vec3(v.x, v.y, v.z).normalize(); }
    public float beamLength() { return beamRange() * animationFrame(0).extension(); }
    public float beamRange() { return entityData.get(RANGE); }
    public boolean firing() { return animationFrame(0).aperture() > 0; }
    public AnimationFrame animationFrame(float partialTick) {
        float age = age(partialTick);
        // 客户端世界时间会被服务端校正，光影也可能重复取样；视觉可短暂停留，不能倒放张嘴或收口。
        // 模型和激光共用这一单调时间，服务端命中使用原始权威 Tick。
        if (level().isClientSide()) age = visualAge = Math.max(visualAge, age);
        return new AnimationFrame(age, windupTicks(), beamTicks(), closeTicks());
    }

    @Override public void tick() {
        super.tick();
        if (!(level() instanceof ServerLevel level)) return;
        Entity caster = ownerId == null ? null : level.getEntity(ownerId);
        if (caster == null || !caster.isAlive() || caster.isSpectator()
                || age(0) >= windupTicks() + beamTicks() + closeTicks()) {
            discard();
            return;
        }
        AnimationFrame frame = animationFrame(0.5F);
        if (age(0) < windupTicks() && frame.aperture() == 0) {
            Entity target = targetId == null ? null : level.getEntity(targetId);
            if (target != null && target.isAlive()) targetPoint = target.getBoundingBox().getCenter();
            aimAtTarget();
        }
        // 发射基准固定。后退只是客户端动画。
        if (frame.aperture() > 0) {
            // 每个目标独立检查其受光路径。边缘碰墙、擦地不能缩短其他径向位置的伤害范围。
            var active = new MagicCollision.Cylinder(position(), direction(),
                    range * frame.extension(), radius() * frame.aperture());
            MagicCollision.Query query = MagicCollision.query(level, caster, List.of(active),
                    target -> level.isPositionEntityTicking(target.blockPosition()) && Karma.canHarm(caster, target));
            for (LivingEntity target : query.hits()) {
                if (!MagicCollision.beamReaches(level, active, target.getBoundingBox(), frame.retraction())) continue;
                double buildup = (struck.contains(target.getUUID()) ? 0.1 : 1.0) * karmaMultiplier;
                if (Karma.hit(level, this, caster, target, damage, buildup)) struck.add(target.getUUID());
            }
        }
    }

    private void aimAtTarget() {
        Vec3 direction = targetPoint.subtract(position());
        if (direction.lengthSqr() > 1.0E-8) {
            direction = direction.normalize();
            entityData.set(DIRECTION, new Vector3f((float) direction.x, (float) direction.y, (float) direction.z));
        }
    }

    @Override public boolean isPickable() { return false; }
    @Override public boolean shouldRenderAtSqrDistance(double distance) {
        double limit = (Math.max(128, 64 * scale()) + maximumRecoil()) * getViewScale();
        return distance < limit * limit;
    }
    @Override public boolean isPushable() { return false; }
    @Override public boolean hurtServer(ServerLevel level, DamageSource source, float amount) { return false; }
    @Override protected void readAdditionalSaveData(ValueInput input) { discard(); }
    @Override protected void addAdditionalSaveData(ValueOutput output) {}
    @Override public void registerControllers(AnimatableManager.ControllerRegistrar controllers) { controllers.add(new Timeline()); }
    @Override public AnimatableInstanceCache getAnimatableInstanceCache() { return cache; }

    private static final class Timeline extends AnimationController<GasterBlaster> {
        private static final RawAnimation SHOOT = RawAnimation.begin().thenPlayAndHold("shoot");
        private static final RawAnimation CLOSE = RawAnimation.begin().thenPlayAndHold("close");
        private AnimationFrame frame;

        Timeline() {
            super("lifecycle", 0, test -> test.setAndContinue(test.getData(ANIMATION_FRAME).closing() ? CLOSE : SHOOT));
        }

        @Override public void prepareForRenderPass(GasterBlaster animatable, AnimatableManager<GasterBlaster> manager,
                MolangQueries.Actor<GasterBlaster> actor, Reference2DoubleMap<Variable> variables,
                double tick, GeoModel<GasterBlaster> model) {
            frame = actor.renderState().getGeckolibData(ANIMATION_FRAME);
            super.prepareForRenderPass(animatable, manager, actor, variables, tick, model);
        }

        @Override protected double adjustTick(double tick) {
            // 切换动画时必须消费 GeckoLib 的 reset，并返回精确的 0 才能取出新队列。
            // 若直接返回带 partialTick 的关闭时间，控制器会继续采样旧 shoot 的第 0 帧。
            if (shouldResetTick) return super.adjustTick(tick);
            if (frame == null) return 0;
            // 动画必须跟随已同步的法术年龄。晚进入追踪范围不能从头重播前摇。
            return frame.animationTick();
        }

        @Override public void beginTick(software.bernie.geckolib.animatable.processing.AnimationState<GasterBlaster> state,
                java.util.Map<String, software.bernie.geckolib.cache.object.GeoBone> bones,
                java.util.Map<String, software.bernie.geckolib.animation.state.BoneSnapshot> snapshots, double tick) {
            super.beginTick(state, bones, snapshots, tick);
            if (getAnimationState() == State.TRANSITIONING && currentAnimation != null) {
                // 零时长切换取出新动画后，同一帧立即采样权威时间，不能让嘴停在 close 第 0 帧。
                boneAnimationQueues.clear();
                shouldResetTick = false;
                justStartedTransition = false;
                processedAnimationTick = frame.animationTick();
                setAnimationState(State.RUNNING);
                super.beginTick(state, bones, snapshots, tick);
            }
        }
    }
}
