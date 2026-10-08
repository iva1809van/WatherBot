package com.iva.flighthud;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Хотбар в полёте:
 * AC-130 — только лук, арбалет, трезубец (сами переносятся в слоты 1–3);
 * обычный полёт — только фейерверки, каждый вид в своём слоте.
 * Остальные слоты выбрать нельзя: колесо и цифры перескакивают на разрешённые.
 */
final class HotbarManager {
    private static int lastSlot = -1;
    private static int cooldown;

    private HotbarManager() {}

    private static boolean isWeapon(ItemStack s) {
        return s.is(Items.BOW) || s.is(Items.CROSSBOW) || s.is(Items.TRIDENT);
    }

    private static boolean isRocket(ItemStack s) {
        return s.is(Items.FIREWORK_ROCKET);
    }

    static void tick(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null || mc.screen != null || mc.gameMode == null) { lastSlot = -1; return; }
        Predicate<ItemStack> allowed;
        if (Gunship.active) allowed = HotbarManager::isWeapon;
        else if (p.isFallFlying()) allowed = HotbarManager::isRocket;
        else { lastSlot = -1; return; }

        Inventory inv = p.getInventory();
        if (cooldown > 0) cooldown--;
        else arrange(mc, p, inv, allowed);

        // Ограничение выбора слота
        int sel = inv.getSelectedSlot();
        if (!allowed.test(inv.getItem(sel))) {
            int dir = lastSlot < 0 ? 1 : (Math.floorMod(sel - lastSlot, 9) <= 4 ? 1 : -1);
            for (int i = 1; i <= 9; i++) {
                int s = Math.floorMod(sel + dir * i, 9);
                if (allowed.test(inv.getItem(s))) { inv.setSelectedSlot(s); sel = s; break; }
            }
        }
        lastSlot = sel;
    }

    /** Переносит по одному нужному предмету за раз из рюкзака в хотбар. */
    private static void arrange(Minecraft mc, LocalPlayer p, Inventory inv, Predicate<ItemStack> allowed) {
        // Какие разные виды уже есть в хотбаре
        List<ItemStack> inHotbar = new ArrayList<>();
        for (int i = 0; i < 9; i++) if (allowed.test(inv.getItem(i))) inHotbar.add(inv.getItem(i));

        for (int i = 9; i < 36; i++) {
            ItemStack s = inv.getItem(i);
            if (!allowed.test(s)) continue;
            boolean already = false;
            for (ItemStack h : inHotbar) if (ItemStack.isSameItemSameComponents(h, s)) { already = true; break; }
            if (already) continue;
            // Свободный (или занятый чем-то посторонним) слот хотбара, по порядку 1..9
            for (int slot = 0; slot < 9; slot++) {
                if (allowed.test(inv.getItem(slot))) continue;
                // В меню инвентаря рюкзак — слоты 9..35, кнопка = номер слота хотбара
                mc.gameMode.handleInventoryMouseClick(p.inventoryMenu.containerId, i, slot, ClickType.SWAP, p);
                cooldown = 2;
                return;
            }
            return; // хотбар заполнен разрешёнными предметами
        }
    }
}
