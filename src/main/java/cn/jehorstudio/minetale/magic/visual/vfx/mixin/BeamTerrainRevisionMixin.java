package cn.jehorstudio.minetale.magic.visual.vfx.mixin;

import cn.jehorstudio.minetale.magic.visual.vfx.BeamTerrainRevision;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.chunk.PalettedContainer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// 监听容器的实际写入口，覆盖单方块、批量更新、直接 palette 写入和整区块网络替换。
// 只在物理客户端应用；缓存只读客户端世界，集成服务器的独立容器不与它共享状态。
@Mixin(PalettedContainer.class)
abstract class BeamTerrainRevisionMixin implements BeamTerrainRevision {
    @Unique private long minetale$beamRevision;

    @Override public long minetale$beamRevision() { return minetale$beamRevision; }

    @Inject(method = "getAndSet(ILjava/lang/Object;)Ljava/lang/Object;", at = @At("RETURN"))
    private void minetale$changed(int index, Object state, CallbackInfoReturnable<Object> callback) {
        if (callback.getReturnValue() != state) minetale$beamRevision++;
    }

    @Inject(method = "set(ILjava/lang/Object;)V", at = @At("RETURN"))
    private void minetale$set(int index, Object state, CallbackInfo callback) { minetale$beamRevision++; }

    @Inject(method = "read", at = @At("RETURN"))
    private void minetale$read(FriendlyByteBuf buffer, CallbackInfo callback) { minetale$beamRevision++; }
}
