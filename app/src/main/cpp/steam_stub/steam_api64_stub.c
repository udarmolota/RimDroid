// libsteam_api64.so for RimWorld 1.7 on Android: no Steam client here. The game's Steamworks.NET
// (Windows build, P/Invoke "steam_api64") quits the game when the library cannot be loaded at all
// (SteamManager: DllNotFoundException -> Application.Quit, RimWorld 1.7.4977). With this stub the
// library loads and simply answers "Steam is not running": RestartAppIfNecessary and Init return
// false, and the game goes on without Steam, as it did on the Linux build. Only the startup entry
// points exist; the game makes no other Steam call once Init failed.
// Newer Steamworks.NET (RimWorld 1.7 builds after 4995, 2026-11-11) starts through
// SteamInternal_SteamAPI_Init and gets ESteamAPIInitResult back; the answer is NoSteamClient (2).
#include <stddef.h>

#define API __attribute__((visibility("default")))

API int SteamAPI_RestartAppIfNecessary(unsigned int appId) { (void)appId; return 0; }
API int SteamAPI_Init(void) { return 0; }
API int SteamAPI_IsSteamRunning(void) { return 0; }
API void SteamAPI_Shutdown(void) {}
API void SteamAPI_RunCallbacks(void) {}
API void SteamAPI_ReleaseCurrentThreadMemory(void) {}
API void SteamAPI_ManualDispatch_Init(void) {}
API int SteamAPI_GetHSteamPipe(void) { return 0; }
API int SteamAPI_GetHSteamUser(void) { return 0; }
API const char* SteamAPI_GetSteamInstallPath(void) { return NULL; }
enum { kInitNoSteamClient = 2 };

/* ESteamAPIInitResult; pOutErrMsg is a SteamErrMsg, char[1024], or NULL. */
static int no_steam(char* err) {
    static const char msg[] = "Steam is not running";
    if (err) for (size_t i = 0; i < sizeof msg; i++) err[i] = msg[i];
    return kInitNoSteamClient;
}

API int SteamInternal_SteamAPI_Init(const char* versions, char* err) { (void)versions; return no_steam(err); }
API int SteamAPI_InitFlat(char* err) { return no_steam(err); }
API int SteamInternal_GameServer_Init_V2(unsigned int ip, unsigned short gamePort, unsigned short queryPort,
                                         int mode, const char* version, const char* versions, char* err) {
    (void)ip; (void)gamePort; (void)queryPort; (void)mode; (void)version; (void)versions;
    return no_steam(err);
}
API int SteamInternal_GameServer_Init(unsigned int ip, unsigned short a, unsigned short b, unsigned short c, int mode, const char* version) {
    (void)ip; (void)a; (void)b; (void)c; (void)mode; (void)version; return 0;
}
