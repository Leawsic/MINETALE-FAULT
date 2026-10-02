package cn.jehorstudio.minetale.voxel.scene.runtime;

import cn.jehorstudio.minetale.MineTale;
import cn.jehorstudio.minetale.voxel.scene.asset.SceneAsset;
import cn.jehorstudio.minetale.voxel.scene.asset.SceneImages;
import cn.jehorstudio.minetale.voxel.scene.geometry.SceneIndex;
import cn.jehorstudio.minetale.voxel.scene.geometry.SceneLayout;
import cn.jehorstudio.minetale.voxel.scene.geometry.SceneSeams;
import cn.jehorstudio.minetale.voxel.scene.geometry.SceneSurface;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.BooleanSupplier;

// 工作线程准备完整 CPU 场景；成功后 Loaded 将资产所有权交给运行时，失败时由此关闭资产。
final class SceneLoader {
    private SceneLoader() {}

    static Loaded load(
            ResourceLocation requested, ResourceManager resources, boolean shaderPack, BooleanSupplier cancelled) {
        SceneAsset asset = null;
        try {
            try (var input = resources.open(requested)) {
                asset = SceneAsset.open(input);
            }
            String shader = SceneMaterials.source(resources);
            String identity = java.util.HexFormat.of().formatHex(asset.contentDigest()) + ":"
                    + java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(shader.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            String geometryIdentity = asset.sourceAvailable() ? SceneBaseCache.geometryKey(asset) : null;
            if (asset.runtimeLod()) {
                try (SceneAsset sourceAsset = asset) {
                    asset = null;
                    return loadBase(sourceAsset, shader, shaderPack, cancelled).identified(identity, geometryIdentity);
                }
            }
            return readLoaded(asset, shaderPack, cancelled).identified(identity, geometryIdentity);
        } catch (Exception failure) {
            if (asset != null)
                try {
                    asset.close();
                } catch (IOException ignored) {
                }
            throw new CompletionException(failure);
        }
    }

    private static Loaded readLoaded(SceneAsset asset, boolean shaderPack, java.util.function.BooleanSupplier cancelled)
            throws IOException {
        float[][] low = new float[asset.pages().size()][];
        for (var page : asset.pages()) {
            if (cancelled.getAsBoolean()) throw new java.util.concurrent.CancellationException();
            low[page.id()] = asset.read(page, false);
        }
        SceneImages textures = SceneImages.read(asset, shaderPack);
        SceneSeams seams =
                asset.sourceAvailable()
                        ? new SceneSeams(low, asset.pageSize())
                        : null;
        SceneLayout layout = new SceneLayout(asset, low);
        SceneIndex index = null;
        SceneSurface surface = null;
        String warning = null;
        if (asset.generatedLod()) {
            surface = SceneSurface.read(asset);
            index = SceneIndex.generated(asset, layout, surface, seams, cancelled);
        } else if (asset.staticIndexAvailable()) {
            try {
                index = SceneIndex.read(asset, layout);
                surface = index.surface();
            } catch (IOException | RuntimeException invalid) {
                index = null;
                surface = null;
                warning = "静态索引无效，保留 Low：" + invalid.getMessage();
                MineTale.LOGGER.error("Scene static index rejected", invalid);
            }
        }
        if (asset.sourceAvailable() && index == null && warning == null) {
            surface = SceneSurface.read(asset);
            index = SceneIndex.generated(asset, layout, surface, seams, cancelled);
        }
        return new Loaded(asset, layout, textures, seams, index, surface, warning, null, null);
    }

    private static Loaded readCached(
            java.nio.file.Path path, boolean shaderPack, java.util.function.BooleanSupplier cancelled)
            throws IOException {
        SceneAsset cached = SceneAsset.open(path);
        try {
            if (!cached.generatedLod()) throw new IOException("缓存不是生成的基础 LOD");
            return readLoaded(cached, shaderPack, cancelled);
        } catch (IOException | RuntimeException failure) {
            try {
                cached.close();
            } catch (IOException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    private static Loaded loadBase(
            SceneAsset source,
            String shader,
            boolean shaderPack,
            java.util.function.BooleanSupplier cancelled)
            throws IOException {
        java.nio.file.Path cache =
                Minecraft.getInstance()
                        .gameDirectory
                        .toPath()
                        .resolve("cache/minetale/core-lod")
                        .resolve(SceneBaseCache.key(source, shader + "\noutput=" + shaderPack) + ".mtscene");
        if (java.nio.file.Files.isRegularFile(cache)) {
            try {
                Loaded result = readCached(cache, shaderPack, cancelled);
                MineTale.LOGGER.info("Scene base LOD cache hit: {}", cache);
                return result;
            } catch (java.util.concurrent.CancellationException stop) {
                throw stop;
            } catch (IOException | RuntimeException invalid) {
                MineTale.LOGGER.warn(
                        "Scene base LOD cache rejected; rebuilding {}", cache, invalid);
            }
        }
        if (cancelled.getAsBoolean()) throw new java.util.concurrent.CancellationException();
        MineTale.LOGGER.info("Generating scene base LOD: {}", cache);
        long context =
                CompletableFuture.supplyAsync(
                                () -> {
                                    try {
                                        return SceneMaterials.createContext();
                                    } catch (IOException failure) {
                                        throw new CompletionException(failure);
                                    }
                                },
                                Minecraft.getInstance()::execute)
                        .join();
        try {
            org.lwjgl.glfw.GLFW.glfwMakeContextCurrent(context);
            org.lwjgl.opengl.GL.createCapabilities();
            SceneBaseCache.generate(source, cache, shader, shaderPack, cancelled);
        } finally {
            org.lwjgl.glfw.GLFW.glfwMakeContextCurrent(0);
            org.lwjgl.opengl.GL.setCapabilities(null);
            Minecraft.getInstance().execute(() -> org.lwjgl.glfw.GLFW.glfwDestroyWindow(context));
        }
        return readCached(cache, shaderPack, cancelled);
    }

    record Loaded(
            SceneAsset asset,
            SceneLayout low,
            SceneImages textures,
            SceneSeams seams,
            SceneIndex index,
            SceneSurface surface,
            String warning, String identity, String geometryIdentity) {
        Loaded identified(String value, String geometry) { return new Loaded(asset, low, textures, seams, index, surface, warning, value, geometry); }
    }
}
