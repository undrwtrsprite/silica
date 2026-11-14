package dev.betavk.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;

/**
 * {@code -javaagent:patch-agent.jar -Dbetavk.patchset=a.b.PatchSet[,c.d.Other]}: loads the named patch sets (our classes, on the application
 * classpath) and applies their patches to Minecraft classes as the JVM loads them.
 */
public final class PatchAgent {
    private PatchAgent() {
    }

    /** Serialises a patched class. Minecraft's classes are class-file version 49 (no stack map frames); anything newer gets frames recomputed. */
    public static byte[] write(ClassNode cn) {
        ClassWriter cw = new ClassWriter(cn.version >= 50 ? ClassWriter.COMPUTE_FRAMES : ClassWriter.COMPUTE_MAXS) {
            @Override protected String getCommonSuperClass(String a, String b) {
                return "java/lang/Object";
            }
        };
        cn.accept(cw);
        return cw.toByteArray();
    }

    public static void premain(String args, Instrumentation inst) throws Exception {
        PatchRegistry registry = new PatchRegistry();
        String sets = System.getProperty("betavk.patchset", args == null ? "" : args);
        for (String name : sets.split(",")) {
            name = name.trim();
            if (name.isEmpty()) continue;
            ((PatchSet) Class.forName(name, true, ClassLoader.getSystemClassLoader()).getDeclaredConstructor().newInstance()).register(registry);
        }
        boolean log = Boolean.getBoolean("betavk.patchlog");
        inst.addTransformer(new ClassFileTransformer() {
            @Override public byte[] transform(ClassLoader loader, String className, Class<?> redefined, ProtectionDomain pd, byte[] bytes) {
                if (className == null || !registry.has(className)) return null;
                try {
                    ClassNode cn = new ClassNode();
                    new ClassReader(bytes).accept(cn, 0);
                    int n = registry.apply(cn);
                    if (log && n > 0) System.out.println("[patch] " + className + ": " + n + " change(s)");
                    return write(cn);
                } catch (Throwable t) {
                    // an exception thrown from a transformer is swallowed by the JVM, which would run the class unpatched: report it and stop
                    t.printStackTrace();
                    Runtime.getRuntime().halt(3);
                    return null;
                }
            }
        });
    }
}
