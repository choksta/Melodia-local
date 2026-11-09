<div align="center">
  <img src="docs/images/cover.png" alt="Melodia" />

参考Spotify UI构建的现代化、轻量级第三方网易云音乐客户端。


[![GitHub Release](https://img.shields.io/github/v/release/choksta/Melodia-local?style=flat-square&color=blue)](https://github.com/choksta/Melodia-local/releases)
[![Platform](https://img.shields.io/badge/Platform-Android%208.0%2B-green?style=flat-square)](https://github.com/choksta/Melodia-local/releases)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.x-purple?style=flat-square)](https://kotlinlang.org)
[![License](https://img.shields.io/badge/License-MIT-green?style=flat-square)](LICENSE)

[官网](https://rinchao0721.github.io/Melodia/) · [插件格式](https://rinchao0721.github.io/Melodia/docs/plugin-format/) · [皮肤格式](https://rinchao0721.github.io/Melodia/docs/skin-format/)
</div>

---

> **本仓库为非官方修改版**
>
> 本项目基于 [rinchao0721/Melodia](https://github.com/rinchao0721/Melodia)（MIT License）修改而来，
> 与原作者无关，不代表其立场，也**不是官方发布渠道**。原项目的版权与署名归原作者所有，详见 [LICENSE](LICENSE)。
>
> 相对上游的改动说明见 [Releases](https://github.com/choksta/Melodia-local/releases)。

## 与上游的差异

本项目代码主体来自上游，仓库同步于上游 `v2.1.0-beta.1`（`732c1b23`）及其后续修正。下面这些是本仓库自己的改动。

### 发布与更新

| 改动 | 说明 |
|---|---|
| **独立包名** | `com.choksta.melodia`，可与官方版并存安装、互不覆盖 |
| **应用内更新** | 更新源指向本仓库 Releases；APK 下载走国内镜像多源并发探测，失败回落官方直连，支持断点续传 |
| **直观版本号** | 从 `2.1.0-beta.1-local53` 改为 `2.1.56` 这种形式，版本号、APK 文件名、应用内显示三处始终一致 |
| **发版门禁** | 构建前比对签名证书指纹与历史一致、版本号严格递增，不符直接失败，不会发出一个装不上的包 |
| **更新日志** | 支持手写用户视角说明（`.github/release-notes/<tag>.md`），缺省时按提交自动生成 |

### 播放与音频

| 改动 | 说明 |
|---|---|
| **自研 PCM 均衡器** | 不再调用系统 `AudioEffect`，改为在解码后的 PCM 上做均衡。切换预设、开关音效都不再有「啪」的爆音；12 档预设 + 五段手动调节 + 独立低音增强，抬升类预设自动做曲线峰值预衰减，配合 2ms 预读限压器避免削顶 |
| **私人 FM** | 侧栏入口。没有独立页面，直接复用全屏播放页，来源条显示「私人 FM」，控制条左侧换「不喜欢」、右侧换「换一批」 |
| **切歌交叉淡化** | 相邻曲目交叠过渡，时长可调；与硬件音频解码互斥，避免两台播放器争用卸载通道 |
| **拔出耳机暂停** | 有线拔出、蓝牙断开、收回耳机盒都会暂停。同时监听系统广播与输出设备变化（部分机型不发广播），只在「一个收听设备都不剩」时触发，耳机互切不误打断；可在设置里关闭 |

### 界面与交互

| 改动 | 说明 |
|---|---|
| **侧边栏重写** | 改用 Material3 `ModalNavigationDrawer`，过渡压到 150ms；菜单跳转不再闪回主页，动画期间就能点中 |
| **侧栏明暗切换** | 「设置和隐私」上方新增一键切换主题 |
| **播放页过渡** | 封面左右推入切歌，歌名与歌手交叉淡入；缓冲等待时大播放按钮外圈显示转环，不再像点了没反应 |
| **搜索框配色** | 修正发现态搜索框取色反用导致的「浅色纯黑 / 深色纯白」 |
| **移除关于页** | 独立关于页删除，设置里只保留「检查更新」入口 |

### 性能与体积

| 项目 | 改进 |
|---|---|
| 安装包体积 | 20.95 MB → **18.52 MB**（−12%） |
| 手动切歌出声 | 1.0~1.3 秒 → **0.19 秒** |
| 冷启动 | 去掉起播路径上的同步读盘，不再阻塞出声 |

其余功能、架构设计、使用说明与构建文档均来自上游项目，本仓库未作改动。

## 截图预览

### 手机

<div align="center">
  <img src="docs/screenshots/home.webp" width="19%" alt="首页" />
  <img src="docs/screenshots/library.webp" width="19%" alt="音乐库" />
  <img src="docs/screenshots/player.webp" width="19%" alt="播放器" />
  <img src="docs/screenshots/lyrics.webp" width="19%" alt="歌词" />
  <img src="docs/screenshots/profile.webp" width="19%" alt="个人主页" />
</div>

### 平板

<div align="center">
  <img src="docs/screenshots/tablet-portrait-home.webp" width="32%" alt="平板竖屏首页" />
  <img src="docs/screenshots/tablet-portrait-panel.webp" width="32%" alt="平板竖屏播放侧栏" />
</div>
<div align="center">
  <img src="docs/screenshots/tablet-landscape-panel.webp" width="32%" alt="平板横屏播放侧栏" />
  <img src="docs/screenshots/tablet-fullscreen-lyrics.webp" width="32%" alt="平板全屏播放页歌词" />
  <img src="docs/screenshots/tablet-fullscreen-info.webp" width="32%" alt="平板全屏播放页歌曲信息" />
</div>

### 桌面端

<div align="center">
  <img src="docs/screenshots/desktop-home.webp" width="49%" alt="桌面端首页" />
  <img src="docs/screenshots/desktop-library.webp" width="49%" alt="桌面端音乐库" />
</div>
<div align="center">
  <img src="docs/screenshots/desktop-lyrics.webp" width="32%" alt="桌面端歌词" />
  <img src="docs/screenshots/desktop-artist.webp" width="32%" alt="桌面端歌手页" />
  <img src="docs/screenshots/desktop-library-compact.webp" width="32%" alt="桌面端音乐库（紧凑）" />
</div>

---

## 下载与反馈

本仓库只发布 **Android APK**，不提供 Windows 桌面端安装包（桌面端需按下方「构建与开发」自行编译）。

- **安装包下载**：前往 [Releases](https://github.com/choksta/Melodia-local/releases) 获取最新 APK
- **应用内更新**：设置 → 关于与更新 → 检查更新，可自动发现新版本并在应用内下载安装
- **缺陷与建议**：[Issues](https://github.com/choksta/Melodia-local/issues)
- **安装说明**：包名为 `com.choksta.melodia`，与官方版可并存安装；覆盖安装不会清除数据

---

## 插件

插件系统与插件规范来自上游项目，本仓库同步支持：

- **插件仓库**：[rinchao0721/melodia-plugins](https://github.com/rinchao0721/melodia-plugins)
- **在线浏览与下载**：<https://rinchao0721.github.io/melodia-plugins/>
- **插件规范**：[docs/plugin-format.md](docs/plugin-format.md)；目录格式见 [docs/plugin-catalog.md](docs/plugin-catalog.md)

---
## 项目架构

项目由三个 Gradle 模块组成，网易云接口、加密、数据仓储与大部分 ViewModel 只在 `shared` 中维护一份，Android 与桌面端各自负责界面和平台能力：

| 模块 | 类型 | 职责 |
|---|---|---|
| `shared` | Kotlin Multiplatform（Android + 桌面 JVM） | 数据模型、网络与原生加密、各业务域 `data` / `domain`、跨平台 ViewModel 与展示逻辑、播放器抽象 `PlaybackController` |
| `app` | Android 应用 | Jetpack Compose 界面、Media3 播放、下载、本地音乐、识曲、悬浮与系统歌词等 Android 专属能力 |
| `desktopApp` | Compose Desktop 应用（Windows） | 桌面窗口与界面、libmpv 播放、托盘、系统媒体控制（SMTC）与全局快捷键、桌面歌词；系统能力经平台接口接入 |

代码按**业务域 (Feature-Driven)** 组织：`core` 承载全局共享基础设施与通用能力，`feature` 下每个业务域自持 `data` / `domain` / `ui` 结构。依赖方向单向收敛——`feature` 依赖 `core`，`core` 不反向依赖 `feature`，各业务域之间解耦无循环依赖；`app` 与 `desktopApp` 都依赖 `shared`，两者互不依赖。

```text
shared/src/
├── commonMain/kotlin/com/lin0721/linmusic/
│   ├── core/                    # api、auth、network、model、preferences、player 抽象、format 格式化等共享基础能力
│   ├── feature/                 # 各业务域的 data / domain、跨平台 ViewModel（ui）与展示逻辑（presentation）
│   └── di/                      # 网络与仓储的 Koin 模块，两端共用
├── androidMain/                 # Context 相关实现（DataStore 落盘、网络状态、音乐库偏好等）
└── desktopMain/                 # 桌面 JVM 平台实现（日志、设备信息等）

app/src/main/java/com/lin0721/linmusic/
├── MelodiaApplication.kt        # 应用程序入口：Koin 依赖注入初始化与 Coil 预热
├── MainActivity.kt              # 单 Activity 架构：系统窗口适应与悬浮窗权限引导
├── MelodiaApp.kt                # 顶层组合：导航骨架编排与播放器图层联动
├── MelodiaNavHost.kt            # 核心路由分发与平滑转场动画配置
├── MelodiaOverlays.kt           # 全屏播放器底栏、底部弹窗与全局反馈浮层
├── MelodiaNavigationState.kt    # 自定义导航回退栈管理与参数上下文恢复
├── MelodiaPlayerSheetState.kt   # 全屏播放器展开/收起手势状态机
├── MelodiaSidebarState.kt       # 侧边栏抽屉滑动状态机
├── core/                        # Android 专属基础能力（Media3 播放引擎、下载、Material 3 主题与组件、应用内更新等）
├── di/                          # Android 侧 Koin 模块（Local, Player, Download, Update, ViewModel 等）
└── feature/                     # 各业务域的 Compose 界面与 Android 专属 ViewModel（本地音乐、云盘、识曲等）

desktopApp/src/main/kotlin/com/lin0721/linmusic/desktop/
├── Main.kt                      # 应用入口：Koin 初始化、主窗口、托盘、全局快捷键与桌面歌词窗口
├── di/                          # Koin 模块、按系统选择平台实现、构造界面所需的依赖集合
├── app/                         # 启动基础设施：日志、崩溃处理、单实例、数据目录、缓存迁移、窗口位置
├── data/                        # 桌面偏好、音乐库偏好、下载、图片加载等数据层实现
├── platform/                    # 系统能力接口（媒体会话、全局热键、开机自启、窗口效果、浏览器查找）
│   ├── windows/                 # Windows 实现：Win32 热键、SMTC 桥接、DWM 窗口样式、注册表
│   └── fallback/                # 尚未适配系统的空实现，界面据此隐藏对应入口
├── player/                      # libmpv 的 JNA 绑定与 PlaybackController 实现
└── ui/
    ├── shell/                   # 应用壳：界面状态、两侧栏布局、路由到页面的注册表、标题栏与播放栏接线
    ├── navigation/              # 浏览器式返回栈与按栈帧保存页面现场
    ├── components/ · track/     # 通用组件与歌曲行、歌曲操作
    ├── home/ · playlist/ · …    # 按功能分包的页面（歌手、搜索、播客、个人主页、音乐库、设置等）
    └── player/ · nowplaying/ · lyricsview/  # 播放条与队列、正在播放侧栏、全屏歌词

desktopApp/native-src/smtc/      # SMTC 桥接 DLL 源码（C++/WinRT，CMake 构建）
```

桌面端界面只依赖平台接口与 `shared` 中的 ViewModel：依赖在 `Main.kt` 构造一次后显式向下传递，界面层不直接访问 Koin；接入新的桌面系统时，只需在 `platform/` 下补一套接口实现并在 `di/PlatformModule.kt` 中按系统选择。

---


## 统一错误处理

Repository 边界统一产出 Kotlin `Result<T>`，异常类型抽象为领域错误模型 `AppError`（包括网络中断、风控拦截、鉴权过期、数据解析失败及业务错误码）。UI 表现层针对不同错误语义分流呈现对应的重试引导或提示。

---

## 构建与开发

### 环境要求

- **JDK**：Java 21（推荐 Eclipse Temurin 21）
- **Gradle**：9.3+（项目内置 Gradle Wrapper 9.3.1）
- **Android Gradle Plugin (AGP)**：9.1.1+
- **Android SDK**：Compile / Target SDK 36，Min SDK 26 (Android 8.0+)
- **IDE**：Android Studio Ladybug (2024.2.1) 或更高版本
- **桌面端（仅在需要运行或打包时）**：
  - 64 位 Windows 10 及以上
  - `libmpv-2.dll`：使用定制的精简音频版（[mpv-winbuild-cmake](https://github.com/rinchao0721/mpv-winbuild-cmake) 的 `AUDIO_ONLY` 构建），运行或打包前由 Gradle 按 `gradle.properties` 中固定的 Release 自动下载到 `desktopApp/native/` 并校验 SHA256（不入库）；也可单独执行 `./gradlew :desktopApp:downloadLibmpv`
  - SMTC 桥接 DLL 由 Gradle 在运行与打包前自动调用 CMake 编译，需安装 CMake 与 Visual Studio（含“使用 C++ 的桌面开发”工作负载及 Windows 10/11 SDK）；本地缺少时跳过编译，运行时退回全局媒体键（发版构建传 `-PrequireSmtc=true`，编不出来直接失败）
  - 打包安装程序需安装 [WiX Toolset 3.14](https://github.com/wixtoolset/wix3/releases) 并将其 `bin` 目录加入 `PATH`（用于生成内嵌在 `setup.exe` 中的 MSI），安装与卸载程序同样由 CMake 编译；打包所用 JDK 21 由 Gradle 工具链自动下载

### 常用命令

- **Android 调试构建**：
  ```bash
  ./gradlew :app:assembleDebug
  ```
- **运行单元测试**（`shared` 的测试跑在桌面 JVM 目标上）：
  ```bash
  ./gradlew :app:testDebugUnitTest :shared:desktopTest :desktopApp:test
  ```
- **构建 Android Release 安装包**：
  ```bash
  ./gradlew :app:assembleRelease
  ```
- **运行桌面端**：
  ```bash
  ./gradlew :desktopApp:run
  ```
- **打包桌面端**（发布版启用 ProGuard 裁剪，产物位于 `desktopApp/build/compose/binaries/main-release/`）：
  ```bash
  ./gradlew :desktopApp:packageReleaseZip :desktopApp:packageReleaseSetup
  ```

### 混淆与签名说明

- **代码裁剪与混淆**：`release` 构建已开启 R8 压缩与混淆保护。项目对 Retrofit 接口与 `@Serializable` 数据传输类配置了显式 Keep 规则，确保混淆后的运行安全。
- **签名机制**：签名材料从版本控制外部注入（读取 `local.properties` 或 CI 环境变量）。若未配置正式签名，将自动回退为 Debug 签名，确保本地可编译出可运行的 APK。完整签名与自动化发版配置请参阅 [RELEASE_SIGNING.md](RELEASE_SIGNING.md)。
- **CI 流水线**：向 `main` 推送或提交 PR 时运行 `app`、`shared` 与 `desktopApp` 的单元测试与编译检查；推送版本 Tag 时只构建 Android 正式签名 APK 并发布到 GitHub Release（本仓库不发布桌面端安装包）。发版流程与签名约束详见 [RELEASE_SIGNING.md](RELEASE_SIGNING.md)。

---

## 非常感谢

- [NeteaseCloudMusicApi](https://github.com/binaryify/NeteaseCloudMusicApi) - 网易云音乐 API 分析与实现参考
- [api-enhanced](https://github.com/NeteaseCloudMusicApiEnhanced/api-enhanced) - 增强接口与加密逻辑参考
- [node-vibrant](https://github.com/Vibrant-Colors/node-vibrant) - 取色算法参考
- [SPlayer](https://github.com/SPlayer-Dev/SPlayer) - 现代流媒体架构设计启发
- [Mei](https://github.com/ljyh223/Mei) — QQ 音乐歌词解析参考
- [AMLL 歌词库](https://github.com/amll-dev/amll-ttml-db) - TTML 逐字歌词、对唱与背景和声歌词来源
- [Spotify](https://spotify.com) - 优秀的移动端流媒体交互范式与 UI/UX 体验灵感
- [SuperLyric](https://github.com/HChenX/SuperLyric) - 系统级实时歌词协议与 AIDL 规范
- [LyricInfo](https://github.com/limczhh/LyricInfo) - 蓝牙/系统歌词协议规范
- [Lyricon](https://github.com/proify/Lyricon) - 词幕协议广播规范
- [mpv](https://github.com/mpv-player/mpv) - 桌面端音频播放内核 libmpv
- [media-kit](https://github.com/media-kit/media-kit) - 精简的纯音频 libmpv 构建
- [Compose Multiplatform](https://github.com/JetBrains/compose-multiplatform) - 跨平台共享层与桌面端界面框架
- [JNA](https://github.com/java-native-access/jna) - 桌面端调用 libmpv 与 Win32 接口
- [TagLib](https://github.com/Kyant0/taglib) - 本地音乐标签读写

---

## 免责声明

1. 本项目为个人技术交流与 Android 现代原生架构学习研究所用。
2. 本项目不包含任何商业目的，不提供任何形式的商业变现或增值服务。
3. 软件中调用的所有音频及元数据均来源于公开网络接口，版权归属于原权利人所有。请在下载安装后 24 小时内删除，严禁用于任何商业用途。
