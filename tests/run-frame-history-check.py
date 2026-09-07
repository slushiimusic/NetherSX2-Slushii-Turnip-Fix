#!/usr/bin/env python3
"""Compile real LSFG Context code against deterministic host GPU boundaries."""
from pathlib import Path
import os,subprocess,tempfile
root=Path(__file__).resolve().parent.parent
with tempfile.TemporaryDirectory(prefix='nether-history-') as tmp:
    out=Path(tmp)
    includes=['volk.h','vulkan/vulkan_core.h','common/utils.hpp','common/exception.hpp']
    includes += ['core/'+x+'.hpp' for x in ['image','semaphore','fence','commandbuffer']]
    includes += ['shaders/'+x+'.hpp' for x in ['mipmaps','alpha','beta','gamma','delta','generate']]
    for rel in includes:
        p=out/rel;p.parent.mkdir(parents=True,exist_ok=True)
        p.write_text('#include "'+str(root/'tests/FrameHistoryBoundary.hpp')+'"\n')
    base=root/'third_party/lsfg-vk-android/framegen'
    for suffix,ns in [('', 'v3_1'),('p','v3_1p')]:
        executable=out/('history'+suffix)
        # Copy the exact public context declaration beside boundary shader headers
        # so quoted relative includes resolve to doubles, not real GPU wrappers.
        header=out/ns/'context.hpp'; header.parent.mkdir(parents=True,exist_ok=True)
        header.write_bytes((base/('v3.1'+suffix+'_include')/ns/'context.hpp').read_bytes())
        subprocess.run([os.environ.get('CXX','c++'),'-std=c++17','-O2',
            '-I'+str(out),'-I'+str(base/('v3.1'+suffix+'_include')),
            '-DCONTEXT_HEADER="'+ns+'/context.hpp"',
            '-DTEST_NAMESPACE=LSFG_3_1'+suffix.upper(),
            str(root/'tests/FrameHistoryTest.cpp'),str(base/('v3.1'+suffix+'_src/context.cpp')),
            '-o',str(executable)],check=True)
        subprocess.run([str(executable)],check=True)
