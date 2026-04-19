@echo off
REM SMS-Link 项目构建脚本（Windows 版本）
REM 用途：检查编译错误并生成 APK

echo ==========================================
echo SMS-Link 项目构建脚本
echo ==========================================
echo.

cd /d "%~dp0"

echo 1. 清理项目...
call gradlew.bat clean

echo.
echo 2. 编译项目（Debug 版本）...
call gradlew.bat assembleDebug

if %ERRORLEVEL% NEQ 0 (
    echo.
    echo 错误：编译失败！
    pause
    exit /b %ERRORLEVEL%
)

echo.
echo 3. 运行单元测试...
call gradlew.bat test --continue

echo.
echo 4. 检查代码风格...
call gradlew.bat ktlintCheck

echo.
echo ==========================================
echo 构建完成！
echo ==========================================
echo.
echo APK 位置：
dir /s /b app\build\outputs\apk\debug\*.apk

echo.
echo 下一步：
echo 1. 安装 APK: gradlew.bat installDebug
echo 2. 查看日志: adb logcat -s SmsLinkApp
echo 3. 运行测试: gradlew.bat connectedAndroidTest
echo.
pause
