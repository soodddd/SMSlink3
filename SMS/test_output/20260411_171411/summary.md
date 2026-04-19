
## Environment
- Workspace root: C:\Users\forek\Desktop\ccwork\SMS
- Run root: C:\Users\forek\Desktop\ccwork\SMS\test_output\20260411_171411
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
- >> C:\Users\forek\AppData\Local\Android\Sdk\platform-tools\adb.exe -s 400E7800HQ00000 shell am clear-debug-app
- >> C:\Users\forek\AppData\Local\Android\Sdk\platform-tools\adb.exe -s 400E7800HQ00000 shell settings put global wait_for_debugger 0
- >> C:\Users\forek\AppData\Local\Android\Sdk\platform-tools\adb.exe -s 400E7800HQ00000 install -r -g C:\Users\forek\Desktop\ccwork\SMS\app\build\outputs\apk\debug\app-debug.apk
- FAIL: Command timed out after 180 seconds: C:\Users\forek\AppData\Local\Android\Sdk\platform-tools\adb.exe -s 400E7800HQ00000 install -r -g C:\Users\forek\Desktop\ccwork\SMS\app\build\outputs\apk\debug\app-debug.apk

Artifacts:
- Logs: C:\Users\forek\Desktop\ccwork\SMS\test_output\20260411_171411\logs
- Screenshots: C:\Users\forek\Desktop\ccwork\SMS\test_output\20260411_171411\screenshots
- Videos: C:\Users\forek\Desktop\ccwork\SMS\test_output\20260411_171411\videos
- UI dumps: C:\Users\forek\Desktop\ccwork\SMS\test_output\20260411_171411\uia
- Build logs: C:\Users\forek\Desktop\ccwork\SMS\test_output\20260411_171411\build
