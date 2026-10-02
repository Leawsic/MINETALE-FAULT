package cn.jehorstudio.minetale.dimension.ebott.mountain;

import cn.jehorstudio.minetale.MineTale;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import java.util.function.Supplier;

public final class EbottMountainAttachments {
    private static final DeferredRegister<AttachmentType<?>> ATTACHMENTS =
            DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, MineTale.MODID);

    public static final Supplier<AttachmentType<SurfaceSnapshot>> SURFACE_SNAPSHOT =
            ATTACHMENTS.register(
                    "ebott_surface_snapshot",
                    () -> AttachmentType.<SurfaceSnapshot>builder(() -> {
                                throw new IllegalStateException("Ebott surface snapshot has no default value");
                            })
                            .serialize(SurfaceSnapshot.CODEC)
                            .build()
            );

    public static void register(IEventBus modEventBus) {
        ATTACHMENTS.register(modEventBus);
    }

    private EbottMountainAttachments() {}
}
