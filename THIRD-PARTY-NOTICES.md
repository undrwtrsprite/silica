# Third-party notices

Silica's own code is under the [GPL-3.0](LICENSE). It uses the software below. Each project keeps its own license and copyright, and the license texts are in the projects' own repositories and in the packages themselves. This list was put together from the build files and setup code, so check each project's current license before you redistribute a build.

## Libraries Silica's game code is built with

| Project | Used for | License |
|---|---|---|
| [LWJGL 3](https://github.com/LWJGL/lwjgl3) 3.3.6 | Java bindings for GLFW, OpenGL, OpenAL, stb, Vulkan and shaderc | BSD-3-Clause |
| [GLFW](https://github.com/glfw/glfw) (in the LWJGL natives) | Windowing and input | zlib |
| [OpenAL Soft](https://github.com/kcat/openal-soft) (in the LWJGL natives) | Audio | LGPL-2.0-or-later |
| [stb](https://github.com/nothings/stb) (in the LWJGL natives) | Image decoding | MIT or public domain |
| [MoltenVK](https://github.com/KhronosGroup/MoltenVK) (in the LWJGL natives) | Vulkan on top of Metal | Apache-2.0 |
| [shaderc](https://github.com/google/shaderc) (in the LWJGL natives) | Compiling shaders | Apache-2.0 |
| [ASM](https://asm.ow2.io/) 9.9 | Bytecode patching and jar preparation | BSD-3-Clause |

These come from Maven Central when you build. None of them is stored in this repository.

## Bundled inside the Silica app (not in this repository)

The built `Silica.app` carries these jars for the game's sound. They are the unmodified libraries from Maven Central, and their license texts are in [`licenses/`](licenses) and inside the app.

| Project | Used for | License |
|---|---|---|
| [paulscode SoundSystem](http://www.paulscode.com) with LibraryJavaSound, LibraryLWJGLOpenAL and CodecWav, by Paul Lamb | The sound API the game calls | [SoundSystem License](licenses/SoundSystem-License.txt): free to use and redistribute unmodified, no warranty. Credit is optional, and if given it must mention http://www.paulscode.com |
| CodecJOrbis, which contains [JOrbis](http://www.jcraft.com/jorbis/) and JOgg by JCraft | Reading the game's Ogg Vorbis sounds | LGPL ([LGPL-2.1 text](licenses/LGPL-2.1.txt)). The source is at jcraft.com and on Maven Central; the jar is shipped unmodified and can be replaced |

## Downloaded when you run setup

Silica downloads these to your Mac on first run. They are not part of this repository or of the app.

| Download | From | License |
|---|---|---|
| Eclipse Temurin JDK 21 (arm64) | Adoptium | GPL-2.0 with Classpath Exception |
| [RetroMCP-Java](https://github.com/MCPHackers/RetroMCP-Java) and its Beta 1.7.3 name table | GitHub | MIT |
| Minecraft Beta 1.7.3 client and sounds | Mojang's own servers | Mojang's license, see below |

## Parts of the game Silica works with but does not contain

- **Minecraft** belongs to Mojang and Microsoft. It is never stored in this repository or in the app. Your use of it is under the Minecraft End User License Agreement.
- Silica talks to Microsoft, Xbox Live and Mojang web services for sign-in, skins and joining servers. Their terms apply to that use.

## Trademarks

Minecraft is a trademark of Mojang Synergies AB. Silica is an unofficial project and is not affiliated with or endorsed by Mojang or Microsoft.
