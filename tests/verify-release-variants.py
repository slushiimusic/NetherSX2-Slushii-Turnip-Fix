#!/usr/bin/env python3
"""Verify signed matching Pink release variants, including actual XML rows."""
from pathlib import Path
import hashlib,json,os,re,subprocess,sys,zipfile,struct,tempfile
fg,nofg=map(Path,sys.argv[1:3]);version=sys.argv[3]
sdk=Path(os.environ.get('ANDROID_HOME',str(Path.home()/'Library/Android/sdk')));bt=sdk/'build-tools/35.0.0'
def run(name,*args):return subprocess.check_output([str(bt/name),*map(str,args)],text=True)
def verify_launcher(p):
 background='res/drawable/ic_launcher_background.xml'
 tree=run('aapt','dump','xmltree',p,background)
 # The broken release contained width/height but no viewport or path data.
 for key in ['viewportWidth','viewportHeight']:
  value=re.search(r'android:'+key+r'\([^)]*\)=\(type 0x4\)0x([0-9a-f]+)',tree)
  assert value and struct.unpack('<f',struct.pack('<I',int(value[1],16)))[0]>0,(p,key,'invalid icon viewport')
 assert re.search(r'android:pathData\([^)]*\)="[^"]+"',tree),(p,'missing icon path')
 assert 'android:fillColor' in tree,(p,'missing icon fill')
 # Normal uses a vector background; round uses an existing transparent color.
 resources=run('aapt','dump','--values','resources',p)
 def resource_id(name):
  return re.search(r'spec resource (0x[0-9a-f]+) [^:]+:'+name+r':',resources)[1]
 fg=resource_id('mipmap/ic_launcher_foreground')
 with zipfile.ZipFile(p) as z:
  for leaf in ['ic_launcher','ic_launcher_round']:
   bg=resource_id(('drawable' if leaf=='ic_launcher' else 'color')+'/ic_launcher_background')
   if leaf=='ic_launcher_round':
    assert re.search(r'resource '+bg+r' [^\n]+: t=0x1[cd] d=0x[0-9a-f]+',resources),(p,'invalid round background')
   adaptive=run('aapt','dump','xmltree',p,'res/mipmap-anydpi/'+leaf+'.xml')
   assert 'E: adaptive-icon' in adaptive and '@'+bg in adaptive and '@'+fg in adaptive,(p,leaf)
   for density,size in [('mdpi',48),('hdpi',72),('xhdpi',96),('xxhdpi',144),('xxxhdpi',192)]:
    for icon in [leaf,'ic_launcher_foreground']:
     png=z.read('res/mipmap-'+density+'/'+icon+'.png')
     edge=size*9//4 if icon=='ic_launcher_foreground' else size
     assert png[:8]==b'\x89PNG\r\n\x1a\n' and struct.unpack('>II',png[16:24])==(edge,edge),(p,icon,density)
records={};certs=[]
for label,p in [('FRAMEGEN',fg),('NONFRAMEGEN',nofg)]:
 verify_launcher(p)
 badging=run('aapt','dump','badging',p)
 assert "name='xyz.aethersx2.cpink02'" in badging
 assert f"versionCode='{version}'" in badging and f"versionName='v{version}'" in badging
 expected_label="NetherSX2 (Slushii's Turnip Fix)"
 app_labels=re.findall(r"^application-label(?:-[^:]+)?:'(.*)'$",badging,re.M)
 assert app_labels and all(s==expected_label for s in app_labels),(label,'installer label')
 assert "label='"+expected_label+"'" in next(s for s in badging.splitlines() if s.startswith('launchable-activity:'))
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
records.update(versionCode=int(version),versionName='v'+version,package='xyz.aethersx2.cpink02',certificate=certs[0],variant_differences=changed,removed_from_nonframegen=sorted(removed),all_other_entries_identical=True,adaptive_icons_and_legacy_pngs_verified=True,installer_and_launcher_labels_match=True)
print(json.dumps(records,indent=2))
