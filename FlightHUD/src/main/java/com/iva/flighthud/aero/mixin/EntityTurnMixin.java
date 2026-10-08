package com.iva.flighthud.aero.mixin;

import com.iva.flighthud.aero.Aero;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Мышь в полёте: X — крен, Y — тангаж относительно самолёта. */
@Mixin(Entity.class)
public abstract class EntityTurnMixin {
    @Inject(method = "turn", at = @At("HEAD"), cancellable = true)
    private void aero$turn(double yRot, double xRot, CallbackInfo ci) {
        if (!((Object) this instanceof LocalPlayer p) || p != Minecraft.getInstance().player) return;
        if (!Aero.active(p)) return;
        ci.cancel();
        // Ванильный turn умножает на 0.15 — сохраняем тот же масштаб
        Aero.rotate(p, xRot * 0.15, yRot * 0.15 * Aero.ROLL_SENS, 0);
    }
}
