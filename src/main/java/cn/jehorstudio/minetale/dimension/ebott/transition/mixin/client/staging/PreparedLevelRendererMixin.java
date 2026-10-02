package cn.jehorstudio.minetale.dimension.ebott.transition.mixin.client.staging;

import cn.jehorstudio.minetale.dimension.ebott.transition.client.PreparedSectionPoolRegistry;
import cn.jehorstudio.minetale.dimension.ebott.transition.client.TransitionClient;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// 在 prepared renderer 的生命周期边界安装并退役独立编译池。
@Mixin(LevelRenderer.class)
abstract class PreparedLevelRendererMixin {
    @Inject(method = "setLevel", at = @At("HEAD"), cancellable = true)
    private void minetale$keepPreparedRenderState(ClientLevel level, CallbackInfo callback) {
        if (TransitionClient.INSTANCE.shouldKeepPreparedRenderState(
                (LevelRenderer) (Object) this,
                level
        )) {
            callback.cancel();
        }
    }

    @Inject(method = "setLevel", at = @At("TAIL"))
    private void minetale$retirePreparedSectionPool(ClientLevel level, CallbackInfo callback) {
        if (level == null) {
            PreparedSectionPoolRegistry.retire((LevelRenderer) (Object) this);
        }
    }

}
