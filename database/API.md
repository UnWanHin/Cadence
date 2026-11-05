# 校园智能运动平台 · HTTP 接口契约

> 本文件定义**服务端接口**，不是后端实现。数据库表结构见 `schema.sql`，字段口径见 `README.md`，机器可读定义见 `openapi.yaml`。
>
> 接口以 `docs/model-docs/canonical-rules.md` 为评分口径：端侧完成姿态判定与计数，提交模型摘要；服务端负责身份、规则版本、幂等结算、日上限、记录和排名。

## 1. 通用约定

### 1.1 基础信息

| 项目 | 约定 |
|---|---|
| Base URL | `/api/v1` |
| 传输 | HTTPS（生产）；JSON UTF-8 |
| 时间 | ISO 8601 UTC，毫秒精度，例如 `2026-11-04T12:00:00.000Z` |
| 业务日 | Asia/Shanghai 零点；服务端换算 `attribution_date` |
| 时长 | 非负整数毫秒 |
| 次数 | 非负整数；平板支撑为 0 |
| 质量分 | `0.0—1.0` 小数；前端展示时乘 100 |
| 积分 | 非负整数 |
| 分页 | `page` 从 1 开始；`pageSize` 默认 20，最大 100 |
| 认证 | `Authorization: Bearer <token>` |
| 角色 | `student` / `admin` |

### 1.2 统一响应

成功响应直接返回资源对象或分页对象：

```json
{
  "items": [],
  "page": 1,
  "pageSize": 20,
  "total": 0
}
```

失败响应：

```json
{
  "error": {
    "code": "SESSION_NOT_FOUND",
    "message": "会话不存在或无权访问",
    "details": {}
  }
}
```

### 1.3 常用错误码

| HTTP | code | 含义 |
|---:|---|---|
| 400 | `VALIDATION_ERROR` | 字段缺失/格式错误 |
| 401 | `UNAUTHENTICATED` | 未登录或 Token 失效 |
| 403 | `FORBIDDEN` | 角色或资源归属不允许 |
| 404 | `NOT_FOUND` | 资源不存在（他人资源也返回 404，避免泄露） |
| 409 | `CONFLICT` | 唯一键冲突、重复操作 |
| 410 | `SESSION_DELETED` | 会话已删除 |
| 422 | `INVALID_SESSION_SUMMARY` | 模型摘要与规则/时长不一致 |
| 422 | `SESSION_ALREADY_SETTLED` | 已结算，不能再次累计或修改 |
| 413 | `VIDEO_TOO_LARGE` | 本地视频上传超过上限 |
| 415 | `UNSUPPORTED_VIDEO_TYPE` | 不是可接收的视频类型 |
| 503 | `LOCAL_STORAGE_UNAVAILABLE` | 本地视频/元数据目录不可写 |
| 429 | `RATE_LIMITED` | 请求过频 |
| 500 | `INTERNAL_ERROR` | 服务端错误 |

### 1.4 认证与权限

- 注册、登录、健康检查不需要 Token；其余接口需要 Bearer Token。
- 第 8 节“本地视频库”在局域网模拟模式下不要求 Token：`owner` 只用于本机档案隔离，不构成真实权限边界；正式多用户部署时必须改为 Bearer Token + 资源归属校验。
- 学生只能读写自己的记录、反馈和排名开关。
- 管理员接口以 `/admin/` 开头，必须 `role='admin'`；管理员不能通过接口读取原始影像（系统不存）。
- Token 建议使用短期 JWT + 可撤销会话表；本契约不规定具体实现。

### 1.5 幂等

- `POST /sessions/{publicId}/finish` 以 `publicId` 为幂等键；重复提交返回第一次的结果，并带 `idempotentReplay: true`。
- 可选请求头 `Idempotency-Key` 用于网络重试；服务端可按用户 + key 缓存 24 小时。
- 结算同时受数据库 `uk_ledger_session_type` 唯一键保护。

## 2. 数据对象

### 2.1 User

```json
{
  "loginId": "20260001",
  "nickname": "同学A",
  "role": "student",
  "rankingOptIn": false,
  "status": "active",
  "createdAt": "2026-11-04T12:00:00.000Z"
}
```

不返回密码哈希、内部自增 ID、学号以外的敏感字段。

### 2.2 Exercise

```json
{
  "code": "squat",
  "name": "深蹲",
  "type": "reps",
  "description": "双脚与肩同宽，下蹲至膝角不超过90°……",
  "enabled": true,
  "rule": { "ruleId": 1, "version": 1, "mainMetric": "knee_angle", "targetDirection": "lte", "targetLow": 90, "targetHigh": null, "enterThreshold": 0.75, "exitThreshold": 0.55, "minVisibility": 0.5, "pointsPerRep": 10, "pointsPerSecond": 0, "caloriesPerRep": 0.6, "caloriesPerSecond": 0, "maxSessionDurationMs": 1200000, "dailyPointsCap": 600, "effectiveFrom": "2026-11-04T00:00:00.000Z" }
}
```

### 2.3 ModelSummary

与 `src/cadence/wasm_api.cpp` 的 `cadence_session_summary` 对齐：

```json
{
  "repetitions": 12,
  "points": 120,
  "calories": 7.2,
  "averageQuality": 0.92,
  "elapsedSeconds": 125
}
```

字段含义：

| 字段 | 类型 | 说明 |
|---|---|---|
| `repetitions` | integer | 计数型完成次数；保持型 0 |
| `points` | integer | 模型基础积分（日上限前） |
| `calories` | number | 模型卡路里 |
| `averageQuality` | number | 0—1 平均质量分 |
| `elapsedSeconds` | number | 有效运动秒数；应与 `validDurationMs/1000` 一致 |

### 2.4 SessionRecord

```json
{
  "publicId": "2b9e5d4a-5f4c-4f6e-9f3a-1d2c3b4a5e6f",
  "exerciseCode": "squat",
  "exerciseName": "深蹲",
  "exerciseType": "reps",
  "status": "settled",
  "startedAt": "2026-11-04T12:00:00.000Z",
  "endedAt": "2026-11-04T12:02:00.000Z",
  "validDurationMs": 125000,
  "naturalDurationMs": 180000,
  "repetitions": 12,
  "averageQuality": 0.92,
  "calories": 7.2,
  "modelPoints": 120,
  "basePoints": 120,
  "actualPoints": 120,
  "attributionDate": "2026-11-04",
  "ruleVersion": 1,
  "deletedAt": null,
  "idempotentReplay": false
}
```

未结算会话的 `basePoints/actualPoints/attributionDate` 可以为 `null`；只有 `settled` 后才有账本值。

## 3. 认证接口

### POST `/auth/register`

注册学生账号。服务端必须使用 bcrypt/argon2 生成哈希，禁止明文。

请求：

```json
{ "loginId": "20260001", "nickname": "同学A", "password": "至少8位密码" }
```

响应 `201`：

```json
{ "loginId": "20260001", "nickname": "同学A", "role": "student", "rankingOptIn": false, "status": "active", "createdAt": "2026-11-04T12:00:00.000Z" }
```

校验：`loginId` 1—64 字符且唯一；`nickname` 1—20 字符；密码长度与强度由服务端策略决定。

### POST `/auth/login`

请求：

```json
{ "loginId": "20260001", "password": "至少8位密码" }
```

响应 `200`：

```json
{ "token": "<opaque-or-jwt>", "expiresAt": "2026-11-04T20:00:00.000Z", "user": { "loginId": "20260001", "nickname": "同学A", "role": "student", "rankingOptIn": false, "status": "active" } }
```

失败统一返回 `401 UNAUTHENTICATED`，不区分“账号不存在”和“密码错误”。

### POST `/auth/logout`

需要认证。响应 `204`。服务端使当前 Token/会话失效。

### GET `/me`

需要认证。返回当前用户对象（2.1）。

### PATCH `/me`

需要认证。请求（字段可选，至少一个）：

```json
{ "nickname": "新昵称", "rankingOptIn": true }
```

响应 `200`：更新后的用户对象。昵称 1—20 字符；排名默认关闭。

## 4. 运动项目与规则

### GET `/exercises`

需要认证。默认只返回 `enabled=true` 的项目；管理员可加 `?includeDisabled=true`。

响应 `200`：

```json
{ "items": [ { "code": "squat", "name": "深蹲", "type": "reps", "description": "...", "enabled": true, "rule": { "...": "..." } } ] }
```

### GET `/exercises/{code}`

需要认证。返回单个项目及当前已发布规则。项目停用时返回 `200` 但 `enabled=false`，客户端不得开始新会话。

### POST `/admin/exercises/{code}/rules`

管理员。创建规则草稿（`status='draft'`），不覆盖历史版本。

请求：

```json
{
  "mainMetric": "knee_angle",
  "targetDirection": "lte",
  "targetLow": 90,
  "targetHigh": null,
  "enterThreshold": 0.75,
  "exitThreshold": 0.55,
  "minVisibility": 0.5,
  "pointsPerRep": 10,
  "pointsPerSecond": 0,
  "caloriesPerRep": 0.6,
  "caloriesPerSecond": 0,
  "maxSessionDurationMs": 1200000,
  "dailyPointsCap": 600,
  "jointDefinition": { "required_joints": ["..."] },
  "thresholdConfig": { "quality_formula": "..." }
}
```

响应 `201`：规则对象，`status='draft'`。校验：`enterThreshold > exitThreshold`；`minVisibility` 0—1；目标方向与边界一致；数值非负；同一时刻启用规则使用同一 `dailyPointsCap`。

### POST `/admin/rules/{ruleId}/publish`

管理员。发布草稿，写 `published_at`、`published_by`，并写 `audit_log(action='rule.publish')`。

请求：

```json
{ "effectiveFrom": "2026-11-05T00:00:00.000Z" }
```

响应 `200`：已发布规则对象。已发布版本不可覆盖；如需调整，创建新版本。

### POST `/admin/exercises/{code}/disable`

管理员。停用项目；不结束已开始会话。写审计。

响应 `200`：`{ "code": "squat", "enabled": false, "disabledAt": "..." }`。

## 5. 会话与结算

### POST `/sessions`

开始一次会话。服务端：

1. 校验动作已启用且有完整已发布规则；
2. 选择 `status='published' AND (effective_from IS NULL OR effective_from <= UTC_TIMESTAMP(3))` 中 `version_no` 最大者；
3. 生成 UUID `public_id`；
4. 插入 `training_session`，`status='preparing'`，`started_at=UTC_TIMESTAMP(3)`，锁定 `rule_id`。

请求：

```json
{ "exerciseCode": "squat" }
```

响应 `201`：

```json
{
  "publicId": "2b9e5d4a-5f4c-4f6e-9f3a-1d2c3b4a5e6f",
  "exercise": { "code": "squat", "name": "深蹲", "type": "reps" },
  "rule": { "ruleId": 1, "version": 1, "mainMetric": "knee_angle", "targetDirection": "lte", "targetLow": 90, "enterThreshold": 0.75, "exitThreshold": 0.55, "minVisibility": 0.5, "pointsPerRep": 10, "pointsPerSecond": 0, "maxSessionDurationMs": 1200000, "dailyPointsCap": 600 },
  "startedAt": "2026-11-04T12:00:00.000Z"
}
```

客户端必须在开始时保存 `publicId` 与规则版本；提交时只使用该 `publicId`，不允许替换用户或规则。

### PATCH `/sessions/{publicId}/state`

需要认证，会话拥有者。用于心跳/断网恢复/暂停状态同步（可选实现，但建议提供）。

请求：

```json
{
  "status": "timing",
  "validDurationMs": 65000,
  "naturalDurationMs": 90000,
  "repetitions": 6,
  "averageQuality": 0.91,
  "calories": 3.6,
  "modelPoints": 60,
  "anomalySummary": { "codes": [ { "code": "LOW_VISIBILITY", "ms": 1200 } ] }
}
```

允许的状态：`preparing`、`detecting`、`timing`、`auto_paused`、`user_paused`、`pending_submit`。`settled`/`abnormal_end` 不能通过本接口写入。

校验：`validDurationMs <= naturalDurationMs`；次数/积分/卡路里非负；`averageQuality` 0—1。已结算会话返回 `409 SESSION_ALREADY_SETTLED`。

响应 `200`：更新后的会话摘要。

### POST `/sessions/{publicId}/finish`

需要认证，会话拥有者。提交冻结摘要并结算。

请求：

```json
{
  "validDurationMs": 125000,
  "naturalDurationMs": 180000,
  "modelSummary": {
    "repetitions": 12,
    "points": 120,
    "calories": 7.2,
    "averageQuality": 0.92,
    "elapsedSeconds": 125
  },
  "anomalySummary": {
    "codes": [ { "code": "LOW_VISIBILITY", "ms": 1200 }, { "code": "MULTI_PERSON", "ms": 800 } ]
  }
}
```

服务端校验：

- 会话属于当前用户、未删除、未结算；
- `validDurationMs <= naturalDurationMs`；
- `abs(modelSummary.elapsedSeconds * 1000 - validDurationMs) <= 1000`；
- 计数型：`modelSummary.points >= 0` 且 `modelSummary.points <= repetitions * rule.pointsPerRep`；`calories >= 0` 且 `calories <= repetitions * rule.caloriesPerRep + 0.01`；
- 保持型：`repetitions = 0`；`modelSummary.points = floor(validDurationMs / 1000 * rule.pointsPerSecond)`；`calories ≈ validDurationMs / 1000 * rule.caloriesPerSecond`；
- `averageQuality` 0—1。

结算事务（与 `README.md` 第 1 节一致）：

1. `SELECT ... FOR UPDATE` 锁定会话与用户；
2. 若已有 `points_ledger` 的 `credit`，直接返回原结果；
3. 读 `v_user_daily_points` 得当日已入账；
4. `base = modelSummary.points`；`actual = min(base, max(0, dailyPointsCap - used))`；
5. 插入 `points_ledger(entry_type='credit', base_points, actual_points, attribution_date)`；
6. 更新 `training_session`：模型摘要字段、`status='settled'`、`submitted_at`、`settled_at`；
7. 返回结果。

响应 `200`：

```json
{
  "publicId": "2b9e5d4a-5f4c-4f6e-9f3a-1d2c3b4a5e6f",
  "status": "settled",
  "exerciseCode": "squat",
  "repetitions": 12,
  "validDurationMs": 125000,
  "naturalDurationMs": 180000,
  "averageQuality": 0.92,
  "calories": 7.2,
  "modelPoints": 120,
  "basePoints": 120,
  "actualPoints": 120,
  "dailyUsed": 120,
  "dailyCap": 600,
  "attributionDate": "2026-11-04",
  "ruleVersion": 1,
  "idempotentReplay": false
}
```

幂等重试：返回第一次的 `basePoints/actualPoints/attributionDate`，`idempotentReplay: true`。

校验失败：`422 INVALID_SESSION_SUMMARY`，会话置 `abnormal_end`，不写账本。已删除：`410 SESSION_DELETED`。非本人：`404 NOT_FOUND`。

### POST `/sessions/{publicId}/abort`

需要认证。主动放弃/无效结束。请求：

```json
{ "reason": "USER_CANCELLED" }
```

服务端置 `status='abnormal_end'`、`ended_at`、`submitted_at`，不写账本。响应 `200`：`{ "publicId": "...", "status": "abnormal_end" }`。

### GET `/sessions`

需要认证。查询本人记录，默认 `deleted_at IS NULL`，按 `started_at DESC` 排序。

查询参数：

| 参数 | 类型 | 说明 |
|---|---|---|
| `from` | ISO 8601 | 起始时间（含） |
| `to` | ISO 8601 | 结束时间（不含） |
| `exerciseCode` | string | 动作代码 |
| `page` | integer | 默认 1 |
| `pageSize` | integer | 默认 20，最大 100 |

响应 `200`：分页的 `SessionRecord`（不含 `deletedAt` 的记录）。

### GET `/sessions/{publicId}`

需要认证，仅本人。返回 `SessionRecord`，包含 `basePoints/actualPoints/ruleVersion`。

### DELETE `/sessions/{publicId}`

需要认证，仅本人。软删除并撤销积分（与 `README.md` 第 2 节一致）。

响应 `200`：

```json
{ "publicId": "...", "deletedAt": "2026-11-04T12:05:00.000Z", "revokedPoints": 120 }
```

重复删除返回 `200` 且 `revokedPoints=0`，不再次扣分。

## 6. 统计与排行榜

### GET `/stats/weekly`

需要认证。查询参数：`weekStart`（周一，默认本周）、`exerciseCode`（可选）。

响应 `200`：

```json
{
  "weekStart": "2026-11-02",
  "days": [
    { "date": "2026-11-04", "exerciseCode": "squat", "validDurationMs": 125000, "repetitions": 12, "averageQuality": 0.92, "calories": 7.2, "points": 120 }
  ],
  "totals": { "validDurationMs": 125000, "repetitions": 12, "points": 120, "calories": 7.2 }
}
```

无记录日期由服务层补 0；统计只算已结算、未删除、账本有效记录。

### GET `/leaderboard/weekly`

需要认证。查询参数：`weekStart`（周一，默认本周）、`page`、`pageSize`。

响应 `200`：

```json
{
  "weekStart": "2026-11-02",
  "items": [
    { "rank": 1, "nickname": "同学A", "points": 120 },
    { "rank": 1, "nickname": "同学B", "points": 120 },
    { "rank": 3, "nickname": "同学C", "points": 90 }
  ],
  "me": { "rank": 3, "nickname": "同学C", "points": 90 }
}
```

只输出昵称、积分、名次；不输出 `user_id`、学号或姓名。未加入排名的用户不出现在公开列表；`me` 可为 `null`。

### GET `/leaderboard/me`

需要认证。查询参数 `weekStart`。响应：

```json
{ "weekStart": "2026-11-02", "optedIn": true, "rank": 3, "nickname": "同学C", "points": 90 }
```

未加入时 `optedIn=false`、`rank=null`。

## 7. 反馈

### POST `/feedback`

需要认证。请求：

```json
{ "sessionPublicId": "2b9e5d4a-...", "category": "misjudge", "content": "深蹲计数偏少，可能遮挡" }
```

`sessionPublicId` 可为空；不能引用他人会话。`content` 1—1000 字。响应 `201`：反馈对象。

### GET `/feedback/me`

需要认证。分页返回本人反馈。

### GET `/admin/feedback`

管理员。查询参数 `status`、`page`、`pageSize`。返回反馈列表。

### PATCH `/admin/feedback/{id}`

管理员。请求：

```json
{ "status": "resolved", "handleNote": "已确认模型阈值并记录" }
```

写 `handled_by`、`handled_at`、`handle_note`，并写 `audit_log`。状态：`open/processing/resolved/rejected`。

## 8. 本地视频库（局域网模拟）

当前项目还没有建立中心数据库。视频相关接口采用**局域网本地优先**模式：视频文件保存到运行服务的主机本地目录，元数据保存到同目录的 JSON 文件；不把原始视频写入 MySQL，也不做云端同步。行为类似微信把聊天媒体缓存到本地设备：清理本地数据后不可恢复。

- 本地模式不需要 Bearer Token；`owner` 是本地档案名/设备键，只用于区分同一台主机上的不同使用者，不是身份认证。
- 中心数据库接好以后，视频仍可只保存元数据到 `local_video_asset` 表，二进制文件继续由本地存储适配器管理。
- 默认上传上限 200 MB；可通过本地服务的 `MAX_UPLOAD_BYTES` 环境变量调整。
- 本地服务默认监听 `0.0.0.0:8787`，同一 Wi-Fi 下的手机可访问 `http://<主机IP>:8787/`。
- 端点实现见 `database/local-server/server.mjs`，数据目录为 `database/local-server/data/`。

### 数据对象 VideoAsset

```json
{
  "videoId": "f4e3c2b1-a9d8-4c7b-8e6f-123456789abc",
  "owner": "同学A",
  "originalName": "squat-demo.mp4",
  "contentType": "video/mp4",
  "byteSize": 561602,
  "durationMs": 17167,
  "width": 504,
  "height": 900,
  "sha256": "9f2c...64位十六进制...",
  "status": "active",
  "createdAt": "2026-11-05T12:00:00.000Z",
  "contentUrl": "/api/v1/videos/f4e3c2b1-a9d8-4c7b-8e6f-123456789abc/content"
}
```

### POST `/videos`

本地上传视频，`multipart/form-data`：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `file` | binary | 是 | 视频文件；浏览器可解码的 mp4/webm/mov 等 |
| `owner` | string | 是 | 本地档案名/设备键，1—64 字符 |
| `durationMs` | integer | 否 | 客户端读取到的视频时长，毫秒 |
| `width` / `height` | integer | 否 | 视频像素尺寸 |

响应 `201`：`VideoAsset`。服务端计算 SHA-256，把文件写入本机 `data/videos/`，元数据写入 `data/store.json`。

错误：

| HTTP | code | 含义 |
|---:|---|---|
| 400 | `VALIDATION_ERROR` | 缺少文件/owner 或字段非法 |
| 413 | `VIDEO_TOO_LARGE` | 超过本地上传上限 |
| 415 | `UNSUPPORTED_VIDEO_TYPE` | 不是视频类型 |
| 503 | `LOCAL_STORAGE_UNAVAILABLE` | 本地目录无法写入 |

### GET `/videos`

查询本机视频库。参数：

| 参数 | 类型 | 说明 |
|---|---|---|
| `owner` | string | 按本地档案过滤；为空时返回本机全部 |
| `page` | integer | 默认 1 |
| `pageSize` | integer | 默认 20，最大 100 |

响应 `200`：

```json
{ "items": [ { "videoId": "...", "owner": "同学A", "originalName": "...", "durationMs": 17167, "byteSize": 561602, "contentUrl": "/api/v1/videos/.../content" } ], "page": 1, "pageSize": 20, "total": 1 }
```

### GET `/videos/{videoId}`

返回单个 `VideoAsset` 元数据；`404 NOT_FOUND` 表示不存在或已删除。

### GET `/videos/{videoId}/content`

返回视频二进制，支持 `Range` 请求（`206 Partial Content`）。客户端可直接用于 `<video>` 回放，也可以下载后交给 MediaPipe 分析。

### DELETE `/videos/{videoId}`

删除本地视频文件和元数据，响应 `204`。重复删除返回 `204`，不报错。该操作不可恢复，和微信清理本地媒体一致。

---

## 9. 审计

### GET `/admin/audit`

管理员。查询参数 `targetType`、`targetId`、`actorLoginId`、`from`、`to`、`page`、`pageSize`。

响应分页审计事件：

```json
{
  "items": [
    { "action": "rule.publish", "targetType": "rule", "targetId": "12", "result": "success", "actorLoginId": "admin", "createdAt": "2026-11-04T12:00:00.000Z" }
  ]
}
```

审计只返回去标识字段，不返回密码、Token、影像或被删除运动详情。

## 10. 会话状态迁移

| 当前状态 | 事件 | 下一状态 | 服务端动作 |
|---|---|---|---|
| `preparing` | 客户端开始检测 | `detecting` | 更新状态 |
| `detecting` | 检测到有效姿态 | `timing` | 更新状态 |
| `timing` | 不达标/遮挡/页面隐藏 | `auto_paused` | 保留累计 |
| `timing` / `detecting` | 用户暂停 | `user_paused` | 保留累计 |
| 任一未结束 | 结束/断网/达到上限 | `pending_submit` | 冻结摘要 |
| `pending_submit` | 结算成功 | `settled` | 写账本 |
| `pending_submit` | 校验失败 | `abnormal_end` | 不写账本 |
| 任一未结束 | 主动放弃 | `abnormal_end` | 不写账本 |

客户端可以在本地维护更细的 UI 状态，但服务端只接受上表状态。

## 11. 与数据库的映射

| 接口字段 | 数据库字段 |
|---|---|
| `publicId` | `training_session.public_id` |
| `exerciseCode` | `exercise.code` |
| `exerciseType` | `exercise.exercise_type` |
| `ruleVersion` | `exercise_rule.version_no` |
| `validDurationMs` | `training_session.valid_duration_ms` |
| `naturalDurationMs` | `training_session.natural_duration_ms` |
| `repetitions` | `training_session.repetitions` |
| `averageQuality` | `training_session.average_quality` |
| `calories` | `training_session.calories` |
| `modelPoints` | `training_session.model_points` |
| `basePoints` | `points_ledger.base_points` |
| `actualPoints` | `points_ledger.actual_points` |
| `attributionDate` | `points_ledger.attribution_date` |
| `rankingOptIn` | `sys_user.ranking_opt_in` |
| `videoId` | `local_video_asset.video_id` |
| `video.owner` | `local_video_asset.owner_key` |
| `video.byteSize` | `local_video_asset.byte_size` |
| `video.storageKey` | `local_video_asset.storage_key` |

## 12. 服务层数据访问接口（Repository）

如果后端不直接写 SQL，建议按下面的 Repository 接口隔离数据库；接口实现负责 SQL 与事务，业务服务只依赖方法。类型仅为契约示意，语言可用 TypeScript/Java/C# 等实现。

```ts
interface UserRepository {
  create(input: { loginId: string; nickname: string; passwordHash: string }): Promise<User>;
  findByLoginId(loginId: string): Promise<User | null>;
  findById(userId: bigint): Promise<User | null>;
  updateProfile(userId: bigint, patch: { nickname?: string; rankingOptIn?: boolean }): Promise<User>;
  lockById(tx: Transaction, userId: bigint): Promise<User>; // SELECT ... FOR UPDATE
}

interface ExerciseRepository {
  list(includeDisabled: boolean): Promise<Exercise[]>;
  findByCode(code: string): Promise<Exercise | null>;
  findLatestPublishedRule(exerciseId: bigint, at: Date): Promise<Rule | null>;
  createRuleDraft(exerciseId: bigint, input: RuleDraftRequest): Promise<Rule>;
  publishRule(ruleId: bigint, actorUserId: bigint, effectiveFrom?: Date): Promise<Rule>;
  disableExercise(code: string, actorUserId: bigint): Promise<void>;
}

interface SessionRepository {
  create(input: {
    publicId: string; userId: bigint; exerciseId: bigint; ruleId: bigint; startedAt: Date;
  }): Promise<Session>;
  findByPublicIdForUser(
    tx: Transaction, publicId: string, userId: bigint, forUpdate: boolean,
  ): Promise<Session | null>;
  updateState(sessionId: bigint, patch: SessionStatePatch): Promise<Session>;
  markSettled(tx: Transaction, sessionId: bigint, summary: ModelSummary, at: Date): Promise<void>;
  markAbnormal(tx: Transaction, sessionId: bigint, reason: string, at: Date): Promise<void>;
  softDelete(tx: Transaction, sessionId: bigint, at: Date): Promise<void>;
  listByUser(userId: bigint, filters: SessionFilters, page: Page): Promise<Page<Session>>;
  findDetailForUser(publicId: string, userId: bigint): Promise<SessionDetail | null>;
}

interface LedgerRepository {
  findCreditBySession(tx: Transaction, sessionId: bigint): Promise<LedgerEntry | null>;
  dailyUsed(userId: bigint, attributionDate: string): Promise<number>; // v_user_daily_points
  insertCredit(tx: Transaction, input: {
    sessionId: bigint; userId: bigint; basePoints: number; actualPoints: number; attributionDate: string;
  }): Promise<LedgerEntry>;
  revoke(tx: Transaction, sessionId: bigint, userId: bigint): Promise<{ revokedPoints: number }>;
  weeklyStats(userId: bigint, weekStart: string, exerciseCode?: string): Promise<WeeklyStats>;
}

interface LeaderboardRepository {
  weekly(weekStart: string, page: Page): Promise<Page<LeaderboardEntry>>;
  myRank(userId: bigint, weekStart: string): Promise<MyRank>;
}

interface FeedbackRepository {
  create(input: { userId: bigint; sessionId?: bigint; category: string; content: string }): Promise<Feedback>;
  listByUser(userId: bigint, page: Page): Promise<Page<Feedback>>;
  listForAdmin(filters: { status?: FeedbackStatus }, page: Page): Promise<Page<Feedback>>;
  handle(tx: Transaction, id: bigint, actorUserId: bigint, input: { status: FeedbackStatus; handleNote?: string }): Promise<Feedback>;
}

interface AuditRepository {
  append(tx: Transaction, event: AuditEventInput): Promise<void>;
  list(filters: AuditFilters, page: Page): Promise<Page<AuditEvent>>;
}

interface VideoAssetRepository {
  // 局域网/端侧模式：元数据存本地 JSON，二进制文件存本地目录。
  create(input: {
    owner: string; originalName: string; contentType: string; byteSize: number;
    durationMs?: number; width?: number; height?: number; sha256: string; storageKey: string;
  }): Promise<VideoAsset>;
  listByOwner(owner: string, page: Page): Promise<Page<VideoAsset>>;
  findById(videoId: string): Promise<VideoAsset | null>;
  readContent(videoId: string): Promise<{ path: string; mimeType: string } | null>;
  delete(videoId: string): Promise<boolean>;
}
```

事务边界：

- `SessionService.finish(publicId, userId, request)`：`SessionRepository.findByPublicIdForUser(tx, ..., true)` → `LedgerRepository.findCreditBySession(tx, ...)` → `UserRepository.lockById(tx, userId)` → `LedgerRepository.dailyUsed` → `LedgerRepository.insertCredit` → `SessionRepository.markSettled` → commit。
- `SessionService.delete(publicId, userId)`：锁会话与用户 → `LedgerRepository.revoke` → `SessionRepository.softDelete` → commit。
- `RuleService.publish`：锁规则行 → 校验草稿与参数 → 更新 `status/published_at/published_by` → `AuditRepository.append` → commit。
- 查询接口不开启写事务；排行榜直接读 `v_weekly_leaderboard`。

## 13. 非目标

- 中心数据库不接收原始视频或逐帧关键点；模型在端侧运行。
- 局域网本地视频库只把原始视频保存在运行主机本地目录；中心数据库只保存元数据。清理本地目录会删除视频，不能从中心库恢复。
- 本地模式的 `owner` 不是登录身份，不能作为正式权限边界；正式部署前必须接入账号、Token 与资源归属校验。
- 不提供正式体育成绩、医疗建议或跨校身份认证。
- 不保证端侧结果不可伪造；首版定位为课程演示与自愿激励。正式防作弊需另立身份、活体、审计和风控需求。
