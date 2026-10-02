package cn.jehorstudio.minetale.content.player.soul;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

final class SoulPresentationAnchors {
    private static final double CHEST_FORWARD_OFFSET = 0.18;
    private static final double CHEST_EYE_OFFSET = -0.48;

    private SoulPresentationAnchors() {
    }

    static Vec3 chestForward(Player player, float partialTick) {
        float bodyYaw = Mth.rotLerp(partialTick, player.yBodyRotO, player.yBodyRot);
        return Vec3.directionFromRotation(0.0F, bodyYaw).normalize();
    }

    static Vec3 chestAnchor(Player player, float partialTick) {
        return player.getEyePosition(partialTick)
                .add(0.0, CHEST_EYE_OFFSET, 0.0)
                .add(chestForward(player, partialTick).scale(CHEST_FORWARD_OFFSET));
    }
}
