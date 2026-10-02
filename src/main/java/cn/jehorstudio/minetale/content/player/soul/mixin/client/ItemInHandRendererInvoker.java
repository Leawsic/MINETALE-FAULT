package cn.jehorstudio.minetale.content.player.soul.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

// 复用原版空手提交路径
@Mixin(ItemInHandRenderer.class)
public interface ItemInHandRendererInvoker {
    @Invoker("renderArmWithItem")
    void minetale$renderArmWithItem(
            AbstractClientPlayer player,
            float partialTick,
            float pitch,
            InteractionHand hand,
            float swingProgress,
            ItemStack item,
            float equippedProgress,
            PoseStack poseStack,
            SubmitNodeCollector nodeCollector,
            int packedLight
    );
}
