package com.iva.locatorcolor;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Shift + ПКМ в воздух компасом с названием «Локатор» открывает диалог выбора цвета.
 * Цвет ставится на точку игрока в панели локатора (команда /waypoint)
 * и показывается кружком перед ником в списке игроков (Tab).
 */
public final class LocatorColorPlugin extends JavaPlugin implements Listener {

    private static final Pattern HEX = Pattern.compile("#?[0-9a-fA-F]{6}");
    private static final Map<String, Integer> PRESETS = new LinkedHashMap<>();
    static {
        PRESETS.put("Красный", 0xFF5555);
        PRESETS.put("Оранжевый", 0xFFAA00);
        PRESETS.put("Жёлтый", 0xFFFF55);
        PRESETS.put("Зелёный", 0x55FF55);
        PRESETS.put("Бирюзовый", 0x55FFFF);
        PRESETS.put("Синий", 0x5555FF);
        PRESETS.put("Фиолетовый", 0xAA00AA);
        PRESETS.put("Розовый", 0xFF55FF);
        PRESETS.put("Белый", 0xFFFFFF);
        PRESETS.put("Серый", 0xAAAAAA);
        PRESETS.put("Чёрный", 0x202020);
        PRESETS.put("Коричневый", 0x8B5A2B);
    }

    private NamespacedKey colorKey;

    @Override
    public void onEnable() {
        colorKey = new NamespacedKey(this, "color");
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getOnlinePlayers().forEach(this::restore);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        // Через тик: игрок должен полностью появиться в мире для /waypoint
        getServer().getScheduler().runTask(this, () -> restore(event.getPlayer()));
    }

    private void restore(Player player) {
        Integer rgb = player.getPersistentDataContainer().get(colorKey, PersistentDataType.INTEGER);
        if (rgb != null) apply(player, rgb);
    }

    @EventHandler
    public void onUse(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR || event.getHand() != EquipmentSlot.HAND) return;
        Player player = event.getPlayer();
        if (!player.isSneaking() || !player.hasPermission("locatorcolor.use")) return;
        if (!isLocator(player.getInventory().getItemInMainHand())) return;
        event.setCancelled(true);
        player.showDialog(buildDialog(player));
    }

    private static boolean isLocator(ItemStack item) {
        if (item.getType() != Material.COMPASS || !item.hasItemMeta() || !item.getItemMeta().hasCustomName()) return false;
        String name = PlainTextComponentSerializer.plainText().serialize(item.getItemMeta().customName()).trim();
        return name.equalsIgnoreCase("локатор") || name.equalsIgnoreCase("locator");
    }

    private Dialog buildDialog(Player player) {
        Integer current = player.getPersistentDataContainer().get(colorKey, PersistentDataType.INTEGER);
        String currentHex = current == null ? "" : String.format("%06X", current);

        List<ActionButton> buttons = new ArrayList<>();
        for (Map.Entry<String, Integer> e : PRESETS.entrySet()) {
            int rgb = e.getValue();
            buttons.add(ActionButton.builder(Component.text("■ " + e.getKey(), TextColor.color(rgb)))
                    .width(100)
                    .action(DialogAction.customClick((view, audience) -> {
                        if (audience instanceof Player p) setColor(p, rgb);
                    }, ClickCallback.Options.builder().build()))
                    .build());
        }
        buttons.add(ActionButton.builder(Component.text("Свой HEX", NamedTextColor.GOLD))
                .width(100)
                .action(DialogAction.customClick((view, audience) -> {
                    if (!(audience instanceof Player p)) return;
                    String text = view.getText("hex");
                    if (text == null || !HEX.matcher(text.trim()).matches()) {
                        p.sendMessage(Component.text("Введите цвет в формате RRGGBB, например FF8800", NamedTextColor.RED));
                        return;
                    }
                    setColor(p, Integer.parseInt(text.trim().replace("#", ""), 16));
                }, ClickCallback.Options.builder().build()))
                .build());
        buttons.add(ActionButton.builder(Component.text("Сбросить", NamedTextColor.GRAY))
                .width(100)
                .action(DialogAction.customClick((view, audience) -> {
                    if (audience instanceof Player p) resetColor(p);
                }, ClickCallback.Options.builder().build()))
                .build());

        return Dialog.create(b -> b.empty()
                .base(DialogBase.builder(Component.text("Цвет локатора"))
                        .canCloseWithEscape(true)
                        .body(List.of(DialogBody.plainMessage(Component.text(
                                "Выберите цвет своей точки на панели локатора. Его увидят другие игроки, "
                                        + "а в списке игроков он появится кружком перед ником."))))
                        .inputs(List.of(DialogInput.text("hex", Component.text("Свой цвет (HEX, например FF8800)"))
                                .initial(currentHex)
                                .maxLength(7)
                                .build()))
                        .build())
                .type(DialogType.multiAction(buttons).columns(3).build()));
    }

    private void setColor(Player player, int rgb) {
        player.getPersistentDataContainer().set(colorKey, PersistentDataType.INTEGER, rgb);
        apply(player, rgb);
        player.sendMessage(Component.text("Цвет локатора изменён: ", NamedTextColor.GRAY)
                .append(Component.text("■ #" + String.format("%06X", rgb), TextColor.color(rgb))));
    }

    private void resetColor(Player player) {
        player.getPersistentDataContainer().remove(colorKey);
        runWaypoint(player, "color reset");
        player.playerListName(null);
        player.sendMessage(Component.text("Цвет локатора сброшен", NamedTextColor.GRAY));
    }

    private void apply(Player player, int rgb) {
        runWaypoint(player, "color hex " + String.format("%06X", rgb));
        player.playerListName(Component.text("● ", TextColor.color(rgb)).append(Component.text(player.getName())));
    }

    private void runWaypoint(Player player, String args) {
        getServer().dispatchCommand(getServer().getConsoleSender(),
                "minecraft:waypoint modify " + player.getUniqueId() + " " + args);
    }
}
