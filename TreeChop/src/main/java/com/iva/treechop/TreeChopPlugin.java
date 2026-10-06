package com.iva.treechop;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageAbortEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Начал ломать бревно — над ним появляется счётчик брёвен в связке.
 * Сломал бревно — ломается вся связка брёвен и прилегающая природная листва,
 * лут выпадает на месте. С зажатым Shift всё работает как в ванили.
 */
public final class TreeChopPlugin extends JavaPlugin implements Listener {

    /** Защита от вырубки огромных построек из брёвен. */
    private static final int MAX_LOGS = 512;
    private static final int LEAF_RANGE = 6;
    private static final int MAX_LEAVES = 2000;

    private final Map<UUID, TextDisplay> indicators = new HashMap<>();
    private boolean chopping = false;

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
    }

    @Override
    public void onDisable() {
        indicators.values().forEach(TextDisplay::remove);
        indicators.clear();
    }

    private boolean active(Player p) {
        return !p.isSneaking() && p.hasPermission("treechop.use");
    }

    // ---------- Индикатор ----------

    @EventHandler(ignoreCancelled = true)
    public void onDamage(BlockDamageEvent event) {
        Block block = event.getBlock();
        Player player = event.getPlayer();
        removeIndicator(player);
        if (!Tag.LOGS.isTagged(block.getType()) || !active(player)) return;

        int count = findLogs(block).size();
        String text = count >= MAX_LOGS ? MAX_LOGS + "+ брёвен" : "Брёвен: " + count;
        Location loc = block.getLocation().add(0.5, 1.25, 0.5);
        TextDisplay display = block.getWorld().spawn(loc, TextDisplay.class, d -> {
            d.text(Component.text(text, NamedTextColor.GOLD));
            d.setBillboard(Display.Billboard.CENTER);
            d.setSeeThrough(true);
            d.setPersistent(false);
            d.setVisibleByDefault(false);
        });
        player.showEntity(this, display);
        indicators.put(player.getUniqueId(), display);
    }

    @EventHandler
    public void onAbort(BlockDamageAbortEvent event) {
        removeIndicator(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        removeIndicator(event.getPlayer());
    }

    private void removeIndicator(Player player) {
        TextDisplay old = indicators.remove(player.getUniqueId());
        if (old != null) old.remove();
    }

    // ---------- Рубка ----------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (chopping) return; // события, которые мы сами вызываем ниже
        Player player = event.getPlayer();
        removeIndicator(player);
        Block origin = event.getBlock();
        if (!Tag.LOGS.isTagged(origin.getType()) || !active(player)) return;

        Set<Block> logs = findLogs(origin);
        logs.remove(origin); // его ломает сама игра
        List<Block> leaves = findLeaves(origin, logs);

        ItemStack tool = player.getInventory().getItemInMainHand();
        boolean survival = player.getGameMode() != GameMode.CREATIVE;

        chopping = true;
        try {
            for (Block log : logs) {
                if (!canBreak(log, player)) continue;
                if (survival) {
                    log.breakNaturally(tool);
                    if (damageTool(player, tool)) break; // топор сломался
                    tool = player.getInventory().getItemInMainHand();
                } else {
                    log.setType(org.bukkit.Material.AIR);
                }
            }
            for (Block leaf : leaves) {
                if (!Tag.LEAVES.isTagged(leaf.getType()) || !canBreak(leaf, player)) continue;
                // Без инструмента: выпадает обычный лут листвы (саженцы, палки, яблоки)
                if (survival) leaf.breakNaturally();
                else leaf.setType(org.bukkit.Material.AIR);
            }
        } finally {
            chopping = false;
        }
    }

    /** Даём другим плагинам (приваты и т.п.) запретить ломание блока. */
    private boolean canBreak(Block block, Player player) {
        BlockBreakEvent check = new BlockBreakEvent(block, player);
        check.setDropItems(true);
        getServer().getPluginManager().callEvent(check);
        return !check.isCancelled();
    }

    /** @return true, если инструмент сломался */
    private boolean damageTool(Player player, ItemStack tool) {
        if (tool == null || tool.getType().isAir() || !(tool.getItemMeta() instanceof Damageable)) return false;
        ItemStack result = tool.damage(1, player);
        player.getInventory().setItemInMainHand(result);
        return result.getType().isAir();
    }

    /** Все брёвна, соприкасающиеся друг с другом (включая диагонали). */
    private Set<Block> findLogs(Block start) {
        Set<Block> found = new LinkedHashSet<>();
        ArrayDeque<Block> queue = new ArrayDeque<>();
        found.add(start);
        queue.add(start);
        while (!queue.isEmpty() && found.size() < MAX_LOGS) {
            Block b = queue.poll();
            for (int dx = -1; dx <= 1; dx++)
                for (int dy = -1; dy <= 1; dy++)
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;
                        Block n = b.getRelative(dx, dy, dz);
                        if (Tag.LOGS.isTagged(n.getType()) && found.add(n)) {
                            queue.add(n);
                            if (found.size() >= MAX_LOGS) return found;
                        }
                    }
        }
        return found;
    }

    /** Природная (не поставленная игроком) листва вокруг брёвен дерева. */
    private List<Block> findLeaves(Block origin, Set<Block> logs) {
        List<Block> sources = new ArrayList<>(logs);
        sources.add(origin);
        Map<Block, Integer> dist = new HashMap<>();
        ArrayDeque<Block> queue = new ArrayDeque<>();
        Set<Block> logSet = new HashSet<>(sources);
        for (Block log : sources) {
            for (Block n : neighbours(log)) {
                if (isNaturalLeaf(n) && !dist.containsKey(n)) {
                    dist.put(n, 1);
                    queue.add(n);
                }
            }
        }
        while (!queue.isEmpty() && dist.size() < MAX_LEAVES) {
            Block b = queue.poll();
            int d = dist.get(b);
            if (d >= LEAF_RANGE) continue;
            for (Block n : neighbours(b)) {
                if (!logSet.contains(n) && isNaturalLeaf(n) && !dist.containsKey(n)) {
                    dist.put(n, d + 1);
                    queue.add(n);
                }
            }
        }
        return new ArrayList<>(dist.keySet());
    }

    private static boolean isNaturalLeaf(Block b) {
        return Tag.LEAVES.isTagged(b.getType())
                && b.getBlockData() instanceof Leaves leaves && !leaves.isPersistent();
    }

    private static Block[] neighbours(Block b) {
        return new Block[]{
                b.getRelative(1, 0, 0), b.getRelative(-1, 0, 0),
                b.getRelative(0, 1, 0), b.getRelative(0, -1, 0),
                b.getRelative(0, 0, 1), b.getRelative(0, 0, -1)
        };
    }
}
