package cn.jehorstudio.minetale.battle.presentation.screen.render;

import cn.jehorstudio.minetale.MineTale;
import cn.jehorstudio.minetale.battle.presentation.screen.render.rendertypes.BattleAuxiliaryGrid;
import cn.jehorstudio.minetale.battle.presentation.screen.render.rendertypes.BattleEntity;
import cn.jehorstudio.minetale.battle.presentation.screen.render.rendertypes.BattleFrame;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterPictureInPictureRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterRenderPipelinesEvent;

@EventBusSubscriber(modid = MineTale.MODID, value = Dist.CLIENT)
public final class PipelineRegister {
    public static final RenderPipeline BATTLE_SOLID_SCREEN = drawPipeline(
            "battle_solid_screen", "battle/primitive/draw", "battle/primitive/solid",
            DefaultVertexFormat.POSITION_TEX, VertexFormat.Mode.TRIANGLE_STRIP,
            PipelinePolicy.SCREEN, false
    );
    public static final RenderPipeline BATTLE_SOLID_WORLD = drawPipeline(
            "battle_solid_world", "battle/primitive/draw", "battle/primitive/solid",
            DefaultVertexFormat.POSITION_TEX, VertexFormat.Mode.TRIANGLE_STRIP,
            PipelinePolicy.OPAQUE, false
    );
    public static final RenderPipeline BATTLE_SOLID_WORLD_TRANSLUCENT = drawPipeline(
            "battle_solid_world_translucent", "battle/primitive/draw", "battle/primitive/solid",
            DefaultVertexFormat.POSITION_TEX, VertexFormat.Mode.TRIANGLE_STRIP,
            PipelinePolicy.TRANSLUCENT, false
    );
    public static final RenderPipeline BATTLE_TEXTURE_WORLD_CUTOUT = drawPipeline(
            "battle_texture_world_cutout", "battle/primitive/draw", "battle/primitive/texture",
            DefaultVertexFormat.POSITION_TEX, VertexFormat.Mode.TRIANGLE_STRIP,
            PipelinePolicy.CUTOUT, true
    );
    public static final RenderPipeline BATTLE_TEXTURE_WORLD_CUTOUT_QUADS = drawPipeline(
            "battle_texture_world_cutout_quads_unmodified", "battle/entity/unmodified/battle_scene", "battle/primitive/texture",
            DefaultVertexFormat.POSITION_TEX_COLOR_NORMAL, VertexFormat.Mode.QUADS,
            PipelinePolicy.CUTOUT, true
    );
    public static final RenderPipeline BATTLE_TEXTURE_WORLD_TRANSLUCENT = drawPipeline(
            "battle_texture_world_translucent", "battle/primitive/draw", "battle/primitive/texture",
            DefaultVertexFormat.POSITION_TEX, VertexFormat.Mode.TRIANGLE_STRIP,
            PipelinePolicy.TRANSLUCENT, true
    );
    public static final RenderPipeline BATTLE_TEXTURE_SCREEN = drawPipeline(
            "battle_texture_screen", "battle/primitive/draw", "battle/primitive/texture",
            DefaultVertexFormat.POSITION_TEX, VertexFormat.Mode.TRIANGLE_STRIP,
            PipelinePolicy.SCREEN, true
    );
    public static final RenderPipeline BATTLE_GLYPH_INTENSITY = drawPipeline(
            "battle_glyph_intensity", "battle/text/glyph", "battle/text/intensity",
            DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP, VertexFormat.Mode.QUADS,
            PipelinePolicy.SCREEN, true
    );
    public static final RenderPipeline BATTLE_GLYPH_COLOR = drawPipeline(
            "battle_glyph_color", "battle/text/glyph", "battle/text/color",
            DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP, VertexFormat.Mode.QUADS,
            PipelinePolicy.SCREEN, true
    );
    public static final RenderPipeline BATTLE_GLYPH_WORLD_INTENSITY = drawPipeline(
            "battle_glyph_world_intensity", "battle/text/glyph", "battle/text/intensity",
            DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP, VertexFormat.Mode.QUADS,
            PipelinePolicy.TRANSLUCENT, true
    );
    public static final RenderPipeline BATTLE_GLYPH_WORLD_COLOR = drawPipeline(
            "battle_glyph_world_color", "battle/text/glyph", "battle/text/color",
            DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP, VertexFormat.Mode.QUADS,
            PipelinePolicy.TRANSLUCENT, true
    );
    public static final RenderPipeline BATTLE_LINE = drawPipeline(
            "battle_line", "battle/primitive/line", "battle/primitive/solid",
            DefaultVertexFormat.POSITION_TEX, VertexFormat.Mode.TRIANGLE_STRIP,
            PipelinePolicy.OPAQUE, false
    );
    public static final RenderPipeline BATTLE_LINE_TRANSLUCENT = drawPipeline(
            "battle_line_translucent", "battle/primitive/line", "battle/primitive/solid",
            DefaultVertexFormat.POSITION_TEX, VertexFormat.Mode.TRIANGLE_STRIP,
            PipelinePolicy.TRANSLUCENT, false
    );
    public static final RenderPipeline BATTLE_POST_COPY = postPipeline("battle_post_copy", "battle/post/copy");
    public static final RenderPipeline BATTLE_POST_FXAA = postPipeline("battle_post_fxaa", "battle/post/fxaa");
    public static final RenderPipeline BATTLE_POST_BLOOM_DOWNSAMPLE = postProcessingPipeline(
            "battle_post_bloom_downsample", "battle/post/blur_pyramid_downsample", "Sampler0"
    );
    public static final RenderPipeline BATTLE_POST_BLOOM_UPSAMPLE = postProcessingPipeline(
            "battle_post_bloom_upsample", "battle/post/blur_pyramid_upsample",
            "CoarseSampler", "FineSampler"
    );
    public static final RenderPipeline BATTLE_POST_BLOOM_COMPOSITE = postProcessingPipeline(
            "battle_post_bloom_composite", "battle/post/bloom_composite", "SceneSampler", "BloomSampler"
    );
    public static final RenderPipeline BATTLE_RENDER_MODE_CROSSFADE = RenderPipeline.builder()
            .withLocation(id("pipeline/battle_render_mode_crossfade"))
            .withVertexShader(id("core/battle/post/fullscreen"))
            .withFragmentShader(id("core/battle/post/render_mode_crossfade"))
            .withSampler("UnmodifiedSampler")
            .withSampler("RenderedSampler")
            .withUniform("RenderModeCrossfadeUniform", UniformType.UNIFORM_BUFFER)
            .withVertexFormat(DefaultVertexFormat.EMPTY, VertexFormat.Mode.TRIANGLES)
            .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
            .withDepthWrite(false)
            .withCull(false)
            .build();
    public static final RenderPipeline BATTLE_ENVIRONMENT_CAPTURE = RenderPipeline.builder()
            .withLocation(id("pipeline/battle_environment_capture"))
            .withVertexShader(id("core/battle/post/fullscreen"))
            .withFragmentShader(id("core/battle/environment/capture"))
            .withSampler("SourceSampler")
            .withVertexFormat(DefaultVertexFormat.EMPTY, VertexFormat.Mode.TRIANGLES)
            .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
            .withDepthWrite(false)
            .withCull(false)
            .build();
    public static final RenderPipeline BATTLE_ENVIRONMENT_LOBE_EXTRACT = RenderPipeline.builder()
            .withLocation(id("pipeline/battle_environment_lobe_extract"))
            .withVertexShader(id("core/battle/post/fullscreen"))
            .withFragmentShader(id("core/battle/environment/lobe_extract"))
            .withSampler("EnvironmentAtlas")
            .withVertexFormat(DefaultVertexFormat.EMPTY, VertexFormat.Mode.TRIANGLES)
            .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
            .withDepthWrite(false)
            .withCull(false)
            .build();
    public static final RenderPipeline BATTLE_ENVIRONMENT_FIELD_RESOLVE = RenderPipeline.builder()
            .withLocation(id("pipeline/battle_environment_field_resolve"))
            .withVertexShader(id("core/battle/post/fullscreen"))
            .withFragmentShader(id("core/battle/environment/field_resolve"))
            .withSampler("EnvironmentAtlas")
            .withSampler("RawLobeSampler")
            .withVertexFormat(DefaultVertexFormat.EMPTY, VertexFormat.Mode.TRIANGLES)
            .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
            .withDepthWrite(false)
            .withCull(false)
            .build();
    public static final RenderPipeline BATTLE_ENVIRONMENT_DEBUG = RenderPipeline.builder()
            .withLocation(id("pipeline/battle_environment_debug"))
            .withVertexShader(id("core/battle/post/fullscreen"))
            .withFragmentShader(id("core/battle/environment/debug"))
            .withSampler("EnvironmentDebugSampler")
            .withVertexFormat(DefaultVertexFormat.EMPTY, VertexFormat.Mode.TRIANGLES)
            .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
            .withDepthWrite(false)
            .withCull(false)
            .build();
    public static final RenderPipeline BATTLE_ENVIRONMENT_BACKGROUND_EVALUATE = RenderPipeline.builder()
            .withLocation(id("pipeline/battle_environment_background_evaluate"))
            .withVertexShader(id("core/battle/post/fullscreen"))
            .withFragmentShader(id("core/battle/environment/background_evaluate"))
            .withSampler("EnvironmentFieldSampler")
            .withUniform("EnvironmentBackgroundUniform", UniformType.UNIFORM_BUFFER)
            .withVertexFormat(DefaultVertexFormat.EMPTY, VertexFormat.Mode.TRIANGLES)
            .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
            .withDepthWrite(false)
            .withCull(false)
            .build();
    public static final RenderPipeline BATTLE_ENVIRONMENT_BACKGROUND_COMPOSITE = RenderPipeline.builder()
            .withLocation(id("pipeline/battle_environment_background_composite"))
            .withVertexShader(id("core/battle/post/fullscreen"))
            .withFragmentShader(id("core/battle/environment/background_composite"))
            .withSampler("EnvironmentBackgroundSampler")
            .withSampler("ActivationFrameSampler")
            .withSampler("BlueNoiseSampler")
            .withUniform("EnvironmentBackgroundUniform", UniformType.UNIFORM_BUFFER)
            .withVertexFormat(DefaultVertexFormat.EMPTY, VertexFormat.Mode.TRIANGLES)
            .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
            .withDepthWrite(false)
            .withCull(false)
            .build();
    public static final RenderPipeline BATTLE_SHADOW_MINMAX_BASE = RenderPipeline.builder()
            .withLocation(id("pipeline/battle_shadow_minmax_base"))
            .withVertexShader(id("core/battle/post/fullscreen"))
            .withFragmentShader(id("core/battle/shadow/directional/minmax_base"))
            .withSampler("ShadowSampler")
            .withUniform("ShadowMinMaxUniform", UniformType.UNIFORM_BUFFER)
            .withVertexFormat(DefaultVertexFormat.EMPTY, VertexFormat.Mode.TRIANGLES)
            .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
            .withDepthWrite(false)
            .withCull(false)
            .build();
    public static final RenderPipeline BATTLE_SHADOW_MINMAX_REDUCE = RenderPipeline.builder()
            .withLocation(id("pipeline/battle_shadow_minmax_reduce"))
            .withVertexShader(id("core/battle/post/fullscreen"))
            .withFragmentShader(id("core/battle/shadow/directional/minmax_reduce"))
            .withSampler("PreviousMinMaxSampler")
            .withUniform("ShadowMinMaxUniform", UniformType.UNIFORM_BUFFER)
            .withVertexFormat(DefaultVertexFormat.EMPTY, VertexFormat.Mode.TRIANGLES)
            .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
            .withDepthWrite(false)
            .withCull(false)
            .build();
    public static final RenderPipeline BATTLE_VOLUMETRIC_INTERVAL_TRACE = RenderPipeline.builder()
            .withLocation(id("pipeline/battle_volumetric_interval_trace"))
            .withVertexShader(id("core/battle/post/fullscreen"))
            .withFragmentShader(id("core/battle/volume/interval_trace"))
            .withSampler("SceneDepthSampler")
            .withSampler("ShadowSampler")
            .withSampler("MinMaxLevel0")
            .withSampler("MinMaxLevel1")
            .withSampler("MinMaxLevel2")
            .withSampler("MinMaxLevel3")
            .withSampler("MinMaxLevel4")
            .withSampler("PointShadowAtlas")
            .withSampler("PointMinMaxLevel0")
            .withSampler("PointMinMaxLevel1")
            .withSampler("PointMinMaxLevel2")
            .withSampler("PointMinMaxLevel3")
            .withUniform("LightingUniform", UniformType.UNIFORM_BUFFER)
            .withUniform("VolumetricUniform", UniformType.UNIFORM_BUFFER)
            .withBlend(BlendFunction.TRANSLUCENT_PREMULTIPLIED_ALPHA)
            .withVertexFormat(DefaultVertexFormat.EMPTY, VertexFormat.Mode.TRIANGLES)
            .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
            .withDepthWrite(false)
            .withCull(false)
            .build();
    public static final RenderPipeline BATTLE_VOLUMETRIC_POINT_ENTITY_SHADOW = RenderPipeline.builder()
            .withLocation(id("pipeline/battle_soul_point_entity_shadow"))
            .withVertexShader(id("core/battle/shadow/point/capture"))
            .withFragmentShader(id("core/battle/shadow/point/capture"))
            .withShaderDefine("SOUL_POINT_TEXTURED_CASTER")
            .withSampler("Sampler0")
            .withUniform("DrawUniform", UniformType.UNIFORM_BUFFER)
            .withUniform("SoulPointShadowUniform", UniformType.UNIFORM_BUFFER)
            .withVertexFormat(DefaultVertexFormat.POSITION_TEX_COLOR_NORMAL, VertexFormat.Mode.QUADS)
            .withDepthTestFunction(DepthTestFunction.LESS_DEPTH_TEST)
            .withDepthWrite(true)
            .withCull(false)
            .build();
    public static final RenderPipeline BATTLE_VOLUMETRIC_POINT_FRAME_SHADOW = RenderPipeline.builder()
            .withLocation(id("pipeline/battle_soul_point_frame_shadow"))
            .withVertexShader(id("core/battle/shadow/point/capture"))
            .withFragmentShader(id("core/battle/shadow/point/capture"))
            .withShaderDefine("SOUL_POINT_FRAME_CASTER")
            .withUniform("DrawUniform", UniformType.UNIFORM_BUFFER)
            .withUniform("SoulPointShadowUniform", UniformType.UNIFORM_BUFFER)
            .withVertexFormat(DefaultVertexFormat.POSITION, VertexFormat.Mode.QUADS)
            .withDepthTestFunction(DepthTestFunction.LESS_DEPTH_TEST)
            .withDepthWrite(true)
            .withCull(false)
            .build();
    public static final RenderPipeline BATTLE_VOLUMETRIC_POINT_MINMAX_BASE = RenderPipeline.builder()
            .withLocation(id("pipeline/battle_soul_point_minmax_base"))
            .withVertexShader(id("core/battle/post/fullscreen"))
            .withFragmentShader(id("core/battle/shadow/point/minmax_base"))
            .withSampler("PointShadowAtlas")
            .withUniform("SoulPointMinMaxUniform", UniformType.UNIFORM_BUFFER)
            .withVertexFormat(DefaultVertexFormat.EMPTY, VertexFormat.Mode.TRIANGLES)
            .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
            .withDepthWrite(false)
            .withCull(false)
            .build();
    public static final RenderPipeline BATTLE_VOLUMETRIC_POINT_MINMAX_REDUCE = RenderPipeline.builder()
            .withLocation(id("pipeline/battle_soul_point_minmax_reduce"))
            .withVertexShader(id("core/battle/post/fullscreen"))
            .withFragmentShader(id("core/battle/shadow/point/minmax_reduce"))
            .withSampler("PreviousPointMinMax")
            .withUniform("SoulPointMinMaxUniform", UniformType.UNIFORM_BUFFER)
            .withVertexFormat(DefaultVertexFormat.EMPTY, VertexFormat.Mode.TRIANGLES)
            .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
            .withDepthWrite(false)
            .withCull(false)
            .build();
    public static final RenderPipeline BATTLE_SCREEN_TRANSITION = RenderPipeline.builder()
            .withLocation(id("pipeline/battle_screen_transition"))
            .withVertexShader(id("core/battle/post/fullscreen"))
            .withFragmentShader(id("core/battle/post/transition"))
            .withSampler("FreezeSampler")
            .withSampler("CurrentSampler")
            .withUniform("TransitionUniform", UniformType.UNIFORM_BUFFER)
            .withVertexFormat(DefaultVertexFormat.EMPTY, VertexFormat.Mode.TRIANGLES)
            .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
            .withDepthWrite(false)
            .withCull(false)
            .build();
    public static final RenderPipeline BATTLE_SCREEN_SHAKE = screenEffectPipeline(
            "battle_screen_shake", "battle/post/shake", "ShakeUniform"
    );
    public static final RenderPipeline BATTLE_SCREEN_FLASH = screenEffectPipeline(
            "battle_screen_flash", "battle/post/flash", "FlashUniform"
    );
    public static final RenderPipeline BATTLE_SCREEN_FOREGROUND_OVERLAY = RenderPipeline.builder()
            .withLocation(id("pipeline/battle_screen_foreground_overlay"))
            .withVertexShader(id("core/battle/post/fullscreen"))
            .withFragmentShader(id("core/battle/post/foreground_overlay"))
            .withSampler("SceneSampler")
            .withSampler("EnvironmentBackgroundSampler")
            .withSampler("BlueNoiseSampler")
            .withUniform("ForegroundOverlayUniform", UniformType.UNIFORM_BUFFER)
            .withVertexFormat(DefaultVertexFormat.EMPTY, VertexFormat.Mode.TRIANGLES)
            .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
            .withDepthWrite(false)
            .withCull(false)
            .build();

    private PipelineRegister() {
    }

    private static RenderPipeline drawPipeline(
            String location,
            String vertexShader,
            String fragmentShader,
            VertexFormat format,
            VertexFormat.Mode mode,
            PipelinePolicy policy,
            boolean sampler
    ) {
        RenderPipeline.Builder builder = RenderPipeline.builder()
                .withLocation(id("pipeline/" + location))
                .withVertexShader(id("core/" + vertexShader))
                .withFragmentShader(id("core/" + fragmentShader))
                .withUniform("DrawUniform", UniformType.UNIFORM_BUFFER)
                .withVertexFormat(format, mode)
                .withDepthTestFunction(policy.depthTest ? DepthTestFunction.LESS_DEPTH_TEST : DepthTestFunction.NO_DEPTH_TEST)
                .withDepthWrite(policy.depthWrite)
                .withCull(false);
        if (policy.blend) {
            builder.withBlend(BlendFunction.TRANSLUCENT);
        }
        if (sampler) {
            builder.withSampler("Sampler0");
        }
        return builder.build();
    }

    // 有限策略集合从类型入口排除 translucent + depth-write 组合。
    private enum PipelinePolicy {
        SCREEN(false, false, true),
        OPAQUE(true, true, false),
        CUTOUT(true, true, false),
        TRANSLUCENT(true, false, true);

        private final boolean depthTest;
        private final boolean depthWrite;
        private final boolean blend;

        PipelinePolicy(boolean depthTest, boolean depthWrite, boolean blend) {
            this.depthTest = depthTest;
            this.depthWrite = depthWrite;
            this.blend = blend;
        }
    }

    private static RenderPipeline postPipeline(String location, String fragmentShader) {
        return RenderPipeline.builder()
                .withLocation(id("pipeline/" + location))
                .withVertexShader(id("core/battle/post/fullscreen"))
                .withFragmentShader(id("core/" + fragmentShader))
                .withSampler("Sampler0")
                .withVertexFormat(DefaultVertexFormat.EMPTY, VertexFormat.Mode.TRIANGLES)
                .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
                .withDepthWrite(false)
                .withCull(false)
                .build();
    }

    private static RenderPipeline postProcessingPipeline(
            String location,
            String fragmentShader,
            String... samplers
    ) {
        RenderPipeline.Builder builder = RenderPipeline.builder()
                .withLocation(id("pipeline/" + location))
                .withVertexShader(id("core/battle/post/fullscreen"))
                .withFragmentShader(id("core/" + fragmentShader))
                .withUniform("PostProcessingUniform", UniformType.UNIFORM_BUFFER)
                .withVertexFormat(DefaultVertexFormat.EMPTY, VertexFormat.Mode.TRIANGLES)
                .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
                .withDepthWrite(false)
                .withCull(false);
        for (String sampler : samplers) {
            builder.withSampler(sampler);
        }
        return builder.build();
    }

    private static RenderPipeline screenEffectPipeline(String location, String fragmentShader, String uniform) {
        return RenderPipeline.builder()
                .withLocation(id("pipeline/" + location))
                .withVertexShader(id("core/battle/post/fullscreen"))
                .withFragmentShader(id("core/" + fragmentShader))
                .withSampler("Sampler0")
                .withUniform(uniform, UniformType.UNIFORM_BUFFER)
                .withVertexFormat(DefaultVertexFormat.EMPTY, VertexFormat.Mode.TRIANGLES)
                .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
                .withDepthWrite(false)
                .withCull(false)
                .build();
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MineTale.MODID, path);
    }

    @SubscribeEvent
    public static void onRegisterPipelines(RegisterRenderPipelinesEvent event) {
        event.registerPipeline(BATTLE_SOLID_SCREEN);
        event.registerPipeline(BATTLE_SOLID_WORLD);
        event.registerPipeline(BATTLE_SOLID_WORLD_TRANSLUCENT);
        event.registerPipeline(BATTLE_TEXTURE_WORLD_CUTOUT);
        event.registerPipeline(BATTLE_TEXTURE_WORLD_CUTOUT_QUADS);
        event.registerPipeline(BATTLE_TEXTURE_WORLD_TRANSLUCENT);
        event.registerPipeline(BATTLE_TEXTURE_SCREEN);
        event.registerPipeline(BATTLE_GLYPH_INTENSITY);
        event.registerPipeline(BATTLE_GLYPH_COLOR);
        event.registerPipeline(BATTLE_GLYPH_WORLD_INTENSITY);
        event.registerPipeline(BATTLE_GLYPH_WORLD_COLOR);
        event.registerPipeline(BATTLE_LINE);
        event.registerPipeline(BATTLE_LINE_TRANSLUCENT);
        event.registerPipeline(BattleAuxiliaryGrid.RENDERED_PIPELINE);
        event.registerPipeline(BattleFrame.UNMODIFIED_PIPELINE);
        event.registerPipeline(BattleFrame.RENDERED_PIPELINE);
        event.registerPipeline(BattleEntity.UNMODIFIED_CUTOUT_PIPELINE);
        event.registerPipeline(BattleEntity.UNMODIFIED_TRANSLUCENT_PIPELINE);
        event.registerPipeline(BattleEntity.RENDERED_CUTOUT_PIPELINE);
        event.registerPipeline(BattleEntity.RENDERED_TRANSLUCENT_PIPELINE);
        event.registerPipeline(BattleEntity.RENDERED_SPECULAR_CUTOUT_PIPELINE);
        event.registerPipeline(BattleEntity.RENDERED_SPECULAR_TRANSLUCENT_PIPELINE);
        event.registerPipeline(BattleEntity.EMISSIVE_BLOOM_SOURCE_PIPELINE);
        event.registerPipeline(BattleEntity.EMISSIVE_QUAD_BLOOM_SOURCE_PIPELINE);
        event.registerPipeline(BattleEntity.RENDERED_EXTRUDED_PIPELINE);
        event.registerPipeline(BattleEntity.SHADOW_PIPELINE);
        event.registerPipeline(BattleEntity.UNMODIFIED_RENDER_TYPE_PIPELINE);
        event.registerPipeline(BATTLE_POST_COPY);
        event.registerPipeline(BATTLE_POST_FXAA);
        event.registerPipeline(BATTLE_POST_BLOOM_DOWNSAMPLE);
        event.registerPipeline(BATTLE_POST_BLOOM_UPSAMPLE);
        event.registerPipeline(BATTLE_POST_BLOOM_COMPOSITE);
        event.registerPipeline(BATTLE_RENDER_MODE_CROSSFADE);
        event.registerPipeline(BATTLE_ENVIRONMENT_CAPTURE);
        event.registerPipeline(BATTLE_ENVIRONMENT_LOBE_EXTRACT);
        event.registerPipeline(BATTLE_ENVIRONMENT_FIELD_RESOLVE);
        event.registerPipeline(BATTLE_ENVIRONMENT_DEBUG);
        event.registerPipeline(BATTLE_ENVIRONMENT_BACKGROUND_EVALUATE);
        event.registerPipeline(BATTLE_ENVIRONMENT_BACKGROUND_COMPOSITE);
        event.registerPipeline(BATTLE_SHADOW_MINMAX_BASE);
        event.registerPipeline(BATTLE_SHADOW_MINMAX_REDUCE);
        event.registerPipeline(BATTLE_VOLUMETRIC_INTERVAL_TRACE);
        event.registerPipeline(BATTLE_VOLUMETRIC_POINT_ENTITY_SHADOW);
        event.registerPipeline(BATTLE_VOLUMETRIC_POINT_FRAME_SHADOW);
        event.registerPipeline(BATTLE_VOLUMETRIC_POINT_MINMAX_BASE);
        event.registerPipeline(BATTLE_VOLUMETRIC_POINT_MINMAX_REDUCE);
        event.registerPipeline(BATTLE_SCREEN_TRANSITION);
        event.registerPipeline(BATTLE_SCREEN_SHAKE);
        event.registerPipeline(BATTLE_SCREEN_FLASH);
        event.registerPipeline(BATTLE_SCREEN_FOREGROUND_OVERLAY);
    }

    @SubscribeEvent
    public static void onRegisterPipRenderers(RegisterPictureInPictureRenderersEvent event) {
        event.register(Renderer.State.class, Renderer::new);
    }
}
