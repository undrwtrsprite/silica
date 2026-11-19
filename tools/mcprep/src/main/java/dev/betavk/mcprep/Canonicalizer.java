package dev.betavk.mcprep;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Normalizes minor RetroMCP naming/SRG differences across Alpha and Beta jars into Silica's canonical symbols using structural bytecode matching.
 * This lets one PatchSet target every version from Alpha 1.0.x through Beta 1.7.3.
 */
final class Canonicalizer {
    private Canonicalizer() {
    }

    static void normalize(Map<String, ClassNode> classes) {
        Map<String, String> map = new HashMap<>();

        if (classes.containsKey("net/minecraft/src/Frustum") && !classes.containsKey("net/minecraft/src/Frustrum")) {
            map.put("net/minecraft/src/Frustum", "net/minecraft/src/Frustrum");
        }
        if (classes.containsKey("net/minecraft/src/MobSpawnerBase") && !classes.containsKey("net/minecraft/src/BiomeGenBase")) {
            map.put("net/minecraft/src/MobSpawnerBase", "net/minecraft/src/BiomeGenBase");
        }

        // Block
        ClassNode block = classes.get("net/minecraft/src/Block");
        if (block != null && !hasMethod(block, "getRenderBlockPass", "()I")) {
            ClassNode ice = classes.get("net/minecraft/src/BlockIce");
            String oldPass = null;
            for (MethodNode m : block.methods) {
                if (m.desc.equals("()I") && !m.name.equals("getRenderType") && !m.name.equals("tickRate")
                        && (ice == null || hasMethod(ice, m.name, "()I"))) {
                    oldPass = m.name;
                    break;
                }
            }
            if (oldPass != null) {
                for (ClassNode c : classes.values()) {
                    map.put(c.name + "." + oldPass + "()I", "getRenderBlockPass");
                }
            }
        }

        // Chunk
        ClassNode chunk = classes.get("net/minecraft/src/Chunk");
        if (chunk != null && !hasField(chunk, "isLit", "Z")) {
            for (FieldNode f : chunk.fields) {
                if ((f.access & Opcodes.ACC_STATIC) != 0 && f.desc.equals("Z")) {
                    map.put(chunk.name + "." + f.name, "isLit");
                    break;
                }
            }
        }

        // IBlockAccess, IWorldAccess & ChunkCache
        ClassNode iba = classes.get("net/minecraft/src/IBlockAccess");
        if (iba != null) {
            aliasMethod(iba, map, "getWorldChunkManager", "()Lnet/minecraft/src/WorldChunkManager;", m -> true);
            aliasMethod(iba, map, "getLightBrightness", "(III)F", m -> true);
            aliasMethod(iba, map, "isBlockOpaqueCube", "(III)Z", m -> true);
        }
        ClassNode iwa = classes.get("net/minecraft/src/IWorldAccess");
        if (iwa != null) {
            aliasMethod(iwa, map, "markBlockAndNeighborsNeedsUpdate", "(III)V", m -> true);
        }
        ClassNode cc = classes.get("net/minecraft/src/ChunkCache");
        if (cc != null) {
            aliasMethod(cc, map, "getWorldChunkManager", "()Lnet/minecraft/src/WorldChunkManager;", m -> true);
            aliasMethod(cc, map, "getLightBrightness", "(III)F", m -> true);
            aliasMethod(cc, map, "getBrightness", "(IIII)F", m -> true);
            aliasMethod(cc, map, "getLightValueExt", "(IIIZ)I", m -> true);
            aliasMethod(cc, map, "isBlockOpaqueCube", "(III)Z", m -> usesField(m, "opaqueCubeLookup") || !hasMethod(cc, "isBlockOpaqueCube", "(III)Z"));
            aliasField(cc, map, "chunkArray", "[[Lnet/minecraft/src/Chunk;");
            if (!hasField(cc, "chunkX", "I") || !hasField(cc, "chunkZ", "I")) {
                String[] cNames = {"chunkX", "chunkZ"};
                int idx = 0;
                for (FieldNode f : cc.fields) {
                    if ((f.access & Opcodes.ACC_STATIC) == 0 && f.desc.equals("I") && idx < 2) {
                        if (!f.name.equals(cNames[idx])) map.put(cc.name + "." + f.name, cNames[idx]);
                        idx++;
                    }
                }
            }
        }

        ClassNode wcm = classes.get("net/minecraft/src/WorldChunkManager");
        if (wcm != null) {
            aliasMethod(wcm, map, "getBiomeGenAt", "(II)Lnet/minecraft/src/BiomeGenBase;", m -> true);
            aliasMethod(wcm, map, "getBiomeGenAt", "(II)Lnet/minecraft/src/MobSpawnerBase;", m -> true);
            aliasMethod(wcm, map, "loadBlockGeneratorData", "([Lnet/minecraft/src/BiomeGenBase;IIII)[Lnet/minecraft/src/BiomeGenBase;", m -> true);
            aliasMethod(wcm, map, "loadBlockGeneratorData", "([Lnet/minecraft/src/MobSpawnerBase;IIII)[Lnet/minecraft/src/MobSpawnerBase;", m -> true);
        }

        // ClippingHelper & Frustrum
        ClassNode ch = classes.get("net/minecraft/src/ClippingHelper");
        if (ch != null) {
            aliasMethod(ch, map, "isBoxInFrustum", "(DDDDDD)Z", m -> true);
            aliasField(ch, map, "frustum", "[[F");
            // Subclasses (ClippingHelperImplementation) reference inherited fields via their own owner name in bytecode.
            String frustumOld = null;
            for (FieldNode f : ch.fields) {
                if ((f.access & Opcodes.ACC_STATIC) == 0 && f.desc.equals("[[F") && !f.name.equals("frustum")) {
                    frustumOld = f.name;
                    break;
                }
            }
            if (frustumOld != null) {
                for (ClassNode sub : classes.values()) {
                    if (ch.name.equals(sub.superName)) {
                        map.put(sub.name + "." + frustumOld, "frustum");
                    }
                }
            }
        }
        ClassNode icam = classes.get("net/minecraft/src/ICamera");
        if (icam != null) {
            aliasMethod(icam, map, "setPosition", "(DDD)V", m -> true);
            aliasMethod(icam, map, "isBoxInFrustum", "(DDDDDD)Z", m -> true);
            aliasMethod(icam, map, "isBoundingBoxInFrustum", "(Lnet/minecraft/src/AxisAlignedBB;)Z", m -> true);
        }
        ClassNode fr = classes.get("net/minecraft/src/Frustrum");
        if (fr == null) fr = classes.get("net/minecraft/src/Frustum");
        if (fr != null) {
            aliasMethod(fr, map, "setPosition", "(DDD)V", m -> true);
            aliasMethod(fr, map, "isBoxInFrustum", "(DDDDDD)Z", m -> true);
            aliasMethod(fr, map, "isBoundingBoxInFrustum", "(Lnet/minecraft/src/AxisAlignedBB;)Z", m -> true);
            aliasField(fr, map, "clippingHelper", "Lnet/minecraft/src/ClippingHelper;");
            if (!hasField(fr, "xPosition", "D")) {
                String[] posNames = {"xPosition", "yPosition", "zPosition"};
                int idx = 0;
                for (FieldNode f : fr.fields) {
                    if ((f.access & Opcodes.ACC_STATIC) == 0 && f.desc.equals("D") && idx < 3) {
                        if (!f.name.equals(posNames[idx])) map.put(fr.name + "." + f.name, posNames[idx]);
                        idx++;
                    }
                }
            }
        }

        // GuiIngame
        ClassNode gi = classes.get("net/minecraft/src/GuiIngame");
        if (gi != null) {
            aliasMethod(gi, map, "updateTick", "()V", m -> true);
            aliasMethod(gi, map, "renderGameOverlay", "(FZII)V", m -> true);
        }

        // MouseHelper (note: some RetroMCP versions misname grabMouseCursor as ungrabMouseCursor)
        ClassNode mh = classes.get("net/minecraft/src/MouseHelper");
        if (mh != null) {
            MethodNode grab = null, ungrab = null;
            for (MethodNode m : mh.methods) {
                if (!m.desc.equals("()V")) continue;
                if (calls(m, "dev/betavk/platform/Mouse", "setGrabbed") && hasIntConst(m, 0)) ungrab = m;
                else if (calls(m, "dev/betavk/platform/Mouse", "setGrabbed") && hasIntConst(m, 1)) grab = m;
            }
            if (grab != null && grab.name.equals("ungrabMouseCursor")) {
                map.put(mh.name + "." + grab.name + "()V", "grabMouseCursor");
            }
            if (ungrab != null && !ungrab.name.equals("ungrabMouseCursor")) {
                map.put(mh.name + "." + ungrab.name + "()V", "ungrabMouseCursor");
            }
        }

        // MovementInput & MovementInputFromOptions
        ClassNode mib = classes.get("net/minecraft/src/MovementInput");
        if (mib != null) {
            aliasMethod(mib, map, "updatePlayerMoveState", "(Lnet/minecraft/src/EntityPlayer;)V", m -> true);
            if (!hasField(mib, "moveStrafe", "F")) {
                String[] fNames = {"moveStrafe", "moveForward"};
                int idx = 0;
                for (FieldNode f : mib.fields) {
                    if ((f.access & Opcodes.ACC_STATIC) == 0 && f.desc.equals("F") && idx < fNames.length) {
                        map.put(mib.name + "." + f.name, fNames[idx]);
                        map.put("net/minecraft/src/MovementInputFromOptions." + f.name, fNames[idx]);
                        idx++;
                    }
                }
            }
            if (!hasField(mib, "jump", "Z")) {
                String[] bNames = {"field_1177_c", "jump", "sneak"};
                int idx = 0;
                for (FieldNode f : mib.fields) {
                    if ((f.access & Opcodes.ACC_STATIC) == 0 && f.desc.equals("Z") && idx < bNames.length) {
                        map.put(mib.name + "." + f.name, bNames[idx]);
                        map.put("net/minecraft/src/MovementInputFromOptions." + f.name, bNames[idx]);
                        idx++;
                    }
                }
            }
        }
        ClassNode mi = classes.get("net/minecraft/src/MovementInputFromOptions");
        if (mi != null) {
            aliasMethod(mi, map, "updatePlayerMoveState", "(Lnet/minecraft/src/EntityPlayer;)V", m -> true);
            aliasField(mi, map, "movementKeyStates", "[Z");
            aliasField(mi, map, "gameSettings", "Lnet/minecraft/src/GameSettings;");
        }

        // SoundManager & Colorizer*
        ClassNode sm = classes.get("net/minecraft/src/SoundManager");
        if (sm != null) {
            aliasMethod(sm, map, "playRandomMusicIfReady", "()V", m -> hasLdc(m, "BgMusic"));
            aliasMethod(sm, map, "playSoundFX", "(Ljava/lang/String;FF)V", m -> true);
            if (!hasField(sm, "ticksBeforeMusic", "I")) {
                int idx = 0;
                for (FieldNode f : sm.fields) {
                    if ((f.access & Opcodes.ACC_STATIC) == 0 && f.desc.equals("I")) {
                        if (idx == 1) {
                            map.put(sm.name + "." + f.name, "ticksBeforeMusic");
                            break;
                        }
                        idx++;
                    }
                }
            }
        }
        ClassNode gb = classes.get("net/minecraft/src/GuiButton");
        if (gb != null && !hasField(gb, "enabled2", "Z")) {
            int idx = 0;
            for (FieldNode f : gb.fields) {
                if ((f.access & Opcodes.ACC_STATIC) == 0 && f.desc.equals("Z")) {
                    if (idx == 1) {
                        map.put(gb.name + "." + f.name, "enabled2");
                        break;
                    }
                    idx++;
                }
            }
        }
        ClassNode cg = classes.get("net/minecraft/src/ColorizerGrass");
        if (cg != null) aliasMethod(cg, map, "getGrassColor", "(DD)I", m -> true);
        ClassNode cf = classes.get("net/minecraft/src/ColorizerFoliage");
        if (cf != null) aliasMethod(cf, map, "getFoliageColor", "(DD)I", m -> true);
        ClassNode eo = classes.get("net/minecraft/src/EnumOptions");
        if (eo != null) {
            aliasMethod(eo, map, "getEnumOptions", "(I)Lnet/minecraft/src/EnumOptions;", m -> true);
            aliasMethod(eo, map, "returnEnumOrdinal", "()I", m -> true);
            aliasMethod(eo, map, "getEnumString", "()Ljava/lang/String;", m -> true);
            if (!hasMethod(eo, "getEnumFloat", "()Z")) {
                String[] bMethods = {"getEnumFloat", "getEnumBoolean"};
                int idx = 0;
                for (MethodNode m : eo.methods) {
                    if (!m.name.startsWith("<") && m.desc.equals("()Z") && idx < 2) {
                        if (!m.name.equals(bMethods[idx])) map.put(eo.name + "." + m.name + m.desc, bMethods[idx]);
                        idx++;
                    }
                }
            }
        }
        ClassNode cst = classes.get("net/minecraft/src/CompressedStreamTools");
        if (cst != null) aliasMethod(cst, map, "func_1138_a", "(Ljava/io/InputStream;)Lnet/minecraft/src/NBTTagCompound;", m -> true);

        // MetadataChunkBlock, NoiseGeneratorPerlin, WorldGenMinable
        ClassNode mcb = classes.get("net/minecraft/src/MetadataChunkBlock");
        if (mcb != null) {
            aliasMethod(mcb, map, "func_4127_a", "(Lnet/minecraft/src/World;)V", m -> true);
            if (!hasField(mcb, "field_1298_b", "I")) {
                aliasField(mcb, map, "field_1299_a", "Lnet/minecraft/src/EnumSkyBlock;");
                String[] intNames = {"field_1298_b", "field_1304_c", "field_1303_d", "field_1302_e", "field_1301_f", "field_1300_g"};
                int idx = 0;
                for (FieldNode f : mcb.fields) {
                    if ((f.access & Opcodes.ACC_STATIC) == 0 && f.desc.equals("I") && idx < intNames.length) {
                        map.put(mcb.name + "." + f.name, intNames[idx++]);
                    }
                }
            }
        }
        ClassNode ngp = classes.get("net/minecraft/src/NoiseGeneratorPerlin");
        if (ngp != null) aliasMethod(ngp, map, "func_805_a", "([DDDDIIIDDDD)V", m -> true);
        ClassNode ngo = classes.get("net/minecraft/src/NoiseGeneratorOctaves");
        if (ngo != null) {
            aliasMethod(ngo, map, "func_806_a", "(DD)D", m -> true);
            aliasMethod(ngo, map, "func_807_a", "([DDDDIIIDDD)[D", m -> true);
        }
        ClassNode cpg = classes.get("net/minecraft/src/ChunkProviderGenerate");
        if (cpg != null && !hasField(cpg, "mobSpawnerNoise", "Lnet/minecraft/src/NoiseGeneratorOctaves;")) {
            for (MethodNode m : cpg.methods) {
                if (m.name.equals("<init>")) {
                    String lastNoise = null;
                    for (AbstractInsnNode insn : m.instructions) {
                        if (insn instanceof FieldInsnNode fi && fi.getOpcode() == Opcodes.PUTFIELD
                                && fi.owner.equals(cpg.name) && fi.desc.equals("Lnet/minecraft/src/NoiseGeneratorOctaves;")) {
                            lastNoise = fi.name;
                        }
                    }
                    if (lastNoise != null) map.put(cpg.name + "." + lastNoise, "mobSpawnerNoise");
                    break;
                }
            }
        }
        ClassNode wgm = classes.get("net/minecraft/src/WorldGenMinable");
        if (wgm != null) aliasMethod(wgm, map, "generate", "(Lnet/minecraft/src/World;Ljava/util/Random;III)Z", m -> true);

        // World
        ClassNode world = classes.get("net/minecraft/src/World");
        if (world != null) {
            if (!hasField(world, "chunkProvider", "Lnet/minecraft/src/IChunkProvider;")) {
                FieldNode cpF = findFieldByDesc(world, "Lnet/minecraft/src/IChunkProvider;");
                if (cpF != null) renameInheritedField(classes, map, world.name, cpF.name, "chunkProvider");
            }
            if (!hasField(world, "saveHandler", "Lnet/minecraft/src/ISaveHandler;")) {
                FieldNode shF = findFieldByDesc(world, "Lnet/minecraft/src/ISaveHandler;");
                if (shF != null) renameInheritedField(classes, map, world.name, shF.name, "saveHandler");
            }
            if (!hasField(world, "worldInfo", "Lnet/minecraft/src/WorldInfo;")) {
                FieldNode wiF = findFieldByDesc(world, "Lnet/minecraft/src/WorldInfo;");
                if (wiF != null) renameInheritedField(classes, map, world.name, wiF.name, "worldInfo");
            }
            aliasMethod(world, map, "getWorldChunkManager", "()Lnet/minecraft/src/WorldChunkManager;", m -> true);
            aliasMethod(world, map, "getWorldInfo", "()Lnet/minecraft/src/WorldInfo;", m -> true);
            if (!hasMethod(world, "getSpawnPoint", "()Lnet/minecraft/src/ChunkCoordinates;")) {
                MethodNode sp = findMethodByDesc(world, "()Lnet/minecraft/src/ChunkCoordinates;");
                if (sp != null) renameHierarchyMethod(classes, map, world.name, sp.name, sp.desc, "getSpawnPoint");
            }
            if (!hasMethod(world, "getRandomSeed", "()J")) {
                for (MethodNode m : world.methods) {
                    if (m.desc.equals("()J") && calls(m, "net/minecraft/src/WorldInfo", "getRandomSeed")) {
                        renameHierarchyMethod(classes, map, world.name, m.name, m.desc, "getRandomSeed");
                        break;
                    }
                }
            }
            if (!hasMethod(world, "getWorldTime", "()J")) {
                for (MethodNode m : world.methods) {
                    if (m.desc.equals("()J") && calls(m, "net/minecraft/src/WorldInfo", "getWorldTime")) {
                        renameHierarchyMethod(classes, map, world.name, m.name, m.desc, "getWorldTime");
                        break;
                    }
                }
            }
            aliasMethod(world, map, "getLightBrightness", "(III)F", m -> true);
            aliasMethod(world, map, "isBlockOpaqueCube", "(III)Z", m -> m.name.equals("isBlockNormalCube") || m.name.equals("doesBlockAllowAttachment"));
            aliasMethod(world, map, "updatingLighting", "()Z", m -> calls(m, "java/util/List", "size"));
            // a1.0.2_01, a1.0.16, a1.0.17_02 left lightingToUpdate obfuscated (and even assigned "lightingToUpdate" to a different List)
            for (MethodNode m : world.methods) {
                if (m.desc.equals("()Z") && calls(m, "java/util/List", "size")) {
                    for (AbstractInsnNode insn : m.instructions) {
                        if (insn instanceof FieldInsnNode fi && fi.getOpcode() == Opcodes.GETFIELD
                                && fi.owner.equals("net/minecraft/src/World") && fi.desc.equals("Ljava/util/List;")) {
                            if (!fi.name.equals("lightingToUpdate")) {
                                if (hasField(world, "lightingToUpdate", "Ljava/util/List;")) {
                                    map.put(world.name + ".lightingToUpdate", "_misnamed_lightingToUpdate");
                                }
                                map.put(world.name + "." + fi.name, "lightingToUpdate");
                            }
                            break;
                        }
                    }
                    break;
                }
            }
        }

        // EntityRenderer
        ClassNode er = classes.get("net/minecraft/src/EntityRenderer");
        if (er != null) {
            aliasMethod(er, map, "getFOVModifier", "(F)F", m -> hasFloatConst(m, 70.0F) || hasFloatConst(m, 60.0F));
            aliasMethod(er, map, "setupCameraTransform", "(FI)V", m -> calls(m, "dev/betavk/render/Gfx", "gluPerspective"));
            aliasMethod(er, map, "func_4135_b", "(FI)V", m -> !calls(m, "dev/betavk/render/Gfx", "gluPerspective"));
            aliasMethod(er, map, "renderWorld", "(FJ)V", m -> callsAny(m, "setupCameraTransform") || calls(m, "dev/betavk/render/Gfx", "glEnable"));
            if (!hasMethod(er, "renderWorld", "(FJ)V")) {
                MethodNode realRw = null;
                MethodNode fakeRw = null;
                for (MethodNode m : er.methods) {
                    if (!m.desc.equals("(F)V")) continue;
                    if (m.name.equals("renderWorld")) fakeRw = m;
                    if (calls(m, "net/minecraft/src/RenderGlobal", "updateRenderers")
                            || callsAny(m, "setupCameraTransform")) {
                        realRw = m;
                    }
                }
                if (realRw != null && !realRw.name.equals("renderWorld")) {
                    if (fakeRw != null) {
                        map.put(er.name + ".renderWorld(F)V", hasMethod(er, "getMouseOver", "(F)V") ? "_misnamed_renderWorld" : "getMouseOver");
                    }
                    map.put(er.name + "." + realRw.name + "(F)V", "renderWorld");
                }
            }
            aliasMethod(er, map, "updateCameraAndRender", "(F)V", m -> hasFloatConst(m, 8.0F));
            // Canonicalize farPlaneDistance field written in setupCameraTransform from 256 >> renderDistance
            if (!hasField(er, "farPlaneDistance", "F")) {
                for (MethodNode m : er.methods) {
                    if (m.desc.equals("(FI)V") && (hasIntConst(m, 256) || calls(m, "dev/betavk/render/Gfx", "gluPerspective"))) {
                        for (AbstractInsnNode i : m.instructions) {
                            if (i instanceof FieldInsnNode f && f.getOpcode() == Opcodes.PUTFIELD && f.owner.equals(er.name) && f.desc.equals("F")) {
                                map.put(er.name + "." + f.name, "farPlaneDistance");
                                break;
                            }
                        }
                    }
                }
            }
        }

        // RenderList
        ClassNode rl = classes.get("net/minecraft/src/RenderList");
        if (rl != null) {
            aliasMethod(rl, map, "func_861_a", "(IIIDDD)V", m -> true);
            aliasMethod(rl, map, "func_858_a", "(I)V", m -> true);
            aliasMethod(rl, map, "func_859_b", "()V", m -> m.name.equals("reset") || !calls(m, "dev/betavk/render/Gfx", "glCallLists"));
            aliasField(rl, map, "field_1236_g", "Ljava/nio/IntBuffer;");
        }

        // WorldRenderer
        ClassNode wr = classes.get("net/minecraft/src/WorldRenderer");
        if (wr != null) {
            aliasMethod(wr, map, "setPosition", "(III)V", m -> true);
            // b1.2_02 RetroMCP misnamed updateRenderer() as setDontDraw(), and left real setDontDraw() as func_1195_b()
            MethodNode existingSetDontDraw = findMethod(wr, "setDontDraw", "()V");
            boolean misnamedSetDontDraw = existingSetDontDraw != null && existingSetDontDraw.instructions.size() > 150;
            if (misnamedSetDontDraw && !hasMethod(wr, "updateRenderer", "()V")) {
                map.put(wr.name + ".setDontDraw()V", "updateRenderer");
            } else {
                aliasMethod(wr, map, "updateRenderer", "()V", m -> m.instructions.size() > 150 && calls(m, "net/minecraft/src/Tessellator", "startDrawingQuads"));
            }
            if (existingSetDontDraw == null || misnamedSetDontDraw) {
                for (MethodNode m : wr.methods) {
                    if (m.desc.equals("()V") && m.instructions.size() < 80 && countsOpcode(m, Opcodes.BASTORE) >= 1 && !calls(m, "net/minecraft/src/GLAllocation", "deleteDisplayLists")) {
                        map.put(wr.name + "." + m.name + "()V", "setDontDraw");
                        break;
                    }
                }
            }
            if (!hasField(wr, "posXMinus", "I")) {
                for (MethodNode m : wr.methods) {
                    if (m.desc.equals("()V") && calls(m, "dev/betavk/render/Gfx", "glTranslatef")) {
                        String[] minus = {"posXMinus", "posYMinus", "posZMinus"};
                        int idx = 0;
                        for (AbstractInsnNode insn : m.instructions) {
                            if (insn instanceof FieldInsnNode fi && fi.getOpcode() == Opcodes.GETFIELD
                                    && fi.owner.equals(wr.name) && fi.desc.equals("I") && idx < 3) {
                                map.put(wr.name + "." + fi.name, minus[idx++]);
                            }
                        }
                        break;
                    }
                }
            }
            if (!hasField(wr, "posX", "I")) {
                for (MethodNode m : wr.methods) {
                    if (m.desc.equals("(III)V")) {
                        String[] pos = {"posX", "posY", "posZ"};
                        int idx = 0;
                        for (AbstractInsnNode insn : m.instructions) {
                            if (insn instanceof FieldInsnNode fi && fi.getOpcode() == Opcodes.PUTFIELD
                                    && fi.owner.equals(wr.name) && fi.desc.equals("I") && idx < 3) {
                                map.put(wr.name + "." + fi.name, pos[idx++]);
                            }
                        }
                        break;
                    }
                }
            }
            aliasField(wr, map, "skipRenderPass", "[Z");
            if (!hasField(wr, "glRenderList", "I")) {
                for (MethodNode m : wr.methods) {
                    if (m.desc.equals("()V") && calls(m, "dev/betavk/render/Gfx", "glCallList")) {
                        for (AbstractInsnNode insn : m.instructions) {
                            if (insn instanceof FieldInsnNode fi && fi.getOpcode() == Opcodes.GETFIELD
                                    && fi.owner.equals(wr.name) && fi.desc.equals("I")) {
                                map.put(wr.name + "." + fi.name, "glRenderList");
                                break;
                            }
                        }
                        break;
                    }
                }
            }
            if (!hasField(wr, "tileEntities", "Ljava/util/List;")) {
                for (FieldNode f : wr.fields) {
                    if ((f.access & Opcodes.ACC_STATIC) == 0 && f.desc.equals("Ljava/util/List;") && !f.name.equals("tileEntityRenderers")) {
                        map.put(wr.name + "." + f.name, "tileEntities");
                        break;
                    }
                }
            }
            if (!hasField(wr, "chunksUpdated", "I")) {
                for (MethodNode m : wr.methods) {
                    if (m.desc.equals("()V") && calls(m, "net/minecraft/src/Tessellator", "startDrawingQuads")) {
                        for (AbstractInsnNode insn : m.instructions) {
                            if (insn instanceof FieldInsnNode fi && fi.getOpcode() == Opcodes.PUTSTATIC
                                    && fi.owner.equals(wr.name) && fi.desc.equals("I")) {
                                map.put(wr.name + "." + fi.name, "chunksUpdated");
                                break;
                            }
                        }
                        break;
                    }
                }
            }
            aliasMethod(wr, map, "updateInFrustrum", "(Lnet/minecraft/src/ICamera;)V", m -> true);
            aliasMethod(wr, map, "setupGLTranslation", "()V", m -> calls(m, "dev/betavk/render/Gfx", "glTranslatef"));
            aliasMethod(wr, map, "skipAllRenderPasses", "()Z", m -> true);
            aliasMethod(wr, map, "distanceToEntitySquared", "(Lnet/minecraft/src/Entity;)F", m -> true);
            aliasField(wr, map, "rendererBoundingBox", "Lnet/minecraft/src/AxisAlignedBB;");
            if (!hasField(wr, "isInFrustum", "Z")) {
                for (MethodNode m : wr.methods) {
                    if (m.desc.equals("(Lnet/minecraft/src/ICamera;)V")) {
                        for (AbstractInsnNode insn : m.instructions) {
                            if (insn instanceof FieldInsnNode fi && fi.getOpcode() == Opcodes.PUTFIELD
                                    && fi.owner.equals(wr.name) && fi.desc.equals("Z")) {
                                map.put(wr.name + "." + fi.name, "isInFrustum");
                                break;
                            }
                        }
                        break;
                    }
                }
            }
            if (!hasField(wr, "sizeWidth", "I") || !hasField(wr, "tileEntityRenderers", "Ljava/util/List;")) {
                for (MethodNode m : wr.methods) {
                    if (m.name.equals("<init>")) {
                        String[] sizeNames = {"sizeDepth", "sizeHeight", "sizeWidth"};
                        int intIdx = 0;
                        int listIdx = 0;
                        for (AbstractInsnNode insn : m.instructions) {
                            if (insn instanceof FieldInsnNode fi && fi.getOpcode() == Opcodes.PUTFIELD && fi.owner.equals(wr.name)) {
                                if (fi.desc.equals("I")) {
                                    // 0: glRenderList = -1; 1..3: sizeDepth, sizeHeight, sizeWidth
                                    if (intIdx >= 1 && intIdx <= 3 && !fi.name.equals(sizeNames[intIdx - 1])) {
                                        map.put(wr.name + "." + fi.name, sizeNames[intIdx - 1]);
                                    }
                                    intIdx++;
                                } else if (fi.desc.equals("Ljava/util/List;")) {
                                    if (listIdx == 1 && !fi.name.equals("tileEntityRenderers")) {
                                        map.put(wr.name + "." + fi.name, "tileEntityRenderers");
                                    }
                                    listIdx++;
                                }
                            }
                        }
                        break;
                    }
                }
            }
            if (!hasField(wr, "isChunkLit", "Z") || !hasField(wr, "isInitialized", "Z")) {
                String[] boolNames = {"isInFrustum", "needsUpdate", "isVisible", "isWaitingOnOcclusionQuery", "isChunkLit", "isInitialized"};
                int idx = 0;
                for (FieldNode f : wr.fields) {
                    if ((f.access & Opcodes.ACC_STATIC) == 0 && f.desc.equals("Z") && idx < boolNames.length) {
                        if (!f.name.equals(boolNames[idx])) map.put(wr.name + "." + f.name, boolNames[idx]);
                        idx++;
                    }
                }
            }
        }

        // Tessellator static instance field
        ClassNode tess = classes.get("net/minecraft/src/Tessellator");
        if (tess != null && !hasField(tess, "instance", "Lnet/minecraft/src/Tessellator;")) {
            for (FieldNode f : tess.fields) {
                if ((f.access & Opcodes.ACC_STATIC) != 0 && f.desc.equals("Lnet/minecraft/src/Tessellator;")) {
                    map.put(tess.name + "." + f.name, "instance");
                    break;
                }
            }
        }

        // RenderGlobal
        ClassNode rg = classes.get("net/minecraft/src/RenderGlobal");
        if (rg != null) {
            aliasMethod(rg, map, "loadRenderers", "()V", m -> hasNewArray(m, "net/minecraft/src/WorldRenderer"));
            aliasMethod(rg, map, "markBlockAndNeighborsNeedsUpdate", "(III)V", m -> !hasIntConst(m, Integer.MAX_VALUE));
            aliasField(rg, map, "allRenderLists", "[Lnet/minecraft/src/RenderList;");
            String sortedField = null;
            for (MethodNode m : rg.methods) {
                if (!m.desc.endsWith(")I") && !m.desc.endsWith(")V")) continue;
                String lastArr = null;
                for (AbstractInsnNode insn : m.instructions) {
                    if (insn instanceof FieldInsnNode fi && fi.getOpcode() == Opcodes.GETFIELD
                            && fi.owner.equals(rg.name) && fi.desc.equals("[Lnet/minecraft/src/WorldRenderer;")) {
                        lastArr = fi.name;
                    } else if (insn instanceof MethodInsnNode callInsn && callInsn.getOpcode() == Opcodes.INVOKESTATIC
                            && callInsn.owner.equals("java/util/Arrays") && callInsn.name.equals("sort") && lastArr != null) {
                        sortedField = lastArr;
                        break;
                    }
                }
                if (sortedField != null) break;
            }
            if (sortedField != null) {
                String otherArr = null;
                for (FieldNode f : rg.fields) {
                    if ((f.access & Opcodes.ACC_STATIC) == 0 && f.desc.equals("[Lnet/minecraft/src/WorldRenderer;")
                            && !f.name.equals(sortedField)) {
                        otherArr = f.name;
                        break;
                    }
                }
                if (!sortedField.equals("sortedWorldRenderers")) map.put(rg.name + "." + sortedField, "sortedWorldRenderers");
                if (otherArr != null && !otherArr.equals("worldRenderers")) map.put(rg.name + "." + otherArr, "worldRenderers");
            } else if (!hasField(rg, "worldRenderers", "[Lnet/minecraft/src/WorldRenderer;")) {
                for (MethodNode m : rg.methods) {
                    if (m.desc.equals("()V") && hasNewArray(m, "net/minecraft/src/WorldRenderer")) {
                        String[] arrNames = {"sortedWorldRenderers", "worldRenderers"};
                        int idx = 0;
                        for (AbstractInsnNode insn : m.instructions) {
                            if (insn instanceof FieldInsnNode fi && fi.getOpcode() == Opcodes.PUTFIELD
                                    && fi.owner.equals(rg.name) && fi.desc.equals("[Lnet/minecraft/src/WorldRenderer;") && idx < 2) {
                                map.put(rg.name + "." + fi.name, arrNames[idx++]);
                            }
                        }
                        break;
                    }
                }
            }
            if (!hasField(rg, "glRenderLists", "Ljava/util/List;") || !hasField(rg, "worldRenderersToUpdate", "Ljava/util/List;")) {
                for (MethodNode m : rg.methods) {
                    if (m.name.equals("<init>")) {
                        String[] listNames = {"tileEntities", "worldRenderersToUpdate", "glRenderLists"};
                        int idx = 0;
                        for (AbstractInsnNode insn : m.instructions) {
                            if (insn instanceof FieldInsnNode fi && fi.getOpcode() == Opcodes.PUTFIELD
                                    && fi.owner.equals(rg.name) && fi.desc.equals("Ljava/util/List;") && idx < 3) {
                                if (!fi.name.equals(listNames[idx])) map.put(rg.name + "." + fi.name, listNames[idx]);
                                idx++;
                            }
                        }
                        break;
                    }
                }
            }
            if (wr != null && !hasField(wr, "chunkIndex", "I")) {
                for (MethodNode m : rg.methods) {
                    if (m.desc.equals("()V") && hasNewArray(m, "net/minecraft/src/WorldRenderer")) {
                        for (AbstractInsnNode insn : m.instructions) {
                            if (insn instanceof FieldInsnNode fi && fi.getOpcode() == Opcodes.PUTFIELD
                                    && fi.owner.equals("net/minecraft/src/WorldRenderer") && fi.desc.equals("I")) {
                                map.put(wr.name + "." + fi.name, "chunkIndex");
                                break;
                            }
                        }
                        break;
                    }
                }
            }
            if (!hasField(rg, "renderChunksWide", "I") || !hasField(rg, "renderChunksTall", "I") || !hasField(rg, "renderChunksDeep", "I")) {
                for (MethodNode m : rg.methods) {
                    if (m.desc.equals("()V") && hasNewArray(m, "net/minecraft/src/WorldRenderer")) {
                        java.util.List<String> intFields = new java.util.ArrayList<>();
                        for (AbstractInsnNode insn : m.instructions) {
                            if (insn instanceof TypeInsnNode ti && ti.getOpcode() == Opcodes.ANEWARRAY && ti.desc.equals("net/minecraft/src/WorldRenderer")) {
                                break;
                            }
                            if (insn instanceof FieldInsnNode fi && fi.getOpcode() == Opcodes.PUTFIELD
                                    && fi.owner.equals(rg.name) && fi.desc.equals("I")) {
                                intFields.add(fi.name);
                            }
                        }
                        if (intFields.size() >= 3) {
                            String[] dimNames = {"renderChunksWide", "renderChunksTall", "renderChunksDeep"};
                            int base = intFields.size() - 3;
                            for (int i = 0; i < 3; i++) {
                                if (!intFields.get(base + i).equals(dimNames[i])) {
                                    map.put(rg.name + "." + intFields.get(base + i), dimNames[i]);
                                }
                            }
                        }
                        break;
                    }
                }
            }
            aliasMethod(rg, map, "sortAndRender", "(Lnet/minecraft/src/EntityLiving;ID)I", m -> true);
            aliasMethod(rg, map, "sortAndRender", "(Lnet/minecraft/src/EntityPlayer;ID)I", m -> true);
            aliasMethod(rg, map, "renderSortedRenderers", "(IIID)I", m -> true);
            aliasMethod(rg, map, "renderAllRenderLists", "(ID)V", m -> true);
            aliasMethod(rg, map, "updateRenderers", "(Lnet/minecraft/src/EntityLiving;Z)Z", m -> true);
            aliasMethod(rg, map, "updateRenderers", "(Lnet/minecraft/src/EntityPlayer;Z)Z", m -> true);
            aliasMethod(rg, map, "clipRenderersByFrustrum", "(Lnet/minecraft/src/ICamera;F)V", m -> true);
            if (!hasField(rg, "renderersSkippingRenderPass", "I") && hasField(rg, "dummyRenderInt", "I")) {
                map.put(rg.name + ".dummyRenderInt", "renderersSkippingRenderPass");
            }
            // renderChunksDeep field if unnamed in older mappings
            if (!hasField(rg, "renderChunksDeep", "I")) {
                for (MethodNode m : rg.methods) {
                    if (hasNewArray(m, "net/minecraft/src/WorldRenderer")) {
                        // loadRenderers computes renderChunksWide / renderChunksTall / renderChunksDeep before ANEWARRAY
                        FieldInsnNode lastIntPut = null;
                        for (AbstractInsnNode i : m.instructions) {
                            if (i instanceof TypeInsnNode t && t.getOpcode() == Opcodes.ANEWARRAY && t.desc.equals("net/minecraft/src/WorldRenderer")) {
                                if (lastIntPut != null) map.put(rg.name + "." + lastIntPut.name, "renderChunksDeep");
                                break;
                            }
                            if (i instanceof FieldInsnNode f && f.getOpcode() == Opcodes.PUTFIELD && f.owner.equals(rg.name) && f.desc.equals("I")) {
                                lastIntPut = f;
                            }
                        }
                    }
                }
            }
        }

        // GameSettings, ChunkProvider, StringTranslate, WorldInfo, Entity, EntityLiving, EntityPlayer, EntityPlayerSP
        ClassNode gs = classes.get("net/minecraft/src/GameSettings");
        if (gs != null) {
            aliasField(gs, map, "optionsFile", "Ljava/io/File;");
            aliasMethod(gs, map, "getKeyBindingDescription", "(I)Ljava/lang/String;", m -> !m.name.equals("getOptionDisplayString"));
            if (classes.containsKey("net/minecraft/src/EnumOptions")) {
                aliasMethod(gs, map, "getOptionFloatValue", "(Lnet/minecraft/src/EnumOptions;)F", m -> true);
                aliasMethod(gs, map, "getOptionOrdinalValue", "(Lnet/minecraft/src/EnumOptions;)Z", m -> true);
            }
            if (!hasField(gs, "guiScale", "I") && classes.containsKey("net/minecraft/src/EnumOptions")) {
                for (MethodNode m : gs.methods) {
                    if (!m.name.equals("getKeyBinding") && !m.name.equals("setOptionValue")) continue;
                    boolean sawGuiScale = false;
                    for (AbstractInsnNode insn : m.instructions) {
                        if (insn instanceof FieldInsnNode fi && fi.owner.equals("net/minecraft/src/EnumOptions") && fi.name.equals("GUI_SCALE")) {
                            sawGuiScale = true;
                        } else if (sawGuiScale && insn instanceof FieldInsnNode fi && fi.owner.equals(gs.name) && fi.desc.equals("I")) {
                            map.put(gs.name + "." + fi.name, "guiScale");
                            sawGuiScale = false;
                            break;
                        }
                    }
                    if (map.containsValue("guiScale")) break;
                }
            }
        }
        ClassNode icprov = classes.get("net/minecraft/src/IChunkProvider");
        ClassNode cprov = classes.get("net/minecraft/src/ChunkProvider");
        if (cprov != null) {
            aliasField(cprov, map, "droppedChunksSet", "Ljava/util/Set;");
            aliasField(cprov, map, "chunkLoader", "Lnet/minecraft/src/IChunkLoader;");
            aliasField(cprov, map, "chunkProvider", "Lnet/minecraft/src/IChunkProvider;");
            aliasField(cprov, map, "chunkMap", "Ljava/util/Map;");
            aliasField(cprov, map, "chunkList", "Ljava/util/List;");
            MethodNode prepM = findMethod(cprov, "prepareChunk", "(II)Lnet/minecraft/src/Chunk;");
            String oldPrep = null;
            if (prepM == null) {
                for (MethodNode m : cprov.methods) {
                    if (m.desc.equals("(II)Lnet/minecraft/src/Chunk;")
                            && (calls(m, "net/minecraft/src/ChunkProvider", "populate") || calls(m, "net/minecraft/src/ChunkCoordIntPair", "chunkXZ2Int"))) {
                        oldPrep = m.name;
                        map.put(cprov.name + "." + oldPrep + m.desc, "prepareChunk");
                        break;
                    }
                }
            }
            if (oldPrep != null && icprov != null) {
                for (MethodNode m : icprov.methods) {
                    if (m.name.equals(oldPrep) && m.desc.equals("(II)Lnet/minecraft/src/Chunk;")) {
                        map.put(icprov.name + "." + oldPrep + m.desc, "prepareChunk");
                        for (ClassNode sub : classes.values()) {
                            if (sub != cprov && implementsInterface(classes, sub, icprov.name)) {
                                if (hasMethod(sub, oldPrep, m.desc)) {
                                    map.put(sub.name + "." + oldPrep + m.desc, "prepareChunk");
                                }
                            }
                        }
                        break;
                    }
                }
            }
        }
        ClassNode coords = classes.get("net/minecraft/src/ChunkCoordinates");
        if (coords != null && !hasField(coords, "x", "I")) {
            MethodNode init3 = findMethod(coords, "<init>", "(III)V");
            if (init3 != null) {
                String[] names = {"x", "y", "z"};
                int idx = 0;
                for (AbstractInsnNode insn : init3.instructions) {
                    if (insn instanceof FieldInsnNode fi && fi.getOpcode() == Opcodes.PUTFIELD
                            && fi.owner.equals(coords.name) && fi.desc.equals("I") && idx < 3) {
                        if (!fi.name.equals(names[idx])) map.put(coords.name + "." + fi.name, names[idx]);
                        idx++;
                    }
                }
            }
        }
        ClassNode st = classes.get("net/minecraft/src/StringTranslate");
        if (st != null) {
            aliasMethod(st, map, "getInstance", "()Lnet/minecraft/src/StringTranslate;", m -> true);
            aliasMethod(st, map, "translateKey", "(Ljava/lang/String;)Ljava/lang/String;", m -> !hasLdc(m, ".name"));
            aliasMethod(st, map, "translateNamedKey", "(Ljava/lang/String;)Ljava/lang/String;", m -> hasLdc(m, ".name"));
        }
        ClassNode wi = classes.get("net/minecraft/src/WorldInfo");
        if (wi != null) {
            aliasMethod(wi, map, "updateTagCompound", "(Lnet/minecraft/src/NBTTagCompound;Lnet/minecraft/src/NBTTagCompound;)V", m -> true);
        }
        ClassNode sh = classes.get("net/minecraft/src/SaveHandler");
        if (sh != null && !hasMethod(sh, "getSaveDirectory", "()Ljava/io/File;")) {
            MethodNode getDir = findMethodByDesc(sh, "()Ljava/io/File;");
            if (getDir != null) renameHierarchyMethod(classes, map, sh.name, getDir.name, "()Ljava/io/File;", "getSaveDirectory");
        }
        ClassNode rfc = classes.get("net/minecraft/src/RegionFileCache");
        if (rfc != null) {
            aliasMethod(rfc, map, "getChunkInputStream", "(Ljava/io/File;II)Ljava/io/DataInputStream;", m -> true);
            aliasMethod(rfc, map, "getChunkOutputStream", "(Ljava/io/File;II)Ljava/io/DataOutputStream;", m -> true);
        }
        ClassNode mrcl = classes.get("net/minecraft/src/McRegionChunkLoader");
        if (mrcl != null) {
            aliasField(mrcl, map, "worldDir", "Ljava/io/File;");
        }
        ClassNode gws = classes.get("net/minecraft/src/GuiWorldSlot");
        if (gws != null) {
            aliasField(gws, map, "parentWorldGui", "Lnet/minecraft/src/GuiSelectWorld;");
            aliasMethod(gws, map, "drawSlot", "(IIIILnet/minecraft/src/Tessellator;)V", m -> true);
        }
        ClassNode gsw = classes.get("net/minecraft/src/GuiSelectWorld");
        if (gsw != null) {
            aliasField(gsw, map, "saveList", "Ljava/util/List;");
            aliasMethod(gsw, map, "loadSaves", "()V", m -> !m.name.equals("initGui") && !m.name.equals("initGui2"));
            aliasMethod(gsw, map, "getSize", "(Lnet/minecraft/src/GuiSelectWorld;)Ljava/util/List;", m -> true);
            if (!hasField(gsw, "buttonSelect", "Lnet/minecraft/src/GuiButton;")) {
                MethodNode init2 = findMethod(gsw, "initGui2", "()V");
                if (init2 != null) {
                    String lastKey = null;
                    for (AbstractInsnNode insn : init2.instructions) {
                        if (insn instanceof LdcInsnNode l && l.cst instanceof String s && s.startsWith("selectWorld.")) {
                            lastKey = s;
                        } else if (lastKey != null && insn instanceof FieldInsnNode fi && fi.getOpcode() == Opcodes.PUTFIELD
                                && fi.owner.equals(gsw.name) && fi.desc.equals("Lnet/minecraft/src/GuiButton;")) {
                            switch (lastKey) {
                                case "selectWorld.select" -> map.put(gsw.name + "." + fi.name, "buttonSelect");
                                case "selectWorld.rename" -> map.put(gsw.name + "." + fi.name, "buttonRename");
                                case "selectWorld.delete" -> map.put(gsw.name + "." + fi.name, "buttonDelete");
                            }
                            lastKey = null;
                        }
                    }
                }
            }
        }
        ClassNode sfc = classes.get("net/minecraft/src/SaveFormatComparator");
        if (sfc != null && !hasMethod(sfc, "getFileName", "()Ljava/lang/String;")) {
            String[] names = {"getFileName", "getDisplayName"};
            int idx = 0;
            for (MethodNode m : sfc.methods) {
                if (!m.name.startsWith("<") && m.desc.equals("()Ljava/lang/String;") && idx < 2) {
                    if (!m.name.equals(names[idx])) map.put(sfc.name + "." + m.name + m.desc, names[idx]);
                    idx++;
                }
            }
        }

        ClassNode entity = classes.get("net/minecraft/src/Entity");
        if (entity != null) normalizeEntity(classes, entity, map);

        ClassNode el = classes.get("net/minecraft/src/EntityLiving");
        if (el != null) aliasMethod(el, map, "moveEntityWithHeading", "(FF)V", m -> hasFloatConst(m, 0.91F));
        ClassNode ep = classes.get("net/minecraft/src/EntityPlayer");
        if (ep != null) {
            aliasMethod(ep, map, "updateCloak", "()V", m -> hasLdcSubstring(m, "/cloak/") || usesField(m, "playerCloakUrl"));
            if (!hasField(ep, "username", "Ljava/lang/String;")) {
                aliasField(ep, map, "username", "Ljava/lang/String;");
                for (Map.Entry<String, String> e : java.util.List.copyOf(map.entrySet())) {
                    if (e.getKey().startsWith(ep.name + ".") && e.getValue().equals("username")) {
                        String oldField = e.getKey().substring(ep.name.length() + 1);
                        map.put("net/minecraft/src/EntityPlayerSP." + oldField, "username");
                        map.put("net/minecraft/src/EntityOtherPlayerMP." + oldField, "username");
                    }
                }
            }
        }
        ClassNode epsp = classes.get("net/minecraft/src/EntityPlayerSP");
        if (epsp != null) {
            aliasMethod(epsp, map, "handleKeyPress", "(IZ)V", m -> true);
            aliasField(epsp, map, "movementInput", "Lnet/minecraft/src/MovementInput;");
            aliasField(epsp, map, "mc", "Lnet/minecraft/client/Minecraft;");
        }

        // MCP v2.4 (Alpha 1.2.3 - 1.2.5) searge/early names -> Alpha 1.2.6+ canonical names
        ClassNode math = classes.get("net/minecraft/src/MathHelper");
        if (math != null && !hasMethod(math, "floor_double", "(D)I") && hasMethod(math, "convertToBlockCoord_double", "(D)I")) {
            normalizeMcpV24(classes, map);
        }

        // KeyBinding (b1.8+ leaves pressed/setKeyBindState as srg names)
        ClassNode kb = classes.get("net/minecraft/src/KeyBinding");
        if (kb != null) {
            if (!hasField(kb, "pressed", "Z") && hasField(kb, "field_35965_e", "Z")) {
                map.put("field " + kb.name + "/field_35965_e", "pressed");
            }
            aliasMethod(kb, map, "setKeyBindState", "(IZ)V", m -> m.name.equals("func_35963_a"));
        }

        // Container
        ClassNode cont = classes.get("net/minecraft/src/Container");
        if (cont != null) {
            if (!hasField(cont, "slots", "Ljava/util/List;") && hasField(cont, "inventorySlots", "Ljava/util/List;")) {
                renameInheritedField(classes, map, cont.name, "inventorySlots", "slots");
            }
            aliasMethod(cont, map, "func_27280_a", "(IIZLnet/minecraft/src/EntityPlayer;)Lnet/minecraft/src/ItemStack;", m -> true);
            if (!hasMethod(cont, "getStackInSlot", "(I)Lnet/minecraft/src/ItemStack;")) {
                MethodNode slotM = null;
                int count = 0;
                for (MethodNode m : cont.methods) {
                    if (!m.name.startsWith("<") && m.desc.equals("(I)Lnet/minecraft/src/ItemStack;")) {
                        slotM = m;
                        count++;
                    }
                }
                if (count == 1 && slotM != null) {
                    String desc = "(I)Lnet/minecraft/src/ItemStack;";
                    String oldName = slotM.name;
                    map.put(cont.name + "." + oldName + desc, "getStackInSlot");
                    for (ClassNode c : classes.values()) {
                        if (hasMethod(c, oldName, desc)) {
                            map.put(c.name + "." + oldName + desc, "getStackInSlot");
                        }
                    }
                }
            }
        }

        // Minecraft
        ClassNode mc = classes.get("net/minecraft/client/Minecraft");
        if (mc != null) {
            aliasMethod(mc, map, "getAppDir", "(Ljava/lang/String;)Ljava/io/File;", m -> true);
            aliasMethod(mc, map, "startGame", "()V", m -> calls(m, "dev/betavk/platform/Display", "create"));
            aliasMethod(mc, map, "toggleFullscreen", "()V", m -> calls(m, "dev/betavk/platform/Display", "setFullscreen"));
            aliasMethod(mc, map, "run", "()V", m -> calls(m, "java/lang/Thread", "yield"));
            aliasMethod(mc, map, "runTick", "()V", m -> calls(m, "dev/betavk/platform/Keyboard", "next"));
            aliasMethod(mc, map, "screenshotListener", "()V", m -> calls(m, "dev/betavk/platform/Keyboard", "isKeyDown") && hasIntConst(m, 60));
            aliasMethod(mc, map, "changeWorld2", "(Lnet/minecraft/src/World;Ljava/lang/String;)V", m -> true);
            aliasMethod(mc, map, "changeWorld", "(Lnet/minecraft/src/World;Ljava/lang/String;Lnet/minecraft/src/EntityPlayer;)V", m -> true);
            aliasMethod(mc, map, "startWorld", "(Ljava/lang/String;Ljava/lang/String;J)V", m -> true);
            aliasMethod(mc, map, "startWorld", "(Ljava/lang/String;)V", m -> calls(m, "net/minecraft/src/World", "<init>") || calls(m, "net/minecraft/client/Minecraft", "changeWorld1") || calls(m, "net/minecraft/client/Minecraft", "changeWorld2"));
            aliasMethod(mc, map, "setIngameFocus", "()V", m -> calls(m, "dev/betavk/platform/Display", "isActive") && hasIntConst(m, 10000));
            aliasMethod(mc, map, "displayUnexpectedThrowable", "(Lnet/minecraft/src/UnexpectedThrowable;)V", m -> true);
            aliasMethod(mc, map, "isMultiplayerWorld", "()Z", m -> hasFieldInsn(m, Opcodes.GETFIELD, "net/minecraft/client/Minecraft"));
            aliasField(mc, map, "session", "Lnet/minecraft/src/Session;");
            aliasField(mc, map, "gameSettings", "Lnet/minecraft/src/GameSettings;");
            aliasField(mc, map, "playerController", "Lnet/minecraft/src/PlayerController;");
            aliasField(mc, map, "renderGlobal", "Lnet/minecraft/src/RenderGlobal;");
            aliasField(mc, map, "effectRenderer", "Lnet/minecraft/src/EffectRenderer;");
            aliasField(mc, map, "entityRenderer", "Lnet/minecraft/src/EntityRenderer;");
            if (!hasField(mc, "inGameHasFocus", "Z")) {
                for (MethodNode m : mc.methods) {
                    if (m.desc.equals("()V") && calls(m, "dev/betavk/platform/Display", "isActive") && hasIntConst(m, 10000)) {
                        for (AbstractInsnNode insn : m.instructions) {
                            if (insn instanceof FieldInsnNode fi && fi.getOpcode() == Opcodes.PUTFIELD
                                    && fi.owner.equals(mc.name) && fi.desc.equals("Z")) {
                                map.put(mc.name + "." + fi.name, "inGameHasFocus");
                                break;
                            }
                        }
                        break;
                    }
                }
            }
        }

        if (map.isEmpty()) return;

        SimpleRemapper remapper = new SimpleRemapper(map);
        Map<String, ClassNode> out = new HashMap<>(classes.size());
        for (Map.Entry<String, ClassNode> e : classes.entrySet()) {
            ClassNode next = new ClassNode();
            e.getValue().accept(new ClassRemapper(next, remapper));
            out.put(next.name, next);
        }
        classes.clear();
        classes.putAll(out);
    }

    private static void normalizeEntity(Map<String, ClassNode> classes, ClassNode entity, Map<String, String> map) {
        // b1.6-tb3 .. b1.6.4: RetroMCP shifted Entity fields by 1 slot vs b1.6.5+
        if (!hasField(entity, "motionZ", "D") && hasField(entity, "aI", "D")) {
            String[][] shifted = {
                    {"aC", "entityId"}, {"aD", "renderDistanceWeight"}, {"aE", "preventEntitySpawning"},
                    {"aF", "riddenByEntity"}, {"riddenByEntity", "ridingEntity"}, {"aH", "worldObj"},
                    {"aI", "prevPosX"}, {"prevPosX", "prevPosY"}, {"prevPosY", "prevPosZ"},
                    {"prevPosZ", "posX"}, {"posX", "posY"}, {"posY", "posZ"},
                    {"posZ", "motionX"}, {"motionX", "motionY"}, {"motionY", "motionZ"},
                    {"aR", "rotationYaw"}, {"rotationYaw", "rotationPitch"},
                    {"rotationPitch", "prevRotationYaw"}, {"prevRotationYaw", "prevRotationPitch"},
                    {"aV", "boundingBox"}, {"aW", "onGround"}, {"onGround", "isCollidedHorizontally"},
                    {"isCollidedHorizontally", "isCollidedVertically"}, {"isCollidedVertically", "isCollided"},
                    {"isCollided", "beenAttacked"}, {"beenAttacked", "isInWeb"},
                    {"isInWeb", "field_9293_aM"}, {"field_9293_aM", "isDead"},
                    {"be", "yOffset"}, {"yOffset", "width"}, {"width", "height"},
                    {"height", "prevDistanceWalkedModified"}, {"prevDistanceWalkedModified", "distanceWalkedModified"},
                    {"distanceWalkedModified", "fallDistance"},
                    {"bk", "lastTickPosX"}, {"lastTickPosX", "lastTickPosY"}, {"lastTickPosY", "lastTickPosZ"},
                    {"bn", "ySize"}, {"ySize", "stepHeight"}, {"bp", "noClip"}, {"bq", "entityCollisionReduction"},
                    {"br", "rand"}, {"bs", "ticksExisted"}, {"ticksExisted", "fireResistance"},
                    {"fireResistance", "fire"}, {"fire", "maxAir"}, {"bw", "inWater"},
                    {"bx", "field_9306_bj"}, {"field_9306_bj", "air"}, {"bz", "skinUrl"}, {"skinUrl", "cloakUrl"},
                    {"bB", "isImmuneToFire"}, {"bC", "dataWatcher"}, {"bD", "addedToChunk"},
                    {"bE", "chunkCoordX"}, {"chunkCoordX", "chunkCoordY"}, {"chunkCoordY", "chunkCoordZ"},
                    {"chunkCoordZ", "serverPosX"}, {"serverPosX", "serverPosY"}, {"serverPosY", "serverPosZ"},
                    {"bK", "ignoreFrustumCheck"}
            };
            for (String[] pair : shifted) {
                renameInheritedField(classes, map, entity.name, pair[0], pair[1]);
            }
        } else if (hasField(entity, "aA", "D") && !hasField(entity, "lastTickPosZ", "D")) {
            // a1.0.6: RetroMCP shifted several float/double/bool Entity fields
            String[][] a106 = {
                    {"at", "yOffset"}, {"yOffset", "width"}, {"width", "height"},
                    {"height", "prevDistanceWalkedModified"}, {"prevDistanceWalkedModified", "distanceWalkedModified"},
                    {"ay", "canTriggerWalking"}, {"az", "fallDistance"},
                    {"aA", "lastTickPosX"}, {"lastTickPosX", "lastTickPosY"}, {"lastTickPosY", "lastTickPosZ"},
                    {"aD", "ySize"}, {"ySize", "stepHeight"}, {"aF", "noClip"}, {"aQ", "skinUrl"}
            };
            for (String[] pair : a106) {
                renameInheritedField(classes, map, entity.name, pair[0], pair[1]);
            }
        }

        // a1.0.6 (b/d) & b1.7..b1.7.2 (setPosition misnamed addVelocity, real addVelocity left as h)
        if (!hasMethod(entity, "setPosition", "(DDD)V")) {
            MethodNode realSetPos = null;
            MethodNode realAddVel = null;
            MethodNode realMove = null;
            for (MethodNode m : entity.methods) {
                if (!m.desc.equals("(DDD)V")) continue;
                if (calls(m, "net/minecraft/src/AxisAlignedBB", "setBounds")) realSetPos = m;
                else if (calls(m, "net/minecraft/src/AxisAlignedBB", "offset")) realMove = m;
                else if (!m.name.equals("addVelocity") && !m.name.equals("setVelocity") && usesField(m, "motionX")) realAddVel = m;
            }
            if (realSetPos != null) {
                boolean wasAddVel = realSetPos.name.equals("addVelocity");
                renameHierarchyMethod(classes, map, entity.name, realSetPos.name, "(DDD)V", "setPosition");
                if (wasAddVel && realAddVel != null) {
                    renameHierarchyMethod(classes, map, entity.name, realAddVel.name, "(DDD)V", "addVelocity");
                }
            }
            if (realMove != null && !hasMethod(entity, "moveEntity", "(DDD)V")) {
                renameHierarchyMethod(classes, map, entity.name, realMove.name, "(DDD)V", "moveEntity");
            }
        }

        // b1.1*: RetroMCP named skinUrl -> field_20047_bv and cloakUrl -> skinUrl
        ClassNode omp = classes.get("net/minecraft/src/EntityOtherPlayerMP");
        if (omp != null) {
            MethodNode init = findMethod(omp, "<init>", "(Lnet/minecraft/src/World;Ljava/lang/String;)V");
            if (init != null && hasLdcSubstring(init, "/skin/")) {
                boolean sawSkin = false;
                for (AbstractInsnNode insn : init.instructions) {
                    if (insn instanceof LdcInsnNode l && l.cst instanceof String s && s.contains("/skin/")) {
                        sawSkin = true;
                    } else if (sawSkin && insn instanceof FieldInsnNode fi && fi.getOpcode() == Opcodes.PUTFIELD
                            && fi.desc.equals("Ljava/lang/String;")) {
                        if (!fi.name.equals("skinUrl")) {
                            if (declaresField(entity, "skinUrl")) {
                                renameInheritedField(classes, map, entity.name, "skinUrl", "cloakUrl");
                            }
                            renameInheritedField(classes, map, entity.name, fi.name, "skinUrl");
                        }
                        break;
                    }
                }
            }
        }
    }

    private static void normalizeMcpV24(Map<String, ClassNode> classes, Map<String, String> map) {
        String[][] fields = {
                {"net/minecraft/client/Minecraft", "field_9237_P", "timer"},
                {"net/minecraft/client/Minecraft", "field_6314_o", "fontRenderer"},
                {"net/minecraft/client/Minecraft", "field_6308_u", "ingameGUI"},
                {"net/minecraft/client/Minecraft", "field_6305_x", "objectMouseOver"},
                {"net/minecraft/client/Minecraft", "field_6286_O", "glCapabilities"},
                {"net/minecraft/client/Minecraft", "field_9244_k", "mcCanvas"},
                {"net/minecraft/client/Minecraft", "field_6312_q", "loadingScreen"},
                {"net/minecraft/client/Minecraft", "field_6283_R", "ticksRan"},
                {"net/minecraft/client/Minecraft", "field_9241_z", "mcApplet"},
                {"net/minecraft/client/Minecraft", "field_6298_C", "texturePackList"},
                {"net/minecraft/src/Entity", "field_9285_at", "prevPosX"},
                {"net/minecraft/src/Entity", "field_9284_au", "prevPosY"},
                {"net/minecraft/src/Entity", "field_9283_av", "prevPosZ"},
                {"net/minecraft/src/Entity", "field_9298_aH", "onGround"},
                {"net/minecraft/src/Entity", "field_9292_aO", "yOffset"},
                {"net/minecraft/src/Entity", "field_9289_aT", "entityWalks"},
                {"net/minecraft/src/Entity", "field_9288_aU", "fallDistance"},
                {"net/minecraft/src/Entity", "field_9312_bd", "rand"},
                {"net/minecraft/src/Entity", "field_9309_bg", "fire"},
                {"net/minecraft/src/Entity", "field_9305_bk", "air"},
                {"net/minecraft/src/Entity", "field_9304_bm", "isImmuneToFire"},
                {"net/minecraft/src/EntityLiving", "field_9357_z", "texture"},
                {"net/minecraft/src/EntityLiving", "field_9341_H", "prevSwingProgress"},
                {"net/minecraft/src/EntityLiving", "field_9339_I", "swingProgress"},
                {"net/minecraft/src/EntityLiving", "field_9337_J", "health"},
                {"net/minecraft/src/EntityLiving", "field_9330_P", "attackTime"},
                {"net/minecraft/src/EntityLiving", "field_9336_ak", "isJumping"},
                {"net/minecraft/src/EntityPlayer", "field_9370_g", "score"},
                {"net/minecraft/src/EntityPlayer", "field_4128_n", "fishEntity"},
                {"net/minecraft/src/GuiIngame", "field_9422_g", "mc"},
                {"net/minecraft/src/GuiIngame", "field_9423_f", "rand"},
                {"net/minecraft/src/GuiIngame", "field_9421_h", "updateCounter"},
                {"net/minecraft/src/Block", "allowsAttachmentLookup", "opaqueCubeLookup"},
                {"net/minecraft/src/Block", "field_4063_aU", "blockIce"},
                {"net/minecraft/src/WorldRenderer", "field_1762_b", "chunksUpdated"},
                {"net/minecraft/src/RenderBlocks", "field_1772_a", "blockAccess"},
                {"net/minecraft/src/RenderBlocks", "field_1771_b", "overrideBlockTexture"},
                {"net/minecraft/src/RenderBlocks", "field_1774_c", "flipTexture"},
                {"net/minecraft/src/RenderBlocks", "field_1773_d", "renderAllFaces"},
                {"net/minecraft/src/GameSettings", "field_6524_j", "skin"},
                {"net/minecraft/src/MathHelper", "field_1653_a", "SIN_TABLE"},
                {"net/minecraft/src/World", "field_9434_r", "worldAccesses"}
        };
        for (String[] f : fields) {
            renameInheritedField(classes, map, f[0], f[1], f[2]);
        }

        String[][] methods = {
                {"net/minecraft/client/Minecraft", "func_6243_a", "(I)V", "clickMouse"},
                {"net/minecraft/client/Minecraft", "func_6265_t", "()V", "clickMiddleMouseButton"},
                {"net/minecraft/src/Entity", "func_359_G", "()Z", "handleLavaMovement"},
                {"net/minecraft/src/Entity", "attackEntity", "(Lnet/minecraft/src/Entity;I)Z", "canAttackEntity"},
                {"net/minecraft/src/Entity", "func_6376_z", "()Ljava/lang/String;", "getEntityTexture"},
                {"net/minecraft/src/Entity", "func_354_B", "()Z", "isEntityAlive"},
                {"net/minecraft/src/EntityLiving", "func_425_j", "()V", "onLivingUpdate"},
                {"net/minecraft/src/EntityLiving", "func_419_b", "(Lnet/minecraft/src/Entity;)V", "onDeath"},
                {"net/minecraft/src/EntityLiving", "func_4044_a", "(I)V", "damageEntity"},
                {"net/minecraft/src/EntityPlayer", "func_449_a", "(Lnet/minecraft/src/ItemStack;)V", "dropPlayerItem"},
                {"net/minecraft/src/EntityPlayer", "func_444_a", "(Lnet/minecraft/src/ItemStack;Z)V", "dropPlayerItemWithRandomChoice"},
                {"net/minecraft/src/EntityPlayer", "displayGUICrafting", "()V", "displayWorkbenchGUI"},
                {"net/minecraft/src/EntityPlayerSP", "func_6419_n", "()I", "getPlayerArmorValue"},
                {"net/minecraft/src/EntityPlayerSP", "func_461_a", "(Ljava/lang/String;)V", "sendChatMessage"},
                {"net/minecraft/src/EntityPlayerSP", "func_9372_a_", "(I)V", "setHealth"},
                {"net/minecraft/src/GuiIngame", "func_552_a", "(Ljava/lang/String;)V", "addChatMessage"},
                {"net/minecraft/src/ScaledResolution", "func_903_a", "()I", "getScaledWidth"},
                {"net/minecraft/src/ScaledResolution", "func_902_b", "()I", "getScaledHeight"},
                {"net/minecraft/src/Block", "isSideInsideCoordinate", "(Lnet/minecraft/src/IBlockAccess;IIII)Z", "shouldSideBeRendered"},
                {"net/minecraft/src/Block", "allowsAttachment", "()Z", "isOpaqueCube"},
                {"net/minecraft/src/Block", "func_220_a_", "(Lnet/minecraft/src/World;IIII)V", "harvestBlock"},
                {"net/minecraft/src/BlockLeaves", "func_310_a", "(Z)V", "setGraphicsLevel"},
                {"net/minecraft/src/Tessellator", "func_982_a", "()V", "draw"},
                {"net/minecraft/src/Tessellator", "func_985_d", "()V", "reset"},
                {"net/minecraft/src/Tessellator", "func_977_b", "()V", "startDrawingQuads"},
                {"net/minecraft/src/Tessellator", "func_992_a", "(I)V", "startDrawing"},
                {"net/minecraft/src/Tessellator", "func_981_a", "(DD)V", "setTextureUV"},
                {"net/minecraft/src/Tessellator", "func_987_a", "(FFF)V", "setColorOpaque_F"},
                {"net/minecraft/src/Tessellator", "func_986_a", "(FFFF)V", "setColorRGBA_F"},
                {"net/minecraft/src/Tessellator", "func_979_a", "(III)V", "setColorOpaque"},
                {"net/minecraft/src/Tessellator", "func_978_a", "(IIII)V", "setColorRGBA"},
                {"net/minecraft/src/Tessellator", "func_983_a", "(DDDDD)V", "addVertexWithUV"},
                {"net/minecraft/src/Tessellator", "func_991_a", "(DDD)V", "addVertex"},
                {"net/minecraft/src/Tessellator", "func_990_b", "(I)V", "setColorOpaque_I"},
                {"net/minecraft/src/Tessellator", "func_6513_a", "(II)V", "setColorRGBA_I"},
                {"net/minecraft/src/Tessellator", "func_989_c", "()V", "disableColor"},
                {"net/minecraft/src/Tessellator", "func_980_b", "(FFF)V", "setNormal"},
                {"net/minecraft/src/Tessellator", "func_984_b", "(DDD)V", "setTranslationD"},
                {"net/minecraft/src/Tessellator", "func_988_c", "(FFF)V", "setTranslationF"},
                {"net/minecraft/src/RenderBlocks", "func_1223_a", "(Lnet/minecraft/src/Block;IIII)V", "renderBlockUsingTexture"},
                {"net/minecraft/src/RenderBlocks", "func_1234_a", "(Lnet/minecraft/src/Block;III)Z", "renderBlockByRenderType"},
                {"net/minecraft/src/RenderBlocks", "func_1226_h", "(Lnet/minecraft/src/Block;III)Z", "renderBlockReed"},
                {"net/minecraft/src/RenderBlocks", "func_4152_a", "(Lnet/minecraft/src/Block;IIIFFF)Z", "renderStandardBlockWithColorMultiplier"},
                {"net/minecraft/src/RenderBlocks", "func_1244_a", "(Lnet/minecraft/src/Block;DDDI)V", "renderBottomFace"},
                {"net/minecraft/src/RenderBlocks", "func_1217_b", "(Lnet/minecraft/src/Block;DDDI)V", "renderTopFace"},
                {"net/minecraft/src/RenderBlocks", "func_1220_c", "(Lnet/minecraft/src/Block;DDDI)V", "renderEastFace"},
                {"net/minecraft/src/RenderBlocks", "func_1225_d", "(Lnet/minecraft/src/Block;DDDI)V", "renderWestFace"},
                {"net/minecraft/src/RenderBlocks", "func_1231_e", "(Lnet/minecraft/src/Block;DDDI)V", "renderNorthFace"},
                {"net/minecraft/src/RenderBlocks", "func_1236_f", "(Lnet/minecraft/src/Block;DDDI)V", "renderSouthFace"},
                {"net/minecraft/src/WorldRenderer", "func_1198_a", "()V", "updateRenderer"},
                {"net/minecraft/src/GameSettings", "func_1043_a", "(I)Ljava/lang/String;", "getKeyBinding"},
                {"net/minecraft/src/GameSettings", "func_1042_a", "(II)V", "setKeyBinding"},
                {"net/minecraft/src/GameSettings", "func_1048_a", "(IF)V", "setOptionFloatValue"},
                {"net/minecraft/src/GameSettings", "func_1045_b", "(II)V", "setOptionValue"},
                {"net/minecraft/src/GameSettings", "func_1049_c", "(I)F", "getOptionFloatValue"},
                {"net/minecraft/src/GameSettings", "func_1047_d", "(I)Ljava/lang/String;", "getOptionDisplayString"},
                {"net/minecraft/src/GameSettings", "func_6519_a", "()V", "loadOptions"},
                {"net/minecraft/src/GameSettings", "func_1041_b", "()V", "saveOptions"},
                {"net/minecraft/src/NBTTagCompound", "func_762_a", "(Ljava/lang/String;Lnet/minecraft/src/NBTBase;)V", "setTag"},
                {"net/minecraft/src/NBTTagCompound", "func_761_a", "(Ljava/lang/String;B)V", "setByte"},
                {"net/minecraft/src/NBTTagCompound", "func_749_a", "(Ljava/lang/String;S)V", "setShort"},
                {"net/minecraft/src/NBTTagCompound", "func_758_a", "(Ljava/lang/String;I)V", "setInteger"},
                {"net/minecraft/src/NBTTagCompound", "func_750_a", "(Ljava/lang/String;J)V", "setLong"},
                {"net/minecraft/src/NBTTagCompound", "func_744_a", "(Ljava/lang/String;F)V", "setFloat"},
                {"net/minecraft/src/NBTTagCompound", "func_765_a", "(Ljava/lang/String;D)V", "setDouble"},
                {"net/minecraft/src/NBTTagCompound", "func_754_a", "(Ljava/lang/String;Ljava/lang/String;)V", "setString"},
                {"net/minecraft/src/NBTTagCompound", "func_747_a", "(Ljava/lang/String;[B)V", "setByteArray"},
                {"net/minecraft/src/NBTTagCompound", "func_763_a", "(Ljava/lang/String;Lnet/minecraft/src/NBTTagCompound;)V", "setCompoundTag"},
                {"net/minecraft/src/NBTTagCompound", "func_748_a", "(Ljava/lang/String;Z)V", "setBoolean"},
                {"net/minecraft/src/NBTTagCompound", "func_751_b", "(Ljava/lang/String;)Z", "hasKey"},
                {"net/minecraft/src/NBTTagCompound", "func_746_c", "(Ljava/lang/String;)B", "getByte"},
                {"net/minecraft/src/NBTTagCompound", "func_745_d", "(Ljava/lang/String;)S", "getShort"},
                {"net/minecraft/src/NBTTagCompound", "func_756_e", "(Ljava/lang/String;)I", "getInteger"},
                {"net/minecraft/src/NBTTagCompound", "func_764_f", "(Ljava/lang/String;)J", "getLong"},
                {"net/minecraft/src/NBTTagCompound", "func_752_g", "(Ljava/lang/String;)F", "getFloat"},
                {"net/minecraft/src/NBTTagCompound", "func_757_h", "(Ljava/lang/String;)D", "getDouble"},
                {"net/minecraft/src/NBTTagCompound", "func_755_i", "(Ljava/lang/String;)Ljava/lang/String;", "getString"},
                {"net/minecraft/src/NBTTagCompound", "func_759_j", "(Ljava/lang/String;)[B", "getByteArray"},
                {"net/minecraft/src/NBTTagCompound", "func_743_k", "(Ljava/lang/String;)Lnet/minecraft/src/NBTTagCompound;", "getCompoundTag"},
                {"net/minecraft/src/NBTTagCompound", "func_753_l", "(Ljava/lang/String;)Lnet/minecraft/src/NBTTagList;", "getTagList"},
                {"net/minecraft/src/NBTTagCompound", "func_760_m", "(Ljava/lang/String;)Z", "getBoolean"},
                {"net/minecraft/src/Chunk", "func_999_b", "(II)I", "getHeightValue"},
                {"net/minecraft/src/Chunk", "func_1008_a", "(III)I", "getBlockID"},
                {"net/minecraft/src/Chunk", "func_1010_a", "(IIIII)Z", "setBlockIDWithMetadata"},
                {"net/minecraft/src/Chunk", "func_1022_a", "(IIII)Z", "setBlockID"},
                {"net/minecraft/src/Chunk", "func_1021_b", "(III)I", "getBlockMetadata"},
                {"net/minecraft/src/Chunk", "func_1009_b", "(IIII)V", "setBlockMetadata"},
                {"net/minecraft/src/Chunk", "func_1025_a", "(Lnet/minecraft/src/EnumSkyBlock;III)I", "getSavedLightValue"},
                {"net/minecraft/src/Chunk", "func_1011_a", "(Lnet/minecraft/src/EnumSkyBlock;IIII)V", "setLightValue"},
                {"net/minecraft/src/Chunk", "func_1019_c", "(IIII)I", "getBlockLightValue"},
                {"net/minecraft/src/Chunk", "func_1007_c", "(III)Z", "canBlockSeeTheSky"},
                {"net/minecraft/src/InventoryPlayer", "func_504_a", "(Lnet/minecraft/src/ItemStack;)Z", "addItemStackToInventory"},
                {"net/minecraft/src/PlayerController", "func_6469_d", "()Z", "shouldDrawHUD"},
                {"net/minecraft/src/RenderEngine", "func_1070_a", "(Ljava/lang/String;)I", "getTexture"},
                {"net/minecraft/src/RenderEngine", "func_1066_a", "(Lnet/minecraft/src/TextureFX;)V", "registerTextureFX"},
                {"net/minecraft/src/RenderEngine", "func_1076_b", "(I)V", "bindTexture"},
                {"net/minecraft/src/GuiButton", "func_561_a", "(Lnet/minecraft/client/Minecraft;II)V", "drawButton"},
                {"net/minecraft/src/GuiButton", "func_560_b", "(Lnet/minecraft/client/Minecraft;II)V", "mouseDragged"},
                {"net/minecraft/src/GuiButton", "func_559_a", "(II)V", "mouseReleased"},
                {"net/minecraft/src/GuiButton", "func_562_c", "(Lnet/minecraft/client/Minecraft;II)Z", "mousePressed"},
                {"net/minecraft/src/MathHelper", "convertToBlockCoord_float", "(F)I", "floor_float"},
                {"net/minecraft/src/MathHelper", "convertToBlockCoord_double", "(D)I", "floor_double"},
                {"net/minecraft/src/EntityRenderer", "func_4134_c", "(F)V", "renderWorld"},
                {"net/minecraft/src/ItemStack", "func_1085_a", "(I)Lnet/minecraft/src/ItemStack;", "splitStack"},
                {"net/minecraft/src/ItemStack", "func_1091_a", "()Lnet/minecraft/src/Item;", "getItem"},
                {"net/minecraft/src/ItemStack", "func_1088_b", "()I", "getIconIndex"},
                {"net/minecraft/src/ItemStack", "func_1098_a", "(Lnet/minecraft/src/Block;)F", "getStrVsBlock"},
                {"net/minecraft/src/IWorldAccess", "func_940_a", "(Ljava/lang/String;DDDFF)V", "playSound"},
                {"net/minecraft/src/IWorldAccess", "func_939_a", "(Ljava/lang/String;DDDDDD)V", "spawnParticle"},
                {"net/minecraft/src/IWorldAccess", "func_933_a", "(Ljava/lang/String;III)V", "playRecord"}
        };
        for (String[] m : methods) {
            renameHierarchyMethod(classes, map, m[0], m[1], m[2], m[3]);
        }
    }

    private static void renameInheritedField(Map<String, ClassNode> classes, Map<String, String> map, String owner, String oldField, String canonical) {
        ClassNode root = classes.get(owner);
        if (root == null || !declaresField(root, oldField)) return;
        for (ClassNode cn : classes.values()) {
            if (cn.name.equals(owner) || (isSubclass(classes, cn, owner) && !declaresField(cn, oldField) && !declaresField(cn, canonical))) {
                map.put(cn.name + "." + oldField, canonical);
            }
        }
    }

    private static void renameHierarchyMethod(Map<String, ClassNode> classes, Map<String, String> map, String base, String oldName, String desc, String canonical) {
        ClassNode root = classes.get(base);
        if (root == null || !hasMethod(root, oldName, desc)) return;
        for (ClassNode cn : classes.values()) {
            if ((cn.name.equals(base) || isSubclass(classes, cn, base) || implementsInterface(classes, cn, base))
                    && !hasMethod(cn, canonical, desc)) {
                map.put(cn.name + "." + oldName + desc, canonical);
            }
        }
    }

    private static boolean declaresField(ClassNode cn, String name) {
        for (FieldNode f : cn.fields) if (f.name.equals(name)) return true;
        return false;
    }

    private static boolean isSubclass(Map<String, ClassNode> classes, ClassNode cn, String base) {
        String cur = cn.superName;
        while (cur != null) {
            if (cur.equals(base)) return true;
            ClassNode parent = classes.get(cur);
            cur = parent != null ? parent.superName : null;
        }
        return false;
    }

    private static boolean implementsInterface(Map<String, ClassNode> classes, ClassNode cn, String iface) {
        ClassNode cur = cn;
        while (cur != null) {
            if (cur.interfaces != null && cur.interfaces.contains(iface)) return true;
            cur = classes.get(cur.superName);
        }
        return false;
    }

    private static void aliasMethod(ClassNode cn, Map<String, String> map, String canonical, String desc, Predicate<MethodNode> pred) {
        if (hasMethod(cn, canonical, desc)) return;
        for (MethodNode m : cn.methods) {
            if (m.name.startsWith("<") || !m.desc.equals(desc)) continue;
            if (pred.test(m)) {
                map.put(cn.name + "." + m.name + m.desc, canonical);
                return;
            }
        }
    }

    private static void aliasField(ClassNode cn, Map<String, String> map, String canonical, String desc) {
        if (hasField(cn, canonical, desc)) return;
        for (FieldNode f : cn.fields) {
            if ((f.access & Opcodes.ACC_STATIC) == 0 && f.desc.equals(desc)) {
                map.put(cn.name + "." + f.name, canonical);
                return;
            }
        }
    }

    private static boolean hasMethod(ClassNode cn, String name, String desc) {
        return findMethod(cn, name, desc) != null;
    }

    private static MethodNode findMethod(ClassNode cn, String name, String desc) {
        for (MethodNode m : cn.methods) if (m.name.equals(name) && m.desc.equals(desc)) return m;
        return null;
    }

    private static MethodNode findMethodByDesc(ClassNode cn, String desc) {
        for (MethodNode m : cn.methods) if (!m.name.startsWith("<") && m.desc.equals(desc)) return m;
        return null;
    }

    private static FieldNode findFieldByDesc(ClassNode cn, String desc) {
        for (FieldNode f : cn.fields) if ((f.access & Opcodes.ACC_STATIC) == 0 && f.desc.equals(desc)) return f;
        return null;
    }

    private static boolean hasField(ClassNode cn, String name, String desc) {
        for (FieldNode f : cn.fields) if (f.name.equals(name) && f.desc.equals(desc)) return true;
        return false;
    }

    private static boolean calls(MethodNode m, String owner, String name) {
        for (AbstractInsnNode i : m.instructions) {
            if (i instanceof MethodInsnNode mi && mi.owner.equals(owner) && mi.name.equals(name)) return true;
        }
        return false;
    }

    private static boolean callsAny(MethodNode m, String name) {
        for (AbstractInsnNode i : m.instructions) {
            if (i instanceof MethodInsnNode mi && mi.name.equals(name)) return true;
        }
        return false;
    }

    private static boolean usesField(MethodNode m, String name) {
        for (AbstractInsnNode i : m.instructions) {
            if (i instanceof FieldInsnNode fi && fi.name.equals(name)) return true;
        }
        return false;
    }

    private static boolean hasFieldInsn(MethodNode m, int opcode, String owner) {
        for (AbstractInsnNode i : m.instructions) {
            if (i instanceof FieldInsnNode fi && fi.getOpcode() == opcode && fi.owner.equals(owner)) return true;
        }
        return false;
    }

    private static boolean hasLdc(MethodNode m, String val) {
        for (AbstractInsnNode i : m.instructions) {
            if (i instanceof LdcInsnNode l && val.equals(l.cst)) return true;
        }
        return false;
    }

    private static boolean hasLdcSubstring(MethodNode m, String sub) {
        for (AbstractInsnNode i : m.instructions) {
            if (i instanceof LdcInsnNode l && l.cst instanceof String s && s.contains(sub)) return true;
        }
        return false;
    }

    private static boolean hasIntConst(MethodNode m, int val) {
        for (AbstractInsnNode i : m.instructions) {
            int op = i.getOpcode();
            if (op >= Opcodes.ICONST_M1 && op <= Opcodes.ICONST_5 && (op - Opcodes.ICONST_0) == val) return true;
            if (i instanceof IntInsnNode ii && ii.operand == val) return true;
            if (i instanceof LdcInsnNode l && l.cst instanceof Integer iv && iv == val) return true;
        }
        return false;
    }

    private static boolean hasFloatConst(MethodNode m, float val) {
        for (AbstractInsnNode i : m.instructions) {
            if (i instanceof LdcInsnNode l && l.cst instanceof Float fv && Math.abs(fv - val) < 1e-4f) return true;
        }
        return false;
    }

    private static int countsOpcode(MethodNode m, int opcode) {
        int c = 0;
        for (AbstractInsnNode i : m.instructions) if (i.getOpcode() == opcode) c++;
        return c;
    }

    private static boolean hasNewArray(MethodNode m, String elemType) {
        for (AbstractInsnNode i : m.instructions) {
            if (i instanceof TypeInsnNode t && t.getOpcode() == Opcodes.ANEWARRAY && t.desc.equals(elemType)) return true;
        }
        return false;
    }
}
