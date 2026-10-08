package com.iva.flighthud.aero.mixin;

import com.iva.flighthud.Gunship;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** В режиме AC-130 предмет в руке не рисуется — чистый вид камеры оператора. */
@Mixin(GameRenderer.class)
public abstract class HideHandMixin {
    @Inject(method = "renderItemInHand", at = @At("HEAD"), cancellable = true, require = 0)
    private void flighthud$hideHand(CallbackInfo ci) {
        if (Gunship.active) ci.cancel();
    }
}
