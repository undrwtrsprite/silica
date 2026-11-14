package dev.betavk.vox;

/** Allocates objects without running constructors (for stand-in Worlds). */
final class Sandbox {
    private static final Object UNSAFE;
    private static final java.lang.reflect.Method ALLOCATE;

    static {
        try {
            Class<?> uc = Class.forName("sun.misc.Unsafe");
            java.lang.reflect.Field f = uc.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            UNSAFE = f.get(null);
            ALLOCATE = uc.getMethod("allocateInstance", Class.class);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private Sandbox() {
    }

    @SuppressWarnings("unchecked")
    static <T> T allocate(Class<T> c) {
        try {
            return (T) ALLOCATE.invoke(UNSAFE, c);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
