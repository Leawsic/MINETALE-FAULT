package cn.jehorstudio.minetale.dimension.ebott.transition.client.render.destination;

import cn.jehorstudio.minetale.MineTale;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.UniformType;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterRenderPipelinesEvent;

import java.lang.reflect.Method;

// 集中注册目标地形拼接与结界 MASK 的跨后端 RenderPipeline。
@EventBusSubscriber(modid = MineTale.MODID, value = Dist.CLIENT)
public final class DestinationPipelines {
    public static final RenderPipeline CLIPPED_SOLID = clippedTerrain("solid").build();
    public static final RenderPipeline CLIPPED_CUTOUT_MIPPED = clippedTerrain("cutout_mipped")
            .withShaderDefine("ALPHA_CUTOUT", 0.5F)
            .build();
    public static final RenderPipeline CLIPPED_CUTOUT = clippedTerrain("cutout")
            .withShaderDefine("ALPHA_CUTOUT", 0.1F)
            .build();

    private DestinationPipelines() {
    }

    @SubscribeEvent
    public static void register(RegisterRenderPipelinesEvent event) {
        event.registerPipeline(CLIPPED_SOLID);
        event.registerPipeline(CLIPPED_CUTOUT_MIPPED);
        event.registerPipeline(CLIPPED_CUTOUT);
        registerIrisTerrainPrograms();
    }

    public static RenderPipeline clippedTerrain(RenderPipeline original) {
        if (original == RenderPipelines.CUTOUT_MIPPED) {
            return CLIPPED_CUTOUT_MIPPED;
        }
        if (original == RenderPipelines.CUTOUT) {
            return CLIPPED_CUTOUT;
        }
        return CLIPPED_SOLID;
    }

    private static RenderPipeline.Builder clippedTerrain(String name) {
        return RenderPipeline.builder(RenderPipelines.TERRAIN_SNIPPET)
                .withLocation(id("pipeline/ebott/destination_" + name))
                .withVertexShader(id("core/ebott/destination_terrain"))
                .withFragmentShader(id("core/ebott/destination_terrain"))
                .withUniform("EbottBarrierMask", UniformType.UNIFORM_BUFFER)
                .withUniform("EbottSourceFog", UniformType.UNIFORM_BUFFER);
    }

    private static void registerIrisTerrainPrograms() {
        try {
            Class<?> irisApiClass = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            Class<?> irisProgramClass = Class.forName("net.irisshaders.iris.api.v0.IrisProgram");
            Object irisApi = irisApiClass.getMethod("getInstance").invoke(null);
            Method assignPipeline = irisApiClass.getMethod(
                    "assignPipeline", RenderPipeline.class, irisProgramClass);
            Object terrainSolid = irisProgramClass.getField("TERRAIN_SOLID").get(null);
            Object terrainCutout = irisProgramClass.getField("TERRAIN_CUTOUT").get(null);

            assignPipeline.invoke(irisApi, CLIPPED_SOLID, terrainSolid);
            assignPipeline.invoke(irisApi, CLIPPED_CUTOUT_MIPPED, terrainCutout);
            assignPipeline.invoke(irisApi, CLIPPED_CUTOUT, terrainCutout);
            MineTale.LOGGER.info("已将 STITCHED_TERRAIN 管线登记为 Iris terrain program");
        } catch (ClassNotFoundException absent) {
            // Iris 不存在时退回原版管线
        } catch (ReflectiveOperationException incompatibleApi) {
            MineTale.LOGGER.warn("Iris 已安装，但 STITCHED_TERRAIN terrain program 登记失败", incompatibleApi);
        }
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MineTale.MODID, path);
    }
}
