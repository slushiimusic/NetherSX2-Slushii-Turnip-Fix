#!/usr/bin/env python3
"""
Swap a rebuilt libvulkad.so into a NetherSX2-Turnip APK, re-align, re-sign.

    ./repack-apk.py NetherSX2-Turnip-v0.7.apk libvulkad.so

Writes <input>-patched-signed.apk next to the input.

Alignment matters: native libraries are stored uncompressed and 4096-byte
aligned so Android can mmap them straight out of the APK. Rebuilding the zip
naively breaks that and the library silently gets extracted instead (or fails
to load). This reproduces what `zipalign -p 4` does, in pure Python.
"""
import glob
import os
import shutil
import struct
import subprocess
import sys
import zipfile

ALIGN_DEFAULT = 4
ALIGN_SO = 4096


def find_java() -> str:
    """Resolve a working java binary (macOS /usr/bin/java is often a stub)."""
    if os.environ.get("JAVA_HOME"):
        cand = os.path.join(os.environ["JAVA_HOME"], "bin", "java")
        if os.path.isfile(cand):
            return cand
    for cand in (
        "/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/java",
        "/opt/homebrew/opt/openjdk/bin/java",
        "/opt/homebrew/opt/openjdk@17/bin/java",
        "/opt/homebrew/opt/openjdk@21/bin/java",
    ):
        if os.path.isfile(cand):
            return cand
    for path in sorted(glob.glob("/Library/Java/JavaVirtualMachines/*/Contents/Home/bin/java")):
        if os.path.isfile(path):
            return path
    return shutil.which("java") or "java"


def _align_and_write(zout, filename, data, compress, date_time, external_attr,
                     create_system):
    zi = zipfile.ZipInfo(filename, date_time=date_time)
    zi.compress_type = compress
    zi.external_attr = external_attr
    zi.create_system = create_system

    # Only stored entries need aligning; .so goes to a page boundary so Android
    # can mmap it in place. zipfile writes zi.extra into the local header, so
    # padding there shifts the data start.
    if compress == zipfile.ZIP_STORED:
        align = ALIGN_SO if filename.endswith(".so") else ALIGN_DEFAULT
        data_start = zout.fp.tell() + 30 + len(filename.encode("utf-8"))
        pad = (align - (data_start % align)) % align
        if pad:
            zi.extra = b"\0" * pad

    zout.writestr(zi, data, compress_type=compress)


def repack(apk_in, new_so, apk_out, so_name="lib/arm64-v8a/libvulkad.so",
           add_files=None, bump_version=1):
    """Replace `so_name` with `new_so`; optionally add extra entries.

    add_files maps archive path -> local file path. Added entries are stored
    uncompressed and page-aligned, same as the libraries already in the APK.
    """
    zin = zipfile.ZipFile(apk_in, "r")
    names = zin.namelist()
    if so_name not in names:
        cands = [n for n in names if n.endswith("libvulkad.so")]
        if not cands:
            sys.exit("error: %s has no libvulkad.so — is this a Turnip build?" % apk_in)
        so_name = cands[0]
        print("note: using %s" % so_name)

    replacement = open(new_so, "rb").read()
    add_files = dict(add_files or {})
    drop = set()
    for tok in os.environ.get("DROP_LIBS", "").split(","):
        tok = tok.strip()
        if not tok:
            continue
        drop.add(tok)
        if "/" not in tok:
            drop.add("lib/arm64-v8a/" + tok)
    count = 0

    with zipfile.ZipFile(apk_out, "w") as zout:
        for info in zin.infolist():
            leaf = info.filename.split("/")[-1]
            if info.filename.startswith("META-INF/") and leaf.endswith(
                (".SF", ".RSA", ".DSA", ".EC")
            ):
                continue  # old signature; apksigner writes a fresh one
            if info.filename in drop or leaf in drop:
                print("  dropping %s" % info.filename)
                continue

            if info.filename == so_name:
                data, compress = replacement, zipfile.ZIP_STORED
                print("  replacing %s  %d -> %d bytes"
                      % (so_name, info.file_size, len(data)))
            elif info.filename == "AndroidManifest.xml" and bump_version:
                raw = zin.read(info.filename)
                res = bump_version_code(raw, bump_version)
                if res:
                    data, old, new = res
                    print("  versionCode %d -> %d" % (old, new))
                else:
                    data = raw
                    print("  warning: could not locate versionCode; left unchanged")
                compress = info.compress_type
            elif info.filename in add_files:
                local = add_files[info.filename]
                data, compress = open(local, "rb").read(), zipfile.ZIP_STORED
                print("  replacing %s  %d -> %d bytes"
                      % (info.filename, info.file_size, len(data)))
                del add_files[info.filename]
            else:
                data = zin.read(info.filename)
                compress = info.compress_type

            _align_and_write(zout, info.filename, data, compress,
                             info.date_time, info.external_attr,
                             info.create_system)
            count += 1

        # New entries go last, matching how the existing libs are stored.
        template = zin.infolist()[0]
        for arc, local in sorted(add_files.items()):
            blob = open(local, "rb").read()
            print("  adding    %s  %d bytes" % (arc, len(blob)))
            _align_and_write(zout, arc, blob, zipfile.ZIP_STORED,
                             template.date_time, template.external_attr,
                             template.create_system)
            count += 1

    zin.close()
    print("  wrote %s (%d entries)" % (apk_out, count))


def bump_version_code(axml, delta=1):
    """Raise android:versionCode in a binary AndroidManifest.xml, in place.

    versionCode is a typed uint32 stored directly in the attribute record, so
    it can be overwritten without touching the string pool or any offset --
    the file size never changes. Android refuses to install a build whose
    versionCode is lower than the installed one, so bumping it keeps repeated
    rebuilds installable as ordinary updates.

    Returns (old, new), or None if the attribute could not be located.
    """
    data = bytearray(axml)
    if struct.unpack_from("<H", data, 0)[0] != 0x0003:   # RES_XML_TYPE
        return None
    hdr = struct.unpack_from("<H", data, 2)[0]

    # string pool -> index of "versionCode"
    strings = []
    off = hdr
    name_idx = -1
    while off < len(data) - 8:
        ctyp, chdr, csz = struct.unpack_from("<HHI", data, off)
        if csz == 0:
            break
        if ctyp == 0x0001:                                # STRING_POOL
            cnt, _sc, flags, sstart, _st = struct.unpack_from("<IIIII", data, off + 8)
            utf8 = bool(flags & (1 << 8))
            offs = struct.unpack_from("<%dI" % cnt, data, off + chdr) if cnt else ()
            base = off + sstart
            for o in offs:
                p = base + o
                if utf8:
                    n = data[p]; p += 1
                    if n & 0x80:
                        n = ((n & 0x7f) << 8) | data[p]; p += 1
                    m = data[p]; p += 1
                    if m & 0x80:
                        m = ((m & 0x7f) << 8) | data[p]; p += 1
                    strings.append(bytes(data[p:p + m]).decode("utf-8", "replace"))
                else:
                    n = struct.unpack_from("<H", data, p)[0]; p += 2
                    if n & 0x8000:
                        n = ((n & 0x7fff) << 16) | struct.unpack_from("<H", data, p)[0]; p += 2
                    strings.append(bytes(data[p:p + n * 2]).decode("utf-16-le", "replace"))
            for i, s in enumerate(strings):
                if s == "versionCode":
                    name_idx = i
            break
        off += csz
    if name_idx < 0:
        return None

    # walk elements for that attribute
    off = hdr
    while off < len(data) - 8:
        ctyp, chdr, csz = struct.unpack_from("<HHI", data, off)
        if csz == 0:
            break
        if ctyp == 0x0102:                                # START_ELEMENT
            astart, asize, acount = struct.unpack_from("<HHH", data, off + chdr + 8)
            ap = off + chdr + astart
            for _ in range(acount):
                a_name = struct.unpack_from("<i", data, ap + 4)[0]
                a_type = data[ap + 15]
                if a_name == name_idx and a_type == 0x10:  # TYPE_INT_DEC
                    old = struct.unpack_from("<I", data, ap + 16)[0]
                    new = old + delta
                    struct.pack_into("<I", data, ap + 16, new)
                    return bytes(data), old, new
                ap += asize
        off += csz
    return None


KS_PASS = "nethersx2"

# Android will only install a new build *over* an existing one when the package
# name matches, the versionCode does not go backwards, AND the signing
# certificate is identical. So the key must outlive any one build directory --
# keep it in the user's home, not next to the script, or every rebuild produces
# a differently-signed apk and the installer demands an uninstall first
# (which takes your saves and memory cards with it).
#
# Override with TURNIP_KEYSTORE if you want it somewhere else. Back this file
# up: lose it and you can never update an installed build again.
DEFAULT_KEYSTORE = os.path.expanduser("~/.config/nethersx2-turnip/signing.jks")


def ensure_keystore(path):
    """Generate our own signing key rather than reusing one from a repo.

    The upstream repos ship a private key whose documented passwords are
    swapped relative to the actual files, and signing your own build with
    somebody else's key is a bad habit regardless. Any self-signed key
    works here — Android only cares that it stays consistent across
    updates of your own build.
    """
    if os.path.exists(path):
        print("  signing key: %s (existing — updates will install in place)" % path)
        return
    os.makedirs(os.path.dirname(path), exist_ok=True)
    print("generating a signing key at %s" % path)
    subprocess.check_call([
        "keytool", "-genkeypair", "-v",
        "-keystore", path,
        "-alias", "nethersx2",
        "-keyalg", "RSA", "-keysize", "2048",
        "-validity", "10950",
        "-storepass", KS_PASS, "-keypass", KS_PASS,
        "-dname", "CN=NetherSX2 Patch, OU=local build, O=local, C=US",
    ], stdout=subprocess.DEVNULL)


def main():
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    apk_in, new_so = sys.argv[1], sys.argv[2]
    # optional 3rd arg: which entry to replace (default lib/arm64-v8a/libvulkad.so)
    entry = sys.argv[3] if len(sys.argv) > 3 else "lib/arm64-v8a/libvulkad.so"
    base = os.path.splitext(apk_in)[0]
    unsigned = base + "-patched.apk"
    signed = base + "-patched-signed.apk"

    here = os.path.dirname(os.path.abspath(__file__))
    signer = os.path.join(here, "apksigner.jar")
    keystore = os.environ.get("TURNIP_KEYSTORE") or DEFAULT_KEYSTORE
    # Migrate a key from the old next-to-the-script location so builds made
    # before this change stay updatable rather than needing one last uninstall.
    legacy = os.path.join(here, "signing.jks")
    if os.path.exists(legacy) and not os.path.exists(keystore):
        os.makedirs(os.path.dirname(keystore), exist_ok=True)
        shutil.copy2(legacy, keystore)
        print("  migrated existing signing key to %s" % keystore)

    print("repacking...")
    add = {}
    extra_dir = os.environ.get("ADD_LIBS")
    if extra_dir:
        for fn in sorted(os.listdir(extra_dir)):
            if fn.endswith(".so"):
                add["lib/arm64-v8a/" + fn] = os.path.join(extra_dir, fn)
    extra_assets = os.environ.get("ADD_ASSETS")
    if extra_assets:
        for root, _dirs, files in os.walk(extra_assets):
            for fn in files:
                full = os.path.join(root, fn)
                rel = os.path.relpath(full, extra_assets).replace("\\", "/")
                add["assets/" + rel] = full
    repack(apk_in, new_so, unsigned, so_name=entry, add_files=add,
           bump_version=int(os.environ.get("BUMP_VERSION", "1")))

    if not os.path.exists(signer):
        sys.exit("error: apksigner.jar not found next to this script "
                 "(copy it from NetherSX2-classic/decomp/lib/)")

    ensure_keystore(keystore)

    print("signing...")
    java = find_java()
    shutil.copy(unsigned, signed)
    subprocess.check_call([
        java, "-jar", signer, "sign", "--min-sdk-version", "26",
        "--ks", keystore,
        "--ks-pass", "pass:" + KS_PASS,
        "--key-pass", "pass:" + KS_PASS,
        signed,
    ])
    subprocess.check_call([java, "-jar", signer, "verify", "--verbose", signed])
    os.remove(unsigned)
    print("\ndone -> %s" % signed)
    print("Install updates in place with the matching signing key. "
          "Back up saves first; never uninstall or clear app data to bypass a signing mismatch.")


if __name__ == "__main__":
    main()
