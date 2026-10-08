package com.iva.flighthud.aero.mixin;

import com.iva.flighthud.Gunship;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** В режиме AC-130 скорость задаёт автопилот орбиты, а не направление взгляда. */
@Mixin(LivingEntity.class)
public abstract class FallFlyingMixin {
    @Inject(method = "updateFallFlyingMovement", at = @At("RETURN"), cancellable = true, require = 0)
    private void flighthud$orbit(Vec3 in, CallbackInfoReturnable<Vec3> cir) {
        if (Gunship.active && (Object) this == Minecraft.getInstance().player) {
            cir.setReturnValue(Gunship.orbitVelocity(Minecraft.getInstance().player));
        }
    }
}
