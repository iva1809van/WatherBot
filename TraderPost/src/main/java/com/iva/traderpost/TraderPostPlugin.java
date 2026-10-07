package com.iva.traderpost;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Bell;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TraderLlama;
import org.bukkit.entity.WanderingTrader;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Странствующие торговцы больше не приходят сами. Они бывают двух видов:
 * <ul>
 *   <li><b>Деревенские</b> — появляются в новых деревнях у колокола и живут там всегда,
 *       меняя трейды каждые полдня.</li>
 *   <li><b>Посты</b> — Shift + ПКМ изумрудом по бочке. Раз в 5 дней торговец с ламами
 *       приходит к бочке, 2 дня стоит рядом, каждые полдня меняет трейды,
 *       затем пьёт зелье невидимости и уходит.</li>
 * </ul>
 */
public final class TraderPostPlugin extends JavaPlugin implements Listener {

    static final long DAY = 24000;
    static final long VISIT_INTERVAL = 5 * DAY;
    static final long DEATH_INTERVAL = 7 * DAY;
    static final long REFRESH_INTERVAL = DAY / 2;
    static final int REFRESHES = 3;
    static final long STAY = 2 * DAY;
    /** Сколько тиков даём дойти до бочки, прежде чем переставить торговца рядом. */
    static final long ARRIVE_TIMEOUT = 20 * 120;
    /** Сколько тиков торговец уходит, прежде чем исчезнуть. */
    static final long LEAVE_TIMEOUT = 20 * 45;
    static final double STAY_RADIUS = 5;
    static final double VILLAGE_RADIUS = 16;
    /** Шанс, что в новой деревне поселится торговец. */
    static final double VILLAGE_CHANCE = 0.35;

    /** Состояние одного поста. */
    static final class Post {
        String world;
        int x, y, z;
        UUID owner;
        long nextVisit;
        UUID trader;
        List<UUID> llamas = new ArrayList<>();
        long arrivedAt;
        boolean reached;
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
    private NamespacedKey postKey, homeKey, refreshKey;
    /** Разрешаем спавн, который делает сам плагин. */
    private boolean spawning = false;

    @Override
    public void onEnable() {
        postKey = new NamespacedKey(this, "post");
        homeKey = new NamespacedKey(this, "village_home");
        refreshKey = new NamespacedKey(this, "last_refresh");
        dataFile = new File(getDataFolder(), "posts.yml");
        load();
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getScheduler().runTaskTimer(this, this::tick, 100, 100);
    }

    @Override
    public void onDisable() {
        save();
    }

    // ---------- Отключаем обычный приход торговцев ----------

    @EventHandler(ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent event) {
        if (spawning) return;
        if (!(event.getEntity() instanceof WanderingTrader) && !(event.getEntity() instanceof TraderLlama)) return;
        if (event.getSpawnReason() == CreatureSpawnEvent.SpawnReason.NATURAL) event.setCancelled(true);
    }

    // ---------- Деревенские торговцы ----------

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        if (!event.isNewChunk()) return;
        Chunk chunk = event.getChunk();
        getServer().getScheduler().runTask(this, () -> {
            if (!chunk.isLoaded()) return;
            for (BlockState state : chunk.getTileEntities()) {
                if (state instanceof Bell && random.nextDouble() < VILLAGE_CHANCE) {
                    spawnVillageTrader(state.getLocation());
                    return;
                }
            }
        });
    }

    private void spawnVillageTrader(Location bell) {
        Location spot = findSpot(bell, 2, 6);
        if (spot == null) return;
        WanderingTrader trader = spawnTraderEntity(spot);
        PersistentDataContainer pdc = trader.getPersistentDataContainer();
        pdc.set(homeKey, PersistentDataType.STRING,
                bell.getWorld().getName() + ":" + bell.getBlockX() + ":" + bell.getBlockY() + ":" + bell.getBlockZ());
        pdc.set(refreshKey, PersistentDataType.LONG, bell.getWorld().getFullTime());
    }

    private void tickVillageTraders(World world) {
        long now = world.getFullTime();
        for (WanderingTrader trader : world.getEntitiesByClass(WanderingTrader.class)) {
            PersistentDataContainer pdc = trader.getPersistentDataContainer();
            String home = pdc.get(homeKey, PersistentDataType.STRING);
            if (home == null) continue;
            String[] p = home.split(":");
            Location bell = new Location(world, Integer.parseInt(p[1]) + 0.5, Integer.parseInt(p[2]), Integer.parseInt(p[3]) + 0.5);
            if (trader.getLocation().distanceSquared(bell) > VILLAGE_RADIUS * VILLAGE_RADIUS) {
                trader.getPathfinder().moveTo(bell, 0.6);
            }
            long last = pdc.getOrDefault(refreshKey, PersistentDataType.LONG, now);
            if (now - last >= REFRESH_INTERVAL) {
                pdc.set(refreshKey, PersistentDataType.LONG, now);
                refreshTrades(trader);
            }
        }
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

        Post existing = posts.get(key(block));
        if (existing != null) {
            if (existing.trader != null) {
                player.sendMessage(Component.text("Торговец уже здесь!", NamedTextColor.YELLOW));
            } else {
                long left = Math.max(0, existing.nextVisit - block.getWorld().getFullTime());
                player.sendMessage(Component.text("До прихода торговца: " + formatTime(left), NamedTextColor.YELLOW));
            }
            return;
        }
        Post post = new Post();
        post.world = block.getWorld().getName();
        post.x = block.getX(); post.y = block.getY(); post.z = block.getZ();
        post.owner = player.getUniqueId();
        post.nextVisit = block.getWorld().getFullTime() + VISIT_INTERVAL;
        posts.put(post.key(), post);
        save();

        Location c = block.getLocation().add(0.5, 0.5, 0.5);
        block.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, c, 40, 0.5, 0.6, 0.5, 0.1);
        block.getWorld().spawnParticle(Particle.COMPOSTER, c.clone().add(0, 0.6, 0), 25, 0.3, 0.4, 0.3, 0.05);
        block.getWorld().playSound(c, Sound.ENTITY_WANDERING_TRADER_YES, 1f, 1f);
        player.sendMessage(Component.text("Теперь это пост торговца! Он придёт через " + formatTime(VISIT_INTERVAL) + ".",
                NamedTextColor.GREEN));
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Post post = posts.remove(key(event.getBlock()));
        if (post == null) return;
        if (getServer().getEntity(post.trader == null ? new UUID(0, 0) : post.trader) instanceof WanderingTrader wt) {
            startLeaving(post, wt);
            // Пост удалён, но уход нужно довести — отслеживаем отдельно
            leavingOrphans.add(post);
        }
        save();
        event.getPlayer().sendMessage(Component.text("Пост торговца разрушен — торговец больше не придёт.", NamedTextColor.GRAY));
    }

    /** Торговцы, чей пост разрушен, но они ещё уходят. */
    private final List<Post> leavingOrphans = new ArrayList<>();

    @EventHandler
    public void onDeath(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof WanderingTrader trader)) return;
        String key = trader.getPersistentDataContainer().get(postKey, PersistentDataType.STRING);
        Post post = key == null ? null : posts.get(key);
        if (post == null || !trader.getUniqueId().equals(post.trader)) return;
        long now = trader.getWorld().getFullTime();
        boolean early = !post.leaving;
        removeLlamas(post);
        post.trader = null;
        post.leaving = false;
        post.nextVisit = now + (early ? DEATH_INTERVAL : VISIT_INTERVAL);
        save();
    }

    private static String key(Block b) {
        return b.getWorld().getName() + ":" + b.getX() + ":" + b.getY() + ":" + b.getZ();
    }

    // ---------- Основной цикл (раз в 5 секунд) ----------

    private void tick() {
        for (World w : getServer().getWorlds()) tickVillageTraders(w);

        for (Post orphan : leavingOrphans.toArray(new Post[0])) {
            if (tickLeaving(orphan)) leavingOrphans.remove(orphan);
        }

        boolean dirty = false;
        for (Post post : posts.values().toArray(new Post[0])) {
            Location barrel = post.loc(getServer());
            if (barrel == null) continue;
            long now = barrel.getWorld().getFullTime();

            // Бочку убрали не руками игрока (взрыв, поршень и т.п.)
            if (barrel.isChunkLoaded() && barrel.getBlock().getType() != Material.BARREL) {
                posts.remove(post.key());
                if (post.trader != null && getServer().getEntity(post.trader) instanceof WanderingTrader wt) {
                    startLeaving(post, wt);
                    leavingOrphans.add(post);
                }
                dirty = true;
                continue;
            }

            if (post.trader == null) {
                if (now >= post.nextVisit && barrel.isChunkLoaded() && playerNear(barrel, 96)) {
                    if (startVisit(post, barrel)) dirty = true;
                }
                continue;
            }

            Entity e = getServer().getEntity(post.trader);
            if (!(e instanceof WanderingTrader trader) || !trader.isValid()) {
                // Пропал без события смерти (например, его удалили) — считаем визит законченным
                if (e == null && barrel.isChunkLoaded() && playerNear(barrel, 48)) {
                    endVisit(post, now, VISIT_INTERVAL);
                    dirty = true;
                }
                continue;
            }

            if (post.leaving) {
                if (tickLeaving(post)) { endVisit(post, now, VISIT_INTERVAL); dirty = true; }
                continue;
            }

            double distSq = trader.getLocation().distanceSquared(barrel);
            if (!post.reached) {
                if (distSq <= STAY_RADIUS * STAY_RADIUS) {
                    post.reached = true;
                    dirty = true;
                } else if (now - post.arrivedAt > ARRIVE_TIMEOUT) {
                    // Не смог дойти — появляется рядом
                    Location spot = findSpot(barrel, 1, (int) STAY_RADIUS);
                    if (spot == null) spot = nearestPlayerSpot(barrel);
                    if (spot != null) {
                        teleportWithLlamas(post, trader, spot);
                        post.reached = true;
                        dirty = true;
                    }
                } else {
                    trader.getPathfinder().moveTo(barrel, 0.6);
                }
            } else if (distSq > STAY_RADIUS * STAY_RADIUS) {
                trader.getPathfinder().moveTo(barrel, 0.6);
            }

            // Смена трейдов каждые полдня, только если торговец у бочки
            int due = (int) ((now - post.arrivedAt) / REFRESH_INTERVAL);
            if (due > post.refreshes && post.refreshes < REFRESHES && distSq <= (STAY_RADIUS + 1) * (STAY_RADIUS + 1)) {
                post.refreshes++;
                refreshTrades(trader);
                dirty = true;
            }

            if (now - post.arrivedAt >= STAY) {
                startLeaving(post, trader);
                dirty = true;
            }
        }
        if (dirty) save();
    }

    private void refreshTrades(WanderingTrader trader) {
        trader.setRecipes(TradePool.roll(random));
        trader.getWorld().playSound(trader.getLocation(), Sound.ENTITY_WANDERING_TRADER_TRADE, 1f, 1f);
        trader.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, trader.getLocation().add(0, 2, 0), 10, 0.3, 0.3, 0.3);
    }

    private boolean playerNear(Location loc, double radius) {
        for (Player p : loc.getWorld().getPlayers()) {
            if (p.getLocation().distanceSquared(loc) <= radius * radius) return true;
        }
        return false;
    }

    // ---------- Визит ----------

    private WanderingTrader spawnTraderEntity(Location spot) {
        spawning = true;
        try {
            return spot.getWorld().spawn(spot, WanderingTrader.class, t -> {
                t.setDespawnDelay(0); // исчезновением управляет плагин
                t.setRecipes(TradePool.roll(random));
                t.setPersistent(true);
                t.setRemoveWhenFarAway(false);
            });
        } finally {
            spawning = false;
        }
    }

    private boolean startVisit(Post post, Location barrel) {
        Location spot = findSpot(barrel, 20, 30);
        if (spot == null) spot = findSpot(barrel, 6, 20);
        if (spot == null) return false;
        WanderingTrader trader = spawnTraderEntity(spot);
        trader.getPersistentDataContainer().set(postKey, PersistentDataType.STRING, post.key());
        trader.setWanderingTowards(barrel);
        trader.getPathfinder().moveTo(barrel, 0.6);

        post.llamas.clear();
        spawning = true;
        try {
            for (int i = 0; i < 2; i++) {
                TraderLlama llama = spot.getWorld().spawn(spot.clone().add(random.nextDouble() * 2 - 1, 0, random.nextDouble() * 2 - 1),
                        TraderLlama.class, l -> {
                            l.setPersistent(true);
                            l.setRemoveWhenFarAway(false);
                        });
                llama.setLeashHolder(trader);
                post.llamas.add(llama.getUniqueId());
            }
        } finally {
            spawning = false;
        }

        post.trader = trader.getUniqueId();
        post.arrivedAt = barrel.getWorld().getFullTime();
        post.reached = false;
        post.refreshes = 0;
        post.leaving = false;

        Player owner = post.owner == null ? null : getServer().getPlayer(post.owner);
        if (owner != null && owner.getWorld().equals(barrel.getWorld())) {
            owner.sendMessage(Component.text("К вашему посту идёт странствующий торговец!", NamedTextColor.GREEN));
        }
        return true;
    }

    private void startLeaving(Post post, WanderingTrader trader) {
        post.leaving = true;
        post.leaveStarted = trader.getWorld().getFullTime();
        PotionEffect invis = new PotionEffect(PotionEffectType.INVISIBILITY, 20 * 120, 0, false, false);
        trader.addPotionEffect(invis);
        for (LivingEntity llama : llamas(post)) llama.addPotionEffect(invis);
        trader.getWorld().playSound(trader.getLocation(), Sound.ENTITY_WANDERING_TRADER_DRINK_POTION, 1f, 1f);
        trader.getWorld().playSound(trader.getLocation(), Sound.ENTITY_WANDERING_TRADER_DISAPPEARED, 1f, 1f);

        double angle = random.nextDouble() * Math.PI * 2;
        Location target = trader.getLocation().add(Math.cos(angle) * 60, 0, Math.sin(angle) * 60);
        target.setY(trader.getWorld().getHighestBlockYAt(target) + 1);
        trader.setWanderingTowards(target);
        trader.getPathfinder().moveTo(target, 0.7);
    }

    /** @return true, когда торговец исчез. */
    private boolean tickLeaving(Post post) {
        Entity e = post.trader == null ? null : getServer().getEntity(post.trader);
        if (!(e instanceof WanderingTrader trader) || !trader.isValid()) {
            removeLlamas(post);
            return true;
        }
        long now = trader.getWorld().getFullTime();
        Location from = post.loc(getServer());
        boolean far = from != null && trader.getLocation().distanceSquared(from) > 40 * 40;
        // Ушёл далеко — исчезает; не может уйти (заперт) — испаряется по таймеру
        if (far || now - post.leaveStarted > LEAVE_TIMEOUT) {
            trader.getWorld().spawnParticle(Particle.POOF, trader.getLocation().add(0, 1, 0), 20, 0.3, 0.6, 0.3, 0.02);
            trader.remove();
            removeLlamas(post);
            return true;
        }
        return false;
    }

    private void endVisit(Post post, long now, long interval) {
        post.trader = null;
        post.llamas.clear();
        post.leaving = false;
        post.refreshes = 0;
        post.nextVisit = now + interval;
    }

    private List<LivingEntity> llamas(Post post) {
        List<LivingEntity> out = new ArrayList<>();
        for (UUID id : post.llamas) {
            if (getServer().getEntity(id) instanceof LivingEntity l && l.isValid()) out.add(l);
        }
        return out;
    }

    private void removeLlamas(Post post) {
        for (LivingEntity l : llamas(post)) {
            l.getWorld().spawnParticle(Particle.POOF, l.getLocation().add(0, 1, 0), 10, 0.3, 0.5, 0.3, 0.02);
            l.remove();
        }
        post.llamas.clear();
    }

    private void teleportWithLlamas(Post post, WanderingTrader trader, Location spot) {
        trader.teleport(spot);
        for (LivingEntity l : llamas(post)) l.teleport(spot);
    }

    // ---------- Поиск места ----------

    /** Безопасное место на поверхности в кольце [minDist, maxDist] от точки. */
    private Location findSpot(Location center, int minDist, int maxDist) {
        World w = center.getWorld();
        for (int i = 0; i < 30; i++) {
            double angle = random.nextDouble() * Math.PI * 2;
            double dist = minDist + random.nextDouble() * (maxDist - minDist);
            int x = (int) Math.floor(center.getX() + Math.cos(angle) * dist);
            int z = (int) Math.floor(center.getZ() + Math.sin(angle) * dist);
            if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
            // Ищем пол рядом по высоте с центром (чтобы не на крыше и не в пещере под землёй)
            for (int dy = 4; dy >= -6; dy--) {
                Block ground = w.getBlockAt(x, center.getBlockY() + dy, z);
                Block feet = ground.getRelative(0, 1, 0), head = ground.getRelative(0, 2, 0);
                if (ground.getType().isSolid() && feet.isPassable() && !feet.isLiquid() && head.isPassable()) {
                    return feet.getLocation().add(0.5, 0, 0.5);
                }
            }
        }
        return null;
    }

    private Location nearestPlayerSpot(Location barrel) {
        Player nearest = null;
        double best = Double.MAX_VALUE;
        for (Player p : barrel.getWorld().getPlayers()) {
            double d = p.getLocation().distanceSquared(barrel);
            if (d < best) { best = d; nearest = p; }
        }
        return nearest == null ? null : findSpot(nearest.getLocation(), 2, 5);
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
            p.owner = uuid(s.getString("owner"));
            p.nextVisit = s.getLong("nextVisit");
            p.trader = uuid(s.getString("trader"));
            for (String id : s.getStringList("llamas")) p.llamas.add(UUID.fromString(id));
            p.arrivedAt = s.getLong("arrivedAt");
            p.reached = s.getBoolean("reached");
            p.refreshes = s.getInt("refreshes");
            p.leaving = s.getBoolean("leaving");
            p.leaveStarted = s.getLong("leaveStarted");
            posts.put(p.key(), p);
        }
    }

    private static UUID uuid(String s) {
        return s == null ? null : UUID.fromString(s);
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
            s.set("llamas", p.llamas.stream().map(UUID::toString).toList());
            s.set("arrivedAt", p.arrivedAt);
            s.set("reached", p.reached);
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
