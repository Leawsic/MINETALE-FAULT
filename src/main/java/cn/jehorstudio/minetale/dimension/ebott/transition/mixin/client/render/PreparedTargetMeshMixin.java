package cn.jehorstudio.minetale.dimension.ebott.transition.mixin.client.render;

import cn.jehorstudio.minetale.dimension.ebott.transition.client.TransitionClient;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// 在 prepared mesh 构建边界裁掉接缝以上几何，使结果不依赖 terrain shader 实现。
@Mixin(RenderSectionRegion.class)
abstract class PreparedTargetMeshMixin {
    @Shadow
    @Final
    private Level level;

    @Inject(method = "getBlockState", at = @At("HEAD"), cancellable = true)
    private void minetale$clipPreparedTargetBlock(
            BlockPos pos,
            CallbackInfoReturnable<BlockState> callback
    ) {
        if (TransitionClient.INSTANCE.shouldClipPreparedTargetMesh(this.level, pos.getY())) {
            callback.setReturnValue(Blocks.AIR.defaultBlockState());
        }
    }

    @Inject(method = "getFluidState", at = @At("HEAD"), cancellable = true)
    private void minetale$clipPreparedTargetFluid(
            BlockPos pos,
            CallbackInfoReturnable<FluidState> callback
    ) {
        if (TransitionClient.INSTANCE.shouldClipPreparedTargetMesh(this.level, pos.getY())) {
            callback.setReturnValue(Fluids.EMPTY.defaultFluidState());
        }
    }

    @Inject(method = "getBlockEntity", at = @At("HEAD"), cancellable = true)
    private void minetale$clipPreparedTargetBlockEntity(
            BlockPos pos,
            CallbackInfoReturnable<BlockEntity> callback
    ) {
        if (TransitionClient.INSTANCE.shouldClipPreparedTargetMesh(this.level, pos.getY())) {
            callback.setReturnValue(null);
        }
    }
}
