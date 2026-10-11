#!/usr/bin/env python3
"""Check the packaged Android XML, including rebuilds and attribute ordering."""
import sys, struct, zipfile
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'turnip-shim'))
import gameplay_preferences as g

base, candidate = sys.argv[1:3]
for name in ('graphics_preferences', 'graphics_game_settings_preferences'):
    entry = 'res/xml/' + name + '.xml'
    def read(apk):
        with zipfile.ZipFile(apk) as z: data = z.read(entry)
        strings = g._pool.parse_pool(data, g._pool.find_pool_off(data))['strings']
        prefix, root, _ = g.tree(data)
        return data, strings, prefix, root
    old, old_strings, _, old_root = read(base)
    new, strings, prefix, root = read(candidate)
    def leaves(node, pool):
        for child in node.children:
            if child.children: yield from leaves(child, pool)
            else: yield g.key(child, pool), child
    before = dict(leaves(old_root, old_strings)); after = dict(leaves(root, strings))
    expected = set(before) | {'EmuCore/GS/OsdShowFPS'}
    if 'VulkanShim/Lsfg' not in after: expected.discard('VulkanShim/Lsfg')
    assert set(after) == expected, (name, set(after) ^ expected)
    assert [g.key(n, strings) for n in root.children[0].children] == [k for k in g.COMMON if k in after]
    assert struct.unpack_from('<I', g.attributes(root.children[1], strings)['initialExpandedChildrenCount'], 16)[0] == 0
    assert g.simplify(new, 0x7f04021e) == new, 'Rebuild changed already simplified preferences'
    for chunk in prefix:
        if struct.unpack_from('<H', chunk)[0] == 0x180:
            ids = struct.unpack_from('<%dI' % ((len(chunk)-8)//4), chunk, 8)
    for node in [root, *root.children, *after.values()]:
        attrs = g.attributes(node, strings)
        order = [ids[strings.index(n)] for n in attrs]
        assert order == sorted(order), ('Android styled-attribute order', name, g.key(node, strings))
    for pref in set(before) & set(after):
        for attr_name, attr in g.attributes(before[pref], old_strings).items():
            if attr_name == 'defaultValue' and pref in ('EmuCore/GS/upscale_multiplier', 'EmuCore/GS/HWDownloadMode'): continue
            if pref.startswith('VulkanShim/') and attr_name == 'summary': continue
            updated = g.attributes(after[pref], strings)[attr_name]
            def value(a, pool):
                typ, val = a[15], struct.unpack_from('<I', a, 16)[0]
                return typ, pool[val] if typ == 3 else val
            assert value(attr, old_strings) == value(updated, strings), (name, pref, attr_name)
    print('PASS:', name, 'all controls retained, common controls first, advanced expansion, sorted attributes, rebuild and original values')
