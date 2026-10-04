#!/system/bin/sh
# Run this as root ON the device (e.g. `adb shell`, then `su`, then paste this script, or run it
# from a root terminal app / Magisk's "Run shell script" action) after installing Assist.
#
# It grants/primes everything the app needs so it can run as a true headless background
# assistant without being killed or nagged for permissions.

PKG="com.netbypass.assist"

echo "== Assist root setup for $PKG =="

echo "-- Granting runtime permissions"
pm grant $PKG android.permission.RECORD_AUDIO 2>/dev/null
pm grant $PKG android.permission.POST_NOTIFICATIONS 2>/dev/null
pm grant $PKG android.permission.ACCESS_FINE_LOCATION 2>/dev/null
pm grant $PKG android.permission.ACCESS_COARSE_LOCATION 2>/dev/null
pm grant $PKG android.permission.CAMERA 2>/dev/null
pm grant $PKG android.permission.BLUETOOTH_CONNECT 2>/dev/null
pm grant $PKG android.permission.BLUETOOTH_SCAN 2>/dev/null
appops set $PKG WRITE_SETTINGS allow 2>/dev/null

echo "-- Whitelisting from battery / doze / app-standby so it survives headless with screen off"
dumpsys deviceidle whitelist +$PKG 2>/dev/null
cmd appops set $PKG RUN_IN_BACKGROUND allow 2>/dev/null
cmd appops set $PKG RUN_ANY_IN_BACKGROUND allow 2>/dev/null

echo "-- Allowing background start of the foreground service"
cmd appops set $PKG START_FOREGROUND allow 2>/dev/null

echo "-- Keeping the device from auto-locking so voice can keep running"
settings put system screen_off_timeout 1800000 2>/dev/null

echo "Done. Now:"
echo "1. Open the Assist app once."
echo "2. Tap 'Request / test root (su)' and accept the root prompt."
echo "3. Confirm/edit Base URL + API key, tap Save."
echo "4. Enable 'Start automatically on boot' and 'Headless mode' if this device will run as a"
echo "   dedicated voice speaker, then tap Start."
echo "5. Tap 'Disable battery optimization for Assist' when prompted by Android."
