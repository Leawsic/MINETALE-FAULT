package cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.mixin.client;

import cn.jehorstudio.minetale.MineTale;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.particle.SnowflakeParticle;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SnowflakeParticle.class)
public abstract class SnowflakeParticleMixin extends SingleQuadParticle {
    private static final ResourceKey<Biome> MINETALE$SNOWDIN = ResourceKey.create(
            Registries.BIOME,
            ResourceLocation.fromNamespaceAndPath(MineTale.MODID, "snowdin")
    );

    protected SnowflakeParticleMixin(
            ClientLevel level,
            double x,
            double y,
            double z,
            TextureAtlasSprite sprite
    ) {
        super(level, x, y, z, sprite);
    }

    @Inject(method = "<init>", at = @At("RETURN"), require = 0, expect = 1)
    private void minetale$skipSnowdinCollision(
            ClientLevel level,
            double x,
            double y,
            double z,
            double xSpeed,
            double ySpeed,
            double zSpeed,
            SpriteSet sprites,
            CallbackInfo callbackInfo
    ) {
        if (level.getBiome(BlockPos.containing(x, y, z)).is(MINETALE$SNOWDIN)) {
            this.hasPhysics = false;
        }
    }
}
