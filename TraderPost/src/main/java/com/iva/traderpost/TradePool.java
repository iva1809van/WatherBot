package com.iva.traderpost;

import org.bukkit.Material;
import org.bukkit.Registry;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.FireworkMeta;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Трейды торговца, 5 уровней редкости:
 * 1 обычное (3 слота), 2 необычное (2 слота), 3 редкое (1 слот, 40%),
 * 4 эпическое (1 слот, 12%), 5 легендарное (у каждого товара свой шанс).
 * Плюс всегда 1–2 скупки из всего, что покупают жители любых профессий.
 */
final class TradePool {

    /** Продажа игроку: count предметов за цену в диапазоне [min, max] изумрудов. */
    private record Sell(Material item, int count, int min, int max, int uses) {}
    /** Скупка у игрока: count предметов за 1 изумруд (как у жителей). */
    private record Buy(Material item, int count) {}
    /** Легендарный товар со своим шансом. */
    private record Legend(Material item, int min, int max, double chance) {}

    // ---------- 1. Обычное ----------
    private static final List<Sell> COMMON = List.of(
            new Sell(Material.CHERRY_SAPLING, 1, 3, 5, 6), new Sell(Material.MANGROVE_PROPAGULE, 1, 3, 5, 6),
            new Sell(Material.PALE_OAK_SAPLING, 1, 4, 6, 6), new Sell(Material.AZALEA, 1, 3, 4, 6),
            new Sell(Material.GLOW_BERRIES, 4, 2, 3, 8), new Sell(Material.COCOA_BEANS, 4, 2, 3, 8),
            new Sell(Material.NETHER_WART, 4, 4, 6, 6), new Sell(Material.MOSS_BLOCK, 4, 2, 3, 8),
            new Sell(Material.PITCHER_POD, 1, 5, 7, 4), new Sell(Material.TORCHFLOWER_SEEDS, 2, 4, 6, 4),
            new Sell(Material.BLUE_DYE, 4, 1, 2, 8), new Sell(Material.MAGENTA_DYE, 4, 1, 2, 8),
            new Sell(Material.CYAN_DYE, 4, 1, 2, 8), new Sell(Material.LIME_DYE, 4, 1, 2, 8),
            new Sell(Material.PINK_DYE, 4, 1, 2, 8), new Sell(Material.LIGHT_BLUE_DYE, 4, 1, 2, 8),
            new Sell(Material.GUNPOWDER, 4, 2, 3, 8), new Sell(Material.PAPER, 12, 1, 2, 8),
            new Sell(Material.BOOK, 3, 2, 3, 8), new Sell(Material.BOOKSHELF, 1, 3, 5, 6)
    );
    /** Фейерверки длительности полёта 1–3 без эффектов — тоже обычные. */
    private static final double FIREWORK_SHARE = 0.25;

    // ---------- 2. Необычное ----------
    private static final List<Sell> UNCOMMON = List.of(
            new Sell(Material.BLUE_ICE, 2, 6, 9, 4), new Sell(Material.PACKED_ICE, 4, 4, 6, 4),
            new Sell(Material.AMETHYST_SHARD, 4, 4, 6, 4), new Sell(Material.SEA_LANTERN, 2, 5, 8, 4),
            new Sell(Material.SHROOMLIGHT, 2, 4, 6, 4), new Sell(Material.GLOWSTONE, 4, 4, 6, 4),
            new Sell(Material.PRISMARINE_SHARD, 6, 3, 5, 4), new Sell(Material.SCULK, 4, 5, 8, 4),
            new Sell(Material.TINTED_GLASS, 2, 4, 6, 4), new Sell(Material.POINTED_DRIPSTONE, 4, 3, 4, 4),
            new Sell(Material.SLIME_BALL, 2, 5, 7, 4), new Sell(Material.LEAD, 2, 5, 7, 3),
            new Sell(Material.SPORE_BLOSSOM, 1, 8, 12, 2), new Sell(Material.EXPERIENCE_BOTTLE, 4, 6, 9, 3),
            new Sell(Material.GUNPOWDER, 16, 6, 9, 3), new Sell(Material.BOOKSHELF, 4, 10, 14, 2)
    );

    // ---------- 3. Редкое (40%) ----------
    private static final double RARE_CHANCE = 0.40;
    private static final List<Sell> RARE = List.of(
            new Sell(Material.NAME_TAG, 1, 12, 18, 2), new Sell(Material.SADDLE, 1, 10, 16, 2),
            new Sell(Material.ENDER_PEARL, 2, 10, 14, 2), new Sell(Material.ARMADILLO_SCUTE, 2, 10, 14, 2),
            new Sell(Material.CHORUS_FLOWER, 1, 12, 18, 2), new Sell(Material.TURTLE_SCUTE, 1, 12, 18, 2),
            new Sell(Material.NAUTILUS_SHELL, 1, 12, 20, 2), new Sell(Material.EXPERIENCE_BOTTLE, 12, 16, 24, 1)
    );

    // ---------- 4. Эпическое (12%) ----------
    private static final double EPIC_CHANCE = 0.12;
    private static final List<Sell> EPIC = List.of(
            new Sell(Material.SNIFFER_EGG, 1, 24, 36, 1), new Sell(Material.MUSIC_DISC_CAT, 1, 20, 30, 1),
            new Sell(Material.MUSIC_DISC_PIGSTEP, 1, 30, 45, 1), new Sell(Material.ENDER_PEARL, 8, 24, 36, 1),
            new Sell(Material.COAST_ARMOR_TRIM_SMITHING_TEMPLATE, 1, 20, 32, 1),
            new Sell(Material.WILD_ARMOR_TRIM_SMITHING_TEMPLATE, 1, 20, 32, 1),
            new Sell(Material.DUNE_ARMOR_TRIM_SMITHING_TEMPLATE, 1, 20, 32, 1),
            new Sell(Material.ENCHANTED_GOLDEN_APPLE, 1, 40, 56, 1)
    );

    // ---------- 5. Легендарное ----------
    private static final List<Legend> LEGENDARY = List.of(
            new Legend(Material.NETHERITE_SCRAP, 10, 25, 0.04),
            new Legend(Material.WITHER_SKELETON_SKULL, 45, 64, 0.01),
            new Legend(Material.TOTEM_OF_UNDYING, 40, 60, 0.02),
            new Legend(Material.TRIDENT, 35, 55, 0.01)
    );
    private static final double BOOK_CHANCE = 0.06;

    // ---------- Скупка: всё, что покупают жители всех профессий ----------
    private static final List<Buy> BUYS = List.of(
            // фермер
            new Buy(Material.WHEAT, 20), new Buy(Material.POTATO, 26), new Buy(Material.CARROT, 22),
            new Buy(Material.BEETROOT, 15), new Buy(Material.PUMPKIN, 6), new Buy(Material.MELON, 4),
            // рыбак
            new Buy(Material.STRING, 20), new Buy(Material.COAL, 10), new Buy(Material.COD, 15),
            new Buy(Material.SALMON, 13), new Buy(Material.TROPICAL_FISH, 6), new Buy(Material.PUFFERFISH, 4),
            new Buy(Material.OAK_BOAT, 1), new Buy(Material.SPRUCE_BOAT, 1), new Buy(Material.BIRCH_BOAT, 1),
            new Buy(Material.JUNGLE_BOAT, 1), new Buy(Material.ACACIA_BOAT, 1), new Buy(Material.DARK_OAK_BOAT, 1),
            // пастух
            new Buy(Material.WHITE_WOOL, 18), new Buy(Material.BROWN_WOOL, 18), new Buy(Material.BLACK_WOOL, 18),
            new Buy(Material.GRAY_WOOL, 18),
            // лучник
            new Buy(Material.STICK, 32), new Buy(Material.FLINT, 26), new Buy(Material.FEATHER, 24),
            new Buy(Material.TRIPWIRE_HOOK, 8),
            // библиотекарь и картограф
            new Buy(Material.PAPER, 24), new Buy(Material.BOOK, 4), new Buy(Material.INK_SAC, 5),
            new Buy(Material.WRITABLE_BOOK, 2), new Buy(Material.GLASS_PANE, 11), new Buy(Material.COMPASS, 1),
            // священник
            new Buy(Material.ROTTEN_FLESH, 32), new Buy(Material.GOLD_INGOT, 3), new Buy(Material.RABBIT_FOOT, 2),
            new Buy(Material.TURTLE_SCUTE, 4), new Buy(Material.GLASS_BOTTLE, 9), new Buy(Material.NETHER_WART, 22),
            // бронник, оружейник, инструментальщик
            new Buy(Material.IRON_INGOT, 4), new Buy(Material.LAVA_BUCKET, 1), new Buy(Material.DIAMOND, 1),
            // мясник
            new Buy(Material.CHICKEN, 14), new Buy(Material.PORKCHOP, 7), new Buy(Material.RABBIT, 4),
            new Buy(Material.MUTTON, 7), new Buy(Material.BEEF, 10), new Buy(Material.DRIED_KELP_BLOCK, 10),
            new Buy(Material.SWEET_BERRIES, 10),
            // кожевник
            new Buy(Material.LEATHER, 6), new Buy(Material.RABBIT_HIDE, 9),
            // каменщик
            new Buy(Material.CLAY_BALL, 10), new Buy(Material.STONE, 20), new Buy(Material.GRANITE, 16),
            new Buy(Material.ANDESITE, 16), new Buy(Material.DIORITE, 16), new Buy(Material.QUARTZ, 12)
    );
    private static final int BUY_USES = 16;

    private TradePool() {}

    static List<MerchantRecipe> roll(Random random) {
        List<MerchantRecipe> legendary = new ArrayList<>();
        for (Legend l : LEGENDARY) {
            if (random.nextDouble() < l.chance()) {
                legendary.add(recipe(new ItemStack(l.item()), between(random, l.min(), l.max()), 1));
            }
        }
        if (random.nextDouble() < BOOK_CHANCE) legendary.add(randomBook(random));

        List<MerchantRecipe> recipes = new ArrayList<>(legendary);
        if (random.nextDouble() < EPIC_CHANCE) recipes.add(pick(EPIC, 1, random).get(0));
        if (random.nextDouble() < RARE_CHANCE) recipes.add(pick(RARE, 1, random).get(0));
        recipes.addAll(pick(UNCOMMON, 2, random));
        // 3 обычных слота: каждый с шансом 25% — фейерверк, иначе обычный товар
        int fireworks = 0;
        for (int i = 0; i < 3; i++) if (random.nextDouble() < FIREWORK_SHARE) fireworks++;
        if (fireworks > 0) recipes.add(firework(random));
        recipes.addAll(pick(COMMON, 3 - Math.min(fireworks, 1), random));

        List<Buy> buys = new ArrayList<>(BUYS);
        Collections.shuffle(buys, random);
        int buyCount = 1 + random.nextInt(2);
        for (int i = 0; i < buyCount; i++) {
            Buy b = buys.get(i);
            MerchantRecipe r = new MerchantRecipe(new ItemStack(Material.EMERALD), BUY_USES);
            r.addIngredient(new ItemStack(b.item(), b.count()));
            r.setExperienceReward(true);
            recipes.add(r);
        }
        return recipes;
    }

    private static List<MerchantRecipe> pick(List<Sell> pool, int n, Random random) {
        List<Sell> copy = new ArrayList<>(pool);
        Collections.shuffle(copy, random);
        List<MerchantRecipe> out = new ArrayList<>();
        for (int i = 0; i < Math.min(n, copy.size()); i++) {
            Sell s = copy.get(i);
            out.add(recipe(new ItemStack(s.item(), s.count()), between(random, s.min(), s.max()), s.uses()));
        }
        return out;
    }

    private static MerchantRecipe recipe(ItemStack result, int price, int uses) {
        MerchantRecipe r = new MerchantRecipe(result, uses);
        r.addIngredient(new ItemStack(Material.EMERALD, Math.max(1, Math.min(64, price))));
        r.setExperienceReward(true);
        return r;
    }

    /** Фейерверк с длительностью полёта 1–3 без звёзд: 3–8 шт., цена растёт с уровнем. */
    private static MerchantRecipe firework(Random random) {
        int power = between(random, 1, 3);
        ItemStack rocket = new ItemStack(Material.FIREWORK_ROCKET, 8);
        if (rocket.getItemMeta() instanceof FireworkMeta meta) {
            meta.setPower(power);
            rocket.setItemMeta(meta);
        }
        return recipe(rocket, power + 1, 8);
    }

    private static int between(Random r, int min, int max) {
        return min + r.nextInt(max - min + 1);
    }

    /**
     * Любое зачарование любого уровня. Цена как у библиотекаря
     * (2 + rand(0..5+10·ур) + 3·ур, ×2 для сокровищ), затем +50%, максимум 64. Нужна 1 книга.
     */
    private static MerchantRecipe randomBook(Random random) {
        List<Enchantment> all = new ArrayList<>();
        Registry.ENCHANTMENT.forEach(all::add);
        Enchantment ench = all.get(random.nextInt(all.size()));
        int level = between(random, ench.getStartLevel(), ench.getMaxLevel());

        int price = 2 + random.nextInt(5 + level * 10) + 3 * level;
        if (ench.isTreasure()) price *= 2;
        price = (int) Math.ceil(price * 1.5);

        ItemStack result = new ItemStack(Material.ENCHANTED_BOOK);
        if (result.getItemMeta() instanceof EnchantmentStorageMeta meta) {
            meta.addStoredEnchant(ench, level, true);
            result.setItemMeta(meta);
        }
        MerchantRecipe r = recipe(result, price, 1);
        r.addIngredient(new ItemStack(Material.BOOK));
        return r;
    }
}
