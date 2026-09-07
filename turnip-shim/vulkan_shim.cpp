/*
 * vulkan_shim.c - GOT patch approach
 *
 * Compile:
 *   aarch64-linux-android26-clang -shared -fPIC -O2 -o libvulkad.so \
 *       vulkan_shim.c -ldl -llog
 */

#include <pthread.h>
#include <dlfcn.h>
#include <stdint.h>
#include <string.h>
#include <stdio.h>
#include <stdlib.h>
#include <ctype.h>
#include <sys/mman.h>
#include <elf.h>
#include <android/log.h>
#include <sys/stat.h>
#include <sys/system_properties.h>
#include <unistd.h>
#include <errno.h>
#include <time.h>
#include <android/dlext.h>
#include <sys/ioctl.h>
#include <fcntl.h>
#include <dirent.h>
#include "kgsl.h"
#include "hook_impl_params.h"
#include <adrenotools/driver.h>
#include <android_linker_ns.h>
#include "fsr_upscaler.h"
#include "fg_source.h"

#if defined(NETHER_NO_FSR_FRAMEGEN) && NETHER_NO_FSR_FRAMEGEN
#define NETHER_FSR_FRAMEGEN_ACTIVE 0
#else
#define NETHER_FSR_FRAMEGEN_ACTIVE 1
#endif

#define TAG "VulkanShim"

/* ------------------------------------------------------------------ */
/* State                                                               */
/* ------------------------------------------------------------------ */
static void  *g_sys_vulkan   = NULL;  /* handle to system libvulkan.so */
static char   g_lib_dir[512];         /* our lib directory             */
static char   g_turnip_path[512];     /* full path to Turnip ICD       */
static void *g_turnip = NULL;

// Log file code
static FILE *g_logfile = NULL;

static void init_logfile(void) {
    if (g_logfile) return;
    
    /* Extract package name from our lib path */
    char *pkg_start = strstr(g_lib_dir, "/data/app/");
    if (!pkg_start) return;
    
    char *after_hash = strchr(pkg_start + 10, '/');
    if (!after_hash) return;
    after_hash++;
    
    char *dash = strstr(after_hash, "-");
    if (!dash) return;
    
    char pkg[256] = {0};
    strncpy(pkg, after_hash, dash - after_hash);
    
    /* Build path: /sdcard/Android/data/<pkg>/files/vulkan_shim.log */
    char path[512];
    snprintf(path, sizeof(path), "/sdcard/Android/data/%s/files", pkg);
    mkdir(path, 0755);  /* ensure it exists */
    
    strncat(path, "/vulkan_shim.log", sizeof(path) - strlen(path) - 1);
    
    g_logfile = fopen(path, "a");
    if (g_logfile) {
        setvbuf(g_logfile, NULL, _IOLBF, 0);  /* line-buffered for safety */
        time_t now = time(NULL);
        fprintf(g_logfile, "\n=== shim started at %s", ctime(&now));
    }
}

/*
static void log_to_file(const char *level, const char *fmt, va_list args) {
    if (!g_logfile) init_logfile();
    if (!g_logfile) return;
    
    struct timespec ts;
    clock_gettime(CLOCK_REALTIME, &ts);
    struct tm tm;
    localtime_r(&ts.tv_sec, &tm);
    
    fprintf(g_logfile, "%02d:%02d:%02d.%03ld [%s] ",
            tm.tm_hour, tm.tm_min, tm.tm_sec, ts.tv_nsec / 1000000,
            level);
    vfprintf(g_logfile, fmt, args);
    fputc('\n', g_logfile);
}

static void shim_logi(const char *fmt, ...) {
    va_list args, args2;
    va_start(args, fmt);
    va_copy(args2, args);
    __android_log_vprint(ANDROID_LOG_INFO, TAG, fmt, args);
    log_to_file("I", fmt, args2);
    va_end(args);
    va_end(args2);
}

static void shim_loge(const char *fmt, ...) {
    va_list args, args2;
    va_start(args, fmt);
    va_copy(args2, args);
    __android_log_vprint(ANDROID_LOG_ERROR, TAG, fmt, args);
    log_to_file("E", fmt, args2);
    va_end(args);
    va_end(args2);
}
*/
static void log_to_file(const char *level, const char *fmt, const char *buffer) {
    if (!g_logfile) init_logfile();
    if (!g_logfile) return;
    
    struct timespec ts;
    clock_gettime(CLOCK_REALTIME, &ts);
    struct tm tm;
    localtime_r(&ts.tv_sec, &tm);
    
    fprintf(g_logfile, "%02d:%02d:%02d.%03ld [%s] ",
            tm.tm_hour, tm.tm_min, tm.tm_sec, ts.tv_nsec / 1000000,
            level);
    fprintf(g_logfile, fmt, buffer);
    fputc('\n', g_logfile);
}

static void shim_logi(const char *fmt, ...) {
    va_list args;
    va_start(args, fmt);

    char buffer[1024];
    vsnprintf(buffer, sizeof(buffer), fmt, args);

    va_end(args);

    __android_log_write(ANDROID_LOG_INFO, TAG, buffer);
    /* Was "E": every info line in vulkan_shim.log was tagged as an error, which
     * makes lines like "FSR: RCAS active ..." read as failures. */
    log_to_file("I", "%s", buffer);
}


static void shim_loge(const char *fmt, ...) {
    va_list args;
    va_start(args, fmt);

    char buffer[1024];
    vsnprintf(buffer, sizeof(buffer), fmt, args);

    va_end(args);

    __android_log_write(ANDROID_LOG_ERROR, TAG, buffer);
    log_to_file("E", "%s", buffer);
}

#define LOGI(...) shim_logi(__VA_ARGS__)
#define LOGE(...) shim_loge(__VA_ARGS__)

/* Sinks handed to fsr_set_log_fn(). Without this fsr_upscaler.cpp falls back to
 * __android_log_write, so every FSR line went to logcat only and none of it
 * reached /sdcard/Android/data/<pkg>/files/vulkan_shim.log — which is the log
 * anyone actually reads when checking whether the pass ran.
 *
 * These are one-argument thunks on purpose: shim_logi/shim_loge are variadic and
 * fsr_set_log_fn wants void(*)(const char*). Passing the variadic functions
 * directly would be a mismatched call through an incompatible function pointer,
 * and it would also treat FSR's already-formatted message as a format string,
 * so any '%' in it would read garbage off the stack. */
static void shim_fsr_logi(const char *msg) { shim_logi("%s", msg); }
static void shim_fsr_loge(const char *msg) { shim_loge("%s", msg); }

typedef void (*PFN_vkVoidFunction)(void);
typedef void*    VkInstance;
typedef void*    VkDevice;
typedef uint32_t VkResult;
typedef void*    VkCommandBuffer;
#ifndef VK_SUCCESS
#define VK_SUCCESS 0
#endif
/* Android WSI: surface already bound to another swapchain (not transfer rejection). */
#ifndef VK_ERROR_NATIVE_WINDOW_IN_USE_KHR
#define VK_ERROR_NATIVE_WINDOW_IN_USE_KHR ((VkResult)0xC46535FFu)
#endif

/* ------------------------------------------------------------------ */
/* Vulkan types needed for the readback barrier hook                    */
/* ------------------------------------------------------------------ */
typedef uint32_t VkFlags;
typedef VkFlags  VkPipelineStageFlags;
typedef VkFlags  VkAccessFlags;
typedef VkFlags  VkDependencyFlags;

typedef struct VkMemoryBarrier {
    uint32_t       sType;       /* VK_STRUCTURE_TYPE_MEMORY_BARRIER = 46 */
    const void    *pNext;
    VkAccessFlags  srcAccessMask;
    VkAccessFlags  dstAccessMask;
} VkMemoryBarrier;

/* Vulkan enum values we need */
#define VK_STRUCTURE_TYPE_MEMORY_BARRIER          46
#define VK_PIPELINE_STAGE_ALL_COMMANDS_BIT        0x00010000
#define VK_PIPELINE_STAGE_TRANSFER_BIT            0x00001000
#define VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT      0x00000100
#define VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT 0x00000400
#define VK_ACCESS_SHADER_WRITE_BIT                0x00000040
#define VK_ACCESS_TRANSFER_READ_BIT               0x00000800
#define VK_ACCESS_TRANSFER_WRITE_BIT              0x00001000
#define VK_ACCESS_MEMORY_WRITE_BIT                0x00010000
#define VK_ACCESS_MEMORY_READ_BIT                 0x00008000

/* ------------------------------------------------------------------ */
/* Readback barrier hook state                                         */
/* ------------------------------------------------------------------ */
typedef void (*PFN_vkCmdPipelineBarrier)(
    VkCommandBuffer, VkPipelineStageFlags, VkPipelineStageFlags,
    VkDependencyFlags, uint32_t, const VkMemoryBarrier *,
    uint32_t, const void *, uint32_t, const void *);

typedef void (*PFN_vkCmdCopyImageToBuffer2)(VkCommandBuffer, const void *);

static PFN_vkCmdPipelineBarrier     g_vkCmdPipelineBarrier = NULL;
static PFN_vkCmdCopyImageToBuffer2  g_real_copy_itb2       = NULL;
static PFN_vkCmdCopyImageToBuffer2  g_real_copy_itb2_khr   = NULL;

/* ------------------------------------------------------------------ */
/* Hooked vkCmdCopyImageToBuffer2 — injects a full barrier before      */
/* the copy to ensure UBWC metadata is coherent                        */
/* ------------------------------------------------------------------ */
static void hooked_CmdCopyImageToBuffer2(
    VkCommandBuffer cmdBuf, const void *pInfo)
{
    if (g_vkCmdPipelineBarrier) {
        VkMemoryBarrier memBarrier;
        memBarrier.sType         = VK_STRUCTURE_TYPE_MEMORY_BARRIER;
        memBarrier.pNext         = NULL;
        memBarrier.srcAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT |
                                   VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT |
                                   VK_ACCESS_SHADER_WRITE_BIT |
                                   VK_ACCESS_MEMORY_WRITE_BIT;
        memBarrier.dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT |
                                   VK_ACCESS_MEMORY_READ_BIT;

        g_vkCmdPipelineBarrier(
            cmdBuf,
            VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
            VK_PIPELINE_STAGE_TRANSFER_BIT,
            0,
            1, &memBarrier,
            0, NULL,
            0, NULL);
    }

    if (g_real_copy_itb2)
        g_real_copy_itb2(cmdBuf, pInfo);
}

extern "C" VkResult hooked_EnumeratePhysicalDevices(VkInstance inst, uint32_t *pCount, void *pDevs) {
    static VkResult (*real_fn)(VkInstance, uint32_t*, void*) = NULL;
    if (!real_fn && g_sys_vulkan)
        real_fn = (VkResult (*)(VkInstance, uint32_t*, void*))
            dlsym(g_sys_vulkan, "vkEnumeratePhysicalDevices");
    if (!real_fn) {
        LOGE("VulkanShim: vkEnumeratePhysicalDevices not resolved");
        return -3;
    }
    
    VkResult res = real_fn(inst, pCount, pDevs);
    LOGI("VulkanShim: vkEnumeratePhysicalDevices count=%u result=%d pDevs=%p",
         pCount ? *pCount : 0, res, pDevs);
    return res;
}

/* The real android_load_sphal_library so we can call it for non-Vulkan */
static void *(*g_real_sphal_load)(const char *name, int flags) = NULL;
static void *(*g_real_sphal_load2)(const char *name, int flags) = NULL;
static void *(*g_real_android_dlopen_ext)(const char *filename, int flags, const android_dlextinfo *extinfo) = NULL;
static FILE *(*g_real_fopen)(const char *filename, const char *mode) = NULL;

/* ------------------------------------------------------------------ */
/* GOT patching helpers                                                */
/* ------------------------------------------------------------------ */

/* Find the load base of a mapped library by scanning /proc/self/maps */
static uintptr_t find_lib_base(const char *libname) {
    FILE *f = fopen("/proc/self/maps", "r");
    if (!f) return 0;
    char line[512];
    uintptr_t base = 0;
    while (fgets(line, sizeof(line), f)) {
        if (strstr(line, libname) && strstr(line, "r--p")) {
            base = (uintptr_t)strtoull(line, NULL, 16);
            break;
        }
    }
    fclose(f);
    LOGI("VulkanShim: base of %s = 0x%lx", libname, base);
    return base;
}

/* Patch a GOT entry: find symbol_name in libvulkan's RELA and replace */
static int patch_got(uintptr_t base, const char *symbol_name, void *new_fn) {
    /* Walk ELF headers at base */
    Elf64_Ehdr *ehdr = (Elf64_Ehdr *)base;
    if (memcmp(ehdr->e_ident, ELFMAG, 4) != 0) {
        LOGE("VulkanShim: bad ELF magic at 0x%lx", base);
        return 0;
    }

    Elf64_Phdr *phdr = (Elf64_Phdr *)(base + ehdr->e_phoff);
    uintptr_t load_bias = 0;

    /* Find load bias: difference between actual base and p_vaddr of LOAD */
    for (int i = 0; i < ehdr->e_phnum; i++) {
        if (phdr[i].p_type == PT_LOAD && phdr[i].p_offset == 0) {
            load_bias = base - phdr[i].p_vaddr;
            break;
        }
    }
    LOGI("VulkanShim: load_bias = 0x%lx", load_bias);

    /* Find dynamic segment */
    Elf64_Dyn *dyn = NULL;
    for (int i = 0; i < ehdr->e_phnum; i++) {
        if (phdr[i].p_type == PT_DYNAMIC) {
            dyn = (Elf64_Dyn *)(load_bias + phdr[i].p_vaddr);
            break;
        }
    }
    if (!dyn) { LOGE("VulkanShim: no dynamic segment"); return 0; }

    /* Extract RELA, RELACOUNT, SYMTAB, STRTAB from dynamic section */
    Elf64_Rela  *rela      = NULL;
    Elf64_Sym   *symtab    = NULL;
    const char  *strtab    = NULL;
    size_t       relacount = 0;
    size_t       relasz    = 0;

    for (Elf64_Dyn *d = dyn; d->d_tag != DT_NULL; d++) {
        switch (d->d_tag) {
            case DT_JMPREL:   rela      = (Elf64_Rela *)(load_bias + d->d_un.d_ptr); break;
            case DT_PLTRELSZ: relasz    = d->d_un.d_val; break;
            case DT_SYMTAB:   symtab    = (Elf64_Sym  *)(load_bias + d->d_un.d_ptr); break;
            case DT_STRTAB:   strtab    = (const char *)(load_bias + d->d_un.d_ptr); break;
        }
    }

    if (!rela || !symtab || !strtab) {
        LOGE("VulkanShim: missing RELA/SYMTAB/STRTAB");
        return 0;
    }

    relacount = relasz / sizeof(Elf64_Rela);
    LOGI("VulkanShim: scanning %zu PLT entries for %s", relacount, symbol_name);

    for (size_t i = 0; i < relacount; i++) {
        uint32_t sym_idx = ELF64_R_SYM(rela[i].r_info);
        const char *name = strtab + symtab[sym_idx].st_name;
        if (strcmp(name, symbol_name) == 0) {
            uintptr_t *got_entry = (uintptr_t *)(load_bias + rela[i].r_offset);
            LOGI("VulkanShim: found %s at GOT entry %p (value=0x%lx)",
                 symbol_name, got_entry, *got_entry);

            /* Make page writable */
            uintptr_t page     = (uintptr_t)got_entry & ~(uintptr_t)(4095);
            uintptr_t page_end = ((uintptr_t)got_entry + sizeof(uintptr_t) + 4095) & ~(uintptr_t)(4095);
            if (mprotect((void *)page, page_end - page,
                         PROT_READ | PROT_WRITE) != 0) {
                LOGE("VulkanShim: mprotect failed");
                return 0;
            }

            /* Save old value and patch */
			if (strcmp(symbol_name, "android_load_sphal_library") == 0) {
				if (!g_real_sphal_load)
					g_real_sphal_load = (void *(*)(const char*, int))(*got_entry);
				else
					g_real_sphal_load2 = (void *(*)(const char*, int))(*got_entry);
			}
			else {
				if (strcmp(symbol_name, "android_dlopen_ext") == 0)
					g_real_android_dlopen_ext = (void *(*)(const char*, int, const android_dlextinfo *))(*got_entry);
				else {
					if (strcmp(symbol_name, "fopen") == 0) {
						g_real_fopen = (FILE *(*)(const char*, const char *))(*got_entry);
						LOGI("VulkanShim: GOT Patch for fopen");
					}
					else
						LOGE("Could not save the real function call");
				}
			}
				
			*got_entry = (uintptr_t)new_fn;

            /* Restore to read-execute */
            mprotect((void *)page, page_end - page, PROT_READ | PROT_EXEC);

            LOGI("VulkanShim: GOT patched! old=%p new=%p",
                 g_real_sphal_load, new_fn);
            return 1;
        }
    }

    LOGE("VulkanShim: %s not found in PLT", symbol_name);
    return 0;
}

/* Patch a GOT entry to point to a specific function (not necessarily our hook) */
static int patch_got_with_real(uintptr_t base, const char *symbol_name, void *new_fn) {
    Elf64_Ehdr *ehdr = (Elf64_Ehdr *)base;
    if (memcmp(ehdr->e_ident, ELFMAG, 4) != 0) return 0;

    Elf64_Phdr *phdr = (Elf64_Phdr *)(base + ehdr->e_phoff);
    uintptr_t load_bias = 0;
    for (int i = 0; i < ehdr->e_phnum; i++) {
        if (phdr[i].p_type == PT_LOAD && phdr[i].p_offset == 0) {
            load_bias = base - phdr[i].p_vaddr;
            break;
        }
    }

    Elf64_Dyn *dyn = NULL;
    for (int i = 0; i < ehdr->e_phnum; i++) {
        if (phdr[i].p_type == PT_DYNAMIC) {
            dyn = (Elf64_Dyn *)(load_bias + phdr[i].p_vaddr);
            break;
        }
    }
    if (!dyn) return 0;

    /* Collect ALL rela sections — both PLT (DT_JMPREL) and regular (DT_RELA) */
    Elf64_Rela *plt_rela   = NULL;  size_t plt_sz  = 0;
    Elf64_Rela *rela       = NULL;  size_t rela_sz = 0;
    Elf64_Sym  *symtab     = NULL;
    const char *strtab     = NULL;

    for (Elf64_Dyn *d = dyn; d->d_tag != DT_NULL; d++) {
        switch (d->d_tag) {
            case DT_JMPREL:   plt_rela = (Elf64_Rela *)(load_bias + d->d_un.d_ptr); break;
            case DT_PLTRELSZ: plt_sz   = d->d_un.d_val; break;
            case DT_RELA:     rela     = (Elf64_Rela *)(load_bias + d->d_un.d_ptr); break;
            case DT_RELASZ:   rela_sz  = d->d_un.d_val; break;
            case DT_SYMTAB:   symtab   = (Elf64_Sym  *)(load_bias + d->d_un.d_ptr); break;
            case DT_STRTAB:   strtab   = (const char *)(load_bias + d->d_un.d_ptr); break;
        }
    }
    if (!symtab || !strtab) return 0;

    /* Helper lambda — scan one rela table */
    #define SCAN_RELA(table, count)                                          \
    if (table) {                                                             \
        size_t n = (count) / sizeof(Elf64_Rela);                            \
        LOGI("VulkanShim: scanning %zu entries in " #table " for %s",       \
             n, symbol_name);                                                \
        for (size_t i = 0; i < n; i++) {                                    \
            uint32_t sym_idx = ELF64_R_SYM((table)[i].r_info);             \
            const char *name = strtab + symtab[sym_idx].st_name;           \
            if (strcmp(name, symbol_name) == 0) {                           \
                uintptr_t *got = (uintptr_t *)(load_bias + (table)[i].r_offset); \
                LOGI("VulkanShim: found %s at %p (val=0x%lx)",             \
                     symbol_name, got, *got);                               \
                uintptr_t pg  = (uintptr_t)got & ~(uintptr_t)4095;         \
                uintptr_t end = ((uintptr_t)got + 8 + 4095) & ~(uintptr_t)4095; \
                mprotect((void*)pg, end-pg, PROT_READ|PROT_WRITE);         \
                *got = (uintptr_t)new_fn;                                   \
                mprotect((void*)pg, end-pg, PROT_READ|PROT_EXEC);          \
                LOGI("VulkanShim: patched -> %p", new_fn);                  \
                return 1;                                                    \
            }                                                               \
        }                                                                   \
    }

    SCAN_RELA(plt_rela, plt_sz)   /* PLT entries */
    SCAN_RELA(rela,     rela_sz)  /* regular GOT entries */
    #undef SCAN_RELA

    LOGE("VulkanShim: %s not found in Turnip PLT or RELA", symbol_name);
    return 0;
}

extern "C" __attribute__((visibility("default"))) FILE *fopen_hook(const char *name, const char *mode) {
	LOGI("fopen hook! %s", name);
	
	 if (name) {
        int is_vulkan_icd = 0;

        /* System Adreno ICD patterns */
        if (strstr(name, "vulkan.") != NULL) is_vulkan_icd = 1;
        if (strstr(name, "/hw/vulkan") != NULL) is_vulkan_icd = 1;

        /* Explicitly NOT Vulkan — let these through */
        if (strstr(name, "gralloc") != NULL) is_vulkan_icd = 0;
        if (strstr(name, "hwcomposer") != NULL) is_vulkan_icd = 0;
        if (strstr(name, "egl") != NULL) is_vulkan_icd = 0;
        if (strstr(name, "gles") != NULL) is_vulkan_icd = 0;
        if (strstr(name, "mapper") != NULL) is_vulkan_icd = 0;

        if (is_vulkan_icd) {
            LOGI("VulkanShim: redirecting fopen: %s -> Turnip", name);
            FILE *h = g_real_fopen(g_turnip_path, mode);
            if (h) { LOGI("VulkanShim: Turnip redirect OK"); return h; }
            LOGE("VulkanShim: Turnip redirect failed: %s", dlerror());
        }
    }
	
	return g_real_fopen(name, mode);
}

/* ------------------------------------------------------------------ */
/* Our hook for android_load_sphal_library                            */
/* ------------------------------------------------------------------ */
static void *sphal_hook(const char *name, int flags) {
    LOGI("VulkanShim: sphal_hook intercepted: %s (flags=%d)", name ? name : "NULL", flags);

    /* Only redirect the Vulkan ICD — identified by containing "vulkan"
       AND being a .so in a hw/ path or just named vulkan.*.so         */
    if (name) {
        int is_vulkan_icd = 0;

        /* System Adreno ICD patterns */
        if (strstr(name, "vulkan.") != NULL) is_vulkan_icd = 1;
        if (strstr(name, "/hw/vulkan") != NULL) is_vulkan_icd = 1;

        /* Explicitly NOT Vulkan — let these through */
        if (strstr(name, "gralloc") != NULL) is_vulkan_icd = 0;
        if (strstr(name, "hwcomposer") != NULL) is_vulkan_icd = 0;
        if (strstr(name, "egl") != NULL) is_vulkan_icd = 0;
        if (strstr(name, "gles") != NULL) is_vulkan_icd = 0;
        if (strstr(name, "mapper") != NULL) is_vulkan_icd = 0;

        if (is_vulkan_icd) {
            LOGI("VulkanShim: redirecting Vulkan ICD: %s -> Turnip", name);
            void *h = dlopen(g_turnip_path, flags | RTLD_GLOBAL);
            if (h) { LOGI("VulkanShim: Turnip redirect OK"); return h; }
            LOGE("VulkanShim: Turnip redirect failed: %s", dlerror());
        }
    }

    /* Pass everything else through to the real sphal loader */
   /* Pass everything else through to the real sphal loader */
	if (g_real_sphal_load) {
		const char *basename = name ? strrchr(name, '/') : NULL;
		const char *load_name = basename ? basename + 1 : name;
		if (load_name != name)
			LOGI("VulkanShim: sphal stripped path: %s -> %s", name, load_name);
		return g_real_sphal_load(load_name, flags);
	}
    return NULL;
}

static void *sphal_hook2(const char *name, int flags) {
    LOGI("VulkanShim: sphal_hook2 intercepted: %s (flags=%d)", name ? name : "NULL", flags);

    /* Only redirect the Vulkan ICD — identified by containing "vulkan"
       AND being a .so in a hw/ path or just named vulkan.*.so         */
    if (name) {
        int is_vulkan_icd = 0;

        /* System Adreno ICD patterns */
        if (strstr(name, "vulkan.") != NULL) is_vulkan_icd = 1;
        if (strstr(name, "/hw/vulkan") != NULL) is_vulkan_icd = 1;

        /* Explicitly NOT Vulkan — let these through */
        if (strstr(name, "gralloc") != NULL) is_vulkan_icd = 0;
        if (strstr(name, "hwcomposer") != NULL) is_vulkan_icd = 0;
        if (strstr(name, "egl") != NULL) is_vulkan_icd = 0;
        if (strstr(name, "gles") != NULL) is_vulkan_icd = 0;
        if (strstr(name, "mapper") != NULL) is_vulkan_icd = 0;

        if (is_vulkan_icd) {
            LOGI("VulkanShim: redirecting Vulkan ICD: %s -> Turnip", name);
            void *h = dlopen(g_turnip_path, flags | RTLD_GLOBAL);
            if (h) { LOGI("VulkanShim: Turnip redirect OK"); return h; }
            LOGE("VulkanShim: Turnip redirect failed: %s", dlerror());
        }
    }

    /* Pass everything else through to the real sphal loader */
	// Insist on using 1 not 2 !
    if (g_real_sphal_load) {
		const char *basename = name ? strrchr(name, '/') : NULL;
		const char *load_name = basename ? basename + 1 : name;
		if (load_name != name)
			LOGI("VulkanShim: sphal stripped path: %s -> %s", name, load_name);
		return g_real_sphal_load(load_name, flags);
	}
    return NULL;
}

extern "C" void *android_dlopen_ext_hook(const char *filename, int flags, const android_dlextinfo *extinfo) {
	LOGI("android_dlopen_ext_hook called for %s", filename);
	return g_real_android_dlopen_ext(filename, flags, extinfo);
}

/* Find a lib's path from /proc/self/maps regardless of namespace */
static int find_lib_path_in_maps(const char *libname, char *out_path, size_t out_size) {
    FILE *f = fopen("/proc/self/maps", "r");
    if (!f) return 0;
    char line[512];
    while (fgets(line, sizeof(line), f)) {
        if (strstr(line, libname) && strstr(line, "r--p")) {
            /* Extract path from end of line */
            char *path = strchr(line, '/');
            if (path) {
                path[strlen(path)-1] = '\0'; /* remove newline */
                strncpy(out_path, path, out_size-1);
                fclose(f);
                return 1;
            }
        }
    }
    fclose(f);
    return 0;
}

/* 
 * From:  /data/app/~~hash==/pkg-hash==/lib/arm64/libvulkad.so
 * Get:   /data/data/pkg/cache/vulkan_deps/
 * Via:   /proc/self/maps to find package name, or derive from lib path
 */
static int setup_deps_dir(char *deps_dir, size_t size) {
    /* Our lib path looks like:
       /data/app/~~X==/xyz.aethersx2.android-Y==/lib/arm64/libvulkad.so
       The data dir is:
       /data/data/xyz.aethersx2.android/cache/vulkan_deps/            */

    /* Extract package name from our lib path */
    char *pkg_start = strstr(g_lib_dir, "/data/app/");
    if (!pkg_start) return 0;

    /* Skip past /data/app/~~hash==/ */
    char *after_hash = strchr(pkg_start + 10, '/');
    if (!after_hash) return 0;
    after_hash++; /* skip the / */

    /* Package name ends at the next - followed by hash */
    char pkg[256] = {0};
    char *dash = strstr(after_hash, "-");
    if (!dash) return 0;
    strncpy(pkg, after_hash, dash - after_hash);

    snprintf(deps_dir, size, "/data/data/%s/cache/vulkan_deps", pkg);
    LOGI("VulkanShim: deps dir = %s", deps_dir);
    return 1;
}

static void copy_file(const char *src, const char *dst) {
    /* Check if already copied */
    FILE *test = fopen(dst, "r");
    if (test) { fclose(test); LOGI("VulkanShim: already exists: %s", dst); return; }

    FILE *in = fopen(src, "rb");
    if (!in) { LOGE("VulkanShim: cannot open src: %s", src); return; }

    FILE *out = fopen(dst, "wb");
    if (!out) { 
        fclose(in); 
        LOGE("VulkanShim: cannot open dst: %s", dst); 
        return; 
    }

    char buf[65536];
    size_t n;
    while ((n = fread(buf, 1, sizeof(buf), in)) > 0)
        fwrite(buf, 1, n, out);

    fclose(in);
    fclose(out);
    chmod(dst, 0755);
    LOGI("VulkanShim: copied %s -> %s", src, dst);
}

/* Some libs have different filenames in maps vs their soname */
static int find_lib_path_in_maps_multi(const char **names, char *out_path, size_t out_size) {
    for (int i = 0; names[i]; i++) {
        if (find_lib_path_in_maps(names[i], out_path, out_size))
            return 1;
    }
    return 0;
}

static int find_and_copy_deps(const char *deps_dir) {
    const char *needed[] = {
        "libc++.so",
        "libbase.so",
        "libsync.so",
        "libcutils.so",
        "libvndksupport.so",
        "libhardware.so",
        NULL
    };

    /* Copy standard libs */
    for (int i = 0; needed[i]; i++) {
        char dst[512];
        snprintf(dst, sizeof(dst), "%s/%s", deps_dir, needed[i]);
        FILE *f = fopen(dst, "r");
        if (f) { fclose(f); LOGI("VulkanShim: cached: %s", needed[i]); continue; }
        char src[512] = {0};
        if (!find_lib_path_in_maps(needed[i], src, sizeof(src))) {
            LOGE("VulkanShim: not in maps: %s", needed[i]); continue;
        }
        copy_file(src, dst);
    }

    /* libdl_android.so needs special handling - copy it AND
       create a copy named ld-android.so since that's its SONAME dependency */
    char ldandroid_dst[512], libdlandroid_dst[512];
    snprintf(ldandroid_dst,    sizeof(ldandroid_dst),    "%s/ld-android.so",    deps_dir);
    snprintf(libdlandroid_dst, sizeof(libdlandroid_dst), "%s/libdl_android.so", deps_dir);

    int ld_cached    = (fopen(ldandroid_dst,    "r") != NULL);
    int libdl_cached = (fopen(libdlandroid_dst, "r") != NULL);

    if (!ld_cached || !libdl_cached) {
        char src[512] = {0};
        if (find_lib_path_in_maps("libdl_android.so", src, sizeof(src))) {
            if (!libdl_cached) copy_file(src, libdlandroid_dst);
            if (!ld_cached)    copy_file(src, ldandroid_dst);
            LOGI("VulkanShim: copied libdl_android.so as both names");
        } else {
            /* Try finding via linker64 path - derive libdl_android path */
            char linker_path[512] = {0};
            if (find_lib_path_in_maps("linker64", linker_path, sizeof(linker_path))) {
                /* Replace 'bin/linker64' with 'lib64/bionic/libdl_android.so' */
                char *bin = strstr(linker_path, "/bin/linker64");
                if (bin) {
                    char derived[512];
                    strncpy(derived, linker_path, bin - linker_path);
                    derived[bin - linker_path] = '\0';
                    strncat(derived, "/lib64/bionic/libdl_android.so",
                            sizeof(derived) - strlen(derived) - 1);
                    LOGI("VulkanShim: trying derived path: %s", derived);
                    FILE *tf = fopen(derived, "r");
                    if (tf) {
                        fclose(tf);
                        if (!libdl_cached) copy_file(derived, libdlandroid_dst);
                        if (!ld_cached)    copy_file(derived, ldandroid_dst);
                        LOGI("VulkanShim: copied libdl_android via derived path");
                    } else {
                        LOGE("VulkanShim: derived path not accessible: %s", derived);
                    }
                }
            }
        }
    } else {
        LOGI("VulkanShim: cached: ld-android.so + libdl_android.so");
    }

    return 1;
}
/*
// android_get_exported_namespace — needed by bundled libvndksupport  
__attribute__((visibility("default")))
//void* android_get_exported_namespace(const char *name) {
android_namespace_t *android_get_exported_namespace(const char *) {
    LOGI("VulkanShim: stub android_get_exported_namespace(%s)", name ? name : "NULL");
    // Will be GOT-patched to real version before Turnip calls this    
    return NULL;
}
*/
/* android_load_sphal_library — needed by bundled libhardware         */

__attribute__((visibility("default")))
extern "C" void* android_load_sphal_library(const char *name, int flags) {
    LOGI("VulkanShim: stub android_load_sphal_library(%s)", name ? name : "NULL");
    // Will be GOT-patched — for libvulkan we redirect to Turnip, for everything else we call the real system function            
    if (g_real_sphal_load) {
        return g_real_sphal_load(name, flags);
    }
    return NULL;
}

__attribute__((visibility("default")))
extern "C" void android_unload_sphal_library(void *handle) {
    LOGI("VulkanShim: stub android_unload_sphal_library(%p)", handle);
    if (handle) dlclose(handle);
}

// Functions for missing ones in Android 13 libc++.so

__attribute__((visibility("default")))
extern "C" void _ZNSt3__122__libcpp_verbose_abortEPKcz(const char *fmt, ...) {
    va_list args;
    va_start(args, fmt);
    __android_log_vprint(ANDROID_LOG_FATAL, "VulkanShim", fmt, args);
    va_end(args);
    abort();
}

__attribute__((visibility("default")))
extern "C" void _ZNSt3__113basic_filebufIcNS_11char_traitsIcEEED1Ev(void *self) {
    /* basic_filebuf destructor — close the file if open */
    /* The base class (~basic_streambuf) will be called by the linker automatically.
       We just need to close any FILE* handle at a known offset.
       For safety, just do nothing — libvulkan.so doesn't actually use filebuf at runtime. */
    (void)self;
}

/* Also provide D0 (deleting destructor) variant */
__attribute__((visibility("default")))
extern "C" void _ZNSt3__113basic_filebufIcNS_11char_traitsIcEEED0Ev(void *self) {
    _ZNSt3__113basic_filebufIcNS_11char_traitsIcEEED1Ev(self);
    free(self);
}

extern "C" void setup_turnip_env(int noubwc, int nolrz, int flushall) {
    char buf[256] = {0};
    char *p = buf;

    if (noubwc)  p += sprintf(p, "noubwc,");
    if (nolrz)   p += sprintf(p, "nolrz,");
    if (flushall) p += sprintf(p, "flushall,");

    // Strip trailing comma
    if (p > buf) *(p - 1) = '\0';

    if (buf[0]) 
	{
		LOGI("VulkanShim: TU_DEBUG: %s", buf);
		setenv("TU_DEBUG", buf, 1);
	}
}

static int get_adreno_gpu_id(char *out_name, size_t out_size) {
	/* Try gpu_model first — returns e.g. "Adreno (TM) 650" */
	FILE *f = fopen("/sys/class/kgsl/kgsl-3d0/gpu_model", "r");
    if (f) {
        if (fgets(out_name, out_size, f)) {
            // Strip trailing newline 
            char *nl = strchr(out_name, '\n');
            if (nl) *nl = '\0';
            fclose(f);
            LOGI("VulkanShim: GPU model: %s", out_name);
            return 1;
        }
        fclose(f);
    }

    /* Fallback: chip_id — returns hex like 0x06050002 for Adreno 650 */
    f = fopen("/sys/class/kgsl/kgsl-3d0/gpu_chip_id", "r");
    if (!f)
        f = fopen("/sys/class/kgsl/kgsl-3d0/chip_id", "r");
    if (f) {
        char buf[32] = {0};
        if (fgets(buf, sizeof(buf), f)) {
            unsigned long chip_id = strtoul(buf, NULL, 16);
            /* Adreno chip ID format: 0xCCMMPPPP
               CC = core, MM = major, PPPP = minor/patch
               e.g. 0x06050002 = core 6, major 5 = Adreno 650
                    0x06030001 = core 6, major 3 = Adreno 630
                    0x07030001 = core 7, major 3 = Adreno 730
                    0x43050a01 = Adreno 830 (newer encoding) */
            unsigned int core = (chip_id >> 24) & 0xFF;
            unsigned int major = (chip_id >> 16) & 0xFF;

            if (core == 0x06)
                snprintf(out_name, out_size, "Adreno 6%d0", major);
            else if (core == 0x07)
                snprintf(out_name, out_size, "Adreno 7%d0", major);
            else
                snprintf(out_name, out_size, "Adreno (chip_id=0x%08lx)", chip_id);

            LOGI("VulkanShim: GPU chip_id: 0x%08lx -> %s", chip_id, out_name);
            fclose(f);
            return 1;
        }
        fclose(f);
    }

    snprintf(out_name, out_size, "unknown");
    return 0;
}

static int get_oneui_version() {
    char value[PROP_VALUE_MAX] = {0};
    
    /* One UI major version — returns e.g. "70000" for One UI 7, "80500" for 8.5 */
    if (__system_property_get("ro.build.version.oneui", value) > 0) {
        int ver = atoi(value);
        LOGI("VulkanShim: One UI version raw: %s (%d)", value, ver);
        return ver;
    }
    
    /* Fallback: Samsung Experience version (older devices) */
    if (__system_property_get("ro.build.version.sep", value) > 0) {
        int ver = atoi(value);
        LOGI("VulkanShim: Samsung SEP version: %s (%d)", value, ver);
        /* SEP 150000+ roughly maps to One UI 7+ */
        return ver >= 150000 ? 70000 : 0;
    }
    
    return 0;
}

extern "C" int get_adreno_model(char *value) {
    /* Android 12+ standardized property */
    if (__system_property_get("ro.soc.model", value) > 0) {
        /* SM8750 = Snapdragon 8 Elite (Adreno 830) */
		/* Elite-class / A8xx GPUs */
        if (strstr(value, "SM8850")) return 840;  /* 8 Elite Gen 5 / Elite 2 */
        if (strstr(value, "SM8845")) return 830;  /* 8 Gen 5 (flagship variant) */
        if (strstr(value, "SM8750")) return 830;  /* 8 Elite Gen 4 */
		if (strstr(value, "CQ8725S")) return 830; /* Dragonwing Q8 */
		if (strstr(value, "SM8735")) return 825;  /* 8s Gen 4 */
		if (strstr(value, "SM7635")) return 810;  /* Snapdragon 7s Gen 3 and 4 */
		if (strstr(value, "SM6650")) return 810;  /* Snapdragon 6 Gen 4 */
		
        /* extend as needed */
		if (value[0] != 0) return 0;		// If we got a value just return the string
    }

    /* Fallback: some OEMs use this instead */
    if (__system_property_get("ro.hardware.chipname", value) > 0) {
		if (strstr(value, "sm8850") || strstr(value, "SM8850")) return 840;
        if (strstr(value, "sm8750") || strstr(value, "SM8750")) return 830;
		
		if (value[0] != 0) return 0;		// If we got a value just return the string
    }

    /* Another fallback */
    if (__system_property_get("ro.board.platform", value) > 0) {
		if (strcmp(value, "pineapple") == 0) return 830;  /* SM8750 */
        if (strcmp(value, "sun") == 0)       return 840;  /* SM8850 (rumoured) */
    }

    return 0;
}

#define KGSL_DEVICE_GETPROPERTY    _IOWR(0x09, 0x02, struct kgsl_device_getproperty)

static unsigned int get_gpu_chip_id(void) {
    int fd = open("/dev/kgsl-3d0", O_RDWR);
    if (fd < 0) {
        LOGE("VulkanShim: cannot open /dev/kgsl-3d0: %s", strerror(errno));
        return 0;
    }

    struct kgsl_devinfo info = {0};
    struct kgsl_device_getproperty prop = {
        .type = KGSL_PROP_DEVICE_INFO,
        .value = &info,
        .sizebytes = sizeof(info)
    };

    int ret = ioctl(fd, KGSL_DEVICE_GETPROPERTY, &prop);
    close(fd);

    if (ret < 0) {
        LOGE("VulkanShim: KGSL getproperty failed: %s", strerror(errno));
        return 0;
    }

    LOGI("VulkanShim: KGSL chip_id=0x%08x gpu_id=0x%x gmem=%zuKB",
         info.chip_id, info.gpu_id, info.gmem_sizebytes / 1024);

    return info.chip_id;
}

// Not working - most likely just needs a look up table from Mesa's src/freedreno/common/freedreno_devices.py
static int chip_id_to_adreno(unsigned int chip_id) {
    unsigned int core = (chip_id >> 24) & 0xFF;
    unsigned int major = (chip_id >> 16) & 0xFF;

    if (core == 0x06)
        return 600 + major * 10;   /* 610, 620, 630, 640, 650, 660, 680 */
    if (core == 0x07)
        return 700 + major * 10;   /* 710, 720, 730, 740, 750 */
    if (core >= 0x43) {
        /* a8xx uses a different encoding — decode from major/minor */
        unsigned int minor = (chip_id >> 8) & 0xFF;
        /* 0x43050a01 = Adreno 830, 0x43050801 = Adreno 825 (approximate) */
        /* Use gpu_id from KGSL instead for precise a8xx identification */
        return 800 + major * 10;
    }
    if (core == 0x05)
        return 500 + major * 10;   /* 505, 506, 508, 509, 510, 512, 530, 540 */

    return 0; /* unknown */
}

/* ------------------------------------------------------------------ */
/* Handheld / Snapdragon auto driver pick                              */
/* ------------------------------------------------------------------ */

struct TurnipProfile {
    const char *driver;          /* bare .so filename in lib dir */
    const char *tu_debug;        /* NULL = leave unset */
    const char *fd_dev_features; /* NULL = leave unset */
    int disable_fbfetch;         /* -1 = leave g_disable_fbfetch alone */
    float display_hz;            /* 0 = leave g_display_hz alone */
    const char *label;           /* logged + shown as build default */
};

static int android_api_level(void) {
    char val[PROP_VALUE_MAX] = {0};
    if (__system_property_get("ro.build.version.sdk", val) > 0)
        return atoi(val);
    return 0;
}

static int stristr(const char *hay, const char *needle) {
    size_t nlen;
    if (!hay || !needle || !needle[0]) return 0;
    nlen = strlen(needle);
    for (; *hay; hay++) {
        if (strncasecmp(hay, needle, nlen) == 0) return 1;
    }
    return 0;
}

static int soc_match(const char *soc, const char *plat, const char *chip, const char *needle) {
    if (!needle || !needle[0]) return 0;
    if (soc && (strcmp(soc, needle) == 0 || stristr(soc, needle))) return 1;
    if (plat && strcasecmp(plat, needle) == 0) return 1;
    if (chip && stristr(chip, needle)) return 1;
    return 0;
}

/* Parse "Adreno (TM) 650", "Adreno740v2", "Adreno 730" -> 650, 740, 730. */
static int parse_adreno_gpu_num(const char *gpu_name) {
    const char *p;
    int n;
    if (!gpu_name || !gpu_name[0]) return 0;
    p = gpu_name;
    while (*p) {
        if (isdigit((unsigned char)*p)) {
            n = 0;
            while (isdigit((unsigned char)*p)) {
                n = n * 10 + (*p - '0');
                p++;
            }
            if (n >= 500 && n <= 900) return n;
            continue;
        }
        p++;
    }
    return 0;
}

static int driver_bundled(const char *bare) {
    char path[600];
    if (!bare || !bare[0] || !g_lib_dir[0]) return 0;
    snprintf(path, sizeof(path), "%s%s", g_lib_dir, bare);
    return access(path, F_OK) == 0;
}

static const char *first_bundled_driver(const char *a, const char *b, const char *c) {
    if (a && driver_bundled(a)) return a;
    if (b && driver_bundled(b)) return b;
    if (c && driver_bundled(c)) return c;
    return "libvulkan_freedreno_T28.so";
}

static const char *pick_gen8_driver(int api) {
    /* T29 needs bionic pthread affinity exports (API 36+). */
    if (api >= 36)
        return first_bundled_driver(
            "libvulkan_freedreno_T29.so",
            "libvulkan_freedreno_a8xx-turnip-gen8-V31.so",
            "libvulkan_freedreno_T28.so");
    return first_bundled_driver(
        "libvulkan_freedreno_a8xx-turnip-gen8-V31.so",
        "libvulkan_freedreno_T28.so",
        NULL);
}

/*
 * Map ro.soc.model / ro.board.platform / KGSL GPU name to the best bundled
 * Turnip for retro handhelds. turnip.conf driver= still wins via apply_turnip_conf().
 *
 * Handheld coverage (2026):
 *   SM8250/kona     — RP Mini/V2/5, Flip 2, Odin Lite, Mangmi Pocket Max
 *   SM8550/QCS8550 — Odin2/Portal/Mini, Thor, RP6, Pocket ACE/DS/DMG/EVO/S
 *   SM8650          — Pocket S2, KONKR Pocket Fit
 *   SM8750/pineapple — Odin 3
 *   SM8635          — 8s Gen 3 handhelds (Adreno 735)
 *   SM8450/taro     — 8 Gen 1 class
 *   SM8350/lahaina  — SD888 / Odin 1
 *   SD845/napali    — Odin Lite (older), RP2 era
 *   SM6125/trinket  — budget Qualcomm handhelds
 *   SM6475 / 710-722 — Adreno 710/720 class
 */
static TurnipProfile pick_turnip_profile(const char *soc, const char *plat,
        const char *chip, const char *gpu_name, int adreno_elite) {
    TurnipProfile p = {};
    int api = android_api_level();
    int gpu = parse_adreno_gpu_num(gpu_name);

    p.driver = "libvulkan_freedreno_T28.so";
    p.label = "generic T28 fallback";
    p.disable_fbfetch = -1;

    /* --- Elite Adreno 8xx (get_adreno_model numeric tier) --- */
    if (adreno_elite == 810) {
        p.driver = first_bundled_driver("libvulkan_freedreno_T24.so", NULL, NULL);
        p.label = "Adreno 810 (7s/6 Gen class)";
        return p;
    }
    if (adreno_elite >= 825) {
        p.driver = pick_gen8_driver(api);
        p.label = "Adreno 8xx elite";
        if (adreno_elite == 825) p.disable_fbfetch = 1;
        return p;
    }

    /* --- SD665 / trinket (Adreno 610) --- */
    if (soc_match(soc, plat, chip, "SM6125") || soc_match(soc, plat, chip, "trinket")) {
        p.driver = first_bundled_driver("libvulkan_freedreno_T19.so", NULL, NULL);
        p.label = "SD665 / trinket";
        p.disable_fbfetch = 1;
        return p;
    }

    /* --- a6xx handheld tier: SD865, SD888, SD845, 778G (Adreno 630–660) --- */
    if (soc_match(soc, plat, chip, "SM8250") || soc_match(soc, plat, chip, "kona") ||
        soc_match(soc, plat, chip, "SM7325") ||
        soc_match(soc, plat, chip, "SM8350") || soc_match(soc, plat, chip, "lahaina") ||
        soc_match(soc, plat, chip, "napali") || soc_match(soc, plat, chip, "sdm845") ||
        soc_match(soc, plat, chip, "SDM845") ||
        (gpu >= 630 && gpu <= 660)) {
        p.driver = first_bundled_driver(
            "libvulkan_freedreno_v24.1.0_R18.a6xx-Patched.so",
            "libvulkan_freedreno_T28.so", NULL);
        p.label = "a6xx-Patched (SD865/888/845 class)";
        p.disable_fbfetch = 1;
        if (soc_match(soc, plat, chip, "SM8250") || soc_match(soc, plat, chip, "kona"))
            p.display_hz = 59.94f;
        return p;
    }

    /* --- Adreno 710 / 720 / 722 (incl. SD 6 Gen 3 SM6475) --- */
    if (gpu == 710 || gpu == 720 || gpu == 722 ||
        soc_match(soc, plat, chip, "SM6475")) {
        p.driver = first_bundled_driver(
            "libvulkan_freedreno_V710_722_v35.so",
            "libvulkan_freedreno_25.3.0_R6_Gmem.so",
            "libvulkan_freedreno_T28.so");
        p.tu_debug = "gmem";
        p.label = "Adreno 710/720/722";
        return p;
    }

    /* --- SD720G / SM7125 (Logitech G Cloud class, Adreno 618) --- */
    if (soc_match(soc, plat, chip, "SM7125") || soc_match(soc, plat, chip, "sdm720") ||
        gpu == 618 || gpu == 619) {
        p.driver = first_bundled_driver(
            "libvulkan_freedreno_T19.so",
            "libvulkan_freedreno_v24.1.0_R18.a6xx-Patched.so",
            NULL);
        p.label = "SD720G / Adreno 618";
        p.disable_fbfetch = 1;
        return p;
    }

    /* --- SD8 Gen 1 (Adreno 730) --- */
    if (soc_match(soc, plat, chip, "SM8450") || soc_match(soc, plat, chip, "taro") ||
        gpu == 730) {
        p.driver = pick_gen8_driver(api);
        p.tu_debug = "gmem,nolrz";
        p.label = "SD8 Gen 1 / Adreno 730";
        return p;
    }

    /* --- SD8 Gen 2 handhelds (Adreno 740): Odin2, Thor, RP6, Pocket ACE… --- */
    if (gpu == 740 || gpu == 735 ||
        soc_match(soc, plat, chip, "8550") ||   /* SM8550, QCS8550, SSM8550 */
        soc_match(soc, plat, chip, "SM8635") ||
        soc_match(soc, plat, chip, "kalama")) {
        p.driver = pick_gen8_driver(api);
        p.tu_debug = "gmem,nolrz";
        p.label = "SD8 Gen 2 handheld / Adreno 740";
        if (get_oneui_version() >= 70000 || (gpu_name && strstr(gpu_name, "Adreno740v2")))
            p.fd_dev_features = "enable_tp_ubwc_flag_hint=1";
        return p;
    }

    /* --- SD8 Gen 3 (Adreno 750): Pocket S2, KONKR Fit --- */
    if (soc_match(soc, plat, chip, "SM8650") || gpu == 750) {
        p.driver = pick_gen8_driver(api);
        p.tu_debug = "gmem,nolrz";
        p.label = "SD8 Gen 3 / Adreno 750";
        return p;
    }

    /* --- SD8 Elite (Adreno 830): Odin 3 --- */
    if (soc_match(soc, plat, chip, "SM8750") || soc_match(soc, plat, chip, "pineapple") ||
        gpu == 830) {
        p.driver = pick_gen8_driver(api);
        p.label = "SD8 Elite / Adreno 830";
        return p;
    }

    if (soc_match(soc, plat, chip, "SM8850") || soc_match(soc, plat, chip, "sun") ||
        gpu == 840) {
        p.driver = pick_gen8_driver(api);
        p.label = "SD8 Elite Gen 2 / Adreno 840";
        return p;
    }

    /* --- Adreno 7xx fallback --- */
    if (gpu >= 710) {
        p.driver = pick_gen8_driver(api);
        if (gpu >= 735) p.tu_debug = "gmem,nolrz";
        p.label = "Adreno 7xx default";
        return p;
    }

    /* --- Last resort: gen8 then T28 --- */
    p.driver = pick_gen8_driver(api);
    p.label = "generic gen8";
    return p;
}

static void apply_turnip_profile(const TurnipProfile *p) {
    extern int g_disable_fbfetch;
    extern float g_display_hz;
    if (!p || !p->driver) return;
    snprintf(g_turnip_path, sizeof(g_turnip_path), "%s%s", g_lib_dir, p->driver);
    LOGI("VulkanShim: auto profile '%s' -> %s", p->label, p->driver);
    if (p->tu_debug && p->tu_debug[0]) {
        setenv("TU_DEBUG", p->tu_debug, 1);
        LOGI("VulkanShim: profile TU_DEBUG=%s", p->tu_debug);
    }
    if (p->fd_dev_features && p->fd_dev_features[0]) {
        setenv("FD_DEV_FEATURES", p->fd_dev_features, 1);
        LOGI("VulkanShim: profile FD_DEV_FEATURES=%s", p->fd_dev_features);
    }
    if (p->disable_fbfetch >= 0) {
        g_disable_fbfetch = p->disable_fbfetch;
        LOGI("VulkanShim: profile disable_fbfetch=%d", g_disable_fbfetch);
    }
    if (p->display_hz > 0.0f && g_display_hz <= 0.0f)
        g_display_hz = p->display_hz;
}

// Random Notes :)
// Hitman 2: Blood Money - "Texture Inside RT" - this inbuilt fix is causing the rainbow effect. Hold on the game to go game properties to get to the game fixes.
// Pocket ACE - Adreno 740 - T28 = 24-27 (Punisher Accurate), v26.2.0_R4 = 25-28 
/* ------------------------------------------------------------------ */
/* Constructor                                                         */
/* ------------------------------------------------------------------ */

int g_disable_fbfetch = 0;

/* ------------------------------------------------------------------ */
/* Declared frame rate for the presentation surface.                   */
/*                                                                    */
/* A PS2 is locked to 59.94 Hz (NTSC) or 50 Hz (PAL) and nothing here  */
/* changes that. What this does is TELL Android what rate the app      */
/* actually presents at, so the compositor can pick a display mode     */
/* that divides evenly into it.                                        */
/*                                                                    */
/* Left undeclared, a 120 Hz panel commonly sits at 90 Hz or hops      */
/* around under adaptive refresh. 59.94 into 90 does not divide, so    */
/* frames land on uneven refresh boundaries - visible as exactly the   */
/* recurring micro-stutter you get on an otherwise full-speed game.    */
/* Declaring 59.94 as a FIXED_SOURCE rate lets Android choose 120 Hz   */
/* (2 refreshes per frame) or 60 Hz (1:1); both are even.              */
/*                                                                    */
/* Default 59.94 for NTSC. PAL games want 50. turnip.conf: display_hz  */
/* ------------------------------------------------------------------ */
float g_display_hz = 59.94f;   /* <= 0 disables the call entirely */
/* Parsed from turnip.conf enable_60fps; when off, skip ANativeWindow_setFrameRate. */
static int g_enable_60fps_seen = 0;
static int g_enable_60fps = 0;

#define SHIM_PRESENT_IMMEDIATE    0
#define SHIM_PRESENT_MAILBOX      1
#define SHIM_PRESENT_FIFO         2
#define SHIM_PRESENT_FIFO_RELAXED 3

int g_present_mode = -1;      /* -1 = leave the core's choice alone */
/* turnip.conf presenter_probe=1 — observation-only hooks that identify the
 * core's display pass, so a Xenia-style presenter can be written against
 * facts rather than guesses. Costs a couple of branches per draw when on. */
int g_presenter_probe = 0;
static uint32_t g_panel_w = 0, g_panel_h = 0;  /* the surface's real extent */
/* GS framebuffer (from upscale_multiplier). Observation only. */
static uint32_t g_gs_w = 0, g_gs_h = 0;
int g_min_image_count = 0;    /*  0 = leave alone */

/* Present cadence lock — turnip.conf cadence_lock=60 sleeps before each present
 * so fast frames land on a 60 Hz grid (USM 60fps on SD865). Does not synthesize
 * frames when the emu is slow; pairs with fifo@60 + SyncToHost in the USM ini. */
static int g_cadence_lock_hz = 0;
static uint64_t g_cadence_last_present_ns = 0;

/* FSR1 spatial upscaler (turnip.conf upscaler=fsr1) */
int   g_upscaler = FSR_UPSCALER_OFF;
float g_fsr_sharpness = 0.5f;
int   g_fsr_quality = FSR_QUALITY_BALANCED;

/* Synthetic frame generation (turnip.conf framegen=on) */
int   g_framegen = 0;
int   g_framegen_mode = FG_MODE_BLEND;
float g_framegen_alpha = 0.5f;
/* TRANSFER_SRC|DST on the swapchain was rejected by the surface (retry without
 * TRANSFER succeeded). Do not force transfer again until a fresh surface. */
static int g_transfer_rejected = 0;
int   g_fg_multiplier = 2;
float g_flow_scale = 0.75f;
static int g_turnip_framegen_locked = 0;
static void *g_physical_device = NULL;

/* Render at a fraction of the panel and let EASU do the upscale (turnip.conf
 * render_scale=). 0 = off, which is the default and the only state in which any
 * of the render-scale code below runs at all. */
float g_render_scale = 0.0f;

static int get_files_dir(char *out, size_t outsz);

static void shim_clamp_fsr_framegen_off(void) {
#if !NETHER_FSR_FRAMEGEN_ACTIVE
    g_upscaler = FSR_UPSCALER_OFF;
    g_framegen = 0;
    g_framegen_mode = FG_MODE_BLEND;
    g_render_scale = 0.0f;
#else
    (void)0;
#endif
}

/* ====================================================================== *
 * In-process frame generation — turnip.conf lsfg_overlay.                *
 *                                                                        *
 * NOTE THE NAME. `g_framegen` in this file is the OLD Vulkan-layer route  *
 * and is compiled out by NETHER_NO_FSR_FRAMEGEN. This is a different      *
 * feature: LSFG runs in-process on the GS colour target and posts to its  *
 * own SurfaceView. It must NOT be gated on NETHER_FSR_FRAMEGEN_ACTIVE or  *
 * on shim_postprocess_enabled(), both of which are hard-off in this       *
 * build.                                                                  *
 * ====================================================================== */
static int g_fg_overlay = 0;   /* lsfg_overlay — the pipeline itself      */
/* fg_capture_src — where framegen's source frames come from.
 *
 * 1 = the SWAPCHAIN image handed to vkQueuePresentKHR. There is exactly one
 *     such image per present and it IS the frame, by definition. This deletes
 *     the entire class of failure the GS route lives with: no candidate set to
 *     choose from (seven distinct VkImages were logged at one extent), no
 *     "which of these two live handles is the real one", and nothing PCSX2 can
 *     present without telling us — which is what the black-panel-in-settings
 *     bug is. The known price is real and measured (see the usage forcing in
 *     hooked_CreateSwapchainKHR): TRANSFER_SRC on a presentable image costs
 *     UBWC compression.
 * 0 = the old GS-target latch. Kept switchable at runtime precisely because
 *     the UBWC cost is resolution-dependent and had never been measured at the
 *     IR this device actually runs. */
/* DEFAULT TO THE GS TARGET, NOT THE SWAPCHAIN.
 *
 * Swapchain capture is the universal route and it is what made framegen work at
 * all, but measured side by side on Ultimate Spider-Man with the 60 fps patch it
 * loses: gs reads "live (panel), real 60, gen 60, out 119, skipped 0" where the
 * swapchain route sat at "waiting, out 0" with the output never promoting. The
 * blit is 1:1 out of the render target instead of a 1280x960 -> 1024x896
 * downscale, and it costs no UBWC. fg_capture_src=swapchain is still there for
 * games whose target cannot be identified. */
/* turnip.conf fix_rotation=on — rewrite a rotated swapchain preTransform to
 * IDENTITY and let SurfaceFlinger rotate instead.
 *
 * Ported from the swapchain tree, whose note said no device there ever reported
 * anything but IDENTITY. The AYN Thor does: its panel is physically portrait
 * (1080x1920) driven in landscape, so the surface carries a rotated transform and
 * LSFG's output panel came out 90 degrees off while the emulator's own picture and
 * the Android OSD stayed upright. Default OFF — on an IDENTITY device this is a
 * no-op, and forcing it everywhere would gamble with a working picture. */
static int g_fix_rotation = 0;
static int g_fg_capture_src = 0;
static int fg_swapchain_capture_wanted(void) {
#if defined(NETHER_NO_INPROCESS_FRAMEGEN) && NETHER_NO_INPROCESS_FRAMEGEN
    return 0;
#else
    return g_fg_overlay && g_fg_capture_src;
#endif
}

/* Bind fg_source to the PCSX2 render device only — never LSFG's internal
 * compute device. vkGetDeviceProcAddr fired during LSFG initContext
 * (volkLoadDevice) used to call fg_set_device on that device; when the
 * emulator later created its own VkDevice, fg_notify_device_lost() ran
 * vkWaitForFences/vkDestroy* against the LSFG device while fg-ctx was inside
 * presentContext → SIGSEGV (pc=0) on Adreno 650 / RP Mini. */
static VkDevice g_fg_bound_dev = NULL;

static int device_create_info_has_swapchain(const void *pCreateInfo) {
    if (!pCreateInfo) return 0;
    const unsigned char *ci = (const unsigned char *)pCreateInfo;
    uint32_t n = *(const uint32_t *)(ci + 48);
    const char *const *names = *(const char *const **)(ci + 56);
    for (uint32_t i = 0; i < n && names && i < 64; i++) {
        if (names[i] && !strcmp(names[i], "VK_KHR_swapchain"))
            return 1;
    }
    return 0;
}

static void fg_bind_capture_device(VkDevice dev);
static int g_fg_experimental = 0; /* fg_experimental — invasive probes, default OFF */
static int g_fg_osd     = 0;   /* fg_osd — instrument only, independent   */
static int g_fg_force   = 0;   /* fg_force — legacy perf gate             */
static int shim_framegen_wanted(void) {
#if defined(NETHER_NO_INPROCESS_FRAMEGEN) && NETHER_NO_INPROCESS_FRAMEGEN
    return 0;
#else
    return g_fg_overlay || g_fg_osd;
#endif
}

static int shim_postprocess_enabled(void) {
#if !NETHER_FSR_FRAMEGEN_ACTIVE
    return 0;
#else
    /* Presenter FSR is CASMode, not shim upscaler. Only Frame Gen hooks present. */
    return g_framegen;
#endif
}

/* HARD OFF. Do not substitute swapchain images. Vulkan only permits presenting
 * images the swapchain owns; handing the app FSR-allocated buffers from
 * vkGetSwapchainImagesKHR takes SurfaceFlinger down with the process. A leftover
 * render_scale= line in turnip.conf must not be able to arm this. */
static int shim_render_scale_wanted(void) {
    return 0;
}

/* PCSX2 sometimes passes a non-null garbage pointer (e.g. 0x1) during init. */
static int shim_usable_ptr(const void *p, size_t bytes) {
    uintptr_t a = (uintptr_t)p;
    if (!p || a < 0x1000 || (a & 0x3))
        return 0;
    (void)bytes;
    return 1;
}

static const char *present_name(int m) {
    switch (m) {
        case SHIM_PRESENT_IMMEDIATE:    return "IMMEDIATE";
        case SHIM_PRESENT_MAILBOX:      return "MAILBOX";
        case SHIM_PRESENT_FIFO:         return "FIFO";
        case SHIM_PRESENT_FIFO_RELAXED: return "FIFO_RELAXED";
        default:                        return "?";
    }
}


/* ------------------------------------------------------------------ */
/* turnip.conf — on-device overrides, no rebuild and no adb required.  */
/*                                                                    */
/* Lives beside vulkan_shim.log in the app's external files dir, which */
/* any file manager can write. Applied AFTER auto-detection, so every  */
/* key here wins. Recognised keys:                                     */
/*                                                                    */
/*   driver=libvulkan_freedreno_T28.so   bare name (looked up in the   */
/*                                       apk lib dir) or absolute path */
/*   tu_debug=gmem,nolrz                 Mesa TU_DEBUG                 */
/*   fd_dev_features=enable_tp_ubwc_flag_hint=1                        */
/*   disable_fbfetch=1                   hide fb-fetch from the core   */
/* ------------------------------------------------------------------ */

static int get_files_dir(char *out, size_t outsz) {
    char *pkg_start = strstr(g_lib_dir, "/data/app/");
    if (!pkg_start) return 0;
    char *after_hash = strchr(pkg_start + 10, '/');
    if (!after_hash) return 0;
    after_hash++;
    char *dash = strstr(after_hash, "-");
    if (!dash) return 0;
    char pkg[256] = {0};
    size_t n = (size_t)(dash - after_hash);
    if (n >= sizeof(pkg)) return 0;
    memcpy(pkg, after_hash, n);
    snprintf(out, outsz, "/sdcard/Android/data/%s/files", pkg);
    return 1;
}

static void trim(char *s) {
    char *p = s + strlen(s);
    while (p > s && (p[-1] == '\n' || p[-1] == '\r' || p[-1] == ' ' || p[-1] == '\t')) *--p = '\0';
    p = s;
    while (*p == ' ' || *p == '\t') p++;
    if (p != s) memmove(s, p, strlen(p) + 1);
}

static void apply_vulkan_shim_ini_key(const char *key, char *val) {
    trim(val);
    if (val[0] == '\0') return;
    if (!strcmp(key, "Upscaler") || !strcmp(key, "upscaler")) {
        if (!strcasecmp(val, "fsr1")) g_upscaler = FSR_UPSCALER_FSR1;
        else if (!strcasecmp(val, "off") || !strcasecmp(val, "none")) g_upscaler = FSR_UPSCALER_OFF;
    } else if (!strcmp(key, "FsrQuality") || !strcmp(key, "fsr_quality")) {
        if (!strcasecmp(val, "performance")) g_fsr_quality = FSR_QUALITY_PERFORMANCE;
        else if (!strcasecmp(val, "ultra")) g_fsr_quality = FSR_QUALITY_ULTRA;
        else if (!strcasecmp(val, "4x")) g_fsr_quality = FSR_QUALITY_4X;
        else g_fsr_quality = FSR_QUALITY_BALANCED;
    } else if (!strcmp(key, "FsrSharpness") || !strcmp(key, "fsr_sharpness")) {
        float s = (float)atof(val);
        if (s > 1.0f) s = s / 100.0f;
        if (s < 0.0f) s = 0.0f;
        if (s > 1.0f) s = 1.0f;
        g_fsr_sharpness = s;
    } else if (!strcmp(key, "fg_push_direct")) {
        /* off routes captured frames through the Java bridge instead of
         * lsfg_bridge_push_frame. See fg_source.cpp. */
        fg_set_push_direct((!strcasecmp(val, "on") || !strcasecmp(val, "1") ||
                            !strcasecmp(val, "true")) ? 1 : 0);
        LOGI("VulkanShim: turnip.conf fg_push_direct=%s", val);
    } else if (!strcmp(key, "FrameGen") || !strcmp(key, "framegen")) {
        if (g_turnip_framegen_locked) return;
        g_framegen = (!strcasecmp(val, "on") || !strcasecmp(val, "1") ||
                      !strcasecmp(val, "true")) ? 1 : 0;
    } else if (!strcmp(key, "FrameGenMode") || !strcmp(key, "framegen_mode")) {
        if (g_turnip_framegen_locked) return;
        if (!strcasecmp(val, "flow") || !strcasecmp(val, "optical_flow"))
            g_framegen_mode = FG_MODE_FLOW;
        else if (!strcasecmp(val, "optical_flow_stub"))
            g_framegen_mode = FG_MODE_OPTICAL_FLOW_STUB;
        else if (!strcasecmp(val, "off") || !strcasecmp(val, "none")) {
            g_framegen_mode = FG_MODE_OFF;
            g_framegen = 0;
        } else
            g_framegen_mode = FG_MODE_BLEND;
    } else if (!strcmp(key, "FrameGenMultiplier") || !strcmp(key, "framegen_multiplier")) {
        if (g_turnip_framegen_locked) return;
        g_fg_multiplier = atoi(val);
        if (g_fg_multiplier < 2) g_fg_multiplier = 2;
        if (g_fg_multiplier > 4) g_fg_multiplier = 4;
    } else if (!strcmp(key, "FlowScale") || !strcmp(key, "flow_scale")) {
        float f = (float)atof(val);
        if (f > 1.0f) f = f / 100.0f;
        if (f < 0.25f) f = 0.25f;
        if (f > 1.0f) f = 1.0f;
        g_flow_scale = f;
    } else if (!strcmp(key, "UpscalerFsr1")) {
        if (!strcasecmp(val, "true") || !strcmp(val, "1"))
            g_upscaler = FSR_UPSCALER_FSR1;
        else if (!strcasecmp(val, "false") || !strcmp(val, "0"))
            g_upscaler = FSR_UPSCALER_OFF;
    } else if (!strcmp(key, "FrameGenFlow")) {
        if (g_turnip_framegen_locked) return;
        if (!strcasecmp(val, "true") || !strcmp(val, "1")) g_framegen_mode = FG_MODE_FLOW;
    } else if (!strcmp(key, "FsrQuality4x")) {
        if (!strcasecmp(val, "true") || !strcmp(val, "1")) g_fsr_quality = FSR_QUALITY_4X;
    } else if (!strcmp(key, "CadenceLock") || !strcmp(key, "cadence_lock")) {
        if (!strcasecmp(val, "off") || !strcasecmp(val, "none") || !strcmp(val, "0"))
            g_cadence_lock_hz = 0;
        else {
            int hz = atoi(val);
            if (hz >= 30 && hz <= 240) g_cadence_lock_hz = hz;
        }
    } else if (!strcmp(key, "DisplayHz") || !strcmp(key, "display_hz")) {
        if (!strcasecmp(val, "off") || !strcasecmp(val, "none"))
            g_display_hz = 0.0f;
        else {
            float hz = (float)atof(val);
            if (hz > 0.0f && hz <= 240.0f) g_display_hz = hz;
        }
    } else if (!strcmp(key, "PresentMode") || !strcmp(key, "present_mode")) {
        if (!strcasecmp(val, "immediate"))    g_present_mode = SHIM_PRESENT_IMMEDIATE;
        else if (!strcasecmp(val, "mailbox")) g_present_mode = SHIM_PRESENT_MAILBOX;
        else if (!strcasecmp(val, "fifo"))    g_present_mode = SHIM_PRESENT_FIFO;
        else if (!strcasecmp(val, "fifo_relaxed")) g_present_mode = SHIM_PRESENT_FIFO_RELAXED;
        else if (!strcasecmp(val, "auto"))    g_present_mode = -1;
    }
}

static void apply_vulkan_shim_ini_file(const char *path) {
    FILE *f = fopen(path, "r");
    if (!f) return;
    char line[512];
    int in_section = 0;
    while (fgets(line, sizeof(line), f)) {
        trim(line);
        if (line[0] == '\0' || line[0] == '#' || line[0] == ';') continue;
        if (line[0] == '[') {
            in_section = !strcasecmp(line, "[VulkanShim]");
            continue;
        }
        if (!in_section) continue;
        char *eq = strchr(line, '=');
        if (!eq) continue;
        *eq = '\0';
        char *key = line, *val = eq + 1;
        trim(key); trim(val);
        apply_vulkan_shim_ini_key(key, val);
    }
    fclose(f);
}

static void apply_vulkan_shim_ini(void) {
    char dir[512];
    if (!get_files_dir(dir, sizeof(dir))) return;
    char path[640];
    snprintf(path, sizeof(path), "%s/PCSX2.ini", dir);
    apply_vulkan_shim_ini_file(path);
    char gs_dir[600];
    snprintf(gs_dir, sizeof(gs_dir), "%s/gamesettings", dir);
    DIR *d = opendir(gs_dir);
    if (d) {
        struct dirent *ent;
        while ((ent = readdir(d)) != NULL) {
            const char *name = ent->d_name;
            size_t nlen = strlen(name);
            if (nlen < 5 || strcmp(name + nlen - 4, ".ini") != 0) continue;
            snprintf(path, sizeof(path), "%s/%s", gs_dir, name);
            apply_vulkan_shim_ini_file(path);
        }
        closedir(d);
    }
    LOGI("VulkanShim: applied [VulkanShim] from ini files");
}

/* Written out on first launch so the file is simply there to edit, rather than
   something the user has to create by hand. Every key is commented out, so a
   freshly written template changes nothing. */
static const char TURNIP_CONF_TEMPLATE[] =
R"CONF(# turnip.conf - NetherSX2-Turnip Vulkan shim overrides
#
# This file was created automatically on first launch. Everything is
# commented out, so as it stands it changes nothing.
#
# Uncomment a line, force-stop the app, relaunch. Delete the file to go
# back to fully automatic behaviour. After each change check the tail of
# vulkan_shim.log in this same folder - the shim logs the driver it
# settled on and the final flag values, so you can confirm rather than
# assume.

# ---------------------------------------------------------------------
# driver - which Turnip build to load. Bare filename is looked up in the
# app's own lib dir; an absolute path is used as-is.
#
#   libvulkan_freedreno_T29.so                        Mr Purple T29, Mesa 26.2.0
#   libvulkan_freedreno_T28.so                        Mr Purple T28
#   libvulkan_freedreno_25.3.0_R6_Gmem.so             K11MCH1 Gmem build
#   libvulkan_freedreno_a8xx-turnip-gen8-V31.so       StevenMXZ Gen8
#   libvulkan_freedreno_T24.so                        Mr Purple T24
#   libvulkan_freedreno_v24.1.0_R18.a6xx-Patched.so   patched a6xx
#   libvulkan_freedreno_T19.so                        Mr Purple T19
#   libvulkan_freedreno_V710_722_v35.so               vauzi 710/720/722 v3.5,
#                                                     Mesa 26.3.0 / Vulkan 1.4.359
#
# The vauzi build targets Adreno 710, 720 and 722 specifically. It is here for
# handhelds on those parts, NOT for the 8 Gen 2 this was developed on, where it
# is the wrong device family. Untested here for that reason.
#
# On a Snapdragon 8 Gen 2 this build already defaults to T29. The ones
# worth comparing it against are T28 and the Gmem build. Benchmark the
# same save state and the same busy scene on each.
# ---------------------------------------------------------------------
#driver=libvulkan_freedreno_T29.so

# ---------------------------------------------------------------------
# tu_debug - Mesa TU_DEBUG flags.
#
#   gmem    force the tiled/GMEM path instead of sysmem rendering
#   nolrz   DISABLE low-resolution Z
#
# Read that second one carefully. LRZ is a *performance* feature: Turnip
# builds a coarse depth buffer during binning and uses it to reject
# fragments before the full-resolution depth test. Mesa documents nolrz
# as a way to narrow down hangs and artefacts - a debugging switch, not
# an optimisation.
#
# Upstream forces "gmem,nolrz" on this SoC, so an 8 Gen 2 behaving as
# intended runs with early-Z rejection OFF. If your games don't show the
# artefacts nolrz exists to fix, keeping LRZ on should be faster. Nobody
# has published a measurement on Adreno 740. So measure it:
#
#   1.  (no tu_debug line)   whatever the shim decides
#   2.  tu_debug=gmem        tiled path, LRZ still ON   <- try this
#   3.  tu_debug=gmem,nolrz  what upstream intends
#
# If 2 beats 3 and nothing renders wrong, stay on 2. Depth artefacts,
# flickering geometry or hangs are what nolrz is for - then use 3.
# ---------------------------------------------------------------------
#tu_debug=gmem

# ---------------------------------------------------------------------
# fd_dev_features - Mesa FD_DEV_FEATURES. The shim sets the UBWC hint by
# itself on One UI 7+ and on devices reporting Adreno740v2. Force it here
# if yours reports something else but shows UBWC-style corruption.
# ---------------------------------------------------------------------
#fd_dev_features=enable_tp_ubwc_flag_hint=1

# ---------------------------------------------------------------------
# disable_fbfetch - hide the rasterization-order-attachment-access
# extensions so the emulator stops using framebuffer fetch. Costs
# performance; use only to fix rendering. The Turnip README cites Gran
# Turismo 4's black background. 1 = hide, 0 = leave alone.
# ---------------------------------------------------------------------
#disable_fbfetch=0

# ---------------------------------------------------------------------
# display_hz - 120 Hz / high-refresh panel support.  DEFAULT: 59.94
#
# This does NOT make the game run faster or render more frames. A PS2 is
# locked to 59.94 Hz (NTSC) or 50 Hz (PAL) and nothing can change that.
#
# What it does is tell Android what rate this app actually presents at,
# so the compositor picks a display mode that divides evenly into it.
# Left undeclared, a 120 Hz panel often settles at 90 Hz or hops around
# under adaptive refresh - and 59.94 into 90 does not divide, so frames
# land on uneven refresh boundaries. That is visible as regular micro-
# stutter even when the emulator reports a solid 100% speed.
#
# Declared as a fixed-source rate, Android can pick 120 Hz (2 refreshes
# per frame) or 60 Hz (1:1). Both are even, and the judder goes away.
#
#   59.94   NTSC games - the default, correct for most libraries
#   50      PAL games
#   off     don't declare anything (the old behaviour)
#
# If you mostly play PAL, set 50. If you see no difference, the panel was
# already picking a sane mode and this costs nothing.
# ---------------------------------------------------------------------
#display_hz=59.94

# ---------------------------------------------------------------------
# lsfg_overlay - in-process frame generation.  DEFAULT: on
#
# Interpolates an extra frame between each pair the emulator produces, so
# a 60 fps game presents at 120 on a 120 Hz panel. It ADDS latency; it
# does not reduce it.
#
# It needs the shaders out of Lossless Scaling's Lossless.dll, which is
# not ours to ship. Without that file this does nothing at all, and the
# app offers to import your own copy on launch.
#
# Defaulted ON because the alternative is a feature nobody can find: with
# no key here it stays off, and there is then nothing in the UI that
# reliably turns it on.
#
# Costs roughly 10 percentage points of GPU. Above Internal Resolution
# 2x it stops paying for itself on an 8 Gen 2 - the overlay says so, and
# at 3x it is refused outright (see fg_max_ir).
# ---------------------------------------------------------------------
# SHIPPED OFF. Frame generation needs a user-supplied Lossless.dll, and
# turning it on has costs that are still unmeasured on anything but the
# developer's own device: reading the presented image forces TRANSFER_SRC
# onto the swapchain, which costs UBWC compression for the WHOLE app, and
# interpolation adds at least one frame of input latency by construction.
# A default that charges every user for a feature most cannot even use is
# not a default. The Graphics > Frame Generation switch turns it on.
lsfg_overlay=off
fg_osd=off
)CONF";

static void write_default_conf(const char *path) {
    FILE *f = fopen(path, "w");
    if (!f) {
        LOGE("VulkanShim: could not create %s: %s", path, strerror(errno));
        return;
    }
    fwrite(TURNIP_CONF_TEMPLATE, 1, sizeof(TURNIP_CONF_TEMPLATE) - 1, f);
    fclose(f);
    LOGI("VulkanShim: wrote default turnip.conf to %s", path);
}

/* Read one boolean key straight from turnip.conf, independent of parse order.
 * Needed because the gate below consults fg_force while handling lsfg_overlay,
 * and the file may list them in either order. */
static int shim_conf_flag_set(const char *key) {
    char dir[512];
    if (!get_files_dir(dir, sizeof(dir))) return 0;
    char path[600];
    snprintf(path, sizeof(path), "%s/turnip.conf", dir);
    FILE *f = fopen(path, "r");
    if (!f) return 0;
    char line[256];
    int on = 0;
    size_t klen = strlen(key);
    while (fgets(line, sizeof(line), f)) {
        const char *p2 = line;
        while (*p2 == ' ' || *p2 == '\t') p2++;
        if (strncmp(p2, key, klen) != 0) continue;
        const char *v = p2 + klen;
        while (*v == ' ' || *v == '\t') v++;
        if (*v != '=') continue;
        v++;
        while (*v == ' ' || *v == '\t') v++;
        on = (!strncasecmp(v, "on", 2) || *v == '1' || !strncasecmp(v, "true", 4));
    }
    fclose(f);
    return on;
}

static void apply_turnip_conf(void) {
    char dir[512];
    if (!get_files_dir(dir, sizeof(dir))) return;

    mkdir(dir, 0755);  /* may not exist yet on a fresh install */

    char path[600];
    snprintf(path, sizeof(path), "%s/turnip.conf", dir);

    /* SoC auto-pick from shim_init; restore if driver= points at a missing file. */
    char auto_driver[600];
    strncpy(auto_driver, g_turnip_path, sizeof(auto_driver) - 1);
    auto_driver[sizeof(auto_driver) - 1] = '\0';

    FILE *f = fopen(path, "r");
    if (!f) {
        /* First launch: drop the template in place so it is there to edit.
         *
         * PARSE WHAT WE JUST WROTE. This used to return here, which left every
         * key at its compiled-in default for the life of the process. That is
         * survivable for keys whose default matches the template, and fatal for
         * the framegen ones: reload_turnip_conf_hot() below does not know
         * lsfg_overlay, so once this early return was taken nothing could ever
         * set g_fg_overlay again. Enabling Frame Generation wrote lsfg_overlay=on,
         * Java read the file and reported the pipeline LIVE, and native never
         * captured a thing — measured on a fresh install 2026-08-17: zero
         * "turnip.conf" lines in the native log, zero pushes, and the OSD stuck
         * on "rebuilding". */
        write_default_conf(path);
        LOGI("VulkanShim: no overrides yet, wrote the default turnip.conf");
        f = fopen(path, "r");
        if (!f) return;
    }
    LOGI("VulkanShim: reading overrides from %s", path);

    /* Game/profile ini first; turnip.conf (Graphics toggles) wins on top. */
    apply_vulkan_shim_ini();

    char line[512];
    while (fgets(line, sizeof(line), f)) {
        trim(line);
        if (line[0] == '\0' || line[0] == '#' || line[0] == ';') continue;

        char *eq = strchr(line, '=');
        if (!eq) continue;
        *eq = '\0';
        char *key = line, *val = eq + 1;
        trim(key); trim(val);
        if (val[0] == '\0') continue;

        if (strcmp(key, "driver") == 0) {
            if (val[0] == '/')
                snprintf(g_turnip_path, sizeof(g_turnip_path), "%s", val);
            else
                snprintf(g_turnip_path, sizeof(g_turnip_path), "%s%s", g_lib_dir, val);

            if (access(g_turnip_path, F_OK) == 0) {
                LOGI("VulkanShim: turnip.conf driver -> %s", g_turnip_path);
            } else {
                LOGE("VulkanShim: turnip.conf driver NOT FOUND: %s (keeping auto-selected %s)",
                     g_turnip_path, auto_driver[0] ? auto_driver : "?");
                if (auto_driver[0])
                    snprintf(g_turnip_path, sizeof(g_turnip_path), "%s", auto_driver);
            }
        } else if (strcmp(key, "tu_debug") == 0) {
            setenv("TU_DEBUG", val, 1);
            LOGI("VulkanShim: turnip.conf TU_DEBUG=%s", val);
        } else if (strcmp(key, "fd_dev_features") == 0) {
            setenv("FD_DEV_FEATURES", val, 1);
            LOGI("VulkanShim: turnip.conf FD_DEV_FEATURES=%s", val);
        } else if (strcmp(key, "disable_fbfetch") == 0) {
            g_disable_fbfetch = (atoi(val) != 0);
            LOGI("VulkanShim: turnip.conf disable_fbfetch=%d", g_disable_fbfetch);
        } else if (strcmp(key, "present_mode") == 0) {
            if      (!strcasecmp(val, "immediate"))    g_present_mode = SHIM_PRESENT_IMMEDIATE;
            else if (!strcasecmp(val, "mailbox"))      g_present_mode = SHIM_PRESENT_MAILBOX;
            else if (!strcasecmp(val, "fifo"))         g_present_mode = SHIM_PRESENT_FIFO;
            else if (!strcasecmp(val, "fifo_relaxed")) g_present_mode = SHIM_PRESENT_FIFO_RELAXED;
            else if (!strcasecmp(val, "auto"))         g_present_mode = -1;
            else LOGE("VulkanShim: unknown present_mode '%s' (immediate|mailbox|fifo|fifo_relaxed|auto)", val);
        } else if (strcmp(key, "min_image_count") == 0) {
#if NETHER_FSR_FRAMEGEN_ACTIVE
            g_min_image_count = atoi(val);
#endif
        } else if (strcmp(key, "cadence_lock") == 0) {
            if (!strcasecmp(val, "off") || !strcasecmp(val, "none") || !strcasecmp(val, "0"))
                g_cadence_lock_hz = 0;
            else {
                int hz = atoi(val);
                if (hz >= 30 && hz <= 240)
                    g_cadence_lock_hz = hz;
                else
                    LOGE("VulkanShim: cadence_lock=%s out of range (30-240)", val);
            }
            LOGI("VulkanShim: turnip.conf cadence_lock=%d", g_cadence_lock_hz);
        } else if (strcmp(key, "display_hz") == 0) {
            if (strcasecmp(val, "off") == 0 || strcasecmp(val, "none") == 0) {
                g_display_hz = 0.0f;
                LOGI("VulkanShim: turnip.conf display_hz=off (not declaring a rate)");
            } else {
                float hz = (float)atof(val);
                if (hz < 0.0f || hz > 240.0f) {
                    LOGE("VulkanShim: turnip.conf display_hz=%s out of range, ignoring", val);
                } else {
                    g_display_hz = hz;
                    LOGI("VulkanShim: turnip.conf display_hz=%.2f", g_display_hz);
                }
            }
        } else if (strcmp(key, "upscaler") == 0) {
            if (!strcasecmp(val, "off") || !strcasecmp(val, "none"))
                g_upscaler = FSR_UPSCALER_OFF;
            else if (!strcasecmp(val, "fsr1"))
                g_upscaler = FSR_UPSCALER_FSR1;
            else
                LOGE("VulkanShim: unknown upscaler '%s' (off|fsr1)", val);
            LOGI("VulkanShim: turnip.conf upscaler=%s (%d)",
                 g_upscaler == FSR_UPSCALER_FSR1 ? "fsr1" : "off", g_upscaler);
        } else if (strcmp(key, "fsr_sharpness") == 0) {
            float s = (float)atof(val);
            if (s < 0.0f) s = 0.0f;
            if (s > 1.0f) s = 1.0f;
            g_fsr_sharpness = s;
            LOGI("VulkanShim: turnip.conf fsr_sharpness=%.2f", g_fsr_sharpness);
        } else if (strcmp(key, "fsr_quality") == 0) {
            if (!strcasecmp(val, "performance"))
                g_fsr_quality = FSR_QUALITY_PERFORMANCE;
            else if (!strcasecmp(val, "ultra"))
                g_fsr_quality = FSR_QUALITY_ULTRA;
            else if (!strcasecmp(val, "balanced"))
                g_fsr_quality = FSR_QUALITY_BALANCED;
            else if (!strcasecmp(val, "4x"))
                g_fsr_quality = FSR_QUALITY_4X;
            else
                LOGE("VulkanShim: unknown fsr_quality '%s' (performance|balanced|ultra|4x)", val);
            LOGI("VulkanShim: turnip.conf fsr_quality=%d", g_fsr_quality);
        } else if (strcmp(key, "framegen") == 0) {
#if NETHER_FSR_FRAMEGEN_ACTIVE
            g_framegen = (!strcasecmp(val, "on") || !strcasecmp(val, "1") ||
                          !strcasecmp(val, "true")) ? 1 : 0;
            g_turnip_framegen_locked = 1;
            LOGI("VulkanShim: turnip.conf framegen=%s", g_framegen ? "on" : "off");
#else
            /* stripped build — ignore legacy framegen keys silently */
#endif
        } else if (strcmp(key, "framegen_mode") == 0) {
#if NETHER_FSR_FRAMEGEN_ACTIVE
            if (!strcasecmp(val, "off") || !strcasecmp(val, "none")) {
                g_framegen_mode = FG_MODE_OFF;
                if (g_turnip_framegen_locked)
                    g_framegen = 0;
            } else if (!strcasecmp(val, "blend"))
                g_framegen_mode = FG_MODE_BLEND;
            else if (!strcasecmp(val, "flow") || !strcasecmp(val, "optical_flow"))
                g_framegen_mode = FG_MODE_FLOW;
            else if (!strcasecmp(val, "optical_flow_stub"))
                g_framegen_mode = FG_MODE_OPTICAL_FLOW_STUB;
            else
                LOGE("VulkanShim: unknown framegen_mode '%s' (off|blend|flow|optical_flow_stub)", val);
            LOGI("VulkanShim: turnip.conf framegen_mode=%d", g_framegen_mode);
#endif
        } else if (strcmp(key, "framegen_alpha") == 0) {
#if NETHER_FSR_FRAMEGEN_ACTIVE
            float a = (float)atof(val);
            if (a < 0.0f) a = 0.0f;
            if (a > 1.0f) a = 1.0f;
            g_framegen_alpha = a;
            LOGI("VulkanShim: turnip.conf framegen_alpha=%.2f", g_framegen_alpha);
#endif
        } else if (strcmp(key, "framegen_multiplier") == 0) {
#if NETHER_FSR_FRAMEGEN_ACTIVE
            g_fg_multiplier = atoi(val);
            if (g_fg_multiplier < 2) g_fg_multiplier = 2;
            if (g_fg_multiplier > 4) g_fg_multiplier = 4;
            LOGI("VulkanShim: turnip.conf framegen_multiplier=%d", g_fg_multiplier);
#endif
        } else if (strcmp(key, "flow_scale") == 0) {
#if NETHER_FSR_FRAMEGEN_ACTIVE
            float f = (float)atof(val);
            if (f < 0.25f) f = 0.25f;
            if (f > 1.0f) f = 1.0f;
            g_flow_scale = f;
            LOGI("VulkanShim: turnip.conf flow_scale=%.2f", g_flow_scale);
#endif
        } else if (strcmp(key, "min_image_count") == 0) {
#if NETHER_FSR_FRAMEGEN_ACTIVE
            g_min_image_count = atoi(val);
#endif
        } else if (strcmp(key, "render_scale") == 0) {
            /* Fraction of the panel the emulator is told to render at. Accepts
             * 0.5 and 50 alike, the same way fsr_sharpness does. Anything
             * outside (0.25, 1.0] is refused rather than clamped: silently
             * turning render_scale=0.1 into 0.25 would hand back a frame at a
             * resolution nobody asked for. */
            float rs = (float)atof(val);
            if (rs > 1.0f && rs <= 100.0f) rs /= 100.0f;
            if (!(rs >= 0.25f && rs <= 1.0f)) {
                g_render_scale = 0.0f;
                LOGE("VulkanShim: turnip.conf render_scale=%s outside [0.25, 1.0] "
                     "— render-scale off", val);
            } else {
                g_render_scale = rs;
                LOGI("VulkanShim: turnip.conf render_scale=%.3f (will not arm — "
                     "swapchain image substitution is unsound)", g_render_scale);
            }
        } else if (strcmp(key, "presenter_probe") == 0) {
            g_presenter_probe = (atoi(val) != 0);
            LOGI("VulkanShim: turnip.conf presenter_probe=%d", g_presenter_probe);
        } else if (strcmp(key, "enable_60fps") == 0) {
            g_enable_60fps_seen = 1;
            g_enable_60fps = (!strcasecmp(val, "on") || !strcasecmp(val, "1") ||
                              !strcasecmp(val, "true"));
            LOGI("VulkanShim: turnip.conf enable_60fps=%s", g_enable_60fps ? "on" : "off");
        } else if (strcmp(key, "lsfg_overlay") == 0) {
            /* LSFG dlopens the SYSTEM libvulkan.so and builds its own volk table
             * from it -- verified: liblsfg-android.so has zero undefined Vulkan
             * imports and carries the strings "libvulkan.so"/"libvulkan.so.1".
             * It therefore never touches this shim's Turnip ICD, which is why
             * swapping drivers, priming volk and enabling features on the
             * emulator's device all changed nothing.
             *
             * Its frame-generation path needs VK_EXT_robustness2 nullDescriptor.
             * Qualcomm's blob exposes that on Adreno 7xx and newer only; on 6xx
             * it is absent and LSFG dereferences a null descriptor ~0.7 s after
             * the context goes live, killing the game. LSFG-Android and Eden both
             * fall back to capture-only on 6xx for exactly this reason.
             *
             * So refuse on the GPU that LSFG will actually run on, not on panel
             * refresh. fg_force=on overrides for anyone wanting to test. */
            /* CORRECTION (measured): the "Adreno 7xx only" rule from upstream does
             * NOT hold here. LSFG's own log on this Adreno 650 reads
             *   Vulkan session ready (compute family=0, 16 extensions,
             *                         robustness2=yes, swapchain=yes)
             * so nullDescriptor is available and is NOT the cause. The crash cause
             * is still unknown; this gate is empirical, not explanatory: framegen
             * reliably kills the game ~0.7 s after arming on Adreno 6xx here.
             *
             * fg_force is read from the SAME file and may appear after this key, so
             * the file is consulted directly rather than trusting parse order. */
            if ((!strcasecmp(val, "on") || !strcasecmp(val, "1") ||
                 !strcasecmp(val, "true")) && !g_fg_force
                && !shim_conf_flag_set("fg_force")) {
                char gname[128] = {0};
                int adreno = 0;
                /* Returns 1 on success and fills the NAME; the model number has
                 * to come out of the string. Observed forms: "Adreno (TM) 650",
                 * "Adreno650v3", "Adreno 740". First digit run is the model. */
                if (get_adreno_gpu_id(gname, sizeof(gname))) {
                    const char *d = gname;
                    while (*d && (*d < '0' || *d > '9')) d++;
                    if (*d) adreno = atoi(d);
                }
                if (adreno > 0 && adreno < 700) {
                    LOGE("VulkanShim: frame generation disabled on %s: it reliably "
                         "crashes the game about a second after arming on this GPU "
                         "class, cause not yet identified (LSFG reports robustness2 "
                         "available, so that is NOT it). Set fg_force=on in "
                         "turnip.conf to try it anyway.", gname[0] ? gname : "this GPU");
                    g_fg_overlay = 0;
                    return;
                }
            }
            g_fg_overlay = (!strcasecmp(val, "on") || !strcasecmp(val, "1") ||
                            !strcasecmp(val, "true"));
            fg_set_enabled(g_fg_overlay);
            /* Push the source too, so the two agree whatever order the keys
             * appear in — fg_capture_src defaults on this side, and a default
             * that only exists in one of two files is a default that drifts. */
            if (g_fg_capture_src) fg_arm_present_route();
            fg_set_capture_source(g_fg_capture_src);
            LOGI("VulkanShim: turnip.conf lsfg_overlay=%s (in-process framegen)",
                 g_fg_overlay ? "on" : "off");
        } else if (strcmp(key, "fg_osd") == 0) {
            g_fg_osd = (!strcasecmp(val, "on") || !strcasecmp(val, "1") ||
                        !strcasecmp(val, "true"));
            LOGI("VulkanShim: turnip.conf fg_osd=%s", g_fg_osd ? "on" : "off");
        } else if (strcmp(key, "fix_rotation") == 0) {
            g_fix_rotation = (!strcasecmp(val, "on") || !strcmp(val, "1")
                              || !strcasecmp(val, "true"));
            LOGI("VulkanShim: turnip.conf fix_rotation=%s",
                 g_fix_rotation ? "on" : "off");
        } else if (strcmp(key, "fg_capture_src") == 0) {
            g_fg_capture_src = !(!strcasecmp(val, "gs") || !strcasecmp(val, "target")
                                 || !strcasecmp(val, "0"));
            if (g_fg_capture_src) fg_arm_present_route();
            fg_set_capture_source(g_fg_capture_src);
            LOGI("VulkanShim: turnip.conf fg_capture_src=%s -> capture is now %s "
                 "(present route armed=%d)",
                 g_fg_capture_src ? "swapchain" : "gs",
                 fg_capture_is_from_present() ? "PRESENT" : "GS",
                 fg_present_route_armed());
        } else if (strcmp(key, "fg_force") == 0) {
            g_fg_force = (!strcasecmp(val, "on") || !strcasecmp(val, "1") ||
                          !strcasecmp(val, "true"));
            fg_set_force(g_fg_force);
            LOGI("VulkanShim: turnip.conf fg_force=%s", g_fg_force ? "on" : "off");
        } else if (strcmp(key, "fg_experimental") == 0) {
            /* Everything I tried against the Adreno 650 framegen crash, behind one
             * key, default OFF. Injecting device extensions and chaining a
             * robustness2 feature into PCSX2's OWN device is invasive -- it rides in
             * the rendering path, and a build carrying it unconditionally broke
             * graphics on a user's device. None of it fixed the crash, so it has no
             * business being on by default; it stays only so the next session can
             * re-run it deliberately. */
            g_fg_experimental = (!strcasecmp(val, "on") || !strcasecmp(val, "1") ||
                                 !strcasecmp(val, "true"));
            LOGI("VulkanShim: turnip.conf fg_experimental=%s",
                 g_fg_experimental ? "on" : "off");
        } else if (strcmp(key, "fg_present_mode") == 0) {
            /* mailbox (default) | fifo. MUST sit above the fg_* catch-all. */
            extern int g_fg_prefer_mailbox;
            g_fg_prefer_mailbox = !strcasecmp(val, "fifo") ? 0 : 1;
            LOGI("VulkanShim: turnip.conf fg_present_mode=%s -> prefer_mailbox=%d",
                 val, g_fg_prefer_mailbox);
        } else if (strcmp(key, "fg_aspect") == 0) {
            /* "4:3", "16:9", a bare float, or "fill" to stretch (old behaviour).
             * MUST sit above the fg_* catch-all below or it is swallowed. */
            extern float g_fg_output_aspect;
            float a = 0.0f;
            if (!strcasecmp(val, "fill") || !strcasecmp(val, "stretch")) {
                a = 0.0f;
            } else {
                float w = 0, hh = 0;
                if (sscanf(val, "%f:%f", &w, &hh) == 2 && hh > 0) a = w / hh;
                else a = (float)atof(val);
            }
            g_fg_output_aspect = a;
            LOGI("VulkanShim: turnip.conf fg_aspect=%s -> %.4f", val, (double)a);
        } else if (strcmp(key, "fg_pretransform") == 0) {
            /* identity (default) = compositor rotates, no compute pass and the
             * game's aspect is preserved. native = app pre-rotates (old route).
             * MUST sit above the fg_* catch-all below or it is swallowed. */
            extern int g_fg_pretransform_identity;
            g_fg_pretransform_identity = !strcasecmp(val, "native") ? 0 : 1;
            LOGI("VulkanShim: turnip.conf fg_pretransform=%s -> identity=%d",
                 val, g_fg_pretransform_identity);
        } else if (strcmp(key, "fg_panel_rotate") == 0) {
            /* Degrees -> NATIVE_WINDOW_TRANSFORM_*: ROT_90=4, ROT_180=3, ROT_270=7.
             * MUST sit above the fg_* catch-all below or it is swallowed. */
            extern int g_fg_panel_transform;
            int d = atoi(val);
            g_fg_panel_transform = (d == 90) ? 4 : (d == 180) ? 3 : (d == 270) ? 7 : -1;
            LOGI("VulkanShim: turnip.conf fg_panel_rotate=%d -> window transform %d",
                 d, g_fg_panel_transform);
        } else if (strcmp(key, "fg_test_pattern") == 0) {
            extern int g_fg_test_pattern;
            g_fg_test_pattern = (!strcasecmp(val, "on") || !strcasecmp(val, "1") ||
                                 !strcasecmp(val, "true"));
            LOGI("VulkanShim: turnip.conf fg_test_pattern=%s (DIAGNOSTIC: capture "
                 "writes a changing colour instead of the game)",
                 g_fg_test_pattern ? "on" : "off");
        } else if (strcmp(key, "fg_push_direct") == 0) {
            /* MUST sit above the fg_* catch-all below, which would otherwise
             * swallow it as a Java-side key and silently do nothing. */
            int on = (!strcasecmp(val, "on") || !strcasecmp(val, "1") ||
                      !strcasecmp(val, "true"));
            fg_set_push_direct(on);
            LOGI("VulkanShim: turnip.conf fg_push_direct=%s", on ? "on" : "off");
        } else if (strncmp(key, "fg_", 3) == 0 && key[3]) {
            /* fg_capture_w/h, fg_multiplier, fg_flow_scale, fg_queue_depth,
             * fg_target_fps_cap are read Java-side by ShimFrameGen. Recognised
             * so they do not read as "unknown key" noise. */
            LOGI("VulkanShim: turnip.conf %s=%s (Java-side framegen key)", key, val);
        } else if (strcmp(key, "toggles_owner") == 0) {
            /* Java-side keys (pnach gate / toggle ownership). Recognised so they
             * do not read as "unknown key" noise in the log. */
            LOGI("VulkanShim: turnip.conf %s=%s (Java-side key)", key, val);
        } else {
            LOGI("VulkanShim: turnip.conf ignoring unknown key '%s'", key);
        }
    }
    fclose(f);

    if (!g_framegen && g_min_image_count > 4)
        g_min_image_count = 4;

    shim_clamp_fsr_framegen_off();
    if (g_transfer_rejected)
        g_framegen = 0;

    /* Frame Gen TRANSFER on the swapchain drops UBWC on Turnip; leaving the
     * UBWC hint enabled while FG copies/blits the presentable image caused
     * full-screen purple on Adreno 740 Nova. */
    if (g_framegen) {
        unsetenv("FD_DEV_FEATURES");
        LOGI("VulkanShim: FG on — FD_DEV_FEATURES UBWC hint cleared");
    }

    fsr_set_config(g_upscaler, g_fsr_sharpness, g_fsr_quality);
#if NETHER_FSR_FRAMEGEN_ACTIVE
    fg_set_config(g_framegen, g_framegen_mode, g_framegen_alpha);
    fg_set_extended(g_fg_multiplier, g_flow_scale, g_display_hz);
#endif
    /* Never arm. Substituting vkGetSwapchainImagesKHR hands the app images the
     * presentation engine does not own; present then takes SurfaceFlinger down. */
    if (g_render_scale > 0.0f)
        LOGI("VulkanShim: render-scale refused (present-time layer cannot "
             "substitute swapchain images)");
    g_render_scale = 0.0f;
#if NETHER_FSR_FRAMEGEN_ACTIVE
    fsr_set_render_scale(0.0f);
#endif
#if NETHER_FSR_FRAMEGEN_ACTIVE
    LOGI("VulkanShim: effective upscaler=%s framegen=%s mode=%d min_images=%d fsr_q=%d "
         "render_scale=%.3f",
         g_upscaler == FSR_UPSCALER_FSR1 ? "fsr1" : "off",
         g_framegen ? "on" : "off", g_framegen_mode, g_min_image_count, g_fsr_quality,
         g_render_scale);
#else
    LOGI("VulkanShim: effective upscaler=%s fsr_q=%d",
         g_upscaler == FSR_UPSCALER_FSR1 ? "fsr1" : "off", g_fsr_quality);
#endif
}

/* Hot-reload upscaler/FSR from turnip.conf while a game is running (no restart). */
static time_t g_turnip_conf_mtime = 0;
static uint32_t g_turnip_hot_reload_counter = 0;

static void reload_turnip_conf_hot(void) {
    char dir[512];
    if (!get_files_dir(dir, sizeof(dir))) return;
    char path[600];
    snprintf(path, sizeof(path), "%s/turnip.conf", dir);
    struct stat st;
    if (stat(path, &st) != 0) return;
    if (st.st_mtime == g_turnip_conf_mtime) return;
    g_turnip_conf_mtime = st.st_mtime;

    FILE *f = fopen(path, "r");
    if (!f) return;
    int prev_up = g_upscaler;
    int prev_q = g_fsr_quality;
    float prev_sh = g_fsr_sharpness;
    int prev_fg = g_framegen;
    int prev_fgm = g_framegen_mode;
    char line[512];
    while (fgets(line, sizeof(line), f)) {
        trim(line);
        if (line[0] == '\0' || line[0] == '#' || line[0] == ';') continue;
        char *eq = strchr(line, '=');
        if (!eq) continue;
        *eq = '\0';
        char *key = line, *val = eq + 1;
        trim(key); trim(val);
        if (strcmp(key, "upscaler") == 0) {
            if (!strcasecmp(val, "fsr1")) g_upscaler = FSR_UPSCALER_FSR1;
            else g_upscaler = FSR_UPSCALER_OFF;
        } else if (strcmp(key, "fsr_quality") == 0) {
            if (!strcasecmp(val, "performance")) g_fsr_quality = FSR_QUALITY_PERFORMANCE;
            else if (!strcasecmp(val, "ultra")) g_fsr_quality = FSR_QUALITY_ULTRA;
            else if (!strcasecmp(val, "4x")) g_fsr_quality = FSR_QUALITY_4X;
            else g_fsr_quality = FSR_QUALITY_BALANCED;
            } else if (strcmp(key, "fsr_sharpness") == 0) {
                float s = (float)atof(val);
                if (s > 1.0f) s = s / 100.0f;
                if (s < 0.0f) s = 0.0f;
                if (s > 1.0f) s = 1.0f;
                g_fsr_sharpness = s;
            } else if (strcmp(key, "render_scale") == 0) {
                float rs = (float)atof(val);
                if (rs > 1.0f && rs <= 100.0f) rs /= 100.0f;
                if (rs >= 0.25f && rs <= 1.0f) g_render_scale = rs;
                else g_render_scale = 0.0f;
            } else if (strcmp(key, "framegen") == 0) {
                g_framegen = (!strcasecmp(val, "on") || !strcasecmp(val, "1") ||
                              !strcasecmp(val, "true")) ? 1 : 0;
                g_turnip_framegen_locked = 1;
            } else if (strcmp(key, "lsfg_overlay") == 0) {
                /* THE FRAMEGEN KEYS MUST BE HOT-RELOADABLE TOO.
                 *
                 * This block used to know only the FSR-era keys, so lsfg_overlay
                 * was read exactly once at process start. On a fresh install the
                 * conf does not exist at that moment, so framegen could never be
                 * turned on without killing the process, and nothing said so:
                 * the switch wrote the file, Java believed it, native did not. */
                int on = (!strcasecmp(val, "on") || !strcasecmp(val, "1") ||
                          !strcasecmp(val, "true")) ? 1 : 0;
                if (on != g_fg_overlay) {
                    g_fg_overlay = on;
                    fg_set_enabled(g_fg_overlay);
                    if (g_fg_capture_src) fg_arm_present_route();
            fg_set_capture_source(g_fg_capture_src);
                    LOGI("VulkanShim: turnip.conf hot lsfg_overlay=%s", on ? "on" : "off");
                }
            } else if (strcmp(key, "fg_osd") == 0) {
                g_fg_osd = (!strcasecmp(val, "on") || !strcasecmp(val, "1") ||
                            !strcasecmp(val, "true")) ? 1 : 0;
            } else if (strcmp(key, "fg_capture_src") == 0) {
                int src = !strcasecmp(val, "gs") ? 0 : 1;
                if (src != g_fg_capture_src) {
                    g_fg_capture_src = src;
                    if (g_fg_capture_src) fg_arm_present_route();
            fg_set_capture_source(g_fg_capture_src);
                    LOGI("VulkanShim: turnip.conf hot fg_capture_src=%s",
                         src ? "swapchain" : "gs");
                }
            } else if (strcmp(key, "min_image_count") == 0) {
                g_min_image_count = atoi(val);
            } else if (strcmp(key, "cadence_lock") == 0) {
                if (!strcasecmp(val, "off") || !strcasecmp(val, "none") || !strcasecmp(val, "0"))
                    g_cadence_lock_hz = 0;
                else {
                    int hz = atoi(val);
                    if (hz >= 30 && hz <= 240)
                        g_cadence_lock_hz = hz;
                }
                LOGI("VulkanShim: turnip.conf hot cadence_lock=%d", g_cadence_lock_hz);
            } else if (strcmp(key, "framegen_mode") == 0) {
                if (!strcasecmp(val, "flow") || !strcasecmp(val, "optical_flow"))
                    g_framegen_mode = FG_MODE_FLOW;
                else if (!strcasecmp(val, "off") || !strcasecmp(val, "none")) {
                    g_framegen_mode = FG_MODE_OFF;
                    g_framegen = 0;
                } else
                    g_framegen_mode = FG_MODE_BLEND;
            }
    }
    fclose(f);
    /* Game ini can still force FSR on — re-apply then let turnip.conf win again. */
    apply_vulkan_shim_ini();
    /* Re-read only upscaler keys from conf so Graphics toggle beats stale game ini. */
    f = fopen(path, "r");
    if (f) {
        while (fgets(line, sizeof(line), f)) {
            trim(line);
            if (line[0] == '\0' || line[0] == '#' || line[0] == ';') continue;
            char *eq = strchr(line, '=');
            if (!eq) continue;
            *eq = '\0';
            char *key = line, *val = eq + 1;
            trim(key); trim(val);
            if (strcmp(key, "upscaler") == 0) {
                if (!strcasecmp(val, "fsr1")) g_upscaler = FSR_UPSCALER_FSR1;
                else g_upscaler = FSR_UPSCALER_OFF;
            } else if (strcmp(key, "fsr_quality") == 0) {
                if (!strcasecmp(val, "4x")) g_fsr_quality = FSR_QUALITY_4X;
                else if (!strcasecmp(val, "performance")) g_fsr_quality = FSR_QUALITY_PERFORMANCE;
                else if (!strcasecmp(val, "ultra")) g_fsr_quality = FSR_QUALITY_ULTRA;
                else g_fsr_quality = FSR_QUALITY_BALANCED;
            } else if (strcmp(key, "fsr_sharpness") == 0) {
                float s = (float)atof(val);
                if (s > 1.0f) s /= 100.0f;
                if (s < 0.0f) s = 0.0f;
                if (s > 1.0f) s = 1.0f;
                g_fsr_sharpness = s;
            } else if (strcmp(key, "framegen") == 0) {
                g_framegen = (!strcasecmp(val, "on") || !strcasecmp(val, "1") ||
                              !strcasecmp(val, "true")) ? 1 : 0;
                g_turnip_framegen_locked = 1;
            } else if (strcmp(key, "min_image_count") == 0) {
                g_min_image_count = atoi(val);
            } else if (strcmp(key, "framegen_mode") == 0) {
                if (!strcasecmp(val, "flow") || !strcasecmp(val, "optical_flow"))
                    g_framegen_mode = FG_MODE_FLOW;
                else if (!strcasecmp(val, "off") || !strcasecmp(val, "none")) {
                    g_framegen_mode = FG_MODE_OFF;
                    g_framegen = 0;
                } else
                    g_framegen_mode = FG_MODE_BLEND;
            }
    }
    fclose(f);
    }
    if (g_transfer_rejected)
        g_framegen = 0;
    shim_clamp_fsr_framegen_off();
    if (prev_up != g_upscaler || prev_q != g_fsr_quality || prev_sh != g_fsr_sharpness
            || prev_fg != g_framegen || prev_fgm != g_framegen_mode) {
        fsr_set_config(g_upscaler, g_fsr_sharpness, g_fsr_quality);
        fg_set_config(g_framegen, g_framegen_mode, g_framegen_alpha);
        fg_set_extended(g_fg_multiplier, g_flow_scale, g_display_hz);
        g_render_scale = 0.0f;
#if NETHER_FSR_FRAMEGEN_ACTIVE
        fsr_set_render_scale(0.0f);
#endif
        LOGI("VulkanShim: hot-reload upscaler=%s fsr_q=%d render_scale=%.3f framegen=%s "
             "(swapchain restart needed for FSR)",
             g_upscaler == FSR_UPSCALER_FSR1 ? "fsr1" : "off", g_fsr_quality, g_render_scale,
             g_framegen ? "on" : "off");
    }
}

void public_resolve_linker_symbols();

extern "C" __attribute__((constructor, used))
void shim_init(void) {
    /* Set g_lib_dir before first log so vulkan_shim.log captures shim_init. */
    Dl_info info;
    if (dladdr((void*)shim_init, &info) && info.dli_fname) {
        strncpy(g_lib_dir, info.dli_fname, sizeof(g_lib_dir) - 1);
        char *slash = strrchr(g_lib_dir, '/');
        if (slash) *(slash + 1) = '\0';
    }
	LOGI("VulkanShim: shim_init");

	/* Before anything reads turnip.conf, so FSR's own lines (config, warmup,
	 * "RCAS active ...") land in vulkan_shim.log and not just logcat. */
	fsr_set_log_fn(shim_fsr_logi, shim_fsr_loge);

	public_resolve_linker_symbols();

    char value[PROP_VALUE_MAX] = {0};
	int adreno_model = get_adreno_model(value);
		
	//setup_turnip_env(1, 1, 1);
	// No TU_DEBUG flags needed — readback barrier hook ensures UBWC
	// metadata coherency before image-to-buffer copies, avoiding
	// the KGSL/SMMU fault without the performance cost of noubwc/flushall
    //setenv("TU_DEBUG", "noubwc,nolrz,flushall", 1);
    //putenv("TU_DEBUG=noubwc,nolrz,flushall");
    
    //LOGI("VulkanShim: TU_DEBUG verify: %s", getenv("TU_DEBUG"));
	
    /* 1. Find our lib dir (may already be set at entry for early logging). */
    if (!g_lib_dir[0]) {
    Dl_info info;
    if (!dladdr((void*)shim_init, &info) || !info.dli_fname) {
        LOGE("VulkanShim: dladdr failed"); return;
    }
    strncpy(g_lib_dir, info.dli_fname, sizeof(g_lib_dir) - 1);
    char *slash = strrchr(g_lib_dir, '/');
    if (!slash) return;
    *(slash + 1) = '\0';
    }
    LOGI("VulkanShim: lib dir = %s", g_lib_dir);
	LOGI("VulkanShim: adreno_model = %d (%s)\n", adreno_model, value);
	LOGI("VulkanShim: build = " __DATE__ " " __TIME__
#if !NETHER_FSR_FRAMEGEN_ACTIVE
	     " (passthrough present)"
#endif
	     );
	
	// Try and get the Adreno version too
	char gpu_name[64] = {0};
	get_adreno_gpu_id(gpu_name, sizeof(gpu_name));
	LOGI("VulkanShim: Adreno GPU = %s", gpu_name);
	unsigned int chip_id = get_gpu_chip_id();
	//int adreno_version = chip_id_to_adreno(chip_id);
	//LOGI("VulkanShim: adreno_version = %d", adreno_version);

	// Check for override
	const char *override_path = "/data/local/tmp/libvulkan_freedreno.so";
	if (access(override_path, F_OK) == 0) 
	{
		strcpy(g_turnip_path, override_path);
		LOGI("VulkanShim: Using Override");
	}
	else 
	{
		char plat[PROP_VALUE_MAX] = {0};
		char chip[PROP_VALUE_MAX] = {0};
		__system_property_get("ro.board.platform", plat);
		__system_property_get("ro.hardware.chipname", chip);
		LOGI("VulkanShim: soc='%s' platform='%s' chip='%s' gpu='%s' elite=%d",
		     value, plat, chip, gpu_name, adreno_model);
		TurnipProfile prof = pick_turnip_profile(value, plat, chip, gpu_name, adreno_model);
		apply_turnip_profile(&prof);
	}
			 
	/* On-device overrides win over everything decided above. */
	apply_turnip_conf();

    LOGI("VulkanShim: Turnip path = %s", g_turnip_path);
	LOGI("VulkanShim: TU_DEBUG=%s FD_DEV_FEATURES=%s disable_fbfetch=%d present_mode=%s minImages=%d",
	     getenv("TU_DEBUG") ? getenv("TU_DEBUG") : "(unset)",
	     getenv("FD_DEV_FEATURES") ? getenv("FD_DEV_FEATURES") : "(unset)",
	     g_disable_fbfetch,
	     g_present_mode < 0 ? "(core's choice)" : present_name(g_present_mode),
	     g_min_image_count);

	/* Make our own symbols globally visible so bundled libs can 
	   resolve android_get_exported_namespace and android_load_sphal_library
	   from our stubs */
	char self_path[512];
	snprintf(self_path, sizeof(self_path), "%slibvulkad.so", g_lib_dir);
	void *self = dlopen(self_path, RTLD_NOW | RTLD_GLOBAL | RTLD_NODELETE);
	if (self) LOGI("VulkanShim: self promoted to RTLD_GLOBAL");
	else      LOGE("VulkanShim: self promotion failed: %s", dlerror());


	/* 2. Promote libs already accessible in our namespace */
	const char *promote[] = { "liblog.so", "libsync.so", NULL };
	for (int i = 0; promote[i]; i++) {
		void *h = dlopen(promote[i], RTLD_NOLOAD | RTLD_GLOBAL);
		if (h) LOGI("VulkanShim: promoted: %s", promote[i]);
		else   LOGE("VulkanShim: not promotable: %s", promote[i]);
	}

	/////////////////////////////////////////////////////
	// libadrenotools approach
	char tmpDir[512] = {0};
	{
		char *ps = strstr(g_lib_dir, "/data/app/");
		if (ps) {
			char *ah = strchr(ps + 10, '/');
			if (ah) {
				ah++;
				char *ds = strstr(ah, "-");
				if (ds) {
					char pkg[256] = {0};
					strncpy(pkg, ah, ds - ah);
					snprintf(tmpDir, sizeof(tmpDir), "/data/data/%s/cache", pkg);
					mkdir(tmpDir, 0755);
				}
			}
		}
	}
	LOGI("VulkanShim: tmpDir = %s", tmpDir);

	if (!linkernsbypass_load_status())
		LOGE("VulkanShim: Failed to linkernsbypass_load_status");
	
	const char *hookLibDir = g_lib_dir;
	 auto hookNs = android_create_namespace("adrenotools-libvulkan", hookLibDir, nullptr, ANDROID_NAMESPACE_TYPE_SHARED, nullptr, nullptr);
    LOGI("VulkanShim: hookNs = %p, err = %s", hookNs, dlerror() ?: "none");
	
	if (hookNs && !linkernsbypass_link_namespace_to_default_all_libs(hookNs))
        LOGE("VulkanShim: link_namespace failed: %s", dlerror() ?: "unknown");
	
	auto hookImpl = hookNs ? linkernsbypass_namespace_dlopen("libhook_impl.so", RTLD_NOW, hookNs) : nullptr;
    LOGI("VulkanShim: hookImpl = %p, err = %s", hookImpl, dlerror() ?: "none");
	
	auto initHookParam = reinterpret_cast<void (*)(const void *)>(dlsym(hookImpl, "init_hook_param"));
	LOGI("VulkanShim: initHookParam = %p, err = %s", initHookParam, dlerror() ?: "none");
	if (!initHookParam)
		LOGE("VulkanShim: Failed to initHookParam");
	
	auto importMapping{[&]() -> adrenotools_gpu_mapping * {
		return nullptr;
	}()};

	int featureFlags = ADRENOTOOLS_DRIVER_CUSTOM;

	// Extract just the filename from g_turnip_path
	const char *turnip_filename = strrchr(g_turnip_path, '/');
	turnip_filename = turnip_filename ? turnip_filename + 1 : g_turnip_path;

	initHookParam(new HookImplParams(featureFlags, tmpDir[0] ? tmpDir : NULL, hookLibDir,  /*customDriverDir*/ g_lib_dir, /*customDriverName*/ turnip_filename,  NULL, importMapping));

	// Load the libvulkan hook into the isolated namespace
	if (!linkernsbypass_namespace_dlopen("libmain_hook.so", RTLD_GLOBAL, hookNs))
		LOGE("VulkanShim: Failed to linkernsbypass_namespace_dlopen libmain_hook.so");
	
	int dlopenFlags = RTLD_NOW;
	
	void *the_vulk = linkernsbypass_namespace_dlopen_unique("/system/lib64/libvulkan.so", tmpDir[0] ? tmpDir : NULL, dlopenFlags, hookNs);

	LOGI("THE INCREDIBLE VULK: %p", the_vulk);

	LOGI("VulkanShim: the_vulk = %p, err = %s", the_vulk, dlerror() ?: "none");

	if (the_vulk) {
		LOGI("VulkanShim: adrenotools approach succeeded: %p", the_vulk);
		g_sys_vulkan = the_vulk;
		LOGI("VulkanShim: init complete (namespace approach)");
		return;  // Skip all GOT patching — hooks are already in the namespace
	}

	LOGI("VulkanShim: namespace approach failed, falling back to GOT patches");

	/* Only load bundled libs for fallback — namespace approach doesn't need them */
	const char *bundled[] = {
		"libc++.so",
		"libbase.so",
		"libcutils.so",
		"libvndksupport.so",
		"libhardware.so",
		NULL
	};
	for (int i = 0; bundled[i]; i++) {
		char path[512];
		snprintf(path, sizeof(path), "%s%s", g_lib_dir, bundled[i]);
		void *h = dlopen(path, RTLD_NOW | RTLD_GLOBAL | RTLD_NODELETE);
		if (h) LOGI("VulkanShim: loaded bundled: %s", bundled[i]);
		else   LOGE("VulkanShim: bundled load failed %s: %s", bundled[i], dlerror());
	}

    /* 4. Pre-load Turnip while all deps are now satisfied */
    g_turnip = dlopen(g_turnip_path, RTLD_NOW | RTLD_GLOBAL | RTLD_NODELETE);
    if (!g_turnip) {
        LOGE("VulkanShim: Turnip pre-load failed: %s", dlerror()); return;
    }
    LOGI("VulkanShim: Turnip pre-loaded OK");

    /* 5. Load system libvulkan.so — we will GOT patch it */
    g_sys_vulkan = dlopen("libvulkan.so", RTLD_NOW | RTLD_GLOBAL | RTLD_NODELETE);
    if (!g_sys_vulkan) {
        LOGE("VulkanShim: failed to load system libvulkan.so: %s", dlerror()); return;
    }
    LOGI("VulkanShim: system libvulkan.so loaded OK");

    /* 6. Save the real android_load_sphal_library before we patch it away */
    g_real_sphal_load = (void *(*)(const char*, int))
        dlsym(RTLD_DEFAULT, "android_load_sphal_library");
    if (!g_real_sphal_load) {
        LOGE("VulkanShim: cannot find real android_load_sphal_library"); return;
    }
    LOGI("VulkanShim: real sphal = %p", g_real_sphal_load);

    /* 7. Patch libvulkan.so GOT */
	uintptr_t vk_base = find_lib_base("libvulkan.so");
	if (!vk_base) { LOGE("VulkanShim: libvulkan base not found"); return; }

	if (patch_got(vk_base, "android_load_sphal_library", (void*)sphal_hook)) {
		LOGI("VulkanShim: libvulkan sphal GOT patched OK");
	} else {
		LOGE("VulkanShim: libvulkan sphal GOT patch FAILED"); return;
	}

	/* Also patch android_dlopen_ext for newer Android versions that use it directly */
	if (patch_got(vk_base, "android_dlopen_ext", (void*)android_dlopen_ext_hook)) {
		LOGI("VulkanShim: libvulkan dlopen_ext GOT patched OK");
	} else {
		LOGI("VulkanShim: libvulkan dlopen_ext not in PLT - OK on older Android");
	}

    /* 8. Patch bundled libhardware.so GOT to use the REAL system sphal
          so Turnip's internal gralloc calls go through the proper
          vendor namespace rather than our bundled libvndksupport */
    uintptr_t libhardware_base = find_lib_base("libhardware.so");
    if (libhardware_base) {
        //if (patch_got_with_real(libhardware_base, "android_load_sphal_library", (void*)g_real_sphal_load)) {    // <--- original
		if (patch_got(libhardware_base, "android_load_sphal_library", (void*)sphal_hook2)) {    // <--- suggested
            LOGI("VulkanShim: libhardware GOT patched OK");
        } else {
            LOGE("VulkanShim: libhardware GOT patch failed");
        }
    }
	
	uintptr_t libcutils_base = find_lib_base("libcutils.so");
	if (libcutils_base) {
		if (patch_got(libcutils_base, "fopen", (void*)fopen_hook)) {
            LOGI("VulkanShim: libcutils fopen GOT patched OK");
        } else {
            LOGE("VulkanShim: libcutils fopen GOT patch failed");
        }
    }

    /* 9. Patch bundled libvndksupport.so GOT for android_get_exported_namespace
          in case it survived patchelf and still needs the real version */
    uintptr_t vndksupport_base = find_lib_base("libvndksupport.so");
    if (vndksupport_base) {
        void *real_get_ns = dlsym(RTLD_DEFAULT, "android_get_exported_namespace");
        if (real_get_ns) {
            if (patch_got_with_real(vndksupport_base,
                                    "android_get_exported_namespace",
                                    real_get_ns)) {
                LOGI("VulkanShim: libvndksupport namespace fn patched");
            }
        }
    }

    LOGI("VulkanShim: init complete");
}

/* ------------------------------------------------------------------ */
/* Forward all vk* calls to the (now patched) system libvulkan.so     */
/* ------------------------------------------------------------------ */
#define FORWARD(ret, name, args_decl, args_call)          \
ret name args_decl {                                       \
    static ret (*fn) args_decl = NULL;                    \
    if (!fn && g_sys_vulkan)                              \
        fn = (ret (*) args_decl)dlsym(g_sys_vulkan, #name); \
    if (!fn) { LOGE("VulkanShim: " #name " not resolved"); \
               return (ret)0; }                           \
    return fn args_call;                                  \
}

#define FORWARD_VOID(name, args_decl, args_call)          \
void name args_decl {                                     \
    LOGI("VulkanShim: " #name " called");                 \
    static void (*fn) args_decl = NULL;                   \
    if (!fn && g_sys_vulkan)                              \
        fn = (void (*) args_decl)dlsym(g_sys_vulkan, #name); \
    if (fn) fn args_call;                                 \
}

/* For VkResult returning functions */
#define FORWARD_VK(name, args_decl, args_call)            \
VkResult name args_decl {                                 \
    LOGI("VulkanShim: " #name " called");                 \
    static VkResult (*fn) args_decl = NULL;               \
    if (!fn && g_sys_vulkan)                              \
        fn = (VkResult (*) args_decl)dlsym(g_sys_vulkan, #name); \
    if (!fn) { LOGE("VulkanShim: " #name " not resolved"); \
               return -3; } /* VK_ERROR_INITIALIZATION_FAILED */ \
    return fn args_call;                                  \
}

/* For PFN_vkVoidFunction returning functions */
#define FORWARD_PFN(name, args_decl, args_call)           \
PFN_vkVoidFunction name args_decl {                       \
    LOGI("VulkanShim: " #name " called");                 \
    static PFN_vkVoidFunction (*fn) args_decl = NULL;     \
    if (!fn && g_sys_vulkan)                              \
        fn = (PFN_vkVoidFunction (*) args_decl)dlsym(g_sys_vulkan, #name); \
    if (!fn) { LOGE("VulkanShim: " #name " not resolved"); \
               return NULL; }                             \
    return fn args_call;                                  \
}

extern "C" {
FORWARD_VK(vkCreateInstance,
    (const void *pCI, const void *pAlloc, VkInstance *pInst),
    (pCI, pAlloc, pInst))
}

/* Forward declaration — defined below vkGetInstanceProcAddr */
/* ---------------- presenter probe (observation only) ---------------- */
/* Answers the questions that decide whether a Xenia-style presenter can be built
 * here: does the core use render pass objects or dynamic rendering, does its
 * swapchain-targeting pass contain a single fullscreen draw, and what vertex count
 * does that draw use. A presenter draws into the swapchain with FSR in the shader,
 * so it needs neither transfer usage (no UBWC loss) nor image substitution (no
 * compositor crash) — but the interception point differs entirely between render
 * pass objects and dynamic rendering. Enabled by turnip.conf presenter_probe=1.
 * g_presenter_probe itself is declared with the other conf globals above. */

typedef void (*PFN_shim_cmd_begin_rp)(VkCommandBuffer, const void *, uint32_t);
typedef void (*PFN_shim_cmd_begin_rendering)(VkCommandBuffer, const void *);
typedef VkResult (*PFN_shim_queue_submit)(void *, uint32_t, const void *, uint64_t);
typedef void (*PFN_shim_cmd_barrier)(VkCommandBuffer, uint32_t, uint32_t, uint32_t,
                                     uint32_t, const void *, uint32_t, const void *,
                                     uint32_t, const void *);
typedef void (*PFN_shim_cmd_draw_indexed)(VkCommandBuffer, uint32_t, uint32_t,
                                          uint32_t, int32_t, uint32_t);
typedef void (*PFN_shim_cmd_draw)(VkCommandBuffer, uint32_t, uint32_t, uint32_t,
                                 uint32_t);
/* ====================================================================== *
 * Framegen GS-target tracking.                                           *
 *                                                                        *
 * fg_note_current_gs() needs the VkImage HANDLE of the GS colour target   *
 * that is about to be rendered into. Nothing hands that over directly, so *
 * it is reconstructed from three cheap maps built as the core creates     *
 * objects:                                                               *
 *                                                                        *
 *   vkCreateImage       -> image handle + extent  (GS candidates only)    *
 *   vkCreateImageView   -> view  -> image                                 *
 *   vkCreateFramebuffer -> fb    -> image (via its attachments' views)     *
 *                                                                        *
 * Then vkCmdBeginRenderPass reads VkRenderPassBeginInfo::framebuffer and  *
 * vkCmdBeginRendering reads the colour attachments' views, and either way *
 * we land on the image.                                                  *
 *                                                                        *
 * THE TRAP: the GS heuristic in hooked_CreateImage also matches PCSX2's   *
 * 256x256 scratch targets (256/512 = 0.500 vs 256/448 = 0.571 — inside    *
 * the 0.08 aspect tolerance). Capturing one of those gives a flawless     *
 * 60 fps of nothing. So the LARGEST extent seen wins, and a candidate     *
 * below it is dropped rather than tracked.                                *
 * ====================================================================== */
#define FG_MAX_TRACK 64

static uint64_t g_fg_img[FG_MAX_TRACK];
static uint32_t g_fg_img_w[FG_MAX_TRACK], g_fg_img_h[FG_MAX_TRACK];
static int      g_fg_img_n;
static uint64_t g_fg_view[FG_MAX_TRACK], g_fg_view_img[FG_MAX_TRACK];
static int      g_fg_view_n;
static uint64_t g_fg_fb[FG_MAX_TRACK], g_fg_fb_img[FG_MAX_TRACK];
static int      g_fg_fb_n;
static uint32_t g_fg_best_w, g_fg_best_h;   /* largest GS extent seen */

static void fg_track_image(uint64_t img, uint32_t w, uint32_t h) {
    if (!img || !w || !h) return;
    /* The PS2 GS is 512x448 at Native, so a smaller target is USUALLY scratch.
     * Without this floor a 256x244 scratch buffer was adopted as the GS (it
     * satisfies the aspect test: 256/512 = 0.50 vs 244/448 = 0.54) and framegen
     * interpolated a scratch buffer. PCSX2 allocates these in pairs, so a "seen
     * twice" rule alone does not exclude them.
     *
     * This used to say IR "only ever scales it UP", which is WRONG in this
     * build — the picker offers 0.5x and 0.75x, whose real display targets are
     * 256x224 and 384x336, below the floor. Untracked meant the render-pass
     * hook could not resolve them, so fg_note_current_gs never reported the new
     * target and capture stayed on the previous resolution's retired buffer:
     * measured 2026-08-16, picking 0.75x left framegen "LIVE at 640x560"
     * showing a frozen 1.25x image.
     *
     * So the floor drops to the smallest real display target (0.5x = 256x224).
     * Note this table does NOT pick the GS — fg_source.cpp does, through
     * fg_is_valid_gs_extent, whose height check still rejects the 256x244
     * scratch (expected h for w=256 is 224, and 244 is outside the 8px band).
     * Tracking one extra candidate costs a table slot; failing to track the
     * real target costs the picture, so the asymmetry favours the lower floor.
     * Deliberately NOT gated on fg_expected_gs_is(): the hint is pushed from
     * Java and must not be a prerequisite for OBSERVING an image. */
    if (w < 256u || h < 224u) return;
    /* THIS MAP IS OBSERVATION ONLY — it does not pick the GS.
     *
     * It used to: the largest extent won, a smaller one displaced it after two
     * sightings in a row, and either way the maps were WIPED. Both halves were
     * wrong. Ultimate Spider-Man at IR 2.75 renders a 704x610 pass every frame
     * beside the real 1408x1232 target, so "twice in a row" adopted it; and the
     * wipe then forgot the live 1408x1232 images, which PCSX2 never recreates.
     * The render-pass hook could no longer resolve the real target, capture
     * stayed on a buffer nothing draws into, and the panel went permanently
     * black at a flawless 119 fps.
     *
     * So: track every plausible candidate and let fg_source.cpp decide which
     * extent is the GS — it is the only side that sees which target is being
     * rendered INTO each frame. Nothing here is ever dropped for being the
     * wrong size, because "wrong size" is not knowable here.
     *
     * Oldest entry is overwritten when full; refusing new entries at the cap
     * would silently stop tracking the target that matters. */
    for (int i = 0; i < g_fg_img_n; i++)
        if (g_fg_img[i] == img) { g_fg_img_w[i] = w; g_fg_img_h[i] = h; return; }
    static int rr;
    int slot;
    if (g_fg_img_n < FG_MAX_TRACK) slot = g_fg_img_n++;
    else                           slot = rr++ % FG_MAX_TRACK;
    g_fg_img[slot] = img;
    g_fg_img_w[slot] = w;
    g_fg_img_h[slot] = h;
    if ((uint64_t)w * h > (uint64_t)g_fg_best_w * g_fg_best_h) {
        g_fg_best_w = w;
        g_fg_best_h = h;
        LOGI("VulkanShim: framegen largest GS candidate now %ux%u",
             (unsigned)w, (unsigned)h);
    }
}

static int fg_img_extent(uint64_t img, uint32_t *w, uint32_t *h) {
    for (int i = 0; i < g_fg_img_n; i++) {
        if (g_fg_img[i] == img) {
            if (w) *w = g_fg_img_w[i];
            if (h) *h = g_fg_img_h[i];
            return 1;
        }
    }
    return 0;
}

/* fg_source.cpp's blit resolves its source rect through this rather than
 * trusting the resize tracker — see the SOURCE MISMATCH note there. */
extern "C" int fg_query_image_extent(uint64_t img, uint32_t *w, uint32_t *h) {
    return fg_img_extent(img, w, h);
}

/* Forget a destroyed image, and every view/framebuffer that resolved to it.
 *
 * These three maps used to be append-only, which is a bug with a long fuse:
 * Vulkan handles may be REUSED after destruction, so a stale entry does not
 * merely go unused — it can answer for a different object later. Worse, the
 * render-pass hook kept resolving a retired framebuffer to a destroyed image,
 * so fg_note_current_gs went on reporting that handle every frame; the
 * "GS target went unreported for 2 s" guard in fg_source.cpp is therefore blind
 * to it, and capture blits a dead buffer forever. Observed 2026-08-16: the same
 * handle reported for minutes with a BLACK panel at a perfect frame rate, and
 * only a save-state load (which recreates everything) brought the picture back.
 *
 * Swap-with-last removal: order in these tables carries no meaning. */
static void fg_untrack_image(uint64_t img) {
    if (!img) return;
    for (int i = 0; i < g_fg_img_n; i++) {
        if (g_fg_img[i] != img) continue;
        int last = --g_fg_img_n;
        g_fg_img[i]   = g_fg_img[last];
        g_fg_img_w[i] = g_fg_img_w[last];
        g_fg_img_h[i] = g_fg_img_h[last];
        g_fg_img[last] = 0; g_fg_img_w[last] = 0; g_fg_img_h[last] = 0;
        break;
    }
    for (int i = 0; i < g_fg_view_n; ) {
        if (g_fg_view_img[i] == img) {
            int last = --g_fg_view_n;
            g_fg_view[i]     = g_fg_view[last];
            g_fg_view_img[i] = g_fg_view_img[last];
            g_fg_view[last] = 0; g_fg_view_img[last] = 0;
        } else i++;
    }
    for (int i = 0; i < g_fg_fb_n; ) {
        if (g_fg_fb_img[i] == img) {
            int last = --g_fg_fb_n;
            g_fg_fb[i]     = g_fg_fb[last];
            g_fg_fb_img[i] = g_fg_fb_img[last];
            g_fg_fb[last] = 0; g_fg_fb_img[last] = 0;
        } else i++;
    }
}

static void fg_untrack_view(uint64_t view) {
    if (!view) return;
    for (int i = 0; i < g_fg_view_n; i++) {
        if (g_fg_view[i] != view) continue;
        int last = --g_fg_view_n;
        g_fg_view[i]     = g_fg_view[last];
        g_fg_view_img[i] = g_fg_view_img[last];
        g_fg_view[last] = 0; g_fg_view_img[last] = 0;
        return;
    }
}

static void fg_untrack_fb(uint64_t fb) {
    if (!fb) return;
    for (int i = 0; i < g_fg_fb_n; i++) {
        if (g_fg_fb[i] != fb) continue;
        int last = --g_fg_fb_n;
        g_fg_fb[i]     = g_fg_fb[last];
        g_fg_fb_img[i] = g_fg_fb_img[last];
        g_fg_fb[last] = 0; g_fg_fb_img[last] = 0;
        return;
    }
}

static void fg_track_view(uint64_t view, uint64_t img) {
    if (!view || !img || !fg_img_extent(img, NULL, NULL)) return;
    for (int i = 0; i < g_fg_view_n; i++)
        if (g_fg_view[i] == view) { g_fg_view_img[i] = img; return; }
    /* Ring, not a hard stop — see fg_track_image. */
    static int rr;
    int slot = g_fg_view_n < FG_MAX_TRACK ? g_fg_view_n++ : rr++ % FG_MAX_TRACK;
    g_fg_view[slot] = view;
    g_fg_view_img[slot] = img;
}

static uint64_t fg_img_of_view(uint64_t view) {
    for (int i = 0; i < g_fg_view_n; i++)
        if (g_fg_view[i] == view) return g_fg_view_img[i];
    return 0;
}

static void fg_track_fb(uint64_t fb, uint64_t img) {
    if (!fb || !img) return;
    for (int i = 0; i < g_fg_fb_n; i++)
        if (g_fg_fb[i] == fb) { g_fg_fb_img[i] = img; return; }
    static int rr;
    int slot = g_fg_fb_n < FG_MAX_TRACK ? g_fg_fb_n++ : rr++ % FG_MAX_TRACK;
    g_fg_fb[slot] = fb;
    g_fg_fb_img[slot] = img;
}

static uint64_t fg_img_of_fb(uint64_t fb) {
    for (int i = 0; i < g_fg_fb_n; i++)
        if (g_fg_fb[i] == fb) return g_fg_fb_img[i];
    return 0;
}

/** Hand the GS target to fg_source, with the image's OWN recorded extent. */
static void fg_notify_target(uint64_t img) {
    uint32_t w = 0, h = 0;
    if (!img || !fg_img_extent(img, &w, &h)) return;
    /* NO AREA FILTER HERE. A "reject anything under half the largest candidate"
     * rule was tried and is WRONG: it assumes every non-GS target is SMALLER.
     * A panel-sized 1280x960 image sets best_area to 1,228,800, and at IR 1.5 the
     * real GS target (768x672 = 516,096) then fails 516096*2 < 1228800 and is
     * discarded -- the pipeline sits at "waiting for GS target (native 0x0)"
     * while the frame it needs is handed to it every frame.
     *
     * It was redundant as well as harmful: fg_note_current_gs already rejects a
     * mismatched extent (fg_is_valid_gs_extent, fg_extent_expected, and the
     * w != S.gs_w check), which is where the 256x256 scratch target was always
     * being dropped. Extent selection belongs there, keyed to the expected GS
     * size from IR -- not to whatever the largest thing seen so far happens to be. */
    /* Log every CHANGE of resolved target, rate-limited. Logging only the first
     * one hid the fault this whole file exists to prevent: when tracking loses
     * the live target this hook simply stops resolving, and a single line from
     * boot looks identical to a healthy stream. */
    static uint64_t last_img;
    static uint64_t last_log_ns;
    if (img != last_img) {
        struct timespec ts;
        clock_gettime(CLOCK_MONOTONIC, &ts);
        uint64_t now = (uint64_t)ts.tv_sec * 1000000000ULL + (uint64_t)ts.tv_nsec;
        if (now - last_log_ns > 2000000000ULL) {
            last_log_ns = now;
            LOGI("VulkanShim: framegen GS target image %llu %ux%u",
                 (unsigned long long)img, (unsigned)w, (unsigned)h);
        }
    }
    last_img = img;
    fg_note_current_gs(img, w, h, 0);
}

static void fg_log_bridge(int is_error, const char *msg) {
    if (is_error) LOGE("VulkanShim: framegen: %s", msg ? msg : "");
    else          LOGI("VulkanShim: framegen: %s", msg ? msg : "");
}

extern "C" PFN_vkVoidFunction vkGetDeviceProcAddr(VkDevice dev, const char *name);

/* Set once the device came from vkCreateDevice WITH VK_KHR_swapchain, i.e. the
 * emulator's own render device. */
static int g_fg_dev_authoritative = 0;

/* LSFG_3_1::Context::present reads its Vulkan entry points from volk's
 * PROCESS-GLOBAL table. Disassembly of liblsfg-android.so: the slot it calls is
 * .bss at 0x213e10 with no relocation (zero at load), written only by
 * volkLoadDevice and zeroed only by volkFinalize -- and volkFinalize has no
 * caller anywhere in the library or in this shim. So the table is simply never
 * loaded, present() calls address 0, and the fg-ctx worker dies ~0.6 s after the
 * context goes live. Measured identically on two Turnip drivers, two app ids and
 * both push paths, which is what ruled everything else out.
 *
 * volkLoadDevice is exported, so load it against the emulator's device -- the
 * same device LSFG itself uses, since no device is ever created without
 * VK_KHR_swapchain here. */
static void fg_prime_lsfg_volk(VkDevice dev) {
    static int done;
    if (done || !dev) return;
    void *h = dlopen("liblsfg-android.so", RTLD_NOW | RTLD_NOLOAD);
    if (!h) return;            /* not mapped yet; retried on the next bind */
    void (*volk_load_device)(VkDevice) =
            (void (*)(VkDevice))dlsym(h, "volkLoadDevice");
    if (!volk_load_device) {
        static int warned;
        if (!warned++) LOGE("VulkanShim: volkLoadDevice not exported by LSFG");
        return;
    }
    volk_load_device(dev);
    done = 1;
    LOGI("VulkanShim: primed LSFG's volk device table (present slot was never "
         "loaded; that null is what killed fg-ctx)");
}

static void fg_bind_capture_device_ex(VkDevice dev, int authoritative) {
    if (!shim_framegen_wanted() || !dev) return;
    LOGI("VulkanShim: framegen bind attempt dev=%p auth=%d (bound=%p authFlag=%d)",
         (void *)dev, authoritative, (void *)g_fg_bound_dev, g_fg_dev_authoritative);
    /* Ahead of the early return below: on the FIRST bind liblsfg-android.so is
     * usually not mapped yet, so priming has to get another chance on the later
     * binds (swapchain creation) once it is. Guarded by `done` inside. */
    if (authoritative || dev == g_fg_bound_dev) { if (g_fg_experimental) fg_prime_lsfg_volk(dev); }
    if (g_fg_bound_dev == dev) {
        if (authoritative) g_fg_dev_authoritative = 1;
        return;
    }
    /* LSFG creates its OWN swapchain, on its OWN compute device, for the output
     * surface -- so hooked_CreateSwapchainKHR is not proof of the emulator's
     * device. Rebinding there hands fg_source the LSFG device, and the next
     * fg_notify_device_lost runs vkWaitForFences/vkDestroy* against it while
     * fg-ctx is inside presentContext: SIGSEGV pc=0 on Adreno 650 / RP Mini,
     * which is the crash the comment on g_fg_bound_dev describes.
     *
     * Only vkCreateDevice(+VK_KHR_swapchain) is authoritative. A real device
     * recreation (IR change, resume) comes back through THAT path and rebinds
     * normally, so this refuses the wrong device without pinning a dead one. */
    if (!authoritative && g_fg_dev_authoritative) {
        /* Was deduped on the device POINTER. The driver reuses handle values
         * across a device recreation (see hooked_DestroyDevice), so a refusal
         * of the SECOND game's device matched the first game's pointer and
         * logged nothing at all -- the refusal that mattered was the invisible
         * one. Deduped on time instead. */
        static uint64_t refused_ms;
        struct timespec rts;
        clock_gettime(CLOCK_MONOTONIC, &rts);
        uint64_t now_ms = (uint64_t)rts.tv_sec * 1000ULL
                        + (uint64_t)rts.tv_nsec / 1000000ULL;
        if (!refused_ms || now_ms - refused_ms > 2000ULL) {
            refused_ms = now_ms;
            LOGI("VulkanShim: framegen keeping the emulator device, refusing a "
                 "swapchain-side rebind dev=%p (likely LSFG's own compute "
                 "device)", (void *)dev);
        }
        return;
    }
    g_fg_bound_dev = dev;
    if (authoritative) g_fg_dev_authoritative = 1;
    fg_set_logger(fg_log_bridge);
    fg_set_device(dev, (void *)vkGetDeviceProcAddr);
    { if (g_fg_experimental) fg_prime_lsfg_volk(dev); }
    LOGI("VulkanShim: framegen bound to emulator device (lsfg_overlay=%s, fg_osd=%s, %s)",
         g_fg_overlay ? "on" : "off", g_fg_osd ? "on" : "off",
         authoritative ? "from vkCreateDevice" : "from swapchain");
}

static void fg_bind_capture_device(VkDevice dev) {
    fg_bind_capture_device_ex(dev, 0);
}

static PFN_shim_cmd_begin_rp        real_CmdBeginRenderPass = NULL;
static PFN_shim_cmd_begin_rendering real_CmdBeginRendering  = NULL;
static PFN_shim_cmd_draw            real_CmdDraw            = NULL;
static PFN_shim_cmd_draw_indexed    real_CmdDrawIndexed     = NULL;
static PFN_shim_cmd_barrier         real_CmdPipelineBarrier = NULL;
static PFN_shim_queue_submit        real_QueueSubmit        = NULL;

/* Observe which queue the core renders on. Our own capture submit goes through
 * the driver pointer fg_source resolved, so it may arrive here too -- harmless,
 * it is the same queue we are trying to converge on. */
static VkResult hooked_QueueSubmit(void *queue, uint32_t count, const void *subs,
                                   uint64_t fence) {
    if (shim_framegen_wanted() && queue) {
        static void *noted;
        if (noted != queue) {
            noted = queue;
            fg_note_render_queue(queue, 0xFFFFFFFFu);
        }
    }
    return real_QueueSubmit ? real_QueueSubmit(queue, count, subs, fence)
                            : (VkResult)0;
}
/* Set once the barrier hook is live; it supersedes draw-based selection. */
static int g_barrier_hook_live = 0;
/* Last GS target to finish rendering (COLOR_ATTACHMENT -> SHADER_READ_ONLY). */
static uint64_t g_last_finished_img = 0;

/* Set while a pass is open, so draws can be attributed to it. */
static int      g_probe_pass_depth    = 0;
static uint32_t g_probe_draws_in_pass = 0;
/* Target bound by the current render pass, notified on its FIRST DRAW. */
static uint64_t g_pass_img = 0;
/* Set once a draw hook is actually installed. Until then the pass-begin notify
 * stays as the fallback, so a failure to hook cannot leave framegen with no
 * source at all. */
static int g_draw_hook_live = 0;

static void hooked_CmdBeginRenderPass(VkCommandBuffer cmd, const void *pBegin,
                                      uint32_t contents) {
    static int logged = 0;
    if (!logged++)
        LOGI("VulkanShim: probe: vkCmdBeginRenderPass called — render pass OBJECTS "
             "in use; a replacement pipeline must be render-pass compatible");
    g_probe_pass_depth++;
    g_probe_draws_in_pass = 0;
    /* VkRenderPassBeginInfo::framebuffer sits at +24. */
    /* Latch the target that is DRAWN INTO, not merely bound.
     *
     * PCSX2 begins passes on several targets per frame and draws into only some.
     * Notifying here latched whichever pass began last, which is how capture ran
     * at 31 captures/s with "0 unique for 8 ticks" on GTA:SA -- a static sibling,
     * faithfully captured, while the live frame went elsewhere. The cost guard
     * then switched framegen off for costing frames it was not earning. */
    if (shim_framegen_wanted() && pBegin) {
        g_pass_img = fg_img_of_fb(
                *(const uint64_t *)((const unsigned char *)pBegin + 24));
        if (!g_draw_hook_live) fg_notify_target(g_pass_img);
    }
    if (real_CmdBeginRenderPass) real_CmdBeginRenderPass(cmd, pBegin, contents);
}

static void hooked_CmdBeginRendering(VkCommandBuffer cmd, const void *pInfo) {
    static int logged = 0;
    if (!logged++)
        LOGI("VulkanShim: probe: vkCmdBeginRendering called — DYNAMIC rendering in "
             "use; there is no render pass object to be compatible with");
    g_probe_pass_depth++;
    g_probe_draws_in_pass = 0;
    /* VkRenderingInfo: colorAttachmentCount +44, pColorAttachments +48;
     * VkRenderingAttachmentInfo stride 64 with imageView at +16. */
    if (shim_framegen_wanted() && pInfo) {
        const unsigned char *p = (const unsigned char *)pInfo;
        uint32_t n = *(const uint32_t *)(p + 44);
        const unsigned char *ca = *(const unsigned char *const *)(p + 48);
        if (ca && n) {
            for (uint32_t i = 0; i < n; i++) {
                uint64_t img = fg_img_of_view(
                        *(const uint64_t *)(ca + (size_t)i * 64 + 16));
                if (img) { fg_notify_target(img); break; }
            }
        }
    }
    if (real_CmdBeginRendering) real_CmdBeginRendering(cmd, pInfo);
}

/* THE FINISHED FRAME IS THE ONE TRANSITIONED INTO SHADER_READ_ONLY.
 *
 * Capture assumes the source sits in SHADER_READ_ONLY_OPTIMAL (fg_source.cpp,
 * src_layout) because that is the layout PCSX2 leaves the frame in so the present
 * pass can sample it. A target selected while it is still being DRAWN is in
 * COLOR_ATTACHMENT_OPTIMAL, so the capture barrier declares a wrong oldLayout and
 * the contents are undefined -- which is how capture ran at 59/s with "0 unique"
 * and LSFG posted black.
 *
 * So select on the COLOR_ATTACHMENT -> SHADER_READ_ONLY transition: that is the
 * exact moment a frame is finished and about to be read, and it makes the layout
 * capture assumes true by construction. Untracked images are ignored inside
 * fg_notify_target (fg_img_extent fails), so this only ever picks GS targets.
 *
 * VkImageMemoryBarrier is 72 bytes: oldLayout@24, newLayout@28, image@40.
 * COLOR_ATTACHMENT_OPTIMAL = 2, SHADER_READ_ONLY_OPTIMAL = 5. */
static void hooked_CmdPipelineBarrier(VkCommandBuffer cmd, uint32_t srcStage,
                                      uint32_t dstStage, uint32_t dep,
                                      uint32_t memCount, const void *mem,
                                      uint32_t bufCount, const void *buf,
                                      uint32_t imgCount, const void *img) {
    if (shim_framegen_wanted() && imgCount && img) {
        const unsigned char *p = (const unsigned char *)img;
        for (uint32_t i = 0; i < imgCount && i < 32u; i++) {
            uint32_t oldL = *(const uint32_t *)(p + (size_t)i * 72 + 24);
            uint32_t newL = *(const uint32_t *)(p + (size_t)i * 72 + 28);
            uint64_t im   = *(const uint64_t *)(p + (size_t)i * 72 + 40);
            if (!im) continue;
            /* DIAGNOSTIC: report the transitions that actually happen on tracked
             * GS images, rather than assuming 2 -> 5. Distinct pairs only, capped. */
            uint32_t tw = 0, th = 0;
            if (fg_img_extent(im, &tw, &th)) {
                static uint32_t seen[16];
                static int seen_n;
                uint32_t key = (oldL << 8) | newL;
                int known = 0;
                for (int k = 0; k < seen_n; k++) if (seen[k] == key) { known = 1; break; }
                if (!known && seen_n < 16) {
                    seen[seen_n++] = key;
                    LOGI("VulkanShim: framegen GS layout %u -> %u on %ux%u",
                         oldL, newL, (unsigned)tw, (unsigned)th);
                }
            }
            /* Remember, do not latch yet. Several targets finish per frame
             * (2 -> 5); the DISPLAY frame is whichever finished last before the
             * present call, so the present hook is where this gets committed. */
            /* Record the REAL layout of every tracked image so the capture can
             * transition from what the image is actually in. */
            fg_note_image_layout(im, newL);
            if (newL == 5u && oldL == 2u)
                g_last_finished_img = im;
        }
    }
    if (real_CmdPipelineBarrier)
        real_CmdPipelineBarrier(cmd, srcStage, dstStage, dep, memCount, mem,
                                bufCount, buf, imgCount, img);
}

/* PCSX2's GS renderer draws the game with INDEXED draws; plain vkCmdDraw is the
 * fullscreen present/CAS pass. Hooking only vkCmdDraw therefore latched the
 * display pass and never the GS, which still left capture on a static sibling
 * ("0 unique"). This is the hook that actually sees the frame being drawn. */
static void hooked_CmdDrawIndexed(VkCommandBuffer cmd, uint32_t idx, uint32_t inst,
                                  uint32_t firstIdx, int32_t vtxOff,
                                  uint32_t firstInst) {
    if (!g_barrier_hook_live && g_probe_draws_in_pass == 0 && g_pass_img
            && shim_framegen_wanted())
        fg_notify_target(g_pass_img);
    g_probe_draws_in_pass++;
    if (real_CmdDrawIndexed)
        real_CmdDrawIndexed(cmd, idx, inst, firstIdx, vtxOff, firstInst);
}

static void hooked_CmdDraw(VkCommandBuffer cmd, uint32_t vtx, uint32_t inst,
                           uint32_t firstVtx, uint32_t firstInst) {
    if (g_probe_pass_depth > 0 && vtx <= 6) {
        /* 3 or 4 vertices inside a pass is the fullscreen-blit shape a presenter
         * uses, so this identifies the display pass. */
        static int logged = 0;
        if (logged < 4) {
            logged++;
            LOGI("VulkanShim: probe: fullscreen-shaped draw vtx=%u inst=%u "
                 "(draw #%u in this pass)", vtx, inst, g_probe_draws_in_pass + 1);
        }
    }
    if (!g_barrier_hook_live && g_probe_draws_in_pass == 0 && g_pass_img
            && shim_framegen_wanted())
        fg_notify_target(g_pass_img);
    g_probe_draws_in_pass++;
    if (real_CmdDraw) real_CmdDraw(cmd, vtx, inst, firstVtx, firstInst);
}

extern "C" PFN_vkVoidFunction vkGetDeviceProcAddr(VkDevice dev, const char *name);

/* ------------------------------------------------------------------ */
/* Swapchain present mode override.                                    */
/*                                                                    */
/* The core has its own SelectPresentMode() and normally settles on    */
/* FIFO, which blocks each present until the next vblank. A frame that */
/* misses its slot by even a little then waits a whole refresh, which  */
/* is the +/-1 refresh jitter visible in frame-interval captures.      */
/* MAILBOX replaces the queued image instead of blocking, so a late    */
/* frame can still make the following refresh.                        */
/*                                                                    */
/* Off by default: FIFO is the safe, tear-free choice and MAILBOX      */
/* raises GPU load slightly by allowing work to run ahead. Enable per  */
/* device with turnip.conf present_mode=.                              */
/*                                                                    */
/* NOTE this targets *jitter* only. A hitch with a period measured in  */
/* frames rather than milliseconds is the game or the core doing       */
/* periodic work, and no present mode changes that.                    */
/* ------------------------------------------------------------------ */
/* VkSwapchainCreateInfoKHR, natural LP64 layout. presentMode sits at 88
   and minImageCount at 32; verified against the Vulkan headers rather
   than assumed, the same way the surface-create struct was. */
#define SHIM_SCCI_MIN_IMAGE_COUNT 32
#define SHIM_SCCI_IMAGE_FORMAT    36
#define SHIM_SCCI_IMAGE_EXTENT    44
#define SHIM_SCCI_IMAGE_USAGE     56
#define SHIM_SCCI_PRE_TRANSFORM   80
#define SHIM_SCCI_PRESENT_MODE    88

static VkDevice g_fsr_bound_device = NULL;

/* The driver's own vkGetDeviceProcAddr, with none of this file's interception.
 * The FSR module resolves its ~50 entry points through here: routing it through
 * the hooked vkGetDeviceProcAddr instead would hand it our own
 * vkGetSwapchainImagesKHR, so FSR would be told about the substituted
 * app-visible images and would then blit the finished frame into one of those
 * instead of into the real swapchain image. */
static int g_have_real_gdpa = 0;

static PFN_vkVoidFunction shim_real_device_proc(VkDevice dev, const char *name) {
    static PFN_vkVoidFunction (*real_gdpa)(VkDevice, const char*) = NULL;
    if (!real_gdpa && g_sys_vulkan)
        real_gdpa = (PFN_vkVoidFunction (*)(VkDevice, const char*))
            dlsym(g_sys_vulkan, "vkGetDeviceProcAddr");
    if (!real_gdpa) {
        g_have_real_gdpa = 0;
        LOGE("VulkanShim: vkGetDeviceProcAddr not resolved");
        return NULL;
    }
    g_have_real_gdpa = 1;
    return real_gdpa(dev, name);
}

static void *shim_fsr_get_proc(VkDevice dev, const char *name) {
    return (void *)shim_real_device_proc(dev, name);
}

typedef VkResult (*PFN_vkQueuePresentKHR)(void *, const void *);
static PFN_vkQueuePresentKHR real_QueuePresentKHR = NULL;

/* Observation only: prove the core's CAS/EASU compute pass (DoCAS) is
 * actually dispatching. Native 512×448 → 1280×960 present is ~80×60 groups.
 * Never hooks present or render-scale. */
typedef void (*PFN_shim_cmd_dispatch)(void *cb, uint32_t x, uint32_t y, uint32_t z);
static PFN_shim_cmd_dispatch real_CmdDispatch = NULL;
static uint32_t g_cas_gx, g_cas_gy;
static uint64_t g_cas_last_ns;
static uint64_t g_cas_last_log_ns;

static uint64_t shim_now_ns(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint64_t)ts.tv_sec * 1000000000ull + (uint64_t)ts.tv_nsec;
}

static void cadence_pace_before_present(void) {
    if (g_cadence_lock_hz <= 0) return;
    uint64_t period_ns = 1000000000ull / (uint64_t)g_cadence_lock_hz;
    uint64_t now = shim_now_ns();
    if (g_cadence_last_present_ns == 0) {
        g_cadence_last_present_ns = now;
        return;
    }
    uint64_t elapsed = now - g_cadence_last_present_ns;
    if (elapsed < period_ns) {
        uint64_t sleep_ns = period_ns - elapsed;
        struct timespec req;
        req.tv_sec = (time_t)(sleep_ns / 1000000000ull);
        req.tv_nsec = (long)(sleep_ns % 1000000000ull);
        nanosleep(&req, NULL);
    }
}

static VkResult shim_queue_present(void *queue, const void *presentInfo) {
    if (g_cadence_lock_hz > 0 && !g_fg_overlay)
        cadence_pace_before_present();
    VkResult r = real_QueuePresentKHR(queue, presentInfo);
    if (g_cadence_lock_hz > 0 && !g_fg_overlay && (r == 0 /* VK_SUCCESS */))
        g_cadence_last_present_ns = shim_now_ns();
    return r;
}

static void write_fsr_present_file(uint32_t w, uint32_t h) {
    static uint32_t last_w, last_h;
    if (w == 0u || h == 0u || (w == last_w && h == last_h))
        return;
    last_w = w;
    last_h = h;
    char dir[512];
    if (!get_files_dir(dir, sizeof(dir)))
        return;
    char path[640];
    snprintf(path, sizeof(path), "%s/fsr_present.txt", dir);
    FILE *f = fopen(path, "w");
    if (!f)
        return;
    fprintf(f, "%ux%u\n", (unsigned)w, (unsigned)h);
    fclose(f);
}

static void write_fsr_present_samp0_file(uint32_t w, uint32_t h, uint32_t tw,
                                         uint32_t th) {
    static uint32_t last_w, last_h, last_tw, last_th;
    if (w == 0u || h == 0u)
        return;
    if (w == last_w && h == last_h && tw == last_tw && th == last_th)
        return;
    last_w = w;
    last_h = h;
    last_tw = tw;
    last_th = th;
    char dir[512];
    if (!get_files_dir(dir, sizeof(dir)))
        return;
    char path[640];
    snprintf(path, sizeof(path), "%s/fsr_present_samp0.txt", dir);
    FILE *f = fopen(path, "w");
    if (!f)
        return;
    fprintf(f, "samp0=%ux%u target=%ux%u\n", (unsigned)w, (unsigned)h,
            (unsigned)tw, (unsigned)th);
    fclose(f);
}

static int shim_dims_near(uint32_t w, uint32_t h, uint32_t tw, uint32_t th) {
    if (!tw || !th)
        return 0;
    int dw = (int)w - (int)tw;
    int dh = (int)h - (int)th;
    if (dw < 0)
        dw = -dw;
    if (dh < 0)
        dh = -dh;
    return dw <= (int)(tw / 20u) + 2 && dh <= (int)(th / 20u) + 2;
}

static void shim_note_panel_extent(uint32_t w, uint32_t h, const char *via) {
    if (!w || !h || (g_panel_w == w && g_panel_h == h))
        return;
    g_panel_w = w;
    g_panel_h = h;
    LOGI("VulkanShim: panel extent %ux%u (%s)", (unsigned)w, (unsigned)h, via);
}

/* VkSurfaceCapabilitiesKHR currentExtent@8. Observation only — never rewrites. */
static void shim_capture_panel_extent(void *pCaps) {
    if (!shim_usable_ptr(pCaps, 52))
        return;
    unsigned char *caps = (unsigned char *)pCaps;
    uint32_t *cur = (uint32_t *)(caps + 8); /* currentExtent */
    if (cur[0] == 0 || cur[1] == 0 ||
        cur[0] == 0xFFFFFFFFu || cur[1] == 0xFFFFFFFFu)
        return;
    shim_note_panel_extent(cur[0], cur[1], "surface caps");
}

static int shim_cas_probe_wanted(void) {
    return g_presenter_probe;
}

static void hooked_CmdDispatch(void *cb, uint32_t x, uint32_t y, uint32_t z) {
    /* DoCAS is 16×16 groups covering the CAS dest. z==1, typical 40–160. */
    if (z == 1u && g_presenter_probe && x >= 16u && y >= 14u && x <= 160u && y <= 160u) {
        g_cas_gx = x;
        g_cas_gy = y;
        g_cas_last_ns = shim_now_ns();
        write_fsr_present_file(x * 16u, y * 16u);
        if (g_cas_last_log_ns == 0ull || g_cas_last_ns - g_cas_last_log_ns > 2000000000ull) {
            g_cas_last_log_ns = g_cas_last_ns;
            LOGI("FSR presenter ON: DoCAS dest %ux%u (workgroups %u×%u)",
                 (unsigned)(x * 16u), (unsigned)(y * 16u), (unsigned)x, (unsigned)y);
        }
    }
    if (real_CmdDispatch)
        real_CmdDispatch(cb, x, y, z);
}

/* Observation only: log GS framebuffer extents (512×448 × IR). Never
 * substitutes images or touches render_scale. VkImageCreateInfo on arm64:
 * sType@0, pNext@8, flags@16, imageType@20, format@24, extent.w@28, h@32,
 * usage@56. imageType 1 = 2D. */
typedef VkResult (*PFN_shim_create_image)(VkDevice, const void *, const void *,
                                          uint64_t *);
static PFN_shim_create_image real_CreateImage = NULL;
static VkResult hooked_CreateImage(VkDevice dev, const void *pCreateInfo,
                                   const void *pAllocator, uint64_t *pImage) {
    VkResult r = real_CreateImage ? real_CreateImage(dev, pCreateInfo, pAllocator, pImage)
                                  : 0;
    if (!pCreateInfo)
        return r;
    const unsigned char *p = (const unsigned char *)pCreateInfo;
    uint32_t type = 0, w = 0, h = 0, usage = 0;
    memcpy(&type, p + 20, 4);
    memcpy(&w, p + 28, 4);
    memcpy(&h, p + 32, 4);
    memcpy(&usage, p + 56, 4);
    if (type != 1u)
        return r;
    /* COLOR_ATTACHMENT — GS render target at upscale_multiplier scale. */
    if ((usage & 0x10u) == 0u)
        return r;
    if (w < 256u || h < 224u)
        return r;
    /* Scale comes from HEIGHT. The PS2's vertical resolution is the stable one
     * (448 for NTSC progressive); the WIDTH is not -- 512 and 640 are both
     * ordinary framebuffer widths, and 704 shows up too.
     *
     * This used to derive the scale from width alone, as w/512, and then require
     * it to agree with h/448 within 0.08. That silently excluded every 640-wide
     * game: GTA:SA renders 640x448, giving sx 1.25 against sy 1.00, so its frame
     * was handed to this hook every frame and thrown away for having the wrong
     * width. Measured on a Nova at IR 2.0: the game drew 1280x896, nothing was
     * ever classified as a GS target, and the overlay sat at "FRAMEGEN waiting,
     * not capturing" with pushed 0. That is what "framegen works in some games
     * and not others" was -- it was decided by framebuffer width. */
    float sy = (float)h / 448.f;
    if (sy < 0.45f || sy > 8.1f)
        return r;
    static const float kBaseW[] = { 512.f, 640.f, 704.f };
    int aspect_ok = 0;
    for (unsigned bi = 0; bi < sizeof(kBaseW) / sizeof(kBaseW[0]); bi++) {
        float d = (float)w / kBaseW[bi] - sy;
        if (d < 0.f) d = -d;
        if (d <= 0.08f) { aspect_ok = 1; break; }
    }
    if (!aspect_ok)
        return r;
    g_gs_w = w;
    g_gs_h = h;
    if (shim_framegen_wanted() && pImage) {
        fg_track_image(*pImage, w, h);
        fg_notify_gs_resize(w, h);
    }
    static uint32_t last_w, last_h;
    if (w != last_w || h != last_h) {
        last_w = w;
        last_h = h;
        LOGI("VulkanShim: GS image %ux%u usage=0x%x (upscale_multiplier ~%.2f)",
             (unsigned)w, (unsigned)h, (unsigned)usage, (double)sy);
    }
    return r;
}

/* VkImageViewCreateInfo::image sits at +24. */
static VkResult (*real_CreateImageView)(VkDevice, const void*, const void*,
                                        uint64_t*) = NULL;
static VkResult hooked_CreateImageView(VkDevice dev, const void *pCI,
                                       const void *pAlloc, uint64_t *pView) {
    VkResult r = real_CreateImageView ? real_CreateImageView(dev, pCI, pAlloc, pView) : 0;
    if (r == 0 && pCI && pView)
        fg_track_view(*pView, *(const uint64_t *)((const unsigned char *)pCI + 24));
    return r;
}

/* VkFramebufferCreateInfo: attachmentCount +32, pAttachments +40. */
static VkResult (*real_CreateFramebuffer)(VkDevice, const void*, const void*,
                                          uint64_t*) = NULL;
/* Destroy observers. Purge BEFORE the driver frees the object, so the present
 * thread can never resolve a handle the driver has already released. */
typedef void (*PFN_shim_destroy_handle)(VkDevice, uint64_t, const void *);
static PFN_shim_destroy_handle real_DestroyImage = NULL;
static PFN_shim_destroy_handle real_DestroyImageView = NULL;
static PFN_shim_destroy_handle real_DestroyFramebuffer = NULL;

typedef void (*PFN_shim_destroy_device)(VkDevice, const void *);
static PFN_shim_destroy_device real_DestroyDevice = NULL;

/* Everything framegen holds — command pool, command buffers, fences, images,
 * memory, queue — belongs to this device and becomes undefined the moment it is
 * destroyed. This CANNOT be detected by watching the device handle change:
 * exiting a game and starting another in the same process destroys the device
 * and creates a new one, and the driver reuses the handle value, so every
 * pointer check says "unchanged" while every object is dead. The symptom was
 * submits that never complete, so fences never signal, so capture skipped ~70%
 * of frames and handed the generator buffers nothing had written:
 * "pushed 2615 skipped 5658 posted 0 gen 0" on the second game of a session. */
static void hooked_DestroyDevice(VkDevice dev, const void *pAlloc) {
    if (shim_framegen_wanted()) {
        LOGI("VulkanShim: framegen vkDestroyDevice dev=%p (bound=%p authFlag=%d)",
             (void *)dev, (void *)g_fg_bound_dev, g_fg_dev_authoritative);
        fg_notify_device_lost();
        if (g_fg_bound_dev == dev) g_fg_bound_dev = NULL;
    }
    if (real_DestroyDevice) real_DestroyDevice(dev, pAlloc);
}

static void hooked_DestroyImage(VkDevice dev, uint64_t img, const void *pAlloc) {
    if (img) {
        fg_untrack_image(img);
        fg_notify_image_destroyed(img);   /* drops the latch if it was this one */
    }
    if (real_DestroyImage) real_DestroyImage(dev, img, pAlloc);
}

static void hooked_DestroyImageView(VkDevice dev, uint64_t view, const void *pAlloc) {
    fg_untrack_view(view);
    if (real_DestroyImageView) real_DestroyImageView(dev, view, pAlloc);
}

static void hooked_DestroyFramebuffer(VkDevice dev, uint64_t fb, const void *pAlloc) {
    fg_untrack_fb(fb);
    if (real_DestroyFramebuffer) real_DestroyFramebuffer(dev, fb, pAlloc);
}

static VkResult hooked_CreateFramebuffer(VkDevice dev, const void *pCI,
                                         const void *pAlloc, uint64_t *pFB) {
    VkResult r = real_CreateFramebuffer ? real_CreateFramebuffer(dev, pCI, pAlloc, pFB) : 0;
    if (r == 0 && pCI && pFB) {
        const unsigned char *p = (const unsigned char *)pCI;
        uint32_t n = *(const uint32_t *)(p + 32);
        const uint64_t *att = *(const uint64_t *const *)(p + 40);
        if (att && n) {
            for (uint32_t i = 0; i < n; i++) {
                uint64_t img = fg_img_of_view(att[i]);
                if (img) { fg_track_fb(*pFB, img); break; }
            }
        }
    }
    return r;
}

static void (*real_GetDeviceQueue)(VkDevice, uint32_t, uint32_t, void**) = NULL;
static void hooked_GetDeviceQueue(VkDevice dev, uint32_t family, uint32_t index,
                                  void **pQueue) {
    if (real_GetDeviceQueue) real_GetDeviceQueue(dev, family, index, pQueue);
    if (pQueue && *pQueue) fg_note_queue(*pQueue, family);
}

static void (*real_GetPhysicalDeviceMemoryProperties)(void*, void*) = NULL;
static void hooked_GetPhysicalDeviceMemoryProperties(void *phys, void *pProps) {
    if (real_GetPhysicalDeviceMemoryProperties)
        real_GetPhysicalDeviceMemoryProperties(phys, pProps);
    if (pProps) fg_note_memory_properties(pProps);
}

/* ------------------------------------------------------------------ */
/* Render-scale: make the emulator size itself to panel*render_scale.  */
/*                                                                    */
/* Rewriting imageExtent in VkSwapchainCreateInfoKHR is not enough on  */
/* its own — the core has already computed that extent from somewhere  */
/* else and keeps its own copy for viewports, scissors and (crucially) */
/* the framebuffers it builds over the swapchain images. The only      */
/* place to change its mind is the surface capabilities it read the    */
/* extent from, so currentExtent (and maxImageExtent, for a core that  */
/* clamps rather than copies) is scaled on the way out.                */
/*                                                                    */
/* Whether the core actually sizes itself from here is then PROVEN,    */
/* not assumed: hooked_CreateSwapchainKHR only arms the substitution   */
/* if the extent the core asks for is the reduced one we advertised.   */
/* ------------------------------------------------------------------ */
/* VkSurfaceCapabilitiesKHR is ten uint32_t fields, so LP64 needs no padding
   anywhere: minImageCount 0, maxImageCount 4, currentExtent 8, minImageExtent
   16, maxImageExtent 24. Verified against vulkan_core.h, not assumed. */
#define SHIM_SCAP_CURRENT_EXTENT 8
#define SHIM_SCAP_MIN_EXTENT     16
#define SHIM_SCAP_MAX_EXTENT     24
/* VkSurfaceCapabilities2KHR: sType 0, pNext 8, surfaceCapabilities 16. */
#define SHIM_SCAP2_CAPS          16

/* g_panel_w/h declared with the other conf-driven globals above. */
static uint32_t g_rs_w = 0, g_rs_h = 0;        /* what the app is told instead */

#if NETHER_FSR_FRAMEGEN_ACTIVE
static void shim_rs_rewrite_caps(void *pCaps) {
    if (!shim_render_scale_wanted() || !shim_usable_ptr(pCaps, 52))
        return;
    unsigned char *caps = (unsigned char *)pCaps;
    uint32_t *cur = (uint32_t *)(caps + SHIM_SCAP_CURRENT_EXTENT);
    if (cur[0] == 0 || cur[1] == 0 ||
        cur[0] == 0xFFFFFFFFu || cur[1] == 0xFFFFFFFFu) {
        fsr_render_scale_report_off("surface currentExtent is undefined");
        return;
    }
    /* Already rewritten this query round-trip? The core asks repeatedly. */
    if (g_rs_w && cur[0] == g_rs_w && cur[1] == g_rs_h)
        return;

    uint32_t rw = 0, rh = 0;
    if (!fsr_render_scale_extent(cur[0], cur[1], &rw, &rh))
        return;  /* reason already logged once by the FSR side */

    shim_note_panel_extent(cur[0], cur[1], "render-scale caps");
    g_rs_w = rw;
    g_rs_h = rh;
    cur[0] = rw;
    cur[1] = rh;
    /* A core that clamps its own idea of the window into [min,max] instead of
     * copying currentExtent lands on the reduced extent this way. min is only
     * lowered if it would otherwise exceed the new max. */
    uint32_t *mn = (uint32_t *)(caps + SHIM_SCAP_MIN_EXTENT);
    uint32_t *mx = (uint32_t *)(caps + SHIM_SCAP_MAX_EXTENT);
    if (mn[0] > rw) mn[0] = rw;
    if (mn[1] > rh) mn[1] = rh;
    mx[0] = rw;
    mx[1] = rh;

    static int logged = 0;
    if (!logged) {
        logged = 1;
        LOGI("VulkanShim: render-scale surface currentExtent %ux%u -> %ux%u "
             "(the real swapchain stays %ux%u)",
             g_panel_w, g_panel_h, rw, rh, g_panel_w, g_panel_h);
    }
}
#endif /* NETHER_FSR_FRAMEGEN_ACTIVE */

static VkResult (*real_GetPhysDevSurfaceCapsKHR)(void *, uint64_t, void *) = NULL;
static VkResult (*real_GetPhysDevSurfaceCaps2KHR)(void *, const void *, void *) = NULL;

static void shim_on_surface_caps(void *pCaps) {
    shim_capture_panel_extent(pCaps);
#if NETHER_FSR_FRAMEGEN_ACTIVE
    shim_rs_rewrite_caps(pCaps);
#endif
}

static VkResult hooked_GetPhysDevSurfaceCapsKHR(void *physDev, uint64_t surface,
                                                void *pCaps) {
    VkResult r = real_GetPhysDevSurfaceCapsKHR(physDev, surface, pCaps);
    if (r == 0)
        shim_on_surface_caps(pCaps);
    return r;
}

static VkResult hooked_GetPhysDevSurfaceCaps2KHR(void *physDev, const void *pInfo,
                                                 void *pCaps) {
    VkResult r = real_GetPhysDevSurfaceCaps2KHR(physDev, pInfo, pCaps);
    if (r == 0 && pCaps)
        shim_on_surface_caps((unsigned char *)pCaps + SHIM_SCAP2_CAPS);
    return r;
}

/* vkGetSwapchainImagesKHR — where the substitution actually happens. Captured
 * (and hooked) only when render-scale is wanted; hooked_CreateSwapchainKHR
 * refuses to arm unless this pointer is non-NULL, because a core that resolved
 * the real function some other way would keep rendering into the real panel-size
 * image while believing it is the reduced size. */
typedef VkResult (*PFN_vkGetSwapchainImagesKHR)(VkDevice, uint64_t, uint32_t *,
                                                uint64_t *);
static PFN_vkGetSwapchainImagesKHR real_GetSwapchainImagesKHR = NULL;

/* Swapchain image table for framegen's present-time capture.
 *
 * vkQueuePresentKHR is handed (swapchain, imageIndex), not a VkImage, and
 * swapchain images are driver-owned — they never pass through vkCreateImage,
 * which is exactly why a layer that watches image creation can never see the
 * one image class that is definitionally presented. This is the lookup that
 * closes that gap. Small and fixed: PCSX2 keeps one swapchain, occasionally two
 * across a recreate. */
#define SHIM_SC_MAX      4
#define SHIM_SC_IMG_MAX  8
struct shim_sc_entry {
    uint64_t swapchain;
    uint64_t images[SHIM_SC_IMG_MAX];
    uint32_t count;
};
static struct shim_sc_entry g_sc_tab[SHIM_SC_MAX];
static pthread_mutex_t g_sc_mu = PTHREAD_MUTEX_INITIALIZER;

static void shim_sc_record(uint64_t swapchain, const uint64_t *images, uint32_t n) {
    if (!swapchain || !images || !n) return;
    if (n > SHIM_SC_IMG_MAX) n = SHIM_SC_IMG_MAX;
    pthread_mutex_lock(&g_sc_mu);
    int slot = -1, free_slot = -1;
    for (int i = 0; i < SHIM_SC_MAX; i++) {
        if (g_sc_tab[i].swapchain == swapchain) { slot = i; break; }
        if (free_slot < 0 && !g_sc_tab[i].swapchain) free_slot = i;
    }
    if (slot < 0) slot = free_slot >= 0 ? free_slot : 0;  /* oldest wins the race */
    g_sc_tab[slot].swapchain = swapchain;
    g_sc_tab[slot].count = n;
    for (uint32_t i = 0; i < n; i++) g_sc_tab[slot].images[i] = images[i];
    pthread_mutex_unlock(&g_sc_mu);
    LOGI("VulkanShim: swapchain %llu -> %u presentable image(s) recorded",
         (unsigned long long)swapchain, (unsigned)n);
}

static uint64_t shim_sc_image(uint64_t swapchain, uint32_t index) {
    uint64_t out = 0;
    pthread_mutex_lock(&g_sc_mu);
    for (int i = 0; i < SHIM_SC_MAX; i++) {
        if (g_sc_tab[i].swapchain == swapchain && index < g_sc_tab[i].count) {
            out = g_sc_tab[i].images[index];
            break;
        }
    }
    pthread_mutex_unlock(&g_sc_mu);
    return out;
}

static VkResult hooked_GetSwapchainImagesKHR(VkDevice dev, uint64_t swapchain,
                                             uint32_t *pCount, uint64_t *pImages) {
#if NETHER_FSR_FRAMEGEN_ACTIVE
    if (pCount) {
        VkResult sub = 0;
        if (fsr_render_scale_query_images(dev, (VkSwapchainKHR)(uintptr_t)swapchain,
                                         pCount, pImages, &sub))
            return sub;
    }
#endif
    VkResult r = real_GetSwapchainImagesKHR(dev, swapchain, pCount, pImages);
    /* Record only the real enumeration (pImages non-NULL); the count-query call
     * passes NULL and must be forwarded untouched. */
    if (r == 0 && pCount && pImages && fg_swapchain_capture_wanted())
        shim_sc_record(swapchain, pImages, *pCount);
    return r;
}

static VkResult (*real_CreateSwapchainKHR)(VkDevice, void *, const void *,
                                           uint64_t *) = NULL;

static VkResult hooked_CreateSwapchainKHR(VkDevice dev, void *pCreateInfo,
                                          const void *pAllocator,
                                          uint64_t *pSwapchain) {
    fg_bind_capture_device(dev);
    if (pCreateInfo) {
        unsigned char *ci = (unsigned char *)pCreateInfo;
        {
            /* VkSurfaceTransformFlagBitsKHR: IDENTITY=0x1, 90=0x2, 180=0x4, 270=0x8 */
            uint32_t *pxf = (uint32_t *)(ci + SHIM_SCCI_PRE_TRANSFORM);
            uint32_t pre = *pxf;
            /* Log EVERY creation, with extent: the question is whether LSFG makes
             * its own swapchain on the output surface at all, and de-duplicating on
             * the transform value would hide a second one that happens to match. */
            static int sc_n;
            uint32_t sw = *(uint32_t *)(ci + SHIM_SCCI_IMAGE_EXTENT);
            uint32_t sh = *(uint32_t *)(ci + SHIM_SCCI_IMAGE_EXTENT + 4);
            LOGI("VulkanShim: swapchain #%d %ux%u preTransform 0x%x%s", ++sc_n,
                 (unsigned)sw, (unsigned)sh, (unsigned)pre,
                 pre == 0x1u ? " (IDENTITY)" : " (ROTATED)");
            if (g_fix_rotation && pre != 0x1u) {
                *pxf = 0x1u;
                LOGI("VulkanShim: fix_rotation=on — preTransform 0x%x rewritten to "
                     "IDENTITY; SurfaceFlinger rotates instead", (unsigned)pre);
            }
        }
        uint32_t *mode = (uint32_t *)(ci + SHIM_SCCI_PRESENT_MODE);
        uint32_t *imgs = (uint32_t *)(ci + SHIM_SCCI_MIN_IMAGE_COUNT);
        uint32_t was_mode = *mode, was_imgs = *imgs;

        /* DO NOT FORCE FIFO. Measured, and it was a regression.
         *
         * The theory was that MAILBOX discards the queued interpolated frame,
         * so framegen would be paying for work the panel never shows. The
         * device says otherwise, on the same scene:
         *
         *   30 fps source, 4x:  FIFO out 119-121 | MAILBOX out 118   (no gain)
         *   60 fps source, 2x:  FIFO out 28      | MAILBOX out 98
         *                       FIFO real 55-57  | MAILBOX real 60
         *
         * Under FIFO every present blocks until vblank, and at a 60 fps source
         * PCSX2's own 60 presents plus 60 injected ones is exactly 120/s on a
         * 120 Hz panel — no slack, so the app's present starts blocking and
         * stalls the EMULATOR. A 30 fps source survives it only because it has
         * 33 ms per frame instead of 16.7. So forcing FIFO bought nothing where
         * it worked and broke the case it was supposed to help.
         *
         * The "out 123 on a 120 Hz panel" that motivated this was a counter
         * windowing artifact, not MAILBOX cheating. Leave the app's choice
         * alone; an explicit present_mode in turnip.conf still wins. */
        if (g_present_mode >= 0) *mode = (uint32_t)g_present_mode;
        uint32_t min_need = 0;
        if (g_min_image_count > 0 && g_min_image_count <= 4)
            min_need = (uint32_t)g_min_image_count;
        else if (g_min_image_count > 4)
            LOGI("VulkanShim: ignoring min_image_count=%d (max 4)", g_min_image_count);
        if (min_need > 0 && *imgs < min_need)
            *imgs = min_need;

        LOGI("VulkanShim: swapchain presentMode %s(%u)->%s(%u) minImageCount %u->%u",
             present_name(was_mode), was_mode, present_name(*mode), *mode,
             was_imgs, *imgs);
    }
    /* PCSX2 asks for COLOR_ATTACHMENT only (measured: imageUsage=0x10), but the FSR
     * pass has to blit the presented image out and the sharpened result back in.
     * Reading or writing an image as a transfer endpoint without these bits is
     * undefined behaviour — on Turnip it silently does nothing, which is exactly
     * how a running RCAS pass produced no visible change. Add them, and fall back
     * to the app's original usage if the surface will not allow it. */
    uint32_t saved_usage = 0;
    int usage_forced = 0;
    int rs_pending = 0;
#if NETHER_FSR_FRAMEGEN_ACTIVE
    /* Render-scale: the real swapchain has to stay at panel size — that is what
     * EASU writes into and what the compositor scans out — so put the panel
     * extent back before the driver sees it. Arming is conditional on the core
     * having asked for the reduced extent we advertised in the surface
     * capabilities: that is the proof it sizes its viewports, scissors and
     * framebuffers from there too. If it asked for anything else, a substituted
     * image would be rendered into with panel-size framebuffers, so we leave
     * everything alone and say why. */
    if (pCreateInfo && shim_render_scale_wanted()) {
        unsigned char *ci = (unsigned char *)pCreateInfo;
        uint32_t *ext = (uint32_t *)(ci + SHIM_SCCI_IMAGE_EXTENT);
        if (!real_GetSwapchainImagesKHR) {
            fsr_render_scale_report_off(
                "vkGetSwapchainImagesKHR was never resolved through our "
                "vkGetDeviceProcAddr, so the app-visible images cannot be substituted");
        } else if (!real_QueuePresentKHR) {
            fsr_render_scale_report_off(
                "vkQueuePresentKHR is not intercepted, so nothing would upscale "
                "the app's frame into the swapchain image");
        } else if (!g_rs_w || !g_panel_w) {
            fsr_render_scale_report_off(
                "the surface capabilities were never queried through our hook");
        } else if (ext[0] == g_rs_w && ext[1] == g_rs_h) {
            LOGI("VulkanShim: render-scale imageExtent %ux%u -> %ux%u for the driver "
                 "(the app keeps rendering at %ux%u)",
                 ext[0], ext[1], g_panel_w, g_panel_h, g_rs_w, g_rs_h);
            ext[0] = g_panel_w;
            ext[1] = g_panel_h;
            rs_pending = 1;
        } else {
            LOGE("VulkanShim: render-scale not armed — app asked for a %ux%u "
                 "swapchain, not the %ux%u the surface advertised",
                 ext[0], ext[1], g_rs_w, g_rs_h);
            fsr_render_scale_report_off(
                "the app does not size its swapchain from the surface capabilities");
        }
    }
    /* Transfer usage on a presentable image drops UBWC, and the cost scales with
     * how much the GS writes into that image. Measured with RCAS on:
     *   Internal Resolution 4x     -> GPU 39.78 ms (vs 13.61 ms) — fatal
     *   Internal Resolution Native -> GPU  7.54 ms — nearly free, and the frame
     *                                 was still 20.85 ms because of the per-present
     *                                 fence drain, not UBWC.
     * So it cannot be limited to render-scale (which is disabled anyway, being
     * unsound): the sharpen path needs transfer access to the presented image or
     * it can never do anything. Granted whenever a pass will actually run, with
     * the standing caveat that FSR + a high internal resolution is not viable on
     * this hardware. */
    /* TRANSFER on a presentable image drops UBWC. Fatal at 4× IR (~40 ms).
     * Only Frame Gen needs it (blit of real swapchain images). FSR is CAS.
     * If the surface already rejected TRANSFER, never ask again — a create
     * loop with a second ANativeWindow takes the whole device down. */
    if (pCreateInfo && (rs_pending || g_framegen) && !g_transfer_rejected) {
        uint32_t *usage = (uint32_t *)((unsigned char *)pCreateInfo + SHIM_SCCI_IMAGE_USAGE);
        const uint32_t want = 0x1u | 0x2u; /* TRANSFER_SRC | TRANSFER_DST */
        if ((*usage & want) != want) {
            saved_usage = *usage;
            *usage |= want;
            usage_forced = 1;
        }
    }
#endif
    /* Present-time capture needs to read the presentable image, and it must be
     * asked for HERE — usage cannot be added after the swapchain exists.
     *
     * Outside the FSR block on purpose: this build compiles with
     * NETHER_NO_FSR_FRAMEGEN=1, so everything above is #if'd away and the bit
     * was never actually requested. TRANSFER_SRC alone, not SRC|DST: capture
     * only reads, and every extra bit is more UBWC to lose.
     *
     * The cost is real and was measured with the FSR sharpener: at IR 4x it
     * took GPU time from 13.61 ms to 39.78 ms (fatal), at native it was 7.54 ms
     * (nearly free). IR 2x, which is what this device runs, sits between those
     * and had never been measured — hence fg_capture_src, so the answer is one
     * conf line away rather than a rebuild. */
    /* ARM THE SWAPCHAIN WHENEVER FRAMEGEN IS ON, NOT ONLY FOR THE SWAPCHAIN ROUTE.
     *
     * The gs route can end up latched on a target nobody renders into: measured
     * 2026-08-17 with every counter healthy (real 59, gen 61, out 123, 99%
     * speed) and the panel pure black, uniq pinned at 0. The recovery is to fall
     * back to swapchain capture live, and that is only possible if the swapchain
     * was created with TRANSFER_SRC. Paying one bit here is what makes the
     * fallback exist at all; framegen is off by default, so nobody who has not
     * asked for it pays. */
    /* Only the swapchain capture route reads presentable images. Forcing
     * TRANSFER_SRC on every lsfg_overlay session drops UBWC on the display
     * swapchain and produced full-screen vertical stride garbage on Adreno 650
     * while the GS route was active (fg_capture_src=gs, the default). */
    if (pCreateInfo && !usage_forced && !g_transfer_rejected
            && fg_swapchain_capture_wanted()) {
        uint32_t *usage = (uint32_t *)((unsigned char *)pCreateInfo + SHIM_SCCI_IMAGE_USAGE);
        if (!(*usage & 0x1u)) {
            saved_usage = *usage;
            *usage |= 0x1u;               /* TRANSFER_SRC */
            usage_forced = 1;
        }
    }
    VkResult r = real_CreateSwapchainKHR(dev, pCreateInfo, pAllocator, pSwapchain);
    if (r != VK_SUCCESS && usage_forced) {
        if (r == VK_ERROR_NATIVE_WINDOW_IN_USE_KHR) {
            /* PCSX2 often creates a second swapchain before destroying the first.
             * This is not transfer rejection — keep framegen armed. */
            *(uint32_t *)((unsigned char *)pCreateInfo + SHIM_SCCI_IMAGE_USAGE) = saved_usage;
            LOGE("VulkanShim: swapchain create %d (NATIVE_WINDOW_IN_USE); "
                 "framegen unchanged", (int)r);
            return r;
        }
        *(uint32_t *)((unsigned char *)pCreateInfo + SHIM_SCCI_IMAGE_USAGE) = saved_usage;
        usage_forced = 0;
        VkResult r2 = real_CreateSwapchainKHR(dev, pCreateInfo, pAllocator, pSwapchain);
        if (r2 == VK_SUCCESS) {
            LOGE("VulkanShim: swapchain create rejected forced transfer usage (%d); "
                 "retry without TRANSFER succeeded.", (int)r);
            /* Latched so we never ask again: a create loop against a second
             * ANativeWindow is what produced 733 swapchain recreations at
             * 24 fps the first time this was tried. */
            g_transfer_rejected = 1;
            g_framegen = 0;
#if NETHER_FSR_FRAMEGEN_ACTIVE
            fg_set_config(0, g_framegen_mode, g_framegen_alpha);
#endif
            /* Present-time capture cannot read an image without TRANSFER_SRC,
             * so fall back to the GS target rather than silently capturing
             * nothing. Degraded, not dead. */
            if (g_fg_capture_src) {
                g_fg_capture_src = 0;
                fg_set_capture_source(0);
                LOGE("VulkanShim: framegen capture falling back to the GS target "
                     "(the surface will not give the swapchain TRANSFER_SRC)");
            }
            r = r2;
        } else if (r2 == VK_ERROR_NATIVE_WINDOW_IN_USE_KHR) {
            LOGE("VulkanShim: swapchain create %d (NATIVE_WINDOW_IN_USE, no transfer); "
                 "framegen unchanged", (int)r2);
            r = r2;
        } else {
            LOGE("VulkanShim: swapchain create failed %d (with transfer) and %d "
                 "(without); disabling framegen",
                 (int)r, (int)r2);
            g_transfer_rejected = 1;
            g_framegen = 0;
#if NETHER_FSR_FRAMEGEN_ACTIVE
            fg_set_config(0, g_framegen_mode, g_framegen_alpha);
#endif
            r = r2;
        }
    }
    if (usage_forced) {
        /* Print what was ACTUALLY asked for, not a hardcoded guess. This line
         * used to claim "-> 0x13 (added TRANSFER_SRC|DST)" while the present
         * capture path adds TRANSFER_SRC alone, and the very next log line said
         * 0x11. A log that contradicts the one below it costs whoever reads it
         * next the time to work out which one is lying. */
        uint32_t now_usage =
            *(uint32_t *)((unsigned char *)pCreateInfo + SHIM_SCCI_IMAGE_USAGE);
        LOGI("VulkanShim: swapchain imageUsage 0x%x -> 0x%x (added%s%s)",
             saved_usage, now_usage,
             ((now_usage & ~saved_usage) & 0x1u) ? " TRANSFER_SRC" : "",
             ((now_usage & ~saved_usage) & 0x2u) ? " TRANSFER_DST" : "");
    }
    if (r != 0) {
        LOGE("VulkanShim: vkCreateSwapchainKHR failed (%d). If a forced present "
             "mode is set in turnip.conf, the driver may not support it — "
             "remove the line to fall back to the core's own choice.", r);
    } else if (pCreateInfo && pSwapchain && *pSwapchain) {
        unsigned char *ci = (unsigned char *)pCreateInfo;
        uint32_t fmt = *(uint32_t *)(ci + SHIM_SCCI_IMAGE_FORMAT);
        uint32_t w = *(uint32_t *)(ci + SHIM_SCCI_IMAGE_EXTENT);
        uint32_t h = *(uint32_t *)(ci + SHIM_SCCI_IMAGE_EXTENT + 4);
        /* The sharpen/framegen passes blit the presented image both ways, which
         * needs TRANSFER_SRC and TRANSFER_DST in the usage PCSX2 asked for. It is
         * read here rather than forced: adding usage bits can make
         * vkCreateSwapchainKHR fail outright if the surface does not advertise
         * them, and can cost the whole app UBWC on the presentable images. If a
         * hardware log ever shows either bit missing, that is the fix to make. */
        uint32_t su = *(uint32_t *)(ci + SHIM_SCCI_IMAGE_USAGE);
        LOGI("VulkanShim: swapchain imageUsage=0x%x transfer_src=%d transfer_dst=%d",
             su, (su & 0x1u) ? 1 : 0, (su & 0x2u) ? 1 : 0);
        if (w > 0u && h > 0u)
            shim_note_panel_extent(w, h, "swapchain");
        if (g_fsr_bound_device != dev &&
            (shim_postprocess_enabled() || shim_render_scale_wanted())) {
#if NETHER_FSR_FRAMEGEN_ACTIVE
            if (fsr_bind_device(dev, shim_fsr_get_proc))
                g_fsr_bound_device = dev;
#endif
        }
#if NETHER_FSR_FRAMEGEN_ACTIVE
        if (g_fsr_bound_device == dev) {
        /* w/h are read back after the rewrite above, so the FSR side records the
         * panel extent — which is exactly what it needs as its output size. */
        fsr_on_swapchain_created(dev, (VkSwapchainKHR)(uintptr_t)*pSwapchain, w, h, fmt);
        if (rs_pending &&
            !fsr_render_scale_arm(dev, (VkSwapchainKHR)(uintptr_t)*pSwapchain,
                                  g_rs_w, g_rs_h)) {
            LOGE("VulkanShim: render-scale could not be armed — handing back the "
                 "real swapchain images");
        }
        }
#endif
    }
    return r;
}

static VkResult (*real_DestroySwapchainKHR)(VkDevice, uint64_t, const void *) = NULL;

static VkResult hooked_DestroySwapchainKHR(VkDevice dev, uint64_t swapchain,
                                           const void *pAllocator) {
    if (swapchain) {
#if NETHER_FSR_FRAMEGEN_ACTIVE
        fsr_on_swapchain_destroyed(dev, (VkSwapchainKHR)(uintptr_t)swapchain);
#endif
    }
    return real_DestroySwapchainKHR(dev, swapchain, pAllocator);
}

/* ------------------------------------------------------------------ */
/* FSR1 present + viewport hooks                                       */
/* ------------------------------------------------------------------ */
/* vkCmdSetViewport(cmd, firstViewport, viewportCount, pViewports) — FOUR args.
 * This was declared with three, so the hook received viewportCount where it
 * expected pViewports and handed that integer to FSR as a pointer. FSR then
 * never got a viewport, fsr_viewport_is_ready() stayed false, and the upscaler
 * sat in "FSR warmup" forever no matter how the toggle was set. The real call
 * only appeared to work because AArch64 left the true pViewports in x3. */
typedef void (*PFN_vkCmdSetViewport)(VkCommandBuffer, uint32_t, uint32_t,
                                     const void *);
static PFN_vkCmdSetViewport real_CmdSetViewport = NULL;
typedef void (*PFN_vkCmdSetViewportWithCount)(VkCommandBuffer, uint32_t, const void *);
static PFN_vkCmdSetViewportWithCount real_CmdSetViewportWithCount = NULL;

static void shim_fsr_on_viewports(const void *pViewports, uint32_t count) {
    if (!shim_postprocess_enabled() || !pViewports || count == 0)
        return;
    if (!shim_usable_ptr(pViewports, sizeof(float) * 6))
        return;
    const float *vp = (const float *)pViewports;
    float x = vp[0], y = vp[1], w = vp[2], h = vp[3];
    if (w > 8.0f && h > 8.0f)
        fsr_on_viewport(x, y, w, h);
}

static void hooked_CmdSetViewport(VkCommandBuffer cmd, uint32_t first,
                                  uint32_t viewportCount,
                                  const void *pViewports) {
    if (real_CmdSetViewport)
        real_CmdSetViewport(cmd, first, viewportCount, pViewports);
    shim_fsr_on_viewports(pViewports, viewportCount);
}

static void hooked_CmdSetViewportWithCount(VkCommandBuffer cmd, uint32_t viewportCount,
                                           const void *pViewports) {
    if (real_CmdSetViewportWithCount)
        real_CmdSetViewportWithCount(cmd, viewportCount, pViewports);
    shim_fsr_on_viewports(pViewports, viewportCount);
}

static VkResult hooked_QueuePresentKHR(void *queue, const void *presentInfo) {
    /* Pick up Graphics FSR toggle without restarting the game. */
    if ((++g_turnip_hot_reload_counter % 15) == 0)
        reload_turnip_conf_hot();
    shim_clamp_fsr_framegen_off();
    /* In-process framegen: copy the frame into an AHB and hand it to LSFG.
     * Deliberately BEFORE the FSR/render-scale branches below — those can
     * return early, and this must run on every present or the capture rate
     * silently halves. */
    if (g_fg_overlay) {
        /* THE PRESENTED IMAGE IS THE FRAME. NOTHING TO IDENTIFY.
         *
         * VkPresentInfoKHR names the swapchain and the image index of what is
         * about to be scanned out. Read here rather than guessed anywhere else:
         * every "which target is the game" failure in this file — the seven
         * VkImages at one extent, the two live handles that both capture
         * static, the black panel where PCSX2 presented from a target the
         * render-pass hook never saw — is a wrong answer to a question this
         * struct answers exactly.
         *
         * Offsets are hand-computed because this file cannot include vulkan.h
         * (it defines its own opaque VkDevice/VkResult): sType@0, pNext@8,
         * waitSemaphoreCount@16, pWaitSemaphores@24, swapchainCount@32,
         * pSwapchains@40, pImageIndices@48, pResults@56. */
        if (g_barrier_hook_live && g_last_finished_img) {
            /* Commit the frame that finished last before this present. */
            fg_notify_target(g_last_finished_img);
            g_last_finished_img = 0;
        }
        if (g_fg_capture_src && presentInfo) {
            const unsigned char *pi = (const unsigned char *)presentInfo;
            uint32_t sc_count = *(const uint32_t *)(pi + 32);
            const uint64_t *swaps = *(const uint64_t *const *)(pi + 40);
            const uint32_t *idxs  = *(const uint32_t *const *)(pi + 48);
            if (sc_count > 0 && swaps && idxs) {
                uint64_t img = shim_sc_image(swaps[0], idxs[0]);
                if (img) {
                    fg_note_present_image(img, g_panel_w, g_panel_h);
                } else {
                    static int warned;
                    if (!warned++)
                        LOGE("VulkanShim: framegen present image not in the "
                             "swapchain table (sc=%llu idx=%u) — capture idle",
                             (unsigned long long)swaps[0], (unsigned)idxs[0]);
                }
            }
        }
        fg_capture_and_push();
    }
    if (g_cas_last_ns != 0ull) {
        uint64_t now = shim_now_ns();
        if (now - g_cas_last_ns < 500000000ull &&
            (g_cas_last_log_ns == 0ull || now - g_cas_last_log_ns > 2000000000ull)) {
            g_cas_last_log_ns = now;
            LOGI("FSR presenter ON: DoCAS dest %ux%u still dispatching",
                 (unsigned)(g_cas_gx * 16u), (unsigned)(g_cas_gy * 16u));
        }
    }
    /* While render-scale is live the finished frame is in an FSR-owned image and
     * nothing but fsr_on_queue_present() will ever copy it into the swapchain
     * image, so this present cannot be passed through even if the hot-reload has
     * since turned the upscaler off. Always 0 when render_scale is absent. */
#if NETHER_FSR_FRAMEGEN_ACTIVE
    const int rs_live = fsr_render_scale_any_active();
#else
    const int rs_live = 0;
#endif
    if ((shim_postprocess_enabled() || rs_live) && real_QueuePresentKHR) {
        VkDevice dev = g_fsr_bound_device;
        if (dev) {
            if (g_framegen || rs_live) {
                return fsr_on_queue_present((VkQueue)queue, dev, presentInfo,
                                        (VkResult (*)(VkQueue, const void *))real_QueuePresentKHR);
            }
            return shim_queue_present(queue, presentInfo);
        }
    }
    return shim_queue_present(queue, presentInfo);
}

static void (*real_GetPhysicalDeviceProperties)(void*, void*) = NULL;

static void hooked_GetPhysicalDeviceProperties(void *physDev, void *pProps) {
    /* turnip.conf can change (e.g. lsfg_overlay toggled in Settings) after the
     * one-time startup read. vkGetPhysicalDeviceMemoryProperties is queried
     * between here and vkCreateDevice; if g_fg_overlay is still stale, the
     * framegen hook is never installed and capture stays built=0 forever. */
    reload_turnip_conf_hot();
    g_physical_device = physDev;
#if NETHER_FSR_FRAMEGEN_ACTIVE
    /* Hand FSR the physical device and the instance-level memory-properties proc.
     * fsr_bind_device only gets a DEVICE-level get_proc, which cannot resolve
     * vkGetPhysicalDeviceMemoryProperties, so FSR was falling back to guessing a
     * memory type index — host-visible uniform buffers ended up on device-local
     * memory and never mapped. dlsym on the real driver is the only route. */
    if (physDev && g_sys_vulkan) {
        static void *mem_props_fn = NULL;
        if (!mem_props_fn)
            mem_props_fn = dlsym(g_sys_vulkan, "vkGetPhysicalDeviceMemoryProperties");
        if (mem_props_fn)
            fsr_set_physical_device(physDev, mem_props_fn);
        else
            LOGE("VulkanShim: vkGetPhysicalDeviceMemoryProperties not resolved — "
                 "FSR uniform buffers will fail to allocate");
    }
#endif
    if (real_GetPhysicalDeviceProperties)
        real_GetPhysicalDeviceProperties(physDev, pProps);
    
    if (pProps) {
        /* VkPhysicalDeviceProperties layout:
           uint32_t apiVersion       offset 0
           uint32_t driverVersion    offset 4
           uint32_t vendorID         offset 8
           uint32_t deviceID         offset 12
           uint32_t deviceType       offset 16
           char     deviceName[256]  offset 20 */
        uint32_t *u = (uint32_t*)pProps;
        char *name = (char*)pProps + 20;
        LOGI("VulkanShim: PhysDevProps: api=%u.%u.%u driver=%u vendor=0x%x device=0x%x type=%u name=%s",
             u[0] >> 22, (u[0] >> 12) & 0x3ff, u[0] & 0xfff,
             u[1], u[2], u[3], u[4], name);
    }
}


/* ---- CAS gate probe -------------------------------------------------------
 * AetherSX2 prints "CAS is not available, your graphics driver does not support
 * the required functionality" and never dispatches DoCAS. Measured: this happens
 * on BOTH Turnip and the stock Qualcomm ICD, so it is not a Turnip gap. These two
 * hooks show what the core asks the driver for, and what it gets back, so the
 * missing capability can be named instead of guessed at. Observation only. */
static void (*real_GetPhysicalDeviceFeatures2)(void *, void *) = NULL;
static VkResult (*real_CreateDevice)(void *, const void *, const void *, void *) = NULL;

static const char *shim_stype_name(uint32_t t) {
    switch (t) {
        case 49:          return "VULKAN_1_1_FEATURES";
        case 50:          return "VULKAN_1_2_FEATURES";
        case 53:          return "VULKAN_1_3_FEATURES";
        case 1000082000:  return "SHADER_FLOAT16_INT8_FEATURES";
        case 1000083000:  return "16BIT_STORAGE_FEATURES";
        case 1000177000:  return "8BIT_STORAGE_FEATURES";
        case 1000225001:  return "SUBGROUP_SIZE_CONTROL_FEATURES";
        case 1000138000:  return "INLINE_UNIFORM_BLOCK_FEATURES";
        default:          return "?";
    }
}

static void hooked_GetPhysicalDeviceFeatures2(void *physDev, void *pF) {
    if (real_GetPhysicalDeviceFeatures2) real_GetPhysicalDeviceFeatures2(physDev, pF);
    if (!pF) return;
    static int logged = 0;
    if (logged++ > 2) return;
    unsigned char *p = (unsigned char *)pF;
    /* VkPhysicalDeviceFeatures2: sType@0 pNext@8 features@16 */
    uint32_t *feat = (uint32_t *)(p + 16);
    LOGI("VulkanShim: CASprobe Features2: shaderInt16=%u shaderInt64=%u "
         "fragmentStoresAndAtomics=%u vertexPipelineStoresAndAtomics=%u",
         feat[43], feat[41], feat[34], feat[33]);
    void *next = *(void **)(p + 8);
    while (next) {
        unsigned char *n = (unsigned char *)next;
        uint32_t t = *(uint32_t *)n;
        if (t == 1000082000u) {
            LOGI("VulkanShim: CASprobe   %s (%u): shaderFloat16=%u shaderInt8=%u",
                 shim_stype_name(t), t, *(uint32_t *)(n + 16), *(uint32_t *)(n + 20));
        } else {
            LOGI("VulkanShim: CASprobe   pNext %s (%u)", shim_stype_name(t), t);
        }
        next = *(void **)(n + 8);
    }
}

static VkResult hooked_CreateDevice(void *physDev, const void *pCreateInfo,
                                    const void *pAlloc, void *pDevice) {
    reload_turnip_conf_hot();
    if (pCreateInfo) {
        /* VkDeviceCreateInfo on arm64: enabledExtensionCount@48, names@56 */
        const unsigned char *ci = (const unsigned char *)pCreateInfo;
        uint32_t n = *(const uint32_t *)(ci + 48);
        const char *const *names = *(const char *const **)(ci + 56);
        LOGI("VulkanShim: CASprobe vkCreateDevice enables %u extensions:", n);
        for (uint32_t i = 0; i < n && names && i < 64; i++)
            if (names[i]) LOGI("VulkanShim: CASprobe   ext %s", names[i]);
        void *next = *(void **)(ci + 8);
        while (next) {
            unsigned char *x = (unsigned char *)next;
            uint32_t t = *(uint32_t *)x;
            LOGI("VulkanShim: CASprobe   enabled-features pNext %s (%u)",
                 shim_stype_name(t), t);
            next = *(void **)(x + 8);
        }
    }
    /* LSFG resolves its Vulkan entry points with volk, whose function pointers are
     * PROCESS-GLOBAL. volkLoadDevice() resolves each one against the device it is
     * handed, and on a device that never enabled VK_KHR_swapchain the driver
     * correctly returns NULL for vkQueuePresentKHR -- volk then stores that NULL
     * over the emulator's working pointer, and LSFG_3_1::Context::present does
     * `blr` on address 0 from thread fg-ctx, ~0.6 s after the context goes live.
     *
     * Established by disassembling liblsfg-android.so: the called slot is .bss at
     * 0x213e10 with no relocation, written only by volkLoadDevice and zeroed by
     * volkFinalize. It is not reachable from our proc-addr hook, which is why
     * guarding that path changed nothing.
     *
     * Enabling the extension on the device that omitted it keeps the slot
     * resolvable. It is the same physical device PCSX2 already presents from, so
     * support is not in question, and an enabled-but-unused extension costs
     * nothing. The ORIGINAL answer is kept for the binding decision below --
     * otherwise LSFG's compute device would start looking authoritative. */
    /* volkLoadDevice resolves ONLY the entry points whose extensions are enabled
     * on the device it is handed; everything else stays NULL in volk's global
     * table. PCSX2 enables little beyond VK_KHR_swapchain, while LSFG's
     * Context::present does external-semaphore work -- its third argument is a
     * vector<int> of sync fds. So the slot it calls (.bss 0x213e10, proven zero)
     * is an entry point the emulator's device never enabled.
     *
     * Driver SUPPORT was already confirmed for all of these; support is not the
     * same as enabled, which is why checking the .so's strings proved nothing.
     * If creation fails with them added, the retry below falls back untouched. */
    static const char *const kFgWantExts[] = {
        "VK_KHR_swapchain",
        "VK_KHR_external_semaphore_fd",
        "VK_KHR_external_memory_fd",
        "VK_ANDROID_external_memory_android_hardware_buffer",
        "VK_KHR_timeline_semaphore",
        "VK_EXT_robustness2",
    };

/* VkPhysicalDeviceRobustness2FeaturesEXT: sType@0 pNext@8 then three VkBool32.
 * LSFG-Android's own notes say full frame generation needs nullDescriptor, and
 * that it is "only available on recent Adreno GPUs (7xx-class and newer)" -- true
 * of Qualcomm's blob, but Mesa's Turnip sets nullDescriptor unconditionally on
 * a6xx (src/freedreno/vulkan/tu_device.cc, no per-generation guard). This app
 * RUNS on Turnip, so the capability is there; it was simply never ENABLED.
 * Enabling an extension does not enable its features -- the feature struct has to
 * be chained into VkDeviceCreateInfo.pNext, which PCSX2 never does. That is why
 * adding the extension alone changed nothing. */
#define SHIM_STYPE_ROBUSTNESS2 1000286001u
struct ShimRobustness2Features {
    uint32_t sType;
    uint32_t _pad;
    const void *pNext;
    uint32_t robustBufferAccess2;
    uint32_t robustImageAccess2;
    uint32_t nullDescriptor;
    uint32_t _tail;
};
    const int orig_has_sc = device_create_info_has_swapchain(pCreateInfo);
    unsigned char patched_ci[72];
    const char *patched_names[80];
    const void *ci_arg = pCreateInfo;
    int patched = 0;
    if (pCreateInfo && shim_framegen_wanted() && g_fg_experimental) {
        const unsigned char *ci = (const unsigned char *)pCreateInfo;
        uint32_t n = *(const uint32_t *)(ci + 48);
        const char *const *names = *(const char *const **)(ci + 56);
        if (n < 64) {
            uint32_t m = 0;
            for (uint32_t i = 0; i < n; i++)
                patched_names[m++] = names ? names[i] : NULL;
            for (size_t w = 0; w < sizeof(kFgWantExts) / sizeof(kFgWantExts[0]); w++) {
                int already = 0;
                for (uint32_t i = 0; i < n && names; i++)
                    if (names[i] && !strcmp(names[i], kFgWantExts[w])) { already = 1; break; }
                if (!already && m < 79) patched_names[m++] = kFgWantExts[w];
            }
            if (m != n) {
                memcpy(patched_ci, ci, sizeof(patched_ci));
                *(uint32_t *)(patched_ci + 48) = m;
                *(const char *const **)(patched_ci + 56) = patched_names;
                /* Chain nullDescriptor=TRUE, unless the caller already asked. */
                int have_r2 = 0;
                for (const void *nx = *(const void *const *)(ci + 8); nx;
                     nx = *(const void *const *)((const unsigned char *)nx + 8))
                    if (*(const uint32_t *)nx == SHIM_STYPE_ROBUSTNESS2) { have_r2 = 1; break; }
                if (!have_r2) {
                    static struct ShimRobustness2Features r2;
                    r2.sType = SHIM_STYPE_ROBUSTNESS2;
                    r2.pNext = *(const void *const *)(ci + 8);
                    r2.robustBufferAccess2 = 0;
                    r2.robustImageAccess2 = 0;
                    r2.nullDescriptor = 1;
                    *(const void **)(patched_ci + 8) = &r2;
                    static int rl;
                    if (!rl++)
                        LOGI("VulkanShim: enabling robustness2 nullDescriptor — LSFG "
                             "needs it to generate frames, Turnip has it on a6xx, and "
                             "an enabled EXTENSION does not enable its FEATURE");
                }
                ci_arg = patched_ci;
                patched = 1;
                static int l;
                if (!l++) {
                    LOGI("VulkanShim: device extensions %u -> %u for framegen; volk "
                         "can only resolve what is ENABLED, and LSFG presents through "
                         "external-semaphore entry points:", n, m);
                    for (uint32_t i = n; i < m; i++)
                        LOGI("VulkanShim:   + %s", patched_names[i]);
                }
            }
        }
    }
    VkResult r = real_CreateDevice ? real_CreateDevice(physDev, ci_arg, pAlloc, pDevice)
                             : (VkResult)-1;
    if (r != 0 && patched && real_CreateDevice) {
        /* Never let the injection be the reason a device fails to come up. */
        LOGE("VulkanShim: device creation failed with VK_KHR_swapchain added (%d); "
             "retrying with the caller's original extension list", (int)r);
        r = real_CreateDevice(physDev, pCreateInfo, pAlloc, pDevice);
    }
    if (r == 0 && pDevice && *(VkDevice *)pDevice && orig_has_sc)
        fg_bind_capture_device_ex(*(VkDevice *)pDevice, 1);
    return r;
}

static void (*real_GetPhysicalDeviceFeatures)(void*, void*) = NULL;

static void hooked_GetPhysicalDeviceFeatures(void *physDev, void *pFeatures) {
    if (real_GetPhysicalDeviceFeatures)
        real_GetPhysicalDeviceFeatures(physDev, pFeatures);
    
    if (pFeatures) {
        /* VkPhysicalDeviceFeatures is 55 VkBool32s. Log the key ones NetherSX2 likely checks */
        uint32_t *f = (uint32_t*)pFeatures;
        LOGI("VulkanShim: Features: robustBufferAccess=%u geometryShader=%u tessellation=%u "
             "sampleRateShading=%u dualSrcBlend=%u logicOp=%u multiDrawIndirect=%u "
             "depthClamp=%u depthBiasClamp=%u fillModeNonSolid=%u wideLines=%u "
             "largePoints=%u samplerAnisotropy=%u fragmentStoresAndAtomics=%u "
             "shaderInt64=%u",
             f[0], f[4], f[5],
             f[6], f[7], f[10], f[11],
             f[12], f[13], f[14], f[17],
             f[18], f[20], f[28],
             f[34]);
    }
}

static VkResult (*real_EnumDeviceExtProps)(void*, const char*, uint32_t*, void*) = NULL;

static VkResult hooked_EnumDeviceExtProps(void *physDev, const char *pLayer,
                                           uint32_t *pCount, void *pProps) {
    VkResult r = real_EnumDeviceExtProps(physDev, pLayer, pCount, pProps);
    
    if (r == 0 && pProps && pCount && g_disable_fbfetch) {
        /* Filter out the problematic extension */
        uint32_t write = 0;
        for (uint32_t i = 0; i < *pCount; i++) {
            char *extName = (char*)pProps + (i * 260);
            if (strcmp(extName, "VK_EXT_rasterization_order_attachment_access") == 0 ||
                strcmp(extName, "VK_ARM_rasterization_order_attachment_access") == 0) {
                LOGI("VulkanShim: filtering out %s for this GPU", extName);
                continue;
            }
            if (write != i)
                memcpy((char*)pProps + (write * 260), extName, 260);
            write++;
        }
        *pCount = write;
    }
    return r;
}

/* ------------------------------------------------------------------ */
/* ------------------------------------------------------------------ */
/* Surface frame-rate declaration                                      */
/* ------------------------------------------------------------------ */

struct ANativeWindow;

/* VkAndroidSurfaceCreateInfoKHR. Natural LP64 layout: 4 + 4 pad + 8 +
   4 + 4 pad + 8 = 32 bytes, matching the Vulkan header. */
typedef struct {
    uint32_t              sType;
    const void           *pNext;
    uint32_t              flags;
    struct ANativeWindow *window;
} ShimAndroidSurfaceCreateInfo;

/* ANativeWindow_setFrameRate is API 30 and the WithChangeStrategy variant
   is API 31, but this library targets 26 — so resolve at runtime rather
   than link against them, and no-op cleanly on older devices. */
#define SHIM_FRAME_RATE_COMPAT_FIXED_SOURCE 1
#define SHIM_CHANGE_FRAME_RATE_ALWAYS       1

static void apply_frame_rate(struct ANativeWindow *win) {
    /* This used to `return` on a null window without logging, which made a
       non-firing hook indistinguishable from a hook that fired and did
       nothing. Every exit path is traced now. */
    LOGI("VulkanShim: apply_frame_rate(win=%p) hz=%.2f", (void *)win, g_display_hz);
    if (!win) {
        LOGE("VulkanShim: null ANativeWindow -- cannot declare a frame rate");
        return;
    }
    if (g_enable_60fps_seen && !g_enable_60fps) {
        LOGI("VulkanShim: enable_60fps=off — not declaring panel Hz");
        return;
    }
    if (g_display_hz <= 0.0f) {
        LOGI("VulkanShim: display_hz off, leaving refresh rate to the system");
        return;
    }

    static void *libandroid = NULL;
    if (!libandroid) {
        libandroid = dlopen("libandroid.so", RTLD_NOW | RTLD_NOLOAD);
        if (!libandroid) libandroid = dlopen("libandroid.so", RTLD_NOW);
    }
    if (!libandroid) {
        LOGE("VulkanShim: cannot dlopen libandroid.so: %s", dlerror());
        return;
    }

    typedef int32_t (*PFN_setFrameRate2)(struct ANativeWindow *, float, int8_t, int8_t);
    typedef int32_t (*PFN_setFrameRate)(struct ANativeWindow *, float, int8_t);

    PFN_setFrameRate2 f2 = (PFN_setFrameRate2)
        dlsym(libandroid, "ANativeWindow_setFrameRateWithChangeStrategy");
    if (f2) {
        int32_t r = f2(win, g_display_hz,
                       SHIM_FRAME_RATE_COMPAT_FIXED_SOURCE,
                       SHIM_CHANGE_FRAME_RATE_ALWAYS);
        LOGI("VulkanShim: declared %.2f Hz (fixed source, allow non-seamless) -> %d",
             g_display_hz, r);
        return;
    }

    PFN_setFrameRate f1 = (PFN_setFrameRate)
        dlsym(libandroid, "ANativeWindow_setFrameRate");
    if (f1) {
        int32_t r = f1(win, g_display_hz, SHIM_FRAME_RATE_COMPAT_FIXED_SOURCE);
        LOGI("VulkanShim: declared %.2f Hz (fixed source) -> %d", g_display_hz, r);
        return;
    }

    LOGI("VulkanShim: ANativeWindow_setFrameRate unavailable (needs Android 11+)");
}

static VkResult (*real_CreateAndroidSurfaceKHR)(VkInstance, const void *,
                                                const void *, uint64_t *) = NULL;

/* Fresh ANativeWindow — allow TRANSFER on the next swapchain if FG is on. */
static void shim_surface_rearmed(void) {
    if (g_framegen)
        g_transfer_rejected = 0;
}

static VkResult hooked_CreateAndroidSurfaceKHR(VkInstance inst,
                                               const void *pCreateInfo,
                                               const void *pAllocator,
                                               uint64_t *pSurface) {
    LOGI("VulkanShim: hooked_CreateAndroidSurfaceKHR entered");
    VkResult r = real_CreateAndroidSurfaceKHR(inst, pCreateInfo, pAllocator, pSurface);
    if (r == 0 && pCreateInfo) {
        shim_surface_rearmed();
        /* Fires again on every surface recreation - rotation, resume, resize -
           so the declaration survives the app being backgrounded. */
        apply_frame_rate(((const ShimAndroidSurfaceCreateInfo *)pCreateInfo)->window);
    } else {
        LOGE("VulkanShim: surface create returned %d (pCreateInfo=%p) -- no rate set",
             (int)r, pCreateInfo);
    }
    return r;
}

/* ------------------------------------------------------------------ */
/* Custom vkGetInstanceProcAddr — also intercepts readback commands     */
/* ------------------------------------------------------------------ */

extern "C" PFN_vkVoidFunction vkGetInstanceProcAddr(VkInstance inst, const char *name) {
    static PFN_vkVoidFunction (*real_gipa)(VkInstance, const char*) = NULL;
    if (!real_gipa && g_sys_vulkan)
        real_gipa = (PFN_vkVoidFunction (*)(VkInstance, const char*))
            dlsym(g_sys_vulkan, "vkGetInstanceProcAddr");
    if (!real_gipa) {
        LOGE("VulkanShim: vkGetInstanceProcAddr not resolved");
        return NULL;
    }

    PFN_vkVoidFunction fn = real_gipa(inst, name);

    if (name) {
		/*
        if (strcmp(name, "vkCmdPipelineBarrier") == 0 && fn) {
            g_vkCmdPipelineBarrier = (PFN_vkCmdPipelineBarrier)fn;
            LOGI("VulkanShim: captured vkCmdPipelineBarrier (via GIPA) = %p", fn);
        }

        if (strcmp(name, "vkCmdCopyImageToBuffer2") == 0 && fn) {
            g_real_copy_itb2 = (PFN_vkCmdCopyImageToBuffer2)fn;
            LOGI("VulkanShim: intercepting vkCmdCopyImageToBuffer2 (via GIPA) = %p", fn);
            return (PFN_vkVoidFunction)hooked_CmdCopyImageToBuffer2;
        }

        if (strcmp(name, "vkCmdCopyImageToBuffer2KHR") == 0 && fn) {
            if (!g_real_copy_itb2)
                g_real_copy_itb2 = (PFN_vkCmdCopyImageToBuffer2)fn;
            LOGI("VulkanShim: intercepting vkCmdCopyImageToBuffer2KHR (via GIPA) = %p", fn);
            return (PFN_vkVoidFunction)hooked_CmdCopyImageToBuffer2;
        }
		*/
		
		if (strcmp(name, "vkGetPhysicalDeviceProperties") == 0 && fn) {
			real_GetPhysicalDeviceProperties = (void (*)(void*, void*))fn;
			return (PFN_vkVoidFunction)hooked_GetPhysicalDeviceProperties;
		}
		if ((strcmp(name, "vkGetPhysicalDeviceFeatures2") == 0 ||
		     strcmp(name, "vkGetPhysicalDeviceFeatures2KHR") == 0) && fn) {
			real_GetPhysicalDeviceFeatures2 = (void (*)(void *, void *))fn;
			return (PFN_vkVoidFunction)hooked_GetPhysicalDeviceFeatures2;
		}
		if (strcmp(name, "vkCreateDevice") == 0 && fn) {
			real_CreateDevice =
			    (VkResult (*)(void *, const void *, const void *, void *))fn;
			return (PFN_vkVoidFunction)hooked_CreateDevice;
		}
		if (strcmp(name, "vkGetPhysicalDeviceFeatures") == 0 && fn) {
			real_GetPhysicalDeviceFeatures = (void (*)(void*, void*))fn;
			return (PFN_vkVoidFunction)hooked_GetPhysicalDeviceFeatures;
		}
		if (strcmp(name, "vkEnumerateDeviceExtensionProperties") == 0 && fn) {
			real_EnumDeviceExtProps = (VkResult (*)(void*, const char*, uint32_t*, void*))fn;
			return (PFN_vkVoidFunction)hooked_EnumDeviceExtProps;
		}
		/* Always hook surface caps (observation only). */
		if (fn) {
			if (strcmp(name, "vkGetPhysicalDeviceSurfaceCapabilitiesKHR") == 0) {
				real_GetPhysDevSurfaceCapsKHR =
					(VkResult (*)(void *, uint64_t, void *))fn;
				LOGI("VulkanShim: intercepting vkGetPhysicalDeviceSurfaceCapabilitiesKHR "
				     "(panel extent / render-scale)");
				return (PFN_vkVoidFunction)hooked_GetPhysDevSurfaceCapsKHR;
			}
			if (strcmp(name, "vkGetPhysicalDeviceSurfaceCapabilities2KHR") == 0) {
				real_GetPhysDevSurfaceCaps2KHR =
					(VkResult (*)(void *, const void *, void *))fn;
				LOGI("VulkanShim: intercepting vkGetPhysicalDeviceSurfaceCapabilities2KHR "
				     "(panel extent / render-scale)");
				return (PFN_vkVoidFunction)hooked_GetPhysDevSurfaceCaps2KHR;
			}
#if NETHER_FSR_FRAMEGEN_ACTIVE
			/* Some loaders resolve device-level entry points through
			   vkGetInstanceProcAddr as well; the substitution has to be in place
			   whichever route the core took. */
			if (shim_render_scale_wanted()
			    && strcmp(name, "vkGetSwapchainImagesKHR") == 0) {
				real_GetSwapchainImagesKHR = (PFN_vkGetSwapchainImagesKHR)fn;
				LOGI("VulkanShim: intercepting vkGetSwapchainImagesKHR via GIPA "
				     "(render-scale)");
				return (PFN_vkVoidFunction)hooked_GetSwapchainImagesKHR;
			}
#endif
		}
		/* Observe memory properties even while framegen is OFF. The loader
		   caches this instance-level function before the user can enable FG;
		   gating resolution on the toggle permanently misses the first game's
		   query and leaves capture blocked at no-memprops. Recording physical
		   memory types allocates nothing and does not enable capture. */
		if (fn && strcmp(name, "vkGetPhysicalDeviceMemoryProperties") == 0) {
			real_GetPhysicalDeviceMemoryProperties =
				(void (*)(void*, void*))fn;
			static int l; if (!l++)
				LOGI("VulkanShim: intercepting vkGetPhysicalDeviceMemoryProperties "
				     "(framegen)");
			return (PFN_vkVoidFunction)hooked_GetPhysicalDeviceMemoryProperties;
		}
		/* Declare our presentation rate once the surface exists, so the
		   compositor can pick a display mode that divides evenly into it. */
		if (strcmp(name, "vkCreateAndroidSurfaceKHR") == 0 && fn) {
			real_CreateAndroidSurfaceKHR =
				(VkResult (*)(VkInstance, const void*, const void*, uint64_t*))fn;
			LOGI("VulkanShim: intercepting vkCreateAndroidSurfaceKHR");
			return (PFN_vkVoidFunction)hooked_CreateAndroidSurfaceKHR;
		}

        // Return our own vkGetDeviceProcAddr so the interception chain works 
        if (strcmp(name, "vkGetDeviceProcAddr") == 0) {
            LOGI("VulkanShim: returning hooked vkGetDeviceProcAddr");
            return (PFN_vkVoidFunction)vkGetDeviceProcAddr;
        }
		
		if (name && strcmp(name, "vkEnumeratePhysicalDevices") == 0) {
			LOGI("VulkanShim: returning forwarded vkEnumeratePhysicalDevices");
			return (PFN_vkVoidFunction)hooked_EnumeratePhysicalDevices;
		}
    }

    return fn;
}

typedef void (*PFN_vkCmdCopyImageToBuffer)(
    VkCommandBuffer, uint64_t /*VkImage*/, uint32_t /*VkImageLayout*/,
    uint64_t /*VkBuffer*/, uint32_t /*regionCount*/, const void* /*pRegions*/);

static PFN_vkCmdCopyImageToBuffer g_real_copy_itb_v1 = NULL;

static void hooked_CmdCopyImageToBuffer_v1(
    VkCommandBuffer cmdBuf, uint64_t srcImage, uint32_t srcLayout,
    uint64_t dstBuffer, uint32_t regionCount, const void *pRegions)
{
    if (g_vkCmdPipelineBarrier) {
        VkMemoryBarrier memBarrier;
        memBarrier.sType         = VK_STRUCTURE_TYPE_MEMORY_BARRIER;
        memBarrier.pNext         = NULL;
        memBarrier.srcAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT |
                                   VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT |
                                   VK_ACCESS_SHADER_WRITE_BIT |
                                   VK_ACCESS_MEMORY_WRITE_BIT;
        memBarrier.dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT |
                                   VK_ACCESS_MEMORY_READ_BIT;

        g_vkCmdPipelineBarrier(
            cmdBuf,
            VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
            VK_PIPELINE_STAGE_TRANSFER_BIT,
            0, 1, &memBarrier, 0, NULL, 0, NULL);
        
        LOGI("VulkanShim: barrier injected before v1 readback");
    }

    g_real_copy_itb_v1(cmdBuf, srcImage, srcLayout, dstBuffer, regionCount, pRegions);
}

/* ------------------------------------------------------------------ */
/* Custom vkGetDeviceProcAddr — intercepts readback commands            */
/* ------------------------------------------------------------------ */

extern "C" PFN_vkVoidFunction vkGetDeviceProcAddr(VkDevice dev, const char *name) {
    /* Deliberately NOT logging every resolve. Each LOGI is an fprintf plus a write()
     * to /sdcard, and fsr_load_fns alone resolves ~50 names, so this line flooded
     * logcat hard enough to roll the Java-side messages out of the buffer within
     * seconds — which made every pref-change diagnosis blind. */

    PFN_vkVoidFunction fn = shim_real_device_proc(dev, name);
    /* Same early-out as before this was factored into a helper: with no real
     * vkGetDeviceProcAddr there is nothing to wrap, so hand back nothing. */
    if (!g_have_real_gdpa)
        return NULL;

    if (name) {
        if (strcmp(name, "vkCmdDispatch") == 0 && fn && shim_cas_probe_wanted()) {
            real_CmdDispatch = (PFN_shim_cmd_dispatch)fn;
            static int dispatch_hook_logged;
            if (!dispatch_hook_logged++)
                LOGI("VulkanShim: intercepting vkCmdDispatch (CAS-probe)");
            return (PFN_vkVoidFunction)hooked_CmdDispatch;
        }

        if (strcmp(name, "vkCreateImage") == 0 && fn) {
            real_CreateImage = (PFN_shim_create_image)fn;
            static int create_image_hook_logged;
            if (!create_image_hook_logged++)
                LOGI("VulkanShim: intercepting vkCreateImage (GS IR probe)");
            return (PFN_vkVoidFunction)hooked_CreateImage;
        }

        /* In-process framegen observers. Gated on shim_framegen_wanted(), NOT on
         * shim_postprocess_enabled() — that is hard-off in this build. */
        if (shim_framegen_wanted()) {
            /* vkCmdDraw was previously installed ONLY under g_presenter_probe, so
             * in normal operation nothing counted draws -- the same install-gate
             * mistake already found on vkCmdDispatch and the viewport hooks. */
            if (strcmp(name, "vkCmdDraw") == 0 && fn) {
                real_CmdDraw = (PFN_shim_cmd_draw)fn;
                g_draw_hook_live = 1;
                LOGI("VulkanShim: intercepting vkCmdDraw (framegen target select)");
                return (PFN_vkVoidFunction)hooked_CmdDraw;
            }
            if (strcmp(name, "vkQueueSubmit") == 0 && fn) {
                real_QueueSubmit = (PFN_shim_queue_submit)fn;
                LOGI("VulkanShim: intercepting vkQueueSubmit (framegen queue check)");
                return (PFN_vkVoidFunction)hooked_QueueSubmit;
            }
            if (strcmp(name, "vkCmdPipelineBarrier") == 0 && fn) {
                real_CmdPipelineBarrier = (PFN_shim_cmd_barrier)fn;
                g_barrier_hook_live = 1;
                LOGI("VulkanShim: intercepting vkCmdPipelineBarrier "
                     "(framegen finished-frame select)");
                return (PFN_vkVoidFunction)hooked_CmdPipelineBarrier;
            }
            if (strcmp(name, "vkCmdDrawIndexed") == 0 && fn) {
                real_CmdDrawIndexed = (PFN_shim_cmd_draw_indexed)fn;
                g_draw_hook_live = 1;
                LOGI("VulkanShim: intercepting vkCmdDrawIndexed (framegen target select)");
                return (PFN_vkVoidFunction)hooked_CmdDrawIndexed;
            }
            if (strcmp(name, "vkCreateImageView") == 0 && fn) {
                real_CreateImageView =
                    (VkResult (*)(VkDevice, const void*, const void*, uint64_t*))fn;
                static int l; if (!l++) LOGI("VulkanShim: intercepting vkCreateImageView (framegen)");
                return (PFN_vkVoidFunction)hooked_CreateImageView;
            }
            if (strcmp(name, "vkCreateFramebuffer") == 0 && fn) {
                real_CreateFramebuffer =
                    (VkResult (*)(VkDevice, const void*, const void*, uint64_t*))fn;
                static int l; if (!l++) LOGI("VulkanShim: intercepting vkCreateFramebuffer (framegen)");
                return (PFN_vkVoidFunction)hooked_CreateFramebuffer;
            }
            /* Must install alongside the create hooks: without them the maps
             * are append-only and eventually answer for dead objects. */
            if (strcmp(name, "vkDestroyDevice") == 0 && fn) {
                real_DestroyDevice = (PFN_shim_destroy_device)fn;
                static int l; if (!l++) LOGI("VulkanShim: intercepting vkDestroyDevice (framegen)");
                return (PFN_vkVoidFunction)hooked_DestroyDevice;
            }
            if (strcmp(name, "vkDestroyImage") == 0 && fn) {
                real_DestroyImage = (PFN_shim_destroy_handle)fn;
                static int l; if (!l++) LOGI("VulkanShim: intercepting vkDestroyImage (framegen)");
                return (PFN_vkVoidFunction)hooked_DestroyImage;
            }
            if (strcmp(name, "vkDestroyImageView") == 0 && fn) {
                real_DestroyImageView = (PFN_shim_destroy_handle)fn;
                static int l; if (!l++) LOGI("VulkanShim: intercepting vkDestroyImageView (framegen)");
                return (PFN_vkVoidFunction)hooked_DestroyImageView;
            }
            if (strcmp(name, "vkDestroyFramebuffer") == 0 && fn) {
                real_DestroyFramebuffer = (PFN_shim_destroy_handle)fn;
                static int l; if (!l++) LOGI("VulkanShim: intercepting vkDestroyFramebuffer (framegen)");
                return (PFN_vkVoidFunction)hooked_DestroyFramebuffer;
            }
            if (strcmp(name, "vkGetDeviceQueue") == 0 && fn) {
                real_GetDeviceQueue =
                    (void (*)(VkDevice, uint32_t, uint32_t, void**))fn;
                static int l; if (!l++) LOGI("VulkanShim: intercepting vkGetDeviceQueue (framegen)");
                return (PFN_vkVoidFunction)hooked_GetDeviceQueue;
            }
            /* The render-pass hooks double as the framegen GS-target feed, so
             * they must install even when the presenter probe is off. */
            if (strcmp(name, "vkCmdBeginRenderPass") == 0 && fn && !g_presenter_probe) {
                real_CmdBeginRenderPass = (PFN_shim_cmd_begin_rp)fn;
                static int l; if (!l++) LOGI("VulkanShim: intercepting vkCmdBeginRenderPass (framegen)");
                return (PFN_vkVoidFunction)hooked_CmdBeginRenderPass;
            }
            if ((strcmp(name, "vkCmdBeginRendering") == 0 ||
                 strcmp(name, "vkCmdBeginRenderingKHR") == 0) && fn && !g_presenter_probe) {
                real_CmdBeginRendering = (PFN_shim_cmd_begin_rendering)fn;
                static int l; if (!l++) LOGI("VulkanShim: intercepting %s (framegen)", name);
                return (PFN_vkVoidFunction)hooked_CmdBeginRendering;
            }
            if (strcmp(name, "vkQueuePresentKHR") == 0 &&
                !shim_postprocess_enabled()) {
                if (fn) {
                    real_QueuePresentKHR = (PFN_vkQueuePresentKHR)fn;
                    static int l; if (!l++) LOGI("VulkanShim: intercepting vkQueuePresentKHR (framegen)");
                    return (PFN_vkVoidFunction)hooked_QueuePresentKHR;
                }
                /* The driver returns NULL here for a device that never enabled
                 * VK_KHR_swapchain -- LSFG's own compute device. LSFG stores the
                 * result unchecked and calls it from Context::present:
                 *
                 *   ldr x8, [x11]   ; dispatch slot == 0
                 *   blr x8          ; -> SIGSEGV pc=0 in thread fg-ctx
                 *
                 * Hand back the driver's real entry point instead of the NULL.
                 * Deliberately NOT our hook: LSFG's own presents must not be
                 * captured and fed back into the frame source. */
                if (real_QueuePresentKHR) {
                    static int warned;
                    if (!warned++)
                        LOGI("VulkanShim: vkQueuePresentKHR came back NULL for this "
                             "device; handing back the driver's real one so LSFG "
                             "cannot call a null dispatch slot");
                    return (PFN_vkVoidFunction)real_QueuePresentKHR;
                }
            }
        }

        /* g_fix_rotation MUST be in this list. The preTransform rewrite lives in
         * hooked_CreateSwapchainKHR, so without it the hook is never installed and
         * fix_rotation=on parses, logs, and does nothing -- the same install-gate
         * trap already found on vkCmdDispatch, the viewport hooks and vkCmdDraw. */
        if (strcmp(name, "vkCreateSwapchainKHR") == 0 && fn &&
            (g_present_mode >= 0 || g_min_image_count > 0 || g_fix_rotation ||
             shim_postprocess_enabled() || fg_swapchain_capture_wanted())) {
            real_CreateSwapchainKHR =
                (VkResult (*)(VkDevice, void*, const void*, uint64_t*))fn;
            LOGI("VulkanShim: intercepting vkCreateSwapchainKHR");
            return (PFN_vkVoidFunction)hooked_CreateSwapchainKHR;
        }

        if (strcmp(name, "vkGetSwapchainImagesKHR") == 0 && fn &&
            (shim_render_scale_wanted() || fg_swapchain_capture_wanted())) {
            real_GetSwapchainImagesKHR = (PFN_vkGetSwapchainImagesKHR)fn;
            LOGI("VulkanShim: intercepting vkGetSwapchainImagesKHR (%s)",
                 shim_render_scale_wanted() ? "render-scale" : "framegen swapchain capture");
            return (PFN_vkVoidFunction)hooked_GetSwapchainImagesKHR;
        }

        /* Present is only hooked when FSR or framegen is actually on at device
         * create. Always intercepting it (and binding FSR) with the toggles off
         * is what produced KGSL AHB bus errors and a full device reset. */
        if (shim_postprocess_enabled()) {
            if (strcmp(name, "vkQueuePresentKHR") == 0 && fn) {
                real_QueuePresentKHR = (PFN_vkQueuePresentKHR)fn;
                LOGI("VulkanShim: intercepting vkQueuePresentKHR (FSR/FG)");
                return (PFN_vkVoidFunction)hooked_QueuePresentKHR;
            }
            if (strcmp(name, "vkCmdSetViewport") == 0 && fn) {
                real_CmdSetViewport = (PFN_vkCmdSetViewport)fn;
                return (PFN_vkVoidFunction)hooked_CmdSetViewport;
            }
            if ((strcmp(name, "vkCmdSetViewportWithCount") == 0 ||
                 strcmp(name, "vkCmdSetViewportWithCountEXT") == 0) && fn) {
                real_CmdSetViewportWithCount = (PFN_vkCmdSetViewportWithCount)fn;
                return (PFN_vkVoidFunction)hooked_CmdSetViewportWithCount;
            }
            if (strcmp(name, "vkDestroySwapchainKHR") == 0 && fn) {
                real_DestroySwapchainKHR =
                    (VkResult (*)(VkDevice, uint64_t, const void *))fn;
                LOGI("VulkanShim: intercepting vkDestroySwapchainKHR");
                return (PFN_vkVoidFunction)hooked_DestroySwapchainKHR;
            }
        } else if (strcmp(name, "vkQueuePresentKHR") == 0 && fn) {
            static int passthrough_logged = 0;
            if (!passthrough_logged++) {
                LOGI("VulkanShim: present passthrough");
            }
        }

        /* Presenter-probe: which mechanism does the core use to get its frame into
         * the swapchain? A Xenia-style presenter (draw into the swapchain, FSR in
         * the shader) needs no transfer usage and no image substitution, so it
         * avoids both the UBWC cost and the compositor crash — but it can only be
         * built once we know whether to intercept render pass objects or dynamic
         * rendering, and how many draws the pass contains. Observation only. */
        if (g_presenter_probe) {
            if (strcmp(name, "vkCmdBeginRenderPass") == 0 && fn) {
                real_CmdBeginRenderPass = (PFN_shim_cmd_begin_rp)fn;
                LOGI("VulkanShim: probe: core resolved vkCmdBeginRenderPass "
                     "(render pass objects)");
                return (PFN_vkVoidFunction)hooked_CmdBeginRenderPass;
            }
            if ((strcmp(name, "vkCmdBeginRendering") == 0 ||
                 strcmp(name, "vkCmdBeginRenderingKHR") == 0) && fn) {
                real_CmdBeginRendering = (PFN_shim_cmd_begin_rendering)fn;
                LOGI("VulkanShim: probe: core resolved %s (dynamic rendering)", name);
                return (PFN_vkVoidFunction)hooked_CmdBeginRendering;
            }
            if (strcmp(name, "vkCmdDraw") == 0 && fn) {
                real_CmdDraw = (PFN_shim_cmd_draw)fn;
                LOGI("VulkanShim: probe: core resolved vkCmdDraw");
                return (PFN_vkVoidFunction)hooked_CmdDraw;
            }
        }

		/*
        // Capture vkCmdPipelineBarrier when first resolved 
        if (strcmp(name, "vkCmdPipelineBarrier") == 0) {
            g_vkCmdPipelineBarrier = (PFN_vkCmdPipelineBarrier)fn;
            LOGI("VulkanShim: captured vkCmdPipelineBarrier = %p", fn);
        }

        // Capture and intercept vkCmdCopyImageToBuffer2
        if (strcmp(name, "vkCmdCopyImageToBuffer2") == 0 && fn) {
            g_real_copy_itb2 = (PFN_vkCmdCopyImageToBuffer2)fn;
            LOGI("VulkanShim: intercepting vkCmdCopyImageToBuffer2 = %p", fn);
            return (PFN_vkVoidFunction)hooked_CmdCopyImageToBuffer2;
        }

        // Also intercept the KHR variant 
        if (strcmp(name, "vkCmdCopyImageToBuffer2KHR") == 0 && fn) {
            if (!g_real_copy_itb2)
                g_real_copy_itb2 = (PFN_vkCmdCopyImageToBuffer2)fn;
            LOGI("VulkanShim: intercepting vkCmdCopyImageToBuffer2KHR = %p", fn);
            return (PFN_vkVoidFunction)hooked_CmdCopyImageToBuffer2;
        }

        // Also intercept the non-2 variant (older API) 
        if (strcmp(name, "vkCmdCopyImageToBuffer") == 0 && fn) {
            g_real_copy_itb_v1 = (PFN_vkCmdCopyImageToBuffer)fn;
            LOGI("VulkanShim: intercepting vkCmdCopyImageToBuffer (v1) = %p", fn);
            return (PFN_vkVoidFunction)hooked_CmdCopyImageToBuffer_v1;
        }*/
		
		if (name && strcmp(name, "vkEnumeratePhysicalDevices") == 0) {
			return (PFN_vkVoidFunction)hooked_EnumeratePhysicalDevices;
		}
    }

    return fn;
}

/*
FORWARD_PFN(vkGetInstanceProcAddr,
    (VkInstance inst, const char *name),
    (inst, name))


FORWARD_PFN(vkGetDeviceProcAddr,
    (VkDevice dev, const char *name),
    (dev, name))
*/

extern "C" {

FORWARD_VOID(vkDestroyInstance,
    (VkInstance inst, const void *pAlloc),
    (inst, pAlloc))

FORWARD_VK(vkEnumerateInstanceExtensionProperties,
    (const char *pLayer, uint32_t *pCount, void *pProps),
    (pLayer, pCount, pProps))

FORWARD_VK(vkEnumerateInstanceLayerProperties,
    (uint32_t *pCount, void *pProps),
    (pCount, pProps))

FORWARD_VK(vkEnumerateInstanceVersion,
    (uint32_t *pVer),
    (pVer))
	
FORWARD_VK(vkEnumeratePhysicalDevices,
    (VkInstance inst, uint32_t *pCount, void *pDevs),
    (inst, pCount, pDevs))
	
}