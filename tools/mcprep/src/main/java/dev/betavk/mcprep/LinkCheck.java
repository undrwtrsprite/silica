package dev.betavk.mcprep;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipFile;

/**
 * Checks that everything the readable jar calls outside itself exists: for each referenced class that is not in the jar, it must load from the given
 * classpath (our platform/graphics layer, the JDK), and each referenced static/instance method or field on it must exist there.
 * java -cp ... LinkCheck <readable.jar> <classpath entries...>
 */
public final class LinkCheck {
    public static void main(String[] args) throws Exception {
        File jar = new File(args[0]);
        List<URL> urls = new ArrayList<>();
        for (int i = 1; i < args.length; i++) urls.add(new File(args[i]).toURI().toURL());
        URLClassLoader ext = new URLClassLoader(urls.toArray(new URL[0]), LinkCheck.class.getClassLoader());
        Set<String> inJar = new HashSet<>();
        try (ZipFile z = new ZipFile(jar)) {
            for (var e : Collections.list(z.entries())) if (e.getName().endsWith(".class")) inJar.add(e.getName().substring(0, e.getName().length() - 6));
            Map<String, Set<String>> missingClass = new TreeMap<>(), missingMember = new TreeMap<>();
            Map<String, Boolean> classOk = new HashMap<>();
            for (var e : Collections.list(z.entries())) {
                if (!e.getName().endsWith(".class")) continue;
                try (InputStream in = z.getInputStream(e)) {
                    ClassReader cr = new ClassReader(in);
                    String user = cr.getClassName();
                    cr.accept(new ClassVisitor(Opcodes.ASM9) {
                        @Override public MethodVisitor visitMethod(int a, String n, String d, String s, String[] ex) {
                            return new MethodVisitor(Opcodes.ASM9) {
                                @Override public void visitMethodInsn(int op, String owner, String name, String desc, boolean itf) {
                                    check(owner, name, desc, true, user);
                                }

                                @Override public void visitFieldInsn(int op, String owner, String name, String desc) {
                                    check(owner, name, desc, false, user);
                                }

                                @Override public void visitTypeInsn(int op, String type) {
                                    check(type, null, null, false, user);
                                }

                                private void check(String owner, String name, String desc, boolean method, String user) {
                                    if (owner.startsWith("[") || inJar.contains(owner) || owner.startsWith("java/") || owner.startsWith("javax/") && false) return;
                                    Class<?> c = null;
                                    Boolean ok = classOk.get(owner);
                                    if (ok == null) {
                                        try {
                                            c = Class.forName(owner.replace('/', '.'), false, ext);
                                            ok = true;
                                        } catch (Throwable t) {
                                            ok = false;
                                        }
                                        classOk.put(owner, ok);
                                    } else if (ok) {
                                        try {
                                            c = Class.forName(owner.replace('/', '.'), false, ext);
                                        } catch (Throwable t) {
                                            return;
                                        }
                                    }
                                    if (!ok) {
                                        missingClass.computeIfAbsent(owner, k -> new java.util.TreeSet<>()).add(user);
                                        return;
                                    }
                                    if (name == null || owner.startsWith("java/")) return;
                                    boolean found = false;
                                    if (method) {
                                        for (Class<?> k = c; k != null && !found; k = k.getSuperclass()) found = hasMethod(k, name, desc);
                                        if (!found) found = hasMethodInInterfaces(c, name, desc);
                                    } else {
                                        for (Class<?> k = c; k != null && !found; k = k.getSuperclass()) {
                                            for (var f : k.getDeclaredFields()) if (f.getName().equals(name)) found = true;
                                        }
                                    }
                                    if (!found) missingMember.computeIfAbsent(owner + "." + name + (method ? desc : ""), k -> new java.util.TreeSet<>()).add(user);
                                }
                            };
                        }
                    }, 0);
                }
            }
            System.out.println("== classes not found on the given classpath: " + missingClass.size());
            missingClass.forEach((k, v) -> System.out.println("  " + k + "   (used by " + v.size() + " classes, e.g. " + v.iterator().next() + ")"));
            System.out.println("== members not found: " + missingMember.size());
            missingMember.forEach((k, v) -> System.out.println("  " + k + "   (used by " + v.size() + ", e.g. " + v.iterator().next() + ")"));
        }
    }

    private static boolean hasMethod(Class<?> k, String name, String desc) {
        for (Method m : k.getDeclaredMethods()) {
            if (m.getName().equals(name) && descriptor(m).equals(desc)) return true;
        }
        if (name.equals("<init>")) {
            for (var c : k.getDeclaredConstructors()) if (ctorDesc(c).equals(desc)) return true;
        }
        return false;
    }

    private static boolean hasMethodInInterfaces(Class<?> c, String name, String desc) {
        for (Class<?> i : c.getInterfaces()) if (hasMethod(i, name, desc) || hasMethodInInterfaces(i, name, desc)) return true;
        Class<?> s = c.getSuperclass();
        return s != null && hasMethodInInterfaces(s, name, desc);
    }

    private static String descriptor(Method m) {
        StringBuilder sb = new StringBuilder("(");
        for (Class<?> p : m.getParameterTypes()) sb.append(org.objectweb.asm.Type.getDescriptor(p));
        return sb.append(')').append(org.objectweb.asm.Type.getDescriptor(m.getReturnType())).toString();
    }

    private static String ctorDesc(java.lang.reflect.Constructor<?> c) {
        StringBuilder sb = new StringBuilder("(");
        for (Class<?> p : c.getParameterTypes()) sb.append(org.objectweb.asm.Type.getDescriptor(p));
        return sb.append(")V").toString();
    }
}
