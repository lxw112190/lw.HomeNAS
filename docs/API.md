# API v1（0.5.0 备份文件夹）

同源网页使用 HttpOnly 会话 Cookie；原生客户端可使用 `Authorization: Bearer <会话令牌>`。统一采用用户名与密码登录，普通网页登录不在JSON中返回令牌；原生客户端在登录JSON中设置 `client:"native"` 才取得令牌。当前为共享家庭资料库，文件不按用户隔离，上传任务按账号归属。

角色：`admin` 管理账号及全部任务；`member` 浏览、下载及上传/管理自己的任务；`viewer` 仅浏览下载。所有写接口均在服务端检查权限。

| 请求 | 作用 |
| --- | --- |
| GET /api/v1/server | 公共服务名称、身份、版本与连接模式 |
| POST /api/v1/login | `{username,password,client?}` 账号登录 |
| GET /api/v1/me | 当前user_id、username、display_name、role、admin、can_upload |
| POST /api/v1/logout | 清除当前会话 |
| GET /api/v1/overview | 文件统计、容量、容量预留与连接信息 |
| GET /api/v1/users | 管理员列账号，不返回密码摘要 |
| POST /api/v1/users | 管理员 `{username,display_name,role,password}` 创建账号 |
| POST /api/v1/users/{id} | 管理员更新display_name、role、enabled、new_password（均可选） |
| POST /api/v1/password | 本人 `{old_password,new_password}`；该账号旧会话全部失效 |
| POST /api/v1/uploads | 幂等创建上传任务 |
| GET /api/v1/uploads/{id} | 任务状态与服务器确认偏移 |
| PUT /api/v1/uploads/{id}/data | 顺序二进制块上传 |
| POST /api/v1/uploads/{id}/complete | 完整校验排队，返回202；重复调用返回同一结果 |
| POST /api/v1/uploads/{id}/cancel | 取消尚未入库的UPLOADING任务，丢弃临时进度并释放容量预留；重复调用幂等 |
| GET /api/v1/tasks/page | `{items,next}`，每页50条，before游标，filter=all/active/failed/ready；保持账号任务隔离 |
| GET /api/v1/tasks | 最近100条任务；用户只看自己的任务，管理员看全部 |
| POST /api/v1/sources/status | `{task_ids:[上传任务id]}`，1至100个不重复48位小写十六进制ID；只核对当前账号自己的任务 |
| GET /api/v1/assets | `{items,next}`，limit默认60，范围1至100，Web使用48 |
| GET /api/v1/assets/{id}/original | 原件/单段Range，`?download=1`强制下载 |
| GET /api/v1/assets/{id}/metadata | 登录后读取文件信息及有限的图片尺寸/EXIF；解码不可用时 image.available=false，字段缺失不推断 |
| GET /api/v1/assets/{id}/thumbnail | JPEG缩略图；不可用时404 |
| POST /api/v1/assets/{id}/rename | `{name,old_name}`；更改共享资料库名称，保留扩展名 |
| GET /api/v1/certificate | 管理员下载公开服务器证书 |
| GET /api/v1/folders | 共享文件夹列表，48项分页，before游标；默认文件夹在第一页首项 |
| POST /api/v1/folders | `{name,description?}` 创建本人文件夹 |
| GET /api/v1/folders/{id} | 文件夹信息及can_write/can_edit/is_default |
| GET /api/v1/folders/{id}/items | 48项分页，before/type/q；普通文件同样支持 |
| POST /api/v1/folders/{id}/edit | `{name,description?}` 修改名称/说明 |
| POST /api/v1/folders/{id}/delete | 文件归入默认目录，原件保留 |
| POST /api/v1/folders/{id}/add | `{asset_ids:[...]}` 添加引用，不复制内容 |
| POST /api/v1/folders/{id}/remove | `{asset_ids:[...]}` 从此目录归入默认目录 |
| POST /api/v1/folders/{id}/move | `{asset_ids:[...],target_id}` 移动目录引用 |

JSON POST 需要 `Content-Type: application/json`，不带数据时发送 `{}`。状态改变操作拒绝非同源 Origin。未提供跨域网页 CORS 或通用原生桥。

用户名3至32位ASCII字母/数字/下划线/点/短横线，显示名称支持中文，密码至少6个字符。用户名创建后不修改；至少保留一个启用管理员。禁用、改角色和重置密码会撤销该账号全部会话。浏览器管理员会话有效期1天，其余账号7天。旧版 `/pairing`、`/pair`、`/devices` 接口已移除。

创建上传的例子：

```json
{
  "client_task_id": "客户端持久任务标识",
  "name": "我的照片.jpg",
  "size": "123456",
  "sha256": "64位小写十六进制摘要",
  "source_key": "客户端媒体来源标识",
  "album": "Camera",
  "modified": "1793750400000"
}
```

`size/offset/created/modified` 等64位字段以十进制字符串传输。上传块设置 `Content-Length`、`X-Upload-Offset`、`X-Chunk-SHA256`，Body为原始字节。浏览器自行生成 Content-Length，不要在JS中手工覆盖。

409表示需重新查询确认偏移或状态；429表示稍后重试；422表示校验失败；507表示容量或写盘问题。错误结构为 `{code,message}`。`UPLOADING → VERIFYING → COMMITTING → READY`；异常为ERROR，错误任务不等于备份成功。

暂停前台传输可以直接终止请求，确认块不会被撤销。取消任务会丢弃未入库的临时进度并释放容量预留；不能取消正在校验/入库或已经READY的文件。此预览版尚未实现会话自动过期，长期未完成项可在任务页手动取消。

`GET assets` 可使用 `type=image|video|file`、`q=文件名` 、`limit=1..100` 和上次返回的 `before=next` 游标。原件路径不对客户端开放。


0.3 新增 `assets?order=timeline|created`，缺省为 created 以保持旧客户端兼容。timeline 按 modified>0 时的文件修改时间，否则 created 倒序；同时间按 id 倒序，返回 order 和各项 timeline_time（十进制毫秒字符串）。游标必须配合相同排序、筛选和搜索使用，切换条件需丢弃旧游标。

`sources/status` 返回 `{items:[{id,state,size,modified,sha256,asset_id}]}`。仅返回当前账号上传任务，即使管理员也不返回别人的匹配项；不存在或不属于自己的 ID 被省略。READY 任务对应原件缺失/大小不符时返回 MISSING，不暴露路径或手机 URI。此接口核对现有本机历史任务，不按任意文件名推断已备份，也不取代最终完整 SHA256 校验。

0.3.1 视频封面与图片复用 thumbnail API，preview=READY 后返回 JPEG。封面失败不改变原件 READY；新增 MKV/AVI/3GP/MPEG/TS 类型，并修复旧视频的 octet-stream MIME。metadata 返回 size/created/modified 的十进制字符串，图片像素与方向为数值，EXIF 常见字段位于 image.exif，不含磁盘路径。

0.4.1：资料库列表和图集内容列表的文件新增布尔字段 `can_rename`，由当前账号权限决定。管理员可重命名所有 READY 文件；普通用户需要存在自己所属的 READY 上传任务指向该文件（内容去重后的多个上传者均可管理）；只读用户不可修改。图集管理权限不等于文件改名权限。

`POST assets/{id}/rename` 接收完整新名称 `name`（UTF-8 最多240字节）和打开编辑时的完整原名称 `old_name`。原名称不符返回409/NAME_CHANGED，避免覆盖其他人的修改；修改扩展名返回400/EXTENSION_CHANGED；无归属权限返回403/ASSET_OWNER_REQUIRED，只读返回403/READ_ONLY。禁止路径字符、控制字符、首尾空格、末尾句点及Windows保留名称。成功返回更新后的文件对象及 `can_rename`，不包含磁盘路径。

仅修改 `assets.name`：下载名称、按名称搜索和所有图集使用新名称；文件ID、内容路径、字节、摘要、MIME、大小与时间线时间不变。上传任务与手机来源名称保留，重选原文件仍按摘要去重，不会恢复旧名称。允许不同文件拥有相同的显示名称。

0.5：公共server信息新增 `backup_folders:true`。文件夹是逻辑关联，ID为 `default` 或48位小写十六进制。所有账号可浏览；管理员管理全部，member可写默认目录及自己创建的目录，viewer只读。默认目录不可改名或删除；其他目录名称按创建者去重。文件夹add/remove/move每次1至100个不重复文件ID，整批事务执行；从共享默认目录移出时，普通用户还必须有对应文件的已确认上传记录。文件夹管理权限不授予全局文件重命名权限。

上传创建新增可选 `folder_id`，省略使用default。服务端保存原始请求目标，同一个client_task_id不能改变目标。新入库和内容去重都添加目录关联，重复引用幂等；原件可同时属于多个目录。删除目录使用墓碑，现有引用归入默认目录，正在上传或未开始请求的旧目标仍能回退。任务返回 `folder_id`（原始目标）、`effective_folder_id`、`folder_name` 和布尔 `folder_defaulted`，回退时必须向用户说明。

`sources/status` 可附带 `folder_id`，每项增加 `in_folder`，同时核对原件存在和目录关联。不能仅凭全局READY跳过向另一个目录添加关联。默认未指定folder_id时保持旧响应兼容。旧客户端省略目录仍使用默认目录。升级会将现有READY原件补关联到默认目录，不移动内容文件；运行旧服务程序写入升级后的库会遗漏目录关联，不支持回退运行。
# v1.0新增：回收站与移动来源

- `POST /api/v1/assets/trash`、`POST /api/v1/assets/restore`：JSON为 `{"asset_ids":["48位asset id"]}`，每批1至100项，整批事务；返回count/restored。管理员管理全部，member仅操作自己有上传记录的原件，恢复另要求本人删除；viewer拒绝。
- `GET /api/v1/trash?before=&type=all&q=`：每页48项，返回items/next；按删除时间和id倒序，游标`deleted_at:id`。member仅列本人删除，管理员全部；不返回物理路径。
- `GET /api/v1/assets/{id}/folders`：返回此活动原件当前所属的可浏览文件夹及can_write。移动使用已有 `POST /api/v1/folders/{source_id}/move`，提交asset_ids和target_id；指定来源之外的关联保持。
- `POST /api/v1/sources/status`：回收站中的任务返回 `state:"TRASHED"`，区别于MISSING。原件保留、全局去重继续有效，客户端不应自动恢复或重新入队。
- `GET /api/v1/overview`新增trash.files/trash.bytes，表示保留的回收站记录和大小。library仅统计活动原件；磁盘available仍来自实际文件系统。

回收站记录保留原件及分类，普通列表、封面、原件/元数据读取隐藏。无永久清空/自动到期；恢复仍检查原件存在及大小。错误批次回滚，状态已改变返回409。现有所有认证、请求体限制和角色约束继续生效。
