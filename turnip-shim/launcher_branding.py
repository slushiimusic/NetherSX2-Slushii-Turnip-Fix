"""Repair Pink's adaptive-icon background and installer-facing application name."""
import os
from pathlib import Path
import struct
import subprocess
import tempfile
import zipfile

from axml_manifest import find_pool_and_header, iter_elements

APP_LABEL = "NetherSX2 (Slushii's Turnip Fix)"
BACKGROUND = "res/drawable/ic_launcher_background.xml"


def patch_application_label(manifest):
    """Use the existing launcher label for the installer without changing IDs."""
    header, _, pool = find_pool_and_header(manifest)
    labels = {}
    for off, tag, name, _ in iter_elements(manifest, header, pool):
        if tag != "application" and not (
                tag == "activity" and name == "xyz.aethersx2.android.MainActivity"):
            continue
        chunk_header = struct.unpack_from("<H", manifest, off + 2)[0]
        start, size, count = struct.unpack_from("<HHH", manifest, off + chunk_header + 8)
        for i in range(count):
            at = off + chunk_header + start + i * size
            name_index = struct.unpack_from("<i", manifest, at + 4)[0]
            if name_index >= 0 and pool["strings"][name_index] == "label":
                labels[tag] = at
    if set(labels) != {"application", "activity"}:
        raise ValueError("Pink application/launcher labels are missing")
    launcher = labels["activity"]
    label_index = struct.unpack_from("<I", manifest, launcher + 16)[0]
    if manifest[launcher + 15] != 3 or pool["strings"][label_index] != APP_LABEL:
        raise ValueError("Unexpected Pink launcher name; refusing to change branding")
    out = bytearray(manifest)
    app = labels["application"]
    # Copy the raw string index and typed value, retaining namespace/name fields.
    out[app + 8:app + 20] = manifest[launcher + 8:launcher + 20]
    return bytes(out)


def compile_background():
    sdk = Path(os.environ.get("ANDROID_HOME", str(Path.home() / "Library/Android/sdk")))
    aapt = Path(os.environ.get("AAPT", str(sdk / "build-tools/35.0.0/aapt")))
    platform = sdk / "platforms/android-35/android.jar"
    with tempfile.TemporaryDirectory(prefix="pink-launcher-") as tmp:
        tmp = Path(tmp)
        manifest = tmp / "AndroidManifest.xml"
        manifest.write_text('<manifest xmlns:android="http://schemas.android.com/apk/res/android" '
                            'package="pink.icon.compiler"><uses-sdk android:minSdkVersion="26"/>'
                            '</manifest>')
        apk = tmp / "launcher.apk"
        # Without this flag/minSdk, aapt puts vector attributes in a separate
        # v21 resource; copying only drawable/ silently packages an invalid vector.
        subprocess.run([str(aapt), "package", "-f", "--no-version-vectors",
                        "-I", str(platform), "-M", str(manifest),
                        "-S", str(Path(__file__).parent / "launcher-res"),
                        "-F", str(apk)], check=True)
        with zipfile.ZipFile(apk) as compiled:
            return compiled.read(BACKGROUND)
