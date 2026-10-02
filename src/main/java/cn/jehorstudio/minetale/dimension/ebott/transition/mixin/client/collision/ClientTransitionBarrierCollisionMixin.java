package cn.jehorstudio.minetale.dimension.ebott.transition.mixin.client.collision;

import cn.jehorstudio.minetale.dimension.ebott.transition.client.TransitionClient;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.ArrayList;
import java.util.List;

// 将服务端同步的结界形状加入本地预测，以防先穿透再被权威位置拉回。
@Mixin(Entity.class)
abstract class ClientTransitionBarrierCollisionMixin {
    @ModifyReturnValue(method = "collectColliders", at = @At("RETURN"))
    private static List<VoxelShape> minetale$appendTransitionBarrier(
            List<VoxelShape> original,
            @Nullable Entity entity,
            Level level,
            List<VoxelShape> collisions,
            AABB boundingBox
    ) {
        if (!(entity instanceof LocalPlayer)) {
            return original;
        }
        VoxelShape barrier = TransitionClient.INSTANCE.barrierCollisionShape(boundingBox);
        if (barrier.isEmpty()) {
            return original;
        }
        List<VoxelShape> withBarrier = new ArrayList<>(original);
        withBarrier.add(barrier);
        return withBarrier;
    }
}
