package cn.jehorstudio.minetale.magic.skill;

import cn.jehorstudio.minetale.MineTale;
import cn.jehorstudio.minetale.magic.Magic;
import cn.jehorstudio.minetale.magic.MagicConfig;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 世界内魔法状态和施法的唯一所有者。所有操作要求服务端线程与尚未移除的实体。
 * Attachment 保存长期状态，会话和锁定随实体离开 Level 立即失效。
 */
public final class MagicCasting {
    private static final DeferredRegister<AttachmentType<?>> ATTACHMENTS =
            DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, MineTale.MODID);
    static final Supplier<AttachmentType<MagicState>> STATE = ATTACHMENTS.register("magic",
            () -> AttachmentType.builder(MagicState::new).serialize(MagicState.CODEC.fieldOf("state"))
                    .copyOnDeath().build());
    private static final Map<Entity, Runtime> RUNTIMES = new IdentityHashMap<>();

    private MagicCasting() {}

    public static void register(IEventBus modBus) {
        MagicNetworking.register(modBus);
        NeoForge.EVENT_BUS.addListener(MagicCommands::register);
        MagicCatalog.registerSchool(Magic.GASTER_SCHOOL);
        MagicCatalog.registerSkill(new cn.jehorstudio.minetale.magic.skill.gasterimpact.GasterImpact());
        ATTACHMENTS.register(modBus);
        modBus.addListener((FMLCommonSetupEvent event) -> event.enqueueWork(MagicCatalog::freeze));
        NeoForge.EVENT_BUS.addListener(MagicCasting::tick);
        NeoForge.EVENT_BUS.addListener(MagicCasting::leave);
        NeoForge.EVENT_BUS.addListener(MagicCasting::login);
        NeoForge.EVENT_BUS.addListener(MagicCasting::respawn);
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent event) -> RUNTIMES.clear());
    }

    /** 返回不可变状态快照；costMultiplier 是当前游戏模式下的有效倍率，创造模式为零。首次访问非玩家实体建立空掌握状态。 */
    public static Snapshot snapshot(Entity caster) {
        MagicState state = state(caster);
        Runtime runtime = RUNTIMES.get(caster);
        return new Snapshot(state.mana, state.maximum, state.regeneration, freeCasting(caster) ? 0 : state.costMultiplier,
                Set.copyOf(state.schools), Set.copyOf(state.skills), state.selected,
                runtime == null || runtime.cast == null ? 0 : runtime.cast.progress(context(caster, runtime)),
                runtime != null && runtime.cast != null,
                runtime == null || runtime.lock == null ? -1 : runtime.lock.getId());
    }

    public record Snapshot(double mana, double maximum, double regeneration, double costMultiplier,
                           Set<ResourceLocation> schools, Set<ResourceLocation> skills,
                           ResourceLocation selected, float progress, boolean casting, int lockedEntityId) {}

    /** 修改魔力参数并钳制当前魔力；参数均需为有限非负数，范围与服务端配置一致。 */
    public static void configureMana(Entity caster, double maximum, double regeneration, double multiplier) {
        requireRange(maximum, 1_000_000);
        requireRange(regeneration, 100_000);
        requireRange(multiplier, 1000);
        MagicState state = state(caster);
        state.maximum = maximum;
        state.regeneration = regeneration;
        state.costMultiplier = multiplier;
        state.mana = Math.min(state.mana, maximum);
    }

    /** 设置当前魔力；超出总量时钳制，非法值抛出 IllegalArgumentException。 */
    public static void setMana(Entity caster, double amount) {
        requireRange(amount, 1_000_000);
        MagicState state = state(caster);
        state.mana = Math.min(amount, state.maximum);
    }

    /** 授予已注册流派资格，不会自动授予技能。 */
    public static void grantSchool(Entity caster, ResourceLocation school) {
        if (!MagicCatalog.schools().contains(school)) throw new IllegalArgumentException("未知流派: " + school);
        state(caster).schools.add(school);
    }

    /** 撤销流派资格，保留技能记录；取消已经失去资格的施法。 */
    public static void revokeSchool(Entity caster, ResourceLocation school) {
        state(caster).schools.remove(school);
        cancelIfUnavailable(caster);
    }

    /** 授予技能并补齐所属流派；没有选择时自动选中该技能。 */
    public static void grantSkill(Entity caster, ResourceLocation id) {
        Skill skill = requireSkill(id);
        MagicState state = state(caster);
        state.schools.add(skill.definition().school());
        state.skills.add(id);
        if (state.selected == null) state.selected = id;
    }

    /** 撤销技能并清除对应选择，流派资格保留。 */
    public static void revokeSkill(Entity caster, ResourceLocation id) {
        MagicState state = state(caster);
        state.skills.remove(id);
        if (id.equals(state.selected)) state.selected = null;
        cancelIfUnavailable(caster);
    }

    /** 选择当前有权使用的技能 */
    public static Skill.Result select(Entity caster, ResourceLocation id) {
        if (!canCast(caster, id)) return Skill.Result.UNAVAILABLE;
        cancel(caster);
        state(caster).selected = id;
        return Skill.Result.SUCCESS;
    }

    /** 同时要求具体技能掌握和所属流派资格 */
    public static boolean canCast(Entity caster, ResourceLocation id) {
        MagicState state = state(caster);
        Skill skill = MagicCatalog.skill(id);
        return skill != null && state.skills.contains(id) && state.schools.contains(skill.definition().school());
    }

    /** 开始一次施法并扣除起手费用；同一实体最多拥有一个正在运行的施法会话。 */
    public static Skill.Result begin(Entity caster, ResourceLocation skillId) {
        MagicState state = state(caster);
        if (!caster.isAlive() || caster.isSpectator() || !canCast(caster, skillId)) return Skill.Result.UNAVAILABLE;
        Runtime runtime = RUNTIMES.computeIfAbsent(caster, key -> new Runtime());
        if (runtime.cast != null) return Skill.Result.BUSY;
        Skill skill = requireSkill(skillId);
        Skill.Definition definition = skill.definition();
        double initial = freeCasting(caster) ? 0 : definition.activationCost() * state.costMultiplier;
        if (initial > state.mana) return Skill.Result.NO_MANA;
        runtime.cast = skill.createCast();
        runtime.networkCastId = -1;
        runtime.skillId = skillId;
        runtime.ticks = 0;
        runtime.spent = initial;
        runtime.tickCost = definition.powerPerSecond() * state.costMultiplier / 20;
        state.mana -= initial;
        return step(caster, runtime, runtime.cast.start(context(caster, runtime)));
    }

    /** 释放当前施法；释放失败时退还本次已扣魔力。 */
    public static Skill.Result release(Entity caster) {
        requireServer(caster);
        Runtime runtime = RUNTIMES.get(caster);
        if (runtime == null || runtime.cast == null) return Skill.Result.INVALID;
        if (!caster.isAlive() || caster.isRemoved() || caster.isSpectator() || !canCast(caster, runtime.skillId)) {
            cancel(caster);
            return Skill.Result.UNAVAILABLE;
        }
        Skill.Cast cast = runtime.cast;
        double spent = runtime.spent;
        runtime.cast = null; // 在世界副作用之前消费会话，重复释放不会再次执行。
        Skill.Result result = cast.release(context(caster, runtime));
        if (result != Skill.Result.SUCCESS) {
            MagicState state = state(caster);
            state.mana = Math.min(state.maximum, state.mana + spent);
        }
        return result;
    }

    /** 取消当前施法，不返还已消耗魔力；可重复调用。 */
    public static void cancel(Entity caster) {
        requireServer(caster);
        Runtime runtime = RUNTIMES.get(caster);
        if (runtime != null && runtime.cast != null) {
            Skill.Cast cast = runtime.cast;
            runtime.cast = null;
            cast.cancel(context(caster, runtime));
        }
    }

    static boolean acceptSequence(Entity caster, long sequence) {
        requireServer(caster);
        Runtime runtime = RUNTIMES.computeIfAbsent(caster, key -> new Runtime());
        if (sequence < 0 || sequence <= runtime.lastSequence) return false;
        long tick = caster.level().getGameTime();
        if (runtime.inputTick != tick) { runtime.inputTick = tick; runtime.inputCount = 0; }
        if (++runtime.inputCount > 8) return false;
        runtime.lastSequence = sequence;
        return true;
    }

    static void handleInput(Entity caster, int action, long castId, long sequence) {
        Runtime runtime = RUNTIMES.get(caster);
        if (runtime == null) return;
        if (action == MagicNetworking.LOCK) {
            initialize(caster);
            toggleLock(caster);
        } else if (action == MagicNetworking.BEGIN) {
            if (castId != sequence) return;
            initialize(caster);
            Skill.Result result = begin(caster, state(caster).selected);
            if (result == Skill.Result.SUCCESS && runtime.cast != null) runtime.networkCastId = castId;
        } else if (castId == runtime.networkCastId && runtime.cast != null) {
            if (action == MagicNetworking.RELEASE) release(caster);
            else cancel(caster);
        }
    }

    static void toggleLock(Entity caster) {
        requireServer(caster);
        Runtime runtime = RUNTIMES.computeIfAbsent(caster, key -> new Runtime());
        Entity target = MagicTargeting.pointedEntity(caster, MagicConfig.LOCK_RANGE);
        runtime.lock = target == runtime.lock ? null : target;
    }

    /**
     * 查询显式锁定，供技能自行决定是否采用
     * 必须在服务端线程调用，会清除死亡、移除、跨 Level 或超距的失效锁定。
     * @param caster 查询锁定状态的施法者
     * @return 当前有效锁定实体，没有有效锁定时返回 null
     */
    public static Entity lockedTarget(Entity caster) {
        Runtime runtime = RUNTIMES.get(caster);
        if (runtime == null || runtime.lock == null) return null;
        Entity target = runtime.lock;
        if (!target.isAlive() || target.isRemoved() || target.level() != caster.level()
                || target.distanceToSqr(caster) > Math.pow(MagicConfig.LOCK_RANGE, 2)) runtime.lock = null;
        return runtime.lock;
    }

    private static void tick(EntityTickEvent.Post event) {
        Entity caster = event.getEntity();
        if (!(caster.level() instanceof ServerLevel) || !caster.hasData(STATE)) return;
        Runtime runtime = RUNTIMES.get(caster);
        MagicState state = caster.getData(STATE);
        if (!caster.isAlive() || caster.isRemoved() || caster.isSpectator()) {
            cancel(caster);
            if (runtime != null) runtime.lock = null;
            return;
        }
        // 锁定是施法者的持续意向；遮挡和视角变化不能替玩家取消。
        lockedTarget(caster);
        if (runtime == null || runtime.cast == null) {
            state.mana = Math.min(state.maximum, state.mana + state.regeneration / 20);
        } else if (!canCast(caster, runtime.skillId)) {
            cancel(caster);
        } else if (!freeCasting(caster) && state.mana + 1.0E-9 < runtime.tickCost) {
            release(caster);
        } else {
            double cost = freeCasting(caster) ? 0 : runtime.tickCost;
            state.mana = Math.max(0, state.mana - cost);
            runtime.spent += cost;
            runtime.ticks++;
            step(caster, runtime, runtime.cast.tick(context(caster, runtime)));
        }
    }

    private static Skill.Result step(Entity caster, Runtime runtime, Skill.Step step) {
        if (step == Skill.Step.RELEASE) return release(caster);
        if (step == Skill.Step.COMPLETE) runtime.cast = null;
        return Skill.Result.SUCCESS;
    }

    private static void login(PlayerEvent.PlayerLoggedInEvent event) { initialize(event.getEntity()); }

    static void initialize(Entity player) {
        // Attachment 的存在性区分首次进入和主动清空技能后的再次登录。
        if (!player.hasData(STATE)) {
            state(player);
            if (MagicCatalog.skill(Magic.GASTER_IMPACT) != null) grantSkill(player, Magic.GASTER_IMPACT);
        }
    }

    private static void respawn(PlayerEvent.PlayerRespawnEvent event) {
        Entity player = event.getEntity();
        initialize(player);
        if (!event.isEndConquered()) {
            MagicState state = state(player);
            state.mana = state.maximum;
        }
    }

    private static void leave(EntityLeaveLevelEvent event) {
        if (event.getLevel() instanceof ServerLevel) {
            cancel(event.getEntity());
            RUNTIMES.remove(event.getEntity());
        }
    }

    private static void cancelIfUnavailable(Entity caster) {
        Runtime runtime = RUNTIMES.get(caster);
        if (runtime != null && runtime.cast != null && !canCast(caster, runtime.skillId)) cancel(caster);
    }

    private static Skill.Context context(Entity caster, Runtime runtime) { return new Skill.Context(caster, runtime.ticks); }
    // 游戏模式是即时计费条件，不覆盖 Attachment 中的倍率；切回生存后继续使用原倍率。
    private static boolean freeCasting(Entity caster) { return caster instanceof Player player && player.isCreative(); }
    private static Skill requireSkill(ResourceLocation id) {
        Skill skill = MagicCatalog.skill(id);
        if (skill == null) throw new IllegalArgumentException("未知技能: " + id);
        return skill;
    }

    private static MagicState state(Entity caster) {
        requireServer(caster);
        return caster.getData(STATE);
    }

    private static void requireServer(Entity caster) {
        if (!(caster.level() instanceof ServerLevel level) || !level.getServer().isSameThread()) {
            throw new IllegalStateException("Magic 只能在服务端线程调用");
        }
    }

    private static void requireRange(double value, double maximum) {
        if (!Double.isFinite(value) || value < 0 || value > maximum) throw new IllegalArgumentException("魔力参数超出范围");
    }

    private static final class Runtime {
        Skill.Cast cast;
        ResourceLocation skillId;
        Entity lock;
        int ticks;
        double spent;
        double tickCost;
        long lastSequence = -1;
        long networkCastId = -1;
        long inputTick = -1;
        int inputCount;
    }
}
