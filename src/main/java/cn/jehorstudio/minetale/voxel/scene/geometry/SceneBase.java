package cn.jehorstudio.minetale.voxel.scene.geometry;

import cn.jehorstudio.minetale.voxel.scene.asset.SceneAsset;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.zip.*;

// 基础 LOD 按 32 米页在来源空间生成；所有页共享图集和现有 Low 绘制路径。
public final class SceneBase {
    public static final SceneVoxels.Precision PRECISION =
            new SceneVoxels.Precision(.5F, .5F, .5F, .5F);

    public record Tile(SceneVoxels.Page data, SceneVoxels.GeometryResult geometry, int x, int y) {}

    public record Result(
            List<SceneAsset.Page> pages,
            float[][] vertices,
            List<Tile> tiles,
            int width,
            int height,
            int rectangles) {}

    public static Result generate(SceneAsset asset, BooleanSupplier cancelled) throws IOException {
        return generate(asset, true, cancelled);
    }

    public static Result generate(SceneAsset asset, boolean shaderPack, BooleanSupplier cancelled) throws IOException {
        return generate(asset, shaderPack, null, cancelled);
    }

    public static Result generate(SceneAsset asset, boolean shaderPack, Path geometryCache, BooleanSupplier cancelled) throws IOException {
        if (asset.pageSize() != 32) throw new IOException("基础 LOD 要求 32 米空间页");
        SceneSurface surface = SceneSurface.read(asset);
        SceneVoxels voxels = new SceneVoxels(surface);
        Comparator<SceneLayout.Cell> order =
                Comparator.comparingInt(SceneLayout.Cell::z)
                        .thenComparingInt(SceneLayout.Cell::y)
                        .thenComparingInt(SceneLayout.Cell::x);
        var cells = new TreeSet<SceneLayout.Cell>(order);
        Map<SceneLayout.Cell, SceneAsset.Page> originals = new HashMap<>();
        for (var page : asset.pages()) {
            var cell =
                    new SceneLayout.Cell(
                            page.coordinate(0), page.coordinate(1), page.coordinate(2));
            cells.add(cell);
            originals.put(cell, page);
        }
        for (int part = 0; part < surface.partCount(); part++)
            for (double[] b : surface.faceBounds(part)) {
                int[] first = new int[3], last = new int[3];
                for (int a = 0; a < 3; a++) {
                    first[a] = (int) Math.floor(Math.nextDown((b[a] - 2) / 32));
                    last[a] = (int) Math.floor((b[a + 3] + 2) / 32);
                }
                for (int z = first[2]; z <= last[2]; z++)
                    for (int y = first[1]; y <= last[1]; y++)
                        for (int x = first[0]; x <= last[0]; x++) {
                            if (cancelled.getAsBoolean())
                                throw new java.util.concurrent.CancellationException();
                            cells.add(new SceneLayout.Cell(x, y, z));
                        }
            }
        var data = new ArrayList<SceneVoxels.Page>();
        var geometryByPage = new IdentityHashMap<SceneVoxels.Page, SceneVoxels.GeometryResult>();
        var geometryData = readGeometry(geometryCache, cells, cancelled);
        boolean cacheHit = geometryData != null;
        if (!cacheHit) geometryData = new ArrayList<>();
        long area = 0;
        int rectangles = 0;
        int geometryIndex = 0;
        for (var cell : cells) {
            if (cancelled.getAsBoolean()) throw new java.util.concurrent.CancellationException();
            var geometry = cacheHit ? geometryData.get(geometryIndex++) : voxels.geometry(cell, 32, .5F, true, cancelled);
            if (!cacheHit) geometryData.add(geometry);
            var generated = voxels.layout(geometry, PRECISION, shaderPack, cancelled);
            data.add(generated);
            geometryByPage.put(generated, geometry);
            if (generated.vertices().length == 0) continue;
            rectangles += generated.rectangles();
            area += (long) generated.maximumSize() * generated.maximumSize();
        }
        if (!cacheHit && geometryCache != null) writeGeometry(geometryCache, geometryData, cancelled);
        if (rectangles == 0) throw new IOException("基础 LOD 没有可见表面");
        // 二次幂方块按尺寸递减排布，并保持 mip 所需的偶数边界；共享图集由所有砖共用。
        var sorted =
                data.stream()
                        .filter(p -> p.vertices().length != 0)
                        .sorted(Comparator.comparingInt(SceneVoxels.Page::maximumSize).reversed())
                        .toList();
        int width = 16;
        while ((long) width * width < area) width *= 2;
        if (!sorted.isEmpty()) width = Math.max(width, sorted.getFirst().maximumSize());
        int x = 0, y = 0, row = 0;
        var tiles = new ArrayList<Tile>();
        for (var page : sorted) {
            int size = page.maximumSize();
            if (x + size > width) {
                y += row;
                x = 0;
                row = 0;
            }
            tiles.add(new Tile(page, geometryByPage.get(page), x, y));
            x += size;
            row = Math.max(row, size);
        }
        int height = 16;
        while (height < y + row) height *= 2;
        for (Tile tile : tiles) remapTile(tile, width, height);
        // 体素页生成正向边界面时，面位于页的最大平面；Low 目录将共面面片归入正侧页。
        // 先重排完整矩形，再建立页 ID，使同一细节砖只从一个页接收 Low 范围。
        var grouped =
                new TreeMap<SceneLayout.Cell, it.unimi.dsi.fastutil.floats.FloatArrayList>(order);
        for (var cell : cells) grouped.put(cell, new it.unimi.dsi.fastutil.floats.FloatArrayList());
        for (var page : data) {
            if (cancelled.getAsBoolean()) throw new java.util.concurrent.CancellationException();
            float[] vertices = page.vertices();
            for (int v = 0; v < vertices.length; v += 56) {
                var cell =
                        new SceneLayout.Cell(
                                (int) Math.floor(Math.min(vertices[v], vertices[v + 28]) / 32),
                                (int) Math.floor(Math.min(vertices[v + 1], vertices[v + 29]) / 32),
                                (int) Math.floor(Math.min(vertices[v + 2], vertices[v + 30]) / 32));
                var output =
                        grouped.computeIfAbsent(
                                cell, ignored -> new it.unimi.dsi.fastutil.floats.FloatArrayList());
                // 资产 1.0 的 float32 传输表示保留三角形；运行时 Layout 再收紧为四顶点。
                for (int corner : new int[]{0, 1, 2, 0, 2, 3})
                    output.addElements(output.size(), vertices, v + corner * 14, 14);
            }
        }
        var pages = new ArrayList<SceneAsset.Page>();
        float[][] vertices = new float[grouped.size()][];
        for (var entry : grouped.entrySet()) {
            var cell = entry.getKey();
            var original = originals.get(cell);
            int id = pages.size();
            vertices[id] = entry.getValue().toFloatArray();
            pages.add(
                    new SceneAsset.Page(
                            id,
                            new int[] {cell.x(), cell.y(), cell.z()},
                            vertices[id].length / 42,
                            original == null ? 0 : original.raw_triangles()));
        }
        return new Result(
                List.copyOf(pages), vertices, List.copyOf(tiles), width, height, rectangles);
    }

    private static List<SceneVoxels.GeometryResult> readGeometry(Path path, Set<SceneLayout.Cell> cells, BooleanSupplier cancelled) {
        if (path == null || !Files.isRegularFile(path)) return null;
        try (var input = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
            if (input.readInt() != 0x4d544731 || input.readInt() != cells.size()) return null;
            var result = new ArrayList<SceneVoxels.GeometryResult>(cells.size());
            for (var cell : cells) {
                if (cancelled.getAsBoolean()) throw new java.util.concurrent.CancellationException();
                result.add(SceneVoxels.GeometryResult.read(input, cell));
            }
            if (input.read() != -1) return null;
            return result;
        } catch (IOException invalid) {
            // System.getLogger 的输出不落 latest.log；缓存诊断必须走模组主 logger。
            cn.jehorstudio.minetale.MineTale.LOGGER.warn("基础几何缓存已拒绝：" + path, invalid);
            return null;
        }
    }

    private static void writeGeometry(Path path, List<SceneVoxels.GeometryResult> geometry, BooleanSupplier cancelled) throws IOException {
        Files.createDirectories(path.toAbsolutePath().getParent());
        Path temporary = Files.createTempFile(path.toAbsolutePath().getParent(), "geometry-", ".tmp");
        try {
            try (var output = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(temporary)))) {
                output.writeInt(0x4d544731); output.writeInt(geometry.size());
                for (var result : geometry) {
                    if (cancelled.getAsBoolean()) throw new java.util.concurrent.CancellationException();
                    result.write(output);
                }
            }
            try { Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException unsupported) { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }

    private static void remapTile(Tile tile, int width, int height) {
        float scaleX = (float) tile.data.maximumSize() / width,
                scaleY = (float) tile.data.maximumSize() / height;
        for (int v = 0; v < tile.data.vertices().length; v += 14) {
            for (int at : new int[] {3, 12})
                tile.data.vertices()[v + at] =
                        tile.data.vertices()[v + at] * scaleX + (float) tile.x / width;
            for (int at : new int[] {4, 13})
                tile.data.vertices()[v + at] =
                        tile.data.vertices()[v + at] * scaleY + (float) tile.y / height;
        }
    }

    public static void write(
            Path output,
            SceneAsset source,
            Result base,
            byte[][][] textures,
            BooleanSupplier cancelled)
            throws IOException {
        Map<SceneLayout.Cell, SceneAsset.Page> originals = new HashMap<>();
        for (var page : source.pages())
            originals.put(
                    new SceneLayout.Cell(
                            page.coordinate(0), page.coordinate(1), page.coordinate(2)),
                    page);
        try (var zip =
                new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(output)))) {
            zip.setLevel(Deflater.BEST_SPEED);
            source.copySourceEntries(zip);
            for (var page : base.pages) {
                if (cancelled.getAsBoolean())
                    throw new java.util.concurrent.CancellationException();
                if (page.low_triangles() > 0)
                    writeFloats(zip, "low/" + page.id() + ".bin", base.vertices[page.id()]);
                if (page.raw_triangles() > 0)
                    source.copyRawPage(
                            zip,
                            originals.get(
                                    new SceneLayout.Cell(
                                            page.coordinate(0),
                                            page.coordinate(1),
                                            page.coordinate(2))),
                            page.id());
            }
            String[] channels = {"color", "normal", "specular", "emission"};
            for (int c = 0; c < 4; c++) if (textures[c] != null)
                for (int level = 0; level < 2; level++) {
                    zip.putNextEntry(
                            new ZipEntry((level == 0 ? "" : "mip/1/") + channels[c] + ".png"));
                    zip.write(textures[c][level]);
                    zip.closeEntry();
                }
            var manifest = new JsonObject();
            manifest.addProperty("format", "minetale:scene");
            manifest.addProperty("version", source.partsAvailable() ? "1.2" : "1.1");
            if (source.partsAvailable()) manifest.addProperty("parts", "parts/manifest.json");
            manifest.add("material_channels", source.channels().declarations());
            manifest.add("lod_profiles", source.channels().profiles());
            manifest.addProperty("low_encoding", "float32");
            manifest.addProperty("raw_encoding", "float32");
            manifest.addProperty("texture_encoding", "png");
            manifest.addProperty("page_size", 32);
            manifest.addProperty("low_vertex_floats", 14);
            manifest.addProperty("raw_vertex_floats", 8);
            manifest.addProperty("raw_available", source.rawAvailable());
            manifest.addProperty("base_lod", "generated-0.5");
            manifest.addProperty("low_step", 2);
            var atlas = new JsonObject();
            atlas.addProperty("width", base.width);
            atlas.addProperty("height", base.height);
            atlas.addProperty("mip_levels", 2);
            atlas.addProperty("mip_filter", "labpbr");
            atlas.addProperty("regions", base.rectangles);
            manifest.add("atlas", atlas);
            manifest.add("pages", new Gson().toJsonTree(base.pages));
            zip.putNextEntry(new ZipEntry("manifest.json"));
            zip.write(manifest.toString().getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
    }

    private static void writeFloats(ZipOutputStream zip, String name, float[] values)
            throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        ByteBuffer block = ByteBuffer.allocate(65536).order(ByteOrder.LITTLE_ENDIAN);
        for (float value : values) {
            if (block.remaining() < 4) {
                zip.write(block.array(), 0, block.position());
                block.clear();
            }
            block.putFloat(value);
        }
        zip.write(block.array(), 0, block.position());
        zip.closeEntry();
    }
}
