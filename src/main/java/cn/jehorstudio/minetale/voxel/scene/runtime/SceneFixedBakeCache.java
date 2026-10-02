package cn.jehorstudio.minetale.voxel.scene.runtime;

import cn.jehorstudio.minetale.MineTale;
import cn.jehorstudio.minetale.voxel.scene.asset.SceneAsset;

import net.neoforged.fml.loading.FMLPaths;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;

/**
 * 固定网格烘焙结果的磁盘缓存：按资产摘要定位，PNG 编解码与容器格式的唯一所有者。
 * 布局为 [batch][channel(4)][mip(2)] 的 PNG 字节；channel 3 是 R8 自发光。
 * 缓存生命周期与线程编排归 SceneFixed，本类只做无状态的编解码与文件读写。
 */
final class SceneFixedBakeCache {
    private static final int MAGIC = 0x4d544643, VERSION = 1;
    // System.getLogger 的输出不落 latest.log；缓存诊断必须走模组主 logger。
    private static final org.slf4j.Logger LOGGER = MineTale.LOGGER;

    private SceneFixedBakeCache() {}

    /** 缓存键 = 资产摘要 128 位，烘焙输入不变即命中。 */
    static Path path(SceneAsset asset) throws IOException {
        String digest = HexFormat.of().formatHex(asset.contentDigest(), 0, 16);
        return FMLPaths.GAMEDIR.get()
                .resolve("cache/minetale/core-fixed")
                .resolve("fixed-" + digest + ".bin");
    }

    /** 读取缓存；文件缺失、格式异常或规模越界都返回 null，按未命中处理。 */
    static byte[][][][] read(Path file, int batches) {
        if (!Files.isRegularFile(file)) return null;
        try (var input = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            if (input.readInt() != MAGIC || input.readInt() != VERSION || input.readInt() != batches)
                return null;
            byte[][][][] result = new byte[batches][4][2][];
            long total = 0;
            for (var batch : result) for (var channel : batch) for (int level = 0; level < 2; level++) {
                int length = input.readInt();
                if (length < 0 || length > 64 << 20) return null;
                total += length;
                if (total > 512 << 20) return null;
                if (length == 0) continue;
                byte[] png = input.readNBytes(length);
                if (png.length != length) return null;
                channel[level] = png;
            }
            if (input.read() != -1) return null;
            for (var batch : result) if (batch[0][0] == null) return null;
            LOGGER.info("固定网格烘焙缓存命中：{} 批次", batches);
            return result;
        } catch (IOException invalid) {
            return null;
        }
    }

    /** PNG [channel][mip] → ARGB 像素；图像无效抛 IOException，由调用方拒绝该批缓存。 */
    static int[][][] decode(byte[][][] pngs) throws IOException {
        int[][][] pixels = new int[4][][];
        for (int c = 0; c < 4; c++) {
            for (int level = 0; level < 2; level++) {
                byte[] png = pngs[c][level];
                if (png == null) continue;
                var image = ImageIO.read(new ByteArrayInputStream(png));
                if (image == null || image.getWidth() != image.getHeight())
                    throw new IOException("固定网格烘焙缓存图像无效");
                if (pixels[c] == null) pixels[c] = new int[2][];
                pixels[c][level] = image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
                image.flush();
            }
        }
        return pixels;
    }

    /** ARGB 像素 → PNG；非方形区域抛 IOException。 */
    static byte[] encode(int[] pixels) throws IOException {
        int size = (int) Math.round(Math.sqrt(pixels.length));
        if ((long) size * size != pixels.length) throw new IOException("固定网格烘焙区域非方形");
        var image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, size, size, pixels, 0, size);
        try (var bytes = new ByteArrayOutputStream()) {
            if (!ImageIO.write(image, "png", bytes)) throw new IOException("PNG 编码器不可用");
            return bytes.toByteArray();
        } finally {
            image.flush();
        }
    }

    /** 写入缓存容器：magic/version/批次数 + 各 PNG 的长度前缀字节。 */
    static void write(Path file, byte[][][][] pngs) throws IOException {
        Files.createDirectories(file.getParent());
        try (var output = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(file)))) {
            output.writeInt(MAGIC);
            output.writeInt(VERSION);
            output.writeInt(pngs.length);
            for (var batch : pngs) for (var channel : batch) for (var level : channel)
                if (level == null) output.writeInt(0);
                else {
                    output.writeInt(level.length);
                    output.write(level);
                }
        }
    }
}
