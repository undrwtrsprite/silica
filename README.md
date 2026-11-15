<p align="center">
  <img src="docs/assets/banner.png" alt="Silica: Minecraft Beta 1.7.3, native on Apple Silicon" width="720">
</p>

<p align="center">
  <b>A launcher and an improved build of Minecraft Beta 1.7.3, made for the Mac.</b><br>
  Native on Apple Silicon. Vulkan renderer. No Rosetta.
</p>

<p align="center">
  <a href="https://silicalauncher.xyz"><b>silicalauncher.xyz</b></a>
  &nbsp;·&nbsp;
  <a href="docs/DEVELOPMENT.md">Developing</a>
  &nbsp;·&nbsp;
  <a href="CHANGELOG.md">Changelog</a>
  &nbsp;·&nbsp;
  <a href="https://silicalauncher.xyz/privacy">Privacy</a>
</p>

<p align="center">
  <img alt="Apple Silicon" src="https://img.shields.io/badge/Apple%20Silicon-arm64-0a0d11?style=flat-square&logo=apple&logoColor=white">
  <img alt="Vulkan on MoltenVK" src="https://img.shields.io/badge/Vulkan-MoltenVK-0a0d11?style=flat-square&logoColor=white">
  <img alt="macOS 14+" src="https://img.shields.io/badge/macOS-14%2B-0a0d11?style=flat-square&logo=apple&logoColor=white">
  <img alt="GPL-3.0 license" src="https://img.shields.io/badge/license-GPL--3.0-c3e29b?style=flat-square&labelColor=0a0d11">
  <img alt="Swift and Java 21" src="https://img.shields.io/badge/SwiftUI%20%2B%20Java%2021-c3e29b?style=flat-square&labelColor=0a0d11">
</p>

<br>

<p align="center">
  <img src="docs/assets/shot-play.webp" alt="Silica's Play page: an animated blocky landscape behind the Minecraft Beta 1.7.3 title" width="860">
</p>

## What it is

Silica runs Beta 1.7.3 natively on Apple Silicon: an arm64 Java 21 runtime, LWJGL 3 with GLFW and OpenAL, and a Vulkan 1.2 renderer on MoltenVK. Worlds stay plain Beta 1.7.3 worlds, so they work with the original game and with servers. A SwiftUI launcher handles setup, sign-in, worlds and settings.

> **This repository contains no Mojang code, jars or assets.** You need to own Minecraft: Java Edition. On first run Silica downloads the game from Mojang's own servers (or uses a copy you already have), checks it against Mojang's checksum, and prepares it on your Mac. Silica's changes are applied to that copy at load time.

## How it's made

Silica is a passion project and it is heavily AI assisted. I decide what gets built and I test it on my own Mac. An AI (Claude) writes a lot of the code, and you can see that in the commit history. It is GPL-3.0, and the benchmark numbers and scripts are in the repo, so you can check that it holds up instead of just taking my word for it.

## Performance

Same world, camera path and seed on both renderers. Average FPS on an Apple M5, 1080p fullscreen, seed 1337.

| Render distance | OpenGL | Vulkan |
|---|---:|---:|
| Far · 256 blocks | 83 | **289** |
| Normal · 128 blocks | 160 | **374** |

On the far setting that is about 3.5× the frame rate, with a 99th percentile frame of 7.4 ms. The title screen is up in about 1.5 s, against about 3.2 s for the original game under Rosetta.

## The launcher

<table>
  <tr>
    <td width="50%"><img src="docs/assets/shot-worlds.webp" alt="Worlds page with import controls"></td>
    <td width="50%"><img src="docs/assets/shot-settings.webp" alt="Settings page"></td>
  </tr>
  <tr>
    <td align="center"><sub>Worlds: resume, back up, import and export</sub></td>
    <td align="center"><sub>Settings</sub></td>
  </tr>
  <tr>
    <td colspan="2" align="center"><img src="docs/assets/shot-jars.webp" alt="Jars page: the patched game and a modded jar that passed Silica's checks" width="50%"><br><sub>Jars: basic, experimental mod support. Add a modded jar and Silica checks that it fits before you play</sub></td>
  </tr>
</table>

- **Microsoft sign-in** with several accounts, kept in the macOS Keychain. Silica checks the purchase before it starts the game.
- **Skins and capes** for you and for other players, and **online-mode servers** through Mojang's session service.
- **Guided setup**, performance presets, a built-in benchmark, and plain-language crash hints.

## Quality of life in the game

- **Inventory:** drag to spread stacks, number keys to swap, shift-click to craft in bulk, sort buttons.
- **Worlds:** resume your last world, autosave interval, backups, a sortable world list.
- **Controls and sound:** toggle sneak, a zoom key, volume sliders, directional sound captions.
- **Commands and debug:** optional cheats with Tab completion and a much fuller F3 screen.
- **Faster chunk generation:** terrain is generated ahead of you on background threads, and it is identical to the original's.

Gameplay stays vanilla.

## Layout

| Folder | What |
|---|---|
| [`launcher/`](launcher) | The SwiftUI launcher (SwiftPM, tests included) |
| [`game/`](game) | The renderer (OpenGL and Vulkan backends), the LWJGL 2 compatibility layer, networking |
| [`platform/`](platform) | A GLFW / LWJGL 3 layer with the LWJGL 2 API the game expects |
| [`hooks/`](hooks) | Patch definitions and Silica's own implementations of its changes |
| [`agent/`](agent) | The Java agent that applies those patches as classes load |
| [`tools/mcprep/`](tools/mcprep) | Prepares your own jar on your Mac (names, access, LWJGL redirect) |
| [`website/`](website) | The website, static HTML and CSS |
| [`scripts/`](scripts) | Build, benchmark and image comparison helpers |

## Build the launcher

You need macOS on Apple Silicon with the Swift toolchain and JDK 21.

```bash
cd launcher
./build_app.sh        # builds Silica.app
swift test            # runs the launcher's tests
./make_dmg.sh        # packs Silica-<version>.dmg, a drag-to-Applications installer
```

The game build, the preparation steps and the benchmark scripts are described in [`docs/DEVELOPMENT.md`](docs/DEVELOPMENT.md).

## Status

Sign-in works against Microsoft's live services. [Silica 0.1.6 is available on the website](https://silicalauncher.xyz) and [GitHub Releases](https://github.com/undrwtrsprite/silica/releases/latest). Basic, experimental mod support is in: add a modded Beta 1.7.3 jar and Silica checks that its improvements fit before you play. Support for other Beta and Alpha versions is planned; the launcher is meant to run them all. The app is ad-hoc signed, not notarized, so macOS will warn on first open.

## License

Silica is free software under the [GNU General Public License v3.0](LICENSE). You can use, study, change and share it. If you share it, or something built from it, you must keep the copyright and license notices, say that it comes from Silica and what you changed, and release your version under the same license with its source.

This covers Silica's own code only. It does not cover Minecraft, which belongs to Mojang and Microsoft and is never included here, or the Minecraft name and artwork.

Copyright (C) 2026 Silica contributors. Libraries Silica uses keep their own licenses, listed in [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md).

## Not affiliated

Silica is an unofficial project. It is not affiliated with Mojang or Microsoft. Minecraft is a trademark of Mojang Synergies AB.
