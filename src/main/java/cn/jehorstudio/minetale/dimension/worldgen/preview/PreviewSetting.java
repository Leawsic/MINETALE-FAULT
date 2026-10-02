package cn.jehorstudio.minetale.dimension.worldgen.preview;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface PreviewSetting {
    String group();

    String id();

    double min();

    double max();

    double step();

    PreviewSettingKind kind();
}
