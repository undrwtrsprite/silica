import Foundation

struct CrashHint: Identifiable, Equatable {
    let id = UUID()
    let title: String
    let advice: String
}

/// Turns raw JVM/Vulkan/GLFW output into something a person can act on.
enum CrashHints {
    private static let rules: [(pattern: String, title: String, advice: String)] = [
        ("GLFW may only be used on the main thread", "Wrong thread for the window",
         "The game must start with -XstartOnFirstThread. The launcher always passes it; if you launched manually, add that flag."),
        ("UnsupportedClassVersionError", "Java is too old",
         "The game needs Java 21. Use Setup to install the arm64 JDK 21 instead of an older system Java."),
        ("wrong ELF class|mach-o file, but is an incompatible architecture|have 'x86_64', need 'arm64'", "Wrong CPU architecture",
         "An x86_64 Java was used. Open Setup and install the native arm64 JDK (the log should say os.arch=aarch64)."),
        ("OutOfMemoryError", "Out of memory", "Raise the memory limit in Settings (4096 MB is plenty), or lower the render distance in the game's options."),
        ("libMoltenVK|VK_ERROR_INCOMPATIBLE_DRIVER|vkCreateInstance failed", "Vulkan could not start",
         "MoltenVK failed to initialise. Switch the backend to OpenGL in Settings to keep playing, and check that macOS is up to date."),
        ("VK_ERROR_DEVICE_LOST", "GPU device lost", "The GPU reset. Try the OpenGL backend, and report it with the log attached if it repeats."),
        ("missing shader resource", "Shaders missing", "The staged game build has no compiled shaders. Re-run `gradle :game:stageGame`."),
        ("NoClassDefFoundError|ClassNotFoundException", "Game files incomplete", "The staged game folder is missing a jar. Re-run `gradle :game:stageGame` and check the folder in Settings."),
        ("Failed to start game", "The game failed to start", "Look for the first exception above this line in the log."),
        ("hs_err_pid", "The Java VM crashed", "A native crash. The hs_err_pid file next to the game folder has details; try the OpenGL backend."),
        ("asset missing or wrong size", "Some sounds are missing", "Open Setup and run it again to re-download and verify the sound files."),
        ("SpotlessException|AL lib: .*Failed", "Audio device problem", "OpenAL could not open an output device. The game still runs, silently."),
    ]

    static func analyse(lines: [String], exitCode: Int32?) -> [CrashHint] {
        let text = lines.joined(separator: "\n")
        var hints: [CrashHint] = []
        for r in rules where text.range(of: r.pattern, options: .regularExpression) != nil {
            hints.append(CrashHint(title: r.title, advice: r.advice))
        }
        if let code = exitCode, code != 0, hints.isEmpty {
            hints.append(CrashHint(title: "The game exited with status \(code)", advice: "No known cause matched. The last lines of the log usually show it."))
        }
        return hints
    }
}
