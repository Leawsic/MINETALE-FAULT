package cn.jehorstudio.minetale.magic.visual.vfx;

import cn.jehorstudio.minetale.magic.Magic;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.RegisterRenderPipelinesEvent;

import java.util.HashMap;
import java.util.Map;

/** Magic 模型的屏幕像素屏门裁切 */
public final class MagicScreenDoor {
    private static final RenderPipeline BODY = pipeline(false);
    private static final RenderPipeline EMISSIVE = pipeline(true);
    private static final Map<ResourceLocation, RenderType> BODY_TYPES = new HashMap<>();
    private static final Map<ResourceLocation, RenderType> EMISSIVE_TYPES = new HashMap<>();

    private MagicScreenDoor() {}

    public static void register(IEventBus bus) {
        bus.addListener((RegisterRenderPipelinesEvent event) -> {
            event.registerPipeline(BODY);
            event.registerPipeline(EMISSIVE);
            try {
                // assignPipeline 仅登记主画面，漏掉阴影 pass 会让自定义 Shader 写错目标。
                Class<?> pipelines = Class.forName("net.irisshaders.iris.pipeline.IrisPipelines");
                var copy = pipelines.getMethod("copyPipeline", RenderPipeline.class, RenderPipeline.class);
                copy.invoke(null, RenderPipelines.ENTITY_CUTOUT, BODY);
                copy.invoke(null, RenderPipelines.EYES, EMISSIVE);
            } catch (ClassNotFoundException absent) {
                // 未安装 Iris 时直接使用原版核心 Shader。
            } catch (ReflectiveOperationException incompatible) {
                throw new IllegalStateException("Iris 屏门管线登记失败", incompatible);
            }
        });
    }

    private static RenderPipeline pipeline(boolean emissive) {
        var builder = RenderPipeline.builder(RenderPipelines.ENTITY_SNIPPET)
                .withLocation(Magic.id("pipeline/magic/screen_door" + (emissive ? "_emissive" : "")))
                .withFragmentShader(Magic.id("core/magic/model"))
                .withSampler("Sampler1");
        if (emissive) builder.withShaderDefine("EMISSIVE").withShaderDefine("NO_CARDINAL_LIGHTING");
        return builder.build();
    }

    /**
     * 获取共用的模型渲染类型，在客户端渲染线程调用。使用 NEW_ENTITY 顶点格式、背面剔除和深度写入。
     * 所有同屏表面共用屏幕像素点阵；原贴图 Alpha 小于 0.1 的孔洞仍被裁切。
     * @param texture 原模型贴图，按普通 TextureManager 生命周期加载和重载，不生成遮罩副本
     * @param emissive 是否忽略环境光与方向光，适用于发光眼睛
     * @return 按贴图和发光模式缓存的 RenderType；必须搭配 {@link #color(int, float)} 提交顶点颜色
     */
    public static RenderType renderType(ResourceLocation texture, boolean emissive) {
        return (emissive ? EMISSIVE_TYPES : BODY_TYPES).computeIfAbsent(texture, source ->
                RenderType.create("magic_screen_door" + (emissive ? "_emissive" : ""),
                        RenderType.SMALL_BUFFER_SIZE, false, false, emissive ? EMISSIVE : BODY,
                        RenderType.CompositeState.builder()
                                .setTextureState(new RenderStateShard.TextureStateShard(source, false))
                                .setLightmapState(RenderStateShard.LIGHTMAP)
                                .setOverlayState(RenderStateShard.OVERLAY).createCompositeState(false)));
    }

    /**
     * 将覆盖率写入顶点 Alpha，RGB 保持不变。Shader 把 Alpha 用于裁切，保留像素始终完全不透明。
     * @param argb 模型颜色，原 Alpha 被覆盖率替换
     * @param opacity 0..1 覆盖率，越界钳制，非有限值被拒绝；0 时调用方可跳过绘制
     * @return 供屏门 RenderType 使用的 ARGB 顶点颜色
     */
    public static int color(int argb, float opacity) {
        if (!Float.isFinite(opacity)) throw new IllegalArgumentException("屏门覆盖率必须有限");
        return (argb & 0x00FFFFFF) | (Math.round(Math.clamp(opacity, 0, 1) * 255) << 24);
    }
}
