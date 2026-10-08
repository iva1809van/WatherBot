package com.iva.aero.mixin;

import com.iva.aero.Aero;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Наклоняем камеру на угол крена. */
@Mixin(Camera.class)
public abstract class CameraMixin {
    @Inject(method = "setRotation(FF)V", at = @At("TAIL"))
    private void aero$roll(float yRot, float xRot, CallbackInfo ci) {
        Camera self = (Camera) (Object) this;
        if (self.entity() != Minecraft.getInstance().player || !Aero.active(Minecraft.getInstance().player)) return;
        if (Aero.roll == 0) return;
        // +крен (правое крыло вниз) = поворот камеры по часовой вокруг взгляда
        self.rotation().rotateZ((float) Math.toRadians(-Aero.roll * Aero.CAMERA_SIGN));
    }
}
