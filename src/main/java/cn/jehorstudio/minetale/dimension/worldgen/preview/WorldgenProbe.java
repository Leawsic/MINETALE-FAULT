package cn.jehorstudio.minetale.dimension.worldgen.preview;

import java.util.List;

public interface WorldgenProbe {
    String group();

    List<PreviewField> fields();

    double sample(PreviewField field, double x, double z, PreviewSampleContext context);

    default double sample(PreviewField field, double x, double y, double z, PreviewSampleContext context) {
        return sample(field, x, z, context);
    }

    default boolean solid(PreviewField field, double x, double y, double z, PreviewSampleContext context, double value) {
        return switch (field.kind()) {
            case SIGNED_NOISE -> value >= 0.0;
            case MASK, UNIT_NOISE -> value >= 0.5;
            default -> value >= 0.0;
        };
    }
}
