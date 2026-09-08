# Compatibility reports

## Xenosaga Episode I: stale image during loading

**Status: reported; no verified fix in v26500722.** A tester reports that loading screens show the title screen, with and without Lossless Scaling and after switching Turnip drivers. The device, game region, exact app version and settings have not been confirmed. This has not been reproduced locally.

The symptom closely matches [PCSX2 issue #6625](https://github.com/PCSX2/pcsx2/issues/6625), where the title screen or another old image appeared after loading. Two hardware-renderer changes addressed that issue in February 2023:

- [PR #8107](https://github.com/PCSX2/pcsx2/pull/8107) makes texture moves create a missing destination target instead of falling back to stale local-memory contents.
- [PR #8126](https://github.com/PCSX2/pcsx2/pull/8126) prevents idle loading frames from aging out the targets needed for the transition.

Our retained core reports **v2.2n-3668 (Classic)** and a January 1, 2023 build date. That makes an inherited hardware-renderer issue a plausible explanation, not a confirmed diagnosis of this tester's report. These fixes are in the emulator's GS texture-cache code, whose source is not included in this project. Changing the Turnip driver or the framegen library does not apply those core changes.

### Diagnostic comparison

For **Xenosaga only**, select **Game Properties → Graphics → GPU Renderer → Software**, use **Native** resolution, restart the game, and repeat the same loading transition. Compare with hardware rendering at the same point. Software rendering may be slower; this is an isolation test, not a verified performance preset or confirmed fix.

Also record the device, game serial/region, app version and the states of **Low-End Performance**, **60 FPS Mode**, **Widescreen** and **Hardware Download Mode**. The Low-End Performance preset disables hardware readbacks, so an **Accurate** readback comparison is useful if that preset is enabled. Change one setting at a time.

No Xenosaga-specific 60 FPS patch was found in this release's bundled `pnach60` assets. Its widescreen patch and older core behavior still need to be distinguished from the reported loading-screen failure using a reproducible case.

The investigation has not changed app settings, game files, memory cards or save states, and no new APK has been published as a claimed fix.
