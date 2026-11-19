// librdexhelper.so — MonoMod's native exception helper built for Android (MonoMod.Core ships it only
// for glibc: exhelper_linux_arm64.so needs the unwinder from glibc's libgcc_s, which no Android
// library exports). Same exports and behaviour as theirs:
//  - eh_managed_to_native (exhelper_arm64.S): calls the target in x9; a native (C++) exception
//    unwinding out of it is caught here, kept in the thread's slot, and 0 is returned to managed code;
//  - eh_native_to_managed: calls the target in x9 with the slot cleared; if managed code left a native
//    exception there, it is raised again, on the native side;
//  - eh_get_exception_ptr / eh_has_exception: the calling thread's slot.
// MonoMod.Core needs it to hook CoreCLR's JIT (Core21Runtime.InstallManagedJitHook): the JIT's
// exceptions must not unwind through managed frames. librdglibc.so hands this library to MonoMod
// in place of the glibc build (see glibc_mkstemp there).
#include <pthread.h>
#include <stdint.h>
#include <stdlib.h>
#include <unwind.h>

static pthread_key_t slot_key;

__attribute__((constructor)) static void init(void) {
    pthread_key_create(&slot_key, free);
}

// The calling thread's slot; eh_get_exception_ptr (assembly) wraps it to keep x1-x8.
__attribute__((visibility("hidden"))) void** rd_eh_slot(void) {
    void** slot = (void**)pthread_getspecific(slot_key);
    if (!slot) {
        slot = (void**)calloc(1, sizeof(void*));
        pthread_setspecific(slot_key, slot);
    }
    return slot;
}

// Newer MonoMod.Core (Harmony 2.4+ for RimWorld 1.7) on ARM64 also looks this up and calls it after
// writing a detour, to flush the instruction cache. Without it every patch fails with "Unable to find
// an entry point named 'mmh_clear_cache'" (2026-11-18).
__attribute__((visibility("default"))) void mmh_clear_cache(void* addr, size_t size) {
    __builtin___clear_cache((char*)addr, (char*)addr + size);
}

__attribute__((visibility("default"))) int eh_has_exception(void) {
    return *rd_eh_slot() != NULL;
}

// Personality of eh_managed_to_native: its LSDA is one 32-bit offset, from the LSDA to the landing
// pad. Any exception that reaches the frame is caught there, with the exception object in x0.
__attribute__((visibility("hidden"))) _Unwind_Reason_Code rd_eh_personality(
        int version, _Unwind_Action actions, uint64_t exception_class,
        struct _Unwind_Exception* exception, struct _Unwind_Context* context) {
    (void)version;
    (void)exception_class;
    if (actions & _UA_FORCE_UNWIND) return _URC_CONTINUE_UNWIND;
    const int32_t* lsda = (const int32_t*)_Unwind_GetLanguageSpecificData(context);
    if (!lsda || *lsda == 0) return _URC_CONTINUE_UNWIND;
    if (actions & _UA_SEARCH_PHASE) return _URC_HANDLER_FOUND;
    if (!(actions & _UA_HANDLER_FRAME)) return _URC_CONTINUE_UNWIND;
    _Unwind_SetIP(context, (uintptr_t)lsda + *lsda);
    _Unwind_SetGR(context, 0, (uintptr_t)exception);
    return _URC_INSTALL_CONTEXT;
}
