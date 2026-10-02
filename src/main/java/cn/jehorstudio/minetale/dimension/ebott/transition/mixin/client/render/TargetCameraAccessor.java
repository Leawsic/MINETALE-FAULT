package cn.jehorstudio.minetale.dimension.ebott.transition.mixin.client.render;

import net.minecraft.client.Camera;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

// 允许目标渲染器更新独立 Camera
@Mixin(Camera.class)
public interface TargetCameraAccessor {
    @Invoker("setPosition")
    void minetale$setPosition(Vec3 position);

    @Invoker("setRotation")
    void minetale$setRotation(float yaw, float pitch, float roll);
}
