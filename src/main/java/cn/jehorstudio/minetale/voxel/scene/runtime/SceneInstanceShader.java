package cn.jehorstudio.minetale.voxel.scene.runtime;

import com.mojang.blaze3d.opengl.GlProgram;
import com.mojang.blaze3d.pipeline.RenderPipeline;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

import org.lwjgl.opengl.GL43;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

// 只在渲染线程接管单次实例绘制；Iris 共用程序的开关与借用的 SSBO 槽在边界恢复。
public final class SceneInstanceShader {
    private record Binding(int enabled, int base, int slot) {}

    private record Saved(int buffer, long start, long size) {}

    private static final Map<GlProgram, Binding> BINDINGS = new WeakHashMap<>();
    private static Scope current;

    private SceneInstanceShader() {}

    static Scope begin(int buffer, int base) {
        if (current != null) throw new IllegalStateException("实例绘制不能嵌套");
        return current = new Scope(buffer, base);
    }

    static final class Scope implements AutoCloseable {
        final int buffer, previousBuffer;
        int base;
        final Map<Integer, Saved> saved = new HashMap<>();
        final Map<Integer, Integer> enabledUniforms = new HashMap<>();

        Scope(int buffer, int base) {
            this.buffer = buffer;
            this.base = base;
            previousBuffer = GL43.glGetInteger(GL43.GL_SHADER_STORAGE_BUFFER_BINDING);
        }

        @Override
        public void close() {
            enabledUniforms.forEach(
                    (program, uniform) -> GL43.glProgramUniform1i(program, uniform, 0));
            for (var entry : saved.entrySet()) {
                Saved value = entry.getValue();
                if (value.buffer != 0 && value.size > 0)
                    GL43.glBindBufferRange(
                            GL43.GL_SHADER_STORAGE_BUFFER,
                            entry.getKey(),
                            value.buffer,
                            value.start,
                            value.size);
                else
                    GL43.glBindBufferBase(
                            GL43.GL_SHADER_STORAGE_BUFFER, entry.getKey(), value.buffer);
            }
            GL43.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, previousBuffer);
            current = null;
        }
    }

    public static void setup(GlProgram program, RenderPipeline pipeline) {
        boolean enabled =
                current != null
                        && pipeline.getLocation().getNamespace().equals("minetale")
                        && pipeline.getLocation().getPath().equals("pipeline/scene/instances");
        Binding binding = BINDINGS.computeIfAbsent(program, SceneInstanceShader::binding);
        if (binding.enabled < 0) {
            if (enabled) throw new IllegalStateException("当前 terrain Shader 没有实例化输入");
            return;
        }
        GL43.glUniform1i(binding.enabled, enabled ? 1 : 0);
        if (!enabled) return;
        current.enabledUniforms.put(program.getProgramId(), binding.enabled);
        if (binding.slot < 0) throw new IllegalStateException("当前 terrain Shader 没有可用的实例 SSBO 槽");
        current.saved.computeIfAbsent(
                binding.slot,
                slot ->
                        new Saved(
                                GL43.glGetIntegeri(GL43.GL_SHADER_STORAGE_BUFFER_BINDING, slot),
                                GL43.glGetInteger64i(GL43.GL_SHADER_STORAGE_BUFFER_START, slot),
                                GL43.glGetInteger64i(GL43.GL_SHADER_STORAGE_BUFFER_SIZE, slot)));
        GL43.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, binding.slot, current.buffer);
        GL43.glUniform1i(binding.base, current.base);
    }

    private static Binding binding(GlProgram program) {
        int id = program.getProgramId();
        int enabled = GL43.glGetUniformLocation(id, "minetale_SceneInstancing");
        if (enabled < 0) return new Binding(-1, -1, -1);
        int block =
                GL43.glGetProgramResourceIndex(
                        id, GL43.GL_SHADER_STORAGE_BLOCK, "MinetaleSceneInstances");
        if (block == GL43.GL_INVALID_INDEX) return new Binding(enabled, -1, -1);
        boolean[] used = new boolean[GL43.glGetInteger(GL43.GL_MAX_SHADER_STORAGE_BUFFER_BINDINGS)];
        int count =
                GL43.glGetProgramInterfacei(
                        id, GL43.GL_SHADER_STORAGE_BLOCK, GL43.GL_ACTIVE_RESOURCES);
        int[] result = new int[1];
        for (int i = 0; i < count; i++)
            if (i != block) {
                GL43.glGetProgramResourceiv(
                        id,
                        GL43.GL_SHADER_STORAGE_BLOCK,
                        i,
                        new int[] {GL43.GL_BUFFER_BINDING},
                        null,
                        result);
                if (result[0] >= 0 && result[0] < used.length) used[result[0]] = true;
            }
        for (int slot = used.length - 1; slot >= 0; slot--)
            if (!used[slot]) {
                GL43.glShaderStorageBlockBinding(id, block, slot);
                return new Binding(
                        enabled, GL43.glGetUniformLocation(id, "minetale_SceneBase"), slot);
            }
        return new Binding(enabled, -1, -1);
    }

    public static String patch(String vertex) {
        if (vertex == null || !vertex.matches("(?s).*\\bin\\s+vec3\\s+iris_Position\\s*;.*"))
            return vertex;
        boolean normal = vertex.matches("(?s).*\\bin\\s+vec3\\s+iris_Normal\\s*;.*");
        boolean tangent = vertex.matches("(?s).*\\bin\\s+vec4\\s+at_tangent\\s*;.*");
        vertex = input(vertex, "iris_Position", "vec3", "minetale_ScenePosition");
        if (normal) vertex = input(vertex, "iris_Normal", "vec3", "minetale_SceneNormal");
        if (tangent) vertex = input(vertex, "at_tangent", "vec4", "minetale_SceneTangent");
        vertex = vertex.replaceFirst("void\\s+main\\s*\\(", "void minetale_sceneOriginalMain(");
        int versionEnd = vertex.indexOf('\n', vertex.indexOf("#version"));
        if (versionEnd < 0) throw new IllegalStateException("Iris vertex Shader 缺少版本声明");
        vertex =
                vertex.substring(0, versionEnd + 1)
                        + "#extension GL_ARB_shader_storage_buffer_object : require\n"
                        + vertex.substring(versionEnd + 1);
        try (var reader =
                Minecraft.getInstance()
                        .getResourceManager()
                        .openAsReader(
                                ResourceLocation.fromNamespaceAndPath(
                                        "minetale", "shaders/include/scene_instances.glsl"))) {
            String rules = reader.lines().collect(java.util.stream.Collectors.joining("\n"));
            vertex +=
                    "\n"
                            + rules
                            + "\nvoid main() { minetale_ScenePosition = iris_Position;\n"
                            + (normal
                                    ? "minetale_SceneNormal = iris_Normal;\n"
                                    : "vec3 minetale_SceneNormal = vec3(0,1,0);\n")
                            + (tangent
                                    ? "minetale_SceneTangent = at_tangent;\n"
                                    : "vec4 minetale_SceneTangent = vec4(1,0,0,1);\n")
                            + "minetale_sceneTransform(minetale_ScenePosition,"
                            + " minetale_SceneNormal, minetale_SceneTangent);\n"
                            + "minetale_sceneOriginalMain(); }\n";
            return vertex;
        } catch (IOException failure) {
            throw new IllegalStateException("无法读取实例化 Shader", failure);
        }
    }

    private static String input(String source, String name, String type, String replacement) {
        return source.replaceAll("\\b" + name + "\\b", replacement)
                .replaceFirst(
                        "\\bin\\s+" + type + "\\s+" + replacement + "\\s*;",
                        "in " + type + " " + name + "; " + type + " " + replacement + ";");
    }
}
