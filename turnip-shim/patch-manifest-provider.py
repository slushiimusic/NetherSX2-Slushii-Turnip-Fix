#!/usr/bin/env python3
"""Add ShimInitProvider to binary AndroidManifest.xml."""
import os
import struct
import sys

from axml_manifest import (
    RES_XML_END_ELEMENT_TYPE,
    find_pool_and_header,
    iter_elements,
    manifest_has_component,
    parse_pool,
    skip_provider_child_meta,
)

PROVIDER_CLASS = os.environ.get(
    "PROVIDER_CLASS", "xyz.aethersx2.android.shim.ShimInitProvider")
AUTHORITY = os.environ.get("PROVIDER_AUTHORITY", "xyz.aethersx2.cturnip.shiminit")
CLONE_FROM = os.environ.get("PROVIDER_CLONE_FROM",
                            "androidx.startup.InitializationProvider")


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


def set_provider_exported(d, provider_class, exported=True):
    """Force android:exported on an existing provider element."""
    xhdr, pool_off, P = find_pool_and_header(d)
    for off, tag, name, cs in iter_elements(d, xhdr, P):
        if tag != "provider" or name != provider_class:
            continue
        ch = struct.unpack_from("<H", d, off + 2)[0]
        astart, asize, acount = struct.unpack_from("<HHH", d, off + ch + 8)
        ap = off + ch + astart
        for _ in range(acount):
            an = struct.unpack_from("<i", d, ap + 4)[0]
            nm = P["strings"][an] if an >= 0 else ""
            if nm == "exported":
                d[ap + 15] = 0x12
                struct.pack_into("<I", d, ap + 16, 0xFFFFFFFF if exported else 0)
                print("set exported=%s on %s" % (exported, provider_class))
                return d
        break
    print("warning: could not set exported on", provider_class)
    return d


def main():
    src, dst = sys.argv[1], sys.argv[2]
    d = bytearray(open(src, "rb").read())
    if manifest_has_component(d, "provider", PROVIDER_CLASS):
        d = set_provider_exported(d, PROVIDER_CLASS, True)
        open(dst, "wb").write(d)
        print("ShimInitProvider already present")
        return

    xhdr, pool_off, P = find_pool_and_header(d)

    clone_start = clone_len = None
    for off, tag, name, cs in iter_elements(d, xhdr, P):
        if tag == "provider" and name == CLONE_FROM:
            clone_start, clone_len = off, cs
            break
    if clone_start is None:
        sys.exit("provider clone template not found")

    meta_end = skip_provider_child_meta(d, clone_start, clone_len, xhdr, P)
    ct_end, _, end_cs = struct.unpack_from("<HHI", d, meta_end)
    if ct_end != RES_XML_END_ELEMENT_TYPE or not end_cs:
        sys.exit("InitializationProvider closing tag not found")

    old_pool_size = P["size"]
    for s in (PROVIDER_CLASS, AUTHORITY):
        d, _ = append_one_string(d, pool_off, s)
    P = parse_pool(d, pool_off)
    pool_delta = P["size"] - old_pool_size
    clone_start += pool_delta
    meta_end += pool_delta

    ins_at = meta_end + end_cs

    name_idx = P["strings"].index(PROVIDER_CLASS)
    auth_idx = P["strings"].index(AUTHORITY)

    elem = bytearray(d[clone_start : clone_start + clone_len])
    ch = struct.unpack_from("<H", elem, 2)[0]
    astart, asize, acount = struct.unpack_from("<HHH", elem, ch + 8)
    ap = ch + astart
    for _ in range(acount):
        an = struct.unpack_from("<i", elem, ap + 4)[0]
        nm = P["strings"][an] if an >= 0 else ""
        if nm == "name":
            struct.pack_into("<I", elem, ap + 16, name_idx)
            struct.pack_into("<i", elem, ap + 8, name_idx)
        elif nm == "authorities":
            struct.pack_into("<I", elem, ap + 16, auth_idx)
            struct.pack_into("<i", elem, ap + 8, auth_idx)
        elif nm == "exported":
            elem[ap + 15] = 0x12
            # Exported so adb/content call can drive toggles for debug + rescue.
            struct.pack_into("<I", elem, ap + 16, 0xFFFFFFFF)
        ap += asize

    # Line number for the new provider element (AndroidManifest.xml:line).
    if ins_at + 8 <= len(d):
        shim_line = struct.unpack_from("<I", d, ins_at + 8)[0]
    else:
        shim_line = 1
    struct.pack_into("<I", elem, 8, shim_line)

    endel = bytearray(d[meta_end : meta_end + end_cs])
    struct.pack_into("<I", endel, 8, shim_line)

    out = bytearray()
    out += d[:ins_at]
    out += elem + endel
    out += d[ins_at:]
    struct.pack_into("<I", out, 4, len(out))
    open(dst, "wb").write(out)
    print("added provider %s" % PROVIDER_CLASS)


if __name__ == "__main__":
    main()
