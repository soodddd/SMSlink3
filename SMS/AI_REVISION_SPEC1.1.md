AI 代码修改指令集
1. 概览
文件路径: `gradlew.bat`, `gradlew`, `gradle/wrapper/gradle-wrapper.jar`, `gradle/wrapper/gradle-wrapper.properties`

主要任务: 修复 Gradle Wrapper 文件集失配导致的仓库无法构建问题。

技术栈: Android + Kotlin + Gradle Kotlin DSL

2. 修改任务清单
[任务 #001]
定位锚点: `set CLASSPATH=%APP_HOME%\gradle\wrapper\gradle-wrapper.jar` / `org.gradle.wrapper.GradleWrapperMain`

当前代码:

```bat
set CLASSPATH=%APP_HOME%\gradle\wrapper\gradle-wrapper.jar

"%JAVA_EXE%" %DEFAULT_JVM_OPTS% %JAVA_OPTS% %GRADLE_OPTS% "-Dorg.gradle.appname=%APP_BASE_NAME%" -classpath "%CLASSPATH%" org.gradle.wrapper.GradleWrapperMain %*
```

修改方案:

```bash
# 在仓库根目录重新生成并提交一整套彼此匹配的 Gradle Wrapper 文件
gradle wrapper --gradle-version 8.7 --distribution-type bin

# 需要一并更新并提交以下产物
# - gradlew
# - gradlew.bat
# - gradle/wrapper/gradle-wrapper.jar
# - gradle/wrapper/gradle-wrapper.properties
```

修改逻辑: 当前仓库执行 `.\gradlew.bat assembleDebug` 会在 Wrapper 启动阶段直接报错 `NoClassDefFoundError: org/gradle/wrapper/IDownload`，说明提交的 Wrapper 脚本与 `gradle-wrapper.jar` 不是同一套可用产物。该问题会阻断任何开发者在干净环境下执行构建、测试和安装，必须通过重新生成并提交完整且匹配的 Wrapper 文件集修复，禁止只替换其中单个文件。

严重程度: 阻断

3. 约束规则 (强制执行)
编码规范: 禁止手工修改生成后的 Wrapper 启动逻辑；必须使用 Gradle 官方 `wrapper` 任务生成文件。

禁止变动: 严禁修改现有模块结构、包名、`applicationId`、`namespace` 或业务代码来绕过该问题。

依赖限制: 禁止新增任何运行时依赖；仅允许更新 Gradle Wrapper 相关文件。

4. 验证标准
[ ] 修改后必须能在仓库根目录成功执行 `.\gradlew.bat :app:assembleDebug`

[ ] 修改后再次执行 `.\gradlew.bat --version` 不得再出现 `NoClassDefFoundError` 或其他 Wrapper 启动异常

[ ] 提交结果必须同时包含 `gradlew`、`gradlew.bat`、`gradle/wrapper/gradle-wrapper.jar`、`gradle/wrapper/gradle-wrapper.properties` 的匹配版本
