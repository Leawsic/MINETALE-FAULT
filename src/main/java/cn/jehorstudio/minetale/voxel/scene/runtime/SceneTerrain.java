package cn.jehorstudio.minetale.voxel.scene.runtime;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;

import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.ResourceLocation;

import org.joml.Vector3f;

import java.nio.ByteBuffer;

// terrain 属性声明和顶点编码共同定义二进制布局；Iris 扩展与自定义切线保持同一顺序。
final class SceneTerrain {
    private static RenderPipeline terrain, instances;
    private static boolean irisChecked;
    private static Object irisApi;
    private static java.lang.reflect.Method shaderPackCheck;

    static boolean shaderPackInUse() {
        try {
            if (!irisChecked) {
                irisChecked = true;
                Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
                irisApi = api.getMethod("getInstance").invoke(null);
                shaderPackCheck = api.getMethod("isShaderPackInUse");
            }
            return shaderPackCheck != null && (boolean) shaderPackCheck.invoke(irisApi);
        } catch (ClassNotFoundException absent) {
            return false;
        } catch (ReflectiveOperationException failure) {
            shaderPackCheck = null;
            cn.jehorstudio.minetale.MineTale.LOGGER.warn(
                    "Scene Iris output state unavailable; using base output", failure);
            return false;
        }
    }

    private SceneTerrain() {}

    static RenderPipeline pipeline() {
        if (terrain != null) return terrain;
        var layout =
                VertexFormat.builder()
                        .add("Position", VertexFormatElement.POSITION)
                        .add("Color", VertexFormatElement.COLOR)
                        .add("UV0", VertexFormatElement.UV0)
                        .add("UV2", VertexFormatElement.UV2)
                        .add("Normal", VertexFormatElement.NORMAL)
                        .padding(1);
        var sceneTangent =
                VertexFormatElement.register(
                        VertexFormatElement.findNextId(),
                        0,
                        VertexFormatElement.Type.FLOAT,
                        VertexFormatElement.Usage.GENERIC,
                        4);
        Object iris = null;
        Class<?> api = null, program = null;
        try {
            api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            program = Class.forName("net.irisshaders.iris.api.v0.IrisProgram");
            iris = api.getMethod("getInstance").invoke(null);
            Class<?> formats = Class.forName("net.irisshaders.iris.vertices.IrisVertexFormats");
            // 光影输入采用浮点契约，沿用项目 terrain 的浮点转换。
            var material =
                    VertexFormatElement.register(
                            VertexFormatElement.findNextId(),
                            0,
                            VertexFormatElement.Type.FLOAT,
                            VertexFormatElement.Usage.GENERIC,
                            2);
            var midpoint =
                    VertexFormatElement.register(
                            VertexFormatElement.findNextId(),
                            0,
                            VertexFormatElement.Type.FLOAT,
                            VertexFormatElement.Usage.GENERIC,
                            3);
            layout.add("mc_Entity", material)
                    .add(
                            "mc_midTexCoord",
                            (VertexFormatElement) formats.getField("MID_TEXTURE_ELEMENT").get(null))
                    .add(
                            "at_tangent",
                            (VertexFormatElement) formats.getField("TANGENT_ELEMENT").get(null))
                    .add("at_midBlock", midpoint);
        } catch (ClassNotFoundException absent) {
            if (api != null) throw new IllegalStateException("Iris terrain 顶点 API 不兼容", absent);
        } catch (ReflectiveOperationException incompatible) {
            throw new IllegalStateException("Iris terrain 顶点 API 不兼容", incompatible);
        }
        // Iris 的共享 terrain program 按既定属性序号绑定扩展输入；额外属性追加在这些属性之后。
        layout.add("SceneTangent", sceneTangent);
        var pipeline =
                RenderPipeline.builder(RenderPipelines.TERRAIN_SNIPPET)
                        .withLocation(
                                ResourceLocation.fromNamespaceAndPath(
                                        "minetale", "pipeline/scene/terrain"))
                        .withVertexShader(
                                ResourceLocation.fromNamespaceAndPath(
                                        "minetale", "core/scene/terrain"))
                        .withFragmentShader(
                                ResourceLocation.fromNamespaceAndPath(
                                        "minetale", "core/scene/terrain"))
                        .withSampler("SceneEmission")
                        .withCull(true)
                        .withVertexFormat(layout.build(), VertexFormat.Mode.TRIANGLES)
                        .build();
        if (iris != null) {
            try {
                Class.forName("net.irisshaders.iris.pipeline.IrisPipelines")
                        .getMethod("copyPipeline", RenderPipeline.class, RenderPipeline.class)
                        .invoke(null, RenderPipelines.SOLID, pipeline);
            } catch (ReflectiveOperationException incompatible) {
                throw new IllegalStateException("Iris terrain 管线登记失败", incompatible);
            }
        }
        terrain = pipeline;
        return terrain;
    }

    static RenderPipeline instances() {
        if (instances != null) return instances;
        instances =
                RenderPipeline.builder(RenderPipelines.TERRAIN_SNIPPET)
                        .withLocation(
                                ResourceLocation.fromNamespaceAndPath(
                                        "minetale", "pipeline/scene/instances"))
                        .withVertexShader(
                                ResourceLocation.fromNamespaceAndPath(
                                        "minetale", "core/scene/instances"))
                        .withFragmentShader(
                                ResourceLocation.fromNamespaceAndPath(
                                        "minetale", "core/scene/terrain"))
                        .withSampler("SceneEmission")
                        .withCull(true)
                        .withVertexFormat(pipeline().getVertexFormat(), VertexFormat.Mode.TRIANGLES)
                        .build();
        try {
            Class.forName("net.irisshaders.iris.pipeline.IrisPipelines")
                    .getMethod("copyPipeline", RenderPipeline.class, RenderPipeline.class)
                    .invoke(null, RenderPipelines.SOLID, instances);
        } catch (ClassNotFoundException absent) {
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Iris 实例管线登记失败", failure);
        }
        return instances;
    }

    static void encode(ByteBuffer out, float[] data, boolean raw, boolean extended) {
        encode(out, data, raw, extended, 0, data.length / (raw ? 8 : 14));
    }

    static void encode(
            ByteBuffer out, float[] data, boolean raw, boolean extended, int first, int count) {
        encode(out, data, raw, extended, first, count, 1, 0, 0);
    }

    static void encode(
            ByteBuffer out,
            float[] data,
            boolean raw,
            boolean extended,
            int first,
            int count,
            float scale,
            float offsetU,
            float offsetV) {
        int floats = raw ? 8 : 14;
        Vector3f tangent = new Vector3f(), bitangent = new Vector3f(), normal = new Vector3f();
        Vector3f orthogonal = new Vector3f(), cross = new Vector3f();
        int primitiveVertices = raw ? 3 : 1;
        for (int t = first * floats;
                t < (first + count) * floats;
                t += floats * primitiveVertices) {
            if (raw) {
                tangent.set(
                        data[t + 8] - data[t],
                        data[t + 9] - data[t + 1],
                        data[t + 10] - data[t + 2]);
                bitangent.set(
                        data[t + 16] - data[t + 8],
                        data[t + 17] - data[t + 9],
                        data[t + 18] - data[t + 10]);
            }
            for (int corner = 0; corner < primitiveVertices; corner++) {
                int v = t + corner * floats;
                normal.set(data[v + 5], data[v + 6], data[v + 7]);
                out.putFloat(data[v])
                        .putFloat(data[v + 1])
                        .putFloat(data[v + 2])
                        .putInt(-1)
                        .putFloat((raw ? (corner == 0 ? 0 : 1) : data[v + 3]) * scale + offsetU)
                        .putFloat((raw ? (corner == 2 ? 1 : 0) : data[v + 4]) * scale + offsetV)
                        // 场景使用天空光 15、方块光 0；自发光由材质图提供。
                        .putInt(LightTexture.pack(0, 15));
                putNormal(out, normal);
                out.put((byte) 0);
                float handedness;
                if (raw) {
                    orthogonal.set(tangent).fma(-normal.dot(tangent), normal);
                    if (orthogonal.lengthSquared() < 1.0e-12F) {
                        orthogonal
                                .set(
                                        Math.abs(normal.y) < .9F ? 0 : 1,
                                        Math.abs(normal.y) < .9F ? 1 : 0,
                                        0)
                                .cross(normal);
                    }
                    orthogonal.normalize();
                    // Iris 的副切线约定为 cross(T, N) * handedness。
                    handedness = orthogonal.cross(normal, cross).dot(bitangent) < 0 ? -1 : 1;
                } else {
                    orthogonal.set(data[v + 8], data[v + 9], data[v + 10]);
                    handedness = data[v + 11];
                }
                if (extended) {
                    out.putFloat(-1)
                            .putFloat(1)
                            .putFloat((raw ? .5F : data[v + 12]) * scale + offsetU)
                            .putFloat((raw ? .5F : data[v + 13]) * scale + offsetV);
                    putNormal(out, orthogonal);
                    out.put((byte) (handedness * 127));
                    out.putFloat(0).putFloat(0).putFloat(0);
                }
                out.putFloat(orthogonal.x)
                        .putFloat(orthogonal.y)
                        .putFloat(orthogonal.z)
                        .putFloat(handedness);
            }
        }
    }

    private static void putNormal(ByteBuffer out, Vector3f normal) {
        out.put((byte) Math.round(normal.x * 127))
                .put((byte) Math.round(normal.y * 127))
                .put((byte) Math.round(normal.z * 127));
    }
}
