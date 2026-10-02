package cn.jehorstudio.minetale.battle.presentation.screen.render.rendertypes;

import cn.jehorstudio.minetale.MineTale;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

// Frame 一般状态不计算全局 ADS；
// Rendered 模式接收环境染色、全局阴影、Soul 点光与点阴影。
public final class BattleFrame {
    public static final RenderPipeline UNMODIFIED_PIPELINE = RenderPipeline.builder()
            .withLocation(id("pipeline/battle_frame_unmodified"))
            .withVertexShader(id("core/battle/primitive/frame"))
            .withFragmentShader(id("core/battle/primitive/solid"))
            .withUniform("DrawUniform", UniformType.UNIFORM_BUFFER)
            .withVertexFormat(DefaultVertexFormat.POSITION, VertexFormat.Mode.QUADS)
            .withDepthTestFunction(DepthTestFunction.LESS_DEPTH_TEST)
            .withDepthWrite(true)
            .withCull(false)
            .build();
    public static final RenderPipeline RENDERED_PIPELINE = RenderPipeline.builder()
            .withLocation(id("pipeline/battle_frame_rendered"))
            .withVertexShader(id("core/battle/primitive/rendered/frame"))
            .withFragmentShader(id("core/battle/primitive/rendered/frame_shadow"))
            .withSampler("ShadowSampler")
            .withSampler("PointShadowAtlas")
            .withSampler("EnvironmentFieldSampler")
            .withUniform("DrawUniform", UniformType.UNIFORM_BUFFER)
            .withUniform("LightingUniform", UniformType.UNIFORM_BUFFER)
            .withUniform("SoulPointLightUniform", UniformType.UNIFORM_BUFFER)
            .withUniform("EnvironmentFieldUniform", UniformType.UNIFORM_BUFFER)
            .withUniform("EnvironmentLightUniform", UniformType.UNIFORM_BUFFER)
            .withVertexFormat(DefaultVertexFormat.POSITION, VertexFormat.Mode.QUADS)
            .withDepthTestFunction(DepthTestFunction.LESS_DEPTH_TEST)
            .withDepthWrite(true)
            .withCull(false)
            .build();
    public static final RenderPipeline SHADOW_PIPELINE = RenderPipeline.builder()
            .withLocation(id("pipeline/battle_frame_shadow"))
            .withVertexShader(id("core/battle/primitive/rendered/frame_shadow"))
            .withFragmentShader(id("core/battle/primitive/rendered/depth_only"))
            .withUniform("DrawUniform", UniformType.UNIFORM_BUFFER)
            .withUniform("LightingUniform", UniformType.UNIFORM_BUFFER)
            .withVertexFormat(DefaultVertexFormat.POSITION, VertexFormat.Mode.QUADS)
            .withDepthTestFunction(DepthTestFunction.LESS_DEPTH_TEST)
            .withDepthWrite(true)
            .withColorWrite(false)
            .withCull(false)
            .withDepthBias(1.0F, 1.0F)
            .build();
    /** @deprecated 等同于 Unmodified 管线。 */
    @Deprecated public static final RenderPipeline PIPELINE = UNMODIFIED_PIPELINE;

    public static final RenderType RENDER_TYPE = RenderType.create(
            "minetale_battle_frame",
            RenderType.SMALL_BUFFER_SIZE,
            false,
            false,
            UNMODIFIED_PIPELINE,
            RenderType.CompositeState.builder()
                    .setLightmapState(RenderStateShard.NO_LIGHTMAP)
                    .setOverlayState(RenderStateShard.NO_OVERLAY)
                    .createCompositeState(false)
    );

    private BattleFrame() {
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MineTale.MODID, path);
    }
}
