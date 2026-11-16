// libvkshim.so — an observing Vulkan shim for the native engine.
//
// libunity.so opens Vulkan with dlopen("libvulkan.so"); prepare_unity_module.sh patches that string to
// "libvkshim.so" (same length). This library has the system libvulkan.so as a dependency, so every
// symbol it does not define is found there (dlsym on a handle also searches the handle's
// dependencies): the Android loader and its driver choice stay as they are. It defines the two
// resolvers and the calls on the way from a rendered frame to the screen, logs what they do (tag
// VKSHIM) and passes every call through unchanged: nothing is modified, waited for or retried.
//
// Logged: instance/device creation, Android surfaces, surface capabilities, swapchain creation
// (extent, transform, present mode, images, the retired old swapchain), the first acquires/submits/
// presents of each swapchain and every result that is not plain success, and any call that has not
// returned for half a second, from a thread of its own, so a hang shows too. With RIMDROID_DIAG=1 also
// a summary with counters once a second (off by default: a line a second is noise in a player's log),
// and every 10 seconds the images alive by format and memory (vkCreateImage is wrapped only then).
// Always: which texture compression formats the GPU samples (BC, ETC2, ASTC) and which ones the engine
// turned on: a game built for a PC ships BC textures, which a GPU without BC gets decompressed.
// Written for the Mali phones where Unity keeps rendering but no frame reaches the screen.
#include <android/log.h>
#include <dlfcn.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

#define VK_USE_PLATFORM_ANDROID_KHR
#include <vulkan/vulkan.h>

#define EXPORT __attribute__((visibility("default")))
#define LOG(...) __android_log_print(ANDROID_LOG_INFO, "VKSHIM", __VA_ARGS__)
#define DETAILED_CALLS 6   // full lines for the first calls of each kind per swapchain generation
#define MAX_PROBLEM_LINES 40

// ------------------------------------------------------------------ real entry points

static PFN_vkGetInstanceProcAddr real_gipa;
static PFN_vkGetDeviceProcAddr real_gdpa;

// Instance-level calls we wrap: the system loader's own exports, which dispatch any instance.
static PFN_vkCreateDevice real_create_device;
static PFN_vkCreateAndroidSurfaceKHR real_create_surface;
static PFN_vkDestroySurfaceKHR real_destroy_surface;
static PFN_vkGetPhysicalDeviceSurfaceCapabilitiesKHR real_surface_caps;
static PFN_vkGetPhysicalDeviceFeatures real_gpu_features;
static PFN_vkGetPhysicalDeviceFormatProperties real_format_props;
static PFN_vkGetPhysicalDeviceProperties real_gpu_props;
static PFN_vkGetPhysicalDeviceProperties2 real_gpu_props2;

// Device-level calls, per device. A queue shares its device's dispatch table, so the first word of
// either handle finds the entry.
typedef struct
{
    void* key;
    VkDevice device;
    PFN_vkCreateSwapchainKHR create_swapchain;
    PFN_vkDestroySwapchainKHR destroy_swapchain;
    PFN_vkGetSwapchainImagesKHR swapchain_images;
    PFN_vkAcquireNextImageKHR acquire;
    PFN_vkAcquireNextImage2KHR acquire2;
    PFN_vkQueueSubmit submit;
    PFN_vkQueueSubmit2 submit2;
    PFN_vkQueueSubmit2 submit2khr;
    PFN_vkQueuePresentKHR present;
    PFN_vkWaitForFences wait_fences;
    PFN_vkQueueWaitIdle queue_wait_idle;
    PFN_vkDeviceWaitIdle device_wait_idle;
    PFN_vkCreateImage create_image;
    PFN_vkDestroyImage destroy_image;
    PFN_vkGetImageMemoryRequirements image_requirements;
} DeviceFns;

#define MAX_DEVICES 4
static DeviceFns g_devices[MAX_DEVICES];
static pthread_mutex_t g_devices_lock = PTHREAD_MUTEX_INITIALIZER;
// The loader's exported trampolines: used for a handle from a device this shim did not see created
// (when the engine reached vkCreateDevice past it), so a wrapper always has something to call.
static DeviceFns g_trampolines;

void* vkshim_open_custom_loader(const char* driver_path);   // driver.c

static void load_real(void)
{
    static atomic_bool done;
    if (atomic_load(&done))
        return;
    // RIMDROID_VULKAN_DRIVER: another driver than the phone's, through a private loader (driver.c).
    const char* driver = getenv("RIMDROID_VULKAN_DRIVER");
    void* lib = driver && driver[0] ? vkshim_open_custom_loader(driver) : NULL;
    if (driver && driver[0] && !lib)
        LOG("custom driver: not used, the phone's own driver runs the game");
    if (!lib)
        lib = dlopen("libvulkan.so", RTLD_NOW | RTLD_LOCAL);
#define SYM(name) (lib ? dlsym(lib, name) : NULL)
    real_gipa = (PFN_vkGetInstanceProcAddr)SYM("vkGetInstanceProcAddr");
    real_gdpa = (PFN_vkGetDeviceProcAddr)SYM("vkGetDeviceProcAddr");
    real_create_device = (PFN_vkCreateDevice)SYM("vkCreateDevice");
    real_create_surface = (PFN_vkCreateAndroidSurfaceKHR)SYM("vkCreateAndroidSurfaceKHR");
    real_destroy_surface = (PFN_vkDestroySurfaceKHR)SYM("vkDestroySurfaceKHR");
    real_surface_caps = (PFN_vkGetPhysicalDeviceSurfaceCapabilitiesKHR)SYM("vkGetPhysicalDeviceSurfaceCapabilitiesKHR");
    real_gpu_features = (PFN_vkGetPhysicalDeviceFeatures)SYM("vkGetPhysicalDeviceFeatures");
    real_format_props = (PFN_vkGetPhysicalDeviceFormatProperties)SYM("vkGetPhysicalDeviceFormatProperties");
    real_gpu_props = (PFN_vkGetPhysicalDeviceProperties)SYM("vkGetPhysicalDeviceProperties");
    real_gpu_props2 = (PFN_vkGetPhysicalDeviceProperties2)SYM("vkGetPhysicalDeviceProperties2");
    DeviceFns* t = &g_trampolines;
    t->create_swapchain = (PFN_vkCreateSwapchainKHR)SYM("vkCreateSwapchainKHR");
    t->destroy_swapchain = (PFN_vkDestroySwapchainKHR)SYM("vkDestroySwapchainKHR");
    t->swapchain_images = (PFN_vkGetSwapchainImagesKHR)SYM("vkGetSwapchainImagesKHR");
    t->acquire = (PFN_vkAcquireNextImageKHR)SYM("vkAcquireNextImageKHR");
    t->acquire2 = (PFN_vkAcquireNextImage2KHR)SYM("vkAcquireNextImage2KHR");
    t->submit = (PFN_vkQueueSubmit)SYM("vkQueueSubmit");
    t->submit2 = (PFN_vkQueueSubmit2)SYM("vkQueueSubmit2");
    t->submit2khr = (PFN_vkQueueSubmit2)SYM("vkQueueSubmit2KHR");
    t->present = (PFN_vkQueuePresentKHR)SYM("vkQueuePresentKHR");
    t->wait_fences = (PFN_vkWaitForFences)SYM("vkWaitForFences");
    t->queue_wait_idle = (PFN_vkQueueWaitIdle)SYM("vkQueueWaitIdle");
    t->device_wait_idle = (PFN_vkDeviceWaitIdle)SYM("vkDeviceWaitIdle");
    t->create_image = (PFN_vkCreateImage)SYM("vkCreateImage");
    t->destroy_image = (PFN_vkDestroyImage)SYM("vkDestroyImage");
    t->image_requirements = (PFN_vkGetImageMemoryRequirements)SYM("vkGetImageMemoryRequirements");
#undef SYM
    LOG("loaded: system libvulkan %p, vkGetInstanceProcAddr %p", lib, (void*)real_gipa);
    atomic_store(&done, true);
}

static void* dispatch_key(const void* handle)
{
    return handle ? *(void* const*)handle : NULL;
}

static DeviceFns* device_fns(const void* handle)
{
    void* key = dispatch_key(handle);
    for (int i = 0; key && i < MAX_DEVICES; i++)
        if (g_devices[i].key == key)
            return &g_devices[i];
    load_real();
    return &g_trampolines;
}

// ------------------------------------------------------------------ counters and in-flight calls

static uint64_t now_ns(void)
{
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint64_t)ts.tv_sec * 1000000000ull + (uint64_t)ts.tv_nsec;
}

typedef struct
{
    _Atomic uint64_t calls, ok, suboptimal, timeout, not_ready, out_of_date, surface_lost, other;
    _Atomic uint64_t last_ok_ns;
} Counter;

static Counter c_acquire, c_submit, c_present, c_wait;
static _Atomic uint64_t g_swapchains_created, g_swapchain_generation, g_problem_lines;
static _Atomic uint64_t g_detail_acquire, g_detail_submit, g_detail_present;

static void count(Counter* c, VkResult r)
{
    // A lost device takes no more work: from here on the screen shows the last frame drawn before it.
    static atomic_bool device_lost;
    if (r == VK_ERROR_DEVICE_LOST && !atomic_exchange(&device_lost, true))
        LOG("DEVICE LOST: the GPU no longer takes work, the picture stays on the last frame drawn");
    atomic_fetch_add(&c->calls, 1);
    switch (r)
    {
    case VK_SUCCESS: atomic_fetch_add(&c->ok, 1); atomic_store(&c->last_ok_ns, now_ns()); break;
    case VK_SUBOPTIMAL_KHR: atomic_fetch_add(&c->suboptimal, 1); atomic_store(&c->last_ok_ns, now_ns()); break;
    case VK_TIMEOUT: atomic_fetch_add(&c->timeout, 1); break;
    case VK_NOT_READY: atomic_fetch_add(&c->not_ready, 1); break;
    case VK_ERROR_OUT_OF_DATE_KHR: atomic_fetch_add(&c->out_of_date, 1); break;
    case VK_ERROR_SURFACE_LOST_KHR: atomic_fetch_add(&c->surface_lost, 1); break;
    default: atomic_fetch_add(&c->other, 1); break;
    }
}

static bool problem(VkResult r)
{
    // SUBOPTIMAL is every present here (Unity presents unrotated to a rotated surface): counted, not a problem line.
    if (r == VK_SUCCESS || r == VK_SUBOPTIMAL_KHR)
        return false;
    return atomic_fetch_add(&g_problem_lines, 1) < MAX_PROBLEM_LINES;
}

static bool detailed(_Atomic uint64_t* n)
{
    return atomic_fetch_add(n, 1) < DETAILED_CALLS;
}

// A call that may block: which one, on which thread, since when. The watchdog reports the old ones.
#define MAX_SLOTS 32
typedef struct
{
    _Atomic(const char*) fn;
    _Atomic uint64_t since;
    _Atomic int tid;
} Slot;
static Slot g_slots[MAX_SLOTS];

static Slot* enter(const char* fn)
{
    int tid = gettid();
    for (int i = 0; i < MAX_SLOTS; i++)
    {
        const char* expected = NULL;
        if (atomic_compare_exchange_strong(&g_slots[i].fn, &expected, fn))
        {
            atomic_store(&g_slots[i].since, now_ns());
            atomic_store(&g_slots[i].tid, tid);
            return &g_slots[i];
        }
    }
    return NULL;
}

static void leave(Slot* s)
{
    if (!s)
        return;
    atomic_store(&s->since, 0);   // before the slot is free, so its next user never shows this call's time
    atomic_store(&s->fn, NULL);
}

static bool diag_on(void)
{
    static int on = -1;
    if (on < 0)
    {
        const char* v = getenv("RIMDROID_DIAG");
        on = v && strcmp(v, "1") == 0;
    }
    return on;
}

// ------------------------------------------------------------------ images alive (RIMDROID_DIAG only)
// Every image the engine creates, by format, with the memory the driver asks for it. Textures and
// render targets (attachments) apart: textures in BC formats on a GPU without BC never show up as BC,
// they come as RGBA here, and their bytes tell what that costs.

#define MAX_FORMAT 192   // the core formats; a higher one (extensions) counts as "other"
#define IMAGE_SLOTS 65536
typedef struct
{
    uint64_t image;      // 0 = free, 1 = deleted
    uint64_t bytes;
    uint16_t format;
    uint8_t attachment;
} ImageEntry;
static ImageEntry g_images[IMAGE_SLOTS];
static uint64_t g_image_count[2][MAX_FORMAT + 1], g_image_bytes[2][MAX_FORMAT + 1];
static bool g_images_changed;
static pthread_mutex_t g_images_lock = PTHREAD_MUTEX_INITIALIZER;

static uint32_t image_slot(uint64_t image)
{
    return (uint32_t)((image * 0x9E3779B97F4A7C15ull) >> 48) & (IMAGE_SLOTS - 1);
}

static void images_add(uint64_t image, uint32_t format, uint64_t bytes, bool attachment)
{
    uint32_t f = format <= MAX_FORMAT ? format : MAX_FORMAT;
    pthread_mutex_lock(&g_images_lock);
    for (uint32_t i = image_slot(image), n = 0; n < IMAGE_SLOTS; i = (i + 1) & (IMAGE_SLOTS - 1), n++)
    {
        if (g_images[i].image > 1)
            continue;
        g_images[i] = (ImageEntry){ image, bytes, (uint16_t)f, attachment };
        g_image_count[attachment][f]++;
        g_image_bytes[attachment][f] += bytes;
        g_images_changed = true;
        break;
    }
    pthread_mutex_unlock(&g_images_lock);
}

static void images_remove(uint64_t image)
{
    pthread_mutex_lock(&g_images_lock);
    for (uint32_t i = image_slot(image), n = 0; n < IMAGE_SLOTS && g_images[i].image; i = (i + 1) & (IMAGE_SLOTS - 1), n++)
    {
        if (g_images[i].image != image)
            continue;
        ImageEntry* e = &g_images[i];
        g_image_count[e->attachment][e->format]--;
        g_image_bytes[e->attachment][e->format] -= e->bytes;
        e->image = 1;
        g_images_changed = true;
        break;
    }
    pthread_mutex_unlock(&g_images_lock);
}

static const char* format_name(uint32_t f)
{
    switch (f)
    {
    case VK_FORMAT_R8_UNORM: return "R8";
    case VK_FORMAT_R8G8_UNORM: return "RG8";
    case VK_FORMAT_R8G8B8A8_UNORM: return "RGBA8";
    case VK_FORMAT_R8G8B8A8_SRGB: return "RGBA8_SRGB";
    case VK_FORMAT_B8G8R8A8_UNORM: return "BGRA8";
    case VK_FORMAT_B8G8R8A8_SRGB: return "BGRA8_SRGB";
    case VK_FORMAT_A2B10G10R10_UNORM_PACK32: return "RGB10A2";
    case VK_FORMAT_R16_SFLOAT: return "R16F";
    case VK_FORMAT_R16G16B16A16_SFLOAT: return "RGBA16F";
    case VK_FORMAT_R32_SFLOAT: return "R32F";
    case VK_FORMAT_B10G11R11_UFLOAT_PACK32: return "RG11B10F";
    case VK_FORMAT_D16_UNORM: return "D16";
    case VK_FORMAT_D32_SFLOAT: return "D32";
    case VK_FORMAT_D24_UNORM_S8_UINT: return "D24S8";
    case VK_FORMAT_D32_SFLOAT_S8_UINT: return "D32S8";
    case VK_FORMAT_BC1_RGB_UNORM_BLOCK: case VK_FORMAT_BC1_RGB_SRGB_BLOCK:
    case VK_FORMAT_BC1_RGBA_UNORM_BLOCK: case VK_FORMAT_BC1_RGBA_SRGB_BLOCK: return "BC1";
    case VK_FORMAT_BC3_UNORM_BLOCK: case VK_FORMAT_BC3_SRGB_BLOCK: return "BC3";
    case VK_FORMAT_BC4_UNORM_BLOCK: case VK_FORMAT_BC4_SNORM_BLOCK: return "BC4";
    case VK_FORMAT_BC5_UNORM_BLOCK: case VK_FORMAT_BC5_SNORM_BLOCK: return "BC5";
    case VK_FORMAT_BC6H_UFLOAT_BLOCK: case VK_FORMAT_BC6H_SFLOAT_BLOCK: return "BC6H";
    case VK_FORMAT_BC7_UNORM_BLOCK: case VK_FORMAT_BC7_SRGB_BLOCK: return "BC7";
    case VK_FORMAT_ETC2_R8G8B8_UNORM_BLOCK: case VK_FORMAT_ETC2_R8G8B8_SRGB_BLOCK: return "ETC2";
    case VK_FORMAT_ETC2_R8G8B8A8_UNORM_BLOCK: case VK_FORMAT_ETC2_R8G8B8A8_SRGB_BLOCK: return "ETC2A";
    case VK_FORMAT_ASTC_4x4_UNORM_BLOCK: case VK_FORMAT_ASTC_4x4_SRGB_BLOCK: return "ASTC4";
    case VK_FORMAT_ASTC_6x6_UNORM_BLOCK: case VK_FORMAT_ASTC_6x6_SRGB_BLOCK: return "ASTC6";
    case MAX_FORMAT: return "other";
    default: return NULL;
    }
}

// "textures 812, 1234.5 MB: RGBA8_SRGB 300 640.0 MB, ..." — the biggest formats first, up to 8.
static void images_line(char* out, size_t size, int attachment)
{
    uint64_t count[MAX_FORMAT + 1], bytes[MAX_FORMAT + 1], total_count = 0, total_bytes = 0;
    pthread_mutex_lock(&g_images_lock);
    memcpy(count, g_image_count[attachment], sizeof(count));
    memcpy(bytes, g_image_bytes[attachment], sizeof(bytes));
    pthread_mutex_unlock(&g_images_lock);
    for (int f = 0; f <= MAX_FORMAT; f++)
    {
        total_count += count[f];
        total_bytes += bytes[f];
    }
    size_t len = (size_t)snprintf(out, size, "%s %llu, %.1f MB:", attachment ? "render targets" : "textures",
                                  (unsigned long long)total_count, total_bytes / 1048576.0);
    for (int shown = 0; shown < 8 && len < size - 48; shown++)
    {
        int best = -1;
        for (int f = 0; f <= MAX_FORMAT; f++)
            if (count[f] && (best < 0 || bytes[f] > bytes[best]))
                best = f;
        if (best < 0)
            break;
        const char* name = format_name((uint32_t)best);
        char fallback[16];
        if (!name)
        {
            snprintf(fallback, sizeof(fallback), "fmt %d", best);
            name = fallback;
        }
        len += (size_t)snprintf(out + len, size - len, " %s %llu %.1f MB,", name, (unsigned long long)count[best],
                                bytes[best] / 1048576.0);
        count[best] = 0;
    }
    if (len && out[len - 1] == ',')
        out[len - 1] = '\0';
}

static VKAPI_ATTR VkResult VKAPI_CALL shim_CreateImage(VkDevice device, const VkImageCreateInfo* info,
                                                      const VkAllocationCallbacks* alloc, VkImage* image)
{
    DeviceFns* f = device_fns(device);
    VkResult r = f->create_image(device, info, alloc, image);
    if (r == VK_SUCCESS && diag_on() && f->image_requirements)
    {
        VkMemoryRequirements req = { 0 };
        f->image_requirements(device, *image, &req);
        bool attachment = (info->usage & (VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT |
                                          VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT |
                                          VK_IMAGE_USAGE_STORAGE_BIT)) != 0;
        images_add((uint64_t)*image, (uint32_t)info->format, req.size, attachment);
    }
    return r;
}

static VKAPI_ATTR void VKAPI_CALL shim_DestroyImage(VkDevice device, VkImage image, const VkAllocationCallbacks* alloc)
{
    if (image && diag_on())
        images_remove((uint64_t)image);
    device_fns(device)->destroy_image(device, image, alloc);
}

// What the GPU can sample and what the engine turned on, once per device.
static void log_texture_compression(VkPhysicalDevice gpu, const VkDeviceCreateInfo* info)
{
    if (real_gpu_props)
    {
        VkPhysicalDeviceProperties props;
        memset(&props, 0, sizeof(props));
        real_gpu_props(gpu, &props);
        LOG("GPU: %s, vendor 0x%x, device 0x%x, driver version 0x%x, Vulkan %u.%u.%u", props.deviceName,
            props.vendorID, props.deviceID, props.driverVersion, VK_VERSION_MAJOR(props.apiVersion),
            VK_VERSION_MINOR(props.apiVersion), VK_VERSION_PATCH(props.apiVersion));
        // The driver's own name and release string (Vulkan 1.2): driverVersion above is vendor-encoded
        // and does not tell which release a phone runs.
        if (real_gpu_props2 && props.apiVersion >= VK_API_VERSION_1_2)
        {
            VkPhysicalDeviceDriverProperties drv;
            memset(&drv, 0, sizeof(drv));
            drv.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DRIVER_PROPERTIES;
            VkPhysicalDeviceProperties2 props2;
            memset(&props2, 0, sizeof(props2));
            props2.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2;
            props2.pNext = &drv;
            real_gpu_props2(gpu, &props2);
            LOG("driver: %s, %s (id %d, conformance %u.%u.%u.%u)", drv.driverName, drv.driverInfo,
                (int)drv.driverID, drv.conformanceVersion.major, drv.conformanceVersion.minor,
                drv.conformanceVersion.subminor, drv.conformanceVersion.patch);
        }
    }
    if (!real_gpu_features || !real_format_props)
        return;
    VkPhysicalDeviceFeatures have;
    memset(&have, 0, sizeof(have));
    real_gpu_features(gpu, &have);
    const VkPhysicalDeviceFeatures* on = info ? info->pEnabledFeatures : NULL;
    VkFormatProperties bc3;
    memset(&bc3, 0, sizeof(bc3));
    real_format_props(gpu, VK_FORMAT_BC3_UNORM_BLOCK, &bc3);
    LOG("texture compression: BC %s (engine %s), ETC2 %s (engine %s), ASTC %s (engine %s); BC3 sampled %s",
        have.textureCompressionBC ? "yes" : "NO", on ? (on->textureCompressionBC ? "on" : "off") : "?",
        have.textureCompressionETC2 ? "yes" : "NO", on ? (on->textureCompressionETC2 ? "on" : "off") : "?",
        have.textureCompressionASTC_LDR ? "yes" : "NO", on ? (on->textureCompressionASTC_LDR ? "on" : "off") : "?",
        (bc3.optimalTilingFeatures & VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT) ? "yes" : "no");
}

static void* watchdog(void* arg)
{
    bool summaries = diag_on();
    int seconds = 0;
    uint64_t last_calls = UINT64_MAX;
    int quiet = 0;
    for (;;)
    {
        sleep(1);
        if (summaries && ++seconds % 10 == 0)
        {
            pthread_mutex_lock(&g_images_lock);
            bool changed = g_images_changed;
            g_images_changed = false;
            pthread_mutex_unlock(&g_images_lock);
            if (changed)
            {
                char line[512];
                images_line(line, sizeof(line), 0);
                LOG("images: %s", line);
                images_line(line, sizeof(line), 1);
                LOG("images: %s", line);
            }
        }
        uint64_t t = now_ns();
        uint64_t calls = atomic_load(&c_acquire.calls) + atomic_load(&c_submit.calls) + atomic_load(&c_present.calls);
        char stuck[256] = "";
        size_t len = 0;
        for (int i = 0; i < MAX_SLOTS; i++)
        {
            const char* fn = atomic_load(&g_slots[i].fn);
            uint64_t since = atomic_load(&g_slots[i].since);
            // A slot just claimed may have no start time yet (0) or one newer than t: not pending long.
            if (fn && since && since < t && t - since > 500000000ull && len < sizeof(stuck) - 64)
                len += (size_t)snprintf(stuck + len, sizeof(stuck) - len, " %s(tid %d, %llu ms)", fn,
                                        atomic_load(&g_slots[i].tid), (unsigned long long)((t - since) / 1000000));
        }
        // Without RIMDROID_DIAG only a hang is worth a line.
        if (!summaries && !len)
            continue;
        // Quiet when nothing moves and nothing hangs (the game in the background).
        if (calls == last_calls && !len)
        {
            if (++quiet == 3)
                LOG("summary: no Vulkan activity");
            continue;
        }
        quiet = 0;
        last_calls = calls;
        uint64_t last_present = atomic_load(&c_present.last_ok_ns);
        LOG("summary: acquire %llu (ok %llu, subopt %llu, timeout %llu, not ready %llu, out of date %llu, other %llu)"
            " | submit %llu (fail %llu) | present %llu (ok %llu, subopt %llu, out of date %llu, lost %llu, other %llu),"
            " last ok present %lld ms ago | fence waits %llu (timeout %llu) | swapchains %llu |%s",
            (unsigned long long)c_acquire.calls, (unsigned long long)c_acquire.ok, (unsigned long long)c_acquire.suboptimal,
            (unsigned long long)c_acquire.timeout, (unsigned long long)c_acquire.not_ready,
            (unsigned long long)c_acquire.out_of_date, (unsigned long long)(c_acquire.other + c_acquire.surface_lost),
            (unsigned long long)c_submit.calls, (unsigned long long)(c_submit.calls - c_submit.ok),
            (unsigned long long)c_present.calls, (unsigned long long)c_present.ok, (unsigned long long)c_present.suboptimal,
            (unsigned long long)c_present.out_of_date, (unsigned long long)c_present.surface_lost,
            (unsigned long long)c_present.other,
            last_present ? (long long)((t - last_present) / 1000000) : -1ll,
            (unsigned long long)c_wait.calls, (unsigned long long)c_wait.timeout,
            (unsigned long long)g_swapchains_created, len ? stuck : " nothing pending");
    }
    return NULL;
}

static void start_watchdog(void)
{
    static atomic_bool started;
    if (atomic_exchange(&started, true))
        return;
    pthread_t t;
    if (pthread_create(&t, NULL, watchdog, NULL) == 0)
        pthread_detach(t);
}

// ------------------------------------------------------------------ instance level

static VKAPI_ATTR VkResult VKAPI_CALL shim_CreateDevice(VkPhysicalDevice gpu, const VkDeviceCreateInfo* info,
                                                       const VkAllocationCallbacks* alloc, VkDevice* device)
{
    load_real();
    VkResult r = real_create_device(gpu, info, alloc, device);
    LOG("vkCreateDevice -> %d, device %p, %u extensions", r, r == VK_SUCCESS ? (void*)*device : NULL,
        info ? info->enabledExtensionCount : 0);
    for (uint32_t i = 0; info && i < info->enabledExtensionCount; i++)
        LOG("  device extension %s", info->ppEnabledExtensionNames[i]);
    if (r != VK_SUCCESS)
        return r;
    log_texture_compression(gpu, info);
    pthread_mutex_lock(&g_devices_lock);
    DeviceFns* f = NULL;
    for (int i = 0; i < MAX_DEVICES && !f; i++)
        if (!g_devices[i].key)
            f = &g_devices[i];
    if (f)
    {
        VkDevice d = *device;
        f->device = d;
#define GET(field, name) f->field = real_gdpa(d, name) ? (void*)real_gdpa(d, name) : (void*)g_trampolines.field
        GET(create_swapchain, "vkCreateSwapchainKHR");
        GET(destroy_swapchain, "vkDestroySwapchainKHR");
        GET(swapchain_images, "vkGetSwapchainImagesKHR");
        GET(acquire, "vkAcquireNextImageKHR");
        GET(acquire2, "vkAcquireNextImage2KHR");
        GET(submit, "vkQueueSubmit");
        GET(submit2, "vkQueueSubmit2");
        GET(submit2khr, "vkQueueSubmit2KHR");
        GET(present, "vkQueuePresentKHR");
        GET(wait_fences, "vkWaitForFences");
        GET(queue_wait_idle, "vkQueueWaitIdle");
        GET(create_image, "vkCreateImage");
        GET(destroy_image, "vkDestroyImage");
        GET(image_requirements, "vkGetImageMemoryRequirements");
        GET(device_wait_idle, "vkDeviceWaitIdle");
#undef GET
        f->key = dispatch_key(d);
    }
    else
        LOG("more than %d devices: device %p is not observed", MAX_DEVICES, (void*)*device);
    pthread_mutex_unlock(&g_devices_lock);
    start_watchdog();
    return r;
}

static VKAPI_ATTR VkResult VKAPI_CALL shim_CreateAndroidSurfaceKHR(VkInstance instance,
                                                                  const VkAndroidSurfaceCreateInfoKHR* info,
                                                                  const VkAllocationCallbacks* alloc, VkSurfaceKHR* surface)
{
    load_real();
    VkResult r = real_create_surface(instance, info, alloc, surface);
    LOG("vkCreateAndroidSurfaceKHR(window %p) -> %d, surface 0x%llx", info ? (void*)info->window : NULL, r,
        r == VK_SUCCESS ? (unsigned long long)*surface : 0ull);
    return r;
}

static VKAPI_ATTR void VKAPI_CALL shim_DestroySurfaceKHR(VkInstance instance, VkSurfaceKHR surface,
                                                        const VkAllocationCallbacks* alloc)
{
    load_real();
    LOG("vkDestroySurfaceKHR(0x%llx)", (unsigned long long)surface);
    real_destroy_surface(instance, surface, alloc);
}

static VKAPI_ATTR VkResult VKAPI_CALL shim_GetPhysicalDeviceSurfaceCapabilitiesKHR(VkPhysicalDevice gpu,
                                                                                 VkSurfaceKHR surface,
                                                                                 VkSurfaceCapabilitiesKHR* caps)
{
    load_real();
    VkResult r = real_surface_caps(gpu, surface, caps);
    // Logged when something changes, not on every call (engines ask each frame).
    static VkSurfaceCapabilitiesKHR last;
    static VkResult last_r = VK_RESULT_MAX_ENUM;
    if (r != last_r || (r == VK_SUCCESS && memcmp(&last, caps, sizeof(last))))
    {
        last_r = r;
        if (r == VK_SUCCESS)
            last = *caps;
        LOG("surface caps(0x%llx) -> %d: current %ux%u, min %ux%u, max %ux%u, images %u..%u, current transform 0x%x,"
            " supported transforms 0x%x, composite alpha 0x%x, usage 0x%x",
            (unsigned long long)surface, r, caps->currentExtent.width, caps->currentExtent.height,
            caps->minImageExtent.width, caps->minImageExtent.height, caps->maxImageExtent.width,
            caps->maxImageExtent.height, caps->minImageCount, caps->maxImageCount, caps->currentTransform,
            caps->supportedTransforms, caps->supportedCompositeAlpha, caps->supportedUsageFlags);
    }
    return r;
}

// ------------------------------------------------------------------ device level

// When a driver refuses the engine's swapchain (PanVK on a Mali-G615: VK_ERROR_INVALID_EXTERNAL_HANDLE,
// 2026-10-07), the same request is tried in variants, each result logged, so the driver's author learns
// exactly what it does not accept. The first variant that works is kept: the engine then gets a swapchain
// instead of none. Variants that keep the engine's format come first; a UNORM swapchain is created mutable
// with the sRGB format listed, since the engine makes sRGB views of its images.
typedef struct
{
    const char* name;
    VkFormat format;               // VK_FORMAT_UNDEFINED = the engine's
    VkImageUsageFlags usage;       // 0 = the engine's
    VkCompositeAlphaFlagBitsKHR alpha;   // 0 = the engine's
    bool mutable_srgb;
} SwapchainVariant;

static VkResult swapchain_variants(DeviceFns* f, VkDevice device, const VkSwapchainCreateInfoKHR* info,
                                   const VkAllocationCallbacks* alloc, VkSwapchainKHR* swapchain, VkResult original)
{
    static const SwapchainVariant variants[] = {
        { "color attachment usage only", VK_FORMAT_UNDEFINED, VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT, 0, false },
        { "opaque composite alpha", VK_FORMAT_UNDEFINED, 0, VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR, false },
        { "both of those", VK_FORMAT_UNDEFINED, VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT, VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR, false },
        { "UNORM, mutable to sRGB", VK_FORMAT_R8G8B8A8_UNORM, 0, 0, true },
        { "UNORM", VK_FORMAT_R8G8B8A8_UNORM, 0, 0, false },
        { "UNORM, color attachment usage only", VK_FORMAT_R8G8B8A8_UNORM, VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT, 0, false },
        { "BGRA UNORM", VK_FORMAT_B8G8R8A8_UNORM, 0, 0, false },
    };
    VkSwapchainKHR kept = VK_NULL_HANDLE;
    const char* kept_name = NULL;
    for (size_t i = 0; i < sizeof(variants) / sizeof(variants[0]); i++)
    {
        const SwapchainVariant* v = &variants[i];
        VkSwapchainCreateInfoKHR ci = *info;
        if (v->format != VK_FORMAT_UNDEFINED)
            ci.imageFormat = v->format;
        if (v->usage)
            ci.imageUsage = v->usage;
        if (v->alpha)
            ci.compositeAlpha = v->alpha;
        VkFormat both[2] = { VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_R8G8B8A8_SRGB };
        VkImageFormatListCreateInfo list = { VK_STRUCTURE_TYPE_IMAGE_FORMAT_LIST_CREATE_INFO, ci.pNext, 2, both };
        if (v->mutable_srgb)
        {
            ci.flags |= VK_SWAPCHAIN_CREATE_MUTABLE_FORMAT_BIT_KHR;
            ci.pNext = &list;
        }
        VkSwapchainKHR sc = VK_NULL_HANDLE;
        VkResult r = f->create_swapchain(device, &ci, alloc, &sc);
        LOG("swapchain variant '%s' -> %d", v->name, r);
        if (r != VK_SUCCESS)
            continue;
        if (!kept)
        {
            kept = sc;
            kept_name = v->name;
        }
        else
        {
            f->destroy_swapchain(device, sc, alloc);
        }
    }
    if (!kept)
    {
        LOG("swapchain: no variant works either");
        return original;
    }
    LOG("swapchain: using the variant '%s', swapchain 0x%llx", kept_name, (unsigned long long)kept);
    *swapchain = kept;
    return VK_SUCCESS;
}

static VKAPI_ATTR VkResult VKAPI_CALL shim_CreateSwapchainKHR(VkDevice device, const VkSwapchainCreateInfoKHR* info,
                                                             const VkAllocationCallbacks* alloc, VkSwapchainKHR* swapchain)
{
    DeviceFns* f = device_fns(device);
    VkResult r = f->create_swapchain(device, info, alloc, swapchain);
    atomic_fetch_add(&g_swapchains_created, 1);
    atomic_fetch_add(&g_swapchain_generation, 1);
    atomic_store(&g_detail_acquire, 0);
    atomic_store(&g_detail_submit, 0);
    atomic_store(&g_detail_present, 0);
    LOG("vkCreateSwapchainKHR(surface 0x%llx, old 0x%llx) -> %d, swapchain 0x%llx: %ux%u, format %d, color space %d,"
        " min images %u, pre-transform 0x%x, composite alpha 0x%x, present mode %d, usage 0x%x, clipped %u",
        (unsigned long long)info->surface, (unsigned long long)info->oldSwapchain, r,
        r == VK_SUCCESS ? (unsigned long long)*swapchain : 0ull, info->imageExtent.width, info->imageExtent.height,
        info->imageFormat, info->imageColorSpace, info->minImageCount, info->preTransform, info->compositeAlpha,
        info->presentMode, info->imageUsage, info->clipped);
    if (r < 0)
        r = swapchain_variants(f, device, info, alloc, swapchain, r);
    return r;
}

static VKAPI_ATTR void VKAPI_CALL shim_DestroySwapchainKHR(VkDevice device, VkSwapchainKHR swapchain,
                                                          const VkAllocationCallbacks* alloc)
{
    LOG("vkDestroySwapchainKHR(0x%llx)", (unsigned long long)swapchain);
    device_fns(device)->destroy_swapchain(device, swapchain, alloc);
}

static VKAPI_ATTR VkResult VKAPI_CALL shim_GetSwapchainImagesKHR(VkDevice device, VkSwapchainKHR swapchain,
                                                                uint32_t* count, VkImage* images)
{
    VkResult r = device_fns(device)->swapchain_images(device, swapchain, count, images);
    if (images)
        LOG("vkGetSwapchainImagesKHR(0x%llx) -> %d, %u images", (unsigned long long)swapchain, r, count ? *count : 0);
    return r;
}

static VKAPI_ATTR VkResult VKAPI_CALL shim_AcquireNextImageKHR(VkDevice device, VkSwapchainKHR swapchain, uint64_t timeout,
                                                              VkSemaphore semaphore, VkFence fence, uint32_t* index)
{
    Slot* s = enter("vkAcquireNextImageKHR");
    uint64_t t0 = now_ns();
    VkResult r = device_fns(device)->acquire(device, swapchain, timeout, semaphore, fence, index);
    uint64_t ms = (now_ns() - t0) / 1000000;
    leave(s);
    count(&c_acquire, r);
    if (detailed(&g_detail_acquire) || problem(r))
        LOG("vkAcquireNextImageKHR(0x%llx, timeout %lld, semaphore 0x%llx, fence 0x%llx) -> %d, image %d, %llu ms",
            (unsigned long long)swapchain, (long long)timeout, (unsigned long long)semaphore,
            (unsigned long long)fence, r, (r == VK_SUCCESS || r == VK_SUBOPTIMAL_KHR) ? (int)*index : -1,
            (unsigned long long)ms);
    return r;
}

static VKAPI_ATTR VkResult VKAPI_CALL shim_AcquireNextImage2KHR(VkDevice device, const VkAcquireNextImageInfoKHR* info,
                                                               uint32_t* index)
{
    Slot* s = enter("vkAcquireNextImage2KHR");
    uint64_t t0 = now_ns();
    VkResult r = device_fns(device)->acquire2(device, info, index);
    uint64_t ms = (now_ns() - t0) / 1000000;
    leave(s);
    count(&c_acquire, r);
    if (detailed(&g_detail_acquire) || problem(r))
        LOG("vkAcquireNextImage2KHR(0x%llx, timeout %lld) -> %d, image %d, %llu ms",
            (unsigned long long)info->swapchain, (long long)info->timeout, r,
            (r == VK_SUCCESS || r == VK_SUBOPTIMAL_KHR) ? (int)*index : -1, (unsigned long long)ms);
    return r;
}

// Mali drivers have a fixed-size kernel command queue. During a long loading step no frame is presented,
// and without secondary command buffers (our Mali compatibility mode) Unity queues every upload as its own
// primary command buffer and submits them all at once: 2724 in one vkQueueSubmit with a heavy mod list on
// a Mali-G615 (2026-11-16). The driver then logs "waiting for contiguous free space fail", returns 2, runs
// nothing, the frame's fence is never signalled and the game freezes on the loading screen. So a large
// submit is split into chunks, and a refused submit is retried once after the queue drains.
#define SUBMIT_CHUNK 256

static VkResult submit_retry(DeviceFns* f, VkQueue queue, uint32_t n, const VkSubmitInfo* submits, VkFence fence)
{
    VkResult r = f->submit(queue, n, submits, fence);
    if (r > 0)   // not a valid vkQueueSubmit result: the driver did not take the work
    {
        VkResult idle = f->queue_wait_idle(queue);
        VkResult again = f->submit(queue, n, submits, fence);
        LOG("vkQueueSubmit -> %d, retried after the queue drained (wait idle -> %d) -> %d", r, idle, again);
        r = again;
    }
    return r;
}

// Each chunk runs only after the previous one finished (queue wait idle), so a chunk that does not carry
// the original waits still starts after them. Waits go with the first chunk of a batch, signals with its
// last, the fence with the very last. Batches with a pNext chain (timeline values, device groups) are
// never split, as their arrays are sized by the original counts.
static VkResult submit_split(DeviceFns* f, VkQueue queue, uint32_t n, const VkSubmitInfo* submits, VkFence fence)
{
    uint32_t total = 0;
    for (uint32_t i = 0; i < n; i++)
    {
        if (submits[i].pNext)
            return submit_retry(f, queue, n, submits, fence);
        total += submits[i].commandBufferCount;
    }
    if (total <= SUBMIT_CHUNK)
        return submit_retry(f, queue, n, submits, fence);
    LOG("vkQueueSubmit: %u command buffers in %u submits, sent in chunks of %u", total, n, SUBMIT_CHUNK);
    for (uint32_t i = 0; i < n; i++)
    {
        const VkSubmitInfo* s = &submits[i];
        uint32_t done = 0;
        do
        {
            uint32_t take = s->commandBufferCount - done;
            if (take > SUBMIT_CHUNK)
                take = SUBMIT_CHUNK;
            bool first = done == 0, last = done + take == s->commandBufferCount;
            VkSubmitInfo part = *s;
            part.commandBufferCount = take;
            part.pCommandBuffers = s->pCommandBuffers ? s->pCommandBuffers + done : NULL;
            if (!first)
            {
                part.waitSemaphoreCount = 0;
                part.pWaitSemaphores = NULL;
                part.pWaitDstStageMask = NULL;
            }
            if (!last)
            {
                part.signalSemaphoreCount = 0;
                part.pSignalSemaphores = NULL;
            }
            bool final_part = last && i == n - 1;
            VkResult r = submit_retry(f, queue, 1, &part, final_part ? fence : VK_NULL_HANDLE);
            if (r != VK_SUCCESS)
            {
                LOG("vkQueueSubmit chunk (batch %u, buffers %u..%u) -> %d", i, done, done + take, r);
                return r;
            }
            if (!final_part)
                f->queue_wait_idle(queue);
            done += take;
        } while (done < s->commandBufferCount);
    }
    return VK_SUCCESS;
}

static VKAPI_ATTR VkResult VKAPI_CALL shim_QueueSubmit(VkQueue queue, uint32_t n, const VkSubmitInfo* submits, VkFence fence)
{
    VkResult r = submit_split(device_fns(queue), queue, n, submits, fence);
    count(&c_submit, r);
    if (detailed(&g_detail_submit) || problem(r))
    {
        uint32_t waits = 0, signals = 0, buffers = 0;
        for (uint32_t i = 0; i < n; i++)
        {
            waits += submits[i].waitSemaphoreCount;
            signals += submits[i].signalSemaphoreCount;
            buffers += submits[i].commandBufferCount;
        }
        LOG("vkQueueSubmit(queue %p, %u submits: %u command buffers, %u waits, %u signals, fence 0x%llx) -> %d",
            (void*)queue, n, buffers, waits, signals, (unsigned long long)fence, r);
    }
    return r;
}

static VkResult submit2_common(PFN_vkQueueSubmit2 real, const char* name, VkQueue queue, uint32_t n,
                               const VkSubmitInfo2* submits, VkFence fence)
{
    VkResult r = real(queue, n, submits, fence);
    count(&c_submit, r);
    if (detailed(&g_detail_submit) || problem(r))
        LOG("%s(queue %p, %u submits, fence 0x%llx) -> %d", name, (void*)queue, n, (unsigned long long)fence, r);
    return r;
}

static VKAPI_ATTR VkResult VKAPI_CALL shim_QueueSubmit2(VkQueue queue, uint32_t n, const VkSubmitInfo2* submits, VkFence fence)
{
    return submit2_common(device_fns(queue)->submit2, "vkQueueSubmit2", queue, n, submits, fence);
}

static VKAPI_ATTR VkResult VKAPI_CALL shim_QueueSubmit2KHR(VkQueue queue, uint32_t n, const VkSubmitInfo2* submits, VkFence fence)
{
    return submit2_common(device_fns(queue)->submit2khr, "vkQueueSubmit2KHR", queue, n, submits, fence);
}

// Presented frames, for the overlay's FPS counter (il2mono pad.c reads it over dlsym).
static _Atomic uint64_t g_frames_presented;

EXPORT uint64_t vkshim_frame_count(void)
{
    return atomic_load_explicit(&g_frames_presented, memory_order_relaxed);
}

static VKAPI_ATTR VkResult VKAPI_CALL shim_QueuePresentKHR(VkQueue queue, const VkPresentInfoKHR* info)
{
    Slot* s = enter("vkQueuePresentKHR");
    uint64_t t0 = now_ns();
    VkResult r = device_fns(queue)->present(queue, info);
    atomic_fetch_add_explicit(&g_frames_presented, 1, memory_order_relaxed);
    uint64_t ms = (now_ns() - t0) / 1000000;
    leave(s);
    count(&c_present, r);
    bool per_swapchain_problem = false;
    for (uint32_t i = 0; info->pResults && i < info->swapchainCount; i++)
        per_swapchain_problem |= info->pResults[i] != VK_SUCCESS && info->pResults[i] != VK_SUBOPTIMAL_KHR;
    if (detailed(&g_detail_present) || problem(r) || (per_swapchain_problem && problem(VK_ERROR_UNKNOWN)))
    {
        LOG("vkQueuePresentKHR(queue %p, %u waits, %u swapchains) -> %d, %llu ms", (void*)queue,
            info->waitSemaphoreCount, info->swapchainCount, r, (unsigned long long)ms);
        for (uint32_t i = 0; i < info->swapchainCount; i++)
            LOG("  swapchain 0x%llx image %u%s%d", (unsigned long long)info->pSwapchains[i], info->pImageIndices[i],
                info->pResults ? " result " : "", info->pResults ? info->pResults[i] : 0);
    }
    return r;
}

static VKAPI_ATTR VkResult VKAPI_CALL shim_WaitForFences(VkDevice device, uint32_t n, const VkFence* fences,
                                                        VkBool32 all, uint64_t timeout)
{
    Slot* s = enter("vkWaitForFences");
    VkResult r = device_fns(device)->wait_fences(device, n, fences, all, timeout);
    leave(s);
    count(&c_wait, r);
    if (r != VK_SUCCESS && r != VK_TIMEOUT && problem(r))
        LOG("vkWaitForFences(%u fences, timeout %lld) -> %d", n, (long long)timeout, r);
    return r;
}

static VKAPI_ATTR VkResult VKAPI_CALL shim_QueueWaitIdle(VkQueue queue)
{
    Slot* s = enter("vkQueueWaitIdle");
    VkResult r = device_fns(queue)->queue_wait_idle(queue);
    leave(s);
    if (problem(r))
        LOG("vkQueueWaitIdle -> %d", r);
    return r;
}

static VKAPI_ATTR VkResult VKAPI_CALL shim_DeviceWaitIdle(VkDevice device)
{
    Slot* s = enter("vkDeviceWaitIdle");
    VkResult r = device_fns(device)->device_wait_idle(device);
    leave(s);
    if (problem(r))
        LOG("vkDeviceWaitIdle -> %d", r);
    return r;
}

// ------------------------------------------------------------------ resolvers

typedef struct
{
    const char* name;
    PFN_vkVoidFunction fn;
} Hook;

#define HOOK(name, fn) { name, (PFN_vkVoidFunction)fn }
static const Hook g_device_hooks[] = {
    HOOK("vkCreateSwapchainKHR", shim_CreateSwapchainKHR),
    HOOK("vkDestroySwapchainKHR", shim_DestroySwapchainKHR),
    HOOK("vkGetSwapchainImagesKHR", shim_GetSwapchainImagesKHR),
    HOOK("vkAcquireNextImageKHR", shim_AcquireNextImageKHR),
    HOOK("vkAcquireNextImage2KHR", shim_AcquireNextImage2KHR),
    HOOK("vkQueueSubmit", shim_QueueSubmit),
    HOOK("vkQueueSubmit2", shim_QueueSubmit2),
    HOOK("vkQueueSubmit2KHR", shim_QueueSubmit2KHR),
    HOOK("vkQueuePresentKHR", shim_QueuePresentKHR),
    HOOK("vkWaitForFences", shim_WaitForFences),
    HOOK("vkQueueWaitIdle", shim_QueueWaitIdle),
    HOOK("vkDeviceWaitIdle", shim_DeviceWaitIdle),
};

static PFN_vkVoidFunction device_hook(const char* name)
{
    // Images are counted only for a diagnostic run: no wrapper on a call made thousands of times otherwise.
    if (diag_on() && !strcmp(name, "vkCreateImage"))
        return (PFN_vkVoidFunction)shim_CreateImage;
    if (diag_on() && !strcmp(name, "vkDestroyImage"))
        return (PFN_vkVoidFunction)shim_DestroyImage;
    for (size_t i = 0; i < sizeof(g_device_hooks) / sizeof(g_device_hooks[0]); i++)
        if (!strcmp(name, g_device_hooks[i].name))
            return g_device_hooks[i].fn;
    return NULL;
}

EXPORT VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL vkGetDeviceProcAddr(VkDevice device, const char* name)
{
    load_real();
    PFN_vkVoidFunction real = real_gdpa ? real_gdpa(device, name) : NULL;
    if (!real || !name)
        return real;   // a function the device does not have stays unavailable
    PFN_vkVoidFunction hook = device_hook(name);
    return hook ? hook : real;
}

EXPORT VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL vkGetInstanceProcAddr(VkInstance instance, const char* name)
{
    load_real();
    if (!real_gipa || !name)
        return NULL;
    if (!strcmp(name, "vkGetInstanceProcAddr"))
        return (PFN_vkVoidFunction)vkGetInstanceProcAddr;
    if (!strcmp(name, "vkGetDeviceProcAddr"))
        return (PFN_vkVoidFunction)vkGetDeviceProcAddr;
    PFN_vkVoidFunction real = real_gipa(instance, name);
    if (!real || !instance)
        return real;
    if (!strcmp(name, "vkCreateDevice"))
        return (PFN_vkVoidFunction)shim_CreateDevice;
    if (!strcmp(name, "vkCreateAndroidSurfaceKHR"))
        return (PFN_vkVoidFunction)shim_CreateAndroidSurfaceKHR;
    if (!strcmp(name, "vkDestroySurfaceKHR"))
        return (PFN_vkVoidFunction)shim_DestroySurfaceKHR;
    if (!strcmp(name, "vkGetPhysicalDeviceSurfaceCapabilitiesKHR"))
        return (PFN_vkVoidFunction)shim_GetPhysicalDeviceSurfaceCapabilitiesKHR;
    PFN_vkVoidFunction hook = device_hook(name);
    return hook ? hook : real;
}

// ------------------------------------------------------------------ exports for direct dlsym lookups
// An engine that dlsyms these from this library (instead of using the resolvers) gets the same wrappers.

EXPORT VKAPI_ATTR VkResult VKAPI_CALL vkCreateDevice(VkPhysicalDevice g, const VkDeviceCreateInfo* i,
                                                    const VkAllocationCallbacks* a, VkDevice* d)
{ return shim_CreateDevice(g, i, a, d); }
EXPORT VKAPI_ATTR VkResult VKAPI_CALL vkCreateAndroidSurfaceKHR(VkInstance n, const VkAndroidSurfaceCreateInfoKHR* i,
                                                               const VkAllocationCallbacks* a, VkSurfaceKHR* s)
{ return shim_CreateAndroidSurfaceKHR(n, i, a, s); }
EXPORT VKAPI_ATTR void VKAPI_CALL vkDestroySurfaceKHR(VkInstance n, VkSurfaceKHR s, const VkAllocationCallbacks* a)
{ shim_DestroySurfaceKHR(n, s, a); }
EXPORT VKAPI_ATTR VkResult VKAPI_CALL vkGetPhysicalDeviceSurfaceCapabilitiesKHR(VkPhysicalDevice g, VkSurfaceKHR s,
                                                                               VkSurfaceCapabilitiesKHR* c)
{ return shim_GetPhysicalDeviceSurfaceCapabilitiesKHR(g, s, c); }
EXPORT VKAPI_ATTR VkResult VKAPI_CALL vkCreateSwapchainKHR(VkDevice d, const VkSwapchainCreateInfoKHR* i,
                                                          const VkAllocationCallbacks* a, VkSwapchainKHR* s)
{ return shim_CreateSwapchainKHR(d, i, a, s); }
EXPORT VKAPI_ATTR void VKAPI_CALL vkDestroySwapchainKHR(VkDevice d, VkSwapchainKHR s, const VkAllocationCallbacks* a)
{ shim_DestroySwapchainKHR(d, s, a); }
EXPORT VKAPI_ATTR VkResult VKAPI_CALL vkGetSwapchainImagesKHR(VkDevice d, VkSwapchainKHR s, uint32_t* n, VkImage* i)
{ return shim_GetSwapchainImagesKHR(d, s, n, i); }
EXPORT VKAPI_ATTR VkResult VKAPI_CALL vkAcquireNextImageKHR(VkDevice d, VkSwapchainKHR s, uint64_t t, VkSemaphore m,
                                                           VkFence f, uint32_t* i)
{ return shim_AcquireNextImageKHR(d, s, t, m, f, i); }
EXPORT VKAPI_ATTR VkResult VKAPI_CALL vkAcquireNextImage2KHR(VkDevice d, const VkAcquireNextImageInfoKHR* a, uint32_t* i)
{ return shim_AcquireNextImage2KHR(d, a, i); }
EXPORT VKAPI_ATTR VkResult VKAPI_CALL vkQueueSubmit(VkQueue q, uint32_t n, const VkSubmitInfo* s, VkFence f)
{ return shim_QueueSubmit(q, n, s, f); }
EXPORT VKAPI_ATTR VkResult VKAPI_CALL vkQueueSubmit2(VkQueue q, uint32_t n, const VkSubmitInfo2* s, VkFence f)
{ return shim_QueueSubmit2(q, n, s, f); }
EXPORT VKAPI_ATTR VkResult VKAPI_CALL vkQueueSubmit2KHR(VkQueue q, uint32_t n, const VkSubmitInfo2* s, VkFence f)
{ return shim_QueueSubmit2KHR(q, n, s, f); }
EXPORT VKAPI_ATTR VkResult VKAPI_CALL vkQueuePresentKHR(VkQueue q, const VkPresentInfoKHR* i)
{ return shim_QueuePresentKHR(q, i); }
EXPORT VKAPI_ATTR VkResult VKAPI_CALL vkWaitForFences(VkDevice d, uint32_t n, const VkFence* f, VkBool32 a, uint64_t t)
{ return shim_WaitForFences(d, n, f, a, t); }
EXPORT VKAPI_ATTR VkResult VKAPI_CALL vkQueueWaitIdle(VkQueue q)
{ return shim_QueueWaitIdle(q); }
EXPORT VKAPI_ATTR VkResult VKAPI_CALL vkDeviceWaitIdle(VkDevice d)
{ return shim_DeviceWaitIdle(d); }
