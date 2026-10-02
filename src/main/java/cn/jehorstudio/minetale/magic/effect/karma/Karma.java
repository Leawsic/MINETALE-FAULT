package cn.jehorstudio.minetale.magic.effect.karma;

import cn.jehorstudio.minetale.MineTale;
import cn.jehorstudio.minetale.magic.Magic;
import cn.jehorstudio.minetale.magic.MagicConfig;
import com.mojang.serialization.Codec;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/** 服务端 Karma 接触伤害和持久延迟池。所有修改要求服务端线程。 */
public final class Karma {
    public static final ResourceKey<DamageType> CONTACT = ResourceKey.create(Registries.DAMAGE_TYPE, Magic.id("karma_contact"));
    public static final ResourceKey<DamageType> DELAYED = ResourceKey.create(Registries.DAMAGE_TYPE, Magic.id("karma_delayed"));
    private static final DeferredRegister<AttachmentType<?>> ATTACHMENTS =
            DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, MineTale.MODID);
    private static final Supplier<AttachmentType<Debt>> DEBT = ATTACHMENTS.register("karma",
            () -> AttachmentType.builder(Debt::new).serialize(Debt.CODEC.fieldOf("debt")).build());
    // 只调度已加载、实际存在延迟伤害的受害者；数值的唯一来源仍是 Attachment。
    private static final Map<LivingEntity, Debt> ACTIVE = new IdentityHashMap<>();

    private Karma() {}

    public static void register(IEventBus modBus) {
        ATTACHMENTS.register(modBus);
        NeoForge.EVENT_BUS.addListener(Karma::incoming);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, Karma::limitDelayedDamage);
        NeoForge.EVENT_BUS.addListener(Karma::tick);
        NeoForge.EVENT_BUS.addListener(Karma::join);
        NeoForge.EVENT_BUS.addListener((EntityLeaveLevelEvent event) -> {
            if (event.getLevel() instanceof ServerLevel) ACTIVE.remove(event.getEntity());
        });
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent event) -> ACTIVE.clear());
    }

    /** 判断施法者排除、PvP 和队伍友伤规则；无敌状态由标准伤害流程最终裁定。 */
    public static boolean canHarm(Entity caster, LivingEntity target) {
        if (caster == target || !target.isAlive() || target.isSpectator()) return false;
        if (caster instanceof ServerPlayer player && target instanceof Player other && !player.canHarmPlayer(other)) return false;
        var team = caster.getTeam();
        return team == null || !team.isAlliedTo(target.getTeam()) || team.isAllowFriendlyFire();
    }

    /**
     * 造成一次标准接触伤害，仅在确实损失生命或吸收值时增加延迟池。
     * 返回是否成功命中，调用方据此记录该法术是否已经给予首次命中奖励。
     */
    public static boolean hit(ServerLevel level, Entity spell, Entity caster, LivingEntity target,
                              double damage, double buildup) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Karma 需要服务端线程");
        if (!Double.isFinite(damage) || damage < 0 || !Double.isFinite(buildup) || buildup < 0) {
            throw new IllegalArgumentException("伤害和 Karma 必须为有限非负数");
        }
        if (!canHarm(caster, target)) return false;
        float before = target.getHealth() + target.getAbsorptionAmount();
        DamageSource source = new DamageSource(level.registryAccess().getOrThrow(CONTACT), spell, caster);
        if (!target.hurtServer(level, source, (float) damage)
                || target.getHealth() + target.getAbsorptionAmount() >= before) return false;
        if (!target.isAlive()) return true;
        Debt debt = target.getData(DEBT);
        debt.add(caster.getUUID(), caster.getScoreboardName(), caster instanceof Player, buildup);
        ACTIVE.put(target, debt);
        return true;
    }

    /** 读取当前延迟池 */
    public static double amount(Entity target) { return target.hasData(DEBT) ? target.getData(DEBT).amount() : 0; }

    private static void incoming(LivingIncomingDamageEvent event) {
        if (event.getSource().is(CONTACT) || event.getSource().is(DELAYED)) {
            // 不清除其他攻击留下的无敌时间，也不为连续接触刷新全局无敌时间。
            event.setInvulnerabilityTicks(event.getEntity().invulnerableTime);
        }
    }

    private static void limitDelayedDamage(LivingDamageEvent.Pre event) {
        if (event.getSource().is(DELAYED)) {
            float remaining = Math.max(0, event.getEntity().getHealth() - 1);
            event.setNewDamage(Math.min(Math.max(0, event.getNewDamage()), remaining));
        }
    }

    private static void join(EntityJoinLevelEvent event) {
        if (event.getLevel() instanceof ServerLevel && event.getEntity() instanceof LivingEntity living && living.hasData(DEBT)) {
            ACTIVE.put(living, living.getData(DEBT));
        }
    }

    private static void tick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        // hurtServer 可同步触发其他模组的伤害回调或实体移除
        for (LivingEntity target : ACTIVE.keySet().toArray(LivingEntity[]::new)) {
            Debt debt = ACTIVE.get(target);
            if (debt == null) continue;
            if (target.level() != level) continue;
            double total = debt.amount();
            if (!target.isAlive() || total < 1.0E-8) {
                target.removeData(DEBT);
                ACTIVE.remove(target);
                continue;
            }
            // 在 Level Tick 末结算，所有法术先完成本 Tick 的接触伤害与积累。
            if (++debt.elapsed < interval(total)) continue;
            debt.elapsed = 0;
            double remaining = Math.min(0.1, total);
            // 按来源首次进入池的顺序结算；先扣债再交付伤害，回调中新加入的债务留给后续 Tick。
            for (Portion portion : debt.portions.values().toArray(Portion[]::new)) {
                if (remaining < 1.0E-8) break;
                if (target.level() != level || !target.isAlive() || ACTIVE.get(target) != debt) break;
                double amount = Math.min(remaining, portion.amount);
                remaining -= amount;
                portion.amount -= amount;
                if (portion.amount < 1.0E-8) debt.portions.remove(portion.source);
                Entity source = level.getEntity(portion.source);
                boolean allowed = source != null ? canHarm(source, target) : allowedWithoutSource(level, portion, target);
                if (allowed) target.hurtServer(level, new DamageSource(level.registryAccess().getOrThrow(DELAYED), source), (float) amount);
            }
        }
    }

    private static boolean allowedWithoutSource(ServerLevel level, Portion debt, LivingEntity target) {
        if (target.isSpectator()) return false;
        if (debt.playerSource && target instanceof Player && !level.getServer().isPvpAllowed()) return false;
        var sourceTeam = level.getScoreboard().getPlayersTeam(debt.sourceName);
        return sourceTeam == null || !sourceTeam.isAlliedTo(target.getTeam()) || sourceTeam.isAllowFriendlyFire();
    }

    static int interval(double amount) {
        if (amount >= 4 - 1.0E-8) return 1;
        if (amount >= 3 - 1.0E-8) return 2;
        if (amount >= 2 - 1.0E-8) return 4;
        if (amount >= 1 - 1.0E-8) return 10;
        return 20;
    }

    private static final class Debt {
        static final Codec<Debt> CODEC = CompoundTag.CODEC.xmap(Debt::read, Debt::write);
        final Map<UUID, Portion> portions = new LinkedHashMap<>();
        int elapsed;

        double amount() { return portions.values().stream().mapToDouble(portion -> portion.amount).sum(); }

        void add(UUID source, String name, boolean player, double amount) {
            double accepted = Math.min(amount, Math.max(0, MagicConfig.KARMA_CAP - amount()));
            if (accepted < 1.0E-8) return;
            Portion portion = portions.computeIfAbsent(source, id -> new Portion(id, name, player));
            portion.amount += accepted;
        }

        private CompoundTag write() {
            CompoundTag tag = new CompoundTag();
            tag.putInt("elapsed", elapsed);
            ListTag list = new ListTag();
            for (Portion portion : portions.values()) {
                CompoundTag entry = new CompoundTag();
                entry.putDouble("amount", portion.amount);
                entry.putString("source", portion.source.toString());
                entry.putString("source_name", portion.sourceName);
                entry.putBoolean("player_source", portion.playerSource);
                list.add(entry);
            }
            tag.put("sources", list);
            return tag;
        }

        private static Debt read(CompoundTag tag) {
            Debt result = new Debt();
            result.elapsed = Math.clamp(tag.getIntOr("elapsed", 0), 0, 20);
            for (var element : tag.getListOrEmpty("sources")) {
                if (!(element instanceof CompoundTag entry)) continue;
                double value = entry.getDoubleOr("amount", 0);
                if (!Double.isFinite(value) || value <= 0) continue;
                try {
                    result.add(UUID.fromString(entry.getStringOr("source", "")), entry.getStringOr("source_name", ""),
                            entry.getBooleanOr("player_source", false), value);
                } catch (IllegalArgumentException ignored) {
                    // 无法归属的损坏记录不进入伤害流程，其余来源仍可恢复。
                }
            }
            return result;
        }
    }

    private static final class Portion {
        final UUID source;
        final String sourceName;
        final boolean playerSource;
        double amount;

        Portion(UUID source, String sourceName, boolean playerSource) {
            this.source = source;
            this.sourceName = sourceName;
            this.playerSource = playerSource;
        }
    }
}
