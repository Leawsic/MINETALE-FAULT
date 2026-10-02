package cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.entity.monsternpc.mixin.client;

import cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.entity.monsternpc.client.gpu.SnowtownCrowdClient;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// 将预测和权威方块更新汇入同一导航场失效入口。
@Mixin(ClientLevel.class)
abstract class SnowtownCrowdClientLevelMixin {
    @Inject(method = "setBlock", at = @At("RETURN"))
    private void minetale$noticePredictedBlockChange(
            BlockPos position,
            BlockState state,
            int flags,
            int recursionLeft,
            CallbackInfoReturnable<Boolean> callback
    ) {
        if (callback.getReturnValueZ()) {
            SnowtownCrowdClient.onClientBlockChanged(
                    (ClientLevel) (Object) this,
                    position);
        }
    }
}
