package com.iva.breeding;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.HashSet;
import java.util.Set;

public class BreedingIndicatorClient implements ClientModInitializer {
    /** Радиус подсветки: 16 блоков по горизонтали (2 чанка в поперечнике), 8 по вертикали. */
    private static final double RADIUS = 16;
    private static final double RADIUS_Y = 8;
    /** Пауза между кормлениями, тиков (чтобы не спамить сервер). */
    private static final int FEED_INTERVAL = 3;
    public static final int HIGHLIGHT_COLOR = 0x55FF55;

    private static boolean highlightEnabled = false;
    private static Set<Entity> highlighted = Collections.emptySet();
    private static KeyMapping toggleKey;
    private static KeyMapping feedKey;
    private static final java.util.ArrayDeque<Animal> feedQueue = new java.util.ArrayDeque<>();
    private static int feedCooldown = 0;

    public static boolean isHighlighted(Entity e) {
        return highlightEnabled && highlighted.contains(e);
    }

    @Override
    public void onInitializeClient() {
        KeyMapping.Category category = KeyMapping.Category.register(
                Identifier.fromNamespaceAndPath("breedingindicator", "main"));
        toggleKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.breedingindicator.highlight", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_H, category));
        feedKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.breedingindicator.feed", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_J, category));

        ClientEntityEvents.ENTITY_LOAD.register((entity, level) -> BreedingTracker.onEntityLoad(entity));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> BreedingTracker.reset());
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    private void tick(Minecraft mc) {
        while (toggleKey.consumeClick()) {
            highlightEnabled = !highlightEnabled;
            if (mc.player != null) {
                mc.gui.setOverlayMessage(Component.literal(highlightEnabled
                        ? "Подсветка готовых к размножению: ВКЛ"
                        : "Подсветка готовых к размножению: ВЫКЛ")
                        .withStyle(highlightEnabled ? ChatFormatting.GREEN : ChatFormatting.GRAY), false);
            }
        }
        if (mc.level == null || mc.player == null) {
            highlighted = Collections.emptySet();
            feedQueue.clear();
            return;
        }
        BreedingTracker.tick();
        while (feedKey.consumeClick()) startFeeding(mc);
        feedTick(mc);
        if (mc.level.getGameTime() % 100 == 0) BreedingTracker.cleanup();
        if (!highlightEnabled) {
            highlighted = Collections.emptySet();
            return;
        }

        Vec3 eye = mc.player.getEyePosition();
        Set<Entity> result = new HashSet<>();
        AABB area = mc.player.getBoundingBox().inflate(RADIUS, RADIUS_Y, RADIUS);
        for (Animal a : mc.level.getEntitiesOfClass(Animal.class, area)) {
            if (a.isBaby() || BreedingTracker.onCooldown(a)) continue;
            double dx = a.getX() - mc.player.getX(), dz = a.getZ() - mc.player.getZ();
            if (dx * dx + dz * dz > RADIUS * RADIUS) continue;
            if (visible(mc, eye, a)) result.add(a);
        }
        highlighted = result;
    }

    /** Собирает всех животных в зоне досягаемости, которых можно покормить едой в руке. */
    private static void startFeeding(Minecraft mc) {
        ItemStack food = mc.player.getMainHandItem();
        feedQueue.clear();
        if (food.isEmpty()) return;
        double range = mc.player.entityInteractionRange();
        AABB area = mc.player.getBoundingBox().inflate(range + 2);
        List<Animal> targets = new ArrayList<>();
        for (Animal a : mc.level.getEntitiesOfClass(Animal.class, area)) {
            if (canFeed(mc, a, food)) targets.add(a);
        }
        targets.sort(Comparator.comparingDouble(a -> a.distanceToSqr(mc.player)));
        feedQueue.addAll(targets);
        mc.gui.setOverlayMessage(Component.literal(targets.isEmpty()
                ? "Некого кормить рядом"
                : "Кормлю: " + targets.size()).withStyle(ChatFormatting.YELLOW), false);
    }

    private static boolean canFeed(Minecraft mc, Animal a, ItemStack food) {
        return a.isAlive() && !a.isBaby() && a.isFood(food)
                && !BreedingTracker.onCooldown(a) && !BreedingTracker.inLove(a)
                && mc.player.isWithinEntityInteractionRange(a, 0);
    }

    private static void feedTick(Minecraft mc) {
        if (feedQueue.isEmpty()) return;
        if (feedCooldown > 0) { feedCooldown--; return; }
        ItemStack food = mc.player.getMainHandItem();
        while (!feedQueue.isEmpty()) {
            Animal a = feedQueue.poll();
            if (food.isEmpty()) { feedQueue.clear(); return; }
            if (!canFeed(mc, a, food)) continue;
            Vec3 hit = a.getBoundingBox().getCenter();
            mc.gameMode.interact(mc.player, a, new EntityHitResult(a, hit), InteractionHand.MAIN_HAND);
            mc.player.swing(InteractionHand.MAIN_HAND);
            BreedingTracker.markFed(a);
            feedCooldown = FEED_INTERVAL;
            return;
        }
    }

    /** Видно ли животное: луч проверяется только по блокам, мобы его не перекрывают. */
    private static boolean visible(Minecraft mc, Vec3 eye, Entity e) {
        AABB box = e.getBoundingBox();
        Vec3 c = box.getCenter();
        Vec3[] points = {
                c,
                new Vec3(c.x, box.maxY - 0.05, c.z),
                new Vec3(box.minX + 0.05, c.y, box.minZ + 0.05),
                new Vec3(box.maxX - 0.05, c.y, box.maxZ - 0.05),
        };
        for (Vec3 p : points) {
            var hit = mc.level.clip(new ClipContext(eye, p, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
            if (hit.getType() == HitResult.Type.MISS) return true;
        }
        return false;
    }
}
