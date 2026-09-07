#!/usr/bin/env python3
"""Rewrite the stock theme's GREEN accents in resources.arsc to the Slushii palette.

The shim already repaints these at runtime, but it can only do that AFTER the
view tree exists -- which is why the 60 FPS restart dialog, App Settings and
Controller Settings show green text for a second or two before snapping to the
theme. Fixing the VALUES means there is never a green frame to catch.

Only TYPE_INT_COLOR_ARGB8 typed values (dataType 0x1C) are touched, so nothing
that merely happens to contain the same four bytes is affected.

  patch-theme-greens.py <in.apk> <out.apk>
"""
import importlib.util, os, struct, subprocess, sys, zipfile

HERE = os.path.dirname(os.path.abspath(__file__))


def _load(name, fn):
    spec = importlib.util.spec_from_file_location(name, os.path.join(HERE, fn))
    m = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(m)
    return m


_repack = _load("repack_apk", "repack-apk.py")

# stock green -> Slushii palette (ShimAccent.java constants)
NEON_CYAN = 0xFF00E8FF   # ACCENT_AQUA, exactly what the runtime sweep paints
TEAL_DARK = 0xFF006666
TEAL_MUTED = 0xFF7FA8A8

REMAP = {
    0xFF7AE02E: NEON_CYAN,   # lime accent: dialog buttons, preference titles
    0xFF344529: NEON_CYAN,   # dark green: tab indicator, dialog CANCEL
    0xFF7FA87F: TEAL_MUTED,  # muted green
}


def patch_arsc(d):
    d = bytearray(d)
    n = 0
    i = 0
    while True:
        i = d.find(b"\x08\x00\x00\x1c", i)
        if i < 0:
            break
        c = struct.unpack_from("<I", d, i + 4)[0]
        if c in REMAP:
            struct.pack_into("<I", d, i + 4, REMAP[c])
            print("    #%08X -> #%08X" % (c, REMAP[c]))
            n += 1
        i += 1
    print("    %d green colour value(s) rewritten" % n)
    return bytes(d)


def main():
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    src, dst = sys.argv[1:3]
    zin = zipfile.ZipFile(src, "r")
    with zipfile.ZipFile(dst + ".tmp", "w") as zout:
        for info in zin.infolist():
            data = zin.read(info.filename)
            if info.filename == "resources.arsc":
                data = patch_arsc(data)
            zi = zipfile.ZipInfo(info.filename, date_time=info.date_time)
            zi.compress_type = info.compress_type
            zi.external_attr = info.external_attr
            zout.writestr(zi, data)
    zin.close()
    signer = os.path.join(HERE, "apksigner.jar")
    keystore = os.environ.get("TURNIP_KEYSTORE") or _repack.DEFAULT_KEYSTORE
    subprocess.check_call([
        "java", "-jar", signer, "sign",
        "--ks", keystore, "--ks-pass", "pass:" + _repack.KS_PASS,
        "--out", dst, dst + ".tmp",
    ])
    os.remove(dst + ".tmp")
    print("  wrote %s" % dst)


if __name__ == "__main__":
    main()
