AI 代码修改指令集

1. 概览
文件路径: `app/src/main/AndroidManifest.xml`, `app/build.gradle.kts`, `audio/build.gradle.kts`, `audio/CMakeLists.txt`, `audio/README.md`, `README.md`, `plan1.md`, `docs/modules/*.md`, `core/*/build.gradle.kts`, `network/*/build.gradle.kts`, `feature/*/build.gradle.kts`, `ui/build.gradle.kts`

主要任务: 让第一阶段项目骨架达到“可自洽构建、文档与实现一致、模块文档结构完整”的最低交付标准。

技术栈: Android + Kotlin + Gradle Kotlin DSL + Jetpack Compose + Hilt + CMake/NDK

2. 修改任务清单

[任务 #001]
定位锚点: `android:icon="@mipmap/ic_launcher"` / `android:theme="@style/Theme.SmsLink"` / `android:roundIcon="@mipmap/ic_launcher_round"`

当前代码:

```xml
<application
    android:name=".SmsLinkApplication"
    android:allowBackup="true"
    android:icon="@mipmap/ic_launcher"
    android:label="@string/app_name"
    android:roundIcon="@mipmap/ic_launcher_round"
    android:supportsRtl="true"
    android:theme="@style/Theme.SmsLink">

    <activity
        android:name=".MainActivity"
        android:exported="true"
        android:theme="@style/Theme.SmsLink">
```

修改方案:

```xml
<application
    android:name=".SmsLinkApplication"
    android:allowBackup="true"
    android:label="@string/app_name"
    android:supportsRtl="true"
    android:theme="@android:style/Theme.DeviceDefault.DayNight">

    <activity
        android:name=".MainActivity"
        android:exported="true"
        android:theme="@android:style/Theme.DeviceDefault.DayNight">
```

修改逻辑: 当前仓库没有 `mipmap` 图标资源，也没有 `Theme.SmsLink` 的样式定义，但 `Manifest` 强依赖它们，会直接导致资源链接失败。第一阶段应先保证应用可以稳定编译，再决定是否补齐品牌资源。优先移除对不存在资源的硬依赖，避免仓库处于“文档看起来完整、实际无法构建”的状态。

严重程度: 阻断

[任务 #002]
定位锚点: `externalNativeBuild` / `add_subdirectory(src/main/cpp/opus)` / `add_subdirectory(src/main/cpp/webrtc)`

当前代码:

```kotlin
defaultConfig {
    minSdk = 26
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    externalNativeBuild {
        cmake {
            cppFlags += listOf("-std=c++17", "-frtti", "-fexceptions")
            arguments += listOf(
                "-DANDROID_STL=c++_shared",
                "-DANDROID_PLATFORM=android-26"
            )
        }
    }

    ndk {
        abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
    }
}

externalNativeBuild {
    cmake {
        path = file("CMakeLists.txt")
        version = "3.22.1"
    }
}
```

```cmake
# Opus codec library
add_subdirectory(src/main/cpp/opus)

# WebRTC audio processing
add_subdirectory(src/main/cpp/webrtc)

# Main audio library
add_library(smslink_audio SHARED
    src/main/cpp/jni/audio_jni.cpp
    src/main/cpp/jni/opus_codec.cpp
    src/main/cpp/jni/audio_processor.cpp
)
```

修改方案:

```kotlin
defaultConfig {
    minSdk = 33
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
}
```

```md
## 当前状态
- 本模块仅保留阶段性占位结构
- Native 源码、第三方音频库源码和 JNI 实现尚未提交
- 在这些文件真实落库前，禁止在 Gradle 中启用 `externalNativeBuild`
```

修改逻辑: `audio` 模块当前没有任何 JNI/C++ 实现文件，也没有 `opus` / `webrtc` 子目录，`CMakeLists.txt` 和 `build.gradle.kts` 却强行接入 native 构建，这是必然失败的假骨架。第一阶段必须先把该模块降级为“占位 Android Library”，并在文档中明确说明“未实现”，而不是让构建系统指向不存在的源码。

严重程度: 阻断

[任务 #003]
定位锚点: `minSdk = 26`

当前代码:

```kotlin
defaultConfig {
    applicationId = "com.smslink"
    minSdk = 26
    targetSdk = 35
    versionCode = 1
    versionName = "1.0.0"
}
```

修改方案:

```kotlin
defaultConfig {
    applicationId = "com.smslink"
    minSdk = 33
    targetSdk = 35
    versionCode = 1
    versionName = "1.0.0"
}
```

修改逻辑: 产品需求已经明确是 Android 13+，也就是 API 33+。当前 `app`、`audio`、`core/*`、`network/*`、`feature/*`、`ui` 全部仍写成 `minSdk = 26`，属于基础配置与需求文档冲突。必须统一改为 33，并同步修正文档中所有 `minSdk 26`、`android-26` 的描述，避免后续阶段在权限、前台服务、通知、蓝牙和存储策略上建立在错误平台假设之上。

严重程度: 高

[任务 #004]
定位锚点: `class DeviceManager(` / `class BinaryMessageCodec` / `sealed class Result<out T>` / 文档中的“使用示例”

当前代码:

```md
### DeviceManager

```kotlin
class DeviceManager(
    private val context: Context
) {
    fun discoverDevices(): Flow<List<Device>>
    suspend fun pairDevice(deviceId: String): Result<Device>
    suspend fun unpairDevice(deviceId: String): Result<Unit>
    suspend fun switchRole(role: DeviceRole): Result<Unit>
    
    val pairedDevices: StateFlow<List<Device>>
    val currentRole: StateFlow<DeviceRole>
    val connectionState: StateFlow<ConnectionState>
}
```
```

修改方案:

```md
### 状态说明

本文件中的接口定义均为“阶段规划中的目标 API 草案”，当前仓库未提交对应 Kotlin/Java/C++ 实现。
以下内容仅用于约束后续开发方向，不能视为当前可调用接口。

### DeviceManager（Planned API）

```kotlin
class DeviceManager(
    private val context: Context
)
```

```md
使用示例已移除，原因：当前仓库不存在 `DeviceManager` 的真实实现。
```
```

修改逻辑: 目前仓库里真正存在的 Kotlin 源码只有 `MainActivity.kt` 和 `SmsLinkApplication.kt`，但文档却把大量尚未实现的类、方法、返回值和示例写成“现有 API”。这会误导后续 AI 或开发者，以为这些接口已经可用。必须把 `docs/modules/CORE_API.md`、`NETWORK_API.md`、`FEATURE_API.md`、`AUDIO_API.md`、`UI_API.md` 以及各模块 `README.md` 全部改成“规划态/占位态”表述，删除伪使用示例，避免文档对实现状态撒谎。

严重程度: 高

[任务 #005]
定位锚点: `docs/modules/CORE_API.md` / `docs/modules/NETWORK_API.md` / 缺失 `CHANGELOG.md` / 缺失 `app/README.md`

当前代码:

```md
### 8. 文档
- 项目根 README.md
- 每个模块的 README.md (功能说明、依赖关系、使用示例)
- docs/protocol/PROTOCOL_SPEC.md (完整协议规范)
- docs/modules/CORE_API.md (核心模块 API)
- docs/modules/NETWORK_API.md (网络模块 API)
- docs/modules/FEATURE_API.md (功能模块 API)
- docs/modules/AUDIO_API.md (音频模块 API)
- docs/modules/UI_API.md (UI 组件 API)
- docs/development/DEVELOPMENT_GUIDE.md (开发指南)
```

修改方案:

```md
docs/
└── modules/
    ├── app/API.md
    ├── audio/API.md
    ├── core-common/API.md
    ├── core-model/API.md
    ├── core-database/API.md
    ├── core-preferences/API.md
    ├── network-protocol/API.md
    ├── network-transport/API.md
    ├── network-discovery/API.md
    ├── network-hotspot/API.md
    ├── feature-device/API.md
    ├── feature-notification/API.md
    ├── feature-call/API.md
    ├── feature-transfer/API.md
    ├── feature-settings/API.md
    └── ui/API.md

app/README.md
app/CHANGELOG.md
audio/CHANGELOG.md
core/common/CHANGELOG.md
core/model/CHANGELOG.md
core/database/CHANGELOG.md
core/preferences/CHANGELOG.md
network/protocol/CHANGELOG.md
network/transport/CHANGELOG.md
network/discovery/CHANGELOG.md
network/hotspot/CHANGELOG.md
feature/device/CHANGELOG.md
feature/notification/CHANGELOG.md
feature/call/CHANGELOG.md
feature/transfer/CHANGELOG.md
feature/settings/CHANGELOG.md
ui/CHANGELOG.md
```

修改逻辑: 当前仓库只有聚合式 API 文档，没有按模块拆分的 `API.md`，并且所有模块都缺少 `CHANGELOG.md`，`app` 模块甚至连 `README.md` 都没有。这不满足模块级交付标准，也不利于后续自动化重构。必须补齐每个模块的 README、模块级 API 文档、CHANGELOG，并统一内容结构：模块概述、当前状态、依赖、构建方式、公开接口、错误处理、版本记录。

严重程度: 中

[任务 #006]
定位锚点: `./gradlew build` / `./gradlew :app:installDebug`

当前代码:

```md
### 构建

```bash
./gradlew build
```

### 运行

```bash
./gradlew :app:installDebug
```
```

修改方案:

```md
### 构建

仓库必须提交 Gradle Wrapper 文件后，以下命令才是有效入口：

```bash
./gradlew build
```

Windows:

```powershell
.\gradlew.bat build
```
```

修改逻辑: README 明确要求使用 `./gradlew`，但仓库里没有 `gradlew`、`gradlew.bat` 和 wrapper 目录。必须补齐 Gradle Wrapper，并让 README 同时给出 Unix 和 Windows 的可执行入口。否则当前文档里的构建命令是假的，无法作为第一阶段交付说明。

严重程度: 高

3. 约束规则 (强制执行)
编码规范: 保持 Kotlin 与 Gradle Kotlin DSL 风格一致；禁止使用 `any`、伪代码式占位方法体或“仅注释不落文件”的修改；新增文档必须明确区分 `Implemented`、`Planned`、`Stub` 三种状态。

禁止变动: 严禁凭空新增尚未实现的业务功能来“迎合”现有文档；严禁修改现有包名、模块名、`applicationId`、`namespace` 结构；严禁变更对外宣称的产品目标，只能让代码与文档状态对齐。

依赖限制: 禁止引入新的运行时第三方库；允许补齐 Gradle Wrapper；在 native 音频源码未真实提交前，禁止通过下载脚本、远程 clone、Git 子模块或构建时拉取的方式隐式补依赖。

4. 验证标准
[ ] 修改后 `Manifest` 中不得再引用仓库内不存在的 `mipmap` 或 `style` 资源

[ ] 修改后仓库必须能够通过 `./gradlew :app:assembleDebug` 或 `.\gradlew.bat :app:assembleDebug`

[ ] `audio` 模块不得再触发指向不存在目录或文件的 `externalNativeBuild`

[ ] 所有 `build.gradle.kts` 中的 `minSdk` 必须统一为 `33`

[ ] `README.md`、`plan1.md`、`docs/modules/*.md` 中不得再把未实现类写成“当前可调用 API”

[ ] 每个模块都必须具备 `README.md`、`CHANGELOG.md`，并在 `docs/modules/` 下拥有对应模块的 `API.md`

[ ] 新增文档必须明确写出当前阶段状态，不能出现与真实仓库内容冲突的使用示例
