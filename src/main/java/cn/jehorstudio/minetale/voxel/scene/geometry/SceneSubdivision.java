package cn.jehorstudio.minetale.voxel.scene.geometry;

import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

// 控制网格保持完整；局部求值携带两圈顶点邻接面，输出只属于请求的原始面。
final class SceneSubdivision {
    private final float[][] positions;
    private final SceneSurface.Face[] faces;
    private final IntArrayList[] incident;
    private final LongOpenHashSet creases = new LongOpenHashSet();
    private int[][] preparedSupport;
    private int[][] preparedVertices;
    private long[][] preparedSharp;

    long bytes() {
        long bytes = positions.length * 12L + creases.size() * 8L;
        for (var list : incident) bytes += list.elements().length * 4L;
        if (preparedSupport != null) for (int[] values : preparedSupport) bytes += values.length * 4L;
        if (preparedVertices != null) for (int[] values : preparedVertices) bytes += values.length * 4L;
        if (preparedSharp != null) for (long[] values : preparedSharp) bytes += values.length * 8L;
        return bytes;
    }

    float[][] fittedPositions() {
        return positions;
    }

    int[][] prepareSupport() {
        if (preparedSupport == null) {
            preparedSupport = new int[faces.length][];
            for (int f = 0; f < faces.length; f++)
                preparedSupport[f] =
                        neighborhood(Set.of(f), 2).stream().mapToInt(Integer::intValue).toArray();
        }
        return preparedSupport;
    }

    void writePrepared(java.io.DataOutput out) throws java.io.IOException {
        prepareSupport();
        for (int f = 0; f < faces.length; f++) {
            out.writeInt(preparedSupport[f].length);
            for (int v : preparedSupport[f]) out.writeInt(v);
            TreeSet<Integer> used = new TreeSet<>();
            LongOpenHashSet sharp = new LongOpenHashSet();
            for (int face : preparedSupport[f]) {
                int[] corners = faces[face].vertices;
                for (int i = 0; i < corners.length; i++) {
                    used.add(corners[i]);
                    long e = edge(corners[i], corners[(i + 1) % corners.length]);
                    if (creases.contains(e)) sharp.add(e);
                }
            }
            out.writeInt(used.size());
            for (int v : used) out.writeInt(v);
            long[] edges = sharp.toLongArray();
            Arrays.sort(edges);
            out.writeInt(edges.length);
            for (long e : edges) out.writeLong(e);
        }
    }

    void readPrepared(java.io.DataInput in) throws java.io.IOException {
        preparedSupport = new int[faces.length][];
        preparedVertices = new int[faces.length][];
        preparedSharp = new long[faces.length][];
        for (int f = 0; f < faces.length; f++) {
            preparedSupport[f] = readOrdered(in, faces.length);
            if (Arrays.binarySearch(preparedSupport[f], f) < 0)
                throw new java.io.IOException("静态邻域缺少自身");
            preparedVertices[f] = readOrdered(in, positions.length);
            int n = SceneIndex.length(in, creases.size());
            preparedSharp[f] = new long[n];
            for (int i = 0; i < n; i++) {
                long e = preparedSharp[f][i] = in.readLong();
                if (!creases.contains(e)
                        || Arrays.binarySearch(preparedVertices[f], (int) (e >>> 32)) < 0
                        || Arrays.binarySearch(preparedVertices[f], (int) e) < 0
                        || i > 0 && e <= preparedSharp[f][i - 1])
                    throw new java.io.IOException("静态折痕关联无效");
            }
            for (int face : preparedSupport[f])
                for (int v : faces[face].vertices)
                    if (Arrays.binarySearch(preparedVertices[f], v) < 0)
                        throw new java.io.IOException("静态局部顶点映射不完整");
        }
    }

    private static int[] readOrdered(java.io.DataInput in, int max) throws java.io.IOException {
        int n = SceneIndex.length(in, max);
        if (n == 0) throw new java.io.IOException("静态邻域为空");
        int[] result = new int[n];
        for (int i = 0; i < n; i++) {
            result[i] = in.readInt();
            if (result[i] < 0 || result[i] >= max || i > 0 && result[i] <= result[i - 1])
                throw new java.io.IOException("静态邻域索引错误");
        }
        return result;
    }

    SceneSubdivision(
            float[][] positions,
            SceneSurface.Face[] faces,
            float[][] creases,
            boolean surfaceSamples,
            int levels) {
        this.faces = faces;
        incident = new IntArrayList[positions.length];
        for (int i = 0; i < incident.length; i++) incident[i] = new IntArrayList();
        for (int f = 0; f < faces.length; f++) for (int v : faces[f].vertices) incident[v].add(f);
        for (float[] c : creases) if (c[2] > 0) this.creases.add(edge((int) c[0], (int) c[1]));
        this.positions = surfaceSamples ? fitControlPoints(positions, levels) : positions;
    }

    private float[][] fitControlPoints(float[][] samples, int levels) {
        double[][] control = new double[samples.length][3];
        for (int v = 0; v < samples.length; v++)
            for (int axis = 0; axis < 3; axis++) control[v][axis] = samples[v][axis];
        List<Polygon> polygons = new ArrayList<>();
        for (int f = 0; f < faces.length; f++) polygons.add(new Polygon(faces[f].vertices, f));
        Topology topology = new Topology(new State(control, polygons, creases));
        Stencil[] stencils = new Stencil[samples.length];
        for (int v = 0; v < samples.length; v++) stencils[v] = sampleStencil(topology, v, levels);
        double[][] next = new double[samples.length][3];
        for (int iteration = 0; iteration < 256; iteration++) {
            boolean converged = true;
            for (int v = 0; v < samples.length; v++) {
                Stencil stencil = stencils[v];
                for (int axis = 0; axis < 3; axis++) {
                    double limit = 0;
                    for (int i = 0; i < stencil.vertices.length; i++)
                        limit += stencil.weights[i] * control[stencil.vertices[i]][axis];
                    double residual = samples[v][axis] - limit;
                    converged &=
                            Math.abs(residual) <= Math.max(1e-6, Math.ulp(samples[v][axis]) * .125);
                    next[v][axis] = control[v][axis] + residual;
                }
            }
            if (converged) {
                float[][] result = new float[samples.length][3];
                for (int v = 0; v < samples.length; v++)
                    for (int axis = 0; axis < 3; axis++) result[v][axis] = (float) control[v][axis];
                return result;
            }
            double[][] previous = control;
            control = next;
            next = previous;
        }
        throw new IllegalArgumentException("表面采样的 CC 控制笼求解未收敛");
    }

    private Stencil sampleStencil(Topology topology, int vertex, int levels) {
        List<Edge> edges = topology.atVertex[vertex];
        List<Edge> sharp = edges.stream().filter(e -> e.sharp).toList();
        if (sharp.size() > 2 || edges.isEmpty())
            return new Stencil(new int[] {vertex}, new double[] {1});
        if (sharp.size() == 2)
            return new Stencil(
                    new int[] {vertex, sharp.get(0).other(vertex), sharp.get(1).other(vertex)},
                    new double[] {4.0 / 6, 1.0 / 6, 1.0 / 6});

        IntArrayList adjacent = topology.facesAtVertex[vertex];
        Map<Integer, Integer> indices = new LinkedHashMap<>();
        for (int f : adjacent)
            for (int v : faces[f].vertices) indices.computeIfAbsent(v, ignored -> indices.size());
        int width = indices.size(), count = adjacent.size();
        int[][] faceEdges = new int[count][2], edgeFaces = new int[edges.size()][2];
        double[] point = new double[width];
        point[indices.get(vertex)] = 1;
        double[][] neighbors = new double[edges.size()][width];
        double[][] centers = new double[count][width];
        for (int i = 0; i < edges.size(); i++) {
            Edge e = edges.get(i);
            neighbors[i][indices.get(e.other(vertex))] = 1;
            if (!e.sharp) {
                edgeFaces[i][0] = adjacent.indexOf(e.faces.getInt(0));
                edgeFaces[i][1] = adjacent.indexOf(e.faces.getInt(1));
            }
        }
        for (int f = 0; f < count; f++) {
            int[] corners = faces[adjacent.getInt(f)].vertices;
            for (int i = 0; i < corners.length; i++) {
                centers[f][indices.get(corners[i])] += 1.0 / corners.length;
                if (corners[i] == vertex) {
                    faceEdges[f][0] =
                            edges.indexOf(
                                    topology.edges.get(
                                            edge(vertex, corners[(i + 1) % corners.length])));
                    faceEdges[f][1] =
                            edges.indexOf(
                                    topology.edges.get(
                                            edge(
                                                    vertex,
                                                    corners[
                                                            (i + corners.length - 1)
                                                                    % corners.length])));
                }
            }
        }
        double[][] diagonals = null;
        // 原顶点每次细分后的邻域只包含新边点和面点，传播这些权重即可。
        // 求解控制笼时按局部邻域传播权重；n-gon 和单条硬折痕端点沿用实际求值规则。
        for (int level = 0; level < levels; level++) {
            double[] refined = new double[width];
            addWeights(refined, point, (count - 2.0) / count);
            for (double[] neighbor : neighbors)
                addWeights(refined, neighbor, 1.0 / (edges.size() * count));
            for (double[] center : centers) addWeights(refined, center, 1.0 / (count * count));
            double[][] refinedNeighbors = new double[edges.size()][width];
            for (int i = 0; i < edges.size(); i++) {
                boolean hard = edges.get(i).sharp;
                addWeights(refinedNeighbors[i], point, hard ? .5 : .25);
                addWeights(refinedNeighbors[i], neighbors[i], hard ? .5 : .25);
                if (!hard) {
                    addWeights(refinedNeighbors[i], centers[edgeFaces[i][0]], .25);
                    addWeights(refinedNeighbors[i], centers[edgeFaces[i][1]], .25);
                }
            }
            diagonals = centers;
            point = refined;
            neighbors = refinedNeighbors;
            centers = new double[count][width];
            for (int f = 0; f < count; f++) {
                addWeights(centers[f], point, .25);
                addWeights(centers[f], neighbors[faceEdges[f][0]], .25);
                addWeights(centers[f], neighbors[faceEdges[f][1]], .25);
                addWeights(centers[f], diagonals[f], .25);
            }
        }
        double[] weights = new double[width];
        double divisor = count * (count + 5.0);
        addWeights(weights, point, count * (double) count / divisor);
        for (double[] neighbor : neighbors) addWeights(weights, neighbor, 4 / divisor);
        for (double[] diagonal : diagonals) addWeights(weights, diagonal, 1 / divisor);
        return new Stencil(
                indices.keySet().stream().mapToInt(Integer::intValue).toArray(), weights);
    }

    private static void addWeights(double[] destination, double[] source, double weight) {
        for (int i = 0; i < destination.length; i++) destination[i] += source[i] * weight;
    }

    private record Stencil(int[] vertices, double[] weights) {}

    Set<Integer> neighborhood(Set<Integer> selected, int rings) {
        Set<Integer> support = new TreeSet<>(selected);
        for (int ring = 0; ring < rings; ring++) {
            Set<Integer> next = new TreeSet<>(support);
            for (int f : support)
                for (int v : faces[f].vertices) for (int adjacent : incident[v]) next.add(adjacent);
            support = next;
        }
        return support;
    }

    double[] influenceBounds(int face) {
        double[] bounds = {
            Double.POSITIVE_INFINITY,
            Double.POSITIVE_INFINITY,
            Double.POSITIVE_INFINITY,
            Double.NEGATIVE_INFINITY,
            Double.NEGATIVE_INFINITY,
            Double.NEGATIVE_INFINITY
        };
        for (int f : neighborhood(Set.of(face), 2))
            for (int v : faces[f].vertices)
                for (int a = 0; a < 3; a++) {
                    bounds[a] = Math.min(bounds[a], positions[v][a]);
                    bounds[a + 3] = Math.max(bounds[a + 3], positions[v][a]);
                }
        return bounds;
    }

    Map<Integer, float[]> evaluate(Set<Integer> selected, int levels) {
        State state = localControlMesh(selected);
        for (int level = 0; level < levels; level++) state = refine(state);
        double[][] limit = limit(state);
        double[][][] normals = smoothNormals(state, limit);
        Map<Integer, FloatArrayList> grouped = new HashMap<>();
        for (int face = 0; face < state.faces.size(); face++) {
            Polygon polygon = state.faces.get(face);
            if (selected.contains(polygon.origin)) {
                FloatArrayList out =
                        grouped.computeIfAbsent(polygon.origin, k -> new FloatArrayList());
                int[] v = polygon.vertices;
                for (int i = 1; i < v.length - 1; i++) {
                    for (int corner : new int[] {0, i, i + 1}) {
                        for (double p : limit[v[corner]]) out.add((float) p);
                        for (double n : normals[face][corner]) out.add((float) n);
                    }
                    out.add(faces[polygon.origin].material);
                }
            }
        }
        Map<Integer, float[]> result = new HashMap<>();
        grouped.forEach((f, data) -> result.put(f, data.toFloatArray()));
        return result;
    }

    // 在同一顶点的光滑面扇内累积面积加权法线；硬折痕和非流形边切断传播。
    // 使用当前几何级别的邻域即可产生插值法线，无需为着色恢复高密度三角形。
    private static double[][][] smoothNormals(State state, double[][] points) {
        Topology topology = new Topology(state);
        double[][] faceNormals = new double[state.faces.size()][3];
        double[][][] result = new double[state.faces.size()][][];
        for (int face = 0; face < state.faces.size(); face++) {
            int[] corners = state.faces.get(face).vertices;
            result[face] = new double[corners.length][];
            double[] normal = faceNormals[face];
            for (int i = 0; i < corners.length; i++) {
                double[] a = points[corners[i]], b = points[corners[(i + 1) % corners.length]];
                normal[0] += (a[1] - b[1]) * (a[2] + b[2]);
                normal[1] += (a[2] - b[2]) * (a[0] + b[0]);
                normal[2] += (a[0] - b[0]) * (a[1] + b[1]);
            }
        }
        for (int vertex = 0; vertex < points.length; vertex++) {
            IntArrayList incident = topology.facesAtVertex[vertex];
            boolean[] visited = new boolean[incident.size()];
            int[] fan = new int[incident.size()];
            for (int first = 0; first < incident.size(); first++) {
                if (visited[first]) continue;
                visited[first] = true;
                fan[0] = incident.getInt(first);
                int count = 1;
                double[] normal = new double[3];
                for (int next = 0; next < count; next++) {
                    int face = fan[next];
                    for (int a = 0; a < 3; a++) normal[a] += faceNormals[face][a];
                    for (Edge edge : topology.atVertex[vertex]) {
                        if (edge.sharp || !edge.faces.contains(face)) continue;
                        for (int adjacent : edge.faces) {
                            int index = incident.indexOf(adjacent);
                            if (!visited[index]) { visited[index] = true; fan[count++] = adjacent; }
                        }
                    }
                }
                double length = Math.sqrt(dot(normal, normal));
                if (length < 1e-15) {
                    System.arraycopy(faceNormals[fan[0]], 0, normal, 0, 3);
                    length = Math.sqrt(dot(normal, normal));
                }
                if (length > 1e-15) for (int a = 0; a < 3; a++) normal[a] /= length;
                else normal[1] = 1;
                for (int i = 0; i < count; i++) {
                    int face = fan[i];
                    int[] corners = state.faces.get(face).vertices;
                    for (int c = 0; c < corners.length; c++)
                        if (corners[c] == vertex) result[face][c] = normal;
                }
            }
        }
        return result;
    }

    // 支撑拓扑只负责局部重映射；refine/limit 使用相同的顶点和折痕顺序。
    private State localControlMesh(Set<Integer> selected) {
        Set<Integer> support;
        if (preparedSupport == null) support = neighborhood(selected, 2);
        else {
            support = new TreeSet<>();
            for (int face : selected) for (int f : preparedSupport[face]) support.add(f);
        }
        TreeSet<Integer> used = new TreeSet<>();
        if (preparedVertices == null)
            for (int f : support) for (int v : faces[f].vertices) used.add(v);
        else for (int f : selected) for (int v : preparedVertices[f]) used.add(v);
        // 映射限定为本批次支撑顶点，数组容量按局部部件分配。
        Int2IntOpenHashMap local = new Int2IntOpenHashMap(used.size());
        local.defaultReturnValue(-1);
        double[][] points = new double[used.size()][3];
        int next = 0;
        for (int v : used) {
            local.put(v, next);
            for (int a = 0; a < 3; a++) points[next][a] = positions[v][a];
            next++;
        }
        List<Polygon> polygons = new ArrayList<>();
        for (int f : support)
            polygons.add(
                    new Polygon(Arrays.stream(faces[f].vertices).map(local::get).toArray(), f));
        LongOpenHashSet sharp = new LongOpenHashSet();
        if (preparedSharp != null) {
            for (int f : selected)
                for (long e : preparedSharp[f])
                    sharp.add(edge(local.get((int) (e >>> 32)), local.get((int) e)));
        } else
            for (int f : support) {
                int[] corners = faces[f].vertices;
                for (int i = 0; i < corners.length; i++) {
                    int a = corners[i], b = corners[(i + 1) % corners.length];
                    if (creases.contains(edge(a, b))) sharp.add(edge(local.get(a), local.get(b)));
                }
            }
        return new State(points, polygons, sharp);
    }

    // 开口边界始终为 sharp；占用封口使用与可见曲面相同的三级细分和 limit 边界。
    float[][] boundaryCurve(int[] loop, int levels) {
        double[][] points = new double[loop.length][3];
        boolean[] corners = new boolean[loop.length];
        for (int i = 0; i < loop.length; i++) {
            for (int axis = 0; axis < 3; axis++) points[i][axis] = positions[loop[i]][axis];
            int previous = loop[(i + loop.length - 1) % loop.length],
                    next = loop[(i + 1) % loop.length];
            for (long crease : creases) {
                int a = (int) (crease >>> 32), b = (int) crease;
                if (a == loop[i] && b != previous && b != next
                        || b == loop[i] && a != previous && a != next) corners[i] = true;
            }
        }
        for (int level = 0; level < levels; level++) {
            double[][] refined = new double[points.length * 2][3];
            boolean[] sharp = new boolean[refined.length];
            for (int i = 0; i < points.length; i++) {
                double[] previous = points[(i + points.length - 1) % points.length],
                        next = points[(i + 1) % points.length];
                for (int axis = 0; axis < 3; axis++) {
                    refined[i * 2][axis] =
                            corners[i]
                                    ? points[i][axis]
                                    : (6 * points[i][axis] + previous[axis] + next[axis]) / 8;
                    refined[i * 2 + 1][axis] = (points[i][axis] + next[axis]) * .5;
                }
                sharp[i * 2] = corners[i];
            }
            points = refined;
            corners = sharp;
        }
        float[][] result = new float[points.length][3];
        for (int i = 0; i < points.length; i++)
            for (int axis = 0; axis < 3; axis++)
                result[i][axis] =
                        (float)
                                (corners[i]
                                        ? points[i][axis]
                                        : (4 * points[i][axis]
                                                        + points[
                                                                (i + points.length - 1)
                                                                        % points.length][
                                                                axis]
                                                        + points[(i + 1) % points.length][axis])
                                                / 6);
        return result;
    }

    private static State refine(State state) {
        Topology topology = new Topology(state);
        int vertexCount = state.points.length, edgeCount = topology.edges.size();
        double[][] points = new double[vertexCount + edgeCount + state.faces.size()][3];
        for (int v = 0; v < vertexCount; v++) points[v] = vertexPoint(state, topology, v, false);
        for (Edge e : topology.edges.values()) {
            double[] p = points[vertexCount + e.index];
            for (int a = 0; a < 3; a++)
                p[a] =
                        e.sharp
                                ? (state.points[e.a][a] + state.points[e.b][a]) * .5
                                : (state.points[e.a][a]
                                                + state.points[e.b][a]
                                                + topology.centers[e.faces.getInt(0)][a]
                                                + topology.centers[e.faces.getInt(1)][a])
                                        * .25;
        }
        for (int f = 0; f < state.faces.size(); f++)
            points[vertexCount + edgeCount + f] = topology.centers[f];
        List<Polygon> faces = new ArrayList<>();
        for (int f = 0; f < state.faces.size(); f++) {
            Polygon polygon = state.faces.get(f);
            int[] v = polygon.vertices;
            for (int i = 0; i < v.length; i++)
                faces.add(
                        new Polygon(
                                new int[] {
                                    v[i],
                                    vertexCount
                                            + topology.edges.get(edge(v[i], v[(i + 1) % v.length]))
                                                    .index,
                                    vertexCount + edgeCount + f,
                                    vertexCount
                                            + topology.edges.get(
                                                            edge(
                                                                    v[
                                                                            (i + v.length - 1)
                                                                                    % v.length],
                                                                    v[i]))
                                                    .index
                                },
                                polygon.origin));
        }
        LongOpenHashSet sharp = new LongOpenHashSet();
        for (Edge e : topology.edges.values())
            if (e.sharp) {
                sharp.add(edge(e.a, vertexCount + e.index));
                sharp.add(edge(e.b, vertexCount + e.index));
            }
        return new State(points, faces, sharp);
    }

    private static double[][] limit(State state) {
        Topology topology = new Topology(state);
        double[][] points = new double[state.points.length][];
        for (int v = 0; v < points.length; v++) points[v] = vertexPoint(state, topology, v, true);
        return points;
    }

    private static double[] vertexPoint(State state, Topology topology, int v, boolean limit) {
        double[] p = state.points[v], result = new double[3];
        List<Edge> edges = topology.atVertex[v];
        List<Edge> sharp = edges.stream().filter(e -> e.sharp).toList();
        if (sharp.size() > 2 || edges.isEmpty()) return p.clone();
        if (sharp.size() == 2) {
            double[] a = state.points[sharp.get(0).other(v)],
                    b = state.points[sharp.get(1).other(v)];
            for (int k = 0; k < 3; k++)
                result[k] = limit ? (4 * p[k] + a[k] + b[k]) / 6 : (6 * p[k] + a[k] + b[k]) / 8;
            return result;
        }
        int n = topology.facesAtVertex[v].size();
        if (n == 0) return p.clone();
        if (limit) {
            for (int k = 0; k < 3; k++) result[k] = n * n * p[k];
            for (Edge e : edges)
                for (int k = 0; k < 3; k++) result[k] += 4 * state.points[e.other(v)][k];
            for (int f : topology.facesAtVertex[v]) {
                int[] corners = state.faces.get(f).vertices;
                for (int i = 0; i < 4; i++)
                    if (corners[i] == v)
                        for (int k = 0; k < 3; k++)
                            result[k] += state.points[corners[(i + 2) % 4]][k];
            }
            for (int k = 0; k < 3; k++) result[k] /= n * (n + 5.0);
        } else {
            for (int f : topology.facesAtVertex[v])
                for (int k = 0; k < 3; k++) result[k] += topology.centers[f][k] / n;
            for (Edge e : edges)
                for (int k = 0; k < 3; k++)
                    result[k] += (p[k] + state.points[e.other(v)][k]) / edges.size();
            for (int k = 0; k < 3; k++) result[k] = (result[k] + (n - 3) * p[k]) / n;
        }
        return result;
    }

    private static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    private static long edge(int a, int b) {
        return ((long) Math.min(a, b) << 32) | (Math.max(a, b) & 0xffffffffL);
    }

    private record Polygon(int[] vertices, int origin) {}

    private record State(double[][] points, List<Polygon> faces, LongOpenHashSet sharp) {}

    private static final class Edge {
        final int a, b, index;
        final IntArrayList faces = new IntArrayList();
        boolean sharp;

        Edge(int a, int b, int index) {
            this.a = a;
            this.b = b;
            this.index = index;
        }

        int other(int v) {
            return a == v ? b : a;
        }
    }

    private static final class Topology {
        final LinkedHashMap<Long, Edge> edges = new LinkedHashMap<>();
        final double[][] centers;
        final List<Edge>[] atVertex;
        final IntArrayList[] facesAtVertex;

        @SuppressWarnings("unchecked")
        Topology(State state) {
            atVertex = new List[state.points.length];
            facesAtVertex = new IntArrayList[state.points.length];
            for (int v = 0; v < atVertex.length; v++) {
                atVertex[v] = new ArrayList<>();
                facesAtVertex[v] = new IntArrayList();
            }
            centers = new double[state.faces.size()][3];
            for (int f = 0; f < state.faces.size(); f++) {
                int[] corners = state.faces.get(f).vertices;
                for (int i = 0; i < corners.length; i++) {
                    int a = corners[i], b = corners[(i + 1) % corners.length];
                    long key = edge(a, b);
                    Edge e = edges.get(key);
                    if (e == null) {
                        e = new Edge(a, b, edges.size());
                        edges.put(key, e);
                        atVertex[a].add(e);
                        atVertex[b].add(e);
                    }
                    e.faces.add(f);
                    facesAtVertex[a].add(f);
                    for (int k = 0; k < 3; k++)
                        centers[f][k] += state.points[a][k] / corners.length;
                }
            }
            for (var entry : edges.entrySet())
                entry.getValue().sharp =
                        entry.getValue().faces.size() != 2 || state.sharp.contains(entry.getKey());
        }
    }
}
