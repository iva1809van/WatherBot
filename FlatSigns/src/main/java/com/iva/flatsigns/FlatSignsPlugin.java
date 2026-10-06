package com.iva.flatsigns;

import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Shift + ПКМ обычной табличкой по верхней или нижней грани блока:
 * открывается редактор текста, после чего табличка ложится плашмя
 * на пол или потолок (собирается из display-сущностей).
 * Удар по такой табличке ломает её и возвращает предмет.
 */
public final class FlatSignsPlugin extends JavaPlugin implements Listener {

    private static final float THICKNESS = 1f / 12f;
    private static final float TEXT_SCALE = 0.4f;

    private NamespacedKey groupKey;
    private NamespacedKey itemKey;

    /** Временные таблички, ожидающие ввода текста: игрок -> данные. */
    private final Map<UUID, Pending> pending = new HashMap<>();

    private record Pending(Block block, Material signType, boolean ceiling, float yaw) {}

    @Override
    public void onEnable() {
        groupKey = new NamespacedKey(this, "group");
        itemKey = new NamespacedKey(this, "item");
        getServer().getPluginManager().registerEvents(this, this);
    }

    @Override
    public void onDisable() {
        pending.values().forEach(p -> p.block().setType(Material.AIR, false));
        pending.clear();
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND) return;
        Player player = event.getPlayer();
        if (!player.isSneaking() || !player.hasPermission("flatsigns.use")) return;

        ItemStack hand = player.getInventory().getItemInMainHand();
        // Только обычные таблички (подвесные не трогаем)
        if (!Tag.ITEMS_SIGNS.isTagged(hand.getType())) return;

        BlockFace face = event.getBlockFace();
        if (face != BlockFace.UP && face != BlockFace.DOWN) return;
        Block clicked = event.getClickedBlock();
        if (clicked == null) return;
        Block target = clicked.getRelative(face);
        if (!target.getType().isAir()) return;
        if (pending.containsKey(player.getUniqueId())) return;

        event.setCancelled(true);

        // Ориентация как у обычной таблички: 16 положений, текст смотрит на игрока
        float yaw = Math.round((player.getLocation().getYaw() + 180f) / 22.5f) * 22.5f;
        Material signType = hand.getType();

        // Временная настоящая табличка нужна только для окна ввода текста
        target.setType(signType, false);
        if (!(target.getState() instanceof Sign sign)) {
            target.setType(Material.AIR, false);
            return;
        }
        pending.put(player.getUniqueId(), new Pending(target, signType, face == BlockFace.DOWN, yaw));

        getServer().getScheduler().runTask(this, () -> {
            if (target.getState() instanceof Sign s) player.openSign(s, Side.FRONT);
        });
        // Если игрок так и не закрыл редактор — убираем временный блок
        getServer().getScheduler().runTaskLater(this, () -> {
            Pending p = pending.get(player.getUniqueId());
            if (p != null && p.block().equals(target)) {
                pending.remove(player.getUniqueId());
                target.setType(Material.AIR, false);
            }
        }, 20L * 120);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSignEdit(SignChangeEvent event) {
        Player player = event.getPlayer();
        Pending p = pending.get(player.getUniqueId());
        if (p == null || !p.block().equals(event.getBlock())) return;
        pending.remove(player.getUniqueId());

        List<Component> lines = event.lines();
        event.setCancelled(true);
        p.block().setType(Material.AIR, false);

        // Списываем табличку из руки
        if (player.getGameMode() != GameMode.CREATIVE) {
            ItemStack hand = player.getInventory().getItemInMainHand();
            if (hand.getType() != p.signType()) return; // предмет пропал из руки — не ставим
            hand.setAmount(hand.getAmount() - 1);
        }

        spawnFlatSign(p, lines);
        p.block().getWorld().playSound(p.block().getLocation(), Sound.BLOCK_WOOD_PLACE, 1f, 1f);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Pending p = pending.remove(event.getPlayer().getUniqueId());
        if (p != null) p.block().setType(Material.AIR, false);
    }

    private void spawnFlatSign(Pending p, List<Component> lines) {
        Block b = p.block();
        String group = UUID.randomUUID().toString();
        Location base = b.getLocation().add(0.5, 0, 0.5);
        base.setYaw(p.yaw());
        base.setPitch(0);

        float boardY = p.ceiling() ? 1f - THICKNESS : 0f;

        // Доска таблички — тонкий блок досок
        Material planks = Material.matchMaterial(p.signType().name().replace("_SIGN", "_PLANKS"));
        if (planks == null || !planks.isBlock()) planks = Material.OAK_PLANKS;
        final Material planksType = planks;
        b.getWorld().spawn(base, BlockDisplay.class, d -> {
            d.setBlock(planksType.createBlockData());
            d.setTransformation(new Transformation(
                    new Vector3f(-0.5f, boardY, -0.25f),
                    new AxisAngle4f(),
                    new Vector3f(1f, THICKNESS, 0.5f),
                    new AxisAngle4f()));
            tag(d.getPersistentDataContainer(), group, null);
        });

        // Текст
        Component text = Component.empty();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) text = text.append(Component.newline());
            text = text.append(lines.get(i));
        }
        final Component content = text.colorIfAbsent(NamedTextColor.BLACK);
        float textY = p.ceiling() ? boardY - 0.002f : boardY + THICKNESS + 0.002f;
        // На полу текст «растёт» от ближнего к игроку края, на потолке — наоборот
        float textZ = p.ceiling() ? -TEXT_SCALE / 2f : TEXT_SCALE / 2f;
        float angle = (float) Math.toRadians(p.ceiling() ? 90 : -90);
        b.getWorld().spawn(base, TextDisplay.class, t -> {
            t.text(content);
            t.setBillboard(Display.Billboard.FIXED);
            t.setDefaultBackground(false);
            t.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            t.setShadowed(false);
            t.setTransformation(new Transformation(
                    new Vector3f(0f, textY, textZ),
                    new AxisAngle4f(angle, 1f, 0f, 0f),
                    new Vector3f(TEXT_SCALE, TEXT_SCALE, TEXT_SCALE),
                    new AxisAngle4f()));
            tag(t.getPersistentDataContainer(), group, null);
        });

        // Невидимый хитбокс, чтобы табличку можно было сломать
        float hitHeight = 0.15f;
        Location hitLoc = b.getLocation().add(0.5, p.ceiling() ? 1 - hitHeight : 0, 0.5);
        b.getWorld().spawn(hitLoc, Interaction.class, i -> {
            i.setInteractionWidth(0.9f);
            i.setInteractionHeight(hitHeight);
            i.setResponsive(true);
            tag(i.getPersistentDataContainer(), group, p.signType().name());
        });
    }

    private void tag(PersistentDataContainer pdc, String group, String item) {
        pdc.set(groupKey, PersistentDataType.STRING, group);
        if (item != null) pdc.set(itemKey, PersistentDataType.STRING, item);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(PrePlayerAttackEntityEvent event) {
        if (!(event.getAttacked() instanceof Interaction hitbox)) return;
        PersistentDataContainer pdc = hitbox.getPersistentDataContainer();
        String group = pdc.get(groupKey, PersistentDataType.STRING);
        if (group == null) return;
        event.setCancelled(true);

        for (Entity e : hitbox.getNearbyEntities(1.5, 1.5, 1.5)) {
            if (group.equals(e.getPersistentDataContainer().get(groupKey, PersistentDataType.STRING))) {
                e.remove();
            }
        }
        hitbox.remove();

        Location loc = hitbox.getLocation();
        loc.getWorld().playSound(loc, Sound.BLOCK_WOOD_BREAK, 1f, 1f);
        if (event.getPlayer().getGameMode() != GameMode.CREATIVE) {
            Material item = Material.matchMaterial(String.valueOf(pdc.get(itemKey, PersistentDataType.STRING)));
            if (item == null) item = Material.OAK_SIGN;
            loc.getWorld().dropItemNaturally(loc.add(0, 0.1, 0), new ItemStack(item));
        }
    }
}
