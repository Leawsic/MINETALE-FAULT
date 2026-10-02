package cn.jehorstudio.minetale.magic.visual.vfx.mixin;

import cn.jehorstudio.minetale.magic.visual.vfx.MagicBeamRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// 必须先于 Sodium 的可取消 HEAD 执行；只消费 AfterEntities 建立的当前世界帧，不处理阴影帧。
@Mixin(value = ChunkSectionsToRender.class, priority = 2000)
abstract class BeamRenderStageMixin {
    @Inject(method = "renderGroup", at = @At("HEAD"), order = 900)
    private void minetale$beforeWater(ChunkSectionLayerGroup group, CallbackInfo callback) {
        if (group == ChunkSectionLayerGroup.TRANSLUCENT) MagicBeamRenderer.beforeTranslucentTerrain();
    }
}

