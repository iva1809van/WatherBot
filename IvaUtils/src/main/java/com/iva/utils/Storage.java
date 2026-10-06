package com.iva.utils;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Supplier;

/** Хранение данных мода в JSON внутри папки config/ivautils. */
final class Storage {
    static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    static final Path DIR = FabricLoader.getInstance().getConfigDir().resolve("ivautils");

    private Storage() {}

    static <T> T load(Path file, Type type, Supplier<T> fallback) {
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            T value = GSON.fromJson(r, type);
            return value != null ? value : fallback.get();
        } catch (Exception e) {
            return fallback.get();
        }
    }

    static void save(Path file, Object value) {
        try {
            Files.createDirectories(file.getParent());
            try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                GSON.toJson(value, w);
            }
        } catch (Exception ignored) {
        }
    }
}
