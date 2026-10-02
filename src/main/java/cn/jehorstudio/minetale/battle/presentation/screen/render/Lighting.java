package cn.jehorstudio.minetale.battle.presentation.screen.render;

import cn.jehorstudio.minetale.battle.logic.coordinate.CanonicalVec3;
import cn.jehorstudio.minetale.battle.logic.coordinate.BattleCoordinateStateCache;
import cn.jehorstudio.minetale.battle.presentation.VisualConfig;
import org.joml.Matrix4f;
import org.joml.Vector3f;

// 将配置与冻结 Frame 解析为单帧光照事实。
public final class Lighting {
    private static final float SHADOW_BOUNDS_PADDING = 0.5F;
    private static final float SHADOW_RADIUS_STEP = 0.5F;
    private static final float DEFAULT_MODEL_HALF_EXTENT = 0.5F;

    private Lighting() {
    }

    // Crossfade 的两个分支各自冻结设置。
    public static Settings captureSettings(
            BattleCoordinateStateCache.SceneMode sceneMode,
            VisualConfig.BattleRenderMode requestedRenderMode
    ) {
        java.util.Objects.requireNonNull(sceneMode, "sceneMode");
        java.util.Objects.requireNonNull(requestedRenderMode, "requestedRenderMode");
        VisualConfig.BattleRenderMode effectiveRenderMode =
                sceneMode == BattleCoordinateStateCache.SceneMode.THREE_D
                        ? requestedRenderMode
                        : VisualConfig.BattleRenderMode.UNMODIFIED;
        return new Settings(
                effectiveRenderMode,
                VisualConfig.GLOBAL_LIGHT_DIRECTION(),
                VisualConfig.DIFFUSE_LIGHT_INTENSITY(),
                VisualConfig.SPECULAR_LIGHT_INTENSITY(),
                VisualConfig.SPECULAR_SHININESS(),
                VisualConfig.SHADOWS_ENABLED(),
                VisualConfig.SHADOW_MAP_SIZE(),
                VisualConfig.SHADOW_ORTHOGRAPHIC_RADIUS(),
                VisualConfig.SHADOW_BIAS(),
                VisualConfig.SHADOW_STRENGTH(),
                VisualConfig.EXTRUDED_IMAGE_SIDE_BRIGHTNESS_REDUCTION(),
                VisualConfig.SOUL_TINT_LIGHT_RADIUS(),
                VisualConfig.SOUL_TINT_LIGHT_EMISSION_STRENGTH()
        );
    }

    public static LightSpace lightSpace(BattleScene.Frame frame, Settings settings) {
        Bounds bounds = new Bounds();
        Vector3f frameAnchor = null;
        for (BattleScene.RenderCommand command : frame.commands()) {
            switch (command.geometry()) {
                case BATTLE_FRAME -> {
                    BattleScene.FrameMesh mesh = command.frameMesh();
                    addBox(bounds, command.model(), mesh.sizeX() * 0.5F, mesh.sizeY() * 0.5F, mesh.sizeZ() * 0.5F);
                    if (frameAnchor == null) {
                        frameAnchor = command.model().transformPosition(new Vector3f());
                    }
                }
                case EXTRUDED_IMAGE -> {
                    if (!command.rendered().castsShadow()) {
                        continue;
                    }
                    var image = command.extrudedImage();
                    addBox(bounds, command.model(),
                            image.source().width() / VisualConfig.SPRITE_PIXELS_PER_CANONICAL_UNIT() * 0.5F,
                            (float) image.thickness() * 0.5F,
                            image.source().height() / VisualConfig.SPRITE_PIXELS_PER_CANONICAL_UNIT() * 0.5F);
                }
                case OBJ_MODEL -> {
                    if (command.rendered().castsShadow()) {
                        addBox(bounds, command.model(),
                                DEFAULT_MODEL_HALF_EXTENT, DEFAULT_MODEL_HALF_EXTENT, DEFAULT_MODEL_HALF_EXTENT);
                    }
                }
                default -> {
                    // 辅助网格只采样阴影，其范围已由 BattleFrame 覆盖。
                }
            }
        }

        Vector3f center = frameAnchor != null ? frameAnchor : bounds.center();
        float requiredRadius = bounds.empty()
                ? 0.0F
                : bounds.maximumDistance(center) + SHADOW_BOUNDS_PADDING;
        float radius = Math.max(settings.shadowOrthographicRadius(),
                (float) Math.ceil(requiredRadius / SHADOW_RADIUS_STEP) * SHADOW_RADIUS_STEP);
        Vector3f direction = vector(settings.globalLightDirection()).normalize();
        Vector3f up = Math.abs(direction.dot(0.0F, 1.0F, 0.0F)) > 0.99F
                ? new Vector3f(0.0F, 0.0F, 1.0F)
                : new Vector3f(0.0F, 1.0F, 0.0F);
        center = snapToShadowTexel(center, direction, up, radius, settings.shadowMapSize());
        Vector3f eye = new Vector3f(center).sub(new Vector3f(direction).mul(radius * 2.0F));
        Matrix4f view = new Matrix4f().lookAt(eye, center, up);
        Matrix4f projection = new Matrix4f().ortho(-radius, radius, -radius, radius, 0.1F, radius * 4.0F);
        return new LightSpace(new Matrix4f(projection).mul(view), center, radius);
    }

    private static Vector3f snapToShadowTexel(
            Vector3f center,
            Vector3f direction,
            Vector3f up,
            float radius,
            int shadowMapSize
    ) {
        float texelSize = radius * 2.0F / shadowMapSize;
        Vector3f right = direction.cross(up, new Vector3f()).normalize();
        Vector3f lightUp = right.cross(direction, new Vector3f()).normalize();
        float horizontal = center.dot(right);
        float vertical = center.dot(lightUp);
        float snappedHorizontal = Math.round(horizontal / texelSize) * texelSize;
        float snappedVertical = Math.round(vertical / texelSize) * texelSize;
        return new Vector3f(center)
                .fma(snappedHorizontal - horizontal, right)
                .fma(snappedVertical - vertical, lightUp);
    }

    private static void addBox(Bounds bounds, Matrix4f model, float halfX, float halfY, float halfZ) {
        Vector3f point = new Vector3f();
        for (int x = -1; x <= 1; x += 2) {
            for (int y = -1; y <= 1; y += 2) {
                for (int z = -1; z <= 1; z += 2) {
                    model.transformPosition(x * halfX, y * halfY, z * halfZ, point);
                    bounds.include(point);
                }
            }
        }
    }

    private static Vector3f vector(CanonicalVec3 value) {
        return new Vector3f((float) value.x(), (float) value.y(), (float) value.z());
    }

    private static final class Bounds {
        private final Vector3f minimum = new Vector3f(Float.POSITIVE_INFINITY);
        private final Vector3f maximum = new Vector3f(Float.NEGATIVE_INFINITY);

        void include(Vector3f point) {
            this.minimum.min(point);
            this.maximum.max(point);
        }

        boolean empty() {
            return !Float.isFinite(this.minimum.x());
        }

        Vector3f center() {
            return empty() ? new Vector3f() : this.minimum.add(this.maximum, new Vector3f()).mul(0.5F);
        }

        float maximumDistance(Vector3f center) {
            float maximumSquared = 0.0F;
            for (int x = 0; x <= 1; x++) {
                for (int y = 0; y <= 1; y++) {
                    for (int z = 0; z <= 1; z++) {
                        float px = x == 0 ? this.minimum.x() : this.maximum.x();
                        float py = y == 0 ? this.minimum.y() : this.maximum.y();
                        float pz = z == 0 ? this.minimum.z() : this.maximum.z();
                        maximumSquared = Math.max(maximumSquared, center.distanceSquared(px, py, pz));
                    }
                }
            }
            return (float) Math.sqrt(maximumSquared);
        }
    }

    public record Settings(
            VisualConfig.BattleRenderMode renderMode,
            CanonicalVec3 globalLightDirection,
            float diffuseIntensity,
            float specularIntensity,
            float shininess,
            boolean shadowsEnabled,
            int shadowMapSize,
            float shadowOrthographicRadius,
            float shadowBias,
            float shadowStrength,
            float extrudedImageSideBrightnessReduction,
            float soulTintLightRadius,
            float soulTintLightEmissionStrength
    ) {
        public Settings {
            if (!Float.isFinite(soulTintLightRadius) || soulTintLightRadius <= 0.0F) {
                throw new IllegalArgumentException("soulTintLightRadius must be finite and > 0.");
            }
            if (!Float.isFinite(soulTintLightEmissionStrength) || soulTintLightEmissionStrength < 0.0F) {
                throw new IllegalArgumentException("soulTintLightEmissionStrength must be finite and >= 0.");
            }
        }

        public boolean rendered() {
            return this.renderMode == VisualConfig.BattleRenderMode.RENDERED;
        }
    }

    public record LightSpace(Matrix4f viewProjection, Vector3f center, float radius) {
        public LightSpace {
            viewProjection = new Matrix4f(viewProjection);
            center = new Vector3f(center);
        }
    }
}
