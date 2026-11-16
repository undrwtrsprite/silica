package dev.betavk.hooks;

import dev.betavk.agent.PatchRegistry;

/** All patches to the game, by class. Names only: nothing here loads a Minecraft class. */
public final class PatchSet implements dev.betavk.agent.PatchSet {
    private static final String SRC = "net/minecraft/src/";

    @Override public void register(PatchRegistry r) {
        String mcc = "net/minecraft/client/Minecraft", sdisp = "dev/betavk/platform/Display", dm = "()Ldev/betavk/platform/DisplayMode;";
        String loop = "dev/betavk/hooks/MainLoop", zoom = "dev/betavk/hooks/Zoom", set = "dev/betavk/hooks/Settings";

        // start-up, window, directories
        r.on(mcc)
                .atStart("startGame", "()V", loop, "beforeStart")
                .afterCall("startGame", "()V", sdisp, "create", "()V", loop, "afterDisplayCreate")
                .redirectCall("startGame", "()V", sdisp, "getDisplayMode", dm, true, loop, "displayMode")
                .redirectCall("toggleFullscreen", "()V", sdisp, "getDisplayMode", dm, true, loop, "displayMode")
                .atStartReturnIfNotNull("getAppDir", "(Ljava/lang/String;)Ljava/io/File;", loop, "appDir")
                .replaceBody("func_28001_B", "()V", loop, "paidCheck")
                // main loop
                .redirectCall("run", "()V", mcc, "toggleFullscreen", "()V", false, loop, "focusLostToggle")
                .afterCall("run", "()V", "java/lang/Thread", "yield", "()V", loop, "limitFps")
                .afterCall("run", "()V", mcc, "screenshotListener", "()V", loop, "afterFrame")
                // input
                .redirectCall("runTick", "()V", "dev/betavk/platform/Keyboard", "next", "()Z", true, loop, "nextKey")
                .redirectCall("runTick", "()V", "dev/betavk/platform/Mouse", "getEventDWheel", "()I", true, loop, "wheel");
        r.on(SRC + "MouseHelper").replaceBody("ungrabMouseCursor", "()V", loop, "ungrab");

        // field of view, zoom, hand
        r.on(SRC + "EntityRenderer")
                .onReturn("getFOVModifier", "(F)F", zoom, "fov")
                .atStart("updateCameraAndRender", "(F)V", zoom, "update")
                .replaceFloat("updateCameraAndRender", "(F)V", 8.0F, 1, zoom, "turnScale")
                .atStart("func_4135_b", "(FI)V", zoom, "hand");

        // HUD
        r.on(SRC + "GuiIngame")
                .atStart("updateTick", "()V", "dev/betavk/hooks/Hud", "tick")
                .atEnd("renderGameOverlay", "(FZII)V", "dev/betavk/hooks/Hud", "draw");

        // rendering core: chunk meshing on worker threads, one tessellator per thread, vertex packing fixes
        String tess = "dev/betavk/hooks/mesh/TessHooks", am = "dev/betavk/hooks/mesh/AsyncMesh", mh = "dev/betavk/hooks/mesh/Hooks";
        r.everywhereRedirectStaticRead(SRC + "Tessellator", "instance", tess, "instance");
        r.on(SRC + "Tessellator")
                .replaceBody("addVertex", "(DDD)V", tess, "addVertex")
                .replaceBody("setNormal", "(FFF)V", tess, "setNormal");
        r.on(SRC + "RenderGlobal")
                .atEnd("loadRenderers", "()V", "dev/betavk/hooks/mesh/CaveCull", "onLoadRenderers")
                .atEnd("clipRenderersByFrustrum", "(L" + SRC + "ICamera;F)V", "dev/betavk/hooks/mesh/CaveCull", "cull")
                .atStart("updateRenderers", "(L" + SRC + "EntityLiving;Z)Z", am, "frameStart")
                .replaceInt("updateRenderers", "(L" + SRC + "EntityLiving;Z)Z", 2, 1, am, "farBudget")
                .redirectCallNth("updateRenderers", "(L" + SRC + "EntityLiving;Z)Z", SRC + "WorldRenderer", "updateRenderer", "()V", false, 2, am, "updateFar");
        r.on(SRC + "WorldRenderer")
                .atStart("setDontDraw", "()V", am, "retarget")
                .atEnd("updateRenderer", "()V", "dev/betavk/hooks/mesh/CaveCull", "onSyncRebuild");
        r.on(SRC + "ChunkCache")
                .atStartCancelable("getBlockTileEntity", "(III)L" + SRC + "TileEntity;", mh, "noTileEntities")
                .atStartReturnIfNotNull("getWorldChunkManager", "()L" + SRC + "WorldChunkManager;", mh, "chunkManager");
        r.on(SRC + "Chunk").redirectStaticWrite("getBlockLightValue", "(IIII)I", SRC + "Chunk", "isLit", mh, "markLit");
        r.on(SRC + "RenderBlocks")
                .redirectStaticRead("renderBlockReed", "(L" + SRC + "Block;III)Z", SRC + "Block", "tallGrass", mh, "tallGrass")
                .redirectStaticRead("renderStandardBlockWithColorMultiplier", "(L" + SRC + "Block;IIIFFF)Z", SRC + "Block", "grass", mh, "grass");

        // world: loaded-chunk map, deferred population, terrain prefetch, asynchronous saves
        String pop = "dev/betavk/hooks/world/Populate", rw = "dev/betavk/hooks/world/RegionWriter", gp = "dev/betavk/hooks/world/GenPool", wh = "dev/betavk/hooks/world/WorldHooks";
        r.on(SRC + "ChunkProvider")
                .replaceNew("<init>", "(L" + SRC + "World;L" + SRC + "IChunkLoader;L" + SRC + "IChunkProvider;)V", "java/util/HashMap", "dev/betavk/hooks/world/IntKeyMap", 1)
                .redirectCall("prepareChunk", "(II)L" + SRC + "Chunk;", SRC + "ChunkProvider", "populate", "(L" + SRC + "IChunkProvider;II)V", false, pop, "request");
        r.on(SRC + "McRegionChunkLoader")
                .atStart("loadChunk", "(L" + SRC + "World;II)L" + SRC + "Chunk;", rw, "beforeLoad")
                .replaceBody("saveChunk", "(L" + SRC + "World;L" + SRC + "Chunk;)V", rw, "save")
                .atStart("saveExtraData", "()V", rw, "endOfSave")
                .atStart("func_814_a", "()V", rw, "endOfSave");
        r.on(SRC + "ChunkProviderGenerate")
                .atEnd("<init>", "(L" + SRC + "World;J)V", gp, "registerMain")
                .atStartReturnIfNotNull("provideChunk", "(II)L" + SRC + "Chunk;", gp, "take");
        r.on(mcc)
                .atStart("runTick", "()V", wh, "tick")
                .atStart("changeWorld2", "(L" + SRC + "World;Ljava/lang/String;)V", wh, "newWorld")
                .afterFieldSet("changeWorld", "(L" + SRC + "World;Ljava/lang/String;L" + SRC + "EntityPlayer;)V", mcc, "renderViewEntity", wh, "worldReady");

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
        r.on(SRC + "GuiScreen").replaceBody("getClipboardString", "()Ljava/lang/String;", sf, "clipboard");
        r.on(SRC + "EntityOtherPlayerMP").afterFieldSet("<init>", "(L" + SRC + "World;Ljava/lang/String;)V", SRC + "EntityOtherPlayerMP", "skinUrl", sf, "otherSkin");
        r.on(SRC + "EntityPlayerSP").afterFieldSet("<init>", "(Lnet/minecraft/client/Minecraft;L" + SRC + "World;L" + SRC + "Session;I)V", SRC + "EntityPlayerSP", "username", sf, "ownSkin");   // the constructor sets skinUrl before username: look the skin up once the name is known
        r.on(SRC + "EntityPlayer").afterFieldSet("updateCloak", "()V", SRC + "EntityPlayer", "playerCloakUrl", sf, "cloak");
        String as = "dev/betavk/hooks/Assets";
        r.on(SRC + "ThreadDownloadResources").replaceBody("run", "()V", as, "run");
        r.on(SRC + "NetClientHandler").redirectCall("handleHandshake", "(L" + SRC + "Packet2Handshake;)V", "java/net/URL", "openStream", "()Ljava/io/InputStream;", false, as, "join");

        // inventory: drag, double-click gather, shift-craft
        String inv = "dev/betavk/hooks/Inventory";
        r.on(SRC + "Container")
                .atStartReturnIfNotNull("func_27280_a", "(IIZL" + SRC + "EntityPlayer;)L" + SRC + "ItemStack;", inv, "click");
        r.on(SRC + "GuiContainer")
                .atStartCancelable("mouseClicked", "(III)V", inv, "mouseClicked")
                .atStart("drawScreen", "(IIF)V", inv, "poll")
                .atEnd("drawScreen", "(IIF)V", inv, "preview")
                .atStartCancelable("keyTyped", "(CI)V", "dev/betavk/hooks/InventoryPlus", "key");
        r.on(SRC + "GuiScreen").atStart("handleMouseInput", "()V", "dev/betavk/hooks/InventoryPlus", "wheel");

        // video settings screen
        String vs = "dev/betavk/hooks/VideoScreen";
        r.on(SRC + "GuiVideoSettings")
                .replaceBody("initGui", "()V", vs, "init")
                .replaceBody("actionPerformed", "(L" + SRC + "GuiButton;)V", vs, "action")
                .addMethod("onGuiClosed", "()V", vs, "closed");

        // Options and Controls screens, GUI scale and sensitivity labels
        String os = "dev/betavk/hooks/OptionsScreens";
        r.on(SRC + "GuiOptions")
                .replaceBody("initGui", "()V", os, "init")
                .replaceBody("actionPerformed", "(L" + SRC + "GuiButton;)V", os, "action")
                .addMethod("onGuiClosed", "()V", os, "closed");
        String controls = "dev/betavk/hooks/Controls";
        r.on(SRC + "GuiControls")
                .atEnd("initGui", "()V", controls, "addMouseSettings")
                .atStartCancelable("actionPerformed", "(L" + SRC + "GuiButton;)V", controls, "mouseAction")
                .addMethod("onGuiClosed", "()V", controls, "closed");
        r.on(SRC + "GameSettings")
                .atStartReturnIfNotNull("getKeyBinding", "(L" + SRC + "EnumOptions;)Ljava/lang/String;", os, "label")
                .atStartCancelable("setOptionValue", "(L" + SRC + "EnumOptions;I)V", os, "setOption");

        // worlds: resume, list order and seeds, autosave, backups
        String wd = "dev/betavk/hooks/Worlds";
        r.on(mcc).atStart("startWorld", "(Ljava/lang/String;Ljava/lang/String;J)V", wd, "startWorld");
        r.on(SRC + "GuiMainMenu")
                .atEnd("initGui", "()V", wd, "menuInit")
                .atStartCancelable("actionPerformed", "(L" + SRC + "GuiButton;)V", wd, "menuAction");
        r.on(SRC + "GuiSelectWorld")
                .atEnd("loadSaves", "()V", wd, "selectSort")
                .atEnd("initGui", "()V", wd, "selectInit")
                .atStartCancelable("actionPerformed", "(L" + SRC + "GuiButton;)V", wd, "selectAction");
        r.on(SRC + "GuiWorldSlot").atEnd("drawSlot", "(IIIIL" + SRC + "Tessellator;)V", wd, "slotSeed");
        String sleep = "(III)L" + SRC + "EnumStatus;";
        r.on(SRC + "BlockBed").redirectCall("blockActivated", "(L" + SRC + "World;IIIL" + SRC + "EntityPlayer;)Z",
                SRC + "EntityPlayer", "sleepInBedAt", sleep, false, "dev/betavk/hooks/Beds", "use");

        // movement keys: none while a screen is open, the real keyboard state when it closes
        r.on(SRC + "EntityPlayerSP").atStartCancelable("handleKeyPress", "(IZ)V", "dev/betavk/hooks/Keys", "press");
        r.on(SRC + "MovementInputFromOptions").atStart("updatePlayerMoveState", "(L" + SRC + "EntityPlayer;)V",
                "dev/betavk/hooks/Keys", "applySneak");
        r.on(mcc).atEnd("setIngameFocus", "()V", "dev/betavk/hooks/Keys", "focus");

        String commands = "dev/betavk/hooks/Commands";
        r.on(mcc)
                .redirectCallNth("runTick", "()V", mcc, "isMultiplayerWorld", "()Z", false, 0, commands, "canOpenChat")
                .replaceBody("lineIsCommand", "(Ljava/lang/String;)Z", commands, "run");
        String suggestions = "dev/betavk/hooks/CommandSuggestions";
        r.on(SRC + "GuiChat")
                .atStartCancelable("keyTyped", "(CI)V", suggestions, "key")
                .atEnd("drawScreen", "(IIF)V", suggestions, "draw");
        r.on(SRC + "WorldInfo")
                .atEnd("<init>", "(L" + SRC + "NBTTagCompound;)V", commands, "load")
                .atEnd("<init>", "(L" + SRC + "WorldInfo;)V", commands, "copy")
                .atEnd("updateTagCompound", "(L" + SRC + "NBTTagCompound;L" + SRC + "NBTTagCompound;)V", commands, "save");

        r.on(SRC + "SoundManager").atStart("playRandomMusicIfReady", "()V", "dev/betavk/hooks/Music", "skipDelay");

        // options file
        r.on(SRC + "GameSettings")
                .beforeCall("<init>", "(Lnet/minecraft/client/Minecraft;Ljava/io/File;)V", SRC + "GameSettings", "loadOptions", "()V", controls, "addZoom")
                .atEnd("loadOptions", "()V", set, "load")
                .atEnd("saveOptions", "()V", set, "save");
    }
}
