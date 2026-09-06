# SMS-link 1.0.1

本目录包含 Android 13+ 的 Android↔Android 通信应用源码、单元测试和 debug 构建配置。

## 产物

- 源码：`SMS3/app/src/main`
- 构建文件：`SMS3/app/build.gradle.kts`、`SMS3/build.gradle.kts`、`SMS3/settings.gradle.kts`
- debug APK：`SMS3/app/build/outputs/apk/debug/app-debug.apk`

## 功能说明

SMS-link 用于多设备之间的发现、配对和内容同步，当前版本包含以下核心能力：

1. 签名二维码配对、BLE GATT 发现/配对与 RFCOMM 回退
2. Wi-Fi 局域网/热点连接、TLS 和应用层身份认证
3. 短信读取、发送、回执和跨设备同步
4. 通知监听、镜像显示及交互回传
5. 带确认、进度、ACK、校验、重试和断点续传的文件传输
6. Telecom/InCallService 通话状态同步与控制
7. 权限引导、角色设置和诊断日志

## 使用说明

1. 在两台 Android 13+ 设备安装同一份 debug APK，授予实际需要的短信、电话、蓝牙和通知权限。
2. 在设备页开启发现；首次配对使用目标设备显示的签名二维码，扫描后由系统保存双方身份和 TLS 证书。
3. 局域网/热点要求两台设备互相可达；蓝牙回退要求系统已完成配对并允许 BLE/RFCOMM 权限。
4. 按需开启通知监听访问，并按系统提示申请默认电话应用/Telecom 能力。
5. 分别验证短信、通知、文件和通话；文件接收必须在目标设备明确接受后才开始写入。

## 编译方式

```powershell
.\gradlew.bat assembleDebug
```

编译完成后，APK 位于：

```text
SMS3/app/build/outputs/apk/debug/app-debug.apk
```

这是一份 debug 构建，不能替代发布签名包。两台真机上的链路、短信、通知、通话和大文件续传仍需现场逐项验收；本地测试通过不等于现场验收通过。

