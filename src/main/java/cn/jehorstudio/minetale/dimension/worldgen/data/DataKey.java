package cn.jehorstudio.minetale.dimension.worldgen.data;

import java.util.Objects;

public record DataKey<T>(String id, Class<T> type) {
    public DataKey {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
    }

    public static <T> DataKey<T> of(String id, Class<T> type) {
        return new DataKey<>(id, type);
    }
}
