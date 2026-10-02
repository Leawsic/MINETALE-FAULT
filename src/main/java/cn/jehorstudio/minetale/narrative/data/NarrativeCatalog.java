package cn.jehorstudio.minetale.narrative.data;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

public final class NarrativeCatalog {
    private static final AtomicReference<NarrativeSnapshot> CURRENT =
            new AtomicReference<>(NarrativeSnapshot.EMPTY);

    private NarrativeCatalog() {
    }

    public static NarrativeSnapshot current() {
        return CURRENT.get();
    }

    public static void publish(NarrativeSnapshot snapshot) {
        CURRENT.set(Objects.requireNonNull(snapshot, "snapshot"));
    }
}
