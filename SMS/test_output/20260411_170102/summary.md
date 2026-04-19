
## Environment
- Workspace root: C:\Users\forek\Desktop\ccwork\SMS
- Run root: C:\Users\forek\Desktop\ccwork\SMS\test_output\20260411_170102
- Android SDK root: C:\Users\forek\AppData\Local\Android\Sdk
- Step timeout seconds: 300
- Build task mode: app-only
- Unit tests enabled: False
- SDK component present: platforms;android-35
- SDK component present: build-tools;35.0.0
- >> C:\Users\forek\AppData\Local\Android\Sdk\platform-tools\adb.exe devices -l
- Primary device: 400E7800HQ00000
- Secondary device: MZEAYX6LE67T995D

## Device preparation
- Preparing device 400E7800HQ00000
- >> C:\Users\forek\AppData\Local\Android\Sdk\platform-tools\adb.exe -s 400E7800HQ00000 wait-for-device
- >> C:\Users\forek\AppData\Local\Android\Sdk\platform-tools\adb.exe -s 400E7800HQ00000 install -r -g C:\Users\forek\Desktop\ccwork\SMS\app\build\outputs\apk\debug\app-debug.apk
- >> C:\Users\forek\AppData\Local\Android\Sdk\platform-tools\adb.exe -s 400E7800HQ00000 shell pm clear com.smslink
- >> C:\Users\forek\AppData\Local\Android\Sdk\platform-tools\adb.exe -s 400E7800HQ00000 shell pm grant com.smslink android.permission.ACCESS_FINE_LOCATION
- >> C:\Users\forek\AppData\Local\Android\Sdk\platform-tools\adb.exe -s 400E7800HQ00000 shell pm grant com.smslink android.permission.ACCESS_COARSE_LOCATION
- >> C:\Users\forek\AppData\Local\Android\Sdk\platform-tools\adb.exe -s 400E7800HQ00000 shell pm grant com.smslink android.permission.CHANGE_WIFI_STATE
- >> C:\Users\forek\AppData\Local\Android\Sdk\platform-tools\adb.exe -s 400E7800HQ00000 shell pm grant com.smslink android.permission.ACCESS_WIFI_STATE
- >> C:\Users\forek\AppData\Local\Android\Sdk\platform-tools\adb.exe -s 400E7800HQ00000 shell pm grant com.smslink android.permission.READ_PHONE_STATE
- >> C:\Users\forek\AppData\Local\Android\Sdk\platform-tools\adb.exe -s 400E7800HQ00000 shell pm grant com.smslink android.permission.CALL_PHONE
- >> C:\Users\forek\AppData\Local\Android\Sdk\platform-tools\adb.exe -s 400E7800HQ00000 shell pm grant com.smslink android.permission.READ_CALL_LOG
- >> C:\Users\forek\AppData\Local\Android\Sdk\platform-tools\adb.exe -s 400E7800HQ00000 shell pm grant com.smslink android.permission.RECORD_AUDIO
- >> C:\Users\forek\AppData\Local\Android\Sdk\platform-tools\adb.exe -s 400E7800HQ00000 shell pm grant com.smslink android.permission.CAMERA
- >> C:\Users\forek\AppData\Local\Android\Sdk\platform-tools\adb.exe -s 400E7800HQ00000 shell pm grant com.smslink android.permission.POST_NOTIFICATIONS
- >> C:\Users\forek\AppData\Local\Android\Sdk\platform-tools\adb.exe -s 400E7800HQ00000 shell pm grant com.smslink android.permission.READ_MEDIA_IMAGES
- >> C:\Users\forek\AppData\Local\Android\Sdk\platform-tools\adb.exe -s 400E7800HQ00000 shell pm grant com.smslink android.permission.READ_MEDIA_VIDEO
- >> C:\Users\forek\AppData\Local\Android\Sdk\platform-tools\adb.exe -s 400E7800HQ00000 shell pm grant com.smslink android.permission.READ_MEDIA_AUDIO
- >> C:\Users\forek\AppData\Local\Android\Sdk\platform-tools\adb.exe -s 400E7800HQ00000 shell pm grant com.smslink android.permission.NEARBY_WIFI_DEVICES
- >> C:\Users\forek\AppData\Local\Android\Sdk\platform-tools\adb.exe -s 400E7800HQ00000 shell settings put secure enabled_notification_listeners com.smslink/com.smslink.feature.notification.SmsLinkNotificationListenerService
- >> C:\Users\forek\AppData\Local\Android\Sdk\platform-tools\adb.exe -s 400E7800HQ00000 shell settings put global stay_on_while_plugged_in 7
- >> C:\Users\forek\AppData\Local\Android\Sdk\platform-tools\adb.exe -s 400E7800HQ00000 shell getprop ro.product.model
- >> C:\Users\forek\AppData\Local\Android\Sdk\platform-tools\adb.exe -s 400E7800HQ00000 shell wm size
- 400E7800HQ00000 model: PA2473
- 400E7800HQ00000 wm size: Physical size: 2064x3096
- Preparing device MZEAYX6LE67T995D
- >> C:\Users\forek\AppData\Local\Android\Sdk\platform-tools\adb.exe -s MZEAYX6LE67T995D wait-for-device
- >> C:\Users\forek\AppData\Local\Android\Sdk\platform-tools\adb.exe -s MZEAYX6LE67T995D install -r -g C:\Users\forek\Desktop\ccwork\SMS\app\build\outputs\apk\debug\app-debug.apk
- >> C:\Users\forek\AppData\Local\Android\Sdk\platform-tools\adb.exe -s MZEAYX6LE67T995D shell pm clear com.smslink
- FAIL: Command failed with exit code 255: C:\Users\forek\AppData\Local\Android\Sdk\platform-tools\adb.exe -s MZEAYX6LE67T995D shell pm clear com.smslink

Artifacts:
- Logs: C:\Users\forek\Desktop\ccwork\SMS\test_output\20260411_170102\logs
- Screenshots: C:\Users\forek\Desktop\ccwork\SMS\test_output\20260411_170102\screenshots
- Videos: C:\Users\forek\Desktop\ccwork\SMS\test_output\20260411_170102\videos
- UI dumps: C:\Users\forek\Desktop\ccwork\SMS\test_output\20260411_170102\uia
- Build logs: C:\Users\forek\Desktop\ccwork\SMS\test_output\20260411_170102\build
