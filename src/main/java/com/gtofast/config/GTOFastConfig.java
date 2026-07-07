package com.gtofast.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * GTO Fast configuration — JSON file at config/gtofast.json.
 * Created on first launch.
 */
public class GTOFastConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = Paths.get("config", "gtofast.json");

    /**
     * Recipe duration factor.
     * <ul>
     *   <li>0.0  = 1-tick mode (all recipes take 1 tick)</li>
     *   <li>0.05 = 5% duration = 20x speedup</li>
     *   <li>1.0  = unchanged (original speed)</li>
     *   <li>2.0  = 2x slower</li>
     * </ul>
     */
    public double durationFactor = 1.0;

    private static GTOFastConfig INSTANCE = new GTOFastConfig();

    public static void load() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            if (Files.exists(CONFIG_PATH)) {
                String raw = Files.readString(CONFIG_PATH);
                INSTANCE = GSON.fromJson(raw, GTOFastConfig.class);
                if (INSTANCE == null) INSTANCE = new GTOFastConfig();
            }
            Files.writeString(CONFIG_PATH, GSON.toJson(INSTANCE));
            System.out.println("[GTOFast] Config loaded: " + CONFIG_PATH.toAbsolutePath());
        } catch (Exception e) {
            System.err.println("[GTOFast] Config load failed: " + e.getMessage());
            INSTANCE = new GTOFastConfig();
        }
    }

    public static void reload() { load(); }

    public static double getDurationFactor() { return INSTANCE.durationFactor; }
}
