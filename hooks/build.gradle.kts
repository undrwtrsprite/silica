// Our hook classes and the patch definitions that connect them to the user's Minecraft classes, plus the bootstrap that starts the game.
// Compiles against the user's readable jar (build/mc/, made by scripts/mcprep.sh); it is not part of the build when that jar is absent.
plugins {
    `java-library`
}

repositories {
    mavenCentral()
}

val readableJar = rootProject.file("build/mc/minecraft-readable.jar")

dependencies {
    compileOnly(files(readableJar))
    compileOnly(project(":agent"))
    compileOnly(project(":platform"))
    compileOnly(fileTree(rootProject.file("mojang/retromcp/libraries")) { include("com/paulscode/**/*.jar"); exclude("**/*-sources.jar") })
    // render backends and compat layer still live in :game next to the old in-tree sources; only dev/** is used here
    compileOnly(files(rootProject.file("game/build/classes/java/main")))
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:-options"))
    dependsOn(":game:classes")
}

// Self-contained runtime folder for the launcher (and anyone who does not want Gradle at run time): build/stage/{lib/*.jar, agent/patch-agent.jar,
// prep/*.jar, launch.json}. No Mojang code in it: the game's own classes come from the user's prepared jar, which the launcher builds with prep/.
tasks.register("stageGame") {
    group = "distribution"
    dependsOn(":game:jar", ":agent:agentJar", ":mcprep:installDist", tasks.named("jar"))
    val stage = layout.buildDirectory.dir("stage")
    doLast {
        val st = stage.get().asFile
        st.deleteRecursively()
        val lib = File(st, "lib").apply { mkdirs() }
        val game = project(":game")
        val jars = (listOf(tasks.named<Jar>("jar").get().archiveFile.get().asFile, game.tasks.named<Jar>("jar").get().archiveFile.get().asFile) +
            game.configurations["runtimeClasspath"].files.filter { it.name.endsWith(".jar") }).distinctBy { it.name }
        jars.forEach { it.copyTo(File(lib, it.name), overwrite = true) }
        val agent = project(":agent").tasks.named<Jar>("agentJar").get().archiveFile.get().asFile
        File(st, "agent").mkdirs(); agent.copyTo(File(st, "agent/patch-agent.jar"))
        project(":mcprep").layout.buildDirectory.dir("install/mcprep/lib").get().asFile.copyRecursively(File(st, "prep"), overwrite = true)
        val cp = jars.joinToString(",") { "\"lib/${it.name}\"" }
        File(st, "launch.json").writeText(
            """{ "mainClass": "dev.betavk.boot.Boot", "classpath": [$cp], "jvmArgs": ["-XstartOnFirstThread", "-Djava.awt.headless=true", "-javaagent:${'$'}{stage}/agent/patch-agent.jar", "-Dbetavk.patchset=dev.betavk.hooks.PatchSet"], "prepMain": "dev.betavk.mcprep.Main" }""" + "\n")
        println("staged ${jars.size} jars in $st")
    }
}
