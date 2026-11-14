// Fail fast if Gradle itself runs on an x86_64 JVM (Rosetta). On this machine
// `java_home` lists only x86_64 JDKs, so an arm64 JDK 21 must be selected
// explicitly: `source scripts/env.sh` before running gradle.
check(System.getProperty("os.arch") == "aarch64") {
    "Gradle is running on a '${System.getProperty("os.arch")}' JVM (${System.getProperty("java.home")}). " +
        "Use an arm64 JDK 21: `source scripts/env.sh`."
}
check(JavaVersion.current() >= JavaVersion.VERSION_21) {
    "Need Java 21+, running ${JavaVersion.current()}."
}
