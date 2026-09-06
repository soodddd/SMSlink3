# SMSlink3

SMSlink3 是一个 Android 13+ 的 Android↔Android 通信应用。源码和 Gradle 工程位于 `SMS3/`。

## 当前实现

- 设备发现、二维码配对、设备身份密钥和配对令牌
- Wi-Fi 局域网/热点优先，BLE GATT 用于发现、配对和控制，RFCOMM 作为轻量消息回退
- 应用层签名认证、长度分帧和重连/链路切换
- 短信读取、发送、状态回执、已读同步和跨设备请求
- 通知监听、同步及回复/操作回传
- 带确认、进度、校验、逐块 ACK、失败重试和断点续传的文件传输
- Telecom/InCallService 通话状态同步、拨号入口和通话控制入口
- 权限引导、持久化连接策略、诊断日志和 Room 数据库迁移

## 构建

```powershell
cd SMS3
.\gradlew.bat clean :app:testDebugUnitTest --no-daemon
.\gradlew.bat clean :app:assembleDebug --no-daemon
```

APK 输出在 `SMS3/app/build/outputs/apk/debug/app-debug.apk`。

## 使用前提

1. 在两台 Android 13+ 设备上安装 APK，并分别完成运行时权限、通知监听访问权限和必要的系统角色设置。
2. 两台设备首次通过二维码完成配对；二维码只负责建立双方身份和端点绑定，不是明文共享密钥。
3. 局域网通信要求设备处于同一可互通网络；热点场景需要按系统提示连接到同一热点。
4. 短信收发需要真实 SIM、电话/SMS 权限及系统允许的短信能力；通话控制需要系统 Telecom/InCallService 条件。
5. 文件选择使用系统 Storage Access Framework，不依赖整机存储权限。

## 验证边界

本仓库的本地单元测试和 debug APK 构建可在无 Android 真机时执行。两台真实设备上的二维码、局域网/热点、BLE/RFCOMM、短信、通知、通话和大文件断点续传仍需在目标设备上逐项验收；不能用本地编译结果代替现场验收。

## 更新文档

- [变更日志](SMS3/CHANGELOG.md)
- [本次发布说明](SMS3/RELEASE_NOTES_2026-09-06.md)
- [本次验证报告](SMS3/VALIDATION_REPORT_2026-09-06.md)

## 来源

上游仓库：[soodddd/SMSlink3](https://github.com/soodddd/SMSlink3)
