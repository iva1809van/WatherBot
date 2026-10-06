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

    /**
     * Новые сущности: флаг «детёныш» приходит отдельным пакетом уже после
     * появления сущности, поэтому проверяем их несколько тиков подряд.
     */
    private static final Map<Animal, Long> recentlyLoaded = new java.util.IdentityHashMap<>();
    private static final long BABY_CHECK_TICKS = 40;

    public static void onEntityLoad(Entity entity) {
        if (entity instanceof Animal a) recentlyLoaded.put(a, now());
    }

    /** Вызывается каждый тик клиента. */
    public static void tick() {
        long t = now();
        for (Iterator<Map.Entry<Animal, Long>> it = recentlyLoaded.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            Animal a = e.getKey();
            if (a.isRemoved() || t - e.getValue() > BABY_CHECK_TICKS) { it.remove(); continue; }
            if (a.isBaby()) { it.remove(); onBabySeen(a); }
        }
    }

    private static void onBabySeen(Animal baby) {
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

    /** Животное недавно покормили (сердечки) — повторно кормить не нужно. */
    public static boolean inLove(Entity entity) {
        Long at = inLove.get(entity.getUUID());
        return at != null && now() - at < 600;
    }

    /** Помечаем сразу после кормления, не дожидаясь ответа сервера. */
    public static void markFed(Animal animal) {
        inLove.put(animal.getUUID(), now());
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
        recentlyLoaded.clear();
    }
}
