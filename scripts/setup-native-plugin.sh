#!/bin/bash
set -e

# 1. 复制原生 AudioFocus 插件 Java 文件
mkdir -p android/app/src/main/java/com/alger/audio
cp native/android/capacitor-audio/src/main/java/com/alger/audio/*.java android/app/src/main/java/com/alger/audio/
echo "AudioPlugin Java copied"

# 2. 注入 androidx.media 依赖到 build.gradle
BG=android/app/build.gradle
if ! grep -q 'androidx.media:media' "$BG"; then
  sed -i 's|^dependencies {|dependencies {\n    implementation "androidx.media:media:1.6.0"|' "$BG"
  echo "media dep injected"
fi

# 3. 注入 WAKE_LOCK + POST_NOTIFICATIONS 权限
MF=android/app/src/main/AndroidManifest.xml
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

# 5. 暗色主题：修改 manifest theme + 追加 styles 文件
#    替换 Capacitor 默认的 SplashScreen 主题为自定义暗色主题
if grep -q 'AppTheme.NoActionBarLaunch' "$MF"; then
  sed -i 's|@style/AppTheme.NoActionBarLaunch|@style/AppTheme|' "$MF"
  echo "AppTheme replaced in manifest"
fi

#    创建独立的 themes.xml（不覆盖 Capacitor 的 styles.xml）
THEMES="android/app/src/main/res/values/themes.xml"
if [ ! -f "$THEMES" ]; then
  cat > "$THEMES" << 'THEMEXML'
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <style name="AppTheme" parent="Theme.AppCompat.DayNight.NoActionBar">
        <item name="android:statusBarColor">@android:color/black</item>
        <item name="android:navigationBarColor">@android:color/black</item>
        <item name="android:windowLightStatusBar">false</item>
        <item name="android:windowBackground">@android:color/black</item>
    </style>
</resources>
THEMEXML
  echo "themes.xml created with dark AppTheme"
fi

# 6. 启用 WebView 硬件加速（默认 true，显式声明确保）
if ! grep -q 'hardwareAccelerated' "$MF"; then
  sed -i 's|<application|<application android:hardwareAccelerated="true"|' "$MF"
  echo "Hardware acceleration enabled"
fi

echo "Native AudioPlugin setup complete"
