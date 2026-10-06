package com.iva.utils;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Тихий чат: скрывает сообщения со словами из чёрного списка и повторы
 * (одинаковое сообщение чаще раза в 10 секунд), а при упоминании вашего ника
 * или слов из списка подсветки — звук и надпись над хотбаром.
 */
final class ChatFilter {
    static final class Config {
        List<String> highlight = new ArrayList<>();
        List<String> block = new ArrayList<>();
        int repeatSeconds = 10;
        boolean pingOnName = true;
    }

    private static final Path FILE = Storage.DIR.resolve("chat.json");
    private static Config config = new Config();
    private static final Map<String, Long> recent = new HashMap<>();

    private ChatFilter() {}

    static void init() {
        config = Storage.load(FILE, Config.class, Config::new);
        Storage.save(FILE, config);

        ClientReceiveMessageEvents.ALLOW_CHAT.register((message, signed, sender, params, time) -> allow(message));
        ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> overlay || allow(message));
        ClientReceiveMessageEvents.CHAT.register((message, signed, sender, params, time) -> ping(message));
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> { if (!overlay) ping(message); });

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, ctx) -> dispatcher.register(
                ClientCommands.literal("чат")
                        .then(ClientCommands.literal("подсветка").then(ClientCommands.argument("слово", StringArgumentType.greedyString())
                                .executes(c -> edit(config.highlight, StringArgumentType.getString(c, "слово"), true))))
                        .then(ClientCommands.literal("скрыть").then(ClientCommands.argument("слово", StringArgumentType.greedyString())
                                .executes(c -> edit(config.block, StringArgumentType.getString(c, "слово"), true))))
                        .then(ClientCommands.literal("убрать").then(ClientCommands.argument("слово", StringArgumentType.greedyString())
                                .executes(c -> {
                                    String w = StringArgumentType.getString(c, "слово");
                                    edit(config.highlight, w, false);
                                    return edit(config.block, w, false);
                                })))
                        .then(ClientCommands.literal("список").executes(c -> {
                            say(Component.literal("Подсветка: " + config.highlight).withStyle(ChatFormatting.YELLOW));
                            say(Component.literal("Скрывать: " + config.block).withStyle(ChatFormatting.RED));
                            return 1;
                        }))));
    }

    private static boolean allow(Component message) {
        String text = message.getString();
        String lower = text.toLowerCase(Locale.ROOT);
        for (String w : config.block) {
            if (!w.isBlank() && lower.contains(w.toLowerCase(Locale.ROOT))) return false;
        }
        long now = System.currentTimeMillis();
        recent.values().removeIf(t -> now - t > config.repeatSeconds * 1000L);
        if (config.repeatSeconds > 0 && recent.containsKey(text)) return false;
        recent.put(text, now);
        return true;
    }

    private static void ping(Component message) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        String lower = message.getString().toLowerCase(Locale.ROOT);
        boolean hit = config.pingOnName && lower.contains(mc.player.getName().getString().toLowerCase(Locale.ROOT));
        for (String w : config.highlight) {
            if (!w.isBlank() && lower.contains(w.toLowerCase(Locale.ROOT))) hit = true;
        }
        if (!hit) return;
        mc.player.playSound(SoundEvents.EXPERIENCE_ORB_PICKUP, 0.7f, 1.4f);
        mc.gui.setOverlayMessage(Component.literal("✉ ").withStyle(ChatFormatting.GOLD).append(message.copy()), false);
    }

    private static int edit(List<String> list, String word, boolean add) {
        word = word.trim();
        if (add && !list.contains(word)) list.add(word);
        if (!add) list.remove(word);
        Storage.save(FILE, config);
        say(Component.literal((add ? "Добавлено: " : "Убрано: ") + word).withStyle(ChatFormatting.GREEN));
        return 1;
    }

    private static void say(Component c) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.sendSystemMessage(c);
    }
}
