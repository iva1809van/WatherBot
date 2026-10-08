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
    /** false — истребитель, true — оператор дрона. */
    static boolean droneMode = false;

    /** Скрываем ванильный интерфейс, пока виден HUD пилота. */
    static boolean hideVanilla() {
        return enabled && FlightData.flying;
    }

    @Override
    public void onInitializeClient() {
        KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("flighthud", "main"));
        KeyMapping toggle = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.flighthud.toggle", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_U, category));
        KeyMapping mode = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.flighthud.mode", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_I, category));
        hideVanillaElements();
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            while (toggle.consumeClick()) enabled = !enabled;
            while (mode.consumeClick()) droneMode = !droneMode;
            FlightData.tick(mc);
        });
        HudElementRegistry.addFirst(Identifier.fromNamespaceAndPath("flighthud", "overlay"), FlightHud::renderOverlay);
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("flighthud", "hud"), FlightHud::render);
    }

    /**
     * Прицел, хотбар, здоровье, голод, броня, опыт и т.п. прячем во время полёта.
     * Список берётся из констант Fabric по названию, чтобы не зависеть от точного набора.
     */
    private static void hideVanillaElements() {
        String[] hide = {"CROSSHAIR", "HOTBAR", "ARMOR", "HEALTH", "FOOD", "EXPERIENCE", "AIR", "MOUNT", "HELD_ITEM", "INFO_BAR"};
        for (var f : net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements.class.getFields()) {
            if (f.getType() != Identifier.class) continue;
            boolean match = false;
            for (String h : hide) if (f.getName().contains(h)) match = true;
            if (!match) continue;
            try {
                Identifier id = (Identifier) f.get(null);
                HudElementRegistry.replaceElement(id, old -> (g, d) -> {
                    if (!hideVanilla()) old.extractRenderState(g, d);
                });
            } catch (Exception ignored) {
            }
        }
    }
}
