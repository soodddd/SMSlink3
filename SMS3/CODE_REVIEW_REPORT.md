# SMS-link 代码审查报告

## 审查日期：2026-04-19

## 严重问题（Critical）- 必须在真机测试前修复

### 1. 数据库索引缺失 - 性能严重下降
- Message.threadId, address 无索引
- AppNotification.deviceId 无索引  
- Device.isPaired 无索引
- FileTransferEntity.state 无索引

### 2. BluetoothGatt 资源泄漏
- disconnect() 和 close() 顺序问题
- 连接超时后状态不一致

### 3. BluetoothConnection 竞态条件
- volatile 变量非原子操作
- 多线程访问导致 NPE

### 4. 文件句柄和 Socket 泄漏
- BufferedInputStream/OutputStream 未正确关闭
- BluetoothSocket connect() 失败时未关闭

### 5. 权限检查缺失
- SmsReceiver 无 READ_SMS 检查
- InCallService 无默认电话应用检查

### 6. 消息长度验证不足
- MAX_MESSAGE_SIZE = 100MB 可能导致 OOM

### 7. threadId 实现错误
- 使用 hashCode() 导致短信会话混乱

## 立即开始修复
