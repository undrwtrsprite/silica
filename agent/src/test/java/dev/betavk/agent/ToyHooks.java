package dev.betavk.agent;

public final class ToyHooks {
    public static boolean cancel;

    public static int mul(Toy self, int a, int b) {
        return a * b;
    }

    public static void start(Toy self, String s) {
        Toy.log.add("start:" + s);
    }

    public static boolean startCancel(Toy self, String s) {
        return cancel;
    }

    public static void end(Toy self, String s) {
        Toy.log.add("end:" + s);
    }

    public static void before(Toy self) {
        Toy.log.add("before-helper");
    }

    public static void afterHook(Toy self) {
        Toy.log.add("after-helper-call");
    }

    public static float onFov(Toy self, float v) {
        return v * 2.0F;
    }

    public static float base(Toy self) {
        return 10.0F;
    }

    public static void afterSet(Toy self) {
        Toy.log.add("set-seen:" + self.setter);
    }

    public static String early(Toy self, int i) {
        return i == 1 ? "early" : null;
    }

    public static String added(Toy self, int i) {
        return "added" + i;
    }

    public static String sharedHook() {
        return "hooked";
    }

    public static void sharedWrite(String v) {
        Toy.log.add("wrote:" + v);
    }

    public static int nine(Toy self) {
        return 9;
    }

    public static boolean guard(Toy self, int i) {
        return i == 1;
    }

    public static int min(int a, int b) {
        return Math.min(a, b);
    }

    public static String shout(String s) {
        return s.toUpperCase() + "!";
    }
}
