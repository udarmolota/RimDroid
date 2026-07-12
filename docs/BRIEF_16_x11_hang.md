# Brief: RimWorld 1.6 (Unity 2022.3.35f1) hangs in its SDL2 X11 video init under a custom in-process X server

## Context (one paragraph)

RimDroid runs the **Linux x86_64** build of RimWorld on Android via **box64**. RimWorld 1.5 used SDL's
dynamic API override; 1.6 upgraded to Unity 2022.3 whose statically-linked SDL (a Unity fork of ~2.0.22,
`SDL_GetWindowWMInfo` version check is 2.0.22) targets **X11 + Vulkan**. We therefore ported Winlator's
pure-Java X11 server (windowing/input only; no GLX/DRI3/Present) into the app, and box64's wrappedvulkan
redirects `vkCreateXlibSurfaceKHR` → `vkCreateAndroidSurfaceKHR` on the app's ANativeWindow (Turnip ICD).
The guest talks to the X server over a unix socket; libX11/libXrandr are the **emulated Debian guest
libraries** (not wrapped), so all X traffic is observable server-side. BOX64 log + a per-request trace
in the X server + guest stderr all land in Unity's Player.log / logcat.

## What already works (verified on device)

1. X server correctness: a native-compiled guest test client (`vprobe`) using real libX11/libXrandr
   passes the full SDL `X11_InitModes` sequence: XRRQueryVersion 1.3, GetScreenResourcesCurrent
   (1 crtc/output/mode, 1280x720@60), GetOutputPrimary, GetOutputInfo (connected, crtc set),
   GetCrtcInfo, XMatchVisualInfo(24, TrueColor) → visual id 0x1. A second probe (`vprobe4`) creates a
   window, maps it, sets focus, and **receives MapNotify(19), Expose(12), FocusIn(9)** correctly.
2. The game itself completes `X11_InitModes` (SDL sees the display: "Desktop is 1280 x 720 @ 60 Hz"),
   keyboard init (GetKeyboardMapping etc.), creates windows, maps them, warps the pointer.
3. Unity's **Vulkan** leg works: instance + physical device ("Adreno (TM) 830 (v25.1)" via Turnip)
   created; our WSI shim advertises VK_KHR_xlib_surface and swaps it for VK_KHR_android_surface at
   vkCreateInstance ("RIMDROID: vkCreateInstance X11 WSI -> android_surface (8 -> 7 ext)").
4. GL probe failure path: Unity's renderer selection tries GLCore first. We ship a guest `libGL.so.1`
   stub whose `glXChooseVisual` returns the real depth-24 TrueColor visual (so SDL_WINDOW_OPENGL
   windows create fine — Unity hardcodes that flag) and whose `glXCreateContext` returns NULL, so the
   GLCore probe fails exactly like a desktop without GL, and Unity proceeds toward Vulkan.

## The problem

After the GL probe fails (by design), the guest main thread (`RimWorldLinux`) blocks **forever** in
`ppoll(nfds=1, timeout=NULL)` — classic Xlib "wait for more data on the X socket" — even though **both
X sockets have Recv-Q = Send-Q = 0** (everything we sent was read). CPU ~0-4%. All Unity job threads
exist and sleep in futex. Player.log stops right after the GL-probe failure lines; the Vulkan init that
should follow never starts (in the current stub run it stalls even before printing
"Unable to find a supported OpenGL core profile", right after `glXQueryExtensionsString`).

The X request tail at the moment of the hang (server trace; 3 windows were created — the GL probe
created one at seq 69/73, the "main" pair at seq ~103-119):

```
seq=108 ChangeWindowAttributes(2)
seq=109 ChangeWindowAttributes(2)
seq=110 InstallColormap(81)        -> silently ignored (no reply defined, no error sent)
seq=111 ClearArea(61)
seq=112 ConfigureWindow(12)        -> we sent ConfigureNotify 1280x720+0+0 (StructureNotify + parent)
seq=113 MapWindow(8)               -> we sent MapNotify (+VisibilityNotify code 15, +Expose)
seq=114 UngrabPointer(27)
seq=115 WarpPointer(41)            -> we now send MotionNotify (fix: warp goes through setPosition)
seq=116 ChangeProperty(18)         -> PropertyNotify sent
seq=117 ReparentWindow(7)          -> client reparents ITS OWN window 0x400002 under 0x400003 (!);
                                      we now send ReparentNotify (code 21) to both windows
seq=118 WarpPointer(41)            -> MotionNotify sent
seq=119 WarpPointer(41)            -> MotionNotify sent
<silence forever; no further requests, no reads pending>
```

Events delivered to the game in this run: PropertyNotify, ConfigureNotify(1280x720)×2, MapNotify×2,
Expose, FocusIn (SetInputFocus handled; focus-window bug fixed), ReparentNotify, MotionNotify×2,
VisibilityNotify (offered; note SDL never selects VisibilityChangeMask). All events are exactly 32
bytes; replies for GetWindowAttributes/GetGeometry/GetInputFocus are standard-sized. No X errors are
outstanding (InstallColormap/UninstallColormap are swallowed without BadImplementation now).

## Established facts / eliminated hypotheses

- Not a missing MapNotify/FocusIn/ConfigureNotify/ReparentNotify/MotionNotify — all sent AND the probe
  client receives events fine, yet the game still waits. Byte-level event encoding verified 32/32.
- Not a socket-buffer flush problem: Recv-Q/Send-Q are 0 on both ESTAB unix sockets — the client
  consumed everything and deliberately waits for MORE.
- Not the Android surface/lockscreen (screen kept awake; same hang).
- Not a reply-size desync on the visible tail (the client would be stuck mid-packet; instead it polls).
- The same binary on desktop Linux works, so it waits for something a real X server (with a real WM
  running!) produces and we don't.

## Open questions for you

1. The client **reparents its own toplevel** (seq 117: 0x400002 → child of 0x400003) — neither stock
   SDL nor Unity documentation mentions this. What component does that at startup? (SDL 2.0.22
   `SDL_CreateWindow` doesn't reparent; there is no WM here.) Unity's "embedded window" path? Could it
   be waiting for the classic **WM handshake** instead — i.e. it treats OUR missing reparent-to-frame
   as "WM will reparent me soon"?
2. Which blocking Xlib waits exist in SDL 2.0.22 / Unity's fork right after `XMapWindow` +
   `XSetInputFocus` + `XWarpPointer` that a WM normally satisfies? Candidates we suspect but cannot
   confirm: `_NET_WM_STATE` / fullscreen ping-pong (`X11_SetWindowFullscreen` legacy path),
   `X11_CatchAnyError`+XSync loops, `WaitForNotify` on VisibilityNotify (SDL doesn't select it),
   `_NET_SUPPORTING_WM_CHECK` polling loop?
3. Unity Linux Player: is there a known **"wait for window focus/expose before initializing graphics"**
   loop between renderer probing and `InitializeEngineGraphics`? (Player.log stops between the two.)
   Any known env/CLI to skip it (`-force-vulkan`? `SDL_VIDEO_X11_...`? `UNITY_...`?)
4. Since there is **no window manager**, should we make every toplevel `override-redirect`-like from
   the server side (pretend OverrideRedirect=1 in GetWindowAttributes replies), or synthesize the
   full WM handshake (ReparentNotify to a fake frame + synthetic ConfigureNotify + WM_STATE property +
   _NET_WM_STATE change)? Which minimal set does SDL 2.0.22 actually require to consider a window
   "shown, focused, fullscreen-ready"? Note: SDL checks `_NET_SUPPORTING_WM_CHECK` → with no WM it
   should take the no-WM path everywhere (`videodata->net_wm == false`) — unless Unity's fork differs.
5. Any way to see WHERE the statically-linked SDL blocks without symbols? We have: box64 (can patch
   wrapped syscalls, e.g. log the fd/backtrace of the infinite ppoll), full UnityPlayer.so disassembly
   (x86_64, no symbols beyond exports), install-time byte-patching of UnityPlayer.so (already used for
   two other fixes), and we can add ANY logging to the X server. The one thing we lack is a guest
   debugger (no gdbserver; ptrace restricted; debuggerd needs root).

## Extra observations that may matter

- The hang point moved with our fixes: before MotionNotify-on-warp it stalled at the same place but
  with 196 requests; now 200. So the client's *request* stream is deterministic and ends after the
  3rd WarpPointer regardless of which events we feed it. That smells like ONE specific blocking wait
  right after the warp calls, not an event-starvation loop.
- WarpPointer × 3 with UngrabPointer before them — mouse-grab-related? SDL relative-mouse-mode init
  (`SDL_SetRelativeMouseMode`) does WarpPointer to window center + XGrabPointer. We see UNgrab (27) but
  never XGrabPointer (26) — is the client waiting for a grab confirmation our server never sends?
  (GrabPointer has a REPLY; UngrabPointer doesn't. If the client called XGrabPointer earlier and our
  handler failed to reply... but no GrabPointer appears in the trace at all.)
- ClearArea(61) with exposures? If the client requested `exposures=True`, a real server sends an
  Expose event after clearing. Our handler may ignore that flag — an easily-missed event source.
- The client never sends XSync/GetInputFocus after seq 119 — so it is NOT in a poll-with-XSync loop;
  it's a single blocking read.

## What we'd like back

Ranked concrete hypotheses for "what event/reply is the Unity-SDL waiting for after
map+focus+3×warp with no WM", each with a cheap server-side experiment to confirm (we can implement
any event/property synthesis within minutes and re-run on device).
