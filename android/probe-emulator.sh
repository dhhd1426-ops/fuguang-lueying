#!/bin/bash
# 模拟器里的磁力探测：装上安装包，带 probe_magnet 启动，等 150 秒，把 FGPROBE 日志存下来
set -x
mkdir -p /tmp/tr
APK=android/app/build/outputs/apk/release/app-release.apk
adb wait-for-device
adb shell getprop ro.product.cpu.abilist
adb install -r -g "$APK" 2>&1 | tail -3
adb logcat -c
M="magnet:?xt=urn:btih:a1dfefec1a9dd7fa8a041ebeeea271db55126d2f&dn=ubuntu-24.04.3-live-server-amd64.iso"
adb shell am start -n com.fuguang.lueying/.MainActivity --es probe_magnet "'$M'"
sleep 150
{ echo "abi: $(adb shell getprop ro.product.cpu.abilist)"; adb logcat -d -s FGPROBE:I; echo "---- crash / native ----"; adb logcat -d | grep -iE "UnsatisfiedLink|FATAL|xl_thunder|libxl|dlopen|houdini|ndk_translation" | head -40; } > /tmp/tr/emulator.txt 2>&1
cat /tmp/tr/emulator.txt | head -120
exit 0
