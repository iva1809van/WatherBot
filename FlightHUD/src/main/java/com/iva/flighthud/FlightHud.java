package com.iva.flighthud;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.util.Mth;

/**
 * HUD в стиле истребителя: шкала тангажа, лента курса, скорость и высота
 * в рамках, маркер вектора скорости, предупреждения.
 */
final class FlightHud {
    private static final int GREEN = 0xFF33FF66;
    private static final int DIM = 0x9933FF66;
    private static final int AMBER = 0xFFFFC233;
    private static final int RED = 0xFFFF4040;

    private FlightHud() {}

    static void render(GuiGraphicsExtractor g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (!FlightHudClient.enabled || !FlightData.flying || mc.player == null || mc.options.hideGui) return;
        Font font = mc.font;
        int w = mc.getWindow().getGuiScaledWidth();
        int h = mc.getWindow().getGuiScaledHeight();
        int cx = w / 2, cy = h / 2;
        float pitch = mc.player.getXRot();
        float yaw = mc.player.getYRot();
        double ppd = h / (double) Math.max(30, mc.options.fov().get()); // пикселей на градус

        pitchLadder(g, font, cx, cy, pitch, ppd, w, h);
        headingTape(g, font, cx, yaw, w);
        reticle(g, cx, cy);
        velocityMarker(g, cx, cy, yaw, pitch, ppd);
        sideBoxes(g, font, cx, cy, w);
        status(g, font, w, h);
        warnings(g, font, cx, cy, h);
    }

    // ---------- примитивы ----------

    private static void hLine(GuiGraphicsExtractor g, int x1, int x2, int y, int c) {
        g.fill(Math.min(x1, x2), y, Math.max(x1, x2) + 1, y + 1, c);
    }

    private static void vLine(GuiGraphicsExtractor g, int x, int y1, int y2, int c) {
        g.fill(x, Math.min(y1, y2), x + 1, Math.max(y1, y2) + 1, c);
    }

    private static void box(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2, int c) {
        hLine(g, x1, x2, y1, c); hLine(g, x1, x2, y2, c);
        vLine(g, x1, y1, y2, c); vLine(g, x2, y1, y2, c);
    }

    // ---------- элементы ----------

    /** Шкала тангажа: линии каждые 10°, отрицательные — пунктиром. */
    private static void pitchLadder(GuiGraphicsExtractor g, Font font, int cx, int cy, float pitch, double ppd, int w, int h) {
        for (int deg = -90; deg <= 90; deg += 10) {
            // В MC взгляд вверх — отрицательный pitch. Линия «+deg» (над горизонтом)
            // при взгляде прямо стоит выше центра; когда смотрим вверх — опускается.
            int y = (int) Math.round(cy - (deg + pitch) * ppd);
            if (y < h * 0.18 || y > h * 0.82) continue;
            int half = deg == 0 ? 70 : 34;
            int gap = 18;
            int c = deg == 0 ? GREEN : DIM;
            if (deg >= 0) {
                hLine(g, cx - half, cx - gap, y, c);
                hLine(g, cx + gap, cx + half, y, c);
            } else {
                for (int x = cx - half; x < cx - gap; x += 6) hLine(g, x, Math.min(x + 3, cx - gap), y, c);
                for (int x = cx + gap; x < cx + half; x += 6) hLine(g, x, Math.min(x + 3, cx + half), y, c);
            }
            if (deg != 0) {
                vLine(g, cx - half, y, y + (deg > 0 ? 3 : -3), c);
                vLine(g, cx + half, y, y + (deg > 0 ? 3 : -3), c);
                String label = String.valueOf(Math.abs(deg));
                g.text(font, label, cx - half - 4 - font.width(label), y - 4, c, false);
                g.text(font, label, cx + half + 4, y - 4, c, false);
            }
        }
    }

    /** Лента курса вверху экрана. */
    private static void headingTape(GuiGraphicsExtractor g, Font font, int cx, float yaw, int w) {
        float heading = Mth.positiveModulo(yaw + 180f, 360f); // 0 = север
        int top = 6, span = 120, pxPerDeg = 2;
        hLine(g, cx - span, cx + span, top + 12, DIM);
        int start = (int) Math.floor(heading - span / (double) pxPerDeg);
        for (int d = start; d <= heading + span / (double) pxPerDeg; d++) {
            if (Math.floorMod(d, 5) != 0) continue;
            int x = (int) Math.round(cx + (d - heading) * pxPerDeg);
            int deg = Math.floorMod(d, 360);
            boolean major = deg % 15 == 0;
            vLine(g, x, top + 12, top + (major ? 7 : 10), DIM);
            if (deg % 45 == 0) {
                String s = switch (deg) {
                    case 0 -> "N"; case 90 -> "E"; case 180 -> "S"; case 270 -> "W";
                    default -> String.valueOf(deg);
                };
                g.centeredText(font, s, x, top - 3, deg % 90 == 0 ? GREEN : DIM);
            }
        }
        String hdg = String.format("%03d", Math.round(heading) % 360);
        box(g, cx - 13, top + 14, cx + 13, top + 25, GREEN);
        g.centeredText(font, hdg, cx, top + 16, GREEN);
    }

    /** Перекрестие по направлению взгляда. */
    private static void reticle(GuiGraphicsExtractor g, int cx, int cy) {
        hLine(g, cx - 12, cx - 5, cy, GREEN);
        hLine(g, cx + 5, cx + 12, cy, GREEN);
        vLine(g, cx, cy + 5, cy + 9, GREEN);
        g.fill(cx, cy, cx + 1, cy + 1, GREEN);
    }

    /** Маркер вектора скорости — куда реально летим. */
    private static void velocityMarker(GuiGraphicsExtractor g, int cx, int cy, float yaw, float pitch, double ppd) {
        if (FlightData.speed < 1) return;
        float dYaw = Mth.wrapDegrees(FlightData.velYaw - yaw);
        float dPitch = FlightData.velPitch - pitch;
        int x = (int) Math.round(cx + dYaw * ppd);
        int y = (int) Math.round(cy + dPitch * ppd);
        int c = FlightData.impactSeconds >= 0 && FlightData.impactSeconds < 3 ? RED : GREEN;
        box(g, x - 3, y - 3, x + 3, y + 3, c);
        hLine(g, x - 9, x - 4, y, c);
        hLine(g, x + 4, x + 9, y, c);
        vLine(g, x, y - 8, y - 4, c);
    }

    /** Слева скорость, справа высота — в рамках, как на истребителе. */
    private static void sideBoxes(GuiGraphicsExtractor g, Font font, int cx, int cy, int w) {
        int off = Math.min(150, w / 3);
        // Скорость
        int lx = cx - off;
        box(g, lx - 30, cy - 7, lx + 6, cy + 7, GREEN);
        String spd = String.valueOf(Math.round(FlightData.speed));
        g.text(font, spd, lx + 3 - font.width(spd), cy - 3, GREEN, false);
        g.text(font, "SPD б/с", lx - 30, cy + 10, DIM, false);
        g.text(font, String.format("%+.1f", FlightData.vSpeed), lx - 30, cy - 18, FlightData.vSpeed < -15 ? AMBER : DIM, false);

        // Высота
        int rx = cx + off;
        box(g, rx - 6, cy - 7, rx + 34, cy + 7, GREEN);
        g.text(font, String.valueOf(Math.round(FlightData.altitude)), rx - 2, cy - 3, GREEN, false);
        g.text(font, "ALT Y", rx - 6, cy + 10, DIM, false);
        String ra = FlightData.radarAlt < 0 ? "R ---" : "R " + Math.round(FlightData.radarAlt);
        g.text(font, ra, rx - 6, cy - 18, FlightData.radarAlt >= 0 && FlightData.radarAlt < 10 ? AMBER : DIM, false);
    }

    /** Нижний левый блок: элитры, ракеты, время полёта, координаты. */
    private static void status(GuiGraphicsExtractor g, Font font, int w, int h) {
        int x = 8, y = h - 64;
        int ec = FlightData.elytraPercent >= 0 && FlightData.elytraPercent < 15 ? RED : GREEN;
        g.text(font, "ELY " + (FlightData.elytraPercent < 0 ? "--" : FlightData.elytraPercent + "%"), x, y, ec, false);
        g.text(font, "RKT " + FlightData.rockets, x, y + 10, FlightData.rockets == 0 ? AMBER : GREEN, false);
        int sec = FlightData.flightTicks / 20;
        g.text(font, String.format("T+ %d:%02d", sec / 60, sec % 60), x, y + 20, DIM, false);
        var p = Minecraft.getInstance().player;
        g.text(font, String.format("POS %d %d", Math.round(p.getX()), Math.round(p.getZ())), x, y + 30, DIM, false);
    }

    /** Предупреждения по центру. Мигают. */
    private static void warnings(GuiGraphicsExtractor g, Font font, int cx, int cy, int h) {
        boolean blink = (System.currentTimeMillis() / 300) % 2 == 0;
        int y = cy + 40;
        if (FlightData.impactSeconds >= 0 && FlightData.impactSeconds < 2.5 && FlightData.speed > 8) {
            if (blink) { box(g, cx - 34, y - 3, cx + 34, y + 10, RED); g.centeredText(font, "PULL UP", cx, y, RED); }
            y += 16;
        }
        if (FlightData.speed < 6 && FlightData.flightTicks > 20) {
            g.centeredText(font, "STALL", cx, y, AMBER);
            y += 12;
        }
        if (FlightData.elytraPercent >= 0 && FlightData.elytraPercent < 10 && blink) {
            g.centeredText(font, "ELYTRA DMG", cx, y, RED);
        }
    }
}
