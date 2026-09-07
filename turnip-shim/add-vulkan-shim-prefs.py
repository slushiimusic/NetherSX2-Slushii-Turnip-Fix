#!/usr/bin/env python3
"""
Insert / refresh VulkanShim prefs on the Graphics screen with readable labels.

Removes any existing VulkanShim/* keys first (fixes duplicate CAS titles from
reusing settings_cas string ids). Title/summary are literal UTF-8 strings in
the local string pool — not CAS / upscale_multiplier resources.

    unzip -p in.apk res/xml/graphics_preferences.xml > gfx.xml
    ./add-vulkan-shim-prefs.py gfx.xml gfx.out.xml
"""
import os
import struct
import sys

CLONE_FROM = "EmuCore/EnableNoInterlacingPatches"
CLONE_FALLBACK = "EmuCore/GS/VsyncEnable"

# FSR Mode: presenter EASU+RCAS via CASMode=2. Does not change Internal Resolution.
SWITCH_PREFS = [
    ("VulkanShim/FsrQuality4x",
     "FSR Mode",
     "FSR1 EASU/RCAS. Internal Resolution stays yours.",
     False),
]

# In-process frame generation. Injected only when NETHER_FRAMEGEN_ROW=1, which
# the cpink02 framegen build sets — orange and the stock pink build must not get
# a switch for a feature their libvulkad.so does not contain.
#
# Note this is a DIFFERENT feature from the FSR-era framegen that
# NETHER_NO_FSR_FRAMEGEN strips, so that variable must not suppress this row.
# The summary says what it actually does; a switch that reads "on" while doing
# nothing has cost this project whole sessions before.
# Default OFF, matching the conf, which now ships lsfg_overlay=off. The row
# and the conf must agree or the switch lies about a running pipeline — and
# the moment the watchdog could actually read the row (it could not until
# PrefCompat stopped using Class.forName) a disagreement would be written
# straight back over the conf.
# Vulkan driver picker. A SwitchPreference, not a list, because the injector
# clones an existing switch out of the binary XML and a ListPreference would
# need its entries as resource arrays in resources.arsc. ON opens the picker,
# OFF drops driver= and returns to the build default -- an honest boolean.
# Export logs + device facts to Downloads. A switch for the same reason the
# driver row is: the injector can only clone a SwitchPreference. Either edge
# writes a report, so it never needs unchecking.
BUGREPORT_PREFS = [
    ("VulkanShim/BugReport",
     "Export Bug Report",
     # Length-capped by the binary XML string pool -- keep under ~120 chars.
     "Saves the shim logs and this device's GPU, driver and display info to Downloads.",
     False),
]

LOWEND_PREFS = [
    ("VulkanShim/LowEndPerf",
     "Low-End Performance",
     # Length-capped by the binary XML string pool -- keep under ~120 chars.
     "Lighter GS for SD865-class handhelds: Basic blending, Partial preload, no aniso, lighter OSD.",
     False),
]

DRIVER_PREFS = [
    ("VulkanShim/Driver",
     "Turnip Driver",
     # Length-capped by the binary XML string pool -- keep under ~120 chars.
     "Swap the Turnip GPU driver. Pick a bundled build or download one. Needs a full app restart.",
     False),
]

FRAMEGEN_PREFS = [
    ("VulkanShim/Lsfg",
     "Frame Generation",
     # Length-capped by the binary XML string pool — keep under ~120 chars.
     "Doubles displayed frame rate via Lossless Scaling. Needs your own "
     "Lossless.dll. Costs GPU and adds latency.",
     False),
]


class InsertTracker:
  def __init__(self):
    self.ins_at = None


def parse_pool(d, off):
    _t, hdr, size = struct.unpack_from("<HHI", d, off)
    cnt, scnt, flags, sstart, systart = struct.unpack_from("<IIIII", d, off + 8)
    utf8 = bool(flags & (1 << 8))
    offs = list(struct.unpack_from("<%dI" % cnt, d, off + hdr)) if cnt else []
    base = off + sstart
    strings = []
    spans = []
    for o in offs:
        p = base + o
        if utf8:
            n = d[p]; p += 1
            if n & 0x80:
                n = ((n & 0x7F) << 8) | d[p]; p += 1
            m = d[p]; p += 1
            if m & 0x80:
                m = ((m & 0x7F) << 8) | d[p]; p += 1
            start = p
            strings.append(d[p:p + m].decode("utf-8", "replace"))
            spans.append((start, p + m))
        else:
            n = struct.unpack_from("<H", d, p)[0]; p += 2
            if n & 0x8000:
                n = ((n & 0x7FFF) << 16) | struct.unpack_from("<H", d, p)[0]; p += 2
            start = p
            strings.append(d[p:p + n * 2].decode("utf-16-le", "replace"))
            spans.append((start, p + n * 2))
    return dict(hdr=hdr, size=size, cnt=cnt, scnt=scnt, flags=flags, utf8=utf8,
                sstart=sstart, systart=systart, offs=offs, strings=strings, spans=spans)


def find_pool_off(d):
    _typ, xhdr, _ = struct.unpack_from("<HHI", d, 0)
    off = xhdr
    while off < len(d) - 8:
        ct, ch, cs = struct.unpack_from("<HHI", d, off)
        if cs == 0:
            break
        if ct == 0x0001:
            return off
        off += cs
    return None


def collect_string_refs(d):
    """String-pool indices referenced by elements/attributes."""
    _typ, xhdr, _ = struct.unpack_from("<HHI", d, 0)
    refs = set()
    off = xhdr
    while off < len(d) - 8:
        ct, ch, cs = struct.unpack_from("<HHI", d, off)
        if cs == 0:
            break
        if ct == 0x0102:
            ni = struct.unpack_from("<i", d, off + ch + 4)[0]
            if ni >= 0:
                refs.add(ni)
            astart, asize, acount = struct.unpack_from("<HHH", d, off + ch + 8)
            ap = off + ch + astart
            for _ in range(acount):
                an = struct.unpack_from("<i", d, ap + 4)[0]
                at = d[ap + 15]
                ad = struct.unpack_from("<I", d, ap + 16)[0]
                if an >= 0:
                    refs.add(an)
                if at == 0x03:
                    refs.add(ad)
                ap += asize
        off += cs
    return refs


def _is_vulkan_shim_pool_string(s):
    return (s.startswith("VulkanShim/")
            or "FsrQuality" in s
            or s in ("FSR Mode",
                     "FSR1 EASU/RCAS. Internal Resolution stays yours.",
                     "Frame Gen",
                     "Interpolate frames (experimental).")
            or "EASU" in s
            or "FSR1" in s
            or s.startswith("AMD FSR")
            or s == "EmuCore/GS/CASMode"
            or s == "EmuCore/GS/CASSharpness"
            or "FidelityFX" in s)


def scrub_orphan_vulkan_shim_strings(data):
    """Zero orphan VulkanShim/FSR strings left in the pool after pref removal."""
    d = bytearray(data)
    pool_off = find_pool_off(d)
    if pool_off is None:
        return bytes(d)
    P = parse_pool(d, pool_off)
    refs = collect_string_refs(d)
    scrubbed = 0
    for i, s in enumerate(P["strings"]):
        if i in refs or not _is_vulkan_shim_pool_string(s):
            continue
        start, end = P["spans"][i]
        d[start:end] = b"\0" * (end - start)
        scrubbed += 1
    if scrubbed:
        print("scrubbed %d orphan VulkanShim/FSR pool strings" % scrubbed)
    return bytes(d)


def encode_str(s, utf8):
    if utf8:
        b = s.encode("utf-8")
        if len(b) >= 128 or len(s) >= 128:
            raise ValueError("string too long for utf8 pool: %s" % s)
        return bytes([len(s), len(b)]) + b + b"\0"
    b = s.encode("utf-16-le")
    if len(s) >= 0x8000:
        raise ValueError("string too long: %s" % s)
    return struct.pack("<H", len(s)) + b + b"\0\0"


def append_one_string(data, pool_off, s):
    d = bytearray(data)
    P = parse_pool(d, pool_off)
    if s in P["strings"]:
        return d, P["strings"].index(s)
    enc = encode_str(s, P["utf8"])
    data_end = pool_off + (P["systart"] if P["scnt"] else P["size"])
    new_off = data_end - (pool_off + P["sstart"])
    pool = bytearray(d[pool_off:pool_off + P["size"]])
    pool[P["hdr"] + 4 * P["cnt"]:P["hdr"] + 4 * P["cnt"]] = struct.pack("<I", new_off)
    ins_point = (data_end - pool_off) + 4
    pool[ins_point:ins_point] = enc
    struct.pack_into("<I", pool, 8, P["cnt"] + 1)
    struct.pack_into("<I", pool, 20, P["sstart"] + 4)
    if P["scnt"]:
        struct.pack_into("<I", pool, 24, P["systart"] + 4 + len(enc))
    while len(pool) % 4:
        pool += b"\0"
    struct.pack_into("<I", pool, 4, len(pool))
    d = d[:pool_off] + pool + d[pool_off + P["size"]:]
    struct.pack_into("<I", d, 4, len(d))
    P = parse_pool(d, pool_off)
    return d, P["strings"].index(s)


def remove_vulkan_shim_keys(data):
    d = bytearray(data)
    _typ, xhdr, xsize = struct.unpack_from("<HHI", d, 0)
    pool_off = None
    off = xhdr
    while off < len(d) - 8:
        ct, ch, cs = struct.unpack_from("<HHI", d, off)
        if cs == 0:
            break
        if ct == 0x0001 and pool_off is None:
            pool_off = off
        off += cs
    P = parse_pool(d, pool_off)
    removed = 0
    out = bytearray()
    out += d[:xhdr]
    off = xhdr
    while off < len(d) - 8:
        ct, ch, cs = struct.unpack_from("<HHI", d, off)
        if cs == 0:
            out += d[off:]
            break
        if ct == 0x0102:
            astart, asize, acount = struct.unpack_from("<HHH", d, off + ch + 8)
            ap = off + ch + astart
            key = None
            for _ in range(acount):
                an = struct.unpack_from("<i", d, ap + 4)[0]
                at = d[ap + 15]
                ad = struct.unpack_from("<I", d, ap + 16)[0]
                nm = P["strings"][an] if an >= 0 else ""
                if nm == "key" and at == 0x03:
                    key = P["strings"][ad]
                ap += asize
            _t2, _h2, end_len = struct.unpack_from("<HHI", d, off + cs)
            if key and key.startswith("VulkanShim/"):
                off += cs + end_len
                removed += 1
                continue
            out += d[off:off + cs + end_len]
            off += cs + end_len
            continue
        out += d[off:off + cs]
        off += cs
    struct.pack_into("<I", out, 4, len(out))
    if removed:
        print("removed %d VulkanShim/* prefs" % removed)
    return scrub_orphan_vulkan_shim_strings(bytes(out))


def remove_named_keys(data, names):
    """Drop preference elements whose android:key is in names."""
    names = set(names)
    d = bytearray(data)
    _typ, xhdr, xsize = struct.unpack_from("<HHI", d, 0)
    pool_off = None
    off = xhdr
    while off < len(d) - 8:
        ct, ch, cs = struct.unpack_from("<HHI", d, off)
        if cs == 0:
            break
        if ct == 0x0001 and pool_off is None:
            pool_off = off
        off += cs
    P = parse_pool(d, pool_off)
    removed = 0
    out = bytearray()
    out += d[:xhdr]
    off = xhdr
    while off < len(d) - 8:
        ct, ch, cs = struct.unpack_from("<HHI", d, off)
        if cs == 0:
            out += d[off:]
            break
        if ct == 0x0102:
            astart, asize, acount = struct.unpack_from("<HHH", d, off + ch + 8)
            ap = off + ch + astart
            key = None
            for _ in range(acount):
                an = struct.unpack_from("<i", d, ap + 4)[0]
                at = d[ap + 15]
                ad = struct.unpack_from("<I", d, ap + 16)[0]
                nm = P["strings"][an] if an >= 0 else ""
                if nm == "key" and at == 0x03:
                    key = P["strings"][ad]
                ap += asize
            _t2, _h2, end_len = struct.unpack_from("<HHI", d, off + cs)
            if key and key in names:
                off += cs + end_len
                removed += 1
                continue
            out += d[off:off + cs + end_len]
            off += cs + end_len
            continue
        out += d[off:off + cs]
        off += cs
    struct.pack_into("<I", out, 4, len(out))
    if removed:
        print("removed %d named prefs %s" % (removed, sorted(names)))
    return bytes(out)


def pref_key_in_tree(d, key):
    _typ, xhdr, _ = struct.unpack_from("<HHI", d, 0)
    pool_off = None
    off = xhdr
    while off < len(d) - 8:
        ct, ch, cs = struct.unpack_from("<HHI", d, off)
        if cs == 0:
            break
        if ct == 0x0001 and pool_off is None:
            pool_off = off
        off += cs
    P = parse_pool(d, pool_off)
    off = xhdr
    while off < len(d) - 8:
        ct, ch, cs = struct.unpack_from("<HHI", d, off)
        if cs == 0:
            break
        if ct == 0x0102:
            astart, asize, acount = struct.unpack_from("<HHH", d, off + ch + 8)
            ap = off + ch + astart
            for _ in range(acount):
                an = struct.unpack_from("<i", d, ap + 4)[0]
                at = d[ap + 15]
                ad = struct.unpack_from("<I", d, ap + 16)[0]
                if an >= 0 and P["strings"][an] == "key" and at == 0x03:
                    if P["strings"][ad] == key:
                        return True
                ap += asize
        off += cs
    return False


def find_clone_site(d):
    _typ, xhdr, xsize = struct.unpack_from("<HHI", d, 0)
    pool_off = None
    off = xhdr
    while off < len(d) - 8:
        ct, ch, cs = struct.unpack_from("<HHI", d, off)
        if cs == 0:
            break
        if ct == 0x0001 and pool_off is None:
            pool_off = off
        off += cs
    P = parse_pool(d, pool_off)

    def elements():
        o = xhdr
        while o < len(d) - 8:
            ct, ch, cs = struct.unpack_from("<HHI", d, o)
            if cs == 0:
                break
            yield o, ct, ch, cs
            o += cs

    for o, ct, ch, cs in elements():
        if ct != 0x0102:
            continue
        astart, asize, acount = struct.unpack_from("<HHH", d, o + ch + 8)
        ap = o + ch + astart
        k = None
        for _ in range(acount):
            an = struct.unpack_from("<i", d, ap + 4)[0]
            at = d[ap + 15]
            ad = struct.unpack_from("<I", d, ap + 16)[0]
            if an >= 0 and P["strings"][an] == "key" and at == 0x03:
                k = P["strings"][ad]
            ap += asize
        if k == CLONE_FROM or k == CLONE_FALLBACK:
            clone_start, clone_len = o, cs
            _t2, _h2, end_len = struct.unpack_from("<HHI", d, o + cs)
            return pool_off, P, clone_start, clone_len, end_len
    raise RuntimeError("clone template %s / %s not found" % (CLONE_FROM, CLONE_FALLBACK))


def insert_switch(d, key, title, summary, default_on, tracker):
    if pref_key_in_tree(d, key):
        print("skip (already present):", key)
        return d

    pool_off, P, clone_start, clone_len, end_len = find_clone_site(d)
    if tracker.ins_at is None:
        tracker.ins_at = clone_start + clone_len + end_len

    need = [key, title, summary]
    old_pool_size = P["size"]
    for s in need:
        d, _ = append_one_string(d, pool_off, s)
    P = parse_pool(d, pool_off)
    pool_delta = P["size"] - old_pool_size
    clone_start += pool_delta
    tracker.ins_at += pool_delta
    ins_at = tracker.ins_at

    key_idx = P["strings"].index(key)
    title_idx = P["strings"].index(title)
    summary_idx = P["strings"].index(summary)

    elem = bytearray(d[clone_start:clone_start + clone_len])
    endel = bytearray(d[clone_start + clone_len:clone_start + clone_len + end_len])
    ch = struct.unpack_from("<H", elem, 2)[0]
    astart, asize, acount = struct.unpack_from("<HHH", elem, ch + 8)
    ap = ch + astart
    for _ in range(acount):
        an = struct.unpack_from("<i", elem, ap + 4)[0]
        nm = P["strings"][an] if an >= 0 else ""
        if nm == "key":
            struct.pack_into("<I", elem, ap + 16, key_idx)
            struct.pack_into("<i", elem, ap + 8, key_idx)
            elem[ap + 15] = 0x03
        elif nm == "title":
            struct.pack_into("<I", elem, ap + 16, title_idx)
            struct.pack_into("<i", elem, ap + 8, title_idx)
            elem[ap + 15] = 0x03
        elif nm == "summary":
            struct.pack_into("<I", elem, ap + 16, summary_idx)
            struct.pack_into("<i", elem, ap + 8, summary_idx)
            elem[ap + 15] = 0x03
        elif nm == "defaultValue":
            elem[ap + 15] = 0x12
            struct.pack_into("<I", elem, ap + 16, 0xFFFFFFFF if default_on else 0)
        elif nm == "useSimpleSummaryProvider":
            # Literal title/summary strings + SimpleSummaryProvider can hide rows on some builds.
            elem[ap + 15] = 0x12
            struct.pack_into("<I", elem, ap + 16, 0)
        ap += asize

    chunk = elem + endel
    out = bytearray()
    out += d[:ins_at]
    out += chunk
    out += d[ins_at:]
    tracker.ins_at = ins_at + len(chunk)
    struct.pack_into("<I", out, 4, len(out))
    print("added switch", key)
    return bytes(out)


def validate_tree(d):
    _typ, xhdr, _ = struct.unpack_from("<HHI", d, 0)
    pool_off = None
    off = xhdr
    while off < len(d) - 8:
        ct, ch, cs = struct.unpack_from("<HHI", d, off)
        if cs == 0:
            break
        if ct == 0x0001 and pool_off is None:
            pool_off = off
        off += cs
    P = parse_pool(d, pool_off)["strings"]
    off = xhdr
    while off < len(d) - 8:
        ct, ch, cs = struct.unpack_from("<HHI", d, off)
        if cs == 0:
            break
        if ct == 0x0102:
            tag = P[struct.unpack_from("<i", d, off + ch + 4)[0]]
            if "/" in tag and not tag.startswith("xyz."):
                raise RuntimeError("corrupt element tag: %s at offset %d" % (tag, off))
        off += cs


def main():
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    src, dst = sys.argv[1], sys.argv[2]
    data = open(src, "rb").read()
    data = remove_vulkan_shim_keys(data)
    if os.environ.get("NETHER_NO_FSR_SWITCH") == "1":
        data = scrub_orphan_vulkan_shim_strings(data)
    tracker = InsertTracker()
    # With native FSR/framegen compiled out, or when the caller explicitly hides the
    # row, do not re-inject VulkanShim/FsrQuality4x — remove_vulkan_shim_keys()
    # already stripped stale rows; skipping re-add avoids a dead switch.
    no_switch = os.environ.get("NETHER_NO_FSR_SWITCH") == "1"
    no_fg = os.environ.get("NETHER_NO_FSR_FRAMEGEN") == "1"
    if no_switch or no_fg:
        prefs = []
        if no_switch:
            print("NETHER_NO_FSR_SWITCH=1: no VulkanShim FSR row injected")
        if no_fg:
            print("NETHER_NO_FSR_FRAMEGEN=1: no VulkanShim FSR row injected")
    else:
        prefs = SWITCH_PREFS
    # Low-end perf first in Graphics so it is visible without scrolling past FG.
    prefs = LOWEND_PREFS + prefs
    print("injecting VulkanShim/LowEndPerf switch")
    # Independent of the FSR rows above — see FRAMEGEN_PREFS.
    if os.environ.get("NETHER_FRAMEGEN_ROW") == "1":
        prefs = prefs + FRAMEGEN_PREFS
        print("NETHER_FRAMEGEN_ROW=1: injecting VulkanShim/Lsfg switch")
    if os.environ.get("NETHER_NO_DRIVER_ROW") != "1":
        prefs = prefs + DRIVER_PREFS
        print("injecting VulkanShim/Driver picker row")
        prefs = prefs + BUGREPORT_PREFS
        print("injecting VulkanShim/BugReport row")
    for key, title, summary, default in prefs:
        data = insert_switch(data, key, title, summary, default, tracker)
    validate_tree(data)
    open(dst, "wb").write(data)
    print(dst, len(data), "bytes (was %d)" % len(open(src, "rb").read()))


if __name__ == "__main__":
    main()
