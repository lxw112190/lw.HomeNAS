# 视频封面依赖

将可信 Windows x64 的 `ffmpeg.exe` 及对应许可证放在本目录。VS2022/CMake 编译和打包脚本会在存在 EXE 时复制整个目录到运行目录的 `tools/ffmpeg`。

默认配置 `ffmpeg_path` 为 `../tools/ffmpeg/ffmpeg.exe`，相对于 `config/server.json`。也可指定自己的相对路径，空字符串禁用；不搜索系统 PATH。

CI9 完整部署包已包含经实测的 FFmpeg 7.1 Windows x64 二进制，原始来源为 imageio-ffmpeg 0.6.0 的 Windows wheel，构建标识为 Gyan essentials。没有修改或重新编译该 EXE。源码 Git 不提交 EXE 和源码压缩归档；部署时一起保留整个目录。

随包包含 GPLv3 全文、版本/构建参数、来源与 SHA256 记录 `provenance.json`、上游 FFmpeg 7.1 源码压缩包及 ImageIO 分发许可。FFmpeg 本身及其启用的组件依实际构建许可证分发；它作为独立命令行工具被 NAS 调用。

重启 NAS 后，已有视频缺少的封面会逐个补齐。截帧失败不会修改原件或撤销 READY。本功能不对完整视频做兼容转码。
