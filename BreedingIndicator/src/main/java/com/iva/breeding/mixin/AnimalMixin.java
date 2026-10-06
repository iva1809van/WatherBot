package com.iva.breeding.mixin;

import com.iva.breeding.BreedingTracker;
import net.minecraft.world.entity.animal.Animal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Animal.class)
public abstract class AnimalMixin {
    /** Событие 18 — животное вошло в режим любви (сердечки). */
    @Inject(method = "handleEntityEvent", at = @At("HEAD"))
    private void breedingindicator$onEvent(byte id, CallbackInfo ci) {
        Animal self = (Animal) (Object) this;
        if (id == 18 && self.level().isClientSide()) BreedingTracker.onLove(self);
    }
}
