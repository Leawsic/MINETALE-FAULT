package cn.jehorstudio.minetale.voxel.scene.asset;

import com.google.gson.Gson;

import org.joml.Vector3f;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

// 部件目录是共享原型与摆放记录的唯一来源；重建的顶点由运行时缓存拥有。
public final class SceneParts {
    public record Image(String entry, int bytes) {}

    public record Atlas(int width, int height, Map<String, Image> channels) {}

    public record Lod(String entry, int faces, int decoded_bytes, float error, float[] bounds) {}

    // priority 为准入需求乘数：缺省 1，低优先级原型小于 1（通常同时缺省最高精度档）。
    public record Prototype(
            String id, List<Lod> lods, List<float[]> copies, float[] bounds, Float priority) {}

    public record Target(String name, float[] bounds) {}

    public record Instance(
            String id,
            int prototype,
            int target,
            float[] anchor,
            float[] offset,
            float[] linear,
            float search_radius,
            float[] attachment_normal) {}

    public record Mesh(float[] vertices, int[] offsets, int[] quads) {
        public long bytes() {
            return vertices.length * 4L;
        }
    }

    private record Manifest(
            int version,
            String coordinates,
            String geometry_encoding,
            int tiles,
            int tile_bytes,
            List<Atlas> atlases,
            List<Prototype> prototypes,
            List<Target> targets,
            List<Instance> instances) {}

    private final SceneAsset asset;
    private final List<Atlas> atlases;
    private final List<Prototype> prototypes;
    private final List<Target> targets;
    private final List<Instance> instances;
    private final short[] tileAtlases;
    private final short[] tileUv;
    private final float[] priorities;

    public SceneParts(SceneAsset asset) throws IOException {
        this.asset = asset;
        Manifest m;
        try {
            byte[] json = asset.readSource("parts/manifest.json");
            if (json.length > 16 * (1 << 20)) throw new IOException("部件目录过大");
            m = new Gson().fromJson(new String(json, StandardCharsets.UTF_8), Manifest.class);
        } catch (RuntimeException invalid) {
            throw new IOException("部件目录无效", invalid);
        }
        if (m == null
                || m.version != 1
                || !"scene-local(x,z,-y)".equals(m.coordinates)
                || !"quad-dictionary-xor-v1".equals(m.geometry_encoding)
                || m.atlases == null
                || m.atlases.isEmpty()
                || m.atlases.size() > 256
                || m.prototypes == null
                || m.prototypes.isEmpty()
                || m.prototypes.size() > 4096
                || m.targets == null
                || m.targets.size() > 4096
                || m.instances == null
                || m.instances.size() > 1_000_000
                || m.tiles < 1
                || m.tiles > 16_000_000
                || m.tile_bytes != 8L + m.tiles * 10L) throw new IOException("部件目录版本或数量越界");
        for (int i = 0; i < m.atlases.size(); i++) {
            Atlas a = m.atlases.get(i);
            if (a == null || !extent(a.width) || !extent(a.height) || a.channels == null)
                throw new IOException("部件图集尺寸无效");
            for (String channel : List.of("color", "normal", "specular")) {
                Image image = a.channels.get(channel);
                if (image == null
                        || !image.entry.equals("parts/atlas-" + i + "-" + channel + ".webp")
                        || image.bytes < 25
                        || image.bytes > (long) a.width * a.height * 4 + 65536)
                    throw new IOException("部件图集条目无效");
            }
        }
        var ids = new HashSet<String>();
        for (Prototype p : m.prototypes) {
            if (p == null
                    || p.id == null
                    || !p.id.matches("[A-Za-z0-9_-]{1,80}")
                    || !ids.add(p.id)
                    || p.lods == null
                    || p.lods.size() < 2
                    || p.lods.size() > 4
                    || p.copies == null
                    || p.copies.isEmpty()
                    || p.copies.size() > 4096) throw new IOException("部件原型无效");
            bounds(p.bounds);
            if (p.priority != null
                    && (!Float.isFinite(p.priority) || p.priority <= 0 || p.priority > 1))
                throw new IOException("部件优先级无效");
            float previousError = Float.POSITIVE_INFINITY;
            for (int i = 0; i < p.lods.size(); i++) {
                Lod lod = p.lods.get(i);
                if (lod == null
                        || !lod.entry.equals("parts/" + p.id + "-" + (i + 1) + ".bin.xz")
                        || lod.faces < 1
                        || lod.faces > 4_000_000
                        || lod.decoded_bytes < 20L + lod.faces * 61L
                        || lod.decoded_bytes > 512L * (1 << 20)
                        || !Float.isFinite(lod.error)
                        || lod.error < 0
                        || lod.error > previousError) throw new IOException("部件 LOD 声明无效");
                previousError = lod.error;
                bounds(lod.bounds);
            }
            for (float[] copy : p.copies) {
                finite(copy, 12);
                determinant(copy, 4);
            }
        }
        ids.clear();
        for (Target t : m.targets) {
            if (t == null || t.name == null || t.name.isBlank() || !ids.add(t.name))
                throw new IOException("附着目标无效");
            bounds(t.bounds);
        }
        ids.clear();
        for (Instance i : m.instances) {
            if (i == null
                    || i.id == null
                    || i.id.length() > 128
                    || !ids.add(i.id)
                    || i.prototype < 0
                    || i.prototype >= m.prototypes.size()
                    || i.target < -1
                    || i.target >= m.targets.size()
                    || !Float.isFinite(i.search_radius)
                    || i.search_radius <= 0
                    || i.search_radius > 256) throw new IOException("部件摆放记录无效");
            finite(i.anchor, 3);
            finite(i.offset, 3);
            finite(i.linear, 9);
            if (i.attachment_normal != null) {
                finite(i.attachment_normal, 3);
                double length = 0;
                for (float v : i.attachment_normal) length += v * v;
                if (Math.abs(length - 1) > 1e-4)
                    throw new IOException("附着方向必须为单位法线");
            }
            if (i.target >= 0)
                for (float v : i.anchor)
                    if (v < 0 || v > 1) throw new IOException("附着种子必须位于归一化包围盒内");
            determinant(i.linear, 3);
        }
        atlases = List.copyOf(m.atlases);
        prototypes = List.copyOf(m.prototypes);
        targets = List.copyOf(m.targets);
        instances = List.copyOf(m.instances);
        priorities = new float[m.prototypes.size()];
        for (int i = 0; i < priorities.length; i++) {
            Float priority = m.prototypes.get(i).priority;
            priorities[i] = priority == null ? 1F : priority;
        }
        byte[] tiles = asset.readPartEntry("parts/tiles.bin.xz", m.tile_bytes, true);
        ByteBuffer header = ByteBuffer.wrap(tiles).order(ByteOrder.LITTLE_ENDIAN);
        if (header.getInt() != 0x4d545054 || header.getInt() != m.tiles)
            throw new IOException("部件图块表头无效");
        tileAtlases = new short[m.tiles];
        tileUv = new short[Math.multiplyExact(m.tiles, 4)];
        for (int i = 0; i < m.tiles; i++) {
            int atlas = planeShort(tiles, 8, m.tiles, 0, i);
            if (atlas >= atlases.size()) throw new IOException("图块图集索引越界");
            tileAtlases[i] = (short) atlas;
            for (int j = 0; j < 4; j++)
                tileUv[i * 4 + j] = (short) planeShort(tiles, 8, m.tiles, 2 + j * 2, i);
            int u0 = uv(i, 0), v0 = uv(i, 1), u1 = uv(i, 2), v1 = uv(i, 3);
            Atlas a = atlases.get(atlas);
            if (u0 >= u1 || v0 >= v1 || u1 > a.width * 2 || v1 > a.height * 2)
                throw new IOException("图块 UV 越界");
        }
    }

    public List<Prototype> prototypes() {
        return prototypes;
    }

    public List<Instance> instances() {
        return instances;
    }

    public List<Target> targets() {
        return targets;
    }

    public List<Atlas> atlases() {
        return atlases;
    }

    public float priority(int prototype) {
        return priorities[prototype];
    }

    public SceneImages readAtlas(int index, boolean shaderPack) throws IOException {
        return SceneImages.readPartAtlas(asset, atlases.get(index), shaderPack);
    }

    public long directoryBytes() {
        return (long) tileUv.length * 2 + tileAtlases.length * 2L;
    }

    public Mesh readMesh(int prototype, int level) throws IOException {
        Lod lod = prototypes.get(prototype).lods.get(level);
        byte[] bytes = asset.readPartEntry(lod.entry, lod.decoded_bytes, true);
        ByteBuffer header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int magic = header.getInt(),
                faces = header.getInt(),
                origins = header.getInt(),
                edges = header.getInt(),
                normals = header.getInt();
        if (magic != 0x4d545047
                || faces != lod.faces
                || origins < 1
                || origins > faces
                || edges < 1
                || edges > faces * 2L
                || normals < 1
                || normals > 65535
                || 20L + (origins + (long) edges + normals) * 12 + faces * 61L != bytes.length)
            throw new IOException("部件四边形流表头无效");
        int originBase = 20,
                edgeBase = originBase + origins * 12,
                normalBase = edgeBase + edges * 12;
        int faceBase = normalBase + normals * 12;
        int[] tiles = new int[faces],
                counts = new int[atlases.size()],
                offsets = new int[counts.length];
        for (int i = 0; i < faces; i++) {
            int tile = planeInt(bytes, faceBase, faces, 48, i);
            if (tile < 0 || tile >= tileAtlases.length) throw new IOException("部件图块索引越界");
            tiles[i] = tile;
            counts[tileAtlases[tile] & 65535]++;
        }
        int total = 0;
        for (int i = 0; i < counts.length; i++) {
            offsets[i] = total;
            total += counts[i] * 4;
        }
        int[] cursors = offsets.clone();
        float[] vertices = new float[Math.multiplyExact(total, 14)];
        // 法线按唯一索引求值一次：坐标转换、长度验证与 normalize 只在被引用的
        // 字典项上执行，逐角切线正交化仍依赖 UV 与该角法线，不能整体复用。
        float[] normalTable = new float[normals * 3];
        boolean[] normalSeen = new boolean[normals];
        float[][] p = new float[4][3];
        float[][] texture = new float[4][2];
        Vector3f tangent = new Vector3f(),
                bitangent = new Vector3f(),
                n = new Vector3f(),
                t = new Vector3f(),
                cross = new Vector3f();
        for (int f = 0; f < faces; f++) {
            int origin = index(planeInt(bytes, faceBase, faces, 0, f), origins);
            int e1 = index(planeInt(bytes, faceBase, faces, 4, f), edges);
            int e3 = index(planeInt(bytes, faceBase, faces, 8, f), edges);
            for (int a = 0; a < 3; a++) {
                float base = value(bytes, originBase, origins, a * 4, origin);
                float first = base + value(bytes, edgeBase, edges, a * 4, e1);
                float thirdEdge = value(bytes, edgeBase, edges, a * 4, e3);
                p[0][a] = base;
                p[1][a] = restored(first, planeInt(bytes, faceBase, faces, 12 + a * 4, f));
                p[2][a] =
                        restored(
                                first + thirdEdge, planeInt(bytes, faceBase, faces, 24 + a * 4, f));
                p[3][a] =
                        restored(base + thirdEdge, planeInt(bytes, faceBase, faces, 36 + a * 4, f));
            }
            int tile = tiles[f], atlas = tileAtlases[tile] & 65535;
            Atlas image = atlases.get(atlas);
            int selector = bytes[faceBase + 60 * faces + f] & 255;
            for (int c = 0; c < 4; c++) {
                texture[c][0] =
                        uv(tile, ((selector >>> (c * 2)) & 1) == 0 ? 0 : 2) / (2F * image.width);
                texture[c][1] =
                        uv(tile, ((selector >>> (c * 2 + 1)) & 1) == 0 ? 1 : 3)
                                / (2F * image.height);
                float y = p[c][1];
                p[c][1] = p[c][2];
                p[c][2] = -y;
            }
            float du1 = texture[1][0] - texture[0][0], dv1 = texture[1][1] - texture[0][1];
            float du2 = texture[2][0] - texture[0][0], dv2 = texture[2][1] - texture[0][1];
            float det = du1 * dv2 - du2 * dv1;
            if (det == 0) throw new IOException("部件 UV 基底退化");
            tangent.set(
                            (p[1][0] - p[0][0]) * dv2 - (p[2][0] - p[0][0]) * dv1,
                            (p[1][1] - p[0][1]) * dv2 - (p[2][1] - p[0][1]) * dv1,
                            (p[1][2] - p[0][2]) * dv2 - (p[2][2] - p[0][2]) * dv1)
                    .div(det);
            bitangent
                    .set(
                            (p[2][0] - p[0][0]) * du1 - (p[1][0] - p[0][0]) * du2,
                            (p[2][1] - p[0][1]) * du1 - (p[1][1] - p[0][1]) * du2,
                            (p[2][2] - p[0][2]) * du1 - (p[1][2] - p[0][2]) * du2)
                    .div(det);
            int out = cursors[atlas] * 14;
            for (int c = 0; c < 4; c++, out += 14) {
                int ni = index(planeShort(bytes, faceBase, faces, 52 + c * 2, f), normals);
                int nt = ni * 3;
                if (normalSeen[ni]) {
                    n.set(normalTable[nt], normalTable[nt + 1], normalTable[nt + 2]);
                } else {
                    n.set(
                            value(bytes, normalBase, normals, 0, ni),
                            value(bytes, normalBase, normals, 8, ni),
                            -value(bytes, normalBase, normals, 4, ni));
                    if (n.lengthSquared() < .5F || n.lengthSquared() > 1.5F)
                        throw new IOException("部件法线无效");
                    n.normalize();
                    normalTable[nt] = n.x;
                    normalTable[nt + 1] = n.y;
                    normalTable[nt + 2] = n.z;
                    normalSeen[ni] = true;
                }
                t.set(tangent).fma(-n.dot(tangent), n);
                if (t.lengthSquared() < 1e-16F) throw new IOException("部件切线退化");
                t.normalize();
                // 源 UV 用 OpenGL 副切线；LabPBR 已翻转法线绿色通道，Iris 使用 cross(T,N)。
                float sign = n.cross(t, cross).dot(bitangent) < 0 ? -1 : 1;
                for (int a = 0; a < 3; a++) vertices[out + a] = p[c][a];
                vertices[out + 3] = texture[c][0];
                vertices[out + 4] = texture[c][1];
                vertices[out + 5] = n.x;
                vertices[out + 6] = n.y;
                vertices[out + 7] = n.z;
                vertices[out + 8] = t.x;
                vertices[out + 9] = t.y;
                vertices[out + 10] = t.z;
                vertices[out + 11] = sign;
                vertices[out + 12] = (texture[0][0] + texture[2][0]) * .5F;
                vertices[out + 13] = (texture[0][1] + texture[2][1]) * .5F;
            }
            cursors[atlas] += 4;
        }
        return new Mesh(vertices, offsets, counts);
    }

    private int uv(int tile, int component) {
        return tileUv[tile * 4 + component] & 65535;
    }

    private static boolean extent(int v) {
        return v >= 16 && v <= 8192 && Integer.bitCount(v) == 1;
    }

    private static void finite(float[] values, int count) throws IOException {
        if (values == null || values.length != count) throw new IOException("部件向量长度无效");
        for (float v : values)
            if (!Float.isFinite(v) || Math.abs(v) > 1_000_000) throw new IOException("部件向量越界");
    }

    private static void bounds(float[] values) throws IOException {
        finite(values, 6);
        for (int a = 0; a < 3; a++) if (values[a] > values[a + 3]) throw new IOException("部件包围盒无效");
    }

    private static void determinant(float[] m, int stride) throws IOException {
        double d =
                m[0]
                                * (m[stride + 1] * (double) m[2 * stride + 2]
                                        - m[stride + 2] * (double) m[2 * stride + 1])
                        - m[1]
                                * (m[stride] * (double) m[2 * stride + 2]
                                        - m[stride + 2] * (double) m[2 * stride])
                        + m[2]
                                * (m[stride] * (double) m[2 * stride + 1]
                                        - m[stride + 1] * (double) m[2 * stride]);
        if (!Double.isFinite(d) || Math.abs(d) < 1e-12) throw new IOException("部件变换不可逆");
    }

    private static int index(int value, int count) throws IOException {
        if (value < 0 || value >= count) throw new IOException("部件字典索引越界");
        return value;
    }

    private static float restored(float predicted, int residual) throws IOException {
        float value = Float.intBitsToFloat(Float.floatToRawIntBits(predicted) ^ residual);
        if (!Float.isFinite(value) || Math.abs(value) > 1_000_000) throw new IOException("部件位置无效");
        return value;
    }

    private static float value(byte[] data, int base, int count, int column, int row)
            throws IOException {
        return restored(0, planeInt(data, base, count, column, row));
    }

    private static int planeShort(byte[] data, int base, int count, int column, int row) {
        return (data[base + column * count + row] & 255)
                | (data[base + (column + 1) * count + row] & 255) << 8;
    }

    private static int planeInt(byte[] data, int base, int count, int column, int row) {
        return planeShort(data, base, count, column, row)
                | planeShort(data, base, count, column + 2, row) << 16;
    }
}
