package cn.jehorstudio.minetale.battle.presentation.screen.render;

import cn.jehorstudio.minetale.battle.logic.actor.BattleArenaBounds;
import cn.jehorstudio.minetale.battle.logic.coordinate.BattleCoordinateStateCache;
import cn.jehorstudio.minetale.battle.logic.coordinate.CanonicalVec3;
import cn.jehorstudio.minetale.battle.presentation.VisualConfig;
import cn.jehorstudio.minetale.battle.presentation.screen.render.environment.EnvironmentCaptureSnapshot;
import cn.jehorstudio.minetale.battle.presentation.screen.render.environment.EnvironmentField;
import com.mojang.blaze3d.buffers.Std140SizeCalculator;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;

// 为 3D + RENDERED 材质绑定冻结 Environment Field 与位置感知染色。
// 与背景只共享只读 Field；代理范围首次进入该模式时冻结。
final class EnvironmentLighting implements AutoCloseable {
    private static final float PROXY_EXPANSION = 0.5F;
    private static final Basis FALLBACK_BASIS = new Basis(
            new Vec3(1.0D, 0.0D, 0.0D),
            new Vec3(0.0D, 1.0D, 0.0D),
            new Vec3(0.0D, 0.0D, 1.0D)
    );

    private final Uniforms uniforms = new Uniforms();
    private final TextureTarget blackFallback = createBlackFallback();
    private UUID proxyBattleId;
    private ProxyBox frozenProxyBox;

    FramePlan plan(
            UUID battleId,
            BattleScene.Snapshot scene,
            BattleScene.Frame frame,
            Lighting.Settings lighting,
            EnvironmentCaptureSnapshot environment
    ) {
        Objects.requireNonNull(battleId, "battleId");
        Objects.requireNonNull(scene, "scene");
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(lighting, "lighting");
        Objects.requireNonNull(environment, "environment");

        Settings settings = Settings.capture();
        boolean active = frame.sceneMode() == BattleCoordinateStateCache.SceneMode.THREE_D
                && lighting.rendered();
        if (!active) {
            return FramePlan.disabled();
        }

        if (!battleId.equals(this.proxyBattleId) || this.frozenProxyBox == null) {
            this.proxyBattleId = battleId;
            this.frozenProxyBox = resolveProxyBox(scene, frame);
        }

        boolean fieldValid = environment.ready();
        EnvironmentField field = fieldValid ? environment.field().orElseThrow() : null;
        Basis basis = fieldValid ? Basis.from(field) : FALLBACK_BASIS;
        GpuTextureView texture = fieldValid
                ? field.textureView()
                : this.blackFallback.getColorTextureView();
        return new FramePlan(
                texture,
                basis,
                fieldValid,
                this.frozenProxyBox,
                settings
        );
    }

    void upload(FramePlan plan) {
        requireBindings(plan);
        this.uniforms.upload(plan);
    }

    // 仅 Rendered OBJ Material 与 BattleFrame pipeline 可绑定该环境光状态。
    void bind(RenderPass pass, FramePlan plan) {
        Objects.requireNonNull(pass, "pass");
        requireBindings(plan);
        this.uniforms.bind(pass, plan);
    }

    void rotate(FramePlan plan) {
        if (plan.bindingsRequired()) {
            this.uniforms.rotate();
        }
    }

    @Override
    public void close() {
        this.uniforms.close();
        this.blackFallback.destroyBuffers();
    }

    private static ProxyBox resolveProxyBox(BattleScene.Snapshot scene, BattleScene.Frame frame) {
        BattleArenaBounds arena = BattleArenaBounds.resolve(scene.logic().actors());
        if (arena.provider() != null) {
            return ProxyBox.fromBounds(
                    vector(arena.min()),
                    vector(arena.max()),
                    ProxySource.BATTLE_ARENA_BOUNDS
            );
        }

        BattleScene.VolumeRegion frameRegion = frame.volumeRegion().orElse(null);
        if (frameRegion != null) {
            return frameProxyBox(frameRegion);
        }

        return ProxyBox.fromBounds(
                vector(BattleArenaBounds.LEGACY_DEFAULT.min()),
                vector(BattleArenaBounds.LEGACY_DEFAULT.max()),
                ProxySource.LEGACY_DEFAULT
        );
    }

    private static ProxyBox frameProxyBox(BattleScene.VolumeRegion region) {
        Matrix4f transform = region.localToWorld();
        Vector3f half = region.halfSize();
        Vector3f minimum = new Vector3f(Float.POSITIVE_INFINITY);
        Vector3f maximum = new Vector3f(Float.NEGATIVE_INFINITY);
        Vector3f point = new Vector3f();
        for (int x = -1; x <= 1; x += 2) {
            for (int y = -1; y <= 1; y += 2) {
                for (int z = -1; z <= 1; z += 2) {
                    transform.transformPosition(
                            x * half.x(),
                            y * half.y(),
                            z * half.z(),
                            point
                    );
                    minimum.min(point);
                    maximum.max(point);
                }
            }
        }
        return ProxyBox.fromBounds(minimum, maximum, ProxySource.BATTLE_FRAME);
    }

    private static TextureTarget createBlackFallback() {
        TextureTarget target = new TextureTarget(
                "MineTale Battle environment field black fallback",
                1,
                1,
                false
        );
        target.setFilterMode(FilterMode.NEAREST);
        RenderSystem.getDevice().createCommandEncoder()
                .clearColorTexture(target.getColorTexture(), 0xFF000000);
        return target;
    }

    private static Vector3f vector(CanonicalVec3 value) {
        return new Vector3f((float) value.x(), (float) value.y(), (float) value.z());
    }

    private static void requireBindings(FramePlan plan) {
        Objects.requireNonNull(plan, "plan");
        if (!plan.bindingsRequired()) {
            throw new IllegalArgumentException("未激活的环境光计划不得提交或绑定 GPU 状态");
        }
    }

    private record Settings(
            boolean enabled,
            float intensity,
            float widthScale,
            float baseLevel,
            float colorStrength,
            float spatialVariation
    ) {
        static Settings capture() {
            return new Settings(
                    VisualConfig.ENVIRONMENT_LIGHT_ENABLED(),
                    VisualConfig.ENVIRONMENT_LIGHT_INTENSITY(),
                    VisualConfig.ENVIRONMENT_LIGHT_WIDTH_SCALE(),
                    VisualConfig.ENVIRONMENT_LIGHT_BASE_LEVEL(),
                    VisualConfig.ENVIRONMENT_LIGHT_COLOR_STRENGTH(),
                    VisualConfig.ENVIRONMENT_LIGHT_SPATIAL_VARIATION()
            );
        }
    }

    record FramePlan(
            GpuTextureView fieldTexture,
            Basis captureBasis,
            boolean fieldValid,
            ProxyBox proxyBox,
            Settings settings
    ) {
        FramePlan {
            if (fieldTexture != null) {
                Objects.requireNonNull(fieldTexture, "fieldTexture");
                Objects.requireNonNull(captureBasis, "captureBasis");
                Objects.requireNonNull(proxyBox, "proxyBox");
                Objects.requireNonNull(settings, "settings");
            } else if (captureBasis != null || proxyBox != null || settings != null || fieldValid) {
                throw new IllegalArgumentException("未激活的环境光计划不得保留 GPU 输入或代理箱");
            }
        }

        boolean bindingsRequired() {
            return this.fieldTexture != null;
        }

        static FramePlan disabled() {
            return new FramePlan(null, null, false, null, null);
        }
    }

    private record Basis(Vec3 right, Vec3 up, Vec3 forward) {
        private Basis {
            Objects.requireNonNull(right, "right");
            Objects.requireNonNull(up, "up");
            Objects.requireNonNull(forward, "forward");
        }

        static Basis from(EnvironmentField field) {
            return new Basis(field.right(), field.up(), field.forward());
        }
    }

    private record ProxyBox(Vector3f center, Vector3f halfExtent, ProxySource source) {
        private ProxyBox {
            center = new Vector3f(Objects.requireNonNull(center, "center"));
            halfExtent = new Vector3f(Objects.requireNonNull(halfExtent, "halfExtent"));
            Objects.requireNonNull(source, "source");
            if (!center.isFinite()
                    || !halfExtent.isFinite()
                    || !(halfExtent.x() > 0.0F && halfExtent.y() > 0.0F && halfExtent.z() > 0.0F)) {
                throw new IllegalArgumentException("Environment Proxy Box 必须有限且具有正半轴");
            }
        }

        static ProxyBox fromBounds(Vector3f minimum, Vector3f maximum, ProxySource source) {
            if (!minimum.isFinite()
                    || !maximum.isFinite()
                    || !(minimum.x() < maximum.x()
                    && minimum.y() < maximum.y()
                    && minimum.z() < maximum.z())) {
                throw new IllegalArgumentException("Environment Proxy Box 边界无效");
            }
            Vector3f center = minimum.add(maximum, new Vector3f()).mul(0.5F);
            Vector3f halfExtent = maximum.sub(minimum, new Vector3f())
                    .mul(0.5F)
                    .add(PROXY_EXPANSION, PROXY_EXPANSION, PROXY_EXPANSION);
            return new ProxyBox(center, halfExtent, source);
        }

        @Override
        public Vector3f center() {
            return new Vector3f(this.center);
        }

        @Override
        public Vector3f halfExtent() {
            return new Vector3f(this.halfExtent);
        }
    }

    private enum ProxySource {
        BATTLE_ARENA_BOUNDS,
        BATTLE_FRAME,
        LEGACY_DEFAULT
    }

    // Java 统一轮转独立的 Field/Light UBO。
    private static final class Uniforms implements AutoCloseable {
        private static final float LAMBDA_MIN = 1.5F;
        private static final float LAMBDA_MAX = 6.8F;
        private static final int FIELD_BLOCK_SIZE = new Std140SizeCalculator()
                .putVec4().putVec4().putVec4().putVec4()
                .get();
        private static final int LIGHT_BLOCK_SIZE = new Std140SizeCalculator()
                .putVec4().putVec4().putVec4().putVec4()
                .get();

        private final MappableRingBuffer fieldBuffer = new MappableRingBuffer(
                () -> "Battle sparse-SG environment field uniforms", 130, FIELD_BLOCK_SIZE);
        private final MappableRingBuffer lightBuffer = new MappableRingBuffer(
                () -> "Battle sparse-SG environment light uniforms", 130, LIGHT_BLOCK_SIZE);

        private void upload(FramePlan plan) {
            uploadField(plan.captureBasis(), plan.fieldValid());
            uploadLight(plan.settings(), plan.proxyBox());
        }

        private void bind(RenderPass pass, FramePlan plan) {
            pass.bindSampler("EnvironmentFieldSampler", plan.fieldTexture());
            pass.setUniform("EnvironmentFieldUniform", this.fieldBuffer.currentBuffer());
            pass.setUniform("EnvironmentLightUniform", this.lightBuffer.currentBuffer());
        }

        private void rotate() {
            this.fieldBuffer.rotate();
            this.lightBuffer.rotate();
        }

        @Override
        public void close() {
            this.fieldBuffer.close();
            this.lightBuffer.close();
        }

        private void uploadField(Basis captureBasis, boolean fieldValid) {
            try (var mapped = RenderSystem.getDevice().createCommandEncoder()
                    .mapBuffer(this.fieldBuffer.currentBuffer(), false, true)) {
                ByteBuffer data = mapped.data();
                putBasis(data, 0, captureBasis.right());
                putBasis(data, 16, captureBasis.up());
                putBasis(data, 32, captureBasis.forward());
                putVec4(data, 48,
                        fieldValid ? 1.0F : 0.0F,
                        LAMBDA_MIN,
                        LAMBDA_MAX,
                        0.0F);
            }
        }

        private void uploadLight(Settings settings, ProxyBox proxyBox) {
            Vector3f center = proxyBox.center();
            Vector3f halfExtent = proxyBox.halfExtent();
            try (var mapped = RenderSystem.getDevice().createCommandEncoder()
                    .mapBuffer(this.lightBuffer.currentBuffer(), false, true)) {
                ByteBuffer data = mapped.data();
                putVec4(data, 0,
                        settings.enabled() ? 1.0F : 0.0F,
                        settings.intensity(),
                        settings.widthScale(),
                        settings.baseLevel());
                putVec4(data, 16,
                        settings.colorStrength(),
                        settings.spatialVariation(),
                        0.0F,
                        0.0F);
                putVec4(data, 32, center.x(), center.y(), center.z(), 0.0F);
                putVec4(data, 48,
                        halfExtent.x(), halfExtent.y(), halfExtent.z(), 0.0F);
            }
        }

        private static void putBasis(ByteBuffer data, int offset, Vec3 vector) {
            putVec4(data, offset, (float) vector.x, (float) vector.y, (float) vector.z, 0.0F);
        }

        private static void putVec4(
                ByteBuffer data,
                int offset,
                float x,
                float y,
                float z,
                float w
        ) {
            data.putFloat(offset, x);
            data.putFloat(offset + 4, y);
            data.putFloat(offset + 8, z);
            data.putFloat(offset + 12, w);
        }
    }
}
