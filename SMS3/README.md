# SMS-link 1.0.1 发布包

本仓库这一版包含可编译的 Android 应用源码、测试通过的 debug APK，以及面向使用者的功能说明和使用说明。

## 产物

- 源码：`SMS3/app/src/main`
- 构建文件：`SMS3/app/build.gradle.kts`、`SMS3/build.gradle.kts`、`SMS3/settings.gradle.kts`
- 编译 APK：`SMS3/dist/SMSlink3-1.0.1-debug.apk`

## 功能说明

SMS-link 用于多设备之间的发现、配对和内容同步，当前版本包含以下核心能力：

1. 设备发现与配对
2. 多设备主设备切换
3. 短信同步
4. 通知流转与镜像显示
5. 文件传输
6. 通话相关同步与控制
7. 本地日志与诊断页面

## 使用说明

1. 安装 `SMSlink3-1.0.1-debug.apk` 到至少两台 Android 设备或模拟器。
2. 启动应用后，先授予短信、通知、存储以及相关系统权限。
3. 在设备页开启发现，并完成设备配对。
4. 在通信页或通知页验证同步状态，确保目标设备处于连接有效窗口内。
5. 进行短信、通知或文件操作时，先确认目标设备已连接，再执行发送。
6. 若要验证主设备切换，请在多台设备之间切换角色后重新进入设备页确认角色显示一致。

## 编译方式

```powershell
.\gradlew.bat assembleDebug
```

编译完成后，APK 位于：

```text
SMS3/app/build/outputs/apk/debug/app-debug.apk
```

如需对外发布，建议优先使用 `SMS3/dist/SMSlink3-1.0.1-debug.apk` 这一份已整理好的发布包副本。

