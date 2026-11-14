// Our own platform-independent game code: the renderer (Vulkan and GL backends), the LWJGL 2 compatibility layer, endpoints.
// It contains no Mojang code and does not need one; the game itself comes from the user's jar (scripts/mcprep.sh), see :hooks.
plugins {
    `java-library`
}

repositories {
    mavenCentral()
}

// paulscode SoundSystem jars fetched by RetroMCP (git-ignored under mojang/); LWJGL 2 is gone, replaced by :platform (LWJGL 3).
val retroLibs = rootProject.file("mojang/retromcp/libraries")
val legacyLibs = fileTree(retroLibs) {
    include(
        "com/paulscode/**/*.jar",
    )
    exclude("**/*-sources.jar")
}

dependencies {
    api(project(":platform"))
    implementation(legacyLibs)
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
    options.encoding = "UTF-8"
    options.isDeprecation = false          // 2011 code: thousands of deprecation notes would bury real errors
    options.compilerArgs.addAll(listOf("-Xlint:-options", "-Xmaxerrs", "500", "-Xmaxwarns", "50"))
}

// GLSL -> SPIR-V at build time (shaderc). Output goes to the classpath under /shaders.
val compileShaders = tasks.register<JavaExec>("compileShaders") {
    val src = file("src/main/shaders")
    val out = layout.buildDirectory.dir("generated/shaders/shaders")
    inputs.dir(src)
    outputs.dir(out)
    dependsOn(tasks.named("compileJava"))
    mainClass.set("dev.betavk.render.vulkan.ShaderCompiler")
    classpath = sourceSets.main.get().output.classesDirs + configurations.runtimeClasspath.get()
    args(src.absolutePath, out.get().asFile.absolutePath)
}
sourceSets.main { resources.srcDir(layout.buildDirectory.dir("generated/shaders")) }
tasks.named("processResources") { dependsOn(compileShaders) }

// Runtime jars (our game code + LWJGL 3 + sound libraries) in build/stage/lib for scripts/run-patched.sh and the launcher. No Mojang code in them.
tasks.register("stageGame") {
    group = "distribution"
    dependsOn(tasks.named("jar"))
    val stage = layout.buildDirectory.dir("stage")
    doLast {
        val lib = stage.get().dir("lib").asFile
        lib.deleteRecursively()
        lib.mkdirs()
        val jars = (listOf(tasks.named<Jar>("jar").get().archiveFile.get().asFile) +
            configurations.runtimeClasspath.get().files.filter { it.name.endsWith(".jar") }).distinctBy { it.name }
        jars.forEach { it.copyTo(File(lib, it.name), overwrite = true) }
        println("staged ${jars.size} jars in ${stage.get().asFile}")
    }
}
