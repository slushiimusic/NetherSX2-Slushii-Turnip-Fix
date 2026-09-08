# Compatibility reports

## Xenosaga Episode I: stale image during loading

**Status: reported on AYN Thor; no verified fix in v26500722.** The tester confirms the **U.S. version** of Xenosaga Episode I, with **Low-End Performance OFF** and **60 FPS Mode OFF**. During loading, the image can be any previously displayed scene, including the title screen, or sometimes the expected black loading screen. It occurs with and without Lossless Scaling and persists across Turnip drivers. This has not been reproduced locally; the exact installed app version, widescreen and Hardware Download Mode settings remain unconfirmed.

The bundled U.S. profile is `SLUS-20469` / CRC `6D1276AB`. Its shadow/font adjustments and save-point thumbnail patch do not implement the loading-transition fixes discussed below.

The symptom closely matches [PCSX2 issue #6625](https://github.com/PCSX2/pcsx2/issues/6625), where the title screen or another old image appeared after loading. Two hardware-renderer changes addressed that issue in February 2023:

- [PR #8107](https://github.com/PCSX2/pcsx2/pull/8107) makes texture moves create a missing destination target instead of falling back to stale local-memory contents.
- [PR #8126](https://github.com/PCSX2/pcsx2/pull/8126) prevents idle loading frames from aging out the targets needed for the transition.

Our retained core reports **v2.2n-3668 (Classic)** and a January 1, 2023 build date. That makes an inherited hardware-renderer issue a plausible explanation, not a confirmed diagnosis of this tester's report. These fixes are in the emulator's GS texture-cache code, whose source is not included in this project. Changing the Turnip driver or the framegen library does not apply those core changes.

### Diagnostic comparison

For **Xenosaga only**, select **Game Properties → Graphics → GPU Renderer → Software**, use **Native** resolution, restart the game, and repeat the same loading transition. Compare with hardware rendering at the same point. Software rendering may be slower; this is an isolation test, not a verified performance preset or confirmed fix.

Record the installed app version and the states of **Widescreen** and **Hardware Download Mode**. Low-End Performance and 60 FPS Mode are already confirmed OFF. Hardware Download Mode is independent: if it is set to disable readbacks, compare **Accurate** for this game and restart before retesting. This comparison is not a confirmed workaround. Change one setting at a time and restore the previous value if it makes no difference.

No Xenosaga-specific 60 FPS patch was found in this release's bundled `pnach60` assets. Its widescreen patch and older core behavior still need to be distinguished from the reported loading-screen failure using a reproducible case.

The investigation has not changed app settings, game files, memory cards or save states, and no new APK has been published as a claimed fix.
