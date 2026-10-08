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
        Vec3 eye = shooter.getEyePosition(partial);
        Vec3 tp = center(target);
        Vec3 vt = new Vec3(target.getX() - target.xo, target.getY() - target.yo, target.getZ() - target.zo);
        Vec3 vs = shooter.getDeltaMovement();
        Vec3 rel = tp.subtract(eye);
        double t = rel.length() / speed;
        Vec3 aim = rel;
        for (int i = 0; i < 4; i++) {
            aim = rel.add(vt.subtract(vs).scale(t)).add(0, 0.5 * grav * t * t, 0);
            t = aim.length() / speed;
        }
        return eye.add(aim);
    }
}
