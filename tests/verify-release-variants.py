#!/usr/bin/env python3
"""Verify signed matching Pink release variants, including actual XML rows."""
from pathlib import Path
import hashlib,json,os,re,subprocess,sys,zipfile,struct,tempfile
fg,nofg=map(Path,sys.argv[1:3]);version=sys.argv[3]
sdk=Path(os.environ.get('ANDROID_HOME',str(Path.home()/'Library/Android/sdk')));bt=sdk/'build-tools/35.0.0'
def run(name,*args):return subprocess.check_output([str(bt/name),*map(str,args)],text=True)
records={};certs=[]
for label,p in [('FRAMEGEN',fg),('NONFRAMEGEN',nofg)]:
 badging=run('aapt','dump','badging',p)
 assert "name='xyz.aethersx2.cpink02'" in badging
 assert f"versionCode='{version}'" in badging and f"versionName='v{version}'" in badging
 certs.append(re.search(r'certificate SHA-256 digest: (\w+)',run('apksigner','verify','--print-certs',p))[1])
 run('zipalign','-c','-p','4',p)
 for xml in ['res/xml/graphics_preferences.xml','res/xml/graphics_game_settings_preferences.xml']:
  tree=run('aapt','dump','xmltree',p,xml)
  assert ('VulkanShim/Lsfg' in tree)==(label=='FRAMEGEN'),(label,xml)
  assert all(key in tree for key in ['VulkanShim/Driver','VulkanShim/LowEndPerf','upscale_multiplier'])
 with zipfile.ZipFile(p) as z:
  resources=z.read('resources.arsc')
  for color in [0xff344529,0xff7ae02e]:assert struct.pack('<HBBI',8,0,0x1c,color) not in resources
  if label=='NONFRAMEGEN':
   assert not any('liblsfg' in n for n in z.namelist())
   assert b'Java_xyz_aethersx2_android_shim_ShimFrameGen_native' not in z.read('lib/arm64-v8a/libvulkad.so')
 records[label]=dict(file=p.name,bytes=p.stat().st_size,sha256=hashlib.sha256(p.read_bytes()).hexdigest())
assert certs[0]==certs[1]
with zipfile.ZipFile(fg) as a,zipfile.ZipFile(nofg) as b:
 removed=set(a.namelist())-set(b.namelist());assert removed=={'lib/arm64-v8a/liblsfg-android.so'}
 assert not set(b.namelist())-set(a.namelist())
 changed=[n for n in b.namelist() if a.read(n)!=b.read(n)]
 allowed={'classes.dex','res/xml/graphics_preferences.xml','res/xml/graphics_game_settings_preferences.xml','lib/arm64-v8a/libvulkad.so'}
 assert all(n in allowed or n.startswith('META-INF/') for n in changed),changed
records.update(versionCode=int(version),versionName='v'+version,package='xyz.aethersx2.cpink02',certificate=certs[0],variant_differences=changed,removed_from_nonframegen=sorted(removed),all_other_entries_identical=True)
print(json.dumps(records,indent=2))
