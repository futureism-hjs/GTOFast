package com.gtofix.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * GTO Fix configuration — JSON file at config/gtofix.json.
 * Created on first launch.
 */
public class GTOFixConfig {

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

    private static GTOFixConfig INSTANCE = new GTOFixConfig();

    public static void load() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            if (Files.exists(CONFIG_PATH)) {
                String raw = Files.readString(CONFIG_PATH);
                INSTANCE = GSON.fromJson(raw, GTOFixConfig.class);
                if (INSTANCE == null) INSTANCE = new GTOFixConfig();
            }
            Files.writeString(CONFIG_PATH, GSON.toJson(INSTANCE));
            System.out.println("[GTOFix] Config loaded: " + CONFIG_PATH.toAbsolutePath());
        } catch (Exception e) {
            System.err.println("[GTOFix] Config load failed: " + e.getMessage());
            INSTANCE = new GTOFixConfig();
        }
    }

    public static void reload() { load(); }

    public static double getDurationFactor() { return INSTANCE.durationFactor; }
}
