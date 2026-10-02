package cn.jehorstudio.minetale.lib.client.particle.gpu;

import cn.jehorstudio.minetale.MineTale;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.NativeImage;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.world.level.block.Blocks;

// 隔离 GPU 粒子对 Iris 可选 API、terrain 材质映射和内部 PBR loader 的版本边界。
final class GpuParticleIrisBridge {
    private static final int FLAT_NORMAL = 0xFF7F7FFF;
    private static final int FULL_EMISSION = 0xFE0000FF;

    private final Object api;
    private final Object settings;
    private final Object terrainCutout;
    private final Object pbrLoaderRegistry;
    private final Class<?> pbrLoaderType;
    private final Class<?> pbrConsumerType;
    private final Method active;
    private final Method assign;
    private final Method blockIds;
    private final Method copyPipeline;
    private final Method registerPbrLoader;
    private boolean pbrLoaderRegistered;

    static GpuParticleIrisBridge load() {
        Class<?> apiType;
        try {
            apiType = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
        } catch (ClassNotFoundException absent) {
            return null;
        }
        try {
            return new GpuParticleIrisBridge(apiType);
        } catch (ReflectiveOperationException failure) {
            MineTale.LOGGER.warn("Iris GPU particle integration unavailable", failure);
            return null;
        }
    }

    private GpuParticleIrisBridge(Class<?> apiType) throws ReflectiveOperationException {
        Class<?> programType = Class.forName("net.irisshaders.iris.api.v0.IrisProgram");
        Class<?> renderingType = Class.forName(
                "net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings"
        );
        Class<?> pipelinesType = Class.forName("net.irisshaders.iris.pipeline.IrisPipelines");
        Class<?> registryType = Class.forName(
                "net.irisshaders.iris.pbr.loader.PBRTextureLoaderRegistry"
        );
        this.pbrLoaderType = Class.forName("net.irisshaders.iris.pbr.loader.PBRTextureLoader");
        this.pbrConsumerType = Class.forName(
                "net.irisshaders.iris.pbr.loader.PBRTextureLoader$PBRTextureConsumer"
        );
        this.api = apiType.getMethod("getInstance").invoke(null);
        this.active = apiType.getMethod("isShaderPackInUse");
        this.assign = apiType.getMethod("assignPipeline", RenderPipeline.class, programType);
        @SuppressWarnings({"unchecked", "rawtypes"})
        Object cutout = Enum.valueOf(
                (Class<? extends Enum>) programType.asSubclass(Enum.class),
                "TERRAIN_CUTOUT"
        );
        this.terrainCutout = cutout;
        this.settings = renderingType.getField("INSTANCE").get(null);
        this.blockIds = renderingType.getMethod("getBlockStateIds");
        this.copyPipeline = pipelinesType.getMethod(
                "copyPipeline",
                RenderPipeline.class,
                RenderPipeline.class
        );
        this.pbrLoaderRegistry = registryType.getField("INSTANCE").get(null);
        this.registerPbrLoader = registryType.getMethod(
                "register",
                Class.class,
                this.pbrLoaderType
        );
    }

    boolean active() throws ReflectiveOperationException {
        return (boolean) this.active.invoke(this.api);
    }

    void copyFallbackPipeline(RenderPipeline fallback) throws ReflectiveOperationException {
        this.copyPipeline.invoke(null, RenderPipelines.ENTITY_CUTOUT_NO_CULL, fallback);
    }

    void assignTerrainCutout(RenderPipeline pipeline) throws ReflectiveOperationException {
        this.assign.invoke(this.api, pipeline, this.terrainCutout);
    }

    int materialId() throws ReflectiveOperationException {
        var ids = (Object2IntMap<?>) this.blockIds.invoke(this.settings);
        return ids == null ? -1 : ids.getOrDefault(Blocks.SEA_LANTERN.defaultBlockState(), -1);
    }

    void registerPbrLoader(Class<? extends AbstractTexture> textureType)
            throws ReflectiveOperationException {
        if (this.pbrLoaderRegistered) {
            return;
        }
        Object loader = Proxy.newProxyInstance(
                this.pbrLoaderType.getClassLoader(),
                new Class<?>[] {this.pbrLoaderType},
                (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return switch (method.getName()) {
                            case "toString" -> "MineTale GPU particle PBR loader";
                            case "hashCode" -> System.identityHashCode(proxy);
                            case "equals" -> proxy == args[0];
                            default -> null;
                        };
                    }
                    if (!method.getName().equals("load")) {
                        throw new UnsupportedOperationException(method.toString());
                    }
                    DynamicTexture normal = singlePixelTexture("normal", FLAT_NORMAL);
                    DynamicTexture specular = null;
                    try {
                        specular = singlePixelTexture("specular", FULL_EMISSION);
                        this.pbrConsumerType
                                .getMethod("acceptNormalTexture", AbstractTexture.class)
                                .invoke(args[2], normal);
                        this.pbrConsumerType
                                .getMethod("acceptSpecularTexture", AbstractTexture.class)
                                .invoke(args[2], specular);
                    } catch (Throwable failure) {
                        normal.close();
                        if (specular != null) {
                            specular.close();
                        }
                        throw failure;
                    }
                    return null;
                }
        );
        this.registerPbrLoader.invoke(this.pbrLoaderRegistry, textureType, loader);
        this.pbrLoaderRegistered = true;
    }

    private static DynamicTexture singlePixelTexture(String component, int argb) {
        NativeImage image = new NativeImage(1, 1, false);
        image.setPixel(0, 0, argb);
        return new DynamicTexture(() -> "MineTale GPU particle " + component, image);
    }
}
