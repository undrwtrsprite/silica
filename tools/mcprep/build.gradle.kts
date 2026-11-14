// Turns the user's own obfuscated Beta 1.7.3 jar into a readable, access-widened jar that our code compiles against.
// Contains no Mojang code; reads the jar and a mapping file the setup step downloads.
plugins {
    application
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.ow2.asm:asm:9.9")
    implementation("org.ow2.asm:asm-commons:9.9")
    implementation("org.ow2.asm:asm-tree:9.9")
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
    options.encoding = "UTF-8"
}

application {
    mainClass.set("dev.betavk.mcprep.Main")
}
