package cn.jehorstudio.minetale.dimension.region.core.mixin.client;

import cn.jehorstudio.minetale.dimension.region.core.client.CoreRegionClient;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;

// 将本地 Core 方块占据加入客户端预测碰撞，与服务端权威栅格同构，避免先穿透再被拉回。
@Mixin(Entity.class)
abstract class ClientCoreCollisionMixin {
    @ModifyReturnValue(method = "collectColliders", at = @At("RETURN"))
    private static List<VoxelShape> minetale$appendCoreCollision(
            List<VoxelShape> original,
            @Nullable Entity entity,
            Level level,
            List<VoxelShape> collisions,
            AABB boundingBox
    ) {
        return CoreRegionClient.appendCollision(level, boundingBox, original);
    }
}
