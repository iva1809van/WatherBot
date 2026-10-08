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
        if (com.iva.flighthud.Gunship.active) {
            // Камера оператора: обычный поворот, чувствительность падает с зумом
            ci.cancel();
            double z = com.iva.flighthud.Gunship.zoom();
            float dy = (float) (yRot * 0.15 / z), dx = (float) (xRot * 0.15 / z);
            float nx = Math.max(-90, Math.min(90, p.getXRot() + dx));
            p.setYRot(p.getYRot() + dy);
            p.yRotO += dy;
            p.xRotO += nx - p.getXRot();
            p.setXRot(nx);
            return;
        }
        if (!Aero.active(p)) return;
        ci.cancel();
        // Ванильный turn умножает на 0.15 — сохраняем тот же масштаб
        Aero.rotate(p, xRot * 0.15, yRot * 0.15 * Aero.ROLL_SENS, 0);
    }
}
