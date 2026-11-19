package dev.betavk.mcprep;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Obfuscated client jar + mappings -> readable jar:
 * names remapped, every class/field/method made public and non-final, and the few LWJGL 2 classes the game uses redirected to our own platform and
 * graphics layer. Everything is derived from the jar the user supplies; nothing is stored in the repository.
 */
public final class Prep {
    /** LWJGL 2 owner -> our class with the same static API. */
    static final Map<String, String> REDIRECT = new HashMap<>();

    static {
        for (String g : new String[]{"GL11", "GL12", "GL13", "GL14", "GL15", "ARBOcclusionQuery", "ARBVertexBufferObject"}) REDIRECT.put("org/lwjgl/opengl/" + g, "dev/betavk/render/Gfx");
        REDIRECT.put("org/lwjgl/util/glu/GLU", "dev/betavk/render/Gfx");
        for (String p : new String[]{"Keyboard", "Mouse"}) REDIRECT.put("org/lwjgl/input/" + p, "dev/betavk/platform/" + p);
        REDIRECT.put("org/lwjgl/opengl/Display", "dev/betavk/platform/Display");
        REDIRECT.put("org/lwjgl/opengl/DisplayMode", "dev/betavk/platform/DisplayMode");
        REDIRECT.put("org/lwjgl/opengl/PixelFormat", "dev/betavk/compat/PixelFormat");
        REDIRECT.put("org/lwjgl/LWJGLException", "dev/betavk/platform/LWJGLException");
        REDIRECT.put("org/lwjgl/Sys", "dev/betavk/platform/Sys");
        REDIRECT.put("org/lwjgl/opengl/GLContext", "dev/betavk/compat/GLContext");
        REDIRECT.put("org/lwjgl/opengl/ContextCapabilities", "dev/betavk/compat/ContextCapabilities");
        REDIRECT.put("org/lwjgl/input/Controllers", "dev/betavk/compat/Controllers");
        REDIRECT.put("org/lwjgl/input/Cursor", "dev/betavk/compat/Cursor");
        REDIRECT.put("paulscode/sound/SoundSystem", "dev/betavk/audio/AlSoundSystem");   // the game's sound calls go to our OpenAL 3 sound system
    }

    /** Calls into Gfx whose LWJGL 2 shape differs; these owners are switched to our adapter class (name + descriptor). */
    static final java.util.Set<String> ODD_GL = java.util.Set.of(
            "glColorPointer(IZILjava/nio/ByteBuffer;)V", "glNormalPointer(ILjava/nio/ByteBuffer;)V", "glTexCoordPointer(IILjava/nio/FloatBuffer;)V",
            "glVertexPointer(IILjava/nio/FloatBuffer;)V", "glFog(ILjava/nio/FloatBuffer;)V", "glLight(IILjava/nio/FloatBuffer;)V",
            "glLightModel(ILjava/nio/FloatBuffer;)V", "glGetFloat(ILjava/nio/FloatBuffer;)V", "glGetQueryObjectuARB(IILjava/nio/IntBuffer;)V");

    private static final class OddGl extends ClassVisitor {
        OddGl(ClassVisitor next) {
            super(Opcodes.ASM9, next);
        }

        @Override public MethodVisitor visitMethod(int a, String n, String d, String s, String[] e) {
            MethodVisitor mv = super.visitMethod(a, n, d, s, e);
            return mv == null ? null : new MethodVisitor(Opcodes.ASM9, mv) {
                @Override public void visitMethodInsn(int op, String owner, String name, String desc, boolean itf) {
                    if (op == Opcodes.INVOKESTATIC && owner.equals("dev/betavk/render/Gfx") && ODD_GL.contains(name + desc)) owner = "dev/betavk/compat/Lwjgl2Gl";
                    super.visitMethodInsn(op, owner, name, desc, itf);
                }
            };
        }
    }

    private static final class Info {
        String superName;
        String[] interfaces;
    }

    private final TinyMappings maps;
    private final Map<String, Info> hierarchy = new HashMap<>();

    Prep(TinyMappings maps) {
        this.maps = maps;
    }

    private final class MapRemapper extends Remapper {
        @Override public String map(String internalName) {
            String r = REDIRECT.get(internalName);
            if (r != null) return r;
            String m = maps.classes.get(internalName);
            if ("net/minecraft/src/Minecraft".equals(m) && !maps.classes.containsValue("net/minecraft/client/Minecraft")) {
                return "net/minecraft/client/Minecraft";
            }
            return m != null ? m : internalName;
        }

        @Override public String mapMethodName(String owner, String name, String descriptor) {
            if (name.startsWith("<")) return name;
            String r = find(maps.methods, owner, name, descriptor);
            return r != null ? r : name;
        }

        @Override public String mapFieldName(String owner, String name, String descriptor) {
            String r = find(maps.fields, owner, name, descriptor);
            return r != null ? r : name;
        }

        private String find(Map<String, String> table, String owner, String name, String desc) {
            if (owner == null || owner.startsWith("[")) return null;
            String hit = table.get(owner + "." + name + " " + desc);
            if (hit != null) return hit;
            Info i = hierarchy.get(owner);
            if (i == null) return null;
            if (i.superName != null) {
                String r = find(table, i.superName, name, desc);
                if (r != null) return r;
            }
            if (i.interfaces != null) {
                for (String itf : i.interfaces) {
                    String r = find(table, itf, name, desc);
                    if (r != null) return r;
                }
            }
            return null;
        }
    }

    /** Public, non-final everything. */
    private static final class Widen extends ClassVisitor {
        Widen(ClassVisitor next) {
            super(Opcodes.ASM9, next);
        }

        private static int pub(int access) {
            return (access & ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED)) | Opcodes.ACC_PUBLIC;
        }

        @Override public void visit(int version, int access, String name, String sig, String superName, String[] interfaces) {
            super.visit(version, pub(access) & ~Opcodes.ACC_FINAL, name, sig, superName, interfaces);
        }

        @Override public void visitInnerClass(String name, String outer, String inner, int access) {
            super.visitInnerClass(name, outer, inner, pub(access) & ~Opcodes.ACC_FINAL);
        }

        @Override public FieldVisitor visitField(int access, String name, String desc, String sig, Object value) {
            boolean constant = (access & Opcodes.ACC_STATIC) != 0 && (access & Opcodes.ACC_FINAL) != 0 && value != null;
            int a = constant ? pub(access) : pub(access) & ~Opcodes.ACC_FINAL;
            return super.visitField(a & ~Opcodes.ACC_SYNTHETIC, name, desc, sig, value);   // the outer-class reference of an inner class and the like: javac hides synthetic members
        }

        @Override public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] exceptions) {
            int a = name.equals("<clinit>") ? access : pub(access);
            if (!name.startsWith("<")) a &= ~Opcodes.ACC_FINAL;
            if ((a & Opcodes.ACC_STATIC) != 0 && (a & Opcodes.ACC_BRIDGE) == 0) a &= ~Opcodes.ACC_SYNTHETIC;   // the static accessors inner classes use to reach their outer class
            return super.visitMethod(a, name, desc, sig, exceptions);
        }
    }

    public void run(Path inJar, Path outJar) throws IOException {
        List<String> classEntries = new ArrayList<>();
        try (ZipFile z = new ZipFile(inJar.toFile())) {
            for (var e : Collections.list(z.entries())) {
                if (!e.getName().endsWith(".class")) continue;
                classEntries.add(e.getName());
                try (InputStream in = z.getInputStream(e)) {
                    ClassReader cr = new ClassReader(in);
                    Info i = new Info();
                    i.superName = cr.getSuperName();
                    i.interfaces = cr.getInterfaces();
                    hierarchy.put(cr.getClassName(), i);
                }
            }
            Files.createDirectories(outJar.toAbsolutePath().getParent());
            Remapper remapper = new MapRemapper();
            Map<String, org.objectweb.asm.tree.ClassNode> remapped = new java.util.LinkedHashMap<>();
            for (String name : classEntries) {
                if (name.startsWith("paulscode/") || name.startsWith("com/jcraft/")) continue;   // third-party libraries bundled in the jar: the game uses the separate library jars (and our sound system replaces SoundSystem)
                byte[] bytes;
                try (InputStream in = z.getInputStream(z.getEntry(name))) {
                    bytes = in.readAllBytes();
                }
                ClassReader cr = new ClassReader(bytes);
                org.objectweb.asm.tree.ClassNode cn = new org.objectweb.asm.tree.ClassNode();
                cr.accept(new ClassRemapper(new Widen(new OddGl(cn)), remapper), 0);
                remapped.put(cn.name, cn);
            }
            Canonicalizer.normalize(remapped);
            try (OutputStream os = Files.newOutputStream(outJar); ZipOutputStream out = new ZipOutputStream(os)) {
                for (org.objectweb.asm.tree.ClassNode cn : remapped.values()) {
                    ClassWriter cw = new ClassWriter(0);
                    cn.accept(cw);
                    out.putNextEntry(new ZipEntry(cn.name + ".class"));
                    out.write(cw.toByteArray());
                    out.closeEntry();
                }
            }
        }
    }
}
