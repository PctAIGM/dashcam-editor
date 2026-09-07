# AGENTS.md — dashcam-editor

面向 AI 编码助手 / 自动化代理的项目说明。修改本仓库代码前请先读完本文。

## 项目概览

**dashcam-editor** 是 Android 视频处理工具：打点不用拖、逐帧能放大、截图原画质、跨文件裁剪一步出。

| 项 | 值 |
|---|---|
| 包名 | `com.dashcam.editor` |
| 模块 | 单模块 `:app` |
| minSdk / targetSdk / compileSdk | 24 / **35（勿升 36，见下）** / 37 |
| 语言 / UI | Kotlin + Jetpack Compose（Material 3 底座 + `ui/Theme.kt` 的 iOS 18 设计令牌） |
| 播放 | Media3 ExoPlayer 1.11（TextureView + EXACT seek） |
| 导出 | `dev.ffmpegkit-maintained:ffmpeg-kit-full-gpl:8.1.7`（FFmpeg 8.1，x264 + MediaCodec 硬编） |
| 构建 | AGP 9.4 / Gradle 9.6 / JDK 21（未用 Version Catalog，版本直接写在 `app/build.gradle.kts`） |

> **targetSdk 固定 35，勿升 36**：Android 16 对 targetSdk 36+ 的应用把媒体权限强制走相册选择器隔离模式，`MediaStore` 视频查询恒返回空（真机+模拟器均实测复现），首页网格会空白。35 下正常。

## 架构

单模块 `:app`，包路径 `app/src/main/java/com/dashcam/editor/`：

- `MainActivity.kt` — 入口：SAF / 分享导入、页面切换；configChanges 自持横竖屏状态
- `ui/` — 界面与状态：`AppModel`（全局状态）、`LibraryScreen`（首页网格、多选插入）、`EditorScreen`（编辑器主界面）、`ExportSheet`（导出面板）、`ShotsGallery`（截图画廊）、`Theme.kt`（iOS 18 设计令牌与 `Ios*` 组件）
- `media/` — `PlayerController`（ExoPlayer 封装、EXACT seek、逐帧步进）、`Probe`（片源探测）、`FfExec`（ffmpeg 异步执行）
- `export/` — `ExportEngine`（导出管线）、`MediaStoreSaver`（成品入相册）
- `shot/` `zoom/` `timeline/` `util/` — 截图、缩放平移、时间轴、时间码
- `com/arthenica/smartexception/java/Exceptions.java` — ffmpeg-kit 缺失依赖 stub，**勿删**

## 修改原则

1. **targetSdk 固定 35**，勿升 36（原因见上）。
2. **ffmpeg-kit 的坑**（已内置修复，勿删勿绕）：
   - stub 类 `com/arthenica/smartexception/java/Exceptions.java` 必须保留；
   - FFprobeKit 不可用，探测一律走 MediaMetadataRetriever + MediaExtractor；
   - ffmpeg 调用一律走 `FfExec`，不要用同步 `execute()`。
3. **导出语义**：裁剪 seek 保持「输入侧预滚 2s + 输出侧精确丢帧」；改动导出管线时保持帧精确语义与多级编解码回退链。
4. **UI 规范**：新增控件复用 `ui/Theme.kt` 的 `Ios*` 组件与令牌，不要写裸 `RoundedCornerShape(18.dp)` 之类。
5. **依赖**：新库版本直接写入 `app/build.gradle.kts`（本项目未用 Version Catalog）。
6. **范围**：只改与任务相关的代码；不顺手大重构、不改无关版本号。
7. **文档**：行为或架构变更时同步更新 `README.md` 与本文相关段落。

## 强制验证（每次修改后必须执行）

**凡是改动了可影响构建或运行时行为的文件（Kotlin / XML / Gradle / Manifest / 资源等），在本次任务结束前必须在项目根目录执行：**

```bat
.\gradlew.bat :app:assembleDebug
```

要求：

- **只需要构建 assembleDebug，不要构建 release 包**，除非任务明确要求。
- 必须使用 Windows 包装脚本 **`gradlew.bat`**（不要只写 `./gradlew`，除非当前 shell 已明确可用且等价）。
- 任务必须是 **`:app:assembleDebug`**（不要用只编译不打包的任务代替验收）。
- **构建成功（exit code 0）才算修改完成**；失败则先修编译错误再重跑，直到通过。
- 同一轮对话中有多处改动时：可在中途多次构建，但**最终交付前至少成功一次**。
- 纯文档改动（仅 `*.md`、且无代码/资源/构建脚本变更）可不跑构建；一旦触及源码、资源或 Gradle，必须跑。

推荐工作流：

1. 阅读相关文件，做最小必要修改
2. 执行 `.\gradlew.bat :app:assembleDebug`
3. 根据错误修复，再次构建
4. 在回复中说明构建结果（成功 / 失败原因与已修复点）

可选（非强制，真机联调时）：

```bat
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

产物路径：`app/build/outputs/apk/debug/app-debug.apk`。

## 环境提示

- JDK 21 路径写在 `gradle.properties` 的 `org.gradle.java.home`（本机 `D:/Java/jdk-21`）；换机需改本地路径，**不要把本机绝对路径提交进业务代码**。
- compileSdk 37 的 platform 37 是手动安装的（`platform-37.0_r02.zip` 解压到 `<sdk>/platforms/android-37`），换机器构建需同样处理。
- 命令行构建需走代理时：`GRADLE_OPTS="-Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=<port>"`。
- 真机验证优先（4K/60 硬编速度、VFR 片源行为）；模拟器可验证基础流程。

## 不该做的事

- 不要提交 `local.properties`、`.gradle/`、`build/`、`keystore/`、`testmedia/` 或个人 SDK 路径到无关文件。
- 不要把 targetSdk 升到 36。
- 不要删除 `Exceptions.java` stub、`FfExec` 异步封装或导出多级回退链。
- 不要绕过 `ui/Theme.kt` 的设计令牌写裸圆角。
- 不要主动构建 release 包，除非任务明确要求。
- 不要在未跑通 `.\gradlew.bat :app:assembleDebug` 的情况下声称“已完成修改”。

## 快速命令备忘

```bat
REM 强制验收（每次代码修改后）
.\gradlew.bat :app:assembleDebug

REM 清理后全量构建（遇到奇怪缓存问题时）
.\gradlew.bat clean :app:assembleDebug
```
