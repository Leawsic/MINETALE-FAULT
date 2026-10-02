package cn.jehorstudio.minetale.dimension.region.core.mixin.client;

import cn.jehorstudio.minetale.dimension.region.core.client.CoreRegionClient;

import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// 将 Core 占据并入客户端射线检测：比原版结果更近时以假想方块命中返回，
// 使放置、右键使用等以选择框为落点；位置为空气，破坏天然无效。
@Mixin(BlockGetter.class)
interface ClientCoreClipMixin {
    @Inject(method = "clip", at = @At("RETURN"), cancellable = true)
    private void minetale$coreClip(ClipContext context, CallbackInfoReturnable<BlockHitResult> cir) {
        BlockHitResult replacement = CoreRegionClient.clipCore(context, cir.getReturnValue());
        if (replacement != null) cir.setReturnValue(replacement);
    }
}
