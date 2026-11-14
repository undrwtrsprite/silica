package dev.betavk.render.vulkan;

import org.lwjgl.util.shaderc.Shaderc;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;

/** Build-time GLSL -> SPIR-V (shaderc). usage: ShaderCompiler <srcDir> <outDir>; writes <name>.spv for each .vert/.frag. */
public final class ShaderCompiler {
    public static void main(String[] args) throws IOException {
        Path src = Paths.get(args[0]), out = Paths.get(args[1]);
        Files.createDirectories(out);
        long compiler = Shaderc.shaderc_compiler_initialize();
        long opts = Shaderc.shaderc_compile_options_initialize();
        Shaderc.shaderc_compile_options_set_target_env(opts, Shaderc.shaderc_target_env_vulkan, Shaderc.shaderc_env_version_vulkan_1_2);
        Shaderc.shaderc_compile_options_set_optimization_level(opts, Shaderc.shaderc_optimization_level_performance);
        try (Stream<Path> files = Files.list(src)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".vert") || p.toString().endsWith(".frag")).toList()) {
                int kind = f.toString().endsWith(".vert") ? Shaderc.shaderc_glsl_vertex_shader : Shaderc.shaderc_glsl_fragment_shader;
                long res = Shaderc.shaderc_compile_into_spv(compiler, Files.readString(f), kind, f.getFileName().toString(), "main", opts);
                if (Shaderc.shaderc_result_get_compilation_status(res) != Shaderc.shaderc_compilation_status_success) {
                    System.err.println("shader compile failed: " + f + "\n" + Shaderc.shaderc_result_get_error_message(res));
                    System.exit(1);
                }
                ByteBuffer spv = Shaderc.shaderc_result_get_bytes(res);
                byte[] bytes = new byte[spv.remaining()];
                spv.get(bytes);
                Files.write(out.resolve(f.getFileName() + ".spv"), bytes);
                System.out.println("compiled " + f.getFileName() + " -> " + bytes.length + " bytes SPIR-V");
                Shaderc.shaderc_result_release(res);
            }
        }
        Shaderc.shaderc_compile_options_release(opts);
        Shaderc.shaderc_compiler_release(compiler);
    }
}
