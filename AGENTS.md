# lw.HomeNAS 开发约定

- 功能优先，Windows x64 / C++17 / VS2022 是当前实际目标。不要为开源预先扩展跨平台、插件框架或复杂 CI。
- 用户自己执行提交和推送。可以初始化与配置 remote；后续只给命令，不自动 git add/commit/push/tag 或修改远程。
- 正式版本由根VERSION标识，并与core.hpp、CMake、Android versionName一致；APK生产变更另递增versionCode。main每次推送均构建包，只有尚未公开的vVERSION发布正式Release，已有公开标签附件不可改写。版本门禁不替代Windows/固定签名APK/模拟器/完整ZIP验收，不把本机准备称为已在线发布。
- 修改Actions工作流须运行tools/check-workflows.py及test_release_*check.py；SafeLoader解析成功不代表GitHub接受，重复YAML键可能被静默覆盖。checkout ref按具体job修改，不做跨整文件的模糊批量替换；select不可引用自身未来输出，package/publish仅引用已声明needs。校验工具的新Python依赖须在使用它的每个CI任务显式安装；本地结构检查仍不等于云端工作流执行通过。
- 默认相对路径按配置文件目录解析。默认配置位于 exe/config/server.json，不依赖进程工作目录。文档图片使用相对路径。
- 原件入库前必须校验大小和完整 SHA256；确认偏移只在文件刷新与数据库持久化后前移。保留提交中间状态和崩溃恢复。
- 上传采用有界流式读取，禁止 req.body / base64 承载整个大视频。所有文件长度/偏移保持64位，传到网页时使用十进制字符串。
- 手机删除、撤销设备和预览失败都不能删除服务器原件。测试只能终止测试自己启动且路径确认的进程。
- Web删除按用户选择进入可恢复回收站，不物理删除原件；用独立asset_trash标记保留全局去重及目录/图集关联。活动列表、封面、下载需一致隐藏，手机TRASHED不得误当MISSING自动恢复。批量操作权限和事务保持全有或全无；普通用户仅恢复自己删除，admin可全部。修改此链路运行recycle.py和对应smoke/folders，浏览器实走删除/恢复；不得隐式增加永久删除或自动清空。
- data、真实照片、数据库、运行日志、凭据、TLS私钥和APK签名身份不进入 Git。不要对外提供用户测试数据。
- 修改认证、上传、恢复、去重或缩略图方向后，运行对应 tests/smoke.py 检查。界面上传要用实际浏览器验证，不能仅以接口测试代替。
- 明确区分设计、已实现、自动测试通过、Android真机验证；不能把现有网页前台上传称为后台自动备份。

- 账号版本采用admin/member/viewer三角色，服务端限制所有写接口；当前资料库共享，不承诺文件隔离。schema2升级需保留原件/确认偏移并撤销旧配对凭据。不能保留配对入口作为绕过账号的后门。
- Windows服务走原生SCM入口，脚本卸载不删数据、不静默覆盖同名服务；只制作脚本时不在开发机自行安装服务。日志不得打印密码或令牌。
- deploy只保留用户指定的7个BAT（install/start/stop/restart/uninstall-service及open/remove_firewall），其余维护用PowerShell命令。安装直接调用Unicode CreateServiceW、虚拟账号密码传NULL，保留Win32原码；创建失败也必须有独立安装日志，不能只指向尚不存在的service.log。禁止transcript捕获密码、配置正文或程序输出，打包/复制运行目录使用部署文件白名单排除本地日志。新的原生安装路径未实测前不能沿用旧WMI安装的通过结论。
- CreateServiceW的1783是RPC数据错误，不等同于已确定系统损坏或防火墙拦截。仅在确认同名服务不存在时允许一次同账号/同路径sc.exe兼容尝试，其他错误不重试，保留两条路径错误；不自动切LocalSystem、关闭防火墙或采用已有服务。含嵌套引号的binPath通过无Shell进程参数转义并用真实argv回显验证，不能只检查生成字符串。
- 双路径同错后先做范围诊断，不能反复改调用器并声称修复。diagnose-service默认只读；创建探测必须显式-ProbeCreation，GUID临时名、Disabled、系统无害命令、永不启动，仅用本次成功创建返回的句柄删除。真实探测不在开发机自动执行，不碰正式服务/config/data/ACL/防火墙，不因账号对照而自动改变正式服务身份；报告可能含路径/系统事件，只存忽略目录。
- Android专用Runtime在android/app，采用Java+可信内置网页，完整APK由GitHub Actions编译；用户当前没有Android开发环境，不自行安装SDK。不得把语法检查称为Android编译或真机通过。
- Android单元测试也使用Android API桩编译；CI用JDK17不代表所有桌面JDK方法可用。读取UTF-8文件用Files.readAllBytes加显式字符集，避免Files.readString/writeString；同步运行tools/check-android.py与test_android_*check.py。
- 原生桥只接受可信本地页面主框架的固定命令；不返回令牌、不接受任意路径/URL。自签名证书导入保留主机名校验；会话用Keystore加密；APK签名身份存GitHub Secrets或仓库外，不进源码/日志。
- Android自动任务采用有限批次，手动长任务使用用户明确启动的dataSync前台服务；不通过开机广播或永久长Worker绕过系统限制。每次显示成功必须取得服务端READY，暂停/退出保留队列和原件。
- 调试采用CI构建→真机测试→导出诊断ZIP反馈；每轮APK递增versionCode，界面/日志记录版本、提交、CI编号与错误/请求关联ID。日志有界轮转，不记请求正文、密码、令牌、媒体文件名或URI，不打包真实日志。
- 用户已配置仓库四个ANDROID签名Secrets：后续分发APK始终复用这套持久签名及com.lxw.homenas包名，修改APK时递增versionCode并更新versionName，不重新生成密钥。push/手动发布缺少签名配置应失败，不退回临时签名；PR仅验证，不分发临时签名APK。收到新APK后核对证书SHA256与固定签名基准；截图仅说明配置已填写，不等于实际产物签名已核验。
- 地址草稿与已认证endpoint分开保存；失败测试不能改变Keystore密文AAD或将原队列路由到新服务器。电脑HTTP由allow_insecure_lan控制，按用户要求默认true，命令行显式开/关可覆盖；HTTPS不静默降级。
- 离线管理员密码工具必须取得相同实例锁、拒绝运行中的资料库，不启动后台恢复/媒体处理线程，不打印或保存自选明文密码。
- Android仪器测试结束后应用可能已卸载；CI采集截图前重新安装APK并解析实际Launcher，不硬编码applicationId与Activity组合。测试失败仍保留原退出码并在模拟器结束前采集日志。
- Windows控制台中文统一使用consoleText：控制台走WriteConsoleW，文件/管道/服务日志保留UTF-8。不要强制修改父控制台代码页；PowerShell交互入口避免二次解码。兼容检查可运行tests/console_encoding.py，不能将本机代码页测试称为Server 2012 R2实机通过。

- 客户端维护分为APK版本与已签名Web资源版本，原生桥协议变化须调整兼容要求。资源私钥仅保存在仓库忽略的local-signing目录或CI Secrets，源码只提交公钥；不得打进部署包。
- 修改原生协议升级/活动界面重置逻辑时，同步更新ClientMaintenanceTest的启动前提：先模拟当前APK首次启动，再测试同协议资源回退；另测旧协议升级清理指针、保留其他状态。测试不能将首次迁移返回的builtin/null当成已下载界面目录。JUnit失败摘要须展示具体用例与堆栈，完整XML保留Artifact；不得跳过失败测试放行APK。
- 防火墙脚本仅生成或验证计划，不自行在开发机开放端口；用户显式运行Open/Remove时才修改当前程序路径对应的受管规则。
- 完整包CI接续可信main分支Android成功运行，APK与模拟器job都要通过，Windows与APK固定同一提交，证书匹配android/signing-certificate.json；发布前验收实际ZIP。只公开附件齐全的草稿，已公开发布不覆盖，不自动提交版本或重建私钥。UI签名Secret可选，缺少时使用APK完整内置界面；自动UI revision使用1000000+Android CI序号。PowerShell辅助函数避免与python等外部命令同名并递归调用。

- 签名UI资源发布前必须按已分发APK的真实解包规则检查每个ZIP条目（路径/类型/数量/展开大小/重复/必要入口），不能仅验证签名和HTTP。现有APK拒绝TXT，许可证应保留全文为HTML；使用递增revision修复已发布包，不原地覆盖同版本。运行tools/check-android.py和test_android_*check.py回归。
- 所有锁定哈希针对原始字节，.gitattributes必须同时保持third_party/** -text及tools/ffmpeg/** -text，FFmpeg许可证/README也受provenance.json锁定，不只保护C/C++源码。避免Windows core.autocrlf改写导致CI校验失败；不得跳过校验、改为CRLF哈希或校验时忽略换行。发布测试须合并两份锁定清单检查所有已入库文件的Git属性，并覆盖Windows检出过滤器及篡改拒绝。
- 备份文件夹是共享资料库的逻辑关联，不是客户端磁盘路径；删除/移动目录不能删除或搬动内容原件。默认目录兼容旧文件和旧客户端。队列入队时固定folder_id，改变偏好仅作用于新任务；幂等上传不可改目标。跨目录同内容复用原件但必须补目录关联，不能因全局READY跳过。手机队列升级保留ID、client、摘要、远端任务、64位确认偏移，目录须加入队列去重键；旧目录删除后默认回退需明确显示。修改此链路运行folders.py、smoke.py和Android队列SQL/迁移检查。
- MediaStore测试夹具需分别支持集合列表与单项元数据查询：集合列表检查Bundle及SQL排序，单项URI要支持传统五参数query与Bundle重载，包含空/指定projection。MediaCatalog.describe会在真正入队前走五参数查询，不能用全局AssertionError拦截合法单项读取；修复时保留排除照片后的实际队列条数/URI断言，并分别回归元数据读取和集合旧查询拒绝。Java语法通过不等于ContentResolver重载路径已在Robolectric执行。
- Robolectric用例不能依赖Application重置或执行顺序来隔离生产静态单例。涉及TaskStore的隔离用例通过测试专用QueueFixture在@Before/@After关闭旧helper、清空单例并删除测试SQLite数据库；设置偏好也显式初始化。保留“异常操作不入队”的零条断言，在@Before先确认空库，不能把其他用例残留记录解释成生产入队错误，也不能通过调整方法顺序或放宽数量断言绕过。
- 原生协议或服务能力变更时，同时检查src/test与src/androidTest的模拟服务器：能力声明、目标目录查询、任务目录字段和来源目录成员状态必须符合当前接口。保持续传丢失确认后的偏移/字节断言及实际选图入队断言；夹具过旧应更新夹具，不能移除生产校验。收到APK Artifact仍需核实整体workflow及模拟器job状态，不能将APK构建成功当作整套发布通过。
