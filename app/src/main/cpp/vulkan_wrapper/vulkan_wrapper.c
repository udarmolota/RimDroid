// libvulkan.so in the APK (RimWorld 1.7): forwards everything to the system Vulkan loader, except that
// it hides the display's rotation from the game. A landscape app on a phone whose screen is natively
// portrait gets a surface with currentTransform ROTATE_90/270. Waylith takes that transform for its
// swapchain without rotating what it draws, so the picture came out turned by 90 degrees. Here the
// surface reports IDENTITY and every swapchain is created with preTransform IDENTITY; Android's
// compositor does the rotation.
//
// .NET finds this file first (P/Invoke "vulkan" probes the app's native library folder), and the
// engine fetches every other function through the vkGetInstanceProcAddr exported here.
#include <android/log.h>
#include <dlfcn.h>
#include <pthread.h>
#include <stdio.h>
#include <stdarg.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>
#include <sys/stat.h>
#include <vulkan/vulkan.h>

#define API __attribute__((visibility("default")))
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "RimDroid/vkwrap", __VA_ARGS__)
// Pipeline diagnosis (2026-10-17), defined further down with the rest of it.
__attribute__((format(printf, 1, 2))) static void PDIAG(const char* fmt, ...);
__attribute__((format(printf, 3, 4))) static void pd_app(char* buf, int* m, const char* fmt, ...);
static int g_featuresLogged;
static PFN_vkVoidFunction resolve_alias(const char* name, PFN_vkVoidFunction (*get)(void*, const char*), void* owner);
static PFN_vkVoidFunction gipa_get(void* i, const char* n);
static PFN_vkVoidFunction debug_utils_stub(const char* name);

static void* g_real;
static PFN_vkGetInstanceProcAddr g_gipa;
static PFN_vkGetDeviceProcAddr g_gdpa;
static PFN_vkGetPhysicalDeviceSurfaceCapabilitiesKHR g_caps;
static PFN_vkCreateSwapchainKHR g_swapchain;

static void* real(const char* name) {
    if (!g_real) {
        g_real = dlopen("/system/lib64/libvulkan.so", RTLD_NOW | RTLD_LOCAL);
        if (!g_real) g_real = dlopen("libvulkan.so", RTLD_NOW | RTLD_LOCAL);   // never ourselves: different path
    }
    return g_real ? dlsym(g_real, name) : NULL;
}

// Android reports currentExtent already in the window's (landscape) size; only the transform says the
// display is turned. Reporting IDENTITY keeps the engine from passing ROTATE_90 as preTransform.
static VkResult VKAPI_CALL caps_wrap(VkPhysicalDevice pd, VkSurfaceKHR surface, VkSurfaceCapabilitiesKHR* caps) {
    VkResult r = g_caps(pd, surface, caps);
    if (r != VK_SUCCESS) return r;
    caps->currentTransform = VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR;
    caps->supportedTransforms |= VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR;
    return r;
}

// With preTransform != the display's transform Android answers VK_SUBOPTIMAL_KHR on every frame, and
// the engine then rebuilt its swapchain every frame. The image is still shown correctly (the
// compositor rotates it), so SUBOPTIMAL is reported as SUCCESS.
static PFN_vkQueuePresentKHR g_present;
static PFN_vkAcquireNextImageKHR g_acquire;
// libglfw.so (glfw_android.c): one tick per presented frame, read by the FPS overlay.
extern void rd_frame_tick(void) __attribute__((weak));

static VkResult VKAPI_CALL present_wrap(VkQueue q, const VkPresentInfoKHR* info) {
    VkResult r = g_present(q, info);
    if (rd_frame_tick) rd_frame_tick();
    if (info->pResults) for (uint32_t i = 0; i < info->swapchainCount; i++)
        if (info->pResults[i] == VK_SUBOPTIMAL_KHR) info->pResults[i] = VK_SUCCESS;
    return r == VK_SUBOPTIMAL_KHR ? VK_SUCCESS : r;
}
static VkResult VKAPI_CALL acquire_wrap(VkDevice d, VkSwapchainKHR sc, uint64_t timeout, VkSemaphore sem, VkFence f, uint32_t* idx) {
    VkResult r = g_acquire(d, sc, timeout, sem, f, idx);
    return r == VK_SUBOPTIMAL_KHR ? VK_SUCCESS : r;
}

// Hooks shared by the instance and device lookups.
static PFN_vkVoidFunction hook(const char* name, PFN_vkVoidFunction f);

// ---- BC on GPUs without it (Mali, PowerVR) ----------------------------------------------------------
// RimWorld 1.7's engine compresses textures to BC only (BC7 for colour, BC4 for masks), and Mali and
// PowerVR GPUs cannot sample BC: on an Adreno phone with BC hidden the engine had no other way and
// every texture failed (2026-10-13). Here every BC texture is created in the ETC2/EAC format with the
// same block size instead - BC7/BC3/BC2 -> ETC2 RGBA8, BC1 -> ETC2 RGB8, BC4 -> EAC R11, BC5 -> EAC
// RG11, 16 or 8 bytes per 4x4 block either way - so every size, offset and copy region the engine
// computes stays right, and the blocks are re-encoded in place in the engine's upload buffer just
// before the command buffer that copies them reaches the GPU (bctranscode.cpp: bcdec, etcpak). The
// engine's GPU encoder writes BC blocks the CPU never sees; on these GPUs DotnetPatcher turns it off
// (the engine then encodes on the CPU), so the blocks come from memory the CPU has mapped. BC1's
// one-bit alpha has no counterpart in ETC2 RGB8 and is lost; BC6H and signed BC4/BC5 are not handled.
// On when the GPU has no textureCompressionBC, or on any GPU for a test: the file rd_no_bc in the game
// folder ($HOME, set by ClrActivity) or RIMDROID_BC_CONVERT=1 (the launcher's switch, set together
// with the engine patch).
static int g_bcConv = -1;    // -1: not decided yet
static int g_realBc = -1;    // the GPU's own textureCompressionBC; -1: not asked yet
static PFN_vkGetPhysicalDeviceFeatures g_getFeatures;
static PFN_vkGetPhysicalDeviceFeatures2 g_getFeatures2;
static PFN_vkGetPhysicalDeviceFormatProperties g_getFormatProps;
static PFN_vkGetPhysicalDeviceFormatProperties2 g_getFormatProps2;
static PFN_vkGetPhysicalDeviceImageFormatProperties g_getImageFormatProps;
static PFN_vkGetPhysicalDeviceImageFormatProperties2 g_getImageFormatProps2;
static PFN_vkCreateDevice g_createDevice;

static int bc_convert(void) {
    if (g_bcConv >= 0) return g_bcConv;
    const char* e = getenv("RIMDROID_BC_CONVERT");
    const char* home = getenv("HOME");
    char path[512];
    snprintf(path, sizeof path, "%s/rd_no_bc", home ? home : "");
    int forced = (e && e[0] == '1') || (home && access(path, F_OK) == 0);
    if (!forced && g_realBc < 0) return 0;   // decided once the GPU's features are known
    g_bcConv = forced || g_realBc == 0;
    __android_log_print(ANDROID_LOG_WARN, "RimDroid/vkmem", "BC conversion %s (GPU's own BC: %s%s)", g_bcConv ? "ON" : "off",
                        g_realBc < 0 ? "not asked" : g_realBc ? "yes" : "no", forced ? "; forced for a test" : "");
    return g_bcConv;
}

static int is_bc(VkFormat f) { return f >= VK_FORMAT_BC1_RGB_UNORM_BLOCK && f <= VK_FORMAT_BC7_SRGB_BLOCK; }

// The format a BC texture is created in when converting; UNDEFINED where there is no encoder here.
static VkFormat bc_substitute(VkFormat f) {
    switch (f) {
        case VK_FORMAT_BC1_RGB_UNORM_BLOCK: case VK_FORMAT_BC1_RGBA_UNORM_BLOCK: return VK_FORMAT_ETC2_R8G8B8_UNORM_BLOCK;
        case VK_FORMAT_BC1_RGB_SRGB_BLOCK: case VK_FORMAT_BC1_RGBA_SRGB_BLOCK: return VK_FORMAT_ETC2_R8G8B8_SRGB_BLOCK;
        case VK_FORMAT_BC2_UNORM_BLOCK: case VK_FORMAT_BC3_UNORM_BLOCK: case VK_FORMAT_BC7_UNORM_BLOCK:
            return VK_FORMAT_ETC2_R8G8B8A8_UNORM_BLOCK;
        case VK_FORMAT_BC2_SRGB_BLOCK: case VK_FORMAT_BC3_SRGB_BLOCK: case VK_FORMAT_BC7_SRGB_BLOCK:
            return VK_FORMAT_ETC2_R8G8B8A8_SRGB_BLOCK;
        case VK_FORMAT_BC4_UNORM_BLOCK: return VK_FORMAT_EAC_R11_UNORM_BLOCK;
        case VK_FORMAT_BC5_UNORM_BLOCK: return VK_FORMAT_EAC_R11G11_UNORM_BLOCK;
        default: return VK_FORMAT_UNDEFINED;
    }
}

// The format to ask the driver about: the substitute while converting, else the format itself.
static VkFormat bc_query_format(VkFormat f) {
    if (!is_bc(f) || !bc_convert()) return f;
    return bc_substitute(f);   // UNDEFINED: the driver reports no support, as it should
}

// ---- BC conversion diagnosis (2026-10-17) -----------------------------------------------------------
// On a Mali tester's phone the Architect icons of beds, braziers, drapes, cribs... stayed empty, while
// the same conversion on an S25 forced into it (rd_no_bc) draws them: every texture re-encoded, no
// error. What differs is the driver's answers about ETC2/EAC, which the engine is given for BC. Logged
// once each: the format features per BC format asked about, every distinct image-format question and
// its answer, every distinct shape of BC image the engine creates, and any image or view the driver
// refuses. Compared between the two phones. RimDroid/vkbcdiag, and $HOME/vk_bcdiag.log (the instance;
// rewritten each run) for the bug report: the startup lines fall out of logcat in a long session.
static FILE* g_bcDiagFile;
static pthread_mutex_t g_bcDiagFileMu = PTHREAD_MUTEX_INITIALIZER;

__attribute__((format(printf, 1, 2)))
static void BCDIAG(const char* fmt, ...) {
    char line[512];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(line, sizeof line, fmt, ap);
    va_end(ap);
    __android_log_print(ANDROID_LOG_INFO, "RimDroid/vkbcdiag", "%s", line);
    pthread_mutex_lock(&g_bcDiagFileMu);
    if (!g_bcDiagFile) {
        const char* home = getenv("HOME");
        char path[512];
        snprintf(path, sizeof path, "%s/vk_bcdiag.log", home ? home : ".");
        g_bcDiagFile = fopen(path, "w");
    }
    if (g_bcDiagFile) { fprintf(g_bcDiagFile, "%s\n", line); fflush(g_bcDiagFile); }
    pthread_mutex_unlock(&g_bcDiagFileMu);
}
#define BCDIAG_KEYS 96
static uint64_t g_bcDiagKeys[BCDIAG_KEYS];
static int g_bcDiagCount;
static pthread_mutex_t g_bcDiagMu = PTHREAD_MUTEX_INITIALIZER;

// 1 the first time this key is seen (and there is room to remember it), else 0.
static int bcdiag_first(uint64_t key) {
    int first = 0;
    pthread_mutex_lock(&g_bcDiagMu);
    int i = 0;
    while (i < g_bcDiagCount && g_bcDiagKeys[i] != key) i++;
    if (i == g_bcDiagCount && g_bcDiagCount < BCDIAG_KEYS) { g_bcDiagKeys[g_bcDiagCount++] = key; first = 1; }
    pthread_mutex_unlock(&g_bcDiagMu);
    return first;
}

static void bcdiag_features(VkFormat asked, VkFormat q, const VkFormatProperties* p) {
    if (!is_bc(asked) || !bc_convert() || !bcdiag_first(0x1000000000ULL | (uint32_t)asked)) return;
    BCDIAG("format %d (as %d): optimal 0x%x, linear 0x%x, buffer 0x%x", asked, q,
           p->optimalTilingFeatures, p->linearTilingFeatures, p->bufferFeatures);
}

static void bcdiag_imageprops(VkFormat asked, VkImageType type, VkImageTiling tiling, VkImageUsageFlags usage,
                              VkImageCreateFlags flags, VkResult r, const VkImageFormatProperties* p) {
    if (!is_bc(asked) || !bc_convert()) return;
    uint64_t key = 0x2000000000ULL ^ ((uint64_t)asked << 40) ^ ((uint64_t)type << 36) ^ ((uint64_t)tiling << 34)
                   ^ ((uint64_t)usage << 16) ^ flags;
    if (!bcdiag_first(key)) return;
    if (r == VK_SUCCESS)
        BCDIAG("image props format %d type %d tiling %d usage 0x%x flags 0x%x: max %ux%ux%u, %u mips, %u layers, samples 0x%x",
               asked, type, tiling, usage, flags, p->maxExtent.width, p->maxExtent.height, p->maxExtent.depth,
               p->maxMipLevels, p->maxArrayLayers, p->sampleCounts);
    else
        BCDIAG("image props format %d type %d tiling %d usage 0x%x flags 0x%x: result %d", asked, type, tiling, usage, flags, r);
}

static void VKAPI_CALL getfeatures_wrap(VkPhysicalDevice pd, VkPhysicalDeviceFeatures* f) {
    g_getFeatures(pd, f);
    if (!g_featuresLogged) {
        g_featuresLogged = 1;
        PDIAG("features: robustBufferAccess %d depthClamp %d dualSrcBlend %d independentBlend %d shaderClipDistance %d fillModeNonSolid %d BC %d ETC2 %d ASTC %d samplerAnisotropy %d vertexStoresAtomics %d fragmentStoresAtomics %d",
              f->robustBufferAccess, f->depthClamp, f->dualSrcBlend, f->independentBlend, f->shaderClipDistance, f->fillModeNonSolid, f->textureCompressionBC,
              f->textureCompressionETC2, f->textureCompressionASTC_LDR, f->samplerAnisotropy, f->vertexPipelineStoresAndAtomics, f->fragmentStoresAndAtomics);
    }
    if (g_realBc < 0) g_realBc = f->textureCompressionBC;
    if (bc_convert()) f->textureCompressionBC = VK_TRUE;
}

static void VKAPI_CALL getfeatures2_wrap(VkPhysicalDevice pd, VkPhysicalDeviceFeatures2* f) {
    g_getFeatures2(pd, f);
    if (g_realBc < 0) g_realBc = f->features.textureCompressionBC;
    if (bc_convert()) f->features.textureCompressionBC = VK_TRUE;
}

static void VKAPI_CALL getformatprops_wrap(VkPhysicalDevice pd, VkFormat format, VkFormatProperties* p) {
    VkFormat q = bc_query_format(format);
    if (q == VK_FORMAT_UNDEFINED) { memset(p, 0, sizeof *p); return; }
    g_getFormatProps(pd, q, p);
    bcdiag_features(format, q, p);
}

static void VKAPI_CALL getformatprops2_wrap(VkPhysicalDevice pd, VkFormat format, VkFormatProperties2* p) {
    VkFormat q = bc_query_format(format);
    g_getFormatProps2(pd, q == VK_FORMAT_UNDEFINED ? format : q, p);
    if (q == VK_FORMAT_UNDEFINED) memset(&p->formatProperties, 0, sizeof p->formatProperties);
    else bcdiag_features(format, q, &p->formatProperties);
}

static VkResult VKAPI_CALL getimageformatprops_wrap(VkPhysicalDevice pd, VkFormat format, VkImageType type, VkImageTiling tiling,
                                                    VkImageUsageFlags usage, VkImageCreateFlags flags, VkImageFormatProperties* p) {
    VkFormat q = bc_query_format(format);
    if (q == VK_FORMAT_UNDEFINED) return VK_ERROR_FORMAT_NOT_SUPPORTED;
    VkResult r = g_getImageFormatProps(pd, q, type, tiling, usage, flags, p);
    bcdiag_imageprops(format, type, tiling, usage, flags, r, p);
    return r;
}

static VkResult VKAPI_CALL getimageformatprops2_wrap(VkPhysicalDevice pd, const VkPhysicalDeviceImageFormatInfo2* info, VkImageFormatProperties2* p) {
    VkFormat q = bc_query_format(info->format);
    if (q == VK_FORMAT_UNDEFINED) return VK_ERROR_FORMAT_NOT_SUPPORTED;
    if (q == info->format) return g_getImageFormatProps2(pd, info, p);
    VkPhysicalDeviceImageFormatInfo2 copy = *info;
    copy.format = q;
    VkResult r = g_getImageFormatProps2(pd, &copy, p);
    bcdiag_imageprops(info->format, info->type, info->tiling, info->usage, info->flags, r, &p->imageFormatProperties);
    return r;
}

// The engine asks for the textureCompressionBC it was told about; a GPU without it would refuse the
// device. Left out of the request (in the caller's own VkPhysicalDeviceFeatures2 too: the driver only
// reads it).
static VkResult VKAPI_CALL createdevice_wrap(VkPhysicalDevice pd, const VkDeviceCreateInfo* info, const VkAllocationCallbacks* a, VkDevice* out) {
    {
        char buf[1500]; int m = 0;
        pd_app(buf, &m, "vkCreateDevice: %u extensions enabled:", info->enabledExtensionCount);
        for (uint32_t k = 0; k < info->enabledExtensionCount; k++) pd_app(buf, &m, " %s", info->ppEnabledExtensionNames[k]);
        for (const VkBaseInStructure* x = (const VkBaseInStructure*)info->pNext; x; x = x->pNext) pd_app(buf, &m, " [sType %d]", (int)x->sType);
        PDIAG("%s", buf);
    }
    if (g_realBc < 0) {
        PFN_vkGetPhysicalDeviceFeatures gf = g_getFeatures ? g_getFeatures : (PFN_vkGetPhysicalDeviceFeatures)real("vkGetPhysicalDeviceFeatures");
        if (gf) { VkPhysicalDeviceFeatures f; gf(pd, &f); g_realBc = f.textureCompressionBC; }
    }
    if (bc_convert() && g_getFormatProps) {   // the driver's answers for every converted format, asked or not
        for (VkFormat f = VK_FORMAT_BC1_RGB_UNORM_BLOCK; f <= VK_FORMAT_BC7_SRGB_BLOCK; f++) {
            if (!bc_substitute(f)) continue;
            VkFormatProperties p;
            getformatprops_wrap(pd, f, &p);
        }
    }
    if (g_realBc != 0) return g_createDevice(pd, info, a, out);
    VkDeviceCreateInfo copy = *info;
    VkPhysicalDeviceFeatures features;
    if (info->pEnabledFeatures) {
        features = *info->pEnabledFeatures;
        features.textureCompressionBC = VK_FALSE;
        copy.pEnabledFeatures = &features;
    }
    for (VkBaseOutStructure* s = (VkBaseOutStructure*)info->pNext; s; s = s->pNext)
        if (s->sType == VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2)
            ((VkPhysicalDeviceFeatures2*)s)->features.textureCompressionBC = VK_FALSE;
    return g_createDevice(pd, &copy, a, out);
}

// ---- memory diagnosis (2026-10-14) ------------------------------------------------------------------
// The system counted 4.5 GB of GPU memory for the game, VMA (libvma.so) only 2.1 GB of it, and 2.6 GB
// were there before VMA held 0.4 GB. Counted here: every vkAllocateMemory (VMA's blocks and anything
// the engine allocates itself), descriptor pools as they are asked for, pipelines. RimDroid/vkmem.
#define MLOG(...) __android_log_print(ANDROID_LOG_INFO, "RimDroid/vkmem", __VA_ARGS__)
#define SLOTS 65536
#define TOMB (~0ULL)
static PFN_vkAllocateMemory g_allocMem;
static PFN_vkFreeMemory g_freeMem;
static PFN_vkCreateDescriptorPool g_descPool;
static PFN_vkCreateGraphicsPipelines g_gfxPipes;
static PFN_vkCreateComputePipelines g_compPipes;
static pthread_mutex_t g_mu = PTHREAD_MUTEX_INITIALIZER;
static struct { uint64_t mem; VkDeviceSize size; } g_mem[SLOTS];
static VkDeviceSize g_live, g_peak, g_largest;
static unsigned g_count, g_pipelines, g_pools;
static uint64_t g_lastLog;

static unsigned mb(VkDeviceSize b) { return (unsigned)((b + (1 << 19)) >> 20); }
static void cmd_summary(void);
static void pools_summary(void);
static void bc_summary(void);
static void bc_forget_mapping(VkDeviceMemory m);
static void bc_note_memory(VkDeviceMemory m, uint32_t type);
static void ring_add(const char* fmt, ...);
static unsigned g_nullWrites;
static void target_remember(VkImage img, uint32_t w, uint32_t h);
static uint64_t kgsl_mapped_bytes(void);

// Texture shrink state (see the texture shrink section below): an open-addressing hash set keyed by
// the image handle. A texture is created shrunk only once a slot is reserved for it: an untracked
// shrunk texture takes full-size copies past its edges (Ultra low on 1.7, 2026-10-14: thousands of
// 512+ textures overflowed the first, 512-entry table and the driver crashed in vkCmdCopyBufferToImage).
#define SHRUNK 65536                                   // power of two
#define SHRUNK_LIMIT (SHRUNK / 4 * 3)
#define SHRUNK_TOMB ((VkImage)(uintptr_t)~(uintptr_t)0)
typedef struct { VkImage image; uint32_t origMips, newMips; } ShrunkImage;
static pthread_mutex_t g_smu = PTHREAD_MUTEX_INITIALIZER;
static ShrunkImage g_shrunk[SHRUNK], g_shrunkSpare[SHRUNK];
static int g_shrunkUsed;          // set once anything was shrunk: the fast path for everything else
static unsigned g_shrunkLive, g_shrunkReserved, g_shrunkTombs;
static int g_shrinkOn = -1;       // -1: not read yet
static unsigned g_shrinkMin = 2048, g_shrinkCount, g_shrinkReadbacks;
static VkDeviceSize g_shrinkSavedApprox;

static void mem_summary(void) {   // g_mu held
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    uint64_t now = (uint64_t)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
    if (now - g_lastLog < 5000) return;
    g_lastLog = now;
    MLOG("device memory live %u MB in %u allocations (peak %u MB, largest %u MB); %u pipelines, %u descriptor pools; "
         "kgsl mapped into the process %u MB", mb(g_live), g_count, mb(g_peak), mb(g_largest), g_pipelines, g_pools,
         mb(kgsl_mapped_bytes()));
    cmd_summary();
    pools_summary();
    if (g_shrinkCount) MLOG("textures shrunk: %u, ~%u MB saved, %u readbacks of a dropped level", g_shrinkCount, mb(g_shrinkSavedApprox), g_shrinkReadbacks);
    if (g_nullWrites) MLOG("descriptor writes with a null handle so far: %u", g_nullWrites);
    bc_summary();
}

static VkResult VKAPI_CALL allocmem_wrap(VkDevice d, const VkMemoryAllocateInfo* info, const VkAllocationCallbacks* a, VkDeviceMemory* out) {
    VkResult r = g_allocMem(d, info, a, out);
    if (r != VK_SUCCESS) {
        MLOG("vkAllocateMemory %u MB (type %u) failed: %d", mb(info->allocationSize), info->memoryTypeIndex, r);
        return r;
    }
    uint64_t key = (uint64_t)*out;
    bc_note_memory(*out, info->memoryTypeIndex);
    pthread_mutex_lock(&g_mu);
    for (unsigned i = (unsigned)(key * 0x9E3779B97F4A7C15ULL >> 48), n = 0; n < SLOTS; i = (i + 1) & (SLOTS - 1), n++)
        if (g_mem[i].mem == 0 || g_mem[i].mem == TOMB) { g_mem[i].mem = key; g_mem[i].size = info->allocationSize; break; }
    g_live += info->allocationSize;
    g_count++;
    if (g_live > g_peak) g_peak = g_live;
    if (info->allocationSize > g_largest) g_largest = info->allocationSize;
    if (info->allocationSize >= (64u << 20)) MLOG("vkAllocateMemory %u MB (type %u)", mb(info->allocationSize), info->memoryTypeIndex);
    mem_summary();
    pthread_mutex_unlock(&g_mu);
    return r;
}

static void VKAPI_CALL freemem_wrap(VkDevice d, VkDeviceMemory m, const VkAllocationCallbacks* a) {
    uint64_t key = (uint64_t)m;
    if (key) bc_forget_mapping(m);
    if (key) {
        pthread_mutex_lock(&g_mu);
        for (unsigned i = (unsigned)(key * 0x9E3779B97F4A7C15ULL >> 48), n = 0; n < SLOTS && g_mem[i].mem; i = (i + 1) & (SLOTS - 1), n++)
            if (g_mem[i].mem == key) { g_live -= g_mem[i].size; g_count--; g_mem[i].mem = TOMB; break; }
        mem_summary();
        pthread_mutex_unlock(&g_mu);
    }
    g_freeMem(d, m, a);
}

// The GPU memory the kernel has mapped into this process (every kgsl mapping in /proc/self/maps):
// the driver's own allocations included, which no Vulkan call reports. Read around descriptor pool
// creation to see what each pool really costs (a reviewer's lead: five pools reserving 3.4 million
// descriptors; the process map showed five 360 MB mappings).
static uint64_t kgsl_mapped_bytes(void) {
    FILE* f = fopen("/proc/self/maps", "r");
    if (!f) return 0;
    char line[512];
    uint64_t total = 0;
    while (fgets(line, sizeof line, f)) {
        if (!strstr(line, "kgsl")) continue;
        unsigned long long lo, hi;
        if (sscanf(line, "%llx-%llx", &lo, &hi) == 2 && hi > lo) total += hi - lo;
    }
    fclose(f);
    return total;
}

// Measured 2026-10-14 (Adreno 830): the driver backs a descriptor pool with GPU memory for its whole
// declared capacity, ~23 KB per set. RimWorld 1.7's MeshBatchRenderer reserves five pools of 16384
// sets at startup (+375 MB each, 1.9 GB) and more of 2048 sets as it goes (+48 MB each): ~2.5 GB of
// the game's 4.5 GB of GPU memory sat in empty tables, which on a phone is RAM. Pools of 16384 sets
// are created with their capacity divided by RIMDROID_VK_POOL_DIVISOR (default 8, i.e. 2048 sets,
// the size the engine's later pools use; 1 = as declared). The sets each pool hands out are counted
// so that a pool running out (VK_ERROR_OUT_OF_POOL_MEMORY, which the engine's material set cache is
// said to catch and fall back from) is visible in the log, with its high-water mark.
#define POOLS 128
static PFN_vkAllocateDescriptorSets g_allocSets;
static PFN_vkFreeDescriptorSets g_freeSets;
static PFN_vkResetDescriptorPool g_resetDescPool;
static PFN_vkDestroyDescriptorPool g_destroyDescPool;
static struct { VkDescriptorPool pool; unsigned id, maxSets, live, peak, failed; } g_poolTable[POOLS];
static unsigned g_poolDivisor = 8;

static int pool_index(VkDescriptorPool p) {   // g_mu held
    for (int i = 0; i < POOLS; i++) if (g_poolTable[i].pool == p) return i;
    return -1;
}

static VkResult VKAPI_CALL descpool_wrap(VkDevice d, const VkDescriptorPoolCreateInfo* info, const VkAllocationCallbacks* a, VkDescriptorPool* out) {
    static int divisorRead;
    if (!divisorRead) {
        divisorRead = 1;
        const char* e = getenv("RIMDROID_VK_POOL_DIVISOR");
        if (e && atoi(e) >= 1) g_poolDivisor = (unsigned)atoi(e);
    }
    VkDescriptorPoolCreateInfo copy = *info;
    VkDescriptorPoolSize sizesCopy[32];
    // 16384-set pools: /8 (2048 sets, peak use seen 332); the engine's own 2048-set pools: /2 (peak 45).
    // Only those round capacities are reservations made up front. A pool sized to an exact count, like
    // the texture encoder's (one set per texture of a batch, e.g. 2724), needs every set: one short,
    // the engine drops the whole batch to its slower CPU encoder (13 times on a fresh 1.7 instance,
    // 2026-10-15). Those stay as declared; they live about a second.
    int reservation = (info->maxSets & (info->maxSets - 1)) == 0;
    unsigned divisor = g_poolDivisor > 1 && reservation && info->poolSizeCount <= 32
            ? (info->maxSets >= 16384 ? g_poolDivisor : info->maxSets >= 2048 ? 2 : 1) : 1;
    if (divisor > 1) {
        copy.maxSets = info->maxSets / divisor;
        for (uint32_t i = 0; i < info->poolSizeCount; i++) {
            sizesCopy[i] = info->pPoolSizes[i];
            sizesCopy[i].descriptorCount = info->pPoolSizes[i].descriptorCount / divisor;
            if (sizesCopy[i].descriptorCount == 0) sizesCopy[i].descriptorCount = 1;
        }
        copy.pPoolSizes = sizesCopy;
    }
    uint64_t before = kgsl_mapped_bytes();
    VkResult r = g_descPool(d, &copy, a, out);
    uint64_t after = kgsl_mapped_bytes();
    pthread_mutex_lock(&g_mu);
    g_pools++;
    if (r == VK_SUCCESS) {
        int i = pool_index(VK_NULL_HANDLE);
        if (i >= 0) { g_poolTable[i].pool = *out; g_poolTable[i].id = g_pools; g_poolTable[i].maxSets = copy.maxSets; g_poolTable[i].live = g_poolTable[i].peak = g_poolTable[i].failed = 0; }
    }
    char sizes[512];
    int len = 0;
    sizes[0] = 0;
    for (uint32_t i = 0; i < copy.poolSizeCount && len < (int)sizeof sizes - 32; i++)
        len += snprintf(sizes + len, sizeof sizes - len, " type%d:%u", copy.pPoolSizes[i].type, copy.pPoolSizes[i].descriptorCount);
    MLOG("descriptor pool #%u: maxSets %u (declared /%u), flags 0x%x,%s -> result %d, GPU memory of the process %u -> %u MB (+%u MB)",
         g_pools, copy.maxSets, divisor, copy.flags, sizes, r, mb(before), mb(after), mb(after > before ? after - before : 0));
    pthread_mutex_unlock(&g_mu);
    return r;
}

static VkResult VKAPI_CALL allocsets_wrap(VkDevice d, const VkDescriptorSetAllocateInfo* info, VkDescriptorSet* out) {
    VkResult r = g_allocSets(d, info, out);
    pthread_mutex_lock(&g_mu);
    mem_summary();
    int i = pool_index(info->descriptorPool);
    if (i >= 0) {
        if (r == VK_SUCCESS) {
            g_poolTable[i].live += info->descriptorSetCount;
            if (g_poolTable[i].live > g_poolTable[i].peak) g_poolTable[i].peak = g_poolTable[i].live;
        } else if (g_poolTable[i].failed++ < 20) {
            MLOG("descriptor pool #%u OUT OF SETS: result %d asking %u, live %u of %u (peak %u)", g_poolTable[i].id, r,
                 info->descriptorSetCount, g_poolTable[i].live, g_poolTable[i].maxSets, g_poolTable[i].peak);
        }
    }
    pthread_mutex_unlock(&g_mu);
    return r;
}

static VkResult VKAPI_CALL freesets_wrap(VkDevice d, VkDescriptorPool p, uint32_t n, const VkDescriptorSet* sets) {
    pthread_mutex_lock(&g_mu);
    int i = pool_index(p);
    if (i >= 0) g_poolTable[i].live = g_poolTable[i].live >= n ? g_poolTable[i].live - n : 0;
    pthread_mutex_unlock(&g_mu);
    return g_freeSets(d, p, n, sets);
}

static VkResult VKAPI_CALL resetdescpool_wrap(VkDevice d, VkDescriptorPool p, VkDescriptorPoolResetFlags f) {
    pthread_mutex_lock(&g_mu);
    int i = pool_index(p);
    if (i >= 0) g_poolTable[i].live = 0;
    pthread_mutex_unlock(&g_mu);
    return g_resetDescPool(d, p, f);
}

static void VKAPI_CALL destroydescpool_wrap(VkDevice d, VkDescriptorPool p, const VkAllocationCallbacks* a) {
    pthread_mutex_lock(&g_mu);
    int i = pool_index(p);
    if (i >= 0) {
        MLOG("descriptor pool #%u destroyed: peak %u sets of %u, %u failed allocations", g_poolTable[i].id, g_poolTable[i].peak,
             g_poolTable[i].maxSets, g_poolTable[i].failed);
        g_poolTable[i].pool = VK_NULL_HANDLE;
    }
    pthread_mutex_unlock(&g_mu);
    g_destroyDescPool(d, p, a);
}

static void pools_summary(void) {   // g_mu held
    char line[768];
    int len = 0;
    line[0] = 0;
    for (int i = 0; i < POOLS && len < (int)sizeof line - 48; i++) {
        if (!g_poolTable[i].pool || g_poolTable[i].maxSets < 1024) continue;
        len += snprintf(line + len, sizeof line - len, " #%u:%u/%u(peak %u)", g_poolTable[i].id, g_poolTable[i].live,
                        g_poolTable[i].maxSets, g_poolTable[i].peak);
    }
    MLOG("descriptor sets in use, big pools:%s", line);
}

// Second round (same day): the kernel charged the process 4.7 GB of GPU memory while vkAllocateMemory
// accounted for 2.1 GB; the process map showed the rest as five 360 MB and eight 46 MB kgsl mappings
// the engine never asked for through vkAllocateMemory: driver-internal memory. Suspects: command
// buffers that are allocated and never freed or reset (Adreno keeps their recording memory per pool),
// query pools, images the driver backs itself. Counted here.
static PFN_vkCreateCommandPool g_createPool;
static PFN_vkDestroyCommandPool g_destroyPool;
static PFN_vkResetCommandPool g_resetPool;
static PFN_vkAllocateCommandBuffers g_allocCb;
static PFN_vkFreeCommandBuffers g_freeCb;
static PFN_vkCreateQueryPool g_queryPool;
static PFN_vkCreateImage g_createImage;
static PFN_vkMapMemory g_map;
static unsigned g_cmdPools, g_cmdPoolsDestroyed, g_cmdResets, g_cbLive, g_cbAllocated, g_cbFreed, g_cbNext, g_images, g_maps;
static VkDeviceSize g_mapped;

static void cmd_summary(void) {   // g_mu held
    MLOG("command buffers: %u live (%u allocated, %u freed), %u pools (%u destroyed, %u resets); %u images created; "
         "%u maps (%u MB mapped)", g_cbLive, g_cbAllocated, g_cbFreed, g_cmdPools, g_cmdPoolsDestroyed, g_cmdResets,
         g_images, g_maps, mb(g_mapped));
}

static VkResult VKAPI_CALL createpool_wrap(VkDevice d, const VkCommandPoolCreateInfo* info, const VkAllocationCallbacks* a, VkCommandPool* out) {
    pthread_mutex_lock(&g_mu);
    g_cmdPools++;
    if (g_cmdPools <= 40) MLOG("command pool #%u: queue family %u, flags 0x%x%s%s", g_cmdPools, info->queueFamilyIndex, info->flags,
                               (info->flags & VK_COMMAND_POOL_CREATE_TRANSIENT_BIT) ? " transient" : "",
                               (info->flags & VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT) ? " reset-per-buffer" : "");
    pthread_mutex_unlock(&g_mu);
    return g_createPool(d, info, a, out);
}

static void VKAPI_CALL destroypool_wrap(VkDevice d, VkCommandPool p, const VkAllocationCallbacks* a) {
    pthread_mutex_lock(&g_mu);
    g_cmdPoolsDestroyed++;
    pthread_mutex_unlock(&g_mu);
    g_destroyPool(d, p, a);
}

static VkResult VKAPI_CALL resetpool_wrap(VkDevice d, VkCommandPool p, VkCommandPoolResetFlags f) {
    __atomic_add_fetch(&g_cmdResets, 1, __ATOMIC_RELAXED);
    return g_resetPool(d, p, f);
}

static VkResult VKAPI_CALL alloccb_wrap(VkDevice d, const VkCommandBufferAllocateInfo* info, VkCommandBuffer* out) {
    VkResult r = g_allocCb(d, info, out);
    if (r != VK_SUCCESS) return r;
    pthread_mutex_lock(&g_mu);
    g_cbAllocated += info->commandBufferCount;
    g_cbLive += info->commandBufferCount;
    if (g_cbLive >= g_cbNext) { g_cbNext = g_cbLive + 256; cmd_summary(); }
    pthread_mutex_unlock(&g_mu);
    return r;
}

static void bc_forget(VkCommandBuffer cb);

static void VKAPI_CALL freecb_wrap(VkDevice d, VkCommandPool p, uint32_t n, const VkCommandBuffer* cbs) {
    for (uint32_t i = 0; i < n; i++) bc_forget(cbs[i]);
    pthread_mutex_lock(&g_mu);
    g_cbFreed += n;
    g_cbLive -= n;
    pthread_mutex_unlock(&g_mu);
    g_freeCb(d, p, n, cbs);
}

static VkResult VKAPI_CALL querypool_wrap(VkDevice d, const VkQueryPoolCreateInfo* info, const VkAllocationCallbacks* a, VkQueryPool* out) {
    MLOG("query pool: type %d, %u queries", info->queryType, info->queryCount);
    return g_queryPool(d, info, a, out);
}

// ---- BC conversion bookkeeping ---------------------------------------------------------------------
// What the re-encoding needs to find the engine's blocks (see "BC on GPUs without it"): which images
// were created converted and from which BC format, where each buffer is bound, where each memory
// object is mapped. A copy into a converted image is noted for its command buffer and done at that
// command buffer's vkQueueSubmit: by then the engine has written its upload buffer, and the GPU has
// not read it yet. A copy whose buffer the CPU has not mapped (blocks the GPU produced) is counted
// and logged: its blocks stay BC.
#include "bctranscode.h"

typedef struct { uint64_t key, a, b; } MapSlot;
typedef struct { MapSlot* s; unsigned cap, live, tombs; } HMap;
#define HMAP_TOMB (~0ULL)

static unsigned hmap_slot(const HMap* m, uint64_t key) { return (unsigned)((key * 0x9E3779B97F4A7C15ULL) >> 32) & (m->cap - 1); }

static MapSlot* hmap_find(HMap* m, uint64_t key) {
    if (!m->cap || !key || key == HMAP_TOMB) return NULL;
    for (unsigned i = hmap_slot(m, key), n = 0; n < m->cap; i = (i + 1) & (m->cap - 1), n++) {
        if (m->s[i].key == 0) return NULL;
        if (m->s[i].key == key) return &m->s[i];
    }
    return NULL;
}

static void hmap_put(HMap* m, uint64_t key, uint64_t a, uint64_t b);

// Twice the size when at least a quarter is live, else the same size without the tombstones.
static void hmap_rebuild(HMap* m) {
    unsigned cap = !m->cap ? 1024 : m->live * 4 >= m->cap ? m->cap * 2 : m->cap;
    MapSlot* s = calloc(cap, sizeof *s);
    if (!s) return;
    MapSlot* old = m->s;
    unsigned oldCap = m->cap;
    m->s = s;
    m->cap = cap;
    m->live = m->tombs = 0;
    for (unsigned i = 0; i < oldCap; i++)
        if (old[i].key && old[i].key != HMAP_TOMB) hmap_put(m, old[i].key, old[i].a, old[i].b);
    free(old);
}

static void hmap_put(HMap* m, uint64_t key, uint64_t a, uint64_t b) {
    if (!key || key == HMAP_TOMB) return;
    MapSlot* hit = hmap_find(m, key);
    if (hit) { hit->a = a; hit->b = b; return; }
    if ((m->live + m->tombs + 1) * 4 > m->cap * 3) hmap_rebuild(m);
    if ((m->live + m->tombs + 1) * 4 > m->cap * 3) return;   // out of memory: not tracked
    for (unsigned i = hmap_slot(m, key); ; i = (i + 1) & (m->cap - 1)) {
        if (m->s[i].key && m->s[i].key != HMAP_TOMB) continue;
        if (m->s[i].key == HMAP_TOMB) m->tombs--;
        m->s[i].key = key;
        m->s[i].a = a;
        m->s[i].b = b;
        m->live++;
        return;
    }
}

static void hmap_del(HMap* m, uint64_t key) {
    MapSlot* hit = hmap_find(m, key);
    if (!hit) return;
    hit->key = HMAP_TOMB;
    m->live--;
    m->tombs++;
}

typedef struct { VkCommandBuffer cb; RdBcRegion r; } BcJob;
static pthread_mutex_t g_cvmu = PTHREAD_MUTEX_INITIALIZER;
static HMap g_convImages;   // image -> its BC format
static HMap g_bufBinds;     // buffer -> memory, offset
static HMap g_memMaps;      // memory -> pointer, offset it was mapped at
static HMap g_bufInfo;      // buffer -> usage, size (for the log)
static HMap g_memTypes;     // memory -> memory type index (for the log)
static BcJob* g_jobs;
static unsigned g_jobCount, g_jobCap;
// Copies from a mapped buffer into one the CPU does not map (vkCmdCopyBuffer), until their command
// buffer is submitted: blocks relayed that way into a converted image are re-encoded at the source.
typedef struct { VkCommandBuffer cb; VkBuffer dst; VkDeviceSize dstOff, size; uint8_t* src; } BcRelay;
static BcRelay* g_relays;
static unsigned g_relayCount, g_relayCap;
static unsigned g_convImageCount, g_convNoEncoder, g_convUnmapped, g_convReadbacks, g_convRegions, g_convRelayed;
static uint64_t g_convBlocks, g_convNanos;

static void bc_track_image(VkImage img, VkFormat bc) {
    pthread_mutex_lock(&g_cvmu);
    hmap_put(&g_convImages, (uint64_t)img, (uint64_t)bc, 0);
    g_convImageCount++;
    pthread_mutex_unlock(&g_cvmu);
}

// The BC format a converted image was asked for, 0 if it is not one. g_cvmu held.
static VkFormat bc_image_format(VkImage img) {
    MapSlot* hit = hmap_find(&g_convImages, (uint64_t)img);
    return hit ? (VkFormat)hit->a : (VkFormat)0;
}

static uint8_t* bc_buffer_ptr(VkBuffer b, VkDeviceSize off) {
    MapSlot* bind = hmap_find(&g_bufBinds, (uint64_t)b);
    MapSlot* map = bind ? hmap_find(&g_memMaps, bind->a) : NULL;
    if (!map || bind->b < map->b) return NULL;
    return (uint8_t*)(uintptr_t)map->a + (bind->b - map->b) + off;
}

static void bc_add_job(VkCommandBuffer cb, const RdBcRegion* r) {
    if (g_jobCount == g_jobCap) {
        unsigned cap = g_jobCap ? g_jobCap * 2 : 256;
        BcJob* jobs = realloc(g_jobs, cap * sizeof *jobs);
        if (!jobs) return;
        g_jobs = jobs;
        g_jobCap = cap;
    }
    g_jobs[g_jobCount].cb = cb;
    g_jobs[g_jobCount].r = *r;
    g_jobCount++;
}

// One copy region into a converted image. g_cvmu held.
static void bc_note_region(VkCommandBuffer cb, VkFormat fmt, VkBuffer src, VkDeviceSize bufferOffset, uint32_t rowLength,
                           uint32_t imageHeight, VkExtent3D extent, uint32_t layers) {
    uint32_t bytes = (fmt <= VK_FORMAT_BC1_RGBA_SRGB_BLOCK || fmt == VK_FORMAT_BC4_UNORM_BLOCK || fmt == VK_FORMAT_BC4_SNORM_BLOCK) ? 8 : 16;
    uint32_t rowTexels = rowLength ? rowLength : extent.width, heightTexels = imageHeight ? imageHeight : extent.height;
    RdBcRegion r;
    r.bw = (extent.width + 3) / 4;
    r.bh = (extent.height + 3) / 4;
    r.rowPitch = (rowTexels + 3) / 4 * bytes;
    r.slicePitch = (heightTexels + 3) / 4 * r.rowPitch;
    r.slices = layers * (extent.depth ? extent.depth : 1);
    r.format = fmt;
    r.data = bc_buffer_ptr(src, bufferOffset);
    if (r.data) { bc_add_job(cb, &r); return; }
    // Not mapped: blocks a copy brought over from a mapped buffer, in a command buffer not submitted
    // yet, are re-encoded at their source when that one is submitted.
    VkDeviceSize len = (VkDeviceSize)r.slicePitch * (r.slices - 1) + (VkDeviceSize)r.rowPitch * (r.bh - 1) + (VkDeviceSize)r.bw * bytes;
    for (unsigned i = g_relayCount; i-- > 0;) {
        BcRelay* x = &g_relays[i];
        if (x->dst != src || bufferOffset < x->dstOff || bufferOffset + len > x->dstOff + x->size) continue;
        r.data = x->src + (bufferOffset - x->dstOff);
        bc_add_job(x->cb, &r);
        g_convRelayed++;
        return;
    }
    if (++g_convUnmapped <= 10) {
        MapSlot* bind = hmap_find(&g_bufBinds, (uint64_t)src);
        MapSlot* info = hmap_find(&g_bufInfo, (uint64_t)src);
        MapSlot* type = bind ? hmap_find(&g_memTypes, bind->a) : NULL;
        MLOG("BC conversion: a copy into a converted texture (%ux%u, format %d) comes from a buffer the CPU has not mapped "
             "(usage 0x%llx, %llu KB, memory type %lld, %s) and no pending copy filled it: its blocks stay BC",
             extent.width, extent.height, fmt, info ? (unsigned long long)info->a : 0ULL, info ? (unsigned long long)(info->b >> 10) : 0ULL,
             type ? (long long)type->a : -1LL, bind ? "bound" : "not seen bound");
    }
}

// vkCmdCopyBuffer regions from a mapped buffer into an unmapped one: remembered as relays. g_cvmu held.
static void bc_note_relay(VkCommandBuffer cb, VkBuffer src, VkBuffer dst, VkDeviceSize srcOff, VkDeviceSize dstOff, VkDeviceSize size) {
    uint8_t* from = bc_buffer_ptr(src, srcOff);
    if (!from || bc_buffer_ptr(dst, dstOff)) return;
    if (g_relayCount == g_relayCap) {
        unsigned cap = g_relayCap ? g_relayCap * 2 : 256;
        BcRelay* relays = realloc(g_relays, cap * sizeof *relays);
        if (!relays) return;
        g_relays = relays;
        g_relayCap = cap;
    }
    g_relays[g_relayCount++] = (BcRelay){ cb, dst, dstOff, size, from };
}

static void bc_forget_relays(VkCommandBuffer cb) {   // g_cvmu held
    for (unsigned i = 0; i < g_relayCount;)
        if (g_relays[i].cb == cb) g_relays[i] = g_relays[--g_relayCount];
        else i++;
}

static void bc_note_copies(VkCommandBuffer cb, VkBuffer src, VkImage dst, uint32_t n, const VkBufferImageCopy* r) {
    if (!bc_convert()) return;
    pthread_mutex_lock(&g_cvmu);
    VkFormat fmt = bc_image_format(dst);
    if (fmt) for (uint32_t i = 0; i < n; i++)
        bc_note_region(cb, fmt, src, r[i].bufferOffset, r[i].bufferRowLength, r[i].bufferImageHeight, r[i].imageExtent,
                       r[i].imageSubresource.layerCount);
    pthread_mutex_unlock(&g_cvmu);
}

static void bc_note_copies2(VkCommandBuffer cb, VkBuffer src, VkImage dst, uint32_t n, const VkBufferImageCopy2* r) {
    if (!bc_convert()) return;
    pthread_mutex_lock(&g_cvmu);
    VkFormat fmt = bc_image_format(dst);
    if (fmt) for (uint32_t i = 0; i < n; i++)
        bc_note_region(cb, fmt, src, r[i].bufferOffset, r[i].bufferRowLength, r[i].bufferImageHeight, r[i].imageExtent,
                       r[i].imageSubresource.layerCount);
    pthread_mutex_unlock(&g_cvmu);
}

static void bc_note_readback(VkImage src) {
    if (!bc_convert()) return;
    pthread_mutex_lock(&g_cvmu);
    VkFormat fmt = bc_image_format(src);
    if (fmt && ++g_convReadbacks <= 10)
        MLOG("BC conversion: READBACK of a converted texture (format %d): the engine gets ETC2/EAC blocks where it expects BC", fmt);
    pthread_mutex_unlock(&g_cvmu);
}

static int region_cmp(const void* a, const void* b) {
    uintptr_t x = (uintptr_t)((const RdBcRegion*)a)->data, y = (uintptr_t)((const RdBcRegion*)b)->data;
    return x < y ? -1 : x > y;
}

// Before these command buffers go to the GPU: re-encode the blocks their copies read.
static void bc_run(const VkCommandBuffer* cbs, uint32_t n) {
    if (!n || !(__atomic_load_n(&g_jobCount, __ATOMIC_RELAXED) | __atomic_load_n(&g_relayCount, __ATOMIC_RELAXED))) return;
    pthread_mutex_lock(&g_cvmu);
    RdBcRegion* todo = malloc((size_t)g_jobCount * sizeof *todo);
    unsigned count = 0;
    for (unsigned i = 0; todo && i < g_jobCount;) {
        int mine = 0;
        for (uint32_t k = 0; k < n && !mine; k++) mine = g_jobs[i].cb == cbs[k];
        if (!mine) { i++; continue; }
        todo[count++] = g_jobs[i].r;
        g_jobs[i] = g_jobs[--g_jobCount];
    }
    for (uint32_t k = 0; k < n && g_relayCount; k++) bc_forget_relays(cbs[k]);   // their data is on its way to the GPU
    pthread_mutex_unlock(&g_cvmu);
    // The same blocks copied twice (one upload into two textures) must be re-encoded once: a second
    // pass would read ETC2 as BC.
    if (count > 1) {
        qsort(todo, count, sizeof *todo, region_cmp);
        unsigned kept = 1;
        for (unsigned i = 1; i < count; i++)
            if (todo[i].data != todo[kept - 1].data) todo[kept++] = todo[i];
        count = kept;
    }
    if (count) {
        struct timespec t0, t1;
        clock_gettime(CLOCK_MONOTONIC, &t0);
        uint64_t blocks = rd_bc_transcode(todo, count);
        clock_gettime(CLOCK_MONOTONIC, &t1);
        pthread_mutex_lock(&g_cvmu);
        g_convRegions += count;
        g_convBlocks += blocks;
        g_convNanos += (uint64_t)(t1.tv_sec - t0.tv_sec) * 1000000000ULL + (uint64_t)(t1.tv_nsec - t0.tv_nsec);
        pthread_mutex_unlock(&g_cvmu);
    }
    free(todo);
}

// A command buffer begun again, reset or freed: what it noted is gone with its old recording.
static void bc_forget(VkCommandBuffer cb) {
    if (!(__atomic_load_n(&g_jobCount, __ATOMIC_RELAXED) | __atomic_load_n(&g_relayCount, __ATOMIC_RELAXED))) return;
    pthread_mutex_lock(&g_cvmu);
    for (unsigned i = 0; i < g_jobCount;)
        if (g_jobs[i].cb == cb) g_jobs[i] = g_jobs[--g_jobCount];
        else i++;
    bc_forget_relays(cb);
    pthread_mutex_unlock(&g_cvmu);
}

static void bc_summary(void) {
    if (!g_convImageCount) return;
    pthread_mutex_lock(&g_cvmu);
    MLOG("BC conversion: %u textures created as ETC2/EAC, %u regions / %llu MB re-encoded in %llu ms (%u relayed through a "
         "GPU buffer); %u from unmapped buffers (left BC), %u readbacks, %u without an encoder", g_convImageCount, g_convRegions,
         (unsigned long long)(g_convBlocks * 16 >> 20), (unsigned long long)(g_convNanos / 1000000), g_convRelayed, g_convUnmapped,
         g_convReadbacks, g_convNoEncoder);
    pthread_mutex_unlock(&g_cvmu);
}

static PFN_vkBindBufferMemory g_bindBuf;
static PFN_vkBindBufferMemory2 g_bindBuf2;
static PFN_vkDestroyBuffer g_destroyBuf;
static PFN_vkUnmapMemory g_unmap;
static PFN_vkBeginCommandBuffer g_beginCb;
static PFN_vkResetCommandBuffer g_resetCb;

static VkResult VKAPI_CALL bindbuf_wrap(VkDevice d, VkBuffer b, VkDeviceMemory m, VkDeviceSize off) {
    VkResult r = g_bindBuf(d, b, m, off);
    if (r == VK_SUCCESS && bc_convert()) {
        pthread_mutex_lock(&g_cvmu);
        hmap_put(&g_bufBinds, (uint64_t)b, (uint64_t)m, off);
        pthread_mutex_unlock(&g_cvmu);
    }
    return r;
}

static VkResult VKAPI_CALL bindbuf2_wrap(VkDevice d, uint32_t n, const VkBindBufferMemoryInfo* infos) {
    VkResult r = g_bindBuf2(d, n, infos);
    if (r == VK_SUCCESS && bc_convert()) {
        pthread_mutex_lock(&g_cvmu);
        for (uint32_t i = 0; i < n; i++) hmap_put(&g_bufBinds, (uint64_t)infos[i].buffer, (uint64_t)infos[i].memory, infos[i].memoryOffset);
        pthread_mutex_unlock(&g_cvmu);
    }
    return r;
}

static void VKAPI_CALL destroybuf_wrap(VkDevice d, VkBuffer b, const VkAllocationCallbacks* a) {
    if (b && (g_bufBinds.live || g_bufInfo.live)) {
        pthread_mutex_lock(&g_cvmu);
        hmap_del(&g_bufBinds, (uint64_t)b);
        hmap_del(&g_bufInfo, (uint64_t)b);
        pthread_mutex_unlock(&g_cvmu);
    }
    g_destroyBuf(d, b, a);
}

// Unmapped or freed (freeing a mapped memory object unmaps it).
static void bc_forget_mapping(VkDeviceMemory m) {
    if (!g_memMaps.live && !g_memTypes.live) return;
    pthread_mutex_lock(&g_cvmu);
    hmap_del(&g_memMaps, (uint64_t)m);
    pthread_mutex_unlock(&g_cvmu);
}

static void bc_note_memory(VkDeviceMemory m, uint32_t type) {
    if (!bc_convert()) return;
    pthread_mutex_lock(&g_cvmu);
    hmap_put(&g_memTypes, (uint64_t)m, type, 0);
    pthread_mutex_unlock(&g_cvmu);
}

static void bc_note_buffer(VkBuffer b, VkBufferUsageFlags usage, VkDeviceSize size) {
    if (!bc_convert()) return;
    pthread_mutex_lock(&g_cvmu);
    hmap_put(&g_bufInfo, (uint64_t)b, usage, size);
    pthread_mutex_unlock(&g_cvmu);
}

static void VKAPI_CALL unmap_wrap(VkDevice d, VkDeviceMemory m) {
    if (bc_convert()) return;   // kept until the memory is freed (see map_wrap)
    bc_forget_mapping(m);
    g_unmap(d, m);
}

static PFN_vkCmdCopyBuffer g_copyBuf;
static PFN_vkCmdCopyBuffer2 g_copyBuf2;

static void VKAPI_CALL copybuf_wrap(VkCommandBuffer cb, VkBuffer src, VkBuffer dst, uint32_t n, const VkBufferCopy* regions) {
    if (bc_convert() && g_convImageCount) {
        pthread_mutex_lock(&g_cvmu);
        for (uint32_t i = 0; i < n; i++) bc_note_relay(cb, src, dst, regions[i].srcOffset, regions[i].dstOffset, regions[i].size);
        pthread_mutex_unlock(&g_cvmu);
    }
    g_copyBuf(cb, src, dst, n, regions);
}

static void VKAPI_CALL copybuf2_wrap(VkCommandBuffer cb, const VkCopyBufferInfo2* info) {
    if (bc_convert() && g_convImageCount) {
        pthread_mutex_lock(&g_cvmu);
        for (uint32_t i = 0; i < info->regionCount; i++)
            bc_note_relay(cb, info->srcBuffer, info->dstBuffer, info->pRegions[i].srcOffset, info->pRegions[i].dstOffset, info->pRegions[i].size);
        pthread_mutex_unlock(&g_cvmu);
    }
    g_copyBuf2(cb, info);
}

static VkResult VKAPI_CALL begincb_wrap(VkCommandBuffer cb, const VkCommandBufferBeginInfo* info) {
    bc_forget(cb);
    return g_beginCb(cb, info);
}

static VkResult VKAPI_CALL resetcb_wrap(VkCommandBuffer cb, VkCommandBufferResetFlags f) {
    bc_forget(cb);
    return g_resetCb(cb, f);
}

// ---- texture shrink --------------------------------------------------------------------------------
// The same lever the 1.6 engines have (box64's mip shim, Unity's mipmap limit): a big sampled
// block-compressed texture is created at half its size with one mip fewer, i.e. without its top
// level, 4x less memory for it. The engine keeps talking about the texture it asked for; every
// reference to a mip level is shifted by one here, and references to the dropped level 0 are left out:
//  - vkCmdCopyBufferToImage / vkCmdCopyImage / vkCmdBlitImage (and their *2 forms): a region for old
//    level n is a region for new level n-1 with the SAME offsets and extent (old level n's extent is
//    new level n-1's), regions for level 0 are dropped;
//  - image views and barriers: baseMipLevel-1, levelCount trimmed, a range of level 0 alone dropped.
// Never render targets, storage images, arrays or uncompressed images (the engine's compute encoders
// address storage images by texel; uncompressed big images are its targets). The engine uploads BC
// mip chains ready-made (BC cannot be blit-filtered), so nothing generates mips from level 0.
// Readback of a shrunk texture (vkCmdCopyImageToBuffer of level 0) would hand the engine half-size
// data under the full-size name: logged loudly, not expected with a built texture cache.
// RIMDROID_TEX_SHRINK=0 turns it off; RIMDROID_TEX_SHRINK_MIN (default 2048) is the smallest side.
static PFN_vkDestroyImage g_destroyImage;
static PFN_vkCreateImageView g_createView;
static PFN_vkCmdCopyBufferToImage g_copyB2I;
static PFN_vkCmdCopyImage g_copyImage;
static PFN_vkCmdBlitImage g_blitImage;
static PFN_vkCmdCopyImageToBuffer g_copyI2B;
static PFN_vkCmdPipelineBarrier g_barrier;
static PFN_vkCmdPipelineBarrier2 g_barrier2;
static PFN_vkCmdCopyBufferToImage2 g_copyB2I2;
static PFN_vkCmdCopyImage2 g_copyImage2;
static PFN_vkCmdBlitImage2 g_blitImage2;
static PFN_vkCmdCopyImageToBuffer2 g_copyI2B2;

static void shrink_config(void) {
    if (g_shrinkOn >= 0) return;
    const char* e = getenv("RIMDROID_TEX_SHRINK");
    g_shrinkOn = !(e && e[0] == '0');
    e = getenv("RIMDROID_TEX_SHRINK_MIN");
    if (e && atoi(e) >= 256) g_shrinkMin = (unsigned)atoi(e);
    MLOG("texture shrink %s, from %u texels on a side", g_shrinkOn ? "on" : "off", g_shrinkMin);
}

static int is_block_compressed(VkFormat f) {
    return (f >= VK_FORMAT_BC1_RGB_UNORM_BLOCK && f <= VK_FORMAT_BC7_SRGB_BLOCK)
        || (f >= VK_FORMAT_ETC2_R8G8B8_UNORM_BLOCK && f <= VK_FORMAT_ASTC_12x12_SRGB_BLOCK);
}

static unsigned shrunk_slot(VkImage img) {
    return (unsigned)(((uint64_t)(uintptr_t)img * 0x9E3779B97F4A7C15ULL) >> 48) & (SHRUNK - 1);
}

// Index of a shrunk image, or -1. g_smu held.
static int shrunk_index(VkImage img) {
    if (img == VK_NULL_HANDLE || img == SHRUNK_TOMB) return -1;
    for (unsigned i = shrunk_slot(img), n = 0; n < SHRUNK; i = (i + 1) & (SHRUNK - 1), n++) {
        if (g_shrunk[i].image == VK_NULL_HANDLE) return -1;
        if (g_shrunk[i].image == img) return (int)i;
    }
    return -1;
}

// g_smu held and a slot reserved (g_shrunkLive + g_shrunkReserved < SHRUNK_LIMIT), so one is free.
static void shrunk_insert(VkImage img, uint32_t origMips, uint32_t newMips) {
    for (unsigned i = shrunk_slot(img); ; i = (i + 1) & (SHRUNK - 1)) {
        if (g_shrunk[i].image != VK_NULL_HANDLE && g_shrunk[i].image != SHRUNK_TOMB) continue;
        if (g_shrunk[i].image == SHRUNK_TOMB) g_shrunkTombs--;
        g_shrunk[i].image = img;
        g_shrunk[i].origMips = origMips;
        g_shrunk[i].newMips = newMips;
        g_shrunkLive++;
        return;
    }
}

// g_smu held. Removed entries leave tombstones; once they are a quarter of the table, it is rebuilt
// so that lookups keep finding empty slots early.
static void shrunk_remove(int index) {
    g_shrunk[index].image = SHRUNK_TOMB;
    g_shrunkLive--;
    if (++g_shrunkTombs < SHRUNK / 4) return;
    memcpy(g_shrunkSpare, g_shrunk, sizeof g_shrunk);
    memset(g_shrunk, 0, sizeof g_shrunk);
    g_shrunkLive = 0;
    g_shrunkTombs = 0;
    for (unsigned i = 0; i < SHRUNK; i++)
        if (g_shrunkSpare[i].image != VK_NULL_HANDLE && g_shrunkSpare[i].image != SHRUNK_TOMB)
            shrunk_insert(g_shrunkSpare[i].image, g_shrunkSpare[i].origMips, g_shrunkSpare[i].newMips);
}

// Old level -> new level; 0 means "dropped". Returns 0 if the whole range is the dropped level.
static int shift_range(const ShrunkImage* e, VkImageSubresourceRange* r) {
    if (r->baseMipLevel == 0 && r->levelCount == 1) return 0;
    if (r->baseMipLevel > 0) r->baseMipLevel--;
    else if (r->levelCount != VK_REMAINING_MIP_LEVELS) r->levelCount--;
    if (r->levelCount != VK_REMAINING_MIP_LEVELS && r->baseMipLevel + r->levelCount > e->newMips)
        r->levelCount = e->newMips > r->baseMipLevel ? e->newMips - r->baseMipLevel : 1;
    return 1;
}

static int shrinkable(const VkImageCreateInfo* info) {
    return g_shrinkOn && info->imageType == VK_IMAGE_TYPE_2D && info->tiling == VK_IMAGE_TILING_OPTIMAL
        && info->mipLevels >= 2 && info->arrayLayers == 1 && info->samples == VK_SAMPLE_COUNT_1_BIT
        && is_block_compressed(info->format)
        && !(info->usage & (VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT
                            | VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_TRANSIENT_ATTACHMENT_BIT))
        && !(info->flags & (VK_IMAGE_CREATE_SPARSE_BINDING_BIT | VK_IMAGE_CREATE_SPARSE_RESIDENCY_BIT))
        && (info->extent.width >= g_shrinkMin || info->extent.height >= g_shrinkMin);
}

// Images the driver might back itself (lazily allocated, sparse) or that are simply huge: logged
// from the create call, before any memory is bound. Big block-compressed textures: see texture shrink.
static const char* imageRoleName(uint32_t usage) {
    if (usage & VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT) return "depth target";
    if (usage & VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT) return "color target";
    return "storage image";
}

static VkResult VKAPI_CALL createimage_wrap(VkDevice d, const VkImageCreateInfo* info, const VkAllocationCallbacks* a, VkImage* out) {
    shrink_config();
    VkImageCreateInfo copy = *info;
    VkFormat sub = is_bc(info->format) && bc_convert() ? bc_substitute(info->format) : VK_FORMAT_UNDEFINED;
    if (is_bc(info->format) && bc_convert() && !sub && __atomic_add_fetch(&g_convNoEncoder, 1, __ATOMIC_RELAXED) <= 10)
        MLOG("BC conversion: no encoder for format %d (%ux%u): created as asked", info->format, info->extent.width, info->extent.height);
    if (sub) {
        copy.format = sub;
        // A mutable image's list of view formats: its BC entries become their substitutes too (the
        // caller's own struct; the driver only reads it).
        for (VkBaseOutStructure* x = (VkBaseOutStructure*)info->pNext; x; x = x->pNext) {
            if (x->sType != VK_STRUCTURE_TYPE_IMAGE_FORMAT_LIST_CREATE_INFO) continue;
            VkImageFormatListCreateInfo* list = (VkImageFormatListCreateInfo*)x;
            for (uint32_t k = 0; k < list->viewFormatCount; k++) {
                VkFormat v = bc_substitute(list->pViewFormats[k]);
                if (v) ((VkFormat*)list->pViewFormats)[k] = v;
            }
        }
    }
    int shrink = shrinkable(info);
    if (shrink) {
        pthread_mutex_lock(&g_smu);
        if (g_shrunkLive + g_shrunkReserved < SHRUNK_LIMIT) g_shrunkReserved++;
        else shrink = 0;                                  // no room to track it: created as asked
        pthread_mutex_unlock(&g_smu);
    }
    if (shrink) {
        copy.extent.width = info->extent.width > 1 ? info->extent.width / 2 : 1;
        copy.extent.height = info->extent.height > 1 ? info->extent.height / 2 : 1;
        copy.mipLevels = info->mipLevels - 1;
    }
    VkResult r = g_createImage(d, shrink || sub ? &copy : info, a, out);
    if (sub && r == VK_SUCCESS) bc_track_image(*out, info->format);
    if (sub) {
        uint64_t key = 0x3000000000ULL ^ ((uint64_t)info->format << 40) ^ ((uint64_t)info->imageType << 36)
                       ^ ((uint64_t)(info->arrayLayers > 1) << 35) ^ ((uint64_t)(info->mipLevels > 1) << 34)
                       ^ ((uint64_t)info->usage << 16) ^ info->flags;
        if (r != VK_SUCCESS || bcdiag_first(key))
            BCDIAG("BC image format %d as %d: %ux%u x%u layers, %u mips, type %d, usage 0x%x, flags 0x%x%s: result %d",
                   info->format, sub, info->extent.width, info->extent.height, info->arrayLayers, info->mipLevels,
                   info->imageType, info->usage, info->flags, shrink ? " (shrunk)" : "", r);
    }
    if (shrink) {
        pthread_mutex_lock(&g_smu);
        g_shrunkReserved--;
        if (r == VK_SUCCESS) {
            shrunk_insert(*out, info->mipLevels, copy.mipLevels);
            g_shrunkUsed = 1;
            g_shrinkCount++;
            g_shrinkSavedApprox += (VkDeviceSize)info->extent.width * info->extent.height * 3 / 4;   // BC: ~1 B/texel, top level = 3/4 of the chain
            if (g_shrinkCount <= 40)
                MLOG("texture shrunk: %ux%u %u mips -> %ux%u %u mips, format %d (#%u, ~%u MB saved so far)",
                     info->extent.width, info->extent.height, info->mipLevels, copy.extent.width, copy.extent.height,
                     copy.mipLevels, info->format, g_shrinkCount, mb(g_shrinkSavedApprox));
        }
        pthread_mutex_unlock(&g_smu);
    }
    if (r != VK_SUCCESS) return r;
    pthread_mutex_lock(&g_mu);
    g_images++;
    uint64_t texels = (uint64_t)info->extent.width * info->extent.height * info->extent.depth * info->arrayLayers;
    int compressed = info->format >= VK_FORMAT_BC1_RGB_UNORM_BLOCK && info->format <= VK_FORMAT_BC7_SRGB_BLOCK;
    uint64_t approx = compressed ? texels : texels * 4;   // bytes for level 0: 1 B/texel BC, 4 B/texel otherwise
    int big = approx >= (48u << 20) || (info->arrayLayers > 1 && approx >= (8u << 20));
    if (info->usage & (VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT | VK_IMAGE_USAGE_STORAGE_BIT)) {
        ring_add("%s %ux%u x%u, %u mips, format %d, usage 0x%x (%llx)", imageRoleName(info->usage), info->extent.width, info->extent.height,
                 info->arrayLayers, info->mipLevels, info->format, info->usage, (unsigned long long)*out);
        target_remember(*out, info->extent.width, info->extent.height);
    }
    else if (approx >= (16u << 20))
        ring_add("texture %ux%u, %u mips, format %d", info->extent.width, info->extent.height, info->mipLevels, info->format);
    if (big || (info->flags & VK_IMAGE_CREATE_SPARSE_BINDING_BIT))
        MLOG("image #%u: %ux%ux%u x%u layers, %u mips, format %d, usage 0x%x, flags 0x%x, tiling %d, ~%u MB level 0",
             g_images, info->extent.width, info->extent.height, info->extent.depth, info->arrayLayers, info->mipLevels,
             info->format, info->usage, info->flags, info->tiling, mb(approx));
    pthread_mutex_unlock(&g_mu);
    return r;
}

#define TARGETS 256
static struct { VkImage image; uint32_t w, h; } g_targets[TARGETS];
static pthread_mutex_t g_tmu = PTHREAD_MUTEX_INITIALIZER;

static void target_remember(VkImage img, uint32_t w, uint32_t h) {
    pthread_mutex_lock(&g_tmu);
    for (int i = 0; i < TARGETS; i++) if (!g_targets[i].image) { g_targets[i].image = img; g_targets[i].w = w; g_targets[i].h = h; break; }
    pthread_mutex_unlock(&g_tmu);
}

static void VKAPI_CALL destroyimage_wrap(VkDevice d, VkImage img, const VkAllocationCallbacks* a) {
    if (img) {
        pthread_mutex_lock(&g_tmu);
        for (int i = 0; i < TARGETS; i++) if (g_targets[i].image == img) {
            ring_add("destroy target %ux%u (%llx)", g_targets[i].w, g_targets[i].h, (unsigned long long)img);
            g_targets[i].image = VK_NULL_HANDLE;
            break;
        }
        pthread_mutex_unlock(&g_tmu);
    }
    if (g_shrunkUsed) {
        pthread_mutex_lock(&g_smu);
        int i = shrunk_index(img);
        if (i >= 0) shrunk_remove(i);
        pthread_mutex_unlock(&g_smu);
    }
    if (img && g_convImages.live) {
        pthread_mutex_lock(&g_cvmu);
        hmap_del(&g_convImages, (uint64_t)img);
        pthread_mutex_unlock(&g_cvmu);
    }
    g_destroyImage(d, img, a);
}

static VkResult VKAPI_CALL createview_wrap(VkDevice d, const VkImageViewCreateInfo* info, const VkAllocationCallbacks* a, VkImageView* out) {
    VkImageViewCreateInfo copy = *info;
    VkFormat sub = is_bc(info->format) && bc_convert() ? bc_substitute(info->format) : VK_FORMAT_UNDEFINED;
    if (sub) copy.format = sub;
    if (g_shrunkUsed) {
        pthread_mutex_lock(&g_smu);
        int i = shrunk_index(info->image);
        if (i >= 0) {
            VkImageSubresourceRange* r = &copy.subresourceRange;
            if (r->baseMipLevel > 0) r->baseMipLevel--;
            else if (r->levelCount != VK_REMAINING_MIP_LEVELS && r->levelCount > 1) r->levelCount--;   // a view of level 0 alone stays one level: the new level 0
            if (r->levelCount != VK_REMAINING_MIP_LEVELS && r->baseMipLevel + r->levelCount > g_shrunk[i].newMips)
                r->levelCount = g_shrunk[i].newMips - r->baseMipLevel;
            pthread_mutex_unlock(&g_smu);
            VkResult vr = g_createView(d, &copy, a, out);
            if (sub && vr != VK_SUCCESS)
                BCDIAG("BC view format %d as %d, type %d (shrunk image): result %d", info->format, sub, info->viewType, vr);
            return vr;
        }
        pthread_mutex_unlock(&g_smu);
    }
    VkResult r = g_createView(d, sub ? &copy : info, a, out);
    if (sub && (r != VK_SUCCESS || bcdiag_first(0x4000000000ULL ^ ((uint64_t)info->format << 8) ^ info->viewType)))
        BCDIAG("BC view format %d as %d, type %d, levels %u+%u, layers %u+%u: result %d", info->format, sub, info->viewType,
               info->subresourceRange.baseMipLevel, info->subresourceRange.levelCount,
               info->subresourceRange.baseArrayLayer, info->subresourceRange.layerCount, r);
    return r;
}

// Copies: regions of old level n -> new level n-1, same offsets and extent; level 0 regions dropped.
static void VKAPI_CALL copyb2i_wrap(VkCommandBuffer cb, VkBuffer src, VkImage dst, VkImageLayout layout, uint32_t n, const VkBufferImageCopy* regions) {
    if (g_shrunkUsed && n > 0) {
        pthread_mutex_lock(&g_smu);
        int i = shrunk_index(dst);
        pthread_mutex_unlock(&g_smu);
        if (i >= 0) {
            VkBufferImageCopy stackKept[32];
            VkBufferImageCopy* kept = n <= 32 ? stackKept : (VkBufferImageCopy*)malloc(n * sizeof(VkBufferImageCopy));
            if (!kept) return;   /* out of memory: the copy is lost, never passed unshifted */
            uint32_t k = 0;
            for (uint32_t j = 0; j < n; j++) {
                if (regions[j].imageSubresource.mipLevel == 0) continue;
                kept[k] = regions[j];
                kept[k].imageSubresource.mipLevel--;
                k++;
            }
            if (k) { bc_note_copies(cb, src, dst, k, kept); g_copyB2I(cb, src, dst, layout, k, kept); }
            if (kept != stackKept) free(kept);
            return;
        }
    }
    bc_note_copies(cb, src, dst, n, regions);
    g_copyB2I(cb, src, dst, layout, n, regions);
}

static void VKAPI_CALL copyimage_wrap(VkCommandBuffer cb, VkImage src, VkImageLayout sl, VkImage dst, VkImageLayout dl, uint32_t n, const VkImageCopy* regions) {
    if (g_shrunkUsed && n > 0) {
        pthread_mutex_lock(&g_smu);
        int si = shrunk_index(src), di = shrunk_index(dst);
        pthread_mutex_unlock(&g_smu);
        if (si >= 0 || di >= 0) {
            VkImageCopy stackKept[32];
            VkImageCopy* kept = n <= 32 ? stackKept : (VkImageCopy*)malloc(n * sizeof(VkImageCopy));
            if (!kept) return;   /* out of memory: the copy is lost, never passed unshifted */
            uint32_t k = 0;
            for (uint32_t j = 0; j < n; j++) {
                if ((si >= 0 && regions[j].srcSubresource.mipLevel == 0) || (di >= 0 && regions[j].dstSubresource.mipLevel == 0)) continue;
                kept[k] = regions[j];
                if (si >= 0) kept[k].srcSubresource.mipLevel--;
                if (di >= 0) kept[k].dstSubresource.mipLevel--;
                k++;
            }
            if (k) g_copyImage(cb, src, sl, dst, dl, k, kept);
            if (kept != stackKept) free(kept);
            return;
        }
    }
    g_copyImage(cb, src, sl, dst, dl, n, regions);
}

static void VKAPI_CALL blitimage_wrap(VkCommandBuffer cb, VkImage src, VkImageLayout sl, VkImage dst, VkImageLayout dl, uint32_t n, const VkImageBlit* regions, VkFilter f) {
    if (g_shrunkUsed && n > 0) {
        pthread_mutex_lock(&g_smu);
        int si = shrunk_index(src), di = shrunk_index(dst);
        pthread_mutex_unlock(&g_smu);
        if (si >= 0 || di >= 0) {
            VkImageBlit stackKept[32];
            VkImageBlit* kept = n <= 32 ? stackKept : (VkImageBlit*)malloc(n * sizeof(VkImageBlit));
            if (!kept) return;   /* out of memory: the copy is lost, never passed unshifted */
            uint32_t k = 0;
            for (uint32_t j = 0; j < n; j++) {
                if ((si >= 0 && regions[j].srcSubresource.mipLevel == 0) || (di >= 0 && regions[j].dstSubresource.mipLevel == 0)) continue;
                kept[k] = regions[j];
                if (si >= 0) kept[k].srcSubresource.mipLevel--;
                if (di >= 0) kept[k].dstSubresource.mipLevel--;
                k++;
            }
            if (k) g_blitImage(cb, src, sl, dst, dl, k, kept, f);
            if (kept != stackKept) free(kept);
            return;
        }
    }
    g_blitImage(cb, src, sl, dst, dl, n, regions, f);
}

static void VKAPI_CALL copyi2b_wrap(VkCommandBuffer cb, VkImage src, VkImageLayout sl, VkBuffer dst, uint32_t n, const VkBufferImageCopy* regions) {
    bc_note_readback(src);
    if (g_shrunkUsed && n > 0) {
        pthread_mutex_lock(&g_smu);
        int i = shrunk_index(src);
        pthread_mutex_unlock(&g_smu);
        if (i >= 0) {
            VkBufferImageCopy stackKept[32];
            VkBufferImageCopy* kept = n <= 32 ? stackKept : (VkBufferImageCopy*)malloc(n * sizeof(VkBufferImageCopy));
            if (!kept) return;   /* out of memory: the copy is lost, never passed unshifted */
            uint32_t k = 0, dropped = 0;
            for (uint32_t j = 0; j < n; j++) {
                if (regions[j].imageSubresource.mipLevel == 0) { dropped++; continue; }
                kept[k] = regions[j];
                kept[k].imageSubresource.mipLevel--;
                k++;
            }
            if (dropped && g_shrinkReadbacks++ < 20)
                MLOG("READBACK of a shrunk texture: %u region(s) of its dropped level 0 not copied (the engine gets stale data there)", dropped);
            if (k) g_copyI2B(cb, src, sl, dst, k, kept);
            if (kept != stackKept) free(kept);
            return;
        }
    }
    g_copyI2B(cb, src, sl, dst, n, regions);
}

static void VKAPI_CALL barrier_wrap(VkCommandBuffer cb, VkPipelineStageFlags srcStage, VkPipelineStageFlags dstStage, VkDependencyFlags dep,
                                    uint32_t nm, const VkMemoryBarrier* mem, uint32_t nb, const VkBufferMemoryBarrier* buf,
                                    uint32_t ni, const VkImageMemoryBarrier* img) {
    if (g_shrunkUsed && ni > 0) {
        VkImageMemoryBarrier stackKept[16];
        VkImageMemoryBarrier* kept = ni <= 16 ? stackKept : (VkImageMemoryBarrier*)malloc(ni * sizeof(VkImageMemoryBarrier));
        if (!kept) return;
        uint32_t k = 0;
        int touched = 0;
        pthread_mutex_lock(&g_smu);
        for (uint32_t j = 0; j < ni; j++) {
            int i = shrunk_index(img[j].image);
            kept[k] = img[j];
            if (i >= 0) {
                touched = 1;
                if (!shift_range(&g_shrunk[i], &kept[k].subresourceRange)) continue;
            }
            k++;
        }
        pthread_mutex_unlock(&g_smu);
        if (touched) {
            if (k || nm || nb) g_barrier(cb, srcStage, dstStage, dep, nm, mem, nb, buf, k, kept);
            if (kept != stackKept) free(kept);
            return;
        }
        if (kept != stackKept) free(kept);
    }
    g_barrier(cb, srcStage, dstStage, dep, nm, mem, nb, buf, ni, img);
}

static void VKAPI_CALL barrier2_wrap(VkCommandBuffer cb, const VkDependencyInfo* info) {
    uint32_t ni = info->imageMemoryBarrierCount;
    if (g_shrunkUsed && ni > 0) {
        VkImageMemoryBarrier2 stackKept[16];
        VkImageMemoryBarrier2* kept = ni <= 16 ? stackKept : (VkImageMemoryBarrier2*)malloc(ni * sizeof(VkImageMemoryBarrier2));
        if (!kept) return;
        uint32_t k = 0;
        int touched = 0;
        pthread_mutex_lock(&g_smu);
        for (uint32_t j = 0; j < ni; j++) {
            int i = shrunk_index(info->pImageMemoryBarriers[j].image);
            kept[k] = info->pImageMemoryBarriers[j];
            if (i >= 0) {
                touched = 1;
                if (!shift_range(&g_shrunk[i], &kept[k].subresourceRange)) continue;
            }
            k++;
        }
        pthread_mutex_unlock(&g_smu);
        if (touched) {
            VkDependencyInfo copy = *info;
            copy.imageMemoryBarrierCount = k;
            copy.pImageMemoryBarriers = kept;
            if (k || copy.memoryBarrierCount || copy.bufferMemoryBarrierCount) g_barrier2(cb, &copy);
            if (kept != stackKept) free(kept);
            return;
        }
        if (kept != stackKept) free(kept);
    }
    g_barrier2(cb, info);
}

static void VKAPI_CALL copyb2i2_wrap(VkCommandBuffer cb, const VkCopyBufferToImageInfo2* info) {
    uint32_t n = info->regionCount;
    if (g_shrunkUsed && n > 0) {
        pthread_mutex_lock(&g_smu);
        int i = shrunk_index(info->dstImage);
        pthread_mutex_unlock(&g_smu);
        if (i >= 0) {
            VkBufferImageCopy2 stackKept[32];
            VkBufferImageCopy2* kept = n <= 32 ? stackKept : (VkBufferImageCopy2*)malloc(n * sizeof(VkBufferImageCopy2));
            if (!kept) return;   /* out of memory: the copy is lost, never passed unshifted */
            uint32_t k = 0;
            for (uint32_t j = 0; j < n; j++) {
                if (info->pRegions[j].imageSubresource.mipLevel == 0) continue;
                kept[k] = info->pRegions[j];
                kept[k].imageSubresource.mipLevel--;
                k++;
            }
            if (k) {
                bc_note_copies2(cb, info->srcBuffer, info->dstImage, k, kept);
                VkCopyBufferToImageInfo2 copy = *info; copy.regionCount = k; copy.pRegions = kept; g_copyB2I2(cb, &copy);
            }
            if (kept != stackKept) free(kept);
            return;
        }
    }
    bc_note_copies2(cb, info->srcBuffer, info->dstImage, info->regionCount, info->pRegions);
    g_copyB2I2(cb, info);
}

static void VKAPI_CALL copyimage2_wrap(VkCommandBuffer cb, const VkCopyImageInfo2* info) {
    uint32_t n = info->regionCount;
    if (g_shrunkUsed && n > 0) {
        pthread_mutex_lock(&g_smu);
        int si = shrunk_index(info->srcImage), di = shrunk_index(info->dstImage);
        pthread_mutex_unlock(&g_smu);
        if (si >= 0 || di >= 0) {
            VkImageCopy2 stackKept[32];
            VkImageCopy2* kept = n <= 32 ? stackKept : (VkImageCopy2*)malloc(n * sizeof(VkImageCopy2));
            if (!kept) return;   /* out of memory: the copy is lost, never passed unshifted */
            uint32_t k = 0;
            for (uint32_t j = 0; j < n; j++) {
                const VkImageCopy2* r = &info->pRegions[j];
                if ((si >= 0 && r->srcSubresource.mipLevel == 0) || (di >= 0 && r->dstSubresource.mipLevel == 0)) continue;
                kept[k] = *r;
                if (si >= 0) kept[k].srcSubresource.mipLevel--;
                if (di >= 0) kept[k].dstSubresource.mipLevel--;
                k++;
            }
            if (k) { VkCopyImageInfo2 copy = *info; copy.regionCount = k; copy.pRegions = kept; g_copyImage2(cb, &copy); }
            if (kept != stackKept) free(kept);
            return;
        }
    }
    g_copyImage2(cb, info);
}

static void VKAPI_CALL blitimage2_wrap(VkCommandBuffer cb, const VkBlitImageInfo2* info) {
    uint32_t n = info->regionCount;
    if (g_shrunkUsed && n > 0) {
        pthread_mutex_lock(&g_smu);
        int si = shrunk_index(info->srcImage), di = shrunk_index(info->dstImage);
        pthread_mutex_unlock(&g_smu);
        if (si >= 0 || di >= 0) {
            VkImageBlit2 stackKept[32];
            VkImageBlit2* kept = n <= 32 ? stackKept : (VkImageBlit2*)malloc(n * sizeof(VkImageBlit2));
            if (!kept) return;   /* out of memory: the copy is lost, never passed unshifted */
            uint32_t k = 0;
            for (uint32_t j = 0; j < n; j++) {
                const VkImageBlit2* r = &info->pRegions[j];
                if ((si >= 0 && r->srcSubresource.mipLevel == 0) || (di >= 0 && r->dstSubresource.mipLevel == 0)) continue;
                kept[k] = *r;
                if (si >= 0) kept[k].srcSubresource.mipLevel--;
                if (di >= 0) kept[k].dstSubresource.mipLevel--;
                k++;
            }
            if (k) { VkBlitImageInfo2 copy = *info; copy.regionCount = k; copy.pRegions = kept; g_blitImage2(cb, &copy); }
            if (kept != stackKept) free(kept);
            return;
        }
    }
    g_blitImage2(cb, info);
}

static void VKAPI_CALL copyi2b2_wrap(VkCommandBuffer cb, const VkCopyImageToBufferInfo2* info) {
    bc_note_readback(info->srcImage);
    uint32_t n = info->regionCount;
    if (g_shrunkUsed && n > 0) {
        pthread_mutex_lock(&g_smu);
        int i = shrunk_index(info->srcImage);
        pthread_mutex_unlock(&g_smu);
        if (i >= 0) {
            VkBufferImageCopy2 stackKept[32];
            VkBufferImageCopy2* kept = n <= 32 ? stackKept : (VkBufferImageCopy2*)malloc(n * sizeof(VkBufferImageCopy2));
            if (!kept) return;   /* out of memory: the copy is lost, never passed unshifted */
            uint32_t k = 0, dropped = 0;
            for (uint32_t j = 0; j < n; j++) {
                if (info->pRegions[j].imageSubresource.mipLevel == 0) { dropped++; continue; }
                kept[k] = info->pRegions[j];
                kept[k].imageSubresource.mipLevel--;
                k++;
            }
            if (dropped && g_shrinkReadbacks++ < 20)
                MLOG("READBACK of a shrunk texture: %u region(s) of its dropped level 0 not copied (the engine gets stale data there)", dropped);
            if (k) { VkCopyImageToBufferInfo2 copy = *info; copy.regionCount = k; copy.pRegions = kept; g_copyI2B2(cb, &copy); }
            if (kept != stackKept) free(kept);
            return;
        }
    }
    g_copyI2B2(cb, info);
}

// ---- device-lost diagnosis ------------------------------------------------------------------------
// CameraPlus on 1.7 loses the device (VK_ERROR_DEVICE_LOST at vkQueueSubmit) in the first frame after a
// save loads. The fault may be in work submitted earlier than the submit that reports it, so the last
// notable things the engine did are kept in a ring: render targets and storage images created (any
// size), big buffers, framebuffers, oversized dispatches and draws, and every submit's command buffer
// count. On a failed submit, wait or device-wait-idle the ring is printed (RimDroid/vkmem).
#define RING 48
static PFN_vkQueueSubmit g_submit;
static PFN_vkQueueSubmit2 g_submit2;
static PFN_vkWaitForFences g_waitFences;
static PFN_vkDeviceWaitIdle g_waitIdle;
static PFN_vkCreateBuffer g_createBuffer;
static PFN_vkCreateFramebuffer g_createFb;
static PFN_vkCmdDispatch g_dispatch;
static PFN_vkCmdDraw g_draw;
static PFN_vkCmdDrawIndexed g_drawIndexed;
static PFN_vkCmdDrawIndexedIndirect g_drawIndexedIndirect;
static PFN_vkCmdDrawIndirect g_drawIndirect;
static pthread_mutex_t g_rmu = PTHREAD_MUTEX_INITIALIZER;
static char g_ring[RING][200];
static unsigned g_ringNext, g_submits, g_draws, g_dispatches, g_lostDumped;

static void ring_add(const char* fmt, ...) __attribute__((format(printf, 1, 2)));
static void ring_add(const char* fmt, ...) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    pthread_mutex_lock(&g_rmu);
    char* line = g_ring[g_ringNext % RING];
    int n = snprintf(line, 200, "[%lu.%03lu s%u d%u] ", (unsigned long)ts.tv_sec, (unsigned long)(ts.tv_nsec / 1000000), g_submits, g_draws);
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(line + n, 200 - n, fmt, ap);
    va_end(ap);
    g_ringNext++;
    pthread_mutex_unlock(&g_rmu);
}

static void ring_dump(const char* why) {
    pthread_mutex_lock(&g_rmu);
    if (g_lostDumped++ < 3) {
        MLOG("=== %s: last %u events (submits %u, draws %u, dispatches %u) ===", why, g_ringNext < RING ? g_ringNext : RING, g_submits, g_draws, g_dispatches);
        unsigned from = g_ringNext > RING ? g_ringNext - RING : 0;
        for (unsigned i = from; i < g_ringNext; i++) MLOG("  %s", g_ring[i % RING]);
    }
    pthread_mutex_unlock(&g_rmu);
}

static VkResult VKAPI_CALL submit_wrap(VkQueue q, uint32_t n, const VkSubmitInfo* infos, VkFence fence) {
    __atomic_add_fetch(&g_submits, 1, __ATOMIC_RELAXED);
    for (uint32_t i = 0; i < n; i++) bc_run(infos[i].pCommandBuffers, infos[i].commandBufferCount);
    VkResult r = g_submit(q, n, infos, fence);
    if (r != VK_SUCCESS) {
        uint32_t cbs = 0;
        for (uint32_t i = 0; i < n; i++) cbs += infos[i].commandBufferCount;
        char why[96];
        snprintf(why, sizeof why, "vkQueueSubmit -> %d (%u submits, %u command buffers)", r, n, cbs);
        ring_dump(why);
    }
    return r;
}

static VkResult VKAPI_CALL submit2_wrap(VkQueue q, uint32_t n, const VkSubmitInfo2* infos, VkFence fence) {
    __atomic_add_fetch(&g_submits, 1, __ATOMIC_RELAXED);
    for (uint32_t i = 0; i < n; i++)
        for (uint32_t k = 0; k < infos[i].commandBufferInfoCount; k++) bc_run(&infos[i].pCommandBufferInfos[k].commandBuffer, 1);
    VkResult r = g_submit2(q, n, infos, fence);
    if (r != VK_SUCCESS) {
        uint32_t cbs = 0;
        for (uint32_t i = 0; i < n; i++) cbs += infos[i].commandBufferInfoCount;
        char why[96];
        snprintf(why, sizeof why, "vkQueueSubmit2 -> %d (%u submits, %u command buffers)", r, n, cbs);
        ring_dump(why);
    }
    return r;
}

static VkResult VKAPI_CALL waitfences_wrap(VkDevice d, uint32_t n, const VkFence* f, VkBool32 all, uint64_t timeout) {
    VkResult r = g_waitFences(d, n, f, all, timeout);
    if (timeout) ring_add("waited for %u fence(s) -> %d", n, r);
    if (r != VK_SUCCESS && r != VK_TIMEOUT) { char why[64]; snprintf(why, sizeof why, "vkWaitForFences -> %d", r); ring_dump(why); }
    return r;
}

static VkResult VKAPI_CALL waitidle_wrap(VkDevice d) {
    VkResult r = g_waitIdle(d);
    if (r != VK_SUCCESS) { char why[64]; snprintf(why, sizeof why, "vkDeviceWaitIdle -> %d", r); ring_dump(why); }
    return r;
}

static VkResult VKAPI_CALL createbuffer_wrap(VkDevice d, const VkBufferCreateInfo* info, const VkAllocationCallbacks* a, VkBuffer* out) {
    VkResult r = g_createBuffer(d, info, a, out);
    if (r == VK_SUCCESS) bc_note_buffer(*out, info->usage, info->size);
    if (info->size >= (32u << 20)) ring_add("buffer %u MB usage 0x%x -> %d", mb(info->size), (unsigned)info->usage, r);
    return r;
}

static VkResult VKAPI_CALL createfb_wrap(VkDevice d, const VkFramebufferCreateInfo* info, const VkAllocationCallbacks* a, VkFramebuffer* out) {
    VkResult r = g_createFb(d, info, a, out);
    ring_add("framebuffer %ux%u x%u, %u attachments -> %d", info->width, info->height, info->layers, info->attachmentCount, r);
    {
        char buf[1500]; int m = 0;
        pd_app(buf, &m, "framebuffer %llx -> %d: pass %llx %ux%u, views", (unsigned long long)*out, r, (unsigned long long)info->renderPass, info->width, info->height);
        for (uint32_t k = 0; k < info->attachmentCount && info->pAttachments; k++) pd_app(buf, &m, " %llx", (unsigned long long)info->pAttachments[k]);
        PDIAG("%s", buf);
    }
    return r;
}

static void VKAPI_CALL dispatch_wrap(VkCommandBuffer cb, uint32_t x, uint32_t y, uint32_t z) {
    __atomic_add_fetch(&g_dispatches, 1, __ATOMIC_RELAXED);
    if ((uint64_t)x * y * z > (1u << 20)) ring_add("dispatch %ux%ux%u groups", x, y, z);
    g_dispatch(cb, x, y, z);
}

static void VKAPI_CALL draw_wrap(VkCommandBuffer cb, uint32_t vc, uint32_t ic, uint32_t fv, uint32_t fi) {
    __atomic_add_fetch(&g_draws, 1, __ATOMIC_RELAXED);
    if (vc > (16u << 20) || ic > 200000) ring_add("draw %u vertices x %u instances", vc, ic);
    g_draw(cb, vc, ic, fv, fi);
}


// ---- pipeline diagnosis (2026-10-17) ----------------------------------------------------------------
// The Architect icons of masked things (beds, bedrolls, braziers...) come out empty on Mali while the
// same draws are fine on Adreno: RimWorld 1.7's ImGui backend draws them with a pipeline of its own
// (the material's UI fragment shader over ImGui's vertices, plus a stride-0 binding of attribute
// defaults). Logged so the two phones can be compared: every shader module (stage, hash, the code
// dumped to $HOME/vk_spv/<hash>.spv), every descriptor set and pipeline layout, every graphics
// pipeline's vertex input, raster, depth, blend and dynamic state, and the commands of the first draws
// made with a pipeline that has a stride-0 vertex binding. RimDroid/vkpipe and $HOME/vk_pipe.log.
static FILE* g_pdiagFile;
static pthread_mutex_t g_pdiagMu = PTHREAD_MUTEX_INITIALIZER;
static VkPipeline g_tracePipes[8];
static VkPipelineLayout g_uiMatLayouts[16];   // 3 sets + 16-byte push: the ImGui material pipelines'
// The draw-command trace and the per-frame lines were for the icon hunt; off for releases (set the
// budgets again to bring them back). Layouts, pipelines, samplers and shader hashes stay logged.
static int g_tracing, g_traceBudget = 0, g_viewportLogs = 0, g_beginLogs = 0, g_uboLogs = 0;
static unsigned g_imgLayoutSeen[16];          // image layouts seen in sampler descriptor writes

__attribute__((format(printf, 1, 2)))
static void PDIAG(const char* fmt, ...) {
    char line[1600];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(line, sizeof line, fmt, ap);
    va_end(ap);
    __android_log_print(ANDROID_LOG_INFO, "RimDroid/vkpipe", "%s", line);
    pthread_mutex_lock(&g_pdiagMu);
    if (!g_pdiagFile) {
        const char* home = getenv("HOME");
        char path[512];
        snprintf(path, sizeof path, "%s/vk_pipe.log", home ? home : ".");
        g_pdiagFile = fopen(path, "w");
    }
    if (g_pdiagFile) { fprintf(g_pdiagFile, "%s\n", line); fflush(g_pdiagFile); }
    pthread_mutex_unlock(&g_pdiagMu);
}

/* Appends to a 1500-byte buffer; nothing once it is full. */
__attribute__((format(printf, 3, 4)))
static void pd_app(char* buf, int* m, const char* fmt, ...) {
    if (*m < 0 || *m >= 1500 - 1) return;
    va_list ap;
    va_start(ap, fmt);
    int k = vsnprintf(buf + *m, (size_t)(1500 - *m), fmt, ap);
    va_end(ap);
    *m = k < 0 ? 1500 : *m + k;
}

static void pdiag_dump_spv(uint32_t hash, const void* code, size_t size) {
    const char* home = getenv("HOME");
    char dir[512], path[600];
    snprintf(dir, sizeof dir, "%s/vk_spv", home ? home : ".");
    mkdir(dir, 0700);
    snprintf(path, sizeof path, "%s/%08x.spv", dir, hash);
    if (access(path, F_OK) == 0) return;
    FILE* f = fopen(path, "wb");
    if (f) { fwrite(code, 1, size, f); fclose(f); }
}

static PFN_vkCreateDescriptorSetLayout g_createDsl;
static PFN_vkCreatePipelineLayout g_createPl;
static PFN_vkCreateRenderPass g_createRp;
static PFN_vkCreateSampler g_createSampler;
static PFN_vkGetPhysicalDeviceProperties g_getProps;
static int g_propsLogged;

static VkResult VKAPI_CALL createsampler_wrap(VkDevice d, const VkSamplerCreateInfo* info, const VkAllocationCallbacks* a, VkSampler* out) {
    VkResult r = g_createSampler(d, info, a, out);
    PDIAG("sampler %llx -> %d: mag %d min %d mip %d address %d/%d/%d lod %g..%g bias %g aniso %d/%g compare %d border %d unnorm %d",
          (unsigned long long)*out, r, (int)info->magFilter, (int)info->minFilter, (int)info->mipmapMode, (int)info->addressModeU, (int)info->addressModeV,
          (int)info->addressModeW, info->minLod, info->maxLod, info->mipLodBias, (int)info->anisotropyEnable, info->maxAnisotropy, (int)info->compareEnable,
          (int)info->borderColor, (int)info->unnormalizedCoordinates);
    return r;
}

static void VKAPI_CALL getprops_wrap(VkPhysicalDevice pd, VkPhysicalDeviceProperties* p) {
    g_getProps(pd, p);
    if (!g_propsLogged) {
        g_propsLogged = 1;
        PDIAG("device '%s' api %u.%u.%u driver 0x%x: minUboAlign %llu minSsboAlign %llu nonCoherentAtom %llu maxPush %u maxSets %u maxVertexStride %u maxViewports %u",
              p->deviceName, VK_VERSION_MAJOR(p->apiVersion), VK_VERSION_MINOR(p->apiVersion), VK_VERSION_PATCH(p->apiVersion), p->driverVersion,
              (unsigned long long)p->limits.minUniformBufferOffsetAlignment, (unsigned long long)p->limits.minStorageBufferOffsetAlignment,
              (unsigned long long)p->limits.nonCoherentAtomSize, p->limits.maxPushConstantsSize, p->limits.maxBoundDescriptorSets,
              p->limits.maxVertexInputBindingStride, p->limits.maxViewports);
    }
}
static PFN_vkCreateRenderPass2 g_createRp2;
static PFN_vkCmdBeginRenderPass g_beginRp;
static PFN_vkCmdBeginRenderPass2 g_beginRp2;
static PFN_vkCmdEndRenderPass g_endRp;

static VkResult VKAPI_CALL createrp_wrap(VkDevice d, const VkRenderPassCreateInfo* info, const VkAllocationCallbacks* a, VkRenderPass* out) {
    VkResult r = g_createRp(d, info, a, out);
    char buf[1500]; int m = 0;
    pd_app(buf, &m, "render pass %llx -> %d: %u attachments", (unsigned long long)*out, r, info->attachmentCount);
    for (uint32_t k = 0; k < info->attachmentCount; k++) {
        const VkAttachmentDescription* at = &info->pAttachments[k];
        pd_app(buf, &m, " [%u: fmt %d samples %d load %d store %d layout %d->%d]", k, (int)at->format, (int)at->samples, (int)at->loadOp, (int)at->storeOp, (int)at->initialLayout, (int)at->finalLayout);
    }
    for (uint32_t sp = 0; sp < info->subpassCount; sp++) {
        const VkSubpassDescription* su = &info->pSubpasses[sp];
        pd_app(buf, &m, "; subpass %u color", sp);
        for (uint32_t c = 0; c < su->colorAttachmentCount; c++) pd_app(buf, &m, " %d(l%d)", (int)su->pColorAttachments[c].attachment, (int)su->pColorAttachments[c].layout);
        if (su->pDepthStencilAttachment) pd_app(buf, &m, " depth %d(l%d)", (int)su->pDepthStencilAttachment->attachment, (int)su->pDepthStencilAttachment->layout);
        if (su->pResolveAttachments) pd_app(buf, &m, " resolve");
    }
    PDIAG("%s", buf);
    return r;
}

static VkResult VKAPI_CALL createrp2_wrap(VkDevice d, const VkRenderPassCreateInfo2* info, const VkAllocationCallbacks* a, VkRenderPass* out) {
    VkResult r = g_createRp2(d, info, a, out);
    char buf[1500]; int m = 0;
    pd_app(buf, &m, "render pass2 %llx -> %d: %u attachments", (unsigned long long)*out, r, info->attachmentCount);
    for (uint32_t k = 0; k < info->attachmentCount; k++) {
        const VkAttachmentDescription2* at = &info->pAttachments[k];
        pd_app(buf, &m, " [%u: fmt %d samples %d load %d store %d layout %d->%d]", k, (int)at->format, (int)at->samples, (int)at->loadOp, (int)at->storeOp, (int)at->initialLayout, (int)at->finalLayout);
    }
    for (uint32_t sp = 0; sp < info->subpassCount; sp++) {
        const VkSubpassDescription2* su = &info->pSubpasses[sp];
        pd_app(buf, &m, "; subpass %u color", sp);
        for (uint32_t c = 0; c < su->colorAttachmentCount; c++) pd_app(buf, &m, " %d(l%d)", (int)su->pColorAttachments[c].attachment, (int)su->pColorAttachments[c].layout);
        if (su->pDepthStencilAttachment) pd_app(buf, &m, " depth %d(l%d)", (int)su->pDepthStencilAttachment->attachment, (int)su->pDepthStencilAttachment->layout);
    }
    PDIAG("%s", buf);
    return r;
}

static void VKAPI_CALL beginrp_wrap(VkCommandBuffer cb, const VkRenderPassBeginInfo* info, VkSubpassContents c) {
    if (g_beginLogs > 0 || g_tracing) {
        if (!g_tracing) g_beginLogs--;
        PDIAG("cmd %llx: begin render pass %llx framebuffer %llx area %d,%d %ux%u, %u clears", (unsigned long long)cb, (unsigned long long)info->renderPass,
              (unsigned long long)info->framebuffer, info->renderArea.offset.x, info->renderArea.offset.y, info->renderArea.extent.width, info->renderArea.extent.height, info->clearValueCount);
    }
    g_beginRp(cb, info, c);
}

static void VKAPI_CALL beginrp2_wrap(VkCommandBuffer cb, const VkRenderPassBeginInfo* info, const VkSubpassBeginInfo* sb) {
    if (g_beginLogs > 0 || g_tracing) {
        if (!g_tracing) g_beginLogs--;
        PDIAG("cmd %llx: begin render pass2 %llx framebuffer %llx area %d,%d %ux%u", (unsigned long long)cb, (unsigned long long)info->renderPass,
              (unsigned long long)info->framebuffer, info->renderArea.offset.x, info->renderArea.offset.y, info->renderArea.extent.width, info->renderArea.extent.height);
    }
    g_beginRp2(cb, info, sb);
}

static void VKAPI_CALL endrp_wrap(VkCommandBuffer cb) {
    if (g_tracing) { PDIAG("cmd %llx: end render pass", (unsigned long long)cb); g_tracing = 0; }
    g_endRp(cb);
}
static PFN_vkCmdBindPipeline g_bindPipeline;
static PFN_vkCmdPushConstants g_pushConstants;
static PFN_vkCmdBindDescriptorSets g_bindSets;
static PFN_vkCmdBindVertexBuffers g_bindVbs;
static PFN_vkCmdSetViewport g_setViewport;
static PFN_vkCmdSetScissor g_setScissor;

static VkResult VKAPI_CALL dsl_wrap(VkDevice d, const VkDescriptorSetLayoutCreateInfo* info, const VkAllocationCallbacks* a, VkDescriptorSetLayout* out) {
    VkResult r = g_createDsl(d, info, a, out);
    char buf[1500]; int m = 0;
    pd_app(buf, &m, "set layout %llx (flags 0x%x, %u bindings):", (unsigned long long)*out, info->flags, info->bindingCount);
    for (uint32_t k = 0; k < info->bindingCount; k++) {
        const VkDescriptorSetLayoutBinding* b = &info->pBindings[k];
        pd_app(buf, &m, " [b%u type %d x%u stages 0x%x]", b->binding, (int)b->descriptorType, b->descriptorCount, b->stageFlags);
    }
    PDIAG("%s -> %d", buf, r);
    return r;
}

static VkResult VKAPI_CALL pl_wrap(VkDevice d, const VkPipelineLayoutCreateInfo* info, const VkAllocationCallbacks* a, VkPipelineLayout* out) {
    VkResult r = g_createPl(d, info, a, out);
    char buf[1500]; int m = 0;
    pd_app(buf, &m, "pipeline layout %llx: sets", (unsigned long long)*out);
    for (uint32_t k = 0; k < info->setLayoutCount; k++) pd_app(buf, &m, " %llx", (unsigned long long)info->pSetLayouts[k]);
    pd_app(buf, &m, "; push");
    for (uint32_t k = 0; k < info->pushConstantRangeCount; k++)
        pd_app(buf, &m, " [stages 0x%x off %u size %u]", info->pPushConstantRanges[k].stageFlags, info->pPushConstantRanges[k].offset, info->pPushConstantRanges[k].size);
    if (r == VK_SUCCESS && info->setLayoutCount == 3 && info->pushConstantRangeCount == 1 && info->pPushConstantRanges[0].size == 16) {
        pthread_mutex_lock(&g_pdiagMu);
        for (int t = 0; t < 16; t++) if (!g_uiMatLayouts[t]) { g_uiMatLayouts[t] = *out; break; }
        pthread_mutex_unlock(&g_pdiagMu);
        pd_app(buf, &m, " (UI material)");
    }
    PDIAG("%s -> %d", buf, r);
    return r;
}

static void VKAPI_CALL bindpipeline_wrap(VkCommandBuffer cb, VkPipelineBindPoint bp, VkPipeline p) {
    int traced = 0;
    for (int t = 0; t < 8; t++) if (g_tracePipes[t] && g_tracePipes[t] == p) traced = 1;
    if (traced && g_traceBudget > 0) { g_tracing = 1; PDIAG("cmd %llx: bind pipeline %llx (traced)", (unsigned long long)cb, (unsigned long long)p); }
    else { if (g_tracing) PDIAG("cmd %llx: bind pipeline %llx (other)", (unsigned long long)cb, (unsigned long long)p); g_tracing = 0; }
    g_bindPipeline(cb, bp, p);
}

static void VKAPI_CALL pushconstants_wrap(VkCommandBuffer cb, VkPipelineLayout l, VkShaderStageFlags st, uint32_t off, uint32_t size, const void* v) {
    if (g_tracing) {
        char buf[1500]; int m = 0;
        pd_app(buf, &m, "cmd %llx: push layout %llx stages 0x%x off %u size %u:", (unsigned long long)cb, (unsigned long long)l, st, off, size);
        const float* f = (const float*)v;
        for (uint32_t k = 0; k < size / 4 && k < 16; k++) pd_app(buf, &m, " %g", f[k]);
        PDIAG("%s", buf);
    }
    g_pushConstants(cb, l, st, off, size, v);
}

static void VKAPI_CALL bindsets_wrap(VkCommandBuffer cb, VkPipelineBindPoint bp, VkPipelineLayout l, uint32_t first, uint32_t n, const VkDescriptorSet* sets, uint32_t dn, const uint32_t* doff) {
    if (g_tracing) {
        char buf[1500]; int m = 0;
        pd_app(buf, &m, "cmd %llx: bind sets layout %llx first %u count %u:", (unsigned long long)cb, (unsigned long long)l, first, n);
        for (uint32_t k = 0; k < n; k++) pd_app(buf, &m, " %llx", (unsigned long long)sets[k]);
        for (uint32_t k = 0; k < dn; k++) pd_app(buf, &m, " dyn%u", doff[k]);
        PDIAG("%s", buf);
    }
    g_bindSets(cb, bp, l, first, n, sets, dn, doff);
}

static void VKAPI_CALL bindvbs_wrap(VkCommandBuffer cb, uint32_t first, uint32_t n, const VkBuffer* b, const VkDeviceSize* off) {
    if (g_tracing) {
        char buf[1500]; int m = 0;
        pd_app(buf, &m, "cmd %llx: bind vertex buffers first %u count %u:", (unsigned long long)cb, first, n);
        for (uint32_t k = 0; k < n; k++) pd_app(buf, &m, " %llx+%llu", (unsigned long long)b[k], (unsigned long long)off[k]);
        PDIAG("%s", buf);
    }
    g_bindVbs(cb, first, n, b, off);
}

static void VKAPI_CALL setviewport_wrap(VkCommandBuffer cb, uint32_t first, uint32_t n, const VkViewport* v) {
    if ((g_tracing || g_viewportLogs > 0) && n) {
        if (!g_tracing) g_viewportLogs--;
        PDIAG("cmd %llx: viewport %g %g %g %g depth %g..%g", (unsigned long long)cb, v[0].x, v[0].y, v[0].width, v[0].height, v[0].minDepth, v[0].maxDepth);
    }
    g_setViewport(cb, first, n, v);
}

static void VKAPI_CALL setscissor_wrap(VkCommandBuffer cb, uint32_t first, uint32_t n, const VkRect2D* r) {
    if (g_tracing && n) PDIAG("cmd %llx: scissor %d %d %u %u", (unsigned long long)cb, r[0].offset.x, r[0].offset.y, r[0].extent.width, r[0].extent.height);
    g_setScissor(cb, first, n, r);
}

static void VKAPI_CALL drawindexed_wrap(VkCommandBuffer cb, uint32_t ic, uint32_t inst, uint32_t fi, int32_t vo, uint32_t fin) {
    __atomic_add_fetch(&g_draws, 1, __ATOMIC_RELAXED);
    if (g_tracing && g_traceBudget > 0) { g_traceBudget--; PDIAG("cmd %llx: drawIndexed %u x %u first %u vtxoff %d firstInst %u", (unsigned long long)cb, ic, inst, fi, vo, fin); }
    if (ic > (16u << 20) || inst > 200000) ring_add("drawIndexed %u indices x %u instances", ic, inst);
    g_drawIndexed(cb, ic, inst, fi, vo, fin);
}

static void VKAPI_CALL drawindexedindirect_wrap(VkCommandBuffer cb, VkBuffer b, VkDeviceSize off, uint32_t count, uint32_t stride) {
    __atomic_add_fetch(&g_draws, 1, __ATOMIC_RELAXED);
    if (count > 100000) ring_add("drawIndexedIndirect %u draws", count);
    g_drawIndexedIndirect(cb, b, off, count, stride);
}

static void VKAPI_CALL drawindirect_wrap(VkCommandBuffer cb, VkBuffer b, VkDeviceSize off, uint32_t count, uint32_t stride) {
    __atomic_add_fetch(&g_draws, 1, __ATOMIC_RELAXED);
    if (count > 100000) ring_add("drawIndirect %u draws", count);
    g_drawIndirect(cb, b, off, count, stride);
}

// Descriptor writes with a null image view / sampler / buffer: a material whose texture never
// resolved (a mod's asset-bundle shader and textures the engine could not take) ends up as a
// descriptor the GPU reads garbage through. Counted and ringed, never changed.
static PFN_vkUpdateDescriptorSets g_updateSets;
static PFN_vkCreateShaderModule g_shaderModule;
static unsigned g_shaderModules;

static void VKAPI_CALL updatesets_wrap(VkDevice d, uint32_t nw, const VkWriteDescriptorSet* w, uint32_t nc, const VkCopyDescriptorSet* c) {
    for (uint32_t i = 0; i < nw; i++) {
        const VkWriteDescriptorSet* x = &w[i];
        if (x->descriptorType == VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER && x->pImageInfo) {
            for (uint32_t j = 0; j < x->descriptorCount; j++) {
                unsigned l = (unsigned)x->pImageInfo[j].imageLayout;
                if (l < 16 && !g_imgLayoutSeen[l]) {
                    g_imgLayoutSeen[l] = 1;
                    PDIAG("descriptor write: first sampler descriptor with image layout %u: set %llx binding %u view %llx sampler %llx", l,
                          (unsigned long long)x->dstSet, x->dstBinding, (unsigned long long)x->pImageInfo[j].imageView, (unsigned long long)x->pImageInfo[j].sampler);
                }
            }
        }
        if (x->descriptorType == VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER && x->pBufferInfo && g_uiMatLayouts[0] && g_uboLogs > 0) {
            g_uboLogs--;
            PDIAG("descriptor write: UBO set %llx binding %u buffer %llx offset %llu range %llu", (unsigned long long)x->dstSet, x->dstBinding,
                  (unsigned long long)x->pBufferInfo[0].buffer, (unsigned long long)x->pBufferInfo[0].offset, (unsigned long long)x->pBufferInfo[0].range);
        }
        unsigned nulls = 0;
        switch (x->descriptorType) {
            case VK_DESCRIPTOR_TYPE_SAMPLER:
                for (uint32_t j = 0; x->pImageInfo && j < x->descriptorCount; j++) if (!x->pImageInfo[j].sampler) nulls++;
                break;
            case VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER:
                for (uint32_t j = 0; x->pImageInfo && j < x->descriptorCount; j++) if (!x->pImageInfo[j].imageView || !x->pImageInfo[j].sampler) nulls++;
                break;
            case VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE: case VK_DESCRIPTOR_TYPE_STORAGE_IMAGE: case VK_DESCRIPTOR_TYPE_INPUT_ATTACHMENT:
                for (uint32_t j = 0; x->pImageInfo && j < x->descriptorCount; j++) if (!x->pImageInfo[j].imageView) nulls++;
                break;
            case VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER: case VK_DESCRIPTOR_TYPE_STORAGE_BUFFER:
            case VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC: case VK_DESCRIPTOR_TYPE_STORAGE_BUFFER_DYNAMIC:
                for (uint32_t j = 0; x->pBufferInfo && j < x->descriptorCount; j++) if (!x->pBufferInfo[j].buffer) nulls++;
                break;
            default: break;
        }
        if (nulls) {
            unsigned n = __atomic_add_fetch(&g_nullWrites, nulls, __ATOMIC_RELAXED);
            if (n <= 40) ring_add("NULL descriptor write: type %d, binding %u, element %u, %u of %u null (total %u)",
                                  x->descriptorType, x->dstBinding, x->dstArrayElement, nulls, x->descriptorCount, n);
        }
    }
    g_updateSets(d, nw, w, nc, c);
}

// ---- Mali: empty icons of masked things (2026-10-17) ------------------------------------------------
// RimWorld 1.7 draws the Architect icons of things tinted through a mask (beds, bedrolls, cribs,
// braziers, drapes...) with ImGui's vertex shader in front of the material's UI fragment shader
// (CutoutComplexUI). ImGui's shader writes colour and UV as ONE struct output at location 0
// ("Out { vec4 Color; vec2 UV; }"), the material's reads two plain inputs (vColor at 0, vUv at 1).
// Vulkan only matches a struct with the same struct; Adreno lays it out anyway, Mali's driver hands
// the fragment shader nothing, the UV is 0 and the icon samples one transparent corner texel. For
// such pipelines the vertex stage becomes the same shader with the two outputs declared separately
// (SPIR-V of the GLSL below, glslc -O, vulkan1.0); ImGui's own pipelines keep their struct pair.
//   layout(location=0) in vec2 aPos; layout(location=1) in vec2 aUV; layout(location=2) in vec4 aColor;
//   layout(push_constant) uniform uPushConstant { vec2 uScale; vec2 uTranslate; } pc;
//   layout(location=0) out vec4 oColor; layout(location=1) out vec2 oUV;
//   void main() { oColor = aColor; oUV = aUV; gl_Position = vec4(aPos * pc.uScale + pc.uTranslate, 0.0, 1.0); }
static const uint32_t kFlatImguiVs[] = {
    0x07230203u, 0x00010000u, 0x000d000au, 0x0000002eu, 0x00000000u, 0x00020011u,
    0x00000001u, 0x0006000bu, 0x00000001u, 0x4c534c47u, 0x6474732eu, 0x3035342eu,
    0x00000000u, 0x0003000eu, 0x00000000u, 0x00000001u, 0x000b000fu, 0x00000000u,
    0x00000004u, 0x6e69616du, 0x00000000u, 0x00000009u, 0x0000000bu, 0x0000000fu,
    0x00000011u, 0x00000018u, 0x0000001bu, 0x00040047u, 0x00000009u, 0x0000001eu,
    0x00000000u, 0x00040047u, 0x0000000bu, 0x0000001eu, 0x00000002u, 0x00040047u,
    0x0000000fu, 0x0000001eu, 0x00000001u, 0x00040047u, 0x00000011u, 0x0000001eu,
    0x00000001u, 0x00050048u, 0x00000016u, 0x00000000u, 0x0000000bu, 0x00000000u,
    0x00050048u, 0x00000016u, 0x00000001u, 0x0000000bu, 0x00000001u, 0x00050048u,
    0x00000016u, 0x00000002u, 0x0000000bu, 0x00000003u, 0x00050048u, 0x00000016u,
    0x00000003u, 0x0000000bu, 0x00000004u, 0x00030047u, 0x00000016u, 0x00000002u,
    0x00040047u, 0x0000001bu, 0x0000001eu, 0x00000000u, 0x00050048u, 0x0000001du,
    0x00000000u, 0x00000023u, 0x00000000u, 0x00050048u, 0x0000001du, 0x00000001u,
    0x00000023u, 0x00000008u, 0x00030047u, 0x0000001du, 0x00000002u, 0x00020013u,
    0x00000002u, 0x00030021u, 0x00000003u, 0x00000002u, 0x00030016u, 0x00000006u,
    0x00000020u, 0x00040017u, 0x00000007u, 0x00000006u, 0x00000004u, 0x00040020u,
    0x00000008u, 0x00000003u, 0x00000007u, 0x0004003bu, 0x00000008u, 0x00000009u,
    0x00000003u, 0x00040020u, 0x0000000au, 0x00000001u, 0x00000007u, 0x0004003bu,
    0x0000000au, 0x0000000bu, 0x00000001u, 0x00040017u, 0x0000000du, 0x00000006u,
    0x00000002u, 0x00040020u, 0x0000000eu, 0x00000003u, 0x0000000du, 0x0004003bu,
    0x0000000eu, 0x0000000fu, 0x00000003u, 0x00040020u, 0x00000010u, 0x00000001u,
    0x0000000du, 0x0004003bu, 0x00000010u, 0x00000011u, 0x00000001u, 0x00040015u,
    0x00000013u, 0x00000020u, 0x00000000u, 0x0004002bu, 0x00000013u, 0x00000014u,
    0x00000001u, 0x0004001cu, 0x00000015u, 0x00000006u, 0x00000014u, 0x0006001eu,
    0x00000016u, 0x00000007u, 0x00000006u, 0x00000015u, 0x00000015u, 0x00040020u,
    0x00000017u, 0x00000003u, 0x00000016u, 0x0004003bu, 0x00000017u, 0x00000018u,
    0x00000003u, 0x00040015u, 0x00000019u, 0x00000020u, 0x00000001u, 0x0004002bu,
    0x00000019u, 0x0000001au, 0x00000000u, 0x0004003bu, 0x00000010u, 0x0000001bu,
    0x00000001u, 0x0004001eu, 0x0000001du, 0x0000000du, 0x0000000du, 0x00040020u,
    0x0000001eu, 0x00000009u, 0x0000001du, 0x0004003bu, 0x0000001eu, 0x0000001fu,
    0x00000009u, 0x00040020u, 0x00000020u, 0x00000009u, 0x0000000du, 0x0004002bu,
    0x00000019u, 0x00000024u, 0x00000001u, 0x0004002bu, 0x00000006u, 0x00000028u,
    0x00000000u, 0x0004002bu, 0x00000006u, 0x00000029u, 0x3f800000u, 0x00050036u,
    0x00000002u, 0x00000004u, 0x00000000u, 0x00000003u, 0x000200f8u, 0x00000005u,
    0x0004003du, 0x00000007u, 0x0000000cu, 0x0000000bu, 0x0003003eu, 0x00000009u,
    0x0000000cu, 0x0004003du, 0x0000000du, 0x00000012u, 0x00000011u, 0x0003003eu,
    0x0000000fu, 0x00000012u, 0x0004003du, 0x0000000du, 0x0000001cu, 0x0000001bu,
    0x00050041u, 0x00000020u, 0x00000021u, 0x0000001fu, 0x0000001au, 0x0004003du,
    0x0000000du, 0x00000022u, 0x00000021u, 0x00050041u, 0x00000020u, 0x00000025u,
    0x0000001fu, 0x00000024u, 0x0004003du, 0x0000000du, 0x00000026u, 0x00000025u,
    0x0008000cu, 0x0000000du, 0x00000027u, 0x00000001u, 0x00000032u, 0x0000001cu,
    0x00000022u, 0x00000026u, 0x00050051u, 0x00000006u, 0x0000002au, 0x00000027u,
    0x00000000u, 0x00050051u, 0x00000006u, 0x0000002bu, 0x00000027u, 0x00000001u,
    0x00070050u, 0x00000007u, 0x0000002cu, 0x0000002au, 0x0000002bu, 0x00000028u,
    0x00000029u, 0x00050041u, 0x00000008u, 0x0000002du, 0x00000018u, 0x0000001au,
    0x0003003eu, 0x0000002du, 0x0000002cu, 0x000100fdu, 0x00010038u
};
#define IMGUI_VS_HASH 0x09c72260u   /* FNV-1a of ImGui's vertex SPIR-V in RimWorld.dll (1296 bytes) */
#define IMGUI_FS_HASH 0xa54df87eu   /* ImGui's fragment shader (772 bytes) */
static VkShaderModule g_imguiVs[8], g_imguiFs[8], g_flatVs;
static pthread_mutex_t g_imguiMu = PTHREAD_MUTEX_INITIALIZER;

static int module_in(VkShaderModule m, VkShaderModule* set) {
    for (int k = 0; k < 8; k++) if (set[k] && set[k] == m) return 1;
    return 0;
}

// Every shader module: its size, whether it starts with the SPIR-V magic word, and how many
// instructions it holds (garbage or empty code from a failed compile is the one thing that makes a
// GPU die without any API error). Logged once the game is up (the first ~260 are the pack's).
static VkResult VKAPI_CALL shadermodule_wrap(VkDevice d, const VkShaderModuleCreateInfo* info, const VkAllocationCallbacks* a, VkShaderModule* out) {
    VkResult r = g_shaderModule(d, info, a, out);
    unsigned n = __atomic_add_fetch(&g_shaderModules, 1, __ATOMIC_RELAXED);
    uint32_t magic = info->codeSize >= 4 ? info->pCode[0] : 0, bound = info->codeSize >= 16 ? info->pCode[3] : 0;
    int bad = magic != 0x07230203u || info->codeSize < 20 || (info->codeSize & 3);
    if (bad || n > 200) {
        ring_add("shader module #%u: %u bytes, magic 0x%08x%s, bound %u -> %d", n, (unsigned)info->codeSize, magic,
                 bad ? " (NOT SPIR-V)" : "", bound, r);
        if (bad) MLOG("shader module #%u is NOT valid SPIR-V: %u bytes, first word 0x%08x", n, (unsigned)info->codeSize, magic);
    }
    if (r == VK_SUCCESS && !bad) {
        uint32_t h = 2166136261u;
        const uint8_t* p = (const uint8_t*)info->pCode;
        for (size_t k = 0; k < info->codeSize; k++) { h ^= p[k]; h *= 16777619u; }
        const uint32_t* w = info->pCode;
        size_t nw = info->codeSize / 4;
        int model = -1;
        for (size_t k = 5; k < nw; ) {   // OpEntryPoint (15): execution model 0 vertex, 4 fragment, 5 compute
            uint32_t len = w[k] >> 16, op = w[k] & 0xffff;
            if (!len) break;
            if (op == 15 && k + 1 < nw) { model = (int)w[k + 1]; break; }
            k += len;
        }
        PDIAG("shader module %llx: %u bytes, stage %s, hash %08x", (unsigned long long)*out, (unsigned)info->codeSize,
              model == 0 ? "vertex" : model == 4 ? "fragment" : model == 5 ? "compute" : "other", h);
        (void)pdiag_dump_spv;   // dumping every module to $HOME/vk_spv was for the icon hunt
        if (h == IMGUI_VS_HASH || h == IMGUI_FS_HASH) {
            VkShaderModule* set = h == IMGUI_VS_HASH ? g_imguiVs : g_imguiFs;
            pthread_mutex_lock(&g_imguiMu);
            for (int k = 0; k < 8; k++) if (!set[k]) { set[k] = *out; break; }
            pthread_mutex_unlock(&g_imguiMu);
        }
    }
    return r;
}

// While converting BC, a memory object stays mapped from its first vkMapMemory until it is freed
// (unmap_wrap leaves it), mapped whole: the engine writes an upload buffer, unmaps it at once and
// only later submits the copy that reads it, by when re-encoding its blocks needs the mapping (122
// copies "from a buffer the CPU has not mapped", memory type 6, 2026-10-15). A later vkMapMemory of
// the same memory gets the same mapping at the offset asked for.
static VkResult VKAPI_CALL map_wrap(VkDevice d, VkDeviceMemory m, VkDeviceSize off, VkDeviceSize size, VkMemoryMapFlags f, void** data) {
    VkResult r;
    if (bc_convert()) {
        pthread_mutex_lock(&g_cvmu);
        MapSlot* kept = hmap_find(&g_memMaps, (uint64_t)m);
        void* base = kept ? (void*)(uintptr_t)kept->a : NULL;
        pthread_mutex_unlock(&g_cvmu);
        r = VK_SUCCESS;
        if (!base) {
            r = g_map(d, m, 0, VK_WHOLE_SIZE, f, &base);
            if (r == VK_SUCCESS) {
                pthread_mutex_lock(&g_cvmu);
                hmap_put(&g_memMaps, (uint64_t)m, (uint64_t)(uintptr_t)base, 0);
                pthread_mutex_unlock(&g_cvmu);
            }
        }
        if (r == VK_SUCCESS) *data = (uint8_t*)base + off;
    } else {
        r = g_map(d, m, off, size, f, data);
    }
    if (r == VK_SUCCESS) {
        pthread_mutex_lock(&g_mu);
        g_maps++;
        if (size != VK_WHOLE_SIZE) g_mapped += size;
        pthread_mutex_unlock(&g_mu);
    }
    return r;
}

static VkResult VKAPI_CALL gfxpipes_wrap(VkDevice d, VkPipelineCache c, uint32_t n, const VkGraphicsPipelineCreateInfo* i, const VkAllocationCallbacks* a, VkPipeline* p) {
    unsigned total = __atomic_add_fetch(&g_pipelines, n, __ATOMIC_RELAXED);
    // ImGui's vertex shader in front of a fragment shader that is not ImGui's: the flat-output copy
    // (see kFlatImguiVs). The caller's create infos stay as they are; copies go to the driver.
    VkGraphicsPipelineCreateInfo* fixedInfos = NULL;
    VkPipelineShaderStageCreateInfo* fixedStages = NULL;
    if (g_imguiVs[0]) {
        for (uint32_t k = 0; k < n; k++) {
            const VkGraphicsPipelineCreateInfo* g = &i[k];
            int vsAt = -1, fsForeign = 0;
            for (uint32_t st = 0; st < g->stageCount; st++) {
                if (g->pStages[st].stage == VK_SHADER_STAGE_VERTEX_BIT && module_in(g->pStages[st].module, g_imguiVs)) vsAt = (int)st;
                if (g->pStages[st].stage == VK_SHADER_STAGE_FRAGMENT_BIT && !module_in(g->pStages[st].module, g_imguiFs)) fsForeign = 1;
            }
            if (vsAt < 0 || !fsForeign) continue;
            pthread_mutex_lock(&g_imguiMu);
            if (!g_flatVs) {
                VkShaderModuleCreateInfo mi = { VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO, NULL, 0, sizeof kFlatImguiVs, kFlatImguiVs };
                if (g_shaderModule(d, &mi, NULL, &g_flatVs) != VK_SUCCESS) g_flatVs = VK_NULL_HANDLE;
                LOGI("flat-output ImGui vertex shader for material UI draws: %s", g_flatVs ? "created" : "FAILED");
            }
            VkShaderModule flat = g_flatVs;
            pthread_mutex_unlock(&g_imguiMu);
            if (!flat) break;
            if (!fixedInfos) {
                fixedInfos = (VkGraphicsPipelineCreateInfo*)malloc(n * sizeof *fixedInfos);
                fixedStages = (VkPipelineShaderStageCreateInfo*)malloc(n * 8 * sizeof *fixedStages);
                if (!fixedInfos || !fixedStages) { free(fixedInfos); free(fixedStages); fixedInfos = NULL; fixedStages = NULL; break; }
                memcpy(fixedInfos, i, n * sizeof *fixedInfos);
            }
            if (g->stageCount > 8) continue;
            memcpy(&fixedStages[k * 8], g->pStages, g->stageCount * sizeof *fixedStages);
            fixedStages[k * 8 + vsAt].module = flat;
            fixedInfos[k].pStages = &fixedStages[k * 8];
            PDIAG("pipeline #%u: material UI draw, vertex stage -> flat-output ImGui shader", total - n + k + 1);
        }
    }
    VkResult r = g_gfxPipes(d, c, n, fixedInfos ? fixedInfos : i, a, p);
    free(fixedInfos);
    free(fixedStages);
    for (uint32_t k = 0; k < n && k < 4; k++) {
        const VkGraphicsPipelineCreateInfo* g = &i[k];
        uint64_t vs = 0, fs = 0;
        for (uint32_t st = 0; st < g->stageCount; st++) {
            if (g->pStages[st].stage == VK_SHADER_STAGE_VERTEX_BIT) vs = (uint64_t)g->pStages[st].module;
            if (g->pStages[st].stage == VK_SHADER_STAGE_FRAGMENT_BIT) fs = (uint64_t)g->pStages[st].module;
        }
        const VkPipelineColorBlendStateCreateInfo* cb = g->pColorBlendState;
        ring_add("graphics pipeline = %u -> %d: vs %llx fs %llx, %u attachments, blend %s, topology %d", total, r,
                 (unsigned long long)vs, (unsigned long long)fs, cb ? cb->attachmentCount : 0,
                 cb && cb->attachmentCount && cb->pAttachments && cb->pAttachments[0].blendEnable ? "on" : "off",
                 g->pInputAssemblyState ? (int)g->pInputAssemblyState->topology : -1);
    }
    for (uint32_t k = 0; k < n; k++) {
        const VkGraphicsPipelineCreateInfo* g = &i[k];
        char buf[1500]; int m = 0; int stride0 = 0;
        pd_app(buf, &m, "pipeline %llx -> %d: layout %llx pass %llx sub %u; stages", (unsigned long long)p[k], r,
               (unsigned long long)g->layout, (unsigned long long)g->renderPass, g->subpass);
        for (uint32_t s = 0; s < g->stageCount; s++)
            pd_app(buf, &m, " [0x%x %llx %s]", g->pStages[s].stage, (unsigned long long)g->pStages[s].module, g->pStages[s].pName ? g->pStages[s].pName : "?");
        const VkPipelineVertexInputStateCreateInfo* vi = g->pVertexInputState;
        if (vi) {
            pd_app(buf, &m, "; bindings");
            for (uint32_t b = 0; b < vi->vertexBindingDescriptionCount; b++) {
                const VkVertexInputBindingDescription* vb = &vi->pVertexBindingDescriptions[b];
                pd_app(buf, &m, " [b%u stride %u rate %d]", vb->binding, vb->stride, (int)vb->inputRate);
                if (vb->stride == 0) stride0 = 1;
            }
            pd_app(buf, &m, " attrs");
            for (uint32_t at = 0; at < vi->vertexAttributeDescriptionCount; at++) {
                const VkVertexInputAttributeDescription* va = &vi->pVertexAttributeDescriptions[at];
                pd_app(buf, &m, " [loc%u b%u fmt %d off %u]", va->location, va->binding, (int)va->format, va->offset);
            }
        }
        const VkPipelineRasterizationStateCreateInfo* rs = g->pRasterizationState;
        if (rs) pd_app(buf, &m, "; raster cull %u front %d poly %d clamp %d discard %d", rs->cullMode, (int)rs->frontFace, (int)rs->polygonMode, (int)rs->depthClampEnable, (int)rs->rasterizerDiscardEnable);
        const VkPipelineDepthStencilStateCreateInfo* ds = g->pDepthStencilState;
        if (ds) pd_app(buf, &m, "; depth test %d write %d op %d stencil %d", (int)ds->depthTestEnable, (int)ds->depthWriteEnable, (int)ds->depthCompareOp, (int)ds->stencilTestEnable);
        const VkPipelineMultisampleStateCreateInfo* ms = g->pMultisampleState;
        if (ms) pd_app(buf, &m, "; samples %d", (int)ms->rasterizationSamples);
        const VkPipelineColorBlendStateCreateInfo* cb = g->pColorBlendState;
        if (cb) for (uint32_t at = 0; at < cb->attachmentCount && at < 2; at++) {
            const VkPipelineColorBlendAttachmentState* ba = &cb->pAttachments[at];
            pd_app(buf, &m, "; blend%u %d src %d dst %d op %d asrc %d adst %d mask 0x%x", at, (int)ba->blendEnable, (int)ba->srcColorBlendFactor,
                   (int)ba->dstColorBlendFactor, (int)ba->colorBlendOp, (int)ba->srcAlphaBlendFactor, (int)ba->dstAlphaBlendFactor, ba->colorWriteMask);
        }
        const VkPipelineDynamicStateCreateInfo* dy = g->pDynamicState;
        if (dy) { pd_app(buf, &m, "; dynamic"); for (uint32_t x = 0; x < dy->dynamicStateCount; x++) pd_app(buf, &m, " %d", (int)dy->pDynamicStates[x]); }
        const VkPipelineViewportStateCreateInfo* vp = g->pViewportState;
        if (vp) pd_app(buf, &m, "; viewports %u scissors %u", vp->viewportCount, vp->scissorCount);
        for (int t = 0; t < 16; t++) if (g_uiMatLayouts[t] && g_uiMatLayouts[t] == g->layout) stride0 = 1;
        if (stride0 && r == VK_SUCCESS) {
            pthread_mutex_lock(&g_pdiagMu);
            for (int t = 0; t < 8; t++) if (!g_tracePipes[t]) { g_tracePipes[t] = p[k]; break; }
            pthread_mutex_unlock(&g_pdiagMu);
            pd_app(buf, &m, "; TRACED");
        }
        PDIAG("%s", buf);
    }
    return r;
}

static VkResult VKAPI_CALL comppipes_wrap(VkDevice d, VkPipelineCache c, uint32_t n, const VkComputePipelineCreateInfo* i, const VkAllocationCallbacks* a, VkPipeline* p) {
    __atomic_add_fetch(&g_pipelines, n, __ATOMIC_RELAXED);
    return g_compPipes(d, c, n, i, a, p);
}

static VkResult VKAPI_CALL swapchain_wrap(VkDevice device, const VkSwapchainCreateInfoKHR* info,
                                          const VkAllocationCallbacks* alloc, VkSwapchainKHR* out) {
    VkSwapchainCreateInfoKHR copy = *info;
    copy.preTransform = VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR;
    LOGI("swapchain %ux%u (preTransform forced to identity)", copy.imageExtent.width, copy.imageExtent.height);
    return g_swapchain(device, &copy, alloc, out);
}

static PFN_vkVoidFunction VKAPI_CALL gdpa_wrap(VkDevice device, const char* name);

static PFN_vkVoidFunction VKAPI_CALL gipa_wrap(VkInstance instance, const char* name) {
    if (!g_gipa) g_gipa = (PFN_vkGetInstanceProcAddr)real("vkGetInstanceProcAddr");
    if (!name || !g_gipa) return NULL;
    if (!strcmp(name, "vkGetInstanceProcAddr")) return (PFN_vkVoidFunction)gipa_wrap;
    if (!strcmp(name, "vkGetDeviceProcAddr")) {
        if (!g_gdpa) g_gdpa = (PFN_vkGetDeviceProcAddr)g_gipa(instance, "vkGetDeviceProcAddr");
        return (PFN_vkVoidFunction)gdpa_wrap;
    }
    PFN_vkVoidFunction f = g_gipa(instance, name);
    if (!f && instance) f = resolve_alias(name, gipa_get, instance);
    if (!f) return debug_utils_stub(name);
    if (!strcmp(name, "vkGetPhysicalDeviceSurfaceCapabilitiesKHR")) { g_caps = (PFN_vkGetPhysicalDeviceSurfaceCapabilitiesKHR)f; return (PFN_vkVoidFunction)caps_wrap; }
    if (!strcmp(name, "vkGetPhysicalDeviceFeatures")) { g_getFeatures = (PFN_vkGetPhysicalDeviceFeatures)f; return (PFN_vkVoidFunction)getfeatures_wrap; }
    if (!strcmp(name, "vkGetPhysicalDeviceFeatures2") || !strcmp(name, "vkGetPhysicalDeviceFeatures2KHR")) { g_getFeatures2 = (PFN_vkGetPhysicalDeviceFeatures2)f; return (PFN_vkVoidFunction)getfeatures2_wrap; }
    if (!strcmp(name, "vkGetPhysicalDeviceFormatProperties")) { g_getFormatProps = (PFN_vkGetPhysicalDeviceFormatProperties)f; return (PFN_vkVoidFunction)getformatprops_wrap; }
    if (!strcmp(name, "vkGetPhysicalDeviceFormatProperties2") || !strcmp(name, "vkGetPhysicalDeviceFormatProperties2KHR")) { g_getFormatProps2 = (PFN_vkGetPhysicalDeviceFormatProperties2)f; return (PFN_vkVoidFunction)getformatprops2_wrap; }
    if (!strcmp(name, "vkGetPhysicalDeviceImageFormatProperties")) { g_getImageFormatProps = (PFN_vkGetPhysicalDeviceImageFormatProperties)f; return (PFN_vkVoidFunction)getimageformatprops_wrap; }
    if (!strcmp(name, "vkGetPhysicalDeviceImageFormatProperties2") || !strcmp(name, "vkGetPhysicalDeviceImageFormatProperties2KHR")) { g_getImageFormatProps2 = (PFN_vkGetPhysicalDeviceImageFormatProperties2)f; return (PFN_vkVoidFunction)getimageformatprops2_wrap; }
    if (!strcmp(name, "vkCreateDevice")) { g_createDevice = (PFN_vkCreateDevice)f; return (PFN_vkVoidFunction)createdevice_wrap; }
    return hook(name, f);
}

// ---- Vulkan 1.1 drivers (2026-10-18) ----------------------------------------------------------------
// RimWorld 1.7 died 6-8 s after launch on a Mali-G57 with a Vulkan 1.1 driver: a call through a null
// function pointer from the engine's own code. The engine asks for functions that are core only in
// Vulkan 1.2/1.3 by their core names, which such a driver does not resolve, though it often has the
// same function from an extension (vkWaitSemaphoresKHR...). A name the driver does not know is
// looked up again with KHR and EXT; every miss, and every alias used, is logged once
// (RimDroid/vkpipe, vk_pipe.log).
static PFN_vkVoidFunction resolve_alias(const char* name, PFN_vkVoidFunction (*get)(void*, const char*), void* owner) {
    static const char* const sufs[] = { "KHR", "EXT" };
    char alt[160];
    size_t len = strlen(name);
    if (len + 4 >= sizeof alt) return NULL;
    for (int k = 0; k < 2; k++) {
        if (len > 3 && !strcmp(name + len - 3, sufs[k])) {   /* already a suffixed name */
            PDIAG("Vulkan function %s missing in the driver", name);
            return NULL;
        }
        snprintf(alt, sizeof alt, "%s%s", name, sufs[k]);
        PFN_vkVoidFunction f = get(owner, alt);
        if (f) { PDIAG("Vulkan function %s missing in the driver: using %s", name, alt); return f; }
    }
    PDIAG("Vulkan function %s missing in the driver (no KHR/EXT alias either)", name);
    return NULL;
}

// VK_EXT_debug_utils stand-ins (2026-10-18). The engine checks the extension only for its message
// callback; FrameRenderer.RecordFrame labels every render pass with vkCmdBeginDebugUtilsLabelEXT
// unconditionally, so on a driver without the extension (Mali-G57 r32, Adreno 618 of 2021) the
// first frame called a null pointer and the game died "after a few seconds" with a black screen.
// Labels and object names are for graphics debuggers; without the extension they do nothing.
static void VKAPI_CALL dbg_cmd_label(VkCommandBuffer c, const VkDebugUtilsLabelEXT* l) { (void)c; (void)l; }
static void VKAPI_CALL dbg_cmd_end(VkCommandBuffer c) { (void)c; }
static void VKAPI_CALL dbg_queue_label(VkQueue q, const VkDebugUtilsLabelEXT* l) { (void)q; (void)l; }
static void VKAPI_CALL dbg_queue_end(VkQueue q) { (void)q; }
static VkResult VKAPI_CALL dbg_set_name(VkDevice d, const VkDebugUtilsObjectNameInfoEXT* i) { (void)d; (void)i; return VK_SUCCESS; }
static VkResult VKAPI_CALL dbg_set_tag(VkDevice d, const VkDebugUtilsObjectTagInfoEXT* i) { (void)d; (void)i; return VK_SUCCESS; }
// A driver can list VK_EXT_debug_utils for the instance and still return null for its functions
// (Adreno 610, Honor, Android 14, 2024 driver): the engine then creates its message callback through a
// null pointer at startup. Without a messenger the engine just gets no driver messages.
static VkResult VKAPI_CALL dbg_create_messenger(VkInstance i, const VkDebugUtilsMessengerCreateInfoEXT* c, const VkAllocationCallbacks* a, VkDebugUtilsMessengerEXT* out) {
    (void)i; (void)c; (void)a;
    *out = (VkDebugUtilsMessengerEXT)(uintptr_t)1;
    return VK_SUCCESS;
}
static void VKAPI_CALL dbg_destroy_messenger(VkInstance i, VkDebugUtilsMessengerEXT m, const VkAllocationCallbacks* a) { (void)i; (void)m; (void)a; }
static void VKAPI_CALL dbg_submit_message(VkInstance i, VkDebugUtilsMessageSeverityFlagBitsEXT s, VkDebugUtilsMessageTypeFlagsEXT t, const VkDebugUtilsMessengerCallbackDataEXT* d) { (void)i; (void)s; (void)t; (void)d; }
static PFN_vkVoidFunction debug_utils_stub(const char* name) {
    PFN_vkVoidFunction f = NULL;
    if (!strcmp(name, "vkCmdBeginDebugUtilsLabelEXT") || !strcmp(name, "vkCmdInsertDebugUtilsLabelEXT")) f = (PFN_vkVoidFunction)dbg_cmd_label;
    else if (!strcmp(name, "vkCmdEndDebugUtilsLabelEXT")) f = (PFN_vkVoidFunction)dbg_cmd_end;
    else if (!strcmp(name, "vkQueueBeginDebugUtilsLabelEXT") || !strcmp(name, "vkQueueInsertDebugUtilsLabelEXT")) f = (PFN_vkVoidFunction)dbg_queue_label;
    else if (!strcmp(name, "vkQueueEndDebugUtilsLabelEXT")) f = (PFN_vkVoidFunction)dbg_queue_end;
    else if (!strcmp(name, "vkSetDebugUtilsObjectNameEXT")) f = (PFN_vkVoidFunction)dbg_set_name;
    else if (!strcmp(name, "vkSetDebugUtilsObjectTagEXT")) f = (PFN_vkVoidFunction)dbg_set_tag;
    else if (!strcmp(name, "vkCreateDebugUtilsMessengerEXT")) f = (PFN_vkVoidFunction)dbg_create_messenger;
    else if (!strcmp(name, "vkDestroyDebugUtilsMessengerEXT")) f = (PFN_vkVoidFunction)dbg_destroy_messenger;
    else if (!strcmp(name, "vkSubmitDebugUtilsMessageEXT")) f = (PFN_vkVoidFunction)dbg_submit_message;
    if (f) PDIAG("Vulkan function %s missing in the driver: using a no-op stand-in", name);
    return f;
}

static PFN_vkVoidFunction gdpa_get(void* d, const char* n) { return g_gdpa((VkDevice)d, n); }
static PFN_vkVoidFunction gipa_get(void* i, const char* n) { return g_gipa((VkInstance)i, n); }

static PFN_vkVoidFunction VKAPI_CALL gdpa_wrap(VkDevice device, const char* name) {
    if (!g_gdpa || !name) return NULL;
    if (!strcmp(name, "vkGetDeviceProcAddr")) return (PFN_vkVoidFunction)gdpa_wrap;
    PFN_vkVoidFunction f = g_gdpa(device, name);
    if (!f) f = resolve_alias(name, gdpa_get, device);
    if (!f) return debug_utils_stub(name);
    return hook(name, f);
}

static PFN_vkVoidFunction hook(const char* name, PFN_vkVoidFunction f) {
    if (!strcmp(name, "vkCreateSwapchainKHR")) { g_swapchain = (PFN_vkCreateSwapchainKHR)f; return (PFN_vkVoidFunction)swapchain_wrap; }
    if (!strcmp(name, "vkQueuePresentKHR")) { g_present = (PFN_vkQueuePresentKHR)f; return (PFN_vkVoidFunction)present_wrap; }
    if (!strcmp(name, "vkAcquireNextImageKHR")) { g_acquire = (PFN_vkAcquireNextImageKHR)f; return (PFN_vkVoidFunction)acquire_wrap; }
    if (!strcmp(name, "vkAllocateMemory")) { g_allocMem = (PFN_vkAllocateMemory)f; return (PFN_vkVoidFunction)allocmem_wrap; }
    if (!strcmp(name, "vkFreeMemory")) { g_freeMem = (PFN_vkFreeMemory)f; return (PFN_vkVoidFunction)freemem_wrap; }
    if (!strcmp(name, "vkCreateDescriptorPool")) { g_descPool = (PFN_vkCreateDescriptorPool)f; return (PFN_vkVoidFunction)descpool_wrap; }
    if (!strcmp(name, "vkAllocateDescriptorSets")) { g_allocSets = (PFN_vkAllocateDescriptorSets)f; return (PFN_vkVoidFunction)allocsets_wrap; }
    if (!strcmp(name, "vkFreeDescriptorSets")) { g_freeSets = (PFN_vkFreeDescriptorSets)f; return (PFN_vkVoidFunction)freesets_wrap; }
    if (!strcmp(name, "vkResetDescriptorPool")) { g_resetDescPool = (PFN_vkResetDescriptorPool)f; return (PFN_vkVoidFunction)resetdescpool_wrap; }
    if (!strcmp(name, "vkDestroyDescriptorPool")) { g_destroyDescPool = (PFN_vkDestroyDescriptorPool)f; return (PFN_vkVoidFunction)destroydescpool_wrap; }
    if (!strcmp(name, "vkCreateGraphicsPipelines")) { g_gfxPipes = (PFN_vkCreateGraphicsPipelines)f; return (PFN_vkVoidFunction)gfxpipes_wrap; }
    if (!strcmp(name, "vkCreateComputePipelines")) { g_compPipes = (PFN_vkCreateComputePipelines)f; return (PFN_vkVoidFunction)comppipes_wrap; }
    if (!strcmp(name, "vkCreateCommandPool")) { g_createPool = (PFN_vkCreateCommandPool)f; return (PFN_vkVoidFunction)createpool_wrap; }
    if (!strcmp(name, "vkDestroyCommandPool")) { g_destroyPool = (PFN_vkDestroyCommandPool)f; return (PFN_vkVoidFunction)destroypool_wrap; }
    if (!strcmp(name, "vkResetCommandPool")) { g_resetPool = (PFN_vkResetCommandPool)f; return (PFN_vkVoidFunction)resetpool_wrap; }
    if (!strcmp(name, "vkAllocateCommandBuffers")) { g_allocCb = (PFN_vkAllocateCommandBuffers)f; return (PFN_vkVoidFunction)alloccb_wrap; }
    if (!strcmp(name, "vkFreeCommandBuffers")) { g_freeCb = (PFN_vkFreeCommandBuffers)f; return (PFN_vkVoidFunction)freecb_wrap; }
    if (!strcmp(name, "vkCreateQueryPool")) { g_queryPool = (PFN_vkCreateQueryPool)f; return (PFN_vkVoidFunction)querypool_wrap; }
    if (!strcmp(name, "vkCreateImage")) { g_createImage = (PFN_vkCreateImage)f; return (PFN_vkVoidFunction)createimage_wrap; }
    if (!strcmp(name, "vkDestroyImage")) { g_destroyImage = (PFN_vkDestroyImage)f; return (PFN_vkVoidFunction)destroyimage_wrap; }
    if (!strcmp(name, "vkCreateImageView")) { g_createView = (PFN_vkCreateImageView)f; return (PFN_vkVoidFunction)createview_wrap; }
    if (!strcmp(name, "vkCmdCopyBufferToImage")) { g_copyB2I = (PFN_vkCmdCopyBufferToImage)f; return (PFN_vkVoidFunction)copyb2i_wrap; }
    if (!strcmp(name, "vkCmdCopyImage")) { g_copyImage = (PFN_vkCmdCopyImage)f; return (PFN_vkVoidFunction)copyimage_wrap; }
    if (!strcmp(name, "vkCmdBlitImage")) { g_blitImage = (PFN_vkCmdBlitImage)f; return (PFN_vkVoidFunction)blitimage_wrap; }
    if (!strcmp(name, "vkCmdCopyImageToBuffer")) { g_copyI2B = (PFN_vkCmdCopyImageToBuffer)f; return (PFN_vkVoidFunction)copyi2b_wrap; }
    if (!strcmp(name, "vkCmdPipelineBarrier")) { g_barrier = (PFN_vkCmdPipelineBarrier)f; return (PFN_vkVoidFunction)barrier_wrap; }
    if (!strcmp(name, "vkCmdPipelineBarrier2") || !strcmp(name, "vkCmdPipelineBarrier2KHR")) { g_barrier2 = (PFN_vkCmdPipelineBarrier2)f; return (PFN_vkVoidFunction)barrier2_wrap; }
    if (!strcmp(name, "vkCmdCopyBufferToImage2") || !strcmp(name, "vkCmdCopyBufferToImage2KHR")) { g_copyB2I2 = (PFN_vkCmdCopyBufferToImage2)f; return (PFN_vkVoidFunction)copyb2i2_wrap; }
    if (!strcmp(name, "vkCmdCopyImage2") || !strcmp(name, "vkCmdCopyImage2KHR")) { g_copyImage2 = (PFN_vkCmdCopyImage2)f; return (PFN_vkVoidFunction)copyimage2_wrap; }
    if (!strcmp(name, "vkCmdBlitImage2") || !strcmp(name, "vkCmdBlitImage2KHR")) { g_blitImage2 = (PFN_vkCmdBlitImage2)f; return (PFN_vkVoidFunction)blitimage2_wrap; }
    if (!strcmp(name, "vkCmdCopyImageToBuffer2") || !strcmp(name, "vkCmdCopyImageToBuffer2KHR")) { g_copyI2B2 = (PFN_vkCmdCopyImageToBuffer2)f; return (PFN_vkVoidFunction)copyi2b2_wrap; }
    if (!strcmp(name, "vkMapMemory")) { g_map = (PFN_vkMapMemory)f; return (PFN_vkVoidFunction)map_wrap; }
    if (!strcmp(name, "vkQueueSubmit")) { g_submit = (PFN_vkQueueSubmit)f; return (PFN_vkVoidFunction)submit_wrap; }
    if (!strcmp(name, "vkUpdateDescriptorSets")) { g_updateSets = (PFN_vkUpdateDescriptorSets)f; return (PFN_vkVoidFunction)updatesets_wrap; }
    if (!strcmp(name, "vkCreateShaderModule")) { g_shaderModule = (PFN_vkCreateShaderModule)f; return (PFN_vkVoidFunction)shadermodule_wrap; }
    if (!strcmp(name, "vkCreateDescriptorSetLayout")) { g_createDsl = (PFN_vkCreateDescriptorSetLayout)f; return (PFN_vkVoidFunction)dsl_wrap; }
    if (!strcmp(name, "vkCreatePipelineLayout")) { g_createPl = (PFN_vkCreatePipelineLayout)f; return (PFN_vkVoidFunction)pl_wrap; }
    if (!strcmp(name, "vkCmdBindPipeline")) { g_bindPipeline = (PFN_vkCmdBindPipeline)f; return (PFN_vkVoidFunction)bindpipeline_wrap; }
    if (!strcmp(name, "vkCmdPushConstants")) { g_pushConstants = (PFN_vkCmdPushConstants)f; return (PFN_vkVoidFunction)pushconstants_wrap; }
    if (!strcmp(name, "vkCmdBindDescriptorSets")) { g_bindSets = (PFN_vkCmdBindDescriptorSets)f; return (PFN_vkVoidFunction)bindsets_wrap; }
    if (!strcmp(name, "vkCmdBindVertexBuffers")) { g_bindVbs = (PFN_vkCmdBindVertexBuffers)f; return (PFN_vkVoidFunction)bindvbs_wrap; }
    if (!strcmp(name, "vkCmdSetViewport")) { g_setViewport = (PFN_vkCmdSetViewport)f; return (PFN_vkVoidFunction)setviewport_wrap; }
    if (!strcmp(name, "vkCmdSetScissor")) { g_setScissor = (PFN_vkCmdSetScissor)f; return (PFN_vkVoidFunction)setscissor_wrap; }
    if (!strcmp(name, "vkCreateRenderPass")) { g_createRp = (PFN_vkCreateRenderPass)f; return (PFN_vkVoidFunction)createrp_wrap; }
    if (!strcmp(name, "vkCreateSampler")) { g_createSampler = (PFN_vkCreateSampler)f; return (PFN_vkVoidFunction)createsampler_wrap; }
    if (!strcmp(name, "vkGetPhysicalDeviceProperties")) { g_getProps = (PFN_vkGetPhysicalDeviceProperties)f; return (PFN_vkVoidFunction)getprops_wrap; }
    if (!strcmp(name, "vkCreateRenderPass2") || !strcmp(name, "vkCreateRenderPass2KHR")) { g_createRp2 = (PFN_vkCreateRenderPass2)f; return (PFN_vkVoidFunction)createrp2_wrap; }
    if (!strcmp(name, "vkCmdBeginRenderPass")) { g_beginRp = (PFN_vkCmdBeginRenderPass)f; return (PFN_vkVoidFunction)beginrp_wrap; }
    if (!strcmp(name, "vkCmdBeginRenderPass2") || !strcmp(name, "vkCmdBeginRenderPass2KHR")) { g_beginRp2 = (PFN_vkCmdBeginRenderPass2)f; return (PFN_vkVoidFunction)beginrp2_wrap; }
    if (!strcmp(name, "vkCmdEndRenderPass")) { g_endRp = (PFN_vkCmdEndRenderPass)f; return (PFN_vkVoidFunction)endrp_wrap; }
    if (!strcmp(name, "vkQueueSubmit2") || !strcmp(name, "vkQueueSubmit2KHR")) { g_submit2 = (PFN_vkQueueSubmit2)f; return (PFN_vkVoidFunction)submit2_wrap; }
    if (!strcmp(name, "vkWaitForFences")) { g_waitFences = (PFN_vkWaitForFences)f; return (PFN_vkVoidFunction)waitfences_wrap; }
    if (!strcmp(name, "vkDeviceWaitIdle")) { g_waitIdle = (PFN_vkDeviceWaitIdle)f; return (PFN_vkVoidFunction)waitidle_wrap; }
    if (!strcmp(name, "vkCreateBuffer")) { g_createBuffer = (PFN_vkCreateBuffer)f; return (PFN_vkVoidFunction)createbuffer_wrap; }
    if (!strcmp(name, "vkCmdCopyBuffer")) { g_copyBuf = (PFN_vkCmdCopyBuffer)f; return (PFN_vkVoidFunction)copybuf_wrap; }
    if (!strcmp(name, "vkCmdCopyBuffer2") || !strcmp(name, "vkCmdCopyBuffer2KHR")) { g_copyBuf2 = (PFN_vkCmdCopyBuffer2)f; return (PFN_vkVoidFunction)copybuf2_wrap; }
    if (!strcmp(name, "vkBindBufferMemory")) { g_bindBuf = (PFN_vkBindBufferMemory)f; return (PFN_vkVoidFunction)bindbuf_wrap; }
    if (!strcmp(name, "vkBindBufferMemory2") || !strcmp(name, "vkBindBufferMemory2KHR")) { g_bindBuf2 = (PFN_vkBindBufferMemory2)f; return (PFN_vkVoidFunction)bindbuf2_wrap; }
    if (!strcmp(name, "vkDestroyBuffer")) { g_destroyBuf = (PFN_vkDestroyBuffer)f; return (PFN_vkVoidFunction)destroybuf_wrap; }
    if (!strcmp(name, "vkUnmapMemory")) { g_unmap = (PFN_vkUnmapMemory)f; return (PFN_vkVoidFunction)unmap_wrap; }
    if (!strcmp(name, "vkBeginCommandBuffer")) { g_beginCb = (PFN_vkBeginCommandBuffer)f; return (PFN_vkVoidFunction)begincb_wrap; }
    if (!strcmp(name, "vkResetCommandBuffer")) { g_resetCb = (PFN_vkResetCommandBuffer)f; return (PFN_vkVoidFunction)resetcb_wrap; }
    if (!strcmp(name, "vkCreateFramebuffer")) { g_createFb = (PFN_vkCreateFramebuffer)f; return (PFN_vkVoidFunction)createfb_wrap; }
    if (!strcmp(name, "vkCmdDispatch")) { g_dispatch = (PFN_vkCmdDispatch)f; return (PFN_vkVoidFunction)dispatch_wrap; }
    if (!strcmp(name, "vkCmdDraw")) { g_draw = (PFN_vkCmdDraw)f; return (PFN_vkVoidFunction)draw_wrap; }
    if (!strcmp(name, "vkCmdDrawIndexed")) { g_drawIndexed = (PFN_vkCmdDrawIndexed)f; return (PFN_vkVoidFunction)drawindexed_wrap; }
    if (!strcmp(name, "vkCmdDrawIndexedIndirect")) { g_drawIndexedIndirect = (PFN_vkCmdDrawIndexedIndirect)f; return (PFN_vkVoidFunction)drawindexedindirect_wrap; }
    if (!strcmp(name, "vkCmdDrawIndirect")) { g_drawIndirect = (PFN_vkCmdDrawIndirect)f; return (PFN_vkVoidFunction)drawindirect_wrap; }
    return f;
}

API PFN_vkVoidFunction vkGetInstanceProcAddr(VkInstance instance, const char* name) { return gipa_wrap(instance, name); }
API PFN_vkVoidFunction vkGetDeviceProcAddr(VkDevice device, const char* name) {
    if (!g_gdpa) g_gdpa = (PFN_vkGetDeviceProcAddr)real("vkGetDeviceProcAddr");
    return gdpa_wrap(device, name);
}
API VkResult vkCreateInstance(const VkInstanceCreateInfo* info, const VkAllocationCallbacks* alloc, VkInstance* out) {
    return ((PFN_vkCreateInstance)real("vkCreateInstance"))(info, alloc, out);
}
API VkResult vkEnumerateInstanceExtensionProperties(const char* layer, uint32_t* count, VkExtensionProperties* props) {
    return ((PFN_vkEnumerateInstanceExtensionProperties)real("vkEnumerateInstanceExtensionProperties"))(layer, count, props);
}
API VkResult vkEnumerateInstanceLayerProperties(uint32_t* count, VkLayerProperties* props) {
    return ((PFN_vkEnumerateInstanceLayerProperties)real("vkEnumerateInstanceLayerProperties"))(count, props);
}
API VkResult vkEnumerateInstanceVersion(uint32_t* version) {
    PFN_vkEnumerateInstanceVersion f = (PFN_vkEnumerateInstanceVersion)real("vkEnumerateInstanceVersion");
    if (!f) { *version = VK_API_VERSION_1_0; return VK_SUCCESS; }
    return f(version);
}
// The instance-level surface query, in case the engine binds it directly instead of through
// vkGetInstanceProcAddr.
API VkResult vkGetPhysicalDeviceSurfaceCapabilitiesKHR(VkPhysicalDevice pd, VkSurfaceKHR s, VkSurfaceCapabilitiesKHR* c) {
    if (!g_caps) g_caps = (PFN_vkGetPhysicalDeviceSurfaceCapabilitiesKHR)real("vkGetPhysicalDeviceSurfaceCapabilitiesKHR");
    return caps_wrap(pd, s, c);
}
