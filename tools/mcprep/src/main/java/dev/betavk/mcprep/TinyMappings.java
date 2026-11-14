package dev.betavk.mcprep;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a Tiny v2 mapping file (namespaces: named, client, server) into obfuscated -> readable lookups for the client. Descriptors in the file use
 * the readable names, so member keys are converted to obfuscated descriptors with the inverse class map.
 */
public final class TinyMappings {
    /** obfuscated class name -> readable class name (internal form, slashes). */
    public final Map<String, String> classes = new HashMap<>();
    /** "ownerObf.nameObf descObf" -> readable member name. */
    public final Map<String, String> fields = new HashMap<>(), methods = new HashMap<>();

    private static final Pattern DESC_CLASS = Pattern.compile("L([^;]+);");

    public static TinyMappings read(Path file) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        if (lines.isEmpty() || !lines.get(0).startsWith("tiny\t2")) throw new IOException("not a tiny v2 file: " + file);
        String[] header = lines.get(0).split("\t");
        int named = -1, client = -1;
        for (int i = 3; i < header.length; i++) {
            if (header[i].equals("named")) named = i - 3;
            if (header[i].equals("client")) client = i - 3;
        }
        if (named < 0 || client < 0) throw new IOException("mapping needs 'named' and 'client' namespaces: " + lines.get(0));
        TinyMappings m = new TinyMappings();
        // pass 1: classes
        Map<String, String> readableToObf = new HashMap<>();
        for (String l : lines) {
            if (!l.startsWith("c\t")) continue;
            String[] p = l.split("\t", -1);
            String readable = p[1 + named], obf = p[1 + client];
            if (obf.isEmpty()) continue;
            m.classes.put(obf, readable);
            readableToObf.put(readable, obf);
        }
        // pass 2: members under their class
        String ownerObf = null;
        for (String l : lines) {
            if (l.startsWith("c\t")) {
                String[] p = l.split("\t", -1);
                ownerObf = p[1 + client].isEmpty() ? null : p[1 + client];
            } else if (ownerObf != null && (l.startsWith("\tf\t") || l.startsWith("\tm\t"))) {
                String[] p = l.split("\t", -1);   // "", f|m, desc, named, client, server
                String desc = p[2];
                String readable = p[2 + 1 + named], obf = p[2 + 1 + client];
                if (obf.isEmpty()) continue;
                String key = ownerObf + "." + obf + " " + toObfDesc(desc, readableToObf);
                (p[1].equals("f") ? m.fields : m.methods).put(key, readable);
            }
        }
        return m;
    }

    static String toObfDesc(String desc, Map<String, String> readableToObf) {
        Matcher mt = DESC_CLASS.matcher(desc);
        StringBuilder sb = new StringBuilder();
        while (mt.find()) {
            String o = readableToObf.get(mt.group(1));
            mt.appendReplacement(sb, Matcher.quoteReplacement("L" + (o != null ? o : mt.group(1)) + ";"));
        }
        mt.appendTail(sb);
        return sb.toString();
    }
}
