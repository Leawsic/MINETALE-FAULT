package cn.jehorstudio.minetale.voxel.scene.runtime;

import cn.jehorstudio.minetale.MineTale;
import cn.jehorstudio.minetale.voxel.scene.asset.SceneImages;
import cn.jehorstudio.minetale.voxel.scene.geometry.SceneVoxels;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.TextureFormat;

import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.metadata.animation.FrameSize;
import net.minecraft.resources.ResourceLocation;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

// 颜色与独立 emission 常驻；Iris 按需借用 normal/specular 的 view。
final class SceneTexture extends TextureAtlas {
    private static boolean attempted;
    private final int width, height;
    private GpuTexture proceduralNormal, proceduralSpecular, emission;
    private com.mojang.blaze3d.textures.GpuTextureView normalView, specularView, emissionView;
    private int[][][] pixels;
    private int channel, level, row;
    private final java.util.Set<BorrowedTexture> borrowed = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

    SceneTexture(ResourceLocation location, SceneImages encoded, int width, int height)
            throws IOException {
        this(location, encoded, width, height, 0xFFFFFFFF);
    }

    /** colorDefault 供未烘焙兜底纹理指定恒定颜色（Core 主体反照率均值）。 */
    SceneTexture(
            ResourceLocation location,
            SceneImages encoded,
            int width,
            int height,
            int colorDefault)
            throws IOException {
        this(location, width, height, encoded != null && encoded.hasChannel(1) ? width : 0,
                encoded != null && encoded.hasChannel(2) ? width : 0, width);
        try {
            pixels = new int[4][][];
            int[] defaults = {colorDefault, 0xFF8080FF, 0x00000A00, 0xFF000000};
            for (int c = 0; c < 4; c++) {
                if (encoded != null) pixels[c] = encoded.takeChannel(c);
                if (pixels[c] == null) {
                    GpuTexture target = channelTexture(c);
                    int area = target.getWidth(0) * target.getHeight(0);
                    pixels[c] = new int[][] {new int[area], new int[area / 4]};
                    for (int[] mip : pixels[c]) java.util.Arrays.fill(mip, defaults[c]);
                }
            }
        } catch (RuntimeException failure) {
            close();
            throw failure;
        }
    }

    SceneTexture(ResourceLocation location, int width, int height) throws IOException {
        this(location, width, height, width, width, width);
    }

    SceneTexture(
            ResourceLocation location, int width, int height, int normalWidth, int specularWidth, int emissionWidth)
            throws IOException {
        super(location);
        this.width = width;
        this.height = height;
        try {
            if (width > maxSupportedTextureSize() || height > maxSupportedTextureSize())
                throw new IOException("场景图集超过设备上限");
            ResourceLocation id = MissingTextureAtlasSprite.getLocation();
            NativeImage base = new NativeImage(2, 2, false), mip = new NativeImage(1, 1, false);
            base.fillRect(0, 0, 2, 2, -1);
            mip.fillRect(0, 0, 1, 1, -1);
            SpriteContents contents = new SpriteContents(id, new FrameSize(2, 2), base);
            contents.byMipLevel = new NativeImage[] {base, mip};
            TextureAtlasSprite sprite =
                    new TextureAtlasSprite(location, contents, width, height, 0, 0) {};
            upload(
                    new SpriteLoader.Preparations(
                            width,
                            height,
                            1,
                            sprite,
                            Map.of(id, sprite),
                            CompletableFuture.completedFuture(null)));
            var device = RenderSystem.getDevice();
            proceduralNormal =
                    device.createTexture(
                            () -> "Scene normal",
                            7,
                            TextureFormat.RGBA8,
                            Math.max(2, normalWidth),
                            Math.max(2, height * normalWidth / width),
                            1,
                            2);
            proceduralSpecular =
                    device.createTexture(
                            () -> "Scene specular",
                            7,
                            TextureFormat.RGBA8,
                            Math.max(2, specularWidth),
                            Math.max(2, height * specularWidth / width),
                            1,
                            2);
            emission = device.createTexture(() -> "Scene emission", 7, TextureFormat.RED8,
                    Math.max(2, emissionWidth), Math.max(2, height * emissionWidth / width), 1, 2);
            proceduralNormal.setTextureFilter(FilterMode.NEAREST, true);
            proceduralSpecular.setTextureFilter(FilterMode.NEAREST, true);
            normalView = device.createTextureView(proceduralNormal);
            specularView = device.createTextureView(proceduralSpecular);
            emission.setTextureFilter(FilterMode.NEAREST, true);
            emissionView = device.createTextureView(emission);
            if (normalWidth == 0) initializeDefault(proceduralNormal, 0xFF8080FF);
            if (specularWidth == 0) initializeDefault(proceduralSpecular, 0x00000A00);
            setFilter(false, true);
            setClamp(true);
            registerLoader();
            getTexture();
        } catch (IOException | RuntimeException failure) {
            close();
            throw failure;
        }
    }

    boolean uploaded() {
        return pixels == null;
    }

    long uploadBytes() {
        long bytes = 0;
        if (pixels != null) for (int[][] channel : pixels) if (channel != null)
            for (int[] mip : channel) if (mip != null) bytes += (long) mip.length * Integer.BYTES;
        return bytes;
    }

    long upload() throws IOException {
        return upload(Integer.MAX_VALUE);
    }

    private static void initializeDefault(GpuTexture texture, int value) {
        for (int level = 0; level < 2; level++) try (NativeImage image = new NativeImage(2 >> level, 2 >> level, false)) {
            image.fillRect(0, 0, 2 >> level, 2 >> level, value);
            RenderSystem.getDevice().createCommandEncoder().writeToTexture(texture, image, level, 0, 0, 0, 2 >> level, 2 >> level, 0, 0);
        }
    }
    GpuTexture channelTexture(int c) {
        return switch(c) { case 0 -> texture; case 1 -> proceduralNormal; case 2 -> proceduralSpecular; case 3 -> emission; default -> throw new IllegalArgumentException(); };
    }

    long upload(int maximumBytes) throws IOException {
        if (pixels == null) return 0;
        GpuTexture target = channelTexture(channel);
        int w = target.getWidth(level), h = target.getHeight(level), bytesPerPixel = channel == 3 ? 1 : 4;
        int columns = Math.min(w - column, maximumBytes / bytesPerPixel);
        int rows = column == 0 && columns == w ? Math.min(h - row, maximumBytes / (w * bytesPerPixel)) : 1;
        if (columns == 0) return 0;
        if (rows == 0) return 0;
        if (channel == 3) {
            var bytes = org.lwjgl.system.MemoryUtil.memAlloc(columns * rows);
            try {
                int[] source = pixels[channel][level];
                for (int y = 0; y < rows; y++) for (int x = 0; x < columns; x++) bytes.put((byte)(source[(row+y)*w+column+x] >>> 16));
                bytes.flip();
                RenderSystem.getDevice().createCommandEncoder().writeToTexture(target, bytes, NativeImage.Format.LUMINANCE, level, 0, column, row, columns, rows);
            } finally { org.lwjgl.system.MemoryUtil.memFree(bytes); }
        } else try (NativeImage image = new NativeImage(columns, rows, false)) {
            int[] source = pixels[channel][level];
            for (int y = 0; y < rows; y++)
                for (int x = 0; x < columns; x++) image.setPixel(x, y, source[(row + y) * w + column + x]);
            RenderSystem.getDevice()
                    .createCommandEncoder()
                    .writeToTexture(target, image, level, 0, column, row, columns, rows, 0, 0);
        }
        column += columns;
        if (column == w) { column = 0; row += rows; }
        if (row == h) {
            pixels[channel][level] = null;
            row = 0;
            level++;
            if (level == 2) {
                pixels[channel] = null;
                level = 0;
                channel++;
            }
            if (channel == 4) pixels = null;
        }
        return (long) rows * columns * bytesPerPixel;
    }

    private int column;
    int uploadAlignment() { return channel == 3 ? 1 : 4; }
    com.mojang.blaze3d.textures.GpuTextureView emissionView() { return emissionView; }
    void reuseColor(SceneMaterials.Bake bake, int x, int y) {
        bake.reuseColor(((com.mojang.blaze3d.opengl.GlTexture) getTexture()).glId(),
                ((com.mojang.blaze3d.opengl.GlTexture) emission).glId(), x, y);
    }

    long bytes() {
        long bytes = 0;
        for (int c = 0; c < 4; c++) { GpuTexture t = channelTexture(c); bytes += (long)t.getWidth(0)*t.getHeight(0)*(c == 3 ? 5 : 20)/4; }
        return bytes;
    }

    com.mojang.blaze3d.textures.GpuTextureView normalView() {
        return normalView;
    }

    com.mojang.blaze3d.textures.GpuTextureView specularView() {
        return specularView;
    }

    SceneMaterials.Bake beginBake(SceneVoxels.Page page, SceneMaterials materials, int x, int y)
            throws IOException {
        return materials.begin(page, texture, proceduralNormal, proceduralSpecular, emission, x, y);
    }

    @Override
    public void close() {
        pixels = null;
        for (BorrowedTexture view : java.util.List.copyOf(borrowed)) view.close();
        if (emissionView != null) { emissionView.close(); emissionView = null; }
        if (emission != null) { emission.close(); emission = null; }
        if (normalView != null) {
            normalView.close();
            normalView = null;
        }
        if (specularView != null) {
            specularView.close();
            specularView = null;
        }
        if (proceduralNormal != null) {
            proceduralNormal.close();
            proceduralNormal = null;
        }
        if (proceduralSpecular != null) {
            proceduralSpecular.close();
            proceduralSpecular = null;
        }
        clearTextureData();
        super.close();
    }

    private static void registerLoader() {
        if (attempted) return;
        attempted = true;
        try {
            Class<?> registryType =
                    Class.forName("net.irisshaders.iris.pbr.loader.PBRTextureLoaderRegistry");
            Class<?> loaderType = Class.forName("net.irisshaders.iris.pbr.loader.PBRTextureLoader");
            Class<?> consumerType =
                    Class.forName(
                            "net.irisshaders.iris.pbr.loader.PBRTextureLoader$PBRTextureConsumer");
            Object loader =
                    Proxy.newProxyInstance(
                            loaderType.getClassLoader(),
                            new Class<?>[] {loaderType},
                            (proxy, method, args) -> {
                                if (method.getDeclaringClass() == Object.class) {
                                    return switch (method.getName()) {
                                        case "toString" -> "MineTale scene baked PBR loader";
                                        case "hashCode" -> System.identityHashCode(proxy);
                                        case "equals" -> proxy == args[0];
                                        default -> null;
                                    };
                                }
                                if (!method.getName().equals("load"))
                                    throw new UnsupportedOperationException(method.toString());
                                SceneTexture texture = (SceneTexture) args[0];
                                AbstractTexture normal = null, specular = null;
                                try {
                                    normal = texture.new BorrowedTexture(texture.proceduralNormal);
                                    specular = texture.new BorrowedTexture(texture.proceduralSpecular);
                                    consumerType
                                            .getMethod("acceptNormalTexture", AbstractTexture.class)
                                            .invoke(args[2], normal);
                                    consumerType
                                            .getMethod(
                                                    "acceptSpecularTexture", AbstractTexture.class)
                                            .invoke(args[2], specular);
                                } catch (Throwable error) {
                                    if (normal != null) normal.close();
                                    if (specular != null) specular.close();
                                    throw error;
                                }
                                return null;
                            });
            registryType
                    .getMethod("register", Class.class, loaderType)
                    .invoke(
                            registryType.getField("INSTANCE").get(null),
                            SceneTexture.class,
                            loader);
        } catch (ClassNotFoundException absent) {
            // 原版客户端由场景管线消费颜色和独立 emission。
        } catch (ReflectiveOperationException incompatible) {
            MineTale.LOGGER.warn("Scene baked PBR integration unavailable", incompatible);
        }
    }

    // 程序材质的三个通道由场景页共同拥有。
    private final class BorrowedTexture extends AbstractTexture {
        BorrowedTexture(GpuTexture source) {
            texture = source;
            borrowed.add(this);
            textureView = RenderSystem.getDevice().createTextureView(source);
        }

        @Override
        public void close() {
            if (textureView != null) {
                textureView.close();
                textureView = null;
            }
            texture = null;
            borrowed.remove(this);
        }
    }
}
