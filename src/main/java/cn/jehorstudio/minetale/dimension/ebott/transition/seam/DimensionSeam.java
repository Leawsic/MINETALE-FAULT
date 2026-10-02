package cn.jehorstudio.minetale.dimension.ebott.transition.seam;

import cn.jehorstudio.minetale.dimension.worldgen.registry.ModWorldgenKeys;
import cn.jehorstudio.minetale.dimension.ebott.EbottData;
import cn.jehorstudio.minetale.dimension.ebott.EbottDestination;
import cn.jehorstudio.minetale.dimension.ebott.PlaceManager;
import cn.jehorstudio.minetale.dimension.ebott.shaft.ShaftData;
import cn.jehorstudio.minetale.dimension.ebott.shaft.ShaftGenerator;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.Objects;

// 服务端、客户端与 worldgen 共用的冻结接缝事实；圆形开口和无旋转映射由此唯一拥有。
public record DimensionSeam(
        ResourceKey<Level> sourceDimension,
        ResourceKey<Level> targetDimension,
        double sourceCenterX,
        double sourceCenterZ,
        double sourcePlaneY,
        double targetCenterX,
        double targetCenterZ,
        double targetPlaneY,
        CircularSeamAperture aperture,
        int geometryVersion,
        long geometryRevision
) {
    public DimensionSeam {
        Objects.requireNonNull(sourceDimension, "sourceDimension");
        Objects.requireNonNull(targetDimension, "targetDimension");
        Objects.requireNonNull(aperture, "aperture");
        requireFinite(sourceCenterX, "sourceCenterX");
        requireFinite(sourceCenterZ, "sourceCenterZ");
        requireFinite(sourcePlaneY, "sourcePlaneY");
        requireFinite(targetCenterX, "targetCenterX");
        requireFinite(targetCenterZ, "targetCenterZ");
        requireFinite(targetPlaneY, "targetPlaneY");
        if (sourceDimension.equals(targetDimension) || geometryVersion <= 0) {
            throw new IllegalArgumentException("invalid dimension seam");
        }
    }

    // 权威接缝只从冻结的 Ebott 快照与目标高度创建。
    public static DimensionSeam from(EbottData.Snapshot snapshot, double targetPlaneY) {
        PlaceManager.Place place = snapshot.place();
        BlockPos sourceOpening = snapshot.caveEntrance().shaftOpening(place);
        ShaftData.Profile profile = snapshot.shaft().profile();
        ShaftGenerator.LayerSample layer = ShaftGenerator.sampleSourceLayer(
                profile, profile.sourceSeamY());
        int geometryVersion = profile.profileVersion();
        long revision = Long.rotateLeft(profile.shaftSeed(), 17)
                ^ ((long) geometryVersion << 32)
                ^ geometryVersion
                ^ Double.doubleToLongBits(targetPlaneY);
        return new DimensionSeam(
                Level.OVERWORLD,
                ModWorldgenKeys.UNDERGROUND_LEVEL,
                sourceOpening.getX() + 0.5,
                sourceOpening.getZ() + 0.5,
                profile.sourceSeamY(),
                EbottDestination.centerX() + 0.5,
                EbottDestination.centerZ() + 0.5,
                targetPlaneY,
                new CircularSeamAperture(
                        layer.centerX(),
                        layer.centerZ(),
                        layer.airBoundary(),
                        profile.profileVersion()
                ),
                geometryVersion,
                revision
        );
    }

    public SeamTransform transform() {
        return new SeamTransform(
                sourceCenterX,
                sourceCenterZ,
                sourcePlaneY,
                targetCenterX,
                targetCenterZ,
                targetPlaneY
        );
    }

    private static void requireFinite(double value, String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }

    // 接缝层的中心线偏移与空气半径在服务端冻结
    public record CircularSeamAperture(
            double centerOffsetX,
            double centerOffsetZ,
            double radius,
            int profileVersion
    ) {
        public CircularSeamAperture {
            requireFinite(centerOffsetX, "centerOffsetX");
            requireFinite(centerOffsetZ, "centerOffsetZ");
            requireFinite(radius, "radius");
            if (!(radius > 0.0) || profileVersion <= 0) {
                throw new IllegalArgumentException("invalid circular seam aperture");
            }
        }

        public boolean contains(double localX, double localZ) {
            return signedBoundaryDistance(localX, localZ) >= 0.0;
        }

        // 有符号裕量：开口内为正，边界为零，开口外为负。
        public double signedBoundaryDistance(double localX, double localZ) {
            requireFinite(localX, "localX");
            requireFinite(localZ, "localZ");
            return radius - StrictMath.hypot(localX - centerOffsetX, localZ - centerOffsetZ);
        }

        // 返回同时包住中心偏移与圆形开口的保守半径。
        public double conservativeRadius() {
            return radius + StrictMath.hypot(centerOffsetX, centerOffsetZ);
        }
    }

    // 保持局部 XZ 与运动连续性的无旋转跨维映射。
    public record SeamTransform(
            double sourceCenterX,
            double sourceCenterZ,
            double sourcePlaneY,
            double targetCenterX,
            double targetCenterZ,
            double targetPlaneY
    ) {
        public SeamTransform {
            requireFinite(sourceCenterX, "sourceCenterX");
            requireFinite(sourceCenterZ, "sourceCenterZ");
            requireFinite(sourcePlaneY, "sourcePlaneY");
            requireFinite(targetCenterX, "targetCenterX");
            requireFinite(targetCenterZ, "targetCenterZ");
            requireFinite(targetPlaneY, "targetPlaneY");
        }

        public Vec3 sourceToTargetProbe(Vec3 sourceProbe) {
            requireFinite(sourceProbe, "sourceProbe");
            return new Vec3(
                    targetCenterX + (sourceProbe.x - sourceCenterX),
                    targetPlaneY + (sourceProbe.y - sourcePlaneY),
                    targetCenterZ + (sourceProbe.z - sourceCenterZ)
            );
        }

        public Vec3 sourceToTargetVelocity(Vec3 sourceVelocity) {
            requireFinite(sourceVelocity, "sourceVelocity");
            return sourceVelocity;
        }

        private static void requireFinite(Vec3 value, String name) {
            if (value == null || !Double.isFinite(value.x)
                    || !Double.isFinite(value.y) || !Double.isFinite(value.z)) {
                throw new IllegalArgumentException(name + " must be finite");
            }
        }

        private static void requireFinite(double value, String name) {
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException(name + " must be finite");
            }
        }
    }
}
