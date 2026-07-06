package com.gtofix;

import com.gregtechceu.gtceu.api.recipe.GTRecipeDefinition;
import com.gregtechceu.gtceu.api.registry.GTRegistries;
import com.gtofix.config.GTOFixConfig;
import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.Recipe;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Batch recipe patcher — handles GT recipe data, vanilla recipes,
 * and RecipeModifier overclocking wrapping for electric furnace fix.
 */
final class Patcher {

    static final Logger LOGGER = LogUtils.getLogger();

    private static final Set<String> GENERATOR_TYPES = Set.of(
        "combustion_generator", "gas_turbine", "steam_turbine",
        "plasma_generator", "thermal_generator", "semi_fluid_generator",
        "supercritical_steam_turbine", "rocket_engine", "large_boiler",
        "steam_boiler",
        "naquadah_reactor", "hyper_reactor", "advanced_hyper_reactor",
        "large_naquadah_reactor", "mana_garden", "annihilate_generator",
        "fuel_cell_energy_absorption", "fuel_cell_energy_transfer",
        "fuel_cell_energy_release", "space_elevator"
    );

    /** recipeId -> originalDuration, shared with Scanner for factor-mode correction */
    static final Map<String, Integer> originalDurations = new ConcurrentHashMap<>();

    private Patcher() {}

    // ============ GT recipe batch patch ============

    static void patchGTRecipes(MinecraftServer server) {
        long t0 = System.currentTimeMillis();

        List<Object> recipes = collectAllGTRecipes(server);
        LOGGER.info("[GTOFix] Collected {} GT recipe objects", recipes.size());
        if (recipes.isEmpty()) { LOGGER.warn("[GTOFix] No GT recipes found"); return; }

        double factor = GTOFixConfig.getDurationFactor();
        if (factor == 1.0) return;

        int patched = 0, genSkip = 0, errors = 0;
        for (int i = 0; i < recipes.size(); i++) {
            Object r = recipes.get(i);
            try {
                String id = getRecipeId(r);
                if (id != null && isGeneratorId(id)) { genSkip++; continue; }

                int orig = Utils.getIntField(r, "duration");
                if (orig <= 0) continue;

                if (id != null) originalDurations.put(id, orig);

                int target = factor <= 0.0 ? 1 : Math.max(1, (int)(orig * factor));
                if (target != orig) {
                    Utils.setIntField(r, "duration", target);
                    patched++;
                }

                if ((i + 1) % 5000 == 0) {
                    LOGGER.info("[GTOFix] progress {}/{} d={}", i + 1, recipes.size(), patched);
                }
            } catch (Exception e) {
                if (++errors <= 5) LOGGER.warn("[GTOFix] GT patch err: {}", e.getMessage());
            }
        }
        LOGGER.info("[GTOFix] GT patch: {}/{} patched {} gen {} errs {}ms",
            patched, recipes.size() - genSkip, genSkip, errors, System.currentTimeMillis() - t0);
    }

    // ============ Recipe collection ============

    @SuppressWarnings("unchecked")
    private static List<Object> collectAllGTRecipes(MinecraftServer server) {
        LinkedHashSet<Object> all = new LinkedHashSet<>();
        int typeHits = 0;

        try {
            Object registry = GTRegistries.RECIPE_TYPES;
            Method valuesMethod = registry.getClass().getMethod("values");
            Collection<Object> types = (Collection<Object>) valuesMethod.invoke(registry);

            LOGGER.info("[GTOFix] RECIPE_TYPES count: {}", types.size());

            for (Object type : types) {
                if (type == null) continue;

                String typeName = type.toString().toLowerCase();
                if (isGeneratorTypeName(typeName)) {
                    LOGGER.info("[GTOFix] Skipping generator type: {}", typeName);
                    continue;
                }

                Collection<Object> values = getRecipesFromType(type);
                if (values != null && !values.isEmpty()) {
                    all.addAll(values);
                    typeHits++;
                }
            }

            LOGGER.info("[GTOFix] recipe types: {} hit, total recipes: {}", typeHits, all.size());
        } catch (Exception e) {
            LOGGER.warn("[GTOFix] GTRegistries enumeration failed: {}", e.getMessage());
        }

        if (all.isEmpty()) {
            all.addAll(collectViaGTORecipeTypes());
        }

        if (all.isEmpty()) {
            for (Recipe<?> r : server.getRecipeManager().getRecipes()) {
                @SuppressWarnings("unchecked")
                boolean isGT = GTRecipeDefinition.class.isInstance(r);
                if (isGT) all.add(r);
            }
            LOGGER.info("[GTOFix] RecipeManager fallback: {} recipes", all.size());
        }

        return new ArrayList<>(all);
    }

    @SuppressWarnings("unchecked")
    private static Collection<Object> getRecipesFromType(Object type) {
        try {
            Field recipesField = Utils.findField(type.getClass(), "recipes");
            if (recipesField != null) {
                Object recipesObj = recipesField.get(type);
                if (recipesObj instanceof Map) {
                    return (Collection<Object>) (Collection<?>) ((Map<?, ?>) recipesObj).values();
                } else if (recipesObj instanceof Collection) {
                    return (Collection<Object>) recipesObj;
                }
            }
        } catch (Exception ignored) {}

        for (String methodName : new String[]{"getRecipes", "getRecipeMap", "recipeMap", "getEntries", "entries"}) {
            try {
                Method m = type.getClass().getMethod(methodName);
                Object result = m.invoke(type);
                if (result == null) continue;
                if (result instanceof Map) {
                    Collection<Object> vals = (Collection<Object>) (Collection<?>) ((Map<?, ?>) result).values();
                    if (!vals.isEmpty()) return vals;
                } else if (result instanceof Collection) {
                    return (Collection<Object>) result;
                }
            } catch (NoSuchMethodException ignored) {
            } catch (Exception ignored) {}
        }

        return null;
    }

    @SuppressWarnings("unchecked")
    private static Collection<Object> collectViaGTORecipeTypes() {
        LinkedHashSet<Object> all = new LinkedHashSet<>();
        try {
            Class<?> cls = Class.forName("com.gtocore.common.data.GTORecipeTypes");
            Class<?> recipeTypeCls;
            try {
                recipeTypeCls = Class.forName("com.gtolib.api.recipe.RecipeType");
            } catch (ClassNotFoundException e) {
                recipeTypeCls = Class.forName("com.gregtechceu.gtceu.api.recipe.GTRecipeType");
            }

            for (Field f : cls.getDeclaredFields()) {
                if (!java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);
                Object val = f.get(null);
                if (val == null) continue;
                if (!recipeTypeCls.isAssignableFrom(val.getClass())) continue;

                if (isGeneratorTypeName(f.getName().toLowerCase())) continue;

                Collection<Object> recipes = getRecipesFromType(val);
                if (recipes != null) all.addAll(recipes);
            }
            LOGGER.info("[GTOFix] GTORecipeTypes fallback: {} recipes", all.size());
        } catch (Exception e) {
            LOGGER.warn("[GTOFix] GTORecipeTypes fallback failed: {}", e.getMessage());
        }
        return all;
    }

    // ============ RecipeModifier overclocking hook ============

    /**
     * RecipeModifier is a @FunctionalInterface. Machines call
     * {@code modifier.applyModifier(holder, unit, recipe)} which runs overclocking
     * and recalculates duration (was overriding our patched recipe data).
     *
     * We wrap all public static final RecipeModifier fields with Proxy wrappers
     * that apply durationFactor AFTER the original overclocking completes.
     * This fixes electric furnace and all other standard machines that go
     * through the RecipeModifier overclocking path.
     */
    static void hookOverclocking() {
        double factor = GTOFixConfig.getDurationFactor();
        if (factor == 1.0 || factor <= 0.0) return;

        sun.misc.Unsafe u = Utils.getUnsafe();
        if (u == null) { LOGGER.warn("[GTOFix] Unsafe unavailable, overclocking hook skipped"); return; }

        int hooked = 0;
        try {
            Class<?> recipeModifierCls = Class.forName(
                "com.gregtechceu.gtceu.api.recipe.modifier.RecipeModifier");

            for (Field f : recipeModifierCls.getDeclaredFields()) {
                if (!java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                if (!java.lang.reflect.Modifier.isPublic(f.getModifiers())) continue;
                if (!f.getType().equals(recipeModifierCls)) continue;

                long offset = u.staticFieldOffset(f);
                Object base = u.staticFieldBase(f);
                Object original = u.getObject(base, offset);
                if (original == null) continue;

                Object wrapper = java.lang.reflect.Proxy.newProxyInstance(
                    recipeModifierCls.getClassLoader(),
                    new Class<?>[]{recipeModifierCls},
                    (proxy, method, args) -> {
                        Object result = method.invoke(original, args);
                        if (result != null && "applyModifier".equals(method.getName())) {
                            try {
                                int dur = Utils.getIntField(result, "duration");
                                int newDur = Math.max(1, (int)(dur * factor));
                                if (newDur != dur) {
                                    Utils.setIntField(result, "duration", newDur);
                                }
                            } catch (Exception ignored) {}
                        }
                        return result;
                    });

                u.putObject(base, offset, wrapper);
                hooked++;
                LOGGER.info("[GTOFix] Overclocking hooked: {}", f.getName());
            }
        } catch (Exception e) {
            LOGGER.warn("[GTOFix] Overclocking hook failed: {}", e.getMessage());
        }
        LOGGER.info("[GTOFix] Overclocking hook: {} modifiers wrapped", hooked);
    }

    // ============ Generator detection ============

    private static boolean isGeneratorId(String recipeId) {
        if (recipeId == null) return false;
        int colon = recipeId.indexOf(':');
        if (colon >= 0) {
            String afterColon = recipeId.substring(colon + 1);
            int slash = afterColon.indexOf('/');
            return GENERATOR_TYPES.contains(slash >= 0 ? afterColon.substring(0, slash) : afterColon);
        }
        return GENERATOR_TYPES.contains(recipeId.toLowerCase());
    }

    private static boolean isGeneratorTypeName(String typeName) {
        if (typeName == null) return false;
        for (String kw : GENERATOR_TYPES) {
            if (typeName.contains(kw.replace('_', ' ')) || typeName.contains(kw)) return true;
        }
        return false;
    }

    // ============ Recipe ID helpers ============

    private static String getRecipeId(Object recipe) {
        try {
            Object id = recipe.getClass().getMethod("getId").invoke(recipe);
            return id != null ? id.toString() : null;
        } catch (Exception ignored) {}

        Field idField = Utils.findField(recipe.getClass(), "id");
        if (idField != null) {
            try {
                Object id = idField.get(recipe);
                return id != null ? id.toString() : null;
            } catch (Exception ignored) {}
        }
        return null;
    }

    // ============ Vanilla recipe patch ============

    private static Field cookingTimeField;
    private static long cookingTimeOffset = -1;
    private static boolean unsafeOk;
    private static boolean needDiscovery = true;

    static void patchVanillaRecipes(MinecraftServer server) {
        double factor = GTOFixConfig.getDurationFactor();
        if (factor == 1.0) return;

        int count = 0, errs = 0;

        for (Recipe<?> r : server.getRecipeManager().getRecipes()) {
            if (!(r instanceof AbstractCookingRecipe acr)) continue;
            try {
                int orig = acr.getCookingTime();
                int target = factor <= 0.0 ? 1 : Math.max(1, (int)(orig * factor));
                if (target != orig) {
                    if (setCookingTime(acr, target)) count++;
                    else errs++;
                }
            } catch (Exception e) {
                if (++errs <= 5) LOGGER.warn("[GTOFix] Vanilla err: {}", e.getMessage());
            }
        }
        LOGGER.info("[GTOFix] Vanilla patch: {} patched {} errs", count, errs);
    }

    private static boolean setCookingTime(AbstractCookingRecipe recipe, int value) {
        if (needDiscovery) discoverCookingTimeField(recipe);
        if (cookingTimeField == null) return false;
        try {
            if (unsafeOk) {
                sun.misc.Unsafe u = Utils.getUnsafe();
                if (u != null) { u.putInt(recipe, cookingTimeOffset, value); return true; }
            }
            cookingTimeField.setInt(recipe, value);
            return true;
        } catch (Exception ignored) { return false; }
    }

    private static void discoverCookingTimeField(AbstractCookingRecipe sample) {
        needDiscovery = false;
        String[] names = {"cookingTime", "f_43750_", "cookingTime_", "field_43750"};
        for (Class<?> c = AbstractCookingRecipe.class; c != null && c != Object.class; c = c.getSuperclass()) {
            for (String n : names) {
                try {
                    Field f = c.getDeclaredField(n);
                    if (f.getType() == int.class) { f.setAccessible(true); cookingTimeField = f; tryInitUnsafe(); return; }
                } catch (NoSuchFieldException ignored) {}
            }
        }
        int expected = sample.getCookingTime();
        for (Class<?> c = sample.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (f.getType() != int.class) continue;
                f.setAccessible(true);
                try { if (f.getInt(sample) == expected) { cookingTimeField = f; tryInitUnsafe(); return; } }
                catch (Exception ignored) {}
            }
        }
    }

    private static void tryInitUnsafe() {
        if (cookingTimeField == null) return;
        try {
            sun.misc.Unsafe u = Utils.getUnsafe();
            if (u != null) { cookingTimeOffset = u.objectFieldOffset(cookingTimeField); unsafeOk = true; }
        } catch (Exception ignored) {}
    }
}
