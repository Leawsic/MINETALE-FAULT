package cn.jehorstudio.minetale.voxel.scene.geometry;

import it.unimi.dsi.fastutil.floats.FloatArrayList;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.BitSet;

// Low 由轴对齐矩形组成闭合体；该类按采样格点恢复离散占用，供截面和遮挡查询使用。
final class SceneLowVolume {
    final double step;
    final double[] origin;
    final int[] size;
    final BitSet occupied;

    private SceneLowVolume(double[] origin, int[] size, BitSet occupied, double step) {
        this.step = step;
        this.origin = origin;
        this.size = size;
        this.occupied = occupied;
    }

    static SceneLowVolume build(float[][] low) {
        return build(low, 2);
    }

    static SceneLowVolume build(float[][] low, double step) {
        double[] origin = {
            Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY
        };
        double[] end = {
            Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY
        };
        for (float[] mesh : low)
            for (int i = 0; i < mesh.length; i += 14)
                for (int axis = 0; axis < 3; axis++) {
                    origin[axis] = Math.min(origin[axis], mesh[i + axis]);
                    end[axis] = Math.max(end[axis], mesh[i + axis]);
                }
        int[] size = new int[3];
        for (int axis = 0; axis < 3; axis++)
            size[axis] = (int) Math.round((end[axis] - origin[axis]) / step);
        BitSet[] boundaries = {new BitSet(), new BitSet()};
        // 沿 X 轴扫描只读取 X 法线方向的面，并将分页矩形片段归并到同一单元边界。
        for (float[] mesh : low) {
            for (int start = 0; start < mesh.length; start += 84) {
                if (Math.abs(mesh[start + 5]) < .9999) continue;
                int plane = (int) Math.round((mesh[start] - origin[0]) / step);
                int direction = mesh[start + 5] > 0 ? 1 : 0;
                double minY = Double.POSITIVE_INFINITY, minZ = minY;
                double maxY = Double.NEGATIVE_INFINITY, maxZ = maxY;
                for (int corner = 0; corner < 6; corner++) {
                    int at = start + corner * 14;
                    minY = Math.min(minY, mesh[at + 1]);
                    maxY = Math.max(maxY, mesh[at + 1]);
                    minZ = Math.min(minZ, mesh[at + 2]);
                    maxZ = Math.max(maxZ, mesh[at + 2]);
                }
                int firstY = (int) Math.floor((minY - origin[1]) / step + 1e-5);
                int lastY = (int) Math.ceil((maxY - origin[1]) / step - 1e-5);
                int firstZ = (int) Math.floor((minZ - origin[2]) / step + 1e-5);
                int lastZ = (int) Math.ceil((maxZ - origin[2]) / step - 1e-5);
                for (int z = firstZ; z < lastZ; z++)
                    for (int y = firstY; y < lastY; y++)
                        boundaries[direction].set((plane * size[2] + z) * size[1] + y);
            }
        }
        BitSet occupied = new BitSet(size[0] * size[1] * size[2]);
        for (int z = 0; z < size[2]; z++)
            for (int y = 0; y < size[1]; y++) {
                boolean inside = false;
                for (int x = 0; x < size[0]; x++) {
                    int edge = (x * size[2] + z) * size[1] + y;
                    if (boundaries[0].get(edge)) inside = true;
                    if (boundaries[1].get(edge)) inside = false;
                    if (inside) occupied.set((z * size[1] + y) * size[0] + x);
                }
            }
        return new SceneLowVolume(origin, size, occupied, step);
    }

    boolean cell(int x, int y, int z) {
        return x >= 0
                && y >= 0
                && z >= 0
                && x < size[0]
                && y < size[1]
                && z < size[2]
                && occupied.get((z * size[1] + y) * size[0] + x);
    }

    boolean inside(double x, double y, double z) {
        return cell(
                (int) Math.floor((x - origin[0]) / step),
                (int) Math.floor((y - origin[1]) / step),
                (int) Math.floor((z - origin[2]) / step));
    }

    boolean beside(double[] point, int axis, boolean positive) {
        int[] cell = new int[3];
        for (int a = 0; a < 3; a++) {
            double grid = (point[a] - origin[a]) / step;
            // 在格点域计算相邻侧，nextDown 作用于格点值，保持世界零点负侧的边界判定稳定。
            if (a == axis && !positive) grid = Math.nextDown(grid);
            cell[a] = (int) Math.floor(grid);
        }
        return cell(cell[0], cell[1], cell[2]);
    }

    boolean containsBox(double[] box) {
        int[] first = new int[3], last = new int[3];
        for (int a = 0; a < 3; a++) {
            first[a] = (int) Math.floor((box[a] - origin[a]) / step);
            last[a] = (int) Math.floor(Math.nextDown((box[a + 3] - origin[a]) / step));
        }
        for (int z = first[2]; z <= last[2]; z++)
            for (int y = first[1]; y <= last[1]; y++)
                for (int x = first[0]; x <= last[0]; x++) if (!cell(x, y, z)) return false;
        return true;
    }

    float[] cuts(int axis, float first, float last) {
        FloatArrayList cuts = new FloatArrayList();
        cuts.add(first);
        int start = (int) Math.floor((first - origin[axis]) / step) + 1;
        for (int i = start; origin[axis] + i * step < last; i++)
            cuts.add((float) (origin[axis] + i * step));
        cuts.add(last);
        return cuts.toFloatArray();
    }

    long bytes() {
        return occupied.toLongArray().length * 8L + 36;
    }

    void write(DataOutput out) throws IOException {
        for (double d : origin) out.writeDouble(d);
        for (int n : size) out.writeInt(n);
        long[] words = occupied.toLongArray();
        out.writeInt(words.length);
        for (long word : words) out.writeLong(word);
    }

    static SceneLowVolume read(DataInput in, double step) throws IOException {
        double[] origin = new double[3];
        int[] size = new int[3];
        for (int a = 0; a < 3; a++) origin[a] = in.readDouble();
        for (int a = 0; a < 3; a++) size[a] = in.readInt();
        int count = in.readInt();
        long[] words = new long[count];
        for (int i = 0; i < count; i++) words[i] = in.readLong();
        BitSet bits = BitSet.valueOf(words);
        return new SceneLowVolume(origin, size, bits, step);
    }
}
