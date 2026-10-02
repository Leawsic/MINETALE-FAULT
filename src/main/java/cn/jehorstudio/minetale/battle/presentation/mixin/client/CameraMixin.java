package cn.jehorstudio.minetale.battle.presentation.mixin.client;

import cn.jehorstudio.minetale.battle.presentation.screen.render.environment.EnvironmentCaptureService;
import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// 在 Camera.setup 的所有分支结束后覆盖捕获姿态，仅修改 Camera。
@Mixin(Camera.class)
abstract class CameraMixin {
    @Shadow
    protected abstract void setPosition(Vec3 position);

    @Shadow
    protected abstract void setRotation(float yaw, float pitch, float roll);

    @Inject(method = "setup", at = @At("TAIL"))
    private void minetale$applyEnvironmentCapturePose(
            BlockGetter level,
            Entity entity,
            boolean detached,
            boolean thirdPersonReverse,
            float partialTick,
            CallbackInfo callback
    ) {
        EnvironmentCaptureService.EnvironmentCameraOverride override =
                EnvironmentCaptureService.INSTANCE.cameraOverride();
        if (override == null) {
            return;
        }
        setRotation(override.yawDegrees(), override.pitchDegrees(), override.rollDegrees());
        setPosition(override.position());
    }
}
