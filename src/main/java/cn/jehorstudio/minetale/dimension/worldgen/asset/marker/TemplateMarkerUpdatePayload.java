package cn.jehorstudio.minetale.dimension.worldgen.asset.marker;

import cn.jehorstudio.minetale.MineTale;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record TemplateMarkerUpdatePayload(
        BlockPos pos,
        TemplateMarkerData data,
        Direction facing,
        MarkerVisual visual
) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<TemplateMarkerUpdatePayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(MineTale.MODID, "template_marker_update"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TemplateMarkerUpdatePayload> STREAM_CODEC =
            StreamCodec.ofMember(TemplateMarkerUpdatePayload::write, TemplateMarkerUpdatePayload::read);

    private void write(RegistryFriendlyByteBuf buf) {
        buf.writeBlockPos(this.pos);
        this.data.writeToBuffer(buf);
        buf.writeEnum(this.facing);
        buf.writeUtf(this.visual.getSerializedName());
    }

    private static TemplateMarkerUpdatePayload read(RegistryFriendlyByteBuf buf) {
        return new TemplateMarkerUpdatePayload(
                buf.readBlockPos(),
                TemplateMarkerData.readFromBuffer(buf),
                buf.readEnum(Direction.class),
                MarkerVisual.byName(buf.readUtf())
        );
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(TemplateMarkerUpdatePayload payload, IPayloadContext context) {
        Player player = context.player();
        if (player == null) {
            return;
        }
        Level level = player.level();
        if (level.isClientSide()) {
            return;
        }
        if (!TemplateMarkerBlock.canEdit(player)) {
            player.displayClientMessage(Component.literal("Template Marker save denied: creative mode or operator permission required."), false);
            return;
        }
        double distance = player.distanceToSqr(payload.pos.getX() + 0.5D, payload.pos.getY() + 0.5D, payload.pos.getZ() + 0.5D);
        if (distance > 64.0D) {
            player.displayClientMessage(Component.literal("Template Marker save denied: target is too far away."), false);
            return;
        }

        BlockState oldState = level.getBlockState(payload.pos);
        if (!oldState.is(TemplateMarkerRegistry.TEMPLATE_MARKER.get())) {
            player.displayClientMessage(Component.literal("Template Marker save denied: target block is not minetale:template_marker."), false);
            return;
        }
        BlockEntity blockEntity = level.getBlockEntity(payload.pos);
        if (!(blockEntity instanceof TemplateMarkerBlockEntity markerBlockEntity)) {
            player.displayClientMessage(Component.literal("Template Marker save denied: target block entity is missing."), false);
            return;
        }

        List<String> errors = TemplateMarkerValidation.validateForSave(payload.data);
        if (!errors.isEmpty()) {
            player.displayClientMessage(Component.literal("Template Marker save failed: " + String.join("; ", errors)), false);
            return;
        }

        BlockState newState = oldState
                .setValue(TemplateMarkerBlock.FACING, payload.facing)
                .setValue(TemplateMarkerBlock.VISUAL, payload.visual);
        if (newState != oldState) {
            level.setBlock(payload.pos, newState, Block.UPDATE_CLIENTS | Block.UPDATE_NEIGHBORS);
        }

        BlockEntity updatedEntity = level.getBlockEntity(payload.pos);
        if (updatedEntity instanceof TemplateMarkerBlockEntity updatedMarker) {
            updatedMarker.setData(payload.data);
        } else {
            markerBlockEntity.setData(payload.data);
        }
        level.sendBlockUpdated(payload.pos, oldState, newState, Block.UPDATE_CLIENTS);
        player.displayClientMessage(Component.literal("Template Marker saved successfully."), false);
    }
}
