# Windows 服务批处理

参考 [lw.OpenCVDNN.PPOCR/server/deploy](https://github.com/lxw112190/lw.OpenCVDNN.PPOCR/tree/main/server/deploy) 的入口组织，提供：

| 文件 | 用途 |
| --- | --- |
| install_service.bat | 安装为自动启动的 lw.HomeNAS 服务并启动 |
| start_service.bat | 启动服务，等待 Running |
| stop_service.bat | 停止服务，等待 Stopped，不强制杀进程 |
| restart_service.bat | 停止完成后启动，避免固定延时造成竞态 |
| status_service.bat | 查看状态、可执行路径、服务账号与退出代码 |
| uninstall_service.bat | 停止并卸载服务，保留所有数据和程序文件 |
| start_console.bat | 前台运行，便于查看启动错误及初始账号密码 |

安装/启动/停止/重启/卸载时右键“以管理员身份运行”；查看状态和前台运行通常不需要管理员权限。脚本不自动弹出UAC、不修改防火墙。

配置来自程序旁 `config/server.json`；`data_dir`、`web_dir`按配置文件所在目录解析，不依赖服务的System32工作目录。安装包内直接找到根目录NasServer.exe；源码目录则查找out/Release或build/app/Release。

服务使用 `NT SERVICE\lw.HomeNAS` 虚拟账号，安装脚本为它授予程序/网页读取执行、数据目录修改权限，不使用LocalSystem，不给Everyone完全控制。外置盘需在开机时可用；更换数据目录后应由管理员为该服务身份配置新目录权限。

首次用户名 `admin`，初始密码保存在数据目录的 `initial-admin-password.txt`；服务日志不输出密码。日志为 `data/logs/service.log`，服务重启时超过4MiB会保留一份previous日志。

先停止前台程序再启动服务，避免端口和资料库实例锁冲突。更新时停止服务，替换exe和web，保留config/data，再启动服务。已有同名服务不会被安装脚本直接删除或覆盖。程序改到其他路径时，先卸载旧服务再在新位置安装，卸载不会移动或删除数据。

可以只查看安装计划，不修改SCM、目录或ACL：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File deploy/service.ps1 -Action Install -PlanOnly
```

批处理包装与PowerShell脚本随源码和运行包一起提供。Windows服务必须通过SCM启动；直接在终端运行 `NasServer.exe --service` 会明确报1063，不会伪装服务成功。
