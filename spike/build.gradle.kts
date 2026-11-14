plugins {
    application
}

val lwjglVersion = "3.3.6"
val lwjglNatives = "natives-macos-arm64"

repositories {
    mavenCentral()
}

dependencies {
    implementation(platform("org.lwjgl:lwjgl-bom:$lwjglVersion"))

    implementation("org.lwjgl:lwjgl")
    implementation("org.lwjgl:lwjgl-glfw")
    implementation("org.lwjgl:lwjgl-vulkan")
    implementation("org.lwjgl:lwjgl-shaderc")

    // lwjgl-vulkan's macOS natives jar is what carries libMoltenVK.dylib.
    runtimeOnly("org.lwjgl:lwjgl::$lwjglNatives")
    runtimeOnly("org.lwjgl:lwjgl-glfw::$lwjglNatives")
    runtimeOnly("org.lwjgl:lwjgl-vulkan::$lwjglNatives")
    runtimeOnly("org.lwjgl:lwjgl-shaderc::$lwjglNatives")
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
    options.compilerArgs.add("-Xlint:all")
}

application {
    mainClass.set("dev.betavk.spike.VulkanSpike")
    // GLFW must own the Cocoa main thread on macOS.
    applicationDefaultJvmArgs = listOf("-XstartOnFirstThread")
}

// ./gradlew :spike:run -PspikeArgs="--frames 600 --uncapped"
tasks.named<JavaExec>("run") {
    (findProperty("spikeArgs") as String?)?.let { args(it.split(" ").filter(String::isNotBlank)) }
    standardInput = System.`in`
}
