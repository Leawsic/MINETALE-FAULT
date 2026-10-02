package cn.jehorstudio.minetale.magic.effect.karma.mixin;

import cn.jehorstudio.minetale.magic.effect.karma.Karma;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(LivingEntity.class)
abstract class KarmaCooldownMixin {
    @Shadow protected float lastHurt;

    @WrapOperation(method = "hurtServer", at = @At(value = "FIELD", opcode = Opcodes.PUTFIELD,
            target = "Lnet/minecraft/world/entity/LivingEntity;lastHurt:F"), require = 2, allow = 2)
    private void minetale$preserveLastHurt(LivingEntity entity, float value, Operation<Void> original,
                                          ServerLevel level, DamageSource source, float amount) {
        original.call(entity, source.is(Karma.CONTACT) || source.is(Karma.DELAYED) ? lastHurt : value);
    }

    @WrapOperation(method = "hurtServer", at = @At(value = "FIELD", opcode = Opcodes.PUTFIELD,
            target = "Lnet/minecraft/world/entity/LivingEntity;invulnerableTime:I"), require = 1, allow = 1)
    private void minetale$preserveCooldown(LivingEntity entity, int value, Operation<Void> original,
                                         ServerLevel level, DamageSource source, float amount) {
        // 只保留当前字段值
        original.call(entity, source.is(Karma.CONTACT) || source.is(Karma.DELAYED) ? entity.invulnerableTime : value);
    }
}
