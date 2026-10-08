package com.iva.flighthud.aero.mixin;

import com.iva.flighthud.Gunship;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Зум камеры оператора. */
@Mixin(GameRenderer.class)
public abstract class FovMixin {
    @Inject(method = "calculateFov", at = @At("RETURN"), cancellable = true)
    private void flighthud$zoom(float partial, CallbackInfoReturnable<Float> cir) {
        if (Gunship.active) cir.setReturnValue((float) (cir.getReturnValue() / Gunship.zoom()));
    }
}
