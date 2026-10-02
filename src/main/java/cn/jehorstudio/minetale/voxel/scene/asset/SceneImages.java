package cn.jehorstudio.minetale.voxel.scene.asset;

import com.twelvemonkeys.imageio.plugins.webp.WebPImageReaderSpi;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;

import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;

// 场景读取线程完成图像解码和 mip 生成；结果使用 NativeImage.setPixel 的 ARGB 顺序。
public final class SceneImages {
    private static final String[] CHANNELS = {"color", "normal", "specular", "emission"};
    private static final float[] LINEAR = new float[256];

    static {
        for (int i = 0; i < LINEAR.length; i++) LINEAR[i] = (float) Math.pow(i / 255F, 2.2F);
    }

    private final int[][][] prepared = new int[4][][];

    private SceneImages() {}

    public static SceneImages read(SceneAsset asset) throws IOException {
        return read(asset, true);
    }

    public static SceneImages read(SceneAsset asset, boolean shaderPack) throws IOException {
        SceneImages images = new SceneImages();
        int width = asset.atlasWidth(), height = asset.atlasHeight();
        for (int channel = 0; channel < CHANNELS.length; channel++) {
            if ((channel == 1 || channel == 2) && !shaderPack) continue;
            if (channel != 0 && !asset.hasTexture(CHANNELS[channel])) continue;
            int[] base;
            try (var input = asset.openTexture(CHANNELS[channel], 0)) {
                base = decode(input.readAllBytes(), asset.generatedMips(), width, height);
            }
            int[] mip;
            if (asset.generatedMips()) mip = nextMip(base, width, height, channel);
            else
                try (var input = asset.openTexture(CHANNELS[channel], 1)) {
                    mip = decode(input.readAllBytes(), false, width / 2, height / 2);
                }
            images.prepared[channel] = new int[][] {base, mip};
        }
        if (images.prepared[3] == null) {
            int[][] specular = images.prepared[2];
            if (specular == null && asset.hasTexture("specular")) {
                specular = new int[2][];
                try (var input = asset.openTexture("specular", 0)) {
                    specular[0] = decode(input.readAllBytes(), asset.generatedMips(), width, height);
                }
                if (asset.generatedMips()) specular[1] = nextMip(specular[0], width, height, 2);
                else try (var input = asset.openTexture("specular", 1)) {
                    specular[1] = decode(input.readAllBytes(), false, width/2, height/2);
                }
            }
            images.prepared[3] = new int[][]{new int[width*height], new int[width*height/4]};
            if (specular != null) for (int level = 0; level < 2; level++) for (int i = 0; i < specular[level].length; i++) {
                int alpha = specular[level][i] >>> 24;
                int emission = alpha == 255 ? 0 : Math.round(alpha * 255F / 254F);
                images.prepared[3][level][i] = 0xff000000 | emission << 16;
            }
        }
        return images;
    }

    public boolean hasChannel(int channel) { return prepared[channel] != null; }

    public static SceneImages readPartAtlas(SceneAsset asset, SceneParts.Atlas atlas, boolean shaderPack)
            throws IOException {
        SceneImages images = new SceneImages();
        for (int channel = 0; channel < 3; channel++) {
            if (channel != 0 && !shaderPack) continue;
            SceneParts.Image image = atlas.channels().get(CHANNELS[channel]);
            int[] base = decode(asset.readPartEntry(image.entry(), image.bytes(), false), true, atlas.width(), atlas.height());
            images.prepared[channel] = new int[][]{base, nextMip(base, atlas.width(), atlas.height(), channel)};
        }
        // emission 在非光影输出中同样有效；仅借用 specular 的编码，解码后释放临时通道。
        int[][] specular = images.prepared[2];
        if (specular == null) {
            SceneParts.Image image = atlas.channels().get("specular");
            int[] base = decode(asset.readPartEntry(image.entry(), image.bytes(), false), true, atlas.width(), atlas.height());
            specular = new int[][]{base, nextMip(base, atlas.width(), atlas.height(), 2)};
        }
        images.prepared[3] = new int[][]{new int[atlas.width()*atlas.height()], new int[atlas.width()*atlas.height()/4]};
        for (int level = 0; level < 2; level++) for (int i = 0; i < specular[level].length; i++) {
            int alpha = specular[level][i] >>> 24;
            int emission = alpha == 255 ? 0 : Math.round(alpha * 255F / 254F);
            images.prepared[3][level][i] = 0xff000000 | emission << 16;
        }
        return images;
    }

    public long bytes() {
        long bytes = 0;
        for (int[][] channel : prepared) if (channel != null)
            for (int[] mip : channel) if (mip != null) bytes += (long) mip.length * Integer.BYTES;
        return bytes;
    }

    // 每个通道只向 SceneTexture 转移一次；光影重载借用 GPU view，渲染线程复用已上传图像。
    public int[][] takeChannel(int channel) {
        int[][] pixels = prepared[channel];
        prepared[channel] = null;
        return pixels;
    }

    private static int[] decode(byte[] encoded, boolean webp, int width, int height)
            throws IOException {
        BufferedImage image;
        if (webp) {
            if (encoded.length < 25
                    || encoded[12] != 'V'
                    || encoded[13] != 'P'
                    || encoded[14] != '8'
                    || encoded[15] != 'L') throw new IOException("场景 WebP 贴图必须是无损 VP8L");
            // 显式选择解码器，保持结果独立于其他模组的 ImageIO 注册顺序。
            var reader = new WebPImageReaderSpi().createReaderInstance();
            try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(encoded))) {
                reader.setInput(input);
                if (reader.getWidth(0) != width || reader.getHeight(0) != height)
                    throw new IOException("场景纹理尺寸与 manifest 不一致");
                image = reader.read(0);
            } finally {
                reader.dispose();
            }
        } else {
            try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(encoded))) {
                var readers = ImageIO.getImageReadersByFormatName("PNG");
                if (!readers.hasNext()) throw new IOException("PNG 解码器不可用");
                var reader = readers.next();
                try {
                    reader.setInput(input);
                    if (reader.getWidth(0) != width || reader.getHeight(0) != height)
                        throw new IOException("场景纹理尺寸与 manifest 不一致");
                    image = reader.read(0);
                } finally {
                    reader.dispose();
                }
            }
        }
        try {
            return image.getRGB(0, 0, width, height, null, 0, width);
        } finally {
            image.flush();
        }
    }

    static int[] nextMip(int[] base, int width, int height, int channel) {
        int[] result = new int[width * height / 4];
        int[] values = new int[4];
        int[] components = new int[4];
        for (int y = 0; y < height; y += 2) {
            for (int x = 0; x < width; x += 2) {
                int start = y * width + x;
                values[0] = base[start];
                values[1] = base[start + 1];
                values[2] = base[start + width];
                values[3] = base[start + width + 1];
                for (int c = 0; c < 4; c++) {
                    int sum = 0;
                    for (int pixel : values) sum += component(pixel, c);
                    components[c] = round(sum / 4F);
                }
                if (channel == 0) {
                    for (int c = 0; c < 3; c++) {
                        float sum = 0;
                        for (int pixel : values) sum += LINEAR[component(pixel, c)];
                        components[c] = round((float) Math.pow(sum / 4F, (float) (1 / 2.2)) * 255F);
                    }
                } else if (channel == 1) {
                    double nx = 0, ny = 0, nz = 0;
                    for (int pixel : values) {
                        int r = component(pixel, 0), g = component(pixel, 1);
                        double vx = r == 0 && g == 0 ? 0 : r / 127.5 - 1;
                        double vy = r == 0 && g == 0 ? 0 : g / 127.5 - 1;
                        double length = Math.max(1, Math.sqrt(vx * vx + vy * vy));
                        vx /= length;
                        vy /= length;
                        nx += vx;
                        ny += vy;
                        nz += Math.sqrt(Math.max(0, 1 - (vx * vx + vy * vy)));
                    }
                    nx /= 4;
                    ny /= 4;
                    nz /= 4;
                    double length = Math.max(1e-10, Math.sqrt(nx * nx + ny * ny + nz * nz));
                    components[0] = round((float) ((nx / length * .5 + .5) * 255));
                    components[1] = round((float) ((ny / length * .5 + .5) * 255));
                } else {
                    for (int c = 1; c < 4; c++) components[c] = discreteAverage(values, c);
                }
                result[(y / 2) * (width / 2) + x / 2] =
                        components[3] << 24
                                | components[0] << 16
                                | components[1] << 8
                                | components[2];
            }
        }
        return result;
    }

    private static int discreteAverage(int[] pixels, int component) {
        int selected = 0, most = 0;
        // 平票选择最先出现的类别；像素顺序固定为左上、右上、左下、右下。
        for (int pixel : pixels) {
            int category = category(component(pixel, component), component), count = 0;
            for (int other : pixels)
                if (category(component(other, component), component) == category) count++;
            if (count > most) {
                most = count;
                selected = category;
            }
        }
        int sum = 0;
        for (int pixel : pixels) {
            int value = component(pixel, component);
            if (category(value, component) == selected) sum += value;
        }
        return round(sum / (float) most);
    }

    private static int category(int value, int component) {
        return switch (component) {
            case 1 -> value < 230 ? 0 : value - 229;
            case 2 -> value >= 65 ? 1 : 0;
            default -> value == 255 ? 1 : 0;
        };
    }

    private static int component(int pixel, int component) {
        return (pixel >>> (component == 3 ? 24 : (2 - component) * 8)) & 255;
    }

    private static int round(float value) {
        return (int) Math.rint(Math.clamp(value, 0F, 255F));
    }
}
