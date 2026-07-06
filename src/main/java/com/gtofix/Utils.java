package com.gtofix;

import java.lang.reflect.Field;

/**
 * Minimal reflection utilities shared by Patcher, Scanner and Mixins.
 */
public final class Utils {

    private Utils() {}

    public static Field findField(Class<?> clazz, String name) {
        for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {}
        }
        return null;
    }

    public static int getIntField(Object obj, String name) {
        Field f = findField(obj.getClass(), name);
        if (f != null) try { return f.getInt(obj); } catch (Exception ignored) {}
        return 0;
    }

    public static Object getObjectField(Object obj, String name) {
        Field f = findField(obj.getClass(), name);
        if (f != null) try { return f.get(obj); } catch (Exception ignored) {}
        return null;
    }

    public static void setIntField(Object obj, String name, int value) {
        Field f = findField(obj.getClass(), name);
        if (f != null) try { f.setInt(obj, value); } catch (Exception ignored) {}
    }

    public static void setObjectField(Object obj, String name, Object value) {
        Field f = findField(obj.getClass(), name);
        if (f != null) try { f.set(obj, value); } catch (Exception ignored) {}
    }

    public static sun.misc.Unsafe getUnsafe() {
        try {
            Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            return (sun.misc.Unsafe) f.get(null);
        } catch (Exception e) { return null; }
    }

    /** Read a static int field, even if private. Returns -1 on failure. */
    public static int getStaticIntField(Class<?> clazz, String fieldName) {
        Field f = findField(clazz, fieldName);
        if (f != null) try { return f.getInt(null); } catch (Exception ignored) {}
        return -1;
    }

    /** Set a static final int field using Unsafe. Returns true on success. */
    public static boolean setStaticFinalInt(Class<?> clazz, String fieldName, int value) {
        sun.misc.Unsafe u = getUnsafe();
        if (u == null) return false;
        try {
            Field f = clazz.getDeclaredField(fieldName);
            long offset = u.staticFieldOffset(f);
            Object base = u.staticFieldBase(f);
            u.putInt(base, offset, value);
            return true;
        } catch (Exception e) { return false; }
    }
}
