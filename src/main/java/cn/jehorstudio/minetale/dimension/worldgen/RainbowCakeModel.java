package cn.jehorstudio.minetale.dimension.worldgen;

import java.util.Objects;

// 沿 Z/line_progress 规划地下 Region 序列；X 只表示横向延展，模型本身不写入地形。
public final class RainbowCakeModel {
    private final long seed;
    private final CakeConfig config;

    private RainbowCakeModel(long seed, CakeConfig config) {
        this.seed = seed;
        this.config = Objects.requireNonNull(config, "config");
    }

    public static RainbowCakeModel create(long seed) {
        return new RainbowCakeModel(seed, CakeConfig.defaults());
    }

    public CakeSample sample(double x, double z) {
        if (z < 0.0) {
            return isInsideOrigin(x, z) ? originSample(x, z) : edgeSample(x, z);
        }
        double lineX = mainLineX(z);
        double lineProgress = z;
        double lateralDistance = x - lineX;
        RegionBand band = regionBandAt(lineProgress);

        return new CakeSample(
                band.region(),
                lineProgress,
                config.mainLineY(),
                lateralDistance,
                band.localProgress(),
                band.regionPresence()
        );
    }

    public void dispatch(CakeSample sample, CakePipelines pipelines) {
        Objects.requireNonNull(sample, "sample");
        Objects.requireNonNull(pipelines, "pipelines");
        switch (sample.region()) {
            case EDGE_REGION -> pipelines.edgeRegion().run(sample);
            case ORIGIN -> pipelines.origin().run(sample);
            case TRANSITION_TUNNEL -> pipelines.transitionTunnel().run(sample);
            case RUINS -> pipelines.ruins().run(sample);
            case SNOWDIN -> pipelines.snowdin().run(sample);
            case WATERFALL -> pipelines.waterfall().run(sample);
            case HOT_LAND -> pipelines.hotLand().run(sample);
        }
    }

    private boolean isInsideOrigin(double x, double z) {
        return x >= config.originMinX()
                && x < config.originMaxXExclusive()
                && z >= config.originMinZ()
                && z < config.originMaxZExclusive();
    }

    private CakeSample originSample(double x, double z) {
        return new CakeSample(
                CakeRegion.ORIGIN,
                z,
                config.mainLineY(),
                x - config.originCenterX(),
                normalized(z - config.originMinZ(), config.originLength()),
                1.0
        );
    }

    private CakeSample edgeSample(double x, double z) {
        return new CakeSample(
                CakeRegion.EDGE_REGION,
                z,
                config.mainLineY(),
                x - mainLineX(z),
                0.0,
                1.0
        );
    }

    private RegionBand regionBandAt(double lineProgress) {
        if (lineProgress < 0.0) {
            return edgeRegion();
        }

        RegionBand band = consume(lineProgress, CakeRegion.TRANSITION_TUNNEL, config.transitionTunnelLength(), 0.0);
        if (band != null) {
            return band;
        }
        double cursor = config.transitionTunnelLength();

        band = consume(lineProgress, CakeRegion.RUINS, config.ruinsLength(), cursor);
        if (band != null) {
            return band;
        }
        cursor += config.ruinsLength();

        band = consume(lineProgress, CakeRegion.TRANSITION_TUNNEL, config.transitionTunnelLength(), cursor);
        if (band != null) {
            return band;
        }
        cursor += config.transitionTunnelLength();

        band = consume(lineProgress, CakeRegion.SNOWDIN, config.snowdinLength(), cursor);
        if (band != null) {
            return band;
        }
        cursor += config.snowdinLength();

        band = consume(lineProgress, CakeRegion.TRANSITION_TUNNEL, config.transitionTunnelLength(), cursor);
        if (band != null) {
            return band;
        }
        cursor += config.transitionTunnelLength();

        band = consume(lineProgress, CakeRegion.WATERFALL, config.waterfallLength(), cursor);
        if (band != null) {
            return band;
        }
        cursor += config.waterfallLength();

        band = consume(lineProgress, CakeRegion.TRANSITION_TUNNEL, config.transitionTunnelLength(), cursor);
        if (band != null) {
            return band;
        }
        cursor += config.transitionTunnelLength();

        band = consume(lineProgress, CakeRegion.HOT_LAND, config.hotLandLength(), cursor);
        if (band != null) {
            return band;
        }

        return edgeRegion();
    }

    private RegionBand consume(double progress, CakeRegion region, double length, double start) {
        double end = start + length;
        if (progress < start || progress >= end) {
            return null;
        }
        return new RegionBand(region, normalized(progress - start, length), 1.0);
    }

    private double mainLineX(double z) {
        return config.originExitX() + rawMainLineWarp(z) - rawMainLineWarp(0.0);
    }

    private double rawMainLineWarp(double z) {
        return signedValueNoise1d(z * config.mainLinePrimaryWarpScale(), seed ^ 0x4D41494E4C494E45L)
                * config.mainLinePrimaryWarpStrength()
                + signedValueNoise1d(z * config.mainLineSecondaryWarpScale(), seed ^ 0x5241494E424F574CL)
                * config.mainLineSecondaryWarpStrength();
    }

    private static RegionBand edgeRegion() {
        return new RegionBand(CakeRegion.EDGE_REGION, 0.0, 1.0);
    }

    private static double normalized(double value, double length) {
        if (length <= 0.0) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, value / length));
    }

    private static double signedValueNoise1d(double x, long salt) {
        long left = fastFloor(x);
        long right = left + 1L;
        double t = smooth(x - left);
        double a = unitNoise(left, salt);
        double b = unitNoise(right, salt);
        return lerp(t, a, b) * 2.0 - 1.0;
    }

    private static long fastFloor(double value) {
        long floor = (long) value;
        return value < floor ? floor - 1L : floor;
    }

    private static double smooth(double t) {
        return t * t * (3.0 - 2.0 * t);
    }

    private static double lerp(double t, double a, double b) {
        return a + (b - a) * t;
    }

    private static double unitNoise(long x, long salt) {
        long h = x ^ salt;
        h ^= h >>> 33;
        h *= 0xff51afd7ed558ccdL;
        h ^= h >>> 33;
        h *= 0xc4ceb9fe1a85ec53L;
        h ^= h >>> 33;
        return (h >>> 11) * 0x1.0p-53;
    }

    public enum CakeRegion {
        // 规划序列外的封闭边界，生成阶段以 bedrock 填充。
        EDGE_REGION,
        ORIGIN,
        TRANSITION_TUNNEL,
        RUINS,
        SNOWDIN,
        WATERFALL,
        HOT_LAND
    }

    public record CakeSample(
            CakeRegion region,
            double lineProgress,
            double lineY,
            double lateralDistance,
            double localRegionProgress,
            double regionPresence
    ) {}

    public record CakePipelines(
            CakePipeline edgeRegion,
            CakePipeline origin,
            CakePipeline transitionTunnel,
            CakePipeline ruins,
            CakePipeline snowdin,
            CakePipeline waterfall,
            CakePipeline hotLand
    ) {}

    @FunctionalInterface
    public interface CakePipeline {
        void run(CakeSample sample);
    }

    private record RegionBand(CakeRegion region, double localProgress, double regionPresence) {}
}
