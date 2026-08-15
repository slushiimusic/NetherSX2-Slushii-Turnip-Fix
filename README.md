# NetherSX2 (Slushii's Turnip Fix)

NetherSX2 tuned for the Retroid Pocket Nova: a Turnip-driver build focused on getting the most performance possible from a handheld while keeping the setup simple.

## Download

**[Download the latest APK](https://github.com/slushiimusic/NetherSX2-Slushii-Turnip-Fix-/releases/latest)**

1. Download the APK on your Android device.
2. Open it from Downloads and allow installation when Android asks.
3. Launch **NetherSX2 (Slushii's Turnip Fix)** and complete setup.

The build is a separate app, so it can sit alongside other NetherSX2 installations.

---

## Why this build exists

This started from wanting to squeeze as much real performance as possible out of the Retroid Pocket Nova. The focus is native-resolution playability, reliable frame pacing, and settings that make sense for the device instead of chasing a one-size-fits-all preset.

For now, *Shadow of the Colossus* and *Metal Gear Solid 3: Subsistence* will only hit ~60 FPS at native resolution, with heavier moments dipping toward 50 FPS. Results vary with the game area, game version, and individual settings.

---

## What is included

- A continuation of NetherSX2 based on AetherSX2 4248.
- Turnip driver installation and Retroid Pocket Nova-oriented performance settings.
- 60 FPS patch support for compatible titles, including community work from PeterDelta and Gabominated.
- Per-game Shadow of the Colossus profiles and patch installation for USA and PAL releases.
- The Slushii theme, icon treatment, and setup experience.

*Resident Evil 4* and *Resident Evil Code: Veronica* do not currently have working 60 FPS patches in this build. Work on those is ongoing.

### How 60 FPS works

The 60 FPS option uses AetherSX2/PCSX2's existing PNACH cheat-patch system. It does not generate frames or force a game to run twice as fast. For supported games, it enables the matching 60 FPS patch and tuned per-game profile; frame generation remains off. The original NetherSX2 Turnip builds use the same core patch mechanism—the difference here is the included patch and Retroid Pocket Nova-specific profile selection.

---

## Shadow of the Colossus

The SOTC profile is configured for native resolution with the tuned EE cycle rate used by this build; cycle skipping stays disabled. Enable **60 FPS Mode** in Graphics, then cold-boot the game rather than loading an old save state.

---

## In progress: FSR through LSFG Android

Work is underway to implement FSR through the Android version of LSFG. It will require `Lossless.dll` from a legal copy of Lossless Scaling; that file is not bundled with this project or its releases.

---

## Project lineage

This project continues the NetherSX2 patch work from [Trixarian/NetherSX2-patch](https://github.com/Trixarian/NetherSX2-patch), built around AetherSX2 4248.

NetherSX2 and AetherSX2 are separate projects. This build is unaffiliated with their respective owners.