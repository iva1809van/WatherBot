package com.iva.flighthud;

import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Данные полёта, пересчитываются каждый тик. */
final class FlightData {
    static boolean flying;
    static double speed;         // блоков/с
    static double vSpeed;        // блоков/с, вверх +
    static double altitude;      // Y
    static double radarAlt = -1; // высота над землёй, -1 = больше 256
    static double impactSeconds = -1; // время до столкновения по курсу
    static float velYaw, velPitch;
    static int elytraPercent = -1;
    static int rockets;
    static int flightTicks;

    private FlightData() {}

    static void tick(Minecraft mc) {
        var p = mc.player;
        flying = p != null && p.isFallFlying();
        if (!flying) { flightTicks = 0; return; }
        flightTicks++;

        Vec3 v = p.getDeltaMovement();
        speed = v.length() * 20;
        vSpeed = v.y * 20;
        altitude = p.getY();
        double horiz = Math.sqrt(v.x * v.x + v.z * v.z);
        velYaw = (float) Math.toDegrees(Math.atan2(-v.x, v.z));
        velPitch = (float) -Math.toDegrees(Math.atan2(v.y, horiz));

        Vec3 pos = p.position();
        var down = mc.level.clip(new ClipContext(pos, pos.add(0, -256, 0), ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, p));
        radarAlt = down.getType() == HitResult.Type.MISS ? -1 : pos.y - down.getLocation().y;

        // Столкновение по вектору скорости в ближайшие 4 секунды
        impactSeconds = -1;
        if (v.lengthSqr() > 1e-4) {
            Vec3 ahead = pos.add(v.scale(80));
            var hit = mc.level.clip(new ClipContext(pos, ahead, ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, p));
            if (hit.getType() != HitResult.Type.MISS) {
                impactSeconds = hit.getLocation().distanceTo(pos) / Math.max(0.01, v.length()) / 20.0;
            }
        }

        ItemStack chest = p.getItemBySlot(EquipmentSlot.CHEST);
        elytraPercent = chest.isDamageableItem()
                ? (int) Math.round(100.0 * (chest.getMaxDamage() - chest.getDamageValue()) / chest.getMaxDamage()) : -1;

        int r = 0;
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
            ItemStack s = p.getInventory().getItem(i);
            if (s.is(Items.FIREWORK_ROCKET)) r += s.getCount();
        }
        rockets = r;
    }
}
