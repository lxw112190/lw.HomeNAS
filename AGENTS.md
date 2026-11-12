# lw.HomeNAS 开发约定

- 功能优先，Windows x64 / C++17 / VS2022 是当前实际目标。不要为开源预先扩展跨平台、插件框架或复杂 CI。
- 用户自己执行提交和推送。可以初始化与配置 remote；后续只给命令，不自动 git add/commit/push/tag 或修改远程。
- 正式版本由根VERSION标识，并与core.hpp、CMake、Android versionName一致；APK生产变更另递增versionCode。main每次推送均构建包，只有尚未公开的vVERSION发布正式Release，已有公开标签附件不可改写。版本门禁不替代Windows/固定签名APK/模拟器/完整ZIP验收，不把本机准备称为已在线发布。
- 修改Actions工作流须运行tools/check-workflows.py及test_release_*check.py；SafeLoader解析成功不代表GitHub接受，重复YAML键可能被静默覆盖。checkout ref按具体job修改，不做跨整文件的模糊批量替换；select不可引用自身未来输出，package/publish仅引用已声明needs。校验工具的新Python依赖须在使用它的每个CI任务显式安装；本地结构检查仍不等于云端工作流执行通过。
- Windows发布脚本要按CI实际的pwsh与部署现场Windows PowerShell 5.1分别验证；Add-Type自PowerShell 7.1起不支持ConsoleApplication/WindowsApplication。需要真实argv回显EXE时，用Windows自带.NET Framework csc.exe生成，不通过跳过执行断言或只换旧Shell掩盖问题。
- APK媒体预览按可用视口分配固定头部/操作区及可收缩媒体区，不把多个vh高度与固定按钮高度相加。至少检查320px窄屏与横屏、长文件名、元数据展开、缩放及关闭；元数据可独立滚动，外层不能靠overflow:hidden裁掉仍需使用的按钮。资源布局验证不等于Android真机通过。
- 媒体错误码4不等同于已确定编码不支持，先核对请求路由/MIME/Range和真实编码。旧MP3可能category=file，应按audio MIME识别。元数据桥与NativeApi固定路由白名单同步修改并执行真实MockWebServer断言。原生播放仅接受已加载资料库ID，复用认证/证书校验，禁止把任意URL或令牌发给JS/外部播放器；关闭/换账号释放流，测试精确Range、拒绝错误206及注销。真实HEVC设备支持只能由新APK真机验证，不能拿浏览器或语法检查代替。
- Media3 ProgressiveMediaPeriod默认在DataSpec添加Icy-MetaData: 1，包括普通MP3/MP4；自定义数据源不能一概拒绝非空请求头。可接受并忽略这个提示，但不转发外来头，认证与Range仍由NativeApi生成，继续拒绝任意URI、方法、正文和其他头。Robolectric夹具应包含真实播放器默认头；保留真实ExoPlayer的READY、时长、跳转断言，捕获onPlayerError因果链。模拟器失败先看JUnit和对应UTP单用例logcat，清理阶段dumpsys/run-as输出不代表首因；诊断收集失败不能覆盖原始退出码，控制台末尾输出具体用例摘要。
- 默认相对路径按配置文件目录解析。默认配置位于 exe/config/server.json，不依赖进程工作目录。文档图片使用相对路径。
- 原件入库前必须校验大小和完整 SHA256；确认偏移只在文件刷新与数据库持久化后前移。保留提交中间状态和崩溃恢复。
- 资料库按天全选必须跟随后续游标分页，明确1000项边界，不能将“全选本页”冒称整天。图集候选用当前分页的in_album标记，避免全量读取大图集；客户端核对from/to与membership_album_id回执，防止旧服务忽略参数后误选其他日期。批量写按100项分批，成功项移出选择，失败报告已完成数并保留剩余项；永久删除仍单项校验原回收站记录。Web/APK共享控制器保持同步并通过DOM跨页回归。ZIP导出仅取活动资料库，认证后流式ZIP64、64KiB读取、不缓存完整视频或临时整包、名称去路径和重名编号；测试实际CRC/原件字节、权限与混入回收站时拒绝。仅网页变化时增量构建也要同步运行目录web。
- 跨页选择绑定账号、展示位置、目标、类型和搜索，分页保留但换条件清除；异步响应核对同一上下文。批量成功后的页码与按钮由最新游标决定，不恢复操作前缓存的disabled值；必须断言删空末页回到第一页时“上一页”禁用。移动预检只提供全部所选项目共同且可写的来源，客户端确认和失败重试使用冻结ID集合，跨页类型校验不能只看可见DOM。
- 上传采用有界流式读取，禁止 req.body / base64 承载整个大视频。所有文件长度/偏移保持64位，传到网页时使用十进制字符串。
- APK大批量选择保存全选/月/日及排除规则，原生每页100项处理，先验证所有显式排除句柄再写队列；异步扫描须有进度、取消和已提交数量，换身份/目标后不得继续入队。并发默认2、上限3，单一协调器持锁，领取与内存租用原子化，旧线程全部退出后才释放协调器锁；父RunControl取消所有分路，摘要/发送不能冒充READY，进度按任务隔离，重跑旧偏移不算新增速度。执行选图DOM、隔离5MiB并发续传和Android领取/取消/跨页回归；模拟桥和Java语法检查不等于手机上传验收。
- 手机删除、撤销设备和预览失败都不能删除服务器原件。测试只能终止测试自己启动且路径确认的进程。
- Web普通删除进入可恢复回收站，用独立asset_trash标记保留去重和分类关联。活动列表、封面、下载一致隐藏，手机TRASHED不得误当MISSING自动恢复；普通用户仅恢复自己删除，admin可全部。用户已明确要求Web彻底删除：仅admin、仅回收站、输入“彻底删除”确认并校验deleted_at；不提供隐式清空。先持久化PURGING再清原件/缓存/关联，失败保留可重试意图、重启幂等完成，禁止恢复/去重入库复活该记录；同一asset与缩略图工作线程加锁，校验路径边界/别名/共享路径，禁止递归删除。修改此链路运行recycle.py、smoke/folders及purge-ui.cjs，验证实际字节、占用、越界、重启和恢复竞争；不得用真实资料库做删除测试。
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
- FFmpeg获取区分网络中断与固定内容错误：PyPI元数据和文件下载共用有限重试（最多5次、2/4/8/16秒退避），只重试连接/超时/短响应及临时HTTP状态；404、证书错误、超限和SHA256不符不能靠跳过校验放行。每次用独立同目录临时文件，成功校验后原子替换，失败清理且保留原目标。用断线/截断响应和有限重试回归验证，不将本地校验称作云端下载已恢复。
- 备份文件夹是共享资料库的逻辑关联，不是客户端磁盘路径；删除/移动目录不能删除或搬动内容原件。默认目录兼容旧文件和旧客户端。队列入队时固定folder_id，改变偏好仅作用于新任务；幂等上传不可改目标。跨目录同内容复用原件但必须补目录关联，不能因全局READY跳过。手机队列升级保留ID、client、摘要、远端任务、64位确认偏移，目录须加入队列去重键；旧目录删除后默认回退需明确显示。修改此链路运行folders.py、smoke.py和Android队列SQL/迁移检查。
- 手机“仅选未备份/未入队”也必须按目标目录判定：其他目录QUEUED/HASHING/UPLOADING/VERIFYING/COMMITTING不能阻止新目录入队，当前目录仍去重。自动相册与系统文件选择逐项检查账号及目录变化，停止后保留已提交批次；入队摘要、每文件进度显示冻结的实际目录，诊断只记目录ID。新增原生回归仍须经CI，模拟桥和Java语法检查不代表真机成功。
- MediaStore测试夹具需分别支持集合列表与单项元数据查询：集合列表检查Bundle及SQL排序，单项URI要支持传统五参数query与Bundle重载，包含空/指定projection。MediaCatalog.describe会在真正入队前走五参数查询，不能用全局AssertionError拦截合法单项读取；修复时保留排除照片后的实际队列条数/URI断言，并分别回归元数据读取和集合旧查询拒绝。Java语法通过不等于ContentResolver重载路径已在Robolectric执行。
- Robolectric用例不能依赖Application重置或执行顺序来隔离生产静态单例。涉及TaskStore的隔离用例通过测试专用QueueFixture在@Before/@After关闭旧helper、清空单例并删除测试SQLite数据库；设置偏好也显式初始化。保留“异常操作不入队”的零条断言，在@Before先确认空库，不能把其他用例残留记录解释成生产入队错误，也不能通过调整方法顺序或放宽数量断言绕过。
- 原生协议或服务能力变更时，同时检查src/test与src/androidTest的模拟服务器：能力声明、目标目录查询、任务目录字段和来源目录成员状态必须符合当前接口。保持续传丢失确认后的偏移/字节断言及实际选图入队断言；夹具过旧应更新夹具，不能移除生产校验。收到APK Artifact仍需核实整体workflow及模拟器job状态，不能将APK构建成功当作整套发布通过。

- Web上传目标在每批入队时冻结；队列去重及client_task_id同时绑定目标，不能因全局READY阻止其他文件夹补关联。新目标仅影响新任务，重试保留旧目标并核对原始/有效folder回执。历史任务列表也应返回有界查询的目录名称与显式回退，不凭缺失字段猜默认。退出账号清空选择与扫描游标，隔离过期摘要/选择框结果；修改后运行web-upload-folders.cjs、folders.py、smoke.py及当前build产物的分页测试，并用真实浏览器上传合成文件核对目录和原件字节。

- 完整包APK/UI从EXE旁client-release按清单在同一runServer启动路径自动发布，只在取得实例锁后、开放HTTP前执行一次。校验大小/摘要、复制后复核和原子清单；不降级、不以同版不同内容覆盖，异常不阻止NAS。撤下版本水位先持久化且公共目录读取必须尊重，重启不能上架同版；管理员显式Web/离线发布可清除标记。正式ZIP验收首次必须直接启动，不可先运行导入脚本掩盖缺失；CI仍核验真实固定签名，同提交构建要求不因后台自动发布而取消。
