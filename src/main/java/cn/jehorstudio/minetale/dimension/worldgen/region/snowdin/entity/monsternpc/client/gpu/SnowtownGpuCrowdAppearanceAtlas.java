package cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.entity.monsternpc.client.gpu;

import cn.jehorstudio.minetale.content.entity.monster_npc.MonsterNpcAppearance;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

// 合并全部外观的只读 GPU 数据，使单次 Draw 可按 appearanceId 索引。
final class SnowtownGpuCrowdAppearanceAtlas implements AutoCloseable {
    private static final int PREFERRED_ATLAS_WIDTH = 1024;
    private static final int DIFFUSE_PADDING = 1;

    private final DynamicTexture diffuseTexture;
    private final DynamicTexture geometryTexture;
    private final DynamicTexture animationTexture;
    private final List<Entry> entries;

    private SnowtownGpuCrowdAppearanceAtlas(
            DynamicTexture diffuseTexture,
            DynamicTexture geometryTexture,
            DynamicTexture animationTexture,
            List<Entry> entries
    ) {
        this.diffuseTexture = diffuseTexture;
        this.geometryTexture = geometryTexture;
        this.animationTexture = animationTexture;
        this.entries = entries;
    }

    static SnowtownGpuCrowdAppearanceAtlas create(
            List<SnowtownGpuCrowdAppearanceMesh> meshes
    ) {
        if (meshes.size() != MonsterNpcAppearance.count()) {
            throw new IllegalArgumentException("外观 Mesh 数量与枚举不一致");
        }
        int maximumTextureSize = RenderSystem.getDevice().getMaxTextureSize();
        List<NativeImage> ownedDiffuseImages = new ArrayList<>(meshes.size());
        List<NativeImage> ownedAtlasImages = new ArrayList<>(3);
        DynamicTexture diffuseTexture = null;
        DynamicTexture geometryTexture = null;
        DynamicTexture animationTexture = null;
        try {
            List<Source> diffuseSources = new ArrayList<>(meshes.size());
            List<Source> geometrySources = new ArrayList<>(meshes.size());
            List<Source> animationSources = new ArrayList<>(meshes.size());
            for (SnowtownGpuCrowdAppearanceMesh mesh : meshes) {
                int id = mesh.appearance().id();
                if (id != diffuseSources.size()) {
                    throw new IllegalStateException("外观 Mesh 未按 appearanceId 排序");
                }
                NativeImage diffuse;
                try (var stream = Minecraft.getInstance()
                        .getResourceManager().open(mesh.appearance().textureResource())) {
                    diffuse = NativeImage.read(stream);
                }
                ownedDiffuseImages.add(diffuse);
                diffuseSources.add(new Source(id, diffuse));
                geometrySources.add(new Source(
                        id,
                        Objects.requireNonNull(mesh.geometryTexture().getPixels())));
                animationSources.add(new Source(
                        id,
                        Objects.requireNonNull(mesh.animationTexture().getPixels())));
            }

            PackedImage diffuse = pack(diffuseSources, DIFFUSE_PADDING, maximumTextureSize);
            ownedAtlasImages.add(diffuse.image());
            PackedImage geometry = pack(geometrySources, 0, maximumTextureSize);
            ownedAtlasImages.add(geometry.image());
            PackedImage animation = pack(animationSources, 0, maximumTextureSize);
            ownedAtlasImages.add(animation.image());
            diffuseTexture = createTexture("Snowtown crowd diffuse atlas", diffuse.image());
            ownedAtlasImages.remove(diffuse.image());
            geometryTexture = createTexture("Snowtown crowd geometry atlas", geometry.image());
            ownedAtlasImages.remove(geometry.image());
            animationTexture = createTexture("Snowtown crowd animation atlas", animation.image());
            ownedAtlasImages.remove(animation.image());

            List<Entry> entries = new ArrayList<>(meshes.size());
            for (int id = 0; id < meshes.size(); id++) {
                entries.add(new Entry(
                        diffuse.regions().get(id),
                        geometry.regions().get(id),
                        animation.regions().get(id)));
            }
            return new SnowtownGpuCrowdAppearanceAtlas(
                    diffuseTexture,
                    geometryTexture,
                    animationTexture,
                    List.copyOf(entries));
        } catch (IOException failure) {
            closeTexture(diffuseTexture);
            closeTexture(geometryTexture);
            closeTexture(animationTexture);
            throw new IllegalStateException("无法构建 Snowtown 人群外观 atlas", failure);
        } catch (RuntimeException | LinkageError failure) {
            closeTexture(diffuseTexture);
            closeTexture(geometryTexture);
            closeTexture(animationTexture);
            throw failure;
        } finally {
            ownedDiffuseImages.forEach(NativeImage::close);
            ownedAtlasImages.forEach(NativeImage::close);
        }
    }

    private static DynamicTexture createTexture(String label, NativeImage image) {
        DynamicTexture texture = new DynamicTexture(() -> label, image);
        texture.setClamp(true);
        texture.setFilter(false, false);
        return texture;
    }

    private static PackedImage pack(
            List<Source> sources,
            int padding,
            int maximumTextureSize
    ) {
        List<Source> sorted = sources.stream()
                .sorted(Comparator.comparingInt((Source source) -> source.image().getHeight())
                        .reversed()
                        .thenComparingInt(Source::id))
                .toList();
        int largestWidth = sorted.stream()
                .mapToInt(source -> source.image().getWidth() + padding * 2)
                .max()
                .orElseThrow();
        if (largestWidth > maximumTextureSize) {
            throw new IllegalStateException("单个外观纹理超过 GPU 最大纹理宽度");
        }

        int width = Math.min(
                maximumTextureSize,
                Math.max(PREFERRED_ATLAS_WIDTH, nextPowerOfTwo(largestWidth)));
        PlacementPlan plan;
        while (true) {
            plan = place(sorted, sources.size(), width, padding);
            if (plan.height() <= maximumTextureSize) {
                break;
            }
            if (width == maximumTextureSize) {
                throw new IllegalStateException("全部外观纹理无法装入 GPU atlas");
            }
            width = Math.min(maximumTextureSize, width * 2);
        }

        NativeImage atlas = new NativeImage(width, Math.max(plan.height(), 1), true);
        try {
            for (Source source : sources) {
                Region region = plan.regions().get(source.id());
                source.image().copyRect(
                        atlas,
                        0,
                        0,
                        region.x(),
                        region.y(),
                        region.width(),
                        region.height(),
                        false,
                        false);
                if (padding > 0) {
                    duplicateOnePixelBorder(source.image(), atlas, region);
                }
            }
            return new PackedImage(atlas, plan.regions());
        } catch (RuntimeException | LinkageError failure) {
            atlas.close();
            throw failure;
        }
    }

    private static PlacementPlan place(
            List<Source> sorted,
            int sourceCount,
            int atlasWidth,
            int padding
    ) {
        Region[] regions = new Region[sourceCount];
        int x = 0;
        int y = 0;
        int rowHeight = 0;
        for (Source source : sorted) {
            int packedWidth = source.image().getWidth() + padding * 2;
            int packedHeight = source.image().getHeight() + padding * 2;
            if (x > 0 && x + packedWidth > atlasWidth) {
                x = 0;
                y += rowHeight;
                rowHeight = 0;
            }
            regions[source.id()] = new Region(
                    x + padding,
                    y + padding,
                    source.image().getWidth(),
                    source.image().getHeight(),
                    atlasWidth,
                    0);
            x += packedWidth;
            rowHeight = Math.max(rowHeight, packedHeight);
        }
        int height = y + rowHeight;
        List<Region> completed = new ArrayList<>(sourceCount);
        for (Region region : regions) {
            completed.add(region.withAtlasHeight(Math.max(height, 1)));
        }
        return new PlacementPlan(Math.max(height, 1), List.copyOf(completed));
    }

    private static void duplicateOnePixelBorder(
            NativeImage source,
            NativeImage target,
            Region region
    ) {
        int x = region.x();
        int y = region.y();
        int width = region.width();
        int height = region.height();
        for (int column = 0; column < width; column++) {
            target.setPixel(x + column, y - 1, source.getPixel(column, 0));
            target.setPixel(
                    x + column,
                    y + height,
                    source.getPixel(column, height - 1));
        }
        for (int row = 0; row < height; row++) {
            target.setPixel(x - 1, y + row, source.getPixel(0, row));
            target.setPixel(
                    x + width,
                    y + row,
                    source.getPixel(width - 1, row));
        }
        target.setPixel(x - 1, y - 1, source.getPixel(0, 0));
        target.setPixel(x + width, y - 1, source.getPixel(width - 1, 0));
        target.setPixel(x - 1, y + height, source.getPixel(0, height - 1));
        target.setPixel(
                x + width,
                y + height,
                source.getPixel(width - 1, height - 1));
    }

    private static int nextPowerOfTwo(int value) {
        int highest = Integer.highestOneBit(value);
        return highest == value ? value : Math.multiplyExact(highest, 2);
    }

    private static void closeTexture(DynamicTexture texture) {
        if (texture != null) {
            texture.close();
        }
    }

    DynamicTexture diffuseTexture() {
        return this.diffuseTexture;
    }

    DynamicTexture geometryTexture() {
        return this.geometryTexture;
    }

    DynamicTexture animationTexture() {
        return this.animationTexture;
    }

    Entry entry(int appearanceId) {
        return this.entries.get(appearanceId);
    }

    String dimensions() {
        return this.diffuseTexture.getTexture().getWidth(0) + "x"
                + this.diffuseTexture.getTexture().getHeight(0) + "/"
                + this.geometryTexture.getTexture().getWidth(0) + "x"
                + this.geometryTexture.getTexture().getHeight(0) + "/"
                + this.animationTexture.getTexture().getWidth(0) + "x"
                + this.animationTexture.getTexture().getHeight(0);
    }

    @Override
    public void close() {
        this.diffuseTexture.close();
        this.geometryTexture.close();
        this.animationTexture.close();
    }

    record Entry(Region diffuse, Region geometry, Region animation) {
    }

    record Region(
            int x,
            int y,
            int width,
            int height,
            int atlasWidth,
            int atlasHeight
    ) {
        private Region withAtlasHeight(int value) {
            return new Region(this.x, this.y, this.width, this.height, this.atlasWidth, value);
        }

        float normalizedX() {
            return (float)this.x / this.atlasWidth;
        }

        float normalizedY() {
            return (float)this.y / this.atlasHeight;
        }

        float normalizedWidth() {
            return (float)this.width / this.atlasWidth;
        }

        float normalizedHeight() {
            return (float)this.height / this.atlasHeight;
        }
    }

    private record Source(int id, NativeImage image) {
    }

    private record PlacementPlan(int height, List<Region> regions) {
    }

    private record PackedImage(NativeImage image, List<Region> regions) {
    }
}
