package dev.betavk.render.vulkan;

import dev.betavk.platform.Display;
import dev.betavk.render.Renderer;
import dev.betavk.render.vulkan.VkCore.Buf;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.Configuration;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static dev.betavk.render.vulkan.VkCore.check;
import static org.lwjgl.glfw.GLFWVulkan.glfwInitVulkanLoader;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK12.*;

/**
 * Vulkan backend for the fixed-function Renderer interface: CPU matrix stack, GL state hashed into pipelines, one uber
 * shader with a per-draw dynamic UBO, one packed 32-byte vertex, display lists recorded as op lists whose vertices live
 * in a persistent arena. Apple Silicon unified memory: geometry is written straight into mapped buffers, no staging.
 */
public final class VulkanRenderer implements Renderer {
    // ---- GL enum values (the interface keeps GL's vocabulary) -----------------------------
    private static final int TEXTURE_2D = 0xDE1, BLEND = 0xBE2, DEPTH_TEST = 0xB71, ALPHA_TEST = 0xBC0, CULL_FACE = 0xB44, FOG = 0xB60,
            LIGHTING = 0xB50, LIGHT0 = 0x4000, LIGHT1 = 0x4001, POLYGON_OFFSET_FILL = 0x8037;
    private static final int MODELVIEW = 0x1700, PROJECTION = 0x1701, MODELVIEW_MATRIX = 0xBA6, PROJECTION_MATRIX = 0xBA7;
    private static final int GL_QUADS = 7, COLOR_BUFFER_BIT = 0x4000, DEPTH_BUFFER_BIT = 0x100;

    private static final String DBG = System.getProperty("betavk.dbg", "");
    private static final boolean BIND_CACHE = !"false".equalsIgnoreCase(System.getProperty("betavk.bindCache", "true"));
    private static final boolean BAKE_CHUNKS = !"false".equalsIgnoreCase(System.getProperty("betavk.bakeChunks", "true"));
    private static final boolean DEFER_MIPGEN = !"false".equalsIgnoreCase(System.getProperty("betavk.deferMipgen", "true"));
    private static final boolean INDIRECT = Boolean.getBoolean("betavk.indirect");
    private int dumped;
    private static final float[] ZERO4 = new float[4];
    private final float[] mvpTmp = new float[16];
    private static final int VERTEX = 32, PARAMS_SIZE = 304;
    private static final long UBO_RING = 8L << 20, VERTEX_RING = 24L << 20, STAGING_RING = 16L << 20, INDIRECT_RING = 1L << 20, LIST_ARENA = 256L << 20;
    private static final int MAX_QUADS = 1 << 18;

    public static void install() {
        Configuration.VULKAN_EXPLICIT_INIT.set(true);
        VK.create();
        glfwInitVulkanLoader(VK.getFunctionProvider().getFunctionAddress("vkGetInstanceProcAddr"));
        Display.openGlContext = false;
    }

    // ---- device objects -------------------------------------------------------------------
    private final VkCore core = new VkCore();
    private long setLayoutParams, setLayoutTex, pipelineLayout, descPool, pipelineCache, vertModule, fragModule;
    private final long[] paramsSet = new long[VkCore.FRAMES];
    private final Buf[] uboRing = new Buf[VkCore.FRAMES], vertexRing = new Buf[VkCore.FRAMES], stagingRing = new Buf[VkCore.FRAMES], indirectRing = new Buf[VkCore.FRAMES];
    private final long[] ringStamp = newStamps();

    private static long[] newStamps() {
        long[] a = new long[VkCore.FRAMES];
        java.util.Arrays.fill(a, -1L);
        return a;
    }
    private final long[] uboOff = new long[VkCore.FRAMES], vertexOff = new long[VkCore.FRAMES], stagingOff = new long[VkCore.FRAMES], indirectOff = new long[VkCore.FRAMES];
    private Buf quadIndexBuf;
    // Chunk meshes live in sub-allocated vertex buffers. One 256 MB buffer was not enough for far render distance (draws were dropped,
    // which looks like holes in the terrain), so further buffers are added on demand up to MAX_LIST_ARENAS.
    private static final int MAX_LIST_ARENAS = 8;
    private final List<Buf> listBufs = new ArrayList<>();
    private final List<Arena> listArenas = new ArrayList<>();
    private int arenaDrops;
    private final Map<Long, Long> pipelines = new HashMap<>();
    private final Map<Integer, Long> samplers = new HashMap<>();
    private final List<Object[]> dying = new ArrayList<>();   // {releaseAtFrame, Runnable}
    private Tex whiteTex;

    // ---- GL state --------------------------------------------------------------------------
    private final float[][][] stacks = new float[3][32][16];
    private final int[] sp = new int[3];
    private int matrixMode = 0;
    private final float[] color = {1, 1, 1, 1}, normal = {0, 0, 1};
    private boolean tex2d, blend, depthTest, alphaTest, cull, fog, lighting, light0, light1, polyOffset;
    private int blendSrc = 1, blendDst = 0, depthOp = 1, alphaOp = 7, cullMode = 0x405;
    private float alphaRef = 0;
    private boolean depthWrite = true;
    private int colorMask = 15;
    private int fogMode = 0x800;
    private float fogStart, fogEnd = 1, fogDensity = 1;
    private boolean fogRadial;   // GL_NV_fog_distance GL_EYE_RADIAL_NV
    private final float[] fogColor = new float[4];
    private final float[][] lightDir = new float[2][4], lightDiff = new float[2][4];
    private final float[] ambient = {0.2f, 0.2f, 0.2f, 1f};
    private float polyFactor, polyUnits;
    private int vx, vy, vw, vh;
    private final float[] clearColor = {0, 0, 0, 1};
    private float clearDepth = 1f;
    private boolean vertexArray, texArray, colorArray, normalArray;
    private long vertexAddr;
    private int boundTex;
    private Tex curTex;
    private final Map<Integer, Tex> textures = new HashMap<>();
    private int nextTex = 1, nextList = 1;

    // per-command-buffer caches
    private long boundPipeline, boundVB, boundTexSet;
    private long lastPipeKey = -1, lastPipe;
    private int boundParamOff = -1;
    private boolean indexBound, viewportDirty = true, paramsDirty = true;
    private int lastParamFlags = -1;
    private long lastParamOff;

    // ---- display lists ---------------------------------------------------------------------
    private static final class DisplayList {
        final List<Runnable> ops = new ArrayList<>();
        final List<long[]> blocks = new ArrayList<>();   // arena {offset, bytes}
        int arenaIdx = -1, firstVertex, indexCount, arrayFlags;
        int cutArenaIdx = -1, cutFirstVertex, cutIndexCount, cutArrayFlags;
        boolean pureQuad, noAlpha;
        boolean pureEligible, recDrawn, recAlphaDisabled, recHasSolid, recHasCutout;
        int recDepth, recVertCount, cutVertCount;
        float recSx = 1f, recSy = 1f, recSz = 1f, recTx, recTy, recTz;
        long recBoff, cutBoff;
    }

    private DisplayList[] listArr = new DisplayList[8192];
    private DisplayList recording;
    private int recordingId;
    private final int[] indCount = new int[MAX_LIST_ARENAS];
    private final int[][] indData = new int[MAX_LIST_ARENAS][];

    // ---- textures --------------------------------------------------------------------------
    private static final class Tex {
        long image, memory, view;
        int w, h, mips;
        int minF = 0x2702, magF = 0x2601, wrapS = 0x2901, wrapT = 0x2901;
        int lastSmpKey = -1;
        long lastSet;
        boolean mipsDirty;
        final Map<Integer, Long> sets = new HashMap<>();
    }
    private final List<Tex> pendingMips = new ArrayList<>();

    public VulkanRenderer() {
        core.init(Display.handle());
        try (MemoryStack st = stackPush()) {
            LongBuffer p = st.mallocLong(1);
            VkDescriptorSetLayoutBinding.Buffer b0 = VkDescriptorSetLayoutBinding.calloc(1, st).binding(0)
                    .descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC).descriptorCount(1)
                    .stageFlags(VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT);
            check(vkCreateDescriptorSetLayout(core.device, VkDescriptorSetLayoutCreateInfo.calloc(st).sType$Default().pBindings(b0), null, p), "set layout 0");
            setLayoutParams = p.get(0);
            VkDescriptorSetLayoutBinding.Buffer b1 = VkDescriptorSetLayoutBinding.calloc(1, st).binding(0)
                    .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT);
            check(vkCreateDescriptorSetLayout(core.device, VkDescriptorSetLayoutCreateInfo.calloc(st).sType$Default().pBindings(b1), null, p), "set layout 1");
            setLayoutTex = p.get(0);
            check(vkCreatePipelineLayout(core.device, VkPipelineLayoutCreateInfo.calloc(st).sType$Default().pSetLayouts(st.longs(setLayoutParams, setLayoutTex)), null, p), "pipeline layout");
            pipelineLayout = p.get(0);

            VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(2, st);
            sizes.get(0).type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC).descriptorCount(VkCore.FRAMES);
            sizes.get(1).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(4096);
            check(vkCreateDescriptorPool(core.device, VkDescriptorPoolCreateInfo.calloc(st).sType$Default()
                    .flags(VK_DESCRIPTOR_POOL_CREATE_FREE_DESCRIPTOR_SET_BIT).maxSets(4096 + VkCore.FRAMES).pPoolSizes(sizes), null, p), "descriptor pool");
            descPool = p.get(0);
            // Persisted across runs; the driver validates the blob's header (vendor/device/UUID) and ignores a mismatching one.
            java.nio.file.Path cacheFile = cacheFile();
            ByteBuffer initial = null;
            try {
                if (java.nio.file.Files.isRegularFile(cacheFile)) {
                    byte[] blob = java.nio.file.Files.readAllBytes(cacheFile);
                    initial = st.malloc(blob.length);
                    initial.put(blob).flip();
                }
            } catch (IOException e) {
                System.err.println("[betavk] could not read pipeline cache: " + e);
            }
            VkPipelineCacheCreateInfo pci0 = VkPipelineCacheCreateInfo.calloc(st).sType$Default();
            if (initial != null) pci0.pInitialData(initial);
            check(vkCreatePipelineCache(core.device, pci0, null, p), "pipeline cache");
            pipelineCache = p.get(0);
            vertModule = shader(st, "/shaders/gfx.vert.spv");
            fragModule = shader(st, "/shaders/gfx.frag.spv");

            for (int i = 0; i < VkCore.FRAMES; i++) {
                uboRing[i] = core.createBuffer(UBO_RING, VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT);
                vertexRing[i] = core.createBuffer(VERTEX_RING, VK_BUFFER_USAGE_VERTEX_BUFFER_BIT);
                stagingRing[i] = core.createBuffer(STAGING_RING, VK_BUFFER_USAGE_TRANSFER_SRC_BIT);
                if (INDIRECT) indirectRing[i] = core.createBuffer(INDIRECT_RING, VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT);
                check(vkAllocateDescriptorSets(core.device, VkDescriptorSetAllocateInfo.calloc(st).sType$Default().descriptorPool(descPool).pSetLayouts(st.longs(setLayoutParams)), p), "alloc params set");
                paramsSet[i] = p.get(0);
                VkDescriptorBufferInfo.Buffer bi = VkDescriptorBufferInfo.calloc(1, st).buffer(uboRing[i].buffer).offset(0).range(PARAMS_SIZE);
                vkUpdateDescriptorSets(core.device, VkWriteDescriptorSet.calloc(1, st).sType$Default().dstSet(paramsSet[i]).dstBinding(0)
                        .descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC).descriptorCount(1).pBufferInfo(bi), null);
            }
        }
        addListArena();
        quadIndexBuf = core.createBuffer((long) MAX_QUADS * 6 * 4, VK_BUFFER_USAGE_INDEX_BUFFER_BIT);
        IntBuffer qi = MemoryUtil.memIntBuffer(quadIndexBuf.ptr, MAX_QUADS * 6);
        for (int q = 0; q < MAX_QUADS; q++) {
            int v = q * 4;
            qi.put(v).put(v + 1).put(v + 2).put(v).put(v + 2).put(v + 3);
        }
        createQueryPools();
        for (int i = 0; i < 3; i++) for (int j = 0; j < 32; j++) ident(stacks[i][j]);
        whiteTex = new Tex();
        createImage(whiteTex, 1, 1);
        upload(whiteTex, 0, 0, 1, 1, MemoryUtil.memAlloc(4).putInt(0, -1), true);

        if (VkCore.STATS) Runtime.getRuntime().addShutdownHook(new Thread(core::printStats));
        Runtime.getRuntime().addShutdownHook(new Thread(this::savePipelineCache));
        Display.presentHook = this::present;
        Display.frameGrabber = this::readRgb;
        System.out.println("[betavk] Vulkan backend: " + core.deviceName + " via MoltenVK, " + core.width + "x" + core.height + ", frames in flight " + VkCore.FRAMES);
    }

    private static java.nio.file.Path cacheFile() {
        String dir = System.getProperty("betavk.cachedir");
        java.nio.file.Path base = dir != null ? java.nio.file.Paths.get(dir) : java.nio.file.Paths.get(System.getProperty("user.home"), "Library", "Caches", "beta-vulkan");
        return base.resolve("pipeline.cache");
    }

    private void savePipelineCache() {
        try (MemoryStack st = stackPush()) {
            org.lwjgl.PointerBuffer size = st.mallocPointer(1);
            if (vkGetPipelineCacheData(core.device, pipelineCache, size, null) != VK_SUCCESS || size.get(0) == 0) return;
            ByteBuffer data = MemoryUtil.memAlloc((int) size.get(0));
            try {
                if (vkGetPipelineCacheData(core.device, pipelineCache, size, data) != VK_SUCCESS) return;
                byte[] out = new byte[(int) size.get(0)];
                data.get(0, out);
                java.nio.file.Path f = cacheFile();
                java.nio.file.Files.createDirectories(f.getParent());
                java.nio.file.Files.write(f, out);
                System.out.println("[betavk] pipeline cache saved: " + out.length + " bytes, " + pipelines.size() + " pipelines");
            } finally {
                MemoryUtil.memFree(data);
            }
        } catch (IOException e) {
            System.err.println("[betavk] could not save pipeline cache: " + e);
        }
    }

    private long shader(MemoryStack st, String res) {
        try (InputStream in = VulkanRenderer.class.getResourceAsStream(res)) {
            if (in == null) throw new IllegalStateException("missing shader resource " + res + " (run :game:compileShaders)");
            byte[] bytes = in.readAllBytes();
            ByteBuffer bb = st.malloc(bytes.length);
            bb.put(bytes).flip();
            LongBuffer p = st.mallocLong(1);
            check(vkCreateShaderModule(core.device, VkShaderModuleCreateInfo.calloc(st).sType$Default().pCode(bb), null, p), "shader module " + res);
            return p.get(0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    // ============================== matrices ==================================================

    private static void ident(float[] m) {
        java.util.Arrays.fill(m, 0);
        m[0] = m[5] = m[10] = m[15] = 1;
    }

    /** out = a * b (column-major). */
    private static final float[] T16 = new float[16];
    private static final float[] MUL_TMP = new float[16];

    private static void mul(float[] a, float[] b, float[] out) {
        float[] r = MUL_TMP;
        for (int c = 0; c < 4; c++)
            for (int rr = 0; rr < 4; rr++) {
                float s = 0;
                for (int k = 0; k < 4; k++) s += a[k * 4 + rr] * b[c * 4 + k];
                r[c * 4 + rr] = s;
            }
        System.arraycopy(r, 0, out, 0, 16);
    }

    private float[] cur() {
        return stacks[matrixMode][sp[matrixMode]];
    }

    private void postMul(float[] t) {
        float[] m = cur();
        mul(m, t, m);
        paramsDirty = true;
    }

    private boolean rec(Runnable op) {
        if (recording == null) return false;
        recording.pureEligible = false;
        recording.ops.add(op);
        return true;
    }

    @Override public void glMatrixMode(int p0) { if (rec(() -> glMatrixMode(p0))) return; matrixMode = p0 == MODELVIEW ? 0 : p0 == PROJECTION ? 1 : 2; }
    @Override public void glLoadIdentity() { if (rec(this::glLoadIdentity)) return; ident(cur()); paramsDirty = true; }
    @Override public void glPushMatrix() {
        if (recording != null) {
            if (recording.pureEligible && recording.recDepth == 0 && !recording.recDrawn) recording.recDepth = 1;
            else recording.pureEligible = false;
            recording.ops.add(this::glPushMatrix);
            return;
        }
        System.arraycopy(stacks[matrixMode][sp[matrixMode]], 0, stacks[matrixMode][sp[matrixMode] + 1], 0, 16);
        sp[matrixMode]++;
    }
    @Override public void glPopMatrix() {
        if (recording != null) {
            if (recording.pureEligible && recording.recDepth == 1 && recording.recDrawn && !recording.recAlphaDisabled) recording.recDepth = 0;
            else recording.pureEligible = false;
            recording.ops.add(this::glPopMatrix);
            return;
        }
        sp[matrixMode]--;
        paramsDirty = true;
    }
    @Override public void glTranslatef(float x, float y, float z) {
        if (recording != null) {
            if (recording.pureEligible && recording.recDepth == 1 && !recording.recDrawn) {
                recording.recTx += recording.recSx * x;
                recording.recTy += recording.recSy * y;
                recording.recTz += recording.recSz * z;
            } else {
                recording.pureEligible = false;
            }
            recording.ops.add(() -> glTranslatef(x, y, z));
            return;
        }
        float[] t = T16;
        ident(t);
        t[12] = x;
        t[13] = y;
        t[14] = z;
        postMul(t);
    }
    @Override public void glScalef(float x, float y, float z) {
        if (recording != null) {
            if (recording.pureEligible && recording.recDepth == 1 && !recording.recDrawn && x > 0f && y > 0f && z > 0f) {
                recording.recSx *= x;
                recording.recSy *= y;
                recording.recSz *= z;
            } else {
                recording.pureEligible = false;
            }
            recording.ops.add(() -> glScalef(x, y, z));
            return;
        }
        float[] t = T16;
        ident(t);
        t[0] = x;
        t[5] = y;
        t[10] = z;
        postMul(t);
    }
    @Override public void glScaled(double x, double y, double z) { glScalef((float) x, (float) y, (float) z); }
    @Override public void glRotatef(float angle, float x, float y, float z) {
        if (rec(() -> glRotatef(angle, x, y, z))) return;
        double len = Math.sqrt(x * x + y * y + z * z);
        if (len == 0) return;
        float ax = (float) (x / len), ay = (float) (y / len), az = (float) (z / len);
        float c = (float) Math.cos(Math.toRadians(angle)), s = (float) Math.sin(Math.toRadians(angle)), k = 1 - c;
        float[] t = T16;
        java.util.Arrays.fill(t, 0);
        t[0] = ax * ax * k + c;
        t[1] = ay * ax * k + az * s;
        t[2] = az * ax * k - ay * s;
        t[4] = ax * ay * k - az * s;
        t[5] = ay * ay * k + c;
        t[6] = az * ay * k + ax * s;
        t[8] = ax * az * k + ay * s;
        t[9] = ay * az * k - ax * s;
        t[10] = az * az * k + c;
        t[15] = 1;
        postMul(t);
    }
    @Override public void glOrtho(double l, double r, double b, double t, double n, double f) {
        if (rec(() -> glOrtho(l, r, b, t, n, f))) return;
        float[] m = new float[16];
        m[0] = (float) (2 / (r - l));
        m[5] = (float) (2 / (t - b));
        m[10] = (float) (-2 / (f - n));
        m[12] = (float) (-(r + l) / (r - l));
        m[13] = (float) (-(t + b) / (t - b));
        m[14] = (float) (-(f + n) / (f - n));
        m[15] = 1;
        postMul(m);
    }
    @Override public void glFrustum(double l, double r, double b, double t, double n, double f) {
        if (rec(() -> glFrustum(l, r, b, t, n, f))) return;
        float[] m = new float[16];
        m[0] = (float) (2 * n / (r - l));
        m[5] = (float) (2 * n / (t - b));
        m[8] = (float) ((r + l) / (r - l));
        m[9] = (float) ((t + b) / (t - b));
        m[10] = (float) (-(f + n) / (f - n));
        m[11] = -1;
        m[14] = (float) (-2 * f * n / (f - n));
        postMul(m);
    }
    @Override public void glGetFloatv(int pname, FloatBuffer out) {
        float[] m = pname == MODELVIEW_MATRIX ? stacks[0][sp[0]] : pname == PROJECTION_MATRIX ? stacks[1][sp[1]] : null;
        if (m == null) throw new UnsupportedOperationException("glGetFloatv " + pname);
        out.put(m, 0, 16);
    }
    @Override public void glGetFloatv(int pname, float[] out) {
        FloatBuffer fb = FloatBuffer.allocate(16);
        glGetFloatv(pname, fb);
        System.arraycopy(fb.array(), 0, out, 0, 16);
    }

    // ============================== simple state ==============================================

    @Override public void glColor4f(float r, float g, float b, float a) { if (rec(() -> glColor4f(r, g, b, a))) return; color[0] = r; color[1] = g; color[2] = b; color[3] = a; paramsDirty = true; }
    @Override public void glColor3f(float r, float g, float b) { glColor4f(r, g, b, 1f); }
    @Override public void glNormal3f(float x, float y, float z) { if (rec(() -> glNormal3f(x, y, z))) return; normal[0] = x; normal[1] = y; normal[2] = z; paramsDirty = true; }
    @Override public void glEnable(int cap) {
        if (recording != null) {
            if (cap == ALPHA_TEST && recording.pureEligible && recording.recDepth == 1 && recording.recAlphaDisabled && recording.recHasSolid) {
                recording.recAlphaDisabled = false;
            } else {
                recording.pureEligible = false;
            }
            recording.ops.add(() -> glEnable(cap));
            return;
        }
        setCap(cap, true);
    }
    @Override public void glDisable(int cap) {
        if (recording != null) {
            if (cap == ALPHA_TEST && recording.pureEligible && recording.recDepth == 1 && !recording.recDrawn && !recording.recHasSolid) {
                recording.recAlphaDisabled = true;
            } else {
                recording.pureEligible = false;
            }
            recording.ops.add(() -> glDisable(cap));
            return;
        }
        setCap(cap, false);
    }

    private void setCap(int cap, boolean on) {
        switch (cap) {
            case TEXTURE_2D -> tex2d = on;
            case BLEND -> blend = on;
            case DEPTH_TEST -> depthTest = on;
            case ALPHA_TEST -> alphaTest = on;
            case CULL_FACE -> cull = on;
            case FOG -> fog = on;
            case LIGHTING -> lighting = on;
            case LIGHT0 -> light0 = on;
            case LIGHT1 -> light1 = on;
            case POLYGON_OFFSET_FILL -> polyOffset = on;
            default -> { }   // COLOR_MATERIAL, NORMALIZE, RESCALE_NORMAL, ...: implied by the shader
        }
        paramsDirty = true;
    }

    @Override public void glBlendFunc(int s, int d) { if (rec(() -> glBlendFunc(s, d))) return; blendSrc = s; blendDst = d; }
    /** The depth buffer is reversed (1 at the near plane): LESS <-> GREATER, LESS_OR_EQUAL <-> GREATER_OR_EQUAL; the rest are symmetric. */
    private static int reverseCompare(int op) {
        return op == 1 ? 4 : op == 4 ? 1 : op == 3 ? 6 : op == 6 ? 3 : op;
    }

    @Override public void glDepthFunc(int f) { if (rec(() -> glDepthFunc(f))) return; depthOp = f - 0x200; }
    @Override public void glDepthMask(boolean m) { if (rec(() -> glDepthMask(m))) return; depthWrite = m; }
    @Override public void glAlphaFunc(int f, float ref) { if (rec(() -> glAlphaFunc(f, ref))) return; alphaOp = f - 0x200; alphaRef = ref; paramsDirty = true; }
    @Override public void glColorMask(boolean r, boolean g, boolean b, boolean a) { if (rec(() -> glColorMask(r, g, b, a))) return; colorMask = (r ? 1 : 0) | (g ? 2 : 0) | (b ? 4 : 0) | (a ? 8 : 0); }
    @Override public void glCullFace(int m) { if (rec(() -> glCullFace(m))) return; cullMode = m; }
    @Override public void glPolygonOffset(float f, float u) { if (rec(() -> glPolygonOffset(f, u))) return; polyFactor = f; polyUnits = u; }
    @Override public void glFogi(int pname, int v) { if (rec(() -> glFogi(pname, v))) return; if (pname == 0xB65) fogMode = v; else if (pname == 0x855A) fogRadial = v == 0x855B; paramsDirty = true; }
    @Override public void glFogf(int pname, float v) {
        if (rec(() -> glFogf(pname, v))) return;
        switch (pname) {
            case 0xB62 -> fogDensity = v;
            case 0xB63 -> fogStart = v;
            case 0xB64 -> fogEnd = v;
            case 0xB65 -> fogMode = (int) v;
            default -> { }
        }
        paramsDirty = true;
    }
    @Override public void glFogfv(int pname, FloatBuffer v) { float a = v.get(v.position()), b = v.get(v.position() + 1), c = v.get(v.position() + 2), d = v.get(v.position() + 3); glFogfv(pname, new float[]{a, b, c, d}); }
    @Override public void glFogfv(int pname, float[] v) {
        float[] c = v.clone();
        if (rec(() -> glFogfv(pname, c))) return;
        if (pname == 0xB66) System.arraycopy(c, 0, fogColor, 0, 4);
        paramsDirty = true;
    }
    @Override public void glLightfv(int light, int pname, FloatBuffer v) { glLightfv(light, pname, new float[]{v.get(v.position()), v.get(v.position() + 1), v.get(v.position() + 2), v.get(v.position() + 3)}); }
    @Override public void glLightfv(int light, int pname, float[] v) {
        float[] c = v.clone();
        if (rec(() -> glLightfv(light, pname, c))) return;
        int i = light - LIGHT0;
        if (i < 0 || i > 1) return;
        if (pname == 0x1203) {   // POSITION: transform the direction into eye space by the current modelview rotation
            float[] m = stacks[0][sp[0]];
            float x = m[0] * c[0] + m[4] * c[1] + m[8] * c[2], y = m[1] * c[0] + m[5] * c[1] + m[9] * c[2], z = m[2] * c[0] + m[6] * c[1] + m[10] * c[2];
            double len = Math.sqrt(x * x + y * y + z * z);
            lightDir[i][0] = (float) (x / len);
            lightDir[i][1] = (float) (y / len);
            lightDir[i][2] = (float) (z / len);
        } else if (pname == 0x1201) {
            System.arraycopy(c, 0, lightDiff[i], 0, 4);
        }
        paramsDirty = true;
    }
    @Override public void glLightModelfv(int pname, FloatBuffer v) { glLightModelfv(pname, new float[]{v.get(v.position()), v.get(v.position() + 1), v.get(v.position() + 2), v.get(v.position() + 3)}); }
    @Override public void glLightModelfv(int pname, float[] v) {
        float[] c = v.clone();
        if (rec(() -> glLightModelfv(pname, c))) return;
        if (pname == 0xB53) System.arraycopy(c, 0, ambient, 0, 4);
        paramsDirty = true;
    }
    @Override public void glShadeModel(int m) { }
    @Override public void glColorMaterial(int a, int b) { }
    @Override public void glLineWidth(float w) { }   // wide lines are not portable on MoltenVK
    @Override public void glPixelStorei(int a, int b) { }
    @Override public void glViewport(int x, int y, int w, int h) { vx = x; vy = y; vw = w; vh = h; viewportDirty = true; }
    @Override public void glClearColor(float r, float g, float b, float a) { clearColor[0] = r; clearColor[1] = g; clearColor[2] = b; clearColor[3] = a; }
    @Override public void glClearDepth(double d) { clearDepth = (float) d; }
    @Override public int glGetError() { return 0; }
    @Override public String glGetString(int name) {
        return switch (name) {
            case 0x1F00 -> "Apple";
            case 0x1F01 -> core.deviceName + " (Vulkan, MoltenVK)";
            case 0x1F02 -> "Vulkan 1.2";
            default -> "";
        };
    }
    @Override public String name() { return "Vulkan"; }
    @Override public boolean supportsOcclusionQueries() { return true; }
    @Override public boolean supportsVertexBufferObjects() { return false; }
    @Override public boolean supportsFogDistanceNV() { return true; }   // emulated in the vertex shader

    // client-side arrays: the Tessellator always emits the packed 32-byte vertex, so only the vertex pointer matters
    @Override public void glEnableClientState(int a) { setArray(a, true); }
    @Override public void glDisableClientState(int a) { setArray(a, false); }
    private void setArray(int a, boolean on) {
        switch (a) {
            case 0x8074 -> vertexArray = on;
            case 0x8078 -> texArray = on;
            case 0x8076 -> colorArray = on;
            case 0x8075 -> normalArray = on;
            default -> { }
        }
        paramsDirty = true;
    }
    private void vptr(Buffer b, int stride) {
        if (stride != VERTEX) throw new UnsupportedOperationException("vertex stride " + stride + " (only the packed 32-byte Tessellator vertex is supported)");
        vertexAddr = MemoryUtil.memAddress0(b) + (long) b.position() * (b instanceof FloatBuffer || b instanceof IntBuffer ? 4 : b instanceof ShortBuffer ? 2 : 1);
    }
    @Override public void glVertexPointer(int s, int t, int stride, ByteBuffer b) { vptr(b, stride); }
    @Override public void glVertexPointer(int s, int t, int stride, FloatBuffer b) { vptr(b, stride); }
    @Override public void glVertexPointer(int s, int t, int stride, IntBuffer b) { vptr(b, stride); }
    @Override public void glVertexPointer(int s, int t, int stride, ShortBuffer b) { vptr(b, stride); }
    @Override public void glVertexPointer(int s, int t, int stride, long off) { throw new UnsupportedOperationException("VBO vertex pointer"); }
    @Override public void glTexCoordPointer(int s, int t, int stride, ByteBuffer b) { }
    @Override public void glTexCoordPointer(int s, int t, int stride, FloatBuffer b) { }
    @Override public void glTexCoordPointer(int s, int t, int stride, IntBuffer b) { }
    @Override public void glTexCoordPointer(int s, int t, int stride, ShortBuffer b) { }
    @Override public void glTexCoordPointer(int s, int t, int stride, long off) { }
    @Override public void glColorPointer(int s, int t, int stride, ByteBuffer b) { }
    @Override public void glColorPointer(int s, int t, int stride, FloatBuffer b) { }
    @Override public void glColorPointer(int s, int t, int stride, IntBuffer b) { }
    @Override public void glColorPointer(int s, int t, int stride, ShortBuffer b) { }
    @Override public void glColorPointer(int s, int t, int stride, long off) { }
    @Override public void glNormalPointer(int t, int stride, ByteBuffer b) { }
    @Override public void glNormalPointer(int t, int stride, FloatBuffer b) { }
    @Override public void glNormalPointer(int t, int stride, IntBuffer b) { }
    @Override public void glNormalPointer(int t, int stride, ShortBuffer b) { }
    @Override public void glNormalPointer(int t, int stride, long off) { }

    // VBOs and occlusion queries are not offered (supports*() == false); these exist only to satisfy the interface
    @Override public void glBindBufferARB(int a, int b) { }
    @Override public int glGenBuffersARB() { return 0; }
    @Override public void glGenBuffersARB(int[] a) { }
    @Override public void glGenBuffersARB(IntBuffer a) { }
    @Override public void glBufferDataARB(int a, double[] b, int c) { }
    @Override public void glBufferDataARB(int a, float[] b, int c) { }
    @Override public void glBufferDataARB(int a, int[] b, int c) { }
    @Override public void glBufferDataARB(int a, short[] b, int c) { }
    @Override public void glBufferDataARB(int a, ByteBuffer b, int c) { }
    @Override public void glBufferDataARB(int a, DoubleBuffer b, int c) { }
    @Override public void glBufferDataARB(int a, FloatBuffer b, int c) { }
    @Override public void glBufferDataARB(int a, IntBuffer b, int c) { }
    @Override public void glBufferDataARB(int a, ShortBuffer b, int c) { }
    @Override public void glBufferDataARB(int a, long b, int c) { }
    // ---- occlusion queries (GL_ARB_occlusion_query semantics on VkQueryPool) --------------------
    // One pool per frame slot. A query issued in frame N is collected when that slot is reused (its fence has signalled),
    // so GL_QUERY_RESULT_AVAILABLE turns true about FRAMES frames later, which the game already tolerates (it polls).
    private static final int QUERY_POOL_SIZE = 4096, SAMPLES_PASSED = 0x8914, QUERY_RESULT = 0x8866, QUERY_RESULT_AVAILABLE = 0x8867;

    private static final class Q {
        int slot = -1, idx;
        boolean done = true;
        int samples = 1;   // never issued / dropped: conservatively visible
    }

    private final long[] queryPool = new long[VkCore.FRAMES];
    private final int[] queryUsed = new int[VkCore.FRAMES];
    private final boolean[] queryNeedsReset = new boolean[VkCore.FRAMES];
    @SuppressWarnings("unchecked")
    private final List<Q>[] issued = new List[VkCore.FRAMES];
    {
        java.util.Arrays.fill(queryNeedsReset, true);
        for (int i = 0; i < VkCore.FRAMES; i++) issued[i] = new ArrayList<>();
    }
    private final Map<Integer, Q> queries = new HashMap<>();
    private int nextQuery = 1;
    private Q currentQuery;

    private void createQueryPools() {
        try (MemoryStack st = stackPush()) {
            LongBuffer p = st.mallocLong(1);
            for (int i = 0; i < VkCore.FRAMES; i++) {
                check(vkCreateQueryPool(core.device, VkQueryPoolCreateInfo.calloc(st).sType$Default().queryType(VK_QUERY_TYPE_OCCLUSION)
                        .queryCount(QUERY_POOL_SIZE), null, p), "query pool");
                queryPool[i] = p.get(0);
            }
        }
        core.onSlotAcquired = this::collectQueries;
    }

    /** The slot's previous frame has finished: read back every query issued in it. */
    private void collectQueries() {
        int slot = core.slot, n = queryUsed[slot];
        if (n == 0) return;
        try (MemoryStack st = stackPush()) {
            ByteBuffer res = st.malloc(n * 8);
            int r = vkGetQueryPoolResults(core.device, queryPool[slot], 0, n, res, 8, VK_QUERY_RESULT_64_BIT);
            boolean ok = r == VK_SUCCESS;
            for (Q q : issued[slot]) {
                q.done = true;
                if (ok) {
                    long v = res.getLong(q.idx * 8);
                    q.samples = v == 0 ? 0 : (int) Math.min(v, Integer.MAX_VALUE);
                } else {
                    q.samples = 1;
                }
            }
        }
        issued[slot].clear();
        queryUsed[slot] = 0;
        queryNeedsReset[slot] = true;
    }

    @Override public int glGenQueriesARB() { int id = nextQuery++; queries.put(id, new Q()); return id; }
    @Override public void glGenQueriesARB(int[] a) { for (int i = 0; i < a.length; i++) a[i] = glGenQueriesARB(); }
    @Override public void glGenQueriesARB(IntBuffer a) { for (int i = a.position(); i < a.limit(); i++) a.put(i, glGenQueriesARB()); }

    @Override public void glBeginQueryARB(int target, int id) {
        Q q = new Q();
        queries.put(id, q);
        currentQuery = null;
        if (target != SAMPLES_PASSED || !beginDraw()) return;
        int slot = core.slot;
        if (queryUsed[slot] >= QUERY_POOL_SIZE) return;   // pool exhausted: stays "visible"
        if (queryNeedsReset[slot]) {
            VkCommandBuffer ucb = core.uploadCommands();   // executes before this frame's draw commands
            vkCmdResetQueryPool(ucb, queryPool[slot], 0, QUERY_POOL_SIZE);
            vkCmdPipelineBarrier(ucb, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, null, null, null);
            queryNeedsReset[slot] = false;
        }
        q.slot = slot;
        q.idx = queryUsed[slot]++;
        q.done = false;
        q.samples = 1;
        issued[slot].add(q);
        vkCmdBeginQuery(core.drawCmd[slot], queryPool[slot], q.idx, 0);
        currentQuery = q;
    }

    @Override public void glEndQueryARB(int target) {
        Q q = currentQuery;
        currentQuery = null;
        if (q != null) vkCmdEndQuery(core.drawCmd[q.slot], queryPool[q.slot], q.idx);
    }

    private int queryValue(int id, int pname) {
        Q q = queries.get(id);
        if (q == null) return pname == QUERY_RESULT_AVAILABLE ? 1 : 1;
        return pname == QUERY_RESULT_AVAILABLE ? (q.done ? 1 : 0) : (q.done ? q.samples : 1);
    }

    @Override public void glGetQueryObjectuiv(int id, int pname, int[] out) { out[0] = queryValue(id, pname); }
    @Override public void glGetQueryObjectuiv(int id, int pname, IntBuffer out) { out.put(out.position(), queryValue(id, pname)); }
    @Override public void glGetQueryObjectuiv(int id, int pname, long out) { throw new UnsupportedOperationException("query result into a buffer object"); }

    // ============================== display lists =============================================

    private void ensureListCap(int id) {
        if (id >= listArr.length) listArr = java.util.Arrays.copyOf(listArr, Math.max(id + 1, listArr.length * 2));
    }

    @Override public int glGenLists(int n) { int base = nextList; nextList += n; return base; }
    @Override public void glNewList(int id, int mode) {
        recording = new DisplayList();
        recording.pureEligible = BAKE_CHUNKS && matrixMode == 0;
        recordingId = id;
    }
    private void bakeVerts(int arenaIdx, long boff, int vertCount, float sx, float sy, float sz, float tx, float ty, float tz) {
        long base = listBufs.get(arenaIdx).ptr + boff;
        for (int i = 0; i < vertCount; i++) {
            long p = base + (long) i * VERTEX;
            MemoryUtil.memPutFloat(p, MemoryUtil.memGetFloat(p) * sx + tx);
            MemoryUtil.memPutFloat(p + 4, MemoryUtil.memGetFloat(p + 4) * sy + ty);
            MemoryUtil.memPutFloat(p + 8, MemoryUtil.memGetFloat(p + 8) * sz + tz);
        }
    }

    @Override public void glEndList() {
        if (recording.pureEligible && recording.recDepth == 0 && recording.recDrawn && recording.arenaIdx >= 0) {
            float sx = recording.recSx, sy = recording.recSy, sz = recording.recSz;
            float tx = recording.recTx, ty = recording.recTy, tz = recording.recTz;
            if (sx != 1f || sy != 1f || sz != 1f || tx != 0f || ty != 0f || tz != 0f) {
                bakeVerts(recording.arenaIdx, recording.recBoff, recording.recVertCount, sx, sy, sz, tx, ty, tz);
                if (recording.recHasCutout) {
                    bakeVerts(recording.cutArenaIdx, recording.cutBoff, recording.cutVertCount, sx, sy, sz, tx, ty, tz);
                }
            }
            recording.noAlpha = recording.recHasSolid;
            recording.pureQuad = true;
            recording.ops.clear();
        } else {
            recording.pureQuad = recording.ops.size() == 1 && recording.arenaIdx >= 0;
        }
        ensureListCap(recordingId);
        DisplayList old = listArr[recordingId];
        listArr[recordingId] = recording;
        if (old != null) freeList(old);
        recording = null;
    }
    @Override public void glDeleteLists(int base, int range) {
        for (int i = 0; i < range; i++) {
            int id = base + i;
            if (id >= 0 && id < listArr.length) {
                DisplayList l = listArr[id];
                listArr[id] = null;
                if (l != null) freeList(l);
            }
        }
    }
    private void addListArena() {
        listBufs.add(core.createBuffer(LIST_ARENA, VK_BUFFER_USAGE_VERTEX_BUFFER_BIT));
        listArenas.add(new Arena(LIST_ARENA, VERTEX));
        if (listArenas.size() > 1) System.out.println("[betavk] mesh memory grown to " + listArenas.size() + " x " + (LIST_ARENA >> 20) + " MB");
    }

    private void freeList(DisplayList l) {
        List<long[]> blocks = l.blocks;
        deferred(() -> { for (long[] b : blocks) listArenas.get((int) b[2]).release(b[0], b[1]); });
    }
    @Override public void glCallList(int id) {
        if (rec(() -> glCallList(id))) return;
        DisplayList l = (id >= 0 && id < listArr.length) ? listArr[id] : null;
        if (l == null) return;
        if (l.pureQuad) {
            if (l.noAlpha) {
                boolean saved = alphaTest;
                if (saved) { alphaTest = false; paramsDirty = true; }
                drawQuadRange(listBufs.get(l.arenaIdx).buffer, l.firstVertex, l.indexCount, l.arrayFlags);
                if (saved) { alphaTest = true; paramsDirty = true; }
                if (l.cutArenaIdx >= 0) {
                    drawQuadRange(listBufs.get(l.cutArenaIdx).buffer, l.cutFirstVertex, l.cutIndexCount, l.cutArrayFlags);
                }
            } else {
                drawQuadRange(listBufs.get(l.arenaIdx).buffer, l.firstVertex, l.indexCount, l.arrayFlags);
            }
        } else {
            for (Runnable op : l.ops) op.run();
        }
    }
    @Override public void glCallLists(IntBuffer ids) {
        if (recording != null) {
            for (int i = ids.position(), lim = ids.limit(); i < lim; i++) glCallList(ids.get(i));
            return;
        }
        if (!INDIRECT || !core.multiDrawIndirect) {
            boolean savedAlpha = alphaTest;
            boolean anyCutout = false;
            for (int i = ids.position(), lim = ids.limit(); i < lim; i++) {
                int id = ids.get(i);
                DisplayList l = (id >= 0 && id < listArr.length) ? listArr[id] : null;
                if (l == null) continue;
                if (l.pureQuad) {
                    if (l.noAlpha) {
                        if (alphaTest) { alphaTest = false; paramsDirty = true; }
                        drawQuadRange(listBufs.get(l.arenaIdx).buffer, l.firstVertex, l.indexCount, l.arrayFlags);
                        if (l.cutArenaIdx >= 0) anyCutout = true;
                    } else {
                        anyCutout = true;
                    }
                } else {
                    if (alphaTest != savedAlpha) { alphaTest = savedAlpha; paramsDirty = true; }
                    for (Runnable op : l.ops) op.run();
                    savedAlpha = alphaTest;
                }
            }
            if (alphaTest != savedAlpha) { alphaTest = savedAlpha; paramsDirty = true; }
            if (anyCutout) {
                for (int i = ids.position(), lim = ids.limit(); i < lim; i++) {
                    int id = ids.get(i);
                    DisplayList l = (id >= 0 && id < listArr.length) ? listArr[id] : null;
                    if (l == null || !l.pureQuad) continue;
                    if (!l.noAlpha) {
                        drawQuadRange(listBufs.get(l.arenaIdx).buffer, l.firstVertex, l.indexCount, l.arrayFlags);
                    } else if (l.cutArenaIdx >= 0) {
                        drawQuadRange(listBufs.get(l.cutArenaIdx).buffer, l.cutFirstVertex, l.cutIndexCount, l.cutArrayFlags);
                    }
                }
            }
            return;
        }
        int commonFlags = -1;
        for (int i = ids.position(), lim = ids.limit(); i < lim; i++) {
            int id = ids.get(i);
            DisplayList l = (id >= 0 && id < listArr.length) ? listArr[id] : null;
            if (l == null) continue;
            if (!l.pureQuad || l.noAlpha || (commonFlags >= 0 && l.arrayFlags != commonFlags)) {
                flushIndirect(commonFlags);
                commonFlags = -1;
                if (l.pureQuad && !l.noAlpha) {
                    commonFlags = l.arrayFlags;
                    appendIndirect(l);
                } else {
                    glCallList(id);
                }
            } else {
                commonFlags = l.arrayFlags;
                appendIndirect(l);
            }
        }
        flushIndirect(commonFlags);
    }

    private void appendIndirect(DisplayList l) {
        int ai = l.arenaIdx;
        int n = indCount[ai];
        int[] buf = indData[ai];
        if (buf == null) buf = indData[ai] = new int[4096 * 2];
        else if (n * 2 + 2 > buf.length) buf = indData[ai] = java.util.Arrays.copyOf(buf, buf.length * 2);
        buf[n * 2] = l.firstVertex;
        buf[n * 2 + 1] = l.indexCount;
        indCount[ai] = n + 1;
    }

    private void flushIndirect(int arrayFlags) {
        if (arrayFlags < 0) return;
        boolean prepared = false;
        for (int ai = 0, nArenas = listArenas.size(); ai < nArenas; ai++) {
            int count = indCount[ai];
            if (count <= 0) continue;
            indCount[ai] = 0;
            int[] data = indData[ai];
            long vbuf = listBufs.get(ai).buffer;
            if (!prepared) {
                if (!prepareDraw(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST, true, arrayFlags, vbuf)) return;
                prepared = true;
            } else if (vbuf != boundVB) {
                vkCmdBindVertexBuffers(core.drawCmd[core.slot], 0, stackLong1(vbuf), ZERO_OFFSET);
                boundVB = vbuf;
            }
            VkCommandBuffer cb = core.drawCmd[core.slot];
            if (count == 1) {
                vkCmdDrawIndexed(cb, data[1], 1, 0, data[0], 0);
                continue;
            }
            int slot = core.slot;
            int maxCmds = Math.max(1, core.maxDrawIndirectCount);
            int done = 0;
            while (done < count) {
                int batch = Math.min(count - done, maxCmds);
                long bytes = (long) batch * 20;
                if (indirectOff[slot] + bytes > INDIRECT_RING) {
                    for (int k = done; k < done + batch; k++) vkCmdDrawIndexed(cb, data[k * 2 + 1], 1, 0, data[k * 2], 0);
                    done += batch;
                    continue;
                }
                long off = indirectOff[slot];
                indirectOff[slot] += bytes;
                long ptr = indirectRing[slot].ptr + off;
                for (int k = 0; k < batch; k++) {
                    long p = ptr + (long) k * 20;
                    MemoryUtil.memPutInt(p, data[(done + k) * 2 + 1]);   // indexCount
                    MemoryUtil.memPutInt(p + 4, 1);                      // instanceCount
                    MemoryUtil.memPutInt(p + 8, 0);                      // firstIndex
                    MemoryUtil.memPutInt(p + 12, data[(done + k) * 2]);  // vertexOffset
                    MemoryUtil.memPutInt(p + 16, 0);                     // firstInstance
                }
                vkCmdDrawIndexedIndirect(cb, indirectRing[slot].buffer, off, batch, 20);
                done += batch;
            }
        }
    }
    @Override public void glCallLists(ByteBuffer ids) { for (int i = ids.position(); i < ids.limit(); i++) glCallList(ids.get(i) & 0xFF); }
    @Override public void glCallLists(ShortBuffer ids) { for (int i = ids.position(); i < ids.limit(); i++) glCallList(ids.get(i) & 0xFFFF); }
    @Override public void glCallLists(int n, ByteBuffer ids) { throw new UnsupportedOperationException("glCallLists(int, ByteBuffer)"); }

    private void deferred(Runnable r) {
        dying.add(new Object[]{core.frameCounter + VkCore.FRAMES + 1, r});
    }

    // ============================== draws =====================================================

    @Override public void glDrawArrays(int mode, int first, int count) {
        if (!vertexArray || count <= 0) return;
        long src = vertexAddr + (long) first * VERTEX;
        long bytes = (long) count * VERTEX;
        int flags = (texArray ? 1 : 0) | (colorArray ? 2 : 0) | (normalArray ? 4 : 0);
        if (DBG.equals("dumpnormals") && normalArray && dumped < 12 && count <= 8) {
            dumped++;
            StringBuilder sb = new StringBuilder("[dbg] draw mode=" + mode + " count=" + count + " recording=" + (recording != null) + ":");
            for (int v = 0; v < Math.min(count, 4); v++) {
                long a0 = src + (long) v * VERTEX;
                sb.append(String.format(" v%d pos=(%.2f,%.2f,%.2f) rgba=%08x nrm=(%d,%d,%d,%d)", v, MemoryUtil.memGetFloat(a0), MemoryUtil.memGetFloat(a0 + 4), MemoryUtil.memGetFloat(a0 + 8),
                        MemoryUtil.memGetInt(a0 + 20), MemoryUtil.memGetByte(a0 + 24), MemoryUtil.memGetByte(a0 + 25), MemoryUtil.memGetByte(a0 + 26), MemoryUtil.memGetByte(a0 + 27)));
            }
            System.out.println(sb);
        }
        if (recording != null) {
            long off = -1;
            int ai = 0;
            for (; ai < listArenas.size() && (off = listArenas.get(ai).alloc(bytes)) < 0; ai++) { }
            if (off < 0 && listArenas.size() < MAX_LIST_ARENAS) {
                addListArena();
                ai = listArenas.size() - 1;
                off = listArenas.get(ai).alloc(bytes);
            }
            if (off < 0) {
                if (arenaDrops++ < 5) System.err.printf("[betavk] display-list arenas full (%d x %d MB); dropping a draw of %d KB%n", listArenas.size(), LIST_ARENA >> 20, bytes >> 10);
                return;
            }
            final long boff = off;
            final Buf lb = listBufs.get(ai);
            MemoryUtil.memCopy(src, lb.ptr + boff, bytes);
            recording.blocks.add(new long[]{boff, bytes, ai});
            if (mode == GL_QUADS && count <= MAX_QUADS * 4 && recording.pureEligible && recording.recDepth == 1 && !normalArray) {
                if (recording.recAlphaDisabled && !recording.recHasSolid) {
                    recording.arenaIdx = ai;
                    recording.firstVertex = (int) (boff / VERTEX);
                    recording.indexCount = count / 4 * 6;
                    recording.arrayFlags = flags;
                    recording.recHasSolid = true;
                    recording.recDrawn = true;
                    recording.recBoff = boff;
                    recording.recVertCount = count;
                } else if (!recording.recAlphaDisabled && !recording.recDrawn) {
                    recording.arenaIdx = ai;
                    recording.firstVertex = (int) (boff / VERTEX);
                    recording.indexCount = count / 4 * 6;
                    recording.arrayFlags = flags;
                    recording.recDrawn = true;
                    recording.recBoff = boff;
                    recording.recVertCount = count;
                } else if (!recording.recAlphaDisabled && recording.recHasSolid && !recording.recHasCutout) {
                    recording.cutArenaIdx = ai;
                    recording.cutFirstVertex = (int) (boff / VERTEX);
                    recording.cutIndexCount = count / 4 * 6;
                    recording.cutArrayFlags = flags;
                    recording.recHasCutout = true;
                    recording.cutBoff = boff;
                    recording.cutVertCount = count;
                } else {
                    recording.pureEligible = false;
                }
            } else if (mode == GL_QUADS && count <= MAX_QUADS * 4 && recording.arenaIdx < 0) {
                recording.arenaIdx = ai;
                recording.firstVertex = (int) (boff / VERTEX);
                recording.indexCount = count / 4 * 6;
                recording.arrayFlags = flags;
                recording.pureEligible = false;
            } else {
                recording.pureEligible = false;
            }
            recording.ops.add(() -> drawBuffer(lb.buffer, boff, mode, count, flags));
            return;
        }
        int slot = core.slot;
        if (ringStamp[slot] != core.frameCounter) resetRings(slot);
        if (vertexOff[slot] + bytes > VERTEX_RING) {
            System.err.println("[betavk] vertex ring overflow; dropping a draw");
            return;
        }
        long off = vertexOff[slot];
        vertexOff[slot] += (bytes + VERTEX - 1) / VERTEX * VERTEX;
        MemoryUtil.memCopy(src, vertexRing[slot].ptr + off, bytes);
        drawBuffer(vertexRing[slot].buffer, off, mode, count, flags);
    }

    private void resetRings(int slot) {
        ringStamp[slot] = core.frameCounter;
        uboOff[slot] = vertexOff[slot] = stagingOff[slot] = indirectOff[slot] = 0;
    }

    private boolean beginDraw() {
        boolean was = core.frameActive;
        if (!was) flushPendingMips();
        if (!core.ensureFrame(clearColor, clearDepth)) return false;
        if (!was) {
            boundPipeline = 0;
            boundVB = 0;
            boundTexSet = 0;
            boundParamOff = -1;
            indexBound = false;
            viewportDirty = true;
            paramsDirty = true;
            lastParamFlags = -1;
            if (ringStamp[core.slot] != core.frameCounter) resetRings(core.slot);
        }
        return true;
    }

    private final LongBuffer tmpLong1 = MemoryUtil.memAllocLong(1);
    private static final LongBuffer ZERO_OFFSET = MemoryUtil.memCallocLong(1);

    private LongBuffer stackLong1(long v) {
        tmpLong1.put(0, v);
        return tmpLong1;
    }

    private boolean prepareDraw(int topo, boolean quads, int arrayFlags, long vbuf) {
        if (!beginDraw()) return false;
        VkCommandBuffer cb = core.drawCmd[core.slot];
        long pipe = pipeline(topo);
        if (pipe != boundPipeline) {
            vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, pipe);
            boundPipeline = pipe;
        }
        int slot = core.slot;
        int off = paramsOffset(slot, arrayFlags);
        Tex t = tex2d ? curTex : null;
        if (t == null || t.image == 0) t = whiteTex;
        long texSet = textureSet(t);
        boolean bindDesc = !BIND_CACHE || off != boundParamOff || texSet != boundTexSet;
        if (viewportDirty || bindDesc || vbuf != boundVB) {
            try (MemoryStack st = stackPush()) {
                if (viewportDirty) {
                    int w = vw > 0 ? vw : core.width, h = vh > 0 ? vh : core.height;
                    // negative height flips Y so GL's bottom-left origin and winding carry over unchanged
                    vkCmdSetViewport(cb, 0, VkViewport.calloc(1, st).x(vx).y(core.height - vy).width(w).height(-h).minDepth(0).maxDepth(1));
                    VkRect2D.Buffer sc = VkRect2D.calloc(1, st);
                    sc.offset().set(Math.max(0, vx), Math.max(0, core.height - (vy + h)));
                    sc.extent().set(Math.max(1, w), Math.max(1, h));
                    vkCmdSetScissor(cb, 0, sc);
                    viewportDirty = false;
                }
                if (bindDesc) {
                    vkCmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, pipelineLayout, 0, st.longs(paramsSet[slot], texSet), st.ints(off));
                    boundParamOff = off;
                    boundTexSet = texSet;
                }
                if (vbuf != boundVB) {
                    vkCmdBindVertexBuffers(cb, 0, st.longs(vbuf), st.longs(0));
                    boundVB = vbuf;
                }
            }
        }
        if (polyOffset) vkCmdSetDepthBias(cb, -polyUnits, 0f, -polyFactor);   // reversed depth: 'further away' is a smaller value
        if (quads && !indexBound) {
            vkCmdBindIndexBuffer(cb, quadIndexBuf.buffer, 0, VK_INDEX_TYPE_UINT32);
            indexBound = true;
        }
        return true;
    }

    private void drawQuadRange(long vbuf, int firstVertex, int indexCount, int arrayFlags) {
        if (!prepareDraw(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST, true, arrayFlags, vbuf)) return;
        vkCmdDrawIndexed(core.drawCmd[core.slot], indexCount, 1, 0, firstVertex, 0);
    }

    private void drawBuffer(long vbuf, long byteOffset, int mode, int count, int arrayFlags) {
        int topo;
        boolean quads = false;
        switch (mode) {
            case GL_QUADS -> { topo = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST; quads = true; }
            case 4 -> topo = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
            case 5 -> topo = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP;
            case 6, 9 -> topo = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_FAN;
            case 1 -> topo = VK_PRIMITIVE_TOPOLOGY_LINE_LIST;
            case 3 -> topo = VK_PRIMITIVE_TOPOLOGY_LINE_STRIP;
            default -> { return; }
        }
        if (!prepareDraw(topo, quads, arrayFlags, vbuf)) return;
        VkCommandBuffer cb = core.drawCmd[core.slot];
        int firstVertex = (int) (byteOffset / VERTEX);
        if (quads) {
            int done = 0;
            while (done < count) {
                int n = Math.min(count - done, MAX_QUADS * 4) / 4 * 4;
                if (n == 0) break;
                vkCmdDrawIndexed(cb, n / 4 * 6, 1, 0, firstVertex + done, 0);
                done += n;
            }
        } else {
            vkCmdDraw(cb, count, 1, firstVertex, 0);
        }
    }

    private int paramsOffset(int slot, int arrayFlags) {
        if (!paramsDirty && arrayFlags == lastParamFlags) return (int) lastParamOff;
        if (uboOff[slot] + PARAMS_SIZE > UBO_RING) {
            System.err.println("[betavk] uniform ring overflow");
            return (int) lastParamOff;
        }
        long off = uboOff[slot];
        uboOff[slot] += (PARAMS_SIZE + 255) / 256 * 256;
        FloatBuffer f = MemoryUtil.memFloatBuffer(uboRing[slot].ptr + off, PARAMS_SIZE / 4);
        float[] mvp = mvpTmp;
        mul(stacks[1][sp[1]], stacks[0][sp[0]], mvp);
        float[] pm = stacks[1][sp[1]];
        boolean persp = pm[11] == -1f && pm[15] == 0f;
        if (persp) {
            // Reversed Z, finite far: depth = n (f - w) / ((f - n) w), 1 at the near plane and 0 at the far plane, so float depth precision stays even
            // over the whole range (the standard mapping loses it all in the distance). Written as z = A + B * w so nothing cancels; the game's own
            // projection matrix stays standard for the things that read it (frustum culling).
            float near = pm[14] / (pm[10] - 1f), far = pm[14] / (pm[10] + 1f);
            float a = near * far / (far - near), b = -near / (far - near);
            mvp[2] = b * mvp[3];
            mvp[6] = b * mvp[7];
            mvp[10] = b * mvp[11];
            mvp[14] = a + b * mvp[15];
        }
        f.put(mvp).put(stacks[0][sp[0]]);
        f.put(color).put(normal[0]).put(normal[1]).put(normal[2]).put(persp ? 1f : 0f);   // normal.w: this draw uses a perspective projection (reversed-Z depth built on the CPU)
        f.put(fogColor);
        int fm = !fog ? 0 : fogMode == 0x2601 ? 1 : fogMode == 0x800 ? 2 : 3;
        f.put(fogStart).put(fogEnd).put(fogDensity).put(fm);
        f.put(alphaOp).put(alphaRef).put(alphaTest ? 1f : 0f).put(fogRadial ? 1f : 0f);
        String dbg = DBG;
        if (dbg.equals("nonormals")) arrayFlags &= ~4;
        if (dbg.equals("nocolors")) arrayFlags &= ~2;
        boolean hasTex = tex2d && (arrayFlags & 1) != 0;
        boolean light = lighting && !dbg.equals("nolight");
        f.put(hasTex ? 1f : 0f).put((arrayFlags & 2) != 0 ? 1f : 0f).put(light ? 1f : 0f).put((arrayFlags & 4) != 0 ? 1f : 0f);
        f.put(lightDir[0]);
        f.put(light0 ? lightDiff[0] : ZERO4);
        f.put(lightDir[1]);
        f.put(light1 ? lightDiff[1] : ZERO4);
        f.put(ambient);
        paramsDirty = false;
        lastParamFlags = arrayFlags;
        lastParamOff = off;
        return (int) off;
    }

    private long pipeline(int topo) {
        boolean useAlpha = alphaTest && !DBG.equals("noalpha");
        long key = topo | (blend ? 1L << 4 : 0) | ((long) blendSrc << 5) | ((long) blendDst << 17) | (depthTest ? 1L << 29 : 0)
                | ((long) (depthOp & 7) << 30) | (depthWrite ? 1L << 33 : 0) | (cull ? 1L << 34 : 0) | ((cullMode == 0x404 ? 1L : 0) << 35)
                | ((long) colorMask << 36) | (polyOffset ? 1L << 40 : 0) | (useAlpha ? 1L << 41 : 0);
        if (key == lastPipeKey) return lastPipe;
        Long cached = pipelines.get(key);
        long p = cached != null ? cached : createPipeline(topo, useAlpha);
        if (cached == null) pipelines.put(key, p);
        lastPipeKey = key;
        lastPipe = p;
        return p;
    }

    private static int vkFactor(int gl) {
        return switch (gl) {
            case 0 -> VK_BLEND_FACTOR_ZERO;
            case 1 -> VK_BLEND_FACTOR_ONE;
            case 0x300 -> VK_BLEND_FACTOR_SRC_COLOR;
            case 0x301 -> VK_BLEND_FACTOR_ONE_MINUS_SRC_COLOR;
            case 0x302 -> VK_BLEND_FACTOR_SRC_ALPHA;
            case 0x303 -> VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
            case 0x304 -> VK_BLEND_FACTOR_DST_ALPHA;
            case 0x305 -> VK_BLEND_FACTOR_ONE_MINUS_DST_ALPHA;
            case 0x306 -> VK_BLEND_FACTOR_DST_COLOR;
            case 0x307 -> VK_BLEND_FACTOR_ONE_MINUS_DST_COLOR;
            default -> VK_BLEND_FACTOR_ONE;
        };
    }

    private long createPipeline(int topo, boolean useAlpha) {
        try (MemoryStack st = stackPush()) {
            VkSpecializationMapEntry.Buffer specMap = VkSpecializationMapEntry.calloc(1, st).constantID(0).offset(0).size(4);
            VkSpecializationInfo spec = VkSpecializationInfo.calloc(st).pMapEntries(specMap).pData(st.bytes((byte) (useAlpha ? 1 : 0), (byte) 0, (byte) 0, (byte) 0));
            VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo.calloc(2, st);
            stages.get(0).sType$Default().stage(VK_SHADER_STAGE_VERTEX_BIT).module(vertModule).pName(st.UTF8("main"));
            stages.get(1).sType$Default().stage(VK_SHADER_STAGE_FRAGMENT_BIT).module(fragModule).pName(st.UTF8("main")).pSpecializationInfo(spec);
            VkVertexInputBindingDescription.Buffer vb = VkVertexInputBindingDescription.calloc(1, st).binding(0).stride(VERTEX).inputRate(VK_VERTEX_INPUT_RATE_VERTEX);
            VkVertexInputAttributeDescription.Buffer va = VkVertexInputAttributeDescription.calloc(4, st);
            va.get(0).location(0).binding(0).format(VK_FORMAT_R32G32B32_SFLOAT).offset(0);
            va.get(1).location(1).binding(0).format(VK_FORMAT_R32G32_SFLOAT).offset(12);
            va.get(2).location(2).binding(0).format(VK_FORMAT_R8G8B8A8_UNORM).offset(20);
            va.get(3).location(3).binding(0).format(VK_FORMAT_R8G8B8A8_SNORM).offset(24);
            VkPipelineVertexInputStateCreateInfo vi = VkPipelineVertexInputStateCreateInfo.calloc(st).sType$Default().pVertexBindingDescriptions(vb).pVertexAttributeDescriptions(va);
            VkPipelineInputAssemblyStateCreateInfo ia = VkPipelineInputAssemblyStateCreateInfo.calloc(st).sType$Default().topology(topo);
            VkPipelineViewportStateCreateInfo vp = VkPipelineViewportStateCreateInfo.calloc(st).sType$Default().viewportCount(1).scissorCount(1);
            VkPipelineRasterizationStateCreateInfo rs = VkPipelineRasterizationStateCreateInfo.calloc(st).sType$Default().polygonMode(VK_POLYGON_MODE_FILL)
                    .cullMode(!cull ? VK_CULL_MODE_NONE : cullMode == 0x404 ? VK_CULL_MODE_FRONT_BIT : VK_CULL_MODE_BACK_BIT)
                    .frontFace(Boolean.getBoolean("betavk.cw") ? VK_FRONT_FACE_CLOCKWISE : VK_FRONT_FACE_COUNTER_CLOCKWISE)   // verified empirically with the negative-height viewport
                    .depthBiasEnable(polyOffset).lineWidth(1f);
            VkPipelineMultisampleStateCreateInfo ms = VkPipelineMultisampleStateCreateInfo.calloc(st).sType$Default().rasterizationSamples(VK_SAMPLE_COUNT_1_BIT);
            VkPipelineDepthStencilStateCreateInfo ds = VkPipelineDepthStencilStateCreateInfo.calloc(st).sType$Default().depthTestEnable(depthTest)
                    .depthWriteEnable(depthWrite && depthTest).depthCompareOp(reverseCompare(depthOp & 7));
            VkPipelineColorBlendAttachmentState.Buffer cba = VkPipelineColorBlendAttachmentState.calloc(1, st).blendEnable(blend)
                    .srcColorBlendFactor(vkFactor(blendSrc)).dstColorBlendFactor(vkFactor(blendDst)).colorBlendOp(VK_BLEND_OP_ADD)
                    .srcAlphaBlendFactor(vkFactor(blendSrc)).dstAlphaBlendFactor(vkFactor(blendDst)).alphaBlendOp(VK_BLEND_OP_ADD).colorWriteMask(colorMask);
            VkPipelineColorBlendStateCreateInfo cbs = VkPipelineColorBlendStateCreateInfo.calloc(st).sType$Default().pAttachments(cba);
            VkPipelineDynamicStateCreateInfo dyn = VkPipelineDynamicStateCreateInfo.calloc(st).sType$Default()
                    .pDynamicStates(st.ints(VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR, VK_DYNAMIC_STATE_DEPTH_BIAS));
            VkGraphicsPipelineCreateInfo.Buffer pci = VkGraphicsPipelineCreateInfo.calloc(1, st).sType$Default().pStages(stages).pVertexInputState(vi)
                    .pInputAssemblyState(ia).pViewportState(vp).pRasterizationState(rs).pMultisampleState(ms).pDepthStencilState(ds)
                    .pColorBlendState(cbs).pDynamicState(dyn).layout(pipelineLayout).renderPass(core.renderPass).subpass(0);
            LongBuffer p = st.mallocLong(1);
            check(vkCreateGraphicsPipelines(core.device, pipelineCache, pci, null, p), "vkCreateGraphicsPipelines");
            return p.get(0);
        }
    }

    @Override public void glClear(int mask) {
        if (!beginDraw()) return;
        try (MemoryStack st = stackPush()) {
            int n = ((mask & COLOR_BUFFER_BIT) != 0 ? 1 : 0) + ((mask & DEPTH_BUFFER_BIT) != 0 ? 1 : 0);
            if (n == 0) return;
            VkClearAttachment.Buffer ca = VkClearAttachment.calloc(n, st);
            int i = 0;
            if ((mask & COLOR_BUFFER_BIT) != 0) {
                ca.get(i).aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).colorAttachment(0).clearValue().color().float32(st.floats(clearColor));
                i++;
            }
            if ((mask & DEPTH_BUFFER_BIT) != 0) ca.get(i).aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT).clearValue().depthStencil().depth(1f - clearDepth);
            VkClearRect.Buffer cr = VkClearRect.calloc(1, st).baseArrayLayer(0).layerCount(1);
            cr.rect().offset().set(0, 0);
            cr.rect().extent().set(core.width, core.height);
            vkCmdClearAttachments(core.drawCmd[core.slot], ca, cr);
        }
    }

    // ============================== textures ==================================================

    @Override public int glGenTextures() { return nextTex++; }
    @Override public void glGenTextures(int[] a) { for (int i = 0; i < a.length; i++) a[i] = nextTex++; }
    @Override public void glGenTextures(IntBuffer a) { for (int i = a.position(); i < a.limit(); i++) a.put(i, nextTex++); }
    @Override public void glDeleteTextures(int id) {
        Tex t = textures.remove(id);
        if (id == boundTex) curTex = null;
        if (t == null || t.image == 0) return;
        destroyTex(t);
    }
    @Override public void glDeleteTextures(int[] a) { for (int id : a) glDeleteTextures(id); }
    @Override public void glDeleteTextures(IntBuffer a) { for (int i = a.position(); i < a.limit(); i++) glDeleteTextures(a.get(i)); }
    @Override public void glBindTexture(int target, int id) {
        if (rec(() -> glBindTexture(target, id))) return;
        if (id == boundTex && curTex != null) return;
        boundTex = id;
        curTex = textures.computeIfAbsent(id, k -> new Tex());
    }

    @Override public void glTexParameteri(int target, int pname, int v) {
        Tex t = (curTex != null && boundTex != 0) ? curTex : textures.computeIfAbsent(boundTex, k -> new Tex());
        switch (pname) {
            case 0x2801 -> t.minF = v;
            case 0x2800 -> t.magF = v;
            case 0x2802 -> t.wrapS = v;
            case 0x2803 -> t.wrapT = v;
            default -> { return; }
        }
        t.lastSmpKey = -1;
        t.lastSet = 0;
    }

    private void destroyTex(Tex t) {
        if (t.mipsDirty) {
            t.mipsDirty = false;
            pendingMips.remove(t);
        }
        long image = t.image, memory = t.memory, view = t.view;
        List<Long> sets = new ArrayList<>(t.sets.values());
        t.image = 0;
        t.lastSmpKey = -1;
        t.lastSet = 0;
        t.sets.clear();
        deferred(() -> {
            for (long s : sets) vkFreeDescriptorSets(core.device, descPool, s);
            vkDestroyImageView(core.device, view, null);
            vkDestroyImage(core.device, image, null);
            vkFreeMemory(core.device, memory, null);
        });
    }

    private void createImage(Tex t, int w, int h) {
        t.w = w;
        t.h = h;
        t.mips = 32 - Integer.numberOfLeadingZeros(Math.max(w, h));
        try (MemoryStack st = stackPush()) {
            VkImageCreateInfo ci = VkImageCreateInfo.calloc(st).sType$Default().imageType(VK_IMAGE_TYPE_2D).format(VK_FORMAT_R8G8B8A8_UNORM)
                    .mipLevels(t.mips).arrayLayers(1).samples(VK_SAMPLE_COUNT_1_BIT).tiling(VK_IMAGE_TILING_OPTIMAL)
                    .usage(VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE).initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            ci.extent().set(w, h, 1);
            LongBuffer p = st.mallocLong(1);
            check(vkCreateImage(core.device, ci, null, p), "texture image");
            t.image = p.get(0);
            t.memory = core.allocForImage(t.image);
            VkImageViewCreateInfo vi = VkImageViewCreateInfo.calloc(st).sType$Default().image(t.image).viewType(VK_IMAGE_VIEW_TYPE_2D).format(VK_FORMAT_R8G8B8A8_UNORM);
            vi.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(t.mips).layerCount(1);
            check(vkCreateImageView(core.device, vi, null, p), "texture view");
            t.view = p.get(0);
        }
    }

    private int samplerKey(Tex t) {
        boolean mip = t.minF >= 0x2700;
        boolean minLinear = t.minF == 0x2601 || t.minF == 0x2701 || t.minF == 0x2703;
        boolean mipLinear = t.minF == 0x2702 || t.minF == 0x2703;
        return (mip ? 1 : 0) | (minLinear ? 2 : 0) | (mipLinear ? 4 : 0) | (t.magF == 0x2601 ? 8 : 0)
                | ((t.wrapS == 0x2901 ? 1 : 0) << 4) | ((t.wrapT == 0x2901 ? 1 : 0) << 5);
    }

    private long sampler(Tex t, int key) {
        Long s = samplers.get(key);
        if (s != null) return s;
        boolean mip = (key & 1) != 0, minLinear = (key & 2) != 0, mipLinear = (key & 4) != 0;
        try (MemoryStack st = stackPush()) {
            LongBuffer p = st.mallocLong(1);
            boolean aniso = mip && minLinear;
            check(vkCreateSampler(core.device, VkSamplerCreateInfo.calloc(st).sType$Default()
                    .magFilter(t.magF == 0x2601 ? VK_FILTER_LINEAR : VK_FILTER_NEAREST).minFilter(minLinear ? VK_FILTER_LINEAR : VK_FILTER_NEAREST)
                    .mipmapMode(mipLinear ? VK_SAMPLER_MIPMAP_MODE_LINEAR : VK_SAMPLER_MIPMAP_MODE_NEAREST)
                    .addressModeU(t.wrapS == 0x2901 ? VK_SAMPLER_ADDRESS_MODE_REPEAT : VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeV(t.wrapT == 0x2901 ? VK_SAMPLER_ADDRESS_MODE_REPEAT : VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeW(VK_SAMPLER_ADDRESS_MODE_REPEAT).anisotropyEnable(aniso).maxAnisotropy(aniso ? Math.min(16f, core.maxAnisotropy) : 1f)
                    .minLod(0f).maxLod(mip ? VK_LOD_CLAMP_NONE : 0f).borderColor(VK_BORDER_COLOR_FLOAT_TRANSPARENT_BLACK), null, p), "sampler");
            samplers.put(key, p.get(0));
            return p.get(0);
        }
    }

    private long textureSet(Tex t) {
        int skey = samplerKey(t);
        if (t.lastSet != 0 && t.lastSmpKey == skey) return t.lastSet;
        long smp = sampler(t, skey);
        int key = (int) (smp ^ (smp >>> 32));
        Long set = t.sets.get(key);
        if (set == null) {
            try (MemoryStack st = stackPush()) {
                LongBuffer p = st.mallocLong(1);
                check(vkAllocateDescriptorSets(core.device, VkDescriptorSetAllocateInfo.calloc(st).sType$Default().descriptorPool(descPool).pSetLayouts(st.longs(setLayoutTex)), p), "alloc texture set");
                VkDescriptorImageInfo.Buffer ii = VkDescriptorImageInfo.calloc(1, st).sampler(smp).imageView(t.view).imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                vkUpdateDescriptorSets(core.device, VkWriteDescriptorSet.calloc(1, st).sType$Default().dstSet(p.get(0)).dstBinding(0)
                        .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).pImageInfo(ii), null);
                set = p.get(0);
                t.sets.put(key, set);
            }
        }
        t.lastSmpKey = skey;
        t.lastSet = set;
        return set;
    }

    private static ByteBuffer rgba(Buffer data, int w, int h) {
        int n = w * h * 4;
        ByteBuffer out = MemoryUtil.memAlloc(n);
        if (data == null) {
            MemoryUtil.memSet(out, 0);
            return out;
        }
        ByteBuffer src = data instanceof ByteBuffer bb ? bb.duplicate() : MemoryUtil.memByteBuffer(MemoryUtil.memAddress0(data) + (long) data.position() * 4, data.remaining() * 4);
        if (src.remaining() >= n) {
            for (int i = 0; i < n; i++) out.put(i, src.get(src.position() + i));
        } else if (src.remaining() >= w * h * 3) {   // RGB -> RGBA
            for (int i = 0; i < w * h; i++) {
                out.put(i * 4, src.get(src.position() + i * 3)).put(i * 4 + 1, src.get(src.position() + i * 3 + 1))
                        .put(i * 4 + 2, src.get(src.position() + i * 3 + 2)).put(i * 4 + 3, (byte) -1);
            }
        } else {
            throw new IllegalStateException("texture data too small: " + src.remaining() + " bytes for " + w + "x" + h);
        }
        return out;
    }

    private void texImage(int level, int w, int h, Buffer data) {
        if (level != 0) return;   // mip levels are generated on the GPU
        Tex t = textures.computeIfAbsent(boundTex, k -> new Tex());
        curTex = t;
        if (t.mipsDirty) {
            t.mipsDirty = false;
            pendingMips.remove(t);
        }
        if (t.image != 0 && (t.w != w || t.h != h)) destroyTex(t);
        if (t.image == 0) createImage(t, w, h);
        ByteBuffer px = rgba(data, w, h);
        upload(t, 0, 0, w, h, px, true);
    }

    private void texSubImage(int level, int x, int y, int w, int h, Buffer data) {
        if (level != 0) return;
        Tex t = textures.get(boundTex);
        if (t == null || t.image == 0) return;
        upload(t, x, y, w, h, rgba(data, w, h), false);
    }

    /** Copy RGBA pixels into level 0 and regenerate the mip chain on the GPU. */
    private void upload(Tex t, int x, int y, int w, int h, ByteBuffer px, boolean immediate) {
        long bytes = (long) w * h * 4;
        if (immediate) {
            Buf staging = core.createBuffer(bytes, VK_BUFFER_USAGE_TRANSFER_SRC_BIT);
            MemoryUtil.memCopy(MemoryUtil.memAddress(px), staging.ptr, bytes);
            core.immediate(cb -> recordUpload(cb, t, x, y, w, h, staging.buffer, 0, true, true));
            core.destroyBuffer(staging);
        } else {
            int slot = core.slot;
            if (ringStamp[slot] != core.frameCounter) resetRings(slot);
            if (stagingOff[slot] + bytes > STAGING_RING) {
                System.err.println("[betavk] staging ring overflow; dropping a texture update");
                MemoryUtil.memFree(px);
                return;
            }
            long off = stagingOff[slot];
            stagingOff[slot] += (bytes + 255) / 256 * 256;
            MemoryUtil.memCopy(MemoryUtil.memAddress(px), stagingRing[slot].ptr + off, bytes);
            VkCommandBuffer cb = core.uploadCommands();
            if (!DEFER_MIPGEN) {
                recordUpload(cb, t, x, y, w, h, stagingRing[slot].buffer, off, false, true);
            } else {
                if (!t.mipsDirty) {
                    barrier(cb, t.image, t.mips, 0, t.mips, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                            0, VK_ACCESS_TRANSFER_WRITE_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);
                    t.mipsDirty = true;
                    pendingMips.add(t);
                }
                copyToLevel0(cb, t, x, y, w, h, stagingRing[slot].buffer, off);
            }
        }
        MemoryUtil.memFree(px);
    }

    private void barrier(VkCommandBuffer cb, long image, int mips, int baseMip, int levels, int oldL, int newL, int srcA, int dstA, int srcS, int dstS) {
        try (MemoryStack st = stackPush()) {
            VkImageMemoryBarrier.Buffer b = VkImageMemoryBarrier.calloc(1, st).sType$Default().srcAccessMask(srcA).dstAccessMask(dstA)
                    .oldLayout(oldL).newLayout(newL).srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).image(image);
            b.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(baseMip).levelCount(levels).layerCount(1);
            vkCmdPipelineBarrier(cb, srcS, dstS, 0, null, null, b);
        }
    }

    private void copyToLevel0(VkCommandBuffer cb, Tex t, int x, int y, int w, int h, long srcBuf, long srcOff) {
        try (MemoryStack st = stackPush()) {
            VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, st).bufferOffset(srcOff);
            region.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).layerCount(1);
            region.imageOffset().set(x, y, 0);
            region.imageExtent().set(w, h, 1);
            vkCmdCopyBufferToImage(cb, srcBuf, t.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region);
        }
    }

    /** Generate mip levels from level 0 (all levels currently TRANSFER_DST_OPTIMAL) and transition back to SHADER_READ_ONLY_OPTIMAL. */
    private void finishUploadMips(VkCommandBuffer cb, Tex t) {
        try (MemoryStack st = stackPush()) {
            int mw = t.w, mh = t.h;
            for (int i = 1; i < t.mips; i++) {
                barrier(cb, t.image, t.mips, i - 1, 1, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                        VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);
                int nw = Math.max(1, mw / 2), nh = Math.max(1, mh / 2);
                VkImageBlit.Buffer blit = VkImageBlit.calloc(1, st);
                blit.srcSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(i - 1).layerCount(1);
                blit.srcOffsets(1).set(mw, mh, 1);
                blit.dstSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(i).layerCount(1);
                blit.dstOffsets(1).set(nw, nh, 1);
                vkCmdBlitImage(cb, t.image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, t.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, blit, VK_FILTER_LINEAR);
                mw = nw;
                mh = nh;
            }
        }
        int fragment = VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;
        // levels 0..mips-2 are TRANSFER_SRC, the last is TRANSFER_DST
        if (t.mips > 1) barrier(cb, t.image, t.mips, 0, t.mips - 1, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                VK_ACCESS_TRANSFER_READ_BIT, VK_ACCESS_SHADER_READ_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, fragment);
        barrier(cb, t.image, t.mips, t.mips - 1, 1, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, fragment);
    }

    private void flushPendingMips() {
        if (pendingMips.isEmpty()) return;
        VkCommandBuffer cb = core.uploadCommands();
        for (int i = 0; i < pendingMips.size(); i++) {
            Tex t = pendingMips.get(i);
            if (!t.mipsDirty || t.image == 0) continue;
            t.mipsDirty = false;
            finishUploadMips(cb, t);
        }
        pendingMips.clear();
    }

    private void recordUpload(VkCommandBuffer cb, Tex t, int x, int y, int w, int h, long srcBuf, long srcOff, boolean first, boolean genMips) {
        // a full (re)creation replaces everything, so the old contents need not be preserved
        int oldLayout = first ? VK_IMAGE_LAYOUT_UNDEFINED : VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
        barrier(cb, t.image, t.mips, 0, t.mips, oldLayout, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 0, VK_ACCESS_TRANSFER_WRITE_BIT,
                first ? VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT : VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);
        copyToLevel0(cb, t, x, y, w, h, srcBuf, srcOff);
        if (genMips) finishUploadMips(cb, t);
    }

    @Override public void glTexImage2D(int t, int l, int f, int w, int h, int b, int fmt, int type, ByteBuffer d) { texImage(l, w, h, d); }
    @Override public void glTexImage2D(int t, int l, int f, int w, int h, int b, int fmt, int type, IntBuffer d) { texImage(l, w, h, d); }
    @Override public void glTexImage2D(int t, int l, int f, int w, int h, int b, int fmt, int type, ShortBuffer d) { texImage(l, w, h, d); }
    @Override public void glTexImage2D(int t, int l, int f, int w, int h, int b, int fmt, int type, FloatBuffer d) { throw new UnsupportedOperationException("float texture"); }
    @Override public void glTexImage2D(int t, int l, int f, int w, int h, int b, int fmt, int type, DoubleBuffer d) { throw new UnsupportedOperationException("double texture"); }
    @Override public void glTexImage2D(int t, int l, int f, int w, int h, int b, int fmt, int type, double[] d) { throw new UnsupportedOperationException(); }
    @Override public void glTexImage2D(int t, int l, int f, int w, int h, int b, int fmt, int type, float[] d) { throw new UnsupportedOperationException(); }
    @Override public void glTexImage2D(int t, int l, int f, int w, int h, int b, int fmt, int type, int[] d) { texImage(l, w, h, IntBuffer.wrap(d)); }
    @Override public void glTexImage2D(int t, int l, int f, int w, int h, int b, int fmt, int type, short[] d) { throw new UnsupportedOperationException(); }
    @Override public void glTexImage2D(int t, int l, int f, int w, int h, int b, int fmt, int type, long d) { texImage(l, w, h, null); }
    @Override public void glTexSubImage2D(int t, int l, int x, int y, int w, int h, int fmt, int type, ByteBuffer d) { texSubImage(l, x, y, w, h, d); }
    @Override public void glTexSubImage2D(int t, int l, int x, int y, int w, int h, int fmt, int type, IntBuffer d) { texSubImage(l, x, y, w, h, d); }
    @Override public void glTexSubImage2D(int t, int l, int x, int y, int w, int h, int fmt, int type, ShortBuffer d) { texSubImage(l, x, y, w, h, d); }
    @Override public void glTexSubImage2D(int t, int l, int x, int y, int w, int h, int fmt, int type, FloatBuffer d) { throw new UnsupportedOperationException(); }
    @Override public void glTexSubImage2D(int t, int l, int x, int y, int w, int h, int fmt, int type, DoubleBuffer d) { throw new UnsupportedOperationException(); }
    @Override public void glTexSubImage2D(int t, int l, int x, int y, int w, int h, int fmt, int type, double[] d) { throw new UnsupportedOperationException(); }
    @Override public void glTexSubImage2D(int t, int l, int x, int y, int w, int h, int fmt, int type, float[] d) { throw new UnsupportedOperationException(); }
    @Override public void glTexSubImage2D(int t, int l, int x, int y, int w, int h, int fmt, int type, int[] d) { texSubImage(l, x, y, w, h, IntBuffer.wrap(d)); }
    @Override public void glTexSubImage2D(int t, int l, int x, int y, int w, int h, int fmt, int type, short[] d) { throw new UnsupportedOperationException(); }
    @Override public void glTexSubImage2D(int t, int l, int x, int y, int w, int h, int fmt, int type, long d) { throw new UnsupportedOperationException(); }

    @Override public void glReadPixels(int x, int y, int w, int h, int fmt, int type, ByteBuffer out) {
        if (x != 0 || y != 0 || fmt != 0x1907) throw new UnsupportedOperationException("glReadPixels: only a full-frame GL_RGB read is supported");
        readRgb(w, h, out);   // F2 screenshots
    }
    @Override public void glReadPixels(int x, int y, int w, int h, int fmt, int type, float[] o) { throw new UnsupportedOperationException(); }
    @Override public void glReadPixels(int x, int y, int w, int h, int fmt, int type, int[] o) { throw new UnsupportedOperationException(); }
    @Override public void glReadPixels(int x, int y, int w, int h, int fmt, int type, short[] o) { throw new UnsupportedOperationException(); }
    @Override public void glReadPixels(int x, int y, int w, int h, int fmt, int type, FloatBuffer o) { throw new UnsupportedOperationException(); }
    @Override public void glReadPixels(int x, int y, int w, int h, int fmt, int type, IntBuffer o) { throw new UnsupportedOperationException(); }
    @Override public void glReadPixels(int x, int y, int w, int h, int fmt, int type, ShortBuffer o) { throw new UnsupportedOperationException(); }
    @Override public void glReadPixels(int x, int y, int w, int h, int fmt, int type, long o) { throw new UnsupportedOperationException(); }

    // ============================== present / readback =========================================

    private void present() {
        flushPendingMips();
        core.present();
        for (int i = dying.size() - 1; i >= 0; i--) {
            Object[] d = dying.get(i);
            if ((long) d[0] <= core.frameCounter) {
                ((Runnable) d[1]).run();
                dying.remove(i);
            }
        }
    }

    /** Frame grab for the screenshot hook: RGB, tightly packed, bottom row first (GL's convention). */
    private void readRgb(int w, int h, ByteBuffer out) {
        flushPendingMips();
        if (!beginDraw()) return;
        Buf rb = core.createBuffer((long) core.width * core.height * 4, VK_BUFFER_USAGE_TRANSFER_DST_BIT);
        VkCommandBuffer cb = core.drawCmd[core.slot];
        vkCmdEndRenderPass(cb);
        core.passEnded = true;
        long img = core.colorImageForReadback();
        int origLayout = core.colorLayoutForReadback();
        barrier(cb, img, 1, 0, 1, origLayout, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT,
                VK_ACCESS_TRANSFER_READ_BIT, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);
        try (MemoryStack st = stackPush()) {
            VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, st);
            region.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
            region.imageExtent().set(core.width, core.height, 1);
            vkCmdCopyImageToBuffer(cb, img, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, rb.buffer, region);
        }
        barrier(cb, img, 1, 0, 1, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, origLayout, VK_ACCESS_TRANSFER_READ_BIT, 0,
                VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT);
        core.submit();
        vkWaitForFences(core.device, core.fence[core.slot], true, Long.MAX_VALUE);
        ByteBuffer px = MemoryUtil.memByteBuffer(rb.ptr, core.width * core.height * 4);
        for (int row = 0; row < h; row++) {
            int srcRow = h - 1 - row;   // bottom row first
            for (int x = 0; x < w; x++) {
                int i = (srcRow * core.width + x) * 4;
                out.put((row * w + x) * 3, px.get(i + 2)).put((row * w + x) * 3 + 1, px.get(i + 1)).put((row * w + x) * 3 + 2, px.get(i));   // BGRA -> RGB
            }
        }
        core.destroyBuffer(rb);
    }
}
