import re
import sys

# ---------- AndroidManifest.xml ----------
P = 'android/app/src/main/AndroidManifest.xml'
s = open(P, encoding='utf-8').read()

perms = [
    'android.permission.ACCESS_FINE_LOCATION',
    'android.permission.ACCESS_COARSE_LOCATION',
    'android.permission.READ_PHONE_STATE',
    'android.permission.ACCESS_NETWORK_STATE',
    'moe.shizuku.manager.permission.API_V23',
]
add = ''
for p in perms:
    if p not in s:
        add += f'    <uses-permission android:name="{p}" />\n'

# Lets the app see whether the Shizuku app is installed (Android 11+ package visibility).
if 'moe.shizuku.privileged.api' not in s:
    add += '    <queries>\n        <package android:name="moe.shizuku.privileged.api" />\n    </queries>\n'

provider = (
    '        <provider\n'
    '            android:name="rikka.shizuku.ShizukuProvider"\n'
    '            android:authorities="${applicationId}.shizuku"\n'
    '            android:multiprocess="false"\n'
    '            android:enabled="true"\n'
    '            android:exported="true"\n'
    '            android:permission="android.permission.INTERACT_ACROSS_USERS_FULL" />\n'
)
if 'rikka.shizuku.ShizukuProvider' not in s:
    if '</application>' not in s:
        sys.exit('Manifest has no </application> tag, cannot add the Shizuku provider')
    s = s.replace('</application>', provider + '    </application>', 1)

if add:
    s = s.replace('</manifest>', add + '</manifest>')
open(P, 'w', encoding='utf-8').write(s)
print('Manifest patched')

# ---------- app/build.gradle: Shizuku libraries ----------
G = 'android/app/build.gradle'
g = open(G, encoding='utf-8').read()
if 'dev.rikka.shizuku' not in g:
    g += (
        "\n// Shizuku (added by scripts/patch_android.py)\n"
        "dependencies {\n"
        "    implementation 'dev.rikka.shizuku:api:13.1.5'\n"
        "    implementation 'dev.rikka.shizuku:provider:13.1.5'\n"
        "}\n"
    )
    open(G, 'w', encoding='utf-8').write(g)
print('Gradle dependencies patched')

# ---------- variables.gradle: Shizuku needs minSdk 24 or higher; use 26 ----------
V = 'android/variables.gradle'
try:
    v = open(V, encoding='utf-8').read()
    m = re.search(r'minSdkVersion\s*=\s*(\d+)', v)
    if m and int(m.group(1)) < 26:
        v = v[:m.start(1)] + '26' + v[m.end(1):]
        open(V, 'w', encoding='utf-8').write(v)
        print('minSdkVersion raised to 26')
    else:
        print('minSdkVersion left as is:', m.group(1) if m else 'not found')
except FileNotFoundError:
    print('variables.gradle not found, minSdk unchanged')
