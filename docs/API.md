# API v1（0.3 时间线与账号权限预览）

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
| GET /api/v1/assets/{id}/thumbnail | JPEG缩略图；不可用时404 |
| GET /api/v1/certificate | 管理员下载公开服务器证书 |

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
