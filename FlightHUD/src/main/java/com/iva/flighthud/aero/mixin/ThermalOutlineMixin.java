package com.iva.flighthud.aero.mixin;

import com.iva.flighthud.Gunship;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Тепловизор: все живые существа получают ярко-белый силуэт (видно и сквозь блоки). */
@Mixin(EntityRenderer.class)
public abstract class ThermalOutlineMixin {
    @Inject(method = "extractRenderState", at = @At("TAIL"), require = 0)
    private void flighthud$thermal(Entity entity, EntityRenderState state, float partialTick, CallbackInfo ci) {
        if (Gunship.active && Gunship.thermal && entity instanceof LivingEntity
                && entity != Minecraft.getInstance().player) {
            state.outlineColor = 0xFFFFFF;
        }
    }
}
