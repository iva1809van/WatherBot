package com.iva.traderpost;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantRecipe;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/** Расширенный набор трейдов. Каждый раз выбирается случайный набор. */
final class TradePool {

    /** price изумрудов -> count предметов (продажа игроку) */
    private record Sell(Material item, int count, int price, int uses) {}
    /** count предметов -> price изумрудов (покупка у игрока) */
    private record Buy(Material item, int count, int emeralds, int uses) {}

    private static final List<Sell> SELLS = List.of(
            // Редкие растения и саженцы
            new Sell(Material.CHERRY_SAPLING, 1, 5, 6), new Sell(Material.MANGROVE_PROPAGULE, 1, 5, 6),
            new Sell(Material.PALE_OAK_SAPLING, 1, 6, 6), new Sell(Material.AZALEA, 1, 4, 6),
            new Sell(Material.BAMBOO, 4, 2, 8), new Sell(Material.CACTUS, 2, 2, 8),
            new Sell(Material.SUGAR_CANE, 4, 2, 8), new Sell(Material.PITCHER_POD, 1, 8, 3),
            new Sell(Material.TORCHFLOWER_SEEDS, 2, 6, 3), new Sell(Material.SPORE_BLOSSOM, 1, 10, 2),
            new Sell(Material.GLOW_BERRIES, 4, 3, 6), new Sell(Material.COCOA_BEANS, 4, 3, 6),
            new Sell(Material.NETHER_WART, 4, 6, 6), new Sell(Material.CHORUS_FLOWER, 1, 12, 2),
            // Декор и стройматериалы
            new Sell(Material.MOSS_BLOCK, 4, 3, 8), new Sell(Material.PACKED_ICE, 3, 4, 6),
            new Sell(Material.BLUE_ICE, 1, 8, 4), new Sell(Material.AMETHYST_SHARD, 4, 5, 6),
            new Sell(Material.POINTED_DRIPSTONE, 4, 3, 6), new Sell(Material.SEA_LANTERN, 2, 6, 4),
            new Sell(Material.SHROOMLIGHT, 2, 5, 4), new Sell(Material.GLOWSTONE, 4, 5, 6),
            new Sell(Material.PRISMARINE_SHARD, 6, 4, 6), new Sell(Material.NAUTILUS_SHELL, 1, 10, 2),
            new Sell(Material.SCULK, 4, 6, 4), new Sell(Material.MUD, 8, 2, 8),
            new Sell(Material.TERRACOTTA, 8, 3, 8), new Sell(Material.TINTED_GLASS, 2, 5, 4),
            // Красители и разное
            new Sell(Material.BLUE_DYE, 4, 2, 8), new Sell(Material.MAGENTA_DYE, 4, 2, 8),
            new Sell(Material.CYAN_DYE, 4, 2, 8), new Sell(Material.LIME_DYE, 4, 2, 8),
            new Sell(Material.NAME_TAG, 1, 15, 2), new Sell(Material.SADDLE, 1, 12, 2),
            new Sell(Material.LEAD, 2, 6, 4), new Sell(Material.SLIME_BALL, 2, 6, 4),
            new Sell(Material.GUNPOWDER, 4, 4, 6), new Sell(Material.ENDER_PEARL, 1, 8, 4),
            new Sell(Material.EXPERIENCE_BOTTLE, 3, 6, 6), new Sell(Material.TURTLE_SCUTE, 1, 12, 2),
            new Sell(Material.HEART_OF_THE_SEA, 1, 48, 1), new Sell(Material.MUSIC_DISC_CAT, 1, 20, 1),
            new Sell(Material.TRIDENT, 1, 40, 1), new Sell(Material.TOTEM_OF_UNDYING, 1, 50, 1),
            new Sell(Material.SNIFFER_EGG, 1, 32, 1), new Sell(Material.ARMADILLO_SCUTE, 2, 8, 3)
    );

    private static final List<Buy> BUYS = List.of(
            new Buy(Material.ROTTEN_FLESH, 32, 1, 8), new Buy(Material.BONE, 16, 1, 8),
            new Buy(Material.STRING, 16, 1, 8), new Buy(Material.SPIDER_EYE, 8, 1, 6),
            new Buy(Material.WHEAT, 24, 1, 8), new Buy(Material.POTATO, 24, 1, 8),
            new Buy(Material.CARROT, 24, 1, 8), new Buy(Material.PUMPKIN, 8, 1, 8),
            new Buy(Material.MELON_SLICE, 32, 1, 8), new Buy(Material.LEATHER, 8, 1, 8),
            new Buy(Material.FEATHER, 16, 1, 8), new Buy(Material.COBBLESTONE, 64, 1, 8),
            new Buy(Material.COAL, 16, 1, 8), new Buy(Material.IRON_INGOT, 6, 1, 8),
            new Buy(Material.GOLD_INGOT, 4, 1, 6), new Buy(Material.DIAMOND, 1, 4, 4)
    );

    private TradePool() {}

    /** Случайный набор: 5–7 товаров на продажу и 2–3 скупки. */
    static List<MerchantRecipe> roll(Random random) {
        List<Sell> sells = new ArrayList<>(SELLS);
        List<Buy> buys = new ArrayList<>(BUYS);
        Collections.shuffle(sells, random);
        Collections.shuffle(buys, random);

        List<MerchantRecipe> recipes = new ArrayList<>();
        int sellCount = 5 + random.nextInt(3);
        for (int i = 0; i < sellCount; i++) {
            Sell s = sells.get(i);
            MerchantRecipe r = new MerchantRecipe(new ItemStack(s.item(), s.count()), s.uses());
            r.addIngredient(new ItemStack(Material.EMERALD, Math.min(64, s.price())));
            r.setExperienceReward(true);
            recipes.add(r);
        }
        int buyCount = 2 + random.nextInt(2);
        for (int i = 0; i < buyCount; i++) {
            Buy b = buys.get(i);
            MerchantRecipe r = new MerchantRecipe(new ItemStack(Material.EMERALD, b.emeralds()), b.uses());
            r.addIngredient(new ItemStack(b.item(), b.count()));
            r.setExperienceReward(true);
            recipes.add(r);
        }
        return recipes;
    }
}
