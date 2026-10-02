package cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.entity.monsternpc.client.gpu;

import cn.jehorstudio.minetale.content.entity.monster_npc.MonsterNpcAppearance;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.texture.DynamicTexture;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import software.bernie.geckolib.animatable.processing.AnimationState;
import software.bernie.geckolib.animation.Animation;
import software.bernie.geckolib.animation.keyframe.AnimationPoint;
import software.bernie.geckolib.animation.keyframe.BoneAnimation;
import software.bernie.geckolib.animation.keyframe.Keyframe;
import software.bernie.geckolib.animation.keyframe.KeyframeStack;
import software.bernie.geckolib.animation.state.BoneSnapshot;
import software.bernie.geckolib.cache.GeckoLibResources;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.cache.object.GeoCube;
import software.bernie.geckolib.cache.object.GeoQuad;
import software.bernie.geckolib.cache.object.GeoVertex;
import software.bernie.geckolib.loading.math.MathValue;
import software.bernie.geckolib.loading.object.BakedAnimations;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

// 将 GeckoLib 烘焙结果转换为共享 mesh 与顶点动画纹理
final class SnowtownGpuCrowdAppearanceMesh implements AutoCloseable {
    private static final int ANIMATION_FRAME_RATE = 30;
    private static final float POSITION_RANGE = 8.0F;
    private static final float HEAD_ANIMATION_WEIGHT = 0.20F;
    private static final float NECK_ANIMATION_WEIGHT = 0.15F;
    private static final float NECK_LOOK_SHARE = 0.35F;
    private static final float MAX_LOOK_YAW = (float) Math.toRadians(42.0D);
    private static final float FOOTPRINT_MARGIN = 0.05F;

    private final MonsterNpcAppearance appearance;
    private final GpuBuffer vertices;
    private final GpuBuffer appearanceUniform;
    private final DynamicTexture animationTexture;
    private final DynamicTexture geometryTexture;
    private final int vertexCount;
    private final int indexCount;
    private final int idleFrames;
    private final int walkFrames;
    private final float idleSeconds;
    private final float walkSeconds;
    private final float halfWidth;
    private final float halfLength;

    private SnowtownGpuCrowdAppearanceMesh(
            MonsterNpcAppearance appearance,
            GpuBuffer vertices,
            GpuBuffer appearanceUniform,
            DynamicTexture animationTexture,
            DynamicTexture geometryTexture,
            int vertexCount,
            int indexCount,
            int idleFrames,
            int walkFrames,
            float idleSeconds,
            float walkSeconds,
            float halfWidth,
            float halfLength
    ) {
        this.appearance = appearance;
        this.vertices = vertices;
        this.appearanceUniform = appearanceUniform;
        this.animationTexture = animationTexture;
        this.geometryTexture = geometryTexture;
        this.vertexCount = vertexCount;
        this.indexCount = indexCount;
        this.idleFrames = idleFrames;
        this.walkFrames = walkFrames;
        this.idleSeconds = idleSeconds;
        this.walkSeconds = walkSeconds;
        this.halfWidth = halfWidth;
        this.halfLength = halfLength;
    }

    static List<SnowtownGpuCrowdAppearanceMesh> createAll() {
        List<SnowtownGpuCrowdAppearanceMesh> meshes = new ArrayList<>(MonsterNpcAppearance.count());
        try {
            for (MonsterNpcAppearance appearance : MonsterNpcAppearance.values()) {
                meshes.add(create(appearance));
            }
            return List.copyOf(meshes);
        } catch (RuntimeException | LinkageError failure) {
            meshes.forEach(SnowtownGpuCrowdAppearanceMesh::close);
            throw failure;
        }
    }

    // 只计算模拟碰撞所需的动画占地
    static List<Footprint> measureFootprints() {
        List<Footprint> footprints = new ArrayList<>(MonsterNpcAppearance.count());
        for (MonsterNpcAppearance appearance : MonsterNpcAppearance.values()) {
            footprints.add(measureFootprint(appearance));
        }
        return List.copyOf(footprints);
    }

    private static Footprint measureFootprint(MonsterNpcAppearance appearance) {
        BakedGeoModel model = Objects.requireNonNull(
                GeckoLibResources.getBakedModels().get(appearance.modelResource()),
                () -> "GeckoLib 模型未加载：" + appearance.modelResource());
        BakedAnimations animations = Objects.requireNonNull(
                GeckoLibResources.getBakedAnimations().get(appearance.animationResource()),
                () -> "GeckoLib 动画未加载：" + appearance.animationResource());
        Animation idle = Objects.requireNonNull(
                animations.getAnimation(appearance.animationName(false)),
                () -> "缺少 idle 动画：" + appearance.animationName(false));
        Animation walk = Objects.requireNonNull(
                animations.getAnimation(appearance.animationName(true)),
                () -> "缺少 walk 动画：" + appearance.animationName(true));
        requireConstantAnimation(appearance, idle);
        requireConstantAnimation(appearance, walk);

        List<BoneEntry> bones = new ArrayList<>();
        List<AnimatedVertex> vertices = new ArrayList<>();
        for (GeoBone root : model.topLevelBones()) {
            collectBone(root, -1, false, bones, vertices);
        }
        FootprintAccumulator footprint = new FootprintAccumulator();
        accumulateFootprint(idle, frameCount(idle), bones, vertices, footprint);
        accumulateFootprint(walk, frameCount(walk), bones, vertices, footprint);
        return new Footprint(
                appearance,
                footprint.halfWidth() + FOOTPRINT_MARGIN,
                footprint.halfLength() + FOOTPRINT_MARGIN
        );
    }

    private static void accumulateFootprint(
            Animation animation,
            int frameCount,
            List<BoneEntry> bones,
            List<AnimatedVertex> vertices,
            FootprintAccumulator footprint
    ) {
        Map<String, BoneAnimation> animatedBones = new HashMap<>();
        for (BoneAnimation boneAnimation : animation.boneAnimations()) {
            animatedBones.put(boneAnimation.boneName(), boneAnimation);
        }
        Matrix4f[] matrices = new Matrix4f[bones.size()];
        for (int frame = 0; frame < frameCount; frame++) {
            double tick = animation.length() * frame / frameCount;
            for (int boneIndex = 0; boneIndex < bones.size(); boneIndex++) {
                BoneEntry entry = bones.get(boneIndex);
                Pose pose = samplePose(entry, animatedBones.get(entry.bone().getName()), tick);
                matrices[boneIndex] = composeBoneMatrix(entry, pose, matrices);
            }
            for (AnimatedVertex vertex : vertices) {
                footprint.include(matrices[vertex.boneIndex()]
                        .transformPosition(new Vector3f(vertex.position())));
            }
        }
    }

    private static SnowtownGpuCrowdAppearanceMesh create(MonsterNpcAppearance appearance) {
        BakedGeoModel model = Objects.requireNonNull(
                GeckoLibResources.getBakedModels().get(appearance.modelResource()),
                () -> "GeckoLib 模型未加载：" + appearance.modelResource());
        BakedAnimations animations = Objects.requireNonNull(
                GeckoLibResources.getBakedAnimations().get(appearance.animationResource()),
                () -> "GeckoLib 动画未加载：" + appearance.animationResource());
        Animation idle = Objects.requireNonNull(
                animations.getAnimation(appearance.animationName(false)),
                () -> "缺少 idle 动画：" + appearance.animationName(false));
        Animation walk = Objects.requireNonNull(
                animations.getAnimation(appearance.animationName(true)),
                () -> "缺少 walk 动画：" + appearance.animationName(true));
        requireConstantAnimation(appearance, idle);
        requireConstantAnimation(appearance, walk);

        List<BoneEntry> bones = new ArrayList<>();
        List<AnimatedVertex> animatedVertices = new ArrayList<>();
        for (GeoBone root : model.topLevelBones()) {
            collectBone(root, -1, false, bones, animatedVertices);
        }
        if (animatedVertices.isEmpty()) {
            throw new IllegalStateException("GeckoLib 模型没有可绘制顶点：" + appearance.modelResource());
        }

        GpuBuffer vertices = null;
        GpuBuffer appearanceUniform = null;
        AnimationLut animationLut = null;
        DynamicTexture geometryTexture = null;
        try {
            MeshBuffer mesh = createVertexBuffer(appearance, animatedVertices);
            vertices = mesh.vertices();
            geometryTexture = createGeometryTexture(appearance, animatedVertices);
            int idleFrames = frameCount(idle);
            int walkFrames = frameCount(walk);
            animationLut = createAnimationTexture(
                    appearance,
                    bones,
                    animatedVertices,
                    idle,
                    idleFrames,
                    walk,
                    walkFrames);
            appearanceUniform = createAppearanceUniform(
                    appearance,
                    idleFrames,
                    walkFrames,
                    idle,
                    walk);
            return new SnowtownGpuCrowdAppearanceMesh(
                    appearance,
                    vertices,
                    appearanceUniform,
                    animationLut.texture(),
                    geometryTexture,
                    animatedVertices.size(),
                    mesh.indexCount(),
                    idleFrames,
                    walkFrames,
                    (float) (idle.length() / 20.0D),
                    (float) (walk.length() / 20.0D),
                    animationLut.halfWidth(),
                    animationLut.halfLength());
        } catch (RuntimeException | LinkageError failure) {
            if (vertices != null) {
                vertices.close();
            }
            if (appearanceUniform != null) {
                appearanceUniform.close();
            }
            if (animationLut != null) {
                animationLut.texture().close();
            }
            if (geometryTexture != null) {
                geometryTexture.close();
            }
            throw failure;
        }
    }

    MonsterNpcAppearance appearance() {
        return this.appearance;
    }

    GpuBuffer vertices() {
        return this.vertices;
    }

    GpuBuffer appearanceUniform() {
        return this.appearanceUniform;
    }

    DynamicTexture animationTexture() {
        return this.animationTexture;
    }

    DynamicTexture geometryTexture() {
        return this.geometryTexture;
    }

    int vertexCount() {
        return this.vertexCount;
    }

    int indexCount() {
        return this.indexCount;
    }

    int idleFrames() {
        return this.idleFrames;
    }

    int walkFrames() {
        return this.walkFrames;
    }

    float positionRange() {
        return POSITION_RANGE;
    }

    float idleSeconds() {
        return this.idleSeconds;
    }

    float walkSeconds() {
        return this.walkSeconds;
    }

    float halfWidth() {
        return this.halfWidth;
    }

    float halfLength() {
        return this.halfLength;
    }

    int instanceCount(int agentCount) {
        int firstAgent = this.appearance.id();
        if (agentCount <= firstAgent) {
            return 0;
        }
        return (agentCount - 1 - firstAgent) / MonsterNpcAppearance.count() + 1;
    }

    @Override
    public void close() {
        this.vertices.close();
        this.appearanceUniform.close();
        this.animationTexture.close();
        this.geometryTexture.close();
    }

    private static void requireConstantAnimation(MonsterNpcAppearance appearance, Animation animation) {
        if (!animation.usedVariables().isEmpty()) {
            throw new IllegalStateException(
                    "GPU 人群动画暂不支持 Molang 变量：" + appearance.name() + "/" + animation.name());
        }
    }

    private static void collectBone(
            GeoBone bone,
            int parentIndex,
            boolean parentLookAffected,
            List<BoneEntry> bones,
            List<AnimatedVertex> vertices
    ) {
        int boneIndex = bones.size();
        bones.add(new BoneEntry(bone, parentIndex, initialPose(bone)));
        boolean lookAffected = parentLookAffected
                || bone.getName().equalsIgnoreCase("head")
                || bone.getName().equalsIgnoreCase("neck");
        if (!bone.isHidden()) {
            for (GeoCube cube : bone.getCubes()) {
                collectCube(cube, boneIndex, lookAffected, vertices);
            }
        }
        for (GeoBone child : bone.getChildBones()) {
            collectBone(child, boneIndex, lookAffected, bones, vertices);
        }
    }

    private static void collectCube(
            GeoCube cube,
            int boneIndex,
            boolean lookAffected,
            List<AnimatedVertex> vertices
    ) {
        float pivotX = (float) cube.pivot().x / 16.0F;
        float pivotY = (float) cube.pivot().y / 16.0F;
        float pivotZ = (float) cube.pivot().z / 16.0F;
        Matrix4f cubePose = new Matrix4f()
                .translate(pivotX, pivotY, pivotZ)
                .rotateZ((float) cube.rotation().z)
                .rotateY((float) cube.rotation().y)
                .rotateX((float) cube.rotation().x)
                .translate(-pivotX, -pivotY, -pivotZ);
        for (GeoQuad quad : cube.quads()) {
            if (quad == null) {
                continue;
            }
            Vector3f normal = cubePose.transformDirection(new Vector3f(quad.normal())).normalize();
            GeoVertex[] sourceVertices = quad.vertices();
            Vector3f[] positions = new Vector3f[sourceVertices.length];
            float middleU = 0.0F;
            float middleV = 0.0F;
            for (int vertexIndex = 0; vertexIndex < sourceVertices.length; vertexIndex++) {
                GeoVertex vertex = sourceVertices[vertexIndex];
                positions[vertexIndex] = cubePose.transformPosition(
                        new Vector3f(vertex.position()));
                middleU += vertex.texU();
                middleV += vertex.texV();
            }
            middleU /= sourceVertices.length;
            middleV /= sourceVertices.length;
            Vector4f tangent = calculateTangent(sourceVertices, positions, normal);
            for (int vertexIndex = 0; vertexIndex < sourceVertices.length; vertexIndex++) {
                GeoVertex vertex = sourceVertices[vertexIndex];
                vertices.add(new AnimatedVertex(
                        positions[vertexIndex],
                        new Vector3f(normal),
                        vertex.texU(),
                        vertex.texV(),
                        middleU,
                        middleV,
                        new Vector4f(tangent),
                        boneIndex,
                        lookAffected));
            }
        }
    }

    private static Vector4f calculateTangent(
            GeoVertex[] vertices,
            Vector3f[] positions,
            Vector3f normal
    ) {
        if (vertices.length < 3) {
            return fallbackTangent(normal);
        }
        Vector3f edge1 = new Vector3f(positions[1]).sub(positions[0]);
        Vector3f edge2 = new Vector3f(positions[2]).sub(positions[0]);
        float deltaU1 = vertices[1].texU() - vertices[0].texU();
        float deltaV1 = vertices[1].texV() - vertices[0].texV();
        float deltaU2 = vertices[2].texU() - vertices[0].texU();
        float deltaV2 = vertices[2].texV() - vertices[0].texV();
        float determinant = deltaU1 * deltaV2 - deltaU2 * deltaV1;
        if (Math.abs(determinant) < 1.0E-6F) {
            return fallbackTangent(normal);
        }

        float reciprocal = 1.0F / determinant;
        Vector3f tangent = new Vector3f(edge1)
                .mul(deltaV2)
                .sub(new Vector3f(edge2).mul(deltaV1))
                .mul(reciprocal);
        tangent.sub(new Vector3f(normal).mul(tangent.dot(normal)));
        if (tangent.lengthSquared() < 1.0E-8F) {
            return fallbackTangent(normal);
        }
        tangent.normalize();
        Vector3f bitangent = new Vector3f(edge2)
                .mul(deltaU1)
                .sub(new Vector3f(edge1).mul(deltaU2))
                .mul(reciprocal)
                .normalize();
        float handedness = new Vector3f(normal).cross(tangent).dot(bitangent) < 0.0F
                ? -1.0F
                : 1.0F;
        return new Vector4f(tangent, handedness);
    }

    private static Vector4f fallbackTangent(Vector3f normal) {
        Vector3f axis = Math.abs(normal.y) < 0.9F
                ? new Vector3f(0.0F, 1.0F, 0.0F)
                : new Vector3f(1.0F, 0.0F, 0.0F);
        Vector3f tangent = axis.cross(new Vector3f(normal)).normalize();
        return new Vector4f(tangent, 1.0F);
    }

    private static Pose initialPose(GeoBone bone) {
        BoneSnapshot snapshot = bone.getInitialSnapshot();
        if (snapshot != null) {
            return new Pose(
                    snapshot.getOffsetX(), snapshot.getOffsetY(), snapshot.getOffsetZ(),
                    snapshot.getRotX(), snapshot.getRotY(), snapshot.getRotZ(),
                    snapshot.getScaleX(), snapshot.getScaleY(), snapshot.getScaleZ());
        }
        return new Pose(
                bone.getPosX(), bone.getPosY(), bone.getPosZ(),
                bone.getRotX(), bone.getRotY(), bone.getRotZ(),
                bone.getScaleX(), bone.getScaleY(), bone.getScaleZ());
    }

    private static MeshBuffer createVertexBuffer(
            MonsterNpcAppearance appearance,
            List<AnimatedVertex> animatedVertices
    ) {
        int capacity = animatedVertices.size()
                * DefaultVertexFormat.POSITION_TEX_COLOR_NORMAL.getVertexSize();
        try (ByteBufferBuilder bytes = ByteBufferBuilder.exactlySized(capacity)) {
            BufferBuilder builder = new BufferBuilder(
                    bytes,
                    VertexFormat.Mode.QUADS,
                    DefaultVertexFormat.POSITION_TEX_COLOR_NORMAL);
            for (AnimatedVertex vertex : animatedVertices) {
                // 顶点位置来自动画 LUT，Color.r 专用于头颈注视标记。
                builder.addVertex(0.0F, 0.0F, 0.0F)
                        .setUv(vertex.u(), vertex.v())
                        .setColor(vertex.lookAffected() ? 255 : 0, 255, 255, 255)
                        .setNormal(vertex.normal().x, vertex.normal().y, vertex.normal().z);
            }
            try (MeshData mesh = builder.buildOrThrow()) {
                GpuBuffer vertices = RenderSystem.getDevice().createBuffer(
                        () -> "Snowtown crowd " + appearance.name() + " mesh",
                        GpuBuffer.USAGE_VERTEX,
                        mesh.vertexBuffer());
                return new MeshBuffer(vertices, mesh.drawState().indexCount());
            }
        }
    }

    // 每顶点固定占六个 RGBA8 texel；float 必须按 IEEE-754 原始位写入，供 GPU 原样还原。
    private static DynamicTexture createGeometryTexture(
            MonsterNpcAppearance appearance,
            List<AnimatedVertex> vertices
    ) {
        NativeImage image = new NativeImage(vertices.size() * 6, 1, false);
        DynamicTexture texture = null;
        try {
            for (int vertexIndex = 0; vertexIndex < vertices.size(); vertexIndex++) {
                AnimatedVertex vertex = vertices.get(vertexIndex);
                int column = vertexIndex * 6;
                image.setPixelABGR(column, 0, Float.floatToRawIntBits(vertex.u()));
                image.setPixelABGR(column + 1, 0, Float.floatToRawIntBits(vertex.v()));
                image.setPixelABGR(
                        column + 2,
                        0,
                        packBytes(
                                encodeSignedNormal(vertex.normal().x),
                                encodeSignedNormal(vertex.normal().y),
                                encodeSignedNormal(vertex.normal().z),
                                vertex.lookAffected() ? 255 : 0));
                image.setPixelABGR(
                        column + 3,
                        0,
                        Float.floatToRawIntBits(vertex.middleU()));
                image.setPixelABGR(
                        column + 4,
                        0,
                        Float.floatToRawIntBits(vertex.middleV()));
                image.setPixelABGR(
                        column + 5,
                        0,
                        packBytes(
                                encodeSignedNormal(vertex.tangent().x),
                                encodeSignedNormal(vertex.tangent().y),
                                encodeSignedNormal(vertex.tangent().z),
                                encodeSignedNormal(vertex.tangent().w)));
            }
            texture = new DynamicTexture(
                    () -> "Snowtown crowd " + appearance.name() + " static geometry",
                    image);
            texture.setClamp(true);
            texture.setFilter(false, false);
            return texture;
        } catch (RuntimeException | LinkageError failure) {
            if (texture != null) {
                texture.close();
            } else {
                image.close();
            }
            throw failure;
        }
    }

    private static int encodeSignedNormal(float value) {
        return Math.round(Math.clamp(value, -1.0F, 1.0F) * 127.0F) & 0xFF;
    }

    private static int packBytes(int red, int green, int blue, int alpha) {
        return red & 0xFF
                | (green & 0xFF) << 8
                | (blue & 0xFF) << 16
                | (alpha & 0xFF) << 24;
    }

    private static AnimationLut createAnimationTexture(
            MonsterNpcAppearance appearance,
            List<BoneEntry> bones,
            List<AnimatedVertex> vertices,
            Animation idle,
            int idleFrames,
            Animation walk,
            int walkFrames
    ) {
        NativeImage image = new NativeImage(vertices.size() * 6, idleFrames + walkFrames, false);
        DynamicTexture texture = null;
        FootprintAccumulator footprint = new FootprintAccumulator();
        try {
            boolean hasNeck = bones.stream()
                    .anyMatch(entry -> entry.bone().getName().equalsIgnoreCase("neck"));
            writeAnimationFrames(
                    appearance, image, 0, idleFrames, idle, bones, vertices, hasNeck, footprint);
            writeAnimationFrames(
                    appearance, image, idleFrames, walkFrames, walk, bones, vertices, hasNeck, footprint);
            texture = new DynamicTexture(
                    () -> "Snowtown crowd " + appearance.name() + " animation LUT",
                    image);
            texture.setClamp(true);
            texture.setFilter(false, false);
            return new AnimationLut(
                    texture,
                    footprint.halfWidth() + FOOTPRINT_MARGIN,
                    footprint.halfLength() + FOOTPRINT_MARGIN);
        } catch (RuntimeException | LinkageError failure) {
            if (texture != null) {
                texture.close();
            } else {
                image.close();
            }
            throw failure;
        }
    }

    private static void writeAnimationFrames(
            MonsterNpcAppearance appearance,
            NativeImage image,
            int rowOffset,
            int frameCount,
            Animation animation,
            List<BoneEntry> bones,
            List<AnimatedVertex> vertices,
            boolean hasNeck,
            FootprintAccumulator footprint
    ) {
        Map<String, BoneAnimation> animatedBones = new HashMap<>();
        for (BoneAnimation boneAnimation : animation.boneAnimations()) {
            animatedBones.put(boneAnimation.boneName(), boneAnimation);
        }

        Matrix4f[] baseMatrices = new Matrix4f[bones.size()];
        Matrix4f[] leftMatrices = new Matrix4f[bones.size()];
        Matrix4f[] rightMatrices = new Matrix4f[bones.size()];
        for (int frame = 0; frame < frameCount; frame++) {
            double tick = animation.length() * frame / frameCount;
            for (int boneIndex = 0; boneIndex < bones.size(); boneIndex++) {
                BoneEntry entry = bones.get(boneIndex);
                Pose pose = samplePose(entry, animatedBones.get(entry.bone().getName()), tick);
                float lookShare = lookShare(entry.bone().getName(), hasNeck);
                baseMatrices[boneIndex] = composeBoneMatrix(entry, pose, baseMatrices);
                leftMatrices[boneIndex] = composeBoneMatrix(
                        entry, pose.withAdditionalYaw(MAX_LOOK_YAW * lookShare), leftMatrices);
                rightMatrices[boneIndex] = composeBoneMatrix(
                        entry, pose.withAdditionalYaw(-MAX_LOOK_YAW * lookShare), rightMatrices);
            }
            int row = rowOffset + frame;
            for (int vertexIndex = 0; vertexIndex < vertices.size(); vertexIndex++) {
                AnimatedVertex vertex = vertices.get(vertexIndex);
                Vector3f basePosition = baseMatrices[vertex.boneIndex()]
                        .transformPosition(new Vector3f(vertex.position()));
                Vector3f leftPosition = leftMatrices[vertex.boneIndex()]
                        .transformPosition(new Vector3f(vertex.position()));
                Vector3f rightPosition = rightMatrices[vertex.boneIndex()]
                        .transformPosition(new Vector3f(vertex.position()));
                int column = vertexIndex * 6;
                writePosition(appearance, image, column, row, basePosition);
                writePosition(appearance, image, column + 2, row, leftPosition);
                writePosition(appearance, image, column + 4, row, rightPosition);
                footprint.include(basePosition);
            }
        }
    }

    private static Matrix4f composeBoneMatrix(
            BoneEntry entry,
            Pose pose,
            Matrix4f[] parentMatrices
    ) {
        Matrix4f local = boneMatrix(entry.bone(), pose);
        return entry.parentIndex() < 0
                ? local
                : new Matrix4f(parentMatrices[entry.parentIndex()]).mul(local);
    }

    private static void writePosition(
            MonsterNpcAppearance appearance,
            NativeImage image,
            int column,
            int row,
            Vector3f position
    ) {
        image.setPixelABGR(column, row, encodePositionXY(appearance, position));
        image.setPixelABGR(column + 1, row, encodePositionZ(appearance, position.z));
    }

    private static float lookShare(String boneName, boolean hasNeck) {
        if (boneName.equalsIgnoreCase("head")) {
            return hasNeck ? 1.0F - NECK_LOOK_SHARE : 1.0F;
        }
        return boneName.equalsIgnoreCase("neck") ? NECK_LOOK_SHARE : 0.0F;
    }

    private static Pose samplePose(BoneEntry entry, BoneAnimation animation, double tick) {
        Pose initial = entry.initialPose();
        if (animation == null) {
            return initial;
        }
        KeyframeStack<Keyframe<MathValue>> rotation = animation.rotationKeyFrames();
        KeyframeStack<Keyframe<MathValue>> position = animation.positionKeyFrames();
        KeyframeStack<Keyframe<MathValue>> scale = animation.scaleKeyFrames();
        Pose animated = new Pose(
                sampleAxis(position.xKeyframes(), tick, initial.posX()),
                sampleAxis(position.yKeyframes(), tick, initial.posY()),
                sampleAxis(position.zKeyframes(), tick, initial.posZ()),
                initial.rotX() + sampleAxis(rotation.xKeyframes(), tick, 0.0F),
                initial.rotY() + sampleAxis(rotation.yKeyframes(), tick, 0.0F),
                initial.rotZ() + sampleAxis(rotation.zKeyframes(), tick, 0.0F),
                sampleAxis(scale.xKeyframes(), tick, initial.scaleX()),
                sampleAxis(scale.yKeyframes(), tick, initial.scaleY()),
                sampleAxis(scale.zKeyframes(), tick, initial.scaleZ()));
        float animationWeight = animationWeight(entry.bone().getName());
        return animationWeight >= 1.0F ? animated : initial.lerp(animated, animationWeight);
    }

    private static float sampleAxis(List<Keyframe<MathValue>> frames, double tick, float fallback) {
        if (frames.isEmpty()) {
            return fallback;
        }
        double frameEnd = 0.0D;
        Keyframe<MathValue> selected = frames.getLast();
        double localTick = tick;
        for (Keyframe<MathValue> frame : frames) {
            frameEnd += frame.length();
            if (frameEnd > tick) {
                selected = frame;
                localTick = tick - (frameEnd - frame.length());
                break;
            }
        }
        double start = selected.startValue().get(null);
        double end = selected.endValue().get(null);
        AnimationPoint point = new AnimationPoint(selected, localTick, selected.length(), start, end);
        return (float) selected.easingType().apply(point, (AnimationState<?>) null);
    }

    private static float animationWeight(String boneName) {
        if (boneName.equalsIgnoreCase("head")) {
            return HEAD_ANIMATION_WEIGHT;
        }
        if (boneName.equalsIgnoreCase("neck")) {
            return NECK_ANIMATION_WEIGHT;
        }
        return 1.0F;
    }

    private static Matrix4f boneMatrix(GeoBone bone, Pose pose) {
        float pivotX = bone.getPivotX() / 16.0F;
        float pivotY = bone.getPivotY() / 16.0F;
        float pivotZ = bone.getPivotZ() / 16.0F;
        return new Matrix4f()
                .translate(-pose.posX() / 16.0F, pose.posY() / 16.0F, pose.posZ() / 16.0F)
                .translate(pivotX, pivotY, pivotZ)
                .rotateZ(pose.rotZ())
                .rotateY(pose.rotY())
                .rotateX(pose.rotX())
                .scale(pose.scaleX(), pose.scaleY(), pose.scaleZ())
                .translate(-pivotX, -pivotY, -pivotZ);
    }

    private static GpuBuffer createAppearanceUniform(
            MonsterNpcAppearance appearance,
            int idleFrames,
            int walkFrames,
            Animation idle,
            Animation walk
    ) {
        ByteBuffer data = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder());
        data.putFloat(appearance.id());
        data.putFloat(MonsterNpcAppearance.count());
        data.putFloat(idleFrames);
        data.putFloat(walkFrames);
        data.putFloat(appearance.walkingSpeedBlocksPerSecond());
        data.putFloat(POSITION_RANGE);
        data.putFloat((float) (idle.length() / 20.0D));
        data.putFloat((float) (walk.length() / 20.0D));
        data.flip();
        return RenderSystem.getDevice().createBuffer(
                () -> "Snowtown crowd " + appearance.name() + " uniforms",
                GpuBuffer.USAGE_UNIFORM,
                data);
    }

    private static int frameCount(Animation animation) {
        return Math.max(1, (int) Math.round(animation.length() / 20.0D * ANIMATION_FRAME_RATE));
    }

    private static int encodePositionXY(MonsterNpcAppearance appearance, Vector3f position) {
        int x = encodePositionComponent(appearance, position.x);
        int y = encodePositionComponent(appearance, position.y);
        return highByte(x) | lowByte(x) << 8 | highByte(y) << 16 | lowByte(y) << 24;
    }

    private static int encodePositionZ(MonsterNpcAppearance appearance, float z) {
        int encoded = encodePositionComponent(appearance, z);
        return highByte(encoded) | lowByte(encoded) << 8 | 255 << 24;
    }

    private static int encodePositionComponent(MonsterNpcAppearance appearance, float value) {
        if (value < -POSITION_RANGE || value > POSITION_RANGE) {
            throw new IllegalStateException(
                    "动画顶点超出 LUT 编码范围：" + appearance.name() + " value=" + value);
        }
        float normalized = value / (POSITION_RANGE * 2.0F) + 0.5F;
        return Math.clamp(Math.round(normalized * 65535.0F), 0, 65535);
    }

    private static int highByte(int value) {
        return value >>> 8;
    }

    private static int lowByte(int value) {
        return value & 0xFF;
    }

    private record AnimatedVertex(
            Vector3f position,
            Vector3f normal,
            float u,
            float v,
            float middleU,
            float middleV,
            Vector4f tangent,
            int boneIndex,
            boolean lookAffected
    ) {
    }

    private record BoneEntry(GeoBone bone, int parentIndex, Pose initialPose) {
    }

    private record MeshBuffer(GpuBuffer vertices, int indexCount) {
    }

    private record AnimationLut(
            DynamicTexture texture,
            float halfWidth,
            float halfLength
    ) {
    }

    record Footprint(
            MonsterNpcAppearance appearance,
            float halfWidth,
            float halfLength
    ) {
    }

    private static final class FootprintAccumulator {
        private float maximumAbsoluteX;
        private float maximumAbsoluteZ;

        void include(Vector3f position) {
            this.maximumAbsoluteX = Math.max(this.maximumAbsoluteX, Math.abs(position.x));
            this.maximumAbsoluteZ = Math.max(this.maximumAbsoluteZ, Math.abs(position.z));
        }

        float halfWidth() {
            return Math.max(this.maximumAbsoluteX, 0.12F);
        }

        float halfLength() {
            return Math.max(this.maximumAbsoluteZ, 0.12F);
        }
    }

    private record Pose(
            float posX,
            float posY,
            float posZ,
            float rotX,
            float rotY,
            float rotZ,
            float scaleX,
            float scaleY,
            float scaleZ
    ) {
        Pose lerp(Pose target, float weight) {
            return new Pose(
                    mix(this.posX, target.posX, weight),
                    mix(this.posY, target.posY, weight),
                    mix(this.posZ, target.posZ, weight),
                    mix(this.rotX, target.rotX, weight),
                    mix(this.rotY, target.rotY, weight),
                    mix(this.rotZ, target.rotZ, weight),
                    mix(this.scaleX, target.scaleX, weight),
                    mix(this.scaleY, target.scaleY, weight),
                    mix(this.scaleZ, target.scaleZ, weight));
        }

        Pose withAdditionalYaw(float yaw) {
            return new Pose(
                    this.posX,
                    this.posY,
                    this.posZ,
                    this.rotX,
                    this.rotY + yaw,
                    this.rotZ,
                    this.scaleX,
                    this.scaleY,
                    this.scaleZ);
        }

        private static float mix(float start, float end, float weight) {
            return start + (end - start) * weight;
        }
    }
}
