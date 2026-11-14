package dev.betavk.agent;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The patches, by class. Classes and methods are named with the readable (remapped) names; descriptors use those names too. A patch that does not
 * find its target throws when the class loads, so a game version or mapping mismatch fails loudly instead of silently doing nothing.
 *
 * <p>Hooks are public static methods in our own classes. For a patch on an instance method the hook receives {@code this} first, then the method's
 * arguments; for a static method just the arguments.
 */
public final class PatchRegistry {
    private final Map<String, ClassPatch> byClass = new HashMap<>();

    private final java.util.List<GlobalStep> globals = new ArrayList<>();

    public ClassPatch on(String internalName) {
        return byClass.computeIfAbsent(internalName, ClassPatch::new);
    }

    /**
     * Patches applied to every class in net/minecraft/: every read of the static field fieldOwner.fieldName (outside its own class) becomes
     * {@code hook()} returning the same type. Used where a global is really per-thread, such as the game's single Tessellator.
     */
    public PatchRegistry everywhereRedirectStaticRead(String fieldOwner, String fieldName, String hookOwner, String hookName) {
        globals.add((cn) -> {
            if (cn.name.equals(fieldOwner)) return 0;
            int n = 0;
            for (MethodNode m : cn.methods) {
                for (AbstractInsnNode i : m.instructions.toArray()) {
                    if (i instanceof org.objectweb.asm.tree.FieldInsnNode f && f.getOpcode() == Opcodes.GETSTATIC && f.owner.equals(fieldOwner) && f.name.equals(fieldName)) {
                        m.instructions.set(i, new MethodInsnNode(Opcodes.INVOKESTATIC, hookOwner, hookName, "()" + f.desc, false));
                        n++;
                    }
                }
            }
            return n;
        });
        return this;
    }

    boolean has(String internalName) {
        return byClass.containsKey(internalName) || (!globals.isEmpty() && internalName.startsWith("net/minecraft/"));
    }

    /** Applies every patch registered for the class; returns how many instruction groups were changed. */
    int apply(ClassNode cn) {
        ClassPatch p = byClass.get(cn.name);
        int n = p == null ? 0 : p.apply(cn);
        for (GlobalStep g : globals) n += g.apply(cn);
        return n;
    }

    @FunctionalInterface
    private interface GlobalStep {
        int apply(ClassNode cn);
    }

    @FunctionalInterface
    private interface Step {
        int apply(ClassNode cn);
    }

    public static final class ClassPatch {
        private final String owner;
        private final List<Step> steps = new ArrayList<>();
        private final java.util.Set<AbstractInsnNode> cancelReturns = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());   // returns we inserted ourselves: atEnd hooks are for the method's own returns

        ClassPatch(String owner) {
            this.owner = owner;
        }

        int apply(ClassNode cn) {
            int n = 0;
            cancelReturns.clear();
            for (Step s : steps) n += s.apply(cn);
            return n;
        }

        /** Calls hook(this, args...) at the start of the method. A void hook only observes. */
        public ClassPatch atStart(String method, String desc, String hookOwner, String hookName) {
            steps.add(cn -> forEach(cn, method, desc, m -> insertStart(m, hookOwner, hookName, false)));
            return this;
        }

        /** Calls boolean hook(this, args...) at the start; when it returns true the method returns at once (void methods only). */
        public ClassPatch atStartCancelable(String method, String desc, String hookOwner, String hookName) {
            steps.add(cn -> forEach(cn, method, desc, m -> insertStart(m, hookOwner, hookName, true)));
            return this;
        }

        /** Calls hook(this, args...) before every return of a void method. */
        public ClassPatch atEnd(String method, String desc, String hookOwner, String hookName) {
            steps.add(cn -> forEach(cn, method, desc, m -> insertEnd(m, hookOwner, hookName)));
            return this;
        }

        /** Calls hook(this) in front of every call of calleeOwner.calleeName calleeDesc inside the method. */
        public ClassPatch beforeCall(String method, String desc, String calleeOwner, String calleeName, String calleeDesc, String hookOwner, String hookName) {
            steps.add(cn -> forEach(cn, method, desc, m -> aroundCall(m, calleeOwner, calleeName, calleeDesc, hookOwner, hookName, true)));
            return this;
        }

        /** Calls hook(this) right after every call of calleeOwner.calleeName calleeDesc inside the method (the call's own result stays on the stack). */
        public ClassPatch afterCall(String method, String desc, String calleeOwner, String calleeName, String calleeDesc, String hookOwner, String hookName) {
            steps.add(cn -> forEach(cn, method, desc, m -> aroundCall(m, calleeOwner, calleeName, calleeDesc, hookOwner, hookName, false)));
            return this;
        }

        /** Replaces every call of callee inside the method by a static hook taking (receiver if any, the call's arguments...) and returning the same type. */
        public ClassPatch redirectCall(String method, String desc, String calleeOwner, String calleeName, String calleeDesc, boolean calleeStatic, String hookOwner, String hookName) {
            steps.add(cn -> forEach(cn, method, desc, m -> redirect(m, calleeOwner, calleeName, calleeDesc, calleeStatic, hookOwner, hookName)));
            return this;
        }

        /** The method becomes {@code return hook(this, args...)}. */
        public ClassPatch replaceBody(String method, String desc, String hookOwner, String hookName) {
            steps.add(cn -> forEach(cn, method, desc, m -> replace(m, hookOwner, hookName)));
            return this;
        }

        public ClassPatch addField(String name, String desc) {
            steps.add(cn -> {
                for (FieldNode f : cn.fields) if (f.name.equals(name)) return 0;
                cn.fields.add(new FieldNode(Opcodes.ACC_PUBLIC, name, desc, null, null));
                return 1;
            });
            return this;
        }


        /** Calls hook(this, value) before every return of a method that returns an int, float or reference; the hook's result is what is returned. */
        public ClassPatch onReturn(String method, String desc, String hookOwner, String hookName) {
            steps.add(cn -> forEach(cn, method, desc, m -> modifyReturn(m, hookOwner, hookName)));
            return this;
        }

        /** Replaces every float constant equal to {@code value} in the method by hook(this) (must match exactly {@code expected} places). */
        public ClassPatch replaceFloat(String method, String desc, float value, int expected, String hookOwner, String hookName) {
            steps.add(cn -> forEach(cn, method, desc, m -> constant(m, value, expected, hookOwner, hookName)));
            return this;
        }

        /** Calls hook(this) right after every assignment to this class's (or another's) field fieldOwner.fieldName inside the method. */
        public ClassPatch afterFieldSet(String method, String desc, String fieldOwner, String fieldName, String hookOwner, String hookName) {
            steps.add(cn -> forEach(cn, method, desc, m -> afterPut(m, fieldOwner, fieldName, hookOwner, hookName)));
            return this;
        }

        /**
         * At the start: calls hook(this, args...) which returns the method's own return type (a reference); when the result is not null the method
         * returns it, otherwise it runs as usual.
         */
        public ClassPatch atStartReturnIfNotNull(String method, String desc, String hookOwner, String hookName) {
            steps.add(cn -> forEach(cn, method, desc, m -> startReturn(m, hookOwner, hookName)));
            return this;
        }

        /** Adds a public method that calls hook(this, args...) and returns its result (an override point or a new accessor). */
        public ClassPatch addMethod(String name, String desc, String hookOwner, String hookName) {
            steps.add(cn -> {
                for (MethodNode m : cn.methods) if (m.name.equals(name) && m.desc.equals(desc)) throw new IllegalStateException("method already exists: " + owner + "." + name + desc);
                MethodNode m = new MethodNode(Opcodes.ACC_PUBLIC, name, desc, null, null);
                Type ret = Type.getReturnType(desc);
                InsnList l = loadSelfAndArgs(m);
                l.add(new MethodInsnNode(Opcodes.INVOKESTATIC, hookOwner, hookName, hookDesc(m, ret.getDescriptor()), false));
                l.add(new InsnNode(ret.getOpcode(Opcodes.IRETURN)));
                m.instructions.add(l);
                cn.methods.add(m);
                return 1;
            });
            return this;
        }


        /** Like redirectCall, but only the nth (0-based, in code order) matching call site inside the method. */
        public ClassPatch redirectCallNth(String method, String desc, String calleeOwner, String calleeName, String calleeDesc, boolean calleeStatic, int nth, String hookOwner, String hookName) {
            steps.add(cn -> forEach(cn, method, desc, m -> redirectNth(m, calleeOwner, calleeName, calleeDesc, calleeStatic, nth, hookOwner, hookName)));
            return this;
        }

        /** Replaces every int constant equal to {@code value} in the method by hook(this) (must match exactly {@code expected} places). */
        public ClassPatch replaceInt(String method, String desc, int value, int expected, String hookOwner, String hookName) {
            steps.add(cn -> forEach(cn, method, desc, m -> intConstant(m, value, expected, hookOwner, hookName)));
            return this;
        }

        /** Every read of fieldOwner.fieldName (static) inside the method becomes hook() returning the field's type. */
        public ClassPatch redirectStaticRead(String method, String desc, String fieldOwner, String fieldName, String hookOwner, String hookName) {
            steps.add(cn -> forEach(cn, method, desc, m -> staticRead(m, fieldOwner, fieldName, hookOwner, hookName)));
            return this;
        }

        /** Every write to the static field fieldOwner.fieldName inside the method becomes hook(value). */
        public ClassPatch redirectStaticWrite(String method, String desc, String fieldOwner, String fieldName, String hookOwner, String hookName) {
            steps.add(cn -> forEach(cn, method, desc, m -> staticWrite(m, fieldOwner, fieldName, hookOwner, hookName)));
            return this;
        }


        /** Every {@code new oldClass(...)} inside the method creates newClass instead (a subclass or substitute with the same constructors). */
        public ClassPatch replaceNew(String method, String desc, String oldClass, String newClass, int expected) {
            steps.add(cn -> forEach(cn, method, desc, m -> swapNew(m, oldClass, newClass, expected)));
            return this;
        }

        // ---- implementation ------------------------------------------------------------

        private interface Edit {
            int edit(MethodNode m);
        }

        private int forEach(ClassNode cn, String method, String desc, Edit e) {
            int hits = 0, n = 0;
            for (MethodNode m : cn.methods) {
                if (!m.name.equals(method) || (desc != null && !m.desc.equals(desc))) continue;
                hits++;
                n += e.edit(m);
            }
            if (hits == 0) throw new IllegalStateException("patch target not found: " + owner + "." + method + (desc == null ? "" : desc));
            if (n == 0) throw new IllegalStateException("patch matched the method but changed nothing (call site missing?): " + owner + "." + method);
            return n;
        }

        private InsnList loadSelfAndArgs(MethodNode m) {
            InsnList l = new InsnList();
            int slot = 0;
            boolean isStatic = (m.access & Opcodes.ACC_STATIC) != 0;
            if (!isStatic) {
                l.add(new VarInsnNode(Opcodes.ALOAD, 0));
                slot = 1;
            }
            for (Type t : Type.getArgumentTypes(m.desc)) {
                l.add(new VarInsnNode(t.getOpcode(Opcodes.ILOAD), slot));
                slot += t.getSize();
            }
            return l;
        }

        private String hookDesc(MethodNode m, String returns) {
            StringBuilder sb = new StringBuilder("(");
            if ((m.access & Opcodes.ACC_STATIC) == 0) sb.append('L').append(owner).append(';');
            for (Type t : Type.getArgumentTypes(m.desc)) sb.append(t.getDescriptor());
            return sb.append(')').append(returns).toString();
        }

        private int insertStart(MethodNode m, String hookOwner, String hookName, boolean cancelable) {
            InsnList l = loadSelfAndArgs(m);
            if (cancelable) {
                Type ret = Type.getReturnType(m.desc);
                l.add(new MethodInsnNode(Opcodes.INVOKESTATIC, hookOwner, hookName, hookDesc(m, "Z"), false));
                LabelNode go = new LabelNode();
                l.add(new JumpInsnNode(Opcodes.IFEQ, go));
                // when the hook says so the method returns at once: nothing for void, the default value (0, false, null) otherwise
                switch (ret.getSort()) {
                    case Type.VOID -> {
                    }
                    case Type.LONG -> l.add(new InsnNode(Opcodes.LCONST_0));
                    case Type.FLOAT -> l.add(new InsnNode(Opcodes.FCONST_0));
                    case Type.DOUBLE -> l.add(new InsnNode(Opcodes.DCONST_0));
                    case Type.OBJECT, Type.ARRAY -> l.add(new InsnNode(Opcodes.ACONST_NULL));
                    default -> l.add(new InsnNode(Opcodes.ICONST_0));
                }
                InsnNode early = new InsnNode(ret.getOpcode(Opcodes.IRETURN));
                cancelReturns.add(early);
                l.add(early);
                l.add(go);
            } else {
                l.add(new MethodInsnNode(Opcodes.INVOKESTATIC, hookOwner, hookName, hookDesc(m, "V"), false));
            }
            m.instructions.insert(l);
            return 1;
        }

        private int insertEnd(MethodNode m, String hookOwner, String hookName) {
            if (Type.getReturnType(m.desc).getSort() != Type.VOID) throw new IllegalStateException("atEnd is for void methods: " + owner + "." + m.name);
            int n = 0;
            for (AbstractInsnNode i : m.instructions.toArray()) {
                if (i.getOpcode() != Opcodes.RETURN || cancelReturns.contains(i)) continue;
                InsnList l = loadSelfAndArgs(m);
                l.add(new MethodInsnNode(Opcodes.INVOKESTATIC, hookOwner, hookName, hookDesc(m, "V"), false));
                m.instructions.insertBefore(i, l);
                n++;
            }
            return n;
        }

        private int aroundCall(MethodNode m, String cOwner, String cName, String cDesc, String hookOwner, String hookName, boolean before) {
            if ((m.access & Opcodes.ACC_STATIC) != 0) throw new IllegalStateException("beforeCall/afterCall need an instance method: " + owner + "." + m.name);
            int n = 0;
            for (AbstractInsnNode i : m.instructions.toArray()) {
                if (!(i instanceof MethodInsnNode c) || !c.owner.equals(cOwner) || !c.name.equals(cName) || !c.desc.equals(cDesc)) continue;
                InsnList l = new InsnList();
                l.add(new VarInsnNode(Opcodes.ALOAD, 0));
                l.add(new MethodInsnNode(Opcodes.INVOKESTATIC, hookOwner, hookName, "(L" + owner + ";)V", false));
                if (before) m.instructions.insertBefore(i, l);
                else m.instructions.insert(i, l);
                n++;
            }
            return n;
        }

        private int redirect(MethodNode m, String cOwner, String cName, String cDesc, boolean cStatic, String hookOwner, String hookName) {
            int n = 0;
            for (AbstractInsnNode i : m.instructions.toArray()) {
                if (!(i instanceof MethodInsnNode c) || !c.owner.equals(cOwner) || !c.name.equals(cName) || !c.desc.equals(cDesc)) continue;
                String d = cStatic ? cDesc : "(L" + cOwner + ";" + cDesc.substring(1);
                m.instructions.set(i, new MethodInsnNode(Opcodes.INVOKESTATIC, hookOwner, hookName, d, false));
                n++;
            }
            return n;
        }


        private int modifyReturn(MethodNode m, String hookOwner, String hookName) {
            Type ret = Type.getReturnType(m.desc);
            int op = ret.getOpcode(Opcodes.IRETURN);
            if (ret.getSize() != 1 || ret.getSort() == Type.VOID) throw new IllegalStateException("onReturn supports one-slot return types: " + owner + "." + m.name + m.desc);
            if ((m.access & Opcodes.ACC_STATIC) != 0) throw new IllegalStateException("onReturn needs an instance method: " + owner + "." + m.name);
            int n = 0;
            for (AbstractInsnNode i : m.instructions.toArray()) {
                if (i.getOpcode() != op) continue;
                InsnList l = new InsnList();
                l.add(new VarInsnNode(Opcodes.ALOAD, 0));
                l.add(new InsnNode(Opcodes.SWAP));
                l.add(new MethodInsnNode(Opcodes.INVOKESTATIC, hookOwner, hookName, "(L" + owner + ";" + ret.getDescriptor() + ")" + ret.getDescriptor(), false));
                m.instructions.insertBefore(i, l);
                n++;
            }
            return n;
        }

        private int constant(MethodNode m, float value, int expected, String hookOwner, String hookName) {
            int n = 0;
            for (AbstractInsnNode i : m.instructions.toArray()) {
                if (!(i instanceof org.objectweb.asm.tree.LdcInsnNode ld) || !(ld.cst instanceof Float f) || f != value) continue;
                InsnList l = new InsnList();
                l.add(new VarInsnNode(Opcodes.ALOAD, 0));
                l.add(new MethodInsnNode(Opcodes.INVOKESTATIC, hookOwner, hookName, "(L" + owner + ";)F", false));
                m.instructions.insertBefore(i, l);
                m.instructions.remove(i);
                n++;
            }
            if (n != expected) throw new IllegalStateException("expected " + expected + " use(s) of the constant " + value + " in " + owner + "." + m.name + ", found " + n);
            return n;
        }

        private int afterPut(MethodNode m, String fOwner, String fName, String hookOwner, String hookName) {
            int n = 0;
            for (AbstractInsnNode i : m.instructions.toArray()) {
                if (!(i instanceof org.objectweb.asm.tree.FieldInsnNode f) || f.getOpcode() != Opcodes.PUTFIELD || !f.owner.equals(fOwner) || !f.name.equals(fName)) continue;
                InsnList l = new InsnList();
                l.add(new VarInsnNode(Opcodes.ALOAD, 0));
                l.add(new MethodInsnNode(Opcodes.INVOKESTATIC, hookOwner, hookName, "(L" + owner + ";)V", false));
                m.instructions.insert(i, l);
                n++;
            }
            return n;
        }

        private int startReturn(MethodNode m, String hookOwner, String hookName) {
            Type ret = Type.getReturnType(m.desc);
            if (ret.getSort() != Type.OBJECT && ret.getSort() != Type.ARRAY) throw new IllegalStateException("atStartReturnIfNotNull needs a reference return type: " + owner + "." + m.name);
            InsnList l = loadSelfAndArgs(m);
            l.add(new MethodInsnNode(Opcodes.INVOKESTATIC, hookOwner, hookName, hookDesc(m, ret.getDescriptor()), false));
            l.add(new InsnNode(Opcodes.DUP));
            LabelNode none = new LabelNode();
            l.add(new JumpInsnNode(Opcodes.IFNULL, none));
            l.add(new InsnNode(Opcodes.ARETURN));
            l.add(none);
            l.add(new InsnNode(Opcodes.POP));
            m.instructions.insert(l);
            return 1;
        }


        private int redirectNth(MethodNode m, String cOwner, String cName, String cDesc, boolean cStatic, int nth, String hookOwner, String hookName) {
            int seen = 0;
            for (AbstractInsnNode i : m.instructions.toArray()) {
                if (!(i instanceof MethodInsnNode c) || !c.owner.equals(cOwner) || !c.name.equals(cName) || !c.desc.equals(cDesc)) continue;
                if (seen++ != nth) continue;
                String d = cStatic ? cDesc : "(L" + cOwner + ";" + cDesc.substring(1);
                m.instructions.set(i, new MethodInsnNode(Opcodes.INVOKESTATIC, hookOwner, hookName, d, false));
                return 1;
            }
            return 0;
        }

        private static Integer intOf(AbstractInsnNode i) {
            int op = i.getOpcode();
            if (op >= Opcodes.ICONST_M1 && op <= Opcodes.ICONST_5) return op - Opcodes.ICONST_0;
            if (i instanceof org.objectweb.asm.tree.IntInsnNode n && (op == Opcodes.BIPUSH || op == Opcodes.SIPUSH)) return n.operand;
            if (i instanceof org.objectweb.asm.tree.LdcInsnNode l && l.cst instanceof Integer v) return v;
            return null;
        }

        private int intConstant(MethodNode m, int value, int expected, String hookOwner, String hookName) {
            int n = 0;
            for (AbstractInsnNode i : m.instructions.toArray()) {
                Integer v = intOf(i);
                if (v == null || v != value) continue;
                InsnList l = new InsnList();
                l.add(new VarInsnNode(Opcodes.ALOAD, 0));
                l.add(new MethodInsnNode(Opcodes.INVOKESTATIC, hookOwner, hookName, "(L" + owner + ";)I", false));
                m.instructions.insertBefore(i, l);
                m.instructions.remove(i);
                n++;
            }
            if (n != expected) throw new IllegalStateException("expected " + expected + " use(s) of the int constant " + value + " in " + owner + "." + m.name + ", found " + n);
            return n;
        }

        private int staticRead(MethodNode m, String fOwner, String fName, String hookOwner, String hookName) {
            int n = 0;
            for (AbstractInsnNode i : m.instructions.toArray()) {
                if (i instanceof org.objectweb.asm.tree.FieldInsnNode f && f.getOpcode() == Opcodes.GETSTATIC && f.owner.equals(fOwner) && f.name.equals(fName)) {
                    m.instructions.set(i, new MethodInsnNode(Opcodes.INVOKESTATIC, hookOwner, hookName, "()" + f.desc, false));
                    n++;
                }
            }
            return n;
        }

        private int staticWrite(MethodNode m, String fOwner, String fName, String hookOwner, String hookName) {
            int n = 0;
            for (AbstractInsnNode i : m.instructions.toArray()) {
                if (i instanceof org.objectweb.asm.tree.FieldInsnNode f && f.getOpcode() == Opcodes.PUTSTATIC && f.owner.equals(fOwner) && f.name.equals(fName)) {
                    m.instructions.set(i, new MethodInsnNode(Opcodes.INVOKESTATIC, hookOwner, hookName, "(" + f.desc + ")V", false));
                    n++;
                }
            }
            return n;
        }


        private int swapNew(MethodNode m, String oldClass, String newClass, int expected) {
            int n = 0;
            for (AbstractInsnNode i : m.instructions.toArray()) {
                if (i instanceof org.objectweb.asm.tree.TypeInsnNode t && t.getOpcode() == Opcodes.NEW && t.desc.equals(oldClass)) {
                    t.desc = newClass;
                    n++;
                } else if (i instanceof MethodInsnNode c && c.getOpcode() == Opcodes.INVOKESPECIAL && c.owner.equals(oldClass) && c.name.equals("<init>")) {
                    c.owner = newClass;
                }
            }
            if (n != expected) throw new IllegalStateException("expected " + expected + " 'new " + oldClass + "' in " + owner + "." + m.name + ", found " + n);
            return n;
        }

        private int replace(MethodNode m, String hookOwner, String hookName) {
            Type ret = Type.getReturnType(m.desc);
            InsnList l = loadSelfAndArgs(m);
            l.add(new MethodInsnNode(Opcodes.INVOKESTATIC, hookOwner, hookName, hookDesc(m, ret.getDescriptor()), false));
            l.add(new InsnNode(ret.getOpcode(Opcodes.IRETURN)));
            m.instructions.clear();
            m.tryCatchBlocks.clear();
            m.localVariables = null;
            m.instructions.add(l);
            return 1;
        }
    }
}
