package cn.jehorstudio.minetale.dimension.ebott.shaft;

import cn.jehorstudio.minetale.MineTale;
import cn.jehorstudio.minetale.dimension.worldgen.math.WorldgenMath;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.Optional;

// 两端竖井从同一份持久化参数解析；profileVersion 冻结存档采用的几何规则。
public record ShaftData(
        long shaftSeed,
        int profileVersion,
        int appliedVersion,
        int sourceLogicalOriginY,
        int targetLogicalOriginY,
        int sourceSeamY,
        int targetSeamY,
        int fallbackTargetArrivalY
) {
    public static final int SHAFT_SEED_CHANNEL = 0x5A4F;
    public static final int CURRENT_PROFILE_VERSION = 2;
    // appliedVersion 8 将 Snowdin 的接缝截面和穿越面迁移到维度顶面。
    public static final int CURRENT_APPLIED_VERSION = 8;

    public static final int DEFAULT_SOURCE_LOGICAL_ORIGIN_Y = -24;
    public static final int DEFAULT_TARGET_LOGICAL_ORIGIN_Y = 224;
    public static final int DEFAULT_SOURCE_SEAM_Y = -64;
    public static final int DEFAULT_TARGET_SEAM_Y = 253;
    public static final int DEFAULT_TARGET_ARRIVAL_Y = 224;

    // 字段名属于存档格式；旧 minetale_ebott 数据依赖这些扁平键名。
    public static final MapCodec<ShaftData> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            Codec.LONG.fieldOf("shaft_seed").forGetter(ShaftData::shaftSeed),
            Codec.INT.optionalFieldOf("shaft_profile_version", CURRENT_PROFILE_VERSION)
                    .forGetter(ShaftData::profileVersion),
            Codec.INT.optionalFieldOf("shaft_applied_version", 0)
                    .forGetter(ShaftData::appliedVersion),
            Codec.INT.optionalFieldOf("source_logical_origin_y")
                    .forGetter(data -> Optional.of(data.sourceLogicalOriginY)),
            Codec.INT.optionalFieldOf("target_logical_origin_y")
                    .forGetter(data -> Optional.of(data.targetLogicalOriginY)),
            Codec.INT.optionalFieldOf("source_seam_y")
                    .forGetter(data -> Optional.of(data.sourceSeamY)),
            Codec.INT.optionalFieldOf("target_seam_y")
                    .forGetter(data -> Optional.of(data.targetSeamY)),
            Codec.INT.optionalFieldOf("fallback_target_arrival_y")
                    .forGetter(data -> Optional.of(data.fallbackTargetArrivalY)),
            Codec.INT.optionalFieldOf("source_commit_y")
                    .forGetter(data -> Optional.empty()),
            Codec.INT.optionalFieldOf("target_arrival_y")
                    .forGetter(data -> Optional.empty())
    ).apply(instance, ShaftData::fromStorage));

    public ShaftData {
        if (appliedVersion < 0) {
            throw new IllegalArgumentException("appliedVersion must not be negative");
        }
        // 旧 profile 的高度已经进入世界几何，不能用当前规则拒绝其存档值
        if (profileVersion == CURRENT_PROFILE_VERSION
                && (sourceSeamY > sourceLogicalOriginY || targetSeamY <= fallbackTargetArrivalY)) {
            throw new IllegalArgumentException("invalid Ebott shaft heights");
        }
    }

    public static ShaftData unresolved() {
        return new ShaftData(
                0L,
                CURRENT_PROFILE_VERSION,
                0,
                DEFAULT_SOURCE_LOGICAL_ORIGIN_Y,
                DEFAULT_TARGET_LOGICAL_ORIGIN_Y,
                DEFAULT_SOURCE_SEAM_Y,
                DEFAULT_TARGET_SEAM_Y,
                DEFAULT_TARGET_ARRIVAL_Y
        );
    }

    public static ShaftData create(long worldSeed, int sourceMinY) {
        return new ShaftData(
                WorldgenMath.channelSeed(worldSeed, SHAFT_SEED_CHANNEL),
                CURRENT_PROFILE_VERSION,
                0,
                Math.max(sourceMinY + 8, DEFAULT_SOURCE_LOGICAL_ORIGIN_Y),
                DEFAULT_TARGET_LOGICAL_ORIGIN_Y,
                sourceMinY,
                DEFAULT_TARGET_SEAM_Y,
                DEFAULT_TARGET_ARRIVAL_Y
        );
    }

    public ShaftData markApplied(int version) {
        if (version < appliedVersion) {
            throw new IllegalArgumentException("shaft applied version cannot move backwards");
        }
        return version == appliedVersion
                ? this
                : new ShaftData(
                        shaftSeed,
                        profileVersion,
                        version,
                        sourceLogicalOriginY,
                        targetLogicalOriginY,
                        sourceSeamY,
                        targetSeamY,
                        fallbackTargetArrivalY
                );
    }

    public Profile profile() {
        return Profile.forVersion(this);
    }

    private static ShaftData fromStorage(
            long shaftSeed,
            int profileVersion,
            int appliedVersion,
            Optional<Integer> sourceLogicalOriginY,
            Optional<Integer> targetLogicalOriginY,
            Optional<Integer> sourceSeamY,
            Optional<Integer> targetSeamY,
            Optional<Integer> fallbackTargetArrivalY,
            Optional<Integer> legacySourceCommitY,
            Optional<Integer> legacyTargetArrivalY
    ) {
        int resolvedSourceOrigin = sourceLogicalOriginY
                .or(() -> legacySourceCommitY)
                .orElse(DEFAULT_SOURCE_LOGICAL_ORIGIN_Y);
        int resolvedTargetOrigin = targetLogicalOriginY
                .or(() -> legacyTargetArrivalY)
                .orElse(DEFAULT_TARGET_LOGICAL_ORIGIN_Y);
        int resolvedTargetArrival = fallbackTargetArrivalY
                .or(() -> legacyTargetArrivalY)
                .orElse(DEFAULT_TARGET_ARRIVAL_Y);
        return new ShaftData(
                shaftSeed,
                profileVersion,
                appliedVersion,
                resolvedSourceOrigin,
                resolvedTargetOrigin,
                sourceSeamY.orElse(DEFAULT_SOURCE_SEAM_Y),
                targetSeamY.orElse(DEFAULT_TARGET_SEAM_Y),
                resolvedTargetArrival
        );
    }

    // 完整几何由持久化的最小状态派生，避免保存重复常量。
    public record Profile(
            long shaftSeed,
            int profileVersion,
            int sourceLogicalOriginY,
            int targetLogicalOriginY,
            int sourceSeamY,
            int targetSeamY,
            int fallbackTargetArrivalY,
            int sourceFailY,
            int targetBarrierMinY,
            int targetBarrierMaxYExclusive,
            double airRadius,
            double wallThickness,
            double centerlineWander,
            double wallPerturbation,
            double verticalNoiseScale,
            int sourceCollarHeight
    ) {
        private static final int SOURCE_FAIL_Y = -56;
        private static final int TARGET_BARRIER_MIN_Y = 248;
        private static final int TARGET_BARRIER_MAX_Y_EXCLUSIVE = 253;
        private static final double AIR_RADIUS = 12.0;
        private static final double WALL_THICKNESS = 4.0;

        public Profile {
            if (targetBarrierMaxYExclusive <= targetBarrierMinY
                    || targetSeamY != targetBarrierMaxYExclusive) {
                throw new IllegalArgumentException("invalid target barrier range");
            }
            if (!(airRadius > 0.0)
                    || !(wallThickness > 0.0)
                    || centerlineWander < 0.0
                    || wallPerturbation < 0.0
                    || !(verticalNoiseScale > 0.0)
                    || sourceCollarHeight < 1) {
                throw new IllegalArgumentException("invalid Ebott shaft geometry parameters");
            }
        }

        // v1 与 v2 均使用无保护壳几何
        // 未知版本降级到该几何以保持存档可进入。
        private static Profile forVersion(ShaftData data) {
            return switch (data.profileVersion()) {
                case 1, CURRENT_PROFILE_VERSION -> withoutProtection(data);
                default -> {
                    MineTale.LOGGER.warn(
                            "Unknown Ebott shaft profile version {}; falling back to version 2 geometry.",
                            data.profileVersion()
                    );
                    yield withoutProtection(data);
                }
            };
        }

        private static Profile withoutProtection(ShaftData data) {
            return new Profile(
                    data.shaftSeed,
                    data.profileVersion,
                    data.sourceLogicalOriginY,
                    data.targetLogicalOriginY,
                    data.sourceSeamY,
                    data.targetSeamY,
                    data.fallbackTargetArrivalY,
                    SOURCE_FAIL_Y,
                    TARGET_BARRIER_MIN_Y,
                    TARGET_BARRIER_MAX_Y_EXCLUSIVE,
                    AIR_RADIUS,
                    WALL_THICKNESS,
                    1.25,
                    0.75,
                    1.0 / 48.0,
                    4
            );
        }

        public int sourceLogicalY(int sourceY) {
            return sourceY - sourceLogicalOriginY;
        }

        public int targetLogicalY(int targetY) {
            return targetY - targetLogicalOriginY;
        }

        public boolean isTargetBarrierY(int targetY) {
            return targetY >= targetBarrierMinY && targetY < targetBarrierMaxYExclusive;
        }

        public double maximumAirRadius() {
            return airRadius + centerlineWander + wallPerturbation;
        }

        public double maximumShaftRadius() {
            return airRadius
                    + wallThickness
                    + centerlineWander
                    + wallPerturbation;
        }

        public int shaftEnvelopeRadius() {
            return (int) StrictMath.ceil(maximumShaftRadius());
        }
    }
}
