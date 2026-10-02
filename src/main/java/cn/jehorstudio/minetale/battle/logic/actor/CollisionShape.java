package cn.jehorstudio.minetale.battle.logic.actor;

import java.util.List;
import java.util.Objects;

public record CollisionShape(
        CollisionShapeType type,
        List<CollisionBox> boxes
) {
    public static final CollisionShape NONE = new CollisionShape(CollisionShapeType.NONE, List.of());
    public static final CollisionShape POINT = CollisionShape.obb(CollisionBox.POINT);

    public CollisionShape {
        Objects.requireNonNull(type, "type");
        boxes = List.copyOf(Objects.requireNonNull(boxes, "boxes"));
        if (type == CollisionShapeType.NONE && !boxes.isEmpty()) {
            throw new IllegalArgumentException("NONE shape must not contain boxes.");
        }
        if (type == CollisionShapeType.OBB && boxes.size() != 1) {
            throw new IllegalArgumentException("OBB shape must contain exactly one box.");
        }
        if (type == CollisionShapeType.OBB_GROUP && boxes.isEmpty()) {
            throw new IllegalArgumentException("OBB_GROUP shape must contain at least one box.");
        }
    }

    public static CollisionShape obb(CollisionBox box) {
        return new CollisionShape(CollisionShapeType.OBB, List.of(box));
    }

    public static CollisionShape none() {
        return NONE;
    }

    public static CollisionShape obbGroup(List<CollisionBox> boxes) {
        return new CollisionShape(CollisionShapeType.OBB_GROUP, boxes);
    }
}
