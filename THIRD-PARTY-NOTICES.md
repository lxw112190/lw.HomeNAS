# 第三方组件

| 组件 | 固定版本 | 来源 | 许可 |
| --- | --- | --- | --- |
| cpp-httplib | 0.64.0 | https://github.com/yhirose/cpp-httplib | MIT，见 third_party/httplib/LICENSE |
| nlohmann/json | 3.12.0 | https://github.com/nlohmann/json | MIT，见 third_party/json/LICENSE.MIT |
| SQLite amalgamation | 3.54.1 | https://www.sqlite.org/2026/sqlite-amalgamation-3540100.zip | Public Domain，保留 sqlite3.c / sqlite3.h 原始声明 |
| Mbed TLS | 3.6.7 | https://github.com/Mbed-TLS/mbedtls/releases/tag/mbedtls-3.6.7 | 上游提供 Apache-2.0 OR GPL-2.0-or-later；本项目选择 Apache-2.0，完整上游 LICENSE 随源码保留 |

依赖源文件随仓库提供；下载来源和摘要见 third_party/dependencies.lock.json。Windows WIC、BCrypt 等为系统组件。

运行包将许可文本集中放在 `licenses/`；SQLite 的原始声明随公开源码提供。

Android专用Runtime沿用[lw.Web2Android](https://github.com/lxw112190/lw.Web2Android)（MIT）的Java + WebViewAssetLoader方案，未修改上游通用打包器。新模块使用AndroidX Core1.15.0、WebKit1.15.0、WorkManager2.10.1（Apache-2.0）及OkHttp4.12.0（Apache-2.0）；其Okio/Kotlin与其他AndroidX传递依赖由Gradle解析，CI保存runtime-dependencies报告。测试依赖JUnit4.13.2（EPL-1.0）、Robolectric4.14.1（MIT）、AndroidX Test（Apache-2.0）及MockWebServer/okhttp-tls4.12.0（Apache-2.0），不作为测试工具一并注入正式APK。

Android APK内置许可说明与Apache-2.0全文，见android/app/src/main/assets/www/licenses。未包含Android SDK、Google Build Tools或任何签名私钥。各组件版权与许可仍归原作者；以实际Gradle构建解析结果为准。

CI9 Windows 完整部署包新增独立命令行工具 FFmpeg 7.1（Gyan essentials Windows x64，来自 imageio-ffmpeg 0.6.0 wheel），仅用于视频封面截帧。该实际构建启用 GPL 和 version3，许可为 GPL-3.0-or-later，不将其误标为 LGPL-only。原始 EXE 未修改，版本/构建参数、GPLv3全文、来源/摘要记录和 FFmpeg 7.1 上游源码归档位于 `tools/ffmpeg/`；FFmpeg 和各启用组件版权归各自作者。上游说明：[FFmpeg](https://ffmpeg.org/)、[Gyan builds](https://www.gyan.dev/ffmpeg/builds/)、[ImageIO 分发](https://github.com/imageio/imageio-ffmpeg/tree/v0.6.0)。

未集成 AI 模型。

开发/CI的DOM交互测试使用jsdom 26.1.0（MIT，https://github.com/jsdom/jsdom），依赖锁定于tests/dom/package-lock.json；不进入生产APK或Windows部署包。

1.0.1新增AndroidX Media3 ExoPlayer/UI 1.5.1（Apache-2.0，The Android Open Source Project），用于APK原生音视频播放。沿用APK内Apache-2.0全文，版本与传递依赖由CI记录。源码：https://github.com/androidx/media/tree/1.5.1 。
