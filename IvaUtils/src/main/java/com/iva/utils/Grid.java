package com.iva.utils;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Сетка: границы текущего и соседних чанков, сетка блоков вокруг и центр блока под прицелом. */
final class Grid {
    private static boolean enabled = false;
    private static final int CHUNK_COLOR = 0xFFFFAA00;
    private static final int NEIGHBOUR_COLOR = 0x80FFAA00;
    private static final int GRID_COLOR = 0x60FFFFFF;
    private static final int CENTER_COLOR = 0xFF55FFFF;

    private Grid() {}

    static void toggle(Minecraft mc) {
        enabled = !enabled;
        mc.gui.setOverlayMessage(Component.literal("Сетка: " + (enabled ? "ВКЛ" : "ВЫКЛ"))
                .withStyle(enabled ? ChatFormatting.GREEN : ChatFormatting.GRAY), false);
    }

    static void draw(Minecraft mc) {
        if (!enabled) return;
        BlockPos feet = mc.player.blockPosition();
        double y0 = feet.getY() - 4, y1 = feet.getY() + 12;
        int cx = feet.getX() >> 4, cz = feet.getZ() >> 4;

        // Границы чанков: текущий ярко, соседние — бледнее
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                int x = (cx + dx) << 4, z = (cz + dz) << 4;
                int color = dx == 0 && dz == 0 ? CHUNK_COLOR : NEIGHBOUR_COLOR;
                for (int[] c : new int[][]{{0, 0}, {16, 0}, {0, 16}, {16, 16}}) {
                    Gizmos.line(new Vec3(x + c[0], y0, z + c[1]), new Vec3(x + c[0], y1, z + c[1]), color, 2f);
                }
                double y = feet.getY();
                Gizmos.line(new Vec3(x, y, z), new Vec3(x + 16, y, z), color, 2f);
                Gizmos.line(new Vec3(x, y, z), new Vec3(x, y, z + 16), color, 2f);
            }
        }

        // Сетка блоков на уровне ног в радиусе 8
        double gy = feet.getY() + 0.01;
        for (int i = -8; i <= 9; i++) {
            Gizmos.line(new Vec3(feet.getX() + i, gy, feet.getZ() - 8), new Vec3(feet.getX() + i, gy, feet.getZ() + 9), GRID_COLOR, 1f);
            Gizmos.line(new Vec3(feet.getX() - 8, gy, feet.getZ() + i), new Vec3(feet.getX() + 9, gy, feet.getZ() + i), GRID_COLOR, 1f);
        }

        // Блок под прицелом: рамка и крест через центр
        if (mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
            BlockPos p = hit.getBlockPos();
            Gizmos.cuboid(p, GizmoStyle.stroke(CENTER_COLOR, 2f));
            Vec3 c = Vec3.atCenterOf(p);
            Gizmos.line(c.add(-0.7, 0, 0), c.add(0.7, 0, 0), CENTER_COLOR, 2f).setAlwaysOnTop();
            Gizmos.line(c.add(0, -0.7, 0), c.add(0, 0.7, 0), CENTER_COLOR, 2f).setAlwaysOnTop();
            Gizmos.line(c.add(0, 0, -0.7), c.add(0, 0, 0.7), CENTER_COLOR, 2f).setAlwaysOnTop();
        }
    }
}
