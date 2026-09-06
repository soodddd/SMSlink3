# SMS 模块

短信模块负责本机 SMS Provider、短信广播、Room 持久化和跨设备消息同步。

## 实现范围

- 读取收件箱、发件箱和发送失败记录，并使用稳定内容 ID 去重
- 发送单条及长短信，保存 QUEUED/SENT/DELIVERED/FAILED 状态
- 通过 `SMS_RECEIVED`、`SMS_DELIVER` 及发送/送达回执更新状态
- 通过 `ContentObserver` 做增量同步，应用启动时启动后台同步服务
- 主设备直接调用系统 `SmsManager`；副设备向主设备发出带结果回执的发送请求
- 已读、删除和短信内容通过已认证的跨设备链路同步
- Room 迁移保留旧数据库中的消息和新增投递字段

## 关键文件

- `SmsManagerImpl.kt`：短信业务、系统 Provider 同步和远端动作处理
- `SmsReceiver.kt`：系统广播及发送/送达回执
- `SmsContentObserver.kt`：Provider 变化监听与节流同步
- `SmsSyncService.kt`：后台观察者生命周期
- `SmsViewModel.kt`：角色感知的 UI 状态和发送入口
- `SmsModule.kt`：Hilt 绑定

## 运行条件

- 读取短信需要 `READ_SMS`，发送需要 `SEND_SMS`，接收广播需要 `RECEIVE_SMS`；副设备仅请求跨设备发送时不强制读取本机短信。
- 真实设备仍受 Android 默认短信应用、厂商后台限制、双卡策略和运营商行为影响。
- 当前范围是文本 SMS；MMS、群发和 SIM 卡选择没有伪装成已完成的功能。

## 测试

在 `SMS3` 目录执行：

```powershell
.\gradlew.bat :app:testDebugUnitTest --no-daemon
```
