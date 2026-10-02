package cn.jehorstudio.minetale.narrative;

import cn.jehorstudio.minetale.MineTale;
import net.minecraft.nbt.CompoundTag;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import java.util.function.Supplier;

    // 玩家叙事状态以单一 Attachment 作为持久化根。
public final class NarrativeAttachments {
    private static final DeferredRegister<AttachmentType<?>> ATTACHMENTS =
            DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, MineTale.MODID);

    public static final Supplier<AttachmentType<CompoundTag>> PLAYER_DATA =
            ATTACHMENTS.register(
                    "narrative_player_data",
                    () -> AttachmentType.builder((Supplier<CompoundTag>) CompoundTag::new)
                            .serialize(CompoundTag.CODEC.fieldOf("data"))
                            .copyOnDeath()
                            .build()
            );

    public static void register(IEventBus modEventBus) {
        ATTACHMENTS.register(modEventBus);
    }

    private NarrativeAttachments() {}
}
