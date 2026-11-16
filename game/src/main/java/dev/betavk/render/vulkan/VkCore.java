package dev.betavk.render.vulkan;

import dev.betavk.platform.Display;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.lwjgl.glfw.GLFWVulkan.*;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.vulkan.KHRSurface.*;
import static org.lwjgl.vulkan.KHRSwapchain.*;
import static org.lwjgl.vulkan.VK12.*;

/** Instance, device, swapchain(+depth), per-frame slots, memory helpers, submit/present. Main thread only. */
final class VkCore {
    static final String PACER_MODE = System.getProperty("betavk.pacer", "blit");
    static final double PACE_MARGIN_MS = Double.parseDouble(System.getProperty("betavk.paceMarginMs", "2.0"));
    static final int PACE_MULT = Integer.getInteger("betavk.paceMult", 1);
    static final int FRAMES = Integer.getInteger("betavk.frames", "blit".equalsIgnoreCase(PACER_MODE) ? 3 : 2);   // frames in flight
    static final int DEPTH_FORMAT = VK_FORMAT_D32_SFLOAT;
    static final int COLOR_FORMAT = VK_FORMAT_B8G8R8A8_UNORM;   // b1.7.3 has no gamma-correct pipeline

    /** Host-visible, coherent, persistently mapped buffer. */
    static final class Buf {
        long buffer, memory, ptr, size;
    }

    static void check(int r, String what) {
        if (r != VK_SUCCESS) throw new IllegalStateException(what + " failed: VkResult " + r);
    }

    VkInstance instance;
    long surface;
    VkPhysicalDevice pd;
    VkDevice device;
    VkQueue queue;
    int family;
    int hostMemType = -1, deviceMemType = -1;
    float maxAnisotropy = 1f;
    boolean multiDrawIndirect;
    int maxDrawIndirectCount = 1;
    String deviceName = "?";

    long swapchain = VK_NULL_HANDLE;
    int width, height;
    long[] images = new long[0], views = new long[0], framebuffers = new long[0], renderFinished = new long[0];
    long[] offscreenImages = new long[0], offscreenMems = new long[0], offscreenViews = new long[0], offscreenFBs = new long[0];
    final int[] slotImg = new int[FRAMES];
    int spareImg = FRAMES;
    long depthImage, depthMem, depthView;
    long renderPass;
    boolean presentImmediate = Boolean.getBoolean("betavk.uncapped");
    final boolean pacerBlit = presentImmediate && "blit".equalsIgnoreCase(PACER_MODE);
    final boolean pacerDelay = presentImmediate && "delay".equalsIgnoreCase(PACER_MODE);

    long cmdPool, transientPool;
    final VkCommandBuffer[] drawCmd = new VkCommandBuffer[FRAMES];
    final VkCommandBuffer[] uploadCmd = new VkCommandBuffer[FRAMES];
    VkCommandBuffer blitCmd;
    long drawDoneSem, blitFence;
    final long[] imageAvailable = new long[FRAMES];
    final long[] fence = new long[FRAMES];

    // -Dbetavk.vkstats=true: per-frame time spent in each blocking call, printed at exit
    static final boolean STATS = Boolean.getBoolean("betavk.vkstats");
    final long[][] statNs = STATS ? new long[6][1 << 18] : null;   // 0 fence wait, 1 acquire, 2 submit, 3 present, 4 join wait, 5 game work
    int statN, presentCount;
    private long lastPresentEndNs, frameBlockNs;
    private long nextPresentNs, lastSeenPresentDoneNs;
    private volatile long lastPresentDoneNs, lastPresentDurNs;
    private java.util.concurrent.Future<?> presentInflight;

    private long stat(int k, long t0, int fi) {
        long dt = System.nanoTime() - t0;
        if (STATS && fi < statNs[0].length) statNs[k][fi] += dt;
        return dt;
    }

    private static String modeName(int m) {
        return switch (m) {
            case VK_PRESENT_MODE_IMMEDIATE_KHR -> "IMMEDIATE";
            case VK_PRESENT_MODE_MAILBOX_KHR -> "MAILBOX";
            case VK_PRESENT_MODE_FIFO_KHR -> "FIFO";
            case VK_PRESENT_MODE_FIFO_RELAXED_KHR -> "FIFO_RELAXED";
            default -> "mode(" + m + ")";
        };
    }

    void printStats() {
        if (!STATS || statN == 0) return;
        int n = Math.min(statN, statNs[0].length);
        printStatsRange("vkstats", 0, n);
        if (n > 2000) printStatsRange("vkstats-steady", n / 3, n);
    }

    private void printStatsRange(String tag, int from, int to) {
        String[] names = {"fence wait", "acquire", "submit", "present", "join wait", "game work"};
        int len = Math.max(1, to - from);
        System.out.println("[" + tag + "] frames=" + len + " (range " + from + ".." + to + "), swapchainPresents=" + presentCount);
        for (int k = 0; k < 6; k++) {
            long[] a = java.util.Arrays.copyOfRange(statNs[k], from, to);
            java.util.Arrays.sort(a);
            long sum = 0;
            int over = 0;
            for (long v : a) { sum += v; if (v > 8_000_000L) over++; }
            System.out.printf("[%s] %-10s avg %.3f ms  p50 %.3f  p99 %.3f  max %.3f  frames>8ms: %d%n", tag, names[k], sum / 1e6 / len,
                    a[a.length / 2] / 1e6, a[Math.min(a.length - 1, (int) (a.length * 0.99))] / 1e6, a[a.length - 1] / 1e6, over);
        }
    }

    // current frame state
    int slot;
    boolean slotReady, frameActive, passEnded, submitted, uploadActive;
    int imageIndex;
    long frameCounter;

    // ---- init ----------------------------------------------------------------------------

    void init(long window) {
        try (MemoryStack st = stackPush()) {
            PointerBuffer glfwReq = glfwGetRequiredInstanceExtensions();
            if (glfwReq == null) throw new IllegalStateException("GLFW reports no Vulkan instance extensions");
            IntBuffer pc = st.mallocInt(1);
            vkEnumerateInstanceExtensionProperties((ByteBuffer) null, pc, null);
            VkExtensionProperties.Buffer props = VkExtensionProperties.malloc(pc.get(0), st);
            vkEnumerateInstanceExtensionProperties((ByteBuffer) null, pc, props);
            boolean props2 = false, enumeration = false;
            for (VkExtensionProperties p : props) {
                props2 |= p.extensionNameString().equals("VK_KHR_get_physical_device_properties2");
                enumeration |= p.extensionNameString().equals("VK_KHR_portability_enumeration");
            }
            List<String> ext = new ArrayList<>();
            for (int i = 0; i < glfwReq.remaining(); i++) ext.add(MemoryUtil.memUTF8(glfwReq.get(i)));
            if (props2) ext.add("VK_KHR_get_physical_device_properties2");
            if (enumeration) ext.add("VK_KHR_portability_enumeration");   // only offered behind a Vulkan loader
            final boolean validate = Boolean.getBoolean("betavk.validate");   // needs the Khronos layer on VK_LAYER_PATH (brew vulkan-validationlayers)
            if (validate) ext.add("VK_EXT_debug_utils");
            PointerBuffer pe = st.mallocPointer(ext.size());
            for (String e : ext) pe.put(st.UTF8(e));
            pe.flip();
            VkApplicationInfo app = VkApplicationInfo.calloc(st).sType$Default().pApplicationName(st.UTF8("beta-vulkan"))
                    .pEngineName(st.UTF8("beta-vulkan")).apiVersion(VK_API_VERSION_1_2);
            VkInstanceCreateInfo ci = VkInstanceCreateInfo.calloc(st).sType$Default().pApplicationInfo(app).ppEnabledExtensionNames(pe)
                    .flags(enumeration ? 0x1 : 0);
            if (validate) {
                PointerBuffer layers = st.mallocPointer(1);
                layers.put(st.UTF8("VK_LAYER_KHRONOS_validation")).flip();
                ci.ppEnabledLayerNames(layers);
            }
            PointerBuffer pi = st.mallocPointer(1);
            check(vkCreateInstance(ci, null, pi), "vkCreateInstance");
            instance = new VkInstance(pi.get(0), ci);
            if (validate) {
                VkDebugUtilsMessengerCallbackEXT cb = VkDebugUtilsMessengerCallbackEXT.create((severity, types, data, user) -> {
                    VkDebugUtilsMessengerCallbackDataEXT d = VkDebugUtilsMessengerCallbackDataEXT.create(data);
                    String sev = (severity & org.lwjgl.vulkan.EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT) != 0 ? "ERROR" : (severity & org.lwjgl.vulkan.EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT) != 0 ? "WARN" : "INFO";
                    System.out.println("[vk-validation:" + sev + "] " + d.pMessageIdNameString() + ": " + d.pMessageString());
                    if (sev.equals("ERROR")) new Throwable("validation error site").printStackTrace(System.out);
                    return VK_FALSE;
                });
                VkDebugUtilsMessengerCreateInfoEXT mci = VkDebugUtilsMessengerCreateInfoEXT.calloc(st).sType$Default()
                        .messageSeverity(org.lwjgl.vulkan.EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT | org.lwjgl.vulkan.EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT)
                        .messageType(org.lwjgl.vulkan.EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT | org.lwjgl.vulkan.EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT | org.lwjgl.vulkan.EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT)
                        .pfnUserCallback(cb);
                LongBuffer pm = st.mallocLong(1);
                check(org.lwjgl.vulkan.EXTDebugUtils.vkCreateDebugUtilsMessengerEXT(instance, mci, null, pm), "debug messenger");
                System.out.println("[vk] validation layers enabled");
            }

            LongBuffer ps = st.mallocLong(1);
            check(glfwCreateWindowSurface(instance, window, null, ps), "glfwCreateWindowSurface");
            surface = ps.get(0);

            vkEnumeratePhysicalDevices(instance, pc, null);
            PointerBuffer pds = st.mallocPointer(pc.get(0));
            vkEnumeratePhysicalDevices(instance, pc, pds);
            pd = new VkPhysicalDevice(pds.get(0), instance);
            VkPhysicalDeviceProperties dp = VkPhysicalDeviceProperties.malloc(st);
            vkGetPhysicalDeviceProperties(pd, dp);
            deviceName = dp.deviceNameString();
            maxAnisotropy = dp.limits().maxSamplerAnisotropy();
            maxDrawIndirectCount = dp.limits().maxDrawIndirectCount();

            vkGetPhysicalDeviceQueueFamilyProperties(pd, pc, null);
            VkQueueFamilyProperties.Buffer qf = VkQueueFamilyProperties.malloc(pc.get(0), st);
            vkGetPhysicalDeviceQueueFamilyProperties(pd, pc, qf);
            family = -1;
            for (int q = 0; q < qf.capacity() && family < 0; q++) {
                IntBuffer sup = st.mallocInt(1);
                vkGetPhysicalDeviceSurfaceSupportKHR(pd, q, surface, sup);
                if ((qf.get(q).queueFlags() & VK_QUEUE_GRAPHICS_BIT) != 0 && sup.get(0) == VK_TRUE) family = q;
            }
            if (family < 0) throw new IllegalStateException("no graphics+present queue family");

            VkPhysicalDeviceMemoryProperties mp = VkPhysicalDeviceMemoryProperties.malloc(st);
            vkGetPhysicalDeviceMemoryProperties(pd, mp);
            int hv = VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
            for (int i = 0; i < mp.memoryTypeCount(); i++) {
                int f = mp.memoryTypes(i).propertyFlags();
                if ((f & hv) == hv && ((f & VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT) != 0 || hostMemType < 0)) hostMemType = i;
                if ((f & VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT) != 0 && (f & VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT) == 0 && deviceMemType < 0) deviceMemType = i;
            }
            if (deviceMemType < 0) deviceMemType = hostMemType;

            vkEnumerateDeviceExtensionProperties(pd, (ByteBuffer) null, pc, null);
            VkExtensionProperties.Buffer dprops = VkExtensionProperties.malloc(pc.get(0), st);
            vkEnumerateDeviceExtensionProperties(pd, (ByteBuffer) null, pc, dprops);
            boolean portability = false;
            for (VkExtensionProperties p : dprops) portability |= p.extensionNameString().equals("VK_KHR_portability_subset");
            List<String> dext = new ArrayList<>(List.of(VK_KHR_SWAPCHAIN_EXTENSION_NAME));
            if (portability) dext.add("VK_KHR_portability_subset");
            PointerBuffer pde = st.mallocPointer(dext.size());
            for (String e : dext) pde.put(st.UTF8(e));
            pde.flip();

            VkPhysicalDeviceFeatures supFeats = VkPhysicalDeviceFeatures.malloc(st);
            vkGetPhysicalDeviceFeatures(pd, supFeats);
            multiDrawIndirect = supFeats.multiDrawIndirect();
            VkPhysicalDeviceFeatures feats = VkPhysicalDeviceFeatures.calloc(st)
                    .samplerAnisotropy(supFeats.samplerAnisotropy())
                    .multiDrawIndirect(multiDrawIndirect);
            VkDeviceQueueCreateInfo.Buffer qci = VkDeviceQueueCreateInfo.calloc(1, st).sType$Default().queueFamilyIndex(family).pQueuePriorities(st.floats(1f));
            VkDeviceCreateInfo dci = VkDeviceCreateInfo.calloc(st).sType$Default().pQueueCreateInfos(qci).ppEnabledExtensionNames(pde).pEnabledFeatures(feats);
            PointerBuffer pdev = st.mallocPointer(1);
            check(vkCreateDevice(pd, dci, null, pdev), "vkCreateDevice");
            device = new VkDevice(pdev.get(0), pd, dci);
            PointerBuffer pq = st.mallocPointer(1);
            vkGetDeviceQueue(device, family, 0, pq);
            queue = new VkQueue(pq.get(0), device);

            LongBuffer p = st.mallocLong(1);
            check(vkCreateCommandPool(device, VkCommandPoolCreateInfo.calloc(st).sType$Default()
                    .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT).queueFamilyIndex(family), null, p), "vkCreateCommandPool");
            cmdPool = p.get(0);
            check(vkCreateCommandPool(device, VkCommandPoolCreateInfo.calloc(st).sType$Default()
                    .flags(VK_COMMAND_POOL_CREATE_TRANSIENT_BIT).queueFamilyIndex(family), null, p), "vkCreateCommandPool(transient)");
            transientPool = p.get(0);
            PointerBuffer pcb = st.mallocPointer(FRAMES * 2 + 1);
            check(vkAllocateCommandBuffers(device, VkCommandBufferAllocateInfo.calloc(st).sType$Default().commandPool(cmdPool)
                    .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(FRAMES * 2 + 1), pcb), "vkAllocateCommandBuffers");
            for (int i = 0; i < FRAMES; i++) {
                drawCmd[i] = new VkCommandBuffer(pcb.get(i), device);
                uploadCmd[i] = new VkCommandBuffer(pcb.get(FRAMES + i), device);
                check(vkCreateSemaphore(device, VkSemaphoreCreateInfo.calloc(st).sType$Default(), null, p), "vkCreateSemaphore");
                imageAvailable[i] = p.get(0);
                check(vkCreateFence(device, VkFenceCreateInfo.calloc(st).sType$Default().flags(VK_FENCE_CREATE_SIGNALED_BIT), null, p), "vkCreateFence");
                fence[i] = p.get(0);
            }
            blitCmd = new VkCommandBuffer(pcb.get(FRAMES * 2), device);
            check(vkCreateSemaphore(device, VkSemaphoreCreateInfo.calloc(st).sType$Default(), null, p), "vkCreateSemaphore(drawDone)");
            drawDoneSem = p.get(0);
            check(vkCreateFence(device, VkFenceCreateInfo.calloc(st).sType$Default().flags(VK_FENCE_CREATE_SIGNALED_BIT), null, p), "vkCreateFence(blit)");
            blitFence = p.get(0);
            createRenderPass(st);
        }
        createSwapchain();
    }

    private void createRenderPass(MemoryStack st) {
        VkAttachmentDescription.Buffer att = VkAttachmentDescription.calloc(2, st);
        att.get(0).format(COLOR_FORMAT).samples(VK_SAMPLE_COUNT_1_BIT).loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR).storeOp(VK_ATTACHMENT_STORE_OP_STORE)
                .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE).stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED)
                .finalLayout(pacerBlit ? VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL : VK_IMAGE_LAYOUT_PRESENT_SRC_KHR);
        att.get(1).format(DEPTH_FORMAT).samples(VK_SAMPLE_COUNT_1_BIT).loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR).storeOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE).stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED).finalLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
        VkAttachmentReference.Buffer cref = VkAttachmentReference.calloc(1, st).attachment(0).layout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
        VkAttachmentReference dref = VkAttachmentReference.calloc(st).attachment(1).layout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
        VkSubpassDescription.Buffer sub = VkSubpassDescription.calloc(1, st).pipelineBindPoint(VK_PIPELINE_BIND_POINT_GRAPHICS)
                .colorAttachmentCount(1).pColorAttachments(cref).pDepthStencilAttachment(dref);
        int stages = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT | VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT;
        VkSubpassDependency.Buffer dep = VkSubpassDependency.calloc(1, st).srcSubpass(VK_SUBPASS_EXTERNAL).dstSubpass(0)
                .srcStageMask(stages).dstStageMask(stages).srcAccessMask(0)
                .dstAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT | VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT);
        LongBuffer p = st.mallocLong(1);
        check(vkCreateRenderPass(device, VkRenderPassCreateInfo.calloc(st).sType$Default().pAttachments(att).pSubpasses(sub).pDependencies(dep), null, p), "vkCreateRenderPass");
        renderPass = p.get(0);
    }

    // ---- swapchain -----------------------------------------------------------------------

    private void destroySwapchainResources() {
        for (long f : framebuffers) vkDestroyFramebuffer(device, f, null);
        for (long v : views) vkDestroyImageView(device, v, null);
        for (long s : renderFinished) vkDestroySemaphore(device, s, null);
        for (long f : offscreenFBs) if (f != 0) vkDestroyFramebuffer(device, f, null);
        for (long v : offscreenViews) if (v != 0) vkDestroyImageView(device, v, null);
        for (long im : offscreenImages) if (im != 0) vkDestroyImage(device, im, null);
        for (long m : offscreenMems) if (m != 0) vkFreeMemory(device, m, null);
        if (depthView != 0) vkDestroyImageView(device, depthView, null);
        if (depthImage != 0) vkDestroyImage(device, depthImage, null);
        if (depthMem != 0) vkFreeMemory(device, depthMem, null);
        framebuffers = new long[0];
        views = new long[0];
        renderFinished = new long[0];
        offscreenFBs = new long[0];
        offscreenViews = new long[0];
        offscreenImages = new long[0];
        offscreenMems = new long[0];
        depthView = depthImage = depthMem = 0;
    }

    void createSwapchain() {
        drain();
        needRecreate = false;
        vkDeviceWaitIdle(device);
        try (MemoryStack st = stackPush()) {
            VkSurfaceCapabilitiesKHR caps = VkSurfaceCapabilitiesKHR.malloc(st);
            check(vkGetPhysicalDeviceSurfaceCapabilitiesKHR(pd, surface, caps), "surface caps");
            int w = caps.currentExtent().width(), h = caps.currentExtent().height();
            if (w == 0xFFFFFFFF) {
                w = Display.getWidth();
                h = Display.getHeight();
            }
            w = Math.max(caps.minImageExtent().width(), Math.min(caps.maxImageExtent().width(), w));
            h = Math.max(caps.minImageExtent().height(), Math.min(caps.maxImageExtent().height(), h));
            width = w;
            height = h;

            IntBuffer n = st.mallocInt(1);
            vkGetPhysicalDeviceSurfacePresentModesKHR(pd, surface, n, null);
            IntBuffer modes = st.mallocInt(n.get(0));
            vkGetPhysicalDeviceSurfacePresentModesKHR(pd, surface, n, modes);
            String reqMode = System.getProperty("betavk.presentMode", "");
            int desiredMode = switch (reqMode.toLowerCase(java.util.Locale.ROOT)) {
                case "immediate" -> VK_PRESENT_MODE_IMMEDIATE_KHR;
                case "mailbox" -> VK_PRESENT_MODE_MAILBOX_KHR;
                case "fifo" -> VK_PRESENT_MODE_FIFO_KHR;
                case "fifo_relaxed" -> VK_PRESENT_MODE_FIFO_RELAXED_KHR;
                default -> presentImmediate ? VK_PRESENT_MODE_IMMEDIATE_KHR : VK_PRESENT_MODE_FIFO_KHR;
            };
            boolean hasDesired = false;
            List<String> availModes = new ArrayList<>();
            for (int i = 0; i < modes.capacity(); i++) {
                int m = modes.get(i);
                availModes.add(modeName(m));
                hasDesired |= m == desiredMode;
            }
            int mode = hasDesired ? desiredMode : VK_PRESENT_MODE_FIFO_KHR;

            int reqCount = Integer.getInteger("betavk.swapchainImages", caps.minImageCount() + 1);
            int count = Math.max(caps.minImageCount(), reqCount);
            if (caps.maxImageCount() > 0) count = Math.min(count, caps.maxImageCount());
            int usage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | (pacerBlit ? VK_IMAGE_USAGE_TRANSFER_DST_BIT : VK_IMAGE_USAGE_TRANSFER_SRC_BIT);
            if (pacerBlit && (caps.supportedUsageFlags() & VK_IMAGE_USAGE_TRANSFER_DST_BIT) == 0)
                throw new IllegalStateException("swapchain does not support TRANSFER_DST (supportedUsage=0x" + Integer.toHexString(caps.supportedUsageFlags()) + ")");
            VkSwapchainCreateInfoKHR ci = VkSwapchainCreateInfoKHR.calloc(st).sType$Default().surface(surface).minImageCount(count)
                    .imageFormat(COLOR_FORMAT).imageColorSpace(VK_COLOR_SPACE_SRGB_NONLINEAR_KHR).imageArrayLayers(1)
                    .imageUsage(usage)
                    .imageSharingMode(VK_SHARING_MODE_EXCLUSIVE).preTransform(caps.currentTransform())
                    .compositeAlpha(VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR).presentMode(mode).clipped(true).oldSwapchain(swapchain);
            ci.imageExtent().width(w).height(h);
            LongBuffer ps = st.mallocLong(1);
            check(vkCreateSwapchainKHR(device, ci, null, ps), "vkCreateSwapchainKHR");
            long old = swapchain;
            swapchain = ps.get(0);
            destroySwapchainResources();
            if (old != VK_NULL_HANDLE) vkDestroySwapchainKHR(device, old, null);

            vkGetSwapchainImagesKHR(device, swapchain, n, null);
            LongBuffer imgs = st.mallocLong(n.get(0));
            vkGetSwapchainImagesKHR(device, swapchain, n, imgs);
            System.out.printf("[betavk] swapchain %dx%d: caps=[min=%d max=%d usage=0x%x], modes=%s, present=%s (desired=%s), images=%d (requested=%d), pacer=%s%n",
                    w, h, caps.minImageCount(), caps.maxImageCount(), caps.supportedUsageFlags(), availModes, modeName(mode), modeName(desiredMode), n.get(0), count,
                    pacerBlit ? "blit(margin=" + PACE_MARGIN_MS + "ms,mult=" + PACE_MULT + ")" : pacerDelay ? "delay" : "off");
            images = new long[n.get(0)];
            views = new long[n.get(0)];
            framebuffers = new long[n.get(0)];
            renderFinished = new long[n.get(0)];

            // depth
            LongBuffer p = st.mallocLong(1);
            VkImageCreateInfo dic = VkImageCreateInfo.calloc(st).sType$Default().imageType(VK_IMAGE_TYPE_2D).format(DEPTH_FORMAT)
                    .mipLevels(1).arrayLayers(1).samples(VK_SAMPLE_COUNT_1_BIT).tiling(VK_IMAGE_TILING_OPTIMAL)
                    .usage(VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT).sharingMode(VK_SHARING_MODE_EXCLUSIVE).initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            dic.extent().set(w, h, 1);
            check(vkCreateImage(device, dic, null, p), "depth image");
            depthImage = p.get(0);
            depthMem = allocForImage(depthImage);
            VkImageViewCreateInfo dvi = VkImageViewCreateInfo.calloc(st).sType$Default().image(depthImage).viewType(VK_IMAGE_VIEW_TYPE_2D).format(DEPTH_FORMAT);
            dvi.subresourceRange().aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT).levelCount(1).layerCount(1);
            check(vkCreateImageView(device, dvi, null, p), "depth view");
            depthView = p.get(0);

            for (int i = 0; i < images.length; i++) {
                images[i] = imgs.get(i);
                VkImageViewCreateInfo vi = VkImageViewCreateInfo.calloc(st).sType$Default().image(images[i]).viewType(VK_IMAGE_VIEW_TYPE_2D).format(COLOR_FORMAT);
                vi.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
                check(vkCreateImageView(device, vi, null, p), "image view");
                views[i] = p.get(0);
                if (!pacerBlit) {
                    check(vkCreateFramebuffer(device, VkFramebufferCreateInfo.calloc(st).sType$Default().renderPass(renderPass)
                            .pAttachments(st.longs(views[i], depthView)).width(w).height(h).layers(1), null, p), "framebuffer");
                    framebuffers[i] = p.get(0);
                }
                check(vkCreateSemaphore(device, VkSemaphoreCreateInfo.calloc(st).sType$Default(), null, p), "semaphore");
                renderFinished[i] = p.get(0);
            }
            if (pacerBlit) {
                int offCount = FRAMES + 1;
                offscreenImages = new long[offCount];
                offscreenMems = new long[offCount];
                offscreenViews = new long[offCount];
                offscreenFBs = new long[offCount];
                VkImageCreateInfo oci = VkImageCreateInfo.calloc(st).sType$Default().imageType(VK_IMAGE_TYPE_2D).format(COLOR_FORMAT)
                        .mipLevels(1).arrayLayers(1).samples(VK_SAMPLE_COUNT_1_BIT).tiling(VK_IMAGE_TILING_OPTIMAL)
                        .usage(VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT)
                        .sharingMode(VK_SHARING_MODE_EXCLUSIVE).initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
                oci.extent().set(w, h, 1);
                for (int i = 0; i < offCount; i++) {
                    check(vkCreateImage(device, oci, null, p), "offscreen color");
                    offscreenImages[i] = p.get(0);
                    offscreenMems[i] = allocForImage(offscreenImages[i]);
                    VkImageViewCreateInfo ovi = VkImageViewCreateInfo.calloc(st).sType$Default().image(offscreenImages[i]).viewType(VK_IMAGE_VIEW_TYPE_2D).format(COLOR_FORMAT);
                    ovi.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
                    check(vkCreateImageView(device, ovi, null, p), "offscreen view");
                    offscreenViews[i] = p.get(0);
                    check(vkCreateFramebuffer(device, VkFramebufferCreateInfo.calloc(st).sType$Default().renderPass(renderPass)
                            .pAttachments(st.longs(offscreenViews[i], depthView)).width(w).height(h).layers(1), null, p), "offscreen framebuffer");
                    offscreenFBs[i] = p.get(0);
                }
                for (int i = 0; i < FRAMES; i++) slotImg[i] = i;
                spareImg = FRAMES;
            }
        }
    }

    // ---- memory helpers ------------------------------------------------------------------

    long allocForImage(long image) {
        try (MemoryStack st = stackPush()) {
            VkMemoryRequirements req = VkMemoryRequirements.malloc(st);
            vkGetImageMemoryRequirements(device, image, req);
            LongBuffer p = st.mallocLong(1);
            check(vkAllocateMemory(device, VkMemoryAllocateInfo.calloc(st).sType$Default().allocationSize(req.size()).memoryTypeIndex(deviceMemType), null, p), "image memory");
            check(vkBindImageMemory(device, image, p.get(0), 0), "bind image memory");
            return p.get(0);
        }
    }

    Buf createBuffer(long size, int usage) {
        try (MemoryStack st = stackPush()) {
            Buf b = new Buf();
            b.size = size;
            LongBuffer p = st.mallocLong(1);
            check(vkCreateBuffer(device, VkBufferCreateInfo.calloc(st).sType$Default().size(size).usage(usage).sharingMode(VK_SHARING_MODE_EXCLUSIVE), null, p), "vkCreateBuffer");
            b.buffer = p.get(0);
            VkMemoryRequirements req = VkMemoryRequirements.malloc(st);
            vkGetBufferMemoryRequirements(device, b.buffer, req);
            check(vkAllocateMemory(device, VkMemoryAllocateInfo.calloc(st).sType$Default().allocationSize(req.size()).memoryTypeIndex(hostMemType), null, p), "buffer memory");
            b.memory = p.get(0);
            check(vkBindBufferMemory(device, b.buffer, b.memory, 0), "bind buffer");
            PointerBuffer pd2 = st.mallocPointer(1);
            check(vkMapMemory(device, b.memory, 0, size, 0, pd2), "map");
            b.ptr = pd2.get(0);
            return b;
        }
    }

    void destroyBuffer(Buf b) {
        vkUnmapMemory(device, b.memory);
        vkDestroyBuffer(device, b.buffer, null);
        vkFreeMemory(device, b.memory, null);
    }

    /** Record + submit + wait: for rare setup work (texture creation). */
    void immediate(Consumer<VkCommandBuffer> work) {
        drain();
        try (MemoryStack st = stackPush()) {
            PointerBuffer pc = st.mallocPointer(1);
            check(vkAllocateCommandBuffers(device, VkCommandBufferAllocateInfo.calloc(st).sType$Default().commandPool(transientPool)
                    .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(1), pc), "alloc immediate cmd");
            VkCommandBuffer cb = new VkCommandBuffer(pc.get(0), device);
            check(vkBeginCommandBuffer(cb, VkCommandBufferBeginInfo.calloc(st).sType$Default().flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)), "begin immediate");
            work.accept(cb);
            check(vkEndCommandBuffer(cb), "end immediate");
            check(vkQueueSubmit(queue, VkSubmitInfo.calloc(st).sType$Default().pCommandBuffers(st.pointers(cb)), VK_NULL_HANDLE), "submit immediate");
            vkQueueWaitIdle(queue);
            vkFreeCommandBuffers(device, transientPool, pc);
        }
    }

    // ---- frame lifecycle -----------------------------------------------------------------

    /** Wait for this slot's previous use and reset its command buffers. */
    void slotAcquire() {
        if (slotReady) return;
        if (inflight[slot] != null) {
            long tj = System.nanoTime();
            join(inflight[slot]);
            frameBlockNs += stat(4, tj, statN);
            inflight[slot] = null;
        }
        long t0 = System.nanoTime();
        vkWaitForFences(device, fence[slot], true, Long.MAX_VALUE);
        frameBlockNs += stat(0, t0, statN);
        vkResetFences(device, fence[slot]);
        vkResetCommandBuffer(drawCmd[slot], 0);
        vkResetCommandBuffer(uploadCmd[slot], 0);
        slotReady = true;
        uploadActive = false;
        if (onSlotAcquired != null) onSlotAcquired.run();
    }

    VkCommandBuffer uploadCommands() {
        slotAcquire();
        if (!uploadActive) {
            try (MemoryStack st = stackPush()) {
                check(vkBeginCommandBuffer(uploadCmd[slot], VkCommandBufferBeginInfo.calloc(st).sType$Default().flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)), "begin upload");
            }
            uploadActive = true;
        }
        return uploadCmd[slot];
    }

    /** vkAcquireNextImageKHR (game thread) and vkQueuePresentKHR ("Vulkan submit" thread) both take the swapchain, which the spec says
     *  must be externally synchronised. The Khronos validation layer flags this occasionally. Serialising them cannot be the default:
     *  an acquire that has to wait for a free image would then hold the lock the present needs to free one, and the async pipeline
     *  loses a whole pacing step (measured 270 -> 147 FPS). MoltenVK locks internally and has run this way through every soak and
     *  benchmark, so the default is lock-free; -Dbetavk.validate serialises (short-timeout acquire, retried outside the lock) to
     *  keep validation runs clean. */
    private static final boolean SERIALISE_SWAPCHAIN = Boolean.getBoolean("betavk.validate");
    private final Object swapLock = new Object();

    private int acquireImage(IntBuffer pi) {
        if (!SERIALISE_SWAPCHAIN) return vkAcquireNextImageKHR(device, swapchain, Long.MAX_VALUE, imageAvailable[slot], VK_NULL_HANDLE, pi);
        while (true) {
            int r;
            synchronized (swapLock) {
                r = vkAcquireNextImageKHR(device, swapchain, 2_000_000L, imageAvailable[slot], VK_NULL_HANDLE, pi);
            }
            if (r != VK_NOT_READY && r != VK_TIMEOUT) return r;
            Thread.onSpinWait();
        }
    }

    /** @return false while minimised (zero-size framebuffer): drawing is skipped. */
    boolean ensureFrame(float[] clearColor, float clearDepth) {
        if (frameActive) return true;
        if (Display.getWidth() <= 0 || Display.getHeight() <= 0) return false;
        slotAcquire();
        if (needRecreate || Display.getWidth() != width || Display.getHeight() != height) createSwapchain();
        try (MemoryStack st = stackPush()) {
            long fb;
            if (pacerBlit) {
                fb = offscreenFBs[slotImg[slot]];
            } else {
                if (pacerDelay) waitForPaceSlot();
                IntBuffer pi = st.mallocInt(1);
                long ta = System.nanoTime();
                int r = acquireImage(pi);
                frameBlockNs += stat(1, ta, statN);
                if (r == VK_ERROR_OUT_OF_DATE_KHR) {
                    createSwapchain();
                    ta = System.nanoTime();
                    r = acquireImage(pi);
                    frameBlockNs += stat(1, ta, statN);
                }
                if (r != VK_SUCCESS && r != VK_SUBOPTIMAL_KHR) throw new IllegalStateException("vkAcquireNextImageKHR failed: " + r);
                imageIndex = pi.get(0);
                fb = framebuffers[imageIndex];
            }
            VkCommandBuffer cb = drawCmd[slot];
            check(vkBeginCommandBuffer(cb, VkCommandBufferBeginInfo.calloc(st).sType$Default().flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)), "begin draw");
            VkClearValue.Buffer cv = VkClearValue.calloc(2, st);
            cv.get(0).color().float32(st.floats(clearColor));
            cv.get(1).depthStencil().depth(1f - clearDepth).stencil(0);
            VkRenderPassBeginInfo rp = VkRenderPassBeginInfo.calloc(st).sType$Default().renderPass(renderPass).framebuffer(fb).pClearValues(cv);
            rp.renderArea().offset().set(0, 0);
            rp.renderArea().extent().set(width, height);
            vkCmdBeginRenderPass(cb, rp, VK_SUBPASS_CONTENTS_INLINE);
        }
        frameActive = true;
        passEnded = false;
        submitted = false;
        return true;
    }

    long colorImageForReadback() {
        return pacerBlit ? offscreenImages[slotImg[slot]] : images[imageIndex];
    }

    int colorLayoutForReadback() {
        return pacerBlit ? VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL : VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
    }

    private void waitForPaceSlot() {
        long intervalNs = 1_000_000_000L / (Math.max(30, Display.getRefreshRate()) * (long) Math.max(1, PACE_MULT));
        long marginNs = (long) (PACE_MARGIN_MS * 1_000_000.0);
        long now = System.nanoTime();
        if (nextPresentNs == 0L) {
            nextPresentNs = now + intervalNs;
            return;
        }
        long target = nextPresentNs - marginNs;
        long rem;
        while ((rem = target - System.nanoTime()) > 0L) {
            if (rem > 1_500_000L) java.util.concurrent.locks.LockSupport.parkNanos(rem - 1_000_000L);
            else Thread.onSpinWait();
        }
        now = System.nanoTime();
        if (now > nextPresentNs + intervalNs) nextPresentNs = now + intervalNs;
        else nextPresentNs += intervalNs;
    }

    // ---- asynchronous submission -------------------------------------------------------
    // vkQueueSubmit blocks inside MoltenVK while it waits for a Metal drawable (lazily acquired there), which in windowed
    // mode is tied to the display's refresh. So submit+present run on their own thread; the game thread only waits for a
    // slot's previous submission when it wants to reuse that slot (at most FRAMES frames queued).
    private final java.util.concurrent.ExecutorService worker = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Vulkan submit");
        t.setDaemon(true);
        return t;
    });
    private final java.util.concurrent.Future<?>[] inflight = new java.util.concurrent.Future<?>[FRAMES];
    volatile boolean needRecreate;
    /** Called (game thread) right after a slot's previous submission has completed and its command buffers were reset. */
    Runnable onSlotAcquired;

    private static void join(java.util.concurrent.Future<?> f) {
        try {
            f.get();
        } catch (Exception e) {
            throw new IllegalStateException("Vulkan submit thread failed", e.getCause() != null ? e.getCause() : e);
        }
    }

    /** Wait until the submit thread is idle (needed before using the queue from the game thread). */
    void drain() {
        for (int i = 0; i < FRAMES; i++) {
            if (inflight[i] != null) {
                join(inflight[i]);
                inflight[i] = null;
            }
        }
    }

    /** Game thread: close the render pass and the command buffers. */
    private void endRecording() {
        if (frameActive && !passEnded) {
            vkCmdEndRenderPass(drawCmd[slot]);
            passEnded = true;
        }
        if (frameActive && !cmdEnded) check(vkEndCommandBuffer(drawCmd[slot]), "end draw");
        if (uploadActive && !cmdEnded) check(vkEndCommandBuffer(uploadCmd[slot]), "end upload");
        cmdEnded = true;
    }

    private boolean cmdEnded;

    private static void barrier(MemoryStack st, VkCommandBuffer cb, long image, int oldL, int newL, int srcA, int dstA, int srcS, int dstS) {
        VkImageMemoryBarrier.Buffer b = VkImageMemoryBarrier.calloc(1, st).sType$Default().srcAccessMask(srcA).dstAccessMask(dstA)
                .oldLayout(oldL).newLayout(newL).srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).image(image);
        b.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).layerCount(1);
        vkCmdPipelineBarrier(cb, srcS, dstS, 0, null, null, b);
    }

    private void recordBlit(int srcImgIdx, int swapImgIdx) {
        vkResetCommandBuffer(blitCmd, 0);
        try (MemoryStack st = stackPush()) {
            VkCommandBuffer cb = blitCmd;
            check(vkBeginCommandBuffer(cb, VkCommandBufferBeginInfo.calloc(st).sType$Default().flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)), "begin blit");
            barrier(st, cb, offscreenImages[srcImgIdx], VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);
            barrier(st, cb, images[swapImgIdx], VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    0, VK_ACCESS_TRANSFER_WRITE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);
            VkImageCopy.Buffer region = VkImageCopy.calloc(1, st);
            region.srcSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
            region.dstSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
            region.extent().set(width, height, 1);
            vkCmdCopyImage(cb, offscreenImages[srcImgIdx], VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, images[swapImgIdx], VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region);
            barrier(st, cb, images[swapImgIdx], VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,
                    VK_ACCESS_TRANSFER_WRITE_BIT, 0, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT);
            check(vkEndCommandBuffer(cb), "end blit");
        }
    }

    private boolean shouldPresentBlit(long now) {
        if (presentInflight != null && !presentInflight.isDone()) return false;
        long intervalNs = 1_000_000_000L / (Math.max(30, Display.getRefreshRate()) * (long) Math.max(1, PACE_MULT));
        long marginNs = (long) (PACE_MARGIN_MS * 1_000_000.0);
        long doneNs = lastPresentDoneNs;
        if (doneNs != lastSeenPresentDoneNs) {
            lastSeenPresentDoneNs = doneNs;
            if (lastPresentDurNs > 2_000_000L) nextPresentNs = doneNs + intervalNs;
        }
        if (nextPresentNs == 0L || now >= nextPresentNs - marginNs) {
            if (vkGetFenceStatus(device, blitFence) != VK_SUCCESS) return false;
            if (nextPresentNs == 0L || now > nextPresentNs + intervalNs) nextPresentNs = now + intervalNs;
            else nextPresentNs += intervalNs;
            return true;
        }
        return false;
    }

    private void doSubmit(int s, int imgIdx, boolean frame, boolean upload, boolean useSwapchain, long extraSignalSem, int fi) {
        try (MemoryStack st = stackPush()) {
            int n = (upload ? 1 : 0) + (frame ? 1 : 0);
            long ts = System.nanoTime();
            if (n == 0) {
                check(vkQueueSubmit(queue, VkSubmitInfo.calloc(st).sType$Default(), fence[s]), "empty submit");   // still signal the fence
            } else {
                PointerBuffer cbs = st.mallocPointer(n);
                if (upload) cbs.put(uploadCmd[s]);
                if (frame) cbs.put(drawCmd[s]);
                cbs.flip();
                VkSubmitInfo si = VkSubmitInfo.calloc(st).sType$Default().pCommandBuffers(cbs);
                if (useSwapchain) {
                    si.waitSemaphoreCount(1).pWaitSemaphores(st.longs(imageAvailable[s]))
                            .pWaitDstStageMask(st.ints(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT))
                            .pSignalSemaphores(st.longs(renderFinished[imgIdx]));
                } else if (extraSignalSem != 0L) {
                    si.pSignalSemaphores(st.longs(extraSignalSem));
                }
                check(vkQueueSubmit(queue, si, fence[s]), "vkQueueSubmit");
            }
            stat(2, ts, fi);
        }
    }

    private void doSubmitBlit(int s, int imgIdx, int fi) {
        try (MemoryStack st = stackPush()) {
            long ts = System.nanoTime();
            VkSubmitInfo si = VkSubmitInfo.calloc(st).sType$Default()
                    .waitSemaphoreCount(2)
                    .pWaitSemaphores(st.longs(drawDoneSem, imageAvailable[s]))
                    .pWaitDstStageMask(st.ints(VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT))
                    .pCommandBuffers(st.pointers(blitCmd))
                    .pSignalSemaphores(st.longs(renderFinished[imgIdx]));
            check(vkQueueSubmit(queue, si, blitFence), "vkQueueSubmit(blit)");
            stat(2, ts, fi);
        }
    }

    private void doPresent(int imgIdx, int fi) {
        try (MemoryStack st = stackPush()) {
            long tp = System.nanoTime();
            VkPresentInfoKHR pinfo = VkPresentInfoKHR.calloc(st).sType$Default().pWaitSemaphores(st.longs(renderFinished[imgIdx]))
                    .swapchainCount(1).pSwapchains(st.longs(swapchain)).pImageIndices(st.ints(imgIdx));
            int r;
            if (SERIALISE_SWAPCHAIN) {
                synchronized (swapLock) {
                    r = vkQueuePresentKHR(queue, pinfo);
                }
            } else {
                r = vkQueuePresentKHR(queue, pinfo);
            }
            stat(3, tp, fi);
            if (r == VK_ERROR_OUT_OF_DATE_KHR || r == VK_SUBOPTIMAL_KHR) needRecreate = true;
            else if (r != VK_SUCCESS) throw new IllegalStateException("vkQueuePresentKHR failed: " + r);
        }
    }

    /** Synchronous submit (used for readback): drains the submit thread, then submits on the calling thread. Idempotent. */
    void submit() {
        if (submitted) return;
        drain();
        endRecording();
        doSubmit(slot, imageIndex, frameActive, uploadActive, !pacerBlit && frameActive, 0L, statN);
        submitted = true;
    }

    /** End of frame: hand the recorded work to the submit thread and advance to the next slot. */
    void present() {
        if (!slotReady) slotAcquire();   // an empty frame still signals the slot's fence, which must have been waited on and reset first
        endRecording();
        long now = System.nanoTime();
        if (STATS && lastPresentEndNs != 0 && statN < statNs[0].length) {
            statNs[5][statN] = Math.max(0L, (now - lastPresentEndNs) - frameBlockNs);
        }
        frameBlockNs = 0;
        final int s = slot, fi = statN;
        final boolean fa = frameActive, ua = uploadActive, already = submitted;
        boolean doBlit = false;
        int idx = imageIndex;
        if (pacerBlit) {
            if (fa && !already && !needRecreate && shouldPresentBlit(now)) {
                try (MemoryStack st = stackPush()) {
                    IntBuffer pi = st.mallocInt(1);
                    long ta = System.nanoTime();
                    int r = acquireImage(pi);
                    stat(1, ta, statN);
                    if (r == VK_ERROR_OUT_OF_DATE_KHR) {
                        needRecreate = true;
                    } else if (r != VK_SUCCESS && r != VK_SUBOPTIMAL_KHR) {
                        throw new IllegalStateException("vkAcquireNextImageKHR failed: " + r);
                    } else {
                        idx = pi.get(0);
                        int srcImg = slotImg[s];
                        slotImg[s] = spareImg;
                        spareImg = srcImg;
                        vkResetFences(device, blitFence);
                        recordBlit(srcImg, idx);
                        doBlit = true;
                    }
                }
            }
            final boolean blitNow = doBlit;
            final int imgIdx = idx;
            if (blitNow) presentCount++;
            inflight[s] = worker.submit(() -> {
                long t0 = blitNow ? System.nanoTime() : 0L;
                if (!already) doSubmit(s, imgIdx, fa, ua, false, blitNow ? drawDoneSem : 0L, fi);
                if (blitNow) {
                    doSubmitBlit(s, imgIdx, fi);
                    doPresent(imgIdx, fi);
                    long end = System.nanoTime();
                    lastPresentDurNs = end - t0;
                    lastPresentDoneNs = end;
                }
            });
            if (blitNow) presentInflight = inflight[s];
        } else {
            final int imgIdx = idx;
            if (fa) presentCount++;
            inflight[s] = worker.submit(() -> {
                if (!already) doSubmit(s, imgIdx, fa, ua, fa, 0L, fi);
                if (fa) doPresent(imgIdx, fi);
            });
        }
        frameActive = false;
        slotReady = false;
        uploadActive = false;
        submitted = false;
        cmdEnded = false;
        slot = (slot + 1) % FRAMES;
        frameCounter++;
        statN++;
        lastPresentEndNs = System.nanoTime();
    }

    void destroy() {
        drain();
        vkDeviceWaitIdle(device);
        destroySwapchainResources();
        vkDestroySwapchainKHR(device, swapchain, null);
        vkDestroyRenderPass(device, renderPass, null);
        for (int i = 0; i < FRAMES; i++) {
            vkDestroySemaphore(device, imageAvailable[i], null);
            vkDestroyFence(device, fence[i], null);
        }
        vkDestroySemaphore(device, drawDoneSem, null);
        vkDestroyFence(device, blitFence, null);
        vkDestroyCommandPool(device, cmdPool, null);
        vkDestroyCommandPool(device, transientPool, null);
        vkDestroyDevice(device, null);
        vkDestroySurfaceKHR(instance, surface, null);
        vkDestroyInstance(instance, null);
    }

    int memType(int bits, int props) {
        throw new UnsupportedOperationException();
    }
}
