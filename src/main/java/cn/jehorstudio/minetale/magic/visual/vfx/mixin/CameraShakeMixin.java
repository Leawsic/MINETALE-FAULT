package cn.jehorstudio.minetale.magic.visual.vfx.mixin;

import cn.jehorstudio.minetale.magic.visual.vfx.MagicCameraShake;
import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Camera.class)
abstract class CameraShakeMixin {
    @Shadow protected abstract void setPosition(net.minecraft.world.phys.Vec3 position);

    @Inject(method = "setup", at = @At("TAIL"))
    private void minetale$shake(BlockGetter level, Entity entity, boolean detached, boolean reverse,
            float partialTick, CallbackInfo callback) {
        Camera camera = (Camera) (Object) this;
        var offset = MagicCameraShake.displacement(camera, partialTick);
        if (offset.lengthSqr() > 0) {
            setPosition(camera.getPosition().add(offset));
        }
    }
}
