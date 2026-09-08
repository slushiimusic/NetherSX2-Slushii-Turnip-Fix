#!/usr/bin/env python3
"""Refresh VulkanShim graphics prefs + shim UI (dex, manifest, activities)."""
import importlib.util
import os
import subprocess
import sys
import struct
from axml_manifest import find_pool_and_header, iter_elements
from axml_replace_strings import replace_axml_strings
from launcher_branding import BACKGROUND, compile_background, patch_application_label
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))

# Global App Settings + in-game Game Settings both expose a Graphics tab.
GFX_XMLS = (
    "res/xml/graphics_preferences.xml",
    "res/xml/graphics_game_settings_preferences.xml",
)
MANIFEST = "AndroidManifest.xml"
_spec_theme = importlib.util.spec_from_file_location("theme_greens", os.path.join(HERE, "patch-theme-greens.py"))
_theme = importlib.util.module_from_spec(_spec_theme)
_spec_theme.loader.exec_module(_theme)
DEX = "classes.dex"
INSERT = os.path.join(HERE, "add-vulkan-shim-prefs.py")
MERGE_DEX = os.path.join(HERE, "merge-shim-dex.sh")
SHIM_UI = os.path.join(HERE, "build-android-shim-ui.sh")
PATCH_ACT = os.path.join(HERE, "patch-manifest-activity.py")
PATCH_PROV = os.path.join(HERE, "patch-manifest-provider.py")


def apk_has_shim_dex(apk_path):
    """True when classes.dex already contains TurnipConfig (r54+ rebuild path)."""
    import subprocess
    bt = os.path.join(
        os.environ.get("ANDROID_HOME", os.path.expanduser("~/Library/Android/sdk")),
        "build-tools", "35.0.0", "dexdump")
    if not os.path.isfile(bt):
        return False
    try:
        out = subprocess.check_output([bt, "-f", apk_path], stderr=subprocess.DEVNULL,
                                      timeout=30).decode("utf-8", "replace")
        return "Lxyz/aethersx2/android/shim/TurnipConfig;" in out
    except (subprocess.CalledProcessError, subprocess.TimeoutExpired, OSError):
        return False

_spec = importlib.util.spec_from_file_location("add_vulkan_shim_prefs", INSERT)
_add_vulkan = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_add_vulkan)


def patch_gfx_xml(raw):
    stripped = _add_vulkan.remove_vulkan_shim_keys(raw)
    src = "/tmp/_vulkan_shim_gfx_in.xml"
    dst = "/tmp/_vulkan_shim_gfx_out.xml"
    open(src, "wb").write(stripped)
    env = os.environ.copy()
    subprocess.check_call([sys.executable, INSERT, src, dst], env=env)
    return open(dst, "rb").read()


def main():
    if len(sys.argv) != 3:
        sys.exit("usage: ensure-vulkan-shim-prefs.py in.apk out.apk")
    src, dst = sys.argv[1:3]

    base_apk = os.environ.get("BASE_APK")
    if base_apk and os.path.isfile(base_apk):
        subprocess.check_call([MERGE_DEX, base_apk,
                               os.path.join(HERE, "merged-classes.dex")], cwd=HERE)
    elif apk_has_shim_dex(src):
        subprocess.check_call([MERGE_DEX, src, os.path.join(HERE, "merged-classes.dex")],
                              cwd=HERE)
        print("merged shim dex from %s (r54+ patch path)" % src)
    else:
        subprocess.check_call([SHIM_UI], cwd=HERE)
    merged_dex = open(os.path.join(HERE, "merged-classes.dex"), "rb").read()

    zin = zipfile.ZipFile(src, "r")
    patched_gfx = {}
    for xml in GFX_XMLS:
        if xml not in zin.namelist():
            sys.exit("error: %s missing from %s" % (xml, src))
        patched_gfx[xml] = patch_gfx_xml(zin.read(xml))
        print("patched %s (%d bytes)" % (xml, len(patched_gfx[xml])))

    open("/tmp/_manifest_in.xml", "wb").write(zin.read(MANIFEST))
    subprocess.check_call([sys.executable, PATCH_ACT,
                           "/tmp/_manifest_in.xml", "/tmp/_manifest_mid.xml"])
    subprocess.check_call([sys.executable, PATCH_PROV,
                           "/tmp/_manifest_mid.xml", "/tmp/_manifest_out.xml"])
    manifest = open("/tmp/_manifest_out.xml", "rb").read()
    version_name = os.environ.get("RELEASE_VERSION_NAME")
    if version_name:
        xhdr, _, pool = find_pool_and_header(manifest)
        found = False
        for off, tag, _, _ in iter_elements(manifest, xhdr, pool):
            if tag != "manifest": continue
            ch = struct.unpack_from("<H", manifest, off + 2)[0]
            start, size, count = struct.unpack_from("<HHH", manifest, off + ch + 8)
            for i in range(count):
                at = off + ch + start + i * size
                name = struct.unpack_from("<i", manifest, at + 4)[0]
                if name >= 0 and pool["strings"][name] == "versionName" and manifest[at + 15] == 3:
                    value = struct.unpack_from("<I", manifest, at + 16)[0]
                    manifest = replace_axml_strings(manifest, {pool["strings"][value]: version_name})
                    found = True
                    break
            break
        if not found: raise ValueError("Cannot update manifest versionName")

    launcher_background = None
    if os.environ.get("NETHER_PINK_LAUNCHER_BRANDING") == "1":
        manifest = patch_application_label(manifest)
        if BACKGROUND not in zin.namelist():
            raise ValueError("Pink adaptive-icon background is missing")
        launcher_background = compile_background()
    with zipfile.ZipFile(dst, "w") as zout:
        for info in zin.infolist():
            if info.filename in patched_gfx:
                data = patched_gfx[info.filename]
            elif info.filename == MANIFEST:
                data = manifest
            elif info.filename == DEX:
                data = merged_dex
            elif info.filename == BACKGROUND and launcher_background is not None:
                data = launcher_background
            elif info.filename == "resources.arsc":
                data = _theme.patch_arsc(zin.read(info.filename))
            else:
                data = zin.read(info.filename)
            zi = zipfile.ZipInfo(info.filename, date_time=info.date_time)
            zi.compress_type = info.compress_type
            zi.external_attr = info.external_attr
            zi.create_system = info.create_system
            if info.compress_type == zipfile.ZIP_STORED:
                align = 4096 if info.filename.endswith(".so") else 4
                start = zout.fp.tell() + 30 + len(info.filename.encode())
                pad = (align - (start % align)) % align
                if pad:
                    zi.extra = b"\0" * pad
            zout.writestr(zi, data, compress_type=info.compress_type)
    zin.close()
    print("patched Graphics prefs + shim UI -> %s" % dst)


if __name__ == "__main__":
    main()
