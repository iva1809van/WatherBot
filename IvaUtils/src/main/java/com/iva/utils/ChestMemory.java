package com.iva.utils;

import com.google.gson.reflect.TypeToken;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Память сундуков: запоминает содержимое каждого открытого контейнера
 * (отдельно для каждого сервера/мира и измерения). Команда /найти <предмет>
 * подсвечивает сундуки с этим предметом на 30 секунд.
 */
final class ChestMemory {
    /** Сохранённый предмет: id, отображаемое имя, количество. */
    record Entry(String id, String name, int count) {}

    private static final long HIGHLIGHT_MS = 30_000;
    private static final int SEARCH_RADIUS = 128;

    /** "измерение|x,y,z" -> содержимое */
    private static Map<String, List<Entry>> chests = new HashMap<>();
    private static Path file;
    private static BlockPos lastClicked;
    private static final List<BlockPos> highlighted = new ArrayList<>();
    private static long highlightUntil;

    private ChestMemory() {}

    static void init() {
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (level.isClientSide()) lastClicked = hit.getBlockPos();
            return InteractionResult.PASS;
        });

        ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> load(mc));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> {
            save();
            chests = new HashMap<>();
            file = null;
        });

        ScreenEvents.AFTER_INIT.register((mc, screen, w, h) -> {
            if (!(screen instanceof AbstractContainerScreen<?> cs) || lastClicked == null || mc.level == null) return;
            BlockPos pos = lastClicked;
            if (!(mc.level.getBlockEntity(pos) instanceof Container)) return;
            // Содержимое приходит пакетом чуть позже открытия — читаем при закрытии окна
            ScreenEvents.remove(screen).register(s -> remember(mc, pos, cs));
        });

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, ctx) -> {
            for (String name : new String[]{"найти", "chestfind"}) {
                dispatcher.register(ClientCommands.literal(name)
                        .then(ClientCommands.argument("предмет", StringArgumentType.greedyString())
                                .executes(c -> {
                                    search(Minecraft.getInstance(), StringArgumentType.getString(c, "предмет"));
                                    return 1;
                                })));
            }
        });
    }

    private static String key(Minecraft mc, BlockPos pos) {
        return mc.level.dimension().toString() + "|" + pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    private static void remember(Minecraft mc, BlockPos pos, AbstractContainerScreen<?> screen) {
        if (mc.level == null) return;
        Map<String, Entry> merged = new LinkedHashMap<>();
        for (Slot slot : screen.getMenu().slots) {
            if (slot.container instanceof Inventory) continue; // инвентарь игрока
            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) continue;
            String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            String name = stack.getHoverName().getString();
            merged.merge(id + "|" + name, new Entry(id, name, stack.getCount()),
                    (x, y) -> new Entry(x.id(), x.name(), x.count() + y.count()));
        }
        String k = key(mc, pos);
        if (merged.isEmpty()) chests.remove(k);
        else chests.put(k, new ArrayList<>(merged.values()));
        save();
    }

    private static void search(Minecraft mc, String query) {
        if (mc.level == null || mc.player == null) return;
        String q = query.toLowerCase(Locale.ROOT).trim();
        String dim = mc.level.dimension().toString() + "|";
        highlighted.clear();
        List<Component> lines = new ArrayList<>();
        int total = 0;
        for (var e : chests.entrySet()) {
            if (!e.getKey().startsWith(dim)) continue;
            String[] xyz = e.getKey().substring(dim.length()).split(",");
            BlockPos pos = new BlockPos(Integer.parseInt(xyz[0]), Integer.parseInt(xyz[1]), Integer.parseInt(xyz[2]));
            if (pos.distSqr(mc.player.blockPosition()) > (double) SEARCH_RADIUS * SEARCH_RADIUS) continue;
            int count = 0;
            for (Entry it : e.getValue()) {
                if (it.name().toLowerCase(Locale.ROOT).contains(q) || it.id().toLowerCase(Locale.ROOT).contains(q)) {
                    count += it.count();
                }
            }
            if (count == 0) continue;
            highlighted.add(pos);
            total += count;
            lines.add(Component.literal("  " + pos.getX() + " " + pos.getY() + " " + pos.getZ() + " — " + count + " шт.")
                    .withStyle(ChatFormatting.GRAY));
        }
        highlightUntil = System.currentTimeMillis() + HIGHLIGHT_MS;
        if (highlighted.isEmpty()) {
            mc.player.sendSystemMessage(Component.literal("«" + query + "» не найдено в запомненных сундуках рядом")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        mc.player.sendSystemMessage(Component.literal("«" + query + "»: " + total + " шт. в " + highlighted.size()
                + " конт. (подсвечены на 30 с)").withStyle(ChatFormatting.GREEN));
        lines.forEach(mc.player::sendSystemMessage);
    }

    static void draw(Minecraft mc) {
        if (highlighted.isEmpty()) return;
        if (System.currentTimeMillis() > highlightUntil) { highlighted.clear(); return; }
        for (BlockPos p : highlighted) {
            Gizmos.cuboid(p, GizmoStyle.stroke(0xFF55FF55, 3f)).setAlwaysOnTop();
        }
    }

    private static void load(Minecraft mc) {
        String server;
        if (mc.getSingleplayerServer() != null) {
            server = "sp_" + mc.getSingleplayerServer().getWorldData().getLevelName();
        } else if (mc.getCurrentServer() != null) {
            server = "mp_" + mc.getCurrentServer().ip;
        } else {
            server = "unknown";
        }
        file = Storage.DIR.resolve("chests").resolve(server.replaceAll("[^a-zA-Zа-яА-Я0-9._-]", "_") + ".json");
        chests = Storage.load(file, new TypeToken<HashMap<String, List<Entry>>>() {}.getType(), HashMap::new);
    }

    private static void save() {
        if (file != null) Storage.save(file, chests);
    }
}
