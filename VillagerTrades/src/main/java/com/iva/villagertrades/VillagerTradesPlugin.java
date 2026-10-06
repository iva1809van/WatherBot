package com.iva.villagertrades;

import io.papermc.paper.event.player.PlayerTradeEvent;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.VillagerAcquireTradeEvent;
import org.bukkit.event.entity.VillagerReplenishTradeEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantInventory;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;

/**
 * У жителей каждый трейд доступен 64 раза, но не блокируется:
 * после каждых 64 сделок цена (первый предмет оплаты) растёт на 25%
 * от предыдущей: 32 -> 40 -> 50 -> 63 -> 64 (больше стака быть не может).
 * Когда житель пополняет запасы (как в ванили), цена опускается на один шаг.
 */
public final class VillagerTradesPlugin extends JavaPlugin implements Listener {

    private static final int TRADES_PER_TIER = 64;
    private static final double PRICE_STEP = 1.25;

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
    }

    private NamespacedKey key(String name, int index) {
        return new NamespacedKey(this, name + "_" + index);
    }

    /** Новые трейды сразу получают лимит 64. */
    @EventHandler(ignoreCancelled = true)
    public void onAcquire(VillagerAcquireTradeEvent event) {
        MerchantRecipe r = event.getRecipe();
        r.setMaxUses(TRADES_PER_TIER);
    }

    /** Старые жители: при открытии торговли подтягиваем лимиты и цены. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent event) {
        if (!(event.getInventory() instanceof MerchantInventory inv)) return;
        if (!(inv.getHolder() instanceof Villager villager)) return;
        for (int i = 0; i < villager.getRecipeCount(); i++) {
            applyPrice(villager, i);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTrade(PlayerTradeEvent event) {
        if (!(event.getVillager() instanceof Villager villager)) return;
        int index = findRecipe(villager, event.getTrade());
        if (index < 0) return;
        Player player = event.getPlayer();

        // Сделка применяется после события — обрабатываем на следующем тике
        getServer().getScheduler().runTask(this, () -> {
            if (!villager.isValid() || index >= villager.getRecipeCount()) return;
            PersistentDataContainer pdc = villager.getPersistentDataContainer();
            int count = pdc.getOrDefault(key("count", index), PersistentDataType.INTEGER, 0) + 1;
            int tier = pdc.getOrDefault(key("tier", index), PersistentDataType.INTEGER, 0);
            boolean priceChanged = false;
            if (count >= TRADES_PER_TIER) {
                count = 0;
                tier++;
                pdc.set(key("tier", index), PersistentDataType.INTEGER, tier);
                priceChanged = true;
            }
            pdc.set(key("count", index), PersistentDataType.INTEGER, count);
            applyPrice(villager, index);

            // Обновляем открытое окно, чтобы клиент увидел новую цену
            if (priceChanged && player.isOnline()
                    && player.getOpenInventory().getTopInventory() instanceof MerchantInventory inv
                    && inv.getHolder() == villager) {
                player.closeInventory();
                player.openMerchant(villager, true);
            }
        });
    }

    /** Житель пополняет запасы — цена этого трейда снижается на один шаг. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onReplenish(VillagerReplenishTradeEvent event) {
        if (!(event.getEntity() instanceof Villager villager)) return;
        int index = findRecipe(villager, event.getRecipe());
        if (index < 0) return;
        // Сброс использований выполняет игра после события — применяем на следующем тике
        getServer().getScheduler().runTask(this, () -> {
            if (!villager.isValid() || index >= villager.getRecipeCount()) return;
            PersistentDataContainer pdc = villager.getPersistentDataContainer();
            int tier = pdc.getOrDefault(key("tier", index), PersistentDataType.INTEGER, 0);
            pdc.set(key("tier", index), PersistentDataType.INTEGER, Math.max(0, tier - 1));
            pdc.set(key("count", index), PersistentDataType.INTEGER, 0);
            applyPrice(villager, index);
        });
    }

    /**
     * Пересобирает трейд: лимит 64, использования = счётчик сделок текущего
     * шага (чтобы житель понимал, что ему пора пополнить запасы), трейд никогда
     * не блокируется, цена = базовая * 1.25^уровень, ванильный спрос отключён.
     */
    private void applyPrice(Villager villager, int index) {
        MerchantRecipe old = villager.getRecipe(index);
        List<ItemStack> ingredients = new ArrayList<>(old.getIngredients());
        if (ingredients.isEmpty()) return;

        PersistentDataContainer pdc = villager.getPersistentDataContainer();
        NamespacedKey baseKey = key("base", index);
        Integer base = pdc.get(baseKey, PersistentDataType.INTEGER);
        if (base == null) {
            base = ingredients.get(0).getAmount();
            pdc.set(baseKey, PersistentDataType.INTEGER, base);
        }
        int tier = pdc.getOrDefault(key("tier", index), PersistentDataType.INTEGER, 0);
        ItemStack first = ingredients.get(0).clone();
        int price = (int) Math.round(base * Math.pow(PRICE_STEP, tier));
        first.setAmount(Math.max(1, Math.min(price, first.getMaxStackSize())));
        ingredients.set(0, first);

        int count = pdc.getOrDefault(key("count", index), PersistentDataType.INTEGER, 0);
        MerchantRecipe updated = new MerchantRecipe(
                old.getResult(), Math.min(count, TRADES_PER_TIER - 1), TRADES_PER_TIER, old.hasExperienceReward(),
                old.getVillagerExperience(), old.getPriceMultiplier(),
                0, old.getSpecialPrice());
        updated.setIngredients(ingredients);
        villager.setRecipe(index, updated);
    }

    private int findRecipe(Villager villager, MerchantRecipe trade) {
        for (int i = 0; i < villager.getRecipeCount(); i++) {
            MerchantRecipe r = villager.getRecipe(i);
            if (r.getResult().equals(trade.getResult())
                    && r.getIngredients().equals(trade.getIngredients())) {
                return i;
            }
        }
        return -1;
    }
}
