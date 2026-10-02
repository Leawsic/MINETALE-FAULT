package cn.jehorstudio.minetale.voxel.scene.asset;

import com.google.gson.Gson;

import org.tukaani.xz.XZInputStream;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** 提供随机读取的只读分页场景；调用方拥有文件生命周期，并在关闭前取消读取任务。 */
public final class SceneAsset implements AutoCloseable {
    private final ZipFile file;
    private final Path snapshot;
    private final boolean packedLow;
    private final int pageSize;
    private final List<Page> pages;
    private final int lowTriangles;
    private final boolean rawAvailable;
    private final Atlas atlas;
    private final StaticIndex staticIndex;
    private final double lowStep;
    private final String baseLod;
    private final SceneChannels channels;
    private final boolean partsAvailable;

    private SceneAsset(ZipFile file, Path snapshot, Manifest manifest) throws IOException {
        this.file = file;
        this.snapshot = snapshot;
        staticIndex = manifest.static_index;
        baseLod = manifest.base_lod;
        if (baseLod != null && !baseLod.equals("runtime-0.5") && !baseLod.equals("generated-0.5"))
            throw new IOException("未知的基础 LOD 类型");
        lowStep = manifest.low_step;
        if (!Double.isFinite(lowStep) || lowStep < 1e-4 || lowStep > 256)
            throw new IOException("Low 采样间距越界");
        validateEncoding(manifest);
        partsAvailable = manifest.parts != null;
        if (partsAvailable && (!"1.2".equals(manifest.version)
                || !"parts/manifest.json".equals(manifest.parts)
                || file.getEntry(manifest.parts) == null)) throw new IOException("无效的部件目录声明");
        channels = SceneChannels.read(manifest.version, manifest.material_channels, manifest.lod_profiles);
        packedLow = "rectangle80-xz".equals(manifest.low_encoding);
        pageSize = manifest.page_size;
        atlas = manifest.atlas;
        validateAtlas();
        var cells = new HashSet<String>();
        long low = 0, raw = 0;
        for (int i = 0; i < manifest.pages.size(); i++) {
            Page page = manifest.pages.get(i);
            if (page == null
                    || page.id != i
                    || page.cell == null
                    || page.cell.length != 3
                    || page.low_triangles < 0
                    || page.raw_triangles < 0
                    || (!runtimeLod()
                            && !generatedLod()
                            && page.low_triangles + (long) page.raw_triangles == 0)
                    || !cells.add(java.util.Arrays.toString(page.cell)))
                throw new IOException("无效的空间页 " + i);
            for (int c : page.cell)
                if (Math.abs((long) c) > 1_000_000) throw new IOException("页坐标越界");
            if (packedLow) {
                if ((page.low_triangles & 1) != 0) throw new IOException("Low 页必须包含矩形三角形对");
                ZipEntry entry = file.getEntry("low/" + i + ".bin.xz");
                if ((page.low_triangles > 0
                        && (entry == null
                                || entry.getMethod() != ZipEntry.STORED
                                || entry.getSize() < 24
                                || entry.getSize() > page.low_triangles * 40L + 65536)))
                    throw new IOException("无效的 Low 压缩页 " + i);
            } else validateEntry("low/" + i + ".bin", page.low_triangles, 14);
            validateEntry("raw/" + i + ".bin", page.raw_triangles, 8);
            low += page.low_triangles;
            raw += page.raw_triangles;
        }
        if (!runtimeLod() && low == 0) throw new IOException("场景缺少 Low 几何");
        if (low > Integer.MAX_VALUE) throw new IOException("Low 三角形数量超出 int 表示范围");
        if (runtimeLod()
                && (low != 0 || staticIndex != null || !sourceAvailable() || pageSize != 32))
            throw new IOException("运行时基础 LOD 要求 32 米来源页且不携带 Low 或静态索引");
        if (generatedLod()
                && (staticIndex != null || !sourceAvailable() || pageSize != 32 || lowStep != 2))
            throw new IOException("基础 LOD 缓存要求 32 米来源页、2 米体素且不携带静态索引");
        // Raw 可用性独立于三角形数量；零面页表示真实空表面。
        rawAvailable = manifest.raw_available;
        if (!rawAvailable && raw != 0) throw new IOException("未启用 Raw 的场景包含 Raw 几何");
        lowTriangles = (int) low;
        pages = List.copyOf(manifest.pages);
    }

    private void validateEncoding(Manifest manifest) throws IOException {
        if (!("1.0".equals(manifest.version) || "1.1".equals(manifest.version) || "1.2".equals(manifest.version))
                || !"minetale:scene".equals(manifest.format)
                || manifest.low_vertex_floats != 14
                || manifest.raw_vertex_floats != 8
                || manifest.page_size < 1
                || manifest.page_size > 256
                || manifest.raw_available == null
                || manifest.pages == null
                || manifest.pages.isEmpty()) throw new IOException("无效的 .mtscene 1.0/1.1/1.2 场景目录");
        // 分发资产保存压缩矩形和 WebP；生成缓存保存对应 GPU 结果的浮点页和 PNG。
        String lowEncoding = generatedLod() ? "float32" : "rectangle80-xz";
        String textureEncoding = generatedLod() ? "png" : "webp-lossless";
        if (!lowEncoding.equals(manifest.low_encoding)
                || !"float32".equals(manifest.raw_encoding)
                || !textureEncoding.equals(manifest.texture_encoding))
            throw new IOException("场景编码与用途不匹配");
    }

    private void validateAtlas() throws IOException {
        if (atlas == null
                || atlas.width < 16
                || atlas.height < 16
                || Integer.bitCount(atlas.width) != 1
                || Integer.bitCount(atlas.height) != 1
                || atlas.mip_levels != 2
                || atlas.regions < 1) throw new IOException("无效的场景 atlas");
        if (!"labpbr".equals(atlas.mip_filter)) throw new IOException("未知的 mip 算法");
    }

    public static SceneAsset open(Path path) throws IOException {
        return open(path, null);
    }

    public SceneChannels channels() { return channels; }

    public static SceneAsset open(InputStream resource) throws IOException {
        Path snapshot = Files.createTempFile("minetale-scene-", ".mtscene");
        try {
            try (var output = Files.newOutputStream(snapshot)) {
                byte[] buffer = new byte[65536];
                for (int n; (n = resource.read(buffer)) != -1; ) {
                    output.write(buffer, 0, n);
                }
            }
            return open(snapshot, snapshot);
        } catch (Throwable failure) {
            try {
                Files.deleteIfExists(snapshot);
            } catch (IOException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    private static SceneAsset open(Path path, Path snapshot) throws IOException {
        ZipFile zip = new ZipFile(path.toFile());
        try {
            var names = new HashSet<String>();
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                if (!names.add(entries.nextElement().getName())) throw new IOException("重复的场景条目");
            }
            ZipEntry entry = zip.getEntry("manifest.json");
            if (entry == null || entry.getSize() < 1) throw new IOException("缺少或为空的清单");
            try (var reader =
                    new InputStreamReader(zip.getInputStream(entry), StandardCharsets.UTF_8)) {
                Manifest manifest = new Gson().fromJson(reader, Manifest.class);
                if (manifest == null) throw new IOException("空场景清单");
                return new SceneAsset(zip, snapshot, manifest);
            }
        } catch (Throwable failure) {
            zip.close();
            throw failure;
        }
    }

    private void validateEntry(String name, int triangles, int stride) throws IOException {
        ZipEntry entry = file.getEntry(name);
        if (triangles > 0 && (entry == null || entry.getSize() != triangles * 12L * stride))
            throw new IOException("几何大小不匹配: " + name);
    }

    /** 读取已验证顶点。Low 每顶点 14 个 float，含源转换器生成的切线和区域中心；Raw 为 8 个 float。 */
    public synchronized float[] read(Page page, boolean raw) throws IOException {
        int triangles = raw ? page.raw_triangles : page.low_triangles;
        if (triangles == 0) return new float[0];
        String name = (raw ? "raw/" : "low/") + page.id + ".bin";
        int stride = raw ? 8 : 14;
        int size = Math.multiplyExact(triangles, 12 * stride);
        float[] vertices;
        if (!raw && packedLow) vertices = readRectangles(page);
        else {
            byte[] bytes;
            try (InputStream in = file.getInputStream(file.getEntry(name))) {
                bytes = in.readAllBytes();
            }
            if (bytes.length != size) throw new IOException("几何数据截断: " + name);
            vertices = new float[triangles * 3 * stride];
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(vertices);
        }
        for (int v = 0; v < vertices.length; v += stride) {
            for (int j = 0; j < stride; j++)
                if (!Float.isFinite(vertices[v + j])) throw new IOException("非有限顶点");
            double normalLength =
                    Math.sqrt(
                            (double) vertices[v + 5] * vertices[v + 5]
                                    + (double) vertices[v + 6] * vertices[v + 6]
                                    + (double) vertices[v + 7] * vertices[v + 7]);
            if (normalLength < 1.0e-10) throw new IOException("顶点法线为零: " + name);
            for (int j = 5; j < 8; j++) vertices[v + j] /= normalLength;
            for (int a = 0; a < 3; a++) {
                double min = (double) page.cell[a] * pageSize;
                if (vertices[v + a] < min - 0.01 || vertices[v + a] > min + pageSize + 0.01)
                    throw new IOException("顶点超出所属页: " + name);
            }
        }
        if (!raw) validateRegions(vertices);
        return vertices;
    }

    private float[] readRectangles(Page page) throws IOException {
        int count = page.low_triangles / 2;
        byte[] planes;
        try (var source = file.getInputStream(file.getEntry("low/" + page.id + ".bin.xz"));
                var input = new XZInputStream(source)) {
            planes = input.readNBytes(count * 80 + 1);
        }
        if (planes.length != count * 80) throw new IOException("Low 矩形页解码长度错误");
        float[] vertices = new float[count * 84];
        int[] words = new int[20];
        int[] corners = {0, 1, 2, 0, 2, 3};
        for (int r = 0; r < count; r++) {
            for (int w = 0; w < 20; w++) {
                int bits = 0;
                for (int b = 0; b < 4; b++)
                    bits |= (planes[(w * 4 + b) * count + r] & 255) << (b * 8);
                words[w] = bits;
            }
            int mask = words[19];
            if ((mask & ~0xFFFFF) != 0) throw new IOException("Low 矩形角点掩码越界");
            for (int v = 0; v < 6; v++) {
                int offset = r * 84 + v * 14;
                for (int c = 0; c < 5; c++) {
                    int choice = (mask >>> (corners[v] * 5 + c)) & 1;
                    vertices[offset + c] = Float.intBitsToFloat(words[c + choice * 5]);
                }
                for (int c = 0; c < 9; c++)
                    vertices[offset + 5 + c] = Float.intBitsToFloat(words[10 + c]);
            }
        }
        return vertices;
    }

    private static void validateRegions(float[] vertices) throws IOException {
        for (int t = 0; t < vertices.length; t += 42) {
            float halfU = Math.abs(vertices[t + 3] - vertices[t + 12]);
            float halfV = Math.abs(vertices[t + 4] - vertices[t + 13]);
            if (halfU < 1.0e-7F || halfV < 1.0e-7F) throw new IOException("退化的纹理区域");
            int corners = 0;
            for (int c = 0; c < 3; c++) {
                int v = t + c * 14;
                float u = vertices[v + 3], w = vertices[v + 4];
                if (u < 0
                        || u > 1
                        || w < 0
                        || w > 1
                        || Math.abs(Math.abs(u - vertices[t + 12]) - halfU) > 1.0e-6F
                        || Math.abs(Math.abs(w - vertices[t + 13]) - halfV) > 1.0e-6F)
                    throw new IOException("Low 顶点不在矩形纹理区域的角点");
                corners |= 1 << ((u > vertices[t + 12] ? 1 : 0) | (w > vertices[t + 13] ? 2 : 0));
                // 平滑曲面逐顶点保存法线和切线；同一纹理区域只共享 UV 中心。
                for (int j = 12; j < 14; j++)
                    if (Math.abs(vertices[v + j] - vertices[t + j]) > 1.0e-5F)
                        throw new IOException("Low 面的纹理中心不一致");
                float tangentLength = 0, dot = 0;
                for (int j = 0; j < 3; j++) {
                    tangentLength += vertices[v + 8 + j] * vertices[v + 8 + j];
                    dot += vertices[v + 8 + j] * vertices[v + 5 + j];
                }
                if (Math.abs(tangentLength - 1) > 1.0e-4F
                        || Math.abs(dot) > 1.0e-4F
                        || Math.abs(vertices[v + 11]) != 1) throw new IOException("无效的 Low 切线帧");
            }
            if (Integer.bitCount(corners) != 3) throw new IOException("Low 纹理角点重复");
        }
    }

    /** 返回烘焙通道编码流，由调用方关闭；分发资产使用基础级 WebP，生成缓存使用 PNG 和显式 mip。 */
    public InputStream openTexture(String channel, int mipLevel) throws IOException {
        if (!List.of("color", "normal", "specular", "emission").contains(channel))
            throw new IllegalArgumentException("未知烘焙通道");
        if (mipLevel < 0 || mipLevel >= atlas.mip_levels)
            throw new IllegalArgumentException("未知 mip level");
        if (packedLow && mipLevel != 0) throw new IllegalArgumentException("WebP 场景的 mip 在加载时生成");
        ZipEntry entry =
                file.getEntry(
                        (mipLevel == 0 ? "" : "mip/" + mipLevel + "/")
                                + channel
                                + (packedLow ? ".webp" : ".png"));
        if (entry == null || entry.getSize() < 1) throw new IOException("无效烘焙贴图: " + channel);
        return file.getInputStream(entry);
    }

    public boolean hasTexture(String channel) {
        return file.getEntry(channel + (packedLow ? ".webp" : ".png")) != null;
    }

    public int pageSize() {
        return pageSize;
    }

    public List<Page> pages() {
        return pages;
    }

    public boolean rawAvailable() {
        return rawAvailable;
    }

    public boolean sourceAvailable() {
        return file.getEntry("raw/source.json") != null;
    }

    public boolean runtimeLod() {
        return "runtime-0.5".equals(baseLod);
    }

    public boolean generatedLod() {
        return "generated-0.5".equals(baseLod);
    }

    public byte[] contentDigest() throws IOException {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(Path.of(file.getName()))) {
                byte[] block = new byte[65536];
                for (int n; (n = input.read(block)) != -1; ) digest.update(block, 0, n);
            }
            return digest.digest();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    public void copySourceEntries(java.util.zip.ZipOutputStream output) throws IOException {
        for (var entries = file.entries(); entries.hasMoreElements(); ) {
            var entry = entries.nextElement();
            if ((!entry.getName().startsWith("raw/") && !(partsAvailable && entry.getName().startsWith("parts/")))
                    || entry.getName().matches("raw/[0-9]+\\.bin"))
                continue;
            output.putNextEntry(new ZipEntry(entry.getName()));
            try (var input = file.getInputStream(entry)) {
                input.transferTo(output);
            }
            output.closeEntry();
        }
    }

    public void copyRawPage(java.util.zip.ZipOutputStream output, Page source, int target)
            throws IOException {
        output.putNextEntry(new ZipEntry("raw/" + target + ".bin"));
        try (var input = file.getInputStream(file.getEntry("raw/" + source.id + ".bin"))) {
            input.transferTo(output);
        }
        output.closeEntry();
    }

    public boolean staticIndexAvailable() {
        return staticIndex != null;
    }

    public double lowStep() {
        return lowStep;
    }

    public byte[] staticIndexDigest() throws IOException {
        if (!"1.0".equals(staticIndex.version)
                || !"static/index.bin.xz".equals(staticIndex.entry)
                || staticIndex.sha256 == null
                || !staticIndex.sha256.matches("[0-9a-f]{64}"))
            throw new IOException("未知或无效的静态索引目录");
        return java.util.HexFormat.of().parseHex(staticIndex.sha256);
    }

    public byte[] readStaticIndex() throws IOException {
        staticIndexDigest();
        return readPackedSource(staticIndex.entry);
    }

    public synchronized byte[] readPackedSource(String entry) throws IOException {
        ZipEntry item = file.getEntry(entry);
        if (item == null || item.getSize() < 1) throw new IOException("无效的压缩场景来源: " + entry);
        try (var source = file.getInputStream(item);
                var input = new XZInputStream(source)) {
            return input.readAllBytes();
        }
    }

    public int capGroupSize() throws IOException {
        int size = staticIndex.caps_group_size;
        if (size != 64) throw new IOException("未知的静态截面分组大小");
        return size;
    }

    public byte[] geometryDigest() throws IOException {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[65536];
            for (Page page : pages) {
                digest.update(
                        ByteBuffer.allocate(20)
                                .putInt(page.id)
                                .putInt(page.cell[0])
                                .putInt(page.cell[1])
                                .putInt(page.cell[2])
                                .putInt(page.low_triangles)
                                .array());
                if (page.low_triangles == 0) continue;
                String name = "low/" + page.id + (packedLow ? ".bin.xz" : ".bin");
                try (InputStream input = file.getInputStream(file.getEntry(name))) {
                    for (int n; (n = input.read(buffer)) != -1; ) digest.update(buffer, 0, n);
                }
            }
            return digest.digest();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    public synchronized byte[] readSource(String entry) throws IOException {
        ZipEntry item = file.getEntry(entry);
        if (item == null || item.getSize() < 1) throw new IOException("无效的精细场景来源: " + entry);
        try (InputStream in = file.getInputStream(item)) {
            byte[] bytes = in.readAllBytes();
            if (bytes.length != item.getSize()) throw new IOException("精细场景来源长度不符: " + entry);
            return bytes;
        }
    }

    public boolean fixedAvailable() { return file.getEntry("raw/fixed/manifest.json") != null; }
    public boolean partsAvailable() { return partsAvailable; }

    /** 按目录声明的展开长度读取部件流，限制解压内存并拒绝截断和尾随数据。 */
    public byte[] readPartEntry(String name, int length, boolean compressed) throws IOException {
        if (!name.startsWith("parts/") || name.contains("..") || name.contains("\\")
                || length < 1 || length > 512 * (1 << 20)) throw new IOException("部件条目声明越界");
        // 短锁只覆盖 ZIP 条目访问并把输入字节读入独立数组；XZ 解压在锁外执行，
        // 同一资产的两个条目才能并行解压。关闭保护由构造期的条目校验维持。
        byte[] raw;
        synchronized (this) {
            ZipEntry entry = file.getEntry(name);
            if (entry == null || entry.getSize() < 1 || entry.getSize() > 512L * (1 << 20))
                throw new IOException("缺少或过大的部件条目: " + name);
            try (InputStream input = file.getInputStream(entry)) {
                raw = input.readAllBytes();
            }
        }
        if (!compressed) {
            if (raw.length != length) throw new IOException("部件条目长度不符: " + name);
            return raw;
        }
        try (InputStream input = new XZInputStream(new ByteArrayInputStream(raw), 128 * 1024)) {
            byte[] bytes = input.readNBytes(length);
            if (bytes.length != length || input.read() != -1)
                throw new IOException("部件条目长度不符: " + name);
            return bytes;
        }
    }

    public int atlasWidth() {
        return atlas.width;
    }

    public int atlasHeight() {
        return atlas.height;
    }

    public int mipLevels() {
        return atlas.mip_levels;
    }

    public boolean generatedMips() {
        return packedLow;
    }

    @Override
    public synchronized void close() throws IOException {
        try {
            file.close();
        } finally {
            if (snapshot != null) Files.deleteIfExists(snapshot);
        }
    }

    public record Page(int id, int[] cell, int low_triangles, int raw_triangles) {
        public Page {
            cell = cell == null ? null : cell.clone();
        }

        @Override
        public int[] cell() {
            return cell.clone();
        }

        public int coordinate(int axis) {
            return cell[axis];
        }
    }

    private record Atlas(int width, int height, int mip_levels, int regions, String mip_filter) {}

    private record Manifest(
            String format,
            String version,
            int page_size,
            int low_vertex_floats,
            int raw_vertex_floats,
            Boolean raw_available,
            Atlas atlas,
            List<Page> pages,
            String low_encoding,
            String raw_encoding,
            String texture_encoding,
            StaticIndex static_index,
            double low_step,
            String base_lod,
            com.google.gson.JsonObject material_channels,
            com.google.gson.JsonObject lod_profiles,
            String parts) {}

    private record StaticIndex(String version, String entry, String sha256, int caps_group_size) {}
}
