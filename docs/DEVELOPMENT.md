# Developing Silica

**Golden Age Minecraft** (Alpha 1.0 through Beta 1.7.3, plus experimental Infdev `2010-06-30` and Beta 1.8/1.8.1) running **natively on Apple Silicon** (arm64 JDK 21, LWJGL 3, GLFW, OpenAL Soft) with a **Vulkan 1.2 renderer on MoltenVK** and a SwiftUI launcher. Gameplay and the world format stay compatible with original clients and servers.

**This repository contains no Mojang code, jars or assets.** You bring your own copy of Minecraft Beta 1.7.3; a setup step turns your jar into a
readable, patchable form on your machine (git-ignored `build/mc/`, `mojang/`), and our code (renderer, platform layer, hooks) is applied to it at load time.

## Silica launcher

Build the macOS app with `cd launcher && ./build_app.sh`. First-run setup prepares the game locally. On the Play page, sign in with the Microsoft account that owns Minecraft: Java Edition. Silica checks the purchase and Java profile before starting the game or a benchmark. Sign-in lasts for the current app session, up to the Minecraft Services token expiry.

The app registration needs the iOS / macOS redirect URI `msauth.dev.betavk.launcher://auth` and [Minecraft Services approval](https://aka.ms/mce-reviewappid) for Silica's client ID. `swift test --package-path launcher` exercises the ownership and launch checks using simulated service replies.

## Local development setup (your own Beta 1.7.3)

1. Needs macOS on Apple Silicon, an arm64 JDK 21 (`scripts/env.sh` selects the Homebrew one: `brew install openjdk@21`), and `gradle`.
2. Prepare your jar (once, or after changing `tools/mcprep`):
   ```bash
   scripts/mcprep.sh /path/to/your/b1.7.3-client.jar
   ```
   This fetches RetroMCP-Java (open source) and its b1.7.3 mapping table, remaps your jar to readable names, widens access and redirects its LWJGL 2
   calls to our platform layer, and extracts the jar's resources. Everything goes to git-ignored folders.
3. Sounds (optional): point `BETAVK_ASSETS` at a Mojang asset store that has `indexes/b1.7.json` and `objects/` (the launcher's `~/Library/Application Support/BetaVulkan/assets` is used if present).
4. Play: `scripts/run-patched.sh` (extra JVM flags in `JVM_ARGS`, e.g. `JVM_ARGS="-Dbetavk.fullscreen=true"`). The game data goes to `mojang/run-home` (`BETAVK_HOME` to change).

How it works: `tools/mcprep` (ASM) prepares the jar; `agent/` is a Java agent that applies the patch definitions in `hooks/` (`dev.betavk.hooks.PatchSet`) to the game's
classes as they load; the hook classes are our own implementations (rendering workers, chunk prefetch and saves, inventory, options, HUD). `agent:selfTest` checks the patch kinds,
`-Dbetavk.test=hud|video|inv|world|fly|lod|worlds` (see `hooks/.../TestHarness`) check the patched game. Multiplayer online-mode needs `-Dbetavk.joinUrl`. Your own skin and cape come from your signed-in profile; other players are looked up by name on Mojang's servers in the background (`dev.betavk.net.Skins`; `-Dbetavk.skins=off` disables it, `-Dbetavk.skinUrl`/`-Dbetavk.cloakUrl` point at your own host instead; `-Dbetavk.test=skins` checks it).

## Quality of life (patched build)

Inventory: drag to spread a stack, number keys over a slot swap with the hotbar, Q and Ctrl+Q drop one or all, shift + double-click moves every stack of a kind, scroll over a slot to move one item across, Sort buttons, shift-click crafting results crafts as many as possible, durability numbers in tooltips. Visuals & world: Better Grass (`Off`, `Fast`, `Fancy` in Video Settings), `2–32` chunk render distance slider, true-textured 5-tier distant LODs with `.lod` disk cache and border trees. Menus: FOV and sensitivity on the main Options screen, Music & Sounds (master, music, effects, ambient), GUI scale `1x–4x`, World options (autosave interval, backups), Resume on the title screen, a sortable world list with seeds. In game: a fuller F3 screen, middle-click pick block, zoom, FPS counter, optional `/fly` command with speed control. Gameplay is vanilla (no sprinting or hunger).

## Layout

| Path | What |
|---|---|
| `platform/` | GLFW/LWJGL 3 shim with the LWJGL 2 API the game expects (`Display`, `Keyboard`, `Mouse`, ...); LWJGL 2 key codes kept |
| `game/` | Our renderer (`dev.betavk.render`: Renderer interface, GL + Vulkan backends), the LWJGL 2 compatibility layer, endpoints. No Mojang code |
| `hooks/` | Patch definitions, hook classes and the boot class, compiled against your prepared jar; includes the OpenAL sound system |
| `agent/`, `tools/mcprep/` | The patch agent and the jar preparation tool |
| `launcher/` | SwiftUI launcher (Swift 5 mode, macOS 14+, builds with Command Line Tools) |
| `spike/` | The Phase 1 Vulkan/MoltenVK spike |
| `scripts/` | `env.sh` (arm64 JDK), `bench.sh`, `bench_table.py`, `imgdiff.py`, `jfr_top.py`, `GenGfx.java` (generates the Renderer layer) |
| `CHANGELOG.md` | Every migrated or rewritten class, with measurements |

## Build and run

```bash
source scripts/env.sh                     # selects the Homebrew arm64 JDK 21 (java_home here lists only x86_64 JDKs)
scripts/run-patched.sh                    # local development launch (see setup above)
gradle :hooks:stageGame                   # build/stage for the launcher: our jars, the patch agent and the jar preparation tool (no Mojang code)
cd launcher && ./build_app.sh             # Silica.app; first run installs the JDK, your client jar, prepares it (names, access) and fetches sounds
scripts/bench.sh my-label                 # fixed-seed benchmark at 3 render distances (BENCH_BACKEND=vulkan, BENCH_MONITOR=0, ...)
```

Switches: `-Dbetavk.backend=gl|vulkan`, `-Dbetavk.meshworkers=N` (default 1), `-Dbetavk.genworkers=N` (default 2), `-Dbetavk.vsync`, `-Dbetavk.uncapped`, `-Dbetavk.bettergrass=0|1|2`, `-Dbetavk.gamedir`, `-Dbetavk.assets`, `-Dbetavk.vkstats`. CLI: `dev.betavk.Main --help`.

## What was verified (details and numbers in CHANGELOG.md)

- Native arm64 end to end (JVM `os.arch=aarch64`, no Rosetta anywhere); original menu and world render identically on OpenGL and Vulkan (per-band colour within 0.1/255).
- Vulkan backend: all 1,161 GL calls go through a generated `Renderer` interface; occlusion queries, mip-mapped textures, pipeline cache, resize/fullscreen.
- Chunk meshing and terrain generation on worker threads are byte-identical to the original code paths (differential tests, >10k sections, ~3.7k chunks).
- Worlds: Mojang's own b1.7.3 server loads and re-saves worlds my client wrote with 0 differences, and the reverse; generation matches the server's terrain shape to 0.005%.
