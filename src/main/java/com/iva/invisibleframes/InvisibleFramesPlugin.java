package com.iva.invisibleframes;

import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

public final class InvisibleFramesPlugin extends JavaPlugin implements Listener {

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFrameInteract(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        // ItemFrame покрывает и обычные, и светящиеся рамки (GlowItemFrame)
        if (!(event.getRightClicked() instanceof ItemFrame frame)) return;

        Player player = event.getPlayer();
        if (!player.isSneaking()) return;

        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand.getType() != Material.SHEARS) return;
        if (!player.hasPermission("shearframes.use")) return;

        // Не даём повернуть/вставить предмет в рамку
        event.setCancelled(true);

        boolean nowVisible = !frame.isVisible();
        frame.setVisible(nowVisible);

        player.getWorld().playSound(frame.getLocation(), Sound.ENTITY_SHEEP_SHEAR, 1.0f, 1.0f);
        if (player.getGameMode() != GameMode.CREATIVE) {
            player.getInventory().setItemInMainHand(hand.damage(1, player));
        }
    }
}
