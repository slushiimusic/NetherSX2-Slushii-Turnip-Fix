"""Helpers for binary AndroidManifest.xml (AXML) patching."""
import struct

RES_STRING_POOL_TYPE = 0x0001
RES_XML_START_ELEMENT_TYPE = 0x0102
RES_XML_END_ELEMENT_TYPE = 0x0103


def parse_pool(d, off):
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
        offs=offs,
        strings=strings,
    )


def find_pool_and_header(d):
    _typ, xhdr, xsize = struct.unpack_from("<HHI", d, 0)
    pool_off = None
    off = xhdr
    while off < len(d) - 8:
        ct, ch, cs = struct.unpack_from("<HHI", d, off)
        if cs == 0:
            break
        if ct == RES_STRING_POOL_TYPE and pool_off is None:
            pool_off = off
        off += cs
    if pool_off is None:
        raise ValueError("string pool not found")
    return xhdr, pool_off, parse_pool(d, pool_off)


def elem_tag_and_name(d, off, P):
    ct, ch, cs = struct.unpack_from("<HHI", d, off)
    if ct != RES_XML_START_ELEMENT_TYPE:
        return None, None, cs
    tag = P["strings"][struct.unpack_from("<i", d, off + ch + 4)[0]]
    astart, asize, acount = struct.unpack_from("<HHH", d, off + ch + 8)
    ap = off + ch + astart
    name_val = None
    for _ in range(acount):
        an = struct.unpack_from("<i", d, ap + 4)[0]
        at = d[ap + 15]
        ad = struct.unpack_from("<I", d, ap + 16)[0]
        nm = P["strings"][an] if an >= 0 else ""
        if nm == "name" and at == 0x03:
            name_val = P["strings"][ad]
        ap += asize
    return tag, name_val, cs


def iter_elements(d, xhdr, P):
    off = xhdr
    while off < len(d) - 8:
        ct, ch, cs = struct.unpack_from("<HHI", d, off)
        if cs == 0:
            break
        if ct == RES_XML_START_ELEMENT_TYPE:
            tag, name, _ = elem_tag_and_name(d, off, P)
            yield off, tag, name, cs
        off += cs


def manifest_has_component(d, tag, class_name):
    xhdr, pool_off, P = find_pool_and_header(d)
    for _off, t, name, _cs in iter_elements(d, xhdr, P):
        if t == tag and name == class_name:
            return True
    return False


def skip_following_end(d, off):
    ct, ch, cs = struct.unpack_from("<HHI", d, off)
    if ct == RES_XML_END_ELEMENT_TYPE and cs:
        return off + cs
    return off


def skip_provider_child_meta(d, start_off, cs, xhdr, P):
    """After a provider element, skip meta-data children (+ END chunks); return insert offset."""
    off = start_off + cs
    while off < len(d) - 8:
        ct, ch, next_cs = struct.unpack_from("<HHI", d, off)
        if next_cs == 0:
            break
        if ct != RES_XML_START_ELEMENT_TYPE:
            break
        tag = P["strings"][struct.unpack_from("<i", d, off + ch + 4)[0]]
        if tag != "meta-data":
            break
        off += next_cs
        off = skip_following_end(d, off)
    return off


def find_first_end_chunk(d, xhdr):
    off = xhdr
    while off < len(d) - 8:
        ct, ch, cs = struct.unpack_from("<HHI", d, off)
        if cs == 0:
            break
        if ct == RES_XML_END_ELEMENT_TYPE:
            return off, cs
        off += cs
    return None, 0
