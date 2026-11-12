# Windows 服务部署

部署目录只提供 7 个 BAT，安装/启停/卸载/防火墙操作均需右键“以管理员身份运行”。已解压的旧包需使用本轮脚本才能获得新诊断功能。

| 文件 | 用途 |
| --- | --- |
| install-service.bat | 按配置安装自动启动服务并立即启动 |
| start-service.bat | 启动服务，等待 Running |
| stop-service.bat | 停止服务，等待 Stopped |
| restart-service.bat | 等停止完成后再启动 |
| uninstall-service.bat | 卸载服务，保留数据、配置和证书 |
| open_firewall.bat | 按当前程序路径及配置端口开放防火墙 |
| remove_firewall.bat | 删除本程序路径对应的受管防火墙规则 |

旧版下划线服务脚本及单独的 HTTP/HTTPS、状态、前台、密码和 APK 导入 BAT 已合并或移除；不要再按历史文章的旧文件名查找。HTTP 开关统一由 EXE 旁 `config/server.json` 的 `allow_insecure_lan` 控制，默认 true，HTTPS 同时保留。默认 HTTP 8787、HTTPS 9443，改配置后重启服务。既有服务若带显式 `--allow-insecure-lan` 参数，可用下文 Configure 操作移除覆盖，让它跟随配置。

## 安装和更新

1. 完整解压，保留程序、config、web、deploy 等相对目录。
2. 先关闭正在前台运行的 NAS，避免端口和资料库实例锁冲突。
3. 右键 `install-service.bat`，选择“以管理员身份运行”。首次安装后服务自动启动，以后开机启动。
4. 网页登录后修改初始密码。部署包首次账号为 admin/admin123；已有账号不会重置。

安装器拒绝覆盖同名服务。更新时停止服务，替换程序、web 和 deploy，保留 config/data，再启动；迁移安装路径时先卸载旧服务再安装。卸载不删除原件。

服务使用 `NT SERVICE\lw.HomeNAS` 虚拟账号，不切换为 LocalSystem。程序目录授予读取执行，数据目录授予修改。安装使用 Unicode 原生 CreateServiceW 接口，保留准确的 Win32 错误码；服务异常退出配置 5 秒、15 秒重启恢复。创建后若后续步骤失败，保留服务供排查，不自动删除。

## 日志与故障排查

- 安装/服务管理脚本：`deploy/logs/service-install.log`，尚未启动 EXE 也会写日志，记录阶段、系统/PowerShell、账号及错误。
- deploy 不可写时回退到 `%TEMP%/lw.HomeNAS-deploy/service-install.log`，窗口显示实际路径。
- 安装日志达到 1 MiB 后轮转，保留当前文件和 3 份历史。不记录配置正文、密码、令牌或原生程序输出；前台运行/改密码不使用 transcript。
- EXE 启动后：`data/logs/service.log`；请求日志为 `data/logs/requests.log`。
- `CreateServiceW` 阶段失败表示程序尚未启动，此时没有 service.log 正常，应查看安装日志。

旧截图的 `Win32_Service code 8` 是 WMI 通用失败，不能仅凭它判断是系统版本、账号或权限问题。新的具体错误码有助于确认根因；Windows 10 教育版失败机器仍需实际复测。本机非安装检查不等于目标电脑安装成功。

r2针对新反馈的 `CreateServiceW 1783` 增加一次 `sc.exe` 兼容尝试：只在确认同名服务不存在后执行，继续使用相同路径、自动启动及虚拟账号，省略密码参数。若两条路径均失败，安装退出并保留两次结果。1783是Windows的RPC数据错误，不能由此断定防火墙或某个系统组件损坏；脚本不会关闭保护功能、修改RPC服务或换成LocalSystem。请使用窗口显示的最新安装日志继续定位。

r3：用户已确认原生接口与系统sc.exe都失败1783。无需继续反复安装，先在管理员PowerShell执行以下命令；第一条只读，第二条额外执行账号对照探测：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File deploy/diagnose-service.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File deploy/diagnose-service.ps1 -ProbeCreation
```

报告位于窗口显示的 `deploy/logs/service-diagnostics-*.json`，不可写时回退临时目录。报告包含系统组件版本、RPC/防火墙服务状态与最近2小时最多12条SCM错误事件；可能含本机路径/账号，分享前按需脱敏，不提交Git。

ProbeCreation只创建两个GUID命名的禁用临时服务，分别使用虚拟账号、LocalService，对应无害系统命令；不启动、不调用NAS EXE、不改config/data、ACL或系统策略。仅通过本次创建成功返回的句柄请求删除，不按名称删除既有服务。报告Created/Deleted/DeleteCode显示结果；若RPC返回失败但系统实际残留了同名探测服务，报告保留随机名称供检查，不自动接管删除。

虚拟账号失败而LocalService成功时，可进一步评估账号兼容；两者都失败时，需要继续排查系统SCM/RPC或系统拦截环境。这是范围判断，不能单凭错误码确定某个安全产品或系统组件故障。r3没有自动切换正式NAS服务账号。

## 进阶维护命令

在部署包根目录打开 PowerShell；进阶功能不再各配一个 BAT：

```powershell
# 查询状态
powershell -NoProfile -ExecutionPolicy Bypass -File deploy/service.ps1 -Action Status
# 前台调试（输出直接继承控制台）
powershell -NoProfile -ExecutionPolicy Bypass -File deploy/service.ps1 -Action Console
# 停止 NAS 后重置管理员密码，按提示输入
powershell -NoProfile -ExecutionPolicy Bypass -File deploy/service.ps1 -Action AdminPassword
# 让既有服务跟随配置中的 HTTP 开关（需管理员，会按原状态重启）
powershell -NoProfile -ExecutionPolicy Bypass -File deploy/service.ps1 -Action Configure
# 仅查看安装计划，不改 SCM、ACL 或防火墙
powershell -NoProfile -ExecutionPolicy Bypass -File deploy/service.ps1 -Action Install -PlanOnly
# 可选兼容维护入口：停止 NAS 后手动导入/重新上架包内 APK/资源（正常启动已自动发布，无需此步）
powershell -NoProfile -ExecutionPolicy Bypass -File deploy/import_android.ps1
# 查看防火墙规则
powershell -NoProfile -ExecutionPolicy Bypass -File deploy/firewall.ps1 -Action Status
```

脚本只在用户执行 Open 时开放防火墙，不自动修改云安全组。配置的 data_dir/web_dir 相对于配置文件目录解析，不依赖 System32 工作目录。历史实测与现版本验证应分开阅读：[本轮记录](../docs/20261106-服务安装诊断与七个部署入口.md)。
