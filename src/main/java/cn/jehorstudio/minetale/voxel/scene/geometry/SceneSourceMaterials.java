package cn.jehorstudio.minetale.voxel.scene.geometry;

import cn.jehorstudio.minetale.voxel.scene.asset.SceneAsset;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

import javax.imageio.ImageIO;

// Raw 三角形保存 UV 和材质来源；High 只从已选中的三角形采样。
final class SceneSourceMaterials {
    static final class Definition {
        float[] base_color, emission;
        float opacity = 1, roughness = .5F, metallic;
        Map<String, String> textures;
    }

    private record Texture(int width, int height, int[] pixels) {
        float channel(double u, double v, int channel, boolean linear) {
            double x = (u - Math.floor(u)) * width - .5;
            double y = (1 - (v - Math.floor(v))) * height - .5;
            int ix = (int) Math.floor(x), iy = (int) Math.floor(y);
            double fx = x - ix, fy = y - iy, value = 0;
            for (int dy = 0; dy < 2; dy++)
                for (int dx = 0; dx < 2; dx++) {
                    int rgba =
                            pixels[
                                    Math.floorMod(iy + dy, height) * width
                                            + Math.floorMod(ix + dx, width)];
                    int shift = channel == 3 ? 24 : 16 - channel * 8;
                    value +=
                            (linear && channel < 3 ? toLinear(((rgba >>> shift) & 255) / 255F) * 255 : ((rgba >>> shift) & 255))
                                    * (dx == 0 ? 1 - fx : fx)
                                    * (dy == 0 ? 1 - fy : fy);
                }
            return (float) (value / 255);
        }
    }

    private record Triangle(float[][] positions, float[][] uv, int material) {}

    private final Definition[] definitions;
    private final Map<String, Texture> textures = new ConcurrentHashMap<>();
    private final SceneAsset asset;
    private final ArrayList<Triangle> triangles = new ArrayList<>();
    private final java.util.concurrent.atomic.AtomicLong textureBytes = new java.util.concurrent.atomic.AtomicLong();
    private final cn.jehorstudio.minetale.voxel.scene.asset.SceneChannels channels;

    SceneSourceMaterials(SceneAsset asset, Definition[] definitions) throws IOException {
        if (definitions == null || definitions.length == 0) throw new IOException("Raw 材质缺失");
        this.definitions = definitions;
        this.asset = asset;
        channels = asset.channels();
        for (Definition material : definitions) {
            if (material == null) throw new IOException("Raw 材质缺失");
            vector(material.base_color, 3);
            vector(material.emission, 3);
            for (float value :
                    new float[] {material.opacity, material.roughness, material.metallic})
                if (!Float.isFinite(value) || value < 0 || value > 1)
                    throw new IOException("Raw 材质参数越界");
            if (material.textures == null) material.textures = Map.of();
            for (String path : material.textures.values()) {
                if (path == null || !path.matches("raw/materials/[A-Za-z0-9_.-]+\\.png"))
                    throw new IOException("Raw 纹理路径无效");

            }
        }
    }

    private static void vector(float[] values, int length) throws IOException {
        if (values == null || values.length < length) throw new IOException("Raw 材质向量缺失");
        for (float value : values) if (!Float.isFinite(value)) throw new IOException("Raw 材质向量无效");
    }

    int add(float[][] positions, SceneSurface.Face face) throws IOException {
        if (face.material >= definitions.length
                || face.vertices.length != 3
                || face.triangles.length != 1
                || face.uv == null
                || face.uv.length != 3
                || face.normals == null
                || face.normals.length != 3) throw new IOException("Raw 材质要求带 UV 和逐角法线的三角形");
        float[][] points = new float[3][];
        for (int i = 0; i < 3; i++) {
            if (face.triangles[0][i] != face.vertices[i]) throw new IOException("Raw 三角形顶点顺序不一致");
            vector(face.uv[i], 2);
            vector(face.normals[i], 3);
            if (face.uv[i].length != 2 || face.normals[i].length != 3)
                throw new IOException("Raw UV 或法线分量数量不符");
            double length = dot(face.normals[i], face.normals[i]);
            if (Math.abs(length - 1) > .002) throw new IOException("Raw 法线未归一化");
            points[i] = positions[face.vertices[i]];
        }
        int id = triangles.size();
        // 采样记录使用 float 保存来源三角形 ID，因此 ID 必须保持整数精确表示。
        if (id > 1 << 24) throw new IOException("Raw 三角形 ID 超出 float 精确整数范围");
        triangles.add(new Triangle(points, face.uv, face.material));
        return id;
    }

    long bytes() {
        return textureBytes.get() + triangles.size() * 64L;
    }

    long textureBytes() { return textureBytes.get(); }
    void trimTextures(long limit) {
        for (var entry : textures.entrySet()) {
            if (textureBytes.get() <= limit) break;
            if (textures.remove(entry.getKey(), entry.getValue())) textureBytes.addAndGet(-entry.getValue().pixels.length * 4L);
        }
    }

    int materialId(int sourceId) { return triangles.get(sourceId).material; }

    private void samplePoint(float[] samples, int offset) {
        Triangle triangle = triangles.get((int) samples[offset + 7]);
        float[][] p = triangle.positions;
        double[] e = difference(p[1], p[0]), f = difference(p[2], p[0]);
        double[] q = {
            samples[offset] - p[0][0], samples[offset + 1] - p[0][1], samples[offset + 2] - p[0][2]
        };
        double ee = dot(e, e), ff = dot(f, f), ef = dot(e, f), qe = dot(q, e), qf = dot(q, f);
        double determinant = ee * ff - ef * ef;
        double b = determinant > 1e-24 ? (qe * ff - qf * ef) / determinant : -1;
        double c = determinant > 1e-24 ? (qf * ee - qe * ef) / determinant : -1;
        double[] weights = {1 - b - c, b, c};
        if (b < 0 || c < 0 || b + c > 1) {
            double best = Double.POSITIVE_INFINITY;
            for (int i = 0; i < 3; i++) {
                int j = (i + 1) % 3;
                double[] edge = difference(p[j], p[i]);
                double[] delta = {
                    samples[offset] - p[i][0],
                    samples[offset + 1] - p[i][1],
                    samples[offset + 2] - p[i][2]
                };
                double t =
                        dot(edge, edge) > 0
                                ? Math.clamp(dot(delta, edge) / dot(edge, edge), 0, 1)
                                : 0;
                double distance = 0;
                for (int a = 0; a < 3; a++) distance += Math.pow(delta[a] - t * edge[a], 2);
                if (distance < best) {
                    best = distance;
                    java.util.Arrays.fill(weights, 0);
                    weights[i] = 1 - t;
                    weights[j] = t;
                }
            }
        }
        double u = 0, v = 0;
        for (int i = 0; i < 3; i++) {
            u += weights[i] * triangle.uv[i][0];
            v += weights[i] * triangle.uv[i][1];
        }
        Definition m = definitions[triangle.material];
        var declaration = channels.material(triangle.material);
        float[] color = new float[4], normal = {.5F, .5F, 1, 1}, specular = new float[4];
        int channels = (int) samples[offset + 6];
        float intensity = Math.max(declaration.emission().x(), Math.max(declaration.emission().y(), declaration.emission().z()));
        if ((channels & 1) != 0) {
            for (int i = 0; i < 3; i++) {
                float emission = (channels & 16) != 0 ? value(m, "emission", u, v, i, 1) * toLinear(m.emission[i])
                        : i == 0 ? declaration.emission().x() : i == 1 ? declaration.emission().y() : declaration.emission().z();
                color[i] = Math.max(value(m, "base", u, v, i, 1) * toLinear(m.base_color[i]), emission);
                intensity = Math.max(intensity, emission);
            }
            color[3] = value(m, "opacity", u, v, 0, 1) * m.opacity * value(m, "base", u, v, 3, 1);
        }
        specular[0] = 1 - ((channels & 4) != 0 ? value(m, "roughness", u, v, 0, m.roughness) : declaration.roughness().x());
        specular[1] = ((channels & 8) != 0 ? value(m, "metallic", u, v, 0, m.metallic) : declaration.metalness().x()) >= .5F ? 1 : 10F / 255;
        if (!declaration.normal().variable()) {
            normal[0] = declaration.normal().x() * .5F + .5F;
            normal[1] = declaration.normal().y() * .5F + .5F;
        }
        if ((channels & 2) != 0) {
            normal[2] = value(m, "ambient_occlusion", u, v, 0, 1);
            if (m.textures.containsKey("normal")) {
                double[] n = {samples[offset + 3], samples[offset + 4], samples[offset + 5]};
                double[] tangent =
                        Math.abs(n[1]) < .9
                                ? new double[] {n[2], 0, -n[0]}
                                : new double[] {0, -n[2], n[1]};
                normalize(tangent);
                double[] bitangent = cross(tangent, n);
                double du1 = triangle.uv[1][0] - triangle.uv[0][0],
                        dv1 = triangle.uv[1][1] - triangle.uv[0][1];
                double du2 = triangle.uv[2][0] - triangle.uv[0][0],
                        dv2 = triangle.uv[2][1] - triangle.uv[0][1];
                double uvDet = du1 * dv2 - du2 * dv1;
                if (Math.abs(uvDet) > 1e-15) {
                    double[] sourceT = new double[3], sourceB = new double[3];
                    for (int a = 0; a < 3; a++) {
                        sourceT[a] = (e[a] * dv2 - f[a] * dv1) / uvDet;
                        sourceB[a] = (f[a] * du1 - e[a] * du2) / uvDet;
                    }
                    double projection = dot(sourceT, n);
                    for (int a = 0; a < 3; a++) sourceT[a] -= projection * n[a];
                    normalize(sourceT);
                    double sign = dot(cross(n, sourceT), sourceB) < 0 ? -1 : 1;
                    sourceB = cross(n, sourceT);
                    double x = value(m, "normal", u, v, 0, .5F) * 2 - 1;
                    double y = value(m, "normal", u, v, 1, .5F) * 2 - 1;
                    double z = value(m, "normal", u, v, 2, 1) * 2 - 1;
                    double[] world = new double[3];
                    for (int a = 0; a < 3; a++)
                        world[a] = sourceT[a] * x + sourceB[a] * y * sign + n[a] * z;
                    normalize(world);
                    normal[0] = (float) (dot(world, tangent) * .5 + .5);
                    normal[1] = (float) (dot(world, bitangent) * .5 + .5);
                }
            }
        }
        for (int i = 0; i < 3; i++) color[i] = toSrgb(color[i]);
        samples[offset] = Float.intBitsToFloat(pack(color));
        samples[offset + 1] = Float.intBitsToFloat(pack(normal));
        samples[offset + 2] = Float.intBitsToFloat(pack(specular));
        samples[offset + 3] = Float.intBitsToFloat(Math.round(Math.clamp(intensity, 0, 1) * 255));
    }

    private float value(
            Definition m, String channel, double u, double v, int component, float fallback) {
        String path = m.textures.get(channel);
        if (path == null) return fallback;
        Texture texture = textures.computeIfAbsent(path, this::readTexture);
        return texture.channel(u, v, component, channel.equals("base") || channel.equals("emission"));
    }

    private Texture readTexture(String path) {
        try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(asset.readSource(path)))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IOException("Raw 纹理无法解码");
            var reader = readers.next();
            try {
                reader.setInput(input);
                int w = reader.getWidth(0), h = reader.getHeight(0);
                if (w < 1 || h < 1 || (long) w * h > 64L * 1024 * 1024) throw new IOException("Raw 纹理尺寸无效");
                var image = reader.read(0);
                try {
                    Texture result = new Texture(w, h, image.getRGB(0, 0, w, h, null, 0, w));
                    textureBytes.addAndGet((long) w * h * 4);
                    return result;
                } finally { image.flush(); }
            } finally { reader.dispose(); }
        } catch (IOException invalid) { throw new java.io.UncheckedIOException(invalid); }
    }

    // 同一世界足迹使用确定的四个子格；颜色在线性空间平均，法线归一化后把离散度传给粗糙度。
    void sample(float[] samples, int offset) {
        float[] original = java.util.Arrays.copyOfRange(samples, offset, offset + SceneVoxels.SAMPLE_FLOATS);
        double[] n = {original[3], original[4], original[5]};
        double[] tangent = Math.abs(n[1]) < .9 ? new double[]{n[2], 0, -n[0]} : new double[]{0, -n[2], n[1]};
        normalize(tangent);
        double[] bitangent = cross(tangent, n), averageNormal = new double[3];
        float[] color = new float[4], normal = new float[4], specular = new float[4];
        float roughness = 0, emission = 0, ao = 0;
        int metal = 0;
        float[] point = original.clone();
        for (int tap = 0; tap < 4; tap++) {
            System.arraycopy(original, 0, point, 0, original.length);
            double u = ((tap & 1) == 0 ? -.25 : .25) * original[8];
            double v = ((tap & 2) == 0 ? -.25 : .25) * original[8];
            for (int axis = 0; axis < 3; axis++) point[axis] += (float) (tangent[axis] * u + bitangent[axis] * v);
            samplePoint(point, 0);
            int c = Float.floatToRawIntBits(point[0]), nn = Float.floatToRawIntBits(point[1]), sp = Float.floatToRawIntBits(point[2]);
            for (int channel = 0; channel < 4; channel++) {
                float value = ((c >>> (channel * 8)) & 255) / 255F;
                color[channel] += (channel < 3 ? toLinear(value) : value) * .25F;
            }
            double nx = (nn & 255) / 127.5 - 1, ny = ((nn >>> 8) & 255) / 127.5 - 1;
            averageNormal[0] += nx * .25; averageNormal[1] += ny * .25;
            averageNormal[2] += Math.sqrt(Math.max(0, 1 - nx * nx - ny * ny)) * .25;
            ao += ((nn >>> 16) & 255) / 1020F;
            roughness += (1 - (sp & 255) / 255F) * .25F;
            if (((sp >>> 8) & 255) >= 230) metal++;
            emission += Float.floatToRawIntBits(point[3]) / 1020F;
        }
        double length = Math.sqrt(dot(averageNormal, averageNormal));
        float variance = (((int) original[6]) & 2) != 0 ? (float)((1 - Math.min(1, length)) / Math.max(length, 1e-4)) : 0;
        normalize(averageNormal);
        for (int i = 0; i < 3; i++) color[i] = toSrgb(color[i]);
        normal[0] = (float)(averageNormal[0] * .5 + .5); normal[1] = (float)(averageNormal[1] * .5 + .5); normal[2] = ao; normal[3] = 1;
        specular[0] = 1 - roughness;
        specular[1] = metal >= 2 ? 1 : 10F / 255;
        samples[offset] = Float.intBitsToFloat(pack(color)); samples[offset + 1] = Float.intBitsToFloat(pack(normal));
        samples[offset + 2] = Float.intBitsToFloat(pack(specular)); samples[offset + 3] = Float.intBitsToFloat(Math.round(Math.clamp(emission, 0, 1) * 255));
        samples[offset + 10] = variance;
    }

    private static float toLinear(float v) { return v <= .04045F ? v / 12.92F : (float)Math.pow((v + .055F) / 1.055F, 2.4); }
    private static float toSrgb(float v) { return v <= .0031308F ? v * 12.92F : 1.055F * (float)Math.pow(v, 1 / 2.4) - .055F; }

    private static int pack(float[] values) {
        int packed = 0;
        for (int i = 0; i < 4; i++)
            packed |= Math.round(Math.clamp(values[i], 0, 1) * 255) << (i * 8);
        return packed;
    }

    private static double[] difference(float[] a, float[] b) {
        return new double[] {a[0] - b[0], a[1] - b[1], a[2] - b[2]};
    }

    private static double dot(float[] a, float[] b) {
        return (double) a[0] * b[0] + (double) a[1] * b[1] + (double) a[2] * b[2];
    }

    private static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    private static double[] cross(double[] a, double[] b) {
        return new double[] {
            a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]
        };
    }

    private static void normalize(double[] vector) {
        double length = Math.sqrt(dot(vector, vector));
        if (length > 1e-15) for (int a = 0; a < 3; a++) vector[a] /= length;
    }
}
