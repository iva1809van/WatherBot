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

    /**
     * block — временная табличка для ввода текста; anchor — блок, где появится табличка;
     * wall — сторона стены (null для пола/потолка).
     */
    private record Pending(Block block, Material signType, boolean ceiling, float yaw, Block anchor, BlockFace wall, String editGroup) {
        Pending(Block block, Material signType, boolean ceiling, float yaw) {
            this(block, signType, ceiling, yaw, block, null, null);
        }
        Pending(Block block, Material signType, boolean ceiling, float yaw, Block anchor, BlockFace wall) {
            this(block, signType, ceiling, yaw, anchor, wall, null);
        }
    }

    private static final Gson_ GSON = new Gson_();
    /** Обёртка над сериализатором Adventure (компоненты ↔ JSON для хранения в PDC). */
    private static final class Gson_ {
        String to(Component c) { return net.kyori.adventure.text.serializer.gson.GsonComponentSerializer.gson().serialize(c); }
        Component from(String s) { return net.kyori.adventure.text.serializer.gson.GsonComponentSerializer.gson().deserialize(s); }
    }

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
        if (!player.hasPermission("flatsigns.use")) return;

        ItemStack hand = player.getInventory().getItemInMainHand();
        // Только обычные таблички (подвесные не трогаем)
        if (!Tag.ITEMS_SIGNS.isTagged(hand.getType())) return;

        BlockFace face = event.getBlockFace();
        if (tryWallSign(event, player, hand, face)) return;
        if (!player.isSneaking()) return;
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

    /**
     * Табличка на стене там, где обычная не встанет: в блоке уже стоит табличка
     * (угол — несколько табличек в одном блоке, как рамки) или факел и т.п.
     */
    private boolean tryWallSign(PlayerInteractEvent event, Player player, ItemStack hand, BlockFace face) {
        if (face != BlockFace.NORTH && face != BlockFace.SOUTH && face != BlockFace.EAST && face != BlockFace.WEST) return false;
        Block clicked = event.getClickedBlock();
        if (clicked == null || !clicked.getType().isSolid()) return false;
        Block target = clicked.getRelative(face);
        Material t = target.getType();
        boolean occupied = !t.isAir() && (target.isPassable() || Tag.ALL_SIGNS.isTagged(t));
        if (!occupied) return false; // свободно — пусть игра поставит обычную табличку
        if (pending.containsKey(player.getUniqueId())) return true;

        // Для окна ввода текста нужна временная настоящая табличка — ставим её в свободный блок у игрока
        Block edit = null;
        Block feet = player.getLocation().getBlock();
        for (Block b : new Block[]{feet.getRelative(0, 2, 0), feet.getRelative(0, 1, 0), feet}) {
            if (b.getType().isAir()) { edit = b; break; }
        }
        if (edit == null) return false;
        event.setCancelled(true);

        Material signType = hand.getType();
        edit.setType(signType, false);
        if (!(edit.getState() instanceof Sign)) {
            edit.setType(Material.AIR, false);
            return true;
        }
        float yaw = switch (face) {
            case SOUTH -> 0f; case WEST -> 90f; case NORTH -> 180f; default -> -90f;
        };
        final Block editBlock = edit;
        pending.put(player.getUniqueId(), new Pending(edit, signType, false, yaw, target, face));
        getServer().getScheduler().runTask(this, () -> {
            if (editBlock.getState() instanceof Sign s) player.openSign(s, Side.FRONT);
        });
        getServer().getScheduler().runTaskLater(this, () -> {
            Pending p = pending.get(player.getUniqueId());
            if (p != null && p.block().equals(editBlock)) {
                pending.remove(player.getUniqueId());
                editBlock.setType(Material.AIR, false);
            }
        }, 20L * 120);
        return true;
    }

    /** Настенная табличка из display-сущностей, прижатая к стороне блока. */
    private void spawnWallSign(Pending p, List<Component> lines) {
        Block b = p.anchor();
        String group = UUID.randomUUID().toString();
        Location base = b.getLocation().add(0.5, 0.5, 0.5);
        base.setYaw(p.yaw());
        base.setPitch(0);

        Material planks = Material.matchMaterial(p.signType().name().replace("_SIGN", "_PLANKS"));
        if (planks == null || !planks.isBlock()) planks = Material.OAK_PLANKS;
        final Material planksType = planks;
        // Локальная ось +Z смотрит от стены наружу; стена — на z = -0.5
        b.getWorld().spawn(base, BlockDisplay.class, d -> {
            d.setBlock(planksType.createBlockData());
            d.setTransformation(new Transformation(
                    new Vector3f(-0.5f, -0.25f, -0.5f),
                    new AxisAngle4f(),
                    new Vector3f(1f, 0.5f, THICKNESS),
                    new AxisAngle4f()));
            tag(d.getPersistentDataContainer(), group, null);
        });

        Component text = Component.empty();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) text = text.append(Component.newline());
            text = text.append(lines.get(i));
        }
        final Component content = text.colorIfAbsent(NamedTextColor.BLACK);
        b.getWorld().spawn(base, TextDisplay.class, t -> {
            storeLines(t, lines);
            t.setBillboard(Display.Billboard.FIXED);
            t.setDefaultBackground(false);
            t.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            t.setShadowed(false);
            t.setTransformation(new Transformation(
                    new Vector3f(0f, -TEXT_SCALE / 2f, -0.5f + THICKNESS + 0.003f),
                    new AxisAngle4f(),
                    new Vector3f(TEXT_SCALE, TEXT_SCALE, TEXT_SCALE),
                    new AxisAngle4f()));
            tag(t.getPersistentDataContainer(), group, null);
            render(t);
        });

        // Хитбокс у стены
        // Хитбокс — 4 тонких секции вдоль доски, чтобы он совпадал с самой табличкой
        BlockFace f = p.wall();
        double ax = f.getModZ() != 0 ? 1 : 0, az = f.getModX() != 0 ? 1 : 0; // ось вдоль стены
        for (double k : new double[]{-0.375, -0.125, 0.125, 0.375}) {
            Location hit = b.getLocation().add(0.5 - f.getModX() * 0.375 + ax * k, 0.25, 0.5 - f.getModZ() * 0.375 + az * k);
            b.getWorld().spawn(hit, Interaction.class, i -> {
                i.setInteractionWidth(0.25f);
                i.setInteractionHeight(0.5f);
                i.setResponsive(true);
                tag(i.getPersistentDataContainer(), group, p.signType().name());
            });
        }
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

        if (p.editGroup() != null) {
            TextDisplay t = findText(p.anchor().getLocation().add(0.5, 0.5, 0.5), p.editGroup());
            if (t != null) {
                storeLines(t, lines);
                render(t);
            }
            return;
        }

        // Списываем табличку из руки
        if (player.getGameMode() != GameMode.CREATIVE) {
            ItemStack hand = player.getInventory().getItemInMainHand();
            if (hand.getType() != p.signType()) return; // предмет пропал из руки — не ставим
            hand.setAmount(hand.getAmount() - 1);
        }

        if (p.wall() != null) spawnWallSign(p, lines);
        else spawnFlatSign(p, lines);
        p.anchor().getWorld().playSound(p.anchor().getLocation(), Sound.BLOCK_WOOD_PLACE, 1f, 1f);
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
            storeLines(t, lines);
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
            render(t);
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

    // ---------- Текст, краска, свечение, воск ----------

    private NamespacedKey key(String k) { return new NamespacedKey(this, k); }

    private void storeLines(TextDisplay t, List<Component> lines) {
        PersistentDataContainer pdc = t.getPersistentDataContainer();
        for (int i = 0; i < 4; i++) {
            Component c = i < lines.size() ? lines.get(i) : Component.empty();
            pdc.set(key("line" + i), PersistentDataType.STRING, GSON.to(c));
        }
    }

    private List<Component> loadLines(TextDisplay t) {
        PersistentDataContainer pdc = t.getPersistentDataContainer();
        List<Component> out = new java.util.ArrayList<>();
        for (int i = 0; i < 4; i++) {
            String j = pdc.get(key("line" + i), PersistentDataType.STRING);
            out.add(j == null ? Component.empty() : GSON.from(j));
        }
        return out;
    }

    /** Перерисовать текст с учётом цвета и свечения (как у обычной таблички). */
    private void render(TextDisplay t) {
        PersistentDataContainer pdc = t.getPersistentDataContainer();
        int rgb = pdc.getOrDefault(key("color"), PersistentDataType.INTEGER, 0x000000);
        boolean glow = pdc.getOrDefault(key("glow"), PersistentDataType.BYTE, (byte) 0) == 1;
        Component text = Component.empty();
        List<Component> lines = loadLines(t);
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) text = text.append(Component.newline());
            text = text.append(lines.get(i));
        }
        int shown = rgb;
        if (glow && rgb == 0x000000) shown = 0xF0EBCC; // светящийся чёрный в игре — светлый
        t.text(text.colorIfAbsent(net.kyori.adventure.text.format.TextColor.color(shown)));
        t.setBrightness(glow ? new Display.Brightness(15, 15) : null);
        t.setShadowed(glow);
    }

    private TextDisplay findText(Location near, String group) {
        for (Entity e : near.getWorld().getNearbyEntities(near, 1.5, 1.5, 1.5)) {
            if (e instanceof TextDisplay t && group.equals(t.getPersistentDataContainer().get(groupKey, PersistentDataType.STRING))) return t;
        }
        return null;
    }

    /** ПКМ по табличке: краситель, светящиеся/обычные чернила, соты или редактирование текста. */
    @EventHandler(ignoreCancelled = true)
    public void onUse(org.bukkit.event.player.PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof Interaction hitbox) || event.getHand() != EquipmentSlot.HAND) return;
        String group = hitbox.getPersistentDataContainer().get(groupKey, PersistentDataType.STRING);
        if (group == null) return;
        event.setCancelled(true);
        Player player = event.getPlayer();
        TextDisplay t = findText(hitbox.getLocation(), group);
        if (t == null) return;
        PersistentDataContainer pdc = t.getPersistentDataContainer();
        boolean waxed = pdc.getOrDefault(key("wax"), PersistentDataType.BYTE, (byte) 0) == 1;
        ItemStack hand = player.getInventory().getItemInMainHand();
        Material m = hand.getType();
        Location at = hitbox.getLocation();

        if (waxed) {
            at.getWorld().playSound(at, Sound.BLOCK_SIGN_WAXED_INTERACT_FAIL, 1f, 1f);
            return;
        }
        if (m.name().endsWith("_DYE")) {
            org.bukkit.DyeColor dye = org.bukkit.DyeColor.valueOf(m.name().substring(0, m.name().length() - 4));
            pdc.set(key("color"), PersistentDataType.INTEGER, dye.getColor().asRGB());
            consume(player, hand);
            at.getWorld().playSound(at, Sound.ITEM_DYE_USE, 1f, 1f);
        } else if (m == Material.GLOW_INK_SAC) {
            pdc.set(key("glow"), PersistentDataType.BYTE, (byte) 1);
            consume(player, hand);
            at.getWorld().playSound(at, Sound.ITEM_GLOW_INK_SAC_USE, 1f, 1f);
        } else if (m == Material.INK_SAC) {
            pdc.set(key("glow"), PersistentDataType.BYTE, (byte) 0);
            consume(player, hand);
            at.getWorld().playSound(at, Sound.ITEM_INK_SAC_USE, 1f, 1f);
        } else if (m == Material.HONEYCOMB) {
            pdc.set(key("wax"), PersistentDataType.BYTE, (byte) 1);
            consume(player, hand);
            at.getWorld().playSound(at, Sound.ITEM_HONEYCOMB_WAX_ON, 1f, 1f);
            at.getWorld().spawnParticle(org.bukkit.Particle.WAX_ON, at.clone().add(0, 0.3, 0), 6, 0.2, 0.2, 0.2);
            return;
        } else {
            openEditor(player, t, group);
            return;
        }
        render(t);
    }

    private void consume(Player player, ItemStack hand) {
        if (player.getGameMode() != GameMode.CREATIVE) hand.setAmount(hand.getAmount() - 1);
    }

    /** Редактирование: временная табличка с текущим текстом, после ввода текст переносится обратно. */
    private void openEditor(Player player, TextDisplay t, String group) {
        if (pending.containsKey(player.getUniqueId())) return;
        Block edit = null;
        Block feet = player.getLocation().getBlock();
        for (Block b : new Block[]{feet.getRelative(0, 2, 0), feet.getRelative(0, 1, 0), feet}) {
            if (b.getType().isAir()) { edit = b; break; }
        }
        if (edit == null) return;
        edit.setType(Material.OAK_SIGN, false);
        if (!(edit.getState() instanceof Sign sign)) { edit.setType(Material.AIR, false); return; }
        List<Component> lines = loadLines(t);
        for (int i = 0; i < 4; i++) sign.getSide(Side.FRONT).line(i, lines.get(i));
        sign.update(true, false);
        Block anchor = t.getLocation().getBlock();
        final Block editBlock = edit;
        pending.put(player.getUniqueId(), new Pending(edit, Material.OAK_SIGN, false, 0, anchor, null, group));
        getServer().getScheduler().runTask(this, () -> {
            if (editBlock.getState() instanceof Sign s) player.openSign(s, Side.FRONT);
        });
        getServer().getScheduler().runTaskLater(this, () -> {
            Pending p = pending.get(player.getUniqueId());
            if (p != null && p.block().equals(editBlock)) {
                pending.remove(player.getUniqueId());
                editBlock.setType(Material.AIR, false);
            }
        }, 20L * 120);
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
