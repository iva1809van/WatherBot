package com.iva.breeding.mixin;

import com.iva.breeding.BreedingIndicatorClient;
import com.iva.breeding.BreedingTracker;
import net.minecraft.ChatFormatting;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin {
    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void breedingindicator$extract(Entity entity, EntityRenderState state, float partialTick, CallbackInfo ci) {
        if (!(entity instanceof Animal)) return;

        if (BreedingTracker.onCooldown(entity)) {
            MutableComponent mark = Component.literal("!").withStyle(ChatFormatting.RED, ChatFormatting.BOLD);
            state.nameTag = state.nameTag == null ? mark
                    : Component.empty().append(state.nameTag).append(" ").append(mark);
            if (state.nameTagAttachment == null) {
                state.nameTagAttachment = new Vec3(0, entity.getBbHeight() + 0.5, 0);
            }
        }

        if (BreedingIndicatorClient.isHighlighted(entity)) {
            state.outlineColor = BreedingIndicatorClient.HIGHLIGHT_COLOR;
        }
    }
}
