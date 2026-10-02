package cn.jehorstudio.minetale.dimension.ebott.transition;

import cn.jehorstudio.minetale.MineTale;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.TicketType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

// 换维预热只使用非持久化 Ticket，租约结束后必须释放。
public final class TransitionTicketTypes {
    private static final DeferredRegister<TicketType> TICKET_TYPES =
            DeferredRegister.create(Registries.TICKET_TYPE, MineTale.MODID);

    public static final Holder<TicketType> PREWARM = TICKET_TYPES.register(
            "ebott_prewarm",
            () -> new TicketType(
                    TicketType.NO_TIMEOUT,
                    TicketType.FLAG_LOADING | TicketType.FLAG_KEEP_DIMENSION_ACTIVE
            )
    );

    public static void register(IEventBus modEventBus) {
        TICKET_TYPES.register(modEventBus);
    }

    private TransitionTicketTypes() {}
}
