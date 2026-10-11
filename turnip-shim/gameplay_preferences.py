"""Reorganize the existing compiled preferences without removing any controls."""
import importlib.util
import struct
from dataclasses import dataclass, field

_spec = importlib.util.spec_from_file_location("pref_injector", __file__.replace("gameplay_preferences.py", "add-vulkan-shim-prefs.py"))
_pool = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_pool)

COMMON = (
    "EmuCore/GS/Renderer", "EmuCore/GS/upscale_multiplier",
    "EmuCore/GS/AspectRatio", "EmuCore/EnableWideScreenPatches",
    "EmuCore/EnableCheats", "VulkanShim/Lsfg", "EmuCore/GS/OsdShowFPS",
)

@dataclass
class Node:
    start: bytes
    end: bytes = b""
    children: list = field(default_factory=list)

    def pack(self):
        return self.start + b"".join(n.pack() for n in self.children) + self.end

def chunks(data):
    offset = struct.unpack_from("<H", data, 2)[0]
    while offset < len(data):
        kind, header, size = struct.unpack_from("<HHI", data, offset)
        if size < header or offset + size > len(data):
            raise ValueError("Invalid AXML chunk")
        yield kind, data[offset:offset + size]
        offset += size

def tree(data):
    prefix, suffix, stack, root = [], [], [], None
    for kind, chunk in chunks(data):
        if kind == 0x102:
            node = Node(chunk)
            if stack: stack[-1].children.append(node)
            elif root is None: root = node
            else: raise ValueError("Multiple preference roots")
            stack.append(node)
        elif kind == 0x103:
            node = stack.pop()
            if node.start[16:24] != chunk[16:24]:
                raise ValueError("Mismatched preference tags")
            node.end = chunk
        elif root is None: prefix.append(chunk)
        elif not stack: suffix.append(chunk)
        else: raise ValueError("Unexpected chunk inside preferences")
    if stack or root is None: raise ValueError("Incomplete preference tree")
    return prefix, root, suffix

def attributes(node, strings):
    header = struct.unpack_from("<H", node.start, 2)[0]
    start, size, count = struct.unpack_from("<HHH", node.start, header + 8)
    return {strings[struct.unpack_from("<I", node.start, at + 4)[0]]: node.start[at:at + size]
            for at in range(header + start, header + start + count * size, size)}

def key(node, strings):
    attr = attributes(node, strings).get("key")
    return strings[struct.unpack_from("<I", attr, 16)[0]] if attr and attr[15] == 3 else None

def set_attrs(node, values, strings, namespace, resource_ids):
    attrs = attributes(node, strings)
    for name, (kind, value) in values.items():
        index = strings.index(name)
        raw = value if kind == 3 else 0xffffffff
        attrs[name] = struct.pack("<IIIHBBI", namespace, index, raw, 8, 0, kind, value)
    header = struct.unpack_from("<H", node.start, 2)[0]
    start = struct.unpack_from("<H", node.start, header + 8)[0]
    result = bytearray(node.start[:header + start])
    struct.pack_into("<HH", result, header + 10, 20, len(attrs))
    # Android's styled-attribute lookup expects resource-ID ordering, even
    # though aapt's XML dumper will happily print an unsorted attribute list.
    result += b"".join(attrs[name] for name in sorted(attrs,
                       key=lambda name: resource_ids[strings.index(name)]))
    struct.pack_into("<I", result, 4, len(result))
    node.start = bytes(result)

def simplify(data, expanded_attribute_id):
    strings_to_add = ["initialExpandedChildrenCount", "Gameplay", "Advanced graphics",
                      "Pink/Gameplay", "Pink/AdvancedGraphics", "1.000000"]
    pool_offset = _pool.find_pool_off(data)
    for value in strings_to_add:
        data, _ = _pool.append_one_string(data, pool_offset, value)
    strings = _pool.parse_pool(data, pool_offset)["strings"]
    prefix, root, suffix = tree(bytes(data))
    ns = strings.index("http://schemas.android.com/apk/res-auto")
    expanded = strings.index("initialExpandedChildrenCount")
    for i, chunk in enumerate(prefix):
        if struct.unpack_from("<H", chunk)[0] != 0x180: continue
        header = struct.unpack_from("<H", chunk, 2)[0]
        ids = list(struct.unpack_from("<%dI" % ((len(chunk)-header)//4), chunk, header))
        ids += [0] * max(0, expanded + 1 - len(ids))
        if ids[expanded] not in (0, expanded_attribute_id):
            raise ValueError("Unexpected expanded-child resource ID")
        ids[expanded] = expanded_attribute_id
        prefix[i] = struct.pack("<HHI", 0x180, 8, 8 + len(ids)*4) + struct.pack("<%dI" % len(ids), *ids)
        break
    else: raise ValueError("Preference resource map missing")

    leaves, categories = [], []
    def collect(node):
        tag = strings[struct.unpack_from("<I", node.start, 20)[0]]
        if tag in ("PreferenceCategory", "androidx.preference.PreferenceCategory"):
            categories.append(node)
            for child in node.children: collect(child)
        else: leaves.append(node)
    for child in root.children: collect(child)
    if not categories: raise ValueError("No category template")
    by_key = {key(n, strings): n for n in leaves if key(n, strings)}
    if len(by_key) != len(leaves): raise ValueError("Duplicate or missing preference keys")

    # XML defaults apply to fresh preferences only. Never rewrite saved choices.
    if "EmuCore/GS/upscale_multiplier" in by_key:
        set_attrs(by_key["EmuCore/GS/upscale_multiplier"], {"defaultValue": (3, strings.index("1.000000"))}, strings, ns, ids)
    if "EmuCore/GS/HWDownloadMode" in by_key:
        set_attrs(by_key["EmuCore/GS/HWDownloadMode"], {"defaultValue": (0x10, 0)}, strings, ns, ids)

    def category(title, pref_key, children, collapsed=False):
        node = Node(categories[0].start, categories[0].end, children)
        attrs = {"title": (3, strings.index(title)), "key": (3, strings.index(pref_key)),
                 "initialExpandedChildrenCount": (0x10, 0 if collapsed else 0x7fffffff)}
        set_attrs(node, attrs, strings, ns, ids)
        return node
    common = [by_key[k] for k in COMMON if k in by_key]
    advanced = [n for n in leaves if key(n, strings) not in COMMON]
    root.children = [category("Gameplay", "Pink/Gameplay", common),
                     category("Advanced graphics", "Pink/AdvancedGraphics", advanced, True)]
    result = bytearray(data[:8]) + b"".join(prefix) + root.pack() + b"".join(suffix)
    struct.pack_into("<I", result, 4, len(result))
    return bytes(result)
