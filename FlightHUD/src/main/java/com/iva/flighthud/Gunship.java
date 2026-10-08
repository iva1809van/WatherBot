package com.iva.flighthud;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

/**
 * Режим «AC-130»: автопилот ведёт по кругу (левый вираж) на постоянной высоте,
 * а мышь управляет камерой оператора. Включается только в полёте не ниже 50 блоков над землёй.
 */
public final class Gunship {
    public static final double MIN_AGL = 50;      // для включения
    public static final double EXIT_AGL = 35;     // ниже — режим выключается
    public static final double RADIUS = 48;       // радиус орбиты
    public static final double SPEED = 0.8;       // блоков/тик (~16 б/с)

    public static boolean active;
    public static boolean thermal;
    public static int zoomIndex;
    private static final double[] ZOOMS = {1, 2, 4, 8};
    static double centerX, centerZ, holdY;
    /** Захваченная цель: моб/игрок или точка на земле. */
    static net.minecraft.world.entity.Entity lockedEntity;
    static Vec3 lockedPoint;

    static boolean hasLock() { return lockedEntity != null || lockedPoint != null; }

    /** G: захват того, что в прицеле (существо или точка), повторно — сброс. */
    static void toggleLock(Minecraft mc) {
        if (!active) return;
        if (hasLock()) {
            lockedEntity = null;
            lockedPoint = null;
            msg(mc, "Захват сброшен", ChatFormatting.GRAY);
            return;
        }
        if (FlightData.hover != null) {
            lockedEntity = FlightData.hover.e();
            msg(mc, "Цель захвачена", ChatFormatting.RED);
            return;
        }
        var hit = mc.player.pick(512, 1f, false);
        if (hit.getType() != net.minecraft.world.phys.HitResult.Type.MISS) {
            lockedPoint = hit.getLocation();
            msg(mc, String.format("Точка захвачена: %d %d %d", Math.round(lockedPoint.x), Math.round(lockedPoint.y), Math.round(lockedPoint.z)), ChatFormatting.GOLD);
        } else {
            msg(mc, "Нечего захватывать: слишком далеко", ChatFormatting.GRAY);
        }
    }
    static int recTicks;

    private Gunship() {}

    public static double zoom() { return active ? ZOOMS[zoomIndex] : 1; }

    static String fovName() {
        return switch (zoomIndex) { case 0 -> "WIDE"; case 1 -> "MEDIUM"; case 2 -> "NARROW"; default -> "ULTRA"; };
    }

    static void toggle(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (active) { exit(mc, "Режим AC-130 выключен"); return; }
        if (p == null || !p.isFallFlying()) {
            msg(mc, "AC-130: нужно быть в полёте на элитрах", ChatFormatting.RED);
            return;
        }
        double agl = FlightData.radarAlt;
        if (agl >= 0 && agl < MIN_AGL) {
            msg(mc, String.format("AC-130: слишком низко (%.0f бл), нужно не меньше %.0f над землёй", agl, MIN_AGL), ChatFormatting.RED);
            return;
        }
        // Центр орбиты — слева от направления полёта
        Vec3 v = p.getDeltaMovement();
        double h = Math.hypot(v.x, v.z);
        double fx = h > 1e-3 ? v.x / h : -Math.sin(Math.toRadians(p.getYRot()));
        double fz = h > 1e-3 ? v.z / h : Math.cos(Math.toRadians(p.getYRot()));
        // left = (fz, -fx)
        centerX = p.getX() + fz * RADIUS;
        centerZ = p.getZ() - fx * RADIUS;
        holdY = p.getY();
        active = true;
        zoomIndex = 1;
        recTicks = 0;
        // Камеру — на центр орбиты, вниз
        double dx = centerX - p.getX(), dz = centerZ - p.getZ();
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        p.setYRot(yaw); p.yRotO = yaw;
        float pitch = (float) Math.toDegrees(Math.atan2(Math.max(1, agl < 0 ? 80 : agl), RADIUS));
        p.setXRot(pitch); p.xRotO = pitch;
        com.iva.flighthud.aero.Aero.roll = 0;
        msg(mc, "AC-130: орбита установлена", ChatFormatting.GREEN);
    }

    static void exit(Minecraft mc, String why) {
        active = false;
        lockedEntity = null;
        lockedPoint = null;
        msg(mc, why, ChatFormatting.GRAY);
    }

    static void msg(Minecraft mc, String s, ChatFormatting c) {
        mc.gui.setOverlayMessage(Component.literal(s).withStyle(c), false);
    }

    static void tick(Minecraft mc) {
        if (!active) return;
        LocalPlayer p = mc.player;
        if (p == null || !p.isFallFlying()) { exit(mc, "AC-130: полёт окончен"); return; }
        if (FlightData.radarAlt >= 0 && FlightData.radarAlt < EXIT_AGL) {
            exit(mc, "AC-130: слишком низко — режим выключен");
            return;
        }
        recTicks++;
        if (lockedEntity != null && (lockedEntity.isRemoved() || !lockedEntity.isAlive()
                || lockedEntity.distanceTo(p) > 300)) {
            lockedEntity = null;
            msg(mc, "Цель потеряна", ChatFormatting.GRAY);
        }
    }

    /** Скорость на орбите: касательная (левый вираж) + удержание радиуса и высоты. */
    public static Vec3 orbitVelocity(LocalPlayer p) {
        double dx = centerX - p.getX(), dz = centerZ - p.getZ();
        double d = Math.max(1e-3, Math.hypot(dx, dz));
        double nx = dx / d, nz = dz / d;
        // касательная, при которой центр слева: v = (-dz, dx)/|d|
        double vx = -nz * SPEED, vz = nx * SPEED;
        double corr = (d - RADIUS) * 0.08;
        vx += nx * corr;
        vz += nz * corr;
        double vy = Math.max(-0.4, Math.min(0.4, (holdY - p.getY()) * 0.08));
        return new Vec3(vx, vy, vz);
    }

    /** Направление полёта (курс), 0 = север. */
    static double trackHeading(LocalPlayer p) {
        Vec3 v = p.getDeltaMovement();
        return net.minecraft.util.Mth.positiveModulo((float) Math.toDegrees(Math.atan2(v.x, -v.z)), 360f);
    }
}
