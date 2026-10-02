package cn.jehorstudio.minetale.voxel.scene.runtime;

import cn.jehorstudio.minetale.voxel.scene.geometry.SceneVoxels;

import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.textures.GpuTexture;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL43;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

// 公共程序在共享上下文编译；渲染线程分批生成材质，结果写入 terrain 使用的纹理。
final class SceneMaterials implements AutoCloseable {
    private final java.util.Map<Integer, Integer> programs = new java.util.concurrent.ConcurrentHashMap<>();
    private final Set<Integer> requested = new HashSet<>();
    private final java.util.concurrent.BlockingQueue<Integer> requests = new java.util.concurrent.LinkedBlockingQueue<>();
    private final String shaderSource;
    private final boolean background;
    private volatile boolean closed;
    private volatile Throwable compilationFailure;
    private final long[] gpuTimes = new long[600];
    private int gpuSamples;
    private long temporaryBytes;
    private static final java.util.concurrent.atomic.AtomicLong ALL_TEMPORARY = new java.util.concurrent.atomic.AtomicLong();
    static long allTemporaryBytes() { return ALL_TEMPORARY.get(); }
    private static final java.util.concurrent.atomic.AtomicLong ALL_STAGING = new java.util.concurrent.atomic.AtomicLong();
    static long allStagingBytes() { return ALL_STAGING.get(); }


    private final java.util.ArrayList<Buffers> pooledBuffers = new java.util.ArrayList<>();
    private long pooledBytes;

    // fence 完成后按实际容量复用 SSBO；池最多四组、合计 32 MiB，容量计入临时区。
    private final class Buffers implements AutoCloseable {
        final int[] ids = new int[4];
        final long[] sizes;
        final long bytes;
        ByteBuffer staging;
        Buffers(long[] sizes, long bytes) {
            this.sizes = sizes; this.bytes = bytes;
            temporaryBytes += bytes;
            try (State ignored = new State()) {
                staging = MemoryUtil.memAlloc(256 * 1024);
                ALL_STAGING.addAndGet(staging.capacity());
                for (int i = 0; i < 4; i++) {
                    ids[i] = GL43.glGenBuffers(); GL43.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, ids[i]);
                    GL43.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, sizes[i], GL43.GL_STREAM_DRAW);
                }
            } catch (RuntimeException | Error failure) { close(); throw failure; }
        }
        boolean fits(long[] requested, long total) {
            if (bytes > Math.max(16384, total * 2)) return false;
            for (int i = 0; i < 4; i++) if (sizes[i] < requested[i]) return false;
            return true;
        }
        public void close() {
            for (int id : ids) if (id != 0) GL43.glDeleteBuffers(id);
            java.util.Arrays.fill(ids, 0);
            if (staging != null) { ALL_STAGING.addAndGet(-staging.capacity()); MemoryUtil.memFree(staging); staging = null; }
            temporaryBytes -= bytes; ALL_TEMPORARY.addAndGet(-bytes);
        }
    }

    private Buffers acquireBuffers(SceneVoxels.Page page) {
        long[] sizes = { Math.max(16, (long) page.samples().length * 4), Math.max(16, (long) page.texels().length * 4),
                Math.max(16, (long) page.samples().length / SceneVoxels.SAMPLE_FLOATS * (page.shaderPack() ? 20 : 8)),
                Math.max(16, (long) page.materialConstants().length * 4) };
        long bytes = java.util.Arrays.stream(sizes).sum();
        Buffers best = null;
        for (Buffers candidate : pooledBuffers)
            if (candidate.fits(sizes, bytes) && (best == null || candidate.bytes < best.bytes)) best = candidate;
        if (best != null) {
            pooledBuffers.remove(best);
            pooledBytes -= best.bytes;
            return best;
        }
        for (int i = 0; i < sizes.length; i++) sizes[i] = (sizes[i] + 4095) / 4096 * 4096;
        bytes = java.util.Arrays.stream(sizes).sum();
        ALL_TEMPORARY.addAndGet(bytes);
        return new Buffers(sizes, bytes);
    }

    private void releaseBuffers(Buffers buffers) {
        if (!closed && buffers.bytes <= 16 * (1L << 20)) {
            while (!pooledBuffers.isEmpty() && (pooledBuffers.size() >= 4
                    || pooledBytes + buffers.bytes > 32 * (1L << 20))) {
                Buffers oldest = pooledBuffers.removeFirst();
                pooledBytes -= oldest.bytes;
                oldest.close();
            }
            pooledBuffers.add(buffers);
            pooledBytes += buffers.bytes;
        } else buffers.close();
    }

    SceneMaterials() throws IOException {
        checkCapabilities();
        shaderSource = source(Minecraft.getInstance().getResourceManager());
        background = true;
        // 驱动的 compile/link 提交会阻塞；独立共享上下文将整段编译移出游戏渲染线程。
        long context = createContext();
        Thread compiler =
                Thread.ofPlatform()
                        .daemon()
                        .name("MineTale material compiler")
                        .unstarted(() -> compile(context));
        try {
            compiler.start();
        } catch (RuntimeException | Error failure) {
            org.lwjgl.glfw.GLFW.glfwDestroyWindow(context);
            throw failure;
        }
    }

    SceneMaterials(String source) throws IOException {
        checkCapabilities();
        shaderSource = source;
        background = false;
    }

    static long createContext() throws IOException {
        long main = org.lwjgl.glfw.GLFW.glfwGetCurrentContext();
        org.lwjgl.glfw.GLFW.glfwDefaultWindowHints();
        int[] attributes = {
            org.lwjgl.glfw.GLFW.GLFW_CLIENT_API,
            org.lwjgl.glfw.GLFW.GLFW_CONTEXT_CREATION_API,
            org.lwjgl.glfw.GLFW.GLFW_CONTEXT_VERSION_MAJOR,
            org.lwjgl.glfw.GLFW.GLFW_CONTEXT_VERSION_MINOR,
            org.lwjgl.glfw.GLFW.GLFW_OPENGL_PROFILE,
            org.lwjgl.glfw.GLFW.GLFW_OPENGL_FORWARD_COMPAT
        };
        for (int attribute : attributes)
            org.lwjgl.glfw.GLFW.glfwWindowHint(
                    attribute, org.lwjgl.glfw.GLFW.glfwGetWindowAttrib(main, attribute));
        org.lwjgl.glfw.GLFW.glfwWindowHint(
                org.lwjgl.glfw.GLFW.GLFW_VISIBLE, org.lwjgl.glfw.GLFW.GLFW_FALSE);
        org.lwjgl.glfw.GLFW.glfwWindowHint(
                org.lwjgl.glfw.GLFW.GLFW_FOCUSED, org.lwjgl.glfw.GLFW.GLFW_FALSE);
        long context;
        try {
            context =
                    org.lwjgl.glfw.GLFW.glfwCreateWindow(
                            1, 1, "MineTale material compiler", 0, main);
        } finally {
            org.lwjgl.glfw.GLFW.glfwDefaultWindowHints();
        }
        if (context == 0) throw new IOException("无法创建材质编译共享上下文");
        return context;
    }

    private static void checkCapabilities() throws IOException {
        var capabilities = GL.getCapabilities();
        if (capabilities.glDispatchCompute == 0
                || capabilities.glBindImageTexture == 0
                || capabilities.glShaderStorageBlockBinding == 0
                || capabilities.glMemoryBarrier == 0)
            throw new IOException("材质需要 compute、image load/store 和 SSBO 支持");
    }

    static String source(net.minecraft.server.packs.resources.ResourceManager resources)
            throws IOException {
        return read(
                resources,
                ResourceLocation.fromNamespaceAndPath("minetale", "shaders/voxel/core_bake.comp"),
                new HashSet<>());
    }

    private static int compileProgram(String source) throws IOException {
        int shader = GL43.glCreateShader(GL43.GL_COMPUTE_SHADER), result = 0;
        try {
            GL43.glShaderSource(shader, source);
            GL43.glCompileShader(shader);
            result = GL43.glCreateProgram();
            GL43.glAttachShader(result, shader);
            GL43.glLinkProgram(result);
            if (GL43.glGetProgrami(result, GL43.GL_LINK_STATUS) == 0)
                throw new IOException(
                        GL43.glGetShaderInfoLog(shader) + "\n" + GL43.glGetProgramInfoLog(result));
            GL43.glDetachShader(result, shader);
            return result;
        } catch (IOException | RuntimeException failure) {
            if (result != 0) GL43.glDeleteProgram(result);
            throw failure;
        } finally {
            GL43.glDeleteShader(shader);
        }
    }

    private void compile(long context) {
        try {
            org.lwjgl.glfw.GLFW.glfwMakeContextCurrent(context);
            GL.createCapabilities();
            while (!closed) {
                int key = requests.take();
                if (key < 0) break;
                int result = compileProgram(specialize(shaderSource, key));
                GL43.glFinish();
                synchronized (programs) {
                    if (closed) GL43.glDeleteProgram(result);
                    else programs.put(key, result);
                }
            }
        } catch (Throwable failure) {
            compilationFailure = failure;
        } finally {
            org.lwjgl.glfw.GLFW.glfwMakeContextCurrent(0);
            GL.setCapabilities(null);
            // GLFW 窗口创建和销毁归渲染线程；场景关闭时也要回收后台完成的 program。
            Minecraft.getInstance()
                    .execute(
                            () -> org.lwjgl.glfw.GLFW.glfwDestroyWindow(context));
        }
    }

    boolean ready() throws IOException {
        if (compilationFailure != null) throw new IOException("精细材质编译失败", compilationFailure);
        return !closed;
    }

    boolean ready(SceneVoxels.Page page) throws IOException {
        ready();
        boolean complete = request(page.shaderPack() ? 32 : 0);
        if (!page.prebaked()) for (int mask = 0; mask < 32; mask++)
            if (page.sampleRanges()[mask] != page.sampleRanges()[mask+1]) complete &= request(mask | (page.shaderPack() ? 32 : 0));
        return complete;
    }

    private boolean request(int key) throws IOException {
        if (programs.containsKey(key)) return true;
        if (requested.add(key)) {
            if (background) requests.add(key);
            else programs.put(key, compileProgram(specialize(shaderSource, key)));
        }
        return programs.containsKey(key);
    }

    // 生成器输出为 SSA；按最终输出回溯依赖
    static String specialize(String source, int key) throws IOException {
        int mask = key & 31;
        var functions = Pattern.compile("CoreMaterial coreMaterial[0-9]+\\([^)]*\\) \\{(.*?)\\n\\}", Pattern.DOTALL).matcher(source);
        StringBuffer output = new StringBuffer();
        while (functions.find()) {
            String body = functions.group(1);
            int start = body.lastIndexOf("return CoreMaterial(");
            if (start < 0) throw new IOException("程序材质缺少 SSA 返回值");
            String expression = body.substring(start + "return CoreMaterial(".length(), body.lastIndexOf(");"));
            var arguments = new java.util.ArrayList<String>();
            int depth = 0, begin = 0;
            for (int i = 0; i < expression.length(); i++) {
                char c = expression.charAt(i);
                if (c == '(') depth++; else if (c == ')') depth--;
                else if (c == ',' && depth == 0) { arguments.add(expression.substring(begin, i).trim()); begin = i+1; }
            }
            arguments.add(expression.substring(begin).trim());
            if (arguments.size() != 9) throw new IOException("程序材质输出字段变化");
            int[] requirements = {1,4,8,16,16,1,2,2,2};
            String[] fallback = {"vec3(0)","1.0","0.0","vec3(0)","0.0","1.0","0.0","0.0","0.0"};
            for (int i = 0; i < 9; i++) if ((mask & requirements[i]) == 0) arguments.set(i, fallback[i]);
            String result = "    return CoreMaterial(" + String.join(", ", arguments) + ");";
            Set<String> needed = new HashSet<>();
            variables(result, needed);
            String[] lines = body.substring(0, start).split("\\n");
            var kept = new java.util.ArrayList<String>();
            Pattern assignment = Pattern.compile("\\s*precise \\w+ (v[0-9]+) = (.*);");
            for (int i = lines.length-1; i >= 0; i--) {
                var declaration = assignment.matcher(lines[i]);
                if (declaration.matches()) {
                    if (needed.contains(declaration.group(1))) { kept.add(lines[i]); variables(declaration.group(2), needed); }
                } else if (!lines[i].isBlank()) kept.add(lines[i]);
            }
            java.util.Collections.reverse(kept);
            String header = functions.group().substring(0, functions.group().indexOf('{')+1);
            functions.appendReplacement(output, java.util.regex.Matcher.quoteReplacement(header + "\n" + String.join("\n", kept) + "\n" + result + "\n}"));
        }
        functions.appendTail(output);
        return output.toString().replace("#version 430", "#version 430\n#define SAMPLE_MASK " + mask + "\n#define SHADER_PACK " + ((key & 32) != 0 ? 1 : 0));
    }
    private static void variables(String expression, Set<String> result) {
        var names = Pattern.compile("\\bv[0-9]+\\b").matcher(expression);
        while (names.find()) result.add(names.group());
    }

    static String colorDependencies(String source) throws IOException {
        String specialized = specialize(source, 17);
        var functions = new java.util.LinkedHashMap<String, String>();
        var matcher = Pattern.compile("(?m)^\\w+ (\\w+)\\([^;{}]*\\)\\s*\\{").matcher(specialized);
        StringBuilder globals = new StringBuilder();
        int previous = 0;
        while (matcher.find()) {
            int end = matcher.end(), depth = 1;
            while (end < specialized.length() && depth > 0) {
                char c = specialized.charAt(end++); if (c == '{') depth++; else if (c == '}') depth--;
            }
            globals.append(specialized, previous, matcher.start());
            functions.merge(matcher.group(1), specialized.substring(matcher.start(), end), (a, b) -> a + "\n" + b);
            previous = end; matcher.region(end, specialized.length());
        }
        globals.append(specialized, previous, specialized.length());
        Set<String> needed = new java.util.TreeSet<>();
        var pending = new java.util.ArrayDeque<String>(java.util.List.of("coreMaterial", "coreToMaterialSpace", "srgb", "linearColor"));
        while (!pending.isEmpty()) {
            String name = pending.removeFirst();
            if (!needed.add(name)) continue;
            String body = functions.get(name);
            if (body == null) throw new IOException("颜色依赖入口缺失：" + name);
            var calls = Pattern.compile("\\b(\\w+)\\s*\\(").matcher(body);
            while (calls.find()) if (functions.containsKey(calls.group(1)) && !needed.contains(calls.group(1))) pending.addLast(calls.group(1));
        }
        for (String name : needed) globals.append(functions.get(name));
        return globals.toString();
    }

    Bake begin(
            SceneVoxels.Page page,
            GpuTexture color,
            GpuTexture normal,
            GpuTexture specular,
            int x,
            int y)
            throws IOException {
        return begin(page, color, normal, specular, null, x, y);
    }

    Bake begin(SceneVoxels.Page page, GpuTexture color, GpuTexture normal, GpuTexture specular, GpuTexture emission, int x, int y) throws IOException {
        GpuTexture[] channels = {color, normal, specular, emission}; int[] ids = new int[4];
        for (int c = 0; c < 4; c++) if (channels[c] != null) {
            if (!(channels[c] instanceof GlTexture texture)) throw new IOException("精细材质需要 OpenGL 纹理后端");
            ids[c] = texture.glId();
        }
        return begin(page, ids, x, y);
    }


    Bake begin(SceneVoxels.Page page, int[] textures, int x, int y) {
        Buffers buffers = acquireBuffers(page);
        return new Bake(page, textures, x, y, buffers);
    }

    final class Bake implements AutoCloseable {
        private final SceneVoxels.Page page;
        private final int[] textures, buffers;
        private Buffers storage;
        private final int x, y;
        private ByteBuffer staging;
        private int input, offset, phase, index;
        private long fence;
        private final int beginQuery = GL43.glGenQueries(), endQuery = GL43.glGenQueries();
        long uploaded;
        private boolean complete;
        private int previousColor, previousEmission, previousX, previousY;

        void reuseColor(int color, int emission, int x, int y) {
            previousColor = color; previousEmission = emission; previousX = x; previousY = y;
        }

        Bake(SceneVoxels.Page page, int[] textures, int x, int y, Buffers storage) {
            this.page = page; this.textures = textures; this.x = x; this.y = y;
            this.storage = storage; this.buffers = storage.ids; this.staging = storage.staging;
        }

        boolean advance() throws IOException {
            uploaded = 0;
            if (complete) return true;
            if (fence != 0) {
                int status = GL43.glClientWaitSync(fence, 0, 0);
                if (status == GL43.GL_TIMEOUT_EXPIRED) return false;
                if (status == GL43.GL_WAIT_FAILED) throw new IOException("材质 GPU fence 查询失败");
                if (GL43.glGetQueryObjecti(endQuery, GL43.GL_QUERY_RESULT_AVAILABLE) == 0) return false;
                GL43.glDeleteSync(fence);
                fence = 0;
                long elapsed =
                        GL43.glGetQueryObjectui64(endQuery, GL43.GL_QUERY_RESULT)
                                - GL43.glGetQueryObjectui64(beginQuery, GL43.GL_QUERY_RESULT);
                gpuTimes[gpuSamples++ % gpuTimes.length] = elapsed;
                if (phase == 3) {
                    complete = true;
                    return true;
                }
            }
            if (!ready(page)) return false;
            try (State ignored = new State()) {
                while (input < 3) {
                    if (!uploadInput()) return false;
                    if (uploaded >= 512 * 1024 && input < 3) return false;
                }
                return dispatchBatch();
            }
        }

        private boolean uploadInput() {
            int length = input == 0 ? page.samples().length : input == 1 ? page.texels().length : page.materialConstants().length;
            int count = Math.min(length - offset, staging.capacity() / 4);
            long start = System.nanoTime();
            if (count > 0) {
                staging.clear().limit(count * 4);
                if (input == 0) staging.asFloatBuffer().put(page.samples(), offset, count);
                else if (input == 1) staging.asIntBuffer().put(page.texels(), offset, count);
                else staging.asFloatBuffer().put(page.materialConstants(), offset, count);
                GL43.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, buffers[input == 2 ? 3 : input]);
                GL43.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, (long) offset * 4, staging);
                offset += count;
                uploaded += (long) count * 4;
            }
            if (offset == length) {
                input++;
                offset = 0;
            }
            return true;
        }

        // phase 与 compute shader 的 mip 参数共用编号：求值、散布、mip、完成。
        private boolean dispatchBatch() {
            if (phase == 0 && page.samples().length == 0) phase = 1;
            for (int i = 0; i < 4; i++) {
                GL43.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, i, buffers[i]);
                GL43.glBindImageTexture(
                        i, i < textures.length ? textures[i] : 0, 0, false, 0, GL43.GL_READ_WRITE, i == 3 ? GL43.GL_R8 : GL43.GL_RGBA8);
                GL43.glBindImageTexture(
                        i + 4, i < textures.length ? textures[i] : 0, 1, false, 0, GL43.GL_WRITE_ONLY, i == 3 ? GL43.GL_R8 : GL43.GL_RGBA8);
            }
            int mask = 0;
            if (phase == 0 && !page.prebaked()) while (mask < 31 && index >= page.sampleRanges()[mask+1]) mask++;
            int program = programs.get(mask | (page.shaderPack() ? 32 : 0));
            GL43.glUseProgram(program);
            GL43.glUniform1i(GL43.glGetUniformLocation(program, "reuseColor"), page.reusesColor() ? 1 : 0);
            GL43.glUniform1i(GL43.glGetUniformLocation(program, "previousColor"), 0);
            GL43.glUniform1i(GL43.glGetUniformLocation(program, "previousEmission"), 1);
            GL43.glUniform2i(GL43.glGetUniformLocation(program, "previousOrigin"), previousX, previousY);
            GL43.glActiveTexture(GL43.GL_TEXTURE0); GL43.glBindTexture(GL43.GL_TEXTURE_2D, previousColor);
            GL43.glActiveTexture(GL43.GL_TEXTURE1); GL43.glBindTexture(GL43.GL_TEXTURE_2D, previousEmission);
            GL43.glUniform1i(
                    GL43.glGetUniformLocation(program, "prebakedInput"), page.prebaked() ? 1 : 0);
            GL43.glUniform1f(
                    GL43.glGetUniformLocation(program, "textureStep"),
                    1F / page.precision().normal());
            int total =
                    phase == 0
                            ? page.samples().length / SceneVoxels.SAMPLE_FLOATS
                            : page.maximumSize() * page.maximumSize() / (phase == 2 ? 4 : 1);
            // dispatch 分段遵循当前设备的单轴工作组数量限制。
            long capacity =
                    (long) GL43.glGetIntegeri(GL43.GL_MAX_COMPUTE_WORK_GROUP_COUNT, 0) * 128;
            // 固定批次限制单次 compute 的工作量，阶段完成后再进入下一阶段。
            capacity = Math.min(capacity, 131_072);
            int end = (int) Math.min(total, index + capacity);
            if (phase == 0 && !page.prebaked()) end = Math.min(end, page.sampleRanges()[mask+1]);
            GL43.glUniform1i(GL43.glGetUniformLocation(program, "mip"), phase);
            GL43.glUniform1i(
                    GL43.glGetUniformLocation(program, "sampleCount"), page.samples().length / SceneVoxels.SAMPLE_FLOATS);
            GL43.glUniform1i(GL43.glGetUniformLocation(program, "tileSize"), page.maximumSize());
            GL43.glUniform2i(GL43.glGetUniformLocation(program, "tileOrigin"), x, y);
            GL43.glUniform4i(
                    GL43.glGetUniformLocation(program, "channelSizes"),
                    page.channelSizes()[0],
                    page.channelSizes()[1],
                    page.channelSizes()[2], page.channelSizes()[3]);
            GL43.glUniform4i(
                    GL43.glGetUniformLocation(program, "channelOffsets"),
                    page.channelOffsets()[0],
                    page.channelOffsets()[1],
                    page.channelOffsets()[2], page.channelOffsets()[3]);
            GL43.glUniform4i(GL43.glGetUniformLocation(program, "outputSizes"),
                    page.outputSize(0), page.outputSize(1), page.outputSize(2), page.outputSize(3));
            GL43.glUniform1ui(GL43.glGetUniformLocation(program, "firstIndex"), index);
            GL43.glUniform1ui(GL43.glGetUniformLocation(program, "endIndex"), end);
            GL43.glQueryCounter(beginQuery, GL43.GL_TIMESTAMP);
            if (end > index) GL43.glDispatchCompute((int) (((long) end - index + 127) / 128), 1, 1);
            index = end;
            if (index == total) {
                GL43.glMemoryBarrier(
                        GL43.GL_SHADER_STORAGE_BARRIER_BIT
                                | GL43.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT
                                | GL43.GL_TEXTURE_FETCH_BARRIER_BIT);
                phase++;
                index = 0;
            }
            GL43.glQueryCounter(endQuery, GL43.GL_TIMESTAMP);
            fence = GL43.glFenceSync(GL43.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
            GL43.glFlush();
            return false;
        }

        @Override
        public void close() {
            // 主 context 在退休 fence 后调用；独立 context 取消时由该工作线程等待尾部写入，计账到实际结束。
            if (fence != 0) {
                int status;
                while ((status = GL43.glClientWaitSync(fence, GL43.GL_SYNC_FLUSH_COMMANDS_BIT, 0)) == GL43.GL_TIMEOUT_EXPIRED)
                    java.util.concurrent.locks.LockSupport.parkNanos(100_000);
                if (status == GL43.GL_WAIT_FAILED) throw new IllegalStateException("材质资源退休 fence 查询失败");
            }
            if (fence != 0) {
                GL43.glDeleteSync(fence);
                fence = 0;
            }
            if (storage != null) { releaseBuffers(storage); storage = null; staging = null; }
            GL43.glDeleteQueries(beginQuery);
            GL43.glDeleteQueries(endQuery);
        }
    }

    String stats() {
        long[] values = java.util.Arrays.copyOf(gpuTimes, Math.min(gpuSamples, gpuTimes.length));
        java.util.Arrays.sort(values);
        return String.format(
                java.util.Locale.ROOT,
                "gpuBakeP95/max=%.3f/%.3fms gpuTempMiB=%.2f",
                values.length == 0 ? 0 : values[(int) ((values.length - 1) * .95)] / 1e6,
                values.length == 0 ? 0 : values[values.length - 1] / 1e6,
                temporaryBytes / 1048576.0);
    }

    long temporaryBytes() { return temporaryBytes; }

    // 原始 GL 调用必须完整恢复索引缓冲和 image 单元。
    private static final class State implements AutoCloseable {
        final int program = GL43.glGetInteger(GL43.GL_CURRENT_PROGRAM);
        final int buffer = GL43.glGetInteger(GL43.GL_SHADER_STORAGE_BUFFER_BINDING);
        final int[] indexed = new int[4];
        final long[] starts = new long[4], sizes = new long[4];
        final int[][] images = new int[8][6];
        final int activeTexture = GL43.glGetInteger(GL43.GL_ACTIVE_TEXTURE);
        final int[] textures = new int[2];

        State() {
            for (int unit = 0; unit < 2; unit++) {
                GL43.glActiveTexture(GL43.GL_TEXTURE0 + unit);
                textures[unit] = GL43.glGetInteger(GL43.GL_TEXTURE_BINDING_2D);
            }
            GL43.glActiveTexture(activeTexture);
            for (int unit = 0; unit < 4; unit++) {
                indexed[unit] = GL43.glGetIntegeri(GL43.GL_SHADER_STORAGE_BUFFER_BINDING, unit);
                starts[unit] = GL43.glGetInteger64i(GL43.GL_SHADER_STORAGE_BUFFER_START, unit);
                sizes[unit] = GL43.glGetInteger64i(GL43.GL_SHADER_STORAGE_BUFFER_SIZE, unit);
            }
            int[] properties = {
                GL43.GL_IMAGE_BINDING_NAME,
                GL43.GL_IMAGE_BINDING_LEVEL,
                GL43.GL_IMAGE_BINDING_LAYERED,
                GL43.GL_IMAGE_BINDING_LAYER,
                GL43.GL_IMAGE_BINDING_ACCESS,
                GL43.GL_IMAGE_BINDING_FORMAT
            };
            for (int unit = 0; unit < 8; unit++)
                for (int k = 0; k < 6; k++)
                    images[unit][k] = GL43.glGetIntegeri(properties[k], unit);
        }

        @Override
        public void close() {
            for (int unit = 0; unit < 2; unit++) {
                GL43.glActiveTexture(GL43.GL_TEXTURE0 + unit);
                GL43.glBindTexture(GL43.GL_TEXTURE_2D, textures[unit]);
            }
            GL43.glActiveTexture(activeTexture);
            GL43.glUseProgram(program);
            for (int unit = 0; unit < 8; unit++) {
                int[] saved = images[unit];
                GL43.glBindImageTexture(
                        unit, saved[0], saved[1], saved[2] != 0, saved[3], saved[4], saved[5]);
            }
            for (int unit = 0; unit < 4; unit++) {
                if (indexed[unit] != 0 && sizes[unit] > 0)
                    GL43.glBindBufferRange(
                            GL43.GL_SHADER_STORAGE_BUFFER,
                            unit,
                            indexed[unit],
                            starts[unit],
                            sizes[unit]);
                else GL43.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, unit, indexed[unit]);
            }
            GL43.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, buffer);
        }
    }

    private static String read(
            net.minecraft.server.packs.resources.ResourceManager resources,
            ResourceLocation id,
            Set<ResourceLocation> stack)
            throws IOException {
        if (!stack.add(id) || stack.size() > 32) throw new IOException("材质 include 循环: " + id);
        try (var stream = resources.getResourceOrThrow(id).open()) {
            String source = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            var matcher = Pattern.compile("#moj_import <([^>]+)>").matcher(source);
            StringBuilder result = new StringBuilder();
            int end = 0;
            while (matcher.find()) {
                result.append(source, end, matcher.start());
                ResourceLocation include = ResourceLocation.parse(matcher.group(1));
                result.append(
                        read(
                                resources,
                                ResourceLocation.fromNamespaceAndPath(
                                        include.getNamespace(),
                                        "shaders/include/" + include.getPath()),
                                stack));
                end = matcher.end();
            }
            return result.append(source.substring(end)).toString();
        } finally {
            stack.remove(id);
        }
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        for (Buffers buffers : pooledBuffers) buffers.close();
        pooledBuffers.clear();
        pooledBytes = 0;
        requests.offer(-1);
        synchronized (programs) {
            for (int program : programs.values()) GL43.glDeleteProgram(program);
            programs.clear();
        }
    }
}
