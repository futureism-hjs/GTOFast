package com.gtofix;

import com.gtofix.config.GTOFixConfig;
import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Runtime Scanner — mirrors GTOCutCorners' scanRecipeLogics.
 * Every 1s walks all server-levels' loaded chunks, finds MetaMachine block-entities,
 * locates their RecipeLogic, and corrects duration if it was modified externally.
 */
final class Scanner {

    static final Logger LOGGER = LogUtils.getLogger();

    private static ScheduledExecutorService executor;
    private static int cycle;

    private Scanner() {}

    static synchronized void start(MinecraftServer server) {
        stop();
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "GTOFix-Scanner"); t.setDaemon(true); return t;
        });
        executor.scheduleWithFixedDelay(Scanner::scan, 5, 1, TimeUnit.SECONDS);
        LOGGER.info("[GTOFix] Scanner started");
    }

    static synchronized void stop() {
        if (executor != null && !executor.isShutdown()) {
            executor.shutdownNow();
            executor = null;
            LOGGER.info("[GTOFix] Scanner stopped");
        }
    }

    // -- main scan loop --

    private static void scan() {
        cycle++;
        try {
            Object server = ServerLifecycleHooks.getCurrentServer();
            if (server == null) return;

            // Get 'levels' field (Map<ResourceKey, ServerLevel>)
            Field levelsField = null;
            for (Class<?> c = server.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                try { levelsField = c.getDeclaredField("levels"); levelsField.setAccessible(true); break; }
                catch (NoSuchFieldException ignored) {}
            }
            if (levelsField == null) return;

            Map<?, ?> levelMap = (Map<?, ?>) levelsField.get(server);
            int totalBE = 0, totalMM = 0, totalRL = 0, corrected = 0;

            for (Object level : levelMap.values()) {
                Object cs = level.getClass().getMethod("getChunkSource").invoke(level);
                // ChunkMap.getChunks() is protected — use reflection
                Method getChunks = null;
                for (Class<?> cc = cs.getClass(); cc != null && cc != Object.class; cc = cc.getSuperclass()) {
                    try { getChunks = cc.getDeclaredMethod("getChunks"); getChunks.setAccessible(true); break; }
                    catch (NoSuchMethodException ignored) {}
                }
                if (getChunks == null) continue;

                for (Object chunk : (Iterable<?>) getChunks.invoke(cs)) {
                    Map<?, ?> beMap = (Map<?, ?>) chunk.getClass().getMethod("getBlockEntities").invoke(chunk);
                    if (beMap.isEmpty()) continue;

                    for (Object be : beMap.values()) {
                        totalBE++;
                        try {
                            Object mm = be.getClass().getMethod("getMetaMachine").invoke(be);
                            if (mm == null) continue;
                            totalMM++;

                            Object rl = null;
                            try { rl = mm.getClass().getMethod("getRecipeLogic").invoke(mm); } catch (Exception ignored) {}

                            if (rl != null) {
                                totalRL++;
                                int dur = Utils.getIntField(rl, "duration");
                                if (dur > 1) {
                                    int target = computeTarget(rl);
                                    if (target != dur) {
                                        Utils.setIntField(rl, "duration", target);
                                        corrected++;
                                    }
                                }
                            }
                        } catch (Exception ignored) {}
                    }
                }
            }

            if (cycle <= 3) {
                LOGGER.info("[GTOFix] Scanner #{}: BEs={} MMs={} RLs={} corrected={}",
                    cycle, totalBE, totalMM, totalRL, corrected);
            }
        } catch (Exception e) {
            if (cycle <= 3) LOGGER.warn("[GTOFix] Scanner err: {}", e.getMessage());
        }
    }

    // -- target duration for Scanner correction (factor mode) --

    private static int computeTarget(Object recipeLogic) {
        double factor = GTOFixConfig.getDurationFactor();
        if (factor == 1.0) return 1;
        if (factor <= 0.0) return 1;

        try {
            Object lastRecipe = recipeLogic.getClass().getMethod("getLastRecipe").invoke(recipeLogic);
            if (lastRecipe != null) {
                Object idObj = lastRecipe.getClass().getMethod("getId").invoke(lastRecipe);
                String recipeId = idObj != null ? idObj.toString() : null;

                // Strategy 1: known original from Patcher (static registry recipes)
                if (recipeId != null) {
                    Integer orig = Patcher.originalDurations.get(recipeId);
                    if (orig != null) {
                        return Math.max(1, (int)(orig * factor));
                    }
                    // Also try with "unknown/" prefix for dynamically discovered recipes
                    orig = Patcher.originalDurations.get("unknown/" + recipeId);
                    if (orig != null) {
                        return Math.max(1, (int)(orig * factor));
                    }
                }

                // Strategy 2: dynamically-created recipes (fluid drills, custom recipe
                // machines, electric furnace). Read the recipe's own duration as the
                // discovered original since we never touched it.
                int recipeDur = Utils.getIntField(lastRecipe, "duration");
                if (recipeDur > 1) {
                    if (recipeId != null) {
                        Patcher.originalDurations.putIfAbsent("unknown/" + recipeId, recipeDur);
                    }
                    return Math.max(1, (int)(recipeDur * factor));
                }
            }
        } catch (Exception ignored) {}
        return 1;
    }
}
