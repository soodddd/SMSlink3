# Core Common Module

## 功能概述
提供全局通用工具类、扩展函数、常量定义等基础设施。

### 核心功能
- Kotlin 扩展函数
- 日志工具
- 异常处理工具
- 常量定义
- 结果封装类

## 依赖关系
无外部模块依赖（基础模块）

## 使用示例

```kotlin
// 使用 Result 封装
val result = Result.success("data")
result.onSuccess { data -> 
    // 处理成功
}

// 扩展函数
val json = mapOf("key" to "value").toJson()
```

## 测试

```bash
./gradlew :core:common:test
```
