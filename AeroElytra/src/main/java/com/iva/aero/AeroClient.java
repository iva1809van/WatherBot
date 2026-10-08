package com.iva.aero;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

public class AeroClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("aeroelytra", "main"));
        KeyMapping toggle = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.aeroelytra.toggle", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_O, category));
        KeyMapping level = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.aeroelytra.level", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_X, category));

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            while (toggle.consumeClick()) {
                Aero.enabled = !Aero.enabled;
                mc.gui.setOverlayMessage(Component.literal("Управление самолётом: " + (Aero.enabled ? "ВКЛ" : "ВЫКЛ"))
                        .withStyle(Aero.enabled ? ChatFormatting.GREEN : ChatFormatting.GRAY), false);
            }
            // Удерживание X плавно выравнивает крылья
            if (level.isDown() && Aero.active(mc.player)) {
                double step = Math.max(-8, Math.min(8, -Aero.roll));
                Aero.rotate(mc.player, 0, step, 0);
            }
            Aero.tick(mc);
        });
    }
}
