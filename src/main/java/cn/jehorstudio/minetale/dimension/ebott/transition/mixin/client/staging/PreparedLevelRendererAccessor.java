package cn.jehorstudio.minetale.dimension.ebott.transition.mixin.client.staging;

import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

// 暴露 prepared renderer 所需的编译、清理与受限地形绘制入口。
@Mixin(LevelRenderer.class)
public interface PreparedLevelRendererAccessor {
    @Accessor("viewArea")
    ViewArea minetale$getViewArea();

    @Accessor("lastCameraSectionX")
    void minetale$setLastCameraSectionX(int value);

    @Accessor("lastCameraSectionY")
    void minetale$setLastCameraSectionY(int value);

    @Accessor("lastCameraSectionZ")
    void minetale$setLastCameraSectionZ(int value);

    @Invoker("prepareChunkRenders")
    ChunkSectionsToRender minetale$prepareChunkRenders(
            Matrix4fc frustumMatrix,
            double cameraX,
            double cameraY,
            double cameraZ
    );
}
