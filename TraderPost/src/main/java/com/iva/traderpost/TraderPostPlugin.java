package com.iva.traderpost;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.WanderingTrader;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Shift + ПКМ изумрудом по бочке — бочка становится постом торговца.
 * Раз в 5 игровых дней в 20–30 блоках от неё появляется странствующий торговец
 * и идёт к бочке. Он живёт 2 дня, каждые полдня меняет трейды (3 раза),
 * затем уходит в случайную сторону и исчезает.
 */
public final class TraderPostPlugin extends JavaPlugin implements Listener {

    static final long DAY = 24000;
    static final long VISIT_INTERVAL = 5 * DAY;
    static final long REFRESH_INTERVAL = DAY / 2;
    static final int REFRESHES = 3;
    static final long STAY = 2 * DAY;
    static final long LEAVE_TIMEOUT = 20 * 60; // тиков на уход, потом исчезает

    /** Состояние одного поста. */
    static final class Post {
        String world;
        int x, y, z;
        UUID owner;
        long nextVisit;
        UUID trader;       // активный торговец
        long arrivedAt;
        int refreshes;
        boolean leaving;
        long leaveStarted;

        Location loc(org.bukkit.Server server) {
            World w = server.getWorld(world);
            return w == null ? null : new Location(w, x + 0.5, y, z + 0.5);
        }

        String key() { return world + ":" + x + ":" + y + ":" + z; }
    }

    private final Map<String, Post> posts = new HashMap<>();
    private final Random random = new Random();
    private File dataFile;
    private NamespacedKey postKey;

    @Override
    public void onEnable() {
        postKey = new NamespacedKey(this, "post");
        dataFile = new File(getDataFolder(), "posts.yml");
        load();
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getScheduler().runTaskTimer(this, this::tick, 100, 100);
    }

    @Override
    public void onDisable() {
        save();
    }

    // ---------- Регистрация бочки ----------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND) return;
        Player player = event.getPlayer();
        Block block = event.getClickedBlock();
        if (block == null || block.getType() != Material.BARREL || !player.isSneaking()) return;
        if (player.getInventory().getItemInMainHand().getType() != Material.EMERALD) return;
        if (!player.hasPermission("traderpost.use")) return;
        event.setCancelled(true);

        String key = key(block);
        Post existing = posts.get(key);
        if (existing != null) {
            long left = existing.trader != null ? 0 : Math.max(0, existing.nextVisit - block.getWorld().getFullTime());
            player.sendMessage(Component.text(existing.trader != null
                    ? "Торговец уже здесь!"
                    : "Это уже пост торговца. Следующий визит примерно через " + formatTime(left) + ".",
                    NamedTextColor.YELLOW));
            return;
        }
        Post post = new Post();
        post.world = block.getWorld().getName();
        post.x = block.getX(); post.y = block.getY(); post.z = block.getZ();
        post.owner = player.getUniqueId();
        post.nextVisit = block.getWorld().getFullTime() + VISIT_INTERVAL;
        posts.put(key, post);
        save();

        block.getWorld().playSound(block.getLocation(), Sound.ENTITY_WANDERING_TRADER_YES, 1f, 1f);
        block.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, block.getLocation().add(0.5, 1.2, 0.5), 15, 0.4, 0.3, 0.4);
        player.sendMessage(Component.text("Бочка стала постом странствующего торговца! Он будет приходить раз в 5 игровых дней.",
                NamedTextColor.GREEN));
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Post post = posts.remove(key(event.getBlock()));
        if (post == null) return;
        Entity trader = post.trader == null ? null : getServer().getEntity(post.trader);
        if (trader instanceof WanderingTrader wt) startLeaving(post, wt);
        save();
        event.getPlayer().sendMessage(Component.text("Пост торговца разрушен.", NamedTextColor.GRAY));
    }

    private static String key(Block b) {
        return b.getWorld().getName() + ":" + b.getX() + ":" + b.getY() + ":" + b.getZ();
    }

    // ---------- Основной цикл (раз в 5 секунд) ----------

    private void tick() {
        boolean dirty = false;
        for (Post post : posts.values().toArray(new Post[0])) {
            Location barrel = post.loc(getServer());
            if (barrel == null) continue;
            World world = barrel.getWorld();
            long now = world.getFullTime();

            if (post.trader == null) {
                // Ждём, пока подойдёт время и рядом будет кто-то из игроков (чанк загружен)
                if (now >= post.nextVisit && barrel.isChunkLoaded() && playerNear(barrel, 96)) {
                    WanderingTrader trader = spawnTrader(post, barrel);
                    if (trader != null) dirty = true;
                }
                continue;
            }

            Entity e = getServer().getEntity(post.trader);
            if (!(e instanceof WanderingTrader trader) || !trader.isValid()) {
                // Торговец погиб или чанк выгружен надолго — визит окончен
                if (e == null && barrel.isChunkLoaded() && playerNear(barrel, 64)) {
                    endVisit(post, now);
                    dirty = true;
                }
                continue;
            }

            if (post.leaving) {
                if (now - post.leaveStarted > LEAVE_TIMEOUT || trader.getLocation().distanceSquared(barrel) > 48 * 48) {
                    trader.getWorld().spawnParticle(Particle.POOF, trader.getLocation().add(0, 1, 0), 20, 0.3, 0.6, 0.3);
                    trader.remove();
                    endVisit(post, now);
                    dirty = true;
                }
                continue;
            }

            // Держится рядом с бочкой
            if (trader.getLocation().distanceSquared(barrel) > 5 * 5) {
                trader.getPathfinder().moveTo(barrel, 0.6);
            }

            // Смена трейдов каждые полдня
            int due = (int) ((now - post.arrivedAt) / REFRESH_INTERVAL);
            if (due > post.refreshes && post.refreshes < REFRESHES) {
                post.refreshes++;
                trader.setRecipes(TradePool.roll(random));
                trader.getWorld().playSound(trader.getLocation(), Sound.ENTITY_WANDERING_TRADER_TRADE, 1f, 1f);
                trader.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, trader.getLocation().add(0, 2, 0), 10, 0.3, 0.3, 0.3);
                dirty = true;
            }

            if (now - post.arrivedAt >= STAY) {
                startLeaving(post, trader);
                dirty = true;
            }
        }
        if (dirty) save();
    }

    private boolean playerNear(Location loc, double radius) {
        for (Player p : loc.getWorld().getPlayers()) {
            if (p.getLocation().distanceSquared(loc) <= radius * radius) return true;
        }
        return false;
    }

    private WanderingTrader spawnTrader(Post post, Location barrel) {
        Location spot = findSpot(barrel);
        if (spot == null) return null;
        WanderingTrader trader = barrel.getWorld().spawn(spot, WanderingTrader.class, t -> {
            t.setDespawnDelay(0); // уходом управляет плагин
            t.setRecipes(TradePool.roll(random));
            t.getPersistentDataContainer().set(postKey, PersistentDataType.STRING, post.key());
            t.setPersistent(true);
        });
        trader.setWanderingTowards(barrel);
        trader.getPathfinder().moveTo(barrel, 0.6);
        post.trader = trader.getUniqueId();
        post.arrivedAt = barrel.getWorld().getFullTime();
        post.refreshes = 0;
        post.leaving = false;

        Player owner = getServer().getPlayer(post.owner);
        if (owner != null && owner.getWorld().equals(barrel.getWorld())) {
            owner.sendMessage(Component.text("К вашей бочке идёт странствующий торговец!", NamedTextColor.GREEN));
        }
        return trader;
    }

    /** Случайное безопасное место на поверхности в 20–30 блоках от бочки. */
    private Location findSpot(Location barrel) {
        World w = barrel.getWorld();
        for (int i = 0; i < 20; i++) {
            double angle = random.nextDouble() * Math.PI * 2;
            double dist = 20 + random.nextDouble() * 10;
            int x = (int) Math.floor(barrel.getX() + Math.cos(angle) * dist);
            int z = (int) Math.floor(barrel.getZ() + Math.sin(angle) * dist);
            if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
            Block ground = w.getHighestBlockAt(x, z);
            if (!ground.getType().isSolid() || ground.isLiquid()) continue;
            if (Math.abs(ground.getY() - barrel.getY()) > 12) continue;
            Block feet = ground.getRelative(0, 1, 0), head = ground.getRelative(0, 2, 0);
            if (!feet.isPassable() || !head.isPassable() || feet.isLiquid()) continue;
            return feet.getLocation().add(0.5, 0, 0.5);
        }
        return null;
    }

    private void startLeaving(Post post, WanderingTrader trader) {
        post.leaving = true;
        post.leaveStarted = trader.getWorld().getFullTime();
        double angle = random.nextDouble() * Math.PI * 2;
        Location target = trader.getLocation().add(Math.cos(angle) * 60, 0, Math.sin(angle) * 60);
        target.setY(trader.getWorld().getHighestBlockYAt(target) + 1);
        trader.setWanderingTowards(target);
        trader.getPathfinder().moveTo(target, 0.7);
    }

    private void endVisit(Post post, long now) {
        post.trader = null;
        post.leaving = false;
        post.refreshes = 0;
        post.nextVisit = now + VISIT_INTERVAL;
    }

    private static String formatTime(long ticks) {
        double days = ticks / (double) DAY;
        long realMinutes = ticks / 20 / 60;
        return String.format("%.1f игр. дн. (~%d мин)", days, realMinutes);
    }

    // ---------- Сохранение ----------

    private void load() {
        posts.clear();
        if (!dataFile.exists()) return;
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(dataFile);
        for (String k : yml.getKeys(false)) {
            ConfigurationSection s = yml.getConfigurationSection(k);
            if (s == null) continue;
            Post p = new Post();
            p.world = s.getString("world");
            p.x = s.getInt("x"); p.y = s.getInt("y"); p.z = s.getInt("z");
            String owner = s.getString("owner");
            p.owner = owner == null ? null : UUID.fromString(owner);
            p.nextVisit = s.getLong("nextVisit");
            String trader = s.getString("trader");
            p.trader = trader == null ? null : UUID.fromString(trader);
            p.arrivedAt = s.getLong("arrivedAt");
            p.refreshes = s.getInt("refreshes");
            p.leaving = s.getBoolean("leaving");
            p.leaveStarted = s.getLong("leaveStarted");
            posts.put(p.key(), p);
        }
    }

    private void save() {
        YamlConfiguration yml = new YamlConfiguration();
        int i = 0;
        for (Post p : posts.values()) {
            ConfigurationSection s = yml.createSection("post" + (i++));
            s.set("world", p.world);
            s.set("x", p.x); s.set("y", p.y); s.set("z", p.z);
            s.set("owner", p.owner == null ? null : p.owner.toString());
            s.set("nextVisit", p.nextVisit);
            s.set("trader", p.trader == null ? null : p.trader.toString());
            s.set("arrivedAt", p.arrivedAt);
            s.set("refreshes", p.refreshes);
            s.set("leaving", p.leaving);
            s.set("leaveStarted", p.leaveStarted);
        }
        try {
            getDataFolder().mkdirs();
            yml.save(dataFile);
        } catch (IOException e) {
            getLogger().warning("Не удалось сохранить posts.yml: " + e.getMessage());
        }
    }
}
