package com.gtofast;

import com.gtofast.config.GTOFastConfig;
import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import org.slf4j.Logger;

import java.lang.reflect.Method;
import java.util.*;

/**
 * Tick-level overclocking corrector.
 *
 * GTCEu's RecipeModifierFunction.overclocking() recalculates recipe duration
 * inside RecipeLogic.setupRecipe(), overwriting the Patcher prefix.
 *
 * This corrector hooks ServerTickEvent (20 Hz) and corrects every running
 * RecipeLogic's "duration" field immediately after overclocking applied it.
 *
 * Strategy (no Mixin, no JVMTI):
 *   - Every 5 ticks, walk all loaded chunks to discover active RecipeLogics
 *   - Every tick, check cached RecipeLogics and correct duration if needed
 */
final class OverclockCorrector {

    static final Logger LOGGER = LogUtils.getLogger();

    /** RecipeLogic -> { MetaMachine, lastKnownDuration, correctionCount } */
    private static final Map<Object, Entry> cache = new LinkedHashMap<>();
    private static int tickCounter;
    private static int totalCorrected;

    private record Entry(Object machine, int lastDur, int corrected) {}

    private OverclockCorrector() {}

    // -- main tick hook, called from GTOFast.onServerTick --

    static void onTick(MinecraftServer server) {
        if (server == null) return;
        double factor = GTOFastConfig.getDurationFactor();
        if (factor == 1.0) { cache.clear(); return; }

        tickCounter++;

        // Full rescan every 5 ticks (250 ms)
        boolean rescan = tickCounter % 5 == 0;

        if (rescan) {
            discoverRecipeLogics(server);
        }

        // Correct every tick
        List<Object> toRemove = new ArrayList<>();
        for (Map.Entry<Object, Entry> e : cache.entrySet()) {
            Object rl = e.getKey();
            Entry entry = e.getValue();
            try {
                int dur = Utils.getIntField(rl, "duration");
                if (dur <= 1) continue;

                int target = computeTarget(rl, factor);
                if (target != dur) {
                    Utils.setIntField(rl, "duration", target);
                    totalCorrected++;
                    // Update entry
                    cache.put(rl, new Entry(entry.machine, target, entry.corrected + 1));
                }
            } catch (Exception ex) {
                toRemove.add(rl);
            }
        }

        for (Object rl : toRemove) {
            cache.remove(rl);
        }

        // Log stats once per second
        if (tickCounter % 20 == 0 && totalCorrected > 0) {
            LOGGER.debug("[GTOFast] OverclockCorrector: {} active RLs, {} corrected/tick avg",
                cache.size(), totalCorrected / Math.max(1, tickCounter));
        }
    }

    static void reset() {
        cache.clear();
        tickCounter = 0;
        totalCorrected = 0;
    }

    private static Object getChunkMap(Object chunkSource) {
        try {
            Method m = chunkSource.getClass().getMethod("getChunkMap");
            m.setAccessible(true);
            return m.invoke(chunkSource);
        } catch (Exception ignored) {}
        // Forge patches: try field
        try {
            java.lang.reflect.Field f = Utils.findField(chunkSource.getClass(), "chunkMap");
            if (f != null) return f.get(chunkSource);
        } catch (Exception ignored) {}
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Iterable<LevelChunk> getChunks(Object chunkMap) {
        try {
            Method m = chunkMap.getClass().getMethod("getChunks");
            m.setAccessible(true);
            return (Iterable<LevelChunk>) m.invoke(chunkMap);
        } catch (Exception ignored) {}
        return Collections.emptyList();
    }

    // -- discover active RecipeLogics from all loaded chunks --

    private static void discoverRecipeLogics(MinecraftServer server) {
        int found = 0;
        for (ServerLevel level : server.getAllLevels()) {
            Object chunkMap = getChunkMap(level.getChunkSource());
            if (chunkMap == null) continue;
            for (LevelChunk chunk : getChunks(chunkMap)) {
                for (Object be : chunk.getBlockEntities().values()) {
                    try {
                        Method getMM = be.getClass().getMethod("getMetaMachine");
                        Object mm = getMM.invoke(be);
                        if (mm == null) continue;

                        Method getRL = mm.getClass().getMethod("getRecipeLogic");
                        Object rl = getRL.invoke(mm);
                        if (rl == null) continue;

                        // Only add if not already tracked
                        if (!cache.containsKey(rl)) {
                            cache.put(rl, new Entry(mm, 0, 0));
                            found++;
                        }
                    } catch (Exception ignored) {}
                }
            }
        }
        if (found > 0 && tickCounter <= 20) {
            LOGGER.info("[GTOFast] OverclockCorrector discovered {} new RecipeLogics", found);
        }
    }

    // -- target duration calculation --

    private static int computeTarget(Object recipeLogic, double factor) {
        if (factor <= 0.0) return 1;

        try {
            Object lastRecipe = recipeLogic.getClass().getMethod("getLastRecipe").invoke(recipeLogic);
            if (lastRecipe != null) {
                Object idObj = lastRecipe.getClass().getMethod("getId").invoke(lastRecipe);
                String recipeId = idObj != null ? idObj.toString() : null;

                // Check known original from Patcher
                if (recipeId != null) {
                    Integer orig = Patcher.originalDurations.get(recipeId);
                    if (orig != null) {
                        return Math.max(1, (int) (orig * factor));
                    }
                    // Also check dynamically-discovered
                    orig = Patcher.originalDurations.get("unknown/" + recipeId);
                    if (orig != null) {
                        return Math.max(1, (int) (orig * factor));
                    }
                }

                // Fallback: read recipe's own duration
                int recipeDur = Utils.getIntField(lastRecipe, "duration");
                if (recipeDur > 1) {
                    if (recipeId != null) {
                        Patcher.originalDurations.putIfAbsent("unknown/" + recipeId, recipeDur);
                    }
                    return Math.max(1, (int) (recipeDur * factor));
                }
            }
        } catch (Exception ignored) {}

        return 1;
    }
}
