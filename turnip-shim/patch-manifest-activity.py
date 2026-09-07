#!/usr/bin/env python3
"""Clone FileEditorActivity in binary AndroidManifest.xml -> the shim's own activities.

NEW_ACTIVITIES is currently empty, so this is a pass-through copy: the shim has no
activity of its own to register. The cloning machinery below is kept because it is
the only working way to add one to an already-built binary manifest.
"""
import os
import struct
import sys

from axml_manifest import (
    RES_XML_END_ELEMENT_TYPE,
    RES_XML_START_ELEMENT_TYPE,
    find_pool_and_header,
    iter_elements,
    manifest_has_component,
    parse_pool,
)

# ShimTogglesActivity (a second, standalone "NetherSX2 Toggles" screen) is
# deliberately absent: the FSR x4 / Enable 60 FPS switches live in Graphics and
# nowhere else. It was only ever a fallback for when the Graphics switches were
# swallowed, and it was reachable via `am start`, so it could surface as a
# stray window.
NEW_ACTIVITIES = []
CLONE_FROM = "xyz.aethersx2.android.FileEditorActivity"


def encode_str(s, utf8):
    if utf8:
        b = s.encode("utf-8")
        assert len(b) < 128 and len(s) < 128
        return bytes([len(s), len(b)]) + b + b"\0"
    b = s.encode("utf-16-le")
    assert len(s) < 0x8000
    return struct.pack("<H", len(s)) + b + b"\0\0"


def append_one_string(data, pool_off, s):
    d = bytearray(data)
    P = parse_pool(d, pool_off)
    if s in P["strings"]:
        return d, P["strings"].index(s)
    enc = encode_str(s, P["utf8"])
    data_end = pool_off + (P["systart"] if P["scnt"] else P["size"])
    new_off = data_end - (pool_off + P["sstart"])
    pool = bytearray(d[pool_off : pool_off + P["size"]])
    pool[P["hdr"] + 4 * P["cnt"] : P["hdr"] + 4 * P["cnt"]] = struct.pack("<I", new_off)
    ins_point = (data_end - pool_off) + 4
    pool[ins_point:ins_point] = enc
    struct.pack_into("<I", pool, 8, P["cnt"] + 1)
    struct.pack_into("<I", pool, 20, P["sstart"] + 4)
    if P["scnt"]:
        struct.pack_into("<I", pool, 24, P["sstart"] + 4 + len(enc))
    while len(pool) % 4:
        pool += b"\0"
    struct.pack_into("<I", pool, 4, len(pool))
    d = d[:pool_off] + pool + d[pool_off + P["size"] :]
    struct.pack_into("<I", d, 4, len(d))
    P = parse_pool(d, pool_off)
    return d, P["strings"].index(s)


def add_activity(d, new_activity):
    if manifest_has_component(d, "activity", new_activity):
        print("activity already present:", new_activity)
        return d

    xhdr, pool_off, P = find_pool_and_header(d)

    clone_start = clone_len = None
    for off, tag, name, cs in iter_elements(d, xhdr, P):
        if tag == "activity" and name == CLONE_FROM:
            clone_start, clone_len = off, cs
            break
    if clone_start is None:
        sys.exit("clone template activity not found")

    tail = clone_start + clone_len
    end_len = 0
    ct, ch, cs = struct.unpack_from("<HHI", d, tail)
    if ct == RES_XML_END_ELEMENT_TYPE:
        end_len = cs

    old_pool_size = P["size"]
    d, new_idx = append_one_string(d, pool_off, new_activity)
    P = parse_pool(d, pool_off)
    clone_start += P["size"] - old_pool_size

    elem = bytearray(d[clone_start : clone_start + clone_len])
    ch = struct.unpack_from("<H", elem, 2)[0]
    astart, asize, acount = struct.unpack_from("<HHH", elem, ch + 8)
    ap = ch + astart
    for _ in range(acount):
        an = struct.unpack_from("<i", elem, ap + 4)[0]
        nm = P["strings"][an] if an >= 0 else ""
        if nm == "name":
            struct.pack_into("<I", elem, ap + 16, new_idx)
            struct.pack_into("<i", elem, ap + 8, new_idx)
        ap += asize

    endel = bytearray()
    if end_len:
        endel = bytearray(d[clone_start + clone_len : clone_start + clone_len + end_len])

    ins_at = clone_start + clone_len + end_len
    out = bytearray()
    out += d[:ins_at]
    out += elem + endel
    out += d[ins_at:]
    struct.pack_into("<I", out, 4, len(out))
    print("added activity %s" % new_activity)
    return out


def set_activity_exported(d, activity_class, exported=True):
    xhdr, pool_off, P = find_pool_and_header(d)
    for off, tag, name, cs in iter_elements(d, xhdr, P):
        if tag != "activity" or name != activity_class:
            continue
        ch = struct.unpack_from("<H", d, off + 2)[0]
        astart, asize, acount = struct.unpack_from("<HHH", d, off + ch + 8)
        ap = off + ch + astart
        for _ in range(acount):
            an = struct.unpack_from("<i", d, ap + 4)[0]
            at = d[ap + 15]
            nm = P["strings"][an] if an >= 0 else ""
            if nm == "exported" or (at == 0x12 and an >= 0 and "export" in P["strings"][an]):
                d[ap + 15] = 0x12
                struct.pack_into("<I", d, ap + 16, 0xFFFFFFFF if exported else 0)
                print("set exported=%s on %s" % (exported, activity_class))
                return d
            ap += asize
        print("warning: no exported attr on", activity_class)
        return d
    print("warning: activity not found for export:", activity_class)
    return d


def skip_element_tree(d, start_off):
    ct, ch, cs = struct.unpack_from("<HHI", d, start_off)
    off = start_off + cs
    depth = 1
    while off < len(d) - 8 and depth:
        ct, ch, cs = struct.unpack_from("<HHI", d, off)
        if cs == 0:
            break
        if ct == RES_XML_START_ELEMENT_TYPE:
            depth += 1
        elif ct == RES_XML_END_ELEMENT_TYPE:
            depth -= 1
        off += cs
    return off


def remove_activity(d, class_name):
    xhdr, pool_off, P = find_pool_and_header(d)
    for off, tag, name, cs in iter_elements(d, xhdr, P):
        if tag == "activity" and name == class_name:
            end = skip_element_tree(d, off)
            out = bytearray(d[:off] + d[end:])
            struct.pack_into("<I", out, 4, len(out))
            print("removed activity", class_name)
            return bytes(out)
    return d


def main():
    src, dst = sys.argv[1], sys.argv[2]
    d = bytearray(open(src, "rb").read())
    if os.environ.get("NETHER_NO_FSR_FRAMEGEN") == "1":
        d = bytearray(remove_activity(d, "xyz.aethersx2.android.shim.LsfgDllImportActivity"))
    if not NEW_ACTIVITIES:
        print("no shim activities to add")
    for act in NEW_ACTIVITIES:
        d = add_activity(d, act)
    open(dst, "wb").write(d)


if __name__ == "__main__":
    main()
