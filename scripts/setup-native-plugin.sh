#!/bin/bash
set -e

# 复制原生 AudioFocus 插件 Java 文件
mkdir -p android/app/src/main/java/com/alger/audio
cp native/android/capacitor-audio/src/main/java/com/alger/audio/*.java android/app/src/main/java/com/alger/audio/
echo "AudioPlugin Java copied"

# 注入 androidx.media 依赖到 build.gradle
BG=android/app/build.gradle
if ! grep -q 'androidx.media:media' "$BG"; then
  sed -i 's|^dependencies {|dependencies {\n    implementation "androidx.media:media:1.6.0"|' "$BG"
  echo "media dep injected"
fi

# 注册 MediaButtonReceiver 到 AndroidManifest.xml
MF=android/app/src/main/AndroidManifest.xml
if ! grep -q MediaButtonReceiver "$MF"; then
  sed -i '/<\/application>/i\
        <receiver android:name="androidx.media.session.MediaButtonReceiver" android:exported="true">\
            <intent-filter>\
                <action android:name="android.intent.action.MEDIA_BUTTON" />\
            <\/intent-filter>\
        <\/receiver>' "$MF"
  echo "MediaButtonReceiver registered"
fi

echo "Native AudioPlugin setup complete"
