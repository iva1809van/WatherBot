package com.iva.breeding;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.Animal;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Клиент не знает настоящий таймер размножения (сервер его не присылает),
 * поэтому он вычисляется по наблюдениям: животное вошло в «режим любви»
 * (сердечки), и рядом появился детёныш того же вида — значит, оба
 * родителя ушли на перезарядку в 5 минут (6000 тиков).
 */
public final class BreedingTracker {
    public static final long COOLDOWN_TICKS = 6000;
    private static final long LOVE_WINDOW_TICKS = 1200;
    private static final double PARENT_RADIUS_SQ = 6 * 6;

    /** uuid -> тик, когда животное вошло в режим любви */
    private static final Map<UUID, Long> inLove = new HashMap<>();
    /** uuid -> тик окончания перезарядки */
    private static final Map<UUID, Long> cooldownUntil = new HashMap<>();

    private BreedingTracker() {}

    private static long now() {
        Minecraft mc = Minecraft.getInstance();
        return mc.level == null ? 0 : mc.level.getGameTime();
    }

    public static void onLove(Animal animal) {
        inLove.put(animal.getUUID(), now());
    }

    /** Вызывается, когда на клиенте появляется новая сущность. */
    public static void onEntityLoad(Entity entity) {
        if (!(entity instanceof Animal baby) || !baby.isBaby()) return;
        long t = now();
        var level = baby.level();
        for (Animal parent : level.getEntitiesOfClass(Animal.class, baby.getBoundingBox().inflate(6))) {
            if (parent == baby || parent.isBaby() || parent.getType() != baby.getType()) continue;
            Long loveAt = inLove.get(parent.getUUID());
            if (loveAt == null || t - loveAt > LOVE_WINDOW_TICKS) continue;
            if (parent.distanceToSqr(baby) > PARENT_RADIUS_SQ) continue;
            inLove.remove(parent.getUUID());
            cooldownUntil.put(parent.getUUID(), t + COOLDOWN_TICKS);
        }
    }

    public static boolean onCooldown(Entity entity) {
        Long until = cooldownUntil.get(entity.getUUID());
        return until != null && until > now();
    }

    /** Чистка устаревших записей. */
    public static void cleanup() {
        long t = now();
        for (Iterator<Long> it = cooldownUntil.values().iterator(); it.hasNext(); ) {
            if (it.next() <= t) it.remove();
        }
        inLove.values().removeIf(v -> t - v > LOVE_WINDOW_TICKS);
    }

    public static void reset() {
        inLove.clear();
        cooldownUntil.clear();
    }
}
