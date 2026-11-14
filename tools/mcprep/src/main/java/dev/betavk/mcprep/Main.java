package dev.betavk.mcprep;

import java.nio.file.Path;

/** java -jar mcprep.jar <obfuscated-client.jar> <mappings.tiny> <readable-out.jar> */
public final class Main {
    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            System.err.println("usage: mcprep <obfuscated-client.jar> <mappings.tiny> <readable-out.jar>");
            System.exit(2);
        }
        TinyMappings maps = TinyMappings.read(Path.of(args[1]));
        System.out.println("[mcprep] mappings: " + maps.classes.size() + " classes, " + maps.fields.size() + " fields, " + maps.methods.size() + " methods");
        new Prep(maps).run(Path.of(args[0]), Path.of(args[2]));
        System.out.println("[mcprep] wrote " + args[2]);
    }
}
