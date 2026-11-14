package dev.betavk.spike;

import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.system.Configuration;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.Platform;
import org.lwjgl.vulkan.*;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.glfw.GLFWVulkan.*;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.util.shaderc.Shaderc.*;
import static org.lwjgl.vulkan.KHRPortabilityEnumeration.VK_INSTANCE_CREATE_ENUMERATE_PORTABILITY_BIT_KHR;
import static org.lwjgl.vulkan.KHRSurface.*;
import static org.lwjgl.vulkan.KHRSwapchain.*;
import static org.lwjgl.vulkan.VK12.*;

/**
 * Phase 1 spike: window (GLFW_NO_API) -> Vulkan 1.2 instance with the portability
 * extensions -> device -> swapchain -> clear -> pixel readback, on MoltenVK as shipped
 * inside the LWJGL natives jar. Reports numbers and exact errors; throws on the first failure.
 * This is not part of the game and uses no Mojang code.
 */
public final class VulkanSpike {

    private static final int FRAMES_IN_FLIGHT = 2;
    private static final String PORTABILITY_SUBSET = "VK_KHR_portability_subset";
    private static final String PORTABILITY_ENUMERATION = "VK_KHR_portability_enumeration";
    private static final String GET_PHYS_DEV_PROPS2 = "VK_KHR_get_physical_device_properties2";

    // The final frame clears to this exact colour and the spike reads one pixel back.
    private static final float[] FINAL_CLEAR = {0.2f, 0.4f, 0.6f, 1.0f};

    private static final long T0 = System.nanoTime();

    private static void log(String s) {
        System.out.printf("[%8.1f ms] %s%n", (System.nanoTime() - T0) / 1e6, s);
    }

    private static String res(int r) {
        return switch (r) {
            case VK_SUCCESS -> "VK_SUCCESS";
            case VK_NOT_READY -> "VK_NOT_READY";
            case VK_TIMEOUT -> "VK_TIMEOUT";
            case VK_SUBOPTIMAL_KHR -> "VK_SUBOPTIMAL_KHR";
            case VK_ERROR_OUT_OF_HOST_MEMORY -> "VK_ERROR_OUT_OF_HOST_MEMORY";
            case VK_ERROR_OUT_OF_DEVICE_MEMORY -> "VK_ERROR_OUT_OF_DEVICE_MEMORY";
            case VK_ERROR_INITIALIZATION_FAILED -> "VK_ERROR_INITIALIZATION_FAILED";
            case VK_ERROR_DEVICE_LOST -> "VK_ERROR_DEVICE_LOST";
            case VK_ERROR_LAYER_NOT_PRESENT -> "VK_ERROR_LAYER_NOT_PRESENT";
            case VK_ERROR_EXTENSION_NOT_PRESENT -> "VK_ERROR_EXTENSION_NOT_PRESENT";
            case VK_ERROR_FEATURE_NOT_PRESENT -> "VK_ERROR_FEATURE_NOT_PRESENT";
            case VK_ERROR_INCOMPATIBLE_DRIVER -> "VK_ERROR_INCOMPATIBLE_DRIVER";
            case VK_ERROR_SURFACE_LOST_KHR -> "VK_ERROR_SURFACE_LOST_KHR";
            case VK_ERROR_OUT_OF_DATE_KHR -> "VK_ERROR_OUT_OF_DATE_KHR";
            default -> "VkResult(" + r + ")";
        };
    }

    private static void vk(int r, String what) {
        if (r != VK_SUCCESS) {
            throw new IllegalStateException(what + " failed: " + res(r));
        }
    }

    private static String ver(int v) {
        return ((v >>> 22) & 0x7F) + "." + ((v >>> 12) & 0x3FF) + "." + (v & 0xFFF);
    }

    // ---- options -------------------------------------------------------------------------
    private int maxFrames = 240;
    private boolean uncapped = false;
    private boolean validation = false;
    private int monitorIndex = -1;   // move the window onto this monitor before running (-1 = leave where the OS put it)

    // ---- state ---------------------------------------------------------------------------
    private long window;
    private VkInstance instance;
    private long surface;
    private VkPhysicalDevice physicalDevice;
    private VkDevice device;
    private VkQueue queue;
    private int queueFamily = -1;
    private boolean anisotropyEnabled;
    private boolean usePortabilityEnumeration;
    private List<String> presentModeNames = new ArrayList<>();

    private long swapchain = VK_NULL_HANDLE;
    private int swapFormat;
    private int swapWidth, swapHeight;
    private long[] swapImages = new long[0];
    private long[] swapViews = new long[0];
    private long[] swapFramebuffers = new long[0];
    private long[] renderFinished = new long[0];
    private long renderPass;
    private int presentMode;
    private int swapchainRecreations = 0;

    private long commandPool;
    private VkCommandBuffer[] cmd = new VkCommandBuffer[FRAMES_IN_FLIGHT];
    private final long[] imageAvailable = new long[FRAMES_IN_FLIGHT];
    private final long[] inFlight = new long[FRAMES_IN_FLIGHT];

    private long readbackBuffer;
    private long readbackMemory;
    private boolean bgra;

    // ---- main ----------------------------------------------------------------------------
    public static void main(String[] args) {
        VulkanSpike s = new VulkanSpike();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--frames" -> s.maxFrames = Integer.parseInt(args[++i]);
                case "--uncapped" -> s.uncapped = true;
                case "--validation" -> s.validation = true;
                case "--monitor" -> s.monitorIndex = Integer.parseInt(args[++i]);
                default -> throw new IllegalArgumentException("unknown arg: " + args[i]);
            }
        }
        int code = 1;
        try {
            code = s.run();
        } catch (Throwable t) {
            System.out.println();
            System.out.println("SPIKE FAILED: " + t);
            t.printStackTrace(System.out);
        }
        System.exit(code);
    }

    private int run() throws Exception {
        log("os.arch=" + System.getProperty("os.arch") + "  java=" + System.getProperty("java.version")
                + " (" + System.getProperty("java.vendor") + ")  pid=" + ProcessHandle.current().pid());
        log("LWJGL platform=" + Platform.get() + "  arch=" + Platform.getArchitecture());
        if (!"aarch64".equals(System.getProperty("os.arch"))) {
            throw new IllegalStateException("JVM is not arm64 (os.arch=" + System.getProperty("os.arch") + ")");
        }

        shadercSmokeTest();

        // 1. Load MoltenVK from the LWJGL natives jar, then hand its vkGetInstanceProcAddr to GLFW so
        //    GLFW does not go looking for a system Vulkan loader (there is none on a stock Mac).
        long t = System.nanoTime();
        // LWJGL's VK class auto-creates on class load unless told otherwise; be explicit so it is timed and loaded once.
        Configuration.VULKAN_EXPLICIT_INIT.set(true);
        VK.create();
        long gipa = VK.getFunctionProvider().getFunctionAddress("vkGetInstanceProcAddr");
        log(String.format("VK.create() ok (libMoltenVK loaded from natives), vkGetInstanceProcAddr=0x%x  [%.1f ms]",
                gipa, (System.nanoTime() - t) / 1e6));
        glfwInitVulkanLoader(gipa);

        GLFWErrorCallback.createPrint(System.err).set();
        t = System.nanoTime();
        if (!glfwInit()) throw new IllegalStateException("glfwInit() failed (is -XstartOnFirstThread set?)");
        log(String.format("glfwInit ok, GLFW %s  [%.1f ms]", glfwGetVersionString(), (System.nanoTime() - t) / 1e6));
        if (!glfwVulkanSupported()) throw new IllegalStateException("glfwVulkanSupported() == false");
        log("glfwVulkanSupported() == true");

        glfwWindowHint(GLFW_CLIENT_API, GLFW_NO_API);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);
        window = glfwCreateWindow(1280, 720, "beta-vulkan spike", NULL, NULL);
        if (window == NULL) throw new IllegalStateException("glfwCreateWindow failed");
        if (monitorIndex >= 0) {
            try (MemoryStack st = stackPush()) {
                PointerBuffer mons = glfwGetMonitors();
                if (mons == null || monitorIndex >= mons.remaining())
                    throw new IllegalArgumentException("--monitor " + monitorIndex + " out of range");
                IntBuffer mx = st.mallocInt(1), my = st.mallocInt(1);
                glfwGetMonitorPos(mons.get(monitorIndex), mx, my);
                glfwSetWindowPos(window, mx.get(0) + 100, my.get(0) + 100);
                glfwPollEvents();
            }
        }
        logMonitors();
        try (MemoryStack st = stackPush()) {
            IntBuffer ww = st.mallocInt(1), wh = st.mallocInt(1), fw = st.mallocInt(1), fh = st.mallocInt(1);
            FloatBuffer sx = st.mallocFloat(1), sy = st.mallocFloat(1);
            glfwGetWindowSize(window, ww, wh);
            glfwGetFramebufferSize(window, fw, fh);
            glfwGetWindowContentScale(window, sx, sy);
            log("window " + ww.get(0) + "x" + wh.get(0) + " points, framebuffer " + fw.get(0) + "x" + fh.get(0)
                    + " px, content scale " + sx.get(0) + "x" + sy.get(0) + " (Retina)");
        }

        createInstance();
        createSurface();
        pickPhysicalDevice();
        createDevice();
        createCommandResources();
        createSwapchain();
        createReadbackBuffer();

        int rc = loop();

        vkDeviceWaitIdle(device);
        printLoadedNatives();
        cleanup();
        return rc;
    }

    /** Which monitors exist, their refresh rates, and which one the window landed on. */
    private void logMonitors() {
        try (MemoryStack st = stackPush()) {
            IntBuffer wx = st.mallocInt(1), wy = st.mallocInt(1), mx = st.mallocInt(1), my = st.mallocInt(1);
            glfwGetWindowPos(window, wx, wy);
            PointerBuffer mons = glfwGetMonitors();
            long primary = glfwGetPrimaryMonitor();
            for (int i = 0; mons != null && i < mons.remaining(); i++) {
                long m = mons.get(i);
                org.lwjgl.glfw.GLFWVidMode vm = glfwGetVideoMode(m);
                glfwGetMonitorPos(m, mx, my);
                boolean on = vm != null && wx.get(0) >= mx.get(0) && wx.get(0) < mx.get(0) + vm.width()
                        && wy.get(0) >= my.get(0) && wy.get(0) < my.get(0) + vm.height();
                log("monitor " + i + (m == primary ? " (primary)" : "") + " '" + glfwGetMonitorName(m) + "' "
                        + (vm == null ? "?" : vm.width() + "x" + vm.height() + "@" + vm.refreshRate() + "Hz")
                        + " at (" + mx.get(0) + "," + my.get(0) + ")" + (on ? "  <-- window is here" : ""));
            }
            log("window position (" + wx.get(0) + "," + wy.get(0) + ")");
        }
    }

    // ---- shaderc (build-time GLSL->SPIR-V depends on this loading) ------------------------
    private void shadercSmokeTest() {
        long compiler = shaderc_compiler_initialize();
        if (compiler == NULL) throw new IllegalStateException("shaderc_compiler_initialize returned NULL");
        long opts = shaderc_compile_options_initialize();
        String src = "#version 450\nlayout(location=0) in vec3 p;\nvoid main(){ gl_Position = vec4(p,1.0); }\n";
        long result = shaderc_compile_into_spv(compiler, src, shaderc_glsl_vertex_shader, "smoke.vert", "main", opts);
        int status = shaderc_result_get_compilation_status(result);
        if (status != shaderc_compilation_status_success) {
            String msg = shaderc_result_get_error_message(result);
            throw new IllegalStateException("shaderc compile failed: " + msg);
        }
        ByteBuffer spv = shaderc_result_get_bytes(result);
        int magic = spv.getInt(0);
        log(String.format("shaderc natives ok: GLSL -> SPIR-V %d bytes, magic=0x%08x (%s)", spv.remaining(), magic,
                magic == 0x07230203 ? "valid" : "INVALID"));
        shaderc_result_release(result);
        shaderc_compile_options_release(opts);
        shaderc_compiler_release(compiler);
    }

    // ---- instance ------------------------------------------------------------------------
    private void createInstance() {
        long t = System.nanoTime();
        try (MemoryStack st = stackPush()) {
            IntBuffer pv = st.mallocInt(1);
            int instVer = VK_API_VERSION_1_0;
            try {
                if (vkEnumerateInstanceVersion(pv) == VK_SUCCESS) instVer = pv.get(0);
            } catch (Throwable ignored) { /* 1.0 loader */ }
            log("instance version (MoltenVK): " + ver(instVer));

            IntBuffer pc = st.mallocInt(1);
            vk(vkEnumerateInstanceExtensionProperties((ByteBuffer) null, pc, null), "vkEnumerateInstanceExtensionProperties(count)");
            VkExtensionProperties.Buffer props = VkExtensionProperties.malloc(pc.get(0), st);
            vk(vkEnumerateInstanceExtensionProperties((ByteBuffer) null, pc, props), "vkEnumerateInstanceExtensionProperties");
            Set<String> avail = new HashSet<>();
            for (VkExtensionProperties p : props) avail.add(p.extensionNameString());
            log("instance extensions (" + avail.size() + "): " + new java.util.TreeSet<>(avail));

            PointerBuffer glfwReq = glfwGetRequiredInstanceExtensions();
            if (glfwReq == null) throw new IllegalStateException("glfwGetRequiredInstanceExtensions() returned null");
            List<String> enabled = new ArrayList<>();
            for (int i = 0; i < glfwReq.remaining(); i++) enabled.add(org.lwjgl.system.MemoryUtil.memUTF8(glfwReq.get(i)));
            // VK_KHR_portability_enumeration is a *loader* extension. When libMoltenVK is loaded directly (as LWJGL
            // does) it is the ICD itself and does not offer it; the physical device is enumerated without the flag.
            // When a Vulkan loader (e.g. the Vulkan SDK's) sits in front, it offers it and the flag is mandatory.
            usePortabilityEnumeration = avail.contains(PORTABILITY_ENUMERATION);
            log(PORTABILITY_ENUMERATION + (usePortabilityEnumeration
                    ? " offered -> enabling it and VK_INSTANCE_CREATE_ENUMERATE_PORTABILITY_BIT_KHR"
                    : " NOT offered (MoltenVK loaded directly, no Vulkan loader in front) -> not needed, instance flag left at 0"));
            if (usePortabilityEnumeration) enabled.add(PORTABILITY_ENUMERATION);
            if (avail.contains(GET_PHYS_DEV_PROPS2)) enabled.add(GET_PHYS_DEV_PROPS2);
            for (String e : enabled)
                if (!avail.contains(e)) throw new IllegalStateException("required instance extension missing: " + e);
            log("enabling instance extensions: " + enabled);

            PointerBuffer ext = st.mallocPointer(enabled.size());
            for (String e : enabled) ext.put(st.UTF8(e));
            ext.flip();

            PointerBuffer layers = null;
            if (validation) {
                String layer = "VK_LAYER_KHRONOS_validation";
                IntBuffer lc = st.mallocInt(1);
                vkEnumerateInstanceLayerProperties(lc, null);
                VkLayerProperties.Buffer lp = VkLayerProperties.malloc(lc.get(0), st);
                vkEnumerateInstanceLayerProperties(lc, lp);
                boolean found = false;
                for (VkLayerProperties l : lp) found |= layer.equals(l.layerNameString());
                if (!found) log("WARNING: --validation requested but " + layer + " is not installed (needs Vulkan SDK); continuing without");
                else layers = st.pointers(st.UTF8(layer));
            }

            VkApplicationInfo app = VkApplicationInfo.calloc(st).sType$Default()
                    .pApplicationName(st.UTF8("beta-vulkan-spike"))
                    .applicationVersion(1)
                    .pEngineName(st.UTF8("beta-vulkan"))
                    .engineVersion(1)
                    .apiVersion(VK_API_VERSION_1_2);
            VkInstanceCreateInfo ci = VkInstanceCreateInfo.calloc(st).sType$Default()
                    .flags(usePortabilityEnumeration ? VK_INSTANCE_CREATE_ENUMERATE_PORTABILITY_BIT_KHR : 0)
                    .pApplicationInfo(app)
                    .ppEnabledExtensionNames(ext);
            if (layers != null) ci.ppEnabledLayerNames(layers);

            PointerBuffer pi = st.mallocPointer(1);
            vk(vkCreateInstance(ci, null, pi), "vkCreateInstance(apiVersion=1.2)");
            instance = new VkInstance(pi.get(0), ci);
        }
        log(String.format("vkCreateInstance ok (apiVersion 1.2, portability enumeration %s)  [%.1f ms]",
                usePortabilityEnumeration ? "ON" : "n/a", (System.nanoTime() - t) / 1e6));
    }

    private void createSurface() {
        try (MemoryStack st = stackPush()) {
            LongBuffer ps = st.mallocLong(1);
            vk(glfwCreateWindowSurface(instance, window, null, ps), "glfwCreateWindowSurface");
            surface = ps.get(0);
        }
        log("surface created (VK_EXT_metal_surface via GLFW)");
    }

    // ---- physical device -----------------------------------------------------------------
    private void pickPhysicalDevice() {
        try (MemoryStack st = stackPush()) {
            IntBuffer pc = st.mallocInt(1);
            vk(vkEnumeratePhysicalDevices(instance, pc, null), "vkEnumeratePhysicalDevices(count)");
            if (pc.get(0) == 0) throw new IllegalStateException("no Vulkan physical devices (portability enumeration flag honoured?)");
            PointerBuffer pd = st.mallocPointer(pc.get(0));
            vk(vkEnumeratePhysicalDevices(instance, pc, pd), "vkEnumeratePhysicalDevices");
            log("physical devices: " + pc.get(0));

            for (int i = 0; i < pd.capacity(); i++) {
                VkPhysicalDevice cand = new VkPhysicalDevice(pd.get(i), instance);
                VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.malloc(st);
                vkGetPhysicalDeviceProperties(cand, props);

                VkPhysicalDeviceDriverProperties drv = VkPhysicalDeviceDriverProperties.calloc(st).sType$Default();
                VkPhysicalDeviceProperties2 p2 = VkPhysicalDeviceProperties2.calloc(st).sType$Default().pNext(drv);
                vkGetPhysicalDeviceProperties2(cand, p2);

                log("  [" + i + "] " + props.deviceNameString() + "  type=" + props.deviceType()
                        + (props.deviceType() == VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU ? " (INTEGRATED_GPU)" : "")
                        + "  api=" + ver(props.apiVersion()) + "  driverVersion=0x" + Integer.toHexString(props.driverVersion()));
                log("      driver: id=" + drv.driverID() + " name='" + drv.driverNameString() + "' info='" + drv.driverInfoString()
                        + "' conformance=" + drv.conformanceVersion().major() + "." + drv.conformanceVersion().minor()
                        + "." + drv.conformanceVersion().subminor() + "." + drv.conformanceVersion().patch());
                VkPhysicalDeviceLimits lim = props.limits();
                log("      limits: maxImageDimension2D=" + lim.maxImageDimension2D()
                        + " maxSamplerAnisotropy=" + lim.maxSamplerAnisotropy()
                        + " maxPushConstants=" + lim.maxPushConstantsSize()
                        + " minUBOAlign=" + lim.minUniformBufferOffsetAlignment()
                        + " nonCoherentAtomSize=" + lim.nonCoherentAtomSize()
                        + " maxMemoryAllocationCount=" + lim.maxMemoryAllocationCount());

                if (physicalDevice != null) continue;

                // queue families
                IntBuffer qc = st.mallocInt(1);
                vkGetPhysicalDeviceQueueFamilyProperties(cand, qc, null);
                VkQueueFamilyProperties.Buffer qf = VkQueueFamilyProperties.malloc(qc.get(0), st);
                vkGetPhysicalDeviceQueueFamilyProperties(cand, qc, qf);
                for (int q = 0; q < qf.capacity(); q++) {
                    int flags = qf.get(q).queueFlags();
                    IntBuffer sup = st.mallocInt(1);
                    vkGetPhysicalDeviceSurfaceSupportKHR(cand, q, surface, sup);
                    log("      queue family " + q + ": count=" + qf.get(q).queueCount()
                            + " flags=" + ((flags & VK_QUEUE_GRAPHICS_BIT) != 0 ? "GRAPHICS " : "")
                            + ((flags & VK_QUEUE_COMPUTE_BIT) != 0 ? "COMPUTE " : "")
                            + ((flags & VK_QUEUE_TRANSFER_BIT) != 0 ? "TRANSFER " : "")
                            + " present=" + (sup.get(0) == VK_TRUE));
                    if (queueFamily < 0 && (flags & VK_QUEUE_GRAPHICS_BIT) != 0 && sup.get(0) == VK_TRUE) {
                        queueFamily = q;
                        physicalDevice = cand;
                    }
                }
            }
            if (physicalDevice == null) throw new IllegalStateException("no device with a graphics+present queue family");

            VkPhysicalDeviceMemoryProperties mem = VkPhysicalDeviceMemoryProperties.malloc(st);
            vkGetPhysicalDeviceMemoryProperties(physicalDevice, mem);
            StringBuilder sb = new StringBuilder("memory heaps:");
            for (int h = 0; h < mem.memoryHeapCount(); h++)
                sb.append(String.format(" [%d: %d MiB%s]", h, mem.memoryHeaps(h).size() >> 20,
                        (mem.memoryHeaps(h).flags() & VK_MEMORY_HEAP_DEVICE_LOCAL_BIT) != 0 ? " DEVICE_LOCAL" : ""));
            log(sb.toString());
            sb = new StringBuilder("memory types:");
            for (int m = 0; m < mem.memoryTypeCount(); m++) {
                int f = mem.memoryTypes(m).propertyFlags();
                sb.append(String.format(" [%d: heap%d %s%s%s%s]", m, mem.memoryTypes(m).heapIndex(),
                        (f & VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT) != 0 ? "DL " : "",
                        (f & VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT) != 0 ? "HV " : "",
                        (f & VK_MEMORY_PROPERTY_HOST_COHERENT_BIT) != 0 ? "HC " : "",
                        (f & VK_MEMORY_PROPERTY_HOST_CACHED_BIT) != 0 ? "HCa" : ""));
            }
            log(sb.toString());
        }
    }

    // ---- logical device ------------------------------------------------------------------
    private void createDevice() {
        long t = System.nanoTime();
        try (MemoryStack st = stackPush()) {
            IntBuffer pc = st.mallocInt(1);
            vk(vkEnumerateDeviceExtensionProperties(physicalDevice, (ByteBuffer) null, pc, null), "vkEnumerateDeviceExtensionProperties(count)");
            VkExtensionProperties.Buffer props = VkExtensionProperties.malloc(pc.get(0), st);
            vk(vkEnumerateDeviceExtensionProperties(physicalDevice, (ByteBuffer) null, pc, props), "vkEnumerateDeviceExtensionProperties");
            Set<String> avail = new java.util.TreeSet<>();
            for (VkExtensionProperties p : props) avail.add(p.extensionNameString());
            log("device extensions (" + avail.size() + "), of interest: " + interesting(avail));

            if (!avail.contains(VK_KHR_SWAPCHAIN_EXTENSION_NAME))
                throw new IllegalStateException("VK_KHR_swapchain missing");
            boolean portability = avail.contains(PORTABILITY_SUBSET);

            VkPhysicalDeviceFeatures2 f2 = VkPhysicalDeviceFeatures2.calloc(st).sType$Default();
            VkPhysicalDevicePortabilitySubsetFeaturesKHR pf = null;
            if (portability) {
                pf = VkPhysicalDevicePortabilitySubsetFeaturesKHR.calloc(st).sType$Default();
                f2.pNext(pf);
            }
            vkGetPhysicalDeviceFeatures2(physicalDevice, f2);
            VkPhysicalDeviceFeatures f = f2.features();
            log("features: samplerAnisotropy=" + f.samplerAnisotropy() + " multiDrawIndirect=" + f.multiDrawIndirect()
                    + " drawIndirectFirstInstance=" + f.drawIndirectFirstInstance() + " fillModeNonSolid=" + f.fillModeNonSolid()
                    + " depthClamp=" + f.depthClamp() + " textureCompressionBC=" + f.textureCompressionBC());
            if (pf != null) {
                log("portability subset: triangleFans=" + pf.triangleFans() + " samplerMipLodBias=" + pf.samplerMipLodBias()
                        + " constantAlphaColorBlendFactors=" + pf.constantAlphaColorBlendFactors()
                        + " separateStencilMaskRef=" + pf.separateStencilMaskRef() + " pointPolygons=" + pf.pointPolygons()
                        + " imageViewFormatSwizzle=" + pf.imageViewFormatSwizzle());
            }
            anisotropyEnabled = f.samplerAnisotropy();

            List<String> enabled = new ArrayList<>();
            enabled.add(VK_KHR_SWAPCHAIN_EXTENSION_NAME);
            if (portability) enabled.add(PORTABILITY_SUBSET);   // mandatory to enable when the device offers it
            PointerBuffer ext = st.mallocPointer(enabled.size());
            for (String e : enabled) ext.put(st.UTF8(e));
            ext.flip();
            log("enabling device extensions: " + enabled);

            VkDeviceQueueCreateInfo.Buffer qci = VkDeviceQueueCreateInfo.calloc(1, st).sType$Default()
                    .queueFamilyIndex(queueFamily).pQueuePriorities(st.floats(1f));
            VkPhysicalDeviceFeatures enable = VkPhysicalDeviceFeatures.calloc(st).samplerAnisotropy(anisotropyEnabled);
            VkDeviceCreateInfo dci = VkDeviceCreateInfo.calloc(st).sType$Default()
                    .pQueueCreateInfos(qci).ppEnabledExtensionNames(ext).pEnabledFeatures(enable);
            PointerBuffer pd = st.mallocPointer(1);
            vk(vkCreateDevice(physicalDevice, dci, null, pd), "vkCreateDevice");
            device = new VkDevice(pd.get(0), physicalDevice, dci);

            PointerBuffer pq = st.mallocPointer(1);
            vkGetDeviceQueue(device, queueFamily, 0, pq);
            queue = new VkQueue(pq.get(0), device);
        }
        log(String.format("vkCreateDevice ok (queue family %d)  [%.1f ms]", queueFamily, (System.nanoTime() - t) / 1e6));
    }

    private static String interesting(Set<String> all) {
        List<String> out = new ArrayList<>();
        for (String s : new String[]{"VK_KHR_swapchain", "VK_KHR_portability_subset", "VK_KHR_dynamic_rendering",
                "VK_KHR_timeline_semaphore", "VK_KHR_synchronization2", "VK_EXT_descriptor_indexing",
                "VK_KHR_draw_indirect_count", "VK_KHR_push_descriptor", "VK_EXT_memory_budget",
                "VK_EXT_shader_viewport_index_layer", "VK_EXT_metal_objects", "VK_KHR_maintenance4"})
            out.add(s + (all.contains(s) ? "=Y" : "=n"));
        return String.join(" ", out);
    }

    // ---- command pool, sync --------------------------------------------------------------
    private void createCommandResources() {
        try (MemoryStack st = stackPush()) {
            LongBuffer pl = st.mallocLong(1);
            vk(vkCreateCommandPool(device, VkCommandPoolCreateInfo.calloc(st).sType$Default()
                    .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT).queueFamilyIndex(queueFamily), null, pl), "vkCreateCommandPool");
            commandPool = pl.get(0);

            PointerBuffer pc = st.mallocPointer(FRAMES_IN_FLIGHT);
            vk(vkAllocateCommandBuffers(device, VkCommandBufferAllocateInfo.calloc(st).sType$Default()
                    .commandPool(commandPool).level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(FRAMES_IN_FLIGHT), pc), "vkAllocateCommandBuffers");
            for (int i = 0; i < FRAMES_IN_FLIGHT; i++) cmd[i] = new VkCommandBuffer(pc.get(i), device);

            VkSemaphoreCreateInfo sci = VkSemaphoreCreateInfo.calloc(st).sType$Default();
            VkFenceCreateInfo fci = VkFenceCreateInfo.calloc(st).sType$Default().flags(VK_FENCE_CREATE_SIGNALED_BIT);
            LongBuffer p = st.mallocLong(1);
            for (int i = 0; i < FRAMES_IN_FLIGHT; i++) {
                vk(vkCreateSemaphore(device, sci, null, p), "vkCreateSemaphore");
                imageAvailable[i] = p.get(0);
                vk(vkCreateFence(device, fci, null, p), "vkCreateFence");
                inFlight[i] = p.get(0);
            }
        }
        log("command pool, " + FRAMES_IN_FLIGHT + " command buffers, semaphores and fences created (frames in flight = " + FRAMES_IN_FLIGHT + ")");
    }

    // ---- swapchain -----------------------------------------------------------------------
    private void createSwapchain() {
        long t = System.nanoTime();
        try (MemoryStack st = stackPush()) {
            VkSurfaceCapabilitiesKHR caps = VkSurfaceCapabilitiesKHR.malloc(st);
            vk(vkGetPhysicalDeviceSurfaceCapabilitiesKHR(physicalDevice, surface, caps), "vkGetPhysicalDeviceSurfaceCapabilitiesKHR");

            IntBuffer n = st.mallocInt(1);
            vkGetPhysicalDeviceSurfaceFormatsKHR(physicalDevice, surface, n, null);
            VkSurfaceFormatKHR.Buffer formats = VkSurfaceFormatKHR.malloc(n.get(0), st);
            vkGetPhysicalDeviceSurfaceFormatsKHR(physicalDevice, surface, n, formats);
            // b1.7.3 has no gamma-correct pipeline, so use a UNORM (non-sRGB) swapchain to match its visuals.
            int fmt = -1, cs = -1;
            List<String> fl = new ArrayList<>();
            for (VkSurfaceFormatKHR f : formats) {
                fl.add(f.format() + "/cs" + f.colorSpace());
                if (f.format() == VK_FORMAT_B8G8R8A8_UNORM && f.colorSpace() == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR) { fmt = f.format(); cs = f.colorSpace(); }
            }
            if (fmt < 0) { fmt = formats.get(0).format(); cs = formats.get(0).colorSpace(); }
            swapFormat = fmt;
            bgra = fmt == VK_FORMAT_B8G8R8A8_UNORM || fmt == VK_FORMAT_B8G8R8A8_SRGB;

            vkGetPhysicalDeviceSurfacePresentModesKHR(physicalDevice, surface, n, null);
            IntBuffer modes = st.mallocInt(n.get(0));
            vkGetPhysicalDeviceSurfacePresentModesKHR(physicalDevice, surface, n, modes);
            presentModeNames.clear();
            boolean hasImmediate = false;
            for (int i = 0; i < modes.capacity(); i++) {
                presentModeNames.add(switch (modes.get(i)) {
                    case VK_PRESENT_MODE_IMMEDIATE_KHR -> "IMMEDIATE";
                    case VK_PRESENT_MODE_MAILBOX_KHR -> "MAILBOX";
                    case VK_PRESENT_MODE_FIFO_KHR -> "FIFO";
                    case VK_PRESENT_MODE_FIFO_RELAXED_KHR -> "FIFO_RELAXED";
                    default -> "mode" + modes.get(i);
                });
                hasImmediate |= modes.get(i) == VK_PRESENT_MODE_IMMEDIATE_KHR;
            }
            presentMode = (uncapped && hasImmediate) ? VK_PRESENT_MODE_IMMEDIATE_KHR : VK_PRESENT_MODE_FIFO_KHR;

            int w, h;
            if (caps.currentExtent().width() != 0xFFFFFFFF) {
                w = caps.currentExtent().width();
                h = caps.currentExtent().height();
            } else {
                IntBuffer fw = st.mallocInt(1), fh = st.mallocInt(1);
                glfwGetFramebufferSize(window, fw, fh);
                w = Math.max(caps.minImageExtent().width(), Math.min(caps.maxImageExtent().width(), fw.get(0)));
                h = Math.max(caps.minImageExtent().height(), Math.min(caps.maxImageExtent().height(), fh.get(0)));
            }
            swapWidth = w;
            swapHeight = h;

            int imageCount = caps.minImageCount() + 1;
            if (caps.maxImageCount() > 0) imageCount = Math.min(imageCount, caps.maxImageCount());
            int usage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
            if ((caps.supportedUsageFlags() & VK_IMAGE_USAGE_TRANSFER_SRC_BIT) == 0)
                throw new IllegalStateException("swapchain images do not support TRANSFER_SRC (needed for readback)");
            usage |= VK_IMAGE_USAGE_TRANSFER_SRC_BIT;

            VkSwapchainCreateInfoKHR ci = VkSwapchainCreateInfoKHR.calloc(st).sType$Default()
                    .surface(surface).minImageCount(imageCount).imageFormat(fmt).imageColorSpace(cs)
                    .imageArrayLayers(1).imageUsage(usage).imageSharingMode(VK_SHARING_MODE_EXCLUSIVE)
                    .preTransform(caps.currentTransform()).compositeAlpha(VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR)
                    .presentMode(presentMode).clipped(true).oldSwapchain(swapchain);
            ci.imageExtent().width(w).height(h);

            LongBuffer ps = st.mallocLong(1);
            vk(vkCreateSwapchainKHR(device, ci, null, ps), "vkCreateSwapchainKHR");
            long old = swapchain;
            swapchain = ps.get(0);
            destroySwapchainViews();
            if (old != VK_NULL_HANDLE) vkDestroySwapchainKHR(device, old, null);

            vkGetSwapchainImagesKHR(device, swapchain, n, null);
            LongBuffer imgs = st.mallocLong(n.get(0));
            vkGetSwapchainImagesKHR(device, swapchain, n, imgs);
            swapImages = new long[n.get(0)];
            swapViews = new long[n.get(0)];
            swapFramebuffers = new long[n.get(0)];
            renderFinished = new long[n.get(0)];

            if (renderPass == VK_NULL_HANDLE) createRenderPass(st);

            LongBuffer p = st.mallocLong(1);
            for (int i = 0; i < swapImages.length; i++) {
                swapImages[i] = imgs.get(i);
                VkImageViewCreateInfo vi = VkImageViewCreateInfo.calloc(st).sType$Default()
                        .image(swapImages[i]).viewType(VK_IMAGE_VIEW_TYPE_2D).format(fmt);
                vi.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
                vk(vkCreateImageView(device, vi, null, p), "vkCreateImageView");
                swapViews[i] = p.get(0);

                vk(vkCreateFramebuffer(device, VkFramebufferCreateInfo.calloc(st).sType$Default()
                        .renderPass(renderPass).pAttachments(st.longs(swapViews[i])).width(w).height(h).layers(1), null, p), "vkCreateFramebuffer");
                swapFramebuffers[i] = p.get(0);

                vk(vkCreateSemaphore(device, VkSemaphoreCreateInfo.calloc(st).sType$Default(), null, p), "vkCreateSemaphore");
                renderFinished[i] = p.get(0);
            }
            log(String.format("swapchain %s: %dx%d px, %d images, format %d (%s), present=%s, formats=%s, available modes=%s  [%.1f ms]",
                    swapchainRecreations == 0 ? "created" : "RECREATED", w, h, swapImages.length, fmt,
                    bgra ? "B8G8R8A8" : "other", presentMode == VK_PRESENT_MODE_IMMEDIATE_KHR ? "IMMEDIATE" : "FIFO",
                    fl, presentModeNames, (System.nanoTime() - t) / 1e6));
            swapchainRecreations++;
        }
    }

    private void createRenderPass(MemoryStack st) {
        VkAttachmentDescription.Buffer att = VkAttachmentDescription.calloc(1, st)
                .format(swapFormat).samples(VK_SAMPLE_COUNT_1_BIT)
                .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR).storeOp(VK_ATTACHMENT_STORE_OP_STORE)
                .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE).stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED).finalLayout(VK_IMAGE_LAYOUT_PRESENT_SRC_KHR);
        VkAttachmentReference.Buffer ref = VkAttachmentReference.calloc(1, st)
                .attachment(0).layout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
        VkSubpassDescription.Buffer sub = VkSubpassDescription.calloc(1, st)
                .pipelineBindPoint(VK_PIPELINE_BIND_POINT_GRAPHICS).colorAttachmentCount(1).pColorAttachments(ref);
        VkSubpassDependency.Buffer dep = VkSubpassDependency.calloc(1, st)
                .srcSubpass(VK_SUBPASS_EXTERNAL).dstSubpass(0)
                .srcStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT).dstStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT)
                .srcAccessMask(0).dstAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT);
        LongBuffer p = st.mallocLong(1);
        vk(vkCreateRenderPass(device, VkRenderPassCreateInfo.calloc(st).sType$Default()
                .pAttachments(att).pSubpasses(sub).pDependencies(dep), null, p), "vkCreateRenderPass");
        renderPass = p.get(0);
    }

    private void destroySwapchainViews() {
        for (long fb : swapFramebuffers) if (fb != 0) vkDestroyFramebuffer(device, fb, null);
        for (long v : swapViews) if (v != 0) vkDestroyImageView(device, v, null);
        for (long s : renderFinished) if (s != 0) vkDestroySemaphore(device, s, null);
    }

    private void recreateSwapchain() {
        vkDeviceWaitIdle(device);
        createSwapchain();
    }

    // ---- readback buffer (proves the clear really executed) ------------------------------
    private void createReadbackBuffer() {
        try (MemoryStack st = stackPush()) {
            LongBuffer p = st.mallocLong(1);
            vk(vkCreateBuffer(device, VkBufferCreateInfo.calloc(st).sType$Default()
                    .size(16).usage(VK_BUFFER_USAGE_TRANSFER_DST_BIT).sharingMode(VK_SHARING_MODE_EXCLUSIVE), null, p), "vkCreateBuffer");
            readbackBuffer = p.get(0);
            VkMemoryRequirements req = VkMemoryRequirements.malloc(st);
            vkGetBufferMemoryRequirements(device, readbackBuffer, req);

            VkPhysicalDeviceMemoryProperties mp = VkPhysicalDeviceMemoryProperties.malloc(st);
            vkGetPhysicalDeviceMemoryProperties(physicalDevice, mp);
            int want = VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
            int type = -1;
            for (int i = 0; i < mp.memoryTypeCount(); i++)
                if ((req.memoryTypeBits() & (1 << i)) != 0 && (mp.memoryTypes(i).propertyFlags() & want) == want) { type = i; break; }
            if (type < 0) throw new IllegalStateException("no HOST_VISIBLE|HOST_COHERENT memory type for readback buffer");
            vk(vkAllocateMemory(device, VkMemoryAllocateInfo.calloc(st).sType$Default()
                    .allocationSize(req.size()).memoryTypeIndex(type), null, p), "vkAllocateMemory");
            readbackMemory = p.get(0);
            vk(vkBindBufferMemory(device, readbackBuffer, readbackMemory, 0), "vkBindBufferMemory");
        }
    }

    // ---- render loop ---------------------------------------------------------------------
    private int loop() {
        log("entering render loop: " + maxFrames + " frames, " + (presentMode == VK_PRESENT_MODE_IMMEDIATE_KHR ? "uncapped (IMMEDIATE)" : "vsync (FIFO)"));
        double[] frameMs = new double[maxFrames];
        int frame = 0, slot = 0;
        long prev = System.nanoTime();
        long loopStart = prev;
        boolean readbackOk = false;
        String readbackMsg = "readback not performed";

        while (!glfwWindowShouldClose(window) && frame < maxFrames) {
            glfwPollEvents();
            try (MemoryStack st = stackPush()) {
                IntBuffer fw = st.mallocInt(1), fh = st.mallocInt(1);
                glfwGetFramebufferSize(window, fw, fh);
                if (fw.get(0) == 0 || fh.get(0) == 0) {   // minimised
                    glfwWaitEvents();
                    continue;
                }

                vk(vkWaitForFences(device, inFlight[slot], true, Long.MAX_VALUE), "vkWaitForFences");

                IntBuffer pIdx = st.mallocInt(1);
                int r = vkAcquireNextImageKHR(device, swapchain, Long.MAX_VALUE, imageAvailable[slot], VK_NULL_HANDLE, pIdx);
                if (r == VK_ERROR_OUT_OF_DATE_KHR) { recreateSwapchain(); continue; }
                if (r != VK_SUCCESS && r != VK_SUBOPTIMAL_KHR) throw new IllegalStateException("vkAcquireNextImageKHR failed: " + res(r));
                int idx = pIdx.get(0);
                vk(vkResetFences(device, inFlight[slot]), "vkResetFences");

                boolean last = frame == maxFrames - 1;
                float[] c;
                if (last) c = FINAL_CLEAR;
                else {
                    double a = frame * 0.03;
                    c = new float[]{(float) (0.5 + 0.5 * Math.sin(a)), (float) (0.5 + 0.5 * Math.sin(a + 2.1)), (float) (0.5 + 0.5 * Math.sin(a + 4.2)), 1f};
                }
                record(st, cmd[slot], idx, c, last);

                VkSubmitInfo si = VkSubmitInfo.calloc(st).sType$Default()
                        .waitSemaphoreCount(1).pWaitSemaphores(st.longs(imageAvailable[slot]))
                        .pWaitDstStageMask(st.ints(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT))
                        .pCommandBuffers(st.pointers(cmd[slot])).pSignalSemaphores(st.longs(renderFinished[idx]));
                vk(vkQueueSubmit(queue, si, inFlight[slot]), "vkQueueSubmit");

                if (last) {   // read the pixel back before presenting
                    vk(vkWaitForFences(device, inFlight[slot], true, Long.MAX_VALUE), "vkWaitForFences(readback)");
                    PointerBuffer pd = st.mallocPointer(1);
                    vk(vkMapMemory(device, readbackMemory, 0, 16, 0, pd), "vkMapMemory");
                    ByteBuffer px = org.lwjgl.system.MemoryUtil.memByteBuffer(pd.get(0), 4);
                    int b0 = px.get(0) & 0xFF, b1 = px.get(1) & 0xFF, b2 = px.get(2) & 0xFF, b3 = px.get(3) & 0xFF;
                    vkUnmapMemory(device, readbackMemory);
                    int rr = bgra ? b2 : b0, gg = b1, bb = bgra ? b0 : b2, aa = b3;
                    int er = Math.round(FINAL_CLEAR[0] * 255), eg = Math.round(FINAL_CLEAR[1] * 255), eb = Math.round(FINAL_CLEAR[2] * 255);
                    readbackOk = Math.abs(rr - er) <= 1 && Math.abs(gg - eg) <= 1 && Math.abs(bb - eb) <= 1 && aa == 255;
                    readbackMsg = String.format("readback pixel(0,0) RGBA=(%d,%d,%d,%d) expected=(%d,%d,%d,255) -> %s",
                            rr, gg, bb, aa, er, eg, eb, readbackOk ? "MATCH" : "MISMATCH");
                }

                VkPresentInfoKHR pi = VkPresentInfoKHR.calloc(st).sType$Default()
                        .pWaitSemaphores(st.longs(renderFinished[idx])).swapchainCount(1)
                        .pSwapchains(st.longs(swapchain)).pImageIndices(st.ints(idx));
                r = vkQueuePresentKHR(queue, pi);
                if (r == VK_ERROR_OUT_OF_DATE_KHR || r == VK_SUBOPTIMAL_KHR) recreateSwapchain();
                else if (r != VK_SUCCESS) throw new IllegalStateException("vkQueuePresentKHR failed: " + res(r));

                long now = System.nanoTime();
                frameMs[frame] = (now - prev) / 1e6;
                prev = now;
                frame++;
                slot = (slot + 1) % FRAMES_IN_FLIGHT;
            }
        }
        double total = (System.nanoTime() - loopStart) / 1e9;
        if (frame > 1) {
            double[] s = Arrays.copyOfRange(frameMs, 1, frame);   // drop the first (includes warm-up)
            Arrays.sort(s);
            double avg = Arrays.stream(s).average().orElse(0);
            double p99 = s[Math.min(s.length - 1, (int) Math.ceil(s.length * 0.99) - 1)];
            log(String.format("frames=%d in %.2f s: avg %.3f ms (%.1f FPS), p99 %.3f ms (1%% low %.1f FPS), min %.3f ms, max %.3f ms; first frame %.1f ms; swapchain recreations during loop=%d",
                    frame, total, avg, 1000.0 / avg, p99, 1000.0 / p99, s[0], s[s.length - 1], frameMs[0], swapchainRecreations - 1));
        }
        log(readbackMsg);
        boolean ok = frame == maxFrames && readbackOk;
        System.out.println();
        System.out.println(ok ? "SPIKE PASSED" : "SPIKE FAILED (frames=" + frame + "/" + maxFrames + ", readback=" + readbackOk + ")");
        return ok ? 0 : 1;
    }

    private void record(MemoryStack st, VkCommandBuffer cb, int imageIndex, float[] color, boolean readback) {
        vk(vkResetCommandBuffer(cb, 0), "vkResetCommandBuffer");
        vk(vkBeginCommandBuffer(cb, VkCommandBufferBeginInfo.calloc(st).sType$Default()
                .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)), "vkBeginCommandBuffer");

        VkClearValue.Buffer clear = VkClearValue.calloc(1, st);
        clear.color().float32(st.floats(color));
        VkRenderPassBeginInfo rp = VkRenderPassBeginInfo.calloc(st).sType$Default()
                .renderPass(renderPass).framebuffer(swapFramebuffers[imageIndex]).pClearValues(clear);
        rp.renderArea().offset().set(0, 0);
        rp.renderArea().extent().set(swapWidth, swapHeight);
        vkCmdBeginRenderPass(cb, rp, VK_SUBPASS_CONTENTS_INLINE);
        vkCmdEndRenderPass(cb);   // clear happens via loadOp; nothing to draw yet

        if (readback) {
            imageBarrier(st, cb, swapImages[imageIndex], VK_IMAGE_LAYOUT_PRESENT_SRC_KHR, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT,
                    VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);
            VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, st).bufferOffset(0);
            region.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
            region.imageOffset().set(0, 0, 0);
            region.imageExtent().set(1, 1, 1);
            vkCmdCopyImageToBuffer(cb, swapImages[imageIndex], VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, readbackBuffer, region);
            imageBarrier(st, cb, swapImages[imageIndex], VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,
                    VK_ACCESS_TRANSFER_READ_BIT, 0,
                    VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT);
        }
        vk(vkEndCommandBuffer(cb), "vkEndCommandBuffer");
    }

    private static void imageBarrier(MemoryStack st, VkCommandBuffer cb, long image, int oldLayout, int newLayout,
                                     int srcAccess, int dstAccess, int srcStage, int dstStage) {
        VkImageMemoryBarrier.Buffer b = VkImageMemoryBarrier.calloc(1, st).sType$Default()
                .srcAccessMask(srcAccess).dstAccessMask(dstAccess).oldLayout(oldLayout).newLayout(newLayout)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).image(image);
        b.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
        vkCmdPipelineBarrier(cb, srcStage, dstStage, 0, null, null, b);
    }

    // ---- diagnostics + teardown ----------------------------------------------------------
    /** Lists the native libraries actually mapped into this process, to show where MoltenVK came from. */
    private void printLoadedNatives() {
        try {
            Process p = new ProcessBuilder("/usr/sbin/lsof", "-p", Long.toString(ProcessHandle.current().pid()))
                    .redirectErrorStream(true).start();
            Set<String> seen = new java.util.LinkedHashSet<>();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) {
                    int i = line.indexOf('/');
                    if (i < 0) continue;
                    String path = line.substring(i);
                    String low = path.toLowerCase();
                    if (low.contains("moltenvk") || low.contains("lwjgl") || low.contains("glfw") || low.contains("shaderc")
                            || low.contains("libvulkan") || low.contains("/metal") )
                        seen.add(path);
                }
            }
            p.waitFor();
            log("native libraries mapped (from lsof):");
            for (String s : seen) System.out.println("      " + s);
        } catch (Exception e) {
            log("lsof unavailable: " + e);
        }
    }

    private void cleanup() {
        vkDestroyBuffer(device, readbackBuffer, null);
        vkFreeMemory(device, readbackMemory, null);
        destroySwapchainViews();
        vkDestroySwapchainKHR(device, swapchain, null);
        vkDestroyRenderPass(device, renderPass, null);
        for (int i = 0; i < FRAMES_IN_FLIGHT; i++) {
            vkDestroySemaphore(device, imageAvailable[i], null);
            vkDestroyFence(device, inFlight[i], null);
        }
        vkDestroyCommandPool(device, commandPool, null);
        vkDestroyDevice(device, null);
        vkDestroySurfaceKHR(instance, surface, null);
        vkDestroyInstance(instance, null);
        glfwDestroyWindow(window);
        glfwTerminate();
        log("clean shutdown");
    }
}
