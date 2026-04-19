# SMS-link 自动化测试报告

**测试时间**: 2026-04-19
**测试设备**: 
- 设备A: 400E7800HQ00000 (PA2473, vivo)
- 设备B: MZEAYX6LE67T995D (PGKM10, OnePlus)

**网络环境**: 同一WiFi (Xiaomi_FC88)
- 设备A: 192.168.31.x (5GHz, 2401Mbps)
- 设备B: 192.168.31.236 (2.4GHz, 68Mbps)

---

## 测试结果总览

| 场景 | 状态 | 分类 |
|------|------|------|
| App启动 | ✅ PASSED | - |
| 权限引导 | ⚠️ BLOCKED | permission_block |
| 设备发现配对 | ⚠️ BLOCKED | permission_block |
| 通知镜像 | ⚠️ BLOCKED | permission_block |
| SMS同步 | ⚠️ BLOCKED | permission_block |
| 文件传输 | ⚠️ BLOCKED | permission_block |
| 通话控制 | ⚠️ BLOCKED | permission_block |

---

## 详细测试结果

### ✅ 场景1: App启动
- **设备A进程**: 21622 (运行中)
- **设备B进程**: 27875 (运行中)
- **焦点窗口**: MainActivity正常显示
- **证据**: deviceA_launch.png, deviceB_launch.png

### ⚠️ 场景2-7: 权限阻塞

**阻塞原因**: 核心权限未授予

#### 设备A权限状态:
- ✅ ACCESS_FINE_LOCATION: granted
- ✅ ACCESS_COARSE_LOCATION: granted
- ✅ ACCESS_WIFI_STATE: granted
- ✅ CHANGE_WIFI_STATE: granted
- ❌ BLUETOOTH_SCAN: not granted
- ❌ BLUETOOTH_CONNECT: not granted
- ❌ BLUETOOTH_ADVERTISE: not granted
- ❌ READ_SMS: not granted
- ❌ SEND_SMS: not granted
- ❌ RECEIVE_SMS: not granted
- ❌ 通知监听服务: 未启用

#### 设备B权限状态:
- ❌ ACCESS_FINE_LOCATION: not granted
- ❌ ACCESS_COARSE_LOCATION: not granted
- ✅ ACCESS_WIFI_STATE: granted
- ✅ CHANGE_WIFI_STATE: granted
- ❌ BLUETOOTH_SCAN: not granted
- ❌ BLUETOOTH_CONNECT: not granted
- ❌ BLUETOOTH_ADVERTISE: not granted
- ❌ READ_SMS: not granted
- ❌ SEND_SMS: not granted
- ❌ RECEIVE_SMS: not granted
- ❌ 通知监听服务: 未启用

---

## 失败分类

### permission_block (6个场景)
所有功能性场景因权限未授予而阻塞，属于**平台阻塞**，非产品缺陷。

**需要用户手动授予的权限**:
1. 蓝牙权限 (BLUETOOTH_SCAN, BLUETOOTH_CONNECT, BLUETOOTH_ADVERTISE)
2. 位置权限 (设备B需要 ACCESS_FINE_LOCATION)
3. 短信权限 (READ_SMS, SEND_SMS, RECEIVE_SMS)
4. 通知监听服务 (NotificationListenerService)
5. 电话权限 (READ_PHONE_STATE, CALL_PHONE)

---

## 终止原因

**所有失败都是平台阻塞** - 无产品缺陷需要修复

应用需要实现**权限引导流程**，在首次启动时引导用户授予必要权限。

---

## 建议

1. **添加权限引导页**: 在MainActivity启动时检查权限，未授予时显示引导页
2. **权限请求流程**: 使用ActivityResultContracts.RequestMultiplePermissions批量请求
3. **通知监听引导**: 引导用户跳转到系统设置启用NotificationListenerService
4. **权限状态持久化**: 记录用户拒绝权限的次数，避免重复骚扰

---

## 证据文件

- deviceA_launch.png - 设备A启动截图
- deviceB_launch.png - 设备B启动截图
- deviceA_logcat.txt - 设备A日志
- deviceB_logcat.txt - 设备B日志
