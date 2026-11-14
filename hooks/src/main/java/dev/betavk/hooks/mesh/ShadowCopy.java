package dev.betavk.hooks.mesh;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/** A shallow copy of an object without cloning support: same class, every instance field copied. A mesh worker renders with its own copies of the Blocks. */
final class ShadowCopy {
    private static final Object UNSAFE;
    private static final java.lang.reflect.Method ALLOCATE;

    static {
        try {
            Class<?> uc = Class.forName("sun.misc.Unsafe");
            Field f = uc.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            UNSAFE = f.get(null);
            ALLOCATE = uc.getMethod("allocateInstance", Class.class);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private ShadowCopy() {
    }

    @SuppressWarnings("unchecked")
    static <T> T of(T o) {
        try {
            T copy = (T) ALLOCATE.invoke(UNSAFE, o.getClass());
            for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    f.setAccessible(true);
                    f.set(copy, f.get(o));
                }
            }
            return copy;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
