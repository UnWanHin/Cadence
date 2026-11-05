# 局域网本地模拟服务

这个服务用于在**没有 MySQL** 的阶段模拟数据库与视频文件服务。它只使用 Node.js 内置模块，不需要 `npm install`。

## 启动

```powershell
node database/local-server/server.mjs
```

默认监听 `0.0.0.0:8787`。启动后终端会打印：

```text
本机页面:   http://127.0.0.1:8787/
局域网页面: http://192.168.x.x:8787/
接口前缀:   /api/v1
数据目录:   .../database/local-server/data
```

手机与电脑连同一个 Wi-Fi 后，打开终端打印的“局域网页面”地址即可。Windows 首次运行可能询问是否允许 Node.js 通过防火墙，请选择允许专用网络。

## 数据保存位置

- `data/store.json`：视频元数据（JSON）；
- `data/videos/`：原始视频文件；
- `data/` 已加入 `.gitignore`，不会提交到仓库。

清理本地数据：

```powershell
Remove-Item -Recurse -Force database/local-server/data
```

这会删除本机视频与元数据，不能从中心库恢复，行为类似清理微信本地媒体缓存。

## 已实现端点

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/v1/health` | 健康检查 |
| POST | `/api/v1/videos` | 上传视频（multipart/form-data） |
| GET | `/api/v1/videos?owner=...` | 本机视频库列表 |
| GET | `/api/v1/videos/{videoId}` | 视频元数据 |
| GET | `/api/v1/videos/{videoId}/content` | 视频内容，支持 Range |
| DELETE | `/api/v1/videos/{videoId}` | 删除本地视频 |

完整契约见 `database/API.md` 第 8 节和 `database/openapi.yaml`。

## 环境变量

| 变量 | 默认值 | 说明 |
|---|---|---|
| `PORT` | `8787` | 监听端口 |
| `HOST` | `0.0.0.0` | 监听地址 |
| `MAX_UPLOAD_BYTES` | `209715200` | 单次上传上限（200 MB） |

## 说明

- `owner` 只是本地档案名/设备键，不是登录身份，不能作为正式权限边界。
- 中心数据库接入后，视频元数据可迁移到 `local_video_asset`，二进制仍可保存在本地或对象存储。
- 本服务同时托管 `ui/` 静态页面，因此页面和接口同源，不需要额外配置 CORS。
