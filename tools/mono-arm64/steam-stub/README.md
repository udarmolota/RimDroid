# ARM64 Steamworks stub

RimWorld's Steamworks.NET P/Invokes `steam_api`. With native ARM64 Mono the game's
x86_64 `libsteam_api.so` cannot be loaded, and Steamworks.NET's `SteamManager` quits the
game on `DllNotFoundException` (the first headless run ended that way right after the
`RimWorld 1.6` banner).

`build.sh` generates an ARM64 `libsteam_api.so` in which every exported Steamworks
function returns 0. `SteamAPI_RestartAppIfNecessary` and `SteamAPI_Init` therefore report
"Steam is not running", which is what the real library reports on Android anyway, and
the game continues without Steam.

`steam_api_exports.txt` is the list of function exports of the game's x86_64 library
(`llvm-nm -D --defined-only`, type `T`, without `_init`/`_fini`). Regenerate it when the
game updates its Steamworks SDK.

The stub is placed next to the native Mono runtime; the Box64 `libmonobdwgc` wrapper's
dl fallback finds it there.
