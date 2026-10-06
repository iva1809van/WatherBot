package com.iva.utils;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

public class IvaUtilsClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("ivautils", "main"));
        KeyMapping gridKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.ivautils.grid", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_Y, category));
        KeyMapping rulerKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.ivautils.ruler", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_R, category));

        ChestMemory.init();
        ChatFilter.init();

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            while (gridKey.consumeClick()) Grid.toggle(mc);
            while (rulerKey.consumeClick()) Ruler.click(mc);
            if (mc.level == null || mc.player == null) return;
            // Все линии рисуются через встроенные «гизмо» игры, живут до следующего тика
            try (var ignored = mc.collectPerTickGizmos()) {
                Grid.draw(mc);
                Ruler.draw(mc);
                ChestMemory.draw(mc);
            }
        });

        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("ivautils", "hud"), (g, delta) -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.options.hideGui || mc.player == null) return;
            int y = mc.getWindow().getGuiScaledHeight() / 2 + 14;
            int cx = mc.getWindow().getGuiScaledWidth() / 2;
            for (String line : Ruler.hudLines()) {
                g.centeredText(mc.font, line, cx, y, 0xFFFFFFFF);
                y += 10;
            }
        });
    }
}
