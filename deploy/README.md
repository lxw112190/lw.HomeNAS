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
| install_http_service.bat | 新安装服务，显式启用局域网HTTP 8787，HTTPS仍保留 |
| configure_http_service.bat | 将当前安装目录对应的已有服务切到局域网HTTP |
| configure_https_service.bat | 恢复默认：局域网HTTPS，HTTP仅本机 |
| start_console_http.bat | 显式开启局域网HTTP的前台运行方式 |
| set_admin_password.bat | 停止程序后，自选/重置管理员admin密码 |

安装/启动/停止/重启/卸载时右键“以管理员身份运行”；查看状态和前台运行通常不需要管理员权限。脚本不自动弹出UAC、不修改防火墙。

配置来自程序旁 `config/server.json`；`data_dir`、`web_dir`按配置文件所在目录解析，不依赖服务的System32工作目录。安装包内直接找到根目录NasServer.exe；源码目录则查找out/Release或build/app/Release。

服务使用 `NT SERVICE\lw.HomeNAS` 虚拟账号，安装脚本为它授予程序/网页读取执行、数据目录修改权限，不使用LocalSystem，不给Everyone完全控制。外置盘需在开机时可用；更换数据目录后应由管理员为该服务身份配置新目录权限。

首次用户名 `admin`，初始密码保存在数据目录的 `initial-admin-password.txt`；服务日志不输出密码。日志为 `data/logs/service.log`，服务重启时超过4MiB会保留一份previous日志。

## 测试时使用好记的密码

1. 已运行服务的，先执行 `stop_service.bat`；前台运行的，先正常关闭控制台程序。
2. 运行 `set_admin_password.bat`，输入两遍自己的密码，至少10位。例如可自行输入 `nas12345678`，这是示例，不是程序内置默认密码；输入时不回显。
3. 执行 `start_service.bat`，或使用相应前台入口。用户名为 `admin`。

首次启动前也可以先设置。工具只修改本机内置管理员密码、恢复其启用和管理员身份，并撤销该管理员旧会话；保留其他账号、原件和上传进度。自选密码不落明文文件；程序运行时会拒绝修改。若服务目录限制写入，可右键以管理员身份运行。命令行等价入口为 `NasServer.exe --config "配置路径" --initialize-admin`，通过交互输入密码，避免把密码放进命令参数。

## 家庭局域网HTTP

首次安装用 `install_http_service.bat`；已安装到当前目录的服务用 `configure_http_service.bat`，无需卸载或清空数据。配置脚本只调整启动参数，保留服务账号和目录权限：服务原来运行则停止、修改后重新启动，原来停止则保持停止。安装和配置需要管理员权限。

手机选择“HTTP 家庭内网”，填写电脑IP，端口8787。HTTP传输未加密，使用你信任的家庭网络。以后运行 `configure_https_service.bat` 可恢复默认模式。脚本不会修改防火墙；电脑端还需按实际网络情况允许对应端口。

排查手机问题时，手机“连接与设置 → 导出诊断ZIP”提供版本、会话、错误编号和传输阶段日志。电脑 `data/logs/requests.log` 记录UTC时间、状态及相同的trace标识，方便关联；按约2MiB轮转，保留当前文件及4份历史。不记录请求正文、密码、令牌或查询参数。启动失败仍查看service.log；日志文件不要提交Git。

先停止前台程序再启动服务，避免端口和资料库实例锁冲突。更新时停止服务，替换exe和web，保留config/data，再启动服务。已有同名服务不会被安装脚本直接删除或覆盖。程序改到其他路径时，先卸载旧服务再在新位置安装，卸载不会移动或删除数据。

可以只查看安装计划，不修改SCM、目录或ACL：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File deploy/service.ps1 -Action Install -PlanOnly
```

批处理包装与PowerShell脚本随源码和运行包一起提供。Windows服务必须通过SCM启动；直接在终端运行 `NasServer.exe --service` 会明确报1063，不会伪装服务成功。
