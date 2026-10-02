package cn.jehorstudio.minetale.dimension.worldgen.preview;

public record PreviewField(
        String key,
        String label,
        PreviewFieldKind kind,
        PreviewFieldDimension dimension,
        PreviewRenderHint renderHint,
        PreviewVerticalDirection verticalDirection,
        double min,
        double max
) {}
