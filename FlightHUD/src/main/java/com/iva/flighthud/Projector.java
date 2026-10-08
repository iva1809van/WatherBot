package com.iva.flighthud;

import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Точная проекция точки мира на экран через реальную камеру этого кадра
 * (с учётом интерполяции, крена, зума и движения цели).
 */
final class Projector {
    static float partial = 1f;
    private static Vec3 camPos = Vec3.ZERO;
    private static final Quaternionf inv = new Quaternionf();
    private static double tanHalf = 1;
    private static int w, h;

    private Projector() {}

    static void begin(Minecraft mc, DeltaTracker delta, int width, int height) {
        partial = delta.getGameTimeDeltaPartialTick(true);
        Camera cam = mc.gameRenderer.getMainCamera();
        camPos = cam.position();
        inv.set(cam.rotation()).conjugate();
        double fov = cam.getFov();
        if (!(fov > 1 && fov < 179)) fov = mc.options.fov().get() / Gunship.zoom();
        tanHalf = Math.tan(Math.toRadians(fov) / 2);
        w = width;
        h = height;
    }

    /** @return {x, y} на экране или null, если точка позади камеры. */
    static int[] project(Vec3 p) {
        Vector3f v = new Vector3f((float) (p.x - camPos.x), (float) (p.y - camPos.y), (float) (p.z - camPos.z));
        inv.transform(v);
        if (v.z > -0.05f) return null;
        double k = (h / 2.0) / tanHalf / -v.z;
        return new int[]{(int) Math.round(w / 2.0 + v.x * k), (int) Math.round(h / 2.0 - v.y * k)};
    }

    /** Центр цели в этом кадре (интерполированный). */
    static Vec3 center(Entity e) {
        return e.getPosition(partial).add(0, e.getBbHeight() / 2, 0);
    }

    /** Экранный радиус объекта размером size на дистанции dist. */
    static int radius(double size, double dist) {
        return (int) Math.max(3, (size / 2) / Math.max(0.1, dist) * (h / 2.0) / tanHalf);
    }

    /**
     * Точка упреждения: куда целиться, чтобы снаряд со скоростью speed (блоков/тик)
     * и гравитацией grav попал в движущуюся цель с учётом своей скорости.
     */
    static Vec3 leadPoint(Entity shooter, Entity target, double speed, double grav) {
        Vec3 vt = new Vec3(target.getX() - target.xo, target.getY() - target.yo, target.getZ() - target.zo);
        return leadPoint(shooter, center(target), vt, speed, grav);
    }

    /** Сопротивление воздуха снаряда за тик (стрела/трезубец). */
    static final double DRAG = 0.99;

    /**
     * Упреждение для точки tp, движущейся со скоростью vt (блоков/тик).
     * Пошагово моделируем полёт как в игре: снаряд получает скорость выстрела + скорость
     * стрелка, каждый тик скорость умножается на DRAG и падает на grav.
     * Ищем число тиков n, при котором нужная скорость выстрела равна реальной,
     * и возвращаем точку далеко по направлению выстрела (для проекции на экран).
     */
    static Vec3 leadPoint(Entity shooter, Vec3 tp, Vec3 vt, double speed, double grav) {
        Vec3 eye = shooter.getEyePosition(partial);
        Vec3 vs = shooter.getDeltaMovement();
        double drag = grav > 0 ? DRAG : 1.0;
        Vec3 bestDir = tp.subtract(eye).normalize();
        double bestErr = Double.MAX_VALUE;
        double sum = 0;          // сумма drag^k — «путь» единичной скорости за n тиков
        double gravDrop = 0;     // смещение по Y от гравитации за n тиков
        double gv = 0;           // текущая «гравитационная» скорость
        for (int n = 1; n <= 300; n++) {
            sum += Math.pow(drag, n - 1);
            gravDrop += gv;
            gv = gv * drag - grav;
            // где будет цель через n тиков
            Vec3 target = tp.add(vt.scale(n));
            // (u + vs) * sum + (0, gravDrop, 0) = target - eye
            Vec3 need = target.subtract(eye).subtract(0, gravDrop, 0).scale(1.0 / sum).subtract(vs);
            double err = Math.abs(need.length() - speed);
            if (err < bestErr) {
                bestErr = err;
                bestDir = need.normalize();
            }
            // дальше нужная скорость только уменьшается — нашли переход, хватит
            if (need.length() < speed) break;
        }
        return eye.add(bestDir.scale(200));
    }
}
