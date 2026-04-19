# 真机测试前关键修复检查清单

## ✅ 已完成修复（必须项）

### 数据库层
- [x] Message 表添加索引 (threadId, address, timestamp, read)
- [x] AppNotification 表添加索引 (deviceId, timestamp, isSynced)
- [x] Device 表添加索引 (isPaired, lastSeen)
- [x] FileTransferEntity 表添加索引 (state, deviceId, timestamp)
- [x] CallLog 表添加索引 (phoneNumber, timestamp, isSynced)
- [x] Converters 使用 Gson 单例
- [x] 数据库版本升级到 v5

### 资源管理
- [x] BluetoothSocket connect() 失败时正确关闭
- [x] BleGattClient disconnect() 添加延迟和清理
- [x] BluetoothConnection send() 添加 synchronized

### 安全与权限
- [x] SmsReceiver 添加 READ_SMS 权限检查
- [x] InCallService 添加 SecurityException 捕获
- [x] 消息长度限制降低到 10MB

### 逻辑修复
- [x] threadId 使用系统 ContentProvider 查询
- [x] SmsLinkApplication 移除无效代码
- [x] BleGattClient 使用 Dispatchers.IO

### 编译验证
- [x] 代码编译成功
- [x] APK 生成成功

## 📋 真机测试检查项

### 基础功能测试
- [ ] 应用安装成功
- [ ] 应用启动无崩溃
- [ ] 权限请求流程正常
- [ ] 设备发现和配对流程

### 数据库性能测试
- [ ] 插入 100 条短信，查询速度 < 100ms
- [ ] 插入 1000 条通知，查询速度 < 500ms
- [ ] 按 threadId 查询短信正确分组

### 蓝牙连接测试
- [ ] 连接成功率 > 95%
- [ ] 反复连接/断开 20 次无泄漏
- [ ] 并发发送 10 条消息无异常
- [ ] 连接超时后正确清理资源

### 短信功能测试
- [ ] 接收短信正确保存
- [ ] threadId 正确分组（同一联系人）
- [ ] 权限撤销后不崩溃
- [ ] 多段短信正确合并

### 通话功能测试
- [ ] 默认电话应用时接听/挂断正常
- [ ] 非默认应用时错误提示清晰
- [ ] 通话状态变化正确记录

### 资源监控
- [ ] 运行 1 小时内存增长 < 50MB
- [ ] 蓝牙连接数 <= 活跃设备数
- [ ] 文件描述符数 < 100

## ⚠️ 已知限制（需在测试中验证）

1. 数据库使用 fallbackToDestructiveMigration，升级会清空数据
2. BLE 配对未加密，存在中间人攻击风险
3. 数据明文存储，root 设备可读取
4. 大数据量场景未优化分页

## 🚨 测试失败时的排查步骤

### 应用崩溃
1. 使用 `adb logcat | grep -E "AndroidRuntime|FATAL"` 查看崩溃日志
2. 检查权限是否正确授予
3. 检查数据库版本是否正确升级

### 蓝牙连接失败
1. 检查蓝牙权限是否授予
2. 使用 `adb logcat | grep Bluetooth` 查看详细日志
3. 检查设备是否已配对

### 短信接收失败
1. 检查 READ_SMS 权限
2. 使用 `adb logcat | grep SmsReceiver` 查看日志
3. 检查广播接收器是否注册

### 性能问题
1. 使用 Android Profiler 监控内存和 CPU
2. 检查数据库索引是否生效
3. 使用 `adb shell dumpsys meminfo com.smslink` 查看内存

## 📝 测试报告模板

```
测试日期：____
测试设备：____
系统版本：____
测试人员：____

基础功能：[ ] 通过 [ ] 失败
数据库性能：[ ] 通过 [ ] 失败
蓝牙连接：[ ] 通过 [ ] 失败
短信功能：[ ] 通过 [ ] 失败
通话功能：[ ] 通过 [ ] 失败
资源监控：[ ] 通过 [ ] 失败

问题描述：
1. ____
2. ____

建议修复：
1. ____
2. ____
```

## 🎯 测试通过标准

- 所有基础功能测试项通过
- 至少 80% 的性能测试项达标
- 无严重崩溃或资源泄漏
- 关键路径（通知、短信、文件传输）稳定可用

测试通过后，可以进入下一阶段的功能完善和优化。
