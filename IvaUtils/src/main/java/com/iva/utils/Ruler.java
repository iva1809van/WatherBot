package com.iva.utils;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/** Линейка: 1-е нажатие — точка А, 2-е — точка Б, 3-е — сброс. */
final class Ruler {
    private static BlockPos a, b;

    private Ruler() {}

    static void click(Minecraft mc) {
        if (a != null && b != null) { a = b = null; return; }
        if (!(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) return;
        if (a == null) a = hit.getBlockPos();
        else b = hit.getBlockPos();
    }

    /** Вторая точка: зафиксированная или блок под прицелом (живой предпросмотр). */
    private static BlockPos second(Minecraft mc) {
        if (b != null) return b;
        if (mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) return hit.getBlockPos();
        return null;
    }

    static void draw(Minecraft mc) {
        if (a == null) return;
        Gizmos.cuboid(a, GizmoStyle.stroke(0xFF55FF55, 2f)).setAlwaysOnTop();
        BlockPos s = second(mc);
        if (s == null) return;
        Gizmos.cuboid(s, GizmoStyle.stroke(0xFFFF5555, 2f)).setAlwaysOnTop();
        AABB box = new AABB(Vec3.atLowerCornerOf(a), Vec3.atLowerCornerOf(a).add(1, 1, 1))
                .minmax(new AABB(Vec3.atLowerCornerOf(s), Vec3.atLowerCornerOf(s).add(1, 1, 1)));
        Gizmos.cuboid(box, GizmoStyle.stroke(b != null ? 0xFFFFFF55 : 0x80FFFF55, 1.5f));
        Gizmos.line(Vec3.atCenterOf(a), Vec3.atCenterOf(s), 0xFFFFFFFF, 2f).setAlwaysOnTop();
    }

    static List<String> hudLines() {
        Minecraft mc = Minecraft.getInstance();
        if (a == null) return List.of();
        BlockPos s = second(mc);
        if (s == null) return List.of("§aЛинейка: точка А " + fmt(a));
        int dx = Math.abs(s.getX() - a.getX()) + 1;
        int dy = Math.abs(s.getY() - a.getY()) + 1;
        int dz = Math.abs(s.getZ() - a.getZ()) + 1;
        double dist = Math.sqrt(Math.pow(s.getX() - a.getX(), 2) + Math.pow(s.getY() - a.getY(), 2) + Math.pow(s.getZ() - a.getZ(), 2));
        return List.of(
                (b == null ? "§7" : "§e") + "Размер: " + dx + " × " + dy + " × " + dz + " блоков",
                String.format("§fРасстояние: %.2f   Площадь (X×Z): %d   Объём: %d", dist, dx * dz, dx * dy * dz),
                b == null ? "§7нажмите ещё раз, чтобы зафиксировать" : "§7нажмите ещё раз, чтобы сбросить");
    }

    private static String fmt(BlockPos p) {
        return p.getX() + " " + p.getY() + " " + p.getZ();
    }
}
