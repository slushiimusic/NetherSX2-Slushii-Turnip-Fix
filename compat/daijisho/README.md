# Daijisho connector for Pink

This small optional APK implements the exact launch component in Daijisho's existing **ps2 - NetherSX2 Turnip** player. It forwards the selected game's `bootPath` to Pink, `xyz.aethersx2.cpink02/xyz.aethersx2.android.EmulationActivity`.

1. Update Pink in place and add the folder containing your games in Pink.
2. Install `Slushii-Daijisho-Connector-v1.apk`.
3. In Daijisho's PS2 platform editor, choose **ps2 - NetherSX2 Turnip** as the default player. Do not choose the Classic entry for Pink.
4. Open a game from Daijisho.

The compatibility APK uses package `xyz.aethersx2.tturnip`, adds a clearly named **Daijisho Connector** helper icon, contains no emulator, and never reads or writes save data. Daijisho 1.5.0 checks launcher entries before it will launch a player, so this helper entry must remain enabled. The ordinary Pink app retains its icon, package, signing identity and saves. It forwards a content-URI read grant when one is actually held; otherwise Pink must already have permission to the game folder. Missing games/apps produce a short explanation instead of a silent finish.

If `xyz.aethersx2.tturnip` is already occupied by a real emulator, leave that app installed. Customize a player with these launch arguments instead:

```
-n xyz.aethersx2.cpink02/xyz.aethersx2.android.EmulationActivity
-a android.intent.action.MAIN
-e bootPath {file.uri}
--activity-clear-task
--activity-clear-top
```

Source of the compatibility entry: [Daijisho PS2 platform definition](https://github.com/TapiocaFox/Daijishou/blob/main/platforms/SonyPlayStation2.json), inspected October 11, 2026. This is an unofficial compatibility connector, not an official Daijisho or upstream NetherSX2 app.

Build with `bash compat/daijisho/build.sh`. It requires the Android SDK and the existing local Pink signing key. The key is never included in the source or APK.
