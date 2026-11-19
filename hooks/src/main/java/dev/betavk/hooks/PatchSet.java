package dev.betavk.hooks;

import dev.betavk.agent.PatchRegistry;

/** All patches to the game, by class. Names only: nothing here loads a Minecraft class. */
public final class PatchSet implements dev.betavk.agent.PatchSet {
    private static final String SRC = "net/minecraft/src/";

    @Override public void register(PatchRegistry r) {
        String mcc = "net/minecraft/client/Minecraft", sdisp = "dev/betavk/platform/Display", dm = "()Ldev/betavk/platform/DisplayMode;";
        String loop = "dev/betavk/hooks/MainLoop", zoom = "dev/betavk/hooks/Zoom", set = "dev/betavk/hooks/Settings";

        // start-up, window, directories
        String createDesc = PatchRegistry.hasClass(SRC + "PlayerList") ? "(Ldev/betavk/compat/PixelFormat;)V" : "()V";
        r.on(mcc)
                .atStart("startGame", "()V", loop, "beforeStart")
                .afterCall("startGame", "()V", sdisp, "create", createDesc, loop, "afterDisplayCreate")
                .redirectCall("startGame", "()V", sdisp, "getDisplayMode", dm, true, loop, "displayMode")
                .redirectCall("toggleFullscreen", "()V", sdisp, "getDisplayMode", dm, true, loop, "displayMode")
                .redirectCall("?toggleFullscreen", "()V", "java/lang/Thread", "sleep", "(J)V", true, loop, "noopSleep")
                .redirectCall("?toggleFullscreen", "()V", mcc, "setIngameFocus", "()V", false, loop, "fullscreenFocus")
                .atEnd("toggleFullscreen", "()V", loop, "fullscreenFocus")
                .atStartReturnIfNotNull("?getAppDir", "(Ljava/lang/String;)Ljava/io/File;", loop, "appDir")
                .replaceBody("?func_28001_B", "()V", loop, "paidCheck")
                // main loop
                .redirectCall("?run", "()V", mcc, "toggleFullscreen", "()V", false, loop, "focusLostToggle")
                .afterCall("run", "()V", "java/lang/Thread", "yield", "()V", loop, "limitFps")
                .afterCall("run", "()V", sdisp, "update", "()V", loop, "afterFrame")
                // input
                .redirectCall("runTick", "()V", "dev/betavk/platform/Keyboard", "next", "()Z", true, loop, "nextKey")
                .redirectCall("runTick", "()V", "dev/betavk/platform/Mouse", "getEventDWheel", "()I", true, loop, "wheel");
        var mhPatch = r.on(SRC + "MouseHelper").replaceBody("ungrabMouseCursor", "()V", loop, "ungrab");
        if (hasBytecodeMarker(SRC + "MouseHelper.class", "java/awt/MouseInfo")) {
            mhPatch.replaceBody("grabMouseCursor", "()V", loop, "grab")
                    .replaceBody("mouseXYChange", "()V", loop, "mouseDelta");
        }

        // field of view, zoom, hand, render distance
        String lodHooks = "dev/betavk/lod/LodHooks";
        r.on(SRC + "EntityRenderer")
                .onReturn("?getFOVModifier", "(F)F", zoom, "fov")
                .onReturn("?getFOVModifier", "(FZ)F", zoom, "fov")
                .afterFieldSet("setupCameraTransform", "(FI)V", SRC + "EntityRenderer", "farPlaneDistance", loop, "applyFarPlane")
                .atEnd("?setupFog", "(IF)V", loop, "applyFog")
                .atEnd("?setupFog", "(I)V", loop, "applyFog1")
                .atEnd("?func_4140_a", "(I)V", loop, "applyFog1")
                .redirectCall("?renderWorld", "(FJ)V", SRC + "RenderGlobal", "sortAndRender", "(L" + SRC + "EntityLiving;ID)I", false, lodHooks, "sortAndRender")
                .redirectCall("?renderWorld", "(FJ)V", SRC + "RenderGlobal", "renderAllRenderLists", "(ID)V", false, lodHooks, "renderAllRenderLists")
                .redirectCall("?renderWorld", "(F)V", SRC + "RenderGlobal", "sortAndRender", "(L" + SRC + "EntityLiving;ID)I", false, lodHooks, "sortAndRender")
                .redirectCall("?renderWorld", "(F)V", SRC + "RenderGlobal", "sortAndRender", "(L" + SRC + "EntityPlayer;ID)I", false, lodHooks, "sortAndRenderPlayer")
                .redirectCall("?renderWorld", "(F)V", SRC + "RenderGlobal", "renderAllRenderLists", "(ID)V", false, lodHooks, "renderAllRenderLists")
                .atStart("updateCameraAndRender", "(F)V", zoom, "update")
                .replaceFloat("?updateCameraAndRender", "(F)V", 8.0F, 1, zoom, "turnScale")
                .atStart("?func_4135_b", "(FI)V", zoom, "hand");

        // HUD
        r.on(SRC + "GuiIngame")
                .atStart("updateTick", "()V", "dev/betavk/hooks/Hud", "tick")
                .atEnd("renderGameOverlay", "(FZII)V", "dev/betavk/hooks/Hud", "draw");

        // rendering core: chunk meshing on worker threads, one tessellator per thread, vertex packing fixes
        String tess = "dev/betavk/hooks/mesh/TessHooks", am = "dev/betavk/hooks/mesh/AsyncMesh", mh = "dev/betavk/hooks/mesh/Hooks";
        boolean beta18Plus = PatchRegistry.hasClass(SRC + "WorldSettings");
        if (!beta18Plus) {
            r.everywhereRedirectStaticRead(SRC + "Tessellator", "instance", tess, "instance");
            r.on(SRC + "Tessellator")
                    .replaceBody("addVertex", "(DDD)V", tess, "addVertex")
                    .replaceBody("setNormal", "(FFF)V", tess, "setNormal");
        }
        var rgPatch = r.on(SRC + "RenderGlobal")
                .afterFieldSet("loadRenderers", "()V", SRC + "RenderGlobal", "renderChunksDeep", loop, "applyLoadRenderersSize")
                .atEnd("loadRenderers", "()V", "dev/betavk/hooks/mesh/CaveCull", "onLoadRenderers")
                .replaceBody("clipRenderersByFrustrum", "(L" + SRC + "ICamera;F)V", "dev/betavk/hooks/mesh/CaveCull", "clipAndCull")
                .replaceBody("renderSortedRenderers", "(IIID)I", "dev/betavk/hooks/mesh/CaveCull", "renderSortedRenderers")
                .atEnd("markBlockAndNeighborsNeedsUpdate", "(III)V", lodHooks, "onBlockMarked");
        if (!beta18Plus) {
            rgPatch.redirectCall("?sortAndRender", "(L" + SRC + "EntityLiving;ID)I", "java/util/List", "contains", "(Ljava/lang/Object;)Z", false, am, "alreadyQueued")
                    .redirectCall("?sortAndRender", "(L" + SRC + "EntityPlayer;ID)I", "java/util/List", "contains", "(Ljava/lang/Object;)Z", false, am, "alreadyQueued")
                    .replaceBody("?updateRenderers", "(L" + SRC + "EntityLiving;Z)Z", am, "updateRenderers")
                    .replaceBody("?updateRenderers", "(L" + SRC + "EntityPlayer;Z)Z", am, "updateRenderersPlayer");
            r.on(SRC + "WorldRenderer").atStart("setDontDraw", "()V", am, "retarget");
        } else {
            rgPatch.replaceInt("updateRenderers", "(L" + SRC + "EntityLiving;Z)Z", 2, 1, am, "farBudget");
        }
        r.on(SRC + "ClippingHelper")
                .replaceBody("isBoxInFrustum", "(DDDDDD)Z", "dev/betavk/hooks/mesh/CaveCull", "fastBoxInFrustum");
        r.on(SRC + "WorldRenderer")
                .atEnd("setPosition", "(III)V", "dev/betavk/hooks/mesh/CaveCull", "onSetPosition")
                .atEnd("updateRenderer", "()V", "dev/betavk/hooks/mesh/CaveCull", "onSyncRebuild");
        var ccPatch = r.on(SRC + "ChunkCache")
                .atStartCancelable("getBlockTileEntity", "(III)L" + SRC + "TileEntity;", mh, "noTileEntities")
                .replaceBody("isBlockOpaqueCube", "(III)Z", mh, "isOpaque")
                .replaceBody("?getBrightness", "(IIII)F", mh, "getBrightness")
                .replaceBody("getLightBrightness", "(III)F", mh, "getLightBrightness");
        if (PatchRegistry.hasClass(SRC + "WorldChunkManager")) {
            ccPatch.atStartReturnIfNotNull("getWorldChunkManager", "()L" + SRC + "WorldChunkManager;", mh, "chunkManager");
        }
        r.on(SRC + "Chunk").redirectStaticWrite("?getBlockLightValue", "(IIII)I", SRC + "Chunk", "isLit", mh, "markLit");
        var rbPatch = r.on(SRC + "RenderBlocks")
                .redirectStaticRead("?renderStandardBlockWithColorMultiplier", "(L" + SRC + "Block;IIIFFF)Z", SRC + "Block", "grass", mh, "grass")
                .atStartCancelable("renderEastFace", "(L" + SRC + "Block;DDDI)V", mh, "betterEastFace")
                .atStartCancelable("renderWestFace", "(L" + SRC + "Block;DDDI)V", mh, "betterWestFace")
                .atStartCancelable("renderNorthFace", "(L" + SRC + "Block;DDDI)V", mh, "betterNorthFace")
                .atStartCancelable("renderSouthFace", "(L" + SRC + "Block;DDDI)V", mh, "betterSouthFace");
        if (PatchRegistry.hasClass(SRC + "BlockTallGrass")) {
            rbPatch.redirectStaticRead("renderBlockReed", "(L" + SRC + "Block;III)Z", SRC + "Block", "tallGrass", mh, "tallGrass");
        }
        r.on(SRC + "BlockLeavesBase")
                .replaceBody("shouldSideBeRendered", "(L" + SRC + "IBlockAccess;IIII)Z", mh, "shouldLeafSideBeRendered");

        // world: loaded-chunk map, deferred population, terrain prefetch, asynchronous saves
        String pop = "dev/betavk/hooks/world/Populate", rw = "dev/betavk/hooks/world/RegionWriter", gp = "dev/betavk/hooks/world/GenPool", wh = "dev/betavk/hooks/world/WorldHooks", tf = "dev/betavk/hooks/world/TerrainFast";
        r.on(SRC + "MetadataChunkBlock").replaceBody("func_4127_a", "(L" + SRC + "World;)V", tf, "lightUpdateFast");
        r.on(SRC + "WorldGenMinable").replaceBody("generate", "(L" + SRC + "World;Ljava/util/Random;III)Z", tf, "generateMinableFast");
        r.on(SRC + "NoiseGeneratorPerlin").replaceBody("?func_805_a", "([DDDDIIIDDDD)V", tf, "perlinNoiseFast");
        if (PatchRegistry.hasClass(SRC + "ChunkProvider") && !PatchRegistry.hasClass(SRC + "PlayerList")) {
            r.on(SRC + "ChunkProvider")
                    .replaceNew("<init>", "(L" + SRC + "World;L" + SRC + "IChunkLoader;L" + SRC + "IChunkProvider;)V", "java/util/HashMap", "dev/betavk/hooks/world/IntKeyMap", 1)
                    .atStartReturnIfNotNull("provideChunk", "(II)L" + SRC + "Chunk;", wh, "provideChunkFast")
                    .replaceBody("chunkExists", "(II)Z", wh, "chunkExistsFast")
                    .redirectCall("prepareChunk", "(II)L" + SRC + "Chunk;", SRC + "ChunkProvider", "populate", "(L" + SRC + "IChunkProvider;II)V", false, pop, "request");
        }
        if (PatchRegistry.hasClass(SRC + "ChunkProviderLoadOrGenerate")) {
            r.on(SRC + "ChunkProviderLoadOrGenerate")
                    .atStartReturnIfNotNull("provideChunk", "(II)L" + SRC + "Chunk;", wh, "provideLoadOrGenFast")
                    .onReturn("provideChunk", "(II)L" + SRC + "Chunk;", wh, "cacheLoadOrGenChunk")
                    .redirectCall("provideChunk", "(II)L" + SRC + "Chunk;", SRC + "Chunk", "onChunkUnload", "()V", false, wh, "keepLoadOrGenChunk")
                    .redirectCall("provideChunk", "(II)L" + SRC + "Chunk;", SRC + "ChunkProviderLoadOrGenerate", "saveChunk", "(L" + SRC + "Chunk;)V", false, wh, "saveLoadOrGenSlotChunk")
                    .redirectCall("provideChunk", "(II)L" + SRC + "Chunk;", SRC + "ChunkProviderLoadOrGenerate", "saveExtraChunkData", "(L" + SRC + "Chunk;)V", false, wh, "saveLoadOrGenSlotExtra")
                    .replaceBody("chunkExists", "(II)Z", wh, "loadOrGenExistsFast")
                    .replaceBody("?canChunkExist", "(II)Z", wh, "allowChunk")
                    .replaceBody("?func_21111_d", "(II)Z", wh, "allowChunk")
                    .atStart("saveChunks", "(ZL" + SRC + "IProgressUpdate;)Z", wh, "flushLoadOrGenDirty");
        }
        if (PatchRegistry.hasClass(SRC + "McRegionChunkLoader")) {
            r.on(SRC + "McRegionChunkLoader")
                    .atStart("loadChunk", "(L" + SRC + "World;II)L" + SRC + "Chunk;", rw, "beforeLoad")
                    .replaceBody("saveChunk", "(L" + SRC + "World;L" + SRC + "Chunk;)V", rw, "save")
                    .atStart("saveExtraData", "()V", rw, "endOfSave")
                    .atStart("func_814_a", "()V", rw, "endOfSave");
        }
        r.on(SRC + "ChunkProviderGenerate")
                .atEnd("?<init>", "(L" + SRC + "World;J)V", gp, "registerMain")
                .atStartReturnIfNotNull("provideChunk", "(II)L" + SRC + "Chunk;", gp, "take");
        r.on(SRC + "World").replaceBody("updatingLighting", "()Z", wh, "updatingLighting");
        r.on(mcc)
                .atStart("runTick", "()V", wh, "tick")
                .atStart("changeWorld2", "(L" + SRC + "World;Ljava/lang/String;)V", wh, "newWorld")
                .afterFieldSet("?changeWorld", "(L" + SRC + "World;Ljava/lang/String;L" + SRC + "EntityPlayer;)V", mcc, "renderViewEntity", wh, "worldReady");

        // small corrections
        String sf = "dev/betavk/hooks/SmallFixes";
        r.on(SRC + "NextTickListEntry").replaceBody("hashCode", "()I", sf, "tickHash");
        r.on(SRC + "AxisAlignedBB").atStartReturnIfNotNull("getBoundingBoxFromPool", "(DDDDDD)L" + SRC + "AxisAlignedBB;", sf, "pooledBox");
        r.on(SRC + "Vec3D").atStartReturnIfNotNull("createVector", "(DDD)L" + SRC + "Vec3D;", sf, "pooledVector");
        for (String m : new String[]{"shouldSideBeRendered", "idDropped", "onBlockAdded"}) {
            String d = m.equals("shouldSideBeRendered") ? "(L" + SRC + "IBlockAccess;IIII)Z" : m.equals("idDropped") ? "(ILjava/util/Random;)I" : "(L" + SRC + "World;III)V";
            r.on(SRC + "BlockStep").redirectStaticRead(m, d, SRC + "Block", "stairSingle", sf, "stairSingle");
        }
        r.on(SRC + "NetworkMasterThread")
                .redirectCall("run", "()V", "java/lang/Thread", "stop", "()V", false, sf, "stopThread");
        r.on(SRC + "GuiScreen")
                .atStart("setWorldAndResolution", "(Lnet/minecraft/client/Minecraft;II)V", sf, "clearControls")
                .replaceBody("?getClipboardString", "()Ljava/lang/String;", sf, "clipboard");
        if (PatchRegistry.hasClass(SRC + "EntityOtherPlayerMP")) {
            r.on(SRC + "EntityOtherPlayerMP").afterFieldSet("<init>", "(L" + SRC + "World;Ljava/lang/String;)V", SRC + "EntityOtherPlayerMP", "skinUrl", sf + "$Multiplayer", "otherSkin");
        }
        r.on(SRC + "EntityPlayerSP")
                .afterFieldSet("?<init>", "(Lnet/minecraft/client/Minecraft;L" + SRC + "World;L" + SRC + "Session;I)V", SRC + "EntityPlayerSP", "username", sf, "ownSkin")   // the constructor sets skinUrl before username: look the skin up once the name is known
                .afterFieldSet("?<init>", "(Lnet/minecraft/client/Minecraft;L" + SRC + "World;L" + SRC + "Session;)V", SRC + "EntityPlayerSP", "username", sf, "ownSkin");
        r.on(SRC + "EntityPlayer").afterFieldSet("?updateCloak", "()V", SRC + "EntityPlayer", "playerCloakUrl", sf, "cloak");
        String as = "dev/betavk/hooks/Assets";
        r.on(SRC + "ThreadDownloadResources").replaceBody("run", "()V", as, "run");
        r.on(SRC + "NetClientHandler").redirectCall("?handleHandshake", "(L" + SRC + "Packet2Handshake;)V", "java/net/URL", "openStream", "()Ljava/io/InputStream;", false, as, "join");

        // inventory: drag, double-click gather, shift-craft (b1.5+ Container/GuiContainer layout)
        if (PatchRegistry.hasClass(SRC + "Container")) {
            String inv = "dev/betavk/hooks/Inventory";
            r.on(SRC + "Container")
                    .atStartReturnIfNotNull("func_27280_a", "(IIZL" + SRC + "EntityPlayer;)L" + SRC + "ItemStack;", inv, "click");
            r.on(SRC + "GuiContainer")
                    .atStartCancelable("mouseClicked", "(III)V", inv, "mouseClicked")
                    .atStart("drawScreen", "(IIF)V", inv, "poll")
                    .afterCall("drawScreen", "(IIF)V", SRC + "GuiContainer", "drawGuiContainerBackgroundLayer", "(F)V", "dev/betavk/hooks/InventoryPlus", "drawSortButtons")
                    .atEnd("drawScreen", "(IIF)V", inv, "preview")
                    .atStartCancelable("keyTyped", "(CI)V", "dev/betavk/hooks/InventoryPlus", "key");
            r.on(SRC + "GuiScreen").atStart("handleMouseInput", "()V", "dev/betavk/hooks/InventoryPlus", "wheel");
        }

        // video settings screen
        if (PatchRegistry.hasClass(SRC + "GuiVideoSettings")) {
            String vs = "dev/betavk/hooks/VideoScreen";
            r.on(SRC + "GuiVideoSettings")
                    .replaceBody("initGui", "()V", vs, "init")
                    .replaceBody("actionPerformed", "(L" + SRC + "GuiButton;)V", vs, "action")
                    .ensureMethod("onGuiClosed", "()V", vs, "closed");
        }

        // Options and Controls screens, GUI scale and sensitivity labels
        String os = "dev/betavk/hooks/OptionsScreens";
        r.on(SRC + "GuiOptions")
                .replaceBody("initGui", "()V", os, "init")
                .replaceBody("actionPerformed", "(L" + SRC + "GuiButton;)V", os, "action")
                .ensureMethod("onGuiClosed", "()V", os, "closed");
        String controls = "dev/betavk/hooks/Controls";
        r.on(SRC + "GuiControls")
                .atEnd("initGui", "()V", controls, "addMouseSettings")
                .atStartCancelable("actionPerformed", "(L" + SRC + "GuiButton;)V", controls, "mouseAction")
                .ensureMethod("onGuiClosed", "()V", controls, "closed");
        if (PatchRegistry.hasClass(SRC + "EnumOptions")) {
            r.on(SRC + "GameSettings")
                    .atStartReturnIfNotNull("getKeyBinding", "(L" + SRC + "EnumOptions;)Ljava/lang/String;", os, "label")
                    .atStartCancelable("setOptionValue", "(L" + SRC + "EnumOptions;I)V", os, "setOption");
        }

        // worlds: resume, list order and seeds, autosave, backups
        String wd = "dev/betavk/hooks/Worlds";
        r.on(mcc)
                .atStart("?startWorld", "(Ljava/lang/String;Ljava/lang/String;J)V", wd, "startWorld")
                .atStart("?startWorld", "(Ljava/lang/String;)V", wd, "startWorld1");
        r.on(SRC + "GuiMainMenu")
                .atEnd("initGui", "()V", wd, "menuInit")
                .atStartCancelable("actionPerformed", "(L" + SRC + "GuiButton;)V", wd, "menuAction");
        if (PatchRegistry.hasClass(SRC + "GuiWorldSlot")) {
            r.on(SRC + "GuiSelectWorld")
                    .atEnd("?loadSaves", "()V", wd, "selectSort")
                    .atEnd("initGui", "()V", wd, "selectInit")
                    .atStartCancelable("actionPerformed", "(L" + SRC + "GuiButton;)V", wd, "selectAction");
            r.on(SRC + "GuiWorldSlot").atEnd("drawSlot", "(IIIIL" + SRC + "Tessellator;)V", wd, "slotSeed");
        }
        if (PatchRegistry.hasClass(SRC + "BlockBed") && PatchRegistry.hasClass(SRC + "EnumStatus")) {
            String sleep = "(III)L" + SRC + "EnumStatus;";
            r.on(SRC + "BlockBed").redirectCall("blockActivated", "(L" + SRC + "World;IIIL" + SRC + "EntityPlayer;)Z",
                    SRC + "EntityPlayer", "sleepInBedAt", sleep, false, "dev/betavk/hooks/Beds", "use");
        }

        // movement keys: none while a screen is open, the real keyboard state when it closes
        r.on(SRC + "EntityPlayerSP").atStartCancelable("?handleKeyPress", "(IZ)V", "dev/betavk/hooks/Keys", "press");
        r.on(SRC + "MovementInputFromOptions").atStart("updatePlayerMoveState", "(L" + SRC + "EntityPlayer;)V",
                "dev/betavk/hooks/Keys", "applySneak");
        r.on(mcc).atEnd("setIngameFocus", "()V", "dev/betavk/hooks/Keys", "focus");

        String commands = "dev/betavk/hooks/Commands";
        r.on(SRC + "EntityLiving").atStartCancelable("moveEntityWithHeading", "(FF)V", commands, "flyMove");
        r.on(SRC + "EntityPlayer").atStartCancelable("?fall", "(F)V", commands, "ignoreFall");
        r.on(mcc)
                .redirectCallNth("runTick", "()V", mcc, "isMultiplayerWorld", "()Z", false, 0, commands, "canOpenChat")
                .replaceBody("?lineIsCommand", "(Ljava/lang/String;)Z", commands, "run");
        if (PatchRegistry.hasClass(SRC + "GuiChat")) {
            String suggestions = "dev/betavk/hooks/CommandSuggestions";
            r.on(SRC + "GuiChat")
                    .atStartCancelable("keyTyped", "(CI)V", suggestions, "key")
                    .atEnd("drawScreen", "(IIF)V", suggestions, "draw");
        }
        if (PatchRegistry.hasClass(SRC + "WorldInfo")) {
            r.on(SRC + "WorldInfo")
                    .atEnd("<init>", "(L" + SRC + "NBTTagCompound;)V", commands, "load")
                    .atEnd("<init>", "(L" + SRC + "WorldInfo;)V", commands, "copy")
                    .atEnd("updateTagCompound", "(L" + SRC + "NBTTagCompound;L" + SRC + "NBTTagCompound;)V", commands, "save");
        }

        r.on(SRC + "SoundManager").atStart("playRandomMusicIfReady", "()V", "dev/betavk/hooks/Music", "skipDelay");

        // options file
        r.on(SRC + "GameSettings")
                .beforeCall("<init>", "(Lnet/minecraft/client/Minecraft;Ljava/io/File;)V", SRC + "GameSettings", "loadOptions", "()V", controls, "addZoom")
                .atStart("loadOptions", "()V", set, "beforeLoad")
                .atEnd("loadOptions", "()V", set, "load")
                .atEnd("saveOptions", "()V", set, "save");
    }

    private static boolean hasBytecodeMarker(String resource, String utf8Needle) {
        ClassLoader ctx = Thread.currentThread().getContextClassLoader();
        for (ClassLoader cl : new ClassLoader[]{ctx, ClassLoader.getSystemClassLoader()}) {
            if (cl == null) continue;
            try (var in = cl.getResourceAsStream(resource)) {
                if (in != null) {
                    return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.ISO_8859_1).contains(utf8Needle);
                }
            } catch (Exception ignored) {}
        }
        return false;
    }
}
