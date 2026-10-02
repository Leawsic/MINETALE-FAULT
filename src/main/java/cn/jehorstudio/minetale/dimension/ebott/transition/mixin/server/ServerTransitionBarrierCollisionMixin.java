package cn.jehorstudio.minetale.dimension.ebott.transition.mixin.server;

import cn.jehorstudio.minetale.dimension.ebott.transition.TransitionManager;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.ArrayList;
import java.util.List;

// 将权威结界形状并入服务端玩家碰撞查询，统一由普通移动解算处理。
@Mixin(Entity.class)
abstract class ServerTransitionBarrierCollisionMixin {
    @ModifyReturnValue(method = "collectColliders", at = @At("RETURN"))
    private static List<VoxelShape> minetale$appendTransitionBarrier(
            List<VoxelShape> original,
            @Nullable Entity entity,
            Level level,
            List<VoxelShape> collisions,
            AABB boundingBox
    ) {
        if (!(entity instanceof ServerPlayer player)) {
            return original;
        }
        VoxelShape barrier = TransitionManager.barrierCollisionShape(player, boundingBox);
        if (barrier.isEmpty()) {
            return original;
        }
        List<VoxelShape> withBarrier = new ArrayList<>(original);
        withBarrier.add(barrier);
        return withBarrier;
    }
}
