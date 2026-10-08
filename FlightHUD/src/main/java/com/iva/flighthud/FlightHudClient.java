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
        KeyMapping aeroKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.flighthud.aero", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_O, category));
        KeyMapping levelKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.flighthud.level", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_X, category));
        KeyMapping thermalKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.flighthud.thermal", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_N, category));
        KeyMapping zoomKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.flighthud.zoom", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_Z, category));
        Nav.register();
        hideVanillaElements();
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            while (toggle.consumeClick()) enabled = !enabled;
            while (mode.consumeClick()) Gunship.toggle(mc);
            while (thermalKey.consumeClick()) {
                Gunship.thermal = !Gunship.thermal;
                if (!Gunship.active) Gunship.msg(mc, "Тепловизор работает в режиме AC-130 (клавиша I)", net.minecraft.ChatFormatting.GRAY);
            }
            while (zoomKey.consumeClick()) if (Gunship.active) Gunship.zoomIndex = (Gunship.zoomIndex + 1) % 4;
            while (aeroKey.consumeClick()) {
                com.iva.flighthud.aero.Aero.enabled = !com.iva.flighthud.aero.Aero.enabled;
                mc.gui.setOverlayMessage(net.minecraft.network.chat.Component.literal(
                        "Управление самолётом: " + (com.iva.flighthud.aero.Aero.enabled ? "ВКЛ" : "ВЫКЛ")), false);
            }
            // Удерживание X плавно выравнивает крылья
            if (levelKey.isDown() && com.iva.flighthud.aero.Aero.active(mc.player)) {
                double step = Math.max(-8, Math.min(8, -com.iva.flighthud.aero.Aero.roll));
                com.iva.flighthud.aero.Aero.rotate(mc.player, 0, step, 0);
            }
            com.iva.flighthud.aero.Aero.tick(mc);
            FlightData.tick(mc);
            Gunship.tick(mc);
            HotbarManager.tick(mc);
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
