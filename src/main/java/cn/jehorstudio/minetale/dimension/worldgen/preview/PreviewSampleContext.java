package cn.jehorstudio.minetale.dimension.worldgen.preview;

import java.util.Map;

public record PreviewSampleContext(
        long seed,
        double centerX,
        double centerY,
        double centerZ,
        double size,
        int resolution,
        Map<String, Double> settingOverrides
) {}
