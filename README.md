# SMSlink3

SMS-link Android 项目位于 `SMS3/` 目录。当前这一版包含可编译源码、调试 APK，以及功能和使用说明。

## 产物

- 源码：`SMS3/app/src/main`
- 编译 APK：`SMS3/dist/SMSlink3-1.0.1-debug.apk`
- 详细说明：`SMS3/README.md`

## 功能说明

1. 设备发现与配对
2. 多设备主设备切换
3. 短信流转
4. 通知流转
5. 文件传输
6. 通话控制
7. 本地诊断与日志页面

## 使用说明

1. 安装 `SMS3/dist/SMSlink3-1.0.1-debug.apk`。
2. 首次启动后授予短信、通知、文件访问和相关系统权限。
3. 进入设备页完成发现与配对。
4. 在连接有效期内执行短信、通知、文件或主设备切换测试。
5. 先确认目标设备在线，再执行跨设备同步。

## 构建

```powershell
cd SMS3
.\gradlew.bat assembleDebug
```

