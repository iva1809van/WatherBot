package com.iva.aero;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Ориентация «самолёта». Храним крен; курс и тангаж — это yRot/xRot игрока.
 * Все повороты считаются в связанной с самолётом системе координат:
 * f — вперёд, u — вверх (крыша кабины), r — вправо (правое крыло).
 */
public final class Aero {
    /** Чувствительность: градусов крена на градус движения мыши по X. */
    public static final double ROLL_SENS = 1.6;
    /** Рысканье от A/D, градусов за тик. */
    public static final double RUDDER_RATE = 2.0;
    /** Скорость разворота при крене 90°, градусов курса за тик. */
    public static final double BANK_TURN_RATE = 3.5;

    /** 1 или -1: направление наклона камеры (на случай, если окажется зеркальным). */
    public static final int CAMERA_SIGN = 1;

    public static boolean enabled = true;
    /** Текущий крен в градусах, + = правое крыло вниз. */
    public static double roll;

    private Aero() {}

    public static boolean active(LocalPlayer p) {
        return enabled && p != null && p.isFallFlying();
    }

    // ---------- векторная математика ----------

    static Vec3 forward(double yaw, double pitch) {
        double y = Math.toRadians(yaw), x = Math.toRadians(pitch);
        return new Vec3(-Math.sin(y) * Math.cos(x), -Math.sin(x), Math.cos(y) * Math.cos(x));
    }

    /** Правое крыло без крена для курса yaw. */
    static Vec3 levelRight(double yaw) {
        double y = Math.toRadians(yaw);
        return new Vec3(-Math.cos(y), 0, -Math.sin(y));
    }

    private static Vec3 rot(Vec3 a, Vec3 b, double ang) {
        // a·cos + b·sin
        return a.scale(Math.cos(ang)).add(b.scale(Math.sin(ang)));
    }

    /**
     * Повернуть самолёт: dPitch — нос вниз (+), dRoll — правое крыло вниз (+),
     * dYaw — нос вправо (+). Все в градусах, в связанных осях.
     */
    public static void rotate(LocalPlayer p, double dPitch, double dRoll, double dYaw) {
        double yaw = p.getYRot(), pitch = p.getXRot();
        Vec3 f = forward(yaw, pitch);
        Vec3 r0 = levelRight(yaw);
        Vec3 u0 = r0.cross(f);
        double rr = Math.toRadians(roll);
        Vec3 u = rot(u0, r0, rr);
        Vec3 r = rot(r0, u0, -rr);

        // крен — вокруг f
        if (dRoll != 0) {
            double a = Math.toRadians(dRoll);
            Vec3 nu = rot(u, r, a);
            r = rot(r, u, -a);
            u = nu;
        }
        // тангаж — вокруг r (нос вниз = f к -u)
        if (dPitch != 0) {
            double a = Math.toRadians(dPitch);
            Vec3 nf = rot(f, u, -a);
            u = rot(u, f, a);
            f = nf;
        }
        // рысканье — вокруг u (нос вправо = f к r)
        if (dYaw != 0) {
            double a = Math.toRadians(dYaw);
            Vec3 nf = rot(f, r, a);
            r = rot(r, f, -a);
            f = nf;
        }
        f = f.normalize();
        u = u.normalize();

        // Обратно в курс/тангаж/крен
        double newPitch = Math.toDegrees(-Math.asin(Mth.clamp(f.y, -1, 1)));
        double horiz = Math.sqrt(f.x * f.x + f.z * f.z);
        double newYaw = horiz < 1e-4 ? yaw : Math.toDegrees(Math.atan2(-f.x, f.z));
        Vec3 nr0 = levelRight(newYaw);
        Vec3 nu0 = nr0.cross(f);
        roll = Mth.wrapDegrees(Math.toDegrees(Math.atan2(u.dot(nr0), u.dot(nu0))));

        apply(p, newYaw, newPitch);
    }

    /** Ставим курс/тангаж, сдвигая и «прошлые» значения, чтобы не было рывков интерполяции. */
    static void apply(LocalPlayer p, double newYaw, double newPitch) {
        float dy = Mth.wrapDegrees((float) newYaw - p.getYRot());
        float dx = (float) newPitch - p.getXRot();
        p.setYRot(p.getYRot() + dy);
        p.setXRot((float) newPitch);
        p.yRotO += dy;
        p.xRotO += dx;
        p.yHeadRot = p.getYRot();
        p.yHeadRotO += dy;
    }

    /** Каждый тик: разворот от крена, руль направления, выравнивание после посадки. */
    public static void tick(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (!active(p)) {
            roll = 0;
            return;
        }
        double rudder = 0;
        if (mc.options.keyLeft.isDown()) rudder -= RUDDER_RATE;
        if (mc.options.keyRight.isDown()) rudder += RUDDER_RATE;
        if (rudder != 0) rotate(p, 0, 0, rudder);

        // Координированный разворот: крен поворачивает курс вокруг вертикали
        double rr = Math.toRadians(roll);
        double turn = Math.sin(rr) * Math.cos(Math.toRadians(p.getXRot())) * BANK_TURN_RATE;
        if (Math.abs(turn) > 1e-3) p.setYRot(p.getYRot() + (float) turn);
    }
}
