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
        if (!visible(mc) || !Gunship.active) return;
        if (true) { GunshipHud.overlay(g, mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight()); return; }
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
        Projector.begin(mc, delta, w, h);
        if (Gunship.active) { GunshipHud.render(g, mc, w, h); return; }

        if (FlightHudClient.droneMode) {
            droneFrame(g, font, w, h);
        } else {
            pitchLadder(g, font, cx, cy, pitch, ppd, h);
        }
        headingTape(g, font, cx, yaw);
        if (!FlightHudClient.droneMode) {
            modeRow(g, font, w);
            horizonHeading(g, font, cx, cy, yaw, pitch, ppd, h);
            compassRose(g, font, cx, h, yaw);
        }
        waypointMarker(g, font, cx, cy, yaw, pitch, ppd, w, h);
        targets(g, font, cx, cy, yaw, pitch, ppd, w, h);
        reticle(g, cx, cy);
        velocityMarker(g, cx, cy, yaw, pitch, ppd);
        speedTape(g, font, cx, cy, w);
        altitudeTape(g, font, cx, cy, w);
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

    /** Крен в радианах для поворота символики (0, если управление самолётом выключено). */
    private static double rollRad() {
        return com.iva.flighthud.aero.Aero.active(Minecraft.getInstance().player) ? Math.toRadians(com.iva.flighthud.aero.Aero.roll) : 0;
    }

    /**
     * Поворот смещения (dx, dy) от центра экрана вместе с горизонтом.
     * Правый крен — горизонт на экране поворачивается против часовой.
     */
    private static int[] rotate(int cx, int cy, double dx, double dy, double a) {
        double c = Math.cos(a), s = Math.sin(a);
        return new int[]{(int) Math.round(cx + dx * c + dy * s), (int) Math.round(cy - dx * s + dy * c)};
    }

    /** Линия под любым углом (точками по 1 пикселю). */
    private static void line(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2, int c) {
        if (y1 == y2) { hLine(g, x1, x2, y1, c); return; }
        if (x1 == x2) { vLine(g, x1, y1, y2, c); return; }
        int n = Math.max(Math.abs(x2 - x1), Math.abs(y2 - y1));
        for (int i = 0; i <= n; i++) {
            int x = x1 + (x2 - x1) * i / n, y = y1 + (y2 - y1) * i / n;
            g.fill(x, y, x + 1, y + 1, c);
        }
    }

    private static void rLine(GuiGraphicsExtractor g, int cx, int cy, double x1, double y1, double x2, double y2, double a, int c) {
        int[] p = rotate(cx, cy, x1, y1, a), q = rotate(cx, cy, x2, y2, a);
        line(g, p[0], p[1], q[0], q[1], c);
    }

    // ---------- элементы ----------

    /** Шкала тангажа: линии каждые 10°, ниже горизонта — пунктиром. Наклоняется вместе с креном. */
    private static void pitchLadder(GuiGraphicsExtractor g, Font font, int cx, int cy, float pitch, double ppd, int h) {
        double a = rollRad();
        for (int deg = -90; deg <= 90; deg += 10) {
            // В MC взгляд вверх — отрицательный pitch
            double y = -(deg + pitch) * ppd;
            if (Math.abs(y) > h * 0.32) continue;
            int half = deg == 0 ? 70 : 34;
            int gap = 18;
            int c = deg == 0 ? main() : dim();
            if (deg >= 0) {
                rLine(g, cx, cy, -half, y, -gap, y, a, c);
                rLine(g, cx, cy, gap, y, half, y, a, c);
            } else {
                for (int x = -half; x < -gap; x += 6) rLine(g, cx, cy, x, y, Math.min(x + 3, -gap), y, a, c);
                for (int x = gap; x < half; x += 6) rLine(g, cx, cy, x, y, Math.min(x + 3, half), y, a, c);
            }
            if (deg != 0) {
                double tick = deg > 0 ? 3 : -3;
                rLine(g, cx, cy, -half, y, -half, y + tick, a, c);
                rLine(g, cx, cy, half, y, half, y + tick, a, c);
                String label = String.valueOf(Math.abs(deg));
                int[] l = rotate(cx, cy, -half - 4 - font.width(label) / 2.0, y, a);
                int[] r = rotate(cx, cy, half + 4 + font.width(label) / 2.0, y, a);
                g.centeredText(font, label, l[0], l[1] - 4, c);
                g.centeredText(font, label, r[0], r[1] - 4, c);
            }
        }
        bankIndicator(g, font, cx, cy, h);
    }

    /** Шкала крена: дуга с отметками 0/10/20/30/45/60° и указатель. */
    private static void bankIndicator(GuiGraphicsExtractor g, Font font, int cx, int cy, int h) {
        if (!com.iva.flighthud.aero.Aero.active(Minecraft.getInstance().player)) return;
        int r = (int) (h * 0.30);
        int[] marks = {-60, -45, -30, -20, -10, 0, 10, 20, 30, 45, 60};
        for (int m : marks) {
            double t = Math.toRadians(m);
            int len = (m % 30 == 0) ? 6 : 3;
            int x1 = (int) Math.round(cx + Math.sin(t) * r), y1 = (int) Math.round(cy + 0 - Math.cos(t) * r) + 0;
            int x2 = (int) Math.round(cx + Math.sin(t) * (r + len)), y2 = (int) Math.round(cy - Math.cos(t) * (r + len));
            // дуга снизу экрана (как в кабине — указатель «небо» сверху), рисуем сверху центра
            line(g, x1, y1, x2, y2, dim());
        }
        double roll = com.iva.flighthud.aero.Aero.roll;
        double t = Math.toRadians(Mth.clamp(roll, -70, 70));
        int px = (int) Math.round(cx + Math.sin(t) * (r - 2)), py = (int) Math.round(cy - Math.cos(t) * (r - 2));
        int c = Math.abs(roll) > 60 ? AMBER : main();
        line(g, px, py, (int) Math.round(px - Math.sin(t + 0.5) * 6 + Math.sin(t) * 0), (int) Math.round(py + Math.cos(t + 0.5) * 6), c);
        line(g, px, py, (int) Math.round(px - Math.sin(t - 0.5) * 6), (int) Math.round(py + Math.cos(t - 0.5) * 6), c);
        String txt = String.format("%s%.0f°", roll > 0.5 ? "R" : roll < -0.5 ? "L" : "", Math.abs(roll));
        g.centeredText(font, txt, cx, cy - r - 16, c);
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

    /** Отметки мобов и игроков: ромб, дистанция, ник. Позиция — точная проекция через камеру. */
    private static void targets(GuiGraphicsExtractor g, Font font, int cx, int cy, float yaw, float pitch, double ppd, int w, int h) {
        for (FlightData.Target t : FlightData.targets) {
            if (t.e().isRemoved()) continue;
            int[] pos = Projector.project(Projector.center(t.e()));
            boolean onScreen = pos != null && pos[0] > 20 && pos[0] < w - 20 && pos[1] > 40 && pos[1] < h - 20;
            if (!onScreen) {
                if (t.label() == null) continue; // за экраном показываем только игроков
                double dYaw = Mth.wrapDegrees(t.yaw() - yaw);
                int x = pos == null ? (dYaw > 0 ? w - 24 : 24) : Mth.clamp(pos[0], 24, w - 24);
                int y = pos == null ? cy : Mth.clamp(pos[1], 44, h - 24);
                g.centeredText(font, pos == null ? "▼" : "◆", x, y - 4, t.color());
                g.centeredText(font, t.label(), x, y + 6, t.color());
                continue;
            }
            int x = pos[0], y = pos[1];
            int r = Math.min(14, Projector.radius(Math.max(t.w(), t.h()), t.dist()) + 2);
            diamond(g, x, y, r, t.color());
            g.text(font, Math.round(t.dist()) + "м", x + r + 3, y - 4, t.color(), false);
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
        int[] pos = rotate(cx, cy, dYaw * ppd, dPitch * ppd, rollRad());
        int x = pos[0], y = pos[1];
        int c = FlightData.impactSeconds >= 0 && FlightData.impactSeconds < 3 ? RED : main();
        box(g, x - 3, y - 3, x + 3, y + 3, c);
        hLine(g, x - 9, x - 4, y, c);
        hLine(g, x + 4, x + 9, y, c);
        vLine(g, x, y - 8, y - 4, c);
    }

    /**
     * Лента скорости слева: движущаяся шкала (б/с), текущее значение в рамке,
     * тренд (куда придёт скорость через 5 с), метка заданной скорости.
     */
    private static void speedTape(GuiGraphicsExtractor g, Font font, int cx, int cy, int w) {
        int c = main(), d = dim();
        int x = cx - Math.min(150, w / 3);
        int half = 60;             // половина высоты ленты, px
        double pxPerUnit = 4;      // px на 1 б/с
        double v = FlightData.speed;
        vLine(g, x, cy - half, cy + half, d);
        int lo = (int) Math.floor(v - half / pxPerUnit), hi = (int) Math.ceil(v + half / pxPerUnit);
        for (int u = Math.max(0, lo); u <= hi; u++) {
            int y = (int) Math.round(cy - (u - v) * pxPerUnit);
            if (Math.abs(y - cy) > half) continue;
            boolean major = u % 5 == 0;
            hLine(g, x - (major ? 6 : 3), x, y, d);
            if (major && Math.abs(y - cy) > 9) {
                String s = String.valueOf(u);
                g.text(font, s, x - 8 - font.width(s), y - 4, d, false);
            }
        }
        // Тренд: полоса от текущей скорости к прогнозу через 5 секунд
        double trend = Mth.clamp(FlightData.accel * 5, -half / pxPerUnit, half / pxPerUnit);
        int ty = (int) Math.round(cy - trend * pxPerUnit);
        if (Math.abs(ty - cy) > 2) g.fill(x + 1, Math.min(cy, ty), x + 4, Math.max(cy, ty), c);
        // Заданная скорость
        if (Nav.targetSpeed > 0) {
            int sy = (int) Math.round(cy - (Nav.targetSpeed - v) * pxPerUnit);
            sy = Mth.clamp(sy, cy - half, cy + half);
            g.text(font, "◀", x + 3, sy - 4, AMBER, false);
            g.text(font, String.valueOf(Nav.targetSpeed), x - 20, cy - half - 12, AMBER, false);
        }
        // Текущее значение
        String spd = String.valueOf(Math.round(v));
        g.fill(x - 34, cy - 7, x - 2, cy + 8, 0x90000000);
        box(g, x - 34, cy - 7, x - 2, cy + 8, c);
        g.text(font, spd, x - 5 - font.width(spd), cy - 3, c, false);
        g.text(font, "SPD б/с", x - 34, cy + half + 4, d, false);
        g.text(font, "GS " + Math.round(FlightData.groundSpeed), x - 34, cy + half + 14, d, false);
    }

    /**
     * Лента высоты справа: движущаяся шкала (Y), текущее значение в рамке,
     * вертикальная скорость под ней, радиовысота, метка заданной высоты.
     */
    private static void altitudeTape(GuiGraphicsExtractor g, Font font, int cx, int cy, int w) {
        int c = main(), d = dim();
        int x = cx + Math.min(150, w / 3);
        int half = 60;
        double pxPerUnit = 2;      // px на 1 блок
        double alt = FlightData.altitude;
        vLine(g, x, cy - half, cy + half, d);
        int lo = (int) Math.floor(alt - half / pxPerUnit), hi = (int) Math.ceil(alt + half / pxPerUnit);
        for (int u = lo; u <= hi; u++) {
            if (Math.floorMod(u, 5) != 0) continue;
            int y = (int) Math.round(cy - (u - alt) * pxPerUnit);
            if (Math.abs(y - cy) > half) continue;
            boolean major = u % 10 == 0;
            hLine(g, x, x + (major ? 6 : 3), y, d);
            if (major && Math.abs(y - cy) > 9) g.text(font, String.valueOf(u), x + 8, y - 4, d, false);
        }
        // Земля под нами — штриховка на ленте
        if (FlightData.radarAlt >= 0) {
            int gy = (int) Math.round(cy + FlightData.radarAlt * pxPerUnit);
            if (gy < cy + half) {
                for (int y = Math.max(gy, cy - half); y < cy + half; y += 3) hLine(g, x - 4, x, y, AMBER);
            }
        }
        // Заданная высота
        if (Nav.targetAlt != 0) {
            int ay = (int) Math.round(cy - (Nav.targetAlt - alt) * pxPerUnit);
            ay = Mth.clamp(ay, cy - half, cy + half);
            g.text(font, "▶", x - 8, ay - 4, AMBER, false);
            g.text(font, "Y " + Nav.targetAlt, x + 2, cy - half - 12, AMBER, false);
        }
        // Текущее значение
        String a = String.valueOf(Math.round(alt));
        g.fill(x + 2, cy - 7, x + 38, cy + 8, 0x90000000);
        box(g, x + 2, cy - 7, x + 38, cy + 8, c);
        g.text(font, a, x + 6, cy - 3, c, false);
        // Вертикальная скорость
        int vc = FlightData.vsSmooth < -15 ? AMBER : c;
        g.text(font, String.format("%+.1f VS", FlightData.vsSmooth), x + 2, cy + half + 4, vc, false);
        String ra = FlightData.radarAlt < 0 ? "R ---" : "R " + Math.round(FlightData.radarAlt);
        g.text(font, ra, x + 2, cy + half + 14, FlightData.radarAlt >= 0 && FlightData.radarAlt < 10 ? AMBER : d, false);
    }

    /** Строка режимов вверху: управление, навигация, заданные значения. */
    private static void modeRow(GuiGraphicsExtractor g, Font font, int w) {
        int y = 40, c = main(), d = dim();
        boolean aero = com.iva.flighthud.aero.Aero.active(Minecraft.getInstance().player);
        g.text(font, aero ? "AERO" : "MAN", 40, y, aero ? c : d, false);
        g.text(font, Nav.hasWaypoint() ? "LNAV" : "", 80, y, c, false);
        String right = (Nav.targetSpeed > 0 ? "SPD " + Nav.targetSpeed + "  " : "")
                + (Nav.targetAlt != 0 ? "ALT " + Nav.targetAlt : "");
        g.text(font, right, w - 40 - font.width(right), y, c, false);
    }

    /** Курс прямо на линии горизонта: метки каждые 10° с подписью (24 = 240°). */
    private static void horizonHeading(GuiGraphicsExtractor g, Font font, int cx, int cy, float yaw, float pitch, double ppd, int h) {
        double a = rollRad();
        double y = -pitch * ppd; // линия горизонта
        if (Math.abs(y) > h * 0.4) return;
        float heading = Mth.positiveModulo(yaw + 180f, 360f);
        for (int deg = 0; deg < 360; deg += 10) {
            double dx = Mth.wrapDegrees(deg - heading) * ppd;
            if (Math.abs(dx) < 75 || Math.abs(dx) > 160) continue; // не перекрываем центр шкалы тангажа
            rLine(g, cx, cy, dx, y, dx, y + 4, a, dim());
            int[] p = rotate(cx, cy, dx, y + 10, a);
            g.centeredText(font, String.format("%02d", deg / 10), p[0], p[1] - 4, dim());
        }
        // Продолжение горизонта за пределами шкалы тангажа
        rLine(g, cx, cy, -160, y, -70, y, a, dim());
        rLine(g, cx, cy, 70, y, 160, y, a, dim());
    }

    /** Компасная роза внизу по центру; стрелка — направление на путевую точку. */
    private static void compassRose(GuiGraphicsExtractor g, Font font, int cx, int h, float yaw) {
        float heading = Mth.positiveModulo(yaw + 180f, 360f);
        int r = 48, ocx = cx, ocy = h + 12; // центр ниже экрана — видна верхняя дуга
        for (int deg = 0; deg < 360; deg += 10) {
            double t = Math.toRadians(deg - heading);
            double sin = Math.sin(t), cos = Math.cos(t);
            if (cos < 0.15) continue;
            int len = deg % 30 == 0 ? 6 : 3;
            line(g, (int) Math.round(ocx + sin * r), (int) Math.round(ocy - cos * r),
                    (int) Math.round(ocx + sin * (r - len)), (int) Math.round(ocy - cos * (r - len)), dim());
            if (deg % 30 == 0) {
                String s = switch (deg) {
                    case 0 -> "N"; case 90 -> "E"; case 180 -> "S"; case 270 -> "W"; default -> String.valueOf(deg / 10);
                };
                g.centeredText(font, s, (int) Math.round(ocx + sin * (r - 13)), (int) Math.round(ocy - cos * (r - 13)) - 4,
                        deg % 90 == 0 ? main() : dim());
            }
        }
        g.centeredText(font, "▼", ocx, ocy - r - 9, main());
        if (Nav.hasWaypoint()) {
            double t = Math.toRadians(bearingToWp() - heading);
            int x1 = (int) Math.round(ocx + Math.sin(t) * (r - 20)), y1 = (int) Math.round(ocy - Math.cos(t) * (r - 20));
            int x2 = (int) Math.round(ocx + Math.sin(t) * (r - 2)), y2 = (int) Math.round(ocy - Math.cos(t) * (r - 2));
            line(g, x1, y1, x2, y2, AMBER);
        }
    }

    private static double bearingToWp() {
        var p = Minecraft.getInstance().player;
        double dx = Nav.wpX + 0.5 - p.getX(), dz = Nav.wpZ + 0.5 - p.getZ();
        // 0 = север, по часовой
        return Mth.positiveModulo((float) Math.toDegrees(Math.atan2(dx, -dz)), 360f);
    }

    /** Путевая точка: метка на ленте курса, ромб на горизонте, строка с расстоянием и пеленгом. */
    private static void waypointMarker(GuiGraphicsExtractor g, Font font, int cx, int cy, float yaw, float pitch, double ppd, int w, int h) {
        if (!Nav.hasWaypoint()) return;
        var p = Minecraft.getInstance().player;
        double brg = bearingToWp();
        float heading = Mth.positiveModulo(yaw + 180f, 360f);
        double diff = Mth.wrapDegrees((float) (brg - heading));
        double dist = Math.hypot(Nav.wpX + 0.5 - p.getX(), Nav.wpZ + 0.5 - p.getZ());

        // На ленте курса
        int top = FlightHudClient.droneMode ? 28 : 6;
        int tx = (int) Math.round(cx + Mth.clamp(diff, -60, 60) * 2);
        g.centeredText(font, "▼", tx, top + 4, AMBER);

        // На горизонте
        if (Math.abs(diff) < 60) {
            int[] pos = rotate(cx, cy, diff * ppd, -pitch * ppd, rollRad());
            diamond(g, pos[0], pos[1], 5, AMBER);
            g.centeredText(font, Nav.wpName, pos[0], pos[1] - 16, AMBER);
        }

        // Строка навигации
        int sec = FlightData.groundSpeed > 1 ? (int) (dist / FlightData.groundSpeed) : -1;
        String txt = String.format("%s  %d бл  BRG %03d%s", Nav.wpName, Math.round(dist), Math.round(brg) % 360,
                sec >= 0 ? String.format("  ETE %d:%02d", sec / 60, sec % 60) : "");
        g.centeredText(font, txt, cx, h - 70, AMBER);
    }

    /** Нижний левый блок: элитры, ракеты, здоровье, время, координаты. */
    private static void status(GuiGraphicsExtractor g, Font font, int h) {
        int x = 8 + (FlightHudClient.droneMode ? 14 : 0), y = h - 76 - (FlightHudClient.droneMode ? 10 : 0);
        int ec = FlightData.elytraPercent >= 0 && FlightData.elytraPercent < 15 ? RED : main();
        g.text(font, "ELY " + (FlightData.elytraPercent < 0 ? "--" : FlightData.elytraPercent + "%"), x, y, ec, false);
        String rkt = "RKT " + FlightData.rockets + (FlightData.battery >= 0 ? " (" + FlightData.battery + "%)" : "");
        g.text(font, rkt, x, y + 10, FlightData.rockets == 0 ? AMBER : main(), false);
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
