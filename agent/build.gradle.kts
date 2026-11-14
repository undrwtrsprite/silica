// The patch agent: applies our bytecode patches to the user's own (remapped) Minecraft classes as they load. Contains no Mojang code.
plugins {
    `java-library`
}

repositories {
    mavenCentral()
}

dependencies {
    api("org.ow2.asm:asm:9.9")
    api("org.ow2.asm:asm-commons:9.9")
    api("org.ow2.asm:asm-tree:9.9")
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
    options.encoding = "UTF-8"
}

// -javaagent needs one jar with its dependencies inside it.
tasks.register<Jar>("agentJar") {
    archiveBaseName.set("patch-agent")
    manifest {
        attributes(
            "Premain-Class" to "dev.betavk.agent.PatchAgent",
            "Can-Retransform-Classes" to "false",
        )
    }
    from(sourceSets.main.get().output)
    from({ configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) } }) {
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "module-info.class")
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

// ./gradlew :agent:selfTest : applies each patch kind to a toy class and checks behaviour with bytecode verification on.
tasks.register<JavaExec>("selfTest") {
    group = "verification"
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("dev.betavk.agent.PatchSelfTest")
    jvmArgs("-Xverify:all")
}
