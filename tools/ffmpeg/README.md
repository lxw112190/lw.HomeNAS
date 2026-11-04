# 视频封面依赖

将可信 Windows x64 的 `ffmpeg.exe` 及对应许可证放在本目录。VS2022/CMake 编译和打包脚本会在存在 EXE 时复制整个目录到运行目录的 `tools/ffmpeg`。

默认配置 `ffmpeg_path` 为 `../tools/ffmpeg/ffmpeg.exe`，相对于 `config/server.json`。也可指定自己的相对路径，空字符串禁用；不搜索系统 PATH。

本轮源码未捆绑 FFmpeg 二进制，集成测试使用 `test-results` 内隔离的测试依赖，不会被打包。正式分发时保留所选构建附带的许可证、版本和源代码获取说明。

重启 NAS 后，已有视频缺少的封面会逐个补齐。截帧失败不会修改原件或撤销 READY。本功能不对完整视频做兼容转码。
