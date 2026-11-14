package dev.betavk.mcprep;

import dev.betavk.agent.PatchAgent;
import dev.betavk.agent.PatchRegistry;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.ClassWriter;

import java.io.File;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.zip.ZipFile;

/**
 * Checks a user-supplied jar (already remapped by Prep) against the clean one before it is offered for play:
 * <ol>
 *   <li>identity: is it really Beta 1.7.3 underneath?</li>
 *   <li>diff: which classes and methods did the jar change?</li>
 *   <li>dry run: do all of our patches still find their targets in it (what the agent would do at class load)?</li>
 *   <li>overlap: which methods did both the jar and a patch of ours change?</li>
 *   <li>links: does it call anything Silica's own layers do not provide (OptiFine-style direct GL use)?</li>
 * </ol>
 * java -cp ... dev.betavk.mcprep.JarCheck &lt;clean-readable.jar&gt; &lt;candidate-readable.jar&gt; &lt;patchset class&gt; [classpath entries...]  prints one JSON object.
 */
public final class JarCheck {
    private static final String[] KEY_CLASSES = {"net/minecraft/client/Minecraft", "net/minecraft/src/World", "net/minecraft/src/Block", "net/minecraft/src/EntityPlayer",
            "net/minecraft/src/RenderBlocks", "net/minecraft/src/Tessellator", "net/minecraft/src/EntityRenderer", "net/minecraft/src/RenderGlobal"};

    public static void main(String[] args) throws Exception {
        File clean = new File(args[0]), cand = new File(args[1]);
        Map<String, byte[]> a = classes(clean), b = classes(cand);
        List<String> reasons = new ArrayList<>(), notes = new ArrayList<>();
        String verdict = "green";

        // 1. identity
        int vanillaMissing = 0;
        List<String> removedClasses = new ArrayList<>(), keyMissing = new ArrayList<>();
        for (String c : a.keySet()) if (!b.containsKey(c)) { vanillaMissing++; if (removedClasses.size() < 20) removedClasses.add(c); }
        for (String k : KEY_CLASSES) if (!b.containsKey(k)) keyMissing.add(k);
        Map<String, Set<String>> methodsA = methods(a), methodsB = methods(b);
        int vanillaMethods = 0, lostMethods = 0;
        for (var e : methodsA.entrySet()) {
            Set<String> other = methodsB.get(e.getKey());
            for (String m : e.getValue()) { vanillaMethods++; if (other == null || !other.contains(m)) lostMethods++; }
        }
        boolean identityOk = keyMissing.isEmpty() && vanillaMissing <= a.size() * 0.02 && lostMethods <= vanillaMethods * 0.03;
        if (!identityOk) {
            verdict = "red";
            reasons.add("This does not look like a Beta 1.7.3 client: " + vanillaMissing + " of the " + a.size() + " original classes and " + lostMethods + " of their " + vanillaMethods
                    + " methods are missing. Jars built on another version (b1.7_01, b1.8, alpha) cannot work with Silica's patches.");
        }

        // 2. diff
        Map<String, Set<String>> modified = new TreeMap<>();
        List<String> addedClasses = new ArrayList<>();
        Set<String> changedClasses = new TreeSet<>();
        for (var e : b.entrySet()) {
            byte[] old = a.get(e.getKey());
            if (old == null) { addedClasses.add(e.getKey()); continue; }
            if (java.util.Arrays.equals(old, e.getValue())) continue;
            changedClasses.add(e.getKey());
            Map<String, String> ha = methodHashes(old), hb = methodHashes(e.getValue());
            Set<String> changed = new TreeSet<>();
            for (var m : hb.entrySet()) if (!m.getValue().equals(ha.get(m.getKey()))) changed.add(m.getKey());
            if (!changed.isEmpty()) modified.put(e.getKey(), changed);
        }
        int modifiedMethods = modified.values().stream().mapToInt(Set::size).sum();

        // 3. dry run of our patches on the candidate, and 4. overlap
        PatchRegistry registry = new PatchRegistry();
        ((dev.betavk.agent.PatchSet) Class.forName(args[2]).getDeclaredConstructor().newInstance()).register(registry);
        List<String> patchFailures = new ArrayList<>();
        int patchedClasses = 0;
        if (identityOk || true) {
            for (var e : b.entrySet()) {
                if (!registry.targets(e.getKey())) continue;
                try {
                    ClassNode cn = new ClassNode();
                    new ClassReader(e.getValue()).accept(cn, 0);
                    registry.applyTo(cn);
                    PatchAgent.write(cn);
                    patchedClasses++;
                } catch (Throwable t) {
                    patchFailures.add(e.getKey() + ": " + t.getMessage());
                }
            }
            // a patched class the jar does not contain at all
            for (String c : a.keySet()) if (registry.patchesClass(c) && !b.containsKey(c)) patchFailures.add(c + ": class is missing from the jar");
        }
        if (!patchFailures.isEmpty()) {
            verdict = "red";
            reasons.add(patchFailures.size() + " of Silica's patches cannot be applied to this jar (the jar rewrote code they depend on).");
        }
        List<String> overlap = new ArrayList<>();
        for (String t : registry.touchedMethods()) {
            int dot = t.indexOf('.');
            Set<String> ms = modified.get(t.substring(0, dot));
            if (ms != null && ms.contains(t.substring(dot + 1))) overlap.add(t);
        }
        if (!overlap.isEmpty() && verdict.equals("green")) {
            verdict = "yellow";
            reasons.add("The jar changes " + overlap.size() + " method(s) that Silica also patches. The patches still apply, but the two may interact.");
        }

        // 5. links outside the jar that Silica's platform layer does not provide (compare with the clean jar so nothing the base game needs is blamed on the mod)
        List<String> brokenLinks = new ArrayList<>();
        if (args.length > 3) {
            List<URL> urls = new ArrayList<>();
            for (int i = 3; i < args.length; i++) urls.add(new File(args[i]).toURI().toURL());
            Map<String, Set<String>>[] cleanLinks = LinkCheck.scan(clean, urls), candLinks = LinkCheck.scan(cand, urls);
            for (int k = 0; k < 2; k++) for (var e : candLinks[k].entrySet()) if (!cleanLinks[k].containsKey(e.getKey())) brokenLinks.add(e.getKey() + "   (used by " + e.getValue().iterator().next() + ")");
            if (!brokenLinks.isEmpty()) {
                verdict = "red";
                reasons.add("The jar uses " + brokenLinks.size() + " thing(s) that Silica's graphics and platform layer does not provide, such as direct OpenGL calls beyond what Beta used.");
            }
        }
        if (verdict.equals("green")) {
            notes.add(modifiedMethods == 0 && addedClasses.isEmpty() ? "This is the original game." : "Every patch applies, and nothing the jar changed overlaps with them.");
        }

        StringBuilder j = new StringBuilder("{");
        kv(j, "verdict", verdict); j.append(',');
        arr(j, "reasons", reasons); j.append(',');
        arr(j, "notes", notes); j.append(',');
        j.append("\"identityOk\":").append(identityOk).append(',');
        j.append("\"classesChanged\":").append(changedClasses.size()).append(',');
        j.append("\"classesAdded\":").append(addedClasses.size()).append(',');
        j.append("\"methodsChanged\":").append(modifiedMethods).append(',');
        j.append("\"patchedClasses\":").append(patchedClasses).append(',');
        arr(j, "keyMissing", keyMissing); j.append(',');
        arr(j, "removedClasses", removedClasses); j.append(',');
        arr(j, "patchFailures", patchFailures); j.append(',');
        arr(j, "overlap", overlap); j.append(',');
        arr(j, "brokenLinks", brokenLinks.size() > 40 ? brokenLinks.subList(0, 40) : brokenLinks); j.append(',');
        arr(j, "changedClassNames", new ArrayList<>(changedClasses).subList(0, Math.min(60, changedClasses.size()))); j.append(',');
        arr(j, "addedClassNames", addedClasses.subList(0, Math.min(60, addedClasses.size())));
        j.append('}');
        System.out.println(j);
    }

    private static Map<String, byte[]> classes(File jar) throws Exception {
        Map<String, byte[]> out = new LinkedHashMap<>();
        try (ZipFile z = new ZipFile(jar)) {
            for (var e : Collections.list(z.entries())) {
                if (!e.getName().endsWith(".class")) continue;
                try (InputStream in = z.getInputStream(e)) { out.put(e.getName().substring(0, e.getName().length() - 6), in.readAllBytes()); }
            }
        }
        return out;
    }

    private static Map<String, Set<String>> methods(Map<String, byte[]> classes) {
        Map<String, Set<String>> out = new HashMap<>();
        for (var e : classes.entrySet()) {
            ClassNode cn = new ClassNode();
            new ClassReader(e.getValue()).accept(cn, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            Set<String> s = new HashSet<>();
            for (MethodNode m : cn.methods) s.add(m.name + m.desc);
            out.put(e.getKey(), s);
        }
        return out;
    }

    /** name+desc -> a text rendering of the method's instructions (line numbers and frames left out) */
    private static Map<String, String> methodHashes(byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        Map<String, String> out = new HashMap<>();
        for (MethodNode m : cn.methods) {
            ClassNode one = new ClassNode();
            one.version = cn.version;
            one.name = "X";
            one.superName = "java/lang/Object";
            one.methods.add(m);
            ClassWriter cw = new ClassWriter(0);
            one.accept(cw);
            out.put(m.name + m.desc, new String(cw.toByteArray(), java.nio.charset.StandardCharsets.ISO_8859_1));
        }
        return out;
    }

    private static void kv(StringBuilder j, String k, String v) {
        j.append('"').append(k).append("\":").append(q(v));
    }

    private static void arr(StringBuilder j, String k, List<String> v) {
        j.append('"').append(k).append("\":[");
        for (int i = 0; i < v.size(); i++) { if (i > 0) j.append(','); j.append(q(v.get(i))); }
        j.append(']');
    }

    private static String q(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            if (c == '"' || c == '\\') b.append('\\').append(c);
            else if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
            else b.append(c);
        }
        return b.append('"').toString();
    }
}
