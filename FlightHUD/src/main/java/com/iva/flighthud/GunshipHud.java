package com.iva.flighthud;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Экран оператора AC-130 (по мотивам реального интерфейса, адаптировано под Minecraft). */
final class GunshipHud {
    private static final int W = 0xFFE6E6E6;      // основной белый
    private static final int D = 0xA0E6E6E6;      // приглушённый
    private static final int BG = 0x90000000;     // подложка под текст
    private static final int RED = 0xFFFF4545;
    private static final int AMBER = 0xFFFFC233;

    private GunshipHud() {}

    /** Затемнение: тепловизор — почти чёрный мир (видны только «тёплые» цели), TV — лёгкое. */
    static void overlay(GuiGraphicsExtractor g, int w, int h) {
        g.fill(0, 0, w, h, Gunship.thermal ? 0xD0101418 : 0x28000000);
        for (int y = 0; y < h; y += 3) g.fill(0, y, w, y + 1, 0x14000000);
    }

    // ---------- оружие ----------

    private enum Weapon {
        GUN25("25mm", "BOW"), GUN40("40mm", "CROSSBOW"), GUN105("105mm", "TRIDENT");
        final String cal, name;
        Weapon(String cal, String name) { this.cal = cal; this.name = name; }
    }

    private static Weapon held(ItemStack s) {
        if (s.is(Items.BOW)) return Weapon.GUN25;
        if (s.is(Items.CROSSBOW)) return Weapon.GUN40;
        if (s.is(Items.TRIDENT)) return Weapon.GUN105;
        return null;
    }

    private static int ammo(Minecraft mc, Weapon w) {
        var inv = mc.player.getInventory();
        int n = 0;
        boolean hasWeapon = false;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            switch (w) {
                case GUN25 -> {
                    if (s.is(Items.BOW)) hasWeapon = true;
                    if (s.is(Items.ARROW) || s.is(Items.TIPPED_ARROW) || s.is(Items.SPECTRAL_ARROW)) n += s.getCount();
                }
                case GUN40 -> {
                    if (s.is(Items.CROSSBOW)) hasWeapon = true;
                    if (s.is(Items.ARROW) || s.is(Items.TIPPED_ARROW) || s.is(Items.SPECTRAL_ARROW) || s.is(Items.FIREWORK_ROCKET)) n += s.getCount();
                }
                case GUN105 -> {
                    if (s.is(Items.TRIDENT)) { hasWeapon = true; n += s.getCount(); }
                }
            }
        }
        return hasWeapon ? n : -1;
    }

    // ---------- примитивы ----------

    private static void h(GuiGraphicsExtractor g, int x1, int x2, int y, int c) { g.fill(Math.min(x1, x2), y, Math.max(x1, x2) + 1, y + 1, c); }
    private static void v(GuiGraphicsExtractor g, int x, int y1, int y2, int c) { g.fill(x, Math.min(y1, y2), x + 1, Math.max(y1, y2) + 1, c); }
    private static void box(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2, int c) { h(g, x1, x2, y1, c); h(g, x1, x2, y2, c); v(g, x1, y1, y2, c); v(g, x2, y1, y2, c); }

    private static void brackets(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2, int l, int c) {
        h(g, x1, x1 + l, y1, c); v(g, x1, y1, y1 + l, c);
        h(g, x2 - l, x2, y1, c); v(g, x2, y1, y1 + l, c);
        h(g, x1, x1 + l, y2, c); v(g, x1, y2 - l, y2, c);
        h(g, x2 - l, x2, y2, c); v(g, x2, y2 - l, y2, c);
    }

    private static void circle(GuiGraphicsExtractor g, int cx, int cy, int r, int c) {
        for (int i = 0; i < 32; i++) {
            double a = i * Math.PI / 16;
            int x = (int) Math.round(cx + Math.cos(a) * r), y = (int) Math.round(cy + Math.sin(a) * r);
            g.fill(x, y, x + 1, y + 1, c);
        }
    }

    private static void text(GuiGraphicsExtractor g, Font f, String s, int x, int y, int c) {
        g.fill(x - 1, y - 1, x + f.width(s) + 1, y + 9, BG);
        g.text(f, s, x, y, c, false);
    }

    private static void textR(GuiGraphicsExtractor g, Font f, String s, int right, int y, int c) {
        text(g, f, s, right - f.width(s), y, c);
    }

    // ---------- экран ----------

    static void render(GuiGraphicsExtractor g, Minecraft mc, int w, int h) {
        Font f = mc.font;
        var p = mc.player;
        int cx = w / 2, cy = h / 2;
        float yaw = p.getYRot(), pitch = p.getXRot();
        double fov = Math.max(5, mc.options.fov().get() / Gunship.zoom());
        double ppd = h / fov;

        if (Gunship.thermal) heatBlobs(g, cx, cy, yaw, pitch, ppd, w, h);

        // Рамка видоискателя
        int mx = (int) (w * 0.17), my = (int) (h * 0.15);
        brackets(g, mx, my, w - mx, h - my, 16, D);

        crosshair(g, cx, cy, w, h);
        targets(g, f, cx, cy, yaw, pitch, ppd, w, h);

        // --- левый верх ---
        text(g, f, "AC-130 GUNNER", 6, 6, W);
        text(g, f, "SENSOR: " + (Gunship.thermal ? "IR WHOT" : "TV"), 6, 16, W);
        Weapon cur = held(p.getMainHandItem());
        int y = 32;
        for (Weapon wpn : Weapon.values()) {
            int a = ammo(mc, wpn);
            String st = wpn == cur ? (a > 0 || p.isCreative() ? "READY" : "NO AMMO") : (a >= 0 ? "STBY" : "OFF");
            String line = wpn.cal + " " + wpn.name + " - " + st;
            if (wpn == cur) {
                text(g, f, line, 8, y, a > 0 || p.isCreative() ? W : RED);
                box(g, 5, y - 3, 10 + f.width(line), y + 10, W);
                y += 14;
            } else {
                text(g, f, line, 8, y, D);
                y += 10;
            }
        }
        if (cur != null) {
            int a = ammo(mc, cur);
            text(g, f, cur.cal + ": " + (p.isCreative() ? "∞" : a) + " RD", 6, y + 4, W);
        }
        text(g, f, "LST: ACTIVE", 6, y + 20, W);
        text(g, f, "RNG: " + (FlightData.lookDist < 0 ? "----" : Math.round(FlightData.lookDist) + "m"), 6, y + 30, W);

        // --- центр верх ---
        String mode = FlightData.acquired != null ? "TGT TRK" : "SCAN";
        String m = "MODE: " + mode;
        text(g, f, m, cx - f.width(m) / 2, 6, W);
        h(g, cx - f.width(m) / 2 - 2, cx + f.width(m) / 2 + 2, 16, W);
        float hdg = Mth.positiveModulo(yaw + 180f, 360f);
        String hd = String.format("HDG: %03d°", Math.round(hdg) % 360);
        text(g, f, hd, cx - f.width(hd) / 2, 20, W);
        headingTape(g, f, cx, 34, hdg);

        // --- правый верх ---
        int right = w - 6;
        double agl = FlightData.radarAlt;
        textR(g, f, "ALT: " + (agl < 0 ? ">256" : String.valueOf(Math.round(agl))) + " AGL", right, 6, W);
        textR(g, f, "Y " + Math.round(p.getY()), right, 16, D);
        textR(g, f, String.format("POS %d / %d", Math.round(p.getX()), Math.round(p.getZ())), right, 26, W);
        double az = Mth.wrapDegrees((float) (hdg - Gunship.trackHeading(p)));
        textR(g, f, String.format("AZ: %+.1f° / EL: %+.1f°", az, -pitch), right, 36, W);
        int sec = Gunship.recTicks / 20;
        boolean blink = (System.currentTimeMillis() / 500) % 2 == 0;
        String rec = String.format("REC: %02d:%02d:%02d", sec / 3600, sec / 60 % 60, sec % 60);
        textR(g, f, rec, right, 50, W);
        if (blink) g.fill(right - f.width(rec) - 9, 51, right - f.width(rec) - 4, 56, RED);

        // --- низ ---
        int by = h - my + 6;
        text(g, f, "LASER: ARMED", mx, by, W);
        String fovS = "FOV: " + Gunship.fovName() + " " + (int) Gunship.zoom() + "x";
        text(g, f, fovS, cx - f.width(fovS) / 2, by, W);
        boolean canFire = cur != null && (ammo(mc, cur) > 0 || p.isCreative());
        textR(g, f, "FIRE: " + (canFire ? "CAPABLE" : "NO SHOT"), w - mx, by, canFire ? W : RED);

        // Правая колонка статусов
        int sy = h - 46;
        if (cur != null) stack(g, f, cur.cal + " RDY", right, sy, canFire ? W : RED);
        stack(g, f, "MANUAL OVERRIDE", right, sy + 13, W);
        if (FlightData.acquired != null && blink) stack(g, f, "TGT ACQUIRED", right, sy + 26, RED);

        // Левый низ: орбита
        text(g, f, String.format("ORBIT R%.0f  SPD %.0f", Gunship.RADIUS, FlightData.speed), 6, h - 26, D);
        text(g, f, "КРЕН: " + String.format("%.0f°", -Math.toDegrees(Math.atan(FlightData.speed * FlightData.speed / (9.8 * Gunship.RADIUS)))), 6, h - 16, D);
    }

    private static void stack(GuiGraphicsExtractor g, Font f, String s, int right, int y, int c) {
        int x = right - 92;
        g.fill(x, y - 2, right, y + 10, BG);
        box(g, x, y - 2, right, y + 10, c);
        g.centeredText(f, s, (x + right) / 2, y, c);
    }

    private static void headingTape(GuiGraphicsExtractor g, Font f, int cx, int y, float hdg) {
        h(g, cx - 90, cx + 90, y + 8, D);
        for (int d = (int) hdg - 45; d <= hdg + 45; d++) {
            if (Math.floorMod(d, 5) != 0) continue;
            int x = (int) Math.round(cx + (d - hdg) * 2);
            boolean major = Math.floorMod(d, 30) == 0;
            v(g, x, y + 8, y + (major ? 2 : 5), D);
            if (major) g.centeredText(f, String.valueOf(Math.floorMod(d, 360)), x, y - 8, D);
        }
        g.centeredText(f, "▼", cx, y + 9, W);
    }

    /** Большое перекрестие с разрывом в центре и рисками шкал. */
    private static void crosshair(GuiGraphicsExtractor g, int cx, int cy, int w, int h) {
        int lenX = (int) (w * 0.28), lenY = (int) (h * 0.30), gap = 18;
        h(g, cx - lenX, cx - gap, cy, W);
        h(g, cx + gap, cx + lenX, cy, W);
        v(g, cx, cy - lenY, cy - gap, W);
        v(g, cx, cy + gap, cy + lenY, W);
        for (int i = gap + 12; i < lenX; i += 12) { v(g, cx - i, cy - 2, cy + 2, D); v(g, cx + i, cy - 2, cy + 2, D); }
        for (int i = gap + 12; i < lenY; i += 12) { h(g, cx - 2, cx + 2, cy - i, D); h(g, cx - 2, cx + 2, cy + i, D); }
        brackets(g, cx - 10, cy - 10, cx + 10, cy + 10, 4, W);
        g.fill(cx, cy, cx + 1, cy + 1, RED);
    }

    /** Рамки целей вокруг прицела, «LOCK ON» рядом с центром, «TRACK» у захваченной. */
    private static void targets(GuiGraphicsExtractor g, Font f, int cx, int cy, float yaw, float pitch, double ppd, int w, int h) {
        for (FlightData.Target t : FlightData.targets) {
            double dy = Mth.wrapDegrees(t.yaw() - yaw), dp = t.pitch() - pitch;
            int x = (int) Math.round(cx + dy * ppd), y = (int) Math.round(cy + dp * ppd);
            if (x < 20 || x > w - 20 || y < 20 || y > h - 20) continue;
            int r = (int) Math.max(5, Math.toDegrees(Math.atan2(Math.max(t.w(), t.h()), t.dist())) * ppd / 2 + 3);
            double off = Math.hypot(dy, dp);
            int c = t.label() != null ? 0xFF7FD8FF : W;
            if (t == FlightData.acquired) {
                brackets(g, x - r - 4, y - r - 4, x + r + 4, y + r + 4, 5, RED);
                g.text(f, "TRACK", x - r - 4 - f.width("TRACK") - 2, y + r, RED, false);
            } else if (off < 6) {
                circle(g, x, y, r, c);
                g.text(f, "LOCK ON", x + r + 4, y - 4, c, false);
            } else {
                box(g, x - r, y - r, x + r, y + r, D);
            }
            if (t.label() != null) g.centeredText(f, t.label(), x, y - r - 11, c);
        }
    }

    /** Тепловые пятна: живые существа светятся белым («белый — горячий»). */
    private static void heatBlobs(GuiGraphicsExtractor g, int cx, int cy, float yaw, float pitch, double ppd, int w, int h) {
        for (FlightData.Target t : FlightData.targets) {
            double dy = Mth.wrapDegrees(t.yaw() - yaw), dp = t.pitch() - pitch;
            int x = (int) Math.round(cx + dy * ppd), y = (int) Math.round(cy + dp * ppd);
            if (x < -20 || x > w + 20 || y < -20 || y > h + 20) continue;
            int hw = (int) Math.max(2, Math.toDegrees(Math.atan2(t.w() / 2, t.dist())) * ppd);
            int hh = (int) Math.max(2, Math.toDegrees(Math.atan2(t.h() / 2, t.dist())) * ppd);
            g.fill(x - hw - 2, y - hh - 2, x + hw + 2, y + hh + 2, 0x50B0B0B0);
            g.fill(x - hw, y - hh, x + hw, y + hh, 0xC0E0E0E0);
            g.fill(x - hw / 2, y - hh / 2, x + Math.max(1, hw / 2), y + Math.max(1, hh / 2), 0xFFFFFFFF);
        }
    }
}
