package com.iva.flighthud;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

public class FlightHudClient implements ClientModInitializer {
    static boolean enabled = true;

    @Override
    public void onInitializeClient() {
        KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("flighthud", "main"));
        KeyMapping toggle = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.flighthud.toggle", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_U, category));
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            while (toggle.consumeClick()) enabled = !enabled;
            FlightData.tick(mc);
        });
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("flighthud", "hud"), FlightHud::render);
    }
}
