package cn.jehorstudio.minetale.lib;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import cn.jehorstudio.minetale.MineTale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import cn.jehorstudio.minetale.battle.presentation.screen.render.rendertypes.BattleEntity;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ResourceManager;
import org.joml.Vector2f;
import org.joml.Vector3f;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

public final class ObjLoader implements PreparableReloadListener {

    // 缓存拥有按 ObjModels 展开的只读渲染网格。
    private static final Object CACHE_LOCK = new Object();
    private static volatile Map<ObjModels, ObjMesh> cache = Map.of();
    private static volatile long revision;

    public static final ObjLoader INSTANCE = new ObjLoader();

    private ObjLoader() {}

    // 缓存缺失时在调用线程同步加载。
    public static ObjMesh getOrLoad(ObjModels model) {
        ObjMesh cached = cache.get(model);
        if (cached != null) {
            return cached;
        }

        synchronized (CACHE_LOCK) {
            cached = cache.get(model);
            if (cached != null) {
                return cached;
            }

            ResourceManager resourceManager = Minecraft.getInstance().getResourceManager();
            ObjMesh loaded = loadObj(resourceManager, model);
            EnumMap<ObjModels, ObjMesh> updated = new EnumMap<>(ObjModels.class);
            updated.putAll(cache);
            updated.put(model, loaded);
            cache = Map.copyOf(updated);
            return loaded;
        }
    }

    // 只观察缓存
    public static ObjMesh get(ObjModels model) {
        return cache.get(model);
    }

    // 资源重载边界先清空旧 mesh，再由调用方决定是否预加载。
    public static void clearCache() {
        replaceCache(Map.of());
    }

    // 在资源重载完成后同步填充全部模型缓存。
    public static void preloadAll(ResourceManager resourceManager) {
        replaceCache(loadAll(resourceManager));
    }

    public static long revision() {
        return revision;
    }

    @Override
    public CompletableFuture<Void> reload(
            SharedState sharedState,
            Executor prepareExecutor,
            PreparationBarrier barrier,
            Executor applyExecutor
    ) {
        return CompletableFuture
                .supplyAsync(() -> loadAll(sharedState.resourceManager()), prepareExecutor)
                .thenCompose(barrier::wait)
                .thenAcceptAsync(ObjLoader::replaceCache, applyExecutor);
    }

    private static Map<ObjModels, ObjMesh> loadAll(ResourceManager resourceManager) {
        EnumMap<ObjModels, ObjMesh> loaded = new EnumMap<>(ObjModels.class);
        for (ObjModels model : ObjModels.values()) {
            loaded.put(model, loadObj(resourceManager, model));
        }
        return loaded;
    }

    private static void replaceCache(Map<ObjModels, ObjMesh> loaded) {
        synchronized (CACHE_LOCK) {
            cache = Map.copyOf(loaded);
            revision++;
        }
    }

    // OBJ 只接受四边形面；其他面跳过，结果展开为顺序提交的扁平顶点流。
    private static ObjMesh loadObj(ResourceManager resourceManager, ObjModels model) {
        ResourceLocation id = model.getModelPath();

        // 索引源数据只在解析阶段存续。
        List<Vector3f> rawPositions = new ArrayList<>();
        List<Vector2f> rawUVs = new ArrayList<>();
        List<Vector3f> rawNormals = new ArrayList<>();

        // 输出数组按最终 quad 顶点顺序展开。
        FloatBuilder positions = new FloatBuilder(1024);
        FloatBuilder uvs = new FloatBuilder(1024);
        FloatBuilder normals = new FloatBuilder(1024);

        int quadCount = 0;
        float minimumX = Float.POSITIVE_INFINITY;
        float minimumY = Float.POSITIVE_INFINITY;
        float minimumZ = Float.POSITIVE_INFINITY;
        float maximumX = Float.NEGATIVE_INFINITY;
        float maximumY = Float.NEGATIVE_INFINITY;
        float maximumZ = Float.NEGATIVE_INFINITY;

        try (BufferedReader reader = resourceManager.openAsReader(id)) {
            String line;

            while ((line = reader.readLine()) != null) {
                line = line.trim();

                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }

                if (line.startsWith("v ")) {
                    String[] temp = line.split("\\s+");
                    float x = Float.parseFloat(temp[1]);
                    float y = Float.parseFloat(temp[2]);
                    float z = Float.parseFloat(temp[3]);
                    if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)) {
                        throw new IllegalArgumentException("OBJ position 必须是有限数值：" + line);
                    }
                    rawPositions.add(new Vector3f(x, y, z));
                }
                else if (line.startsWith("vt ")) {
                    String[] temp = line.split("\\s+");
                    rawUVs.add(new Vector2f(
                            Float.parseFloat(temp[1]),
                            Float.parseFloat(temp[2])
                    ));
                }
                else if (line.startsWith("vn ")) {
                    String[] temp = line.split("\\s+");
                    rawNormals.add(new Vector3f(
                            Float.parseFloat(temp[1]),
                            Float.parseFloat(temp[2]),
                            Float.parseFloat(temp[3])
                    ));
                }
                else if (line.startsWith("f ")) {
                    String[] temp = line.split("\\s+");

                    // f 必须恰含四个顶点引用。
                    if (temp.length != 5) {
                        MineTale.LOGGER.warn("ObjLoader 当前只支持四边形面，已跳过：{}", line);
                        continue;
                    }

                    for (int i = 1; i <= 4; i++) {
                        String[] parts = temp[i].split("/", -1);

                        int posIndex = parseObjIndex(parts, 0, rawPositions.size());
                        int uvIndex = parseObjIndex(parts, 1, rawUVs.size());
                        int normalIndex = parseObjIndex(parts, 2, rawNormals.size());

                        // position 是面顶点的唯一必需属性。
                        Vector3f pos = rawPositions.get(posIndex);
                        positions.add(pos.x);
                        positions.add(pos.y);
                        positions.add(pos.z);
                        minimumX = Math.min(minimumX, pos.x);
                        minimumY = Math.min(minimumY, pos.y);
                        minimumZ = Math.min(minimumZ, pos.z);
                        maximumX = Math.max(maximumX, pos.x);
                        maximumY = Math.max(maximumY, pos.y);
                        maximumZ = Math.max(maximumZ, pos.z);

                        // 缺失 UV 使用默认值。
                        if (uvIndex >= 0 && uvIndex < rawUVs.size()) {
                            Vector2f uv = rawUVs.get(uvIndex);
                            uvs.add(uv.x);
                            uvs.add(uv.y);
                        } else {
                            uvs.add(0.0f);
                            uvs.add(0.0f);
                        }

                        // 缺失法线使用默认值。
                        if (normalIndex >= 0 && normalIndex < rawNormals.size()) {
                            Vector3f normal = rawNormals.get(normalIndex);
                            normals.add(normal.x);
                            normals.add(normal.y);
                            normals.add(normal.z);
                        } else {
                            normals.add(0.0f);
                            normals.add(1.0f);
                            normals.add(0.0f);
                        }
                    }

                    quadCount++;
                }
            }
        } catch (IOException | RuntimeException e) {
            MineTale.LOGGER.error("load obj failed: {}", id, e);
            return ObjMesh.empty();
        }

        MineTale.LOGGER.info("OBJ loaded: {}, quadCount={}", id, quadCount);

        LocalBounds localBounds = quadCount == 0
                ? LocalBounds.empty()
                : new LocalBounds(
                        minimumX, minimumY, minimumZ,
                        maximumX, maximumY, maximumZ
                );
        return new ObjMesh(
                positions.toArray(),
                uvs.toArray(),
                normals.toArray(),
                quadCount,
                localBounds
        );
    }

    // OBJ 正索引从 1 开始，负索引从数组尾部回溯；缺失属性返回 -1。
    private static int parseObjIndex(String[] parts, int partIndex, int listSize) {
        if (partIndex >= parts.length || parts[partIndex].isEmpty()) {
            return -1;
        }

        int objIndex = Integer.parseInt(parts[partIndex]);

        if (objIndex > 0) {
            return objIndex - 1;
        } else if (objIndex < 0) {
            return listSize + objIndex;
        } else {
            throw new IllegalArgumentException("OBJ index cannot be 0");
        }
    }

    // 向外部 VertexConsumer 按 quad 顺序提交；贴图 consumer 仍需要 UV。
    public static void render(
            PoseStack poseStack,
            VertexConsumer consumer,
            ObjModels model,
            int packedLight,
            float red,
            float green,
            float blue,
            float alpha
    ) {
        ObjMesh mesh = getOrLoad(model);
        if (mesh.getQuadCount() == 0) {
            return;
        }

        PoseStack.Pose pose = poseStack.last();

        float[] positions = mesh.getPositions();
        float[] uvs = mesh.getUvs();
        float[] normals = mesh.getNormals();

        // Mesh 布局固定为每四个连续顶点一个 quad。
        for (int q = 0, p = 0, uv = 0, n = 0;
             q < mesh.getQuadCount();
             q++, p += 12, uv += 8, n += 12) {

            putVertex(consumer, pose,
                    positions[p], positions[p + 1], positions[p + 2],
                    uvs[uv], 1.0f - uvs[uv + 1],
                    normals[n], normals[n + 1], normals[n + 2],
                    packedLight,
                    red, green, blue, alpha);

            putVertex(consumer, pose,
                    positions[p + 3], positions[p + 4], positions[p + 5],
                    uvs[uv + 2], 1.0f - uvs[uv + 3],
                    normals[n + 3], normals[n + 4], normals[n + 5],
                    packedLight,
                    red, green, blue, alpha);

            putVertex(consumer, pose,
                    positions[p + 6], positions[p + 7], positions[p + 8],
                    uvs[uv + 4], 1.0f - uvs[uv + 5],
                    normals[n + 6], normals[n + 7], normals[n + 8],
                    packedLight,
                    red, green, blue, alpha);

            putVertex(consumer, pose,
                    positions[p + 9], positions[p + 10], positions[p + 11],
                    uvs[uv + 6], 1.0f - uvs[uv + 7],
                    normals[n + 9], normals[n + 10], normals[n + 11],
                    packedLight,
                    red, green, blue, alpha);
        }
    }

    // packedLight 由调用方选择 FULL_BRIGHT 或真实场景光照。
    public static void renderTexturedModel(
            PoseStack poseStack,
            MultiBufferSource bufferSource,
            ObjModels model,
            int packedLight,
            float red,
            float green,
            float blue,
            float alpha
    ) {
        VertexConsumer consumer = bufferSource.getBuffer(
                BattleEntity.renderType(model.getTexturePath())
        );

        render(
                poseStack,
                consumer,
                model,
                packedLight,
                red, green, blue, alpha
        );
    }

    private static void putVertex(
            VertexConsumer consumer,
            PoseStack.Pose pose,
            float x, float y, float z,
            float u, float v,
            float nx, float ny, float nz,
            int packedLight,
            float red, float green, float blue, float alpha
    ) {
        consumer.addVertex(pose, x, y, z)
                .setColor(red, green, blue, alpha)
                .setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(packedLight)
                .setNormal(pose, nx, ny, nz);
    }

    // 展开布局：position 3、UV 2、normal 3 个 float；quadCount 记录面数。
    public static final class ObjMesh {
        private final float[] positions;
        private final float[] uvs;
        private final float[] normals;
        private final int quadCount;
        private final LocalBounds localBounds;

        private ObjMesh(
                float[] positions,
                float[] uvs,
                float[] normals,
                int quadCount,
                LocalBounds localBounds
        ) {
            this.positions = positions;
            this.uvs = uvs;
            this.normals = normals;
            this.quadCount = quadCount;
            this.localBounds = localBounds;
        }

        public static ObjMesh empty() {
            return new ObjMesh(
                    new float[0], new float[0], new float[0], 0,
                    LocalBounds.empty()
            );
        }

        public float[] getPositions() {
            return positions;
        }

        public float[] getUvs() {
            return uvs;
        }

        public float[] getNormals() {
            return normals;
        }

        public int getQuadCount() {
            return quadCount;
        }

        public int getVertexCount() {
            return quadCount * 4;
        }

        public LocalBounds getLocalBounds() {
            return this.localBounds;
        }
    }

    public record LocalBounds(
            float minimumX,
            float minimumY,
            float minimumZ,
            float maximumX,
            float maximumY,
            float maximumZ
    ) {
        private static LocalBounds empty() {
            return new LocalBounds(0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F);
        }

        public float centerX() {
            return (this.minimumX + this.maximumX) * 0.5F;
        }

        public float centerY() {
            return (this.minimumY + this.maximumY) * 0.5F;
        }

        public float centerZ() {
            return (this.minimumZ + this.maximumZ) * 0.5F;
        }
    }

    // 解析期专用的原始 float 动态数组，避免逐元素装箱。
    private static final class FloatBuilder {
        private float[] data;
        private int size;

        private FloatBuilder(int initialCapacity) {
            this.data = new float[Math.max(8, initialCapacity)];
            this.size = 0;
        }

        public void add(float value) {
            ensureCapacity(size + 1);
            data[size++] = value;
        }

        public float[] toArray() {
            float[] result = new float[size];
            System.arraycopy(data, 0, result, 0, size);
            return result;
        }

        private void ensureCapacity(int wanted) {
            if (wanted <= data.length) {
                return;
            }

            int newCapacity = data.length << 1;
            while (newCapacity < wanted) {
                newCapacity <<= 1;
            }

            float[] newData = new float[newCapacity];
            System.arraycopy(data, 0, newData, 0, size);
            data = newData;
        }
    }
}
