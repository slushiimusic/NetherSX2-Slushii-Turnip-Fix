"""Rebuild Android binary XML (AXML) string pools for variable-length replacements."""
import struct

RES_XML_TYPE = 0x0003
RES_STRING_POOL_TYPE = 0x0001


def _encode_utf8(s: str) -> bytes:
    raw = s.encode("utf-8")
    out = bytearray()
    n = len(s)
    if n > 0x7F:
        out.append(((n >> 8) & 0x7F) | 0x80)
        out.append(n & 0xFF)
    else:
        out.append(n)
    m = len(raw)
    if m > 0x7F:
        out.append(((m >> 8) & 0x7F) | 0x80)
        out.append(m & 0xFF)
    else:
        out.append(m)
    out.extend(raw)
    out.append(0)
    return bytes(out)


def _encode_utf16(s: str) -> bytes:
    out = bytearray()
    n = len(s)
    if n > 0x7FFF:
        out.extend(struct.pack("<H", ((n >> 16) & 0x7FFF) | 0x8000))
        out.extend(struct.pack("<H", n & 0xFFFF))
    else:
        out.extend(struct.pack("<H", n))
    out.extend(s.encode("utf-16-le"))
    out.append(0)
    out.append(0)
    return bytes(out)


def _parse_pool(d, off):
    _t, hdr, size = struct.unpack_from("<HHI", d, off)
    cnt, scnt, flags, sstart, systart = struct.unpack_from("<IIIII", d, off + 8)
    utf8 = bool(flags & (1 << 8))
    offs = list(struct.unpack_from("<%dI" % cnt, d, off + hdr)) if cnt else []
    base = off + sstart
    strings = []
    for o in offs:
        p = base + o
        if utf8:
            n = d[p]
            p += 1
            if n & 0x80:
                n = ((n & 0x7F) << 8) | d[p]
                p += 1
            m = d[p]
            p += 1
            if m & 0x80:
                m = ((m & 0x7F) << 8) | d[p]
                p += 1
            strings.append(d[p : p + m].decode("utf-8", "replace"))
        else:
            n = struct.unpack_from("<H", d, p)[0]
            p += 2
            if n & 0x8000:
                n = ((n & 0x7FFF) << 16) | struct.unpack_from("<H", d, p)[0]
                p += 2
            strings.append(d[p : p + n * 2].decode("utf-16-le", "replace"))
    return dict(
        hdr=hdr,
        size=size,
        cnt=cnt,
        scnt=scnt,
        flags=flags,
        utf8=utf8,
        sstart=sstart,
        systart=systart,
        strings=strings,
        off=off,
    )


def _build_pool(pool, strings):
    utf8 = pool["utf8"]
    encoded = [
        _encode_utf8(s) if utf8 else _encode_utf16(s) for s in strings
    ]
    hdr = pool["hdr"]
    scnt = pool["scnt"]
    flags = pool["flags"]
    str_offs = []
    blob = bytearray()
    pos = 0
    for e in encoded:
        str_offs.append(pos)
        blob.extend(e)
        pos += len(e)
    while len(blob) % 4:
        blob.append(0)
    sstart = hdr + len(str_offs) * 4
    systart = sstart + len(blob)
    if scnt:
        systart += scnt * 4
    body = bytearray()
    body.extend(struct.pack("<%dI" % len(str_offs), *str_offs))
    body.extend(blob)
    if scnt:
        body.extend(b"\x00" * (scnt * 4))
    size = 8 + 20 + len(body)
    out = bytearray()
    out.extend(struct.pack("<HHI", RES_STRING_POOL_TYPE, hdr, size))
    out.extend(struct.pack("<IIIII", len(strings), scnt, flags, sstart, systart))
    out.extend(body)
    return bytes(out)


def replace_axml_strings(data: bytes, replacements: dict) -> bytes:
    """Replace exact string-pool entries; rebuild pool when lengths change."""
    if struct.unpack_from("<H", data, 0)[0] != RES_XML_TYPE:
        return data
    xhdr = struct.unpack_from("<H", data, 2)[0]
    pool_off = None
    off = xhdr
    while off < len(data) - 8:
        ct, ch, cs = struct.unpack_from("<HHI", data, off)
        if cs == 0:
            break
        if ct == RES_STRING_POOL_TYPE and pool_off is None:
            pool_off = off
        off += cs
    if pool_off is None:
        return data
    pool = _parse_pool(data, pool_off)
    new_strings = [replacements.get(s, s) for s in pool["strings"]]
    if new_strings == pool["strings"]:
        return data
    new_pool = _build_pool(pool, new_strings)
    result = bytearray(data[:pool_off] + new_pool + data[pool_off + pool["size"] :])
    struct.pack_into("<I", result, 4, len(result))
    return bytes(result)


def utf16_sub(data: bytes, old: str, new: str, what: str, required=True) -> bytes:
    if old == new:
        return data
    if len(old) != len(new):
        patched = replace_axml_strings(data, {old: new})
        if patched != data:
            print("    %s: pool rebuild %r -> %r" % (what, old, new))
            return patched
        if not required:
            print("    %s: skip (length %d -> %d)" % (what, len(old), len(new)))
            return data
    ob, nb = old.encode("utf-16-le"), new.encode("utf-16-le")
    n = data.count(ob)
    if n:
        data = data.replace(ob, nb)
    print("    %s: %d occurrence(s) %r -> %r" % (what, n, old, new))
    return data


def utf8_sub(data: bytes, old: str, new: str, what: str, required=True) -> bytes:
    if len(old) != len(new):
        if not required:
            print("    %s: skip (length %d -> %d)" % (what, len(old), len(new)))
            return data
        raise ValueError("%s length %d -> %d" % (what, len(old), len(new)))
    ob, nb = old.encode("utf-8"), new.encode("utf-8")
    n = data.count(ob)
    if n:
        data = data.replace(ob, nb)
    print("    %s: %d utf8 occurrence(s)" % (what, n))
    return data
