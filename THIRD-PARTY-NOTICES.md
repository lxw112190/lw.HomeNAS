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

当前没有集成 FFmpeg 或 AI 模型。后续增加时单独记录版本与分发内容。
