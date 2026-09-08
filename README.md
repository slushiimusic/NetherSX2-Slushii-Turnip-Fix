# NetherSX2 (Slushii's Turnip Fix)

NetherSX2 tuned for the Retroid Pocket Nova and AYN Thor: a Turnip-driver build focused on getting the most performance possible from a handheld while keeping the setup simple.

## Download v26500718

Choose one build from the [latest release](https://github.com/slushiimusic/NetherSX2-Slushii-Turnip-Fix/releases/latest):

- **FRAMEGEN** — includes optional frame generation for a supported GPU and an active 120 Hz panel.
- **NONFRAMEGEN** — the same Pink emulator, with framegen controls, capture runtime and LSFG library removed.

Both APKs use `xyz.aethersx2.cpink02` and the same signing certificate. They are interchangeable versions of one app. Install the chosen APK over your existing Pink installation to retain settings, save states and memory cards. Do not uninstall or clear app data to switch versions.

[Update notes](releases/v26500718.md) · [Build instructions](BUILDING.md)

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

The 60 FPS option uses AetherSX2/PCSX2's existing PNACH cheat-patch system. It does not generate frames or force a game to run twice as fast. For supported games, it enables the matching 60 FPS patch and tuned per-game profile; frame generation is controlled separately in the FRAMEGEN build. The original NetherSX2 Turnip builds use the same core patch mechanism—the difference here is the included patch and Retroid Pocket Nova-specific profile selection.

---

## Shadow of the Colossus

The SOTC profile is configured for native resolution with the tuned EE cycle rate used by this build; cycle skipping stays disabled. Enable **60 FPS Mode** in Graphics, then cold-boot the game rather than loading an old save state.

---

## Optional frame generation

The FRAMEGEN build uses LSFG Android and requires your own Lossless.dll from Lossless Scaling. The DLL is not bundled. NONFRAMEGEN includes no framegen capture runtime or DLL setup prompt.

For **30 → 60 FPS without a 60 FPS patch**, turn **60 FPS Mode OFF**, restart the game, and leave **Frame Generation ON** with the display at **120 Hz**. The default 2× multiplier adds one generated frame between real frames; flow stays at **0.25**. The user confirmed this setup working on September 7, 2026. See the [setup and validation notes](releases/v26500718.md).

---

## Project lineage

This project continues the NetherSX2 patch work from [Trixarian/NetherSX2-patch](https://github.com/Trixarian/NetherSX2-patch), built around AetherSX2 4248.

NetherSX2 and AetherSX2 are separate projects. This build is unaffiliated with their respective owners.