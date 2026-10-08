package com.iva.flighthud;

import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
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
    /** Фейерверки по длительности полёта: [0] — без данных/прочие, [1..3]. */
    static final int[] rocketsByPower = new int[4];
    static int flightTicks;
    static double groundSpeed;      // горизонтальная скорость, блоков/с
    static double glideRatio = -1;  // аэродинамическое качество (гориз./верт.)
    static double timeToGround = -1;// секунд до земли при текущем снижении
    static double accel;            // изменение скорости, блоков/с²
    static float health, maxHealth;
    /** Скорость изменения высоты, сглаженная (для шкалы VSI). */
    static double vsSmooth;
    static final java.util.List<Target> targets = new java.util.ArrayList<>();

    record Target(double yaw, double pitch, double dist, int color, String label, double w, double h, net.minecraft.world.entity.Entity e) {}

    /** Фейерверки на момент взлёта — 100% «батареи». */
    static int initialRockets;
    static int battery = -1;            // %, -1 = нет данных
    static int ping = -1;               // мс
    static double lookDist = -1;        // дистанция до точки под прицелом
    static Target acquired;             // цель в прицеле
    static Target hover;                // цель под прицелом (можно захватить)

    private static double lastSpeed;
    private static int warnCooldown;

    private FlightData() {}

    static void tick(Minecraft mc) {
        var p = mc.player;
        flying = p != null && p.isFallFlying();
        if (!flying) { flightTicks = 0; targets.clear(); acquired = null; return; }
        flightTicks++;

        Vec3 v = p.getDeltaMovement();
        speed = v.length() * 20;
        vSpeed = v.y * 20;
        altitude = p.getY();
        double horiz = Math.sqrt(v.x * v.x + v.z * v.z);
        groundSpeed = horiz * 20;
        glideRatio = v.y < -0.005 ? horiz / -v.y : -1;
        accel = (speed - lastSpeed) * 20;
        lastSpeed = speed;
        vsSmooth += (vSpeed - vsSmooth) * 0.25;
        health = p.getHealth();
        maxHealth = p.getMaxHealth();
        velYaw = (float) Math.toDegrees(Math.atan2(-v.x, v.z));
        velPitch = (float) -Math.toDegrees(Math.atan2(v.y, horiz));

        Vec3 pos = p.position();
        var down = mc.level.clip(new ClipContext(pos, pos.add(0, -256, 0), ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, p));
        radarAlt = down.getType() == HitResult.Type.MISS ? -1 : pos.y - down.getLocation().y;
        timeToGround = radarAlt >= 0 && vSpeed < -0.5 ? radarAlt / -vSpeed : -1;

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
        java.util.Arrays.fill(rocketsByPower, 0);
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
            ItemStack s = p.getInventory().getItem(i);
            if (s.is(Items.FIREWORK_ROCKET)) {
                r += s.getCount();
                var fw = s.get(net.minecraft.core.component.DataComponents.FIREWORKS);
                int pw = fw == null ? 1 : fw.flightDuration();
                rocketsByPower[Math.max(0, Math.min(3, pw))] += s.getCount();
            }
        }
        rockets = r;

        if (flightTicks == 1 || rockets > initialRockets) initialRockets = rockets;
        battery = initialRockets > 0 ? (int) Math.round(100.0 * rockets / initialRockets) : -1;

        var conn = mc.getConnection();
        var info = conn == null ? null : conn.getPlayerInfo(p.getUUID());
        ping = info == null ? -1 : info.getLatency();

        var look = p.pick(512, 1f, false);
        lookDist = look.getType() == HitResult.Type.MISS ? -1 : look.getLocation().distanceTo(p.getEyePosition());

        collectTargets(mc);
        acquired = null;
        double best = Double.MAX_VALUE;
        for (Target t : targets) {
            double tol = Math.toDegrees(Math.atan2(Math.max(t.w(), t.h()) / 2 + 0.5, t.dist())) + 1;
            if (Math.abs(Mth.wrapDegrees(t.yaw() - p.getYRot())) < tol && Math.abs(t.pitch() - p.getXRot()) < tol && t.dist() < best) {
                best = t.dist();
                acquired = t;
            }
        }
        sounds(mc);
    }

    /** Мобы и игроки в радиусе 96 блоков: угол относительно взгляда и дистанция. */
    private static void collectTargets(Minecraft mc) {
        targets.clear();
        var p = mc.player;
        Vec3 eye = p.getEyePosition();
        for (var e : mc.level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class,
                p.getBoundingBox().inflate(96), en -> en != p && en.isAlive())) {
            Vec3 d = e.getBoundingBox().getCenter().subtract(eye);
            double dist = d.length();
            if (dist > 96) continue;
            double yaw = Math.toDegrees(Math.atan2(-d.x, d.z));
            double pitch = -Math.toDegrees(Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)));
            int color;
            String label = null;
            if (e instanceof net.minecraft.world.entity.player.Player pl) {
                color = 0xFF55DDFF;
                label = pl.getName().getString();
            } else if (e instanceof net.minecraft.world.entity.monster.Enemy) {
                color = 0xFFFF5555;
            } else {
                color = 0xFF66FF66;
            }
            targets.add(new Target(yaw, pitch, dist, color, label, e.getBbWidth(), e.getBbHeight(), e));
        }
    }

    /** Звуковые предупреждения. */
    private static void sounds(Minecraft mc) {
        if (!FlightHudClient.enabled) return;
        if (warnCooldown > 0) { warnCooldown--; return; }
        var p = mc.player;
        if (impactSeconds >= 0 && impactSeconds < 2.5 && speed > 8) {
            p.playSound(net.minecraft.sounds.SoundEvents.NOTE_BLOCK_BIT.value(), 1f, 1.8f);
            warnCooldown = 5;
        } else if (speed < 6 && flightTicks > 20) {
            p.playSound(net.minecraft.sounds.SoundEvents.NOTE_BLOCK_BASS.value(), 0.8f, 0.7f);
            warnCooldown = 20;
        } else if (elytraPercent >= 0 && elytraPercent < 10) {
            p.playSound(net.minecraft.sounds.SoundEvents.NOTE_BLOCK_PLING.value(), 0.6f, 0.5f);
            warnCooldown = 40;
        }
    }
}
