#!/bin/bash
set -e

# 1. 复制原生音频插件 Java 文件
mkdir -p android/app/src/main/java/com/alger/audio
cp native/android/capacitor-audio/src/main/java/com/alger/audio/*.java android/app/src/main/java/com/alger/audio/
echo "AudioPlugin Java copied"

# 2. 注入 androidx.media 依赖到 build.gradle
BG=android/app/build.gradle
if ! grep -q 'androidx.media:media' "$BG"; then
  sed -i 's|^dependencies {|dependencies {\n    implementation "androidx.media:media:1.6.0"|' "$BG"
  echo "media dep injected"
fi

MF=android/app/src/main/AndroidManifest.xml

# 3. 注入权限
if ! grep -q 'WAKE_LOCK' "$MF"; then
  sed -i '/<manifest/a\
  <uses-permission android:name="android.permission.WAKE_LOCK" />\
  <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />' "$MF"
  echo "Permissions injected"
fi

# 4. 注册 MediaButtonReceiver
if ! grep -q MediaButtonReceiver "$MF"; then
  sed -i '/<\/application>/i\
        <receiver android:name="androidx.media.session.MediaButtonReceiver" android:exported="true">\
            <intent-filter>\
                <action android:name="android.intent.action.MEDIA_BUTTON" />\
            <\/intent-filter>\
        <\/receiver>' "$MF"
  echo "MediaButtonReceiver registered"
fi

# 5. 注册 MediaBrowserService（Android Auto 车机连接必需）
if ! grep -q 'MediaBrowserService' "$MF"; then
  sed -i '/<\/application>/i\
        <service android:name="com.alger.audio.MediaBrowserService" android:exported="true">\
            <intent-filter>\
                <action android:name="android.media.browse.MediaBrowserService" />\
            <\/intent-filter>\
        <\/service>' "$MF"
  echo "MediaBrowserService registered"
fi

# 6. 添加 Android Auto 元数据声明（让车机识别为媒体播放器）
if ! grep -q 'androidx.car.app' "$MF"; then
  # 在 application 标签内添加 meta-data
  # 使用 python 精确插入，避免 sed 破坏 XML
  python3 -c "
import xml.etree.ElementTree as ET
import re

with open('$MF', 'r') as f:
    content = f.read()

# 在 </application> 前插入 Android Auto 元数据
auto_meta = '''        <meta-data
            android:name=\"com.google.android.gms.car.application\"
            android:resource=\"@xml/automotive_app_desc\" />
'''

if auto_meta not in content:
    content = content.replace('</application>', auto_meta + '    </application>')
    with open('$MF', 'w') as f:
        f.write(content)
    print('Android Auto meta-data injected')
else:
    print('Android Auto meta-data already exists')
"
  echo "Android Auto metadata added"
fi

# 7. 创建 automotive_app_desc.xml（Android Auto 声明文件）
AUTO_XML="android/app/src/main/res/xml/automotive_app_desc.xml"
if [ ! -f "$AUTO_XML" ]; then
  mkdir -p android/app/src/main/res/xml
  cat > "$AUTO_XML" << 'XMLEOF'
<?xml version="1.0" encoding="utf-8"?>
<automotiveApp>
    <uses name="media" />
</automotiveApp>
XMLEOF
  echo "automotive_app_desc.xml created"
fi

# 8. 暗色状态栏
python3 native/android/capacitor-audio/scripts/inject_theme.py

# 9. WebView 硬件加速
if ! grep -q 'hardwareAccelerated' "$MF"; then
  sed -i 's|<application|<application android:hardwareAccelerated="true"|' "$MF"
  echo "Hardware acceleration enabled"
fi

echo "Native AudioPlugin setup complete"