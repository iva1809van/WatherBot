package com.iva.flighthud;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.minecraft.network.chat.Component;

/**
 * Навигация и заданные значения:
 * /hud точка X Z [имя] — путевая точка, /hud точка сброс
 * /hud скорость N, /hud высота N — заданные скорость и высота (0 — сброс).
 */
final class Nav {
    static Integer wpX, wpZ;
    static String wpName = "WPT";
    static int targetSpeed = 0;
    static int targetAlt = 0;

    private Nav() {}

    static boolean hasWaypoint() { return wpX != null; }

    static void register() {
        ClientCommandRegistrationCallback.EVENT.register((d, ctx) -> d.register(ClientCommands.literal("hud")
                .then(ClientCommands.literal("точка")
                        .then(ClientCommands.literal("сброс").executes(c -> {
                            wpX = wpZ = null;
                            c.getSource().sendFeedback(Component.literal("Путевая точка сброшена"));
                            return 1;
                        }))
                        .then(ClientCommands.argument("x", IntegerArgumentType.integer())
                                .then(ClientCommands.argument("z", IntegerArgumentType.integer())
                                        .executes(c -> setWp(c.getSource(), IntegerArgumentType.getInteger(c, "x"),
                                                IntegerArgumentType.getInteger(c, "z"), "WPT"))
                                        .then(ClientCommands.argument("имя", StringArgumentType.greedyString())
                                                .executes(c -> setWp(c.getSource(), IntegerArgumentType.getInteger(c, "x"),
                                                        IntegerArgumentType.getInteger(c, "z"), StringArgumentType.getString(c, "имя")))))))
                .then(ClientCommands.literal("скорость").then(ClientCommands.argument("n", IntegerArgumentType.integer(0, 200))
                        .executes(c -> {
                            targetSpeed = IntegerArgumentType.getInteger(c, "n");
                            c.getSource().sendFeedback(Component.literal("Заданная скорость: " + (targetSpeed == 0 ? "нет" : targetSpeed + " б/с")));
                            return 1;
                        })))
                .then(ClientCommands.literal("высота").then(ClientCommands.argument("n", IntegerArgumentType.integer(-64, 2000))
                        .executes(c -> {
                            targetAlt = IntegerArgumentType.getInteger(c, "n");
                            c.getSource().sendFeedback(Component.literal("Заданная высота: " + (targetAlt == 0 ? "нет" : "Y " + targetAlt)));
                            return 1;
                        })))));
    }

    private static int setWp(net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource src, int x, int z, String name) {
        wpX = x;
        wpZ = z;
        wpName = name.length() > 12 ? name.substring(0, 12) : name;
        src.sendFeedback(Component.literal("Путевая точка «" + wpName + "»: " + x + " " + z));
        return 1;
    }
}
