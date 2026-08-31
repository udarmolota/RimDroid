using System;
using Verse;

namespace RimDroid.ControllerUI
{
    /// <summary>
    /// Enables RimWorld's own controller-oriented UI when RimDroid detected a
    /// physical gamepad before launch. This runs on RimWorld's managed loading
    /// thread, after Mono and Verse are initialized.
    /// </summary>
    public sealed class ControllerUiMod : Mod
    {
        public ControllerUiMod(ModContentPack content) : base(content)
        {
            if (Environment.GetEnvironmentVariable("RIMDROID_CONTROLLER_UI") != "1")
                return;

            DebugSettings.simulateUsingSteamDeck = true;
            Log.Message("[RimDroid] Controller UI enabled for the connected gamepad.");
        }
    }
}
