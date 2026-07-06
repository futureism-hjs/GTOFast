package com.gtofix;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;

/**
 * Bypasses GTOLib's Mixin filter so other mods' Mixins can be applied.
 *
 * <p>GTOLib's {@code GTOMixinExtension.preApply()} removes non-GTOCore mixins
 * using a hardcoded blacklist and {@code MixinCanceller} registry. We clear
 * both so no mixins are blocked.</p>
 */
final class SecurityBypass {

    static final Logger LOGGER = LogUtils.getLogger();

    private SecurityBypass() {}

    /** Clear GTOMixinExtension's blacklist and MixinCanceller sets. */
    static void disableMixinBlocking() {
        try {
            Class<?> extClass = Class.forName("com.gtolib.GTOMixinExtension");
            Unsafe u = Utils.getUnsafe();
            if (u == null) { LOGGER.warn("[GTOFix] Unsafe unavailable, mixin bypass skipped"); return; }

            // Clear all static Set<String> fields (obf_21 = hardcoded blacklist)
            for (Field f : extClass.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers())) continue;
                if (Set.class.isAssignableFrom(f.getType())) {
                    try {
                        Object base = u.staticFieldBase(f);
                        long offset = u.staticFieldOffset(f);
                        Set<?> set = (Set<?>) u.getObject(base, offset);
                        if (set != null && !set.isEmpty()) {
                            int size = set.size();
                            set.clear();
                            LOGGER.info("[GTOFix] Cleared Mixin blacklist {} ({} entries)", f.getName(), size);
                        }
                    } catch (Exception e) {
                        LOGGER.warn("[GTOFix] Failed to clear set field {}: {}", f.getName(), e.getMessage());
                    }
                }
            }

            // Remove GTOMixinExtension from the extension registry
            removeFromExtensionRegistry();

            LOGGER.info("[GTOFix] External Mixin blocking disabled");
        } catch (ClassNotFoundException e) {
            LOGGER.warn("[GTOFix] GTOMixinExtension not found — mixin bypass skipped");
        }
    }

    /** Removes GTOMixinExtension's IExtension from the Mixin transformer pipeline. */
    private static void removeFromExtensionRegistry() {
        try {
            Class<?> envClass = Class.forName("org.spongepowered.asm.mixin.MixinEnvironment");
            Method getEnv = envClass.getMethod("getDefaultEnvironment");
            Object env = getEnv.invoke(null);
            Method getTransformer = envClass.getMethod("getActiveTransformer");
            Object transformer = getTransformer.invoke(env);
            Method getExtensions = transformer.getClass().getMethod("getExtensions");
            Object extensions = getExtensions.invoke(transformer);

            Class<?> extClass = Class.forName("com.gtolib.GTOMixinExtension");
            Field obf57Field = extClass.getDeclaredField("obf_57");
            obf57Field.setAccessible(true);
            Object targetExtension = obf57Field.get(null);

            // Remove from extensionMap
            try {
                Field mapField = extensions.getClass().getDeclaredField("extensionMap");
                mapField.setAccessible(true);
                @SuppressWarnings("unchecked")
                Map<String, ?> map = (Map<String, ?>) mapField.get(extensions);
                map.values().removeIf(v -> v == targetExtension || targetExtension.getClass().isInstance(v));
            } catch (Exception ignored) {}

            // Remove from extensions list
            try {
                Field listField = extensions.getClass().getDeclaredField("extensions");
                listField.setAccessible(true);
                @SuppressWarnings("unchecked")
                java.util.List<?> list = (java.util.List<?>) listField.get(extensions);
                list.removeIf(v -> v == targetExtension || targetExtension.getClass().isInstance(v));
            } catch (Exception ignored) {}

            // Remove from activeExtensions
            try {
                Field activeField = extensions.getClass().getDeclaredField("activeExtensions");
                activeField.setAccessible(true);
                @SuppressWarnings("unchecked")
                java.util.List<?> active = (java.util.List<?>) activeField.get(extensions);
                ArrayList<?> copy = new ArrayList<>(active);
                copy.removeIf(v -> v == targetExtension || targetExtension.getClass().isInstance(v));
                Unsafe u = Utils.getUnsafe();
                if (u != null) {
                    u.putObject(extensions, u.objectFieldOffset(activeField),
                        com.google.common.collect.ImmutableList.copyOf(copy));
                }
            } catch (Exception ignored) {}

            LOGGER.info("[GTOFix] Removed GTOMixinExtension from transformer pipeline");
        } catch (Exception e) {
            LOGGER.warn("[GTOFix] Could not remove extension from registry: {}", e.getMessage());
        }
    }
}
