package com.iva.flighthud;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.util.Mth;

/**
 * HUD пилота. Два режима:
 * истребитель — зелёная символика, шкала тангажа, лента курса;
 * дрон — белая символика, рамка камеры, развёртка, REC, телеметрия.
 */
final class FlightHud {
    private static final int AMBER = 0xFFFFC233;
    private static final int RED = 0xFFFF4040;

    private static int main() { return FlightHudClient.droneMode ? 0xFFE8E8E8 : 0xFF33FF66; }
    private static int dim() { return FlightHudClient.droneMode ? 0x99E8E8E8 : 0x9933FF66; }

    private FlightHud() {}

    private static boolean visible(Minecraft mc) {
        return FlightHudClient.enabled && FlightData.flying && mc.player != null && !mc.options.hideGui;
    }

    /** Подложка под HUD (рисуется до ванильного интерфейса): эффект камеры дрона. */
    static void renderOverlay(GuiGraphicsExtractor g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (!visible(mc) || !FlightHudClient.droneMode) return;
        int w = mc.getWindow().getGuiScaledWidth();
        int h = mc.getWindow().getGuiScaledHeight();
        // Лёгкое затемнение и строки развёртки
        g.fill(0, 0, w, h, 0x30101810);
        for (int y = 0; y < h; y += 3) g.fill(0, y, w, y + 1, 0x18000000);
        // Виньетка по краям
        for (int i = 0; i < 24; i++) {
            int a = (int) (0x70 * (1 - i / 24.0)) << 24;
            g.fill(i, i, w - i, i + 1, a);
            g.fill(i, h - i - 1, w - i, h - i, a);
            g.fill(i, i, i + 1, h - i, a);
            g.fill(w - i - 1, i, w - i, h - i, a);
        }
    }

    static void render(GuiGraphicsExtractor g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (!visible(mc)) return;
        Font font = mc.font;
        int w = mc.getWindow().getGuiScaledWidth();
        int h = mc.getWindow().getGuiScaledHeight();
        int cx = w / 2, cy = h / 2;
        float pitch = mc.player.getXRot();
        float yaw = mc.player.getYRot();
        double ppd = h / (double) Math.max(30, mc.options.fov().get()); // пикселей на градус

        if (FlightHudClient.droneMode) {
            droneFrame(g, font, w, h);
        } else {
            pitchLadder(g, font, cx, cy, pitch, ppd, h);
        }
        headingTape(g, font, cx, yaw);
        targets(g, font, cx, cy, yaw, pitch, ppd, w, h);
        reticle(g, cx, cy);
        velocityMarker(g, cx, cy, yaw, pitch, ppd);
        sideBoxes(g, font, cx, cy, w, h);
        status(g, font, h);
        telemetry(g, font, w, h);
        warnings(g, font, cx, cy);
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

    private static void diamond(GuiGraphicsExtractor g, int x, int y, int r, int c) {
        for (int i = 0; i <= r; i++) {
            g.fill(x - r + i, y - i, x - r + i + 1, y - i + 1, c);
            g.fill(x + r - i, y - i, x + r - i + 1, y - i + 1, c);
            g.fill(x - r + i, y + i, x - r + i + 1, y + i + 1, c);
            g.fill(x + r - i, y + i, x + r - i + 1, y + i + 1, c);
        }
    }

    // ---------- элементы ----------

    /** Шкала тангажа: линии каждые 10°, ниже горизонта — пунктиром. */
    private static void pitchLadder(GuiGraphicsExtractor g, Font font, int cx, int cy, float pitch, double ppd, int h) {
        for (int deg = -90; deg <= 90; deg += 10) {
            // В MC взгляд вверх — отрицательный pitch
            int y = (int) Math.round(cy - (deg + pitch) * ppd);
            if (y < h * 0.18 || y > h * 0.82) continue;
            int half = deg == 0 ? 70 : 34;
            int gap = 18;
            int c = deg == 0 ? main() : dim();
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

    /** Рамка камеры дрона: уголки, REC, масштаб. */
    private static void droneFrame(GuiGraphicsExtractor g, Font font, int w, int h) {
        int m = 14, l = 22, c = main();
        hLine(g, m, m + l, m, c); vLine(g, m, m, m + l, c);
        hLine(g, w - m - l, w - m, m, c); vLine(g, w - m, m, m + l, c);
        hLine(g, m, m + l, h - m, c); vLine(g, m, h - m - l, h - m, c);
        hLine(g, w - m - l, w - m, h - m, c); vLine(g, w - m, h - m - l, h - m, c);
        boolean blink = (System.currentTimeMillis() / 500) % 2 == 0;
        if (blink) g.fill(m + 6, m + 8, m + 11, m + 13, RED);
        g.text(font, "REC", m + 14, m + 7, RED, false);
        int sec = FlightData.flightTicks / 20;
        g.text(font, String.format("%02d:%02d:%02d", sec / 3600, sec / 60 % 60, sec % 60), m + 36, m + 7, c, false);
        g.text(font, "CAM 1  EO  1.0x", w - m - 4 - font.width("CAM 1  EO  1.0x"), m + 7, c, false);
        // Центральная рамка захвата
        int cx = w / 2, cy = h / 2;
        box(g, cx - 40, cy - 28, cx + 40, cy + 28, dim());
    }

    /** Лента курса вверху экрана. */
    private static void headingTape(GuiGraphicsExtractor g, Font font, int cx, float yaw) {
        float heading = Mth.positiveModulo(yaw + 180f, 360f); // 0 = север
        int top = FlightHudClient.droneMode ? 28 : 6, span = 120, pxPerDeg = 2;
        hLine(g, cx - span, cx + span, top + 12, dim());
        int start = (int) Math.floor(heading - span / (double) pxPerDeg);
        for (int d = start; d <= heading + span / (double) pxPerDeg; d++) {
            if (Math.floorMod(d, 5) != 0) continue;
            int x = (int) Math.round(cx + (d - heading) * pxPerDeg);
            int deg = Math.floorMod(d, 360);
            boolean major = deg % 15 == 0;
            vLine(g, x, top + 12, top + (major ? 7 : 10), dim());
            if (deg % 45 == 0) {
                String s = switch (deg) {
                    case 0 -> "N"; case 90 -> "E"; case 180 -> "S"; case 270 -> "W";
                    default -> String.valueOf(deg);
                };
                g.centeredText(font, s, x, top - 3, deg % 90 == 0 ? main() : dim());
            }
        }
        String hdg = String.format("%03d", Math.round(heading) % 360);
        box(g, cx - 13, top + 14, cx + 13, top + 25, main());
        g.centeredText(font, hdg, cx, top + 16, main());
    }

    /** Отметки мобов и игроков: ромб, дистанция, ник. За краем экрана — стрелка у края. */
    private static void targets(GuiGraphicsExtractor g, Font font, int cx, int cy, float yaw, float pitch, double ppd, int w, int h) {
        for (FlightData.Target t : FlightData.targets) {
            double dYaw = Mth.wrapDegrees(t.yaw() - yaw);
            double dPitch = t.pitch() - pitch;
            int x = (int) Math.round(cx + dYaw * ppd);
            int y = (int) Math.round(cy + dPitch * ppd);
            boolean onScreen = Math.abs(dYaw) < 80 && x > 20 && x < w - 20 && y > 40 && y < h - 20;
            if (!onScreen) {
                if (t.label() == null) continue; // за экраном показываем только игроков
                x = Mth.clamp(x, 24, w - 24);
                y = Mth.clamp(y, 44, h - 24);
                g.centeredText(font, Math.abs(dYaw) > 90 ? "▼" : "◆", x, y - 4, t.color());
                g.centeredText(font, t.label(), x, y + 6, t.color());
                continue;
            }
            int r = t.dist() < 20 ? 6 : 4;
            diamond(g, x, y, r, t.color());
            String d = Math.round(t.dist()) + "м";
            g.text(font, d, x + r + 3, y - 4, t.color(), false);
            if (t.label() != null) g.centeredText(font, t.label(), x, y - r - 11, t.color());
        }
    }

    /** Перекрестие по направлению взгляда. */
    private static void reticle(GuiGraphicsExtractor g, int cx, int cy) {
        int c = main();
        if (FlightHudClient.droneMode) {
            hLine(g, cx - 16, cx - 4, cy, c); hLine(g, cx + 4, cx + 16, cy, c);
            vLine(g, cx, cy - 16, cy - 4, c); vLine(g, cx, cy + 4, cy + 16, c);
            return;
        }
        hLine(g, cx - 12, cx - 5, cy, c);
        hLine(g, cx + 5, cx + 12, cy, c);
        vLine(g, cx, cy + 5, cy + 9, c);
        g.fill(cx, cy, cx + 1, cy + 1, c);
    }

    /** Маркер вектора скорости — куда реально летим. */
    private static void velocityMarker(GuiGraphicsExtractor g, int cx, int cy, float yaw, float pitch, double ppd) {
        if (FlightData.speed < 1) return;
        float dYaw = Mth.wrapDegrees(FlightData.velYaw - yaw);
        float dPitch = FlightData.velPitch - pitch;
        int x = (int) Math.round(cx + dYaw * ppd);
        int y = (int) Math.round(cy + dPitch * ppd);
        int c = FlightData.impactSeconds >= 0 && FlightData.impactSeconds < 3 ? RED : main();
        box(g, x - 3, y - 3, x + 3, y + 3, c);
        hLine(g, x - 9, x - 4, y, c);
        hLine(g, x + 4, x + 9, y, c);
        vLine(g, x, y - 8, y - 4, c);
    }

    /** Слева скорость и ускорение, справа высота и шкала вертикальной скорости. */
    private static void sideBoxes(GuiGraphicsExtractor g, Font font, int cx, int cy, int w, int h) {
        int off = Math.min(150, w / 3);
        int c = main(), d = dim();

        // Скорость
        int lx = cx - off;
        box(g, lx - 30, cy - 7, lx + 6, cy + 7, c);
        String spd = String.valueOf(Math.round(FlightData.speed));
        g.text(font, spd, lx + 3 - font.width(spd), cy - 3, c, false);
        g.text(font, "SPD б/с", lx - 30, cy + 10, d, false);
        String acc = (FlightData.accel >= 0 ? "▲" : "▼") + String.format("%.1f", Math.abs(FlightData.accel));
        g.text(font, acc, lx - 30, cy - 18, Math.abs(FlightData.accel) > 15 ? AMBER : d, false);
        g.text(font, "GS " + Math.round(FlightData.groundSpeed), lx - 30, cy + 20, d, false);

        // Высота
        int rx = cx + off;
        box(g, rx - 6, cy - 7, rx + 34, cy + 7, c);
        g.text(font, String.valueOf(Math.round(FlightData.altitude)), rx - 2, cy - 3, c, false);
        g.text(font, "ALT Y", rx - 6, cy + 10, d, false);
        String ra = FlightData.radarAlt < 0 ? "R ---" : "R " + Math.round(FlightData.radarAlt);
        g.text(font, ra, rx - 6, cy - 18, FlightData.radarAlt >= 0 && FlightData.radarAlt < 10 ? AMBER : d, false);

        // Шкала вертикальной скорости (VSI): ±30 б/с, стрелка
        int vx = rx + 42, top = cy - 40, bottom = cy + 40;
        vLine(g, vx, top, bottom, d);
        for (int v = -30; v <= 30; v += 10) {
            int y = cy - (int) Math.round(v / 30.0 * 40);
            hLine(g, vx, vx + (v == 0 ? 5 : 3), y, d);
        }
        double vs = Mth.clamp(FlightData.vsSmooth, -30, 30);
        int vy = cy - (int) Math.round(vs / 30.0 * 40);
        int vc = FlightData.vsSmooth < -15 ? AMBER : c;
        g.fill(vx - 4, vy, vx, vy + 1, vc);
        g.fill(vx - 3, vy - 1, vx - 1, vy + 2, vc);
        g.text(font, String.format("%+.1f", FlightData.vsSmooth), vx + 7, vy - 4, vc, false);
        g.text(font, "VS", vx - 2, bottom + 4, d, false);
    }

    /** Нижний левый блок: элитры, ракеты, здоровье, время, координаты. */
    private static void status(GuiGraphicsExtractor g, Font font, int h) {
        int x = 8 + (FlightHudClient.droneMode ? 14 : 0), y = h - 76 - (FlightHudClient.droneMode ? 10 : 0);
        int ec = FlightData.elytraPercent >= 0 && FlightData.elytraPercent < 15 ? RED : main();
        g.text(font, "ELY " + (FlightData.elytraPercent < 0 ? "--" : FlightData.elytraPercent + "%"), x, y, ec, false);
        g.text(font, "RKT " + FlightData.rockets, x, y + 10, FlightData.rockets == 0 ? AMBER : main(), false);
        int hc = FlightData.health <= FlightData.maxHealth * 0.3 ? RED : main();
        g.text(font, String.format("HP  %.0f/%.0f", FlightData.health, FlightData.maxHealth), x, y + 20, hc, false);
        int sec = FlightData.flightTicks / 20;
        g.text(font, String.format("T+  %d:%02d", sec / 60, sec % 60), x, y + 30, dim(), false);
        var p = Minecraft.getInstance().player;
        g.text(font, String.format("POS %d %d %d", Math.round(p.getX()), Math.round(p.getY()), Math.round(p.getZ())), x, y + 40, dim(), false);
    }

    /** Нижний правый блок: качество, время до земли, до столкновения, время суток. */
    private static void telemetry(GuiGraphicsExtractor g, Font font, int w, int h) {
        int y = h - 66 - (FlightHudClient.droneMode ? 10 : 0);
        int right = w - 8 - (FlightHudClient.droneMode ? 14 : 0);
        String[] lines = {
                "GLIDE " + (FlightData.glideRatio < 0 ? "--" : String.format("%.1f:1", Math.min(99, FlightData.glideRatio))),
                "TTG " + (FlightData.timeToGround < 0 ? "--" : String.format("%.1fс", FlightData.timeToGround)),
                "TTI " + (FlightData.impactSeconds < 0 ? "--" : String.format("%.1fс", FlightData.impactSeconds)),
                "CLK " + clock()
        };
        for (int i = 0; i < lines.length; i++) {
            int c = (i == 2 && FlightData.impactSeconds >= 0 && FlightData.impactSeconds < 3) ? RED : dim();
            g.text(font, lines[i], right - font.width(lines[i]), y + i * 10, c, false);
        }
    }

    private static String clock() {
        var level = Minecraft.getInstance().level;
        if (level == null) return "--:--";
        long t = (level.getOverworldClockTime() + 6000) % 24000;
        return String.format("%02d:%02d", t / 1000, t % 1000 * 60 / 1000);
    }

    /** Предупреждения по центру. Мигают. */
    private static void warnings(GuiGraphicsExtractor g, Font font, int cx, int cy) {
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
            y += 12;
        }
        if (FlightData.rockets == 0 && FlightData.flightTicks > 20) {
            g.centeredText(font, "NO BOOST", cx, y, AMBER);
        }
    }
}
