# Building the Pink variants

These scripts rebuild the published shim and settings layer over an existing Pink APK. They do not contain the proprietary emulator core, BIOS, games, Lossless.dll or signing key.

On macOS, install the Android SDK (platform35 and build-tools35.0.0), NDK26.1.10909125, Java11, Python3, ripgrep and glslangValidator. Put apksigner.jar next to turnip-shim/repack-apk.py. Supply a Pink base APK containing the emulator and the LSFG library; FRAMEGEN reuses that library unless turnip-shim/lsfg-lib contains a replacement. Native LSFG modifications and their upstream revisions are included under third_party and THIRD_PARTY.json.

```bash
VARIANT=FRAMEGEN VERSION_CODE=26500718 bash build-nova-thor.sh /path/to/Pink-base.apk
VARIANT=NONFRAMEGEN VERSION_CODE=26500718 bash build-nova-thor.sh /path/to/Pink-base.apk
bash tests/run-host-checks.sh
bash tests/run-nonframegen-checks.sh
```

Both builds use the same source tree; variant constants are generated in temporary build directories. NONFRAMEGEN substitutes a native no-capture boundary and removes liblsfg-android.so from the APK. The original emulator, drivers and patch assets are retained from the input.

The existing signing key defaults to ~/.config/nethersx2-turnip/signing.jks; TURNIP_KEYSTORE can select another location. A different signing key cannot update an existing Pink installation in place. Keep private keys outside the repository. The APK filenames identify each variant; both share the version name and package.
