#!/bin/bash

# SMS-Link 项目构建脚本
# 用途：检查编译错误并生成 APK

set -e  # 遇到错误立即退出

echo "=========================================="
echo "SMS-Link 项目构建脚本"
echo "=========================================="
echo ""

# 进入项目目录
cd "$(dirname "$0")"

echo "1. 清理项目..."
./gradlew clean

echo ""
echo "2. 编译项目（Debug 版本）..."
./gradlew assembleDebug

echo ""
echo "3. 运行单元测试..."
./gradlew test --continue || echo "警告：部分测试失败"

echo ""
echo "4. 检查代码风格..."
./gradlew ktlintCheck || echo "警告：代码风格检查失败"

echo ""
echo "=========================================="
echo "构建完成！"
echo "=========================================="
echo ""
echo "APK 位置："
find app/build/outputs/apk/debug -name "*.apk" -type f

echo ""
echo "下一步："
echo "1. 安装 APK: ./gradlew installDebug"
echo "2. 查看日志: adb logcat -s SmsLinkApp"
echo "3. 运行测试: ./gradlew connectedAndroidTest"
