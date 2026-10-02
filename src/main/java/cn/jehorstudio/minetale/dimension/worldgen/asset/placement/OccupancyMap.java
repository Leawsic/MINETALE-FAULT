package cn.jehorstudio.minetale.dimension.worldgen.asset.placement;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

public final class OccupancyMap {
    private final List<Entry> entries = new ArrayList<>();

    public void add(BoundingBox box) {
        this.add("", box);
    }

    public void add(String id, BoundingBox box) {
        if (box == null) {
            return;
        }
        this.entries.add(new Entry(id == null ? "" : id, box));
    }

    public boolean collides(BoundingBox box) {
        if (box == null) {
            return false;
        }
        // BoundingBox 两端均计入占用，边界接触也按冲突处理。
        return this.entries.stream().anyMatch(entry -> entry.box().intersects(box));
    }

    public List<BoundingBox> boxes() {
        return this.entries.stream().map(Entry::box).toList();
    }

    public List<Entry> entries() {
        return List.copyOf(this.entries);
    }

    public void clear() {
        this.entries.clear();
    }

    public record Entry(String id, BoundingBox box) {
    }
}