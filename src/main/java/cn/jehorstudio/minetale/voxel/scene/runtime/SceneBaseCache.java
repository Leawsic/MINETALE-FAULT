package cn.jehorstudio.minetale.voxel.scene.runtime;

import cn.jehorstudio.minetale.voxel.scene.asset.SceneAsset;
import cn.jehorstudio.minetale.voxel.scene.geometry.SceneBase;

import org.lwjgl.opengl.GL43;

import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.function.BooleanSupplier;

import javax.imageio.ImageIO;

final class SceneBaseCache {
    private static final java.util.concurrent.atomic.AtomicLong BACKGROUND_GPU = new java.util.concurrent.atomic.AtomicLong();
    static long backgroundGpuBytes() { return BACKGROUND_GPU.get(); }
    static String geometryKey(SceneAsset asset) throws IOException {
        try {
            var source = com.google.gson.JsonParser.parseString(new String(asset.readSource("raw/source.json"), StandardCharsets.UTF_8)).getAsJsonObject();
            source.remove("materials");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update("geometry-v3-low0.5-cc1-smooth-quad\n".getBytes(StandardCharsets.UTF_8));
            digest.update(source.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    static String colorKey(SceneAsset asset, String shader) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update("color-v2-linear-raw-constants-footprint4-emission-r8\n".getBytes(StandardCharsets.UTF_8));
            digest.update(geometryKey(asset).getBytes(StandardCharsets.UTF_8));
            var source = com.google.gson.JsonParser.parseString(new String(asset.readSource("raw/source.json"), StandardCharsets.UTF_8)).getAsJsonObject();
            if (source.has("materials")) for (var element : source.getAsJsonArray("materials")) {
                if (!element.isJsonObject()) { digest.update(element.toString().getBytes(StandardCharsets.UTF_8)); continue; }
                var material = element.getAsJsonObject().deepCopy();
                material.keySet().removeIf(k -> !Set.of("base_color", "opacity", "emission", "textures").contains(k));
                if (material.has("textures")) {
                    var paths = material.getAsJsonObject("textures");
                    paths.keySet().removeIf(k -> !Set.of("base", "opacity", "emission").contains(k));
                    for (var path : paths.entrySet()) digest.update(asset.readSource(path.getValue().getAsString()));
                }
                digest.update(material.toString().getBytes(StandardCharsets.UTF_8));
            }
            var declarations = asset.channels().declarations();
            digest.update(declarations.getAsJsonObject("defaults").get("emission").toString().getBytes(StandardCharsets.UTF_8));
            if (declarations.has("by_material")) for (var entry : declarations.getAsJsonObject("by_material").entrySet()) {
                if (entry.getValue().getAsJsonObject().get("emission").equals(declarations.getAsJsonObject("defaults").get("emission"))) continue;
                digest.update(entry.getKey().getBytes(StandardCharsets.UTF_8));
                digest.update(entry.getValue().getAsJsonObject().get("emission").toString().getBytes(StandardCharsets.UTF_8));
            }
            digest.update(SceneMaterials.colorDependencies(shader).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    static String key(SceneAsset asset, String shader) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(asset.contentDigest());
            digest.update(shader.getBytes(StandardCharsets.UTF_8));
            digest.update("base-v3-channels-linear-raw-constants-footprint4-quad\n".getBytes(StandardCharsets.UTF_8));
            digest.update(geometryKey(asset).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IOException(impossible);
        }
    }

    // 调用方在工作线程绑定独立 context；窗口创建和销毁由游戏主线程负责。
    static void generate(
            SceneAsset source, Path destination, String shader, BooleanSupplier cancelled)
            throws IOException {
        generate(source, destination, shader, true, cancelled);
    }

    static void generate(SceneAsset source, Path destination, String shader, boolean shaderPack, BooleanSupplier cancelled) throws IOException {
        long start = System.nanoTime();
        BooleanSupplier checkpoints = cancelled;
        SceneBase.Result base = SceneBase.generate(source, shaderPack,
                destination.resolveSibling("geometry-" + geometryKey(source) + ".bin"), checkpoints);
        long geometry = System.nanoTime();
        int[] textures = new int[4];
        long allocatedTextureBytes = 0;
        Path colorCache = destination.resolveSibling("color-" + colorKey(source, shader) + ".bin");
        byte[][][] encoded = readColor(colorCache, base.width(), base.height());
        boolean reuseColor = encoded[0] != null;
        if (reuseColor && !shaderPack) {
            publish(source, destination, base, encoded, checkpoints);
            if (checkpoints.getAsBoolean()) throw new java.util.concurrent.CancellationException();
            return;
        }
        try (var materials = new SceneMaterials(shader)) {
            if (base.width() > GL43.glGetInteger(GL43.GL_MAX_TEXTURE_SIZE)
                    || base.height() > GL43.glGetInteger(GL43.GL_MAX_TEXTURE_SIZE))
                throw new IOException("基础 LOD 图集超过设备上限");
            int framebuffer = GL43.glGenFramebuffers();
            try {
                GL43.glBindFramebuffer(GL43.GL_FRAMEBUFFER, framebuffer);
                for (int c = 0; c < 4; c++) {
                    if (!shaderPack && (c == 1 || c == 2)) continue;
                    textures[c] = GL43.glGenTextures();
                    GL43.glBindTexture(GL43.GL_TEXTURE_2D, textures[c]);
                    GL43.glTexStorage2D(
                            GL43.GL_TEXTURE_2D, 2, c == 3 ? GL43.GL_R8 : GL43.GL_RGBA8, base.width(), base.height());
                    long textureBytes = (long) base.width() * base.height() * (c == 3 ? 5 : 20) / 4;
                    allocatedTextureBytes += textureBytes; BACKGROUND_GPU.addAndGet(textureBytes);
                    // 空闲图集区域写入确定内容，缓存保存完整初始化显存。
                    for (int level = 0; level < 2; level++) {
                        GL43.glFramebufferTexture2D(
                                GL43.GL_FRAMEBUFFER,
                                GL43.GL_COLOR_ATTACHMENT0,
                                GL43.GL_TEXTURE_2D,
                                textures[c],
                                level);
                        if (GL43.glCheckFramebufferStatus(GL43.GL_FRAMEBUFFER)
                                != GL43.GL_FRAMEBUFFER_COMPLETE)
                            throw new IOException("基础 LOD 清理 framebuffer 不完整");
                        GL43.glClearBufferfv(GL43.GL_COLOR, 0, new float[4]);
                    }
                }
            } finally {
                GL43.glBindFramebuffer(GL43.GL_FRAMEBUFFER, 0);
                GL43.glDeleteFramebuffers(framebuffer);
            }
            if (reuseColor) for (int c : new int[]{0, 3}) for (int level = 0; level < 2; level++)
                uploadColor(textures[c], level, encoded[c][level], base.width() >> level, base.height() >> level, cancelled);
            var generator = new cn.jehorstudio.minetale.voxel.scene.geometry.SceneVoxels(
                    cn.jehorstudio.minetale.voxel.scene.geometry.SceneSurface.read(source));
            for (var tile : base.tiles()) {
                if (cancelled.getAsBoolean())
                    throw new java.util.concurrent.CancellationException();
                var page = generator.material(tile.geometry(), SceneBase.PRECISION, shaderPack, reuseColor ? tile.data().layout() : null, checkpoints);
                try (var bake = materials.begin(page, textures, tile.x(), tile.y())) {
                    if (reuseColor) bake.reuseColor(textures[0], textures[3], tile.x(), tile.y());
                    boolean complete = false;
                    while (!complete) {
                        if (checkpoints.getAsBoolean())
                            throw new java.util.concurrent.CancellationException();
                        complete = bake.advance();
                        if (!complete) java.util.concurrent.locks.LockSupport.parkNanos(1_000_000);
                    }
                }
            }
            long baked = System.nanoTime();
            for (int c = 0; c < 4; c++) {
                if (textures[c] == 0 || encoded[c] != null) continue;
                encoded[c] = new byte[2][];
                GL43.glBindTexture(GL43.GL_TEXTURE_2D, textures[c]);
                for (int level = 0; level < 2; level++) {
                    if (cancelled.getAsBoolean())
                        throw new java.util.concurrent.CancellationException();
                    int width = base.width() >> level, height = base.height() >> level;
                    int[] pixels = readPixels(textures[c], level, width, height, cancelled);
                    var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
                    image.setRGB(0, 0, width, height, pixels, 0, width);
                    try (var bytes = new ByteArrayOutputStream()) {
                        if (!ImageIO.write(image, "PNG", bytes))
                            throw new IOException("PNG 编码器不可用");
                        encoded[c][level] = bytes.toByteArray();
                    } finally {
                        image.flush();
                    }
                    if (checkpoints.getAsBoolean()) throw new java.util.concurrent.CancellationException();
                }
            }
            if (!reuseColor) writeColor(colorCache, base.width(), base.height(), encoded);
            publish(source, destination, base, encoded, checkpoints);
            if (checkpoints.getAsBoolean()) throw new java.util.concurrent.CancellationException();
            // System.getLogger 的输出不落 latest.log；统计走模组主 logger。
            cn.jehorstudio.minetale.MineTale.LOGGER.info(
                    String.format(
                            Locale.ROOT,
                            "Base LOD: geometry=%.1f ms, material=%.1f ms, cache=%.1f ms,"
                                    + " triangles=%d, atlas=%dx%d, bytes=%d",
                            (geometry - start) / 1e6,
                            (baked - geometry) / 1e6,
                            (System.nanoTime() - baked) / 1e6,
                            base.rectangles() * 2,
                            base.width(),
                            base.height(),
                            Files.size(destination)));
        } finally {
            for (int texture : textures) if (texture != 0) GL43.glDeleteTextures(texture);
            BACKGROUND_GPU.addAndGet(-allocatedTextureBytes);
        }
    }

    private static void publish(SceneAsset source, Path destination, SceneBase.Result base, byte[][][] encoded,
            BooleanSupplier cancelled) throws IOException {
        Files.createDirectories(destination.toAbsolutePath().getParent());
        Path temporary = Files.createTempFile(destination.toAbsolutePath().getParent(), "base-lod-", ".tmp");
        try {
            SceneBase.write(temporary, source, base, encoded, cancelled);
            if (cancelled.getAsBoolean()) throw new java.util.concurrent.CancellationException();
            move(temporary, destination);
        } finally { Files.deleteIfExists(temporary); }
    }

    private static void move(Path temporary, Path destination) throws IOException {
        try { Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException unsupported) { Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING); }
    }

    private static byte[][][] readColor(Path cache, int width, int height) {
        byte[][][] encoded = new byte[4][][];
        if (!Files.isRegularFile(cache)) return encoded;
        try (var input = new DataInputStream(new BufferedInputStream(Files.newInputStream(cache)))) {
            if (input.readInt() != 0x4d544331 || input.readInt() != width || input.readInt() != height) return encoded;
            for (int c : new int[]{0, 3}) {
                encoded[c] = new byte[2][];
                for (int level = 0; level < 2; level++) {
                    int length = input.readInt();
                    if (length <= 0 || length > (long)(width >> level) * (height >> level) * 5 + 65536) throw new IOException("颜色缓存长度无效");
                    encoded[c][level] = input.readNBytes(length);
                    if (encoded[c][level].length != length) throw new EOFException();
                    try (var bytes = new ByteArrayInputStream(encoded[c][level])) {
                        var image = ImageIO.read(bytes);
                        if (image == null || image.getWidth() != width >> level || image.getHeight() != height >> level) throw new IOException("颜色缓存尺寸无效");
                        image.flush();
                    }
                }
            }
            if (input.read() != -1) throw new IOException("颜色缓存存在尾随数据");
            return encoded;
        } catch (IOException invalid) { return new byte[4][][]; }
    }

    private static void writeColor(Path cache, int width, int height, byte[][][] encoded) throws IOException {
        Files.createDirectories(cache.toAbsolutePath().getParent());
        Path temporary = Files.createTempFile(cache.toAbsolutePath().getParent(), "color-", ".tmp");
        try {
            try (var output = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(temporary)))) {
                output.writeInt(0x4d544331); output.writeInt(width); output.writeInt(height);
                for (int c : new int[]{0, 3}) for (byte[] bytes : encoded[c]) { output.writeInt(bytes.length); output.write(bytes); }
            }
            move(temporary, cache);
        } finally { Files.deleteIfExists(temporary); }
    }

    private static void uploadColor(int texture, int level, byte[] encoded, int width, int height,
            BooleanSupplier cancelled) throws IOException {
        var image = ImageIO.read(new ByteArrayInputStream(encoded));
        int[] pixels = image.getRGB(0, 0, width, height, null, 0, width);
        image.flush();
        GL43.glBindTexture(GL43.GL_TEXTURE_2D, texture);
        for (int offset = 0; offset < pixels.length;) {
            if (cancelled.getAsBoolean()) throw new java.util.concurrent.CancellationException();
            int count = Math.min(width - offset % width, pixels.length - offset);
            if (count == 0) { java.util.concurrent.locks.LockSupport.parkNanos(1_000_000); continue; }
            int[] row = Arrays.copyOfRange(pixels, offset, offset + count);
            long start = System.nanoTime();
            GL43.glTexSubImage2D(GL43.GL_TEXTURE_2D, level, offset % width, offset / width, count, 1,
                    GL43.GL_BGRA, GL43.GL_UNSIGNED_INT_8_8_8_8_REV, row);
            offset += count;
        }
    }

    private static int[] readPixels(int texture, int level, int width, int height, BooleanSupplier cancelled) throws IOException {
        int[] pixels = new int[Math.multiplyExact(width, height)];
        int framebuffer = GL43.glGenFramebuffers();
        try {
            GL43.glBindFramebuffer(GL43.GL_READ_FRAMEBUFFER, framebuffer);
            GL43.glFramebufferTexture2D(GL43.GL_READ_FRAMEBUFFER, GL43.GL_COLOR_ATTACHMENT0, GL43.GL_TEXTURE_2D, texture, level);
            if (GL43.glCheckFramebufferStatus(GL43.GL_READ_FRAMEBUFFER) != GL43.GL_FRAMEBUFFER_COMPLETE)
                throw new IOException("基础 LOD 回读 framebuffer 不完整");
            for (int offset = 0; offset < pixels.length;) {
                if (cancelled.getAsBoolean()) throw new java.util.concurrent.CancellationException();
                int count = Math.min(Math.min(width - offset % width, 16384), pixels.length - offset);
                if (count == 0) { java.util.concurrent.locks.LockSupport.parkNanos(1_000_000); continue; }
                int[] row = new int[count];
                long start = System.nanoTime();
                GL43.glReadPixels(offset % width, offset / width, count, 1, GL43.GL_BGRA, GL43.GL_UNSIGNED_INT_8_8_8_8_REV, row);
                System.arraycopy(row, 0, pixels, offset, count);
                offset += count;
            }
            return pixels;
        } finally {
            GL43.glBindFramebuffer(GL43.GL_READ_FRAMEBUFFER, 0);
            GL43.glDeleteFramebuffers(framebuffer);
        }
    }
}
