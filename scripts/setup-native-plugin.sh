#!/bin/bash
set -e

# 1. 复制原生 AudioFocus 插件 Java 文件
mkdir -p android/app/src/main/java/com/alger/audio
cp native/android/capacitor-audio/src/main/java/com/alger/audio/*.java android/app/src/main/java/com/alger/audio/
echo "AudioPlugin Java copied"

# 2. 复制原生 Android 资源 (styles, themes)
if [ -d native/android/app/src/main/res ]; then
  cp -r native/android/app/src/main/res/* android/app/src/main/res/
  echo "Native Android resources copied"
fi

# 3. 注入 androidx.media 依赖到 build.gradle
BG=android/app/build.gradle
if ! grep -q 'androidx.media:media' "$BG"; then
  sed -i 's|^dependencies {|dependencies {\n    implementation "androidx.media:media:1.6.0"|' "$BG"
  echo "media dep injected"
fi

# 4. 注入 WAKE_LOCK + POST_NOTIFICATIONS 权限
MF=android/app/src/main/AndroidManifest.xml
if ! grep -q 'WAKE_LOCK' "$MF"; then
  sed -i '/<manifest/a\
  <uses-permission android:name="android.permission.WAKE_LOCK" />\
  <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />' "$MF"
  echo "Permissions injected"
fi

# 5. 注册 MediaButtonReceiver
if ! grep -q MediaButtonReceiver "$MF"; then
  sed -i '/<\/application>/i\
        <receiver android:name="androidx.media.session.MediaButtonReceiver" android:exported="true">\
            <intent-filter>\
                <action android:name="android.intent.action.MEDIA_BUTTON" />\
            <\/intent-filter>\
        <\/receiver>' "$MF"
  echo "MediaButtonReceiver registered"
fi

# 6. 注入暗色主题（沉浸式状态栏 + 启动黑底）
if ! grep -q 'android:theme' "$MF"; then
  sed -i 's|<application|<application android:theme="@style/AppTheme"|' "$MF"
  echo "AppTheme applied"
fi

# 7. 启用 WebView 硬件加速（默认 true，显式声明确保）
if ! grep -q 'hardwareAccelerated' "$MF"; then
  sed -i 's|<application|<application android:hardwareAccelerated="true"|' "$MF"
  echo "Hardware acceleration enabled"
fi

echo "Native AudioPlugin setup complete"
