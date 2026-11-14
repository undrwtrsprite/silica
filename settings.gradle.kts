rootProject.name = "beta-vulkan"

include(":spike")
include(":platform")
include(":mcprep")
include(":agent")
project(":mcprep").projectDir = file("tools/mcprep")

include(":game")

// The original in-tree build (Mojang-derived sources, local-only): only where mc-src exists; kept as the reference for the 1:1 comparison.
if (file("mc-src/src/main/java").isDirectory) {
    include(":legacy")
}

// Our hooks compile against the user's readable jar; without it (scripts/mcprep.sh not run yet) the module is skipped.
if (file("build/mc/minecraft-readable.jar").isFile) {
    include(":hooks")
}
