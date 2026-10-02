package cn.jehorstudio.minetale.dimension.region.core.mixin.server;

import cn.jehorstudio.minetale.dimension.region.core.CoreRegionManager;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;

// 将服务端 Core 方块占据并入实体碰撞查询；所有实体统一走普通移动解算。
@Mixin(Entity.class)
abstract class ServerCoreCollisionMixin {
    @ModifyReturnValue(method = "collectColliders", at = @At("RETURN"))
    private static List<VoxelShape> minetale$appendCoreCollision(
            List<VoxelShape> original,
            @Nullable Entity entity,
            Level level,
            List<VoxelShape> collisions,
            AABB boundingBox
    ) {
        return CoreRegionManager.appendCollision(level, boundingBox, original);
    }
}
