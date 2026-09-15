#!/usr/bin/env python3
import os, re, shutil, sys
from pathlib import Path
import xml.etree.ElementTree as ET

ANDROID='http://schemas.android.com/apk/res/android'
ET.register_namespace('android', ANDROID)

def a(name): return f'{{{ANDROID}}}{name}'

def die(msg):
    print('ERROR:', msg, file=sys.stderr); sys.exit(2)

if len(sys.argv) != 3:
    die('usage: apply_mncast.py <shield-airplay-source> <overlay-root>')
root=Path(sys.argv[1]).resolve(); overlay=Path(sys.argv[2]).resolve()
if not (root/'app/src/main/AndroidManifest.xml').exists(): die('Not a Shield AirPlay-compatible source tree')

# Branding only in human-readable text. Do not rename upstream symbols.
for p in root.rglob('*'):
    if p.is_file() and p.suffix.lower() in {'.kt','.java','.xml','.md','.txt','.kts','.properties'}:
        try: text=p.read_text(encoding='utf-8')
        except Exception: continue
        new=text.replace('Shield AirPlay','MN Cast').replace('shield-airplay','mn-cast')
        if new!=text: p.write_text(new,encoding='utf-8')

# Unique application id, while leaving the upstream Kotlin namespace untouched.
gradle=root/'app/build.gradle.kts'
text=gradle.read_text(encoding='utf-8')
text=re.sub(r'applicationId\s*=\s*"[^"]+"', 'applicationId = "com.mavinokta.mncast.tv"', text, count=1)
gradle.write_text(text,encoding='utf-8')

# Add MN Cast Android-mirroring implementation.
dst=root/'app/src/main/java/com/mavinokta/mncast/mirror'
dst.mkdir(parents=True,exist_ok=True)
for f in (overlay/'src/com/mavinokta/mncast/mirror').glob('*.kt'):
    shutil.copy2(f,dst/f.name)

manifest=root/'app/src/main/AndroidManifest.xml'
tree=ET.parse(manifest); m=tree.getroot(); app=m.find('application')
if app is None: die('Manifest has no <application>')

existing={x.get(a('name')) for x in m.findall('uses-permission')}
for perm in [
    'android.permission.INTERNET','android.permission.ACCESS_NETWORK_STATE','android.permission.ACCESS_WIFI_STATE',
    'android.permission.CHANGE_WIFI_STATE','android.permission.CHANGE_WIFI_MULTICAST_STATE','android.permission.WAKE_LOCK',
    'android.permission.FOREGROUND_SERVICE','android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE']:
    if perm not in existing:
        node = ET.Element('uses-permission',{a('name'):perm})
        app_index = list(m).index(app)
        m.insert(app_index, node)

def has_component(tag,name):
    return any(x.get(a('name'))==name for x in app.findall(tag))

if not has_component('provider','com.mavinokta.mncast.mirror.MnCastInitProvider'):
    ET.SubElement(app,'provider',{
        a('name'):'com.mavinokta.mncast.mirror.MnCastInitProvider',
        a('authorities'):'${applicationId}.mncast.init',
        a('exported'):'false',
        a('initOrder'):'100'
    })
if not has_component('service','com.mavinokta.mncast.mirror.AndroidMirrorService'):
    ET.SubElement(app,'service',{
        a('name'):'com.mavinokta.mncast.mirror.AndroidMirrorService',
        a('exported'):'false',
        a('foregroundServiceType'):'connectedDevice',
        a('stopWithTask'):'false'
    })
if not has_component('activity','com.mavinokta.mncast.mirror.AndroidMirrorActivity'):
    ET.SubElement(app,'activity',{
        a('name'):'com.mavinokta.mncast.mirror.AndroidMirrorActivity',
        a('exported'):'false',
        a('excludeFromRecents'):'true',
        a('screenOrientation'):'sensorLandscape',
        a('configChanges'):'keyboard|keyboardHidden|navigation|orientation|screenSize|smallestScreenSize|uiMode'
    })

ET.indent(tree, space='    ')
tree.write(manifest,encoding='utf-8',xml_declaration=True)

(root/'MN_CAST_MODIFICATIONS.md').write_text('''# MN Cast modifications\n\nThis tree was generated from the GPLv3 Shield AirPlay project and adds MN Cast Android full-screen mirroring over the local network.\n\n- Apple: UxPlay/AirPlay path from upstream.\n- Android: MediaProjection + hardware AVC encode + low-latency UDP frame transport + MediaCodec decode.\n- Discovery: DNS-SD `_mncast._tcp`.\n- No cloud relay, analytics, account, or internet service is used for mirroring.\n\nBecause the TV app contains GPLv3 code, distribute the corresponding source together with any APK you distribute.\n''',encoding='utf-8')
print('MN Cast overlay applied to', root)
