import xml.etree.ElementTree as ET
import sys, os

styles_path = sys.argv[1] if len(sys.argv) > 1 else 'android/app/src/main/res/values/styles.xml'

if not os.path.exists(styles_path):
    print(f"styles.xml not found at {styles_path}, skipping theme injection")
    sys.exit(0)

ET.register_namespace('android', 'http://schemas.android.com/apk/res/android')
tree = ET.parse(styles_path)
root = tree.getroot()
changed = False

dark_items = {
    'android:statusBarColor': '@android:color/black',
    'android:navigationBarColor': '@android:color/black',
    'android:windowLightStatusBar': 'false',
}

for style in root.findall('style'):
    name = style.get('name', '')
    # 注入到 SplashScreen 主题和基础 AppCompat 主题
    if 'NoActionBar' in name or 'AppTheme' in name:
        existing = {item.get('name') for item in style.findall('item') if item.get('name')}
        for key, val in dark_items.items():
            if key not in existing:
                item = ET.SubElement(style, 'item', {'name': key})
                item.text = val
                changed = True
                print(f"  + {key}={val} → {name}")

if changed:
    tree.write(styles_path, encoding='utf-8', xml_declaration=True)
    print("✅ Dark theme injected into styles.xml")
else:
    print("Dark theme already present, skipped")
