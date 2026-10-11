# NetherSX2 (Slushii's Turnip Fix)

NetherSX2 tuned for the Retroid Pocket Nova and AYN Thor: a Turnip-driver build focused on getting the most performance possible from a handheld while keeping the setup simple.

## Gameplay update candidate: v26500800

Common Graphics controls now come first: renderer, resolution, aspect ratio,
widescreen, 60 FPS patches, optional frame generation and Show FPS. Expand
**Advanced graphics** for driver selection, filters and compatibility options.
Existing settings remain saved. New installs start at native resolution with
accurate readbacks. The performance preset no longer changes readback mode.

The update removes shader preparation from the game-start UI path, prevents a
late prepare callback from undoing Frame Generation OFF, coalesces repeated
settings work, and stops the framegen monitor after a game exits. Choosing
OpenGL or Software now survives app startup. Flow remains 0.25; artifact
protection remains disabled.

This is a test candidate, not a claim that all games or devices are fixed.
[Download the preview](https://github.com/slushiimusic/NetherSX2-Slushii-Turnip-Fix/releases/tag/v26500800) · [Update notes and validation](releases/v26500800.md).

### Daijisho

Install the optional **Slushii Daijisho Connector** and select
**ps2 - NetherSX2 Turnip** as the PS2 player in Daijisho. The connector uses
that existing entry's package/component and opens the installed Pink app.
Daijisho requires a visible launcher entry, so the connector adds a clearly
named helper icon. Pink keeps its original package and data; the connector
contains no emulator core or save storage. Do not uninstall Pink. The **Classic** entry still points
to the separate Classic app.

If a real app already uses `xyz.aethersx2.tturnip`, keep it: do not uninstall it
to install the connector. Instead edit a Daijisho player to target
`xyz.aethersx2.cpink02/xyz.aethersx2.android.EmulationActivity`, retaining the
`bootPath {file.uri}` argument. Add your game folder in Pink first so it has
permission to open those files. See [connector details](compat/daijisho/README.md).

## Download v26500723

Choose one build from the [latest release](https://github.com/slushiimusic/NetherSX2-Slushii-Turnip-Fix/releases/latest):

- **FRAMEGEN** — includes optional frame generation for a supported GPU and an active 60 Hz or 120 Hz panel.
- **NONFRAMEGEN** — the same Pink emulator, with framegen controls, capture runtime and LSFG library removed.

Both APKs use `xyz.aethersx2.cpink02` and the same signing certificate. They are interchangeable versions of one app. Install the chosen APK over your existing Pink installation to retain settings, save states and memory cards. Do not uninstall or clear app data to switch versions.

v26500723 fixes the hidden tabs in Control Settings. Both settings screens now keep their tab strip below the header from the first draw and update the spacing after rotation. The icon and installer-name repairs from v26500722 are retained.

[Update notes](releases/v26500723.md) · [Build instructions](BUILDING.md)

---

## Why this build exists

This started from wanting to squeeze as much real performance as possible out of the Retroid Pocket Nova. The focus is native-resolution playability, reliable frame pacing, and settings that make sense for the device instead of chasing a one-size-fits-all preset.

Performance depends on the game, scene, device and settings. Generated display frames do not increase emulation speed. Start at native resolution and raise it only when gameplay remains steady.

---

## Reported compatibility issue

**Xenosaga Episode I:** a tester reports the title screen or another stale image during loading, including with framegen off. This is under investigation and closely resembles an older PCSX2 hardware-renderer issue. **Neither v26500723 nor the v26500800 preview includes a verified fix.** See the [evidence and diagnostic comparison](COMPATIBILITY.md).

---

## What is included

- A customized NetherSX2 Classic build based on AetherSX2 3668. The included core identifies as `v2.2n-3668 (Classic)`.
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

Artifact protection is disabled completely for both 60 and 120 FPS output. Motion and scene changes no longer trigger its repeated-real-frame fallback, and older saved ON settings are ignored. Interpolation warping and scene-cut artifacts may be more visible.

The FRAMEGEN build uses LSFG Android and requires your own Lossless.dll from Lossless Scaling. The DLL is not bundled. NONFRAMEGEN includes no framegen capture runtime or DLL setup prompt.

For **30 → 60 FPS without a 60 FPS patch**, turn **60 FPS Mode OFF**, restart the game, and leave **Frame Generation ON** with the display at **60 Hz or 120 Hz**. The default 2× multiplier adds one generated frame between real frames; flow stays at **0.25**. The user confirmed 30 → 60 FPS operation on September 7, 2026; the panel can stay at 60 Hz. See the [setup and validation notes](releases/v26500723.md).

---

## Project lineage

This release uses **NetherSX2 Classic**, based on **AetherSX2 3668**. The included emulator core reports **`v2.2n-3668 (Classic)`**. See [Trixarian/NetherSX2-classic](https://github.com/Trixarian/NetherSX2-classic) for the Classic lineage. The previous description naming AetherSX2 4248 was incorrect; this update corrects the description without replacing the emulator core.

**`v26500723` is the Slushii package/release number**, separate from the underlying Classic core version. FRAMEGEN and NONFRAMEGEN share that core; they differ in the optional frame-generation functionality.

NetherSX2 and AetherSX2 are separate projects. This build is unaffiliated with their respective owners.
