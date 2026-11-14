package dev.betavk.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

import java.io.InputStream;

/** java -cp ... dev.betavk.agent.PatchSelfTest : applies every patch kind to a toy class, loads it with verification on, and checks behaviour. */
public final class PatchSelfTest {
    private static int failures;

    static void check(String what, Object got, Object want) {
        boolean ok = got == null ? want == null : got.equals(want);
        System.out.println((ok ? "PASS " : "FAIL ") + what + (ok ? "" : ": got " + got + ", want " + want));
        if (!ok) failures++;
    }

    public static void main(String[] args) throws Exception {
        String toy = "dev/betavk/agent/Toy", hooks = "dev/betavk/agent/ToyHooks";
        PatchRegistry r = new PatchRegistry();
        r.on(toy)
                .replaceBody("add", "(II)I", hooks, "mul")
                .atStart("run", "(Ljava/lang/String;)V", hooks, "start")
                .atStartCancelable("run", "(Ljava/lang/String;)V", hooks, "startCancel")
                .atEnd("run", "(Ljava/lang/String;)V", hooks, "end")
                .beforeCall("caller", "()V", toy, "helper", "()V", hooks, "before")
                .afterCall("caller", "()V", toy, "helper", "()V", hooks, "afterHook")
                .redirectCall("redirectedStatic", "()I", "java/lang/Math", "max", "(II)I", true, hooks, "min")
                .redirectCall("redirectedInstance", "(Ljava/lang/String;)Ljava/lang/String;", "java/lang/String", "trim", "()Ljava/lang/String;", false, hooks, "shout")
                .addField("extra", "I")
                .replaceFloat("fov", "()F", 70.0F, 1, hooks, "base")
                .onReturn("fov", "()F", hooks, "onFov")
                .afterFieldSet("assign", "()V", toy, "setter", hooks, "afterSet")
                .atStartReturnIfNotNull("pick", "(I)Ljava/lang/String;", hooks, "early")
                .addMethod("extraMethod", "(I)Ljava/lang/String;", hooks, "added")
                .redirectStaticRead("readShared", "()Ljava/lang/String;", toy, "shared", hooks, "sharedHook")
                .redirectStaticWrite("writeShared", "()V", toy, "shared", hooks, "sharedWrite")
                .redirectCallNth("twice", "()I", "java/lang/Math", "max", "(II)I", true, 1, hooks, "min")
                .replaceInt("five", "()I", 5, 1, hooks, "nine")
                .atStartCancelable("guarded", "(I)Ljava/lang/String;", hooks, "guard");

        byte[] original;
        try (InputStream in = PatchSelfTest.class.getResourceAsStream("/" + toy + ".class")) {
            original = in.readAllBytes();
        }
        ClassNode cn = new ClassNode();
        new ClassReader(original).accept(cn, 0);
        int changes = r.apply(cn);
        byte[] patched = PatchAgent.write(cn);
        byte[] hookBytes;
        try (InputStream in = PatchSelfTest.class.getResourceAsStream("/" + hooks + ".class")) {
            hookBytes = in.readAllBytes();
        }
        ClassLoader loader = new ClassLoader(PatchSelfTest.class.getClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                byte[] b = name.equals("dev.betavk.agent.Toy") ? patched : name.equals("dev.betavk.agent.ToyHooks") ? hookBytes : null;
                if (b == null) return super.loadClass(name, resolve);
                synchronized (getClassLoadingLock(name)) {
                    Class<?> k = findLoadedClass(name);
                    return k != null ? k : defineClass(name, b, 0, b.length);
                }
            }
        };
        Class<?> c = loader.loadClass("dev.betavk.agent.Toy");
        Class<?> hookClass = loader.loadClass("dev.betavk.agent.ToyHooks");
        check("changes applied", changes > 0, true);
        Object t = c.getDeclaredConstructor().newInstance();
        java.util.List<String> log = null;
        for (var f : c.getFields()) if (f.getName().equals("log")) log = (java.util.List<String>) f.get(null);
        check("replaceBody", c.getMethod("add", int.class, int.class).invoke(t, 6, 7), 42);
        c.getMethod("run", String.class).invoke(t, "x");
        check("atStart + atEnd + body", log.toString(), "[start:x, run:x, end:x]");
        log.clear();
        hookClass.getField("cancel").setBoolean(null, true);
        c.getMethod("run", String.class).invoke(t, "y");
        check("cancelable start stops the body (end hook still not reached by early return)", log.toString(), "[]");
        log.clear();
        c.getMethod("caller").invoke(t);
        check("beforeCall and afterCall", log.toString(), "[before-helper, helper, after-helper-call, after-helper]");
        check("redirectCall static", c.getMethod("redirectedStatic").invoke(t), 3);
        check("redirectCall instance", c.getMethod("redirectedInstance", String.class).invoke(t, " hi "), " HI !");
        check("addField", c.getField("extra").getType(), int.class);
        check("replaceFloat + onReturn", c.getMethod("fov").invoke(t), 24.0F);
        log.clear();
        c.getMethod("assign").invoke(t);
        check("afterFieldSet", log.toString(), "[set-seen:5]");
        check("atStartReturnIfNotNull returns early", c.getMethod("pick", int.class).invoke(t, 1), "early");
        check("atStartReturnIfNotNull falls through", c.getMethod("pick", int.class).invoke(t, 2), "orig2");
        check("addMethod", c.getMethod("extraMethod", int.class).invoke(t, 7), "added7");
        check("redirectStaticRead", c.getMethod("readShared").invoke(t), "hooked");
        log.clear();
        c.getMethod("writeShared").invoke(t);
        check("redirectStaticWrite", log.toString(), "[wrote:written]");
        check("redirectCallNth only the second call", c.getMethod("twice").invoke(t), 5);
        check("replaceInt", c.getMethod("five").invoke(t), 11);
        check("cancelable on a method with a result returns null", c.getMethod("guarded", int.class).invoke(t, 1), null);
        check("cancelable lets the others run", c.getMethod("guarded", int.class).invoke(t, 2), "ran2");
        try {
            PatchRegistry bad = new PatchRegistry();
            bad.on(toy).atStart("noSuchMethod", null, hooks, "start");
            ClassNode n2 = new ClassNode();
            new ClassReader(original).accept(n2, 0);
            bad.apply(n2);
            check("missing target fails loudly", "no exception", "exception");
        } catch (IllegalStateException e) {
            check("missing target fails loudly", true, true);
        }
        System.out.println(failures == 0 ? "ALL PASS" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
