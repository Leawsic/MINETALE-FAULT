package cn.jehorstudio.minetale.dimension.worldgen.asset.marker;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

public class TemplateMarkerBlockEntity extends BlockEntity {
    private TemplateMarkerData data = TemplateMarkerData.defaults();

    public TemplateMarkerBlockEntity(BlockPos pos, BlockState state) {
        super(TemplateMarkerRegistry.TEMPLATE_MARKER_BLOCK_ENTITY.get(), pos, state);
    }

    public TemplateMarkerData getData() {
        return this.data;
    }

    public void setData(TemplateMarkerData data) {
        this.data = data == null ? TemplateMarkerData.defaults() : data.copy();
        this.setChanged();
    }

    public void setDataAndSync(TemplateMarkerData data) {
        this.setData(data);
        this.syncToClient();
    }

    public void syncToClient() {
        Level level = this.getLevel();
        if (level == null || level.isClientSide()) {
            return;
        }
        BlockState state = this.getBlockState();
        level.sendBlockUpdated(this.worldPosition, state, state, Block.UPDATE_CLIENTS);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        this.data = TemplateMarkerData.read(input);
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        this.data.write(output);
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return this.saveCustomOnly(registries);
    }
}
