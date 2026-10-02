package cn.jehorstudio.minetale.voxel.scene.geometry;

import cn.jehorstudio.minetale.voxel.scene.asset.SceneAsset;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/** 固定表面的紧凑采样来源。一个实例由一个生成线程顺序使用，生成结果由调用方持有。 */
public final class SceneFixedSource {
    public record Batch(int size, float resolution, float[] vertices, float[] samples, int[] texels) {}

    private final float[] triangles;
    private final int[] materials;
    private final SceneBvh tree;
    private final double[] point = new double[3], best = new double[2];
    private final java.util.function.IntToDoubleFunction distance = this::distance;

    public SceneFixedSource(SceneAsset asset) throws IOException {
        ByteBuffer in = ByteBuffer.wrap(asset.readSource("raw/fixed/source.bin")).order(ByteOrder.LITTLE_ENDIAN);
        if (in.remaining() < 8 || in.getInt() != 0x4d544653) throw new IOException("固定材质来源头无效");
        int count = in.getInt();
        if (count < 1 || count > 1_000_000 || in.remaining() != count * 40L)
            throw new IOException("固定材质来源长度无效");
        triangles = new float[count * 9]; materials = new int[count];
        double[][] bounds = new double[count][6];
        for (int i = 0; i < count; i++) {
            Arrays.fill(bounds[i], 0, 3, Double.POSITIVE_INFINITY);
            Arrays.fill(bounds[i], 3, 6, Double.NEGATIVE_INFINITY);
            for (int k = 0; k < 9; k++) {
                float v = finite(in.getFloat()); triangles[i * 9 + k] = v;
                bounds[i][k % 3] = Math.min(bounds[i][k % 3], v);
                bounds[i][k % 3 + 3] = Math.max(bounds[i][k % 3 + 3], v);
            }
            materials[i] = in.getInt();
            if (materials[i] < 0 || materials[i] > 7) throw new IOException("固定来源材质越界");
        }
        tree = new SceneBvh(bounds);
    }

    /** 由面参数重建一个图集批次；不保存逐纹素坐标和完整图集索引。 */
    public Batch generate(byte[] encoded) throws IOException {
        ByteBuffer in = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN);
        if (in.remaining() < 20 || in.getInt() != 0x4d544650) throw new IOException("固定采样参数头无效");
        int size = in.getInt(), vf = in.getInt(), count = in.getInt();
        float resolution = in.getFloat();
        if (size < 16 || size > 4096 || Integer.bitCount(size) != 1 || vf <= 0 || vf % 56 != 0
                || count < 1 || count > size * size || resolution != 2
                || in.remaining() != vf * 4L + vf / 56L * 84)
            throw new IOException("固定采样参数长度无效");
        float[] vertices = new float[vf], samples = new float[count * 7];
        for (int i = 0; i < vf; i++) vertices[i] = finite(in.getFloat());
        int[] texels = new int[size * size]; Arrays.fill(texels, -1);
        float[] basis = new float[14]; int first = 0;
        for (int f = 0; f < vf / 56; f++) {
            int ax = in.getInt(), ay = in.getInt(), w = in.getInt(), h = in.getInt();
            int pw = in.getInt(), ph = in.getInt(), material = in.getInt();
            if (ax < 0 || ay < 0 || w < 1 || h < 1 || pw < w + 2L || ph < h + 2L
                    || ax + (long) pw > size || ay + (long) ph > size
                    || first + (long) w * h > count || material < -1 || material > 7)
                throw new IOException("固定面采样范围无效");
            for (int k = 0; k < 14; k++) basis[k] = finite(in.getFloat());
            for (int k = 0; k < 9; k += 3) {
                float length = basis[k]*basis[k] + basis[k+1]*basis[k+1] + basis[k+2]*basis[k+2];
                if (Math.abs(length - 1) > .01) throw new IOException("固定面采样基底无效");
            }
            if (basis[11] < basis[9] || basis[12] < basis[10]) throw new IOException("固定面采样区间无效");
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
                if ((x & 255) == 0 && Thread.currentThread().isInterrupted())
                    throw new java.util.concurrent.CancellationException();
                float u = (float) (basis[9] + (x + .5) * (basis[11] - basis[9]) / w);
                float v = (float) (basis[10] + (y + .5) * (basis[12] - basis[10]) / h);
                int at = (first + y * w + x) * 7;
                for (int k = 0; k < 3; k++) {
                    float p = (basis[k] * u + basis[k+3] * v) + basis[k+6] * basis[13];
                    samples[at+k] = finite(p); point[k] = p;
                    samples[at+k+3] = basis[k+6];
                }
                samples[at+6] = material >= 0 ? material : materials[tree.nearest(point, distance, best)];
            }
            for (int y = 0; y < ph; y++) for (int x = 0; x < pw; x++) {
                int at = (ay + y) * size + ax + x;
                if (texels[at] != -1) throw new IOException("固定图集区域重叠");
                texels[at] = first + Math.clamp(y - 1, 0, h - 1) * w + Math.clamp(x - 1, 0, w - 1);
            }
            first += w * h;
        }
        if (first != count) throw new IOException("固定采样数量不匹配");
        return new Batch(size, resolution, vertices, samples, texels);
    }

    private double distance(int id) {
        int at = id * 9;
        double ax = triangles[at], ay = triangles[at+1], az = triangles[at+2];
        double bx = triangles[at+3]-ax, by = triangles[at+4]-ay, bz = triangles[at+5]-az;
        double cx = triangles[at+6]-ax, cy = triangles[at+7]-ay, cz = triangles[at+8]-az;
        double px = point[0]-ax, py = point[1]-ay, pz = point[2]-az;
        double bb = bx*bx+by*by+bz*bz, cc = cx*cx+cy*cy+cz*cz, bc = bx*cx+by*cy+bz*cz;
        double pb = px*bx+py*by+pz*bz, pc = px*cx+py*cy+pz*cz;
        double determinant = bb*cc-bc*bc;
        if (determinant > 0) {
            double u = (pb*cc-pc*bc)/determinant, v = (pc*bb-pb*bc)/determinant;
            if (u >= 0 && v >= 0 && u+v <= 1) {
                double x = px-u*bx-v*cx, y = py-u*by-v*cy, z = pz-u*bz-v*cz;
                return x*x+y*y+z*z;
            }
        }
        return Math.min(edge(px,py,pz,bx,by,bz), Math.min(edge(px,py,pz,cx,cy,cz),
                edge(px-bx,py-by,pz-bz,cx-bx,cy-by,cz-bz)));
    }

    private static double edge(double x, double y, double z, double a, double b, double c) {
        double length = a*a+b*b+c*c, t = length == 0 ? 0 : Math.clamp((x*a+y*b+z*c)/length, 0, 1);
        x -= t*a; y -= t*b; z -= t*c;
        return x*x+y*y+z*z;
    }

    private static float finite(float value) throws IOException {
        if (!Float.isFinite(value)) throw new IOException("固定来源含非有限值");
        return value;
    }
}
