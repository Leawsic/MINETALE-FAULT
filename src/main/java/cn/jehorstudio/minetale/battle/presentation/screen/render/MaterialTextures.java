package cn.jehorstudio.minetale.battle.presentation.screen.render;

import cn.jehorstudio.minetale.MineTale;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.resources.metadata.animation.AnimationMetadataSection;
import net.minecraft.client.resources.metadata.animation.FrameSize;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.util.ARGB;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

// 重载期验证 *_s.png 的命名、尺寸与动画布局并扫描 B 通道；渲染帧只查询已验证索引。
public final class MaterialTextures implements PreparableReloadListener {
    public static final MaterialTextures INSTANCE = new MaterialTextures();

    private static final String PNG_SUFFIX = ".png";
    private volatile Map<ResourceLocation, Entry> entries = Map.of();

    private MaterialTextures() {
    }

    // 返回完整的已验证属性贴图，不按发光通道内容过滤。
    public Optional<ResourceLocation> materialMap(ResourceLocation baseTexture) {
        Entry entry = this.entries.get(java.util.Objects.requireNonNull(baseTexture, "baseTexture"));
        return entry == null ? Optional.empty() : Optional.of(entry.specularTexture());
    }

    // 仅返回含非零 B 通道、可写入 Bloom Seed 的属性贴图。
    public Optional<ResourceLocation> emissiveMap(ResourceLocation baseTexture) {
        Entry entry = this.entries.get(java.util.Objects.requireNonNull(baseTexture, "baseTexture"));
        return entry != null && entry.mayEmit()
                ? Optional.of(entry.specularTexture())
                : Optional.empty();
    }

    @Override
    public CompletableFuture<Void> reload(
            SharedState sharedState,
            Executor prepareExecutor,
            PreparationBarrier barrier,
            Executor applyExecutor
    ) {
        ResourceManager resources = sharedState.resourceManager();
        return CompletableFuture.supplyAsync(() -> scan(resources), prepareExecutor)
                .thenCompose(barrier::wait)
                .thenAcceptAsync(prepared -> this.entries = prepared, applyExecutor);
    }

    private static Map<ResourceLocation, Entry> scan(ResourceManager resources) {
        Map<ResourceLocation, Entry> result = new HashMap<>();
        int rejected = 0;
        for (Map.Entry<ResourceLocation, Resource> candidate : resources.listResources(
                "textures",
                location -> location.getPath().endsWith("_s.png")
        ).entrySet()) {
            Optional<ResourceLocation> baseId = baseTexture(candidate.getKey());
            Optional<Resource> base = baseId.flatMap(resources::getResource);
            if (baseId.isEmpty() || base.isEmpty()) {
                rejected++;
                continue;
            }
            try {
                Entry entry = inspect(base.get(), candidate.getValue(), candidate.getKey());
                if (entry.validContract()) {
                    result.put(baseId.get(), entry);
                } else {
                    rejected++;
                }
            } catch (IOException | RuntimeException exception) {
                rejected++;
            }
        }
        if (rejected > 0) {
            MineTale.LOGGER.warn("忽略了 {} 个尺寸、动画布局或 PNG 数据无效的 Battle *_s 材质", rejected);
        }
        return Map.copyOf(result);
    }

    private static Optional<ResourceLocation> baseTexture(ResourceLocation specularTexture) {
        String path = specularTexture.getPath();
        String suffix = "_s" + PNG_SUFFIX;
        if (!path.endsWith(suffix)) {
            return Optional.empty();
        }
        String basePath = path.substring(0, path.length() - suffix.length()) + PNG_SUFFIX;
        return Optional.of(ResourceLocation.fromNamespaceAndPath(specularTexture.getNamespace(), basePath));
    }

    private static Entry inspect(Resource base, Resource specular, ResourceLocation specularId) throws IOException {
        try (InputStream baseStream = base.open();
             InputStream specularStream = specular.open();
             NativeImage baseImage = NativeImage.read(baseStream);
             NativeImage specularImage = NativeImage.read(specularStream)) {
            boolean sameDimensions = baseImage.getWidth() == specularImage.getWidth()
                    && baseImage.getHeight() == specularImage.getHeight();
            boolean sameAnimation = animationLayout(
                    base.metadata().getSection(AnimationMetadataSection.TYPE),
                    baseImage.getWidth(),
                    baseImage.getHeight()
            ).equals(animationLayout(
                    specular.metadata().getSection(AnimationMetadataSection.TYPE),
                    specularImage.getWidth(),
                    specularImage.getHeight()
            ));
            boolean mayEmit = false;
            if (sameDimensions && sameAnimation) {
                int[] pixels = specularImage.getPixels();
                for (int pixel : pixels) {
                    if (ARGB.blue(pixel) != 0) {
                        mayEmit = true;
                        break;
                    }
                }
            }
            return new Entry(specularId, sameDimensions && sameAnimation, mayEmit);
        }
    }

    private static AnimationLayout animationLayout(
            Optional<AnimationMetadataSection> metadata,
            int imageWidth,
            int imageHeight
    ) {
        if (metadata.isEmpty()) {
            return new AnimationLayout(imageWidth, imageHeight, List.of(0));
        }
        AnimationMetadataSection animation = metadata.get();
        FrameSize size = animation.calculateFrameSize(imageWidth, imageHeight);
        List<Integer> indices = new ArrayList<>();
        animation.frames().ifPresentOrElse(
                frames -> frames.forEach(frame -> indices.add(frame.index())),
                () -> {
                    int count = Math.max(1, imageWidth / size.width())
                            * Math.max(1, imageHeight / size.height());
                    for (int index = 0; index < count; index++) {
                        indices.add(index);
                    }
                }
        );
        return new AnimationLayout(size.width(), size.height(), List.copyOf(indices));
    }

    private record Entry(ResourceLocation specularTexture, boolean validContract, boolean mayEmit) {
    }

    private record AnimationLayout(int frameWidth, int frameHeight, List<Integer> frameIndices) {
    }
}
