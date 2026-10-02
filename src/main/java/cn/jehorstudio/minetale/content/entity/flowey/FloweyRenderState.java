package cn.jehorstudio.minetale.content.entity.flowey;

import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import org.jetbrains.annotations.Nullable;
import software.bernie.geckolib.constant.dataticket.DataTicket;
import software.bernie.geckolib.renderer.base.GeoRenderState;

import java.util.HashMap;
import java.util.Map;

public final class FloweyRenderState extends LivingEntityRenderState implements GeoRenderState {
    private final Map<DataTicket<?>, Object> geckolibData = new HashMap<>();

    float faceX;
    float faceYaw;
    float stemYaw;

    @Override
    public <D> void addGeckolibData(DataTicket<D> dataTicket, @Nullable D data) {
        this.geckolibData.put(dataTicket, data);
    }

    @Override
    public boolean hasGeckolibData(DataTicket<?> dataTicket) {
        return this.geckolibData.containsKey(dataTicket);
    }

    @SuppressWarnings("unchecked")
    @Nullable
    @Override
    public <D> D getGeckolibData(DataTicket<D> dataTicket) {
        if (!this.geckolibData.containsKey(dataTicket)) {
            throw new IllegalArgumentException("Missing GeckoLib render data: " + dataTicket);
        }
        return (D)this.geckolibData.get(dataTicket);
    }

    @Override
    public Map<DataTicket<?>, Object> getDataMap() {
        return this.geckolibData;
    }
}
