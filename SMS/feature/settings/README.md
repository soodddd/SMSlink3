# Feature Settings Module

## 功能概述
设置模块，提供应用配置、权限管理、关于页面。

### 核心功能
- 权限管理引导
- 应用设置界面
- 通知过滤规则配置
- 关于页面
- 调试选项

## 依赖关系
- `core:common` - 通用工具
- `core:model` - 数据模型
- `core:preferences` - 配置管理
- `ui` - UI 组件

## 使用示例

```kotlin
// 设置界面使用 Compose
@Composable
fun SettingsScreen() {
    // 设置项
}
```

## 测试

```bash
./gradlew :feature:settings:test
```
